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
import android.os.Parcelable
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Button
import android.view.Gravity
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.osfans.trime.data.opencc.OpenCCDictManager
import com.osfans.trime.data.phrase.PhraseCorrector
import com.osfans.trime.data.phrase.PhraseHotwords
import com.osfans.trime.R
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ui.main.MainActivity
import com.osfans.trime.ui.main.NavigationRoute
import com.osfans.trime.ime.voice.VoiceModels.ENGINE_GOOGLE
import com.osfans.trime.ime.voice.VoiceModels.engineName
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean

/** Lets the user switch recognition engines without leaving the active Trime keyboard. */
class VoiceInputController(private val service: TrimeInputMethodService) : RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private var recordingPopup: PopupWindow? = null
    private var recordingMessage: TextView? = null
    private var recordingFinishButton: Button? = null
    private var enginePopup: PopupWindow? = null
    @Volatile private var active = false
    /** Bumped for every recording so callbacks from an older, cancelled one cannot touch the current one. */
    @Volatile private var session = 0
    private var localStop: AtomicBoolean? = null
    private var captureThread: Thread? = null
    private var selected = ENGINE_GOOGLE
    private var bubbleMode = false

    fun onBubbleTap() {
        if (active) {
            VoiceBubbleBridge.setState(VoiceBubbleState.Recognizing)
            localStop?.set(true)
            recognizer?.stopListening()
            return
        }
        loadSelectedEngine()
        if (selected == ENGINE_GOOGLE && !hasAndroidOnDeviceRecognizer()) {
            Toast.makeText(service, "此裝置沒有可用的 Android 本機語音辨識器；請改選離線模型。", Toast.LENGTH_LONG).show()
            VoiceBubbleBridge.setState(VoiceBubbleState.Problem("此手機沒有本機語音辨識"))
            return
        }
        // The IME keeps a connection to non-text windows such as the launcher; only a real text
        // editor has an input type, so recording elsewhere would have nowhere to type.
        val editor = service.currentInputEditorInfo
        if (service.currentInputConnection == null || editor == null || editor.inputType == android.text.InputType.TYPE_NULL) {
            Toast.makeText(service, "請先開啟文字輸入框，再使用語音懸浮球。", Toast.LENGTH_LONG).show()
            VoiceBubbleBridge.setState(VoiceBubbleState.Problem("請先點一下文字欄位"))
            return
        }
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            service.startActivity(Intent(service, VoicePermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            VoiceBubbleBridge.setState(VoiceBubbleState.Problem("請先允許使用麥克風"))
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
        selected = VoiceModels.selectedEngine(service)
    }

    private fun openModelManager() {
        service.startActivity(
            Intent(Intent.ACTION_RUN, null, service, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, NavigationRoute.VoiceModels as Parcelable)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    fun showEnginePicker(anchor: android.view.View? = null) {
        val current = VoiceModels.selectedEngine(service)
        val density = service.resources.displayMetrics.density
        val options = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        fun row(text: String, onClick: () -> Unit) = options.addView(TextView(service).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(24, 18, 24, 18)
            isClickable = true
            setOnClickListener {
                enginePopup?.dismiss()
                enginePopup = null
                onClick()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The model manager comes first so it stays reachable however many engines follow.
        row("⚙  管理模型（下載、選用、刪除）…") { openModelManager() }
        fun choose(engine: String) {
            selected = engine
            VoiceModels.setSelectedEngine(service, engine)
            Toast.makeText(service, "語音引擎已設定：${engineName(engine)}", Toast.LENGTH_SHORT).show()
        }
        row((if (current == ENGINE_GOOGLE) "●  " else "○  ") + "Android 系統語音") { choose(ENGINE_GOOGLE) }
        VoiceModels.all.forEach { model ->
            val installed = model.isInstalled(service)
            row((if (current == model.id) "●  " else "○  ") + model.title + if (installed) "（離線）" else "（未安裝）") {
                if (installed) {
                    choose(model.id)
                } else {
                    Toast.makeText(service, "${model.title} 尚未安裝，請先下載", Toast.LENGTH_LONG).show()
                    openModelManager()
                }
            }
        }
        val width = (density * 320).toInt()
        val root = anchor?.rootView ?: service.window.window!!.decorView
        // Fit the list inside the visible keyboard area and let it scroll when it is taller,
        // instead of dropping below the anchor where the screen edge cuts it off.
        val visible = android.graphics.Rect().also(root::getWindowVisibleDisplayFrame)
        val margin = (density * 8).toInt()
        options.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        val keyboardHeight = root.height.takeIf { it in 1 until visible.height() } ?: (visible.height() / 2)
        val height = minOf(options.measuredHeight, keyboardHeight - 2 * margin)
        val scroll = android.widget.ScrollView(service).apply { addView(options) }
        // Keep the IME window focused; a focusable popup makes Android hide the keyboard window
        // that this popup is attached to before the user can select an engine.
        enginePopup = PopupWindow(scroll, width, height, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.rgb(48, 48, 48)))
            elevation = density * 12
            isOutsideTouchable = true
            setOnDismissListener { enginePopup = null }
            val screenBottom = service.resources.displayMetrics.heightPixels
            showAtLocation(root, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, maxOf(0, screenBottom - visible.bottom) + margin)
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
            if (bubbleMode) {
                cancel()
                VoiceBubbleBridge.setState(VoiceBubbleState.Problem("Android 本機語音辨識不可用"))
            } else {
                Toast.makeText(service, "找不到 Android 語音辨識服務", Toast.LENGTH_LONG).show()
            }
            return
        }
        active = true
        Thread(PhraseCorrector::warmUp, "PhraseReadings").start()
        if (bubbleMode) VoiceBubbleBridge.setState(VoiceBubbleState.Listening)
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
            if (wasBubbleMode) VoiceBubbleBridge.setState(VoiceBubbleState.Problem("語音服務無法啟動"))
        }
    }

    private fun hasAndroidOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(service)

    private fun startLocal() {
        val model = VoiceModels.find(selected)
        if (model == null || !model.isInstalled(service)) {
            if (bubbleMode) VoiceBubbleBridge.setRecordingActive(false)
            Toast.makeText(service, "尚未安裝此模型；請到設定「語音模型」下載", Toast.LENGTH_LONG).show()
            VoiceBubbleBridge.setState(VoiceBubbleState.Problem("尚未安裝此語音模型"))
            return
        }
        active = true
        val id = ++session
        fun current() = active && session == id
        val stop = AtomicBoolean(false)
        // A cancelled recording may still be loading or releasing its model. Wait for it before
        // loading another one, so two native models never sit in memory together.
        val previous = captureThread
        localStop = stop
        if (bubbleMode) {
            VoiceBubbleBridge.setState(VoiceBubbleState.Preparing)
        } else {
            showRecordingDialog(engineName(selected), "正在開啟麥克風…") { _ -> stop.set(true) }
        }
        // Load the model while the user speaks, so its multi-second load overlaps the recording
        // instead of delaying it. A load failure stops the recording early and is reported below.
        var loadMs = 0L
        val loader = FutureTask {
            previous?.join()
            val loadStart = SystemClock.elapsedRealtime()
            try {
                model.createRecognizer(service).also {
                    loadMs = SystemClock.elapsedRealtime() - loadStart
                    service.mainExecutor.execute {
                        if (current() && !stop.get() && bubbleMode) {
                            VoiceBubbleBridge.setHint(if (VoiceModels.autoStop(service)) AUTO_STOP_HINT else "模型已就緒，說完點一下停止")
                        }
                    }
                }
            } catch (error: Throwable) {
                stop.set(true)
                throw error
            }
        }
        Thread(loader, "TrimeLocalASRLoad").start()
        captureThread = Thread({
            Thread(PhraseCorrector::warmUp, "PhraseReadings").start()
            var audioRecord: AudioRecord? = null
            var offline: OfflineRecognizer? = null
            var offlineStream: com.k2fsa.sherpa.onnx.OfflineStream? = null
            var vad: Vad? = null
            try {
                val rate = 16000
                val autoStop = VoiceModels.autoStop(service)
                if (autoStop) {
                    vad = runCatching { createVad() }.onFailure { Log.w(TAG, "VAD unavailable", it) }.getOrNull()
                }
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
                    if (current()) {
                        if (bubbleMode) {
                            VoiceBubbleBridge.setState(VoiceBubbleState.Listening)
                            if (!loader.isDone) VoiceBubbleBridge.setHint("請說話（模型載入中，可以先說）")
                            else if (vad != null) VoiceBubbleBridge.setHint(AUTO_STOP_HINT)
                        } else {
                            recordingMessage?.text = "請開始說話（模型同時在背景載入）。" +
                                if (vad != null) "停頓約 1.5 秒會自動結束，也可按「停止並輸入」。" else "說完按「停止並輸入」。"
                        }
                    }
                }
                // With VAD, stop once speech has been followed by a pause, or when nobody speaks.
                var heardSpeech = false
                var speechStart = 0
                var silentSamples = 0
                var noSpeech = false
                while (!stop.get()) {
                    val count = audioRecord.read(chunk, 0, chunk.size)
                    check(count > 0) { "麥克風讀取失敗：$count" }
                    val samples = FloatArray(count) { chunk[it] / 32768.0f }
                    if (bubbleMode) VoiceBubbleBridge.setLevel(levelOf(samples))
                    check(offlineAudio.size + samples.size <= rate * 120) { "單次離線錄音最多 2 分鐘" }
                    samples.forEach(offlineAudio::add)
                    val detector = vad ?: continue
                    detector.acceptWaveform(samples)
                    while (!detector.empty()) detector.pop()
                    if (detector.isSpeechDetected()) {
                        if (!heardSpeech) speechStart = offlineAudio.size - count
                        heardSpeech = true
                        silentSamples = 0
                    } else {
                        silentSamples += count
                    }
                    if (heardSpeech && silentSamples >= rate * AUTO_STOP_SILENCE_MS / 1000) break
                    if (!heardSpeech && offlineAudio.size >= rate * NO_SPEECH_TIMEOUT_MS / 1000) {
                        noSpeech = true
                        break
                    }
                }
                if (!current()) return@Thread
                audioRecord.stop()
                if (noSpeech) {
                    completeResult("", id)
                    return@Thread
                }
                // Keep a short tail of the final pause; long trailing silence only slows decoding
                // and can make some models repeat the last sentence.
                if (heardSpeech) {
                    val trim = (silentSamples - rate * KEEP_TAIL_MS / 1000).coerceIn(0, offlineAudio.size)
                    repeat(trim) { offlineAudio.removeAt(offlineAudio.lastIndex) }
                    // The VAD reports speech a little after it starts, so keep a longer lead-in.
                    val lead = (speechStart - rate * KEEP_LEAD_MS / 1000).coerceIn(0, offlineAudio.size)
                    if (lead > 0) offlineAudio.subList(0, lead).clear()
                }
                val stillLoading = !loader.isDone
                service.mainExecutor.execute {
                    if (!current()) return@execute
                    if (bubbleMode) {
                        VoiceBubbleBridge.setRecordingActive(false)
                        VoiceBubbleBridge.setState(if (stillLoading) VoiceBubbleState.WaitingModel else VoiceBubbleState.Recognizing)
                    } else if (stillLoading) {
                        recordingMessage?.text = "錄音完成，正在等待模型載入…"
                    }
                }
                val waitStart = SystemClock.elapsedRealtime()
                val engine = try {
                    loader.get()
                } catch (error: ExecutionException) {
                    throw error.cause ?: error
                }
                offline = engine
                val waitMs = SystemClock.elapsedRealtime() - waitStart
                if (stillLoading) {
                    service.mainExecutor.execute {
                        if (current()) {
                            if (bubbleMode) VoiceBubbleBridge.setState(VoiceBubbleState.Recognizing)
                            else recordingMessage?.text = "正在辨識，請稍候…"
                        }
                    }
                }
                val samples = FloatArray(offlineAudio.size) { offlineAudio[it] }
                require(samples.isNotEmpty()) { "沒有錄到語音" }
                val stream = engine.createStream().also { offlineStream = it }
                stream.acceptWaveform(samples, rate)
                val decodeStart = SystemClock.elapsedRealtime()
                engine.decode(stream)
                val result = engine.getResult(stream).text
                Log.i(
                    TAG,
                    "engine=${model.id} loadMs=$loadMs waitMs=$waitMs audioMs=${samples.size * 1000L / rate} " +
                        "decodeMs=${SystemClock.elapsedRealtime() - decodeStart} chars=${result.length}",
                )
                completeResult(result, id)
            } catch (error: Throwable) {
                service.mainExecutor.execute {
                    if (current()) {
                        val fromBubble = bubbleMode
                        cancel()
                        if (fromBubble) VoiceBubbleBridge.setState(VoiceBubbleState.Problem("${model.title} 失敗：${error.message}"))
                        else Toast.makeText(service, "${model.title} 失敗：${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                runCatching { audioRecord?.stop() }
                audioRecord?.release()
                vad?.release()
                offlineStream?.release()
                // A cancelled session still owns the model being loaded; wait for it so it is freed.
                (offline ?: runCatching { loader.get() }.getOrNull())?.release()
            }
        }, "TrimeLocalASR").also { it.start() }
    }

    private fun completeResult(text: String, id: Int = session) = service.mainExecutor.execute {
        if (!active || session != id) return@execute
        val fromBubble = bubbleMode
        cancel()
        if (text.isBlank()) {
            if (fromBubble) VoiceBubbleBridge.setState(VoiceBubbleState.Problem("沒有辨識到語音"))
            else Toast.makeText(service, "沒有辨識到語音", Toast.LENGTH_SHORT).show()
        } else {
            val converted = if (selected != ENGINE_GOOGLE) {
                runCatching { OpenCCDictManager.convertLine(text.trim(), "s2tw.json") }
                    .getOrDefault(text.trim())
            } else {
                text.trim()
            }
            // Put the user's own names and terms back where the recognizer chose a homophone.
            val output = PhraseCorrector.correct(PhraseCorrector.tidy(converted), PhraseHotwords.collect())
            service.commitVoiceText(output)
            if (fromBubble) {
                VoiceBubbleBridge.setState(VoiceBubbleState.Done)
                service.showVoiceKeyboard()
            }
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
        if (wasBubbleMode) VoiceBubbleBridge.setState(VoiceBubbleState.Idle)
    }

    override fun onReadyForSpeech(params: Bundle?) {
        if (bubbleMode) VoiceBubbleBridge.setState(VoiceBubbleState.Listening)
        else recordingMessage?.text = "正在聆聽，請開始說話。說完按「停止並輸入」。"
    }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) {
        // Android reports roughly -2 dB (silence) to 10 dB (loud speech).
        if (bubbleMode) VoiceBubbleBridge.setLevel((rmsdB + 2f) / 12f)
    }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() {
        if (bubbleMode) VoiceBubbleBridge.setState(VoiceBubbleState.Recognizing)
        else recordingMessage?.text = "已停止收音，正在辨識…"
    }
    override fun onError(error: Int) {
        if (active) {
            val fromBubble = bubbleMode
            cancel()
            if (fromBubble) VoiceBubbleBridge.setState(VoiceBubbleState.Problem("語音辨識失敗（$error）"))
            else Toast.makeText(service, "Android 語音辨識失敗（$error）", Toast.LENGTH_LONG).show()
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

    /** Maps the RMS of 16-bit samples to 0..1, with normal speech around the middle. */
    private fun levelOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        val rms = kotlin.math.sqrt(sum / samples.size)
        return (rms * 8).toFloat().coerceIn(0f, 1f)
    }

    private fun createVad() = Vad(
        assetManager = service.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.25f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                maxSpeechDuration = 30f,
            ),
            sampleRate = 16000,
            numThreads = 1,
            provider = "cpu",
        ),
    )

    companion object {
        private const val TAG = "VoiceInput"
        /** Pause after speech that ends a recording; the VAD itself adds about 0.25 s. */
        private const val AUTO_STOP_SILENCE_MS = 1_250
        private const val NO_SPEECH_TIMEOUT_MS = 8_000
        private const val KEEP_TAIL_MS = 300
        private const val KEEP_LEAD_MS = 500
        private const val AUTO_STOP_HINT = "請說話，停頓約 1.5 秒自動結束"
    }
}
