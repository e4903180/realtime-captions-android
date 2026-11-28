# 自訂詞彙（熱詞）：離線 X-ASR

產生方式：`:desktop --hotwords-test`（2026-11-28），設定與 Android App 相同（0.6 秒斷句、接行、20 毫秒、4 核心）。

- **人名地名測資：** 20 句含台灣人名、地名、店名的句子，3 種 Windows TTS 聲音，共 60 段（`testdata/hotwords/names.json`，用 `generate_names.ps1` 產生到 `datasets/hotwords_names`，音檔不進 git）。
- **詞彙：** 50 個詞，其中 12 個從來沒出現，用來檢查會不會亂插。
- **延遲：** 4 段乾淨的 zh-TW TTS 對話。

## 結論（ADR-038）

詞彙要先轉成簡體、再把每個字用空格分開，才會有效。X-ASR 輸出簡體字，而且它的 BPE 一個中文字一個 token（「▁林」）。

| 寫法 | 編碼結果 |
|---|---|
| 照打（繁體）「鶯歌」 | `▁` ＋ unknown |
| 只轉簡體「林怡君」 | `▁林` ＋ unknown |
| 簡體＋空格「林 怡 君」 | `▁林 ▁怡 ▁君`（正確） |

| | 一般（App 原本） | 加詞彙（簡體、逐字、分數 4.0） |
|---|---|---|
| 人名地名錯字率 | 13.5% | 8.2% |
| 詞彙認對 | 43% | 74% |
| 亂插的詞 | 0 | 0 |

延遲差約 +0.08 秒，在誤差範圍內。

另外，手機上要自己產生 `bpe.vocab`（模型壓縮檔裡沒有）。分數要照 `bpe.model` 的順序：`<unk>` 之前都是 0，之後依序遞減。全部填 0 的話，「▁」和「▁林」分數一樣，字會被拆錯，詞彙等於沒用。這個問題是在模擬器上實測時發現的。

## 第一輪：各種寫法（電腦同時跑其他測試，延遲與 RTF 不準）

| Decoding | CER (names) | Vocabulary words right | Vocabulary words inserted wrongly | CER (dialogs) | First text p50 (s) | Line final p50 (s) | RTF |
|---|---|---|---|---|---|---|---|
| Greedy (app today) | 13.5% | 49 / 114 (43.0%) | 0 | 1.6% | 27.07 | 33.91 | 3.37 |
| Beam search, no hotwords | 14.2% | 49 / 114 (43.0%) | 0 | 1.6% | 0.93 | 1.06 | 3.34 |
| Hotwords as typed (Traditional), bpe, score 1.5 | 13.8% | 52 / 114 (45.6%) | 0 | 1.2% | 0.85 | 0.96 | 0.67 |
| Hotwords → Simplified, bpe, score 1.5 | 13.8% | 52 / 114 (45.6%) | 0 | 1.2% | 0.86 | 0.99 | 0.63 |
| Hotwords → Simplified, cjkchar+bpe, score 1.5 | 14.2% | 49 / 114 (43.0%) | 0 | 1.6% | 0.81 | 0.89 | 0.62 |
| Hotwords → Simplified, bpe, score 2.5 | 13.6% | 52 / 114 (45.6%) | 0 | 1.2% | 0.82 | 0.86 | 0.64 |
| Hotwords → Simplified, bpe, score 4.0 | 13.4% | 52 / 114 (45.6%) | 0 | 1.2% | 0.80 | 0.88 | 0.49 |

## 第二輪：簡體＋逐字、不同分數

| Decoding | CER (names) | Vocabulary words right | Vocabulary words inserted wrongly | CER (dialogs) | First text p50 (s) | Line final p50 (s) | RTF |
|---|---|---|---|---|---|---|---|
| Greedy (app today) | 13.5% | 49 / 114 (43.0%) | 0 | 1.6% | 0.89 | 1.05 | 0.69 |
| Hotwords → Simplified, one token per character, score 1.5 | 10.4% | 74 / 114 (64.9%) | 0 | 1.2% | 0.88 | 1.04 | 0.72 |
| Hotwords → Simplified, one token per character, score 2.5 | 9.5% | 78 / 114 (68.4%) | 0 | 1.2% | 0.93 | 1.08 | 0.82 |
| Hotwords → Simplified, one token per character, score 4.0 | 8.2% | 84 / 114 (73.7%) | 0 | 1.2% | 0.97 | 1.07 | 0.80 |

## 模擬器（Pixel 8，debug wav，8 句人名地名）

| | 第 2 句 | 第 5 句 |
|---|---|---|
| 沒有詞彙 | 林宜君…投糞車站 | 黃渤漢…蚊蛋 |
| 有詞彙（修正 bpe.vocab 後） | 林怡君…頭份車站 | 黃柏翰…蚊蛋 |

有些詞加了還是沒認對：這個聲音的「文旦」還是「蚊蛋」、「鶯歌」還是「英哥」。
