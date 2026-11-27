"""Turns downloaded real-speech corpora into benchmark inputs (wav + truth JSON).

Corpora are NOT kept in git. Download them with download_real.sh (or by hand) into a datasets
folder outside the repo, then run:

    python testdata/real/prepare_real.py datasets

Output: <datasets>/prepared/wavs/<id>.wav (16 kHz mono 16-bit) and
        <datasets>/prepared/truth/<id>.json, same format as testdata/truth.

Common Voice 19.0 zh-TW (CC0): Taiwan-accented read Mandarin from many volunteers; sessions
  alternate between two speakers (see common_voice_tw).
NTU ML 2021 lectures (MIT): Taiwan-accent lectures, CER only (see ml_lectures).
AISHELL-1 (Apache 2.0): clean read Mandarin, one speaker per utterance. From one shard of the
  test set (Hugging Face parquet mirror, needs `pip install pyarrow`) we build "sessions" of 12 utterances that alternate between two
  real speakers with 0.7-1.0 s pauses, so the same session scores CER and speaker tags.
AliMeeting Eval (CC BY-SA 4.0): real Mandarin meetings, 2-4 people, far-field 8-mic array.
  We keep microphone channel 0 and the first --minutes minutes of each meeting, with the
  per-speaker TextGrid transcripts as ground truth (speech may overlap).
"""
import json
import pathlib
import re
import sys
import wave

import numpy as np

SR = 16000


