# /// script
# requires-python = ">=3.11"
# ///
import argparse
import json
import re
import string
from collections import Counter

NAME = re.compile(r"\b(?i:my name is|my name's|this is|i am|i'm|it's)\s+([A-Z][a-z]+)")
STOP = {"I", "The", "A", "So", "And", "But", "Not", "Just", "Going", "Gonna", "Like", "Really", "Sure", "Here", "There", "That", "What", "Okay", "OK", "Yeah", "Good", "Fine", "Actually", "Also", "Still", "Very", "Pretty", "Kind", "Sorry", "Done", "Back", "Right", "Thinking", "Trying", "Saying", "Talking", "Looking", "Getting"}


def words(path):
    data = json.load(open(path))
    out = []
    for seg in data["transcription"]:
        seg_end = seg["offsets"]["to"] / 1000
        for tok in seg["tokens"]:
            text = tok["text"]
            if text.startswith("[_") or tok["t_dtw"] < 0:
                continue
            t = tok["t_dtw"] / 100
            if out and not text.startswith(" ") and out[-1]["seg_end"] == seg_end:
                out[-1]["text"] += text
                continue
            out.append({"start": t, "text": text, "seg_end": seg_end})
    for w, nxt in zip(out, out[1:] + [None]):
        w["end"] = min(nxt["start"], w["start"] + 1.0) if nxt else w["seg_end"]
        w["end"] = max(w["end"], w["start"] + 0.05)
    return out


def speaker_for(w, segments, last):
    best, best_ov = None, 0.0
    for s in segments:
        ov = min(w["end"], s["end"]) - max(w["start"], s["start"])
        if ov > best_ov:
            best, best_ov = s["speaker"], ov
    if best is not None:
        return best
    if last is not None:
        return last
    mid = (w["start"] + w["end"]) / 2
    return min(segments, key=lambda s: min(abs(mid - s["start"]), abs(mid - s["end"])))["speaker"]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("diar")
    p.add_argument("whisper_json")
    p.add_argument("out")
    a = p.parse_args()

    diar = json.load(open(a.diar))
    segs = diar["segments"]
    turns, last = [], None
    for w in words(a.whisper_json):
        spk = speaker_for(w, segs, last)
        if turns and turns[-1]["speaker"] == spk:
            turns[-1]["text"] += w["text"]
            turns[-1]["end"] = w["end"]
        else:
            turns.append({"speaker": spk, "start": w["start"], "end": w["end"], "text": w["text"]})
        last = spk

    order = []
    for t in turns:
        if t["speaker"] not in order:
            order.append(t["speaker"])
    letters = {s: f"Speaker {string.ascii_uppercase[i]}" for i, s in enumerate(order)}

    names = {}
    for spk in order:
        found = Counter()
        for t in turns:
            if t["speaker"] == spk:
                found.update(n for n in NAME.findall(t["text"]) if n not in STOP)
        if found:
            names[spk] = found.most_common(1)[0][0]
    labels = {s: names.get(s, letters[s]) for s in order}

    with open(a.out, "w") as f:
        for t in turns:
            m, s = divmod(int(t["start"]), 60)
            f.write(f"[{m:02d}:{s:02d}] {labels[t['speaker']]}: {t['text'].strip()}\n")
    print(json.dumps({"turns": len(turns), "labels": {str(k): v for k, v in labels.items()}, "name_hits": {str(k): v for k, v in names.items()}}))


main()
