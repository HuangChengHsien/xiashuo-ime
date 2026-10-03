/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import android.icu.text.Transliterator
import android.os.Build
import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File
import java.text.Normalizer

/**
 * Restores the user's own names and terms in speech results by sound. A recognizer may write
 * 林雨薇 for the custom phrase 林宇葳: 雨 and 宇 are both yu, and 薇 and 葳 are both wei. A span is
 * replaced when every character shares a toneless reading with the phrase and enough characters
 * are already identical (see [enoughSame]), so ordinary homophones such as 下雨 stay as they are.
 *
 * Readings come from Rime's luna_pinyin dictionary, which lists every reading of a polyphonic
 * character; characters it lacks fall back to Android's ICU transliterator.
 */
object PhraseCorrector {
    private val hanGap = Regex("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})")

    @Volatile private var readings: Map<Char, Set<String>>? = null

    @Volatile private var transliterator: Any? = null

    /** Removes spaces that some recognizers put between Chinese characters. */
    fun tidy(text: String) = text.replace(hanGap, "")

    /** Loads the reading table ahead of time, e.g. while the user is still speaking. */
    fun warmUp() {
        table()
    }

    fun correct(text: String, words: List<String>): String {
        if (text.isEmpty()) return text
        val candidates = words.filter { word -> word.length >= 2 && word.all(::isHan) }
        if (candidates.isEmpty()) return text
        return runCatching { correctBySound(text, candidates) }
            .onFailure { Timber.w(it, "phrase correction failed") }
            .getOrDefault(text)
    }

    private fun correctBySound(text: String, words: List<String>): String {
        val chars = text.toCharArray()
        // Longer phrases first, so a full name wins over a shorter phrase inside it.
        for (word in words.sortedByDescending { it.length }) {
            val target = word.map(::readingsOf)
            var i = 0
            while (i + word.length <= chars.size) {
                val window = String(chars, i, word.length)
                val same = window.indices.count { window[it] == word[it] }
                val matches = window != word &&
                    enoughSame(same, word.length) &&
                    word.indices.all { k -> soundsAlike(chars[i + k], word[k], target[k]) }
                if (matches) {
                    word.forEachIndexed { k, c -> chars[i + k] = c }
                    i += word.length
                } else {
                    i++
                }
            }
        }
        return String(chars)
    }

    /**
     * At least one character must already be right, and at least a third of them: 林雨薇 keeps
     * 林 of 林宇葳, while a two-character phrase needs one of its two characters.
     */
    private fun enoughSame(same: Int, length: Int) = same >= 1 && same * 3 >= length

    /**
     * Characters sound alike when they share a toneless reading, unless ICU gives both the same
     * syllable with different tones: 晃 huǎng is not 黃 huáng, and 玉 yù is not 宇 yǔ. ICU knows a
     * single reading per character, so it only vetoes; luna_pinyin decides polyphones such as
     * 崴 wai/wei.
     */
    private fun soundsAlike(a: Char, b: Char, bReadings: Set<String>): Boolean {
        if (a == b) return true
        if (readingsOf(a).none { it in bReadings }) return false
        val toneA = icuToned(a) ?: return true
        val toneB = icuToned(b) ?: return true
        return toneless(toneA) != toneless(toneB) || toneA == toneB
    }

    private fun readingsOf(c: Char): Set<String> {
        if (!isHan(c)) return setOf(c.toString())
        return table()[c] ?: setOfNotNull(icuReading(c))
    }

    private fun table(): Map<Char, Set<String>> {
        readings?.let { return it }
        synchronized(this) {
            readings?.let { return it }
            val loaded = runCatching { load() }
                .onFailure { Timber.w(it, "luna_pinyin readings unavailable") }
                .getOrDefault(emptyMap())
            readings = loaded
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
    private fun icuReading(c: Char): String? = icuToned(c)?.let(::toneless)?.ifEmpty { null }

    /** ICU's single toned reading, e.g. 宇 → "yǔ"; null below Android 10. */
    private fun icuToned(c: Char): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !isHan(c)) return null
        val latin = (transliterator ?: Transliterator.getInstance("Han-Latin").also { transliterator = it }) as Transliterator
        return Normalizer.normalize(latin.transliterate(c.toString()), Normalizer.Form.NFC).trim().lowercase()
    }

    private fun toneless(toned: String) =
        Normalizer.normalize(toned, Normalizer.Form.NFD).filter { it.isLetter() && it.code < 0x80 }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
