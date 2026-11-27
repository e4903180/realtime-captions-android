"""Turns downloaded real-speech corpora into benchmark inputs (wav + truth JSON).

Corpora are NOT kept in git. Download them with download_real.sh (or by hand) into a datasets
folder outside the repo, then run:

    python testdata/real/prepare_real.py datasets

Output: <datasets>/prepared/wavs/<id>.wav (16 kHz mono 16-bit) and
        <datasets>/prepared/truth/<id>.json, same format as testdata/truth.

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


def save(out, name, dialog, audio, segs, noise):
    write_wav(out / "wavs" / f"{name}.wav", audio)
    truth = {"id": name, "dialog": dialog, "language": "zh-CN", "noise": noise, "snr_db": None,
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
    aishell(root, out)
    alimeeting(root, out, minutes)


if __name__ == "__main__":
    main()
