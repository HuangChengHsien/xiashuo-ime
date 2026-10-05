/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.os.Parcelable
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.data.theme.model.GeneralStyle
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.data.theme.model.Preedit
import com.osfans.trime.data.theme.model.PresetKey
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.data.theme.model.ToolBar
import com.osfans.trime.data.theme.model.Window
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.mapping
import com.osfans.trime.util.yaml.string
import kotlinx.parcelize.Parcelize
import timber.log.Timber

/** 主题和样式配置  */
@Parcelize
data class Theme(
    val name: String,
    val generalStyle: GeneralStyle,
    val preedit: Preedit,
    val window: Window,
    val liquidKeyboard: LiquidKeyboard,
    val presetKeys: Map<String, PresetKey>,
    val presetKeyboards: Map<String, TextKeyboard>,
    val colorSchemes: List<ColorScheme>,
    val fallbackColors: Map<String, String>,
    val toolBar: ToolBar,
) : Parcelable {
    companion object {
        /**
         * Decodes a theme. A malformed preset key, keyboard or color entry is skipped and reported
         * in [warnings] instead of making the whole theme unusable.
         */
        fun decode(
            node: Node.Mapping,
            warnings: MutableList<String>? = null,
        ): Theme = Theme(
            name = requireNotNull(node["name"]?.string) { "缺少 name 欄位" },
            generalStyle = GeneralStyle.decode(requireNotNull(node["style"]) { "缺少 style 區塊" }),
            preedit = Preedit.decode(node["preedit"]?.mapping),
            window = Window.decode(node["window"]?.mapping),
            liquidKeyboard = LiquidKeyboard.decode(node["liquid_keyboard"]?.mapping),
            toolBar = ToolBar.decode(node["tool_bar"]?.mapping),
            presetKeys = decodeEntries(node["preset_keys"], "preset_keys", warnings) {
                PresetKey.decode(requireNotNull(it.mapping) { "不是 key: value 對應" })
            },
            presetKeyboards = decodeEntries(node["preset_keyboards"], "preset_keyboards", warnings) {
                TextKeyboard.decode(requireNotNull(it.mapping) { "不是 key: value 對應" })
            },
            colorSchemes = decodeEntries(node["preset_color_schemes"], "preset_color_schemes", warnings) { scheme ->
                requireNotNull(scheme.mapping) { "不是 key: value 對應" }.entries.mapNotNull { (k, v) ->
                    val key = k.string ?: return@mapNotNull null
                    val value = v.string ?: return@mapNotNull null
                    key to value
                }.toMap()
            }.map { (id, colors) -> ColorScheme(id, colors) },
            fallbackColors = decodeEntries(node["fallback_colors"], "fallback_colors", warnings) {
                requireNotNull(it.string) { "不是文字" }
            },
        )

        private fun <T> decodeEntries(
            node: Node?,
            section: String,
            warnings: MutableList<String>?,
            decode: (Node) -> T,
        ): Map<String, T> {
            val entries = node?.mapping?.entries ?: return emptyMap()
            val result = LinkedHashMap<String, T>()
            for ((key, value) in entries) {
                val name = key.string ?: continue
                runCatching { decode(value) }
                    .onSuccess { result[name] = it }
                    .onFailure {
                        val message = "$section/$name：${it.message ?: it.javaClass.simpleName}"
                        Timber.w(it, "Skip malformed theme entry %s", message)
                        warnings?.add(message)
                    }
            }
            return result
        }
    }
}
