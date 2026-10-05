/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.osfans.trime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads one offline model in the background. Partial files survive interruptions and resume
 * with an HTTP range request; each file is installed only after its size and SHA-256 match.
 */
class LlmModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val model = LlmModels.find(inputData.getString(KEY_MODEL).orEmpty())
    private var lastReport = 0L

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = model ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "未知的模型"))
        if (model.gated) return@withContext Result.failure(workDataOf(KEY_ERROR to "請先在 Hugging Face 接受授權，下載檔案後匯入"))
        val pending = model.missingFiles(applicationContext)
        val total = model.totalBytes
        var done = total - pending.sumOf { it.size }
        val partBytes = pending.sumOf { VoiceModelStore.partFile(model.target(applicationContext, it)).length() }
        val needed = pending.sumOf { it.size } - partBytes
        val free = model.directory(applicationContext).apply { mkdirs() }.usableSpace
        if (free < needed + FREE_SPACE_MARGIN) {
            return@withContext Result.failure(workDataOf(KEY_ERROR to "空間不足：還需要 ${needed.toMegabytes()} MB，剩 ${free.toMegabytes()} MB"))
        }
        try {
            setForeground(foregroundInfo(model, done, total))
            for (spec in pending) {
                if (isStopped) return@withContext Result.failure()
                val target = model.target(applicationContext, spec)
                val part = VoiceModelStore.partFile(target).apply { parentFile?.mkdirs() }
                val base = done
                download(model.url(spec), part, spec.size) { written -> report(model, base + written, total) }
                if (isStopped) return@withContext Result.failure()
                setProgress(workDataOf(KEY_DONE to base + spec.size, KEY_TOTAL to total, KEY_VERIFYING to true))
                VoiceModelStore.commit(spec, part, target)
                done = base + spec.size
                Timber.i("LLM model %s: installed %s", model.id, spec.path)
            }
            Result.success()
        } catch (error: IOException) {
            Timber.w(error, "LLM model %s download interrupted", model.id)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to "網路中斷：${error.message ?: error.javaClass.simpleName}"))
            }
        } catch (error: Exception) {
            Timber.w(error, "LLM model %s download failed", model.id)
            Result.failure(workDataOf(KEY_ERROR to (error.message ?: error.javaClass.simpleName)))
        }
    }

    /** Appends to [part] from its current length; restarts it when the server ignores the range. */
    private suspend fun download(
        url: String,
        part: File,
        size: Long,
        onProgress: suspend (Long) -> Unit,
    ) {
        if (part.length() > size) part.delete()
        if (part.length() == size) return
        val connection = open(url, part.length())
        Timber.i("LLM model download %s from %d: HTTP %d", part.name, part.length(), connection.responseCode)
        try {
            val resumed = when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    // "bytes <start>-<end>/<total>": appending a range that starts elsewhere would
                    // corrupt the part file, so restart it from zero instead.
                    val start = connection.getHeaderField("Content-Range")
                        ?.substringAfter("bytes ", "")?.substringBefore('-')?.toLongOrNull()
                    if (start != part.length()) {
                        part.delete()
                        throw IOException("續傳位置不符（$start），將重新下載")
                    }
                    true
                }
                HttpURLConnection.HTTP_OK -> false
                RANGE_NOT_SATISFIABLE -> {
                    part.delete()
                    throw IOException("伺服器拒絕續傳，將重新下載")
                }
                else -> throw IOException("HTTP ${connection.responseCode}")
            }
            var written = if (resumed) part.length() else 0L
            connection.inputStream.use { input ->
                FileOutputStream(part, resumed).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (!isStopped) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > size) throw IllegalStateException("${part.name} 比預期大，下載來源可能已變更")
                        output.write(buffer, 0, count)
                        onProgress(written)
                    }
                    output.fd.sync()
                }
            }
            if (!isStopped && written != size) throw IOException("下載未完成（$written／$size）")
        } finally {
            connection.disconnect()
        }
    }

    /** Follows redirects by hand so the Range header is sent to the final CDN host too. */
    private fun open(url: String, offset: Long): HttpURLConnection {
        var current = URL(url)
        repeat(MAX_REDIRECTS) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Xiashuo-IME")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            when (connection.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("轉址缺少目標")
                    connection.disconnect()
                    current = URL(current, location)
                }
                else -> return connection
            }
        }
        throw IOException("轉址次數過多")
    }

    private suspend fun report(model: LlmModel, done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport < REPORT_INTERVAL_MS) return
        lastReport = now
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        setForeground(foregroundInfo(model, done, total))
    }

    private fun foregroundInfo(model: LlmModel, done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "潤飾模型下載", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_mic_24)
            .setContentTitle("正在下載 ${model.title}")
            .setContentText("${done.toMegabytes()}／${total.toMegabytes()} MB（$percent%）")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "取消", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        val notificationId = NOTIFICATION_BASE + LlmModels.all.indexOf(model)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "llm_model_download"
        private const val NOTIFICATION_BASE = 33200
        private const val BUFFER_SIZE = 256 * 1024
        private const val TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_RETRIES = 5
        private const val REPORT_INTERVAL_MS = 500L
        private const val RANGE_NOT_SATISFIABLE = 416
        private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024
        private const val CANCEL_TIMEOUT_MS = 10_000L

        fun workName(model: LlmModel) = "llm-model-download-${model.id}"

        /** Starts or keeps the download; [allowMetered] false waits for an unmetered network. */
        fun enqueue(context: Context, model: LlmModel, allowMetered: Boolean) {
            require(!model.gated) { "請先在 Hugging Face 接受授權並匯入檔案" }
            val request = OneTimeWorkRequestBuilder<LlmModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL to model.id))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(model), ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context, model: LlmModel) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(model))
        }

        /**
         * Cancels the download and blocks until the worker has stopped, so it cannot write or
         * install files after the caller deletes the model. Must not run on the main thread.
         */
        fun cancelAndWait(context: Context, model: LlmModel) {
            val manager = WorkManager.getInstance(context)
            manager.cancelUniqueWork(workName(model)).result.get()
            val deadline = SystemClock.elapsedRealtime() + CANCEL_TIMEOUT_MS
            while (SystemClock.elapsedRealtime() < deadline) {
                val running = manager.getWorkInfosForUniqueWork(workName(model)).get()
                    .any { it.state == WorkInfo.State.RUNNING }
                if (!running) return
                Thread.sleep(100)
            }
            error("下載仍在停止中，請稍後再刪除")
        }

        private fun Long.toMegabytes() = this / (1024 * 1024)
    }
}
