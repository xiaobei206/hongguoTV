package com.xiaobei206.hongguotv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class TvUpdateRelease(
    val version: String,
    val tag: String,
    val apkName: String,
    val apkUrl: String,
    val checksumUrl: String,
    val notes: String,
    val pageUrl: String,
)

sealed class TvUpdateCheck {
    data object UpToDate : TvUpdateCheck()
    data class Available(val release: TvUpdateRelease) : TvUpdateCheck()
    data class Failed(val message: String) : TvUpdateCheck()
}

/**
 * Release-based TV updater. It never silently replaces the installed APK: Android's
 * package installer still needs the user's confirmation on ordinary consumer TVs.
 */
class TvUpdateManager(context: Context) {
    companion object {
        private const val TAG = "HongguoTVUpdate"
        private const val RELEASE_API = "https://api.github.com/repos/xiaobei206/hongguoTV/releases/latest"
        private const val PREFS = "tv_update_preferences"
        private const val LAST_AUTO_CHECK = "last_auto_check"
        private const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        private const val MAX_APK_BYTES = 120L * 1024 * 1024
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - prefs.getLong(LAST_AUTO_CHECK, 0L) >= AUTO_CHECK_INTERVAL_MS

    fun markAutoCheck(now: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(LAST_AUTO_CHECK, now).apply()
    }

    suspend fun check(): TvUpdateCheck = withContext(Dispatchers.IO) {
        try {
            val json = requestText(RELEASE_API, MAX_METADATA_BYTES)
            val release = JSONObject(json)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                return@withContext TvUpdateCheck.Failed("最新 Release 暂不可用")
            }
            val tag = release.optString("tag_name").takeIf { it.isNotBlank() }
                ?: return@withContext TvUpdateCheck.Failed("Release 没有版本标签")
            val version = tag.removePrefix("v")
            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                return@withContext TvUpdateCheck.UpToDate
            }
            val assets = release.optJSONArray("assets")
                ?: return@withContext TvUpdateCheck.Failed("Release 没有安装包附件")
            val apkName = "hongguoTV-v$version-universal.apk"
            val sumName = "hongguoTV-v$version-SHA256SUMS.txt"
            var apkUrl = ""
            var checksumUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                when (asset.optString("name")) {
                    apkName -> apkUrl = asset.optString("browser_download_url")
                    sumName -> checksumUrl = asset.optString("browser_download_url")
                }
            }
            if (apkUrl.isBlank() || checksumUrl.isBlank()) {
                return@withContext TvUpdateCheck.Failed("Release 附件不完整，找不到 APK 或 SHA-256 校验文件")
            }
            TvUpdateCheck.Available(
                TvUpdateRelease(
                    version = version,
                    tag = tag,
                    apkName = apkName,
                    apkUrl = apkUrl,
                    checksumUrl = checksumUrl,
                    notes = release.optString("body"),
                    pageUrl = release.optString("html_url"),
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "release check failed", e)
            TvUpdateCheck.Failed(e.message ?: "网络请求失败")
        }
    }

    suspend fun downloadAndVerify(release: TvUpdateRelease): File = withContext(Dispatchers.IO) {
        val checksumText = requestText(release.checksumUrl, 32 * 1024)
        val expected = checksumText.lineSequence()
            .map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            .firstOrNull { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?.lowercase()
            ?: throw IOException("Release 的 SHA-256 文件格式无效")

        val folder = File(appContext.cacheDir, "updates").apply { mkdirs() }
        val target = File(folder, release.apkName)
        if (target.isFile && target.length() > 0L && sha256(target).equals(expected, ignoreCase = true)) {
            return@withContext target
        }
        target.delete()
        val temp = File(folder, release.apkName + ".download")
        temp.delete()
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 18_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "hongguoTV/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("APK 下载 HTTP $code")
            if (connection.contentLength.toLong() > MAX_APK_BYTES) throw IOException("APK 超过大小限制")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_APK_BYTES) throw IOException("APK 超过大小限制")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(expected, ignoreCase = true)) {
                temp.delete()
                throw IOException("SHA-256 校验失败，已删除下载文件")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (e: Exception) {
            temp.delete()
            Log.w(TAG, "APK download failed version=${release.version}", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun requestText(url: String, limit: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 7_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "hongguoTV/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json,text/plain,*/*")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > limit) throw IOException("响应超过大小限制")
                    out.write(buffer, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun compareVersions(left: String, right: String): Int {
        val a = left.removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = right.removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
