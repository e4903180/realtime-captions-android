"""Summarizes results/whisper.json as a Markdown table (pasted into docs/benchmark-real.md)."""
import json
import pathlib
import sys

import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
data = json.loads((HERE / "results" / "whisper.json").read_text(encoding="utf-8"))
ORDER = ["Common Voice zh-TW", "NTU ML lectures", "YouTube CC talks", "zh-TW TTS dialogs"]


def pct(x):
    return "–" if x is None or np.isnan(x) else f"{100 * x:.1f}%"


def p50(v):
    return "–" if not v else f"{np.percentile(v, 50):.2f}"


print("| Model | Set | CER offline | CER streaming | CER streaming, no fillers | First partial p50 (s) | Final p50 (s) | RTF offline / streaming | Peak VRAM |")
print("|---|---|---|---|---|---|---|---|---|")
for model, res in data.items():
    rows = res["rows"]
    sets = sorted({r["set"] for r in rows}, key=lambda s: (ORDER.index(s) if s in ORDER else 99, s))
    for s in sets:
        g = [r for r in rows if r["set"] == s]
        ref = sum(r["ref"] for r in g)
        dur = sum(r["dur"] for r in g)
        print(f"| {model} | {s} | {pct(sum(r['err_offline'] for r in g) / max(1, ref))} | {pct(sum(r['err_stream'] for r in g) / max(1, ref))} | "
              f"{pct(sum(r['err_stream_nofill'] for r in g) / max(1, sum(r['ref_nofill'] for r in g)))} | "
              f"{p50([x for r in g for x in r['ttfp']])} | {p50([x for r in g for x in r['final']])} | "
              f"{sum(r['rtf_offline'] * r['dur'] for r in g) / dur:.2f} / {sum(r['rtf_stream'] * r['dur'] for r in g) / dur:.2f} | {res['vram_mb'] / 1024:.1f} GB |")
