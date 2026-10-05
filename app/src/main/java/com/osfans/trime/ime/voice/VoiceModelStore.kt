/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/** File operations shared by model import, download and deletion. */
object VoiceModelStore {
    /** Hidden file next to [target] that holds an import or download until it is verified. */
    fun partFile(target: File) = File(target.parentFile, ".${target.name}.part")

    /** Copies [input] into the part file, then verifies and installs it. */
    fun importStream(
        model: VoiceModel,
        spec: VoiceModelFile,
        target: File,
        input: InputStream,
        onProgress: (Long) -> Unit,
    ) {
        importStream(model.title, spec, target, input, onProgress)
    }

    fun importStream(
        model: LlmModel,
        spec: VoiceModelFile,
        target: File,
        input: InputStream,
        onProgress: (Long) -> Unit,
    ) {
        importStream(model.title, spec, target, input, onProgress)
    }

    private fun importStream(
        title: String,
        spec: VoiceModelFile,
        target: File,
        input: InputStream,
        onProgress: (Long) -> Unit,
    ) {
        val part = partFile(target).apply { parentFile?.mkdirs() }
        try {
            var copied = 0L
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= spec.size) { "${spec.name} 比 $title 需要的檔案大，可能是其他版本" }
                    output.write(buffer, 0, count)
                    onProgress(copied)
                }
                output.fd.sync()
            }
            commit(spec, part, target)
        } finally {
            part.delete()
        }
    }

    /**
     * Installs a fully written [part] as [target] after checking its size and SHA-256. The previous
     * file, if any, stays in place until the new one has been verified.
     */
    fun commit(spec: VoiceModelFile, part: File, target: File) {
        require(part.length() == spec.size) { "${spec.name} 大小不符（${part.length()}），可能是其他版本" }
        val hash = sha256(part)
        if (hash != spec.sha256) {
            part.delete()
            error("${spec.name} SHA-256 不符，檔案已損壞或是其他版本")
        }
        val backup = File(target.parentFile, ".${target.name}.previous")
        if (target.exists()) {
            check(!backup.exists() || backup.delete()) { "無法清理舊備份：${spec.name}" }
            check(target.renameTo(backup)) { "無法備份現有檔案：${spec.name}" }
        }
        if (!part.renameTo(target)) {
            if (backup.exists()) backup.renameTo(target)
            error("無法完成安裝：${spec.name}")
        }
        backup.delete()
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Stops any download, removes the model and falls back to Android speech if it was selected. */
    fun delete(context: Context, model: VoiceModel) {
        VoiceModelDownloadWorker.cancelAndWait(context, model)
        val dir = model.directory(context)
        check(dir.deleteRecursively()) { "部分檔案無法刪除：${dir.absolutePath}" }
        if (VoiceModels.selectedEngine(context) == model.id) {
            VoiceModels.setSelectedEngine(context, VoiceModels.ENGINE_GOOGLE)
        }
    }

    private const val BUFFER_SIZE = 1024 * 1024
}
