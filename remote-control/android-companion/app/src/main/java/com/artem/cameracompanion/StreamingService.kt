package com.artem.cameracompanion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import android.util.DisplayMetrics
import android.util.Size
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class StreamingService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP  = "STOP"
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1
        var isRunning = false
        @Volatile var screenQuality = 60
        @Volatile var hasProjection = false

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

    private var wsBack: WebSocket? = null
    private var wsFront: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private var wsScreen: WebSocket? = null
    private val lastFrameBackArr  = LongArray(1)
    private val lastFrameFrontArr = LongArray(1)
    private var lastScreenFrameTime = 0L
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var currentCam = "back"
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var screenImageReader: ImageReader? = null
    private var screenHandlerThread: HandlerThread? = null
    private var screenHandler: Handler? = null

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraImageReader: ImageReader? = null
    private var cameraHandlerThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

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
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arp:streaming")
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (wsBack == null) connectCamWs()
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
                    updateNotification("Идёт трансляция")
                    connectFrontCamWs()
                    openCamera("back")
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        when (json.optString("cmd")) {
                            "stop"   -> { disconnect(); stopSelf() }
                            "start"  -> { if (wsBack == null) connectCamWs() }
                            "switch" -> switchCamera(json.optString("cam", "back"))
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

    // ── Camera2 ────────────────────────────────────────────────────────────

    private fun sendCamStatus(text: String) {
        wsBack?.send(JSONObject().put("type", "cam-status").put("text", text).toString())
    }

    private fun openCamera(cam: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Нет разрешения камеры")
            sendCamStatus("Нет разрешения камеры — выдай его в Настройки → Приложения")
            return
        }
        closeCamera()
        currentCam = cam
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val facing = if (cam == "front") CameraCharacteristics.LENS_FACING_FRONT
                         else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == facing
            } ?: run {
                sendCamStatus("Камера ($cam) не найдена на устройстве"); return
            }
            val map = mgr.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()
            val size = sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) } ?: Size(640, 480)

            val ht = HandlerThread("arp_cam").also { it.start(); cameraHandlerThread = it }
            cameraHandler = Handler(ht.looper)

            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            cameraImageReader = reader
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val ws = if (currentCam == "front") wsFront else wsBack
                    val arr = if (currentCam == "front") lastFrameFrontArr else lastFrameBackArr
                    val now = System.currentTimeMillis()
                    if (ws == null || now - arr[0] < 33 || ws.queueSize() > 512 * 1024) return@setOnImageAvailableListener
                    arr[0] = now
                    val plane = img.planes[0]
                    val bytes = ByteArray(plane.buffer.remaining())
                    plane.buffer.get(bytes)
                    ws.send(bytes.toByteString())
                } finally { img.close() }
            }, cameraHandler)

            updateNotification("Открытие камеры…")
            sendCamStatus("Открытие камеры…")

            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    device.createCaptureSession(listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }.build()
                                session.setRepeatingRequest(req, null, cameraHandler)
                                updateNotification("Идёт трансляция")
                                sendCamStatus("ok")
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                updateNotification("Ошибка сессии камеры")
                                sendCamStatus("Не удалось создать сессию камеры")
                            }
                        }, cameraHandler)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); cameraDevice = null
                    sendCamStatus("Камера отключена")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); cameraDevice = null
                    val msg = when (error) {
                        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "Камера занята другим приложением — закрой его"
                        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "Слишком много камер открыто"
                        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "Камера отключена политикой устройства"
                        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "Аппаратная ошибка камеры"
                        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "Ошибка службы камеры Android"
                        else -> "Ошибка камеры #$error"
                    }
                    updateNotification(msg); sendCamStatus(msg)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            val msg = "Исключение: ${e.message?.take(80)}"
            updateNotification("Ошибка: ${e.message?.take(40)}")
            sendCamStatus(msg)
        }
    }

    private fun closeCamera() {
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        cameraImageReader?.close(); cameraImageReader = null
        cameraHandlerThread?.quitSafely(); cameraHandlerThread = null; cameraHandler = null
    }

    private fun switchCamera(cam: String) {
        if (cam == currentCam) return
        closeCamera()
        openCamera(cam)
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private fun disconnect() {
        closeCamera()
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
