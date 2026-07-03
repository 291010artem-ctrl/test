package com.artem.cameracompanion

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var screenGranted = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Tell service about newly granted permissions so it can start camera/audio streams
        StreamingService.start(this)
        updatePermsUi()
        requestScreenCapture()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        screenGranted = result.resultCode == Activity.RESULT_OK && result.data != null
        if (screenGranted) {
            StreamingService.startScreenCapture(this, result.resultCode, result.data!!)
        }
        updatePermsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Connect to server immediately — phone appears in panel before any permission dialogs
        StreamingService.start(this)
        updatePermsUi()
        requestPermissionsFlow()
    }

    override fun onResume() {
        super.onResume()
        updatePermsUi()
    }

    private fun requestPermissionsFlow() {
        val needed = buildList {
            if (!has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) requestScreenCapture()
        else requestPermissions.launch(needed.toTypedArray())
    }

    private fun requestScreenCapture() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }

    fun updatePermsUi() {
        val tv = findViewById<TextView>(R.id.tvPerms) ?: return
        val cam = if (has(Manifest.permission.CAMERA)) "✓" else "✗"
        val mic = if (has(Manifest.permission.RECORD_AUDIO)) "✓" else "✗"
        val scr = if (screenGranted) "✓" else "✗"
        val acc = if (isAccessibilityEnabled()) "✓" else "✗"
        tv.text = "Камера $cam   Микр $mic   Экран $scr   Упр $acc"
    }
}
