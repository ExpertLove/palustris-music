package ru.palustris.music.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Подпись группы в настройках и профиле. */
@Composable
fun GroupHeader(text: String) {
  Text(
    text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 8.dp),
  )
}

/** Карточка группы: скруглённая плашка во всю ширину, по нажатию или без. */
@Composable
fun GroupCard(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = RoundedCornerShape(28.dp),
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 4.dp)
        .clip(RoundedCornerShape(28.dp))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier),
  ) {
    Column(Modifier.padding(20.dp)) { content() }
  }
}

/** Строка-действие внутри карточки: значок, заголовок, пояснение. */
@Composable
fun ActionRow(
  icon: ImageVector,
  title: String,
  subtitle: String? = null,
  tint: Color = MaterialTheme.colorScheme.onSurface,
  onClick: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick, role = Role.Button).padding(vertical = 12.dp, horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
    Spacer(Modifier.width(16.dp))
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
      if (subtitle != null) {
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
