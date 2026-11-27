package com.example.sender

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

data class DisplayMetricsInfo(
    val densityDpi: Int,
    val smallestWidthDp: Int,
    val isTabletWidth: Boolean
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
    private const val KEY_ROOT_MODIFIED = "root_modified"
    private const val KEY_ORIG_DENSITY_FORCED = "orig_density_forced"
    private const val KEY_ORIG_SIZE_FORCED = "orig_size_forced"

    private val mainHandler = Handler(Looper.getMainLooper())

    fun getCurrentDisplayMetricsInfo(context: Context): DisplayMetricsInfo {
        val cfg = context.resources.configuration
        val dm = context.resources.displayMetrics
        val sw = cfg.smallestScreenWidthDp
        val dpi = dm.densityDpi
        return DisplayMetricsInfo(
            densityDpi = dpi,
            smallestWidthDp = sw,
            isTabletWidth = sw >= 600
        )
    }

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

        // 2. Forced Tablet Resolution & DPI (WRITE_SECURE_SETTINGS or Root su fallback)
        if (profile != SecondScreenProfile.OFF) {
            val (natW, natH) = resolveNaturalDimensionsForDevice(context, profile.width, profile.height)
            if (hasWriteSecureSettingsPermission(context)) {
                if (!prefs.getBoolean(KEY_DISPLAY_MODIFIED, false)) {
                    val prevDensity = runCatching {
                        Settings.Secure.getString(context.contentResolver, "display_density_forced")
                    }.getOrNull().orEmpty()
                    val prevSize = runCatching {
                        Settings.Global.getString(context.contentResolver, "display_size_forced")
                    }.getOrNull().orEmpty()
                    prefs.edit()
                        .putString(KEY_ORIG_DENSITY_FORCED, prevDensity)
                        .putString(KEY_ORIG_SIZE_FORCED, prevSize)
                        .apply()
                }
                appliedResDpi = applyForcedDisplaySizeAndDensity(context, natW, natH, profile.dpi)
                if (appliedResDpi) {
                    prefs.edit()
                        .putBoolean(KEY_DISPLAY_MODIFIED, true)
                        .putBoolean(KEY_ROOT_MODIFIED, false)
                        .apply()
                }
            } else if (applyViaRootShell(natW, natH, profile.dpi)) {
                appliedResDpi = true
                prefs.edit()
                    .putBoolean(KEY_DISPLAY_MODIFIED, true)
                    .putBoolean(KEY_ROOT_MODIFIED, true)
                    .apply()
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
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_DISPLAY_MODIFIED, false)) {
            if (hasWriteSecureSettingsPermission(appCtx)) {
                val origDensity = prefs.getString(KEY_ORIG_DENSITY_FORCED, "").orEmpty().trim()
                val origSize = prefs.getString(KEY_ORIG_SIZE_FORCED, "").orEmpty().trim()
                restoreOrClearForcedDisplay(origSize, origDensity)
                prefs.edit()
                    .putBoolean(KEY_DISPLAY_MODIFIED, false)
                    .remove(KEY_ORIG_DENSITY_FORCED)
                    .remove(KEY_ORIG_SIZE_FORCED)
                    .apply()
            } else if (prefs.getBoolean(KEY_ROOT_MODIFIED, false)) {
                clearViaRootShell()
                prefs.edit()
                    .putBoolean(KEY_DISPLAY_MODIFIED, false)
                    .putBoolean(KEY_ROOT_MODIFIED, false)
                    .apply()
            }
        }

        if (prefs.getBoolean(KEY_ROTATION_MODIFIED, false) && hasWriteSettingsPermission(appCtx)) {
            val origAccel = prefs.getInt(KEY_ORIG_ACCEL_ROT, 1)
            val origUserRot = prefs.getInt(KEY_ORIG_USER_ROT, Surface.ROTATION_0)
            snapToPortraitAndRestoreAccel(appCtx, targetUserRot = origUserRot, targetAccel = origAccel)
            prefs.edit().putBoolean(KEY_ROTATION_MODIFIED, false).apply()
        }
    }

    /**
     * Unconditionally clears any forced Tablet DPI/size and snaps the phone back to Portrait + Auto-Rotate.
     */
    fun forceRestorePortraitAndAuto(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (hasWriteSecureSettingsPermission(appCtx)) {
            clearForcedDisplaySizeAndDensity()
            prefs.edit().putBoolean(KEY_DISPLAY_MODIFIED, false).apply()
        } else if (prefs.getBoolean(KEY_ROOT_MODIFIED, false) || hasSuBinary()) {
            clearViaRootShell()
            prefs.edit()
                .putBoolean(KEY_DISPLAY_MODIFIED, false)
                .putBoolean(KEY_ROOT_MODIFIED, false)
                .apply()
        }

        if (hasWriteSettingsPermission(appCtx)) {
            snapToPortraitAndRestoreAccel(appCtx, targetUserRot = Surface.ROTATION_0, targetAccel = 1)
            prefs.edit().putBoolean(KEY_ROTATION_MODIFIED, false).apply()
        }
    }

    private fun snapToPortraitAndRestoreAccel(
        appCtx: Context,
        targetUserRot: Int,
        targetAccel: Int
    ) {
        runCatching {
            val cr = appCtx.contentResolver
            // First lock rotation at 0 (Portrait) so Samsung OneUI snaps upright immediately,
            // instead of lingering in Landscape when ACCELEROMETER_ROTATION turns back on.
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0)
        }
        mainHandler.postDelayed({
            runCatching {
                val cr = appCtx.contentResolver
                if (targetAccel == 0) {
                    Settings.System.putInt(cr, Settings.System.USER_ROTATION, targetUserRot)
                }
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, targetAccel)
            }
        }, 200L)
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
    @Suppress("DEPRECATION")
    private fun applyForcedDisplaySizeAndDensity(
        context: Context,
        width: Int,
        height: Int,
        dpi: Int
    ): Boolean {
        val wms = runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            wmgClass.getMethod("getWindowManagerService").invoke(null)
        }.getOrNull() ?: return false

        val sizeApplied = runCatching {
            wms.javaClass.getMethod(
                "setForcedDisplaySize",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(wms, Display.DEFAULT_DISPLAY, width, height)
            true
        }.getOrDefault(false)

        // If size override was rejected, scale DPI dynamically relative to actual native short edge so smallestWidth ~ 700dp
        val targetDpi = if (sizeApplied) {
            dpi
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            val shortEdge = minOf(dm.widthPixels, dm.heightPixels)
            ((shortEdge * 160) / 700).coerceIn(160, 360)
        }

        val densityApplied = applyForcedDensityOnly(wms, targetDpi)
        return sizeApplied || densityApplied
    }

    @SuppressLint("PrivateApi")
    private fun applyForcedDensityOnly(wms: Any, dpi: Int): Boolean {
        val userId = Process.myUserHandle().hashCode()
        return runCatching {
            wms.javaClass.getMethod(
                "setForcedDisplayDensityForUser",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).invoke(wms, Display.DEFAULT_DISPLAY, dpi, userId)
            true
        }.getOrElse {
            runCatching {
                wms.javaClass.getMethod(
                    "setForcedDisplayDensity",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(wms, Display.DEFAULT_DISPLAY, dpi)
                true
            }.getOrDefault(false)
        }
    }

    @SuppressLint("PrivateApi")
    private fun restoreOrClearForcedDisplay(origSize: String, origDensity: String) {
        val wms = runCatching {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            wmgClass.getMethod("getWindowManagerService").invoke(null)
        }.getOrNull() ?: return

        val sizeParts = origSize.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (sizeParts.size == 2 && sizeParts[0] > 0 && sizeParts[1] > 0) {
            runCatching {
                wms.javaClass.getMethod(
                    "setForcedDisplaySize",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).invoke(wms, Display.DEFAULT_DISPLAY, sizeParts[0], sizeParts[1])
            }
        } else {
            runCatching {
                wms.javaClass.getMethod("clearForcedDisplaySize", Int::class.javaPrimitiveType)
                    .invoke(wms, Display.DEFAULT_DISPLAY)
            }
        }

        val prevDpi = origDensity.toIntOrNull()
        if (prevDpi != null && prevDpi > 0) {
            applyForcedDensityOnly(wms, prevDpi)
        } else {
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

    @SuppressLint("PrivateApi")
    private fun clearForcedDisplaySizeAndDensity() {
        restoreOrClearForcedDisplay(origSize = "", origDensity = "")
    }

    private fun hasSuBinary(): Boolean {
        val paths = arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        return paths.any { java.io.File(it).exists() }
    }

    private fun applyViaRootShell(width: Int, height: Int, dpi: Int): Boolean {
        if (!hasSuBinary()) return false
        return runSuCommand("wm size ${width}x${height} && wm density $dpi")
    }

    private fun clearViaRootShell(): Boolean {
        if (!hasSuBinary()) return false
        return runSuCommand("wm size reset && wm density reset")
    }

    private fun runSuCommand(cmd: String): Boolean {
        return runCatching {
            val proc = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                proc.waitFor() == 0
            }
            if (!finished) {
                proc.destroy()
                false
            } else {
                proc.exitValue() == 0
            }
        }.getOrDefault(false)
    }
}
