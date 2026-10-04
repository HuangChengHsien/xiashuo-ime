/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VoiceTextRulesTest : StringSpec({
    "prefers longer fixes and continues left to right" {
        val fixes = listOf("支持" to "支援", "支持治療" to "支持性治療", "内存" to "記憶體")
        VoiceTextRules.applyFixes("支持治療及内存，也支持", fixes) shouldBe "支持性治療及記憶體，也支援"
    }

    "does not loop on an empty source" {
        VoiceTextRules.applyFixes("原文", listOf("" to "替換")) shouldBe "原文"
    }
})
