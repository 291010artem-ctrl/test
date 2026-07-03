package com.artem.cameracompanion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.*
import android.net.Uri
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val ACTION_STATUS = "com.artem.cameracompanion.STATUS"
        const val EXTRA_STATUS = "status"
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1
        var isRunning = false

        fun start(ctx: Context) {
            ctx.startForegroundService(
                Intent(ctx, StreamingService::class.java).setAction(ACTION_START)
            )
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val lifecycleOwner = StreamingLifecycleOwner()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var wsBack: WebSocket? = null
    private var wsFront: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private val lastFrameBackArr  = LongArray(1)
    private val lastFrameFrontArr = LongArray(1)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var currentCam = "back"
    private var wakeLock: PowerManager.WakeLock? = null

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) host = "localhost:80"
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:80"
        return "ws://$host"
    }
    private val encodedModel: String get() = Uri.encode(Build.MODEL ?: "Android")

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
            ACTION_START -> {
                if (wsBack == null) connectCamWs()
                if (wsAudio == null) connectAudioWs()
            }
            "SWITCH_CAM" -> {
                val cam = intent.getStringExtra("cam") ?: "back"
                switchCamera(cam)
            }
            ACTION_STOP -> { disconnect(); stopSelf() }
        }
        return START_STICKY
    }

    // ── Camera WebSockets ──────────────────────────────────────────────────

    private fun connectCamWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/camera?role=phone&cam=back&model=$encodedModel").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    wsBack = ws
                    updateNotification("Идёт трансляция")
                    connectFrontCamWs()
                    bindCamera()
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        when (JSONObject(text).getString("cmd")) {
                            "stop"  -> { disconnect(); stopSelf() }
                            "start" -> { if (wsBack == null) connectCamWs() }
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsBack = null; updateNotification("Ошибка подключения")
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsBack == null) connectCamWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsBack = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsBack == null) connectCamWs() }, 5000)
                }
            })
    }

    private fun connectFrontCamWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/camera?role=phone&cam=front&model=$encodedModel").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsFront = ws }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { wsFront = null }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) { wsFront = null }
            })
    }

    // ── Audio WebSocket ────────────────────────────────────────────────────

    private fun connectAudioWs() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        http.newWebSocket(
            Request.Builder().url("$serverBase/audio?role=phone&model=$encodedModel").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsAudio = ws; startAudioCapture() }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsAudio = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsAudio == null) connectAudioWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsAudio = null; stopAudioCapture()
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsAudio == null) connectAudioWs() }, 5000)
                }
            })
    }

    private fun startAudioCapture() {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 3200) * 4)
        audioRecord = rec
        rec.startRecording()
        audioThread = Thread {
            val chunk = ShortArray(1600)
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
        audioThread?.interrupt(); audioThread = null
        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
    }

    // ── Camera binding & switching ─────────────────────────────────────────

    private fun bindCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            cameraProvider = provider
            bindCameraInternal(provider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraInternal(provider: ProcessCameraProvider) {
        try {
            provider.unbindAll()
            val backAnalysis  = buildAnalysis { proxy -> sendFrame(proxy, wsBack,  lastFrameBackArr) }

            val supportsConcurrent = provider.availableConcurrentCameraInfos.any { infos ->
                infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
                infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
            }

            val boundConcurrent = if (supportsConcurrent) {
                try {
                    val frontAnalysis = buildAnalysis { proxy -> sendFrame(proxy, wsFront, lastFrameFrontArr) }
                    provider.bindToLifecycle(listOf(
                        ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            UseCaseGroup.Builder().addUseCase(backAnalysis).build(),
                            lifecycleOwner
                        ),
                        ConcurrentCamera.SingleCameraConfig(
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            UseCaseGroup.Builder().addUseCase(frontAnalysis).build(),
                            lifecycleOwner
                        )
                    ))
                    true
                } catch (_: Exception) { false }
            } else false

            if (!boundConcurrent) {
                val sel = if (currentCam == "front") CameraSelector.DEFAULT_FRONT_CAMERA
                          else CameraSelector.DEFAULT_BACK_CAMERA
                val ws  = if (currentCam == "front") wsFront else wsBack
                val arr = if (currentCam == "front") lastFrameFrontArr else lastFrameBackArr
                provider.bindToLifecycle(lifecycleOwner, sel, buildAnalysis { proxy -> sendFrame(proxy, ws, arr) })
            }
        } catch (e: Exception) {
            broadcast("Камера недоступна: ${e.message}")
        }
    }

    private fun switchCamera(cam: String) {
        currentCam = cam
        val provider = cameraProvider ?: return
        ContextCompat.getMainExecutor(this).execute { bindCameraInternal(provider) }
    }

    // ── Frame helpers ──────────────────────────────────────────────────────

    private fun buildAnalysis(handler: (ImageProxy) -> Unit): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(analyzerExecutor, handler) }

    private fun sendFrame(proxy: ImageProxy, target: WebSocket?, lastArr: LongArray) {
        try {
            val now = System.currentTimeMillis()
            val ws = target ?: return
            if (now - lastArr[0] < 120 || ws.queueSize() > 512 * 1024) return
            lastArr[0] = now
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

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private fun disconnect() {
        cameraProvider?.unbindAll()
        wsBack?.close(1000, "stop");  wsBack = null
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
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
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
    fun stop()  { Handler(Looper.getMainLooper()).post { registry.currentState = Lifecycle.State.DESTROYED } }
}
