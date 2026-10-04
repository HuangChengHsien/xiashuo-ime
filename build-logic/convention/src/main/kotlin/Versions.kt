// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

object Versions {
    const val DEFAULT_CMAKE = "3.31.6"
    const val DEFAULT_NDK = "28.0.13004108"

    // sherpa-onnx is currently packaged only for arm64-v8a; emitting other ABI splits
    // produces APKs that install successfully but cannot load the offline recognizer.
    val supportedAbis = setOf("arm64-v8a")
}
