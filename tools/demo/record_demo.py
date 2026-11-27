"""Records a demo video of the app on an emulator: screen + the audio it is captioning.

The debug build plays a wav file instead of the microphone (see README), so the audio track is
exactly that wav, lined up with the screen recording using the device clock.

    python tools/demo/record_demo.py testdata/wavs/zh_family_dinner.wav build/demo/zh_family_dinner.mp4
Needs adb on PATH or ANDROID_HOME, and ffmpeg (pip install imageio-ffmpeg).
"""
import os
import pathlib
import re
import subprocess
import sys
import time
import wave

import imageio_ffmpeg

ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"
FILES = "/sdcard/Android/data/tw.captions.app/files"


def adb(*args, **kw):
    return subprocess.run([ADB, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", **kw).stdout


def main():
    wav, out = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    engine = sys.argv[3] if len(sys.argv) > 3 else "sherpa-onnx"
    with wave.open(str(wav)) as w:
        dur = w.getnframes() / w.getframerate()
    adb("push", str(wav), f"{FILES}/{wav.name}")
    adb("shell", "am", "start", "-n", "tw.captions.app/.MainActivity", "--es", "debug_wav", "")  # stop + reset
    adb("logcat", "-c")
    limit = int(dur + 8)
    rec = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", str(limit), "--bit-rate", "6000000", "/sdcard/demo.mp4"])
    time.sleep(0.5)
    rec_start = float(adb("shell", "date", "+%s.%N").strip()) - 0.5
    adb("shell", "am", "start", "-n", "tw.captions.app/.MainActivity", "--es", "engine", engine,
        "--es", "debug_wav", wav.name, "--ez", "autostart", "true")
    rec.wait()
    time.sleep(1)
    # When did the app start playing the wav? (logcat epoch time of the "wav input" line)
    log = adb("logcat", "-d", "-v", "epoch", "-s", "CaptionsAudio:I")
    m = re.search(r"^\s*(\d+\.\d+).*wav input", log, re.M)
    offset = (float(m.group(1)) - rec_start) if m else 1.5
    out.parent.mkdir(parents=True, exist_ok=True)
    raw = out.with_suffix(".screen.mp4")
    adb("pull", "/sdcard/demo.mp4", str(raw))
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    subprocess.run([ff, "-y", "-loglevel", "error", "-i", str(raw), "-itsoffset", f"{max(0.0, offset):.3f}", "-i", str(wav),
                    "-map", "0:v", "-map", "1:a", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", str(out)], check=True)
    raw.unlink()
    print(f"{out} (audio offset {offset:.2f} s)")


if __name__ == "__main__":
    main()
