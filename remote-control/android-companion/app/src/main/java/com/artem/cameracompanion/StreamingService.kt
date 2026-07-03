package com.artem.cameracompanion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.SingleCameraConfig
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private val analyzerExecutor = Executors.newFixedThreadPool(2)
    private var cameraProvider: ProcessCameraProvider? = null
    private var wsBack: WebSocket? = null
    private var wsFront: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private var lastFrameBack = 0L
    private var lastFrameFront = 0L
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) host = "localhost:80"
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:80"
        return "ws://$host"
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
        connectCamWs("back")
        connectCamWs("front")
        connectAudioWs()
    }

    private fun connectCamWs(cam: String) {
        val url = "$serverBase/camera?role=phone&cam=$cam"
        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (cam == "back") wsBack = ws else wsFront = ws
                if (wsBack != null && wsFront != null) {
                    broadcast("Идёт трансляция")
                    updateNotification("Идёт трансляция")
                    bindCameras()
                }
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    when (JSONObject(text).getString("cmd")) {
                        "stop" -> { disconnect(); stopSelf() }
                        "start" -> connectAndStream()
                    }
                } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                broadcast("Ошибка $cam: ${t.message}")
                updateNotification("Ошибка подключения")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (cam == "back") wsBack = null else wsFront = null
                broadcast("Отключено ($cam)")
            }
        })
        if (cam == "back") wsBack = ws else wsFront = ws
    }

    private fun connectAudioWs() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return

        val url = "$serverBase/audio?role=phone"
        http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                wsAudio = ws
                startAudioCapture()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                wsAudio = null
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                wsAudio = null
                stopAudioCapture()
            }
        })
    }

    private fun startAudioCapture() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 3200) * 4
        )
        audioRecord = rec
        rec.startRecording()

        audioThread = Thread {
            val chunk = ShortArray(1600) // 100ms at 16kHz
            while (!Thread.currentThread().isInterrupted) {
                val ws = wsAudio ?: break
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                if (ws.queueSize() > 64 * 1024) continue
                val bytes = ByteArray(read * 2)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk, 0, read)
                ws.send(bytes.toByteString())
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopAudioCapture() {
        audioThread?.interrupt()
        audioThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun bindCameras() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val backAnalysis = buildAnalysis { proxy -> sendFrame(proxy, "back") }
            val frontAnalysis = buildAnalysis { proxy -> sendFrame(proxy, "front") }

            try {
                provider.unbindAll()
                val concurrentPairs = provider.availableConcurrentCameraInfos
                val pairWithBoth = concurrentPairs.firstOrNull { infos ->
                    infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                    infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
                }
                if (pairWithBoth != null) {
                    val backSel = pairWithBoth.first { it.lensFacing == CameraSelector.LENS_FACING_BACK }.cameraSelector
                    val frontSel = pairWithBoth.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }.cameraSelector
                    val backCfg = SingleCameraConfig(
                        backSel,
                        UseCaseGroup.Builder().addUseCase(backAnalysis).build(),
                        lifecycleOwner
                    )
                    val frontCfg = SingleCameraConfig(
                        frontSel,
                        UseCaseGroup.Builder().addUseCase(frontAnalysis).build(),
                        lifecycleOwner
                    )
                    provider.bindToLifecycle(listOf(backCfg, frontCfg))
                } else {
                    broadcast("Одновременная съёмка не поддерживается, только задняя камера")
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, backAnalysis)
                }
            } catch (e: Exception) {
                broadcast("Камера недоступна: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildAnalysis(handler: (ImageProxy) -> Unit): ImageAnalysis {
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analyzerExecutor, handler)
        return analysis
    }

    private fun sendFrame(proxy: ImageProxy, cam: String) {
        try {
            val now = System.currentTimeMillis()
            val ws = if (cam == "back") wsBack else wsFront
            val lastAt = if (cam == "back") lastFrameBack else lastFrameFront
            if (ws == null || now - lastAt < 120 || ws.queueSize() > 512 * 1024) return
            if (cam == "back") lastFrameBack = now else lastFrameFront = now

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
        wsBack?.close(1000, "stop"); wsBack = null
        wsFront?.close(1000, "stop"); wsFront = null
        wsAudio?.close(1000, "stop"); wsAudio = null
        stopAudioCapture()
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
