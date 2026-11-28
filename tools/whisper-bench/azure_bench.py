"""Cloud speech API reference: Azure AI Speech, real-time conversation transcription (zh-TW).

Uses ConversationTranscriber, which returns text with speaker labels (Guest-1, Guest-2 ...) in
real time. Audio is pushed in 100 ms chunks paced by the wall clock, like a live microphone.
Free F0 tier: 5 audio hours per month. Same files as cloud_bench.py.

Key and region come from datasets/azure.env (git-ignored):
    AZURE_SPEECH_KEY=...
    AZURE_SPEECH_REGION=eastasia
"""
import argparse
import itertools
import json
import pathlib
import threading
import time
import wave

import azure.cognitiveservices.speech as speechsdk

from bench import FILLERS, edit, tokens, tw
from cloud_bench import files

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent
SR = 16000
CHUNK = SR // 10 * 2


def speaker_accuracy(lines, segments):
    """Same idea as :desktop: best one-to-one mapping of labels, weighted by tokens and time overlap."""
    truths = sorted({s["speaker"] for s in segments})
    hyps = sorted({l["speaker"] for l in lines if l["speaker"]})
    w, total = {}, 0.0
    for l in lines:
        n = len(tokens(tw(l["text"])))
        ov = [max(0.0, min(l["end"], s["end"] + 1.0) - max(l["start"], s["start"] - 0.3)) for s in segments]
        if n == 0 or sum(ov) <= 0:
            continue
        total += n
        if not l["speaker"]:
            continue
        for s, o in zip(segments, ov):
            if o > 0:
                w[(l["speaker"], s["speaker"])] = w.get((l["speaker"], s["speaker"]), 0) + n * o / sum(ov)
    if total == 0:
        return None
    best = 0.0
    for perm in itertools.permutations(truths + [None] * len(hyps), len(hyps)):
        best = max(best, sum(w.get((h, t), 0) for h, t in zip(hyps, perm) if t))
    return best / total


def run_one(key, region, d, name):
    truth = json.loads((d / "truth" / f"{name}.json").read_text(encoding="utf-8"))
    with wave.open(str(d / "wavs" / f"{name}.wav"), "rb") as w:
        pcm = w.readframes(w.getnframes())
    cfg = speechsdk.SpeechConfig(subscription=key, region=region)
    cfg.speech_recognition_language = "zh-TW"
    cfg.set_property(speechsdk.PropertyId.SpeechServiceResponse_DiarizeIntermediateResults, "true")
    stream = speechsdk.audio.PushAudioInputStream(speechsdk.audio.AudioStreamFormat(samples_per_second=SR, bits_per_sample=16, channels=1))
    tr = speechsdk.transcription.ConversationTranscriber(speech_config=cfg, audio_config=speechsdk.audio.AudioConfig(stream=stream))
    events, lines, done = [], [], threading.Event()
    t0 = [0.0]

    def on_partial(e):
        events.append((time.perf_counter() - t0[0], "partial"))

    def on_final(e):
        now = time.perf_counter() - t0[0]
        if e.result.text:
            start = e.result.offset / 1e7
            lines.append({"text": e.result.text, "speaker": e.result.speaker_id if e.result.speaker_id != "Unknown" else None,
                          "start": start, "end": start + e.result.duration / 1e7})
            events.append((now, "final"))

    tr.transcribing.connect(on_partial)
    tr.transcribed.connect(on_final)
    tr.session_stopped.connect(lambda e: done.set())
    tr.canceled.connect(lambda e: done.set())
    tr.start_transcribing_async().get()
    t0[0] = time.perf_counter()
    for i in range(0, len(pcm), CHUNK):
        due = t0[0] + (i + CHUNK) / 2 / SR
        wait = due - time.perf_counter()
        if wait > 0:
            time.sleep(wait)
        stream.write(pcm[i:i + CHUNK])
    stream.close()
    done.wait(timeout=60)
    tr.stop_transcribing_async().get()

    hyp_text = "".join(l["text"] for l in lines)
    ref = [x for s in truth["segments"] for x in tokens(tw(s["text"]))]
    hyp = tokens(tw(hyp_text))
    ttfp, fin = [], []
    for sg in truth["segments"]:
        first = [t for t, _ in events if sg["start"] <= t <= sg["end"] + 3]
        fdone = [t for t, k in events if k == "final" and t >= sg["end"]]
        if first:
            ttfp.append(first[0] - sg["start"])
        if fdone:
            fin.append(fdone[0] - sg["end"])
    labelled = not any(s["speaker"] == "?" for s in truth["segments"])
    return {"id": name, "dur": len(pcm) / 2 / SR, "ref": len(ref), "err": edit(ref, hyp),
            "err_nofill": edit([x for x in ref if x not in FILLERS], [x for x in hyp if x not in FILLERS]),
            "ref_nofill": len([x for x in ref if x not in FILLERS]), "ttfp": ttfp, "final": fin,
            "speaker_acc": speaker_accuracy(lines, truth["segments"]) if labelled else None,
            "speakers": len({l["speaker"] for l in lines if l["speaker"]}), "text": tw(hyp_text),
            "lines": [{"text": tw(l["text"]), "speaker": l["speaker"]} for l in lines]}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--parallel", type=int, default=1)  # the free F0 tier allows one stream at a time
    args = ap.parse_args()
    env = dict(l.split("=", 1) for l in (REPO / "datasets" / "azure.env").read_text(encoding="utf-8").split() if "=" in l)
    key, region = env["AZURE_SPEECH_KEY"].strip(), env["AZURE_SPEECH_REGION"].strip()
    todo, results, lock = files(), [], threading.Lock()

    def worker():
        while True:
            with lock:
                if not todo:
                    return
                d, name = todo.pop(0)
            try:
                r = run_one(key, region, d, name)
            except Exception as e:
                r = {"id": name, "error": str(e)[:300]}
            with lock:
                results.append(r)
                print(r["id"], r.get("err", "ERR"), "/", r.get("ref"), r.get("speaker_acc"), r.get("error", ""), flush=True)

    threads = [threading.Thread(target=worker) for _ in range(args.parallel)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    out = HERE / "results" / "azure.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print("wrote", out, f"{sum(r.get('dur', 0) for r in results) / 60:.1f} audio minutes")


if __name__ == "__main__":
    main()
