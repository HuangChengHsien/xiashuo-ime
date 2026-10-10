// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.base

import android.content.res.AssetManager
import android.os.Build
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.ResourceUtils
import com.osfans.trime.util.appContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object DataManager {
    const val DEFAULT_CUSTOM_FILE_NAME = "default.custom.yaml"
    const val USER_CONFIG_FILE_NAME = "user.yaml"
    const val INSTALLATION_FILE_NAME = "installation.yaml"

    val POST_SCHEMA_DEPLOY_EXPORT_FILES =
        listOf(
            DEFAULT_CUSTOM_FILE_NAME,
            USER_CONFIG_FILE_NAME,
        )

    private const val DATA_CHECKSUMS_NAME = "checksums.json"

    private const val BUNDLE_MIGRATION_MARKER = "rime-tw-bundled-v1"

    private const val SHARED_PREFIX = "shared/"

    private val lock = ReentrantLock()

    private val json by lazy { Json }

    private fun deserializeDataChecksums(raw: String): DataChecksums = json.decodeFromString<DataChecksums>(raw)

    // If Android version supports direct boot, we put the hierarchy in device encrypted storage
    // instead of credential encrypted storage so that data can be accessed before user unlock
    private val dataDir: File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Timber.d("Using device protected storage")
            appContext.createDeviceProtectedStorageContext().dataDir
        } else {
            File(appContext.applicationInfo.dataDir)
        }

    private fun AssetManager.dataChecksums(): DataChecksums = open(DATA_CHECKSUMS_NAME)
        .bufferedReader()
        .use { it.readText() }
        .let { deserializeDataChecksums(it) }

    private val appFilesDir get() = appContext.getExternalFilesDir(null) ?: appContext.filesDir
    val sharedDataDir = File(appFilesDir, "shared").also { it.mkdirs() }

    private val runtimeUserDataDir =
        File(appFilesDir, "rime").also { it.mkdirs() }

    /** App-scoped path used by Rime at runtime. */
    val userDataDir get() = runtimeUserDataDir

    val prebuiltDataDir = File(sharedDataDir, "build")

    /** Files the APK ships in the shared directory (rime-tw and Trime's own), relative to it. */
    private val bundledPaths: Set<String> by lazy {
        appContext.assets.dataChecksums().files
            .filter { (path, sha256) -> sha256.isNotBlank() && path.startsWith(SHARED_PREFIX) }
            .keys
            .mapTo(HashSet()) { it.removePrefix(SHARED_PREFIX) }
    }

    /**
     * Whether the APK bundles this file. Rime prefers the user directory, so a user copy of a
     * bundled file would hide every later update of it.
     */
    fun isBundled(relativePath: String) = relativePath in bundledPaths
    val stagingDir get() = File(userDataDir, "build")

    /**
     * Return the absolute path of the compiled config file
     * based on given resource id.
     *
     * @param resourceId usually equals the config file name without the extension
     * @return the absolute path of the compiled config file
     */
    @JvmStatic
    fun resolveDeployedResourcePath(resourceId: String): String {
        val defaultPath = File(stagingDir, "$resourceId.yaml")
        if (!defaultPath.exists()) {
            val fallbackPath = File(prebuiltDataDir, "$resourceId.yaml")
            if (fallbackPath.exists()) return fallbackPath.absolutePath
        }
        return defaultPath.absolutePath
    }

    /** Returns true when bundled data changed, so Rime should run a full deploy. */
    fun sync(): Boolean = lock.withLock {
        val oldChecksumsFile = File(dataDir, DATA_CHECKSUMS_NAME)
        val oldChecksums =
            oldChecksumsFile
                .runCatching { deserializeDataChecksums(bufferedReader().use { it.readText() }) }
                .getOrElse { DataChecksums("", emptyMap()) }

        val newChecksums = appContext.assets.dataChecksums()

        val diffs = DataDiff.diff(oldChecksums, newChecksums)
        diffs.sortedByDescending { it.ordinal }.forEach {
            Timber.d("Diff: $it")
            when (it) {
                is DataDiff.CreateFile,
                is DataDiff.UpdateFile,
                -> {
                    val destPath = sharedDataDir.resolveSibling(it.path).absolutePath
                    ResourceUtils.copyFile(it.path, destPath)
                }
                is DataDiff.DeleteDir,
                is DataDiff.DeleteFile,
                -> FileUtils.delete(sharedDataDir.resolve(it.path.substringAfterLast('/'))).getOrThrow()
            }
        }

        ResourceUtils.copyFile(DATA_CHECKSUMS_NAME, dataDir.resolve(DATA_CHECKSUMS_NAME).absolutePath)

        // No default.custom.yaml is written: the bundled default.yaml already lists rime-tw's
        // schemas, and a user file here would replace rime-tw's global settings.
        val moved = moveAsideUserCopiesOfBundledFiles()

        Timber.d("Synced!")
        diffs.isNotEmpty() || moved
    }

    /**
     * Before rime-tw was bundled, it was copied into the user directory by hand, where it would
     * shadow the bundled files forever. Move those copies aside once. Personal data (user
     * dictionaries, custom phrases, user.yaml, installation.yaml, sync/) is never bundled and stays.
     */
    private fun moveAsideUserCopiesOfBundledFiles(): Boolean {
        val marker = dataDir.resolve(BUNDLE_MIGRATION_MARKER)
        if (marker.exists()) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val backup = File(appFilesDir, "rime-backup-$stamp")
        val candidates = bundledPaths.map { File(userDataDir, it) }.toMutableList()
        // rime-tw's old Android package shipped its global settings as default.custom.yaml.
        userDataDir.resolve(DEFAULT_CUSTOM_FILE_NAME)
            .takeIf { it.isFile && "rime_tw/octagram_default" in it.readText() }
            ?.let { candidates += it }
        var moved = 0
        candidates.filter { it.isFile }.forEach { file ->
            val dest = backup.resolve(file.relativeTo(userDataDir))
            dest.parentFile?.mkdirs()
            if (file.renameTo(dest)) moved++ else Timber.w("Could not move aside $file")
        }
        if (moved > 0) Timber.i("Moved $moved user copies of bundled files to $backup")
        marker.createNewFile()
        return moved > 0
    }
}
