"""Runs the reference engines on the report clips (build/report/clips) and saves their lines.

Cost: about 3 audio minutes on Google (Chirp 3 + V1) and Azure (free tier).
    tools/whisper-bench/.venv/Scripts/python tools/report/run_engines.py
"""
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "whisper-bench"))

import azure_bench  # noqa: E402
import cloud_bench  # noqa: E402
import google_v1_bench  # noqa: E402
from bench import MODELS, read_wav, stream_run, tw  # noqa: E402

REPO = HERE.parents[1]
CLIPS = REPO / "build" / "report" / "clips"
OUT = REPO / "build" / "report"


def main():
    ids = [p.stem for p in sorted((CLIPS / "wavs").glob("*.wav"))]
    res = {i: {} for i in ids}

    from faster_whisper import WhisperModel
    for name in ["breeze-asr-25", "whisper-large-v3-turbo"]:
        model = WhisperModel(MODELS[name], device="cuda", compute_type="float16",
                             download_root=str(HERE.parent / "whisper-bench" / "models" / "hf"))
        for i in ids:
            utts, _ = stream_run(model, read_wav(CLIPS / "wavs" / f"{i}.wav"))
            res[i][name] = [{"text": tw(u["text"]), "speaker": None} for u in utts if u["text"]]
        del model
        print(name, "done", flush=True)

    token = subprocess.run("gcloud auth print-access-token", shell=True, capture_output=True, text=True).stdout.strip()
    from google.api_core.client_options import ClientOptions
    from google.cloud import speech_v1
    from google.cloud.speech_v2 import SpeechClient
    from google.oauth2.credentials import Credentials
    chirp = SpeechClient(credentials=Credentials(token), client_options=ClientOptions(api_endpoint="us-speech.googleapis.com"))
    v1 = speech_v1.SpeechClient(credentials=Credentials(token, quota_project_id="tony-494414"))
    env = dict(l.split("=", 1) for l in (REPO / "datasets" / "azure.env").read_text(encoding="utf-8").split() if "=" in l)
    for i in ids:
        r = cloud_bench.run_one(chirp, "projects/tony-494414/locations/us/recognizers/_", CLIPS, i)
        res[i]["google-chirp3"] = [{"text": r["text"], "speaker": None}] if r["text"] else []
        r = google_v1_bench.run_one(v1, "default", CLIPS, i)
        res[i]["google-v1"] = [{"text": r["text"], "speaker": None}] if r["text"] else []
        r = azure_bench.run_one(env["AZURE_SPEECH_KEY"].strip(), env["AZURE_SPEECH_REGION"].strip(), CLIPS, i)
        res[i]["azure"] = r["lines"]
        print(i, "cloud done", flush=True)
    (OUT / "engines.json").write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()
