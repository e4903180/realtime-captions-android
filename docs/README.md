# 文件索引

| 文件 | 內容 |
| --- | --- |
| [requirements.md](requirements.md) | 產品需求與限制：為誰做、要解決什麼、優先順序 |
| [decisions.md](decisions.md) | 技術決策紀錄（ADR）：做了什麼選擇、為什麼、放棄了哪些選項 |
| [devlog/](devlog/) | 開發日誌，依日期記錄做了什麼、遇到的問題和怎麼解決 |
| [roadmap.md](roadmap.md) | 路線圖：已完成、進行中、之後要做的 |
| benchmark.md / benchmark-noise.md | 辨識準確度、延遲、說話者分辨、噪音測試的數據（由 `:desktop` 產生） |

## 維護規則

- 每完成一個階段，或做出一個會影響之後方向的決定，就更新 `devlog/` 和 `decisions.md`。
- 決策一旦寫下就不刪除；改變方向時新增一條，並在舊的那條標註「已被 ADR-xxx 取代」。
- 數字都要附上來源（哪個測資、哪個 commit 或哪份 benchmark）。
