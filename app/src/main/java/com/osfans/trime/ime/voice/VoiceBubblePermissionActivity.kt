/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Requests the special overlay access and starts the bubble as soon as the user returns. */
class VoiceBubblePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
            startBubble()
        } else {
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                REQUEST_OVERLAY,
            )
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) startBubble()
            else finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun startBubble() {
        ContextCompat.startForegroundService(this, Intent(this, VoiceBubbleService::class.java))
        finish()
    }

    companion object { private const val REQUEST_OVERLAY = 2204 }
}
