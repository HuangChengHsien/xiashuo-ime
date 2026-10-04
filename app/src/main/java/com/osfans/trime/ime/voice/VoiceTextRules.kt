/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/** Pure text substitutions shared by voice polishing and JVM tests. */
internal object VoiceTextRules {
    fun applyFixes(text: String, fixes: List<Pair<String, String>>): String {
        val ordered = fixes.filter { it.first.isNotEmpty() }.sortedByDescending { it.first.length }
        if (ordered.isEmpty()) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val hit = ordered.firstOrNull { (from, _) -> text.startsWith(from, i) }
            if (hit != null) {
                out.append(hit.second)
                i += hit.first.length
            } else {
                out.append(text[i++])
            }
        }
        return out.toString()
    }
}
