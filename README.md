# 即時字幕 (Real-time captions)

Android 和 iPhone 的即時字幕 App：把身邊的人說的話即時變成字幕，用看的就能跟上對話。
以台灣口音國語為主（輸出台灣繁體與台灣用語），可以中英夾雜。

## 設計目標

- **速度第一，品質緊接在後。** 字在說話當下逐字出現，不等整句講完。
- **好讀。** 大字、可調整、高對比；深色／淺色跟隨系統。正在說的句子分成已確定（黃）和可能還會變（灰）的字。
- **分得出誰在說話。** 說話者 A、B、C 用字母和顏色標出，可以長按命名（認聲音）。
- **離線和雲端兩種引擎**（見 `SpeechEngine`）：
  - 離線＋說話者：sherpa-onnx，在手機上跑，免費、不用網路。
  - Azure 雲端：使用者自己的金鑰，比較準；失敗或超過費用上限時自動改用離線。
- **可移植。** 辨識介面和文字處理放在 `:core`（Kotlin Multiplatform），可以用在其他裝置，例如智慧眼鏡。

給使用者看的：[使用說明（含截圖）](docs/user-guide.md)、[Android 安裝說明](docs/install-android.md)。

需求見 [docs/requirements.md](docs/requirements.md)，進度與下一步見 [docs/roadmap.md](docs/roadmap.md)，技術決策見 [docs/decisions.md](docs/decisions.md)。

## 模組

| 模組 | 內容 |
| --- | --- |
| `core` | Kotlin Multiplatform（JVM 給 Android／桌機，iOS 給 iPhone）。`SpeechEngine` 介面、`CaptionAssembler`（中間／定稿結果 → 字幕狀態、已確定／未確定、接行、重新分組）、`TextProcessor`（空白、全形標點、校正字典、簡轉繁）、`StreamingPipeline`（串流辨識＋人聲偵測斷句＋說話者標籤）、`SpeakerClusterer`（即時說話者分群） |
| `app` | Android 畫面（Jetpack Compose）與引擎：`SherpaOnnxEngine`（離線，有說話者標籤；X-ASR 160 ms 預設、480 ms 省電；模型可下載或移除）和 `AzureSpeechEngine`（使用者自己的 Azure 金鑰，加密存放） |
| `ios` | iPhone App（SwiftUI），用同一個核心和同一組 sherpa-onnx 模型；見 [ios/README.md](ios/README.md) |
| `desktop` | 同一套 sherpa-onnx 流程跑在 JVM 上，以及 benchmark（寫出 [docs/benchmark.md](docs/benchmark.md) 等報告） |
| `testdata` | 合成多人對話（Windows TTS）與標準答案、噪音版本；真實語音的下載與轉換腳本 |
| `tools/whisper-bench` | Whisper／Breeze、Google、Azure 的對照測試（Python） |

## 建置

```bash
./gradlew :core:jvmTest :desktop:test :app:assembleDebug
```

sherpa-onnx 的 Android 函式庫和 JVM jar 從 GitHub releases 下載並檢查 SHA-256（快取在 `~/.cache/realtime-captions`，可用 `CAPTIONS_CACHE` 改位置）。

APK 在 `app/build/outputs/apk/debug/`。GitHub Actions 每次推送都會建置，附在執行結果的 `captions-debug-apk`，lint 報告附在 `lint-report`。

「離線＋說話者」第一次開始時下載約 165 MB 模型，之後完全離線。

## 離線引擎（sherpa-onnx）

- 串流 zipformer transducer，中英雙語含標點，160 ms 區塊，int8（X-ASR）；輸出用 OpenCC 轉台灣繁體用語。
- Silero VAD 在說話停 0.6 秒後結束一行；同一個人 1.5 秒內接著說，就接在同一行（ADR-024）。
- 每行算 3D-Speaker CAM++ 聲紋，即時分群成 A、B、C…；說完後再用全部句子重新分組（ADR-027）。
- 每 20 毫秒送一次聲音、4 核心（ADR-021）。

### 不用麥克風測試（debug 版）

```bash
adb push testdata/wavs/zh_family_dinner.wav /sdcard/Android/data/tw.captions.app/files/
adb shell am start -n tw.captions.app/.MainActivity --es engine sherpa-onnx --es debug_wav zh_family_dinner.wav --ez autostart true
adb logcat -s CaptionsSherpa   # 每句定稿一行，含說話者標籤
```

wav（16 kHz 單聲道 16-bit）會照實際時間播放，取代麥克風。傳 `--es debug_wav ""` 改回麥克風。

### Benchmark

```bash
pwsh -File testdata/generate.ps1                       # Windows：TTS 對話＋噪音
./gradlew :desktop:run --args="--models <模型資料夾>"    # 寫出 docs/benchmark.md
```

預設用 Android App 出貨的設定（停頓 0.6 秒、接行 1.5 秒、20 毫秒、4 核心），報告開頭會寫出設定。
`--legacy-config` 改回 2026-11-28 以前報告用的設定（0.4 秒、不接行、100 毫秒、2 核心）；也可以用 `--vad-end`、`--merge-gap`（0 = 不接行）、`--chunk-ms`、`--threads` 個別調整。
錯字率同時列原始值和數字正規化後的值（「一百二十」和「120」算一樣）。

模型資料夾放解開的 sherpa-onnx 模型壓縮檔（見 `desktop` 的 `AsrModel`／`SpeakerModel`）。
