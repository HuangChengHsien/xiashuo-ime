/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.RimeProto
import com.osfans.trime.data.theme.Theme
import splitties.views.dsl.core.Ui
import splitties.views.dsl.recyclerview.recyclerView

class PagedCandidatesUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    private var menu = RimeProto.Context.Menu()

    class UiViewHolder(
        val ui: Ui,
    ) : RecyclerView.ViewHolder(ui.root)

    val candidatesAdapter =
        object : BaseQuickAdapter<RimeProto.Candidate, UiViewHolder>() {
            override fun getItemCount(items: List<RimeProto.Candidate>) =
                items.size + (if (menu.pageNumber != 0 || !menu.isLastPage) 1 else 0)

            override fun getItemViewType(
                position: Int,
                list: List<RimeProto.Candidate>,
            ) = if (position < list.size) 0 else 1

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int,
            ): UiViewHolder =
                when (viewType) {
                    0 -> UiViewHolder(LabeledCandidateItemUi(ctx, theme))
                    else -> UiViewHolder(LabeledCandidateItemUi(ctx, theme))
                }

            override fun onBindViewHolder(
                holder: UiViewHolder,
                position: Int,
                item: RimeProto.Candidate?,
            ) {
                when (getItemViewType(position)) {
                    0 -> {
                        holder.ui as LabeledCandidateItemUi
                        val candidate = item ?: return
                        holder.ui.update(candidate, position == menu.highlightedCandidateIndex)
                    }
                }
            }
        }

    private val candidatesLayoutManager =
        FlexboxLayoutManager(ctx).apply {
            flexWrap = FlexWrap.WRAP
            flexDirection = FlexDirection.ROW
            alignItems = AlignItems.BASELINE
        }

    override val root =
        recyclerView {
            isFocusable = false
            adapter = candidatesAdapter
            layoutManager = candidatesLayoutManager
        }

    fun update(menu: RimeProto.Context.Menu) {
        this.menu = menu
        candidatesAdapter.submitList(menu.candidates.toList())
    }
}
