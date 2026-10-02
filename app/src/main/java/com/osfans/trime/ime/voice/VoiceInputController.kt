/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Button
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.R
import com.osfans.trime.ime.core.TrimeInputMethodService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Lets the user switch recognition engines without leaving the active Trime keyboard. */
class VoiceInputController(private val service: TrimeInputMethodService) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var recordingPopup: PopupWindow? = null
    private var recordingMessage: TextView? = null
    private var recordingFinishButton: Button? = null
    private var enginePopup: PopupWindow? = null
    @Volatile private var active = false
    private var localStop: AtomicBoolean? = null
    private var captureThread: Thread? = null
    private var selected = ENGINE_GOOGLE
    private var bubbleMode = false

    fun onBubbleTap() {
        if (active) {
            VoiceBubbleBridge.setStatus("正在辨識", false)
            localStop?.set(true)
            recognizer?.stopListening()
            return
        }
        loadSelectedEngine()
        if (selected == ENGINE_GOOGLE && !hasAndroidOnDeviceRecognizer()) {
            Toast.makeText(service, "此裝置沒有可用的 Android 本機語音辨識器；請選 SenseVoice 或 Breeze。", Toast.LENGTH_LONG).show()
            VoiceBubbleBridge.setStatus("無系統離線辨識", false)
            return
        }
        if (service.currentInputConnection == null) {
            Toast.makeText(service, "請先開啟文字輸入框，再使用語音懸浮球。", Toast.LENGTH_LONG).show()
            VoiceBubbleBridge.setStatus("請先點文字框", false)
            return
        }
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            service.startActivity(Intent(service, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            VoiceBubbleBridge.setStatus("請授權麥克風", false)
            return
        }
        bubbleMode = true
        VoiceBubbleBridge.setRecordingActive(true)
        service.hideVoiceKeyboard()
        beginSelected()
    }

    fun start() {
        if (active) return
        loadSelectedEngine()
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            service.startActivity(Intent(service, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        bubbleMode = false
        beginSelected()
    }

    private fun loadSelectedEngine() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        val storedEngine = prefs.getString(PREF_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE
        selected = when (storedEngine) {
            ENGINE_BREEZE, ENGINE_SENSEVOICE -> storedEngine
            LEGACY_ZIPFORMER -> ENGINE_SENSEVOICE.also { prefs.edit().putString(PREF_ENGINE, it).apply() }
            else -> ENGINE_GOOGLE
        }
    }

    fun showEnginePicker(anchor: android.view.View? = null) {
        val labels = arrayOf("Android 系統語音", "Breeze ASR 25（離線）", "SenseVoice Small（離線）", "管理／匯入模型")
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        val storedEngine = prefs.getString(PREF_ENGINE, ENGINE_GOOGLE) ?: ENGINE_GOOGLE
        val current = if (storedEngine == LEGACY_ZIPFORMER) ENGINE_SENSEVOICE else storedEngine
        if (current != storedEngine) prefs.edit().putString(PREF_ENGINE, current).apply()
        val options = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        labels.forEachIndexed { index, label ->
            val checked = index < ENGINE_IDS.size && ENGINE_IDS[index] == current
            options.addView(TextView(service).apply {
                text = (if (checked) "●  " else "○  ") + label
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(24, 18, 24, 18)
                isClickable = true
                setOnClickListener {
                    enginePopup?.dismiss()
                    enginePopup = null
                    if (index == labels.lastIndex) {
                        service.startActivity(
                            Intent().setClassName(service, "com.osfans.trime.ime.voice.BreezeBenchmarkActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } else {
                        selected = ENGINE_IDS[index]
                        prefs.edit().putString(PREF_ENGINE, selected).apply()
                        Toast.makeText(service, "語音引擎已設定：${labels[index]}", Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val width = (service.resources.displayMetrics.density * 320).toInt()
        // Keep the IME window focused; a focusable popup makes Android hide the keyboard window
        // that this popup is attached to before the user can select an engine.
        enginePopup = PopupWindow(options, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(48, 48, 48)))
            elevation = service.resources.displayMetrics.density * 12
            isOutsideTouchable = true
            setOnDismissListener { enginePopup = null }
            if (anchor == null) showAtLocation(service.window.window!!.decorView, Gravity.CENTER, 0, 0)
            else showAsDropDown(anchor)
        }
    }

    private fun beginSelected() {
        if (selected == ENGINE_GOOGLE) startGoogle() else startLocal()
    }

    private fun showRecordingDialog(
        title: String,
        message: String,
        finishLabel: String = "停止並輸入",
        finishEnabled: Boolean = true,
        finish: (android.view.View) -> Unit,
    ) {
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 12)
            setBackgroundColor(Color.rgb(48, 48, 48))
        }
        panel.addView(TextView(service).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        recordingMessage = TextView(service).apply {
            text = message
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 3
            minHeight = (service.resources.displayMetrics.density * 64).toInt()
            setPadding(0, 12, 0, 8)
        }.also { panel.addView(it) }
        val actions = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        actions.addView(Button(service).apply {
            text = "取消"
            setOnClickListener { cancel() }
        })
        actions.addView(Button(service).apply {
            text = finishLabel
            isEnabled = finishEnabled
            recordingFinishButton = this
            setOnClickListener {
                recordingMessage?.text = "正在辨識，請稍候…"
                isEnabled = false
                finish(it)
            }
        })
        panel.addView(actions)
        val width = (service.resources.displayMetrics.density * 320).toInt()
        // A non-focusable popup stays attached to the IME window. A modal Dialog causes Android
        // to hide the keyboard and can revoke microphone capture while the user is speaking.
        recordingPopup = PopupWindow(panel, width, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = service.resources.displayMetrics.density * 12
            isOutsideTouchable = false
            showAtLocation(service.window.window!!.decorView, Gravity.CENTER, 0, 0)
        }
    }

    private fun startGoogle() {
        val available = if (bubbleMode) hasAndroidOnDeviceRecognizer() else SpeechRecognizer.isRecognitionAvailable(service)
        if (!available) {
            Toast.makeText(service, if (bubbleMode) "Android 本機語音辨識不可用" else "找不到 Android 語音辨識服務", Toast.LENGTH_LONG).show()
            if (bubbleMode) cancel()
            return
        }
        active = true
        if (bubbleMode) VoiceBubbleBridge.setStatus("Android 離線聆聽", true)
        else showRecordingDialog("Android 語音", "正在聆聽，請開始說話。說完按「停止並輸入」。") { recognizer?.stopListening() }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cmn-Hant-TW")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer = (if (bubbleMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(service)
            } else {
                SpeechRecognizer.createSpeechRecognizer(service)
            }).also {
                it.setRecognitionListener(this)
                it.startListening(intent)
            }
        } catch (error: RuntimeException) {
            val wasBubbleMode = bubbleMode
            cancel()
            Toast.makeText(service, "無法啟動 Android 語音：${error.message}", Toast.LENGTH_LONG).show()
            if (wasBubbleMode) VoiceBubbleBridge.setStatus("語音服務無法啟動", false)
        }
    }

    private fun hasAndroidOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(service)

    private fun startLocal() {
        val files = modelFiles(selected)
        if (files.any { !it.isFile }) {
            if (bubbleMode) VoiceBubbleBridge.setRecordingActive(false)
            Toast.makeText(service, "尚未匯入此模型；請先選「管理／匯入模型」", Toast.LENGTH_LONG).show()
            VoiceBubbleBridge.setStatus("尚未匯入模型", false)
            return
        }
        active = true
        localStop = AtomicBoolean(false)
        if (bubbleMode) {
            VoiceBubbleBridge.setStatus("載入模型", false)
        } else {
            showRecordingDialog(
                engineName(selected),
                "正在載入模型，尚未收音；載入完成後會顯示「請開始說話」。",
                finishEnabled = false,
            ) { _ ->
                localStop?.set(true)
            }
        }
        captureThread = Thread({
            var audioRecord: AudioRecord? = null
            var offline: OfflineRecognizer? = null
            var offlineStream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            try {
                if (selected == ENGINE_BREEZE) offline = createBreeze(files)
                else offline = createSenseVoice(files)
                offlineStream = offline.createStream()
                val rate = 16000
                check(active) { "錄音已取消" }
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minBuffer > 0) { "AudioRecord error $minBuffer" }
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 4096))
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "麥克風初始化失敗" }
                val chunk = ShortArray(1024)
                val offlineAudio = ArrayList<Float>(rate * 30)
                check(active) { "錄音已取消" }
                audioRecord.startRecording()
                service.mainExecutor.execute {
                    if (active) {
                        if (bubbleMode) VoiceBubbleBridge.setStatus("聆聽中", true)
                        else {
                            recordingMessage?.text = "模型已就緒，請開始說話。說完按「停止並輸入」。"
                            recordingFinishButton?.isEnabled = true
                        }
                    }
                }
                while (localStop?.get() == false) {
                    val count = audioRecord.read(chunk, 0, chunk.size)
                    check(count > 0) { "麥克風讀取失敗：$count" }
                    val samples = FloatArray(count) { chunk[it] / 32768.0f }
                    check(offlineAudio.size + samples.size <= rate * 120) { "單次離線錄音最多 2 分鐘" }
                    samples.forEach(offlineAudio::add)
                }
                if (!active) return@Thread
                audioRecord.stop()
                service.mainExecutor.execute {
                    if (bubbleMode) {
                        VoiceBubbleBridge.setRecordingActive(false)
                        VoiceBubbleBridge.setStatus("辨識中", false)
                    }
                }
                val samples = FloatArray(offlineAudio.size) { offlineAudio[it] }
                require(samples.isNotEmpty()) { "沒有錄到語音" }
                val stream = requireNotNull(offlineStream)
                stream.acceptWaveform(samples, rate)
                val engine = requireNotNull(offline)
                engine.decode(stream)
                val result = engine.getResult(stream).text
                completeResult(result)
            } catch (error: Throwable) {
                service.mainExecutor.execute {
                    if (active) {
                        cancel()
                        Toast.makeText(service, "${engineName(selected)} 失敗：${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                runCatching { audioRecord?.stop() }
                audioRecord?.release()
                offlineStream?.release()
                offline?.release()
            }
        }, "TrimeLocalASR").also { it.start() }
    }

    private fun createBreeze(files: List<File>): OfflineRecognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(encoder = files[0].absolutePath, decoder = files[1].absolutePath, language = "zh", task = "transcribe", tailPaddings = 300),
                tokens = files[2].absolutePath,
                modelType = "whisper",
                numThreads = 4,
                provider = "cpu",
            ),
        ),
    )

    private fun createSenseVoice(files: List<File>): OfflineRecognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(model = files[0].absolutePath, language = "auto", useInverseTextNormalization = true),
                tokens = files[1].absolutePath,
                modelType = "sense_voice",
                numThreads = 4,
                provider = "cpu",
            ),
        ),
    )

    private fun modelDirectory(engine: String) = File(service.getExternalFilesDir(null), "models/$engine")

    private fun modelFiles(engine: String): List<File> {
        val dir = modelDirectory(engine)
        return when (engine) {
            ENGINE_BREEZE -> listOf("breeze-asr-25-half-encoder.int8.onnx", "breeze-asr-25-half-decoder.int8.onnx", "breeze-asr-25-half-tokens.txt").map { File(dir, it) }
            ENGINE_SENSEVOICE -> listOf("model.int8.onnx", "tokens.txt").map { File(dir, it) }
            else -> emptyList()
        }
    }

    private fun engineName(engine: String) = when (engine) {
        ENGINE_BREEZE -> "Breeze ASR 25"
        ENGINE_SENSEVOICE -> "SenseVoice Small"
        else -> "Android 系統語音"
    }

    private fun completeResult(text: String) = service.mainExecutor.execute {
        if (!active) return@execute
        val fromBubble = bubbleMode
        cancel()
        if (text.isBlank()) Toast.makeText(service, "沒有辨識到語音", Toast.LENGTH_SHORT).show()
        else {
            val output = if (selected != ENGINE_GOOGLE) {
                runCatching { OpenCCDictManager.convertLine(text.trim(), "s2tw.json") }
                    .getOrDefault(text.trim())
            } else {
                text.trim()
            }
            service.commitVoiceText(output)
            if (fromBubble) service.showVoiceKeyboard()
        }
    }

    fun cancel() {
        val wasBubbleMode = bubbleMode
        active = false
        VoiceBubbleBridge.setRecordingActive(false)
        bubbleMode = false
        localStop?.set(true)
        enginePopup?.dismiss()
        enginePopup = null
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        recordingPopup?.dismiss()
        recordingPopup = null
        recordingMessage = null
        recordingFinishButton = null
        if (wasBubbleMode) VoiceBubbleBridge.setStatus("離線語音", false)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (bubbleMode) VoiceBubbleBridge.setStatus("聆聽中", true)
        else recordingMessage?.text = "正在聆聽，請開始說話。說完按「停止並輸入」。"
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        if (bubbleMode) VoiceBubbleBridge.setStatus("辨識中", false)
        else recordingMessage?.text = "已停止收音，正在辨識…"
    }
    override fun onError(error: Int) {
        if (active) {
            cancel()
            Toast.makeText(service, "Android 語音辨識失敗（$error）", Toast.LENGTH_LONG).show()
        }
    }
    override fun onResults(results: Bundle?) {
        if (!active) return
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        completeResult(text)
    }
    override fun onPartialResults(partialResults: Bundle?) {
        // Keep the status stable so the recording control does not jump while partial text changes.
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val PREF_ENGINE = "voice_recognition_engine"
        private const val ENGINE_GOOGLE = "google"
        private const val ENGINE_BREEZE = "breeze-asr-25"
        private const val LEGACY_ZIPFORMER = "zipformer-small-ctc-zh"
        private const val ENGINE_SENSEVOICE = "sensevoice-small"
        private val ENGINE_IDS = listOf(ENGINE_GOOGLE, ENGINE_BREEZE, ENGINE_SENSEVOICE)
    }
}
