"""Whisper-family reference benchmark on the Taiwan-accent sets (GPU).

Not part of the app: it tells us what a server-side engine could do compared with the on-device
X-ASR 160 ms model. Same inputs and scoring as :desktop (Traditional with Taiwan phrasing, CER on
CJK characters + English words, punctuation ignored).

Two modes per model:
  offline  whole file with Whisper's VAD filter; best-case accuracy.
  stream   pseudo-streaming: speech regions from Silero VAD; while someone talks the region so far
           is re-transcribed every 1 s (partial captions), and 0.4 s after they stop the whole
           region is transcribed once more (final caption). Latency = when that result would be
           on screen (audio time + GPU compute), like the :desktop real-time clock.

    .venv/Scripts/python bench.py --data ../../datasets/prepared ../../datasets/noise_bench ../../testdata
"""
import argparse
import json
import pathlib
import re
import subprocess
import threading
import time
import wave

import numpy as np
from faster_whisper import WhisperModel
from faster_whisper.vad import VadOptions, get_speech_timestamps
from opencc import OpenCC

SR = 16000
HERE = pathlib.Path(__file__).resolve().parent
S2TWP = OpenCC("s2twp")
# Same list as OpenCcTaiwan.taiwanForms in desktop/.../Metrics.kt: keep the two in sync.
TAIWAN_FORMS = str.maketrans({"裏": "裡", "着": "著", "麪": "麵", "爲": "為", "綫": "線", "衆": "眾"})
FILLERS = {"嗯", "啊", "呃", "欸", "誒", "喔", "哦", "唉", "哎", "噢", "呀", "啦", "嘛", "um", "uh"}

MODELS = {
    "whisper-large-v3-turbo": "mobiuslabsgmbh/faster-whisper-large-v3-turbo",
    "breeze-asr-25": str(HERE / "models" / "breeze-asr-25-ct2"),
    "whisper-large-v3": "Systran/faster-whisper-large-v3",
}


def tw(text):
    return S2TWP.convert(text).translate(TAIWAN_FORMS)


def tokens(text):
    out, word = [], ""
    for c in text:
        if "一" <= c <= "鿿" or "㐀" <= c <= "䶿" or "豈" <= c <= "﫿":
            if word:
                out.append(word.lower())
                word = ""
            out.append(c)
        elif c.isalnum() or c == "'":
            word += c
        else:
            if word:
                out.append(word.lower())
                word = ""
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


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        return np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768


