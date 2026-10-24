package com.vpndeck.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.vpndeck.R
import com.vpndeck.VpnDeckApp
import com.vpndeck.data.ApiClient
import com.vpndeck.data.Profile
import com.vpndeck.data.Store
import com.vpndeck.ui.MainActivity
import com.vpndeck.update.Updater
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.NetworkInterface as BoxNetworkInterface

/**
 * The VPN. Android's VpnService owns the TUN device; sing-box (libbox) owns
 * everything that flows through it. Lifecycle operations run on a single
 * serial dispatcher; startup is explicitly cancelled before shutdown.
 */
class VpnDeckService : VpnService(), PlatformInterface, CommandServerHandler {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var profile: Profile? = null
    @Volatile private var startJob: Job? = null
    private var refreshJob: Job? = null
    private var appsJob: Job? = null
    private var watchdog: TunnelWatchdog? = null
    private var updateJob: Job? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STOP -> stopByUser()
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val idle = getSystemService<PowerManager>()?.isDeviceIdleMode == true
                    // Let the core back off in Doze and recover immediately afterwards.
                    scope.launch { commandServer?.let { if (idle) it.pause() else it.wake() } }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopByUser()
            return START_NOT_STICKY
        }
        // Must happen within seconds of startForegroundService(), before any slow work.
        val stopped = VpnState.status.value == VpnState.Status.Stopped
        startInForeground(if (stopped) getString(R.string.status_starting) else runningText())
        if ((intent?.action == ACTION_START || intent == null) && !Store.wantConnected) {
            stopByUser()
            return START_NOT_STICKY
        }
        registerReceiverOnce()
        if (stopped) {
            VpnState.set(VpnState.Status.Starting)
            Store.wantConnected = true
            startJob = scope.launch { start() }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN app took over, or the user revoked us in system settings.
        stopByUser()
    }

    override fun onDestroy() {
        startJob?.cancel()
        runBlocking(serial) { stop() }
        scope.cancel()
        super.onDestroy()
    }

    private fun stopByUser() {
        Store.wantConnected = false
        startJob?.cancel()
        scope.launch { stop() }
    }

    // ---- lifecycle (always on `serial`) ----

    private suspend fun start() {
        try {
            if (prepare(this) != null) error(getString(R.string.err_no_vpn_permission))
            // Fresh parameters first, so server-side changes (port, camouflage, keys) apply on
            // the next connect instead of after the 6-hour refresh. A short timeout keeps a
            // slow or unreachable auth server from delaying the VPN; then the cache is used.
            startWhenWanted(
                fetch = { refreshProfileNow(START_FETCH_TIMEOUT_MS, allowCache = true) ?: error(getString(R.string.err_no_profile)) },
                wanted = { Store.wantConnected && Store.session.value != null },
            ) { current ->
                profile = current
                NetworkMonitor.start()
                Libbox.promoteOOMDraft()
                val server = CommandServer(this, this)
                server.start()
                commandServer = server
                server.startOrReloadService(buildConfig(current), OverrideOptions())
                VpnState.set(VpnState.Status.Running)
                startInForeground(runningText())
            }
            scheduleRefresh()
            watchAppSelection()
            startWatchdog()
            scheduleUpdateCheck()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(VpnDeckApp.TAG, "start failed", e)
            if (VpnState.error.value == null) VpnState.fail(e.message ?: e.toString())
            Store.wantConnected = false
            stop()
        }
    }

    private suspend fun reload(reason: String) {
        if (!Store.wantConnected || Store.session.value == null) return
        val server = commandServer ?: return
        val current = profile ?: return
        try {
            Log.i(VpnDeckApp.TAG, "reload: $reason")
            server.startOrReloadService(buildConfig(current), OverrideOptions())
            startInForeground(runningText())
        } catch (e: Exception) {
            Log.e(VpnDeckApp.TAG, "reload failed", e)
            VpnState.fail(e.message ?: e.toString())
            Store.wantConnected = false
            stop()
        }
    }

    private fun stop() {
        if (VpnState.status.value == VpnState.Status.Stopped && commandServer == null) {
            stopSelfCompletely()
            return
        }
        VpnState.set(VpnState.Status.Stopping)
        startJob?.cancel()
        refreshJob?.cancel()
        appsJob?.cancel()
        watchdog?.stop()
        watchdog = null
        updateJob?.cancel()
        commandServer?.let { server ->
            runCatching { server.closeService() }.onFailure { Log.w(VpnDeckApp.TAG, "closeService", it) }
            runCatching { server.close() }
        }
        commandServer = null
        tunFd?.let { runCatching { it.close() } }
        tunFd = null
        NetworkMonitor.stop()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        VpnState.set(VpnState.Status.Stopped)
        stopSelfCompletely()
    }

    private fun stopSelfCompletely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildConfig(p: Profile): String {
        val apps = Store.selectedApps.value.filter { it != packageName && isInstalled(it) }
        if (apps.isEmpty()) error(getString(R.string.err_no_apps))
        // VpnDeck itself must be inside the tunnel: with the system TCP stack the core
        // answers app connections from a socket in this process, and those replies are
        // lost if our UID is routed around tun. Our own API calls bypass the tunnel
        // explicitly (ApiClient binds to the underlying network).
        val routed = apps + packageName
        val log = java.io.File(cacheDir, LOG_FILE)
        // Fresh log per (re)start keeps it small; the previous one is kept for "it broke overnight" cases.
        if (log.length() > 0) log.renameTo(java.io.File(cacheDir, "$LOG_FILE.old"))
        return ConfigBuilder.build(p, routed, log.path, if (com.vpndeck.BuildConfig.DEBUG) "debug" else "warn")
    }

    private fun isInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Fetches the profile directly (our own traffic never goes through the tunnel). */
    private suspend fun refreshProfileNow(timeoutMs: Int = 15_000, allowCache: Boolean = false): Profile? {
        val session = Store.session.value ?: return null
        return refreshOrCached(
            fetch = { ApiClient.fetchSession(session, timeoutMs) },
            cached = { if (allowCache) Store.profile?.let { ApiClient.LoginResult(session.token, it) } else null },
            save = {
                if (!Store.saveSessionIfCurrent(session, it.token, it.profile)) throw CancellationException("Session changed")
                VpnState.apiResult.value = "Доступен"
            },
            isCurrent = { Store.session.value == session },
            rejected = { error ->
                if (error is ApiClient.ApiException.Unauthorized) Store.logout()
                VpnState.apiResult.value = if (error is ApiClient.ApiException.Unauthorized) "Нужен повторный вход" else "Ключ сервера изменился"
                VpnState.fail(error.message ?: "Ошибка проверки сервера")
                stopByUser()
            },
            unavailable = { VpnState.apiResult.value = "Временно недоступен" },
        )?.profile
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // Refresh soon after connecting if the cache is stale, then on the server's interval.
            val interval = (profile?.refreshIntervalSec ?: 6 * 3600L) * 1000
            val age = System.currentTimeMillis() - Store.profileFetchedAt
            delay(if (age > interval) 5_000 else interval - age)
            while (true) {
                val fresh = refreshProfileNow()
                if (fresh != null && fresh.raw != profile?.raw) {
                    profile = fresh
                    reload("server profile changed")
                }
                delay(if (fresh != null) fresh.refreshIntervalSec * 1000 else 15 * 60_000L)
            }
        }
    }

    /** Once a day: if GitHub has a newer release, tell the user once per version. */
    private fun scheduleUpdateCheck() {
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(60_000)
            while (true) {
                val release = Updater.check()
                if (release != null && Store.notifiedUpdate != release.version) {
                    Store.notifiedUpdate = release.version
                    notifyUpdate(release.version)
                }
                delay(24 * 3600_000L)
            }
        }
    }

    private fun notifyUpdate(version: String) {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, VpnDeckApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.update_available_title))
            .setContentText(getString(R.string.update_available_text, version))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService<android.app.NotificationManager>()?.notify(UPDATE_NOTIFICATION_ID, n) }
    }

    private fun startWatchdog() {
        watchdog?.stop()
        val dog = TunnelWatchdog(
            isIdle = { getSystemService<PowerManager>()?.isDeviceIdleMode == true },
            recover = {
                // Direct over the real network, so it works even with the tunnel down.
                val fresh = refreshProfileNow(10_000)
                if (fresh != null) {
                    withContext(serial) {
                        if (commandServer != null) {
                            val changed = fresh.raw != profile?.raw
                            profile = fresh
                            reload(if (changed) "server parameters changed" else "tunnel stopped passing traffic")
                        }
                    }
                }
                fresh != null
            },
            onHealthChanged = { healthy ->
                VpnState.setHealth(if (healthy) VpnState.Health.Healthy else VpnState.Health.Recovering)
                if (VpnState.status.value == VpnState.Status.Running) {
                    startInForeground(if (healthy) runningText() else getString(R.string.status_reconnecting))
                }
            },
        )
        NetworkMonitor.onNetworkChanged = { dog.kick() }
        watchdog = dog
        dog.start()
        dog.kick()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun watchAppSelection() {
        appsJob?.cancel()
        appsJob = scope.launch {
            Store.selectedApps.drop(1).distinctUntilChanged().debounce(1_500).collect {
                if (it.isEmpty()) {
                    VpnState.fail(getString(R.string.err_no_apps))
                    Store.wantConnected = false
                    stop()
                } else {
                    reload("app selection changed")
                }
            }
        }
    }

    private fun registerReceiverOnce() {
        if (receiverRegistered) return
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(ACTION_STOP)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    // ---- notification ----

    private fun runningText(): String {
        when (VpnState.health.value) {
            VpnState.Health.Checking -> return "Проверяем соединение…"
            VpnState.Health.Recovering -> return getString(R.string.status_reconnecting)
            else -> Unit
        }
        val n = Store.selectedApps.value.size
        return resources.getQuantityString(R.plurals.status_running_apps, n, n)
    }

    private fun startInForeground(text: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getBroadcast(
            this, 1, Intent(ACTION_STOP).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, VpnDeckApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_disconnect), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0,
        )
    }

    // ---- PlatformInterface: how the core reaches Android ----

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver

    override fun usePlatformAutoDetectInterfaceControl() = true

    /** Keeps the core's own sockets (to the VPN server) out of the tunnel. */
    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error(getString(R.string.err_no_vpn_permission))
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu)
            .setConfigureIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        options.inet4Address.forEach { builder.addAddress(it.address(), it.prefix()) }
        options.inet6Address.forEach { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                options.dnsServerAddress.forEachString { builder.addDnsServer(it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val v4 = options.inet4RouteAddress
                if (v4.hasNext()) v4.forEach { builder.addRoute(it.toIpPrefix()) } else if (options.inet4Address.hasNext()) builder.addRoute("0.0.0.0", 0)
                val v6 = options.inet6RouteAddress
                if (v6.hasNext()) v6.forEach { builder.addRoute(it.toIpPrefix()) } else if (options.inet6Address.hasNext()) builder.addRoute("::", 0)
                options.inet4RouteExcludeAddress.forEach { builder.excludeRoute(it.toIpPrefix()) }
                options.inet6RouteExcludeAddress.forEach { builder.excludeRoute(it.toIpPrefix()) }
            } else {
                options.inet4RouteRange.forEach { builder.addRoute(it.address(), it.prefix()) }
                options.inet6RouteRange.forEach { builder.addRoute(it.address(), it.prefix()) }
            }

            // The per-app split: only these apps' traffic ever enters the tunnel.
            options.includePackage.forEachString { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(VpnDeckApp.TAG, "skip missing package $pkg")
                }
            }
            options.excludePackage.forEachString { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
            }
        }

        val pfd = builder.establish() ?: error(getString(R.string.err_no_vpn_permission))
        tunFd?.let { runCatching { it.close() } }
        tunFd = pfd
        return pfd.fd
    }

    override fun useProcFS() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("not supported")
        val uid = VpnDeckApp.connectivity.getConnectionOwnerUid(
            ipProtocol,
            java.net.InetSocketAddress(sourceAddress, sourcePort),
            java.net.InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("connection owner not found")
        val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(StringList(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = NetworkMonitor.setListener(listener)

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = NetworkMonitor.setListener(null)

    override fun getInterfaces(): NetworkInterfaceIterator {
        val cm = VpnDeckApp.connectivity
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<BoxNetworkInterface>()
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val caps = cm.getNetworkCapabilities(network) ?: continue
            // Our own tunnels (including one still being torn down during a reload) are
            // never a way out, and a vanished one throws ENODEV on every query below.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val ni = javaInterfaces.find { it.name == lp.interfaceName } ?: continue
            // One interface disappearing mid-scan must not wipe out the whole list.
            result += runCatching { describeInterface(lp, caps, ni) }
                .onFailure { Log.w(VpnDeckApp.TAG, "skip interface ${lp.interfaceName}: ${it.message}") }
                .getOrNull() ?: continue
        }
        val it = result.iterator()
        return object : NetworkInterfaceIterator {
            override fun hasNext() = it.hasNext()
            override fun next(): BoxNetworkInterface = it.next()
        }
    }

    private fun describeInterface(lp: android.net.LinkProperties, caps: NetworkCapabilities, ni: NetworkInterface) =
        BoxNetworkInterface().apply {
            name = lp.interfaceName
            index = ni.index
            runCatching { mtu = ni.mtu }
            dnsServer = StringList(lp.dnsServers.mapNotNull { it.hostAddress })
            gateway = StringList(
                lp.routes.filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }.filterNot { it.isAnyLocalAddress }.mapNotNull { it.hostAddress },
            )
            type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            addresses = StringList(
                ni.interfaceAddresses.map {
                    val host = if (it.address is java.net.Inet6Address) {
                        java.net.Inet6Address.getByAddress(it.address.address).hostAddress
                    } else {
                        it.address.hostAddress
                    }
                    "$host/${it.networkPrefixLength}"
                },
            )
            var f = 0
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) f = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            if (ni.isLoopback) f = f or OsConstants.IFF_LOOPBACK
            if (ni.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
            if (ni.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
            flags = f
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }

    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(notification: BoxNotification) {}
    override fun cancelNotification(identifier: String, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun usePlatformShell() = false
    override fun checkPlatformShell() = error("not supported")
    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")
    override fun lookupUser(username: String?): PlatformUser = error("not supported")
    override fun lookupSFTPServer(): String = error("not supported")
    override fun readSystemSSHHostKey(): String = error("not supported")
    override fun tailscaleHostname(): String = Build.MODEL
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")

    // ---- CommandServerHandler: requests coming from the core ----

    override fun serviceStop() {
        Store.wantConnected = false
        scope.launch { stop() }
    }

    override fun serviceReload() {
        scope.launch { reload("core requested") }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message.orEmpty())
    }
    override fun connectSSHAgent(): Int = -1

    // ---- helpers ----

    private class StringList(list: List<String>) : StringIterator {
        private val it = list.iterator()
        private val size = list.size
        override fun len() = size
        override fun hasNext() = it.hasNext()
        override fun next(): String = it.next()
    }

    private inline fun io.nekohasekai.libbox.RoutePrefixIterator.forEach(block: (RoutePrefix) -> Unit) {
        while (hasNext()) block(next())
    }

    private inline fun StringIterator.forEachString(block: (String) -> Unit) {
        while (hasNext()) block(next())
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun RoutePrefix.toIpPrefix() = IpPrefix(InetAddress.getByName(address()), prefix())

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val UPDATE_NOTIFICATION_ID = 2
        const val LOG_FILE = "box.log"
        private const val START_FETCH_TIMEOUT_MS = 5_000
        private const val ACTION_START = "com.vpndeck.START"
        const val ACTION_STOP = "com.vpndeck.STOP"

        fun start(context: Context) {
            Store.wantConnected = true
            VpnState.clearError()
            ContextCompat.startForegroundService(context, Intent(context, VpnDeckService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            Store.wantConnected = false
            context.sendBroadcast(Intent(ACTION_STOP).setPackage(context.packageName))
            // If the service is not running yet, there is nobody to receive the
            // broadcast; make sure the user's intent is still recorded.
            Store.wantConnected = false
        }
    }
}
