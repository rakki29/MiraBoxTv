package com.example.sender

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Optional user-enabled AccessibilityService on the Phone Sender side.
 * Translates UIBC Remote Control commands received from the TV Box (Back, Home, Recents,
 * D-Pad swipes, and Air-Mouse screen taps) into real system gestures on the mirrored phone.
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
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
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
            0x0028 -> executeRemoteTap(0.5f, 0.5f) // OK / Center tap fallback
            0x0052 -> performSwipe(0.5f, 0.65f, 0.5f, 0.35f) // DPAD_UP -> scroll up
            0x0051 -> performSwipe(0.5f, 0.35f, 0.5f, 0.65f) // DPAD_DOWN -> scroll down
            0x0050 -> performSwipe(0.75f, 0.5f, 0.25f, 0.5f) // DPAD_LEFT -> swipe left
            0x004F -> performSwipe(0.25f, 0.5f, 0.75f, 0.5f) // DPAD_RIGHT -> swipe right
        }
    }

    private fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val (w, h) = getScreenBounds()
        val path = Path().apply {
            moveTo(x1 * w, y1 * h)
            lineTo(x2 * w, y2 * h)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 180L)
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
