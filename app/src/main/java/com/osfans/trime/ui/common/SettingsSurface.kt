/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.osfans.trime.R

fun Context.settingsBackgroundColor(): Int = ContextCompat.getColor(this, R.color.settings_background)

fun Context.settingsCardBackground(): GradientDrawable = GradientDrawable().apply {
    cornerRadius = 18f * resources.displayMetrics.density
    setColor(ContextCompat.getColor(this@settingsCardBackground, R.color.settings_surface))
}

/** Gives the existing preference controls a shared settings layout without changing their behavior. */
fun RecyclerView.useSettingsCards() {
    setBackgroundColor(context.settingsBackgroundColor())
    val density = resources.displayMetrics.density
    setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
    clipToPadding = false
    addItemDecoration(SettingsCardDecoration(context))
}

private class SettingsCardDecoration(context: Context) : RecyclerView.ItemDecoration() {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.settings_surface)
    }
    private val radius = 18 * density

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val horizontal = (16 * density).toInt()
        val isCategory = preferenceAt(parent, view) is PreferenceCategory
        outRect.set(horizontal, if (isCategory) (14 * density).toInt() else (4 * density).toInt(),
            horizontal, if (isCategory) 0 else (4 * density).toInt())
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (preferenceAt(parent, child) is PreferenceCategory) continue
            canvas.drawRoundRect(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(),
                child.bottom.toFloat(), radius, radius, paint)
        }
    }

    private fun preferenceAt(parent: RecyclerView, child: View) =
        (parent.adapter as? PreferenceGroupAdapter)?.let { adapter ->
            parent.getChildAdapterPosition(child).takeIf { it != RecyclerView.NO_POSITION }?.let(adapter::getItem)
        }
}
