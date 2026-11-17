package ru.palustris.music.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.platform.LocalContext
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import ru.palustris.music.widget.MusicWidgetReceiver
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.palustris.music.BuildConfig
import ru.palustris.music.core.prefs.DownloadQuality
import ru.palustris.music.core.prefs.Settings
import ru.palustris.music.media.StorageUsage
import ru.palustris.music.ui.account.Avatar
import ru.palustris.music.ui.player.SpeedChips
import ru.palustris.music.ui.common.GroupCard
import ru.palustris.music.ui.common.GroupHeader
import ru.palustris.music.ui.common.formatBytes
import ru.palustris.music.ui.common.formatMb
import ru.palustris.music.ui.common.pluralTracks

@Composable
fun SettingsScreen(
  contentPadding: PaddingValues,
  onBack: () -> Unit,
  onOpenAccount: () -> Unit,
  onOpenEqualizer: () -> Unit,
  vm: SettingsViewModel = hiltViewModel(),
) {
  val settings by vm.settings.collectAsStateWithLifecycle()
  val usage by vm.usage.collectAsStateWithLifecycle()
  val email by vm.email.collectAsStateWithLifecycle()
  val me by vm.me.collectAsStateWithLifecycle()
  val pending by vm.pending.collectAsStateWithLifecycle()
  var confirmRemove by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val canPinWidget = remember { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }

  LazyColumn(contentPadding = contentPadding) {
    item {
      Row(Modifier.statusBarsPadding().padding(start = 4.dp, top = 8.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Text("Настройки", style = MaterialTheme.typography.headlineMedium)
      }
    }

    item { GroupHeader("Звук") }
    item {
      GroupCard(onClick = onOpenEqualizer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Эквалайзер", style = MaterialTheme.typography.titleMedium)
            Text("10 полос и готовые настройки", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          Icon(Icons.Rounded.GraphicEq, null)
        }
      }
    }
    item {
      GroupCard(onClick = { vm.setNormalize(!settings.normalize) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Выравнивать громкость", style = MaterialTheme.typography.titleMedium)
            Text(
              "Тихие и громкие треки звучат одинаково, не нужно крутить громкость",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = settings.normalize, onCheckedChange = vm::setNormalize)
        }
      }
    }
    item {
      GroupCard {
        Text("Плавный переход", style = MaterialTheme.typography.titleMedium)
        Text(
          "Конец трека плавно перетекает в начало следующего",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Settings.CROSSFADE_OPTIONS_SEC.forEach { sec ->
            FilterChip(
              selected = settings.crossfadeSec == sec,
              onClick = { vm.setCrossfade(sec) },
              label = { Text(if (sec == 0) "Выключен" else "$sec с") },
            )
          }
        }
      }
    }
    item {
      GroupCard {
        Text("Скорость", style = MaterialTheme.typography.titleMedium)
        Text(
          "Быстрее или медленнее. Тон голоса не меняется",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        SpeedChips(settings.speedPercent, vm::setSpeed)
      }
    }

    if (canPinWidget) {
      item {
        GroupCard(onClick = { pinWidget(context) }) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Виджет на главный экран", style = MaterialTheme.typography.titleMedium)
              Text(
                "Обложка и кнопки: играть, назад, вперёд. Не нужно открывать приложение",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Icon(Icons.Rounded.Widgets, null)
          }
        }
      }
    }

    item { GroupHeader("Память телефона") }
    item { GroupCard { StorageCard(usage, settings) } }
    item {
      GroupCard {
        Text("Кэш прослушанного", style = MaterialTheme.typography.titleMedium)
        Text(
          "Треки, которые вы слушали, хранятся на телефоне и повторно играют без интернета. " +
            "Когда кэш заполнится, первыми удалятся самые давние. Скачанные сюда не входят.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Settings.CACHE_OPTIONS_MB.forEach { mb ->
            FilterChip(
              selected = settings.cacheLimitMb == mb,
              onClick = { vm.setCacheLimit(mb) },
              label = { Text(formatMb(mb)) },
            )
          }
        }
        TextButton(onClick = vm::clearCache, modifier = Modifier.padding(top = 4.dp)) { Text("Очистить кэш сейчас") }
      }
    }

    item { GroupHeader("Скачивание") }
    item {
      GroupCard {
        Text("Качество скачанных треков", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        DownloadQuality.entries.forEach { q ->
          Row(
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(16.dp))
              .selectable(selected = settings.quality == q, onClick = { vm.setQuality(q) }, role = Role.RadioButton)
              .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(selected = settings.quality == q, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) {
              Text(q.title, style = MaterialTheme.typography.bodyLarge)
              Text(q.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
              "≈${(q.mbPerMinute * 3.5f).let { "%.1f".format(it).replace('.', ',') }} МБ\nза трек",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(start = 8.dp),
            )
          }
        }
        Text(
          "Новое качество касается следующих загрузок. Уже скачанные треки остаются как есть.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 8.dp),
        )
      }
    }
    item {
      GroupCard(onClick = { vm.setWifiOnly(!settings.wifiOnly) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text("Скачивать только по Wi‑Fi", style = MaterialTheme.typography.titleMedium)
            Text(
              "Мобильный трафик не тратится, загрузки ждут Wi‑Fi",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(checked = settings.wifiOnly, onCheckedChange = vm::setWifiOnly)
        }
      }
    }
    if ((usage?.downloadsCount ?: 0) > 0) {
      item {
        GroupCard {
          OutlinedButton(
            onClick = { confirmRemove = true },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
          ) {
            Text("Удалить все скачанные треки")
          }
        }
      }
    }

    item { GroupHeader("Аккаунт") }
    item {
      GroupCard(onClick = onOpenAccount) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Avatar(me?.name?.ifBlank { null } ?: email, Modifier.size(44.dp))
          Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(me?.name?.ifBlank { null } ?: email, style = MaterialTheme.typography.titleMedium)
            Text(
              if (pending > 0) "Ждут отправки на сервер: ${pending}. Уйдут, когда появится связь"
              else "Профиль, пароль, устройства",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
        }
      }
    }
    item {
      Text(
        "Версия ${BuildConfig.VERSION_NAME} · шрифт Onest (SIL OFL)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
      )
    }
  }

  if (confirmRemove) {
    AlertDialog(
      onDismissRequest = { confirmRemove = false },
      title = { Text("Удалить все скачанные?") },
      text = { Text("Они пропадут с телефона, без интернета их будет не послушать. Лайки и плейлисты останутся.") },
      confirmButton = {
        TextButton(onClick = {
          vm.removeAllDownloads()
          confirmRemove = false
        }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
      },
      dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Отмена") } },
    )
  }
}

/** Полоса: скачанное, кэш прослушанного, свободно на телефоне. */
@Composable
private fun StorageCard(usage: StorageUsage?, settings: Settings) {
  val scheme = MaterialTheme.colorScheme
  if (usage == null) {
    Text("Считаем…", style = MaterialTheme.typography.bodyMedium)
    return
  }
  val total = (usage.downloadsBytes + usage.cacheBytes + usage.freeBytes).coerceAtLeast(1)
  fun share(b: Long) = (b.toFloat() / total).coerceIn(0f, 1f)
  Text(
    formatBytes(usage.downloadsBytes + usage.cacheBytes),
    style = MaterialTheme.typography.headlineMedium,
  )
  Text("занимает музыка на телефоне", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
  Spacer(Modifier.height(14.dp))
  Row(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(scheme.surfaceContainerHighest)) {
    // Минимальная ширина, чтобы даже маленький кусок было видно.
    val d = share(usage.downloadsBytes)
    val c = share(usage.cacheBytes)
    if (d > 0) Box(Modifier.fillMaxHeight().weight(d.coerceAtLeast(0.012f)).background(scheme.primary))
    if (c > 0) Box(Modifier.fillMaxHeight().weight(c.coerceAtLeast(0.012f)).background(scheme.tertiary))
    Box(Modifier.fillMaxHeight().weight((1f - d - c).coerceAtLeast(0.01f)))
  }
  Spacer(Modifier.height(14.dp))
  Legend(scheme.primary, "Скачанные", "${pluralTracks(usage.downloadsCount)} · ${formatBytes(usage.downloadsBytes)}")
  Legend(scheme.tertiary, "Кэш прослушанного", "${formatBytes(usage.cacheBytes)} из ${formatMb(settings.cacheLimitMb)}")
  Legend(scheme.surfaceContainerHighest, "Свободно на телефоне", formatBytes(usage.freeBytes))
}

@Composable
private fun Legend(color: Color, title: String, value: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(10.dp))
    Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** Системное окно «Добавить виджет»: лаунчер сам предложит место на главном экране. */
private fun pinWidget(context: Context) {
  AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, MusicWidgetReceiver::class.java), null, null)
}
