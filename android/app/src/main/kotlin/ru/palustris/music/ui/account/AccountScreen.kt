package ru.palustris.music.ui.account

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.palustris.music.BuildConfig
import ru.palustris.music.core.model.Me
import ru.palustris.music.ui.common.ActionRow
import ru.palustris.music.ui.common.BackBar
import ru.palustris.music.ui.common.GroupCard
import ru.palustris.music.ui.common.GroupHeader
import ru.palustris.music.ui.common.LoadingState
import ru.palustris.music.ui.common.MessageState
import ru.palustris.music.ui.common.plural
import ru.palustris.music.ui.library.LibraryTab

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AccountScreen(
  contentPadding: PaddingValues,
  onBack: () -> Unit,
  onLogout: () -> Unit,
  onDeleted: () -> Unit,
  onOpenLibrary: (LibraryTab) -> Unit,
  onOpenSettings: () -> Unit,
  onMessage: (String) -> Unit,
  vm: AccountViewModel = hiltViewModel(),
) {
  val me by vm.me.collectAsStateWithLifecycle()
  val ui by vm.ui.collectAsStateWithLifecycle()
  var changingPassword by rememberSaveable { mutableStateOf(false) }
  var confirmLogout by remember { mutableStateOf(false) }
  var confirmDelete by remember { mutableStateOf(false) }
  val context = LocalContext.current
  LaunchedEffect(Unit) { vm.messages.collect(onMessage) }
  LaunchedEffect(Unit) { vm.passwordChanged.collect { changingPassword = false } }

  LazyColumn(contentPadding = contentPadding) {
    item {
      BackBar(onBack) { IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, "Настройки") } }
    }
    val m = me
    if (m == null) {
      item {
        if (ui.error != null) MessageState("Профиль не загрузился", ui.error, action = "Повторить", onAction = vm::refresh)
        else LoadingState()
      }
      return@LazyColumn
    }
    item { Hero(m) }
    item { Stats(m, onOpenLibrary) }
    if (!m.verified) {
      item {
        GroupCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MarkEmailUnread, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(14.dp))
            Text(
              "Подтвердите почту: так получится вернуть доступ, если забудете пароль",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
          }
          FilledTonalButton(onClick = vm::resendVerification, enabled = !ui.verifySent, modifier = Modifier.padding(top = 12.dp)) {
            Text(if (ui.verifySent) "Письмо отправлено" else "Отправить письмо")
          }
        }
      }
    }

    item { GroupHeader("Профиль") }
    item { ProfileCard(m, saving = ui.saving, onSave = vm::save) }

    item { GroupHeader("Безопасность") }
    item {
      GroupCard {
        Text(
          "Вход выполнен на ${plural(m.sessions, "устройстве", "устройствах", "устройствах")}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        ActionRow(Icons.Rounded.Key, "Сменить пароль", "Другие устройства после этого выйдут") { changingPassword = true }
        if (m.sessions > 1) {
          ActionRow(Icons.Rounded.Devices, "Выйти на других устройствах", "Этот телефон останется в аккаунте", onClick = vm::logoutOthers)
        }
        ActionRow(Icons.AutoMirrored.Rounded.Logout, "Выйти из аккаунта", "Скачанные треки останутся на телефоне") { confirmLogout = true }
      }
    }

    item { GroupHeader("Данные") }
    item {
      GroupCard {
        ActionRow(Icons.Rounded.Policy, "Политика конфиденциальности", "Что мы храним и зачем") {
          context.startActivity(Intent(Intent.ACTION_VIEW, (BuildConfig.API_BASE_URL + "privacy").toUri()))
        }
        ActionRow(
          Icons.Rounded.DeleteForever,
          "Удалить аккаунт",
          "Лайки, плейлисты и история удалятся навсегда",
          tint = MaterialTheme.colorScheme.error,
        ) {
          confirmDelete = true
        }
      }
    }
    item { Spacer(Modifier.height(24.dp)) }
  }

  if (changingPassword) {
    PasswordDialog(
      busy = ui.passwordBusy,
      error = ui.passwordError,
      onDismiss = {
        changingPassword = false
        vm.clearPasswordError()
      },
      onChange = vm::changePassword,
    )
  }
  if (confirmLogout) {
    AlertDialog(
      onDismissRequest = { confirmLogout = false },
      title = { Text("Выйти из аккаунта?") },
      text = { Text("Скачанные треки останутся на телефоне.") },
      confirmButton = {
        TextButton(onClick = {
          confirmLogout = false
          onLogout()
        }) { Text("Выйти") }
      },
      dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
    )
  }
  if (confirmDelete) {
    DeleteDialog(
      email = me?.email.orEmpty(),
      busy = ui.deleting,
      onDismiss = { confirmDelete = false },
      onDelete = { vm.deleteAccount(onDeleted) },
    )
  }
}

