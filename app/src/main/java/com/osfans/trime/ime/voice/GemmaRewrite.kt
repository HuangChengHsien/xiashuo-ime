/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/** One-shot local text polishing with the selected LiteRT-LM model. */
object GemmaRewrite {
    const val MODEL_RELATIVE_PATH = "models/gemma-4-e2b/gemma-4-E2B-it.litertlm"
    const val MODEL_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
    private val engineMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var verifiedFile: String? = null
    @Volatile private var cachedEngine: CachedEngine? = null

    private class CachedEngine(val modelPath: String, val backendName: String, val engine: Engine) {
        @Volatile var lastUsedMs: Long = SystemClock.elapsedRealtime()
        var expiryJob: Job? = null
    }

    private const val TAG = "GemmaRewrite"
    private const val ENGINE_IDLE_TIMEOUT_MS = 30_000L

    fun modelFile(context: Context): File = LlmModels.selected(context).target(context)

    /** What to do with the selected text; each task only changes the prompt and wording. */
    enum class Task(
        val label: String,
        val action: String,
        val systemInstruction: String,
        /** English output needs several times more tokens than the Chinese source. */
        val maxOutputTokens: Int,
    ) {
        POLISH("潤", "潤飾", "你是臺灣繁體中文文字潤飾助手。遵守使用者要求，只回傳完成潤飾的文字。", 256),
        TRANSLATE_EN(
            "譯",
            "翻譯",
            "You are a translator. Translate the user's text into English and reply with the English translation only.",
            1024,
        ),
        ;

        fun prompt(source: String): String = when (this) {
            POLISH -> """
                請潤飾以下文字，使用自然、清楚的臺灣繁體中文。
                保留原意、語氣、專有名詞、數字與網址，不新增資訊，不要解釋或加引號，只輸出潤飾後的文字。

                原文：
                $source
            """.trimIndent()
            TRANSLATE_EN -> """
                Translate the following text into natural, fluent English.
                Keep the original meaning and tone. Keep names, numbers and URLs unchanged.
                Do not explain, do not add quotes or notes; output only the English translation.

                Text:
                $source
            """.trimIndent()
        }
    }

    suspend fun rewrite(context: Context, source: String): String = transform(context, source, Task.POLISH)

