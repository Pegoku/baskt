package nl.baskt.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// The complete fallback schemes for Android 8 to 11, which have no dynamic colour: every role is derived from
// the brand green (#1B5E3F), so no surface or container falls back to the Material baseline purple.
private val LightColors = lightColorScheme(
    primary = Color(0xFF246A4B), onPrimary = Color(0xFFFFFFFF), primaryContainer = Color(0xFFAAF2CA), onPrimaryContainer = Color(0xFF005235),
    secondary = Color(0xFF4D6356), onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFD0E8D8), onSecondaryContainer = Color(0xFF364B3F),
    tertiary = Color(0xFF3C6471), onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFC0E9F9), onTertiaryContainer = Color(0xFF234C58),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF5FBF4), onBackground = Color(0xFF171D19), surface = Color(0xFFF5FBF4), onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DC), onSurfaceVariant = Color(0xFF404943), outline = Color(0xFF707972), outlineVariant = Color(0xFFC0C9C0),
    scrim = Color(0xFF000000), inverseSurface = Color(0xFF2B322D), inverseOnSurface = Color(0xFFECF2EB), inversePrimary = Color(0xFF8ED5AF),
    surfaceDim = Color(0xFFD6DCD5), surfaceBright = Color(0xFFF5FBF4), surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFEFF5EE),
    surfaceContainer = Color(0xFFEAEFE8), surfaceContainerHigh = Color(0xFFE4EAE3), surfaceContainerHighest = Color(0xFFDEE4DD),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8ED5AF), onPrimary = Color(0xFF003823), primaryContainer = Color(0xFF005235), onPrimaryContainer = Color(0xFFAAF2CA),
    secondary = Color(0xFFB4CCBC), onSecondary = Color(0xFF20352A), secondaryContainer = Color(0xFF364B3F), onSecondaryContainer = Color(0xFFD0E8D8),
    tertiary = Color(0xFFA4CDDD), onTertiary = Color(0xFF053541), tertiaryContainer = Color(0xFF234C58), onTertiaryContainer = Color(0xFFC0E9F9),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1511), onBackground = Color(0xFFDEE4DD), surface = Color(0xFF0F1511), onSurface = Color(0xFFDEE4DD),
    surfaceVariant = Color(0xFF404943), onSurfaceVariant = Color(0xFFC0C9C0), outline = Color(0xFF8A938B), outlineVariant = Color(0xFF404943),
    scrim = Color(0xFF000000), inverseSurface = Color(0xFFDEE4DD), inverseOnSurface = Color(0xFF2B322D), inversePrimary = Color(0xFF246A4B),
    surfaceDim = Color(0xFF0F1511), surfaceBright = Color(0xFF353B36), surfaceContainerLowest = Color(0xFF0A0F0C), surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D), surfaceContainerHigh = Color(0xFF252B27), surfaceContainerHighest = Color(0xFF303632),
)

@Composable
fun BasktTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
