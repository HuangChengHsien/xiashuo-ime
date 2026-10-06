/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.osfans.trime.core.Rime
import com.osfans.trime.data.base.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.symbol.LiquidData
import com.osfans.trime.util.WeakHashSet
import com.osfans.trime.util.appContext
import com.osfans.trime.util.toast
import com.osfans.trime.util.yaml.Yaml
import com.osfans.trime.util.yaml.mapping
import timber.log.Timber
import java.io.File

object ThemeManager {
    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    fun getAllThemes(): List<ThemeItem> {
        val sharedThemes = ThemeFilesManager.listThemes(DataManager.sharedDataDir)
        val userThemes = ThemeFilesManager.listThemes(DataManager.userDataDir)
        // A theme bundled in the APK can also exist in the user folder (e.g. dak66); Rime uses the
        // user copy, so list it once and let the user entry win.
        return (userThemes + sharedThemes).distinctBy { it.configId }
    }

    private lateinit var _activeTheme: Theme

    private fun ensureActiveTheme() {
        if (!::_activeTheme.isInitialized) {
            _activeTheme = evaluateActiveTheme()
        }
    }

    var activeTheme: Theme
        get() {
            ensureActiveTheme()
            return _activeTheme
        }
        private set(value) {
            if (::_activeTheme.isInitialized && _activeTheme == value) return
            _activeTheme = value
            fireChange()
        }

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.add(listener)
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        onChangeListeners.remove(listener)
    }

    private fun fireChange() {
        onChangeListeners.forEach { it.onThemeChange(_activeTheme) }
    }

    val prefs = AppPrefs.defaultInstance().registerProvider(::ThemePrefs)

    private data class ResolvedTheme(
        val configId: String,
        val theme: Theme,
    )

    /** Why the last [loadThemeByIdOrNull] call returned null, shown when falling back. */
    private var lastLoadError: String? = null

    private fun loadThemeByIdOrNull(id: String): Theme? {
        lastLoadError = null
        if (!Rime.deployRimeConfigFile(id, "config_version")) {
            Timber.w("Failed to deploy theme config file '$id.yaml'")
        }
        val file = File(DataManager.resolveDeployedResourcePath(id))
        if (!file.exists()) {
            Timber.w("Theme file not found for '$id'")
            lastLoadError = "找不到編譯後的主題檔，可能是 $id.yaml 有 YAML 語法錯誤，Rime 部署失敗"
            return null
        }
        return try {
            val node = Yaml.parseToYamlNode(file.readText())
            val mapping = node.mapping
            if (mapping == null) {
                Timber.w("Failed to load theme '$id': YAML root is not a mapping")
                lastLoadError = "主題檔最外層不是 key: value 對應"
                null
            } else {
                val warnings = mutableListOf<String>()
                Theme.decode(mapping, warnings).also {
                    if (warnings.isNotEmpty()) {
                        notifyUser("主題 $id 有 ${warnings.size} 個項目格式錯誤，已略過：\n" + warnings.take(3).joinToString("\n"))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load theme '$id'")
            lastLoadError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    private fun getThemeById(id: String): ResolvedTheme {
        loadThemeByIdOrNull(id)?.let { return ResolvedTheme(id, it) }
        val reason = lastLoadError

        if (id != "trime") {
            loadThemeByIdOrNull("trime")?.let {
                Timber.w("Theme '$id' is unavailable, fallback to default theme 'trime'")
                notifyFallback(id, "trime", reason)
                return ResolvedTheme("trime", it)
            }
        }

        for (fallbackId in getAllThemes().map { it.configId }.distinct()) {
            loadThemeByIdOrNull(fallbackId)?.let {
                Timber.w("Theme '$id' is unavailable, fallback to available theme '$fallbackId'")
                notifyFallback(id, fallbackId, reason)
                return ResolvedTheme(fallbackId, it)
            }
        }

        error("No valid theme available")
    }

    private fun notifyFallback(id: String, fallbackId: String, reason: String?) {
        notifyUser("主題 $id 無法載入，暫時改用 $fallbackId。\n原因：${reason ?: "未知"}")
    }

    /** Theme files are user-edited; tell the user instead of silently switching themes. */
    private fun notifyUser(message: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { appContext.toast(message.take(300), Toast.LENGTH_LONG) }
        }
    }

    private fun evaluateActiveTheme(): Theme {
        val selectedThemeId = prefs.selectedTheme.getValue()
        val resolvedTheme = getThemeById(selectedThemeId)
        val newTheme = resolvedTheme.theme
        if (resolvedTheme.configId != selectedThemeId) {
            prefs.selectedTheme.setValue(resolvedTheme.configId)
        }
        KeyActionManager.resetCache()
        FontManager.resetCache(newTheme)
        ColorManager.switchTheme(newTheme)
        LiquidData.init(newTheme)
        return newTheme
    }

    fun init(configuration: Configuration) {
        ensureActiveTheme()
        ColorManager.init(configuration)
    }

    fun selectTheme(configId: String) {
        val resolvedTheme = getThemeById(configId)
        val theme = resolvedTheme.theme
        KeyActionManager.resetCache()
        FontManager.resetCache(theme)
        ColorManager.switchTheme(theme)
        LiquidData.init(theme)
        activeTheme = theme
        prefs.selectedTheme.setValue(resolvedTheme.configId)
    }
}
