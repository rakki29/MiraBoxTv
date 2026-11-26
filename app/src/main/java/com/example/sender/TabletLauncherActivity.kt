package com.example.sender

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.R
import com.example.data.AppLanguage
import com.example.data.SecondScreenProfile
import com.example.miracast.SenderState
import com.example.ui.components.LocalizedEnvironment
import com.example.ui.components.appString
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.MiraBoxTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class InstalledAppEntry(
    val label: String,
    val packageName: String,
    val iconBitmap: ImageBitmap
)

/**
 * Landscape-locked 16:9 Tablet / Android TV App Launcher opened on the Phone Sender
 * during SecondScreen / DeX casting.
 * - Uses compact Tablet visual DPI scaling automatically (even without ADB)
 * - Routes UIBC D-Pad, OK, P+/P-, and Back directly across Top Bar <-> Apps Grid
 * - Auto-closes and restores Portrait + Auto-rotate cleanly when casting ends
 */
class TabletLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val language = runCatching {
            AppLanguage.valueOf(intent?.getStringExtra(ScreenCastSenderService.EXTRA_LANGUAGE) ?: "")
        }.getOrDefault(AppLanguage.ARABIC)

        val profile = runCatching {
            SecondScreenProfile.valueOf(
                intent?.getStringExtra(ScreenCastSenderService.EXTRA_SECOND_SCREEN_PROFILE) ?: ""
            )
        }.getOrDefault(SecondScreenProfile.TABLET_720P)

        setContent {
            val status by SenderRuntimeBus.status.collectAsStateWithLifecycle()
            var sawActiveCast by remember { mutableStateOf(false) }

            LaunchedEffect(status.state) {
                if (status.state == SenderState.STREAMING || status.state == SenderState.AWAITING_TV_OK) {
                    sawActiveCast = true
                } else if (sawActiveCast && (status.state == SenderState.IDLE || status.state == SenderState.ERROR)) {
                    stopRestoreAndFinish(sendStopService = false)
                }
            }

            MiraBoxTheme {
                LocalizedEnvironment(language = language) {
                    TabletDesktopLauncherScreen(
                        targetName = status.targetName.ifBlank { "MiraBox TV" },
                        secondScreenActive = status.secondScreenNote.isNotEmpty(),
                        profile = profile,
                        onLaunchApp = { pkg ->
                            packageManager.getLaunchIntentForPackage(pkg)?.let { launchIntent ->
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { startActivity(launchIntent) }
                            }
                        },
                        onOpenMiraBox = {
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            )
                        },
                        onStopAndRestore = {
                            stopRestoreAndFinish(sendStopService = true)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        SenderRuntimeBus.isTabletLauncherActive = true
    }

    override fun onPause() {
        SenderRuntimeBus.isTabletLauncherActive = false
        super.onPause()
    }

    override fun onDestroy() {
        SenderRuntimeBus.isTabletLauncherActive = false
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val hid = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> 0x0052
                KeyEvent.KEYCODE_DPAD_DOWN -> 0x0051
                KeyEvent.KEYCODE_DPAD_LEFT -> 0x0050
                KeyEvent.KEYCODE_DPAD_RIGHT -> 0x004F
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x0028
                KeyEvent.KEYCODE_BACK -> 0x0029
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_CHANNEL_UP -> 0x004B
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> 0x004E
                else -> null
            }
            if (hid != null) {
                SenderRuntimeBus.emitTabletKey(hid)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun stopRestoreAndFinish(sendStopService: Boolean) {
        SenderRuntimeBus.isTabletLauncherActive = false
        if (sendStopService) {
            val stopIntent = Intent(this, ScreenCastSenderService::class.java).apply {
                action = ScreenCastSenderService.ACTION_STOP
            }
            runCatching { startService(stopIntent) }
        }
        SecondScreenController.forceRestorePortraitAndAuto(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        finish()
    }
}

@Composable
private fun TabletDesktopLauncherScreen(
    targetName: String,
    secondScreenActive: Boolean,
    profile: SecondScreenProfile,
    onLaunchApp: (String) -> Unit,
    onOpenMiraBox: () -> Unit,
    onStopAndRestore: () -> Unit
) {
    val context = LocalContext.current
    val baseDensity = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl

    // Apply compact Tablet visual DPI inside Tablet Launcher whenever hardware DPI wasn't lowered via ADB
    val currentDpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
    val tabletDensity = remember(baseDensity, currentDpi, profile) {
        if (profile != SecondScreenProfile.OFF && currentDpi > 260) {
            val scale = (profile.dpi.toFloat() / currentDpi.toFloat()).coerceIn(0.56f, 0.80f)
            Density(
                density = (baseDensity.density * scale).coerceAtLeast(1.1f),
                fontScale = baseDensity.fontScale.coerceAtMost(1.0f)
            )
        } else {
            baseDensity
        }
    }

    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var inTopBar by remember { mutableStateOf(false) }
    var topBarFocusIndex by remember { mutableIntStateOf(1) }
    var selectedAppIndex by remember { mutableIntStateOf(0) }
    var columnCount by remember { mutableIntStateOf(6) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            loadLaunchableApps(context.packageManager, context.packageName)
        }
        selectedAppIndex = 0
        inTopBar = apps.isEmpty()
    }

    LaunchedEffect(selectedAppIndex, inTopBar, apps.size) {
        if (!inTopBar && apps.isNotEmpty()) {
            val safeIdx = selectedAppIndex.coerceIn(0, apps.lastIndex)
            runCatching { gridState.animateScrollToItem(safeIdx) }
        }
    }

    LaunchedEffect(isRtl, apps.size, columnCount) {
        SenderRuntimeBus.tabletRemoteKeys.collect { hid ->
            val cols = columnCount.coerceAtLeast(1)
            val totalApps = apps.size
            if (totalApps == 0) {
                when (hid) {
                    0x004F, 0x0050 -> topBarFocusIndex = (topBarFocusIndex + 1) % 2
                    0x0028 -> if (topBarFocusIndex == 0) onOpenMiraBox() else onStopAndRestore()
                    0x0029 -> onOpenMiraBox()
                }
                return@collect
            }

            if (inTopBar) {
                when (hid) {
                    0x0051, 0x004B -> { // DOWN or P+ -> enter Apps Grid!
                        inTopBar = false
                        selectedAppIndex = selectedAppIndex.coerceIn(0, totalApps - 1)
                    }
                    0x004F, 0x0050 -> { // RIGHT / LEFT toggle between top buttons
                        topBarFocusIndex = (topBarFocusIndex + 1) % 2
                    }
                    0x0028 -> { // OK
                        if (topBarFocusIndex == 0) onOpenMiraBox() else onStopAndRestore()
                    }
                    0x0029 -> onOpenMiraBox()
                }
            } else {
                val visualRightDelta = if (isRtl) -1 else 1
                val visualLeftDelta = if (isRtl) 1 else -1
                when (hid) {
                    0x004F -> { // RIGHT
                        selectedAppIndex = (selectedAppIndex + visualRightDelta).coerceIn(0, totalApps - 1)
                    }
                    0x0050 -> { // LEFT
                        selectedAppIndex = (selectedAppIndex + visualLeftDelta).coerceIn(0, totalApps - 1)
                    }
                    0x0051 -> { // DOWN
                        selectedAppIndex = (selectedAppIndex + cols).coerceAtMost(totalApps - 1)
                    }
                    0x0052 -> { // UP
                        if (selectedAppIndex < cols) {
                            inTopBar = true
                        } else {
                            selectedAppIndex = (selectedAppIndex - cols).coerceAtLeast(0)
                        }
                    }
                    0x004B -> { // P+
                        selectedAppIndex = (selectedAppIndex + cols * 2).coerceAtMost(totalApps - 1)
                    }
                    0x004E -> { // P-
                        selectedAppIndex = (selectedAppIndex - cols * 2).coerceAtLeast(0)
                    }
                    0x0028 -> { // OK -> Launch Highlighted App!
                        val safe = selectedAppIndex.coerceIn(0, totalApps - 1)
                        onLaunchApp(apps[safe].packageName)
                    }
                    0x0029 -> { // BACK -> Jump to Top Bar (easy stop/exit)
                        inTopBar = true
                        topBarFocusIndex = 1
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalDensity provides tabletDensity) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF060B15), Color(0xFF0C1A2E), ObsidianBg)
                    )
                )
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Compact Tablet / DeX Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianCard.copy(alpha = 0.92f))
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DesktopWindows,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = appString(R.string.tablet_launcher_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (secondScreenActive) {
                                appString(R.string.tablet_launcher_subtitle_hd, targetName)
                            } else {
                                appString(R.string.tablet_launcher_subtitle, targetName)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val miraboxFocused = inTopBar && topBarFocusIndex == 0
                    val stopFocused = inTopBar && topBarFocusIndex == 1
                    TopActionChip(
                        label = appString(R.string.tablet_btn_mirabox),
                        icon = Icons.Rounded.Home,
                        focused = miraboxFocused,
                        containerColor = if (miraboxFocused) ElectricCyanContainer else ObsidianCardElevated,
                        contentColor = if (miraboxFocused) ElectricCyan else TextPrimary,
                        onClick = onOpenMiraBox,
                        modifier = Modifier.testTag("tablet_btn_mirabox")
                    )
                    TopActionChip(
                        label = appString(R.string.tablet_btn_restore_exit),
                        icon = Icons.Rounded.ScreenRotation,
                        focused = stopFocused,
                        containerColor = CoralError,
                        contentColor = Color.White,
                        onClick = onStopAndRestore,
                        modifier = Modifier.testTag("tablet_btn_restore_exit")
                    )
                }
            }

            // Compact High-Density Landscape Tablet App Grid with TV Cyan Focus Ring
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val computedCols = (maxWidth / 96.dp).toInt().coerceIn(5, 8)
                LaunchedEffect(computedCols) {
                    columnCount = computedCols
                }

                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(computedCols),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                        val isFocused = !inTopBar && selectedAppIndex == index
                        Column(
                            modifier = Modifier
                                .scale(if (isFocused) 1.05f else 1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isFocused) ElectricCyanContainer else ObsidianCardElevated.copy(alpha = 0.88f)
                                )
                                .border(
                                    width = if (isFocused) 2.5.dp else 1.dp,
                                    color = if (isFocused) ElectricCyan else ObsidianBorder,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    inTopBar = false
                                    selectedAppIndex = index
                                    onLaunchApp(app.packageName)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Image(
                                bitmap = app.iconBitmap,
                                contentDescription = app.label,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                color = if (isFocused) ElectricCyan else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    focused: Boolean,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .scale(if (focused) 1.05f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Color.White else ObsidianBorder,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor,
            maxLines = 1
        )
    }
}

private fun loadLaunchableApps(pm: PackageManager, selfPackage: String): List<InstalledAppEntry> {
    val intent = Intent(Intent.ACTION_MAIN, null).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    @Suppress("DEPRECATION")
    val resolved = pm.queryIntentActivities(intent, 0)
    return resolved
        .asSequence()
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == selfPackage) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
            val bmp = runCatching {
                info.loadIcon(pm).toBitmap(width = 84, height = 84).asImageBitmap()
            }.getOrNull() ?: return@mapNotNull null
            InstalledAppEntry(label = label, packageName = pkg, iconBitmap = bmp)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}
