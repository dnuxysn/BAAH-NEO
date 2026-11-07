package com.baah.nativehost.legacy
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = 0.sp)
val BaahTypography = Typography(
    displayLarge = type(36,44), displayMedium = type(28,36), displaySmall = type(24,32),
    headlineLarge = type(28,36,FontWeight.SemiBold), headlineMedium = type(22,28,FontWeight.SemiBold), headlineSmall = type(20,28,FontWeight.SemiBold),
    titleLarge = type(22,28,FontWeight.Medium), titleMedium = type(16,24,FontWeight.Medium), titleSmall = type(14,20,FontWeight.Medium),
    bodyLarge = type(16,24), bodyMedium = type(14,20), bodySmall = type(12,16),
    labelLarge = type(14,20,FontWeight.Medium), labelMedium = type(12,16,FontWeight.Medium), labelSmall = type(12,16,FontWeight.Medium),
)
