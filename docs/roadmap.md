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

- 台灣腔真實語音、噪音驗證、Whisper／Breeze（自己的 4070）、Google Chirp 3／V1、Azure 對照（benchmark-real／noise／engines.md，ADR-014、015）

## 進行中

- 雲端金鑰設定頁＋Azure 引擎

- 說話者門檻取捨（短句多長才可以建立新的說話者：0.6／1.0／1.5 秒）

## 接下來

- 「只顯示近處主要說話者」：用音量和聲紋過濾電視、背景人聲造成的假字幕
- Azure 付費雲端選項（有說話者標籤）；自架伺服器（4070＋Breeze）給課程、演講用
- 台灣地名、用語的修正字典或熱詞

- 雲端／自架辨識伺服器（Docker、WebSocket 串流、faster-whisper 或台灣調校模型、台語）
- 文字優化：使用者自訂詞彙、依停頓分段、LLM 修正標點與用語

## 之後的功能

- SRT 字幕匯出、Notion 筆記、重點摘要
- 結合投影片或講義（Docling、MarkItDown）
- 多語即時翻譯
- 智慧眼鏡等其他裝置

## iOS（iPhone）版（Android 版穩定後再做）

- 架構：把 `:core`（字幕組裝、文字處理、說話者分群）改成 Kotlin Multiplatform 模組，讓 Android 和 iOS 共用。iOS 畫面用 SwiftUI 做，跟 Android 版同樣是黑底大字。
- 離線辨識：sherpa-onnx 有 iOS 版，能用同一組模型（X-ASR、Silero VAD、CAM++）。
- 雲端引擎：Azure Speech SDK、Google 都有 iOS 版。家用伺服器用同一個 WebSocket 協定。
- iOS 的限制：背景收音要開 audio 背景模式；系統內建辨識（SFSpeechRecognizer）沒有分人功能。
- 需要準備（2026-11-27 tony 選定免費方式）：
  - tony 的 Mac 裝 Xcode。Mac 目前只剩約 34 GB，Xcode 和 iOS 模擬器需要 15 到 25 GB，安裝前要先清出空間。
  - 免費的 Apple ID 即可，不用付 99 美元的開發者帳號。
  - 一支測試用的 iPhone，用 USB 連到 Mac 安裝。
- 安裝方式：用 Mac 上的 Xcode 直接安裝到 iPhone。免費帳號裝的 App 每 7 天要重新安裝一次，也不能用 TestFlight 分享給別人。之後如果要給更多人用，再改用付費帳號的 TestFlight 或 App Store。

## 發布（已排入，先不做）

1. 正式簽章版 APK
2. Google Play 內部測試，之後轉為公開上架
3. 備案：Firebase App Distribution，或直接傳 APK
