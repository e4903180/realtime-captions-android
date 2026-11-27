# Engine comparison on Taiwan-accent speech (phone, own GPU, cloud APIs)

2026-11-27. Same Taiwan-accent test files and scoring as [benchmark-real.md](benchmark-real.md): CER after conversion to
Traditional with Taiwan phrasing (OpenCC s2twp), punctuation ignored. Latency is measured the same way for every engine:
audio is streamed in real time and we record when text would appear on screen.

| Engine | Runs on | Common Voice zh-TW (16 two-speaker sessions) | YouTube CC talks | NTU lectures | zh-TW TTS dialogs | First text p50 (s) | Line finished p50 (s) | Speaker tags | Cost |
|---|---|---|---|---|---|---|---|---|---|
| **X-ASR 160 ms + VAD + CAM++** (app default) | phone, offline | 11.7% | 13.6% | 13.7% | 1.6% | **1.00** | **0.67** | yes, 84% | free |
| Breeze-ASR-25 (Taiwan-tuned Whisper) | own GPU (RTX 4070, 6.8 GB VRAM) | 12.9% stream / 8.8% offline | 8.3% / 7.6% | **4.9%** | 0.6% | 1.24 | 0.94 | no | own PC |
| Whisper large-v3 | own GPU (6.9 GB) | 13.1% / 11.6% | 8.5% / 8.4% | 7.3% | 0.3% | 1.16 | 0.85 | no | own PC |
| Whisper large-v3-turbo | own GPU (3.7 GB) | 13.4% / 12.9% | 9.4% / 8.6% | 8.4% | 0.3% | 1.10 | 0.77 | no | own PC |
| Google Chirp 3 (cmn-Hant-TW, US region) | cloud API | 8.0% | 8.2% (2 clips) | – | 4.0% | 3.68 | 1.78 | no | ~US$0.96 / audio hour |
| Google V1 `default` (zh-TW) | cloud API | 21.1% | 16.8% (2 clips) | – | 3.1% | 0.58 | 7.23 | no | ~US$1.44 / audio hour |
| **Azure AI Speech** (zh-TW, East Asia, ConversationTranscriber) | cloud API | **5.1%** | 11.1% (2 clips) | – | 1.6% | 1.27 | 0.84 | **yes, 97%** | ~US$1 + 0.30 (speaker tags) / audio hour; 5 h/month free |

Notes
- Whisper-family "stream" is pseudo-streaming (VAD regions, partial every 1 s, re-decoding at most the last 15 s), the
  realistic way to use them for live captions; "offline" is the whole file at once (best case).
- YouTube latency is not reported for any engine: subtitle cue times are editorial, not speech times.
- Cloud APIs were run on a smaller subset to limit cost (Common Voice, 2 YouTube clips, TTS dialogs, 9 noisy files);
  total spent: Google about US$1.30, Azure free tier.
- Speaker tags: only the phone engine and Azure label speakers. The phone engine's 84% on Common Voice is with the
  1.5 s "new speaker" rule; see the sweep in the devlog for the trade-off.

## Noise (one Common Voice session, `cvtw_00`, mixed with real noise)

This session is hard for everyone (clean CER: X-ASR 34%, Breeze 34%, large-v3 33%, turbo 29%, Azure 17%, Chirp 14%,
Google V1 47%). Cells are CER; Whisper-family values are pseudo-streaming.

| Noise | X-ASR (phone) | Breeze-ASR-25 | Whisper large-v3 | turbo | Chirp 3 | Google V1 | Azure |
|---|---|---|---|---|---|---|---|
| restaurant 10 dB | 59% | 58% | 57% | 65% | 53% | 53% | 54% |
| restaurant 5 dB | 65% | 60% | 70% | 77% | 69% | 57% | 55% |
| restaurant 0 dB | 75% | 81% | 75% | 84% | 81% | 68% | 61% |
| babble 10 dB | 75% | 151% | 57% | 67% | 52% | 57% | 142% |
| babble 5 dB | 92% | 105% | 94% | 122% | 70% | 60% | 146% |
| tv 10 dB | 123% | 105% | 86% | 93% | 89% | 65% | 128% |

Over 100% means the engine also wrote down the background talkers (babble, TV), which every engine does to some degree:
none of them can tell "the person talking to me" from "other people talking". Whisper-family models also hallucinate
long repeated text in loud babble.

## What this means

1. **Phone default stays X-ASR.** It is the fastest to show text (1.0 s), works offline and free, and tags speakers.
   On quiet Taiwan read speech it is behind Azure (5%) and Chirp (8%), and on real talks/lectures it is behind the
   Whisper family (5–9%).
2. **Best quality: Azure** on Taiwan read speech (5.1%) with good speaker tags (97%) and similar latency (1.3 s / 0.8 s).
   It is the strongest candidate for a paid "cloud" option. Chirp 3 is accurate but too slow (3.7 s to first text);
   Google V1 is fast but inaccurate.
3. **Own-GPU server: Breeze-ASR-25** for lectures and long talks (4.9% on NTU lectures, 7.6–8.3% on YouTube talks), but it
   needs ~7 GB VRAM, has no speaker tags, and is not better than X-ASR on short read sentences in pseudo-streaming mode.
   turbo is the light option (3.7 GB) with similar talk accuracy.
4. **Noise and other voices are the shared weak spot.** No engine is usable in loud babble or with a TV talking; the
   next step for everyone is to show only the nearby main speaker (loudness + voice profile), see benchmark-noise.md.
