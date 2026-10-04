/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PhraseCorrectionRulesTest : StringSpec({
    "removes spaces between Han characters only" {
        PhraseCorrectionRules.tidy("請 用 黃 雨 薇，謝謝 A B") shouldBe "請用黃雨薇，謝謝 A B"
    }

    "corrects a matching homophone when enough characters already match" {
        val readings = mapOf('黃' to setOf("huang"), '雨' to setOf("yu"), '禹' to setOf("yu"), '薇' to setOf("wei"), '崴' to setOf("wei"))
        PhraseCorrectionRules.correct("黃雨薇", listOf("黃禹崴"), { readings[it] ?: emptySet() }, { null }) shouldBe "黃禹崴"
    }

    "leaves an unrelated word unchanged" {
        PhraseCorrectionRules.correct("下雨", listOf("夏禹"), { emptySet() }, { null }) shouldBe "下雨"
    }

    "vetoes a homophone with a different tone when ICU readings are available" {
        val readings = mapOf('玉' to setOf("yu"), '禹' to setOf("yu"))
        PhraseCorrectionRules.correct("玉禹", listOf("禹禹"), { readings[it] ?: emptySet() }, { if (it == '玉') "yù" else "yǔ" }) shouldBe "玉禹"
    }
})
