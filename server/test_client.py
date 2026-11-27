"""Streams a wav file to the caption server in real time and prints what comes back.

    CAPTIONS_TOKEN=... python server/test_client.py testdata/wavs/zh_clinic.wav ws://localhost:8765
"""
import asyncio
import json
import os
import sys
import time
import wave

import websockets


async def main(path, url):
    with wave.open(path) as w:
        pcm = w.readframes(w.getnframes())
    async with websockets.connect(url) as ws:
        await ws.send(json.dumps({"token": os.environ["CAPTIONS_TOKEN"]}))
        print(await ws.recv())
        t0 = time.perf_counter()

        async def reader():
            async for m in ws:
                print(f"{time.perf_counter() - t0:6.2f}s", m, flush=True)

        task = asyncio.create_task(reader())
        step = 3200  # 100 ms
        for i in range(0, len(pcm), step):
            await ws.send(pcm[i:i + step])
            wait = t0 + (i + step) / 32000 - time.perf_counter()
            if wait > 0:
                await asyncio.sleep(wait)
        for _ in range(20):  # 2 s of silence so the last line ends
            await ws.send(bytes(step))
            await asyncio.sleep(0.1)
        await asyncio.sleep(3)
        task.cancel()


asyncio.run(main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "ws://localhost:8765"))
