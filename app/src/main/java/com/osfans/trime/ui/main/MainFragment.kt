/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.content.Context
import android.content.Intent
import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import com.osfans.trime.R
import com.osfans.trime.ime.voice.VoiceBubbleBridge
import com.osfans.trime.ime.voice.VoiceBubblePermissionActivity
import com.osfans.trime.ime.voice.VoiceBubbleService
import com.osfans.trime.ime.voice.VoiceModels
import com.osfans.trime.ime.voice.GemmaRewrite
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private companion object {
        const val GEMMA_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    }

    private val viewModel: MainViewModel by activityViewModels()
    private var voiceModelsPreference: Preference? = null
    private var voiceBubblePreference: SwitchPreferenceCompat? = null

    override fun onResume() {
        super.onResume()
        refreshVoiceSection()
        // The bubble service attaches shortly after its permission screen closes.
        view?.postDelayed(::refreshVoiceSection, 600)
    }

    private fun refreshVoiceSection() {
        val ctx = context ?: return
        val selected = VoiceModels.selectedEngine(ctx)
        val installed = VoiceModels.all.count { it.isInstalled(ctx) }
        voiceModelsPreference?.summary = "使用中：${VoiceModels.engineName(selected)} · 已安裝 $installed 個模型"
        voiceBubblePreference?.isChecked = VoiceBubbleBridge.isRunning()
    }

    private fun setVoiceBubble(enabled: Boolean) {
        val ctx = requireContext()
        if (enabled == VoiceBubbleBridge.isRunning()) return
        if (enabled) {
            // Requests overlay access first when needed, then starts the bubble.
            startActivity(Intent(ctx, VoiceBubblePermissionActivity::class.java))
        } else {
            ctx.startService(Intent(ctx, VoiceBubbleService::class.java).setAction(VoiceBubbleService.ACTION_TOGGLE))
        }
    }

    private fun confirmGemmaModelDownload() {
        val ctx = requireContext()
        val modelFile = GemmaRewrite.modelFile(ctx)
        if (modelFile?.isFile == true && modelFile.length() > 0) {
            Toast.makeText(ctx, "Gemma 4 E2B 模型已下載", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.gemma_model_download)
            .setMessage(R.string.gemma_model_download_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ ->
                val relativePath = GemmaRewrite.MODEL_RELATIVE_PATH
                runCatching {
                    val target = GemmaRewrite.modelFile(ctx)
                        ?: error("External model directory is unavailable")
                    target.parentFile?.mkdirs()
                    val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(
                        DownloadManager.Request(Uri.parse(GEMMA_MODEL_URL))
                            .setTitle(ctx.getString(R.string.gemma_model_download))
                            .setDescription(ctx.getString(R.string.gemma_model_download_progress))
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setAllowedOverRoaming(false)
                            .setDestinationInExternalFilesDir(ctx, null, relativePath),
                    )
                }.onSuccess {
                    Toast.makeText(ctx, R.string.gemma_model_download_started, Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(ctx, R.string.gemma_model_download_failed, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    private fun PreferenceGroup.addDestinationPreference(
        @StringRes title: Int,
        @StringRes summary: Int,
        @DrawableRes icon: Int,
        route: NavigationRoute,
    ) {
        addPreference(title, summary = summary, icon = icon) {
            findNavController().navigateWithAnim(route)
        }
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addCategory(R.string.settings_section_data) {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.schemata, R.string.settings_summary_schemata,
                    R.drawable.ic_round_view_list_24, NavigationRoute.SchemaList,
                )
                addDestinationPreference(
                    R.string.user_dictionary, R.string.settings_summary_user_dictionary,
                    R.drawable.ic_baseline_book_24, NavigationRoute.UserDict,
                )
                addDestinationPreference(
                    R.string.custom_phrases, R.string.settings_summary_custom_phrases,
                    R.drawable.ic_baseline_edit_24, NavigationRoute.CustomPhrases,
                )
                addDestinationPreference(
                    R.string.profile, R.string.settings_summary_profile,
                    R.drawable.ic_baseline_snippet_folder_24, NavigationRoute.Profile,
                )
            }
            addCategory(R.string.settings_section_keyboard) {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.general, R.string.settings_summary_general,
                    R.drawable.ic_baseline_tune_24, NavigationRoute.General,
                )
                addDestinationPreference(
                    R.string.virtual_keyboard, R.string.settings_summary_virtual_keyboard,
                    R.drawable.ic_baseline_keyboard_24, NavigationRoute.VirtualKeyboard,
                )
                addDestinationPreference(
                    R.string.candidates_window, R.string.settings_summary_candidates_window,
                    R.drawable.ic_baseline_list_alt_24, NavigationRoute.CandidatesWindow,
                )
                addDestinationPreference(
                    R.string.theme, R.string.settings_summary_theme,
                    R.drawable.ic_baseline_color_lens_24, NavigationRoute.Theme,
                )
                addDestinationPreference(
                    R.string.fonts_and_sizes, R.string.settings_summary_fonts_and_sizes,
                    R.drawable.ic_baseline_text_fields_24, NavigationRoute.Fonts,
                )
            }
            addCategory(R.string.settings_section_voice) {
                isIconSpaceReserved = false
                addPreference(R.string.voice_models, icon = R.drawable.ic_baseline_mic_24) {
                    findNavController().navigateWithAnim(NavigationRoute.VoiceModels)
                }
                voiceModelsPreference = getPreference(preferenceCount - 1)
                voiceBubblePreference = SwitchPreferenceCompat(context).apply {
                    setTitle(R.string.voice_bubble)
                    setSummary(R.string.settings_summary_voice_bubble)
                    isPersistent = false
                    isIconSpaceReserved = false
                    setOnPreferenceChangeListener { _, value ->
                        setVoiceBubble(value as Boolean)
                        true
                    }
                }.also(::addPreference)
                addPreference(SwitchPreferenceCompat(context).apply {
                    key = VoiceModels.PREF_AUTO_STOP
                    setDefaultValue(true)
                    setTitle(R.string.voice_auto_stop)
                    setSummary(R.string.settings_summary_voice_auto_stop)
                    isIconSpaceReserved = false
                })
            }
            addCategory(R.string.settings_section_gemma) {
                isIconSpaceReserved = false
                addPreference(
                    R.string.gemma_model_download,
                    summary = R.string.gemma_model_download_summary,
                    icon = R.drawable.ic_baseline_link_24,
                ) { confirmGemmaModelDownload() }
            }
            addCategory(R.string.settings_section_more) {
                isIconSpaceReserved = false
                addDestinationPreference(
                    R.string.clipboard, R.string.settings_summary_clipboard,
                    R.drawable.ic_clipboard_24, NavigationRoute.Clipboard,
                )
                addDestinationPreference(
                    R.string.advanced, R.string.settings_summary_advanced,
                    R.drawable.ic_baseline_more_horiz_24, NavigationRoute.Advanced,
                )
            }
        }
    }
}
