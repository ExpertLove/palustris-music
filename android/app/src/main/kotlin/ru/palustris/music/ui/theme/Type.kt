package ru.palustris.music.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ru.palustris.music.R

/** Onest, как на сайте: вариативный шрифт с хорошей кириллицей (лицензия OFL, см. assets/licenses). */
private fun onest(weight: Int) =
  Font(
    R.font.onest,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
  )

val Onest = FontFamily(onest(400), onest(500), onest(600), onest(700), onest(800))

private fun TextStyle.onest(weight: FontWeight? = null, tracking: Float? = null) =
  copy(
    fontFamily = Onest,
    fontWeight = weight ?: fontWeight,
    letterSpacing = tracking?.sp ?: letterSpacing,
  )

val MusicTypography: Typography =
  Typography().run {
    copy(
      displayLarge = displayLarge.onest(FontWeight.Bold, -1.2f),
      displayMedium = displayMedium.onest(FontWeight.Bold, -0.8f),
      displaySmall = displaySmall.onest(FontWeight.Bold, -0.5f),
      headlineLarge = headlineLarge.onest(FontWeight.Bold, -0.5f),
      headlineMedium = headlineMedium.onest(FontWeight.Bold, -0.3f),
      headlineSmall = headlineSmall.onest(FontWeight.SemiBold, -0.2f),
      titleLarge = titleLarge.onest(FontWeight.SemiBold, -0.2f),
      titleMedium = titleMedium.onest(FontWeight.SemiBold, 0f),
      titleSmall = titleSmall.onest(FontWeight.SemiBold, 0f),
      bodyLarge = bodyLarge.onest(tracking = 0f),
      bodyMedium = bodyMedium.onest(tracking = 0f),
      bodySmall = bodySmall.onest(tracking = 0.1f),
      labelLarge = labelLarge.onest(FontWeight.SemiBold, 0f),
      labelMedium = labelMedium.onest(FontWeight.Medium, 0.1f),
      labelSmall = labelSmall.onest(FontWeight.Medium, 0.2f),
    )
  }
