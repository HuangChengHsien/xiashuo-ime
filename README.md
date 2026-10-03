<!--
SPDX-FileCopyrightText: 2026 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 蝦說輸入法

以 [Trime（同文輸入法）](https://github.com/osfans/trime) 3.3.12 為基礎的 Android 輸入法，
加上**離線語音輸入**：說完一句話，文字直接寫進目前的文字欄位，鍵盤不切換，接著就能用
Rime 方案（例如嘸蝦米）修字。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

## 功能

- **鍵盤麥克風與語音懸浮球**：在任何文字欄位按住懸浮球 1 秒開始說話，點一下停止，
  拖到下方關閉。
- **辨識都在手機上**：錄音與辨識都在手機上處理，錄音不會上傳；只有下載語音模型時會連網。
- **可選語音引擎**：
  - Android 系統本機辨識（需手機已安裝臺灣華語離線模型）
  - [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8 執行的離線模型：
    Fun-ASR-Nano、SenseVoice Small、Breeze ASR 25
- **語音模型管理**：在設定首頁「語音輸入 → 語音模型」（或鍵盤選單「語音引擎 → 管理模型」）
  下載、選用、刪除模型。下載在背景進行、可中斷續傳；使用行動數據時會先詢問；
  每個檔案都核對大小與 SHA-256 後才安裝。
- **邊錄音邊載入**：按下麥克風立即收音，模型同時在背景載入。
- 離線模型的輸出經 OpenCC `s2tw` 轉為臺灣正體。

## 語音模型

模型不包含在 APK 裡。在「語音模型」頁按「下載」即可；也可以自行下載後按「從檔案匯入」
（可選多個檔案，或選整個資料夾）。下載來源固定在下列 Hugging Face 版本。

| 模型 | 大小 | 下載 | 備註 |
|---|---|---|---|
| Fun-ASR-Nano INT8 | 約 1 GB | [Hugging Face](https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/tree/main) | 需 `encoder_adaptor`、`llm`、`embedding` 三個 `.int8.onnx` 與 `Qwen3-0.6B/` 內的 `tokenizer.json`、`vocab.json`、`merges.txt` |
| SenseVoice Small INT8 | 約 230 MB | [Hugging Face](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/main) | `model.int8.onnx`、`tokens.txt` |
| Breeze ASR 25 | 約 1.7 GB | [Hugging Face](https://huggingface.co/MediaTek-Research/Breeze-ASR-25-onnx-250806/tree/main) | 選檔名含 `half` 與 `int8` 的 encoder、decoder 與 tokens |

Pixel 8 Pro 實測（單次量測，僅供參考）：Fun-ASR-Nano 載入約 6–7 秒、6–9 秒語音辨識約 1.1–1.3 秒；
Breeze ASR 25 辨識約 9–15 秒。大型模型載入時會占用大量記憶體，系統可能因此關閉背景 App。

## 編譯

需要 Java 21、Android SDK 36、NDK 28.0.13004108、CMake 3.31.6。

```sh
git clone --recurse-submodules https://github.com/HuangChengHsien/xiashuo-ime.git
cd xiashuo-ime
BUILD_ABI=arm64-v8a ./gradlew :app:assembleDebug
```

- debug APK 的套件名稱是 `com.osfans.trime.debug`，可和正式版 Trime 並存。
- 編譯過程會讀取 `git config user.name`，未設定時會失敗。
- `app/libs/sherpa-onnx-1.13.8.aar` 由 sherpa-onnx **v1.13.8** 的 Kotlin API 與同版
  [Android 原生函式庫](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8) 製成；
  兩者版本不一致時，取得辨識結果會發生 JNI 崩潰。

## 與 Trime 的關係

本專案保留 Trime 至 v3.3.12 的完整歷史，蝦說輸入法的修改都在其後的 commit。
Trime 原本的說明見 [README_trime.md](README_trime.md)。
本專案未包含上游的 GitHub Actions 打包流程。

## 授權

[GPL-3.0-or-later](LICENSE)，與 Trime 相同。第三方元件授權見 `app/licenses/`。
