"""Builds the listening report page (build/report/site/index.html) from the clips and engine outputs.

Inputs: build/report/clips.json, build/report/xasr.json (from :desktop --clips),
        build/report/engines.json (from run_engines.py). Summary numbers come from docs/benchmark-*.md.
"""
import difflib
import html
import json
import pathlib

from opencc import OpenCC

REPO = pathlib.Path(__file__).resolve().parents[2]
OUT = REPO / "build" / "report"
S2TWP = OpenCC("s2twp")
FORMS = str.maketrans({"裏": "裡", "着": "著", "麪": "麵", "爲": "為", "綫": "線", "衆": "眾"})

ENGINES = [
    ("xasr", "手機離線 X-ASR", "App 預設 · 離線 · 免費 · 有說話者標籤"),
    ("breeze-asr-25", "Breeze-ASR-25", "台灣調校 Whisper · 自己的 4070 顯卡"),
    ("whisper-large-v3-turbo", "Whisper turbo", "OpenAI Whisper · 自己的 4070 顯卡"),
    ("azure", "Azure", "微軟雲端 · 付費 · 有說話者標籤"),
    ("google-chirp3", "Google Chirp 3", "Google 雲端 · 付費"),
    ("google-v1", "Google V1", "Google 雲端（舊版）· 付費"),
]


def tw(s):
    return S2TWP.convert(s).translate(FORMS)


def tokens(text):
    out, word = [], ""
    for c in text:
        if "一" <= c <= "鿿":
            if word:
                out.append(word.lower()); word = ""
            out.append(c)
        elif c.isalnum() or c == "'":
            word += c
        else:
            if word:
                out.append(word.lower()); word = ""
    if word:
        out.append(word.lower())
    return out


def edit(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j - 1] + (a[i - 1] != b[j - 1]), prev[j] + 1, cur[j - 1] + 1)
        prev = cur
    return prev[-1]


def marked(text, ref_tokens):
    """HTML of [text] with characters/words that are not in the reference alignment highlighted."""
    hyp = tokens(text)
    ok = set()
    sm = difflib.SequenceMatcher(a=ref_tokens, b=hyp, autojunk=False)
    for blk in sm.get_matching_blocks():
        ok.update(range(blk.b, blk.b + blk.size))
    out, k, word = [], 0, ""
    # walk the original text, mapping token positions back onto characters
    i = 0
    while i < len(text):
        c = text[i]
        if "一" <= c <= "鿿":
            cls = "" if k in ok else ' class="x"'
            out.append(f"<span{cls}>{html.escape(c)}</span>" if cls else html.escape(c))
            k += 1
            i += 1
        elif c.isalnum() or c == "'":
            j = i
            while j < len(text) and (text[j].isalnum() or text[j] == "'") and not ("一" <= text[j] <= "鿿"):
                j += 1
            w = text[i:j]
            out.append(html.escape(w) if k in ok else f'<span class="x">{html.escape(w)}</span>')
            k += 1
            i = j
        else:
            out.append(html.escape(c))
            i += 1
    return "".join(out)


def caption_html(lines, ref_tokens):
    if not lines:
        return '<p class="none">（沒有輸出任何字幕）</p>'
    rows = []
    for l in lines:
        spk = l.get("speaker")
        tag = f'<b class="spk s{ord(spk[0]) - 65 if spk and len(spk) == 1 else 9}">{html.escape(spk)}：</b>' if spk else ""
        if spk and len(spk) > 1:  # Azure names speakers Guest-1, Guest-2 ...
            n = spk.split("-")[-1]
            tag = f'<b class="spk s{(int(n) - 1) % 5 if n.isdigit() else 9}">{html.escape(spk)}：</b>'
        rows.append(f"<p>{tag}{marked(l['text'], ref_tokens)}</p>")
    return "".join(rows)


def main():
    clips = json.loads((OUT / "clips.json").read_text(encoding="utf-8"))
    xasr = {d["id"]: d["lines"] for d in json.loads((OUT / "xasr.json").read_text(encoding="utf-8"))}
    eng = json.loads((OUT / "engines.json").read_text(encoding="utf-8"))
    groups = []
    for c in clips:
        if c["group"] not in groups:
            groups.append(c["group"])

    cards = []
    for g in groups:
        cards.append(f'<h3 class="group">{html.escape(g)}</h3>')
        for c in [c for c in clips if c["group"] == g]:
            ref_text = "".join(s["text"] for s in c["segments"])
            ref_tok = tokens(tw(ref_text))
            ref_rows = "".join(
                f'<p><b class="spk ref">{html.escape(s["speaker"]) if s["speaker"] != "?" else ""}{"：" if s["speaker"] != "?" else ""}</b>{html.escape(s["text"])}</p>'
                for s in c["segments"]) or '<p class="none">沒有人對使用者說話：正確答案是「沒有字幕」。</p>'
            outs = []
            for key, name, sub in ENGINES:
                lines = xasr.get(c["id"], []) if key == "xasr" else eng.get(c["id"], {}).get(key, [])
                hyp = tokens(tw("".join(l["text"] for l in lines)))
                if ref_tok:
                    cer = edit(ref_tok, hyp) / len(ref_tok)
                    score = f'<span class="cer {"good" if cer < 0.1 else "mid" if cer < 0.3 else "bad"}">錯字率 {cer:.0%}</span>'
                else:
                    score = f'<span class="cer {"good" if not hyp else "bad"}">{"沒有假字幕" if not hyp else f"假字幕 {len(hyp)} 字"}</span>'
                outs.append(f'''<div class="out"><div class="eng"><strong>{name}</strong><small>{sub}</small>{score}</div>
<div class="screen">{caption_html(lines, ref_tok)}</div></div>''')
            cards.append(f'''<article class="clip" id="{c["id"]}">
<header><h4>{html.escape(c["title"])}</h4><span class="meta">{c["duration"]} 秒 · {html.escape(c["license"])}</span></header>
<p class="note">{html.escape(c["note"])}</p>
<audio controls preload="none" src="audio/{c["id"]}.mp3"></audio>
<div class="ref"><span class="label">正確答案</span>{ref_rows}</div>
<div class="outs">{"".join(outs)}</div>
</article>''')

    page = TEMPLATE.replace("{{CLIPS}}", "\n".join(cards))
    (OUT / "site" / "index.html").write_text(page, encoding="utf-8")
    print("wrote", OUT / "site" / "index.html")


TEMPLATE = (pathlib.Path(__file__).with_name("template.html")).read_text(encoding="utf-8")

if __name__ == "__main__":
    main()
