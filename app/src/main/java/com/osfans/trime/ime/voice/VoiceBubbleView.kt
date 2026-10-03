/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.osfans.trime.R

/** What the voice bubble is doing; each state has its own ring, badge and spoken description. */
sealed class VoiceBubbleState(val description: String) {
    data object Idle : VoiceBubbleState("語音懸浮球，按住 1 秒開始說話")
    data object Preparing : VoiceBubbleState("正在開啟麥克風")
    data object Listening : VoiceBubbleState("麥克風開啟，正在聆聽；點一下停止")
    data object WaitingModel : VoiceBubbleState("錄音完成，正在等待模型載入")
    data object Recognizing : VoiceBubbleState("正在辨識")
    data object Done : VoiceBubbleState("已輸入文字")
    data class Problem(val message: String) : VoiceBubbleState(message)
}

/**
 * Draws the app icon with a status ring. Idle is half transparent so the bubble does not hide
 * content; every active state is opaque and also differs by ring shape, so state never depends
 * on color or transparency alone.
 */
class VoiceBubbleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val icon: Drawable? = ContextCompat.getDrawable(context, R.mipmap.ic_app_icon_round)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val bounds = RectF()
    private var spinner: ValueAnimator? = null
    private var rotation = 0f

    var state: VoiceBubbleState = VoiceBubbleState.Idle
        set(value) {
            if (field == value) return
            field = value
            level = 0f
            updateSpinner()
            alpha = if (value == VoiceBubbleState.Idle && !dragging) IDLE_ALPHA else 1f
            contentDescription = value.description
            invalidate()
        }

    /** Long-press progress from 0 to 1, shown as a filling ring while idle. */
    var charge = 0f
        set(value) {
            field = value
            alpha = if (value > 0f || dragging || state != VoiceBubbleState.Idle) 1f else IDLE_ALPHA
            invalidate()
        }

    /** Microphone level from 0 to 1; widens the listening ring so the user sees they are heard. */
    var level = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (state == VoiceBubbleState.Listening) invalidate()
        }

    var dragging = false
        set(value) {
            field = value
            alpha = if (!value && state == VoiceBubbleState.Idle && charge == 0f) IDLE_ALPHA else 1f
        }

    init {
        alpha = IDLE_ALPHA
        contentDescription = state.description
    }

    private fun updateSpinner() {
        val period = when (state) {
            VoiceBubbleState.Preparing, VoiceBubbleState.WaitingModel -> 1600L
            VoiceBubbleState.Recognizing -> 900L
            else -> 0L
        }
        spinner?.cancel()
        spinner = null
        if (period == 0L) return
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = period
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotation = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val stroke = 4 * density
        val inset = 7 * density
        icon?.setBounds(inset.toInt(), inset.toInt(), (size - inset).toInt(), (size - inset).toInt())
        icon?.draw(canvas)
        bounds.set(stroke, stroke, size - stroke, size - stroke)
        ring.strokeWidth = stroke
        when (val s = state) {
            VoiceBubbleState.Idle -> if (charge > 0f) {
                ring.color = Color.WHITE
                canvas.drawArc(bounds, -90f, 360f * charge, false, ring)
            }
            VoiceBubbleState.Listening -> {
                ring.color = LISTENING
                ring.strokeWidth = stroke * (0.75f + level * 0.9f)
                canvas.drawOval(bounds, ring)
            }
            VoiceBubbleState.Preparing, VoiceBubbleState.WaitingModel, VoiceBubbleState.Recognizing -> {
                ring.color = if (s == VoiceBubbleState.Recognizing) BUSY else WAITING
                canvas.drawArc(bounds, rotation - 90f, 100f, false, ring)
            }
            VoiceBubbleState.Done -> {
                ring.color = DONE
                canvas.drawOval(bounds, ring)
                drawBadge(canvas, size, DONE, "✓")
            }
            is VoiceBubbleState.Problem -> drawBadge(canvas, size, PROBLEM, "!")
        }
    }

    private fun drawBadge(canvas: Canvas, size: Float, color: Int, text: String) {
        val radius = 10 * density
        val cx = size - radius
        val cy = radius
        badge.color = color
        canvas.drawCircle(cx, cy, radius, badge)
        badgeText.textSize = 14 * density
        canvas.drawText(text, cx, cy - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
    }

    companion object {
        private const val IDLE_ALPHA = 0.5f
        private val LISTENING = Color.rgb(214, 48, 49)
        private val WAITING = Color.rgb(230, 160, 40)
        private val BUSY = Color.rgb(41, 128, 185)
        private val DONE = Color.rgb(39, 174, 96)
        private val PROBLEM = Color.rgb(211, 84, 0)
    }
}
