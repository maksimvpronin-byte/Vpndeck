package com.vpndeck.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.vpndeck.BuildConfig
import com.vpndeck.data.Store
import com.vpndeck.update.Updater
import com.vpndeck.vpn.VpnDeckService
import com.vpndeck.vpn.VpnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(modifier: Modifier, openApps: () -> Unit) {
    val ctx = LocalContext.current
    val status by VpnState.status.collectAsState()
    val error by VpnState.error.collectAsState()
    val since by VpnState.since.collectAsState()
    val apps by Store.selectedApps.collectAsState()
    val session by Store.session.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            VpnDeckService.start(ctx)
        } else {
            Toast.makeText(ctx, "Без разрешения VPN не запустится", Toast.LENGTH_LONG).show()
        }
    }

    fun connect() {
        if (apps.isEmpty()) {
            Toast.makeText(ctx, "Сначала выберите приложения для VPN", Toast.LENGTH_LONG).show()
            openApps()
            return
        }
        val intent = VpnService.prepare(ctx)
        if (intent != null) permission.launch(intent) else VpnDeckService.start(ctx)
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UpdateCard()
        Spacer(Modifier.height(8.dp))
        ConnectButton(status) {
            when (status) {
                VpnState.Status.Stopped -> connect()
                VpnState.Status.Running, VpnState.Status.Starting -> VpnDeckService.stop(ctx)
                VpnState.Status.Stopping -> Unit
            }
        }
        Text(
            when (status) {
                VpnState.Status.Stopped -> "Отключено"
                VpnState.Status.Starting -> "Подключение…"
                VpnState.Status.Running -> "Подключено"
                VpnState.Status.Stopping -> "Отключение…"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (status == VpnState.Status.Running) Connected else MaterialTheme.colorScheme.onSurface,
        )
        if (status == VpnState.Status.Running) Uptime(since)

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        OutlinedButton(onClick = openApps, modifier = Modifier.fillMaxWidth()) {
            Text(if (apps.isEmpty()) "Выбрать приложения для VPN" else "Через VPN: ${apps.size} ${plural(apps.size, "приложение", "приложения", "приложений")}")
        }

        StabilityCard()

        session?.let { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.account, style = MaterialTheme.typography.bodyLarge)
                    Text(s.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { confirmLogout = true }) { Text("Выйти") }
            }
        }

        VersionRow()
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("VPN отключится. Список приложений сохранится.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    VpnDeckService.stop(ctx)
                    Store.logout()
                }) { Text("Выйти") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ConnectButton(status: VpnState.Status, onClick: () -> Unit) {
    val busy = status == VpnState.Status.Starting || status == VpnState.Status.Stopping
    val on = status == VpnState.Status.Running
    Surface(
        onClick = onClick,
        enabled = status != VpnState.Status.Stopping,
        shape = CircleShape,
        color = if (on) Connected else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(2.dp, if (on) Connected else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(180.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(56.dp))
            } else {
                Icon(
                    Icons.Filled.PowerSettingsNew, null, Modifier.size(72.dp),
                    tint = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Uptime(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text(
        "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Two system settings that matter most for a VPN that must not silently die. */
@SuppressLint("BatteryLife")
@Composable
private fun StabilityCard() {
    val ctx = LocalContext.current
    var ignoringBattery by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        ignoringBattery = ctx.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        onPauseOrDispose {}
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Для стабильной работы", style = MaterialTheme.typography.titleSmall)
            if (!ignoringBattery) {
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.BatteryAlert, null, Modifier.size(18.dp))
                    Text("  Разрешить работу в фоне")
                }
            }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Text("  Включить «Постоянная VPN»")
            }
            Text(
                "В настройках VPN нажмите ⚙ у VpnDeck и включите «Постоянная VPN» — тогда Android сам перезапустит VPN, если система его выгрузит. " +
                    "«Блокировать соединения без VPN» не включайте: остальные приложения потеряют интернет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val n10 = n % 10
    val n100 = n % 100
    return when {
        n10 == 1 && n100 != 11 -> one
        n10 in 2..4 && n100 !in 12..14 -> few
        else -> many
    }
}

@Composable
private fun UpdateCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()

    LifecycleResumeEffect(Unit) {
        val st = Updater.state.value
        if (st is Updater.State.NeedsPermission && ctx.packageManager.canRequestPackageInstalls()) {
            // Came back from the "install unknown apps" screen with permission: carry on.
            scope.launch { Updater.install(ctx, st.release) }
        } else {
            Updater.permissionReturned()
            scope.launch { Updater.check() }
        }
        onPauseOrDispose {}
    }

    val release = when (val st = state) {
        is Updater.State.Available -> st.release
        is Updater.State.Downloading -> st.release
        is Updater.State.NeedsPermission -> st.release
        is Updater.State.Installing -> st.release
        is Updater.State.Failed -> st.release
        else -> null
    } ?: return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Доступна версия ${release.version}", style = MaterialTheme.typography.titleSmall)
            when (val st = state) {
                is Updater.State.Downloading -> {
                    LinearProgressIndicator(progress = { st.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("Загрузка… ${st.percent}%", style = MaterialTheme.typography.bodySmall)
                }
                is Updater.State.Installing -> Text("Установка…", style = MaterialTheme.typography.bodySmall)
                is Updater.State.NeedsPermission -> {
                    Text(
                        "Разрешите VpnDeck устанавливать обновления — это нужно один раз.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
                        )
                    }) { Text("Разрешить") }
                }
                else -> {
                    if (st is Updater.State.Failed) {
                        Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { scope.launch { Updater.install(ctx, release) } }) {
                        Text(if (st is Updater.State.Failed) "Повторить" else "Обновить (${release.size / 1_048_576} МБ)")
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "VpnDeck ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = state !is Updater.State.Checking && state !is Updater.State.Downloading,
            onClick = {
                scope.launch {
                    if (Updater.check(force = true) == null && Updater.state.value is Updater.State.UpToDate) {
                        Toast.makeText(ctx, "У вас последняя версия", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        ) { Text(if (state is Updater.State.Checking) "Проверяю…" else "Проверить обновления") }
    }
}
