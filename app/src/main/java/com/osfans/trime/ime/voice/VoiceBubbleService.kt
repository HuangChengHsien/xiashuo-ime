/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import kotlin.math.abs

/** Draggable, non-focusable voice control kept above the currently focused text editor. */
class VoiceBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private var closeTarget: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false
    private var longPressTriggered = false
    private var listening = false
    private var longPressAction: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        windowManager = getSystemService(WindowManager::class.java)
        showBubble()
        VoiceBubbleBridge.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelLongPress()
        hideCloseTarget()
        VoiceBubbleBridge.detach(this)
        bubble?.let { runCatching { windowManager?.removeView(it) } }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun setStatus(text: String, listening: Boolean) {
        bubble?.post {
            this.listening = listening
            val (symbol, color, action) = when {
                listening -> Triple("■", Color.rgb(170, 45, 45), getString(R.string.voice_bubble_tap_to_stop))
                text.contains("辨識") -> Triple("…", Color.rgb(190, 115, 24), "正在辨識")
                text.contains("授權", ignoreCase = true) || text.contains("尚未") || text.contains("無") || text.contains("錯誤") || text.contains("文字框") || text.contains("服務無法") ->
                    Triple("!", Color.rgb(170, 105, 28), text)
                else -> Triple("🎙", Color.rgb(38, 91, 130), getString(R.string.voice_bubble_hold_to_start))
            }
            bubble?.text = symbol
            bubble?.background = circleBackground(color)
            bubble?.contentDescription = "$text，$action"
        }
    }

    fun setRecordingActive(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            startForeground(NOTIFICATION_ID, notification(), types)
        }
    }

    private fun showBubble() {
        val density = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "🎙"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circleBackground(Color.rgb(38, 91, 130))
            elevation = 12 * density
            contentDescription = getString(R.string.voice_bubble_description)
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        params = WindowManager.LayoutParams(
            (64 * density).toInt(),
            (64 * density).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (180 * density).toInt()
        }
        windowManager?.addView(view, params)
        bubble = view
    }

    private fun circleBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke((1.5f * resources.displayMetrics.density).toInt(), Color.argb(190, 255, 255, 255))
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = bubble ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                dragged = false
                longPressTriggered = false
                if (!listening) {
                    longPressAction = Runnable {
                        if (!dragged) {
                            longPressTriggered = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            VoiceBubbleBridge.onTap()
                        }
                    }.also { view.postDelayed(it, LONG_PRESS_MS) }
                    view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(LONG_PRESS_MS).start()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                // A resting finger can drift beyond Android's normal tap slop during a 2-second hold.
                val slop = maxOf(ViewConfiguration.get(this).scaledTouchSlop, (24 * resources.displayMetrics.density).toInt())
                if (abs(dx) > slop || abs(dy) > slop) {
                    if (!dragged) {
                        dragged = true
                        cancelLongPress()
                        showCloseTarget()
                    }
                }
                if (dragged) {
                    params.x = (startX - dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    runCatching { windowManager?.updateViewLayout(view, params) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                val close = dragged && isOverCloseTarget(event)
                hideCloseTarget()
                when {
                    close -> {
                        VoiceBubbleBridge.onClose()
                        stopSelf()
                    }
                    !dragged && !longPressTriggered && !listening &&
                        event.eventTime - event.downTime >= LONG_PRESS_MS -> {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        VoiceBubbleBridge.onTap()
                    }
                    !dragged && !longPressTriggered && listening -> VoiceBubbleBridge.onTap()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                hideCloseTarget()
                return true
            }
        }
        return false
    }

    private fun cancelLongPress() {
        longPressAction?.let { bubble?.removeCallbacks(it) }
        longPressAction = null
        bubble?.animate()?.cancel()
        bubble?.scaleX = 1f
        bubble?.scaleY = 1f
    }

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val density = resources.displayMetrics.density
        val target = TextView(this).apply {
            text = getString(R.string.voice_bubble_drag_to_close)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 150, 40, 40))
            }
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (72 * density).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        runCatching { windowManager?.addView(target, layout) }
            .onSuccess { closeTarget = target }
    }

    private fun isOverCloseTarget(event: MotionEvent): Boolean {
        val target = closeTarget ?: return false
        if (target.height == 0) return false
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        return event.rawY >= location[1] && event.rawY <= location[1] + target.height
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { windowManager?.removeView(it) } }
        closeTarget = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.voice_bubble_notification), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_baseline_mic_24)
        .setContentTitle(getString(R.string.voice_bubble_notification))
        .setContentText(getString(R.string.voice_bubble_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val ACTION_TOGGLE = "com.osfans.trime.action.TOGGLE_VOICE_BUBBLE"
        private const val CHANNEL_ID = "trime_voice_bubble"
        private const val NOTIFICATION_ID = 33021
        private const val LONG_PRESS_MS = 1_000L
    }
}

/** Bridge between the overlay service and the currently active Trime IME instance. */
object VoiceBubbleBridge {
    private var service: VoiceBubbleService? = null
    private var onTap: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null
    private var onStatus: ((String, Boolean) -> Unit)? = null

    fun attach(service: VoiceBubbleService) { this.service = service }
    fun detach(service: VoiceBubbleService) { if (this.service === service) this.service = null }
    fun bind(onTap: () -> Unit, onClose: () -> Unit, onStatus: (String, Boolean) -> Unit) {
        this.onTap = onTap
        this.onClose = onClose
        this.onStatus = onStatus
    }
    fun unbind() { onTap = null; onClose = null; onStatus = null }
    fun onTap() { onTap?.invoke() ?: service?.let { Toast.makeText(it, R.string.voice_bubble_ime_unavailable, Toast.LENGTH_SHORT).show() } }
    fun onClose() { onClose?.invoke() }
    fun setStatus(text: String, listening: Boolean) { onStatus?.invoke(text, listening); service?.setStatus(text, listening) }
    fun setRecordingActive(active: Boolean) { service?.setRecordingActive(active) }
    fun isRunning() = service != null
}
