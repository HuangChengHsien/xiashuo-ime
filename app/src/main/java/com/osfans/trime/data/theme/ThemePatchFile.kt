/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import java.io.File

/**
 * Edits `style/<key>` entries in a theme's `<id>.custom.yaml` patch, e.g.
 * `style/candidate_font: [ jf-lanyanghei-1.0-w7.otf ]`, keeping every other line, comment and
 * trailing comment as it was. A patch written as a nested `style:` map is not edited, since
 * rewriting it could lose the user's own structure.
 */
class ThemePatchFile(val file: File) {
    private val lines: MutableList<String>
    private val endsWithNewline: Boolean

    init {
        val text = if (file.isFile) file.readText() else HEADER
        lines = text.removeSuffix("\n").split('\n').toMutableList()
        endsWithNewline = text.isEmpty() || text.endsWith("\n")
        check(lines.none { NESTED_STYLE.matches(it.trimEnd('\r')) }) {
            "${file.name} 以巢狀 style: 撰寫，請手動編輯"
        }
    }

    private fun entryRegex(key: String) = Regex("^(\\s+)[\"']?style/${Regex.escape(key)}[\"']?\\s*:\\s*(.*)$")

    private fun patchLine() = lines.indexOfFirst { PATCH.matches(it.trimEnd('\r')) }

    /** The raw YAML value of `style/<key>` in this patch, or null when the theme default applies. */
    fun get(key: String): String? {
        val regex = entryRegex(key)
        return lines.firstNotNullOfOrNull { line ->
            regex.find(line.trimEnd('\r'))?.groupValues?.get(2)?.let(::withoutComment)?.trim()
        }
    }

    /** Sets `style/<key>` to a YAML [value], or removes it so the theme's own value applies. */
    fun set(key: String, value: String?) {
        val regex = entryRegex(key)
        val index = lines.indexOfFirst { regex.containsMatchIn(it.trimEnd('\r')) }
        if (index >= 0) {
            if (value == null) {
                lines.removeAt(index)
            } else {
                val line = lines[index].trimEnd('\r')
                val match = requireNotNull(regex.find(line))
                val comment = commentOf(match.groupValues[2])
                lines[index] = "${match.groupValues[1]}style/$key: $value$comment"
            }
            return
        }
        if (value == null) return
        var patch = patchLine()
        if (patch < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines += ""
            lines += "patch:"
            patch = lines.lastIndex
        }
        // Insert after the last indented line of the patch block.
        var end = patch
        for (i in patch + 1 until lines.size) {
            val line = lines[i].trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (line.first().isWhitespace()) end = i else break
        }
        val indent = lines.subList(patch + 1, end + 1)
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
            ?.takeWhile { it.isWhitespace() } ?: "  "
        lines.add(end + 1, "${indent}style/$key: $value")
    }

    fun save() {
        val temp = File(file.parentFile, ".${file.name}.saving")
        temp.writeText(lines.joinToString("\n", postfix = if (endsWithNewline) "\n" else ""))
        if (!temp.renameTo(file)) {
            temp.delete()
            error("無法寫入 ${file.name}")
        }
    }

    private fun withoutComment(value: String) = value.substring(0, commentStart(value))

    private fun commentOf(value: String) = value.substring(commentStart(value)).let { if (it.isEmpty()) "" else " ${it.trim()}" }

    /** A `#` starts a comment only outside quotes and after whitespace, as in YAML. */
    private fun commentStart(value: String): Int {
        var quote: Char? = null
        value.forEachIndexed { i, c ->
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '#' && (i == 0 || value[i - 1].isWhitespace()) -> return i
            }
        }
        return value.length
    }

    companion object {
        private val PATCH = Regex("^patch:\\s*(#.*)?$")
        private val NESTED_STYLE = Regex("^\\s+style:\\s*(#.*)?$")
        private val HEADER = """
            # 由蝦說輸入法「字型與字級」設定頁維護，也可以手動編輯。
            # Trime 3.3 的字型欄位必須寫成清單，字型檔放在 Rime 使用者目錄的 fonts/。
            patch:
        """.trimIndent() + "\n"

        /** A font list value; names with characters outside a safe set are quoted. */
        fun fontList(name: String?): String = when {
            name == null -> "[]"
            name.all { it.isLetterOrDigit() || it in "._-" } -> "[ $name ]"
            else -> "[ \"${name.replace("\\", "\\\\").replace("\"", "\\\"")}\" ]"
        }
    }
}
