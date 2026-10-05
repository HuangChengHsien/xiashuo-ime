package com.osfans.trime.ui.main.settings

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.osfans.trime.ime.voice.GemmaRewrite
import com.osfans.trime.ime.voice.LlmModel
import com.osfans.trime.ime.voice.LlmModelDownloadWorker
import com.osfans.trime.ime.voice.LlmModels
import com.osfans.trime.ime.voice.VoiceModelStore
import com.osfans.trime.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.dimensions.dp

class LlmModelsFragment : Fragment() {
    private lateinit var list: LinearLayout
    private val workInfo = mutableMapOf<String, WorkInfo?>()
    private var importTarget: LlmModel? = null
    private val importing = mutableSetOf<String>()
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val model = importTarget ?: return@registerForActivityResult
        if (uri != null) importFile(model, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importTarget = savedInstanceState?.getString("import_target")?.let(LlmModels::find)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        importTarget?.let { outState.putString("import_target", it.id) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(24))
        }
        return ScrollView(ctx).apply { addView(list) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val manager = WorkManager.getInstance(requireContext())
        LlmModels.all.forEach { model ->
            manager.getWorkInfosForUniqueWorkLiveData(LlmModelDownloadWorker.workName(model))
                .observe(viewLifecycleOwner) { infos ->
                    workInfo[model.id] = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
                    render()
                }
        }
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        val selected = LlmModels.selected(ctx)
        list.removeAllViews()
        list.addView(TextView(ctx).apply {
            text = "文字潤飾在手機上執行。下載模型時需要網路；每次潤飾使用下方選用的模型。"
            setPadding(0, 0, 0, ctx.dp(8))
        })
        LlmModels.all.forEach { model ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, ctx.dp(14), 0, ctx.dp(4))
            }
            card.line((if (model.id == selected.id) "● " else "○ ") + model.title + if (model.id == selected.id) "　使用中" else "", 18f)
            card.line(model.note)
            val info = workInfo[model.id]
            when {
                model.id in importing -> {
                    card.line("正在匯入並檢查 SHA-256…")
                    card.addView(ProgressBar(ctx))
                }
                info?.state == WorkInfo.State.RUNNING -> {
                    val done = info.progress.getLong(LlmModelDownloadWorker.KEY_DONE, 0)
                    val percent = (done * 100 / model.size).toInt()
                    card.line(if (info.progress.getBoolean(LlmModelDownloadWorker.KEY_VERIFYING, false)) "正在檢查 SHA-256…" else "下載中 ${mb(done)}／${mb(model.size)} MB（$percent%）")
                    card.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = percent })
                    card.actions("取消下載" to { LlmModelDownloadWorker.cancel(ctx, model) })
                }
                info?.state == WorkInfo.State.ENQUEUED || info?.state == WorkInfo.State.BLOCKED -> {
                    card.line(if (info.constraints.requiredNetworkType == androidx.work.NetworkType.UNMETERED) "等待 Wi-Fi；連上後自動下載或續傳" else "等待網路，稍後下載或續傳…")
                    card.actions("改用行動數據" to { startDownload(model) }, "取消下載" to { LlmModelDownloadWorker.cancel(ctx, model) })
                }
                model.isInstalled(ctx) -> {
                    card.line("已安裝 · ${mb(model.size)} MB")
                    if (model.id == selected.id) card.actions("刪除" to { confirmDelete(model) })
                    else card.actions("使用" to { LlmModels.select(ctx, model); render() }, "刪除" to { confirmDelete(model) })
                }
                else -> {
                    val failure = info?.takeIf { it.state == WorkInfo.State.FAILED }?.outputData?.getString(LlmModelDownloadWorker.KEY_ERROR)
                    card.line(failure?.let { "下載失敗：$it" } ?: "未安裝 · 約 ${mb(model.size)} MB")
                    if (model.gated) {
                        card.actions("下載步驟" to { showGatedInstructions(model) }, "匯入檔案" to { chooseImport(model) }, "刪除" to { confirmDelete(model) })
                    } else {
                        card.actions("下載" to { startDownload(model) }, "匯入檔案" to { chooseImport(model) }, "刪除" to { confirmDelete(model) })
                    }
                }
            }
            list.addView(card)
        }
    }

    private fun LinearLayout.line(value: String, fontSize: Float = 14f) {
        addView(TextView(context).apply { text = value; textSize = fontSize; setPadding(0, context.dp(2), 0, 0) })
    }

    private fun LinearLayout.actions(vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { (label, action) -> row.addView(Button(context, null, android.R.attr.borderlessButtonStyle).apply { text = label; setOnClickListener { action() } }) }
        addView(row)
    }

    private fun startDownload(model: LlmModel) {
        val ctx = requireContext()
        val network = ctx.getSystemService(ConnectivityManager::class.java)
        if (network.isActiveNetworkMetered) {
            AlertDialog.Builder(ctx).setTitle("使用行動數據下載？")
                .setMessage("${model.title} 約 ${mb(model.size)} MB。")
                .setPositiveButton("使用行動數據") { _, _ -> enqueueDownload(model, true) }
                .setNeutralButton("等候 Wi-Fi") { _, _ -> enqueueDownload(model, false) }
                .setNegativeButton(android.R.string.cancel, null).show()
        } else enqueueDownload(model, false)
    }

    private fun enqueueDownload(model: LlmModel, allowMetered: Boolean) {
        val ctx = requireContext()
        runCatching { LlmModelDownloadWorker.enqueue(ctx, model, allowMetered) }
            .onSuccess { ctx.toast(if (allowMetered) "${model.title} 已排入下載" else "${model.title} 已排入下載；等待 Wi-Fi 時會自動開始") }
            .onFailure { ctx.toast("無法開始下載：${it.message}") }
    }

    private fun showGatedInstructions(model: LlmModel) {
        AlertDialog.Builder(requireContext())
            .setTitle("下載 ${model.title}")
            .setMessage("1. 按「開啟 Hugging Face」登入並接受 Gemma 使用條款。\n2. 授權後回來按「下載指定檔案」，取得 ${model.fileName}。\n3. 回到管理頁按「匯入檔案」，選取下載的檔案。\n\n匯入時會檢查大小及 SHA-256。若頁面顯示等待審核，必須先等授權通過。")
            .setPositiveButton("開啟 Hugging Face") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.pageUrl))) }
            .setNeutralButton("下載指定檔案") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(model.url + "?download=true"))) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseImport(model: LlmModel) {
        importTarget = model
        AlertDialog.Builder(requireContext()).setTitle("匯入 ${model.title}")
            .setMessage("請選擇 ${model.fileName}；匯入時會檢查檔案大小與 SHA-256。")
            .setPositiveButton("選擇檔案") { _, _ -> pickFile.launch(arrayOf("*/*")) }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun importFile(model: LlmModel, uri: Uri) {
        val ctx = requireContext().applicationContext
        val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        if (name != model.fileName) { ctx.toast("檔名應為 ${model.fileName}"); return }
        importing += model.id
        render()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        VoiceModelStore.importStream(model, model.files.first(), model.target(ctx), input) { }
                    } ?: error("無法開啟檔案")
                }.exceptionOrNull()
            }
            importing -= model.id
            ctx.toast(error?.let { "匯入失敗：${it.message}" } ?: "${model.title} 已匯入")
            render()
        }
    }

    private fun confirmDelete(model: LlmModel) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx).setTitle("刪除 ${model.title}？")
            .setMessage("可釋放 ${mb(model.diskBytes(ctx))} MB；之後可重新下載或匯入。")
            .setPositiveButton("刪除") { _, _ ->
                lifecycleScope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching {
                            LlmModelDownloadWorker.cancelAndWait(ctx, model)
                            GemmaRewrite.releaseCachedEngineAndWait()
                            model.directory(ctx).deleteRecursively()
                            if (LlmModels.selected(ctx).id == model.id) {
                                val replacement = LlmModels.all.firstOrNull { it.id != model.id && it.isInstalled(ctx) }
                                if (replacement != null) LlmModels.select(ctx, replacement)
                            }
                        }.exceptionOrNull()
                    }
                    ctx.toast(error?.let { "刪除失敗：${it.message}" } ?: "已刪除 ${model.title}")
                    render()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun mb(bytes: Long) = bytes / (1024 * 1024)
}
