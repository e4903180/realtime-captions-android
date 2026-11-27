"""Charts for the PDF report (build/report/print/*.svg). Numbers are copied from docs/benchmark-*.md."""
import pathlib

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "build" / "report" / "print"
OUT.mkdir(parents=True, exist_ok=True)

font_manager.fontManager.addfont(r"C:\Windows\Fonts\NotoSansTC-VF.ttf")
plt.rcParams.update({
    "font.family": "Microsoft JhengHei", "font.size": 9, "axes.edgecolor": "#c9ccd1", "axes.labelcolor": "#52514e",
    "xtick.color": "#52514e", "ytick.color": "#52514e", "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "grid.color": "#ebedf0", "grid.linewidth": 0.8, "axes.axisbelow": True,
    "svg.fonttype": "path",
})
# Reference categorical order (dataviz palette, light mode): blue, orange, aqua, yellow, magenta, green
S = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"]
INK, MUTED = "#0b0b0b", "#52514e"


def save(fig, name):
    fig.savefig(OUT / f"{name}.svg", bbox_inches="tight")
    plt.close(fig)


def cer_by_engine():
    engines = ["手機\nX-ASR", "Azure", "Google\nChirp 3", "Google\nV1", "Breeze-\nASR-25", "Whisper\nlarge-v3", "Whisper\nturbo"]
    sets = [("Common Voice 朗讀", [11.7, 5.1, 8.0, 21.1, 12.9, 13.1, 13.4]),
            ("YouTube 對談", [13.6, 11.1, 8.2, 16.8, 8.3, 8.5, 9.4]),
            ("台大課程", [13.7, None, None, None, 4.9, 7.3, 8.4])]
    fig, ax = plt.subplots(figsize=(7.6, 3.4))
    w = 0.27
    for k, (name, vals) in enumerate(sets):
        xs = [i + (k - 1) * w for i in range(len(engines))]
        labelled = False
        for x, v in zip(xs, vals):
            if v is None:
                continue
            ax.bar(x, v, w - 0.03, color=S[k], label=None if labelled else name)
            labelled = True
            ax.text(x, v + 0.3, f"{v:g}", ha="center", va="bottom", fontsize=6.5, color=MUTED)
    ax.set_xticks(range(len(engines)), engines, fontsize=8)
    ax.set_ylabel("錯字率 %")
    ax.set_ylim(0, 24)
    ax.legend(frameon=False, ncol=3, loc="lower left", bbox_to_anchor=(0, 1.0), fontsize=8)
    ax.set_title("各引擎在台灣口音測資的錯字率（越低越好）", loc="left", fontsize=10, color=INK, pad=24)
    save(fig, "cer_by_engine")


def speed_vs_accuracy():
    pts = [("手機 X-ASR", 1.00, 11.7, "手機"), ("Azure", 1.27, 5.1, "雲端"), ("Google Chirp 3", 3.68, 8.0, "雲端"),
           ("Google V1", 0.58, 21.1, "雲端"), ("Breeze-ASR-25", 1.24, 12.9, "4070"), ("Whisper large-v3", 1.16, 13.1, "4070"),
           ("Whisper turbo", 1.10, 13.4, "4070")]
    color = {"手機": S[0], "雲端": S[1], "4070": S[2]}
    fig, ax = plt.subplots(figsize=(7.2, 3.2))
    spots = {"手機 X-ASR": (0.25, 9.0), "Breeze-ASR-25": (1.75, 10.5), "Whisper large-v3": (1.75, 15.5),
             "Whisper turbo": (0.25, 16.5), "Azure": (1.55, 4.0), "Google Chirp 3": (3.0, 10.5), "Google V1": (0.95, 21.5)}
    for name, t, c, where in pts:
        ax.scatter(t, c, s=60, color=color[where], edgecolor="white", linewidth=1.5, zorder=3)
        tx, ty = spots[name]
        ax.annotate(name, (t, c), (tx, ty), fontsize=8, color=INK, va="center",
                    arrowprops=dict(arrowstyle="-", color="#9aa0a6", linewidth=0.7, shrinkA=2, shrinkB=4))
    for where, col in color.items():
        ax.scatter([], [], s=40, color=col, label={"手機": "手機離線", "雲端": "雲端 API", "4070": "自己的 4070 顯卡"}[where])
    ax.set_xlabel("第一個字出現的時間（秒，越左越快）")
    ax.set_ylabel("Common Voice 錯字率 %（越低越好）")
    ax.set_xlim(0, 4.3)
    ax.set_ylim(0, 24)
    ax.legend(frameon=False, loc="upper right", fontsize=8)
    ax.set_title("速度與準確度：左下角最好", loc="left", fontsize=10, color=INK)
    save(fig, "speed_vs_accuracy")


