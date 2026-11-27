"""PDF version of the listening report, with charts.

Takes build/report/site/index.html (make_page.py) and the charts (make_charts.py), drops the audio
players, places the charts next to the sections they belong to, and prints it to
build/report/即時字幕引擎測試報告.pdf with headless Chrome.
"""
import pathlib
import re
import subprocess

REPO = pathlib.Path(__file__).resolve().parents[2]
SITE = REPO / "build" / "report" / "site" / "index.html"
PRINT = REPO / "build" / "report" / "print"
PDF = REPO / "build" / "report" / "即時字幕引擎測試報告.pdf"
CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"


def fig(name, caption):
    return f'<figure><img src="{name}.svg" alt="{caption}"><figcaption>{caption}</figcaption></figure>'


def main():
    s = SITE.read_text(encoding="utf-8")
    s = re.sub(r'<audio[^>]*></audio>', '<p class="note">（音檔可在線上版試聽）</p>', s)
    s = s.replace("<h2>總比較（台灣口音）</h2>",
                  "<h2>總比較（台灣口音）</h2>" + fig("cer_by_engine", "圖 1　各引擎在三組台灣口音測資的錯字率")
                  + fig("speed_vs_accuracy", "圖 2　第一個字出現的時間與錯字率（Common Voice）"))
    s = s.replace("<h2>噪音：手機離線 X-ASR 的錯字率</h2>",
                  "<h2>噪音：手機離線 X-ASR 的錯字率</h2>" + fig("noise_curves", "圖 3　六種真實噪音、不同噪音大小下的錯字率"))
    s = s.replace('<h3 class="group">沒有人說話時的「假字幕」（3 分鐘純噪音）</h3>',
                  '<h3 class="group">沒有人說話時的「假字幕」（3 分鐘純噪音）</h3>' + fig("phantom", "圖 4　3 分鐘純噪音產生的假字幕行數"))
    s = s.replace('<h3 class="group">降噪模型有沒有用？</h3>',
                  '<h3 class="group">降噪模型有沒有用？</h3>' + fig("denoise", "圖 5　降噪模型前後的錯字率"))
    s = s.replace("<h2>口音的影響</h2>", "<h2>口音的影響</h2>" + fig("accent", "圖 6　同一個手機模型在不同口音資料的錯字率"))
    s = s.replace("<p>按播放鍵聽原音，下面是各引擎的字幕，黑底仿照 App 的字幕畫面。</p>",
                  "<p>每段先列出正確答案，下面是各引擎打出的字幕（黑底仿照 App 的字幕畫面）。線上版可以直接試聽：https://claude.ai/artifact/NjtUjKhJm8xkEhFMQBk3E3</p>")
    print_css = """<style>
@page { size: A4; margin: 14mm 12mm 16mm; }
:root, :root[data-theme] { --bg: #ffffff; --panel: #ffffff; --fg: #1b2330; --muted: #5d6878; --line: #dde2ea; --accent: #0f6e8c;
  --good: #1f7a46; --mid: #a26a00; --bad: #b3261e; color-scheme: light; }
body { font-size: 11pt; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
main { max-width: none; padding: 0; }
h2 { break-after: avoid; } h3 { break-after: avoid; }
figure { margin: 10px 0 16px; break-inside: avoid; }
figure img { width: 100%; height: auto; display: block; }
figcaption { font-size: 9pt; color: var(--muted); margin-top: 4px; }
.clip, .verdict div, tr { break-inside: avoid; }
.outs { grid-template-columns: 1fr 1fr; }
.table { overflow: visible; }
</style>"""
    s = s.replace("</style>", "</style>" + print_css, 1)
    PRINT.mkdir(parents=True, exist_ok=True)
    html_file = PRINT / "report.html"
    html_file.write_text("<!doctype html><html lang=\"zh-Hant\"><head><meta charset=\"utf-8\">" + s.replace("<main>", "</head><body><main>", 1) + "</body></html>", encoding="utf-8")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", "--virtual-time-budget=8000",
                    f"--print-to-pdf={PDF}", html_file.as_uri()], check=True, capture_output=True)
    print("wrote", PDF)


if __name__ == "__main__":
    main()
