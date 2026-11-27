# 路線圖

最後更新：2026-11-27

## 已完成

- 專案骨架：`:core`（引擎介面、字幕組裝、文字處理）與 `:app`（Compose 字幕畫面）
- 手機內建語音辨識引擎（連線／離線），自動連續聆聽
- 字幕畫面：大字、高對比、已確定／未確定字分色、說話者標籤與顏色、字體大小調整、螢幕常亮
- 摺疊螢幕支援、前台服務（避免 ColorOS 在背景停止收音）
- 麥克風音量條與「沒有聽到聲音」提示
- GitHub Actions：核心測試＋編譯 debug APK

- 離線引擎「離線＋說話者」（sherpa-onnx：X-ASR 160 ms、Silero VAD、CAM++ 說話者分群），模型首次使用時下載（PR #3）
- 桌機 benchmark 工具（`:desktop`）與 TTS 多人對話測資；11 組設定比較（[benchmark.md](benchmark.md)）
- debug 版「wav 當麥克風」，可在模擬器上自動驗證字幕和說話者標籤

## 進行中

- 台灣口音真實語音 benchmark（Common Voice zh-TW、台大課程、YouTube CC 對談；中國口音對照）→ benchmark-real.md
- 噪音驗證（真實噪音、SNR 20 到 -5 dB、GTCRN／DPDFNet 降噪比較、純噪音假字幕）→ benchmark-noise.md，並決定降噪預設值
- Whisper 對照組（large-v3、large-v3-turbo、Breeze-ASR-25，GPU）
- Pixel Fold 模擬器：摺疊／展開時字幕不中斷

## 接下來

- 雲端／自架辨識伺服器（Docker、WebSocket 串流、faster-whisper 或台灣調校模型、台語）
- 文字優化：使用者自訂詞彙、依停頓分段、LLM 修正標點與用語

## 之後的功能

- SRT 字幕匯出、Notion 筆記、重點摘要
- 結合投影片或講義（Docling、MarkItDown）
- 多語即時翻譯
- 智慧眼鏡等其他裝置

## 發布（已排入，先不做）

1. 正式簽章版 APK
2. Google Play 內部測試，之後轉為公開上架
3. 備案：Firebase App Distribution，或直接傳 APK
