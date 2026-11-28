"""Cloud speech API reference: Google Speech-to-Text V2 (Chirp 3), streaming, Taiwan Mandarin.

Audio is streamed in real time (100 ms chunks, paced by the wall clock) exactly like a live
microphone, so the reported latencies are what a user would see over the network from this PC.
Costs money (about US$0.016 per audio minute); the file list is kept small on purpose.

    gcloud auth login            # the script borrows the gcloud access token
    .venv/Scripts/python cloud_bench.py --project tony-494414
"""
import argparse
import json
import pathlib
import subprocess
import threading
import time
import wave

from google.api_core.client_options import ClientOptions
from google.cloud.speech_v2 import SpeechClient
from google.cloud.speech_v2.types import cloud_speech as cs
from google.oauth2.credentials import Credentials

from bench import FILLERS, edit, tokens, tw

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SR = 16000
CHUNK = SR // 10 * 2  # 100 ms of 16-bit mono


def files():
    prepared = REPO / "datasets" / "prepared"
    out = [(prepared, f"cvtw_{i:02d}") for i in range(16)]
    out += [(prepared, "yttw_dXpko-DEWlA"), (prepared, "yttw_1dy6ME0FEB4")]
    out += [(REPO / "testdata", n) for n in ("zh_clinic", "zh_family_dinner", "zh_meeting", "zh_codeswitch")]
    out += [(REPO / "datasets" / "noise_bench", f"cvtw_00__{k}_{s}dB") for k in ("babble", "restaurant", "tv") for s in (10, 5, 0)]
    return out


def run_one(client, recognizer, d, name):
    truth = json.loads((d / "truth" / f"{name}.json").read_text(encoding="utf-8"))
    with wave.open(str(d / "wavs" / f"{name}.wav"), "rb") as w:
        pcm = w.readframes(w.getnframes())
    dur = len(pcm) / 2 / SR
    config = cs.StreamingRecognitionConfig(
        config=cs.RecognitionConfig(
            explicit_decoding_config=cs.ExplicitDecodingConfig(
                encoding=cs.ExplicitDecodingConfig.AudioEncoding.LINEAR16, sample_rate_hertz=SR, audio_channel_count=1),
            language_codes=["cmn-Hant-TW"],
            model="chirp_3",
        ),
        streaming_features=cs.StreamingRecognitionFeatures(interim_results=True),
    )
    t0 = time.perf_counter()

    def requests():
        yield cs.StreamingRecognizeRequest(recognizer=recognizer, streaming_config=config)
        for i in range(0, len(pcm), CHUNK):
            due = t0 + (i + CHUNK) / 2 / SR
            wait = due - time.perf_counter()
            if wait > 0:
                time.sleep(wait)
            yield cs.StreamingRecognizeRequest(audio=pcm[i:i + CHUNK])

    finals, events, last = [], [], ""
    for resp in client.streaming_recognize(requests=requests()):
        now = time.perf_counter() - t0
        for r in resp.results:
            if not r.alternatives:
                continue
            text = r.alternatives[0].transcript
            if r.is_final:
                finals.append({"text": text})
                events.append((now, "final"))
                last = ""
            elif text != last:
                events.append((now, "partial"))
                last = text
    hyp_text = "".join(f["text"] for f in finals)
    ref = [x for s in truth["segments"] for x in tokens(tw(s["text"]))]
    hyp = tokens(tw(hyp_text))
    # Per truth turn: first new text after the person starts talking, and the first final result
    # after they stop (the API may finish several turns in one result).
    ttfp, fin = [], []
    for sg in truth["segments"]:
        first = [t for t, _ in events if sg["start"] <= t <= sg["end"] + 3]
        done = [t for t, k in events if k == "final" and t >= sg["end"]]
        if first:
            ttfp.append(first[0] - sg["start"])
        if done:
            fin.append(done[0] - sg["end"])
    return {"id": name, "dur": dur, "ref": len(ref), "err": edit(ref, hyp),
            "err_nofill": edit([x for x in ref if x not in FILLERS], [x for x in hyp if x not in FILLERS]),
            "ref_nofill": len([x for x in ref if x not in FILLERS]), "ttfp": ttfp, "final": fin, "text": tw(hyp_text), "finals": [tw(f["text"]) for f in finals]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--project", required=True)
    ap.add_argument("--location", default="us")  # chirp_3 cmn-Hant-TW is not offered in asia-southeast1 any more
    ap.add_argument("--parallel", type=int, default=6)
    args = ap.parse_args()
    token = subprocess.run("gcloud auth print-access-token", shell=True, capture_output=True, text=True).stdout.strip()
    client = SpeechClient(credentials=Credentials(token),
                          client_options=ClientOptions(api_endpoint=f"{args.location}-speech.googleapis.com"))
    recognizer = f"projects/{args.project}/locations/{args.location}/recognizers/_"
    todo = files()
    results, lock = [], threading.Lock()

    def worker():
        while True:
            with lock:
                if not todo:
                    return
                d, name = todo.pop(0)
            try:
                r = run_one(client, recognizer, d, name)
            except Exception as e:  # keep going; report the failure
                r = {"id": name, "error": str(e)[:300]}
            with lock:
                results.append(r)
                print(r["id"], r.get("err", "ERR"), "/", r.get("ref"), r.get("error", ""), flush=True)

    threads = [threading.Thread(target=worker) for _ in range(args.parallel)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    out = HERE / "results" / "chirp3.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    minutes = sum(r.get("dur", 0) for r in results) / 60
    print(f"wrote {out}; {minutes:.1f} audio minutes ≈ US${minutes * 0.016:.2f}")


if __name__ == "__main__":
    main()
