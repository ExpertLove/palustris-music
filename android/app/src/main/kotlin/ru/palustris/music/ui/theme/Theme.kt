package ru.palustris.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

/**
 * Тема приложения: Material 3 Expressive, тёмная, цвета берутся из обложки играющего трека
 * и плавно перетекают при смене трека — как на сайте («обложка задаёт цвет»).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicTheme(seed: Color = BrandAccent, content: @Composable () -> Unit) {
  val animated by animateColorAsState(seed, animationSpec = tween(900), label = "seed")
  val scheme = remember(animated) { schemeFromSeed(animated) }
  MaterialExpressiveTheme(
    colorScheme = scheme,
    motionScheme = MotionScheme.expressive(),
    typography = MusicTypography,
    content = content,
  )
}
