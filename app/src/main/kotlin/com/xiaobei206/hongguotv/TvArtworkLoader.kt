package com.xiaobei206.hongguotv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import kotlin.math.max
import kotlin.math.roundToInt

/** Bounded poster loader: small memory LRU, capped disk cache and three concurrent requests. */
class TvArtworkLoader(context: Context) {
    private val dir = File(context.applicationContext.cacheDir, "tv-covers").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(3)
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun cachedJpeg(raw: String): ByteArray? {
        if (raw.isBlank()) return null
        val f = file(key(raw))
        return if (f.isFile && f.length() <= MAX_FILE_BYTES) runCatching { f.readBytes() }.getOrNull() else null
    }

    fun load(raw: String, image: ImageView, placeholder: Int = 0xff243449.toInt()) {
        if (raw.isBlank()) {
            image.setImageDrawable(null)
            image.setBackgroundColor(placeholder)
            image.tag = null
            return
        }
        val k = key(raw)
        image.tag = k
        image.setBackgroundColor(placeholder)
        memory.get(k)?.let { image.setImageBitmap(it); return }
        scope.launch {
            permits.acquire()
            val bitmap = try { getBitmap(k, raw) } finally { permits.release() }
            withContext(Dispatchers.Main) {
                if (image.tag == k && bitmap != null) image.setImageBitmap(bitmap)
            }
        }
    }

    private fun getBitmap(k: String, raw: String): Bitmap? {
        memory.get(k)?.let { return it }
        val cached = file(k)
        if (cached.isFile) {
            decode(cached.readBytes())?.let { memory.put(k, it); return it }
            cached.delete()
        }
        val bytes = download(raw) ?: return null
        val bitmap = decode(bytes) ?: return null
        memory.put(k, bitmap)
        save(bitmap, cached)
        trim()
        return bitmap
    }

    @Suppress("NewApi")
    private fun decode(bytes: ByteArray): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = minOf(1.0, 420.0 / max(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > 420) sample *= 2
                BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.RGB_565
                    },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun download(raw: String): ByteArray? {
        val connection = runCatching { URL(raw).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "image/webp,image/jpeg,image/png,image/*,*/*")
            connection.setRequestProperty("User-Agent", "hongguoTV/1.0.0")
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) return null
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }

    private fun save(bitmap: Bitmap, target: File) {
        val temp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (!temp.renameTo(target)) temp.delete()
        }.onFailure { temp.delete() }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (item in files) {
            if (total <= MAX_DISK_BYTES) break
            total -= item.length()
            item.delete()
        }
    }

    private fun file(key: String) = File(dir, "$key.jpg")
    private fun key(raw: String): String {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val stable = if (uri?.host != null) uri.host + uri.path.orEmpty() else raw
        return MessageDigest.getInstance("SHA-1").digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_DISK_BYTES = 64L * 1024 * 1024
    }
}
