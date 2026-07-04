package com.artem.cameracompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        StreamingService.start(this)
        updatePermsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        StreamingService.start(this)
        updatePermsUi()
        requestMissingPermissions()

        findViewById<Button>(R.id.btnOpenAppSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
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
        val cam = if (has(Manifest.permission.CAMERA)) "✓" else "✗"
        val mic = if (has(Manifest.permission.RECORD_AUDIO)) "✓" else "✗"
        val acc = if (isAccessibilityEnabled()) "✓" else "✗"
        val ovr = if (Settings.canDrawOverlays(this)) "✓" else "✗"

        val tv = findViewById<TextView>(R.id.tvPerms) ?: return
        tv.text = "Камера $cam   Микрофон $mic   Упр $acc   Оверлей $ovr"

        val needsSetup = !isAccessibilityEnabled()
        val hint = findViewById<TextView>(R.id.tvAccessibilityHint)
        val btnApp = findViewById<Button>(R.id.btnOpenAppSettings)
        val btnAcc = findViewById<Button>(R.id.btnOpenAccessibility)

        hint.visibility = if (needsSetup) View.VISIBLE else View.GONE
        btnApp.visibility = if (needsSetup) View.VISIBLE else View.GONE
        btnAcc.visibility = if (needsSetup) View.VISIBLE else View.GONE

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
