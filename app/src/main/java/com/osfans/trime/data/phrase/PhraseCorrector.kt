/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import android.icu.text.Transliterator
import android.os.Build
import androidx.annotation.RequiresApi
import java.text.Normalizer

/**
 * Restores the user's own names and terms in speech results by sound. A recognizer may write
 * 林雨葳 for the custom phrase 林宇葳 because 雨 and 宇 are both yǔ; a span is replaced only when
 * every syllable matches including its tone and at least half of its characters are already
 * identical, so ordinary words that merely sound alike are left alone.
 */
object PhraseCorrector {
    @Volatile private var transliterator: Any? = null

    fun correct(text: String, words: List<String>): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || text.isEmpty()) return text
        val candidates = words.filter { word -> word.length >= 2 && word.all(::isHan) }
        if (candidates.isEmpty()) return text
        return runCatching { correctByPinyin(text, candidates) }.getOrDefault(text)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun correctByPinyin(text: String, words: List<String>): String {
        val chars = text.toCharArray()
        val readings = arrayOfNulls<String>(chars.size)
        fun readingAt(i: Int) = readings[i] ?: reading(chars[i]).also { readings[i] = it }
        // Longer phrases first, so 林宇葳 wins over a shorter phrase inside it.
        for (word in words.sortedByDescending { it.length }) {
            val target = word.map(::reading)
            var i = 0
            while (i + word.length <= chars.size) {
                val window = String(chars, i, word.length)
                val same = window.indices.count { window[it] == word[it] }
                val matches = window != word &&
                    same * 2 >= word.length &&
                    word.indices.all { readingAt(i + it) == target[it] }
                if (matches) {
                    word.forEachIndexed { k, c ->
                        chars[i + k] = c
                        readings[i + k] = target[k]
                    }
                    i += word.length
                } else {
                    i++
                }
            }
        }
        return String(chars)
    }

    /** Toned pinyin of one character, e.g. 宇 → "yǔ"; other characters stand for themselves. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun reading(c: Char): String {
        if (!isHan(c)) return c.toString()
        val latin = (transliterator ?: Transliterator.getInstance("Han-Latin").also { transliterator = it }) as Transliterator
        return Normalizer.normalize(latin.transliterate(c.toString()), Normalizer.Form.NFC).trim().lowercase()
    }

    private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
