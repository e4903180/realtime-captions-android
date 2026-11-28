# 2026-11-29 接行不再補逗號

- tony 回報：「會出現很多逗號」。
- 原因：接行時在同一個人的兩句之間補「，」，而辨識停頓 0.6 秒就結束一句，等於每換一口氣就一個逗號。
- 修改：`CaptionAssembler.join` 改成用空白分隔（ADR-041），標點相鄰時不加。Android 與 iPhone 共用，一起生效。
- 測試：更新 `LineMergeTest`、`SecondPassTest` 的預期文字。尚未在模擬器或實機上看過畫面。
