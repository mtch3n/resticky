# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["meeteval==0.4.3", "opencc==1.1.9", "numpy", "scipy"]
# ///
import argparse
import difflib
import json
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import opencc
from meeteval.wer import cp_word_error_rate, siso_word_error_rate
from scipy.optimize import linear_sum_assignment

CHUNK_MS = 30000
FRAME_MS = 10
TODO_MATCH = 0.5
UNKNOWN = {"S?", "?"}
NOISE = "<noise>"
PENDING = "\u0000pending"
LINE = re.compile(r"^(\S+)\s+(\S+)\s+(\S+)\s*\|\s?(.*)$")
TAGS = re.compile(r"<\|[^|]*\|>|\[[^\]]*\]|\([^)]*\)|-----")
T2S = opencc.OpenCC("t2s")


def is_cjk(c: str) -> bool:
    return "㐀" <= c <= "鿿" or "豈" <= c <= "﫿"


def tokens(text: str) -> list[str]:
    text = T2S.convert(unicodedata.normalize("NFKC", TAGS.sub(" ", text))).lower()
    text = "".join(" " if unicodedata.category(c)[0] in "PS" and c != "'" else c for c in text)
    out = []
    for word in text.split():
        run = ""
        for c in word:
            if is_cjk(c):
                if run.strip("'"):
                    out.append(run.strip("'"))
                run = ""
                out.append(c)
            else:
                run += c
        if run.strip("'"):
            out.append(run.strip("'"))
    return out


def ms(stamp: str) -> int:
    parts = [float(p) for p in stamp.split(":")]
    s = 0.0
    for p in parts:
        s = s * 60 + p
    return round(s * 1000)


@dataclass
class Line:
    start: int
    end: int
    speaker: str
    text: str
    words: list[tuple[int, int, str]] = field(default_factory=list)


@dataclass
class Session:
    name: str
    lines: list[Line]
    noise: list[tuple[int, int]]
    todos: list[str]


def read_reference(path: Path) -> Session:
    lines, noise, todos = [], [], []
    for raw in path.read_text().splitlines():
        raw = raw.strip()
        if raw.lower().startswith("# todo:"):
            todos.append(raw.split(":", 1)[1].strip())
            continue
        if not raw or raw.startswith("#"):
            continue
        m = LINE.match(raw)
        if not m:
            raise SystemExit(f"{path}: bad line: {raw}")
        start, end, speaker, text = ms(m[1]), ms(m[2]), m[3], m[4].strip()
        if text == NOISE:
            noise.append((start, end))
        else:
            lines.append(Line(start, end, speaker, text))
    return Session(path.name, lines, noise, todos)


def read_hub(path: Path) -> Session:
    meta = json.loads(path.read_text())
    chunks, chunk_ms, segments = meta["chunks"], meta.get("chunk_ms", {}), meta.get("segments", {})
    names = meta.get("speakers", {})
    lines, at = [], 0
    for seq in range(max((int(k) for k in chunks), default=-1) + 1):
        key = str(seq)
        length = chunk_ms.get(key, CHUNK_MS)
        for s in segments.get(key, []):
            speaker = s.get("speaker", -1)
            label = names.get(str(speaker), f"Speaker {speaker}" if speaker >= 0 else "?")
            words = [(at + w[0], at + w[1], w[2]) for w in s.get("words", [])]
            lines.append(Line(at + s["start_ms"], at + s["end_ms"], label, s["text"], words))
        if key in chunks and not segments.get(key) and chunks[key] not in ("", PENDING):
            lines.append(Line(at, at + length, "?", chunks[key]))
        at += length
    return Session(path.parent.name, lines, [], [t if isinstance(t, str) else t.get("task", "") for t in meta.get("todos", [])])


def text_of(lines: list[Line]) -> list[str]:
    return [t for line in sorted(lines, key=lambda x: x.start) for t in tokens(line.text)]


def rate(ref: list[str], hyp: list[str]):
    if not ref and not hyp:
        return 0, 0
    r = siso_word_error_rate(" ".join(ref), " ".join(hyp))
    return r.errors, r.length


def hallucinated(hub: Session, noise: list[tuple[int, int]]) -> int:
    count = 0
    for line in hub.lines:
        items = [(w[0], w[1], w[2]) for w in line.words] or [(line.start, line.end, line.text)]
        for start, end, text in items:
            mid = (start + end) / 2
            if any(a <= mid < b for a, b in noise):
                count += len(tokens(text))
    return count


