package sh.filee.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import sh.filee.android.core.settings.ThemeMode
import sh.filee.android.core.settings.ThemeSettings
import sh.filee.android.core.settings.ThemeStyle

/**
 * Curated theme presets from Filee's ThemeService.cs.
 */
data class FileeThemePreset(
    val key: String,
    val label: String,
    val lightHex: String,
    val darkHex: String
) {
    val lightColor: Color get() = parseHexColor(lightHex)
    val darkColor: Color get() = parseHexColor(darkHex)
}

object SoftPalette {
    val presets = listOf(
        FileeThemePreset("lavender", "薰衣草紫", "#7F77DD", "#BCAEE5"),
        FileeThemePreset("mint", "薄荷绿", "#1D9E75", "#88D5B1"),
        FileeThemePreset("peach", "蜜桃珊瑚", "#D85A30", "#F29B91"),
        FileeThemePreset("sky", "晴空蓝", "#378ADD", "#98B8F6"),
        FileeThemePreset("rose", "玫瑰粉", "#D4537E", "#F79BB0"),
        FileeThemePreset("amber", "琥珀金", "#BA7517", "#F0BA6A"),
        FileeThemePreset("sage", "鼠尾草绿", "#5A8E51", "#8DC983"),
        FileeThemePreset("monochrome", "极简黑白", "#1F1E1D", "#F1EFE8")
    )
}

data class FileeColors(
    val page: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
    val cornerRadiusDp: Int
)

val LocalFileeColors = staticCompositionLocalOf {
    FileeColors(
        page = Color(0xFFF6F5F1),
        surface = Color(0xFFFFFFFF),
        surfaceAlt = Color(0xFFEFEDE6),
        border = Color(0x1F000000),
        textPrimary = Color(0xFF1F1E1D),
        textSecondary = Color(0xFF6B6A66),
        accent = Color(0xFF7F77DD),
        accentSoft = Color(0x2E7F77DD),
        onAccent = Color(0xFFFFFFFF),
        success = Color(0xFF0F7A5A),
        warning = Color(0xFF9A5B0C),
        danger = Color(0xFFC73A39),
        isDark = false,
        cornerRadiusDp = 16
    )
}

fun parseHexColor(hex: String, fallback: Color = Color(0xFF7F77DD)): Color {
    return try {
        val clean = hex.trim().removePrefix("#")
        when (clean.length) {
            6 -> Color(("FF$clean").toLong(16))
            8 -> Color(clean.toLong(16))
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}

@Composable
fun FileeTheme(
    themeSettings: ThemeSettings,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeSettings.mode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemDark
    }

    val preset = SoftPalette.presets.firstOrNull { it.key.equals(themeSettings.presetKey, ignoreCase = true) }
    val baseAccent = preset?.let { if (isDark) it.darkColor else it.lightColor }
        ?: parseHexColor(themeSettings.accent)

    val context = LocalContext.current
    val useMaterialYou = themeSettings.style == ThemeStyle.MaterialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val colorScheme: ColorScheme = if (useMaterialYou) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (isDark) {
        darkColorScheme(
            primary = baseAccent,
            onPrimary = if (preset?.key == "monochrome") Color(0xFF1B1B19) else Color.White,
            secondary = baseAccent,
            background = Color(0xFF1B1B19),
            surface = Color(0xFF262624),
            surfaceVariant = Color(0xFF31312E),
            onBackground = Color(0xFFF1EFE8),
            onSurface = Color(0xFFF1EFE8),
            onSurfaceVariant = Color(0xFFAEACA4),
            error = Color(0xFFF09595)
        )
    } else {
        lightColorScheme(
            primary = baseAccent,
            onPrimary = Color.White,
            secondary = baseAccent,
            background = Color(0xFFF6F5F1),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFEDE6),
            onBackground = Color(0xFF1F1E1D),
            onSurface = Color(0xFF1F1E1D),
            onSurfaceVariant = Color(0xFF6B6A66),
            error = Color(0xFFC73A39)
        )
    }

    val effectiveAccent = if (useMaterialYou) colorScheme.primary else baseAccent
    val fileeColors = if (isDark) {
        FileeColors(
            page = if (useMaterialYou) colorScheme.background else Color(0xFF1B1B19),
            surface = if (useMaterialYou) colorScheme.surface else Color(0xFF262624),
            surfaceAlt = if (useMaterialYou) colorScheme.surfaceVariant else Color(0xFF31312E),
            border = Color(0x24FFFFFF),
            textPrimary = Color(0xFFF1EFE8),
            textSecondary = Color(0xFFAEACA4),
            accent = effectiveAccent,
            accentSoft = effectiveAccent.copy(alpha = 0.22f),
            onAccent = if (preset?.key == "monochrome" && !useMaterialYou) Color(0xFF1B1B19) else Color.White,
            success = Color(0xFF5DCAA5),
            warning = Color(0xFFEF9F27),
            danger = Color(0xFFF09595),
            isDark = true,
            cornerRadiusDp = themeSettings.cornerRadius.coerceIn(6, 28)
        )
    } else {
        FileeColors(
            page = if (useMaterialYou) colorScheme.background else Color(0xFFF6F5F1),
            surface = if (useMaterialYou) colorScheme.surface else Color(0xFFFFFFFF),
            surfaceAlt = if (useMaterialYou) colorScheme.surfaceVariant else Color(0xFFEFEDE6),
            border = Color(0x18000000),
            textPrimary = Color(0xFF1F1E1D),
            textSecondary = Color(0xFF6B6A66),
            accent = effectiveAccent,
            accentSoft = effectiveAccent.copy(alpha = 0.18f),
            onAccent = Color.White,
            success = Color(0xFF0F7A5A),
            warning = Color(0xFF9A5B0C),
            danger = Color(0xFFC73A39),
            isDark = false,
            cornerRadiusDp = themeSettings.cornerRadius.coerceIn(6, 28)
        )
    }

    val r = fileeColors.cornerRadiusDp
    val shapes = Shapes(
        small = RoundedCornerShape((r * 0.6f).dp),
        medium = RoundedCornerShape(r.dp),
        large = RoundedCornerShape((r * 1.35f).dp)
    )

    CompositionLocalProvider(LocalFileeColors provides fileeColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = shapes,
            content = content
        )
    }
}
