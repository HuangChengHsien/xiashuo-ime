package com.osfans.trime.ime.voice

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Debug-only isolated-process probe; it never changes the active keyboard. */
class BreezeBenchmarkActivity : Activity() {
    private data class DecodeResult(val text: String, val decodeMs: Long)
    private data class TrimmedAudio(val samples: FloatArray, val startMs: Int, val endMs: Int)
    private data class ModelSpec(val name: String, val size: Long, val sha256: String)

    private val modelSpecs = listOf(
        ModelSpec("breeze-asr-25-half-encoder.int8.onnx", 765942323L, "fc3e99d1d3abf553d355fe35cc72ff943c6b2b77eeeea97e8d56338bff132fea"),
        ModelSpec("breeze-asr-25-half-decoder.int8.onnx", 1008223031L, "be492b3bf597690dcd9871ac20c555faa9b78514d3e830402afdf2be0689fb20"),
        ModelSpec("breeze-asr-25-half-tokens.txt", 816730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
    )

    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var quickButton: Button
    private lateinit var trimButton: Button
    private lateinit var threadButton: Button
    private lateinit var importButton: Button
    private lateinit var scrollView: ScrollView
    private var running = false
    private var cachedRecognizer: OfflineRecognizer? = null
    private var lastSamples: FloatArray? = null
    private var firstRunSummary = ""
    private var importTarget = "breeze-asr-25"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(36, 48, 36, 48)
        }
        status = TextView(this).apply {
            textSize = 18f
            text = "Breeze ASR 25 離線原型\n等待模型檔案"
        }
        quickButton = Button(this).apply {
            text = "錄音並裁切靜音（載入一次）"
            setOnClickListener { beginQuick() }
        }
        button = Button(this).apply {
            text = "比較補白 1000／300"
            setOnClickListener { begin() }
        }
        trimButton = Button(this).apply {
            text = "比較完整錄音／裁切靜音"
            setOnClickListener { beginTrimComparison() }
        }
        threadButton = Button(this).apply {
            text = "比較 CPU 執行緒 2／4／6"
            setOnClickListener { beginThreadComparison() }
        }
        importButton = Button(this).apply {
            text = "從下載檔匯入模型"
            setOnClickListener { openModelPicker() }
        }
        layout.addView(status)
        layout.addView(quickButton)
        layout.addView(button)
        layout.addView(trimButton)
        layout.addView(threadButton)
        layout.addView(importButton)
        scrollView = ScrollView(this).apply {
            addView(layout)
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    setPadding(0, bars.top, 0, bars.bottom)
                }
                insets
            }
        }
        setContentView(scrollView)
        val dir = modelDir()
        val senseReady = expectedNames("sensevoice-small").all { File(modelDir("sensevoice-small"), it).isFile }
        status.text = "語音模型管理與 Breeze 測試\n" +
            "Breeze: ${if (modelFiles(dir).all { it.isFile }) "已匯入" else "未匯入"}\n" +
            "SenseVoice: ${if (senseReady) "已匯入" else "未匯入"}\n\n" +
            "模型下載位置：\n" +
            "SenseVoice: https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html\n" +
            "Breeze: https://huggingface.co/MediaTek-Research/Breeze-ASR-25（需 sherpa-onnx half encoder/decoder 匯出檔）\n\n" +
            "先在瀏覽器下載並解壓縮，再按下方匯入。\nBreeze 目錄：${dir.absolutePath}"
    }

    private fun modelDir(target: String = "breeze-asr-25"): File = File(getExternalFilesDir(null), "models/$target")

    private fun modelFiles(dir: File) = modelSpecs.map { File(dir, it.name) }

    private fun expectedNames(target: String): List<String> = when (target) {
        "breeze-asr-25" -> modelSpecs.map { it.name }
        else -> listOf("model.int8.onnx", "tokens.txt")
    }

    private fun modelTitle(target: String): String = when (target) {
        "breeze-asr-25" -> "Breeze ASR 25"
        else -> "SenseVoice Small"
    }

    private fun openModelPicker() {
        if (running) return
        AlertDialog.Builder(this)
            .setTitle("選擇要匯入的模型")
            .setItems(arrayOf("Breeze ASR 25", "SenseVoice Small INT8")) { _, which ->
                importTarget = listOf("breeze-asr-25", "sensevoice-small")[which]
                chooseModelFiles()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun chooseModelFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Activity result callback is sufficient for this debug-only prototype")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || data == null || running) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clips ->
            for (i in 0 until clips.itemCount) uris += clips.getItemAt(i).uri
        }
        data.data?.let { uris += it }
        if (uris.isEmpty()) return
        running = true
        setButtonsEnabled(false)
        Thread {
            try {
                cachedRecognizer?.release()
                cachedRecognizer = null
                for (uri in uris.distinct()) importModel(uri)
                val missing = expectedNames(importTarget).filter { !File(modelDir(importTarget), it).isFile }
                showStatus(
                    if (missing.isEmpty()) "${modelTitle(importTarget)} 匯入完成，必要檔案已就緒"
                    else "已匯入 ${uris.size} 個檔案；${modelTitle(importTarget)} 尚缺：\n${missing.joinToString("\n")}",
                )
            } catch (error: Exception) {
                showStatus("匯入失敗：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runOnUiThread {
                    running = false
                    setButtonsEnabled(true)
                }
            }
        }.start()
    }

    private fun importModel(uri: Uri) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: error("無法讀取下載檔名")
        require(name in expectedNames(importTarget)) { "${modelTitle(importTarget)} 不支援的檔案：$name" }
        val spec = modelSpecs.find { it.name == name }
        val maxSize = spec?.size ?: if (name == "model.int8.onnx") 1_500_000_000L else 10_000_000L
        val dir = modelDir(importTarget).apply { mkdirs() }
        val staging = File(dir, ".$name.importing")
        val target = File(dir, name)
        val backup = File(dir, ".$name.previous")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "無法開啟下載檔：$name" }
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= maxSize) { "$name 檔案過大" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (copied % (32L * 1024 * 1024) < count) {
                            showStatus("正在匯入 $name：${copied / (1024 * 1024)} MiB")
                        }
                    }
                    output.fd.sync()
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (spec != null) {
                require(copied == spec.size) { "$name 檔案大小不符：$copied" }
                require(actualHash == spec.sha256) { "$name SHA-256 不符，原模型未更動" }
            }
            if (target.exists()) {
                require(!backup.exists() || backup.delete()) { "無法清理舊備份：$name" }
                require(target.renameTo(backup)) { "無法備份現有模型：$name" }
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("無法完成模型匯入：$name")
            }
            if (backup.exists()) backup.delete()
            Log.i(TAG, "imported name=$name bytes=$copied sha256=$actualHash")
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        button.isEnabled = enabled
        quickButton.isEnabled = enabled
        trimButton.isEnabled = enabled
        threadButton.isEnabled = enabled
        importButton.isEnabled = enabled
    }

    private fun begin() {
        if (running) return
        val files = modelFiles(modelDir())
        if (files.any { !it.isFile }) {
            status.text = "模型檔案尚未齊全"
            return
        }
        if (lastSamples == null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running = true
        button.isEnabled = false
        quickButton.isEnabled = false
        trimButton.isEnabled = false
        threadButton.isEnabled = false
        Thread {
            try {
                val replaySamples = lastSamples
                if (replaySamples != null) {
                    val cached = requireNotNull(cachedRecognizer) { "已載入模型遺失，請重新錄音" }
                    showStatus("同錄音反向重測：先辨識補白 300…")
                    val optimizedResult = try {
                        decode(cached, replaySamples)
                    } finally {
                        cached.release()
                        cachedRecognizer = null
                    }
                    Log.i(TAG, "replay padding=300 decodeMs=${optimizedResult.decodeMs} length=${optimizedResult.text.length}")
                    showStatus("補白 300：辨識 ${optimizedResult.decodeMs} ms；正在重載補白 1000…")
                    val (baseline, baselineLoadMs) = createRecognizer(files, 1000)
                    val baselineResult = try {
                        decode(baseline, replaySamples)
                    } finally {
                        baseline.release()
                    }
                    Log.i(TAG, "replay padding=1000 loadMs=$baselineLoadMs decodeMs=${baselineResult.decodeMs} length=${baselineResult.text.length}")
                    showStatus(
                        "$firstRunSummary\n\n同錄音反向重測\n" +
                            "補白 300：已載入，辨識 ${optimizedResult.decodeMs} ms\n" +
                            "結果：${optimizedResult.text}\n" +
                            "補白 1000：載入 ${baselineLoadMs} ms，辨識 ${baselineResult.decodeMs} ms\n" +
                            "結果：${baselineResult.text}",
                    )
                    lastSamples = null
                } else {
                    showStatus("正在錄音 5 秒，請說一句臺灣華語…")
                    val samples = recordFiveSeconds()
                    cachedRecognizer?.release()
                    cachedRecognizer = null
                    showStatus("錄音完成，正在載入補白 1000 的模型…")
                    val (baseline, baselineLoadMs) = createRecognizer(files, 1000)
                    val baselineResult = try {
                        showStatus("補白 1000：載入 ${baselineLoadMs} ms；正在辨識…")
                        decode(baseline, samples)
                    } finally {
                        baseline.release()
                    }
                    Log.i(TAG, "baseline padding=1000 loadMs=$baselineLoadMs decodeMs=${baselineResult.decodeMs} length=${baselineResult.text.length}")
                    showStatus("補白 1000：辨識 ${baselineResult.decodeMs} ms；正在載入補白 300 的模型…")
                    val (optimized, optimizedLoadMs) = createRecognizer(files, 300)
                    cachedRecognizer = optimized
                    val optimizedResult = decode(optimized, samples)
                    Log.i(TAG, "optimized padding=300 loadMs=$optimizedLoadMs decodeMs=${optimizedResult.decodeMs} length=${optimizedResult.text.length}")
                    firstRunSummary =
                        "同一段 5 秒錄音\n" +
                            "補白 1000：載入 ${baselineLoadMs} ms，辨識 ${baselineResult.decodeMs} ms\n" +
                            "結果：${baselineResult.text}\n" +
                            "補白 300：載入 ${optimizedLoadMs} ms，辨識 ${optimizedResult.decodeMs} ms\n" +
                            "結果：${optimizedResult.text}"
                    lastSamples = samples
                    showStatus("$firstRunSummary\n再次按鈕可用同錄音反向重測")
                }
            } catch (error: Exception) {
                showStatus("測試失敗：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runOnUiThread {
                    running = false
                    button.isEnabled = true
                    quickButton.isEnabled = true
                    trimButton.isEnabled = true
                    threadButton.isEnabled = true
                    button.text = if (lastSamples != null && cachedRecognizer != null) "同錄音反向重測" else "比較補白 1000／300"
                }
            }
        }.start()
    }

    private fun beginQuick() {
        if (running) return
        val files = modelFiles(modelDir())
        if (files.any { !it.isFile }) {
            status.text = "模型檔案尚未齊全"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running = true
        button.isEnabled = false
        quickButton.isEnabled = false
        trimButton.isEnabled = false
        threadButton.isEnabled = false
        Thread {
            try {
                showStatus("正在錄音 5 秒，請說一句臺灣華語…")
                val recorded = recordFiveSeconds()
                val trimmed = trimSilence(recorded)
                var recognizer = cachedRecognizer
                val loadMs = if (recognizer == null) {
                    showStatus("錄音完成，正在載入模型…")
                    val created = createRecognizer(files, 300)
                    recognizer = created.first
                    cachedRecognizer = recognizer
                    created.second
                } else {
                    0L
                }
                showStatus("模型已就緒，正在辨識…")
                val result = decode(requireNotNull(recognizer), trimmed.samples)
                Log.i(TAG, "quick padding=300 loadMs=$loadMs retainedMs=${trimmed.samples.size / 16} decodeMs=${result.decodeMs} length=${result.text.length}")
                showStatus(
                    "補白 300；裁切至 ${trimmed.samples.size / 16} ms\n" +
                        "${if (loadMs == 0L) "模型已載入" else "載入 ${loadMs} ms"}\n" +
                        "辨識 ${result.decodeMs} ms\n結果：${result.text}",
                )
            } catch (error: Exception) {
                showStatus("測試失敗：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runOnUiThread {
                    running = false
                    button.isEnabled = true
                    quickButton.isEnabled = true
                    trimButton.isEnabled = true
                    threadButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun beginTrimComparison() {
        if (running) return
        val files = modelFiles(modelDir())
        if (files.any { !it.isFile }) {
            status.text = "模型檔案尚未齊全"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running = true
        button.isEnabled = false
        quickButton.isEnabled = false
        trimButton.isEnabled = false
        threadButton.isEnabled = false
        Thread {
            try {
                showStatus("正在錄音 5 秒，請說一句臺灣華語…")
                val full = recordFiveSeconds()
                val trimmed = trimSilence(full)
                var recognizer = cachedRecognizer
                val loadMs = if (recognizer == null) {
                    showStatus("錄音完成，正在載入模型…")
                    val created = createRecognizer(files, 300)
                    recognizer = created.first
                    cachedRecognizer = recognizer
                    created.second
                } else {
                    0L
                }
                val readyRecognizer = requireNotNull(recognizer)
                showStatus("裁切至 ${trimmed.samples.size / 16} ms；正在比較四次辨識…")
                val full1 = decode(readyRecognizer, full)
                val trim1 = decode(readyRecognizer, trimmed.samples)
                val trim2 = decode(readyRecognizer, trimmed.samples)
                val full2 = decode(readyRecognizer, full)
                Log.i(
                    TAG,
                    "trim loadMs=$loadMs retainedMs=${trimmed.samples.size / 16} " +
                        "fullMs=${full1.decodeMs},${full2.decodeMs} trimMs=${trim1.decodeMs},${trim2.decodeMs} " +
                        "sameText=${full1.text == trim1.text && full1.text == trim2.text && full1.text == full2.text}",
                )
                showStatus(
                    "完整 5000 ms；裁切 ${trimmed.startMs}–${trimmed.endMs} ms " +
                        "（長 ${trimmed.samples.size / 16} ms）\n" +
                        "模型：${if (loadMs == 0L) "已載入" else "載入 ${loadMs} ms"}\n" +
                        "完整：${full1.decodeMs}、${full2.decodeMs} ms\n" +
                        "裁切：${trim1.decodeMs}、${trim2.decodeMs} ms\n" +
                        "完整結果：${full1.text}\n裁切結果：${trim1.text}",
                )
            } catch (error: Exception) {
                showStatus("測試失敗：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runOnUiThread {
                    running = false
                    button.isEnabled = true
                    quickButton.isEnabled = true
                    trimButton.isEnabled = true
                    threadButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun beginThreadComparison() {
        if (running) return
        val files = modelFiles(modelDir())
        if (files.any { !it.isFile }) {
            status.text = "模型檔案尚未齊全"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        running = true
        button.isEnabled = false
        quickButton.isEnabled = false
        trimButton.isEnabled = false
        threadButton.isEnabled = false
        Thread {
            try {
                showStatus("正在錄音 5 秒，請說一句臺灣華語…")
                val trimmed = trimSilence(recordFiveSeconds())
                cachedRecognizer?.release()
                cachedRecognizer = null
                val results = mutableListOf<String>()
                for (threads in listOf(2, 4, 6)) {
                    showStatus("裁切至 ${trimmed.samples.size / 16} ms；正在測試 $threads 執行緒…\n${results.joinToString("\n")}")
                    val (recognizer, loadMs) = createRecognizer(files, 300, threads)
                    val result = try {
                        decode(recognizer, trimmed.samples)
                    } finally {
                        recognizer.release()
                    }
                    Log.i(TAG, "threads=$threads loadMs=$loadMs retainedMs=${trimmed.samples.size / 16} decodeMs=${result.decodeMs} length=${result.text.length}")
                    results += "$threads 執行緒：載入 ${loadMs} ms，辨識 ${result.decodeMs} ms，結果：${result.text}"
                }
                showStatus("同一段裁切錄音 ${trimmed.samples.size / 16} ms\n${results.joinToString("\n")}")
            } catch (error: Exception) {
                showStatus("測試失敗：${error.javaClass.simpleName}: ${error.message}")
            } finally {
                runOnUiThread {
                    running = false
                    button.isEnabled = true
                    quickButton.isEnabled = true
                    trimButton.isEnabled = true
                    threadButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun trimSilence(samples: FloatArray): TrimmedAudio {
        val frameSize = 320 // 20 ms at 16 kHz
        val frameCount = samples.size / frameSize
        val energies = FloatArray(frameCount)
        var peak = 0f
        for (frame in 0 until frameCount) {
            var sum = 0f
            val offset = frame * frameSize
            for (i in 0 until frameSize) sum += samples[offset + i] * samples[offset + i]
            val rms = kotlin.math.sqrt(sum / frameSize)
            energies[frame] = rms
            if (rms > peak) peak = rms
        }
        val threshold = maxOf(0.008f, peak * 0.15f)
        val first = energies.indexOfFirst { it >= threshold }
        val last = energies.indexOfLast { it >= threshold }
        if (first < 0 || last < first) return TrimmedAudio(samples, 0, samples.size / 16)
        val startFrame = maxOf(0, first - 15) // Keep 300 ms before speech.
        val endFrame = minOf(frameCount, last + 16) // Keep 300 ms after speech.
        val start = startFrame * frameSize
        val end = endFrame * frameSize
        return TrimmedAudio(samples.copyOfRange(start, end), start / 16, end / 16)
    }

    private fun createRecognizer(files: List<File>, tailPaddings: Int, numThreads: Int = 4): Pair<OfflineRecognizer, Long> {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = files[0].absolutePath,
                    decoder = files[1].absolutePath,
                    language = "zh",
                    task = "transcribe",
                    tailPaddings = tailPaddings,
                ),
                tokens = files[2].absolutePath,
                modelType = "whisper",
                numThreads = numThreads,
                provider = "cpu",
            ),
        )
        val start = SystemClock.elapsedRealtime()
        val recognizer = OfflineRecognizer(config = config)
        return recognizer to (SystemClock.elapsedRealtime() - start)
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): DecodeResult {
        val start = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return DecodeResult(recognizer.getResult(stream).text, SystemClock.elapsedRealtime() - start)
        } finally {
            stream.release()
        }
    }

    override fun onDestroy() {
        if (!running) {
            cachedRecognizer?.release()
            cachedRecognizer = null
        }
        super.onDestroy()
    }

    private fun recordFiveSeconds(): FloatArray {
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "AudioRecord unavailable: $minBuffer" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
        val pcm = ShortArray(sampleRate * 5)
        try {
            recorder.startRecording()
            var count = 0
            while (count < pcm.size) {
                val read = recorder.read(pcm, count, minOf(4096, pcm.size - count))
                require(read > 0) { "AudioRecord read failed: $read" }
                count += read
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        return FloatArray(pcm.size) { pcm[it] / 32768.0f }
    }

    private fun showStatus(message: String) = runOnUiThread {
        status.text = message
        scrollView.post { scrollView.scrollTo(0, 0) }
    }

    companion object {
        private const val TAG = "BreezeBenchmark"
        private const val REQUEST_IMPORT = 100
    }
}
