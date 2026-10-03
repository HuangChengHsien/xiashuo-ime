/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import androidx.preference.PreferenceManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * One file a model needs, relative to the model directory. [size] and [sha256] are pinned only for
 * models published as a single fixed release; otherwise [maxSize] bounds an import.
 */
data class VoiceModelFile(
    val path: String,
    val size: Long? = null,
    val sha256: String? = null,
    val maxSize: Long = size ?: 10_000_000L,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class VoiceModel(
    val id: String,
    val title: String,
    val note: String,
    val downloadUrl: String,
    val files: List<VoiceModelFile>,
    private val factory: (File) -> OfflineRecognizer,
) {
    fun directory(context: Context) = File(context.getExternalFilesDir(null), "models/$id")

    fun isInstalled(context: Context): Boolean {
        val dir = directory(context)
        return files.all { spec ->
            val file = File(dir, spec.path)
            file.isFile && (spec.size == null || file.length() == spec.size)
        }
    }

    fun missingFiles(context: Context): List<VoiceModelFile> {
        val dir = directory(context)
        return files.filter { spec ->
            val file = File(dir, spec.path)
            !file.isFile || (spec.size != null && file.length() != spec.size)
        }
    }

    fun diskBytes(context: Context): Long = directory(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun createRecognizer(context: Context): OfflineRecognizer = factory(directory(context))
}

object VoiceModels {
    const val PREF_ENGINE = "voice_recognition_engine"
    const val ENGINE_GOOGLE = "google"
    const val ENGINE_BREEZE = "breeze-asr-25"
    const val ENGINE_SENSEVOICE = "sensevoice-small"
    const val ENGINE_FUNASR_NANO = "funasr-nano"
    private const val LEGACY_ZIPFORMER = "zipformer-small-ctc-zh"

    private val feature = FeatureConfig(sampleRate = 16000, featureDim = 80)

    val all: List<VoiceModel> = listOf(
        VoiceModel(
            id = ENGINE_BREEZE,
            title = "Breeze ASR 25",
            note = "Whisper 架構，約 1.7 GB；辨識較慢，載入時占用大量記憶體。",
            downloadUrl = "https://huggingface.co/MediaTek-Research/Breeze-ASR-25-onnx-250806/tree/main",
            files = listOf(
                VoiceModelFile("breeze-asr-25-half-encoder.int8.onnx", 765942323L, "fc3e99d1d3abf553d355fe35cc72ff943c6b2b77eeeea97e8d56338bff132fea"),
                VoiceModelFile("breeze-asr-25-half-decoder.int8.onnx", 1008223031L, "be492b3bf597690dcd9871ac20c555faa9b78514d3e830402afdf2be0689fb20"),
                VoiceModelFile("breeze-asr-25-half-tokens.txt", 816730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
        ) { dir ->
            recognizer(
                OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, "breeze-asr-25-half-encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "breeze-asr-25-half-decoder.int8.onnx").absolutePath,
                        language = "zh",
                        task = "transcribe",
                        tailPaddings = 300,
                    ),
                    tokens = File(dir, "breeze-asr-25-half-tokens.txt").absolutePath,
                    modelType = "whisper",
                    numThreads = 4,
                    provider = "cpu",
                ),
            )
        },
        VoiceModel(
            id = ENGINE_SENSEVOICE,
            title = "SenseVoice Small",
            note = "約 230 MB；速度快，輸出經 OpenCC 轉為臺灣正體。",
            downloadUrl = "https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html",
            files = listOf(
                VoiceModelFile("model.int8.onnx", maxSize = 1_500_000_000L),
                VoiceModelFile("tokens.txt"),
            ),
        ) { dir ->
            recognizer(
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    modelType = "sense_voice",
                    numThreads = 4,
                    provider = "cpu",
                ),
            )
        },
        VoiceModel(
            id = ENGINE_FUNASR_NANO,
            title = "Fun-ASR-Nano",
            note = "Fun-ASR-Nano-2512 INT8，約 1 GB；以 Qwen3-0.6B 解碼，輸出經 OpenCC 轉為臺灣正體。",
            downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30/tree/main",
            files = listOf(
                VoiceModelFile("encoder_adaptor.int8.onnx", 237792748L, "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"),
                VoiceModelFile("llm.int8.onnx", 600356593L, "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"),
                VoiceModelFile("embedding.int8.onnx", 155584380L, "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"),
                VoiceModelFile("Qwen3-0.6B/tokenizer.json", 11422654L, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"),
                VoiceModelFile("Qwen3-0.6B/vocab.json", 2776833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                VoiceModelFile("Qwen3-0.6B/merges.txt", 1671853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            ),
        ) { dir ->
            recognizer(
                OfflineModelConfig(
                    funasrNano = OfflineFunAsrNanoModelConfig(
                        encoderAdaptor = File(dir, "encoder_adaptor.int8.onnx").absolutePath,
                        llm = File(dir, "llm.int8.onnx").absolutePath,
                        embedding = File(dir, "embedding.int8.onnx").absolutePath,
                        tokenizer = File(dir, "Qwen3-0.6B").absolutePath,
                        maxNewTokens = 128,
                    ),
                    tokens = "",
                    numThreads = 4,
                    provider = "cpu",
                ),
            )
        },
    )

    fun find(id: String): VoiceModel? = all.find { it.id == id }

    fun selectedEngine(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val stored = prefs.getString(PREF_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE
        val engine = when {
            stored == LEGACY_ZIPFORMER -> ENGINE_SENSEVOICE
            find(stored) != null -> stored
            else -> ENGINE_GOOGLE
        }
        if (engine != stored) setSelectedEngine(context, engine)
        return engine
    }

    fun setSelectedEngine(context: Context, engine: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_ENGINE, engine).apply()
    }

    fun engineName(engine: String) = find(engine)?.title ?: "Android 系統語音"

    private fun recognizer(model: OfflineModelConfig) = OfflineRecognizer(
        config = OfflineRecognizerConfig(featConfig = feature, modelConfig = model),
    )
}
