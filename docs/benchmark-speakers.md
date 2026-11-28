# 說話者字母：即時分群 vs 事後重新分組

**目前做法（ADR-039）：** 人數「自動」＝只用即時分群；選了人數才重新分組（0.55，人數為上限）。下面最新的表格是這個做法的量測，再往下是之前的紀錄。

## ADR-039 規則（TTS 對話，App 設定）

| Setup | Dataset | Files | Speaker accuracy | Speakers found / true (avg) | Letter changes on shown lines (per file) |
|---|---|---|---|---|---|
| 自動 = live only (new) | en | 10 | 95.2% | 2.5 / 2.5 | 0.0 |
| 自動 = live only (new) | zh | 20 | 92.7% | 2.1 / 2.3 | 0.0 |
| 自動, short lines keep the last letter | en | 10 | 95.2% | 2.5 / 2.5 | 0.0 |
| 自動, short lines keep the last letter | zh | 20 | 92.7% | 2.1 / 2.3 | 0.0 |
| 自動, loudness filter 8 dB | en | 10 | 95.2% | 2.5 / 2.5 | 0.0 |
| 自動, loudness filter 8 dB | zh | 20 | 92.7% | 2.1 / 2.3 | 0.0 |
| 自動, both (before ADR-036) | en | 10 | 95.2% | 2.5 / 2.5 | 0.0 |
| 自動, both (before ADR-036) | zh | 20 | 92.7% | 2.1 / 2.3 | 0.0 |
| Number of people given (new, 0.55) | en | 10 | 93.4% | 2.4 / 2.5 | 0.0 |
| Number of people given (new, 0.55) | zh | 20 | 92.7% | 2.1 / 2.3 | 0.0 |
| Old: regroup 0.60 on 自動 (main before ADR-039) | en | 10 | 95.2% | 2.5 / 2.5 | 0.0 |
| Old: regroup 0.60 on 自動 (main before ADR-039) | zh | 20 | 92.7% | 2.1 / 2.3 | 0.0 |

真實錄音請看下方「修改後」表格：其中「只即時分群」就是新的「自動」，「0.55＋給定人數」就是新的指定人數。cvtw 85.1%／96.3%，aishell 98.8%／98.8%，字母變更都是 0。alimeeting 的分離實驗見下方（補測中）。

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

## 真實錄音（datasets/prepared）

設定與 Android App 相同：0.6 秒斷句、接行、20 毫秒、4 核心。`--quick` 每個資料集取 3 個多人檔案。aishell1 和 cvtw 是兩人輪流朗讀，cvtw 為台灣口音；alimeeting 是 4 人會議。

### 結論

- **沒有一個固定門檻能兼顧：**
  - 台灣 cvtw：0.45 最好（修改後 86.4%）；0.60 會把人拆開（66.3%，找到 5.3 人，實際 2 人）。
  - aishell：0.45 會把兩人併成一人（67.3%）；0.60 正確（98.9%）。
  - TTS 對話則是 0.60 較好（見上方）。
- **給定人數兩邊都最好：** cvtw 96.3%、aishell 98.8%，cvtw 上字幕顯示後字母沒有再被改。
- **只用即時分群：** cvtw 85.1%、aishell 98.8%，字母從不改變。
- **修改後 core 有效：** 就算門檻寬鬆，已顯示的字母也比較少被改。
- **alimeeting 會議（4 人）：** 從修改前約 40% 降到約 28%。兩次之間同時改了兩件事，所以分不出是哪一件造成的：
  - 太短沒有聲紋的句子不再沿用上一個人的字母，算成沒有字母；
  - 音量背景過濾的預設從 8 dB 改成關。
  - 要另外各自量。

### 修改前（舊 core，App 模式 bench）

