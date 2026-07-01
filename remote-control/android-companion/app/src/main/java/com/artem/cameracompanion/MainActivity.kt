package com.artem.cameracompanion

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.artem.cameracompanion.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var webSocket: WebSocket? = null
    private var streaming = false
    private var lastFrameAt = 0L

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // Адрес панели зашивается при сборке через BuildConfig.DEFAULT_SERVER.
    // Формат: "192.168.0.10:8787" или "192.168.0.10" (порт 8787 по умолчанию).
    private val serverUrl: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) host = "localhost:8787"
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:8787"
        return "ws://$host/camera?role=phone"
    }

    private val requestCamera = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startStreaming()
        else status("Нет доступа к камере — разрешите в настройках приложения")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener {
            if (streaming) stopStreaming() else ensureCameraThenStart()
        }
    }

    private fun ensureCameraThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startStreaming()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startStreaming() {
        val url = serverUrl
        status("Подключение…")
        val req = Request.Builder().url(url).build()
        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                runOnUiThread {
                    status("Идёт трансляция")
                    streaming = true
                    binding.startButton.text = "Остановить"
                }
                bindCamera()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { status("Ошибка: ${t.message}"); stopStreaming() }
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                runOnUiThread { if (streaming) status("Соединение закрыто") }
            }
        })
    }

    private fun stopStreaming() {
        streaming = false
        binding.startButton.text = "Начать трансляцию"
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        webSocket?.close(1000, "stop"); webSocket = null
        status("Остановлено")
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.preview.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { proxy -> handleFrame(proxy) }
            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                runOnUiThread { status("Камера недоступна: ${e.message}") }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val ws = webSocket
            if (!streaming || ws == null || now - lastFrameAt < 120 || ws.queueSize() > 512 * 1024) return
            lastFrameAt = now
            val bitmap = proxy.toBitmap()
            val rotated = rotate(bitmap, proxy.imageInfo.rotationDegrees)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 55, out)
            ws.send(out.toByteArray().toByteString())
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun rotate(bmp: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bmp
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun status(text: String) { binding.statusText.text = text }

    override fun onDestroy() {
        super.onDestroy()
        analyzerExecutor.shutdown()
        webSocket?.close(1000, "destroy")
    }
}
