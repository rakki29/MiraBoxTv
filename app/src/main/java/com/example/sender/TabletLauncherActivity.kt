package com.example.sender

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.MainActivity
import com.example.R
import com.example.data.AppLanguage
import com.example.ui.components.LocalizedEnvironment
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
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
 * during SecondScreen / DeX casting. Provides clean horizontal browsing of all real
 * installed phone apps with native D-Pad + OK remote selection.
 */
class TabletLauncherActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val language = runCatching {
            AppLanguage.valueOf(intent?.getStringExtra(ScreenCastSenderService.EXTRA_LANGUAGE) ?: "")
        }.getOrDefault(AppLanguage.ARABIC)

        setContent {
            val status by SenderRuntimeBus.status.collectAsStateWithLifecycle()
            MiraBoxTheme {
                LocalizedEnvironment(language = language) {
                    TabletDesktopLauncherScreen(
                        targetName = status.targetName.ifBlank { "MiraBox TV" },
                        secondScreenActive = status.secondScreenNote.isNotEmpty(),
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
                        onStopCast = {
                            val stopIntent = Intent(this, ScreenCastSenderService::class.java).apply {
                                action = ScreenCastSenderService.ACTION_STOP
                            }
                            startService(stopIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TabletDesktopLauncherScreen(
    targetName: String,
    secondScreenActive: Boolean,
    onLaunchApp: (String) -> Unit,
    onOpenMiraBox: () -> Unit,
    onStopCast: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            loadLaunchableApps(context.packageManager, context.packageName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF060B15), Color(0xFF0C1A2E), ObsidianBg)
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Top Tablet/DeX Launcher Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ObsidianCard.copy(alpha = 0.92f))
                .border(1.dp, ObsidianBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = appString(R.string.tablet_launcher_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = if (secondScreenActive) {
                            appString(R.string.tablet_launcher_subtitle_hd, targetName)
                        } else {
                            appString(R.string.tablet_launcher_subtitle, targetName)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldMint
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvSecondaryButton(
                    text = appString(R.string.tablet_btn_mirabox),
                    onClick = onOpenMiraBox,
                    leadingIcon = { Icon(Icons.Rounded.Home, contentDescription = null) }
                )
                TvPrimaryButton(
                    text = appString(R.string.sender_btn_stop_cast),
                    onClick = onStopCast,
                    containerColor = CoralError,
                    contentColor = Color.White,
                    leadingIcon = { Icon(Icons.Rounded.Stop, contentDescription = null) }
                )
            }
        }

        // Responsive Landscape App Grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 132.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(apps, key = { it.packageName }) { app ->
                var isFocused by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (isFocused) ElectricCyanContainer else ObsidianCardElevated.copy(alpha = 0.9f)
                        )
                        .border(
                            width = if (isFocused) 2.5.dp else 1.dp,
                            color = if (isFocused) ElectricCyan else ObsidianBorder,
                            shape = RoundedCornerShape(18.dp)
                        )
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .clickable { onLaunchApp(app.packageName) }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
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
                info.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
            }.getOrNull() ?: return@mapNotNull null
            InstalledAppEntry(label = label, packageName = pkg, iconBitmap = bmp)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
        .toList()
}
