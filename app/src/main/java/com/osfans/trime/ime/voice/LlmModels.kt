package com.osfans.trime.ime.voice

import android.content.Context
import androidx.preference.PreferenceManager
import java.io.File

data class LlmModel(
    val id: String,
    val title: String,
    val note: String,
    val repo: String,
    val revision: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val gated: Boolean = false,
) {
    val files get() = listOf(VoiceModelFile(fileName, size, sha256))
    val totalBytes get() = size
    val pageUrl get() = "https://huggingface.co/$repo"
    val url get() = "$pageUrl/resolve/$revision/$fileName"
    fun url(file: VoiceModelFile) = "$pageUrl/resolve/$revision/${file.path}"
    fun directory(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/$id")
    fun target(context: Context) = File(directory(context), fileName)
    fun target(context: Context, file: VoiceModelFile) = File(directory(context), file.path)
    fun missingFiles(context: Context) = files.filter { spec -> target(context, spec).let { !it.isFile || it.length() != spec.size } }
    fun isInstalled(context: Context) = target(context).let { it.isFile && it.length() == size }
    fun diskBytes(context: Context) = directory(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}

object LlmModels {
    const val PREF_MODEL = "rewrite_llm_model"
    val all = listOf(
        LlmModel("gemma-4-e2b", "Gemma 4 E2B", "目前預設模型；約 2.4 GiB。", "litert-community/gemma-4-E2B-it-litert-lm", "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94", "gemma-4-E2B-it.litertlm", 2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
        LlmModel("qwen-3.5-0.8b", "Qwen3.5 0.8B INT8", "約 919 MiB；適合較省空間的裝置。", "litert-community/Qwen3.5-0.8B", "03d910d3643b5ef29babed32327bdfad39fd1aef", "Qwen3.5-0.8B_int8.litertlm", 963184864L, "64ed396fcdae75e5158945c77a08142b1322ce1bbf1be4d2198783119a1169e8"),
        LlmModel("gemma-3-1b-int4", "Gemma 3 1B INT4", "約 557 MiB；需先在 Hugging Face 接受授權並匯入模型檔。", "litert-community/Gemma3-1B-IT", "a6306a4e292016480083b73b8dc6f3f939ae04c3", "gemma3-1b-it-int4.litertlm", 584417280L, "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be", gated = true),
        LlmModel("qwen-2.5-1.5b", "Qwen2.5 1.5B Instruct INT8", "約 1.5 GiB。", "litert-community/Qwen2.5-1.5B-Instruct", "19edb84c69a0212f29a6ef17ba0d6f278b6a1614", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm", 1597931520L, "faa60663b333290c1496c499828b21d3e3254a788cacd8cce917ce0f761a2dc9"),
    )
    fun find(id: String) = all.find { it.id == id }
    fun selected(context: Context): LlmModel = find(PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_MODEL, all.first().id).orEmpty()) ?: all.first()
    fun select(context: Context, model: LlmModel) {
        require(model.isInstalled(context)) { "模型尚未安裝" }
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PREF_MODEL, model.id).apply()
    }
}
