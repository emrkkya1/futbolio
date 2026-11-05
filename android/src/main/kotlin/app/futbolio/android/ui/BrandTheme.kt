package app.futbolio.android.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.futbolio.android.R

internal val kodchasan = FontFamily(Font(R.font.kodchasan))
internal val manrope = FontFamily(
    Font(R.font.manrope_light, FontWeight.Light),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private fun body(size: Int, line: Int) = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Light,
    fontSize = size.sp, lineHeight = line.sp)
private fun title(size: Int, line: Int) = body(size, line).copy(fontWeight = FontWeight.Bold)

internal val futbolioTypography = Typography(
    displayLarge = title(48, 56), displayMedium = title(40, 48), displaySmall = title(34, 42),
    headlineLarge = title(30, 38), headlineMedium = title(26, 34), headlineSmall = title(24, 32),
    titleLarge = title(22, 30), titleMedium = title(18, 26), titleSmall = title(16, 24),
    bodyLarge = body(18, 26), bodyMedium = body(16, 24), bodySmall = body(14, 20),
    labelLarge = title(16, 24), labelMedium = title(14, 20), labelSmall = title(12, 18),
)
