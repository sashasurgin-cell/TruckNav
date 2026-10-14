package com.trucknav.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val tagName: String,
    val releaseUrl: String,
    val apkUrl: String
)

suspend fun checkForTruckNavUpdate(currentVersionCode: Int): AppUpdateInfo? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL("https://api.github.com/repos/sashasurgin-cell/TruckNav/releases/latest")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "TruckNav-Android")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        if (connection.responseCode !in 200..299) return@withContext null
        val release = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val tag = release.optString("tag_name")
        val versionCode = tag.removePrefix("v").toIntOrNull() ?: return@withContext null
        if (versionCode <= currentVersionCode) return@withContext null
        val assets = release.optJSONArray("assets") ?: return@withContext null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                if (apkUrl != null) break
            }
        }
        val finalApkUrl = apkUrl ?: return@withContext null
        AppUpdateInfo(tag, release.optString("html_url"), finalApkUrl)
    } catch (_: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}
