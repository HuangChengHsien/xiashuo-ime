/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.phrase.RimeWorkspaceStamp
import com.osfans.trime.data.sync.RimeDataSync
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemePatchFile
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import timber.log.Timber
import java.io.File

/**
 * Chooses fonts and text sizes of the active theme by editing `style/…` entries of its
 * `<id>.custom.yaml`. Only that theme is recompiled afterwards, which takes about a second.
 */
class FontSettingsFragment : Fragment() {
    private data class FontField(val key: String, val title: String, val current: (GeneralStyle) -> List<String>)

    private data class SizeField(val key: String, val title: String, val current: (GeneralStyle) -> Float)

    private val fontFields = listOf(
        FontField("candidate_font", "候選字") { it.candidateFont },
        FontField("comment_font", "編碼提示／註解") { it.commentFont },
        FontField("label_font", "候選序號") { it.labelFont },
        FontField("text_font", "組字區") { it.textFont },
        FontField("key_font", "按鍵") { it.keyFont },
        FontField("popup_font", "按鍵彈出提示") { it.popupFont },
        FontField("symbol_font", "按鍵上方符號") { it.symbolFont },
        FontField("latin_font", "西文與數字（各區優先使用）") { it.latinFont },
        FontField("hanb_font", "擴充漢字補字") { it.hanbFont },
    )

    private val sizeFields = listOf(
        SizeField("candidate_text_size", "候選字") { it.candidateTextSize },
        SizeField("comment_text_size", "編碼提示／註解") { it.commentTextSize },
        SizeField("label_text_size", "候選序號") { it.labelTextSize },
        SizeField("key_text_size", "按鍵") { it.keyTextSize },
        SizeField("key_long_text_size", "長標籤按鍵") { it.keyLongTextSize },
        SizeField("symbol_text_size", "按鍵上方符號") { it.symbolTextSize },
        SizeField("popup_text_size", "按鍵彈出提示") { it.popupTextSize },
    )

    // A session of its own; see CustomPhraseFragment.
    private val rime by lazy { RimeDaemon.createSession(SESSION) }
    private lateinit var root: LinearLayout
    private var busy = false

