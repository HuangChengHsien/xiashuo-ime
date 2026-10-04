/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.osfans.trime.ime.voice.VoiceModel
import com.osfans.trime.ime.voice.VoiceModelDownloadWorker
import com.osfans.trime.ime.voice.VoiceModelFile
import com.osfans.trime.ime.voice.VoiceModelStore
import com.osfans.trime.ime.voice.VoiceModels
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp
import timber.log.Timber

/** Lists the offline speech models with download, import, select and delete actions. */
class VoiceModelsFragment : Fragment() {
    private lateinit var list: LinearLayout
    private val workInfo = mutableMapOf<String, WorkInfo?>()
    private val importing = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var importTarget: VoiceModel? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val model = importTarget ?: return@registerForActivityResult
        if (uris.isNotEmpty()) importFiles(model, uris.map { it to displayName(it) })
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val model = importTarget ?: return@registerForActivityResult
        if (tree != null) importFolder(model, tree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importTarget = savedInstanceState?.getString(STATE_TARGET)?.let(VoiceModels::find)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        importTarget?.let { outState.putString(STATE_TARGET, it.id) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val ctx = requireContext()
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(24))
        }
        return ScrollView(ctx).apply {
            clipToPadding = false
            addView(list)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val manager = WorkManager.getInstance(requireContext())
        VoiceModels.all.forEach { model ->
            manager.getWorkInfosForUniqueWorkLiveData(VoiceModelDownloadWorker.workName(model))
                .observe(viewLifecycleOwner) { infos ->
                    workInfo[model.id] = infos.lastOrNull()
                    render()
                }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        val selected = VoiceModels.selectedEngine(ctx)
        list.removeAllViews()
        list.addView(TextView(ctx).apply {
            text = "語音辨識都在手機上進行，錄音不會上傳；只有下載模型時會連網。"
            setPadding(0, 0, 0, ctx.dp(8))
        })
        VoiceModels.all.forEach { list.addView(modelCard(it, selected)) }
        list.addView(systemCard(selected))
    }

    private fun card(title: String, inUse: Boolean): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(16), 0, ctx.dp(4))
            addView(TextView(ctx).apply {
                text = if (inUse) "● $title　使用中" else "○ $title"
                textSize = 18f
            })
        }
    }

    private fun LinearLayout.line(text: String) = addView(TextView(context).apply {
        this.text = text
        setPadding(0, context.dp(2), 0, 0)
    })

    private fun LinearLayout.actions(vararg buttons: Pair<String, () -> Unit>) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        buttons.forEach { (label, action) ->
            row.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                setOnClickListener { action() }
            })
        }
        addView(row)
    }

    private fun modelCard(model: VoiceModel, selected: String): View {
        val ctx = requireContext()
        val installed = model.isInstalled(ctx)
        val info = workInfo[model.id]
        val card = card(model.title, model.id == selected && installed)
        val importStatus = importing[model.id]
        when {
            importStatus != null -> {
                card.line(importStatus)
                card.addView(ProgressBar(ctx).apply { isIndeterminate = true })
            }
            info?.state == WorkInfo.State.RUNNING -> {
                val done = info.progress.getLong(VoiceModelDownloadWorker.KEY_DONE, 0)
                val total = info.progress.getLong(VoiceModelDownloadWorker.KEY_TOTAL, model.totalBytes)
                val verifying = info.progress.getBoolean(VoiceModelDownloadWorker.KEY_VERIFYING, false)
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                card.line(if (verifying) "正在檢查檔案…" else "下載中 ${mb(done)}／${mb(total)} MB（$percent%）")
                card.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = percent
                })
                card.actions("取消下載" to { VoiceModelDownloadWorker.cancel(ctx, model) })
            }
            info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED -> {
                val waitingWifi = info.constraints.requiredNetworkType == NetworkType.UNMETERED
                val partial = model.diskBytes(ctx)
                val progress = if (partial > 0) "已下載 ${mb(partial)}／${mb(model.totalBytes)} MB，" else ""
                card.line(
                    progress + when {
                        waitingWifi -> "連上 Wi‑Fi 後繼續下載"
                        info.runAttemptCount > 0 -> "網路中斷，稍後自動續傳"
                        else -> "等待網路後開始下載"
                    },
                )
                card.actions(
                    "改用行動數據下載" to { VoiceModelDownloadWorker.enqueue(ctx, model, allowMetered = true) },
                    "取消下載" to { VoiceModelDownloadWorker.cancel(ctx, model) },
                )
            }
            installed -> {
                card.line("已安裝 · ${size(model.diskBytes(ctx))}")
                card.line(model.note)
                if (model.id == selected) card.actions("刪除" to { confirmDelete(model) })
                else card.actions("使用" to { select(model.id) }, "刪除" to { confirmDelete(model) })
            }
            else -> {
                val failure = info?.takeIf { it.state == WorkInfo.State.FAILED }
                    ?.outputData?.getString(VoiceModelDownloadWorker.KEY_ERROR)
                card.line(
                    when {
                        failure != null -> "下載失敗：$failure"
                        model.hasMismatchedFiles(ctx) -> "版本不符，可重新下載 · 約 ${size(model.totalBytes)}"
                        model.diskBytes(ctx) > 0 -> "尚未完成 · 約 ${size(model.totalBytes)}（下載會從中斷處續傳）"
                        else -> "未安裝 · 約 ${size(model.totalBytes)}"
                    },
                )
                card.line(model.note)
                val buttons = mutableListOf(
                    "下載" to { startDownload(model) },
                    "從檔案匯入" to { chooseImport(model) },
                )
                if (model.diskBytes(ctx) > 0) buttons += "刪除" to { confirmDelete(model) }
                card.actions(*buttons.toTypedArray())
            }
        }
        return card
    }

    private fun systemCard(selected: String): View {
        val card = card("Android 系統語音", selected == VoiceModels.ENGINE_GOOGLE)
        card.line("使用手機內建的本機辨識，不需下載；需手機已安裝臺灣華語離線語音。")
        if (selected != VoiceModels.ENGINE_GOOGLE) card.actions("使用" to { select(VoiceModels.ENGINE_GOOGLE) })
        return card
    }

    private fun select(engine: String) {
        VoiceModels.setSelectedEngine(requireContext(), engine)
        render()
    }

    private fun startDownload(model: VoiceModel) {
        val ctx = requireContext()
        val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
        when {
            connectivity.activeNetwork == null -> {
                VoiceModelDownloadWorker.enqueue(ctx, model, allowMetered = false)
                ctx.toast("目前沒有網路；連上 Wi‑Fi 後會自動下載")
            }
            connectivity.isActiveNetworkMetered -> AlertDialog.Builder(ctx)
                .setTitle("使用行動數據下載？")
                .setMessage("目前未連上 Wi‑Fi。${model.title} 約 ${size(model.totalBytes)}，可能產生數據費用。")
                .setPositiveButton("使用行動數據") { _, _ -> VoiceModelDownloadWorker.enqueue(ctx, model, allowMetered = true) }
                .setNeutralButton("等連上 Wi‑Fi") { _, _ -> VoiceModelDownloadWorker.enqueue(ctx, model, allowMetered = false) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            else -> VoiceModelDownloadWorker.enqueue(ctx, model, allowMetered = false)
        }
    }

    private fun confirmDelete(model: VoiceModel) {
        val ctx = requireContext()
        val inUse = VoiceModels.selectedEngine(ctx) == model.id
        AlertDialog.Builder(ctx)
            .setTitle("刪除 ${model.title}？")
            .setMessage(
                "將釋放 ${size(model.diskBytes(ctx))}，之後需要時可重新下載。" +
                    if (inUse) "\n刪除後語音引擎會改回 Android 系統語音。" else "",
            )
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) { runCatching { VoiceModelStore.delete(ctx, model) }.exceptionOrNull() }
                    ctx.toast(error?.message ?: "已刪除 ${model.title}")
                    render()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseImport(model: VoiceModel) {
        importTarget = model
        AlertDialog.Builder(requireContext())
            .setTitle("從檔案匯入 ${model.title}")
            .setMessage("需要的檔案：\n" + model.files.joinToString("\n") { it.path } + "\n\n檔案在同一個資料夾時，可直接選資料夾。")
            .setPositiveButton("選擇檔案") { _, _ -> pickFiles.launch(arrayOf("*/*")) }
            .setNeutralButton("選擇資料夾") { _, _ -> pickFolder.launch(null) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFiles(model: VoiceModel, picked: List<Pair<Uri, String>>) {
        val matches = picked.map { (uri, name) ->
            val spec = model.files.find { it.name == name }
            if (spec == null) {
                requireContext().toast("${model.title} 不需要這個檔案：$name")
                return
            }
            spec to uri
        }
        runImport(model, matches)
    }

    private fun importFolder(model: VoiceModel, tree: Uri) {
        val ctx = requireContext()
        val resolver = ctx.contentResolver
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { findInTree(resolver, tree, model.files.map { it.name }.toSet()) }
            val matches = model.files.mapNotNull { spec -> found[spec.name]?.let { spec to it } }
            if (matches.isEmpty()) ctx.toast("這個資料夾裡沒有 ${model.title} 需要的檔案")
            else runImport(model, matches)
        }
    }

    private fun runImport(model: VoiceModel, matches: List<Pair<VoiceModelFile, Uri>>) {
        val ctx = requireContext().applicationContext
        importing[model.id] = "正在匯入…"
        render()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    matches.forEach { (spec, uri) ->
                        val input = requireNotNull(ctx.contentResolver.openInputStream(uri)) { "無法開啟 ${spec.name}" }
                        input.use {
                            VoiceModelStore.importStream(model, spec, model.target(ctx, spec), it) { copied ->
                                if (copied % (32L shl 20) < 256 * 1024) {
                                    importing[model.id] = "正在匯入 ${spec.name}：${mb(copied)} MB"
                                    view?.post { render() }
                                }
                            }
                        }
                    }
                }.exceptionOrNull()
            }
            importing.remove(model.id)
            val missing = model.missingFiles(ctx)
            ctx.toast(
                when {
                    error != null -> "匯入失敗：${error.message}".also { Timber.w(error, "voice model import failed") }
                    missing.isEmpty() -> "${model.title} 匯入完成"
                    else -> "還缺：${missing.joinToString("、") { it.path }}"
                },
            )
            render()
        }
    }

    private fun displayName(uri: Uri): String =
        requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()

    /** Walks a picked folder breadth-first (three levels) and returns the first document for each wanted name. */
    private fun findInTree(resolver: android.content.ContentResolver, tree: Uri, wanted: Set<String>): Map<String, Uri> {
        val found = mutableMapOf<String, Uri>()
        var level = listOf(DocumentsContract.getTreeDocumentId(tree))
        repeat(3) {
            val next = mutableListOf<String>()
            for (parent in level) {
                resolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent),
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

    private fun mb(bytes: Long) = bytes / (1024 * 1024)

    private fun size(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${mb(bytes)} MB"

    companion object {
        private const val STATE_TARGET = "import_target"
    }
}
