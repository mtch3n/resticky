package dev.resticky.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import dev.resticky.Release
import dev.resticky.Releases
import kotlinx.coroutines.flow.MutableStateFlow

private const val INSTALLED = "dev.resticky.app.INSTALLED"

class Updater(private val context: Context, private val releases: Releases) {
    val status = MutableStateFlow("")

    init {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION")
                        (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        status.value = "Confirm the install"
                    }
                    PackageInstaller.STATUS_SUCCESS -> status.value = "Installed"
                    else -> status.value = "Install failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}"
                }
            }
        }, IntentFilter(INSTALLED), Context.RECEIVER_NOT_EXPORTED)
    }

    val version: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    suspend fun install(release: Release) {
        val asset = release.apk ?: return run { status.value = "The release has no app" }
        if (!context.packageManager.canRequestPackageInstalls()) {
            status.value = "Allow installs from resticky, then tap again"
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        status.value = "Downloading ${release.version}…"
        val bytes = runCatching { releases.download(asset) }.getOrElse {
            status.value = "Download failed: ${it.message}"
            return
        }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("resticky.apk", 0, bytes.size.toLong()).use {
                it.write(bytes)
                session.fsync(it)
            }
            val done = PendingIntent.getBroadcast(context, id, Intent(INSTALLED).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(done.intentSender)
        }
        status.value = "Installing ${release.version}…"
        log("INFO app update started version=${release.version}")
    }
}
