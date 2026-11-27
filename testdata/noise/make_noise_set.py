"""Builds the noise-robustness benchmark from real recorded noise.

    pip install numpy soundfile soxr pyarrow
    python testdata/noise/make_noise_set.py datasets

Speech (Taiwan accent, clean): the first 4 Common Voice zh-TW two-speaker sessions and the
zh_clinic / zh_family_dinner TTS dialogs (from datasets/prepared and testdata/wavs).

Noise (real recordings unless noted):
  babble      8 overlapping Taiwan-accent talkers (other Common Voice zh-TW speakers, CC0)
  restaurant  DEMAND PRESTO (CC BY-SA 3.0)
  street      DEMAND STRAFFIC (CC BY-SA 3.0)
  kitchen     DEMAND DKITCHEN (CC BY-SA 3.0)
  tv          one Common Voice talker over background music (Kevin MacLeod, CC BY 4.0) at -6 dB
  reverb_cafe speech convolved with a real room impulse response (OpenSLR SLR28, Apache 2.0)
              + DEMAND PCAFETER cafeteria noise
SNR (over speech-active samples): 20, 15, 10, 5, 0, -5 dB.

Noise only (no speech at all), 3 minutes each: babble, tv, music, restaurant, street. Every
caption shown on these is a phantom caption.

Output: datasets/noise_bench/{wavs,truth}, same truth format as testdata/truth.
"""
import io
import json
import pathlib
import sys
import wave

import numpy as np
import soundfile as sf
import soxr

SR = 16000
SNRS = [20, 15, 10, 5, 0, -5]
HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent


def read(path, channel=0):
    x, sr = sf.read(str(path), dtype="float32", always_2d=True)
    x = x[:, channel]
    return soxr.resample(x, sr, SR).astype(np.float32) if sr != SR else x


def write(path, x):
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def loop_to(x, n, rng):
    start = int(rng.integers(0, max(1, len(x) - 1)))
    reps = int(np.ceil((n + start) / len(x))) + 1
    return np.tile(x, reps)[start:start + n]


def cv_utterances(root):
    """Common Voice zh-TW utterances by speaker (same filtering as prepare_real.py)."""
    import pyarrow.parquet as pq

    t = pq.read_table(root / "cv_zh_tw" / "test-00000.parquet", columns=["client_id", "audio"])
    by = {}
    for cid, audio in zip(t.column("client_id").to_pylist(), t.column("audio").to_pylist()):
        by.setdefault(cid, []).append(audio["bytes"])
    return by


def decode_mp3(data):
    x, sr = sf.read(io.BytesIO(data), dtype="float32", always_2d=True)
    return soxr.resample(x[:, 0], sr, SR).astype(np.float32)


def babble(n, voices, rng, talkers=8):
    out = np.zeros(n, np.float32)
    for k in range(talkers):
        utts = voices[k % len(voices)]
        pos = int(rng.uniform(-2, 0) * SR)
        while pos < n:
            x = decode_mp3(utts[int(rng.integers(len(utts)))])
            x = x / (np.sqrt(np.mean(x ** 2)) + 1e-9)
            a, b = max(pos, 0), min(pos + len(x), n)
            if b > a:
                out[a:b] += x[a - pos:b - pos] * rng.uniform(0.5, 1.0)
            pos += len(x) + int(rng.uniform(0.1, 0.6) * SR)
    return out


def tv(n, voice, music, rng):
    talk = np.zeros(n, np.float32)
    pos = 0
    while pos < n:
        x = decode_mp3(voice[int(rng.integers(len(voice)))])
        b = min(pos + len(x), n)
        talk[pos:b] = x[:b - pos] / (np.sqrt(np.mean(x ** 2)) + 1e-9)
        pos += len(x) + int(rng.uniform(0.2, 0.5) * SR)
    m = loop_to(music, n, rng)
    m = m / (np.sqrt(np.mean(m ** 2)) + 1e-9)
    return talk + m * 10 ** (-6 / 20)


def mix(speech, noise, segs, snr):
    mask = np.zeros(len(speech), bool)
    for s in segs:
        mask[int(s["start"] * SR):int(s["end"] * SR)] = True
    ps = np.mean(speech[mask] ** 2)
    pn = np.mean(noise[mask] ** 2) + 1e-12
    y = speech + noise * np.sqrt(ps / (pn * 10 ** (snr / 10)))
    return y / max(1.0, np.max(np.abs(y)) / 0.95)


