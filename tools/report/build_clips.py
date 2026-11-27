"""Cuts short listening examples for the benchmark report.

Each clip is a few turns from a benchmark file (same audio, same ground truth), written as
  build/report/clips/wavs/<id>.wav + truth/<id>.json   (input for the engines)
  build/report/site/audio/<id>.mp3                      (for the web page)
and build/report/clips.json with titles, sources and licenses.
"""
import json
import pathlib
import subprocess
import wave

import imageio_ffmpeg
import numpy as np

REPO = pathlib.Path(__file__).resolve().parents[2]
OUT = REPO / "build" / "report"
SR = 16000

P = REPO / "datasets" / "prepared"
T = REPO / "testdata"
N = REPO / "datasets" / "noise_bench"

# id, title, group, source dir, file id, first turn, last turn (inclusive) or (start, end) seconds, note, license
CLIPS = [
    ("tts-clinic", "醫院看診（合成語音，乾淨）", "TTS 合成對話", T, "zh_clinic", (0, 2), "Windows 台灣中文語音 Yating（醫生）與 Zhiwei（病人）。", "自製"),
    ("tts-clinic-cafe5", "同一段，加咖啡廳噪音 5 dB", "TTS 合成對話", T, "zh_clinic_cafe_snr5", (0, 2), "人聲比噪音只大 5 dB，大約是吵雜咖啡廳裡隔著一張桌子說話。", "自製"),
    ("tts-dinner", "家人討論晚餐（三人）", "TTS 合成對話", T, "zh_family_dinner", (0, 3), "三個聲音：Hanhan、Zhiwei、Yating，測說話者標籤 A／B／C。", "自製"),
    ("cv-kaohsiung", "高雄地名（真人朗讀，台灣腔）", "Common Voice zh-TW", P, "cvtw_03", (0, 2), "兩位台灣志工輪流朗讀，內容含高雄地名。", "CC0"),
    ("cv-shimen", "石門水庫（真人朗讀，台灣腔）", "Common Voice zh-TW", P, "cvtw_00", (0, 2), "較難的一段：錄音品質普通、語速快。", "CC0"),
    ("cv-restaurant5", "同一段，加真實餐廳噪音 5 dB", "噪音驗證", N, "cvtw_00__restaurant_5dB", (0, 2), "DEMAND 資料集的真實餐廳錄音。", "CC0 + CC BY-SA 3.0"),
    ("cv-tv10", "同一段，旁邊開著電視 10 dB", "噪音驗證", N, "cvtw_00__tv_10dB", (0, 2), "電視裡有另一個人在說話，加上背景音樂。", "CC0 + CC BY 4.0"),
    ("yt-pdis", "政府協作會議訪談（YouTube，真實錄音）", "YouTube CC 影片", P, "yttw__Tsg1TgjGcM", (0, 4), "數位發展部 PDIS 影片，人工字幕當作正確答案（字幕有時會略作修飾）。", "CC BY 3.0（PDIS）"),
    ("aishell", "財經新聞朗讀（大陸腔，對照組）", "AISHELL-1", P, "aishell1_S0764_S0765_0", (0, 1), "大陸口音近距離朗讀，所有模型在這裡都表現很好。", "Apache 2.0"),
    ("alimeeting", "真實會議，多人搶話（大陸腔）", "AliMeeting", P, "alimeeting_R8007_M8010", (20.0, 40.0), "會議室遠距收音，超過一半的時間有兩人以上同時說話。", "CC BY-SA 4.0"),
    ("en-coffee", "英文：咖啡店點餐", "TTS 合成對話", T, "en_coffee", (0, 3), "英文語音 David 與 Zira。", "自製"),
    ("noise-tv", "沒有人對你說話，只有電視開著", "噪音驗證", N, "noiseonly__tv", (30.0, 45.0), "這段任何字幕都是「假字幕」。", "CC0 + CC BY 4.0"),
]


def read(path):
    with wave.open(str(path)) as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768


def main():
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    meta = []
    for cid, title, group, d, fid, span, note, lic in CLIPS:
        truth = json.loads((d / "truth" / f"{fid}.json").read_text(encoding="utf-8"))
        audio = read(d / "wavs" / f"{fid}.wav")
        segs = truth["segments"]
        if isinstance(span[0], int):
            a, b = segs[span[0]]["start"] - 0.6, segs[span[1]]["end"] + 0.8
        else:
            a, b = span
        a = max(0.0, a)
        b = min(len(audio) / SR, b)
        clip = audio[int(a * SR):int(b * SR)]
        inside = [dict(s, start=round(s["start"] - a, 3), end=round(s["end"] - a, 3))
                  for s in segs if s["start"] >= a - 0.05 and s["end"] <= b + 0.05]
        (OUT / "clips" / "wavs").mkdir(parents=True, exist_ok=True)
        (OUT / "clips" / "truth").mkdir(parents=True, exist_ok=True)
        with wave.open(str(OUT / "clips" / "wavs" / f"{cid}.wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
            w.writeframes((np.clip(clip, -1, 1) * 32767).astype(np.int16).tobytes())
        t = {"id": cid, "dialog": cid, "language": truth["language"], "noise": truth["noise"], "snr_db": truth.get("snr_db"),
             "duration": round(len(clip) / SR, 3), "segments": inside}
        (OUT / "clips" / "truth" / f"{cid}.json").write_text(json.dumps(t, ensure_ascii=False, indent=1), encoding="utf-8")
        (OUT / "site" / "audio").mkdir(parents=True, exist_ok=True)
        subprocess.run([ff, "-y", "-loglevel", "error", "-i", str(OUT / "clips" / "wavs" / f"{cid}.wav"),
                        "-ac", "1", "-b:a", "64k", str(OUT / "site" / "audio" / f"{cid}.mp3")], check=True)
        meta.append({"id": cid, "title": title, "group": group, "source": fid, "note": note, "license": lic,
                     "duration": round(len(clip) / SR, 1), "segments": inside})
        print(cid, f"{len(clip) / SR:.1f}s", len(inside), "turns")
    (OUT / "clips.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