def noise_curves():
    snr = ["乾淨", "20", "15", "10", "5", "0", "-5"]
    data = [("廚房碗盤", [8, 8, 8, 7, 9, 13, 15]), ("街道車流", [8, 9, 11, 11, 12, 15, 21]),
            ("餐廳", [8, 12, 12, 15, 24, 38, 88]), ("咖啡廳＋殘響", [8, 16, 16, 20, 31, 53, 90]),
            ("多人交談背景", [8, 18, 20, 30, 55, 78, 90]), ("電視（有人說話）", [8, 70, 79, 87, 108, 120, 128])]
    fig, ax = plt.subplots(figsize=(7.2, 3.6))
    for k, (name, ys) in enumerate(data):
        ax.plot(range(len(snr)), ys, color=S[k], linewidth=2, marker="o", markersize=4, markeredgecolor="white", label=name)
    ax.axhline(15, color=MUTED, linewidth=1, linestyle=(0, (4, 3)))
    ax.text(0.0, 2.5, "虛線：錯字率 15%，大致跟得上對話", fontsize=8, color=MUTED)
    ax.set_xticks(range(len(snr)), snr)
    ax.set_xlabel("人聲比噪音大多少（dB，越右越吵）")
    ax.set_ylabel("錯字率 %")
    ax.set_xlim(-0.2, len(snr) - 0.8)
    ax.set_ylim(0, 135)
    ax.legend(frameon=False, ncol=3, loc="upper center", bbox_to_anchor=(0.5, -0.2), fontsize=8)
    ax.set_title("手機離線 X-ASR 在真實噪音中的錯字率", loc="left", fontsize=10, color=INK)
    save(fig, "noise_curves")


def accent():
    rows = [("AISHELL-1 朗讀（大陸）", 4.0, S[1]), ("Common Voice 朗讀（台灣）", 11.7, S[0]), ("YouTube 對談（台灣）", 13.6, S[0]),
            ("台大課程（台灣）", 13.7, S[0]), ("AliMeeting 會議（大陸，多人搶話）", 45.5, S[1])]
    fig, ax = plt.subplots(figsize=(7.2, 2.4))
    for i, (name, v, col) in enumerate(rows):
        ax.barh(i, v, 0.6, color=col)
        ax.text(v + 0.8, i, f"{v:g}%", va="center", fontsize=8, color=INK)
    ax.set_yticks(range(len(rows)), [r[0] for r in rows], fontsize=8)
    ax.invert_yaxis()
    ax.set_xlabel("錯字率 %")
    ax.set_xlim(0, 52)
    ax.grid(axis="y", visible=False)
    ax.set_title("同一個手機模型：台灣口音（藍）vs 大陸口音（橘）", loc="left", fontsize=10, color=INK)
    save(fig, "accent")


def phantom():
    rows = [("電視", 43), ("多人交談背景", 40), ("餐廳", 1), ("街道", 0), ("音樂", 0)]
    fig, ax = plt.subplots(figsize=(7.2, 2.2))
    for i, (name, v) in enumerate(rows):
        ax.barh(i, v, 0.6, color=S[1] if v > 5 else S[0])
        ax.text(v + 0.6, i, f"{v} 行", va="center", fontsize=8, color=INK)
    ax.set_yticks(range(len(rows)), [r[0] for r in rows])
    ax.invert_yaxis()
    ax.set_xlim(0, 50)
    ax.set_xlabel("3 分鐘純噪音裡冒出的假字幕行數")
    ax.grid(axis="y", visible=False)
    ax.set_title("沒有人說話時的假字幕", loc="left", fontsize=10, color=INK)
    save(fig, "phantom")


def denoise():
    cats = ["餐廳 10 dB", "餐廳 5 dB", "餐廳 0 dB", "咖啡廳 5 dB", "咖啡廳 0 dB", "交談 10 dB"]
    none = [15, 23, 39, 31, 53, 30]
    gtcrn = [20, 30, 68, 39, 76, 41]
    dpdf = [17, 25, 44, 28, 61, 33]
    fig, ax = plt.subplots(figsize=(7.2, 2.8))
    w = 0.26
    for k, (name, vals) in enumerate([("不降噪", none), ("GTCRN", gtcrn), ("DPDFNet", dpdf)]):
        ax.bar([i + (k - 1) * w for i in range(len(cats))], vals, w - 0.03, color=S[k], label=name)
    ax.set_xticks(range(len(cats)), cats, fontsize=8)
    ax.set_ylabel("錯字率 %")
    ax.legend(frameon=False, ncol=3, loc="upper left", fontsize=8)
    ax.set_ylim(0, 85)
    ax.set_title("降噪模型反而讓錯字率變高（越低越好）", loc="left", fontsize=10, color=INK)
    save(fig, "denoise")


if __name__ == "__main__":
    for f in (cer_by_engine, speed_vs_accuracy, noise_curves, accent, phantom, denoise):
        f()
    print("charts in", OUT)
