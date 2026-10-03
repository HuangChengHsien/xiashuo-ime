/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.util.appContext
import timber.log.Timber
import java.io.File

/**
 * Turns the Simplified Chinese that offline models write into Taiwan wording, e.g. 视频 → 影片,
 * 内存 → 記憶體, 阿司匹林 → 阿斯匹靈, instead of only converting glyphs.
 *
 * The OpenCC chain follows rime-tw's s2twp_local (its newer dictionaries and local overrides
 * in the Rime user directory) and falls back to Trime's bundled ones. In the Taiwan phrase step
 * TWPhrasesVoiceKeep is consulted first and wins ties: OpenCC turns every 支持 into 支援, which
 * suits only 技術支援, while dictation needs 支持性治療, 社會支持 and 我支持你; it also prefers 計畫.
 * Multi-word keys cannot be added there, because OpenCC converts word by word after segmenting.
 */
object VoiceText {
    fun toTaiwan(text: String): String {
        val config = runCatching { config() }.onFailure { Timber.w(it, "voice OpenCC config unavailable") }.getOrNull()
            ?: return text
        return runCatching { OpenCCDictManager.openCCLineConv(text, config.path) }
            .onFailure { Timber.w(it, "voice OpenCC conversion failed") }
            .getOrDefault(text)
    }

    /**
     * Written outside the Rime user directory with absolute dictionary paths, so it can mix both
     * directories without making Rime rebuild its schemas.
     */
    private fun config(): File {
        val user = File(DataManager.userDataDir, "opencc")
        val shared = File(DataManager.sharedDataDir, "opencc")
        fun find(name: String) = listOf(File(user, name), File(shared, name)).firstOrNull { it.isFile }?.path
        val keep = File(shared, "TWPhrasesVoiceKeep.ocd2")
        if (!keep.isFile && File(shared, "TWPhrasesVoiceKeep.txt").isFile) OpenCCDictManager.buildOpenCCDict()
        fun dict(path: String) = """{"type": "ocd2", "file": ${quote(path)}}"""
        val segmentation = requireNotNull(find("STPhrases.ocd2")) { "STPhrases.ocd2 missing" }
        val toTraditional = find("ST_merged.ocd2")?.let(::dict)
            ?: """{"type": "group", "dicts": [${dict(segmentation)}, ${dict(requireNotNull(find("STCharacters.ocd2")))}]}"""
        val phrases = listOfNotNull(keep.takeIf { it.isFile }?.path, find("TWPhrases.ocd2")).map(::dict)
        val chain = listOfNotNull(
            toTraditional,
            """{"type": "group", "dicts": [${phrases.joinToString(", ")}]}""",
            (find("TWVariants_merged.ocd2") ?: find("TWVariants.ocd2"))?.let(::dict),
            find("TWPhrasesLocal.ocd2")?.let(::dict),
        )
        val json = """{"name": "Xiashuo voice: Simplified to Taiwan wording", """ +
            """"segmentation": {"type": "mmseg", "dict": ${dict(segmentation)}}, """ +
            """"conversion_chain": [${chain.joinToString(", ") { """{"dict": $it}""" }}]}"""
        val file = File(appContext.filesDir, "voice-opencc/s2twp_voice.json")
        if (!file.isFile || file.readText() != json) {
            file.parentFile?.mkdirs()
            file.writeText(json)
        }
        return file
    }

    private fun quote(path: String) = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
