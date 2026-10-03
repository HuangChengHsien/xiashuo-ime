/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.phrase

import com.osfans.trime.core.RimeConfig
import com.osfans.trime.data.base.DataManager
import timber.log.Timber
import java.io.File

/**
 * Mirrors librime's `detect_modifications`: Rime rebuilds every schema at startup when the
 * modification time of a data directory, or of any `.yaml` file in it other than `user.yaml`,
 * is newer than `var/last_build_time` in user.yaml. Saving a phrase file touches both, although
 * no schema reads those files at build time, so a save would otherwise force a ~25 s rebuild.
 * Requires an initialized Rime API.
 */
object RimeWorkspaceStamp {
    private const val KEY = "var/last_build_time"

    private fun watched(): List<File> = listOf(DataManager.userDataDir, DataManager.sharedDataDir)
        .flatMap { dir ->
            listOf(dir) + (dir.listFiles { file: File -> file.isFile && file.extension == "yaml" && file.name != "user.yaml" }
                ?.toList() ?: emptyList())
        }

    private fun lastModifiedSeconds(): Long = watched().maxOf { it.lastModified() } / 1000

    /** Newest modification among watched `.yaml` files other than [except], in seconds. */
    fun lastModifiedExcept(except: File): Long =
        watched().filter { it.isFile && it != except }.maxOfOrNull { it.lastModified() / 1000 } ?: 0

    private fun lastBuildTime(): Long = RimeConfig.openUserConfig("user").use { it.getInt(KEY) ?: 0 }.toLong()

    /** True when something besides our own save is still waiting for a rebuild. */
    fun hasPendingChanges(): Boolean {
        val modified = lastModifiedSeconds()
        val built = lastBuildTime()
        Timber.i("Rime workspace: last modified %d, last build %d", modified, built)
        return modified > built
    }

    /**
     * Records the workspace as built up to now, after a save that no schema depends on. Callers
     * pass the newest other `.yaml` time seen before saving; if another file changed meanwhile,
     * it may need a real rebuild, so nothing is recorded.
     */
    fun markCurrent(saved: File, othersBefore: Long) {
        if (lastModifiedExcept(saved) > othersBefore) {
            Timber.i("Rime workspace: another file changed during the save; leaving rebuild pending")
            return
        }
        val stamp = lastModifiedSeconds().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        RimeConfig.openUserConfig("user").use { it.setInt(KEY, stamp) }
        // librime may keep user.yaml open (its switcher does) and write it only on shutdown, so
        // the value above can stay in memory; put the same value in the file now as well.
        val user = File(DataManager.userDataDir, "user.yaml")
        runCatching {
            val text = user.readText()
            val updated = Regex("(?m)^(\\s+last_build_time:\\s*)\\d+").replace(text) { it.groupValues[1] + stamp }
            // Written in place: a rename would touch the directory and undo the stamp.
            if (updated != text) user.writeText(updated)
        }.onFailure { Timber.w(it, "could not write last_build_time to user.yaml") }
        Timber.i("Rime workspace: recorded last build %d", stamp)
    }
}
