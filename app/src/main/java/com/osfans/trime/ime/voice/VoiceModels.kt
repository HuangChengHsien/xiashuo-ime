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
import com.osfans.trime.data.phrase.PhraseHotwords
import timber.log.Timber
import java.io.File

/** One file a model needs, relative to the model directory, pinned to one published release. */
data class VoiceModelFile(
    val path: String,
    val size: Long,
    val sha256: String,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * An offline model published on Hugging Face. Downloads use [revision] instead of `main`, so the
 * pinned sizes and hashes keep matching even if the upstream repository changes.
 */
/** How a recognizer runs on the phone; [VoiceTuning.DEFAULT] is what the keyboard uses. */
data class VoiceTuning(val numThreads: Int = 4, val provider: String = "cpu") {
    companion object {
        val DEFAULT = VoiceTuning()
    }
}

data class VoiceModel(
    val id: String,
    val title: String,
    val note: String,
    val repo: String,
    val revision: String,
    val files: List<VoiceModelFile>,
    /** How the keyboard runs this model; measured per model on the Pixel 8 Pro. */
    val tuning: VoiceTuning = VoiceTuning.DEFAULT,
    private val factory: (File, VoiceTuning) -> OfflineRecognizer,
) {
    val totalBytes: Long get() = files.sumOf { it.size }

    val pageUrl: String get() = "https://huggingface.co/$repo/tree/$revision"

    fun url(file: VoiceModelFile) = "https://huggingface.co/$repo/resolve/$revision/${file.path}"

    fun directory(context: Context) = File(context.getExternalFilesDir(null), "models/$id")

    fun target(context: Context, file: VoiceModelFile) = File(directory(context), file.path)

    fun isInstalled(context: Context): Boolean = missingFiles(context).isEmpty()

    /** Files that are absent or have a different size, e.g. from another release of the model. */
    fun missingFiles(context: Context): List<VoiceModelFile> =
        files.filter { spec -> target(context, spec).let { !it.isFile || it.length() != spec.size } }

    /** True when some required file exists but belongs to a different release. */
    fun hasMismatchedFiles(context: Context): Boolean =
        files.any { spec -> target(context, spec).let { it.isFile && it.length() != spec.size } }

    fun diskBytes(context: Context): Long = directory(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun createRecognizer(context: Context, tuning: VoiceTuning = this.tuning): OfflineRecognizer =
        factory(directory(context), tuning)
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
            note = "Whisper 架構；辨識較慢（約 10 秒），載入時占用大量記憶體。",
            repo = "MediaTek-Research/Breeze-ASR-25-onnx-250806",
            revision = "a3a2256b20854a2009ca9b4e1d22a05aa7ba992e",
            files = listOf(
                VoiceModelFile("breeze-asr-25-half-encoder.int8.onnx", 765942323L, "fc3e99d1d3abf553d355fe35cc72ff943c6b2b77eeeea97e8d56338bff132fea"),
                VoiceModelFile("breeze-asr-25-half-decoder.int8.onnx", 1008223031L, "be492b3bf597690dcd9871ac20c555faa9b78514d3e830402afdf2be0689fb20"),
                VoiceModelFile("breeze-asr-25-half-tokens.txt", 816730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
        ) { dir, tuning ->
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
                    numThreads = tuning.numThreads,
                    provider = tuning.provider,
                ),
            )
        },
        VoiceModel(
            id = ENGINE_SENSEVOICE,
            title = "SenseVoice Small",
            note = "檔案小、速度快。",
            repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
            revision = "2365baeacb507f821a0c8120fcee3d484dba7a07",
            files = listOf(
                VoiceModelFile("model.int8.onnx", 239233841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
                VoiceModelFile("tokens.txt", 315894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            ),
        ) { dir, tuning ->
            recognizer(
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(dir, "model.int8.onnx").absolutePath,
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    modelType = "sense_voice",
                    numThreads = tuning.numThreads,
                    provider = tuning.provider,
                ),
            )
        },
        VoiceModel(
            id = ENGINE_FUNASR_NANO,
            title = "Fun-ASR-Nano",
            note = "Fun-ASR-Nano-2512 INT8；辨識約 1 秒，載入約 6 秒（可邊說邊載入）。會參考「自訂詞語」裡的人名與專有名詞。",
            repo = "csukuangfj/sherpa-onnx-funasr-nano-int8-2025-12-30",
            // Tensor G3 has five fast cores (1×X3, 4×A715). On the Pixel 8 Pro, 5 threads
            // decoded 14 s of speech in 5.4 s versus 5.9 s with 4 and 6.3 s with 6; NNAPI was
            // no faster and less steady (VoiceModelBenchmarkActivity, 2026-10-03).
            tuning = VoiceTuning(numThreads = 5),
            revision = "6f16bd378457e13f36ccf3910df9017f96c346fb",
            files = listOf(
                VoiceModelFile("encoder_adaptor.int8.onnx", 237792748L, "f36dea2e30fbc33b5db1d7a7265cc976c5e5586c77b042d5adb1ad27c72db422"),
                VoiceModelFile("llm.int8.onnx", 600356593L, "dfbf9aa3be41bccc257587f151e15c63fbe1b549f2b517f5ccd5bdce3bf4322a"),
                VoiceModelFile("embedding.int8.onnx", 155584380L, "95e61cd0c9c3b9543339a4cf973c95c116815e745ccc1e0285cbd81f76d18644"),
                VoiceModelFile("Qwen3-0.6B/tokenizer.json", 11422654L, "aeb13307a71acd8fe81861d94ad54ab689df773318809eed3cbe794b4492dae4"),
                VoiceModelFile("Qwen3-0.6B/vocab.json", 2776833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
                VoiceModelFile("Qwen3-0.6B/merges.txt", 1671853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            ),
        ) { dir, tuning ->
            recognizer(
                OfflineModelConfig(
                    funasrNano = OfflineFunAsrNanoModelConfig(
                        encoderAdaptor = File(dir, "encoder_adaptor.int8.onnx").absolutePath,
                        llm = File(dir, "llm.int8.onnx").absolutePath,
                        embedding = File(dir, "embedding.int8.onnx").absolutePath,
                        tokenizer = File(dir, "Qwen3-0.6B").absolutePath,
                        maxNewTokens = 128,
                        hotwords = PhraseHotwords.collect().also {
                            Timber.i("Fun-ASR-Nano hotwords: %d from custom phrases", it.size)
                        }.joinToString(","),
                    ),
                    tokens = "",
                    numThreads = tuning.numThreads,
                    provider = tuning.provider,
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

    const val PREF_AUTO_STOP = "voice_auto_stop"

    /** Whether offline recording ends by itself after a pause (on unless turned off). */
    fun autoStop(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO_STOP, true)

    fun engineName(engine: String) = find(engine)?.title ?: "Android 系統語音"

    private fun recognizer(model: OfflineModelConfig) = OfflineRecognizer(
        config = OfflineRecognizerConfig(featConfig = feature, modelConfig = model),
    )
}
