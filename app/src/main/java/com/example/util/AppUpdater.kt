package com.example.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.R
import com.example.ui.theme.LanguagePreferences
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Risultato del check aggiornamenti: disponibile, già aggiornato, o errore rete. */
sealed class CheckResult {
    data class Available(val info: UpdateInfo) : CheckResult()
    object UpToDate : CheckResult()
    object NetworkError : CheckResult()
}

object AppUpdater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/giuseppeformisano/family-radar/releases/tags/latest-debug"
    private const val CHANNEL_UPDATE = "radar_update"
    private const val NOTIF_ID = 8_999

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // Client senza timeout di lettura per il download dell'APK (può richiedere minuti)
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    /** Ritorna UpdateInfo se c'e' una versione piu' recente, null altrimenti. */
    suspend fun check(): UpdateInfo? = checkDetailed().let {
        if (it is CheckResult.Available) it.info else null
    }

    /** Come [check] ma distingue "gia' aggiornato" da "errore di rete". */
    suspend fun checkDetailed(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val releaseBody = client.newCall(
                Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()
            ).execute().use { it.body?.string() } ?: return@withContext CheckResult.NetworkError

            val json = JSONObject(releaseBody)
            if (json.has("message") && !json.has("assets")) {
                Log.w("AppUpdater", "GitHub API error: ${json.optString("message")}")
                return@withContext CheckResult.NetworkError
            }

            val assets = json.getJSONArray("assets")
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
                        ).execute().use { it.body?.string() } ?: continue
                        val v = JSONObject(vBody)
                        versionCode = v.getInt("versionCode")
                        versionName = v.getString("versionName")
                    }
                    else -> if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                    }
                }
            }

            when {
                versionCode == null || apkUrl == null -> CheckResult.NetworkError
                versionCode > BuildConfig.VERSION_CODE ->
                    CheckResult.Available(UpdateInfo(versionCode, versionName ?: "", apkUrl))
                else -> CheckResult.UpToDate
            }
        } catch (e: Exception) {
            Log.w("AppUpdater", "check() failed: ${e.message}")
            CheckResult.NetworkError
        }
    }

    /**
     * Scarica l'APK con OkHttp (bypassando DownloadManager, inaffidabile su Samsung)
     * mostrando una notifica con barra di progresso, poi propone l'installazione.
     */
    fun downloadAndInstall(context: Context, apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            android.widget.Toast.makeText(
                context,
                res(context).getString(R.string.update_allow_unknown_sources),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(context, manager)

        // Notifica di progresso indeterminato mentre parte il download
        manager.notify(NOTIF_ID, progressNotification(context, -1, 0).build())
        android.widget.Toast.makeText(
            context,
            res(context).getString(R.string.update_download_started),
            android.widget.Toast.LENGTH_SHORT
        ).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Pulizia file precedenti
                context.cacheDir.listFiles { f -> f.name.startsWith("family-radar-update") }
                    ?.forEach { runCatching { it.delete() } }

                val dest = File(context.cacheDir, "family-radar-update-${System.currentTimeMillis()}.apk")

                val response = downloadClient.newCall(Request.Builder().url(apkUrl).build()).execute()
                val body = response.body ?: run {
                    Log.w("AppUpdater", "Download: body nullo")
                    manager.cancel(NOTIF_ID)
                    showErrorToast(context, res(context).getString(R.string.update_failed_empty_response))
                    return@launch
                }

                val total = body.contentLength()
                var downloaded = 0L

                dest.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8 * 1024)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                manager.notify(NOTIF_ID, progressNotification(context, 100, pct).build())
                            }
                        }
                    }
                }

                if (!dest.exists() || dest.length() == 0L) {
                    Log.w("AppUpdater", "APK vuoto dopo download")
                    manager.cancel(NOTIF_ID)
                    showErrorToast(context, res(context).getString(R.string.update_failed_empty_file))
                    return@launch
                }

                Log.d("AppUpdater", "Download completato: ${dest.length()} byte")
                // Prima prova a lanciare l'installer direttamente dal Main thread:
                // funziona se l'app è ancora in foreground (schermata visibile).
                // Se fallisce (app in background / Android 11+ blocca), mostra la
                // notifica che l'utente tocca per completare l'installazione.
                withContext(Dispatchers.Main) {
                    val launched = launchInstaller(context, dest)
                    if (!launched) showInstallNotification(context, manager, dest)
                    else manager.cancel(NOTIF_ID)
                }

            } catch (e: Exception) {
                Log.w("AppUpdater", "downloadAndInstall errore: ${e.message}")
                manager.cancel(NOTIF_ID)
                showErrorToast(context, res(context).getString(R.string.update_failed_reason, e.message ?: ""))
            }
        }
    }

    /**
     * Context da cui leggere le stringhe: quello che arriva qui e' l'application
     * context, che ignora la lingua scelta in Impostazioni. Senza questo passaggio
     * la notifica di download resterebbe in italiano con l'app in inglese.
     */
    private fun res(context: Context): Context = LanguagePreferences.localizedContext(context)

    private fun progressNotification(
        context: Context,
        max: Int,
        progress: Int
    ): NotificationCompat.Builder {
        val indeterminate = max < 0
        val res = res(context)
        return NotificationCompat.Builder(context, CHANNEL_UPDATE)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(res.getString(R.string.update_downloading_title))
            .setContentText(
                if (indeterminate) res.getString(R.string.update_download_starting)
                else res.getString(R.string.update_download_progress, progress)
            )
            .setProgress(if (indeterminate) 0 else max, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private fun launchInstaller(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            true
        }.getOrElse {
            Log.w("AppUpdater", "launchInstaller fallito: ${it.message}")
            false
        }
    }

    private fun showInstallNotification(context: Context, manager: NotificationManager, file: File) {
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

            manager.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL_UPDATE)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(res(context).getString(R.string.update_ready_title))
                    .setContentText(res(context).getString(R.string.update_ready_body))
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pending)
                    .build()
            )
        } catch (e: Exception) {
            Log.w("AppUpdater", "showInstallNotification fallito: ${e.message}")
        }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_UPDATE,
                    res(context).getString(R.string.channel_update_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun showErrorToast(context: Context, msg: String) {
        CoroutineScope(Dispatchers.Main).launch {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
