package com.example.util

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

object AppUpdater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/giuseppeformisano/family-radar/releases/tags/latest-debug"
    private const val CHANNEL_UPDATE = "radar_update"
    private const val NOTIF_ID = 9001

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()

            val releaseBody = client.newCall(
                Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
            ).execute().body?.string() ?: return@withContext null

            val assets = JSONObject(releaseBody).getJSONArray("assets")

            var apkUrl: String? = null
            var versionCode: Int? = null
            var versionName: String? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.getString("name")) {
                    "version.json" -> {
                        val vBody = client.newCall(
                            Request.Builder()
                                .url(asset.getString("browser_download_url"))
                                .build()
                        ).execute().body?.string() ?: continue
                        val v = JSONObject(vBody)
                        versionCode = v.getInt("versionCode")
                        versionName = v.getString("versionName")
                    }
                    else -> if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                    }
                }
            }

            if (versionCode != null && versionCode > BuildConfig.VERSION_CODE && apkUrl != null) {
                UpdateInfo(versionCode, versionName ?: "", apkUrl)
            } else null
        } catch (_: Exception) { null }
    }

    fun downloadAndInstall(context: Context, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val destFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "family-radar-update.apk"
        )
        if (destFile.exists()) destFile.delete()

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(
            DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Family Radar")
                .setDescription("Scaricamento aggiornamento...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "family-radar-update.apk"
                )
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                ctx.unregisterReceiver(this)
                showInstallNotification(ctx, destFile)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private fun showInstallNotification(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pending = PendingIntent.getActivity(
                context, 0, installIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_UPDATE, "Aggiornamenti", NotificationManager.IMPORTANCE_HIGH)
                )
            }

            manager.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_UPDATE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Family Radar aggiornato")
                    .setContentText("Tocca per installare la versione scaricata")
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build()
            )
        } catch (_: Exception) {}
    }
}
