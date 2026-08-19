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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
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
    private const val NOTIF_ID = 8_999  // era 9001 = conflitto con SOS_NOTIFICATION_ID

    // Timeout espliciti: OkHttpClient() senza configurazione ha timeout 0
    // (aspetta indefinitamente). Su reti instabili il check si bloccava in
    // silenzio e il dialog non compariva mai.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
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
            // GitHub restituisce {"message":"..."} per errori (rate limit, 404…)
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
                "Abilita 'Installa app sconosciute' per Family Radar, poi premi di nuovo Aggiorna",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return
        }

        // Nome univoco a ogni download. Con un nome fisso, se la cancellazione del
        // file precedente non andava a buon fine — capita quando una voce del
        // DownloadManager lo referenzia ancora — DownloadManager non sovrascriveva
        // ma scriveva su "...-1.apk", e l'app continuava a puntare al percorso
        // vecchio: la notifica compariva e toccarla non faceva nulla.
        val fileName = "family-radar-update-${System.currentTimeMillis()}.apk"
        purgeOldDownloads(context)

        // Su molti Samsung il "Gestione download" di sistema puo' essere
        // disattivato dall'utente: in quel caso getSystemService o enqueue lanciano
        // e, senza questo try/catch, il pulsante "Aggiorna" non faceva assolutamente
        // nulla. Se il DownloadManager non e' disponibile si ripiega sul browser,
        // che scarica l'APK in ogni caso.
        val dm = try {
            context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        } catch (_: Exception) { null }

        if (dm == null) {
            openInBrowser(context, apkUrl)
            return
        }

        android.widget.Toast.makeText(context, "Download in corso…", android.widget.Toast.LENGTH_SHORT).show()

        val downloadId = try {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(apkUrl))
                    .setTitle("Family Radar — aggiornamento")
                    .setDescription("Tocca per installare al termine del download")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setMimeType("application/vnd.android.package-archive")
                    .setDestinationInExternalFilesDir(
                        context,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                    )
            )
        } catch (e: Exception) {
            Log.w("AppUpdater", "DownloadManager.enqueue fallito: ${e.message}")
            openInBrowser(context, apkUrl)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                runCatching { ctx.unregisterReceiver(this) }

                val file = resolveDownloadedFile(dm, id)
                if (file == null) {
                    Log.w("AppUpdater", "APK non disponibile dopo il download: apro il browser")
                    openInBrowser(ctx, apkUrl)
                    return
                }

                // Android 10+: startActivity() da BroadcastReceiver (background) è bloccato.
                // Si usa la notifica come unico punto di ingresso: l'utente la tocca
                // e il PendingIntent parte in foreground senza restrizioni.
                showInstallNotification(ctx, file)
            }
        }

        runCatching {
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
    }

    /**
     * Dove il DownloadManager ha scritto davvero, e solo se ha finito bene.
     *
     * Restituisce null se il download e' fallito o se il file non c'e': in quel
     * caso non va offerta l'installazione, perche' toccare la notifica aprirebbe
     * l'installer su un percorso vuoto e non accadrebbe niente.
     */
    private fun resolveDownloadedFile(dm: DownloadManager, id: Long): File? = try {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null

            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                Log.w("AppUpdater", "Download non riuscito: status=$status reason=$reason")
                return null
            }

            val localUri = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                ?: return null
            val path = Uri.parse(localUri).path ?: return null
            File(path).takeIf { it.exists() && it.length() > 0L }
        }
    } catch (e: Exception) {
        Log.w("AppUpdater", "resolveDownloadedFile fallito: ${e.message}")
        null
    }

    /**
     * Ripulisce gli APK scaricati in precedenza. Con il nome univoco per download
     * si accumulerebbero: sono decine di MB a testa nella cartella dell'app.
     */
    private fun purgeOldDownloads(context: Context) {
        runCatching {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.listFiles { f -> f.name.startsWith("family-radar-update") && f.name.endsWith(".apk") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    /** Apre l'URL dell'APK nel browser: fallback quando il DownloadManager non c'e'. */
    private fun openInBrowser(context: Context, apkUrl: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w("AppUpdater", "openInBrowser fallito: ${it.message}") }
    }

    /** Lancia la schermata di installazione del pacchetto scaricato. Ritorna true se avviato. */
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
