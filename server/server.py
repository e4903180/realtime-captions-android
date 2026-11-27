"""Home caption server: streams phone audio to Breeze-ASR-25 / Whisper on a local GPU.

The phone connects over WebSocket, sends 16 kHz mono 16-bit PCM in binary frames (any size,
typically 100 ms), and receives JSON text frames:

    {"type": "partial", "text": "...", "start": 3.2}      # may still change
    {"type": "final",   "text": "...", "start": 3.2, "end": 5.9}
    {"type": "error",   "message": "..."}

Pseudo-streaming, as measured in docs/benchmark-engines.md: Silero VAD finds speech; while
someone talks the current stretch is re-transcribed every second (at most the last 15 s), and
0.4 s after they stop it is transcribed once more and sent as final.

Auth: every connection must send the shared token as the first text frame
({"token": "..."}) - set CAPTIONS_TOKEN before starting.

    pip install -r server/requirements.txt
    CAPTIONS_TOKEN=... python server/server.py --model breeze --port 8765
"""
import argparse
import asyncio
import json
import os
import time

import numpy as np
import websockets
from faster_whisper import WhisperModel
from faster_whisper.vad import VadOptions, get_speech_timestamps
from opencc import OpenCC

SR = 16000
STEP = 1.0          # seconds between partial updates
WINDOW = 15.0       # at most this much audio is re-decoded for a partial
PAUSE = 0.4         # silence that ends a line
MAX_LINE = 20.0     # force a line end after this long

MODELS = {
    "breeze": "models/breeze-asr-25-ct2",
    "turbo": "mobiuslabsgmbh/faster-whisper-large-v3-turbo",
    "large-v3": "Systran/faster-whisper-large-v3",
}
S2TWP = OpenCC("s2twp")


class Session:
    """One phone connection: an audio buffer and the line being spoken."""

    def __init__(self, model):
        self.model = model
        self.audio = np.zeros(0, np.float32)
        self.offset = 0.0           # audio time of audio[0]
        self.line_start = None      # audio time where the current line began
        self.last_partial = 0.0

    def transcribe(self, a, b):
        seg = self.audio[int((a - self.offset) * SR):int((b - self.offset) * SR)]
        if len(seg) < SR // 4:
            return ""
        out, _ = self.model.transcribe(seg, language="zh", beam_size=1, condition_on_previous_text=False)
        return S2TWP.convert("".join(s.text for s in out).strip())

    def push(self, pcm16: bytes):
        """Adds audio; returns messages to send."""
        x = np.frombuffer(pcm16, np.int16).astype(np.float32) / 32768
        self.audio = np.concatenate([self.audio, x])
        now = self.offset + len(self.audio) / SR
        msgs = []
        # Speech regions in the recent audio: the current line plus a little, never more than 30 s.
        span = 3.0 if self.line_start is None else min(30.0, now - self.line_start + 1.0)
        look = self.audio[-int(span * SR):]
        base = now - len(look) / SR
        regions = get_speech_timestamps(look, VadOptions(min_silence_duration_ms=int(PAUSE * 1000), speech_pad_ms=100))
        speaking = bool(regions) and (base + regions[-1]["end"] / SR) > now - PAUSE
        if self.line_start is None and speaking:
            self.line_start = base + regions[-1]["start"] / SR
        if self.line_start is not None:
            ended = (not speaking) or (now - self.line_start > MAX_LINE)
            if ended:
                end = base + regions[-1]["end"] / SR if regions else now
                text = self.transcribe(self.line_start, end)
                if text:
                    msgs.append({"type": "final", "text": text, "start": round(self.line_start, 2), "end": round(end, 2)})
                self.line_start = None
                self.last_partial = 0.0
                # drop audio that is done
                keep = int(max(0.0, (now - 1.0) - self.offset) * SR)
                self.audio = self.audio[keep:]
                self.offset += keep / SR
            elif now - self.last_partial >= STEP:
                text = self.transcribe(max(self.line_start, now - WINDOW), now)
                self.last_partial = now
                if text:
                    msgs.append({"type": "partial", "text": text, "start": round(self.line_start, 2)})
        else:
            # nothing to say: keep only the last few seconds
            keep = max(0, len(self.audio) - 3 * SR)
            self.audio = self.audio[keep:]
            self.offset += keep / SR
        return msgs


async def handler(ws, model, token, lock):
    try:
        hello = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
    except Exception:
        await ws.close(code=4000, reason="expected token")
        return
    if not token or hello.get("token") != token:
        await ws.send(json.dumps({"type": "error", "message": "token rejected"}))
        await ws.close(code=4001, reason="bad token")
        return
    await ws.send(json.dumps({"type": "ready"}))
    session = Session(model)
    pending = bytearray()
    async for msg in ws:
        if isinstance(msg, str):
            continue
        pending += msg
        if len(pending) < SR // 5 * 2:   # decode in >= 200 ms steps
            continue
        if lock.locked() and len(pending) < SR * 2:  # GPU busy: gather more audio instead of queueing
            continue
        chunk, pending = bytes(pending), bytearray()
        async with lock:  # one GPU: decode one session at a time
            out = await asyncio.to_thread(session.push, chunk)
        for m in out:
            await ws.send(json.dumps(m, ensure_ascii=False))


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="breeze", choices=list(MODELS))
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--device", default="cuda")
    args = ap.parse_args()
    token = os.environ.get("CAPTIONS_TOKEN", "")
    if not token:
        raise SystemExit("set CAPTIONS_TOKEN (any long random string; the phone needs the same value)")
    path = MODELS[args.model]
    if path.startswith("models/"):
        path = os.path.join(os.path.dirname(__file__), "..", "tools", "whisper-bench", path)
    model = WhisperModel(path, device=args.device, compute_type="float16" if args.device == "cuda" else "int8")
    lock = asyncio.Lock()
    print(f"captions server: {args.model} on {args.device}, ws://{args.host}:{args.port}", flush=True)
    async with websockets.serve(lambda ws: handler(ws, model, token, lock), args.host, args.port, max_size=2**22, ping_interval=30, ping_timeout=60):
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
