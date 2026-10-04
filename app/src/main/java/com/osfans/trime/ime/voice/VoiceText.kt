/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.phrase.CustomPhraseKind
import com.osfans.trime.util.appContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Turns the Simplified Chinese that offline models write into Taiwan wording, e.g. 视频 → 影片,
 * 内存 → 記憶體, 阿司匹林 → 阿斯匹靈, instead of only converting glyphs, then applies the user's
 * own voice wording fixes.
 *
 * The OpenCC chain follows rime-tw's s2twp_local (its newer dictionaries and local overrides
 * in the Rime user directory) and falls back to Trime's bundled ones. In the Taiwan phrase step
 * TWPhrasesVoiceKeep is consulted first: OpenCC turns every 支持 into 支援, which suits only
 * 技術支援, while dictation needs 支持性治療, 社會支持 and 我支持你; it also prefers 計畫.
 * Multi-word keys cannot work there, because OpenCC converts word by word after segmenting;
 * those belong in the voice wording fixes, which match plain text.
 *
 * Not thread-safe; callers use one worker thread.
 */
object VoiceText {
    private var converter = 0L
    private var converterKey = ""
    private var fixes: List<Pair<String, String>> = emptyList()
    private var fixesStamp = -1L

    fun toTaiwan(text: String): String = runCatching {
        OpenCCDictManager.openCCConverterConvert(converter(), text)
    }.onFailure { Timber.w(it, "voice OpenCC conversion failed") }.getOrDefault(text)

    /** Replaces whole matches from the voice wording list, longest source first, left to right. */
    fun applyFixes(text: String): String = VoiceTextRules.applyFixes(text, fixes())

    private fun fixes(): List<Pair<String, String>> {
        val file = CustomPhraseKind.VOICE_FIX.file(DataManager.userDataDir)
        val stamp = if (file.isFile) file.lastModified() else 0L
        if (stamp != fixesStamp) {
            fixes = CustomPhraseKind.VOICE_FIX.load(DataManager.userDataDir).entries
                .map { it.phrase.text to it.phrase.code }
                .sortedByDescending { it.first.length }
            fixesStamp = stamp
        }
        return fixes
    }

    /**
     * Keeps one native converter, since building one loads every dictionary (about 0.5 s). It is
     * rebuilt when the chain or any dictionary in it changes, e.g. after rime-tw is updated.
     */
    private fun converter(): Long {
        val (config, files) = config()
        val key = config.readText() + files.joinToString { "${it.path}:${it.lastModified()}" }
        if (converter != 0L && key == converterKey) return converter
        val fresh = OpenCCDictManager.openCCConverterOpen(config.path)
        if (converter != 0L) OpenCCDictManager.openCCConverterClose(converter)
        converter = fresh
        converterKey = key
        Timber.i("voice OpenCC converter built")
        return fresh
    }

    /**
     * Written outside the Rime user directory with absolute dictionary paths, so it can mix both
     * directories without making Rime rebuild its schemas.
     */
    private fun config(): Pair<File, List<File>> {
        val user = File(DataManager.userDataDir, "opencc")
        val shared = File(DataManager.sharedDataDir, "opencc")
        fun find(name: String) = listOf(File(user, name), File(shared, name)).firstOrNull { it.isFile }
        val keep = File(shared, "TWPhrasesVoiceKeep.ocd2")
        if (!keep.isFile && File(shared, "TWPhrasesVoiceKeep.txt").isFile) OpenCCDictManager.buildOpenCCDict()
        val used = mutableListOf<File>()
        fun dict(file: File): String {
            used += file
            return """{"type": "ocd2", "file": ${JSONObject.quote(file.path)}}"""
        }
        val segmentation = requireNotNull(find("STPhrases.ocd2")) { "STPhrases.ocd2 missing" }
        val toTraditional = find("ST_merged.ocd2")?.let(::dict)
            ?: """{"type": "group", "dicts": [${dict(segmentation)}, ${dict(requireNotNull(find("STCharacters.ocd2")))}]}"""
        val phrases = listOfNotNull(keep.takeIf { it.isFile }, find("TWPhrases.ocd2")).map(::dict)
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
        return file to used
    }
}
