package com.example.sender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.example.data.SecondScreenProfile

data class SecondScreenApplyResult(
    val appliedResolutionDpi: Boolean = false,
    val appliedRotationLock: Boolean = false,
    val description: String = ""
)

/**
 * Implements genuine SecondScreen-style system display transformation on the Phone Sender:
 * 1. Rotation lock via WRITE_SETTINGS (no ADB required, 1-tap system permission)
 * 2. Resolution + Tablet DPI override via IWindowManager (requires optional WRITE_SECURE_SETTINGS via ADB)
 * Always snapshots original state and restores it cleanly when casting stops.
 */
object SecondScreenController {

    private const val PREFS_NAME = "mirabox_secondscreen_restore"
    private const val KEY_ORIG_ACCEL_ROT = "orig_accel_rot"
    private const val KEY_ORIG_USER_ROT = "orig_user_rot"
    private const val KEY_ROTATION_MODIFIED = "rot_modified"
    private const val KEY_DISPLAY_MODIFIED = "display_modified"

    fun hasWriteSettingsPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
    }

    fun hasWriteSecureSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun adbGrantCommand(context: Context): String {
        return "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
    }

    fun applySecondScreenProfile(
        context: Context,
        profile: SecondScreenProfile,
        forceLandscape: Boolean
    ): SecondScreenApplyResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var appliedRot = false
        var appliedResDpi = false

        // 1. System Rotation Lock (WRITE_SETTINGS)
        if (forceLandscape && hasWriteSettingsPermission(context)) {
            runCatching {
                val cr = context.contentResolver
                if (!prefs.getBoolean(KEY_ROTATION_MODIFIED, false)) {
                    val prevAccel = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                    val prevUserRot = Settings.System.getInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0)
                    prefs.edit()
                        .putInt(KEY_ORIG_ACCEL_ROT, prevAccel)
                        .putInt(KEY_ORIG_USER_ROT, prevUserRot)
                        .putBoolean(KEY_ROTATION_MODIFIED, true)
                        .apply()
                }
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_90)
                appliedRot = true
            }
        }

        // 2. Forced Tablet Resolution & DPI (WRITE_SECURE_SETTINGS)
        if (profile != SecondScreenProfile.OFF && hasWriteSecureSettingsPermission(context)) {
            val (natW, natH) = resolveNaturalDimensionsForDevice(context, profile.width, profile.height)
            appliedResDpi = applyForcedDisplaySizeAndDensity(natW, natH, profile.dpi)
            if (appliedResDpi) {
                prefs.edit().putBoolean(KEY_DISPLAY_MODIFIED, true).apply()
            }
        }

        val desc = when {
            appliedResDpi -> "SecondScreen ${profile.width}x${profile.height} @ ${profile.dpi}dpi"
            appliedRot -> "Landscape Rotation Lock Active"
            else -> ""
        }
        return SecondScreenApplyResult(
            appliedResolutionDpi = appliedResDpi,
            appliedRotationLock = appliedRot,
            description = desc
        )
    }

    fun restoreOriginalDisplay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_DISPLAY_MODIFIED, false) && hasWriteSecureSettingsPermission(context)) {
            clearForcedDisplaySizeAndDensity()
            prefs.edit().putBoolean(KEY_DISPLAY_MODIFIED, false).apply()
        }

        if (prefs.getBoolean(KEY_ROTATION_MODIFIED, false) && hasWriteSettingsPermission(context)) {
            runCatching {
                val cr = context.contentResolver
                val origAccel = prefs.getInt(KEY_ORIG_ACCEL_ROT, 1)
                val origUserRot = prefs.getInt(KEY_ORIG_USER_ROT, Surface.ROTATION_0)
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, origUserRot)
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, origAccel)
            }
            prefs.edit().putBoolean(KEY_ROTATION_MODIFIED, false).apply()
        }
    }

    /**
     * Natural portrait phones (like Galaxy S8) expect smaller width x larger height for setForcedDisplaySize,
     * so rotating 90 degrees into landscape yields 1280x720 (or 1920x1080) without squashing!
     */
    @Suppress("DEPRECATION")
    private fun resolveNaturalDimensionsForDevice(
        context: Context,
        landscapeW: Int,
        landscapeH: Int
    ): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        val rot = display.rotation
        val isRotated = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val natW = if (isRotated) dm.heightPixels else dm.widthPixels
        val natH = if (isRotated) dm.widthPixels else dm.heightPixels
        val shortEdge = minOf(landscapeW, landscapeH)
        val longEdge = maxOf(landscapeW, landscapeH)
        return if (natH >= natW) {
            shortEdge to longEdge
        } else {
            longEdge to shortEdge
        }
    }

    @SuppressLint("PrivateApi")
    private fun applyForcedDisplaySizeAndDensity(width: Int, height: Int, dpi: Int): Boolean {
        return runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getWms = wmgClass.getMethod("getWindowManagerService")
            val wms = getWms.invoke(null) ?: return@runCatching false

            val setSize = wms.javaClass.getMethod(
                "setForcedDisplaySize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            setSize.invoke(wms, Display.DEFAULT_DISPLAY, width, height)

            val userId = Process.myUserHandle().hashCode()
            runCatching {
                val setDensityForUser = wms.javaClass.getMethod(
                    "setForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                setDensityForUser.invoke(wms, Display.DEFAULT_DISPLAY, dpi, userId)
            }.onFailure {
                val setDensity = wms.javaClass.getMethod(
                    "setForcedDisplayDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                setDensity.invoke(wms, Display.DEFAULT_DISPLAY, dpi)
            }
            true
        }.getOrDefault(false)
    }

    @SuppressLint("PrivateApi")
    private fun clearForcedDisplaySizeAndDensity() {
        runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val wms = wmgClass.getMethod("getWindowManagerService").invoke(null) ?: return
            runCatching {
                wms.javaClass.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType)
                    .invoke(wms, Display.DEFAULT_DISPLAY)
            }
            val userId = Process.myUserHandle().hashCode()
            runCatching {
                wms.javaClass.getMethod(
                    "clearForcedDisplayDensityForUser",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(wms, Display.DEFAULT_DISPLAY, userId)
            }.onFailure {
                runCatching {
                    wms.javaClass.getMethod("clearForcedDisplayDensity", Int::class.javaPrimitiveType)
                        .invoke(wms, Display.DEFAULT_DISPLAY)
                }
            }
        }
    }
}
