package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

object AppUpdater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/giuseppeformisano/family-radar/releases/tags/latest-debug"

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
                        val downloadUrl = asset.getString("browser_download_url")
                        val vBody = client.newCall(
                            Request.Builder().url(downloadUrl).build()
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

    fun openDownload(context: Context, apkUrl: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
