// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GeneralStyleTest : StringSpec({
    "decodes values and defaults from a theme style mapping" {
        val node = Yaml.parseToYamlNode("style:\n  auto_caps: false\n  candidate_font: [han.ttf]\n  comment_position: right\n")
            .mapping!!["style"]!!

        val style = GeneralStyle.decode(node)

        style.autoCaps shouldBe false
        style.candidateFont shouldBe listOf("han.ttf")
        style.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
        style.enterLabel.go shouldBe "go"
    }

    "uses defaults for absent and malformed optional values" {
        val node = Yaml.parseToYamlNode("style:\n  comment_position: aaaa\n")
            .mapping!!["style"]!!

        val style = GeneralStyle.decode(node)

        style.autoCaps shouldBe false
        style.candidateBorder shouldBe 0
        style.candidateFont shouldBe emptyList()
        style.commentPosition shouldBe GeneralStyle.CommentPosition.RIGHT
        style.enterLabel.go shouldBe "go"
    }
})
