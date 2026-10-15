package com.squadvoice.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class LatestApkRelease(
    val version: String,
    val downloadUrl: String,
)

object AppUpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/alok4041/voiceover/releases/latest"

    suspend fun fetchLatestApk(): LatestApkRelease = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "SquadVoice-Android")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("GitHub returned HTTP $status")
            }

            val release = JSONObject(responseText)
            val tag = release.optString("tag_name").trim()
            val version = tag.removePrefix("v")
            if (version.isBlank()) throw IllegalStateException("The latest release has no version tag.")

            val assets = release.optJSONArray("assets")
                ?: throw IllegalStateException("The latest release has no APK asset.")
            val apk = (0 until assets.length())
                .asSequence()
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IllegalStateException("The latest release has no APK asset.")
            val downloadUrl = apk.optString("browser_download_url").trim()
            if (downloadUrl.isBlank()) throw IllegalStateException("The APK download link is missing.")

            LatestApkRelease(version = version, downloadUrl = downloadUrl)
        } finally {
            connection.disconnect()
        }
    }
}
