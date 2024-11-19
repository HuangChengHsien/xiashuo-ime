/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

enum class PopupCandidatesMode(
    override val stringRes: Int,
) : PreferenceDelegateEnum {
    CURRENT_PAGE(R.string.current_page_of_candidates),
    PREEDIT_ONLY(R.string.preedit_only),
}
