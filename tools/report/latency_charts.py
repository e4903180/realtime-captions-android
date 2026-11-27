"""Charts for the latency report (numbers from docs/latency.md)."""
import pathlib

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "build" / "report" / "latency"
OUT.mkdir(parents=True, exist_ok=True)
plt.rcParams.update({
    "font.family": "Microsoft JhengHei", "font.size": 9, "axes.edgecolor": "#c9ccd1", "axes.labelcolor": "#52514e",
    "xtick.color": "#52514e", "ytick.color": "#52514e", "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "grid.color": "#ebedf0", "axes.axisbelow": True, "svg.fonttype": "path",
})
BLUE, ORANGE, AQUA, GRAY = "#2a78d6", "#eb6834", "#1baf7a", "#9aa0a6"
INK = "#0b0b0b"

rows = [
    ("原本（每 0.1 秒送聲音、2 核心）", 0.95, 0.64, 10),
    ("每 0.02 秒送聲音", 0.91, 0.60, 10),
    ("4 核心", 0.95, 0.64, 10),
    ("0.02 秒＋4 核心（已套用）", 0.91, 0.59, 10),
    ("定稿靜音 0.3 秒", 0.95, 0.51, 10),
    ("全部組合（再加 0.3 秒）", 0.92, 0.49, 11),
    ("480ms 模型（對照）", 1.13, 0.68, 9),
]

fig, ax = plt.subplots(figsize=(7.4, 3.6))
ys = range(len(rows))
h = 0.36
for i, (name, first, done, cer) in enumerate(rows):
    ax.barh(i - h / 2, first, h - 0.04, color=BLUE, label="第一個字出現" if i == 0 else None)
    ax.barh(i + h / 2, done, h - 0.04, color=ORANGE, label="說完到定稿" if i == 0 else None)
    ax.text(first + 0.015, i - h / 2, f"{first:.2f}", va="center", fontsize=7.5, color=INK)
    ax.text(done + 0.015, i + h / 2, f"{done:.2f}", va="center", fontsize=7.5, color=INK)
ax.set_yticks(list(ys), [r[0] for r in rows], fontsize=8.5)
ax.invert_yaxis()
ax.set_xlabel("秒（中位數，越短越好）")
ax.set_xlim(0, 1.3)
ax.grid(axis="y", visible=False)
ax.legend(frameon=False, loc="lower left", bbox_to_anchor=(0, 1.0), ncol=2, fontsize=8)
ax.set_title("各種加速設定的延遲", loc="left", fontsize=10, color=INK, pad=22)
fig.savefig(OUT / "settings.svg", bbox_inches="tight")
plt.close(fig)

# Where the ~0.95 s to the first character goes (estimated split, see report text).
parts = [("說話本身（聽完第一個字的大半）", 0.25, GRAY), ("模型（聲音區塊＋往後看）", 0.64, BLUE),
         ("送聲音的間隔", 0.05, AQUA), ("運算（電腦）", 0.01, ORANGE)]
fig, ax = plt.subplots(figsize=(7.4, 1.5))
left = 0
for name, v, col in parts:
    ax.barh(0, v, 0.5, left=left, color=col, edgecolor="white", linewidth=2, label=f"{name} 約 {v:.2f} 秒")
    left += v
ax.set_yticks([])
ax.set_xlim(0, 1.0)
ax.set_xlabel("秒")
ax.grid(axis="y", visible=False)
ax.legend(frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.55), ncol=2, fontsize=8)
ax.set_title("第一個字的 0.95 秒花在哪裡（原本設定，估計）", loc="left", fontsize=10, color=INK)
fig.savefig(OUT / "split.svg", bbox_inches="tight")
plt.close(fig)

# matplotlib writes a DOCTYPE line; artifact hosting refuses DTDs in SVG files.
import re  # noqa: E402

for name in ("settings.svg", "split.svg"):
    p = OUT / name
    p.write_text(re.sub(r"<!DOCTYPE[^>]*>\s*", "", p.read_text(encoding="utf-8")), encoding="utf-8")
print("ok")
