/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.phrase.CustomPhrase
import com.osfans.trime.data.phrase.CustomPhraseDocument
import com.osfans.trime.data.phrase.CustomPhraseKind
import com.osfans.trime.data.sync.RimeDataSync
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Edits the personal phrases of the pinyin/bopomofo schemas and of Boshiamy in one place. */
class CustomPhraseFragment : Fragment() {
    // A session of its own: MainViewModel's session is keyed by class name, so a settings
    // window being destroyed can drop the one a newer window still uses.
    private val rime by lazy { RimeDaemon.createSession(SESSION) }
    private var kind = CustomPhraseKind.PINYIN
    private lateinit var document: CustomPhraseDocument
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var search: EditText
    private lateinit var addButton: Button
    private var readOnly = false

    /** Set while a save runs; edits wait so two saves never race over the same file. */
    private var saving: Job? = null

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) export(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(STATE_KIND)?.let { kind = CustomPhraseKind.valueOf(it) }
    }

    override fun onDestroy() {
        // A save in flight still needs the session to apply the change; release it afterwards.
        saving?.invokeOnCompletion { RimeDaemon.destroySession(SESSION) } ?: RimeDaemon.destroySession(SESSION)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_KIND, kind.name)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        readOnly = RimeDataSync.usesExternalSync(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(24))
        }
        root.addView(RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            CustomPhraseKind.entries.forEach { option ->
                addView(RadioButton(ctx).apply {
                    id = View.generateViewId()
                    text = option.title
                    isChecked = option == kind
                    setPadding(0, 0, ctx.dp(16), 0)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked && option != kind) {
                            kind = option
                            reload()
                        }
                    }
                })
            }
        })
        if (readOnly) {
            root.addView(TextView(ctx).apply {
                text = "目前使用外部資料夾同步：下次部署時，外部資料夾的版本會覆蓋這裡的修改。" +
                    "請直接編輯外部資料夾裡的檔案；這裡只能查看與匯出備份。"
                setPadding(0, ctx.dp(8), 0, ctx.dp(8))
            })
        }
        search = EditText(ctx).apply {
            hint = "搜尋詞語或編碼"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = render()
            })
        }
        root.addView(search)
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        addButton = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "新增詞語"
            isEnabled = !readOnly
            setOnClickListener { if (saving == null) edit(null) }
        }
        actions.addView(addButton)
        actions.addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = "匯出備份"
            setOnClickListener {
                val date = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date())
                exportBackup.launch("蝦說自訂詞語-$date.zip")
            }
        })
        root.addView(actions)
        status = TextView(ctx).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) }
        root.addView(status)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        reload()
        return ScrollView(ctx).apply { addView(root) }
    }

    private fun reload() {
        document = kind.load(DataManager.userDataDir)
        if (::list.isInitialized) render()
    }

    private fun render() {
        val ctx = context ?: return
        val query = search.text.toString().trim()
        val entries = document.entries
        val shown = entries.filter { query.isEmpty() || query in it.phrase.text || query in it.phrase.code }
        val matched = if (query.isNotEmpty()) "，符合 ${shown.size} 筆" else ""
        status.text = "${kind.fileName} · 共 ${entries.size} 筆$matched\n點一下詞語可修改或刪除；儲存後會自動套用。"
        list.removeAllViews()
        shown.forEach { entry ->
            list.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, ctx.dp(10), 0, ctx.dp(10))
                isClickable = !readOnly
                if (!readOnly) setOnClickListener { if (saving == null) edit(entry) }
                addView(TextView(ctx).apply {
                    text = entry.phrase.text
                    textSize = 17f
                })
                addView(TextView(ctx).apply {
                    text = "編碼 ${entry.phrase.code}" + (entry.phrase.weight?.let { "　權重 $it" } ?: "")
                })
            })
        }
        if (shown.isEmpty()) {
            list.addView(TextView(ctx).apply {
                text = if (entries.isEmpty()) "還沒有自訂詞語。" else "沒有符合的詞語。"
                setPadding(0, ctx.dp(16), 0, 0)
            })
        }
    }

    private fun edit(entry: CustomPhraseDocument.Entry?) {
        val ctx = requireContext()
        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
        }
        fun field(label: String, value: String, type: Int): EditText {
            form.addView(TextView(ctx).apply {
                text = label
                setPadding(0, ctx.dp(8), 0, 0)
            })
            return EditText(ctx).apply {
                setText(value)
                inputType = type
                isSingleLine = true
            }.also { form.addView(it) }
        }
        val textField = field("詞語", entry?.phrase?.text.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val codeField = field(
            "編碼（${kind.codeHint}）",
            entry?.phrase?.code.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        val weightField = if (kind.hasWeight) {
            field(
                "權重（越大越前面，預設 ${CustomPhraseDocument.DEFAULT_WEIGHT}）",
                entry?.phrase?.weight?.toString().orEmpty(),
                InputType.TYPE_CLASS_NUMBER,
            )
        } else {
            null
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (entry == null) "新增${kind.title}詞語" else "修改詞語")
            .setView(form)
            .setPositiveButton("儲存", null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (entry != null) setNeutralButton("刪除") { _, _ -> confirmDelete(entry) } }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val phrase = CustomPhrase(
                    textField.text.toString().trim(),
                    codeField.text.toString().trim().lowercase(Locale.ROOT),
                    weightField?.text?.toString()?.trim()?.toIntOrNull(),
                )
                val error = kind.validate(phrase)
                    ?: if (document.contains(phrase, except = entry)) "已經有相同的詞語和編碼" else null
                if (error != null) {
                    ctx.toast(error)
                    return@setOnClickListener
                }
                if (entry == null) document.add(phrase) else document.replace(entry, phrase)
                dialog.dismiss()
                saveAndApply()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(entry: CustomPhraseDocument.Entry) {
        AlertDialog.Builder(requireContext())
            .setTitle("刪除「${entry.phrase.text}」？")
            .setMessage("編碼 ${entry.phrase.code}")
            .setPositiveButton("刪除") { _, _ ->
                document.remove(entry)
                saveAndApply()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Saves the file and restarts Rime so the schemas read it again; no full deploy is needed.
     * Runs in Rime's scope rather than the fragment's, so leaving the page cannot cut it short.
     */
    private fun saveAndApply() {
        val ctx = requireContext().applicationContext
        val session = rime
        val pending = document
        addButton.isEnabled = false
        render()
        status.text = "正在儲存並套用…"
        saving = session.lifecycleScope.launch {
            val saveError = withContext(Dispatchers.IO) { runCatching { pending.save(DataManager.userDataDir) }.exceptionOrNull() }
            val applyError = if (saveError == null) runCatching { session.runOnReady { updateConfig() } }.exceptionOrNull() else null
            (saveError ?: applyError)?.let { Timber.w(it, "custom phrase save failed") }
            withContext(Dispatchers.Main) {
                ctx.toast(
                    when {
                        saveError != null -> "儲存失敗，檔案未變更：${saveError.message}"
                        applyError != null -> "已儲存，但尚未套用；請在鍵盤選單按「更新設定」"
                        else -> "已儲存並套用"
                    },
                )
                saving = null
                if (isAdded) {
                    addButton.isEnabled = !readOnly
                    reload()
                }
            }
        }
    }

    private fun export(uri: Uri) {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val files = CustomPhraseKind.entries.map { it.file(DataManager.userDataDir) }.filter { it.isFile }
                    ctx.contentResolver.openOutputStream(uri)!!.use { output ->
                        ZipOutputStream(output).use { zip ->
                            files.forEach { file ->
                                zip.putNextEntry(ZipEntry(file.name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                    files.size
                }
            }
            ctx.toast(count.fold({ "已匯出 $it 個檔案" }, { "匯出失敗：${it.message}" }))
        }
    }

    companion object {
        private const val STATE_KIND = "kind"
        private const val SESSION = "CustomPhraseFragment"
    }
}