def main():
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "datasets")
    out = root / "noise_bench"
    noise_dir = root / "noise"
    rng = np.random.default_rng(7)

    # Clean Taiwan-accent speech with ground truth.
    bases = []
    for name in ["cvtw_00", "cvtw_01", "cvtw_02", "cvtw_03"]:
        bases.append((name, root / "prepared" / "wavs" / f"{name}.wav", root / "prepared" / "truth" / f"{name}.json"))
    for name in ["zh_clinic", "zh_family_dinner"]:
        bases.append((name, REPO / "testdata" / "wavs" / f"{name}.wav", REPO / "testdata" / "truth" / f"{name}.json"))

    # Noise sources.
    cv = cv_utterances(root)
    # Babble and TV talkers: speakers ranked 40+ by utterance count, so none of them is a target speaker.
    others = [u for c, u in sorted(cv.items(), key=lambda kv: -len(kv[1]))[40:] if len(u) >= 5]
    music = [read(p) for p in sorted((noise_dir / "music").glob("*.mp3"))]
    demand = lambda env: read(noise_dir / "demand" / env / "ch01.wav")
    rirs = sorted((noise_dir / "RIRS_NOISES" / "real_rirs_isotropic_noises").glob("*rir*.wav"))
    rir = read(rirs[len(rirs) // 2]) if rirs else None
    if rir is not None:
        rir = rir[np.argmax(np.abs(rir)):][: int(0.8 * SR)]
        rir = rir / (np.sqrt(np.sum(rir ** 2)) + 1e-9)

    def noise_for(kind, n):
        if kind == "babble":
            return babble(n, others[:8], rng)
        if kind == "restaurant":
            return loop_to(demand("PRESTO"), n, rng)
        if kind == "street":
            return loop_to(demand("STRAFFIC"), n, rng)
        if kind == "kitchen":
            return loop_to(demand("DKITCHEN"), n, rng)
        if kind == "tv":
            return tv(n, others[9], music[int(rng.integers(len(music)))], rng)
        if kind == "reverb_cafe":
            return loop_to(demand("PCAFETER"), n, rng)
        if kind == "music":
            return loop_to(music[int(rng.integers(len(music)))], n, rng)
        raise ValueError(kind)

    kinds = ["babble", "restaurant", "street", "kitchen", "tv"] + (["reverb_cafe"] if rir is not None else [])
    for name, wav, truth_file in bases:
        truth = json.loads(truth_file.read_text(encoding="utf-8"))
        speech = read(wav)
        segs = truth["segments"]
        variants = [("clean", None, speech)]
        for kind in kinds:
            s = speech
            if kind == "reverb_cafe":
                s = np.convolve(speech, rir)[: len(speech)].astype(np.float32)
                s = s / (np.max(np.abs(s)) + 1e-9) * np.max(np.abs(speech))
            noise = noise_for(kind, len(speech))
            for snr in SNRS:
                variants.append((kind, snr, mix(s, noise, segs, snr)))
        for kind, snr, audio in variants:
            vid = f"{name}__{kind}" + ("" if snr is None else f"_{snr}dB")
            write(out / "wavs" / f"{vid}.wav", audio)
            t = dict(truth, id=vid, noise=kind, snr_db=snr, duration=round(len(audio) / SR, 3))
            (out / "truth").mkdir(parents=True, exist_ok=True)
            (out / "truth" / f"{vid}.json").write_text(json.dumps(t, ensure_ascii=False, indent=1), encoding="utf-8")
        print(name, len(variants), "variants")

    # Noise only: every caption here is a phantom.
    n = 180 * SR
    for kind in ["babble", "tv", "music", "restaurant", "street"]:
        x = noise_for(kind, n)
        x = 0.1 * x / (np.sqrt(np.mean(x ** 2)) + 1e-9)  # about -20 dBFS, a loud room
        vid = f"noiseonly__{kind}"
        write(out / "wavs" / f"{vid}.wav", np.clip(x, -1, 1))
        t = {"id": vid, "dialog": vid, "language": "zh-TW", "noise": kind, "snr_db": None, "duration": 180.0, "segments": []}
        (out / "truth" / f"{vid}.json").write_text(json.dumps(t, ensure_ascii=False, indent=1), encoding="utf-8")
        print(vid)


if __name__ == "__main__":
    main()
