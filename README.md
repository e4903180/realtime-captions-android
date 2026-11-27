# 即時字幕 (Real-time captions)

An Android app that turns speech into live captions, made so a deaf or hard-of-hearing person can follow a conversation.
Mandarin (Traditional Chinese) comes first. English and Taiwanese come next.

## Design goals

- **Speed first, quality close behind.** Words appear as they are recognized, without waiting for the end of a sentence.
- **Easy to read.** Large, adjustable text on a black background. The current sentence is split into settled words (yellow) and words that may still change (gray), so the reader knows what is final.
- **Online and offline.** The recognition engine can be swapped (see `SpeechEngine`).
- **Portable.** Recognition contracts and text clean-up live in `:core`, a pure Kotlin module with no Android dependency, so they can be reused on other devices such as smart glasses.

## Modules

| Module | What it holds |
| --- | --- |
| `core` | `SpeechEngine` interface, `CaptionAssembler` (partial/final results → caption state, stable/tentative split), `TextProcessor` (whitespace, full-width punctuation, correction dictionary, Simplified→Traditional hook), `StreamingPipeline` (streaming recognizer + VAD endpointing + speaker tags) and `SpeakerClusterer` (online speaker clustering) |
| `app` | Android UI (Jetpack Compose) and engines: `AndroidSpeechEngine` (the phone's built-in recognizer, online or on-device) and `SherpaOnnxEngine` (fully offline, with speaker tags; models are downloaded on first use) |
| `desktop` | The same sherpa-onnx pipeline on the JVM, plus a benchmark that streams test wavs and writes [docs/benchmark.md](docs/benchmark.md) |
| `testdata` | Synthetic multi-speaker dialogs (Windows TTS) with ground truth, and noisy variants |

## Build

```bash
./gradlew :core:test :desktop:test :app:assembleDebug
```

The sherpa-onnx Android library and JVM jars come from GitHub releases; Gradle downloads them and checks their SHA-256 (cached in `~/.cache/realtime-captions`).

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions also builds it on every push and attaches it to the run as `captions-debug-apk`.

「離線」(built-in recognizer) needs the Chinese (Taiwan) offline speech pack installed on the phone (Android 12+ works best).
「離線＋說話者」(sherpa-onnx) needs no language pack: it downloads about 165 MB of models the first time it starts, then works without internet.

## Offline engine (sherpa-onnx)

- Streaming zipformer transducer, zh + en with punctuation, 160 ms chunks, int8 (X-ASR); output converted to Traditional (Taiwan) with OpenCC.
- Silero VAD ends a caption line about 0.4 s after the speaker stops, instead of waiting for the recognizer's own end-of-utterance rule.
- 3D-Speaker CAM++ embeddings per line, clustered online into anonymous speakers A, B, C…; partial lines get a provisional tag after 1 s of speech.
- Results on the synthetic test set: [docs/benchmark.md](docs/benchmark.md).

### Testing without a microphone (debug builds)

```bash
adb push testdata/wavs/zh_family_dinner.wav /sdcard/Android/data/tw.captions.app/files/
adb shell am start -n tw.captions.app/.MainActivity --es engine sherpa-onnx --es debug_wav zh_family_dinner.wav --ez autostart true
adb logcat -s CaptionsSherpa   # one line per finished caption, with its speaker tag
```

The wav (16 kHz mono 16-bit) is played in real time instead of the microphone. Pass `--es debug_wav ""` to go back to the microphone.

### Benchmark

```bash
pwsh -File testdata/generate.ps1                       # Windows: TTS dialogs + noise
./gradlew :desktop:run --args="--models <model dir>"   # writes docs/benchmark.md
```

The model directory holds the unpacked sherpa-onnx model archives (see `AsrModel` / `SpeakerModel` in `desktop`).

## Roadmap

1. ✅ Project skeleton, pluggable engine, built-in Android recognizer (online and offline)
2. ✅ On-device model (sherpa-onnx) with speaker tags, benchmarked on latency first; streaming cloud engine (Google Cloud Speech-to-Text) still to do
3. More text clean-up: OpenCC Traditional conversion, user word list, segmenting at pauses
4. Real-world testing and an installable release APK
