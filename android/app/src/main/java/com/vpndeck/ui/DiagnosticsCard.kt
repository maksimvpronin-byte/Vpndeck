package com.vpndeck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import com.vpndeck.BuildConfig
import com.vpndeck.data.ApiClient
import com.vpndeck.data.Store
import com.vpndeck.vpn.TunnelProbe
import com.vpndeck.vpn.VpnState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Reports only structured facts; raw profiles, credentials and core logs never leave the app. */
@Composable
fun DiagnosticsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api by VpnState.apiResult.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Диагностика", style = MaterialTheme.typography.titleSmall)
            Text("Сервер: $api", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val session = Store.session.value
                            val apiStatus = if (session == null) "Нет сессии" else try {
                                ApiClient.fetchProfile(session, 5_000)
                                "Доступен"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: ApiClient.ApiException.DeviceRevoked) {
                                "Устройство отключено"
                            } catch (_: ApiClient.ApiException.Unauthorized) {
                                "Нужен повторный вход"
                            } catch (_: ApiClient.ApiException.PinMismatch) {
                                "Ключ сервера изменился"
                            } catch (_: Exception) {
                                "Недоступен"
                            }
                            VpnState.apiResult.value = apiStatus
                            val tunnel = if (VpnState.status.value == VpnState.Status.Running) {
                                val ok = TunnelProbe.check()
                                if (VpnState.status.value != VpnState.Status.Running) "Отключён во время проверки"
                                else if (ok) "Передаёт трафик" else "Проверки не прошли"
                            } else "Отключён"
                            val fetchedAt = Store.profileFetchedAt
                            val profileTime = if (fetchedAt == 0L) "Нет профиля" else DateFormat.getDateTimeInstance().format(Date(fetchedAt))
                            report = buildString {
                                appendLine("VpnDeck ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                appendLine("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
                                appendLine("Устройство: ${Store.deviceName}")
                                appendLine("ID устройства: ${session?.let { Store.deviceIdFor(it.account) } ?: Store.deviceId}")
                                appendLine("API: $apiStatus")
                                appendLine("Туннель: $tunnel")
                                appendLine("Выбрано приложений: ${Store.selectedApps.value.size}")
                                appendLine("Профиль обновлён: $profileTime")
                                append("Проверено: ${DateFormat.getDateTimeInstance().format(Date())}")
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "Проверяем…" else "Проверить соединение") }
        }
    }
    report?.let { text ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text("Результат проверки") },
            text = { Text(text, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("Диагностика VpnDeck", text))
                }) { Text("Скопировать") }
            },
            dismissButton = { TextButton(onClick = { report = null }) { Text("Закрыть") } },
        )
    }
}
