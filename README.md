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
| `core` | `SpeechEngine` interface, `CaptionAssembler` (partial/final results → caption state, stable/tentative split), `TextProcessor` (whitespace, full-width punctuation, correction dictionary, Simplified→Traditional hook) |
| `app` | Android UI (Jetpack Compose) and engines. Currently `AndroidSpeechEngine`, which uses the phone's built-in recognizer in online or on-device mode. |

## Build

```bash
./gradlew :core:test :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. GitHub Actions also builds it on every push and attaches it to the run as `captions-debug-apk`.

Offline mode needs the Chinese (Taiwan) offline speech pack installed on the phone (Android 12+ works best).

## Roadmap

1. ✅ Project skeleton, pluggable engine, built-in Android recognizer (online and offline)
2. Streaming cloud engine (Google Cloud Speech-to-Text) and on-device model (sherpa-onnx), compared on latency first
3. More text clean-up: OpenCC Traditional conversion, user word list, segmenting at pauses
4. Real-world testing and an installable release APK