    private val importFonts = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) import(uris)
    }

    private val fontDir get() = File(DataManager.userDataDir, "fonts")

    private val themeId get() = ThemeManager.prefs.selectedTheme.getValue()

    private val patchFile get() = File(DataManager.userDataDir, "$themeId.custom.yaml")

    private val readOnly get() = RimeDataSync.usesExternalSync(requireContext())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(24))
        }
        render()
        return ScrollView(ctx).apply { addView(root) }
    }

    override fun onDestroy() {
        RimeDaemon.destroySession(SESSION)
        super.onDestroy()
    }

    private fun render() {
        val ctx = context ?: return
        root.removeAllViews()
        val style = ThemeManager.activeTheme.generalStyle
        val patch = runCatching { ThemePatchFile(patchFile) }
        root.addView(TextView(ctx).apply {
            text = "主題：$themeId。修改會寫入 ${patchFile.name}，約 1 秒後套用到鍵盤。" +
                when {
                    readOnly -> "\n目前使用外部資料夾同步，下次部署會覆蓋這裡的修改，請改在外部資料夾編輯。"
                    patch.isFailure -> "\n${patch.exceptionOrNull()?.message}"
                    else -> ""
                }
        })
        val editable = !readOnly && patch.isSuccess && !busy

        section("已匯入的字型")
        val fonts = fontFiles()
        line(if (fonts.isEmpty()) "fonts/ 裡還沒有字型檔。" else fonts.joinToString("\n") { it.name })
        root.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "匯入字型（.ttf／.otf）"
            isEnabled = editable
            setOnClickListener { importFonts.launch(arrayOf("*/*")) }
        })

        section("字型")
        fontFields.forEach { field ->
            val custom = patch.getOrNull()?.get(field.key)
            val names = field.current(style)
            val present = names.filter { File(fontDir, it).isFile }
            // Trime silently falls back to the system font when none of the files exist.
            val note = when {
                names.isEmpty() -> ""
                present.isEmpty() -> "（fonts/ 裡沒有這個檔案，目前用系統字型）"
                present.size < names.size -> "（部分檔案不在 fonts/）"
                else -> ""
            }
            val value = names.ifEmpty { listOf("系統字型") }.joinToString("、") + note
            row(field.title, value + if (custom != null) "（已自訂）" else "", editable) { chooseFont(field, custom != null) }
        }

        section("字級")
        sizeFields.forEach { field ->
            val custom = patch.getOrNull()?.get(field.key)
            val size = field.current(style)
            val value = if (size > 0f) formatSize(size) else "依其他設定"
            row(field.title, value + if (custom != null) "（已自訂）" else "", editable) { chooseSize(field, size, custom != null) }
        }
    }

    private fun section(title: String) = root.addView(TextView(requireContext()).apply {
        text = title
        textSize = 18f
        setPadding(0, context.dp(20), 0, context.dp(4))
    })

    private fun line(text: String) = root.addView(TextView(requireContext()).apply { this.text = text })

    private fun row(title: String, value: String, enabled: Boolean, onClick: () -> Unit) =
        root.addView(LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, context.dp(10), 0, context.dp(10))
            isClickable = enabled
            alpha = if (enabled) 1f else 0.5f
            if (enabled) setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = title
                textSize = 16f
            })
            addView(TextView(context).apply { text = value })
        })

    private fun fontFiles(): List<File> = fontDir.listFiles { f -> f.isFile && f.extension.lowercase() in FONT_EXTENSIONS }
        ?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun chooseFont(field: FontField, customized: Boolean) {
        val fonts = fontFiles()
        val labels = listOf("使用主題預設", "系統字型") + fonts.map { it.name }
        AlertDialog.Builder(requireContext())
            .setTitle("${field.title}字型")
            .setItems(labels.toTypedArray()) { _, which ->
                val value = when (which) {
                    0 -> null
                    1 -> ThemePatchFile.fontList(null)
                    else -> ThemePatchFile.fontList(fonts[which - 2].name)
                }
                if (which == 0 && !customized) return@setItems
                apply(field.key, value)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseSize(field: SizeField, current: Float, customized: Boolean) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (current > 0f) formatSize(current) else "")
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("${field.title}字級")
            .setMessage("數字越大字越大，建議 6 到 60。")
            .setView(LinearLayout(ctx).apply {
                setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            })
            .setPositiveButton("儲存", null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (customized) setNeutralButton("使用主題預設") { _, _ -> apply(field.key, null) } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val size = input.text.toString().toFloatOrNull()
                if (size == null || size !in 4f..80f) {
                    ctx.toast("請輸入 4 到 80 之間的數字")
                    return@setOnClickListener
                }
                dialog.dismiss()
                apply(field.key, formatSize(size))
            }
        }
        dialog.show()
    }

    /** Writes the patch, keeps the next start from rebuilding every schema, and reloads the theme. */
    private fun apply(key: String, value: String?) {
        val ctx = requireContext().applicationContext
        val session = rime
        val file = patchFile
        val id = themeId
        busy = true
        render()
        lifecycleScope.launch {
            val error = runCatching {
                val pending = session.runOnReady { RimeWorkspaceStamp.hasPendingChanges() }
                val others = RimeWorkspaceStamp.lastModifiedExcept(file)
                withContext(Dispatchers.IO) { ThemePatchFile(file).apply { set(key, value) }.save() }
                if (!pending) session.runOnReady { RimeWorkspaceStamp.markCurrent(file, others) }
                session.runOnReady { withContext(Dispatchers.Main) { ThemeManager.selectTheme(id) } }
            }.exceptionOrNull()
            error?.let { Timber.w(it, "font setting failed") }
            ctx.toast(error?.let { "套用失敗：${it.message}" } ?: "已套用")
            busy = false
            if (isAdded) render()
        }
    }

    private fun import(uris: List<Uri>) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                fontDir.mkdirs()
                uris.map { uri -> runCatching { copyFont(uri) } }
            }
            val ok = results.mapNotNull { it.getOrNull() }
            val failed = results.mapNotNull { it.exceptionOrNull()?.message }
            ctx.toast(
                buildString {
                    if (ok.isNotEmpty()) append("已匯入：${ok.joinToString("、")}")
                    if (failed.isNotEmpty()) append((if (isNotEmpty()) "\n" else "") + failed.joinToString("\n"))
                },
            )
            if (isAdded) render()
        }
    }

    /** Copies one font into fonts/ after checking that Android can load it. */
    private fun copyFont(uri: Uri): String {
        val resolver = requireContext().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: error("無法讀取檔名")
        require(File(name).extension.lowercase() in FONT_EXTENSIONS) { "$name 不是 .ttf／.otf／.ttc 字型" }
        require(!name.contains('/')) { "$name 檔名不合法" }
        val temp = File(fontDir, ".$name.importing")
        try {
            requireNotNull(resolver.openInputStream(uri)) { "無法開啟 $name" }.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            runCatching { Typeface.createFromFile(temp) }.getOrNull()?.takeIf { it != Typeface.DEFAULT }
                ?: error("$name 無法載入，可能不是有效的字型")
            val target = File(fontDir, name)
            if (target.isFile) {
                // Never replace a font silently: a theme may already use this name.
                if (target.readBytes().contentEquals(temp.readBytes())) return "$name（已存在）"
                error("fonts/ 已有不同內容的 $name，請先改檔名再匯入")
            }
            check(temp.renameTo(target)) { "無法儲存 $name" }
            return name
        } finally {
            temp.delete()
        }
    }

    private fun formatSize(size: Float) = if (size % 1f == 0f) size.toInt().toString() else size.toString()

    companion object {
        private const val SESSION = "FontSettingsFragment"
        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
    }
}
