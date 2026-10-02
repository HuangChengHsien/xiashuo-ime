/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceGroup
import com.osfans.trime.R
import com.osfans.trime.ui.common.PaddingPreferenceFragment
import com.osfans.trime.util.addCategory
import com.osfans.trime.util.addPreference
import com.osfans.trime.util.navigateWithAnim

class MainFragment : PaddingPreferenceFragment() {
    private val viewModel: MainViewModel by activityViewModels()

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
