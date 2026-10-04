/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

/** One-shot local text polishing with the user's Gemma 4 E2B model. */
object GemmaRewrite {
    private const val MODEL_RELATIVE_PATH = "models/gemma-4-e2b/gemma-4-E2B-it.litertlm"

    fun modelFile(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, MODEL_RELATIVE_PATH) }

    suspend fun rewrite(context: Context, source: String): String = withContext(Dispatchers.IO) {
        val model = modelFile(context)
        require(model?.isFile == true) {
            "找不到 Gemma 4 E2B 模型：請確認模型已放在蝦說的 models/gemma-4-e2b 資料夾。"
        }

        val prompt = """
            請潤飾以下文字，使用自然、清楚的臺灣繁體中文。
            保留原意、語氣、專有名詞、數字與網址，不新增資訊，不要解釋或加引號，只輸出潤飾後的文字。

            原文：
            $source
        """.trimIndent()

        // Pixel 8 Pro measurements show faster generation and much quicker subsequent
        // loads on CPU for this short rewrite workload. Fall back to GPU if CPU fails.
        try {
            generate(model, prompt, Backend.CPU(), context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            generate(model, prompt, Backend.GPU(), context)
        }
            .trim()
            .also { check(it.isNotEmpty()) { "模型沒有產生潤飾結果。" } }
    }

    private fun generate(model: File, prompt: String, backend: Backend, context: Context): String {
        val engine = Engine(
            EngineConfig(
                modelPath = model.absolutePath,
                backend = backend,
                cacheDir = File(context.cacheDir, "gemma4").absolutePath,
            ),
        )
        engine.use {
            it.initialize()
            it.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "你是臺灣繁體中文文字潤飾助手。遵守使用者要求，只回傳完成潤飾的文字。",
                    ),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.2),
                    maxOutputToken = 256,
                ),
            ).use { conversation ->
                val response = conversation.sendMessage(prompt)
                return response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
            }
        }
    }
}
