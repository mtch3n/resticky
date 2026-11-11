# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy"]
# ///
import argparse
import json

import numpy as np


def main():
    p = argparse.ArgumentParser()
    p.add_argument("a")
    p.add_argument("b")
    a = p.parse_args()

    ea = json.load(open(a.a))["embeddings"]
    eb = json.load(open(a.b))["embeddings"]
    for name, emb in (("A", ea), ("B", eb)):
        keys = sorted(emb)
        print(f"within {name}:", {f"{x}-{y}": round(float(np.dot(emb[x], emb[y])), 3) for i, x in enumerate(keys) for y in keys[i + 1 :]})
    print("across:")
    for x in sorted(ea):
        print(x, {y: round(float(np.dot(ea[x], eb[y])), 3) for y in sorted(eb)})


main()
