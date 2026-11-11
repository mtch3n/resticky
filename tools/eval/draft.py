# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
import argparse
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

RATE = 16000
CHUNK_MS = 30000
FRAME_MS = 20
SAMPLES_PER_FRAME = 960
PRE_SKIP = 312
SERIAL = 0x52535459
SILENT_TOC = 0x48
DEFAULT_MODEL = "/tmp/resticky-models/ggml-large-v3-turbo.bin"
MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin"
AUDIO = {".wav", ".ogg", ".opus", ".mp3", ".m4a", ".flac", ".webm"}


def _crc_table():
    table = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) if r & 0x80000000 else (r << 1)
            r &= 0xFFFFFFFF
        table.append(r)
    return table


CRC = _crc_table()


def ogg_crc(data: bytes) -> int:
    crc = 0
    for b in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ CRC[((crc >> 24) ^ b) & 0xFF]
    return crc


def page(flags: int, granule: int, seq: int, payload: bytes) -> bytes:
    lacing = [255] * (len(payload) // 255) + [len(payload) % 255]
    head = b"OggS" + struct.pack("<BBqIII", 0, flags, granule, SERIAL, seq, 0) + bytes([len(lacing)]) + bytes(lacing)
    out = bytearray(head + payload)
    out[22:26] = struct.pack("<I", ogg_crc(bytes(out)))
    return bytes(out)


def split_frames(data: bytes) -> list[bytes]:
    frames, i = [], 0
    while i + 2 <= len(data):
        n = data[i] | (data[i + 1] << 8)
        i += 2
        if i + n > len(data):
            break
        frames.append(data[i : i + n])
        i += n
    return frames


def frames_to_ogg(data: bytes) -> bytes:
    head = b"OpusHead" + struct.pack("<BBHIhB", 1, 1, PRE_SKIP, RATE, 0, 0)
    vendor = b"resticky"
    tags = b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)
    pages = [(2, 0, head), (0, 0, tags)]
    granule, toc = 0, SILENT_TOC
    for f in split_frames(data):
        granule += SAMPLES_PER_FRAME
        if f:
            toc = f[0] & 0xFC
        pages.append((0, granule, f if f else bytes([toc])))
    flags, g, payload = pages.pop()
    pages.append((flags | 4, max(g, granule), payload))
    return b"".join(page(f, g, i, p) for i, (f, g, p) in enumerate(pages))


def session_frames(folder: Path) -> bytes:
    meta = folder / "meta.json"
    chunk_ms = json.loads(meta.read_text()).get("chunk_ms", {}) if meta.exists() else {}
    seqs = sorted(int(p.stem) for p in folder.glob("*.opus") if p.stem.isdigit())
    if not seqs:
        sys.exit(f"no <seq>.opus files in {folder}")
    out = bytearray()
    for seq in range(seqs[-1] + 1):
        f = folder / f"{seq}.opus"
        out += f.read_bytes() if f.exists() else bytes(chunk_ms.get(str(seq), CHUNK_MS) // FRAME_MS * 2)
    return bytes(out)


def to_wav(src: Path, wav: Path):
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-ar", str(RATE), "-ac", "1", "-c:a", "pcm_s16le", str(wav)], check=True)


def clock(ms: int) -> str:
    s, ms = divmod(int(ms), 1000)
    return f"{s // 3600:02d}:{s // 60 % 60:02d}:{s % 60:02d}.{ms:03d}"


def draft(src: Path, out: Path, model: Path, language: str, threads: int):
    name = src.name if src.is_dir() else src.stem
    wav = out / f"{name}.wav"
    if src.is_dir():
        with tempfile.NamedTemporaryFile(suffix=".ogg") as ogg:
            ogg.write(frames_to_ogg(session_frames(src)))
            ogg.flush()
            to_wav(Path(ogg.name), wav)
    else:
        to_wav(src, wav)
    base = out / f"{name}.draft"
    subprocess.run(
        ["whisper-cli", "-m", str(model), "-f", str(wav), "-l", language, "-t", str(threads), "-np", "-ojf", "-of", str(base)],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    result = json.loads(Path(f"{base}.json").read_text())
    lines = [
        f"# audio: {wav}",
        f"# draft: whisper-cli {model.name} language={result.get('result', {}).get('language', language)}",
        "# Line format: START END SPEAKER | TEXT. Fix the text, set SPEAKER (S1, S2, ...), split or join lines.",
        "# A span with only noise or music: START END - | <noise>",
        "# A to-do that the meeting asks for: # todo: Send the slides to Anna",
    ]
    for s in result["transcription"]:
        text = s["text"].strip()
        if text:
            lines.append(f"{clock(s['offsets']['from'])} {clock(s['offsets']['to'])} S? | {text}")
    ref = out / f"{name}.ref.txt"
    ref.write_text("\n".join(lines) + "\n")
    print(ref)


def main():
    p = argparse.ArgumentParser(description="Make a draft reference transcript with a large Whisper model.")
    p.add_argument("inputs", nargs="+", type=Path, help="audio files or hub session folders with <seq>.opus chunks")
    p.add_argument("--model", type=Path, default=Path(DEFAULT_MODEL))
    p.add_argument("--language", default="auto", help="en, zh, yue or auto")
    p.add_argument("--out", type=Path, default=Path("."))
    p.add_argument("--threads", type=int, default=8)
    a = p.parse_args()
    if not a.model.exists():
        sys.exit(f"model missing: {a.model}\ndownload it with:\n  mkdir -p {a.model.parent} && curl -L -o {a.model} {MODEL_URL}")
    a.out.mkdir(parents=True, exist_ok=True)
    for src in a.inputs:
        if not src.is_dir() and src.suffix.lower() not in AUDIO:
            sys.exit(f"not an audio file or folder: {src}")
        draft(src, a.out, a.model, a.language, a.threads)


if __name__ == "__main__":
    main()
