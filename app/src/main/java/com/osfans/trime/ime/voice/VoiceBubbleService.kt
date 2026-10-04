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
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.osfans.trime.R
import kotlin.math.abs
import kotlin.math.hypot

/** Draggable, non-focusable voice control kept above the currently focused text editor. */
class VoiceBubbleService : Service() {
    private var windowManager: WindowManager? = null
    private var bubble: VoiceBubbleView? = null
    private var closeTarget: CloseTargetView? = null
    private var hint: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    /** Converts screen coordinates to [params]: x is measured from the right edge (END gravity). */
    private var screenRight = 0
    private var screenTop = 0
    private var dragged = false
    private var snapped = false
    private var longPressTriggered = false
    private var charger: ValueAnimator? = null
    private val hideHint = Runnable { removeHint() }
    private val backToIdle = Runnable { bubble?.state = VoiceBubbleState.Idle }

    private val density get() = resources.displayMetrics.density

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        } catch (error: RuntimeException) {
            stopSelf()
            return
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
        cancelCharge()
        hideCloseTarget()
        removeHint()
        VoiceBubbleBridge.detach(this)
        bubble?.let {
            it.removeCallbacks(backToIdle)
            runCatching { windowManager?.removeView(it) }
        }
        bubble = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val isBusy: Boolean
        get() = when (bubble?.state) {
            VoiceBubbleState.Preparing, VoiceBubbleState.Listening,
            VoiceBubbleState.WaitingModel, VoiceBubbleState.Recognizing -> true
            else -> false
        }

    fun setState(state: VoiceBubbleState) {
        val view = bubble ?: return
        view.post {
            view.removeCallbacks(backToIdle)
            if (view.state != state) view.announceForAccessibility(state.description)
            view.state = state
            when (state) {
                VoiceBubbleState.Idle -> removeHint()
                VoiceBubbleState.Done -> {
                    showHint("已輸入", DONE_MS)
                    view.postDelayed(backToIdle, DONE_MS)
                }
                is VoiceBubbleState.Problem -> {
                    showHint(state.message)
                    view.postDelayed(backToIdle, HINT_MS)
                }
                else -> defaultHint(state)?.let { showHint(it, persistent = true) }
            }
        }
    }

    private fun defaultHint(state: VoiceBubbleState) = when (state) {
        VoiceBubbleState.Preparing -> "準備麥克風…"
        VoiceBubbleState.Listening -> "請說話，說完點一下停止"
        VoiceBubbleState.WaitingModel -> "錄音完成，等待模型載入…"
        VoiceBubbleState.Recognizing -> "辨識中…"
        else -> null
    }

    /** Replaces the text shown for the current busy state, e.g. once the model has loaded. */
    fun setHint(text: String) {
        bubble?.let { view -> view.post { if (isBusy) showHint(text, persistent = true) } }
    }

    fun setLevel(level: Float) {
        bubble?.let { it.post { it.level = level } }
    }

    fun setRecordingActive(active: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            runCatching { startForeground(NOTIFICATION_ID, notification(), types) }
                .onFailure { stopSelf() }
        }
    }

    private fun overlayParams(width: Int, height: Int, touchable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    )

    private fun showBubble() {
        val view = VoiceBubbleView(this).apply {
            setOnTouchListener { _, event -> handleTouch(event) }
        }
        params = overlayParams(bubbleSize, bubbleSize, touchable = true).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * density).toInt()
            y = (180 * density).toInt()
        }
        windowManager?.addView(view, params)
        bubble = view
    }

    private val bubbleSize get() = (64 * density).toInt()

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = bubble ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = params.x
                startY = params.y
                val at = IntArray(2).also(view::getLocationOnScreen)
                screenRight = at[0] + view.width + params.x
                screenTop = at[1] - params.y
                dragged = false
                snapped = false
                longPressTriggered = false
                if (!isBusy) startCharge(view)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                // A resting finger can drift beyond Android's normal tap slop during the hold.
                val slop = maxOf(ViewConfiguration.get(this).scaledTouchSlop, (24 * density).toInt())
                if (!dragged && (abs(dx) > slop || abs(dy) > slop)) {
                    dragged = true
                    cancelCharge()
                    removeHint()
                    view.dragging = true
                    showCloseTarget()
                }
                if (dragged) moveBubble(view, event, dx, dy)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val heldLongEnough = event.eventTime - event.downTime >= LONG_PRESS_MS
                cancelCharge()
                val close = dragged && snapped
                hideCloseTarget()
                view.dragging = false
                when {
                    close -> {
                        VoiceBubbleBridge.onClose()
                        stopSelf()
                    }
                    dragged || longPressTriggered -> Unit
                    isBusy -> VoiceBubbleBridge.onTap()
                    heldLongEnough -> {
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        VoiceBubbleBridge.onTap()
                    }
                    else -> showHint(getString(R.string.voice_bubble_hold_to_start))
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCharge()
                hideCloseTarget()
                view.dragging = false
                return true
            }
        }
        return false
    }

    /** Follows the finger, but snaps onto the close target while the finger is near it. */
    private fun moveBubble(view: VoiceBubbleView, event: MotionEvent, dx: Int, dy: Int) {
        val target = closeTarget
        val center = IntArray(2)
        val nearTarget = target != null && target.width > 0 && run {
            target.getLocationOnScreen(center)
            center[0] += target.width / 2
            center[1] += target.height / 2
            hypot(event.rawX - center[0], event.rawY - center[1]) < SNAP_RADIUS_DP * density
        }
        if (nearTarget != snapped) {
            snapped = nearTarget
            target?.highlighted = nearTarget
            if (nearTarget) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        if (snapped) {
            // Computed from the mapping taken at touch down, so repeated moves never accumulate
            // error from a layout that has not been applied yet.
            params.x = screenRight - (center[0] + view.width / 2)
            params.y = center[1] - view.height / 2 - screenTop
        } else {
            params.x = (startX - dx).coerceAtLeast(0)
            params.y = (startY + dy).coerceAtLeast(0)
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun startCharge(view: VoiceBubbleView) {
        removeHint()
        charger = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LONG_PRESS_MS
            interpolator = LinearInterpolator()
            addUpdateListener { view.charge = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    view.charge = 0f
                    if (cancelled || dragged) return
                    longPressTriggered = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    VoiceBubbleBridge.onTap()
                }
            })
            start()
        }
    }

    private fun cancelCharge() {
        charger?.cancel()
        charger = null
        bubble?.charge = 0f
    }

    private fun showHint(text: String, duration: Long = HINT_MS, persistent: Boolean = false) {
        val view = bubble ?: return
        view.removeCallbacks(hideHint)
        val label = hint ?: TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Color.argb(225, 40, 40, 40))
            }
        }
        label.text = text
        val layout = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = false).apply {
            gravity = Gravity.TOP or Gravity.END
            x = params.x + bubbleSize + (6 * density).toInt()
            y = params.y + (18 * density).toInt()
        }
        if (hint == null) {
            runCatching { windowManager?.addView(label, layout) }.onSuccess { hint = label }
        } else {
            runCatching { windowManager?.updateViewLayout(label, layout) }
        }
        if (!persistent) view.postDelayed(hideHint, duration)
    }

    private fun removeHint() {
        bubble?.removeCallbacks(hideHint)
        hint?.let { runCatching { windowManager?.removeView(it) } }
        hint = null
    }

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val size = (CLOSE_SIZE_DP * density).toInt()
        val target = CloseTargetView(this).apply { contentDescription = getString(R.string.voice_bubble_drag_to_close) }
        val layout = overlayParams(size, size, touchable = false).apply { gravity = Gravity.CENTER }
        runCatching { windowManager?.addView(target, layout) }.onSuccess { closeTarget = target }
    }

    private fun hideCloseTarget() {
        closeTarget?.let { runCatching { windowManager?.removeView(it) } }
        closeTarget = null
        snapped = false
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

    /** Small round ✕ shown at the screen center while dragging; grows when the bubble snaps on. */
    private class CloseTargetView(context: android.content.Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        var highlighted = false
            set(value) {
                field = value
                animate().scaleX(if (value) 1.25f else 1f).scaleY(if (value) 1.25f else 1f).setDuration(120).start()
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            val d = resources.displayMetrics.density
            fill.color = if (highlighted) Color.argb(235, 200, 50, 50) else Color.argb(170, 30, 30, 30)
            canvas.drawCircle(r, r, r * 0.8f, fill)
            cross.strokeWidth = 3 * d
            val arm = r * 0.28f
            canvas.drawLine(r - arm, r - arm, r + arm, r + arm, cross)
            canvas.drawLine(r + arm, r - arm, r - arm, r + arm, cross)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.osfans.trime.action.TOGGLE_VOICE_BUBBLE"
        private const val CHANNEL_ID = "trime_voice_bubble"
        private const val NOTIFICATION_ID = 33021
        private const val LONG_PRESS_MS = 1_000L
        private const val DONE_MS = 800L
        private const val HINT_MS = 2_000L
        private const val CLOSE_SIZE_DP = 72
        private const val SNAP_RADIUS_DP = 64
    }
}

/** Bridge between the overlay service and the currently active Trime IME instance. */
object VoiceBubbleBridge {
    private var service: VoiceBubbleService? = null
    private var onTap: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null

    fun attach(service: VoiceBubbleService) { this.service = service }
    fun detach(service: VoiceBubbleService) { if (this.service === service) this.service = null }
    fun bind(onTap: () -> Unit, onClose: () -> Unit) {
        this.onTap = onTap
        this.onClose = onClose
    }
    fun unbind() { onTap = null; onClose = null }
    fun onTap() {
        onTap?.invoke() ?: service?.setState(VoiceBubbleState.Problem(service!!.getString(R.string.voice_bubble_ime_unavailable)))
    }
    fun onClose() { onClose?.invoke() }
    fun setState(state: VoiceBubbleState) { service?.setState(state) }
    fun setLevel(level: Float) { service?.setLevel(level) }
    fun setHint(text: String) { service?.setHint(text) }
    fun setRecordingActive(active: Boolean) { service?.setRecordingActive(active) }
    fun isRunning() = service != null
}
