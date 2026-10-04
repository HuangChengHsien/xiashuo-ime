/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import java.text.Normalizer

/** Unicode and matching rules kept free of Android and Rime dependencies for JVM tests. */
internal object PhraseCorrectionRules {
    private val hanGap = Regex("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})")

    fun tidy(text: String): String = text.replace(hanGap, "")

    fun correct(
        text: String,
        words: List<String>,
        readings: (Char) -> Set<String>,
        tonedReading: (Char) -> String?,
    ): String {
        if (text.isEmpty()) return text
        val candidates = words.filter { word -> word.length >= 2 && word.all(::isHan) }
        if (candidates.isEmpty()) return text
        val chars = text.toCharArray()
        for (word in candidates.sortedByDescending { it.length }) {
            val target = word.map(readings)
            var i = 0
            while (i + word.length <= chars.size) {
                val window = String(chars, i, word.length)
                val same = window.indices.count { window[it] == word[it] }
                val matches = window != word && same >= 1 && same * 3 >= word.length &&
                    word.indices.all { k -> soundsAlike(chars[i + k], word[k], target[k], readings, tonedReading) }
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

    private fun soundsAlike(
        a: Char,
        b: Char,
        bReadings: Set<String>,
        readings: (Char) -> Set<String>,
        tonedReading: (Char) -> String?,
    ): Boolean {
        if (a == b) return true
        if (readings(a).none { it in bReadings }) return false
        val toneA = tonedReading(a) ?: return true
        val toneB = tonedReading(b) ?: return true
        return toneless(toneA) != toneless(toneB) || toneA == toneB
    }

    private fun toneless(toned: String) =
        Normalizer.normalize(toned, Normalizer.Form.NFD).filter { it.isLetter() && it.code < 0x80 }

    fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
