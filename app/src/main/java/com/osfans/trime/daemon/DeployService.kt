/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.daemon

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.osfans.trime.util.DeployNotification
import com.osfans.trime.util.appContext
import timber.log.Timber

/**
 * Keeps the process running while librime deploys. A first deploy compiles every bundled schema
 * and takes minutes; without a foreground service Android freezes the app as soon as the user
 * leaves it, and the deploy stops halfway.
 */
class DeployService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always enter the foreground first: a service started with startForegroundService()
        // crashes the app if it stops before calling startForeground().
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, DeployNotification.progressNotification(), type)
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        // Separate from DeployNotification.MESSAGE_ID so removing this one never hides the result.
        private const val NOTIFICATION_ID = 2334
        private const val ACTION_STOP = "stop"

        @Volatile private var started = false

        fun start() {
            runCatching {
                ContextCompat.startForegroundService(appContext, Intent(appContext, DeployService::class.java))
                started = true
            }.onFailure {
                Timber.w(it, "Deploy foreground service not allowed, showing a plain notification")
                DeployNotification.showProgress()
            }
        }

        fun stop() {
            if (!started) return DeployNotification.cancel()
            started = false
            runCatching {
                appContext.startService(Intent(appContext, DeployService::class.java).setAction(ACTION_STOP))
            }.onFailure { appContext.stopService(Intent(appContext, DeployService::class.java)) }
        }
    }
}
