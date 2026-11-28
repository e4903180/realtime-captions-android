# 說話者字母：即時分群 vs 事後重新分組

產生方式：`:desktop --speaker-recluster`（2026-11-28）。離線引擎 X-ASR 160 ms＋CAM++，句尾靜音 0.6 秒，4 執行緒，100 ms 音訊區塊。

- **Live only：** 只用即時分群（不重新分組）。
- **End regroup：** 錄音結束後重新分組一次（舊版評測的作法）。
- **App regroup：** 跟 App 一樣，每句結束都重新分組，再把結果交回即時分群（ADR-027）。只有這種會出現「已顯示的字母又被改掉」。
- **Letter changes：** 已經顯示在畫面上的句子，字母被改掉的次數（每個檔案平均）。

結論（ADR-036）：門檻 0.45 → 0.60。中文 TTS：App 模式說話者正確率 87.2% → 93.2%，字母變更 0.4 → 0 次／段；英文 TTS 93.1% → 89.5%。

## TTS 對話（testdata，zh 20 檔、en 10 檔）

### 修改前（舊 core）

| Setup | Dataset | Files | Speaker accuracy | Speakers found / true (avg) | Letter changes on shown lines (per file) |
|---|---|---|---|---|---|
| Live only | en | 10 | 87.4% | 2.3 / 2.5 | 0.0 |
| Live only | zh | 20 | 92.1% | 2.1 / 2.3 | 0.0 |
| End regroup 0.45 | en | 10 | 89.1% | 2.5 / 2.5 | 0.0 |
| End regroup 0.45 | zh | 20 | 89.8% | 2.0 / 2.3 | 0.0 |
| App regroup 0.45 | en | 10 | 93.1% | 2.5 / 2.5 | 0.1 |
| App regroup 0.45 | zh | 20 | 87.2% | 2.0 / 2.3 | 0.4 |
| End regroup 0.55 | en | 10 | 91.6% | 2.5 / 2.5 | 0.0 |
| End regroup 0.55 | zh | 20 | 91.5% | 2.2 / 2.3 | 0.0 |
| App regroup 0.55 | en | 10 | 91.2% | 2.4 / 2.5 | 0.1 |
| App regroup 0.55 | zh | 20 | 93.2% | 2.3 / 2.3 | 0.0 |
| End regroup 0.6 | en | 10 | 92.1% | 2.5 / 2.5 | 0.0 |
| End regroup 0.6 | zh | 20 | 92.3% | 2.2 / 2.3 | 0.0 |
| App regroup 0.6 | en | 10 | 89.5% | 2.5 / 2.5 | 0.0 |
| App regroup 0.6 | zh | 20 | 93.2% | 2.3 / 2.3 | 0.0 |
| End regroup 0.65 | en | 10 | 89.7% | 2.5 / 2.5 | 0.0 |
| End regroup 0.65 | zh | 20 | 93.2% | 2.2 / 2.3 | 0.0 |
| App regroup 0.65 | en | 10 | 89.2% | 2.5 / 2.5 | 0.0 |
| App regroup 0.65 | zh | 20 | 93.2% | 2.3 / 2.3 | 0.0 |
| End regroup 0.7 | en | 10 | 85.8% | 2.8 / 2.5 | 0.0 |
| End regroup 0.7 | zh | 20 | 92.0% | 2.3 / 2.3 | 0.0 |
| App regroup 0.7 | en | 10 | 90.8% | 2.6 / 2.5 | 0.0 |
| App regroup 0.7 | zh | 20 | 93.8% | 2.3 / 2.3 | 0.1 |
| App regroup 0.55, number of people given | en | 10 | 90.7% | 2.5 / 2.5 | 0.0 |
| App regroup 0.55, number of people given | zh | 20 | 91.9% | 2.3 / 2.3 | 0.3 |

### 修改後（ADR-036 的 core：只重新分組這一輪、舊句子凍結、依第一次顯示的標籤分配字母）

| Setup | Dataset | Files | Speaker accuracy | Speakers found / true (avg) | Letter changes on shown lines (per file) |
|---|---|---|---|---|---|
| Live only | en | 10 | 87.4% | 2.3 / 2.5 | 0.0 |
| Live only | zh | 20 | 92.1% | 2.1 / 2.3 | 0.0 |
| End regroup 0.45 | en | 10 | 89.1% | 2.5 / 2.5 | 0.0 |
| End regroup 0.45 | zh | 20 | 89.8% | 2.0 / 2.3 | 0.0 |
| App regroup 0.45 | en | 10 | 93.1% | 2.5 / 2.5 | 0.1 |
| App regroup 0.45 | zh | 20 | 87.2% | 2.0 / 2.3 | 0.4 |
| End regroup 0.55 | en | 10 | 91.6% | 2.5 / 2.5 | 0.0 |
| End regroup 0.55 | zh | 20 | 91.5% | 2.2 / 2.3 | 0.0 |
| App regroup 0.55 | en | 10 | 91.2% | 2.4 / 2.5 | 0.1 |
| App regroup 0.55 | zh | 20 | 93.2% | 2.3 / 2.3 | 0.0 |
| End regroup 0.6 | en | 10 | 92.1% | 2.5 / 2.5 | 0.0 |
| End regroup 0.6 | zh | 20 | 92.3% | 2.2 / 2.3 | 0.0 |
| App regroup 0.6 | en | 10 | 89.5% | 2.5 / 2.5 | 0.0 |
| App regroup 0.6 | zh | 20 | 93.2% | 2.3 / 2.3 | 0.0 |
| End regroup 0.65 | en | 10 | 89.7% | 2.5 / 2.5 | 0.0 |
| End regroup 0.65 | zh | 20 | 93.2% | 2.2 / 2.3 | 0.0 |
| App regroup 0.65 | en | 10 | 89.2% | 2.5 / 2.5 | 0.0 |
| App regroup 0.65 | zh | 20 | 93.2% | 2.3 / 2.3 | 0.0 |
| End regroup 0.7 | en | 10 | 85.8% | 2.8 / 2.5 | 0.0 |
| End regroup 0.7 | zh | 20 | 92.0% | 2.3 / 2.3 | 0.0 |
| App regroup 0.7 | en | 10 | 90.8% | 2.6 / 2.5 | 0.1 |
| App regroup 0.7 | zh | 20 | 93.8% | 2.3 / 2.3 | 0.1 |
| App regroup 0.55, number of people given | en | 10 | 90.7% | 2.5 / 2.5 | 0.2 |
| App regroup 0.55, number of people given | zh | 20 | 91.3% | 2.2 / 2.3 | 0.1 |

TTS 對話沒有跨輪次和短句，所以修改前後在同一門檻下的數字幾乎相同；差別主要來自門檻。

## 真實錄音（datasets/prepared，--quick）

量測中，完成後補上。

## 模擬器（Pixel 8，debug wav 家庭晚餐，正確 A B C A B C）

| 版本 | 畫面最後的字母 | 已顯示的字母被改 | 不清除再跑一次 |
|---|---|---|---|
| 修改前（main 484de0f） | A B A A B A | 第 3 句 C→A；舊句子 A→C→A 兩次 | 舊句子被改 |
| 修改後（門檻 0.60） | A B C A B C | 0 次 | 新一輪 A B C A B C，舊句子不動 |
