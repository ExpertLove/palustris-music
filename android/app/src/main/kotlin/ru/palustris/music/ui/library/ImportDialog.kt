package ru.palustris.music.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.palustris.music.core.model.ImportPreview
import ru.palustris.music.core.model.Track

/**
 * Импорт плейлиста по ссылке (Яндекс, Spotify, Apple Music, Deezer, YouTube Music) или списком строк.
 * Сначала показывает, сколько треков нашлось, потом создаёт плейлист.
 */
@Composable
fun ImportDialog(
  onDismiss: () -> Unit,
  preview: suspend (link: String?, text: String?) -> Result<ImportPreview>,
  create: suspend (name: String, tracks: List<Track>) -> Result<Long>,
  onCreated: (Long) -> Unit,
) {
  val scope = rememberCoroutineScope()
  var link by rememberSaveable { mutableStateOf("") }
  var text by rememberSaveable { mutableStateOf("") }
  var name by rememberSaveable { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var result by remember { mutableStateOf<ImportPreview?>(null) }

  val check: () -> Unit = {
    scope.launch {
      busy = true
      error = null
      preview(link.trim().ifBlank { null }, text.trim().ifBlank { null })
        .onSuccess {
          result = it
          name = it.name
        }
        .onFailure { error = it.message ?: "Не получилось прочитать плейлист" }
      busy = false
    }
  }
  val submit: () -> Unit = {
    val r = result
    if (r != null) {
      scope.launch {
        busy = true
        error = null
        create(name.trim(), r.matched)
          .onSuccess(onCreated)
          .onFailure { error = it.message ?: "Не получилось создать плейлист" }
        busy = false
      }
    }
  }

  AlertDialog(
    onDismissRequest = { if (!busy) onDismiss() },
    title = { Text(if (result == null) "Импорт плейлиста" else "Проверь и создай") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          "Ссылка на публичный плейлист (Яндекс Музыка, Spotify, Apple Music, Deezer, YouTube Music) или список строк «Исполнитель — Название».",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = link,
          onValueChange = { link = it },
          label = { Text("Ссылка") },
          singleLine = true,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          label = { Text("Или список строк") },
          minLines = 3,
          maxLines = 6,
          enabled = !busy,
          modifier = Modifier.fillMaxWidth(),
        )
        val r = result
        if (r != null) {
          Text("Нашлось ${r.matched.size} из ${r.total}", style = MaterialTheme.typography.titleSmall)
          if (r.missing.isNotEmpty()) {
            Text(
              "Не нашлось: " + r.missing.take(4).joinToString("; ") { "${it.artist} — ${it.title}" } +
                if (r.missing.size > 4) " и ещё ${r.missing.size - 4}" else "",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 100) name = it },
            label = { Text("Название плейлиста") },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
          )
        }
        if (busy) {
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Ищу треки в каталоге…", style = MaterialTheme.typography.bodySmall)
          }
          Spacer(Modifier.height(2.dp))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
      }
    },
    confirmButton = {
      if (result == null) {
        TextButton(onClick = check, enabled = !busy && (link.isNotBlank() || text.isNotBlank())) { Text("Проверить") }
      } else {
        TextButton(
          onClick = submit,
          enabled = !busy && name.isNotBlank() && result!!.matched.isNotEmpty(),
        ) { Text("Создать (${result!!.matched.size})") }
      }
    },
    dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
  )
}
