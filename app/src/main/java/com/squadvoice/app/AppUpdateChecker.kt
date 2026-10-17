package com.squadvoice.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LatestApkRelease(
    val version: String,
    val downloadUrl: String,
)

object AppUpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/alok4041/voiceover/releases/latest"
    private const val LATEST_RELEASE_PAGE_URL =
        "https://github.com/alok4041/voiceover/releases/latest"
    private const val MAX_APK_SIZE_BYTES = 250L * 1024L * 1024L
    private const val UPDATE_MIME_TYPE = "application/vnd.android.package-archive"

    suspend fun fetchLatestApk(): LatestApkRelease = withContext(Dispatchers.IO) {
        try {
            fetchLatestApkFromApi()
        } catch (apiError: Exception) {
            try {
                fetchLatestApkFromReleasePage()
            } catch (pageError: Exception) {
                throw IllegalStateException(
                    "GitHub update check failed: ${apiError.message ?: "API unavailable"}. " +
                        "Release page fallback failed: ${pageError.message ?: "page unavailable"}.",
                    apiError,
                )
            }
        }
    }

    private fun fetchLatestApkFromApi(): LatestApkRelease {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "SquadVoice-Android")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = if (status == HttpURLConnection.HTTP_FORBIDDEN) " (possibly rate limited)" else ""
                throw IllegalStateException("GitHub returned HTTP $status$detail")
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

    private fun fetchLatestApkFromReleasePage(): LatestApkRelease {
        val connection = URL(LATEST_RELEASE_PAGE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "SquadVoice-Android")

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("GitHub release page returned HTTP $status")
            }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val asset = Regex(
                """/alok4041/voiceover/releases/download/([^/?#"]+)/([^/?#"]+\.apk)(?:[?#"]|$)""",
                RegexOption.IGNORE_CASE,
            ).find(html) ?: throw IllegalStateException("The latest GitHub release page has no APK asset.")
            val tag = asset.groupValues[1]
            val filename = asset.groupValues[2]
            val version = tag.removePrefix("v").takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("The latest release has no version tag.")
            return LatestApkRelease(
                version = version,
                downloadUrl = "https://github.com/alok4041/voiceover/releases/download/" +
                    "${Uri.encode(tag)}/${Uri.encode(filename)}",
            )
        } finally {
            connection.disconnect()
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun findDownloadedApk(context: Context, release: LatestApkRelease): File? {
        val file = updateFile(context, release.version)
        return file.takeIf { it.isFile && isValidApk(context, it, release.version) }
    }

    suspend fun downloadApk(
        context: Context,
        release: LatestApkRelease,
        onProgress: (Int?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val downloadUri = Uri.parse(release.downloadUrl)
        if (downloadUri.scheme != "https" || downloadUri.host != "github.com") {
            throw IllegalStateException("The update link is not a trusted GitHub HTTPS link.")
        }

        val directory = File(context.cacheDir, "updates")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Couldn't prepare temporary storage for the update.")
        }

        val destination = updateFile(context, release.version)
        if (destination.isFile && isValidApk(context, destination, release.version)) {
            withContext(Dispatchers.Main) { onProgress(100) }
            return@withContext destination
        }
        destination.delete()

        val partial = File(directory, "${destination.name}.part")
        partial.delete()
        val connection = URL(release.downloadUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "SquadVoice-Android")
            connection.setRequestProperty("Accept", "application/octet-stream")

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("APK download failed (HTTP $status).")
            }

            val expectedBytes = connection.contentLengthLong
            if (expectedBytes > MAX_APK_SIZE_BYTES) {
                throw IllegalStateException("The APK is larger than the allowed download size.")
            }

            var downloadedBytes = 0L
            var lastProgressAt = 0L
            connection.inputStream.buffered().use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        if (downloadedBytes > MAX_APK_SIZE_BYTES) {
                            throw IllegalStateException("The APK is larger than the allowed download size.")
                        }
                        output.write(buffer, 0, count)

                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt >= 250) {
                            val progress = if (expectedBytes > 0L) {
                                ((downloadedBytes * 100L) / expectedBytes).toInt().coerceIn(0, 99)
                            } else {
                                null
                            }
                            withContext(Dispatchers.Main) { onProgress(progress) }
                            lastProgressAt = now
                        }
                    }
                }
            }

            if (downloadedBytes == 0L || (expectedBytes > 0L && downloadedBytes != expectedBytes)) {
                throw IllegalStateException("The APK download was incomplete. Try again.")
            }
            if (!isValidApk(context, partial, release.version)) {
                throw IllegalStateException("The downloaded file isn't a compatible SquadVoice update.")
            }
            if (!partial.renameTo(destination)) {
                throw IllegalStateException("Couldn't save the downloaded update.")
            }

            directory.listFiles()
                ?.filter { it.name.startsWith("squadvoice-update-") && it != destination }
                ?.forEach(File::delete)
            withContext(Dispatchers.Main) { onProgress(100) }
            destination
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun openApkInstaller(context: Context, apk: File) {
        if (!apk.isFile) throw IllegalStateException("The downloaded update is no longer available. Download it again.")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, UPDATE_MIME_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    private fun updateFile(context: Context, version: String): File {
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.cacheDir, "updates"), "squadvoice-update-$safeVersion.apk")
    }

    private fun isValidApk(context: Context, apk: File, expectedVersion: String): Boolean {
        if (!apk.isFile || apk.length() <= 0L || apk.length() > MAX_APK_SIZE_BYTES) return false
        val packageInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return packageInfo.packageName == context.packageName &&
            packageInfo.versionName?.removePrefix("v") == expectedVersion.removePrefix("v") &&
            archiveVersionCode > BuildConfig.VERSION_CODE.toLong()
    }
}