@Composable
private fun Hero(m: Me) {
  val scheme = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Avatar(m.name.ifBlank { m.email }, Modifier.size(96.dp), MaterialTheme.typography.displaySmall)
    Spacer(Modifier.height(14.dp))
    Text(
      m.name.ifBlank { "Без имени" },
      style = MaterialTheme.typography.headlineMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
      Text(m.email, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Spacer(Modifier.width(8.dp))
      Icon(
        if (m.verified) Icons.Rounded.MarkEmailRead else Icons.Rounded.MarkEmailUnread,
        if (m.verified) "Почта подтверждена" else "Почта не подтверждена",
        tint = if (m.verified) scheme.primary else scheme.tertiary,
        modifier = Modifier.size(18.dp),
      )
    }
    if (m.created > 0) {
      Text(
        "С нами с " + SimpleDateFormat("d MMMM yyyy", Locale.forLanguageTag("ru")).format(Date(m.created * 1000)),
        style = MaterialTheme.typography.labelMedium,
        color = scheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}

/** Круг с инициалами в цветах темы. */
@Composable
fun Avatar(nameOrEmail: String, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier.clip(CircleShape).background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
    contentAlignment = Alignment.Center,
  ) {
    Text(initials(nameOrEmail), style = style, color = scheme.onPrimary, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
private fun Stats(m: Me, onOpenLibrary: (LibraryTab) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Stat(m.likes, plural(m.likes, "лайк", "лайка", "лайков"), Modifier.weight(1f)) { onOpenLibrary(LibraryTab.Likes) }
    Stat(m.playlists, plural(m.playlists, "плейлист", "плейлиста", "плейлистов"), Modifier.weight(1f)) { onOpenLibrary(LibraryTab.Playlists) }
    Stat(m.plays, plural(m.plays, "прослушивание", "прослушивания", "прослушиваний"), Modifier.weight(1f)) {
      onOpenLibrary(LibraryTab.History)
    }
  }
}

@Composable
private fun Stat(n: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    shape = RoundedCornerShape(24.dp),
    modifier = modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick, role = Role.Button),
  ) {
    Column(Modifier.padding(vertical = 14.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
      Text("$n", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
      // Число уже крупно сверху, подпись без него.
      Text(
        label.substringAfter(' '),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun ProfileCard(m: Me, saving: Boolean, onSave: (String, List<String>) -> Unit) {
  var name by rememberSaveable(m.name) { mutableStateOf(m.name) }
  var genres by rememberSaveable(m.genres) { mutableStateOf(m.genres) }
  val dirty = name.trim() != m.name || genres != m.genres
  GroupCard {
    Text(
      "Имя видно в плейлистах, которыми вы делитесь. Жанры подсказывают «Моей волне», с чего начать.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
      value = name,
      onValueChange = { if (it.length <= 40) name = it },
      label = { Text("Имя") },
      placeholder = { Text("Как вас зовут") },
      singleLine = true,
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    )
    Text(
      "Любимые жанры · ${genres.size} из $MAX_GENRES",
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GENRES.forEach { g ->
        val on = g.id in genres
        FilterChip(
          selected = on,
          onClick = { genres = if (on) genres - g.id else if (genres.size >= MAX_GENRES) genres else genres + g.id },
          label = { Text(g.title) },
          leadingIcon = { Icon(g.icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
        )
      }
    }
    Button(onClick = { onSave(name, genres) }, enabled = dirty && !saving, modifier = Modifier.padding(top = 14.dp)) {
      Text(if (saving) "Сохраняем…" else "Сохранить")
    }
  }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PasswordDialog(busy: Boolean, error: String?, onDismiss: () -> Unit, onChange: (String, String) -> Unit) {
  var old by rememberSaveable { mutableStateOf("") }
  var new by rememberSaveable { mutableStateOf("") }
  var show by rememberSaveable { mutableStateOf(false) }
  val transform = if (show) VisualTransformation.None else PasswordVisualTransformation()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Сменить пароль") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = old,
          onValueChange = { old = it },
          label = { Text("Текущий пароль") },
          singleLine = true,
          visualTransformation = transform,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          shape = RoundedCornerShape(16.dp),
        )
        OutlinedTextField(
          value = new,
          onValueChange = { new = it },
          label = { Text("Новый пароль") },
          supportingText = { Text("Не короче 6 символов") },
          singleLine = true,
          visualTransformation = transform,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          trailingIcon = {
            IconButton(onClick = { show = !show }) {
              Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Скрыть пароли" else "Показать пароли")
            }
          },
          shape = RoundedCornerShape(16.dp),
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
      }
    },
    confirmButton = {
      TextButton(onClick = { onChange(old, new) }, enabled = !busy && old.isNotEmpty() && new.length >= 6) {
        if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("Сменить")
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}

/** Удаление необратимо, поэтому просим вписать почту: случайно так не нажать. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeleteDialog(email: String, busy: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
  var typed by rememberSaveable { mutableStateOf("") }
  val ok = typed.trim().equals(email, ignoreCase = true)
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = { Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
    title = { Text("Удалить аккаунт?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Удалятся профиль, лайки, плейлисты и история. Восстановить их не получится. Скачанные треки тоже сотрём с телефона.")
        Text("Чтобы подтвердить, впишите почту $email", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
          value = typed,
          onValueChange = { typed = it },
          singleLine = true,
          placeholder = { Text(email) },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
          shape = RoundedCornerShape(16.dp),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = onDelete, enabled = ok && !busy) {
        if (busy) LoadingIndicator(Modifier.size(24.dp)) else Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
      }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}