| Setup | Dataset | Files | Speaker accuracy | Speakers found / true (avg) | Letter changes on shown lines (per file) |
|---|---|---|---|---|---|
| Live only | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| Live only | alimeeting | 3 | 40.7% | 4.7 / 4.0 | 0.0 |
| Live only | cvtw | 3 | 86.8% | 2.3 / 2.0 | 0.0 |
| End regroup 0.45 | aishell1 | 3 | 67.2% | 1.3 / 2.0 | 0.0 |
| End regroup 0.45 | alimeeting | 3 | 37.7% | 6.0 / 4.0 | 0.0 |
| End regroup 0.45 | cvtw | 3 | 92.1% | 2.3 / 2.0 | 0.0 |
| App regroup 0.45 | aishell1 | 3 | 67.3% | 1.3 / 2.0 | 0.7 |
| App regroup 0.45 | alimeeting | 3 | 38.2% | 7.0 / 4.0 | 6.3 |
| App regroup 0.45 | cvtw | 3 | 92.3% | 2.3 / 2.0 | 0.7 |
| End regroup 0.55 | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| End regroup 0.55 | alimeeting | 3 | 41.3% | 7.3 / 4.0 | 0.0 |
| End regroup 0.55 | cvtw | 3 | 75.1% | 4.3 / 2.0 | 0.0 |
| App regroup 0.55 | aishell1 | 3 | 98.7% | 2.0 / 2.0 | 0.0 |
| App regroup 0.55 | alimeeting | 3 | 40.5% | 9.7 / 4.0 | 59.7 |
| App regroup 0.55 | cvtw | 3 | 74.2% | 4.3 / 2.0 | 1.0 |
| End regroup 0.6 | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| End regroup 0.6 | alimeeting | 3 | 38.8% | 7.7 / 4.0 | 0.0 |
| End regroup 0.6 | cvtw | 3 | 67.5% | 5.7 / 2.0 | 0.0 |
| App regroup 0.6 | aishell1 | 3 | 98.9% | 2.0 / 2.0 | 0.0 |
| App regroup 0.6 | alimeeting | 3 | 38.9% | 11.3 / 4.0 | 18.7 |
| App regroup 0.6 | cvtw | 3 | 67.5% | 5.3 / 2.0 | 0.0 |
| End regroup 0.65 | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| End regroup 0.65 | alimeeting | 3 | 37.0% | 11.0 / 4.0 | 0.0 |
| End regroup 0.65 | cvtw | 3 | 68.5% | 6.0 / 2.0 | 0.0 |
| App regroup 0.65 | aishell1 | 3 | 98.9% | 2.0 / 2.0 | 0.0 |
| App regroup 0.65 | alimeeting | 3 | 37.4% | 13.0 / 4.0 | 13.0 |
| App regroup 0.65 | cvtw | 3 | 68.3% | 6.0 / 2.0 | 0.0 |
| End regroup 0.7 | aishell1 | 3 | 98.7% | 2.0 / 2.0 | 0.0 |
| End regroup 0.7 | alimeeting | 3 | 35.5% | 14.7 / 4.0 | 0.0 |
| End regroup 0.7 | cvtw | 3 | 61.3% | 6.7 / 2.0 | 0.0 |
| App regroup 0.7 | aishell1 | 3 | 98.6% | 2.0 / 2.0 | 0.0 |
| App regroup 0.7 | alimeeting | 3 | 34.6% | 21.0 / 4.0 | 6.3 |
| App regroup 0.7 | cvtw | 3 | 60.4% | 6.3 / 2.0 | 0.3 |
| App regroup 0.55, number of people given | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| App regroup 0.55, number of people given | alimeeting | 3 | 42.4% | 4.3 / 4.0 | 11.3 |
| App regroup 0.55, number of people given | cvtw | 3 | 96.3% | 2.3 / 2.0 | 0.0 |

### 修改後（ADR-036 的 core）

| Setup | Dataset | Files | Speaker accuracy | Speakers found / true (avg) | Letter changes on shown lines (per file) |
|---|---|---|---|---|---|
| Live only | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| Live only | alimeeting | 3 | 28.7% | 4.7 / 4.0 | 0.0 |
| Live only | cvtw | 3 | 85.1% | 2.3 / 2.0 | 0.0 |
| End regroup 0.45 | aishell1 | 3 | 67.2% | 1.3 / 2.0 | 0.0 |
| End regroup 0.45 | alimeeting | 3 | 26.6% | 4.7 / 4.0 | 0.0 |
| End regroup 0.45 | cvtw | 3 | 92.1% | 2.0 / 2.0 | 0.0 |
| App regroup 0.45 | aishell1 | 3 | 67.3% | 1.3 / 2.0 | 0.7 |
| App regroup 0.45 | alimeeting | 3 | 26.9% | 7.0 / 4.0 | 14.7 |
| App regroup 0.45 | cvtw | 3 | 86.4% | 2.3 / 2.0 | 0.3 |
| End regroup 0.55 | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| End regroup 0.55 | alimeeting | 3 | 28.2% | 5.7 / 4.0 | 0.0 |
| End regroup 0.55 | cvtw | 3 | 84.4% | 3.3 / 2.0 | 0.0 |
| App regroup 0.55 | aishell1 | 3 | 98.7% | 2.0 / 2.0 | 0.0 |
| App regroup 0.55 | alimeeting | 3 | 27.0% | 11.3 / 4.0 | 12.7 |
| App regroup 0.55 | cvtw | 3 | 77.5% | 4.3 / 2.0 | 0.7 |
| End regroup 0.6 | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| End regroup 0.6 | alimeeting | 3 | 27.8% | 4.7 / 4.0 | 0.0 |
| End regroup 0.6 | cvtw | 3 | 79.7% | 4.0 / 2.0 | 0.0 |
| App regroup 0.6 | aishell1 | 3 | 98.9% | 2.0 / 2.0 | 0.0 |
| App regroup 0.6 | alimeeting | 3 | 25.9% | 12.3 / 4.0 | 18.0 |
| App regroup 0.6 | cvtw | 3 | 66.3% | 5.3 / 2.0 | 0.3 |
| End regroup 0.65 | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| End regroup 0.65 | alimeeting | 3 | 27.5% | 6.0 / 4.0 | 0.0 |
| End regroup 0.65 | cvtw | 3 | 82.5% | 4.0 / 2.0 | 0.0 |
| App regroup 0.65 | aishell1 | 3 | 98.9% | 2.0 / 2.0 | 0.0 |
| App regroup 0.65 | alimeeting | 3 | 25.4% | 15.0 / 4.0 | 26.0 |
| App regroup 0.65 | cvtw | 3 | 66.1% | 6.0 / 2.0 | 0.7 |
| End regroup 0.7 | aishell1 | 3 | 98.7% | 2.0 / 2.0 | 0.0 |
| End regroup 0.7 | alimeeting | 3 | 27.3% | 5.3 / 4.0 | 0.0 |
| End regroup 0.7 | cvtw | 3 | 78.7% | 4.3 / 2.0 | 0.0 |
| App regroup 0.7 | aishell1 | 3 | 98.6% | 2.0 / 2.0 | 0.0 |
| App regroup 0.7 | alimeeting | 3 | 24.2% | 21.0 / 4.0 | 29.7 |
| App regroup 0.7 | cvtw | 3 | 58.1% | 6.3 / 2.0 | 1.0 |
| App regroup 0.55, number of people given | aishell1 | 3 | 98.8% | 2.0 / 2.0 | 0.0 |
| App regroup 0.55, number of people given | alimeeting | 3 | 26.9% | 4.0 / 4.0 | 8.0 |
| App regroup 0.55, number of people given | cvtw | 3 | 96.3% | 2.0 / 2.0 | 0.0 |

