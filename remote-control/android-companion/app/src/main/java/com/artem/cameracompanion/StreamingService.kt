package com.artem.cameracompanion

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
        const val ACTION_STOP = "STOP"
        const val ACTION_STATUS = "com.artem.cameracompanion.STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val CHANNEL_ID = "streaming"
        const val NOTIF_ID = 1
        var isRunning = false

        fun start(ctx: Context, resultCode: Int = 0, data: Intent? = null) {
            val i = Intent(ctx, StreamingService::class.java).setAction(ACTION_START)
            if (resultCode != 0 && data != null) {
                i.putExtra(EXTRA_RESULT_CODE, resultCode)
                i.putExtra(EXTRA_RESULT_DATA, data)
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
    private var wsBack: WebSocket? = null
    private var wsAudio: WebSocket? = null
    private var wsScreen: WebSocket? = null
    private var lastFrameBack = 0L
    private var lastFrameScreen = 0L
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var screenThread: Thread? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var mediaProjection: MediaProjection? = null
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
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(resultCode, resultData)
                }
                connectAndStream()
            }
            ACTION_STOP -> { disconnect(); stopSelf() }
        }
        return START_STICKY
    }

    private fun connectAndStream() {
        connectCamWs()
        connectAudioWs()
        connectScreenWs()
    }

    private fun connectCamWs() {
        val url = "$serverBase/camera?role=phone&cam=back&model=$encodedModel"
        http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                wsBack = ws
                broadcast("Камера подключена")
                updateNotification("Идёт трансляция")
                bindCamera()
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
                wsBack = null
                broadcast("Ошибка камеры: ${t.message}")
                updateNotification("Ошибка подключения")
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                wsBack = null
                broadcast("Камера отключена")
            }
        })
    }

    private fun connectAudioWs() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        val url = "$serverBase/audio?role=phone&model=$encodedModel"
        http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { wsAudio = ws; startAudioCapture() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { wsAudio = null }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { wsAudio = null; stopAudioCapture() }
        })
    }

    private fun connectScreenWs() {
        if (mediaProjection == null) return
        val url = "$serverBase/screen?role=phone&model=$encodedModel"
        http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { wsScreen = ws; startScreenCapture() }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { wsScreen = null; stopScreenCapture() }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { wsScreen = null; stopScreenCapture() }
        })
    }

    private fun startAudioCapture() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 3200) * 4
        )
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

    private fun startScreenCapture() {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)

        // Scale to 720px wide max for bandwidth efficiency
        val scale = minOf(1f, 720f / metrics.widthPixels)
        val sw = (metrics.widthPixels * scale).toInt()
        val sh = (metrics.heightPixels * scale).toInt()

        imageReader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "arp-screen", sw, sh, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        screenThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val ws = wsScreen ?: break
                val now = System.currentTimeMillis()
                if (now - lastFrameScreen < 150) { Thread.sleep(30); continue }
                if (ws.queueSize() > 512 * 1024) { Thread.sleep(50); continue }
                val image = imageReader?.acquireLatestImage() ?: run { Thread.sleep(30); continue }
                try {
                    val plane = image.planes[0]
                    val buf = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    val w = image.width
                    val h = image.height
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    if (rowStride == w * 4 && pixelStride == 4) {
                        bmp.copyPixelsFromBuffer(buf)
                    } else {
                        val pixels = IntArray(w * h)
                        var idx = 0
                        for (row in 0 until h) {
                            val rowBase = row * rowStride
                            for (col in 0 until w) {
                                val pos = rowBase + col * pixelStride
                                val r = buf.get(pos).toInt() and 0xFF
                                val g = buf.get(pos + 1).toInt() and 0xFF
                                val b = buf.get(pos + 2).toInt() and 0xFF
                                val a = buf.get(pos + 3).toInt() and 0xFF
                                pixels[idx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            }
                        }
                        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                    }
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    bmp.recycle()
                    ws.send(out.toByteArray().toByteString())
                    lastFrameScreen = now
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopScreenCapture() {
        screenThread?.interrupt(); screenThread = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val backAnalysis = buildAnalysis { proxy -> sendFrame(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, backAnalysis)
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

    private fun sendFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            val ws = wsBack ?: return
            if (now - lastFrameBack < 120 || ws.queueSize() > 512 * 1024) return
            lastFrameBack = now
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
        wsAudio?.close(1000, "stop"); wsAudio = null
        wsScreen?.close(1000, "stop"); wsScreen = null
        stopAudioCapture()
        stopScreenCapture()
        mediaProjection?.stop(); mediaProjection = null
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
