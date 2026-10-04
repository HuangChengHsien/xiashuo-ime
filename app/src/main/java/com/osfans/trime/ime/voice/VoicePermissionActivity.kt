/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.app.AlertDialog
import android.widget.Toast
import com.osfans.trime.R

/** One-time microphone permission request for the keyboard service. */
class VoicePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("voice_permission", MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            finish()
        } else if (prefs.getBoolean("requested", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setMessage("麥克風權限已關閉，請到應用程式設定中允許使用麥克風。")
                .setPositiveButton("前往設定") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    finish()
                }
                .setNegativeButton("取消") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } else {
            prefs.edit().putBoolean("requested", true).apply()
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO) {
            val message = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                R.string.voice_permission_granted
            } else {
                R.string.voice_permission_denied
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private companion object {
        const val REQUEST_AUDIO = 1
    }
}
