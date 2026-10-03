/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import com.osfans.trime.data.base.DataManager
import java.io.File

/**
 * Picks names and terms from the user's custom phrases as Fun-ASR-Nano hotwords. Hotwords go
 * into the model's prompt, so only short entries qualify: addresses, phone numbers, e-mail and
 * sentences (anything with digits, `@` or sentence punctuation) would just lengthen the prompt.
 */
object PhraseHotwords {
    private const val MAX_WORDS = 40
    private val excluded = setOf('@', ',', '，', '。', '、', '：', ':', '；', ';', '！', '？', '「', '」', '（', '）', '(', ')')

    /** Qualifying phrase texts, at most [MAX_WORDS], possibly empty. */
    fun collect(userDataDir: File = DataManager.userDataDir): List<String> =
        CustomPhraseKind.entries
            .flatMap { kind -> runCatching { kind.load(userDataDir).entries }.getOrDefault(emptyList()) }
            .map { it.phrase.text.trim() }
            .filter(::suitable)
            .distinct()
            .take(MAX_WORDS)

    private fun suitable(text: String): Boolean {
        if (text.any { it.isDigit() || it in excluded }) return false
        val han = text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return if (han > 0) {
            text.length in 2..12 && han == text.length
        } else {
            text.length in 4..32 && text.all { it.isLetter() || it == ' ' || it == '-' }
        }
    }
}
