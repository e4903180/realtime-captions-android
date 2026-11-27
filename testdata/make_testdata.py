"""Assembles synthetic multi-speaker dialogs and noisy variants from TTS clips.

Input:  build/tts/<dialog>_<turn>.wav and build/tts/babble_*.wav (made by generate.ps1)
Output: wavs/<name>.wav (16 kHz mono 16-bit) and truth/<name>.json with speaker turns:
        {"id", "language", "noise", "snr_db", "segments": [{"start", "end", "speaker", "text"}]}

Noise types (both synthetic, deterministic with a fixed seed):
  babble  six overlapping distractor talkers (Windows TTS voices)
  cafe    softer babble + pink noise + cup/cutlery clinks
SNR is measured over the speech-active parts of the dialog.
"""
import json
import pathlib
import wave

import numpy as np

SR = 16000
HERE = pathlib.Path(__file__).resolve().parent
TTS = HERE / "build" / "tts"
WAVS = HERE / "wavs"
TRUTH = HERE / "truth"
SNRS = [10, 5]


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0


def write_wav(path, x):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1.0, 1.0) * 32767.0).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def trim(x, thresh=0.01, pad=0.05):
    idx = np.flatnonzero(np.abs(x) > thresh)
    if idx.size == 0:
        return x
    p = int(pad * SR)
    return x[max(0, idx[0] - p): min(len(x), idx[-1] + p)]


def assemble(dialog, rng):
    parts = [np.zeros(int(0.8 * SR), np.float32)]
    t = len(parts[0])
    segs = []
    for i, turn in enumerate(dialog["turns"]):
        clip = trim(read_wav(TTS / f"{dialog['id']}_{i:02d}.wav"))
        clip = 0.5 * clip / (np.max(np.abs(clip)) + 1e-9)
        segs.append({"start": round(t / SR, 3), "end": round((t + len(clip)) / SR, 3),
                     "speaker": turn["speaker"], "text": turn["text"]})
        parts.append(clip)
        t += len(clip)
        gap = np.zeros(int(rng.uniform(0.6, 1.1) * SR), np.float32)
        parts.append(gap)
        t += len(gap)
    parts.append(np.zeros(int(1.0 * SR), np.float32))
    return np.concatenate(parts), segs


def babble(n, rng, talkers=6):
    clips = [trim(read_wav(p)) for p in sorted(TTS.glob("babble_*.wav"))]
    clips = [c for c in clips if len(c) > 0.3 * SR]  # English voices cannot read Chinese text
    out = np.zeros(n, np.float32)
    for _ in range(talkers):
        pos = int(rng.uniform(-1.0, 0.0) * SR)
        while pos < n:
            c = clips[rng.integers(len(clips))]
            c = c / (np.sqrt(np.mean(c ** 2)) + 1e-9)
            a, b = max(pos, 0), min(pos + len(c), n)
            if b > a:
                out[a:b] += c[a - pos: b - pos] * rng.uniform(0.6, 1.0)
            pos += len(c) + int(rng.uniform(0.05, 0.4) * SR)
    # crude room colouring: short exponential smear so distractors sound further away
    k = np.exp(-np.arange(int(0.03 * SR)) / (0.008 * SR)).astype(np.float32)
    return np.convolve(out, k / k.sum(), mode="same")


def pink(n, rng):
    white = rng.standard_normal(n)
    f = np.fft.rfft(white)
    scale = 1.0 / np.sqrt(np.maximum(np.arange(len(f)), 1))
    return np.fft.irfft(f * scale, n).astype(np.float32)


def cafe(n, rng):
    b = babble(n, rng, talkers=8)
    p = pink(n, rng)
    out = b / (np.std(b) + 1e-9) + 0.5 * p / (np.std(p) + 1e-9)
    tt = np.arange(int(0.15 * SR)) / SR
    for _ in range(int(n / SR * 0.7)):  # clinks, ~0.7 per second
        f0 = rng.uniform(2000, 4500)
        clink = np.sin(2 * np.pi * f0 * tt) * np.exp(-tt * 40) * rng.uniform(2, 5)
        s = rng.integers(0, n - len(tt))
        out[s:s + len(tt)] += clink.astype(np.float32)
    return out


def mix(speech, noise, segs, snr_db):
    mask = np.zeros(len(speech), bool)
    for s in segs:
        mask[int(s["start"] * SR): int(s["end"] * SR)] = True
    ps = np.mean(speech[mask] ** 2)
    pn = np.mean(noise[mask] ** 2) + 1e-12
    noise = noise * np.sqrt(ps / (pn * 10 ** (snr_db / 10)))
    y = speech + noise
    return y / max(1.0, np.max(np.abs(y)) / 0.95)


def main():
    spec = json.loads((HERE / "dialogs.json").read_text(encoding="utf-8"))
    for d in spec["dialogs"]:
        rng = np.random.default_rng(sum(map(ord, d["id"])))
        speech, segs = assemble(d, rng)
        variants = [("clean", None, speech)]
        for kind, gen in (("babble", babble), ("cafe", cafe)):
            noise = gen(len(speech), rng)
            for snr in SNRS:
                variants.append((kind, snr, mix(speech, noise, segs, snr)))
        for kind, snr, audio in variants:
            name = d["id"] if kind == "clean" else f"{d['id']}_{kind}_snr{snr}"
            write_wav(WAVS / f"{name}.wav", audio)
            truth = {"id": name, "dialog": d["id"], "language": d["language"], "noise": kind,
                     "snr_db": snr, "duration": round(len(audio) / SR, 3), "segments": segs}
            TRUTH.mkdir(exist_ok=True)
            (TRUTH / f"{name}.json").write_text(json.dumps(truth, ensure_ascii=False, indent=1), encoding="utf-8")
            print(f"{name}: {len(audio) / SR:.1f}s, {len(segs)} turns")


if __name__ == "__main__":
    main()
