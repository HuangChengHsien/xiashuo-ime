// SPDX-FileCopyrightText: 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.theme

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

class ThemePatchFileTest :
    StringSpec({
        fun edit(content: String, block: ThemePatchFile.() -> Unit): String {
            val file = File.createTempFile("theme", ".custom.yaml")
            try {
                file.writeText(content)
                ThemePatchFile(file).apply(block).save()
                return file.readText()
            } finally {
                file.delete()
            }
        }

        val original = """
            # private fonts
            patch:
              style/candidate_font: [ jf.otf ] # licensed
              style/comment_font: [ jf.otf ]

        """.trimIndent()

        "adding and removing an entry restores the file exactly" {
            edit(original) { set("candidate_text_size", "26") }.let { changed ->
                changed shouldBe original.replace(
                    "  style/comment_font: [ jf.otf ]\n",
                    "  style/comment_font: [ jf.otf ]\n  style/candidate_text_size: 26\n",
                )
                edit(changed) { set("candidate_text_size", null) } shouldBe original
            }
        }

        "replacing a value keeps its trailing comment" {
            edit(original) { set("candidate_font", "[ Barlow.ttf ]") } shouldBe
                original.replace("[ jf.otf ] # licensed", "[ Barlow.ttf ] # licensed")
        }

        "entries outside the patch block are left alone" {
            val text = "other:\n  style/key_text_size: 30\npatch:\n  style/key_text_size: 20\n"
            edit(text) { set("key_text_size", "24") } shouldBe
                "other:\n  style/key_text_size: 30\npatch:\n  style/key_text_size: 24\n"
            ThemePatchFile(File.createTempFile("t", ".yaml").apply { writeText("other:\n  style/x: 1\n"); deleteOnExit() })
                .get("x") shouldBe null
        }

        "CRLF line endings are kept" {
            val text = "patch:\r\n  style/key_font: [ a.ttf ]\r\n"
            edit(text) {
                set("key_font", "[ b.ttf ]")
                set("label_font", "[ c.ttf ]")
            } shouldBe "patch:\r\n  style/key_font: [ b.ttf ]\r\n  style/label_font: [ c.ttf ]\r\n"
        }

        "a hash inside quotes is not a comment" {
            val text = "patch:\n  style/key_font: [ \"a \\\" #b.ttf\" ] # note\n"
            val file = File.createTempFile("t", ".yaml").apply {
                writeText(text)
                deleteOnExit()
            }
            ThemePatchFile(file).get("key_font") shouldBe "[ \"a \\\" #b.ttf\" ]"
        }

        "a missing patch block is created" {
            edit("# only a comment\n") { set("key_text_size", "22") } shouldBe
                "# only a comment\n\npatch:\n  style/key_text_size: 22\n"
        }
    })
