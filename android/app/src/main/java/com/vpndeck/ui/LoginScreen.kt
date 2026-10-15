package com.vpndeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vpndeck.data.ApiClient
import com.vpndeck.data.Store
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(Store.lastHost?.let { h -> if (Store.lastPort == ApiClient.DEFAULT_PORT) h else "$h:${Store.lastPort}" } ?: "") }
    var username by rememberSaveable { mutableStateOf(Store.lastAccount ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Pending trust decision: (host, port, fingerprint)
    var askTrust by remember { mutableStateOf<Triple<String, Int, String>?>(null) }
    var pinMismatch by remember { mutableStateOf(false) }
    // A key the user already accepted in this session (the pin is only persisted on a
    // successful login), so a mistyped password doesn't bring the trust dialog back.
    var acceptedPin by remember { mutableStateOf<Triple<String, Int, String>?>(null) }

    fun doLogin(host: String, port: Int, pin: String) {
        scope.launch {
            busy = true
            error = null
            try {
                val r = ApiClient.login(host, port, pin, username.trim(), password)
                Store.saveLogin(Store.Session(host, port, pin, r.profile.account.ifEmpty { username.trim() }, r.token), r.profile)
            } catch (e: ApiClient.ApiException.PinMismatch) {
                pinMismatch = true
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"
            } finally {
                busy = false
            }
        }
    }

    fun submit(forgetPin: Boolean = false) {
        val (host, port) = ApiClient.parseAddress(address) ?: run {
            error = "Укажите адрес сервера"
            return
        }
        if (username.isBlank() || password.isEmpty()) {
            error = "Введите логин и пароль"
            return
        }
        val known = if (forgetPin) {
            null
        } else {
            acceptedPin?.takeIf { it.first == host && it.second == port }?.third ?: Store.pinFor(host, port)
        }
        if (known != null) {
            doLogin(host, port, known)
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                askTrust = Triple(host, port, ApiClient.probeFingerprint(host, port))
            } catch (e: Exception) {
                error = "Сервер недоступен: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(Icons.Filled.Shield, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Text("VpnDeck", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = address, onValueChange = { address = it },
                label = { Text("Сервер") }, placeholder = { Text("1.2.3.4") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("Логин") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Пароль") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!busy) submit() }),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Text("Войти")
            }
        }
    }

    askTrust?.let { (host, port, fp) ->
        AlertDialog(
            onDismissRequest = { askTrust = null },
            title = { Text("Новый сервер") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Первое подключение к $host. Ключ сервера:")
                    Text(fp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("Если не уверены — сверьте с тем, что выводит «vpndeck-server fingerprint» на сервере. Приложение запомнит этот ключ и будет предупреждать, если он изменится.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askTrust = null
                    acceptedPin = Triple(host, port, fp)
                    doLogin(host, port, fp)
                }) { Text("Доверять") }
            },
            dismissButton = { TextButton(onClick = { askTrust = null }) { Text("Отмена") } },
        )
    }

    if (pinMismatch) {
        AlertDialog(
            onDismissRequest = { pinMismatch = false },
            title = { Text("Ключ сервера изменился") },
            text = {
                Text("Сервер предъявил другой ключ, чем раньше. Так бывает после переустановки сервера — или при перехвате соединения. Продолжайте, только если сервер точно переустанавливали.")
            },
            confirmButton = {
                TextButton(onClick = {
                    pinMismatch = false
                    submit(forgetPin = true)
                }) { Text("Проверить новый ключ") }
            },
            dismissButton = { TextButton(onClick = { pinMismatch = false }) { Text("Отмена") } },
        )
    }
}