def activity(lines: list[Line], length: int) -> tuple[list[str], np.ndarray]:
    labels = sorted({line.speaker for line in lines})
    grid = np.zeros((len(labels), length), dtype=bool)
    for line in lines:
        if not tokens(line.text):
            continue
        grid[labels.index(line.speaker), line.start // FRAME_MS : max(line.start // FRAME_MS + 1, line.end // FRAME_MS)] = True
    return labels, grid


def der(ref: Session, hub: Session):
    length = max([line.end for line in ref.lines + hub.lines] + [0]) // FRAME_MS + 1
    _, r = activity(ref.lines, length)
    _, h = activity(hub.lines, length)
    nref, nhyp = r.sum(0), h.sum(0)
    overlap = r.astype(np.int64) @ h.T.astype(np.int64)
    rows, cols = linear_sum_assignment(-overlap) if overlap.size else ([], [])
    correct = sum(overlap[i, j] for i, j in zip(rows, cols))
    miss = np.maximum(nref - nhyp, 0).sum()
    false_alarm = np.maximum(nhyp - nref, 0).sum()
    confusion = np.minimum(nref, nhyp).sum() - correct
    return int(miss + false_alarm + confusion), int(nref.sum())


def cpwer(ref: Session, hub: Session):
    def by_speaker(lines):
        out = {}
        for line in sorted(lines, key=lambda x: x.start):
            out.setdefault(line.speaker, []).extend(tokens(line.text))
        return {k: " ".join(v) for k, v in out.items()}

    r = cp_word_error_rate(by_speaker(ref.lines), by_speaker(hub.lines))
    return r.errors, r.length


def todo_match(ref: list[str], hub: list[str]) -> int:
    left = [" ".join(tokens(t)) for t in hub]
    matched = 0
    for want in (" ".join(tokens(t)) for t in ref):
        scores = [difflib.SequenceMatcher(None, want, got).ratio() for got in left]
        if scores and max(scores) >= TODO_MATCH:
            left.pop(scores.index(max(scores)))
            matched += 1
    return matched


def pct(errors: int, length: int) -> str:
    return f"{100 * errors / length:.1f}% ({errors}/{length})" if length else "-"


def main():
    p = argparse.ArgumentParser(description="Score hub transcripts against reference files.")
    p.add_argument("--pair", nargs=2, action="append", required=True, metavar=("REF_TXT", "META_JSON"))
    p.add_argument("--json", action="store_true", help="print the totals as JSON")
    a = p.parse_args()

    totals = {k: [0, 0] for k in ("mer", "wer", "cer", "der", "cpwer")}
    todo_hits, todo_hub, todo_ref = 0, 0, 0
    noise_words, noise_ms = 0, 0
    speaker_skipped = []
    for ref_path, meta_path in a.pair:
        ref, hub = read_reference(Path(ref_path)), read_hub(Path(meta_path))
        hub_tokens = text_of(hub.lines)
        ref_tokens = text_of(ref.lines)
        rows = {
            "mer": rate(ref_tokens, hub_tokens),
            "wer": rate([t for t in ref_tokens if not is_cjk(t[0])], [t for t in hub_tokens if not is_cjk(t[0])]),
            "cer": rate([t for t in ref_tokens if is_cjk(t[0])], [t for t in hub_tokens if is_cjk(t[0])]),
        }
        if any(line.speaker in UNKNOWN for line in ref.lines) or not ref.lines:
            speaker_skipped.append(ref.name)
        else:
            rows["der"] = der(ref, hub)
            rows["cpwer"] = cpwer(ref, hub)
        matched = todo_match(ref.todos, hub.todos)
        todo_hits, todo_hub, todo_ref = todo_hits + matched, todo_hub + len(hub.todos), todo_ref + len(ref.todos)
        words = hallucinated(hub, ref.noise)
        span = sum(b - a for a, b in ref.noise)
        noise_words += words
        noise_ms += span
        print(f"{ref.name}: " + "  ".join(f"{k}={pct(*v)}" for k, v in rows.items()) + f"  todos={matched}/{len(hub.todos)} hub/{len(ref.todos)} ref  noise_words={words} in {span / 1000:.0f} s")
        for k, (e, n) in rows.items():
            totals[k][0] += e
            totals[k][1] += n

    summary = {k: (round(e / n, 4) if n else None) for k, (e, n) in totals.items()}
    summary["todo_precision"] = round(todo_hits / todo_hub, 4) if todo_hub else None
    summary["todo_recall"] = round(todo_hits / todo_ref, 4) if todo_ref else None
    summary["hallucinated_words_per_hour"] = round(noise_words / (noise_ms / 3_600_000), 1) if noise_ms else None
    summary["speaker_metrics_skipped"] = speaker_skipped
    if a.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print("TOTAL: " + "  ".join(f"{k}={v}" for k, v in summary.items()))


if __name__ == "__main__":
    main()
