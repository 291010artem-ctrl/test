package com.artem.cameracompanion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.view.Display
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
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1
        var isRunning = false
        @Volatile var screenQuality = 60
        @Volatile var hasProjection = false
        @Volatile var statusText = "Запуск…"
        @Volatile var paused = false

        fun start(ctx: Context, projectionCode: Int = -1, projectionData: Intent? = null) {
            val i = Intent(ctx, StreamingService::class.java).setAction(ACTION_START)
            if (projectionCode != -1 && projectionData != null) {
                i.putExtra("projectionCode", projectionCode)
                i.putExtra("projectionData", projectionData)
            }
            ctx.startForegroundService(i)
        }
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, StreamingService::class.java).setAction(ACTION_STOP))
        }
    }

    private val lifecycleOwner = StreamingLifecycleOwner()
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private val lastFrameBackArr  = LongArray(1)
    private val lastFrameFrontArr = LongArray(1)
    private var lastScreenFrameTime = 0L
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var currentCam = "back"
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenImageReader: ImageReader? = null
    private var screenHandlerThread: HandlerThread? = null
    private var screenHandler: Handler? = null

    private val http = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var wsBack: WebSocket? = null
    @Volatile private var wsFront: WebSocket? = null
    @Volatile private var wsAudio: WebSocket? = null
    @Volatile private var wsScreen: WebSocket? = null
    @Volatile private var frameErrorReported = false

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
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
                else if (cameraProvider == null &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) bindCamera()
                if (wsAudio == null) connectAudioWs()
                val code = intent.getIntExtra("projectionCode", -1)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("projectionData")
                if (code != -1 && data != null && mediaProjection == null) {
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mgr.getMediaProjection(code, data)
                    hasProjection = true
                    if (wsScreen == null) connectScreenWs()
                }
            }
            "SWITCH_CAM" -> switchCamera(intent.getStringExtra("cam") ?: "back")
            ACTION_STOP  -> { disconnect(); stopSelf() }
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
                    currentCam = "back"  // всегда начинаем с задней камеры при (пере)подключении
                    paused = false
                    updateNotification("Идёт трансляция")
                    connectFrontCamWs()
                    bindCamera()
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("cmd")) {
                            "stop"   -> { disconnect(); stopSelf() }
                            "start"  -> { if (wsBack == null) connectCamWs() }
                            "switch" -> switchCamera(json.optString("cam", "back"))
                            "pause"  -> { paused = true; updateNotification("Приостановлено"); sendCamStatus("Трансляция приостановлена") }
                            "resume" -> { paused = false; updateNotification("Идёт трансляция"); sendCamStatus("ok") }
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
                override fun onOpen(ws: WebSocket, response: Response) {
                    wsFront = ws
                    // Если уже переключились на фронт пока соединение устанавливалось — перепривязываем камеру
                    if (currentCam == "front") {
                        cameraProvider?.let { provider ->
                            ContextCompat.getMainExecutor(this@StreamingService).execute {
                                bindCameraInternal(provider)
                            }
                        }
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsFront = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsFront == null) connectFrontCamWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsFront = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsFront == null) connectFrontCamWs() }, 5000)
                }
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
        try {
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = maxOf(if (minBuf > 0) minBuf else 3200, 3200) * 4
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
            if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
            audioRecord = rec
            rec.startRecording()
            audioThread = Thread {
                val chunk = ShortArray(1600)
                while (!Thread.currentThread().isInterrupted) {
                    val ws = wsAudio ?: break
                    val read = try { rec.read(chunk, 0, chunk.size) } catch (_: Exception) { break }
                    if (read <= 0) continue
                    if (ws.queueSize() > 64 * 1024) continue
                    val bytes = ByteArray(read * 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunk, 0, read)
                    ws.send(bytes.toByteString())
                }
            }.apply { isDaemon = true; start() }
        } catch (_: Exception) { }
    }

    private fun stopAudioCapture() {
        audioThread?.interrupt(); audioThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release(); audioRecord = null
    }

    // ── Screen streaming via MediaProjection ──────────────────────────────

    private fun connectScreenWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/screen?role=phone&model=$encodedModel").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsScreen = ws; startVirtualDisplay() }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsScreen = null; releaseVirtualDisplay()
                    if (isRunning && mediaProjection != null)
                        Handler(Looper.getMainLooper()).postDelayed({ if (wsScreen == null) connectScreenWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsScreen = null; releaseVirtualDisplay()
                    if (isRunning && mediaProjection != null)
                        Handler(Looper.getMainLooper()).postDelayed({ if (wsScreen == null) connectScreenWs() }, 5000)
                }
            })
    }

    private fun startVirtualDisplay() {
        val mp = mediaProjection ?: return
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val w = (metrics.widthPixels / 2).coerceAtLeast(360)
        val h = (metrics.heightPixels / 2).coerceAtLeast(640)
        val dpi = metrics.densityDpi / 2
        val ht = HandlerThread("arp_screen_reader").also { it.start(); screenHandlerThread = it }
        val handler = Handler(ht.looper).also { screenHandler = it }
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        screenImageReader = reader
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (paused) return@setOnImageAvailableListener
                val ws = wsScreen ?: return@setOnImageAvailableListener
                if (ws.queueSize() > 512 * 1024) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastScreenFrameTime < 80) return@setOnImageAvailableListener
                lastScreenFrameTime = now
                val plane = img.planes[0]
                val rowW = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(rowW, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = if (rowW > w) Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() } else bmp
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, screenQuality, out)
                cropped.recycle()
                ws.send(out.toByteArray().toByteString())
            } finally { img.close() }
        }, handler)
        virtualDisplay = mp.createVirtualDisplay("arp_screen", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release(); virtualDisplay = null
        screenImageReader?.close(); screenImageReader = null
        screenHandlerThread?.quitSafely(); screenHandlerThread = null; screenHandler = null
    }

    // ── CameraX binding & switching ────────────────────────────────────────

    private fun sendCamStatus(text: String) {
        val msg = JSONObject().put("type", "cam-status").put("text", text).toString()
        val ws = if (currentCam == "front") (wsFront ?: wsBack) else wsBack
        ws?.send(msg)
    }

    private fun bindCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Нет разрешения камеры")
            sendCamStatus("Нет разрешения камеры — выдай его в Настройки → Приложения")
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bindCameraInternal(provider)
            } catch (e: Exception) {
                val msg = "Провайдер камеры: ${e.message?.take(60)}"
                updateNotification(msg)
                sendCamStatus(msg)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraInternal(provider: ProcessCameraProvider) {
        try {
            provider.unbindAll()
            frameErrorReported = false
            updateNotification("Подключение камеры…")
            sendCamStatus("Открытие камеры…")
            val isFront = currentCam == "front"
            val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA
                           else CameraSelector.DEFAULT_BACK_CAMERA
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val camRef = currentCam
            analysis.setAnalyzer(analyzerExecutor) { proxy ->
                sendFrame(proxy,
                    if (camRef == "front") wsFront else wsBack,
                    if (camRef == "front") lastFrameFrontArr else lastFrameBackArr)
            }
            provider.bindToLifecycle(lifecycleOwner, selector, analysis)
            updateNotification("Идёт трансляция")
            sendCamStatus("ok")
        } catch (e: Exception) {
            val msg = "Ошибка камеры: ${e.message?.take(60)}"
            updateNotification(msg)
            sendCamStatus(msg)
        }
    }

    private fun switchCamera(cam: String) {
        currentCam = cam
        if (cam == "front" && wsFront == null) connectFrontCamWs()
        val provider = cameraProvider ?: return
        ContextCompat.getMainExecutor(this).execute { bindCameraInternal(provider) }
    }

    // ── Frame helpers ──────────────────────────────────────────────────────

    private fun sendFrame(proxy: ImageProxy, target: WebSocket?, lastArr: LongArray) {
        try {
            if (paused) return
            val now = System.currentTimeMillis()
            val ws = target ?: return
            if (now - lastArr[0] < 33 || ws.queueSize() > 512 * 1024) return
            lastArr[0] = now
            val bitmap = proxy.toBitmap()
            val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            bitmap.recycle()
            val scale = minOf(1f, 720f / maxOf(rotated.width, rotated.height))
            val final = if (scale < 1f)
                Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true).also { rotated.recycle() }
            else rotated
            val out = ByteArrayOutputStream()
            final.compress(Bitmap.CompressFormat.JPEG, 55, out)
            final.recycle()
            ws.send(out.toByteArray().toByteString())
            frameErrorReported = false
        } catch (e: Exception) {
            if (!frameErrorReported) {
                frameErrorReported = true
                sendCamStatus("Ошибка кадра: ${e.message?.take(50) ?: e.javaClass.simpleName}")
            }
        } finally {
            proxy.close()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private fun disconnect() {
        cameraProvider?.unbindAll(); cameraProvider = null
        wsBack?.close(1000, "stop");   wsBack = null
        wsFront?.close(1000, "stop");  wsFront = null
        wsAudio?.close(1000, "stop");  wsAudio = null
        wsScreen?.close(1000, "stop"); wsScreen = null
        stopAudioCapture()
        releaseVirtualDisplay()
        mediaProjection?.stop(); mediaProjection = null
        hasProjection = false
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
        statusText = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}

class StreamingLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    fun start() {
        Handler(Looper.getMainLooper()).post {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }
    fun stop() {
        Handler(Looper.getMainLooper()).post {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
