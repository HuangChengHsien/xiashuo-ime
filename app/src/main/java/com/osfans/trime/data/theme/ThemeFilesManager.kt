/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.base.DataManager
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import timber.log.Timber
import java.io.File

object ThemeFilesManager {
    fun listThemes(dir: File): MutableList<ThemeItem> {
        val files = dir.listFiles { _, name -> name.endsWith("trime.yaml") } ?: return mutableListOf()
        val deployedMap = hashMapOf<String, String>()
        DataManager.stagingDir.list()?.forEach {
            deployedMap[it] = it
        }
        DataManager.prebuiltDataDir.list()?.forEach {
            deployedMap[it] = it
        }
        return files
            .sortedByDescending { it.lastModified() }
            .mapNotNull decode@{
                val item =
                    runCatching {
                        val configId = it.nameWithoutExtension
                        val fallbackName = configId.removeSuffix(".trime")
                        // Keep a theme listed even when its compiled copy is broken, so the user can
                        // still pick it and see why it fails instead of it silently disappearing.
                        val name =
                            if (deployedMap[it.name] != null) {
                                runCatching {
                                    val file = File(DataManager.resolveDeployedResourcePath(configId))
                                    Yaml.parseToYamlNode(file.readText()).mapping?.get("name")?.string
                                }.onFailure { e ->
                                    Timber.w("Failed to read name of theme ${it.absolutePath}: ${e.message}")
                                }.getOrNull() ?: fallbackName
                            } else {
                                fallbackName
                            }
                        ThemeItem(configId, name)
                    }.getOrElse { e ->
                        Timber.w("Failed to decode theme file ${it.absolutePath}: ${e.message}")
                        return@decode null
                    }
                return@decode item
            }.toMutableList()
    }
}