def write_wav(path, x):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def read_wav(path, channel=0, max_sec=None):
    """16-bit PCM wav (also WAVE_FORMAT_EXTENSIBLE, which the wave module rejects) -> one channel."""
    raw = pathlib.Path(path).read_bytes()
    pos, ch, data = 12, 1, None
    while pos + 8 <= len(raw):
        cid, size = raw[pos:pos + 4], int.from_bytes(raw[pos + 4:pos + 8], "little")
        if cid == b"fmt ":
            ch = int.from_bytes(raw[pos + 10:pos + 12], "little")
            rate = int.from_bytes(raw[pos + 12:pos + 16], "little")
            bits = int.from_bytes(raw[pos + 22:pos + 24], "little")
            assert rate == SR and bits == 16, (path, rate, bits)
        elif cid == b"data":
            data = np.frombuffer(raw[pos + 8:pos + 8 + size], dtype=np.int16)
            break
        pos += 8 + size + (size & 1)
    data = data[: len(data) // ch * ch].reshape(-1, ch)[:, channel]
    if max_sec is not None:
        data = data[: int(max_sec * SR)]
    return data.astype(np.float32) / 32768.0


def tighten(audio, segs):
    """Moves each turn's start/end to where speech actually starts/stops inside the clip.

    Read-speech corpora (Common Voice, AISHELL-1) have up to ~1 s of silence before and after
    each sentence; without this, latency would be measured from the silence instead of the voice."""
    for s in segs:
        a, b = int(s["start"] * SR), int(s["end"] * SR)
        x = np.abs(audio[a:b])
        if len(x) < 320:
            continue
        env = np.convolve(x, np.ones(320) / 320, mode="same")
        idx = np.flatnonzero(env > 0.1 * env.max())
        if len(idx):
            s["start"], s["end"] = round((a + idx[0]) / SR, 3), round((a + idx[-1]) / SR, 3)
    return segs


def save(out, name, dialog, audio, segs, noise, language="zh-CN"):
    write_wav(out / "wavs" / f"{name}.wav", audio)
    if noise in ("real-clean", "real-read"):
        segs = tighten(audio, segs)
    truth = {"id": name, "dialog": dialog, "language": language, "noise": noise, "snr_db": None,
             "duration": round(len(audio) / SR, 3), "segments": segs}
    (out / "truth").mkdir(parents=True, exist_ok=True)
    (out / "truth" / f"{name}.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{name}: {len(audio) / SR:.0f}s, {len(segs)} segments")


def aishell(root, out, per_session=12, sessions=16):
    """AISHELL-1 test utterances from the Hugging Face parquet mirror (needs pyarrow).

    The parquet has audio and text but no utterance ids, so speakers are recovered by matching
    the text against the official transcript (BAC009S0764W0121 -> speaker S0764)."""
    base = root / "aishell1"
    shards = sorted(base.glob("*.parquet"))
    tr = base / "aishell_transcript_v0.8.txt"
    if not shards or not tr.exists():
        return
    import io
    import pyarrow.parquet as pq

    by_text = {}
    for line in tr.read_text(encoding="utf-8").splitlines():
        parts = line.split(maxsplit=1)
        if len(parts) == 2:
            by_text.setdefault(parts[1].replace(" ", ""), parts[0])
    speakers = {}
    for shard in shards:
        t = pq.read_table(shard, columns=["context", "answer"])
        for ctx, ans in zip(t.column("context").to_pylist(), t.column("answer").to_pylist()):
            text = ans.replace(" ", "")
            utt = by_text.get(text)
            if utt is None:
                continue
            speakers.setdefault(utt[6:11], []).append((utt, text, ctx["bytes"]))
    names = sorted(s for s, u in speakers.items() if len(u) >= per_session * 2)
    print(f"aishell1: {sum(len(v) for v in speakers.values())} utterances, {len(names)} usable speakers")
    rng = np.random.default_rng(1)
    pairs = list(zip(names[0::2], names[1::2]))
    made = 0
    for k in range(sessions):
        if not pairs:
            break
        a, b = pairs[k % len(pairs)]
        round_ = k // len(pairs)
        parts, segs, t = [np.zeros(int(0.8 * SR), np.float32)], [], int(0.8 * SR)
        for i in range(per_session):
            spk = a if i % 2 == 0 else b
            idx = round_ * per_session // 2 + i // 2
            if idx >= len(speakers[spk]):
                break
            utt, text, data = speakers[spk][idx]
            x = read_wav_bytes(data)
            segs.append({"start": round(t / SR, 3), "end": round((t + len(x)) / SR, 3),
                         "speaker": "A" if spk == a else "B", "text": text})
            parts.append(x)
            t += len(x)
            gap = np.zeros(int(rng.uniform(0.7, 1.0) * SR), np.float32)
            parts.append(gap)
            t += len(gap)
        parts.append(np.zeros(SR, np.float32))
        save(out, f"aishell1_{a}_{b}_{round_}", f"aishell1_{a}_{b}", np.concatenate(parts), segs, "real-clean")
        made += 1


def read_wav_bytes(data):
    tmp = pathlib.Path("aishell_tmp.wav")
    tmp.write_bytes(data)
    try:
        return read_wav(tmp)
    finally:
        tmp.unlink()


def common_voice_tw(root, out, per_session=12, sessions=16):
    """Common Voice 19.0 zh-TW test split (CC0), Taiwan-accented read Mandarin.

    Mirror: huggingface.co/datasets/JacobLinCool/common_voice_19_0_zh-TW (not gated). Audio is
    mp3, decoded with soundfile and resampled with soxr (`pip install pyarrow soundfile soxr`).
    client_id is the speaker; Hong Kong and other non-Taiwan accent tags are left out. Sessions
    alternate between two speakers with 0.7-1.0 s pauses, like the AISHELL sessions."""
    shard = root / "cv_zh_tw" / "test-00000.parquet"
    if not shard.exists():
        return
    import io
    import pyarrow.parquet as pq
    import soundfile as sf
    import soxr

    t = pq.read_table(shard, columns=["client_id", "audio", "sentence", "accents"])
    speakers = {}
    for cid, audio, text, accent in zip(*(t.column(c).to_pylist() for c in ["client_id", "audio", "sentence", "accents"])):
        if accent and not any(k in accent for k in ("臺", "台", "新北", "高雄", "桃園", "新竹", "苗栗", "彰化", "南投", "雲林", "嘉義", "屏東", "宜蘭", "花蓮", "臺東", "基隆", "金門", "澎湖")):
            continue  # keep Taiwan birthplaces and untagged speakers (the zh-TW locale is mostly Taiwan)
        speakers.setdefault(cid, []).append((text, audio["bytes"]))
    names = sorted((c for c, u in speakers.items() if len(u) >= per_session // 2), key=lambda c: -len(speakers[c]))
    print(f"cv zh-TW: {sum(len(v) for v in speakers.values())} utterances, {len(names)} speakers with >= {per_session // 2}")
    rng = np.random.default_rng(2)
    for k in range(min(sessions, len(names) // 2)):
        a, b = names[2 * k], names[2 * k + 1]
        parts, segs, t0 = [np.zeros(int(0.8 * SR), np.float32)], [], int(0.8 * SR)
        for i in range(per_session):
            spk = a if i % 2 == 0 else b
            text, data = speakers[spk][i // 2]
            x, sr = sf.read(io.BytesIO(data), dtype="float32")
            if x.ndim > 1:
                x = x[:, 0]
            x = soxr.resample(x, sr, SR).astype(np.float32)
            segs.append({"start": round(t0 / SR, 3), "end": round((t0 + len(x)) / SR, 3),
                         "speaker": "A" if spk == a else "B", "text": text})
            parts.append(x)
            t0 += len(x)
            gap = np.zeros(int(rng.uniform(0.7, 1.0) * SR), np.float32)
            parts.append(gap)
            t0 += len(gap)
        parts.append(np.zeros(SR, np.float32))
        truth_audio = np.concatenate(parts)
        save(out, f"cvtw_{k:02d}", f"cvtw_{k:02d}", truth_audio, segs, "real-read", language="zh-TW")


def ml_lectures(root, out, count=2):
    """NTU Machine Learning 2021 lectures (Hung-yi Lee), Taiwan-accent Mandarin with English terms.

    Source: huggingface.co/datasets/generative-fusion-decoding/ml-lecture-2021-long (derived from
    ky552/ML2021_ASR_ST, MIT license). Whole lectures with one transcript and no timestamps, so
    they are scored for CER only."""
    shards = sorted((root / "ml2021").glob("*.parquet"))[:count]
    if not shards:
        return
    import io
    import pyarrow.parquet as pq
    import soundfile as sf

    for shard in shards:
        row = pq.read_table(shard).slice(0, 1).to_pylist()[0]
        x, sr = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32")
        assert sr == SR
        name = "ml2021_" + pathlib.Path(row["file"]).stem
        text = row["transcription"]
        save(out, name, name, x, [{"start": 0.0, "end": round(len(x) / SR, 3), "speaker": "?", "text": text}],
             "real-lecture", language="zh-TW")


def parse_textgrid(path):
    """Long-format Praat TextGrid -> [(tier name, xmin, xmax, text)]."""
    items, tier = [], None
    xmin = xmax = None
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.strip()
        if line.startswith("name ="):
            tier = line.split("=", 1)[1].strip().strip('"')
        elif line.startswith("xmin ="):
            xmin = float(line.split("=", 1)[1])
        elif line.startswith("xmax ="):
            xmax = float(line.split("=", 1)[1])
        elif line.startswith("text =") and tier is not None:
            t = line.split("=", 1)[1].strip().strip('"').strip()
            if t:
                items.append((tier, xmin, xmax, t))
    return items


def alimeeting(root, out, minutes=10.0):
    far = root / "alimeeting" / "Eval_Ali" / "Eval_Ali_far"
    for tg in sorted((far / "textgrid_dir").glob("*.TextGrid")):
        session = tg.stem  # e.g. R8001_M8004
        wavs = sorted((far / "audio_dir").glob(f"{session}_*.wav"))
        if not wavs:
            print("no far-field wav for", session)
            continue
        audio = read_wav(wavs[0], channel=0, max_sec=minutes * 60)
        dur = len(audio) / SR
        items = parse_textgrid(tg)
        tiers = sorted({t for t, *_ in items})
        label = {t: chr(ord("A") + i) for i, t in enumerate(tiers)}
        segs = [{"start": round(s, 3), "end": round(e, 3), "speaker": label[t], "text": re.sub(r"<[^>]*>", "", x)}
                for t, s, e, x in items if e <= dur]
        segs = [s for s in segs if s["text"]]
        segs.sort(key=lambda s: s["start"])
        save(out, f"alimeeting_{session}", f"alimeeting_{session}", audio, segs, "real-far-field")


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "datasets")
    minutes = float(sys.argv[2]) if len(sys.argv) > 2 else 10.0
    out = root / "prepared"
    common_voice_tw(root, out)
    ml_lectures(root, out)
    aishell(root, out)
    alimeeting(root, out, minutes)


if __name__ == "__main__":
    main()
