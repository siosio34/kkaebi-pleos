package ai.kkaebi.app.ui

import ai.kkaebi.app.R
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** DESIGN.md: 계기판의 절제. 토큰은 Material 3 역할로만 쓴다 */
object Tone {
    val bg = Color(0xFF0C0E11); val surface = Color(0xFF13161B); val container = Color(0xFF191D23); val line = Color(0xFF2A3038)
    val fg = Color(0xFFE9ECF1); val fg2 = Color(0xFFA3ADBB)
    val primary = Color(0xFF7FBCFF); val primaryContainer = Color(0xFF1B3A5C)
    val ok = Color(0xFF5FC98B); val caution = Color(0xFFF1B64A); val danger = Color(0xFFF0624D)
}

val Pretendard = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal), Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold), Font(R.font.pretendard_bold, FontWeight.Bold),
)
private const val TNUM = "tnum"

private val scheme = darkColorScheme(
    primary = Tone.primary, onPrimary = Tone.bg, primaryContainer = Tone.primaryContainer, onPrimaryContainer = Tone.fg,
    secondary = Tone.fg2, onSecondary = Tone.bg, secondaryContainer = Tone.container, onSecondaryContainer = Tone.fg,
    background = Tone.bg, onBackground = Tone.fg, surface = Tone.surface, onSurface = Tone.fg,
    surfaceVariant = Tone.container, onSurfaceVariant = Tone.fg2, surfaceContainer = Tone.container, surfaceContainerHigh = Color(0xFF1F242B),
    outline = Tone.line, outlineVariant = Tone.line, error = Tone.danger, onError = Tone.bg,
)

private fun t(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f, tabular: Boolean = false) =
    TextStyle(fontFamily = Pretendard, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = tracking.sp, fontFeatureSettings = if (tabular) TNUM else null)

val CarType = Typography(
    displayLarge = t(56, FontWeight.SemiBold, 60, -0.5f, true), displayMedium = t(40, FontWeight.SemiBold, 46, -0.3f, true), displaySmall = t(32, FontWeight.SemiBold, 38, -0.2f, true),
    headlineLarge = t(30, FontWeight.SemiBold, 36, -0.2f), headlineMedium = t(26, FontWeight.SemiBold, 32), headlineSmall = t(22, FontWeight.SemiBold, 28),
    titleLarge = t(20, FontWeight.Medium, 26), titleMedium = t(18, FontWeight.Medium, 24), titleSmall = t(16, FontWeight.Medium, 22),
    bodyLarge = t(18, FontWeight.Normal, 26), bodyMedium = t(16, FontWeight.Normal, 22), bodySmall = t(14, FontWeight.Normal, 20),
    labelLarge = t(16, FontWeight.Medium, 20, tabular = true), labelMedium = t(14, FontWeight.Medium, 18, tabular = true), labelSmall = t(12, FontWeight.Medium, 16, tabular = true),
)

@Composable
fun KkaebiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = CarType, shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp)), content = content)
}