class VramSampler:
    """Peak GPU memory from nvidia-smi while a model is loaded."""

    def __init__(self):
        self.peak, self.stop = 0, False
        self.t = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop:
            try:
                out = subprocess.run(["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
                                     capture_output=True, text=True, timeout=5).stdout
                self.peak = max(self.peak, int(out.strip().splitlines()[0]))
            except Exception:
                pass
            time.sleep(0.5)

    def __enter__(self):
        self.t.start()
        return self

    def __exit__(self, *a):
        self.stop = True


def transcribe(model, audio, **kw):
    t0 = time.perf_counter()
    segs, _ = model.transcribe(audio, language="zh", beam_size=1, condition_on_previous_text=False, **kw)
    text = "".join(s.text for s in segs)
    return text.strip(), time.perf_counter() - t0


def stream_run(model, audio):
    """Pseudo-streaming over VAD speech regions. Returns (utterances, compute seconds)."""
    regions = get_speech_timestamps(audio, VadOptions(min_silence_duration_ms=400, speech_pad_ms=100))
    clock, busy, utts = 0.0, 0.0, []
    for r in regions:
        s, e = r["start"] / SR, r["end"] / SR
        first = None
        t = s + 1.0
        while t < e:  # partial captions every second while the person is still talking
            clock = max(clock, t)
            # Like whisper_streaming, older text would be committed; only the last 15 s is re-decoded.
            text, dt = transcribe(model, audio[int(max(s, t - 15) * SR):int(t * SR)])
            busy += dt
            clock += dt
            if text and first is None:
                first = clock
            t += 1.0
        clock = max(clock, e + 0.4)  # VAD notices the pause
        text, dt = transcribe(model, audio[int(s * SR):int(e * SR)])
        busy += dt
        clock += dt
        if first is None and text:
            first = clock
        utts.append({"start": s, "end": e, "text": text, "first": first, "final": clock})
    return utts, busy


def latencies(utts, segs):
    ttfp, fin = [], []
    if len(segs) <= 1:
        return ttfp, fin
    for sg in segs:
        ov = [u for u in utts if u["text"] and min(u["end"], sg["end"] + 1.0) > max(u["start"], sg["start"] - 0.3)]
        if ov:
            ttfp.append(min(u["first"] for u in ov) - sg["start"])
            fin.append(max(u["final"] for u in ov) - sg["end"])
    return ttfp, fin


def load_items(dirs, only):
    items = []
    for d in dirs:
        d = pathlib.Path(d)
        for tf in sorted((d / "truth").glob("*.json")):
            t = json.loads(tf.read_text(encoding="utf-8"))
            wav = d / "wavs" / f"{t['id']}.wav"
            if wav.exists() and only(t):
                items.append((t, wav))
    return items


def taiwan(t):
    i = t["id"]
    if "__" not in i and i.startswith(("cvtw_", "yttw_", "ml2021_")):
        return True
    if i in ("zh_clinic", "zh_family_dinner", "zh_meeting", "zh_codeswitch"):
        return True
    # noise subset: two base dialogs, three noises, 10/5/0 dB
    m = re.match(r"(cvtw_00|zh_clinic)__(babble|restaurant|tv)_(10|5|0)dB$", i)
    return bool(m)


def dataset(t):
    i = t["id"]
    if "__" in i:
        return f"noise {i.split('__')[1].rsplit('_', 1)[0]} {t['snr_db']} dB"
    return {"cvtw": "Common Voice zh-TW", "yttw": "YouTube CC talks", "ml2021": "NTU ML lectures"}.get(i.split("_")[0], "zh-TW TTS dialogs")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("--models", nargs="+", default=list(MODELS))
    ap.add_argument("--out", default=str(HERE / "results" / "whisper.json"))
    args = ap.parse_args()
    items = load_items(args.data, taiwan)
    print(len(items), "files")
    results = {}
    for name in args.models:
        with VramSampler() as vram:
            model = WhisperModel(MODELS[name], device="cuda", compute_type="float16",
                                 download_root=str(HERE / "models" / "hf"))
            rows = []
            for t, wav in items:
                audio = read_wav(wav)
                dur = len(audio) / SR
                ref = [x for s in t["segments"] for x in tokens(tw(s["text"]))]
                off_text, off_dt = transcribe(model, audio, vad_filter=True)
                # Lectures have no turn timestamps: offline accuracy only.
                utts, busy = stream_run(model, audio) if not t["id"].startswith("ml2021_") else ([{"start": 0, "end": dur, "text": off_text, "first": None, "final": None}], off_dt)
                st_text = "".join(u["text"] for u in utts)
                ttfp, fin = latencies(utts, t["segments"])
                hyp_off, hyp_st = tokens(tw(off_text)), tokens(tw(st_text))
                rows.append({
                    "id": t["id"], "set": dataset(t), "dur": dur, "ref": len(ref),
                    "err_offline": edit(ref, hyp_off), "err_stream": edit(ref, hyp_st),
                    "err_stream_nofill": edit([x for x in ref if x not in FILLERS], [x for x in hyp_st if x not in FILLERS]),
                    "ref_nofill": len([x for x in ref if x not in FILLERS]),
                    "ttfp": ttfp, "final": fin, "rtf_offline": off_dt / dur, "rtf_stream": busy / dur,
                    # Full hypotheses, so results can be re-scored later without re-running the GPU.
                    "text_stream": tw(st_text), "text_offline": tw(off_text),
                    "utterances": [{"start": u["start"], "end": u["end"], "text": tw(u["text"])} for u in utts],
                })
                print(name, t["id"], f"{time.strftime('%H:%M:%S')}", f"off {rows[-1]['err_offline'] / max(1, len(ref)):.3f}", f"st {rows[-1]['err_stream'] / max(1, len(ref)):.3f}", flush=True)
            del model
        results[name] = {"vram_mb": vram.peak, "rows": rows}
        pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        pathlib.Path(args.out).write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", args.out)


if __name__ == "__main__":
    main()
