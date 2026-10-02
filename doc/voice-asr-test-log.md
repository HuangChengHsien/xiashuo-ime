# 語音辨識實機測試紀錄

## 2026-10-02 — Pixel 8 Pro、Trime 測試版

測試句：「今天晚上吃飯，明天繼續用蝦米改字。」

| 引擎 | 結果 | 備註 |
| --- | --- | --- |
| Streaming Zipformer | 不如 SenseVoice Small 適合 | 使用者回報聆聽面板會跳動、可錄音時間感覺偏短，輸出為簡體中文。 |
| SenseVoice Small INT8 | 正確 | 使用者確認測試句辨識正確，且比 Streaming Zipformer 合用。離線模型；輸出轉為臺灣繁體。 |
| Breeze ASR 25 | 回報無法錄音 | 使用者無法錄到語音。原版在大型模型載入前就顯示聆聽提示；已加入載入狀態，並在 AudioRecord 啟動前停用「停止並輸入」，待再次實機測試確認。 |

決定：從鍵盤選單、模型管理頁與測試 APK 移除 Streaming Zipformer；保留 Google 語音、Breeze ASR 25 和 SenseVoice Small。
