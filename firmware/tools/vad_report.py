#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12"
# ///
import argparse
import base64
import json
import re
import subprocess
import tempfile
from pathlib import Path

FRAME_MS = 20
CLIP_WINDOW_MS = 5000
NOISE = re.compile(r"\([^)]*\)|\[[^\]]*\]|\*[^*]*\*")
WORD = re.compile(r"[A-Za-z0-9']+")


def words(text):
    return WORD.findall(NOISE.sub(" ", text))


def bits(b64, frames):
    raw = base64.b64decode(b64)
    return [bool(raw[i >> 3] >> (i & 7) & 1) if i >> 3 < len(raw) else False for i in range(frames)]


def frame_bytes(path):
    data = path.read_bytes()
    out, i = [], 0
    while i + 2 <= len(data):
        n = data[i] | data[i + 1] << 8
        out.append(2 + n)
        i += 2 + n
    return out


def timed_words(ogg, model):
    with tempfile.TemporaryDirectory() as tmp:
        wav = Path(tmp, "a.wav")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(ogg), "-ar", "16000", "-ac", "1", str(wav)], check=True)
        subprocess.run(["whisper-cli", "-m", model, "-f", str(wav), "-l", "en", "-np", "-t", "8", "-ml", "1", "-sow", "-oj",
                        "-of", str(Path(tmp, "w"))], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        segs = json.loads(Path(tmp, "w.json").read_text())["transcription"]
    out, depth = [], 0
    for s in segs:
        text = []
        for ch in s["text"]:
            if ch in "[(":
                depth += 1
            elif ch in "])":
                depth = max(0, depth - 1)
            elif not depth:
                text.append(ch)
        out += [(w, s["offsets"]["from"], s["offsets"]["to"]) for w in WORD.findall("".join(text))]
    return out


def chunk_rows(session_dir, meta):
    vad = meta.get("vad", {})
    seqs = sorted(int(s) for s in vad)
    rows = []
    for seq in seqs:
        v = vad[str(seq)]
        opus = session_dir / f"{seq}.opus"
        sizes = frame_bytes(opus) if opus.exists() else []
        frames = len(sizes) or len(base64.b64decode(v["speech"])) * 8
        rows.append({"seq": seq, "frames": frames, "sizes": sizes, "speech": bits(v["speech"], frames),
                     "keep": bits(v["keep"], frames), "keep_prev": int(v.get("keep_prev", 0)),
                     "text": meta["chunks"].get(str(seq), ""), "ogg": session_dir / f"{seq}.ogg"})
    by_seq = {r["seq"]: r for r in rows}
    for r in rows:
        prev = by_seq.get(r["seq"] - 1)
        if prev and r["keep_prev"]:
            for i in range(max(0, prev["frames"] - r["keep_prev"]), prev["frames"]):
                prev["keep"][i] = True
    return rows


def openings(keep):
    return [i for i in range(len(keep)) if keep[i] and (i == 0 or not keep[i - 1])]


def main():
    ap = argparse.ArgumentParser(description="Shadow-mode VAD report from the hub data folder (read-only).")
    ap.add_argument("--data", default=str(Path.home() / ".local/share/resticky/data"))
    ap.add_argument("--model", default=str(Path.home() / ".local/share/resticky/models/ggml-base.en.bin"))
    ap.add_argument("--device")
    ap.add_argument("--session")
    args = ap.parse_args()

    total = speech = kept = 0
    bytes_all = bytes_kept = 0
    words_all = words_checked = words_missed = 0
    clipped = no_audio = sessions = 0
    misses = []
    for meta_path in sorted(Path(args.data).glob("*/*/meta.json")):
        meta = json.loads(meta_path.read_text())
        if args.device and meta["device"] != args.device or args.session and meta["id"] != args.session:
            continue
        rows = chunk_rows(meta_path.parent, meta)
        if not rows:
            continue
        sessions += 1
        for r in rows:
            n, keep = r["frames"], r["keep"]
            total += n
            speech += sum(r["speech"])
            kept += sum(keep)
            bytes_all += sum(r["sizes"])
            bytes_kept += sum(b for b, k in zip(r["sizes"], keep) if k)
            stored = words(r["text"]) if not r["text"].startswith("\u0000") else []
            words_all += len(stored)
            if not stored or all(keep):
                continue
            if not r["ogg"].exists():
                no_audio += 1
                continue
            timed = timed_words(r["ogg"], args.model)
            words_checked += len(timed)
            dropped = [(w, a, b) for w, a, b in timed if not keep[min(n - 1, (a + b) // 2 // FRAME_MS)]]
            words_missed += len(dropped)
            for w, a, b in dropped:
                misses.append(f"{meta['id']}/{r['seq']} {a / 1000:6.1f}s {w}")
            for o in openings(keep):
                t = o * FRAME_MS
                if any(t - CLIP_WINDOW_MS <= b <= t for _, _, b in dropped):
                    clipped += 1

    if not total:
        print("No chunks with VAD data (meta.json has no \"vad\" field).")
        return
    hours = total * FRAME_MS / 3_600_000
    print(f"sessions {sessions}, audio {hours:.2f} h ({total} frames)")
    print(f"speech   {100 * speech / total:5.1f}% of the time")
    print(f"kept     {100 * kept / total:5.1f}% of the time (2 s pre-roll, 15 s hang-over)")
    print(f"words    {words_all} in the stored transcripts")
    print(f"missed   {words_missed} words in dropped audio, of {words_checked} words that whisper found again in the "
          f"chunks with dropped frames")
    print(f"clipped  {clipped} gate openings with a missed word in the {CLIP_WINDOW_MS // 1000} s before them")
    if no_audio:
        print(f"skipped  {no_audio} chunks with words and dropped frames but no .ogg file")
    if bytes_all:
        per_day = bytes_all / hours * 24 / 1e6
        print(f"bytes    {bytes_all / 1e6:.2f} MB recorded, {bytes_kept / 1e6:.2f} MB kept: flash and radio save "
              f"{100 * (1 - bytes_kept / bytes_all):.1f}% ({per_day:.0f} -> {per_day * bytes_kept / bytes_all:.0f} MB per 24 h)")
    for m in misses:
        print("  miss", m)


if __name__ == "__main__":
    main()
