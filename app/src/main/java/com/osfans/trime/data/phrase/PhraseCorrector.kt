/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import android.icu.text.Transliterator
import android.os.Build
import android.os.SystemClock
import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File
import java.text.Normalizer

/**
 * Restores the user's own names and terms in speech results by sound. A recognizer may write
 * 黃雨薇 for the custom phrase 黃禹崴: 雨 and 禹 are both yu, and 崴 can be read wei. A span is
 * replaced when every character shares a toneless reading with the phrase and enough characters
 * are already identical (see [enoughSame]), so ordinary homophones such as 下雨 stay as they are.
 *
 * Readings come from Rime's luna_pinyin dictionary, which lists every reading of a polyphonic
 * character; characters it lacks fall back to Android's ICU transliterator.
 */
object PhraseCorrector {
    @Volatile private var readings: Map<Char, Set<String>>? = null
    @Volatile private var retryAfterMs = 0L

    @Volatile private var transliterator: Any? = null

    /** Removes spaces that some recognizers put between Chinese characters. */
    fun tidy(text: String) = PhraseCorrectionRules.tidy(text)

    /** Loads the reading table ahead of time, e.g. while the user is still speaking. */
    fun warmUp() {
        retryAfterMs = 0L
        table()
    }

    fun correct(text: String, words: List<String>): String {
        if (text.isEmpty()) return text
        return runCatching { PhraseCorrectionRules.correct(text, words, ::readingsOf, ::icuToned) }
            .onFailure { Timber.w(it, "phrase correction failed") }
            .getOrDefault(text)
    }

    private fun readingsOf(c: Char): Set<String> {
        if (!PhraseCorrectionRules.isHan(c)) return setOf(c.toString())
        return table()[c] ?: setOfNotNull(icuReading(c))
    }

    private fun table(): Map<Char, Set<String>> {
        readings?.let { return it }
        synchronized(this) {
            readings?.let { return it }
            if (SystemClock.elapsedRealtime() < retryAfterMs) return emptyMap()
            val loaded = runCatching { load() }
                .onFailure { Timber.w(it, "luna_pinyin readings unavailable") }
                .getOrDefault(emptyMap())
            // Deployment may not have copied the dictionary yet. Keep the fallback usable,
            // but let a later call retry instead of caching this transient failure forever.
            if (loaded.isNotEmpty()) {
                readings = loaded
                retryAfterMs = 0L
            } else {
                retryAfterMs = SystemClock.elapsedRealtime() + 5_000L
            }
            return loaded
        }
    }

    /** Single-character lines of luna_pinyin.dict.yaml, e.g. "崴\twai" and "崴\twei". */
    private fun load(): Map<Char, Set<String>> {
        val file = listOf(DataManager.userDataDir, DataManager.sharedDataDir)
            .map { File(it, "luna_pinyin.dict.yaml") }
            .first { it.isFile }
        val map = HashMap<Char, MutableSet<String>>(24_000)
        var inData = false
        file.forEachLine { line ->
            if (!inData) {
                inData = line == "..."
                return@forEachLine
            }
            val tab = line.indexOf('\t')
            if (tab != 1) return@forEachLine
            val end = line.indexOf('\t', 2).let { if (it < 0) line.length else it }
            map.getOrPut(line[0]) { HashSet(2) } += line.substring(2, end).trim()
        }
        Timber.i("Loaded readings for %d characters", map.size)
        return map
    }

    /** Toneless pinyin from ICU, for characters missing in luna_pinyin. */
    private fun icuReading(c: Char): String? = icuToned(c)?.let { toned ->
        java.text.Normalizer.normalize(toned, Normalizer.Form.NFD)
            .filter { it.isLetter() && it.code < 0x80 }.ifEmpty { null }
    }

    /** ICU's single toned reading, e.g. 禹 → "yǔ"; null below Android 10. */
    private fun icuToned(c: Char): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !PhraseCorrectionRules.isHan(c)) return null
        val latin = (transliterator ?: Transliterator.getInstance("Han-Latin").also { transliterator = it }) as Transliterator
        return Normalizer.normalize(latin.transliterate(c.toString()), Normalizer.Form.NFC).trim().lowercase()
    }

}
