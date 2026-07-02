package com.artem.cameracompanion

import android.app.*
import android.content.*
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_STATUS = "com.artem.cameracompanion.STATUS"
        const val EXTRA_STATUS = "status"
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1
        var isRunning = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, StreamingService::class.java).setAction(ACTION_START))
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val lifecycleOwner = StreamingLifecycleOwner()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var webSocket: WebSocket? = null
    private var lastFrameAt = 0L
    private var useFrontCamera = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val serverUrl: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) host = "localhost:8787"
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:8787"
        return "ws://$host/camera?role=phone"
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Подключение…"))
        lifecycleOwner.start()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arp:streaming")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> connectAndStream()
            ACTION_STOP -> { disconnect(); stopSelf() }
        }
        return START_STICKY
    }

    private fun connectAndStream() {
        val req = Request.Builder().url(serverUrl).build()
        webSocket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                broadcast("Идёт трансляция")
                updateNotification("Идёт трансляция")
                bindCamera()
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    when (JSONObject(text).getString("cmd")) {
                        "stop" -> { disconnect(); stopSelf() }
                        "start" -> connectAndStream()
                        "switch" -> { useFrontCamera = !useFrontCamera; bindCamera() }
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                broadcast("Ошибка: ${t.message}")
                updateNotification("Ошибка подключения")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                broadcast("Отключено")
                updateNotification("Отключено")
            }
        })
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor) { proxy -> handleFrame(proxy) }
            try {
                cameraProvider?.unbindAll()
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.bindToLifecycle(lifecycleOwner, selector, analysis)
            } catch (e: Exception) {
                broadcast("Камера недоступна: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val ws = webSocket
            if (ws == null || now - lastFrameAt < 120 || ws.queueSize() > 512 * 1024) return
            lastFrameAt = now
            val bitmap = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            val out = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 55, out)
            ws.send(out.toByteArray().toByteString())
        } catch (_: Exception) {
        } finally {
            proxy.close()
        }
    }

    private fun disconnect() {
        cameraProvider?.unbindAll()
        webSocket?.close(1000, "stop")
        webSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        disconnect()
        lifecycleOwner.stop()
        analyzerExecutor.shutdown()
        wakeLock?.release()
    }

    override fun onBind(intent: Intent?) = null

    private fun broadcast(status: String) {
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS, status))
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Трансляция камеры", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Remote Panel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}

class StreamingLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() { Handler(Looper.getMainLooper()).post { registry.currentState = Lifecycle.State.RESUMED } }
    fun stop() { Handler(Looper.getMainLooper()).post { registry.currentState = Lifecycle.State.DESTROYED } }
}
