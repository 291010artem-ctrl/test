package com.artem.cameracompanion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.artem.cameracompanion.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            binding.statusText.text = intent.getStringExtra(StreamingService.EXTRA_STATUS) ?: ""
        }
    }

    private val requestCamera = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) StreamingService.start(this)
        else binding.statusText.text = "Нет доступа к камере — разрешите в настройках"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener {
            if (StreamingService.isRunning) {
                StreamingService.stop(this)
                binding.startButton.text = "Начать трансляцию"
                binding.statusText.text = "Остановлено"
            } else {
                ensureCameraPermission()
            }
        }

        ensureCameraPermission()
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            StreamingService.start(this)
            binding.startButton.text = "Остановить"
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(StreamingService.ACTION_STATUS),
            RECEIVER_NOT_EXPORTED)
        binding.startButton.text = if (StreamingService.isRunning) "Остановить" else "Начать трансляцию"
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }
}
