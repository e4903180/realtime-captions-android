# 路線圖

最後更新：2026-11-28

## 已完成

- 專案骨架：`:core`（引擎介面、字幕組裝、文字處理）與 `:app`（Compose 字幕畫面）
- ~~手機內建語音辨識引擎~~（已依使用者要求移除）
- 字幕畫面：大字、高對比、已確定／未確定字分色、說話者標籤與顏色、字體大小調整、螢幕常亮
- 摺疊螢幕支援、前台服務（避免 ColorOS 在背景停止收音）
- 麥克風音量條與「沒有聽到聲音」提示
- GitHub Actions：核心測試＋編譯 debug APK

- 離線引擎「離線＋說話者」（sherpa-onnx：X-ASR 160 ms、Silero VAD、CAM++ 說話者分群），模型首次使用時下載（PR #3）
- 桌機 benchmark 工具（`:desktop`）與 TTS 多人對話測資；11 組設定比較（[benchmark.md](benchmark.md)）
- debug 版「wav 當麥克風」，可在模擬器上自動驗證字幕和說話者標籤

- 台灣腔真實語音、噪音驗證、Whisper／Breeze（自己的 4070）、Google Chirp 3／V1、Azure 對照（benchmark-real／noise／engines.md，ADR-014、015）

- iPhone 版第一步：`:core` 改成 Kotlin Multiplatform；SwiftUI App 骨架，sherpa-onnx 離線引擎、模型下載、台灣用語轉換；GitHub Actions macOS 建置與模擬器端對端測試（ADR-017、018，[ios/README.md](../ios/README.md)）

- iPhone 版裝到實機；Azure 雲端引擎與引擎選單（ADR-023）；同一人短停頓接在同一行（ADR-024）；App 圖示

- 引擎清單分「離線／雲端」，每個模型可下載或移除；雲端金鑰設定頁＋Azure 引擎（ADR-019）
- 說話者門檻 1.0 秒（ADR-016）
- 延遲加速：20 毫秒聲音區塊＋4 核心（ADR-021，[latency.md](latency.md)）
- 背景過濾（關／淡化／隱藏，ADR-020）與認聲音：長按字幕指定名字（ADR-022，[benchmark-voices.md](benchmark-voices.md)）

- 兩平台對齊（2026-11-28）：雲端用量畫面與每月費用上限（ADR-028、030）、逐字稿紀錄與 .txt／.srt 下載（ADR-029）、深色／淺色（ADR-031）、看電視模式（ADR-032）、接行、說話者重新分組、說話人數與說話者辨識開關（ADR-024、027、033）

## 進行中（2026-11-28 檢視後重排，先修正確性和分人，再加新功能）

- 檢視找到的問題修正（逐字稿存檔、畫面更新次數、Azure 斷線訊息、字體大小）
- 分人：即時分群與重新分組同步、接行時聲紋依長度加權、聲紋不重複計算

## 接下來

- 整句重聽（ADR-025）：選模型，接上兩個平台
- 熱詞：離線引擎與 Azure 詞彙清單；台灣地名、用語；句尾標點與數字
- 認得的人頁面（改名、合併、忘記）、AI 校稿（ADR-026）
- 把「一次字幕工作階段」的邏輯（用量上限、逐字稿、重新分組）搬到共用核心並補測試；Android 辨識搬進前台服務
- 麥克風選擇（藍牙／外接）、系統降噪開關實測、斷網自動改用離線
- 一句話中間換人說話的偵測；用真實多人對話錄音當分人固定測資
- 自架伺服器（4070＋Breeze）、台語：暫緩

## 之後的功能

- Notion 筆記、重點摘要
- 結合投影片或講義（Docling、MarkItDown）
- 多語即時翻譯
- 智慧眼鏡等其他裝置

## iOS（iPhone）版（2026-11-27 開始，見 ADR-017）

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
