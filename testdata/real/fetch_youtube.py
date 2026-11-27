"""Downloads the clips listed in youtube_sources.json and turns them into benchmark inputs.

    pip install yt-dlp av numpy soxr
    python testdata/real/fetch_youtube.py datasets

Writes datasets/youtube_tw/raw/<id>.* (audio + human subtitles, json3) and
datasets/prepared/{wavs,truth}/yttw_<id>.{wav,json}. Subtitle cues become truth segments with
speaker "?" (no speaker labels), so only CER and latency are meaningful for this set.
"""
import json
import pathlib
import re
import sys

import av
import numpy as np
import soxr
import yt_dlp

HERE = pathlib.Path(__file__).resolve().parent
SR = 16000


def decode(path):
    with av.open(str(path)) as c:
        stream = c.streams.audio[0]
        chunks, rate = [], stream.rate
        for frame in c.decode(stream):
            a = frame.to_ndarray()
            a = a.mean(axis=0) if a.ndim > 1 and a.shape[0] < a.shape[1] else a.reshape(-1)
            if a.dtype != np.float32:
                a = a.astype(np.float32) / (32768.0 if a.dtype == np.int16 else 1.0)
            chunks.append(a)
    return soxr.resample(np.concatenate(chunks), rate, SR).astype(np.float32)


def cues(path, max_sec):
    """json3 subtitles -> [(start, end, text)]"""
    j = json.loads(pathlib.Path(path).read_text(encoding="utf-8"))
    out = []
    for e in j.get("events", []):
        text = "".join(s.get("utf8", "") for s in e.get("segs", []) or []).strip()
        text = re.sub(r"\s+", " ", text)
        if not text or "tStartMs" not in e:
            continue
        s, d = e["tStartMs"] / 1000, e.get("dDurationMs", 0) / 1000
        if s + d <= max_sec:
            out.append((s, s + d, text))
    return out


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "datasets")
    raw = root / "youtube_tw" / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    out = root / "prepared"
    spec = json.loads((HERE / "youtube_sources.json").read_text(encoding="utf-8"))
    for clip in spec["clips"]:
        vid = clip["id"]
        opts = {
            "quiet": True, "format": "bestaudio", "outtmpl": str(raw / f"{vid}.%(ext)s"),
            "writesubtitles": True, "writeautomaticsub": False, "subtitleslangs": [clip["subs"]], "subtitlesformat": "json3",
        }
        if not list(raw.glob(f"{vid}.*.json3")):
            with yt_dlp.YoutubeDL(opts) as y:
                y.download([f"https://www.youtube.com/watch?v={vid}"])
        audio_file = next(p for p in raw.glob(f"{vid}.*") if not p.name.endswith(".json3"))
        sub_file = next(raw.glob(f"{vid}.*.json3"))
        max_sec = clip["max_minutes"] * 60
        audio = decode(audio_file)[: int(max_sec * SR)]
        segs = [{"start": round(s, 3), "end": round(e, 3), "speaker": "?", "text": t} for s, e, t in cues(sub_file, len(audio) / SR)]
        (out / "wavs").mkdir(parents=True, exist_ok=True)
        (out / "truth").mkdir(parents=True, exist_ok=True)
        pcm = (np.clip(audio, -1, 1) * 32767).astype(np.int16)
        import wave
        with wave.open(str(out / "wavs" / f"yttw_{vid}.wav"), "wb") as w:
            w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
        truth = {"id": f"yttw_{vid}", "dialog": f"yttw_{vid}", "language": "zh-TW", "noise": "real-life", "snr_db": None,
                 "duration": round(len(audio) / SR, 3), "segments": segs, "speaker_labels": False}
        (out / "truth" / f"yttw_{vid}.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
        print(f"yttw_{vid}: {len(audio) / SR / 60:.1f} min, {len(segs)} cues")


if __name__ == "__main__":
    main()
