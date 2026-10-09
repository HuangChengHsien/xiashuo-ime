/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan

class GemmaRewriteTaskTest : StringSpec({
    "output budget covers a full reply to the longest allowed selection" {
        GemmaRewrite.Task.entries.forEach { task ->
            task.maxOutputTokens shouldBeGreaterThanOrEqual task.maxInputChars
        }
    }

    "prompt and reply fit in a 4096-token context" {
        GemmaRewrite.Task.entries.forEach { task ->
            val prompt = task.prompt("字".repeat(task.maxInputChars))
            (prompt.length + task.systemInstruction.length + task.maxOutputTokens) shouldBeLessThan 4096
        }
    }
})
