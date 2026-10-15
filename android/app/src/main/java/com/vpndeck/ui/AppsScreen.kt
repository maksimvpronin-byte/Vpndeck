package com.vpndeck.ui

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vpndeck.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val system: Boolean)

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val _apps = MutableStateFlow<List<AppEntry>?>(null)
    val apps = _apps.asStateFlow()
    private val icons = LruCache<String, ImageBitmap>(200)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            val list = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
                .filter { it.packageName != app.packageName }
                .filter { it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true }
                .mapNotNull { p ->
                    val ai = p.applicationInfo ?: return@mapNotNull null
                    AppEntry(
                        pkg = p.packageName,
                        label = ai.loadLabel(pm).toString(),
                        system = ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == ApplicationInfo.FLAG_SYSTEM,
                    )
                }
                .sortedBy { it.label.lowercase() }
            _apps.value = list
        }
    }

    suspend fun icon(pkg: String): ImageBitmap? {
        icons.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()?.also { icons.put(pkg, it) }
        }
    }
}

@Composable
fun AppsScreen(modifier: Modifier, vm: AppsViewModel = viewModel()) {
    val apps by vm.apps.collectAsState()
    val selected by Store.selectedApps.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    // Freeze ordering while the screen is open so rows don't jump when ticked.
    val pinnedOnTop = remember { selected }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Поиск") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Через VPN: ${selected.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text("Системные") })
        }

        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val q = query.trim().lowercase()
        val visible = list
            .filter { showSystem || !it.system || it.pkg in selected }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            .sortedByDescending { it.pkg in pinnedOnTop }

        LazyColumn(Modifier.fillMaxSize()) {
            items(visible, key = { it.pkg }) { app ->
                val checked = app.pkg in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { Store.setAppSelected(app.pkg, !checked) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon by produceState<ImageBitmap?>(null, app.pkg) { value = vm.icon(app.pkg) }
                    Box(Modifier.size(40.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            app.pkg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Checkbox(checked = checked, onCheckedChange = { Store.setAppSelected(app.pkg, it) })
                }
            }
        }
    }
}
