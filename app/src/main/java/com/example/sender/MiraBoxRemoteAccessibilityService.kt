package com.example.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optional user-enabled AccessibilityService on the Phone Sender side.
 * Translates UIBC Remote Control commands received from the TV Box (Back, Home, Recents,
 * P+/P-, D-Pad focus/scroll, OK click, and Air-Mouse screen taps) into real system gestures.
 */
class MiraBoxRemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MiraBoxRemoteAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun executeRemoteTap(normX: Float, normY: Float) {
        val (screenW, screenH) = getScreenBounds()
        val x = (normX.coerceIn(0.02f, 0.98f) * screenW)
        val y = (normY.coerceIn(0.02f, 0.98f) * screenH)
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 55L)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null,
            null
        )
    }

    fun executeRemoteKey(hidKeyCode: Int) {
        when (hidKeyCode) {
            0x0029 -> performGlobalAction(GLOBAL_ACTION_BACK)
            0x004A -> performGlobalAction(GLOBAL_ACTION_HOME)
            0x0076 -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            0x0028 -> {
                // Click accessibility/input-focused item if available, otherwise center tap
                val root = runCatching { rootInActiveWindow }.getOrNull()
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                val clicked = focused?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (!clicked) {
                    executeRemoteTap(0.5f, 0.5f)
                }
            }
            // Safe-zone D-Pad swipes (stay well inside 0.25..0.75 to avoid Android edge-back gesture)
            0x0052 -> performSwipe(0.5f, 0.62f, 0.5f, 0.38f, 160L) // DPAD_UP
            0x0051 -> performSwipe(0.5f, 0.38f, 0.5f, 0.62f, 160L) // DPAD_DOWN
            0x0050 -> performSwipe(0.68f, 0.5f, 0.32f, 0.5f, 160L) // DPAD_LEFT
            0x004F -> performSwipe(0.32f, 0.5f, 0.68f, 0.5f, 160L) // DPAD_RIGHT
            // P+ / P- (Channel / Page Up & Down) -> smooth full-page / feed scroll
            0x004B -> performSwipe(0.5f, 0.74f, 0.5f, 0.24f, 220L) // P+
            0x004E -> performSwipe(0.5f, 0.24f, 0.5f, 0.74f, 220L) // P-
        }
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val (w, h) = getScreenBounds()
        val path = Path().apply {
            moveTo(x1 * w, y1 * h)
            lineTo(x2 * w, y2 * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun getScreenBounds(): Pair<Float, Float> {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().toFloat() to bounds.height().toFloat()
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        }
    }
}
