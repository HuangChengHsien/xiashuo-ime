/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import java.io.File

/** One user phrase: the text to type, its code and (for custom_phrase.txt only) its weight. */
data class CustomPhrase(val text: String, val code: String, val weight: Int? = null)

/**
 * The two hand-edited phrase files of the rime-tw schemas. Editing keeps every comment, header
 * and unrelated line exactly as it was; only phrase lines are replaced, removed or appended.
 */
enum class CustomPhraseKind(
    val title: String,
    val fileName: String,
    val hasWeight: Boolean,
    /** Characters a code may contain; Boshiamy codes also use punctuation keys. */
    private val codeChars: Regex,
    val codeHint: String,
    private val template: String,
) {
    PINYIN(
        title = "雙拼＋／注音＋",
        fileName = "custom_phrase.txt",
        hasWeight = true,
        // Syllables may be separated by single spaces, e.g. "ma jx yi yr".
        codeChars = Regex("[a-z0-9;',./\\[\\]-]+( [a-z0-9;',./\\[\\]-]+)*"),
        codeHint = "小寫英文與數字，音節間可空一格，不必是正確的雙拼碼",
        template = """
            # Rime table
            # coding: utf-8
            #@/db_name	custom_phrase.txt
            #@/db_type	tabledb
            #
            # 自訂短語（雙拼＋、注音＋使用）
            # 格式：文字<Tab>編碼<Tab>權重
            #
        """.trimIndent() + "\n",
    ),
    XIAMI(
        title = "蝦米",
        fileName = "openxiami_CustomWord.dict.yaml",
        hasWeight = false,
        codeChars = Regex("[a-z0-9,.'\\[\\];?]+"),
        codeHint = "小寫英文、數字與 , . ' [ ] ; ?",
        template = """
            # Rime schema 中州輸入法的字碼檔
            # encoding: utf-8
            #
            # 自定詞字典（由 lua_translator@liu_custom_word_translator 載入）
            # 格式：詞條<Tab>編碼
            #
            ---
            name: openxiami_CustomWord
            version: "1"
            sort: original
            ...
        """.trimIndent() + "\n",
    ),
    ;

    fun file(userDataDir: File) = File(userDataDir, fileName)

    /** Returns an error message, or null when the phrase can be saved. */
    fun validate(phrase: CustomPhrase): String? = when {
        phrase.text.isBlank() -> "請輸入詞語"
        phrase.text.any { it == '\t' || it == '\n' || it == '\r' } -> "詞語不能包含 Tab 或換行"
        phrase.code.isEmpty() -> "請輸入編碼"
        !codeChars.matches(phrase.code) -> "編碼格式不符：$codeHint"
        else -> null
    }

    fun load(userDataDir: File): CustomPhraseDocument {
        val file = file(userDataDir)
        val text = if (file.isFile) file.readText() else template
        return CustomPhraseDocument(
            this,
            text.removeSuffix("\n").split('\n').toMutableList(),
            endsWithNewline = text.isEmpty() || text.endsWith("\n"),
        )
    }
}

/** A phrase file held as its original lines, with the indexes of the lines that are phrases. */
class CustomPhraseDocument(
    val kind: CustomPhraseKind,
    private val lines: MutableList<String>,
    private val endsWithNewline: Boolean,
) {
    /** New lines follow the file's existing line ending so Windows-edited files stay CRLF. */
    private val lineEnd = if (lines.any { it.endsWith("\r") }) "\r" else ""
    data class Entry(val line: Int, val phrase: CustomPhrase)

    val entries: List<Entry>
        get() {
            // Boshiamy phrases start after the YAML header ends with "..."; custom_phrase.txt has
            // no YAML header, so every line may hold a phrase.
            var inData = kind == CustomPhraseKind.PINYIN || lines.none { it.trimEnd('\r') == "..." }
            return lines.mapIndexedNotNull { index, raw ->
                val line = raw.trimEnd('\r')
                if (line == "...") {
                    inData = true
                    return@mapIndexedNotNull null
                }
                if (!inData || line.isEmpty() || line.startsWith("#")) return@mapIndexedNotNull null
                val fields = line.split('\t')
                if (fields.size < 2 || fields[0].isEmpty() || fields[1].isEmpty()) return@mapIndexedNotNull null
                Entry(index, CustomPhrase(fields[0], fields[1], fields.getOrNull(2)?.trim()?.toIntOrNull()))
            }
        }

    fun contains(phrase: CustomPhrase, except: Entry? = null) =
        entries.any { it != except && it.phrase.text == phrase.text && it.phrase.code == phrase.code }

    fun add(phrase: CustomPhrase) {
        lines += format(phrase)
    }

    fun replace(entry: Entry, phrase: CustomPhrase) {
        lines[entry.line] = format(phrase)
    }

    fun remove(entry: Entry) {
        lines.removeAt(entry.line)
    }

    private fun format(phrase: CustomPhrase) =
        (if (kind.hasWeight) "${phrase.text}\t${phrase.code}\t${phrase.weight ?: DEFAULT_WEIGHT}" else "${phrase.text}\t${phrase.code}") +
            lineEnd

    /**
     * Writes through a temporary file. The previous version is kept as a timestamped backup in
     * [backupDir], of which the newest [BACKUPS_KEPT] survive; that directory must lie outside
     * the Rime user directory, where any new entry would make Rime rebuild every schema.
     */
    fun save(userDataDir: File, backupDir: File) {
        val target = kind.file(userDataDir)
        val temp = File(userDataDir, ".${kind.fileName}.saving")
        temp.writeText(lines.joinToString("\n", postfix = if (endsWithNewline) "\n" else ""))
        if (target.isFile) {
            val dir = backupDir.apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date())
            target.copyTo(File(dir, "${kind.fileName}.$stamp.bak"), overwrite = true)
            dir.listFiles { file -> file.name.startsWith("${kind.fileName}.") && file.name.endsWith(".bak") }
                ?.sortedByDescending { it.name }
                ?.drop(BACKUPS_KEPT)
                ?.forEach { it.delete() }
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            error("無法寫入 ${kind.fileName}")
        }
    }

    companion object {
        const val DEFAULT_WEIGHT = 100
        private const val BACKUPS_KEPT = 5
    }
}
