package dev.resticky.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.resticky.AUDIO_KEEP
import dev.resticky.HUB_VERSION
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private const val DRAIN_WORK = "drain"

class HubService : Service() {
    private var scope: CoroutineScope? = null
    private var server: EmbeddedServer<*, *>? = null
    private var announced = false
    private var mdns = true
    private val nsd = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = log("INFO mdns announced name=${info.serviceName}")
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = log("WARN mdns announce failed error=$error")
        override fun onServiceUnregistered(info: NsdServiceInfo) = log("INFO mdns stopped")
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = log("WARN mdns stop failed error=$error")
    }
    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = startWifi()
        override fun onLost(network: Network) = stopWifi()
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HubApp
        intent?.getStringExtra("passkey")?.let { app.ble.enterPasskey(it) }
        if (intent?.getStringExtra("pipeline") == "rerun") CoroutineScope(Dispatchers.Default).launch {
            log("INFO pipeline rerun sessions=${app.store.rerunAll(app.engines.speechId())}")
            app.pipeline.kick()
        }
        when (intent?.getStringExtra("firmware")) {
            "check" -> app.stickyUpdate.check()
            "update" -> app.stickyUpdate.update()
            "cancel" -> app.stickyUpdate.cancel()
        }
        if (scope != null) return START_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        mdns = intent?.getBooleanExtra("mdns", true) != false
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).apply {
            launch { app.engines.transcribe.collectLatest { schedule(it) } }
            launch {
                while (true) {
                    app.store.deleteOldAudio(Clock.System.now() - AUDIO_KEEP)
                    delay(1.hours)
                }
            }
            launch { app.weather.run() }

            launch {
                app.calendars.choice.collectLatest {
                    while (true) {
                        val agenda = runCatching { app.calendars.agenda() }.getOrElse { e -> log("WARN agenda failed err=${e.message}"); null }
                        if (agenda != app.hub.agenda.value) log("INFO agenda show=${agenda?.show} events=${agenda?.events?.size}")
                        app.hub.agenda.value = agenda
                        delay(5.minutes)
                    }
                }
            }
        }
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifi,
        )
        app.companion.association?.let(app.companion::observe)
        app.ble.start()
        app.running.value = true
        log("INFO resticky hub started data=${app.store.root}")
        return START_STICKY
    }

    private suspend fun schedule(mode: TranscribeMode) {
        val app = application as HubApp
        val work = WorkManager.getInstance(this)
        log("INFO transcribe mode=$mode")
        if (mode == TranscribeMode.Now) {
            work.cancelUniqueWork(DRAIN_WORK)
            app.pipeline.run()
            return
        }
        val request = PeriodicWorkRequestBuilder<DrainWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiresCharging(mode == TranscribeMode.Charging).build())
            .build()
        work.enqueueUniquePeriodicWork(DRAIN_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    @Synchronized
    private fun startWifi() {
        if (server != null) return
        val app = application as HubApp
        server = embeddedServer(CIO, port = PORT) { app.hub.module(this) }.start(wait = false)
        if (mdns) announce()
        log("INFO wifi hub listening port=$PORT mdns=$announced")
    }

    @Synchronized
    private fun stopWifi() {
        if (announced) getSystemService(NsdManager::class.java).unregisterService(nsd)
        announced = false
        server?.stop(500, 2000)
        server = null
        log("INFO wifi hub stopped")
    }

    private fun announce() {
        val info = NsdServiceInfo().apply {
            serviceName = "resticky-hub ${Build.MODEL}"
            serviceType = "_resticky._tcp"
            port = PORT
            setAttribute("version", HUB_VERSION)
            setAttribute("prio", "10")
        }
        getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, nsd)
        announced = true
    }

    override fun onDestroy() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifi)
        stopWifi()
        (application as HubApp).ble.stop()
        scope?.cancel()
        (application as HubApp).running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val channel = NotificationChannel("hub", "Hub", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "hub")
            .setContentTitle("resticky hub")
            .setContentText("Receiving from the Sticky")
            .setSmallIcon(R.drawable.ic_device_mobile)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
