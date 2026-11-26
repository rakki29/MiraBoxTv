package com.example.ui.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.AppLanguage
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

val LocalAppResources = compositionLocalOf<Resources?> { null }

@Composable
fun LocalizedEnvironment(
    language: AppLanguage,
    content: @Composable () -> Unit
) {
    val baseContext = LocalContext.current
    val localizedResources = remember(language, baseContext) {
        createLocalizedResources(baseContext, language)
    }
    val layoutDirection = if (language == AppLanguage.ARABIC) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    CompositionLocalProvider(
        LocalAppResources provides localizedResources,
        LocalLayoutDirection provides layoutDirection
    ) {
        content()
    }
}

private fun createLocalizedResources(context: Context, language: AppLanguage): Resources {
    val locale = Locale(language.tag)
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    config.setLayoutDirection(locale)
    return context.createConfigurationContext(config).resources
}

@Composable
fun appString(@StringRes id: Int): String {
    val res = LocalAppResources.current ?: LocalContext.current.resources
    return res.getString(id)
}

@Composable
fun appString(@StringRes id: Int, vararg formatArgs: Any): String {
    val res = LocalAppResources.current ?: LocalContext.current.resources
    return res.getString(id, *formatArgs)
}

@Composable
fun TvGlassCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused && onClick != null) 1.02f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "tvCardScale"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isFocused -> ElectricCyan
            selected -> ElectricCyan.copy(alpha = 0.65f)
            else -> ObsidianBorder
        },
        label = "tvCardBorder"
    )
    val bgColor by animateColorAsState(
        targetValue = when {
            selected -> ElectricCyanContainer.copy(alpha = 0.55f)
            isFocused -> ObsidianCardElevated
            else -> ObsidianCard.copy(alpha = 0.92f)
        },
        label = "tvCardBg"
    )

    val shape = RoundedCornerShape(18.dp)
    val interactionModifier = if (onClick != null) {
        Modifier
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = ElectricCyan),
                onClick = onClick
            )
    } else {
        Modifier
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .border(
                width = if (isFocused) 2.5.dp else 1.2.dp,
                color = borderColor,
                shape = shape
            )
            .then(interactionModifier),
        color = bgColor,
        shape = shape,
        tonalElevation = if (isFocused) 6.dp else 2.dp
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun TvPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = ElectricCyan,
    contentColor: Color = ObsidianBg,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "tvBtnScale"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (isFocused) {
                    Modifier.border(2.5.dp, Color.White, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun TvSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = ObsidianBorder,
    contentColor: Color = TextPrimary,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "tvSecBtnScale"
    )

    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .onFocusChanged { isFocused = it.isFocused },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            width = if (isFocused) 2.5.dp else 1.5.dp,
            color = if (isFocused) ElectricCyan else borderColor
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isFocused) ElectricCyanContainer else ObsidianCardElevated,
            contentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
fun TvSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        selected -> ElectricCyan
        isFocused -> ElectricCyanContainer
        else -> ObsidianCardElevated
    }
    val fg = when {
        selected -> ObsidianBg
        isFocused -> ElectricCyan
        else -> TextSecondary
    }
    val border = when {
        isFocused -> Color.White
        selected -> ElectricCyan
        else -> ObsidianBorder
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(shape)
            .background(bg)
            .border(if (isFocused) 2.5.dp else 1.2.dp, border, shape)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = ElectricCyan),
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Renders a deterministic high-contrast geometric pairing matrix representing the Miracast LAN URI.
 */
@Composable
fun PairingMatrixGraphic(
    payload: String,
    modifier: Modifier = Modifier
) {
    val gridSize = 15
    val matrix = remember(payload) {
        val hashSeed = payload.hashCode().toLong()
        Array(gridSize) { r ->
            BooleanArray(gridSize) { c ->
                val inTopLeft = r < 4 && c < 4
                val inTopRight = r < 4 && c >= gridSize - 4
                val inBottomLeft = r >= gridSize - 4 && c < 4
                when {
                    inTopLeft || inTopRight || inBottomLeft -> {
                        val lr = if (r >= gridSize - 4) r - (gridSize - 4) else r
                        val lc = if (c >= gridSize - 4) c - (gridSize - 4) else c
                        lr == 0 || lr == 3 || lc == 0 || lc == 3 || (lr == 1 && lc == 1) || (lr == 2 && lc == 2)
                    }
                    else -> {
                        val bit = ((hashSeed xor (r * 31L + c * 17L) xor (payload.getOrElse((r + c) % payload.length.coerceAtLeast(1)) { 'M' }.code.toLong())) and 1L) == 0L
                        bit
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(96.dp)) {
            val cellW = size.width / gridSize
            val cellH = size.height / gridSize
            for (r in 0 until gridSize) {
                for (c in 0 until gridSize) {
                    if (matrix[r][c]) {
                        drawRect(
                            color = ObsidianBg,
                            topLeft = Offset(c * cellW, r * cellH),
                            size = Size(cellW * 0.92f, cellH * 0.92f)
                        )
                    }
                }
            }
        }
    }
}