### 全量（舊 core、舊 bench：只在檔案最後重新分組一次）

aishell1 16 個、alimeeting 8 個、cvtw 16 個；欄位：說話者正確率、找到人數／實際人數。

| Setup | Dataset | Files | Speaker accuracy | Speakers found / true (avg) |
|---|---|---|---|---|
| Live only (now) | aishell1 | 16 | 99.3% | 2.0 / 2.0 |
| Live only (now) | alimeeting | 8 | 55.2% | 4.1 / 3.1 |
| Live only (now) | cvtw | 16 | 90.6% | 2.3 / 2.0 |
| Regroup, threshold 0.35 | aishell1 | 16 | 58.0% | 1.1 / 2.0 |
| Regroup, threshold 0.35 | alimeeting | 8 | 55.7% | 3.9 / 3.1 |
| Regroup, threshold 0.35 | cvtw | 16 | 97.9% | 2.0 / 2.0 |
| Regroup, threshold 0.45 | aishell1 | 16 | 90.5% | 1.8 / 2.0 |
| Regroup, threshold 0.45 | alimeeting | 8 | 55.3% | 5.0 / 3.1 |
| Regroup, threshold 0.45 | cvtw | 16 | 93.8% | 2.3 / 2.0 |
| Regroup, threshold 0.55 | aishell1 | 16 | 99.3% | 2.0 / 2.0 |
| Regroup, threshold 0.55 | alimeeting | 8 | 56.1% | 7.1 / 3.1 |
| Regroup, threshold 0.55 | cvtw | 16 | 84.5% | 3.5 / 2.0 |
| Regroup, threshold 0.60 | aishell1 | 16 | 99.3% | 2.0 / 2.0 |
| Regroup, threshold 0.60 | alimeeting | 8 | 54.2% | 7.5 / 3.1 |
| Regroup, threshold 0.60 | cvtw | 16 | 79.9% | 4.3 / 2.0 |
| Regroup, threshold 0.65 | aishell1 | 16 | 99.3% | 2.0 / 2.0 |
| Regroup, threshold 0.65 | alimeeting | 8 | 52.8% | 10.8 / 3.1 |
| Regroup, threshold 0.65 | cvtw | 16 | 70.6% | 5.1 / 2.0 |
| Regroup, threshold 0.70 | aishell1 | 16 | 99.3% | 2.0 / 2.0 |
| Regroup, threshold 0.70 | alimeeting | 8 | 51.7% | 13.4 / 3.1 |
| Regroup, threshold 0.70 | cvtw | 16 | 58.6% | 7.1 / 2.0 |
| Regroup, number of people given | aishell1 | 16 | 99.3% | 2.0 / 2.0 |
| Regroup, number of people given | alimeeting | 8 | 51.5% | 4.0 / 3.1 |
| Regroup, number of people given | cvtw | 16 | 97.9% | 2.0 / 2.0 |


## 模擬器（Pixel 8，debug wav 家庭晚餐，正確 A B C A B C）

| 版本 | 畫面最後的字母 | 已顯示的字母被改 | 不清除再跑一次 |
|---|---|---|---|
| 修改前（main 484de0f） | A B A A B A | 第 3 句 C→A；舊句子 A→C→A 兩次 | 舊句子被改 |
| 修改後（門檻 0.60） | A B C A B C | 0 次 | 新一輪 A B C A B C，舊句子不動 |
