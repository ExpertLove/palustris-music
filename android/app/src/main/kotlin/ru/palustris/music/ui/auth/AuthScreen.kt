package ru.palustris.music.ui.auth

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.palustris.music.BuildConfig

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AuthScreen(vm: AuthViewModel = hiltViewModel()) {
  val ui by vm.ui.collectAsStateWithLifecycle()
  var email by rememberSaveable { mutableStateOf("") }
  var password by rememberSaveable { mutableStateOf("") }
  var name by rememberSaveable { mutableStateOf("") }
  var consent by rememberSaveable { mutableStateOf(false) }
  var register by rememberSaveable { mutableStateOf(false) }
  var showPassword by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  val scheme = MaterialTheme.colorScheme
  val submit = { if (register) vm.register(email, password, name, consent) else vm.login(email, password) }

  Box(
    Modifier.fillMaxSize()
      .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.6f), scheme.surface, scheme.surface)))
  ) {
    Column(
      Modifier.fillMaxSize()
        .systemBarsPadding()
        .imePadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Spacer(Modifier.height(56.dp))
      Logo()
      Spacer(Modifier.height(20.dp))
      Text("Музыка", style = MaterialTheme.typography.displaySmall)
      Text(
        "Слушайте онлайн, скачивайте на телефон\nи берите с собой туда, где нет сети",
        style = MaterialTheme.typography.bodyLarge,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
      Spacer(Modifier.height(32.dp))

      Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
          listOf("Вход", "Регистрация").forEachIndexed { i, label ->
            SegmentedButton(
              selected = register == (i == 1),
              onClick = {
                register = i == 1
                vm.clearError()
              },
              shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) {
              Text(label)
            }
          }
        }
        OutlinedTextField(
          value = email,
          onValueChange = { email = it.trim() },
          label = { Text("Почта") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
          shape = RoundedCornerShape(18.dp),
          modifier = Modifier.fillMaxWidth(),
        )
        if (register) {
          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Как вас зовут (необязательно)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
          )
        }
        OutlinedTextField(
          value = password,
          onValueChange = { password = it },
          label = { Text("Пароль") },
          supportingText = if (register) ({ Text("Не короче 6 символов") }) else null,
          singleLine = true,
          visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { submit() }),
          trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
              Icon(
                if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                if (showPassword) "Скрыть пароль" else "Показать пароль",
              )
            }
          },
          shape = RoundedCornerShape(18.dp),
          modifier = Modifier.fillMaxWidth(),
        )
        if (register) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = consent, onCheckedChange = { consent = it })
            Text("Согласен с ", style = MaterialTheme.typography.bodyMedium)
            TextButton(
              onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, (BuildConfig.API_BASE_URL + "privacy").toUri()))
              },
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp),
            ) {
              Text("политикой конфиденциальности")
            }
          }
        }
        if (ui.error != null) {
          Text(ui.error!!, color = scheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (ui.info != null) {
          Text(ui.info!!, color = scheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        Button(
          onClick = submit,
          enabled = !ui.busy && email.contains('@') && password.length >= 6 && (!register || consent),
          modifier = Modifier.fillMaxWidth().height(56.dp),
          shape = RoundedCornerShape(20.dp),
        ) {
          if (ui.busy) LoadingIndicator(Modifier.size(28.dp), color = scheme.onPrimary)
          else Text(if (register) "Создать аккаунт" else "Войти", style = MaterialTheme.typography.titleMedium)
        }
        if (!register) {
          TextButton(onClick = { vm.forgot(email) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Забыли пароль?")
          }
        }
      }
      Spacer(Modifier.height(32.dp))
    }
  }
}

/** Логотип: четыре столбика эквалайзера, как на иконке приложения. */
@Composable
fun Logo(modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  Box(
    modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
    contentAlignment = Alignment.Center,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
      listOf(18, 34, 46, 26).forEach { h ->
        Box(Modifier.width(8.dp).height(h.dp).clip(RoundedCornerShape(4.dp)).background(scheme.onPrimary))
      }
    }
  }
}
