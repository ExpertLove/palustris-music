package ru.palustris.music.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/** Фирменный цвет сайта: им окрашено всё, пока ничего не играет. */
val BrandAccent = Color(0xFFFF5470)

private fun hsl(h: Float, s: Float, l: Float): Color =
  Color(ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))))

/**
 * Тёмная схема из одного цвета — цвета обложки. Оттенок берём у обложки, а насыщенность
 * и яркость задаём сами: так текст остаётся читаемым на любой обложке, даже на почти
 * чёрной или кислотно-зелёной.
 */
fun schemeFromSeed(seed: Color): ColorScheme {
  val hslSeed = FloatArray(3)
  ColorUtils.colorToHSL(seed.toArgb(), hslSeed)
  val h = hslSeed[0]
  // У серых обложек оттенок случайный, поэтому почти не красим фон.
  val chroma = hslSeed[1].coerceIn(0f, 1f)
  val s = (0.35f + chroma * 0.55f).coerceAtMost(0.9f)
  val tint = 0.08f + chroma * 0.16f

  return darkColorScheme(
    primary = hsl(h, s, 0.72f),
    onPrimary = hsl(h, s, 0.14f),
    primaryContainer = hsl(h, s * 0.75f, 0.30f),
    onPrimaryContainer = hsl(h, s, 0.90f),
    inversePrimary = hsl(h, s, 0.40f),
    secondary = hsl(h, s * 0.45f, 0.76f),
    onSecondary = hsl(h, s * 0.5f, 0.16f),
    secondaryContainer = hsl(h, s * 0.35f, 0.24f),
    onSecondaryContainer = hsl(h, s * 0.4f, 0.90f),
    tertiary = hsl(h + 40f, s * 0.6f, 0.76f),
    onTertiary = hsl(h + 40f, s * 0.6f, 0.15f),
    tertiaryContainer = hsl(h + 40f, s * 0.4f, 0.26f),
    onTertiaryContainer = hsl(h + 40f, s * 0.5f, 0.90f),
    background = hsl(h, tint, 0.065f),
    onBackground = hsl(h, 0.12f, 0.93f),
    surface = hsl(h, tint, 0.065f),
    onSurface = hsl(h, 0.12f, 0.93f),
    surfaceVariant = hsl(h, tint, 0.20f),
    onSurfaceVariant = hsl(h, 0.10f, 0.72f),
    surfaceTint = hsl(h, s, 0.72f),
    inverseSurface = hsl(h, 0.12f, 0.92f),
    inverseOnSurface = hsl(h, tint, 0.12f),
    outline = hsl(h, 0.08f, 0.46f),
    outlineVariant = hsl(h, tint, 0.24f),
    surfaceBright = hsl(h, tint, 0.20f),
    surfaceDim = hsl(h, tint, 0.065f),
    surfaceContainerLowest = hsl(h, tint, 0.045f),
    surfaceContainerLow = hsl(h, tint, 0.09f),
    surfaceContainer = hsl(h, tint, 0.11f),
    surfaceContainerHigh = hsl(h, tint, 0.145f),
    surfaceContainerHighest = hsl(h, tint, 0.18f),
    scrim = Color.Black,
  )
}
