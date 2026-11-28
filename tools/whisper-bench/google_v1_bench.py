"""Google Speech-to-Text V1 streaming (older, faster models) for comparison with Chirp 3.

V1 "latest_long" / "default" models stream interim results with much lower delay than Chirp 3
and are served from the global endpoint (nearest region). ~US$0.016-0.024 per audio minute.
    .venv/Scripts/python google_v1_bench.py --model latest_long
"""
import argparse
import json
import pathlib
import subprocess
import threading
import time
import wave

from google.cloud import speech_v1 as speech
from google.oauth2.credentials import Credentials

from bench import FILLERS, edit, tokens, tw
from cloud_bench import files

HERE = pathlib.Path(__file__).resolve().parent
SR = 16000
CHUNK = SR // 10 * 2


def run_one(client, model, d, name):
    truth = json.loads((d / "truth" / f"{name}.json").read_text(encoding="utf-8"))
    with wave.open(str(d / "wavs" / f"{name}.wav"), "rb") as w:
        pcm = w.readframes(w.getnframes())
    cfg = speech.StreamingRecognitionConfig(
        config=speech.RecognitionConfig(encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16, sample_rate_hertz=SR,
                                        language_code="zh-TW", model=model, enable_automatic_punctuation=True),
        interim_results=True)
    t0 = time.perf_counter()

    def reqs():
        for i in range(0, len(pcm), CHUNK):
            wait = t0 + (i + CHUNK) / 2 / SR - time.perf_counter()
            if wait > 0:
                time.sleep(wait)
            yield speech.StreamingRecognizeRequest(audio_content=pcm[i:i + CHUNK])

    finals, events, last = [], [], ""
    for resp in client.streaming_recognize(cfg, reqs()):
        now = time.perf_counter() - t0
        for r in resp.results:
            if not r.alternatives:
                continue
            text = r.alternatives[0].transcript
            if r.is_final:
                finals.append(text)
                events.append((now, "final"))
                last = ""
            elif text != last:
                events.append((now, "partial"))
                last = text
    hyp_text = "".join(finals)
    ref = [x for s in truth["segments"] for x in tokens(tw(s["text"]))]
    hyp = tokens(tw(hyp_text))
    ttfp, fin = [], []
    for sg in truth["segments"]:
        first = [t for t, _ in events if sg["start"] <= t <= sg["end"] + 3]
        done = [t for t, k in events if k == "final" and t >= sg["end"]]
        if first:
            ttfp.append(first[0] - sg["start"])
        if done:
            fin.append(done[0] - sg["end"])
    return {"id": name, "dur": len(pcm) / 2 / SR, "ref": len(ref), "err": edit(ref, hyp),
            "err_nofill": edit([x for x in ref if x not in FILLERS], [x for x in hyp if x not in FILLERS]),
            "ref_nofill": len([x for x in ref if x not in FILLERS]), "ttfp": ttfp, "final": fin, "text": tw(hyp_text), "finals": [tw(f) for f in finals]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="latest_long")
    ap.add_argument("--project", default="tony-494414")
    ap.add_argument("--parallel", type=int, default=6)
    args = ap.parse_args()
    token = subprocess.run("gcloud auth print-access-token", shell=True, capture_output=True, text=True).stdout.strip()
    client = speech.SpeechClient(credentials=Credentials(token, quota_project_id=args.project))
    todo, results, lock = files(), [], threading.Lock()

    def worker():
        while True:
            with lock:
                if not todo:
                    return
                d, name = todo.pop(0)
            try:
                r = run_one(client, args.model, d, name)
            except Exception as e:
                r = {"id": name, "error": str(e)[:300]}
            with lock:
                results.append(r)
                print(r["id"], r.get("err", "ERR"), "/", r.get("ref"), r.get("error", ""), flush=True)

    ts = [threading.Thread(target=worker) for _ in range(args.parallel)]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    out = HERE / "results" / f"google_v1_{args.model}.json"
    out.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out, f"{sum(r.get('dur', 0) for r in results) / 60:.1f} audio minutes")


if __name__ == "__main__":
    main()