    suspend fun transform(context: Context, source: String, task: Task): String = engineMutex.withLock {
        withContext(Dispatchers.IO) {
            val totalStart = SystemClock.elapsedRealtime()
            val selected = LlmModels.selected(context)
            val model = selected.target(context)
            require(model.isFile) {
                "找不到 ${selected.title} 模型：請到 AI 文字潤飾模型頁下載或匯入。"
            }
            val verifyStart = SystemClock.elapsedRealtime()
            verifyModelFile(context, selected)
            Log.i(TAG, "model=${selected.id} phase=verify elapsedMs=${SystemClock.elapsedRealtime() - verifyStart}")

            val prompt = task.prompt(source)

            // Pixel 8 Pro measurements show faster generation and much quicker subsequent
            // loads on CPU for this short rewrite workload. Fall back to GPU if CPU fails.
            val generateStart = SystemClock.elapsedRealtime()
            val result = try {
                generate(model, prompt, task, Backend.CPU(), context, "cpu")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "CPU inference failed; retrying with GPU (task=${task.name})", error)
                discardCachedEngine("cpu")
                generate(model, prompt, task, Backend.GPU(), context, "gpu")
            }
            Log.i(
                TAG,
                "model=${selected.id} task=${task.name} phase=inference elapsedMs=${SystemClock.elapsedRealtime() - generateStart} totalMs=${SystemClock.elapsedRealtime() - totalStart} inputChars=${source.length} outputChars=${result.length}",
            )
            result.trim().also { check(it.isNotEmpty()) { "模型沒有產生${task.action}結果。" } }
        }
    }

    @Synchronized
    fun verifyModelFile(context: Context, selected: LlmModel) {
        val model = selected.target(context)
        check(model.isFile && model.length() == selected.size) { "${selected.title} 模型大小不符，請重新下載或匯入。" }
        val stamp = model.length() to model.lastModified()
        val cacheKey = "${model.absolutePath}:verified-sha256"
        val fingerprint = "${stamp.first}:${stamp.second}:${selected.sha256}"
        val prefs = context.applicationContext.getSharedPreferences("gemma_model_integrity", Context.MODE_PRIVATE)
        if (verifiedFile == "$cacheKey:$fingerprint" || prefs.getString(cacheKey, null) == fingerprint) {
            verifiedFile = "$cacheKey:$fingerprint"
            Log.i(TAG, "phase=verify cached=true bytes=${model.length()}")
            return
        }
        val start = SystemClock.elapsedRealtime()
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual == selected.sha256) { "${selected.title} 模型檔 SHA-256 不符，請刪除後重新下載。" }
        verifiedFile = "$cacheKey:$fingerprint"
        prefs.edit().putString(cacheKey, fingerprint).apply()
        Log.i(TAG, "phase=verify cached=false elapsedMs=${SystemClock.elapsedRealtime() - start} bytes=${model.length()}")
    }

    /** Retained for existing callers that explicitly verify the legacy default model. */
    fun verifyModelFile(context: Context, model: File) {
        val selected = LlmModels.all.find { it.target(context).absolutePath == model.absolutePath }
            ?: error("未知的潤飾模型")
        verifyModelFile(context, selected)
    }

    /** Releases the warm model after a memory warning or when the keyboard service is destroyed. */
    fun releaseCachedEngine() {
        cleanupScope.launch {
            engineMutex.withLock { closeCachedEngine() }
        }
    }

    suspend fun releaseCachedEngineAndWait() {
        engineMutex.withLock { closeCachedEngine() }
    }

    private fun generate(
        model: File,
        prompt: String,
        task: Task,
        backend: Backend,
        context: Context,
        backendName: String,
    ): String {
        val start = SystemClock.elapsedRealtime()
        val (entry, initializeMs) = engineFor(model, backend, context, backendName)
        val conversationStart = SystemClock.elapsedRealtime()
        entry.engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(task.systemInstruction),
                samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2),
                maxOutputToken = task.maxOutputTokens,
            ),
        ).use { conversation ->
            val conversationMs = SystemClock.elapsedRealtime() - conversationStart
            val inferenceStart = SystemClock.elapsedRealtime()
            val response = conversation.sendMessage(prompt)
            val text = response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
            Log.i(
                TAG,
                "backend=$backendName initializeMs=$initializeMs conversationMs=$conversationMs generateMs=${SystemClock.elapsedRealtime() - inferenceStart} engineTotalMs=${SystemClock.elapsedRealtime() - start}",
            )
            return text
        }
    }

    private fun engineFor(model: File, backend: Backend, context: Context, backendName: String): Pair<CachedEngine, Long> {
        val path = model.absolutePath
        cachedEngine?.takeIf { it.modelPath == path && it.backendName == backendName }?.let { cached ->
            cached.lastUsedMs = SystemClock.elapsedRealtime()
            scheduleExpiry(cached)
            return cached to 0L
        }
        closeCachedEngine()
        val engine = Engine(
            EngineConfig(
                modelPath = path,
                backend = backend,
                cacheDir = File(context.cacheDir, "litertlm-${model.parentFile?.name}").apply { mkdirs() }.absolutePath,
            ),
        )
        val start = SystemClock.elapsedRealtime()
        try {
            engine.initialize()
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
        val entry = CachedEngine(path, backendName, engine)
        cachedEngine = entry
        scheduleExpiry(entry)
        return entry to (SystemClock.elapsedRealtime() - start)
    }

    private fun scheduleExpiry(entry: CachedEngine) {
        entry.expiryJob?.cancel()
        entry.expiryJob = cleanupScope.launch {
            while (true) {
                val remaining = ENGINE_IDLE_TIMEOUT_MS - (SystemClock.elapsedRealtime() - entry.lastUsedMs)
                if (remaining > 0) delay(remaining)
                val closed = engineMutex.withLock {
                    if (cachedEngine !== entry) {
                        true
                    } else if (SystemClock.elapsedRealtime() - entry.lastUsedMs >= ENGINE_IDLE_TIMEOUT_MS) {
                        cachedEngine = null
                        runCatching { entry.engine.close() }
                        Log.i(TAG, "engine released after idle timeout")
                        true
                    } else {
                        false
                    }
                }
                if (closed) return@launch
            }
        }
    }

    /** Called only while [engineMutex] is held. */
    private fun discardCachedEngine(backendName: String) {
        if (cachedEngine?.backendName == backendName) closeCachedEngine()
    }

    /** Called only while [engineMutex] is held. */
    private fun closeCachedEngine() {
        val old = cachedEngine ?: return
        cachedEngine = null
        old.expiryJob?.cancel()
        runCatching { old.engine.close() }
    }
}
