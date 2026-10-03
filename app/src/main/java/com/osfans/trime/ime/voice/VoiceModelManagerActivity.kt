/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Imports, selects and deletes the offline speech models used by [VoiceInputController]. */
class VoiceModelManagerActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private val actionButtons = mutableListOf<Button>()
    private var busy = false
    private var importTarget: VoiceModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importTarget = savedInstanceState?.getString(STATE_TARGET)?.let(VoiceModels::find)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(36, 48, 36, 48)
        }
        root.addView(TextView(this).apply {
            text = "語音模型管理"
            textSize = 22f
        })
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }
        root.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        setContentView(ScrollView(this).apply {
            addView(root)
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    setPadding(0, bars.top, 0, bars.bottom)
                }
                insets
            }
        })
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        importTarget?.let { outState.putString(STATE_TARGET, it.id) }
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        actionButtons.clear()
        val selected = VoiceModels.selectedEngine(this)
        status.text = "目前使用：${VoiceModels.engineName(selected)}\n" +
            "模型存放於 ${File(getExternalFilesDir(null), "models").absolutePath}\n" +
            "先用瀏覽器下載模型檔，再按「匯入檔案」或「匯入資料夾」。"
        VoiceModels.all.forEach { model -> list.addView(modelRow(model, selected)) }
    }

    private fun modelRow(model: VoiceModel, selected: String): LinearLayout {
        val installed = model.isInstalled(this)
        val bytes = model.diskBytes(this)
        val state = when {
            installed -> "已安裝，占用 ${formatSize(bytes)}"
            bytes > 0 -> "不完整，尚缺 ${model.missingFiles(this).joinToString("、") { it.path }}"
            else -> "未安裝"
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 28, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = (if (model.id == selected) "● " else "") + model.title
            textSize = 19f
        })
        row.addView(TextView(this).apply {
            text = "$state\n${model.note}\n下載：${model.downloadUrl}"
            textSize = 14f
            setTextIsSelectable(true)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label
                isEnabled = enabled && !busy
                setOnClickListener { if (!busy) onClick() }
                actionButtons += this
            })
        }
        action("匯入檔案") { pickFiles(model) }
        action("匯入資料夾") { pickFolder(model) }
        if (installed && model.id != selected) action("使用") { select(model) }
        if (bytes > 0) action("刪除") { confirmDelete(model, selected) }
        row.addView(actions)
        return row
    }

    private fun select(model: VoiceModel) {
        VoiceModels.setSelectedEngine(this, model.id)
        refresh()
    }

    private fun confirmDelete(model: VoiceModel, selected: String) {
        val bytes = model.diskBytes(this)
        val fallback = if (model.id == selected) "\n刪除後語音引擎會改回 Android 系統語音。" else ""
        AlertDialog.Builder(this)
            .setTitle("刪除 ${model.title}？")
            .setMessage("將釋放 ${formatSize(bytes)}。之後需要時可重新匯入。$fallback")
            .setPositiveButton("刪除") { _, _ -> delete(model) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun delete(model: VoiceModel) {
        runBusy("正在刪除 ${model.title}…") {
            val dir = model.directory(this)
            check(dir.deleteRecursively()) { "部分檔案無法刪除：${dir.absolutePath}" }
            if (VoiceModels.selectedEngine(this) == model.id) {
                VoiceModels.setSelectedEngine(this, VoiceModels.ENGINE_GOOGLE)
            }
            Log.i(TAG, "deleted model=${model.id}")
            "已刪除 ${model.title}"
        }
    }

    private fun pickFiles(model: VoiceModel) {
        importTarget = model
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            },
            REQUEST_FILES,
        )
    }

    private fun pickFolder(model: VoiceModel) {
        importTarget = model
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
    }

    @Deprecated("Activity result callback keeps this screen free of extra AndroidX dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val model = importTarget ?: return
        if (resultCode != RESULT_OK || data == null || busy) return
        when (requestCode) {
            REQUEST_FILES -> {
                val uris = buildList {
                    data.clipData?.let { clips -> for (i in 0 until clips.itemCount) add(clips.getItemAt(i).uri) }
                    data.data?.let(::add)
                }.distinct()
                if (uris.isEmpty()) return
                runBusy("正在匯入 ${model.title}…") {
                    val sources = uris.map { uri -> Source(uri, displayName(uri)) }
                    importSources(model, sources.map { source ->
                        val spec = model.files.find { it.name == source.name }
                            ?: error("${model.title} 不需要這個檔案：${source.name}")
                        spec to source.uri
                    })
                }
            }
            REQUEST_FOLDER -> {
                val tree = data.data ?: return
                runBusy("正在搜尋資料夾內的 ${model.title} 檔案…") {
                    val found = findInTree(tree, model.files.map { it.name }.toSet())
                    val matches = model.files.mapNotNull { spec -> found[spec.name]?.let { spec to it } }
                    require(matches.isNotEmpty()) { "這個資料夾裡沒有 ${model.title} 需要的檔案" }
                    importSources(model, matches)
                }
            }
        }
    }

    private data class Source(val uri: Uri, val name: String)

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: error("無法讀取檔名")

    /** Walks a picked folder breadth-first (three levels) and returns the first document for each wanted name. */
    private fun findInTree(tree: Uri, wanted: Set<String>): Map<String, Uri> {
        val found = mutableMapOf<String, Uri>()
        var level = listOf(DocumentsContract.getTreeDocumentId(tree))
        repeat(3) {
            val next = mutableListOf<String>()
            for (parent in level) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                contentResolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val name = cursor.getString(1)
                        if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) next += id
                        else if (name in wanted && name !in found) found[name] = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    }
                }
            }
            if (found.size == wanted.size || next.isEmpty()) return found
            level = next
        }
        return found
    }

    private fun importSources(model: VoiceModel, sources: List<Pair<VoiceModelFile, Uri>>): String {
        sources.forEach { (spec, uri) -> importFile(model, spec, uri) }
        val missing = model.missingFiles(this)
        return if (missing.isEmpty()) "${model.title} 匯入完成，可以使用"
        else "已匯入 ${sources.size} 個檔案；${model.title} 尚缺：${missing.joinToString("、") { it.path }}"
    }

    private fun importFile(model: VoiceModel, spec: VoiceModelFile, uri: Uri) {
        val target = File(model.directory(this), spec.path)
        val dir = requireNotNull(target.parentFile).apply { mkdirs() }
        val staging = File(dir, ".${spec.name}.importing")
        val backup = File(dir, ".${spec.name}.previous")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "無法開啟 ${spec.name}" }
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= spec.maxSize) { "${spec.name} 檔案過大" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (copied % (32L * 1024 * 1024) < count) showStatus("正在匯入 ${spec.name}：${copied / (1024 * 1024)} MiB")
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            spec.size?.let { require(copied == it) { "${spec.name} 大小不符（$copied），原模型未更動" } }
            spec.sha256?.takeIf { it.isNotEmpty() }?.let { require(hash == it) { "${spec.name} SHA-256 不符，原模型未更動" } }
            if (target.exists()) {
                require(!backup.exists() || backup.delete()) { "無法清理舊備份：${spec.name}" }
                require(target.renameTo(backup)) { "無法備份現有檔案：${spec.name}" }
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("無法完成匯入：${spec.name}")
            }
            backup.delete()
            Log.i(TAG, "imported model=${model.id} file=${spec.path} bytes=$copied sha256=$hash")
        } finally {
            staging.delete()
        }
    }

    private fun runBusy(message: String, work: () -> String) {
        busy = true
        actionButtons.forEach { it.isEnabled = false }
        status.text = message
        Thread({
            val result = try {
                work()
            } catch (error: Exception) {
                Log.w(TAG, "model operation failed", error)
                "失敗：${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    refresh()
                    status.text = result + "\n\n" + status.text
                }
            }
        }, "VoiceModelManager").start()
    }

    private fun showStatus(message: String) = runOnUiThread { status.text = message }

    private fun formatSize(bytes: Long) = when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    }

    companion object {
        private const val TAG = "VoiceModelManager"
        private const val REQUEST_FILES = 1
        private const val REQUEST_FOLDER = 2
        private const val STATE_TARGET = "import_target"
    }
}
