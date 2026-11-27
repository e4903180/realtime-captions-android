# 即時字幕 iPhone 版

SwiftUI 畫面＋共用的 Kotlin 核心（`../core`，Kotlin Multiplatform）＋ sherpa-onnx 離線引擎。設計決策見 [ADR-017、018](../docs/decisions.md)。

## 目前功能

- 離線即時字幕（X-ASR 160 ms、Silero VAD、CAM++ 說話者 A／B／C），和 Android 版同一組模型與設定
- 第一次按「開始」時下載模型（約 163 MB），之後不用網路
- 簡體輸出轉台灣繁體用語（和 Android 版同一批 OpenCC 字典）
- 大字、黑底，已確定（黃）／還會變（灰）分色，字體大小 A−／A+，清除
- 收音時螢幕不會自動關閉；鎖定畫面後也會繼續收音（背景音訊）

## 需要什麼

- 一台 Mac，裝 Xcode（建議 16 以上；需要 macOS 14.5 以上）、JDK 17、[XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）
- iPhone，iOS 16 以上
- 一個 Apple ID（免費即可）

## 在 Mac 上建置

```bash
ios/scripts/build-core.sh          # 共用核心 → core/build/XCFrameworks/release/CaptionsCore.xcframework
ios/scripts/fetch-deps.sh          # sherpa-onnx、onnxruntime、OpenCC 字典（檢查 SHA-256）→ ios/Vendor/
cd ios && xcodegen generate        # 產生 Captions.xcodeproj
open Captions.xcodeproj
```

`ios/scripts/ci-test.sh` 會跑和 GitHub Actions 一樣的完整檢查（核心測試、真機建置、模擬器測試）。

## 用免費 Apple ID 裝到 iPhone

1. Xcode → Settings → Accounts → 按「+」加入你的 Apple ID。下面會出現一個「(Personal Team)」。
2. 複製 `ios/Config/Signing.local.xcconfig.example` 成 `Signing.local.xcconfig`，填入 Team ID（10 個字元，在 Accounts 頁選 Personal Team 後可以看到），並把 bundle ID 改成只屬於你的名字，例如 `tw.captions.ios.tony`。這個檔不會上傳。
3. 重新 `xcodegen generate`，用 USB 線把 iPhone 接到 Mac，在 iPhone 上按「信任這部電腦」。
4. iPhone：設定 → 隱私權與安全性 → 開發者模式 → 打開（會重新開機）。
5. Xcode 上方選你的 iPhone，按 ▶︎ 執行。
6. 第一次開啟時，iPhone：設定 → 一般 → VPN 與裝置管理 → 選你的 Apple ID → 信任。

免費 Apple ID 的限制：App 每 7 天會過期，要接上 Mac 再按一次 ▶︎；同時最多 3 個自己簽的 App。
