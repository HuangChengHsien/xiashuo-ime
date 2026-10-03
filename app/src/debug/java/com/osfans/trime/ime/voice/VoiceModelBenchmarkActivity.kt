/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.osfans.trime.data.phrase.PhraseCorrector
import com.osfans.trime.data.phrase.PhraseHotwords
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only benchmark run in its own process, so memory figures are not mixed with the keyboard.
 * Decodes a 16 kHz mono 16-bit WAV with every requested thread count and provider, e.g.
 *
 *     adb shell am start -n com.osfans.trime.debug/com.osfans.trime.ime.voice.VoiceModelBenchmarkActivity \
 *       --es model funasr-nano --es wav /sdcard/Android/data/com.osfans.trime.debug/files/bench/short.wav \
 *       --es threads 1,2,4,6 --es providers cpu,nnapi --ei repeats 3
 *
 * Each result is logged with the tag `VoiceBench`.
 */
class VoiceModelBenchmarkActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            textSize = 13f
            setPadding(32, 96, 32, 32)
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(output) })
        // `--ez selftest true` runs ThemePatchFile cases (Trime's JVM unit tests do not compile).
        if (intent.getBooleanExtra("selftest", false)) {
            themePatchSelfTest()
            report("done")
            return
        }
        // `--es convert <a|b>` compares the old glyph-only conversion with the Taiwan wording one.
        intent.getStringExtra("convert")?.let { text ->
            text.split('|').forEach {
                val old = com.osfans.trime.data.opencc.OpenCCDictManager.convertLine(it, "s2tw.json")
                val start = SystemClock.elapsedRealtime()
                val new = VoiceText.applyFixes(VoiceText.toTaiwan(it))
                report("convert: $it\n  s2tw : $old\n  new  : $new (${SystemClock.elapsedRealtime() - start} ms)")
            }
            report("done")
            return
        }
        // `--es correct <text>` only runs the homophone correction against the custom phrases.
        intent.getStringExtra("correct")?.let { text ->
            val words = PhraseHotwords.collect()
            text.split('|').forEach { report("correct: $it -> ${PhraseCorrector.correct(PhraseCorrector.tidy(it), words)}") }
            report("done")
            return
        }
        val model = VoiceModels.find(intent.getStringExtra("model") ?: VoiceModels.ENGINE_FUNASR_NANO)
        val wav = File(intent.getStringExtra("wav").orEmpty())
        val threads = intent.getStringExtra("threads")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(4)
        val providers = intent.getStringExtra("providers")?.split(',')?.map { it.trim() } ?: listOf("cpu")
        val repeats = intent.getIntExtra("repeats", 3)
        if (model == null || !model.isInstalled(this) || !wav.isFile) {
            report("model or wav missing: model=$model wav=$wav")
            return
        }
        Thread({ run(model, readWav(wav), threads, providers, repeats) }, "VoiceBench").start()
    }

    private fun run(model: VoiceModel, samples: FloatArray, threads: List<Int>, providers: List<String>, repeats: Int) {
        report("model=${model.id} audioMs=${samples.size / 16} configs=${threads.size * providers.size} repeats=$repeats")
        for (provider in providers) {
            for (count in threads) {
                val tuning = VoiceTuning(count, provider)
                val pssBefore = Debug.getPss()
                val loadStart = SystemClock.elapsedRealtime()
                val recognizer = runCatching { model.createRecognizer(this, tuning) }.getOrElse {
                    report("provider=$provider threads=$count FAILED ${it.message}")
                    continue
                }
                val loadMs = SystemClock.elapsedRealtime() - loadStart
                val pssLoaded = Debug.getPss()
                val decodes = mutableListOf<Long>()
                var text = ""
                repeat(repeats) {
                    val stream = recognizer.createStream()
                    stream.acceptWaveform(samples, 16000)
                    val start = SystemClock.elapsedRealtime()
                    recognizer.decode(stream)
                    decodes += SystemClock.elapsedRealtime() - start
                    text = recognizer.getResult(stream).text
                    stream.release()
                }
                val pssPeak = Debug.getPss()
                recognizer.release()
                report(
                    "provider=$provider threads=$count loadMs=$loadMs decodeMs=${decodes.joinToString("/")} " +
                        "medianMs=${decodes.sorted()[decodes.size / 2]} pssLoadedMB=${(pssLoaded - pssBefore) / 1024} " +
                        "pssPeakMB=${(pssPeak - pssBefore) / 1024} text=$text",
                )
            }
        }
        report("done")
    }

    private fun themePatchSelfTest() {
        fun edit(content: String, block: com.osfans.trime.data.theme.ThemePatchFile.() -> Unit): String {
            val file = File(cacheDir, "selftest.custom.yaml")
            file.writeText(content)
            com.osfans.trime.data.theme.ThemePatchFile(file).apply(block).save()
            return file.readText().also { file.delete() }
        }
        fun check(name: String, actual: String?, expected: String?) =
            report((if (actual == expected) "PASS " else "FAIL ") + name + if (actual != expected) "\n  got: ${actual?.replace("\r", "\\r")}" else "")
        val original = "# private fonts\npatch:\n  style/candidate_font: [ jf.otf ] # licensed\n  style/comment_font: [ jf.otf ]\n\n"
        val added = edit(original) { set("candidate_text_size", "26") }
        check("add after last entry", added, original.replace("  style/comment_font: [ jf.otf ]\n", "  style/comment_font: [ jf.otf ]\n  style/candidate_text_size: 26\n"))
        check("remove restores file", edit(added) { set("candidate_text_size", null) }, original)
        check("replace keeps comment", edit(original) { set("candidate_font", "[ Barlow.ttf ]") }, original.replace("[ jf.otf ] # licensed", "[ Barlow.ttf ] # licensed"))
        check(
            "outside patch untouched",
            edit("other:\n  style/key_text_size: 30\npatch:\n  style/key_text_size: 20\n") { set("key_text_size", "24") },
            "other:\n  style/key_text_size: 30\npatch:\n  style/key_text_size: 24\n",
        )
        check(
            "CRLF kept",
            edit("patch:\r\n  style/key_font: [ a.ttf ]\r\n") { set("key_font", "[ b.ttf ]"); set("label_font", "[ c.ttf ]") },
            "patch:\r\n  style/key_font: [ b.ttf ]\r\n  style/label_font: [ c.ttf ]\r\n",
        )
        val quoted = File(cacheDir, "selftest2.yaml").apply { writeText("patch:\n  style/key_font: [ \"a \\\" #b.ttf\" ] # note\n") }
        check("hash in quotes", com.osfans.trime.data.theme.ThemePatchFile(quoted).get("key_font"), "[ \"a \\\" #b.ttf\" ]")
        quoted.delete()
        check("patch block created", edit("# only a comment\n") { set("key_text_size", "22") }, "# only a comment\n\npatch:\n  style/key_text_size: 22\n")
    }

    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4)
            if (id == "data") {
                val count = size / 2
                return FloatArray(count) { buffer.getShort(offset + 8 + it * 2) / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("no data chunk in $file")
    }

    private fun report(line: String) {
        Log.i(TAG, line)
        runOnUiThread { output.append(line + "\n\n") }
    }

    companion object {
        private const val TAG = "VoiceBench"
    }
}
