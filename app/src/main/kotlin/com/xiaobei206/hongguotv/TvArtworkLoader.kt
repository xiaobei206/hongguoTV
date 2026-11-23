package com.xiaobei206.hongguotv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Bounded artwork loader for older Android TV devices.
 *
 * - Two concurrent image/network jobs, separated from catalog and playback work.
 * - Shared in-flight requests prevent the same poster being downloaded for every rebind.
 * - Recycled views cancel their waiter and cannot receive stale images.
 * - HTTP CDN URLs are upgraded to HTTPS first; legacy TV-friendly JPEG renditions are tried
 *   for the ByteDance novel-image CDN, with the original URL retained as a compatibility fallback.
 * - Small decoded bitmaps, bounded memory/disk caches, and detailed but URL-token-safe logs.
 */
class TvArtworkLoader(context: Context) {
    companion object {
        private const val TAG = "HongguoTVArtwork"
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_DISK_BYTES = 64L * 1024 * 1024
        private const val MAX_EDGE = 420
    }

    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "tv-covers").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2))
    private val requestLock = Any()
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val viewJobs = WeakHashMap<ImageView, Job>()
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun cachedJpeg(raw: String): ByteArray? {
        if (raw.isBlank()) return null
        val k = key(candidateUrls(raw).first())
        val f = file(k)
        return if (f.isFile && f.length() <= MAX_FILE_BYTES) {
            runCatching { f.readBytes() }.getOrNull()
        } else null
    }

    fun load(raw: String, image: ImageView, placeholder: Int = 0xff243449.toInt()) {
        if (raw.isBlank()) {
            cancel(image)
            image.setImageDrawable(null)
            image.setBackgroundColor(placeholder)
            return
        }

        val candidates = candidateUrls(raw)
        val k = key(candidates.first())
        val sameRequestRunning = synchronized(viewJobs) {
            image.tag == k && viewJobs[image]?.isActive == true
        }
        if (sameRequestRunning) return
        cancel(image)
        image.tag = k
        image.setImageDrawable(null)
        image.setBackgroundColor(placeholder)
        memory.get(k)?.let {
            image.setImageBitmap(it)
            return
        }

        val job = scope.launch {
            val request = synchronized(requestLock) {
                inFlight[k]?.takeUnless { it.isCancelled } ?: scope.async(start = CoroutineStart.LAZY) {
                    getBitmap(k, candidates)
                }.also {
                    inFlight[k] = it
                    it.start()
                }
            }
            val bitmap = try {
                request.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "poster request failed key=$k type=${e.javaClass.simpleName}", e)
                null
            } finally {
                synchronized(requestLock) {
                    if (inFlight[k] === request && request.isCompleted) inFlight.remove(k)
                }
            }
            withContext(Dispatchers.Main) {
                if (image.tag == k) {
                    if (bitmap != null) image.setImageBitmap(bitmap)
                    else Log.w(TAG, "poster unavailable key=$k candidates=${candidates.size}")
                }
            }
        }
        synchronized(viewJobs) { viewJobs[image] = job }
    }

    /** Call when RecyclerView releases a holder. */
    fun cancel(image: ImageView) {
        val old = synchronized(viewJobs) { viewJobs.remove(image) }
        old?.cancel()
        image.tag = null
    }

    private fun getBitmap(k: String, candidates: List<String>): Bitmap? {
        memory.get(k)?.let { return it }
        val cached = file(k)
        if (cached.isFile) {
            runCatching { cached.readBytes() }.getOrNull()?.let { bytes ->
                decode(bytes, "disk")?.let {
                    memory.put(k, it)
                    return it
                }
            }
            cached.delete()
        }

        for (candidate in candidates) {
            val bytes = download(candidate) ?: continue
            val bitmap = decode(bytes, URL(candidate).host) ?: continue
            memory.put(k, bitmap)
            save(bitmap, cached)
            trim()
            return bitmap
        }
        return null
    }

    @Suppress("NewApi")
    private fun decode(bytes: ByteArray, source: String): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = minOf(1.0, MAX_EDGE.toDouble() / max(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.w(TAG, "unsupported image source=$source bytes=${bytes.size}")
                    return null
                }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            }
            if (bitmap == null) Log.w(TAG, "decoder returned null source=$source bytes=${bytes.size}")
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "poster decode ran out of memory source=$source bytes=${bytes.size}")
            memory.evictAll()
            null
        } catch (e: Exception) {
            Log.w(TAG, "poster decode failed source=$source bytes=${bytes.size} type=${e.javaClass.simpleName}")
            null
        }
    }

    private fun download(raw: String): ByteArray? {
        val connection = runCatching { URL(raw).openConnection() as HttpURLConnection }.getOrElse {
            Log.w(TAG, "invalid poster URL", it)
            return null
        }
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/avif,image/*;q=0.8,*/*;q=0.5")
            connection.setRequestProperty("User-Agent", "hongguoTV/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "poster HTTP=$code host=${connection.url.host} type=$type")
                return null
            }
            if (connection.contentLength.toLong() > MAX_FILE_BYTES) {
                Log.w(TAG, "poster too large host=${connection.url.host} bytes=${connection.contentLength}")
                return null
            }
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_FILE_BYTES) {
                        Log.w(TAG, "poster exceeded size cap host=${connection.url.host}")
                        return null
                    }
                    out.write(buffer, 0, n)
                }
                if (!type.startsWith("image/", true) && type.isNotBlank()) {
                    Log.i(TAG, "poster content-type=$type host=${connection.url.host}; trying decoder")
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Log.w(TAG, "poster download failed host=${runCatching { URL(raw).host }.getOrDefault("unknown")} type=${e.javaClass.simpleName}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun save(bitmap: Bitmap, target: File) {
        val temp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            if (!temp.renameTo(target)) {
                temp.delete()
                Log.w(TAG, "poster cache replace failed")
            }
        }.onFailure {
            temp.delete()
            Log.w(TAG, "poster cache write failed", it)
        }
    }

    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (item in files) {
            if (total <= MAX_DISK_BYTES) break
            total -= item.length()
            item.delete()
        }
    }

    private fun file(key: String) = File(dir, "$key.jpg")

    private fun candidateUrls(raw: String): List<String> {
        val source = raw.trim()
        if (source.isEmpty()) return emptyList()
        val first = if (source.startsWith("http://", true)) "https://" + source.substringAfter("://") else source
        val result = LinkedHashSet<String>()
        result.add(first)
        val uri = runCatching { URI(first) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val path = uri?.path.orEmpty()
        if ((host.endsWith(".fqnovelpic.com") || host.endsWith(".byteimg.com")) &&
            (path.contains("/novel-pic/") || path.contains("/novel-static/"))) {
            val segment = if (path.contains("/novel-pic/")) "/novel-pic/" else "/novel-static/"
            val id = path.substringAfter(segment).substringBefore("~").substringBefore(".")
            if (id.matches(Regex("[a-fA-F0-9]{32}"))) {
                result.add("https://p3-novel.byteimg.com/img$segment$id~tplv-noop.jpeg")
                result.add("https://p3-novel.byteimg.com/img$segment$id~tplv-shrink:48:0.jpeg")
            } else {
                val legacy = Regex("~tplv-[^/?]+").replace(path, "~tplv-noop.jpeg")
                if (legacy != path && uri != null) {
                    result.add(URI(uri.scheme, uri.authority, legacy, uri.query, null).toASCIIString())
                }
            }
        } else if ((host.endsWith(".byteimg.com") || host.endsWith(".fqnovelpic.com")) &&
            path.contains("~tplv-obj.image")) {
            val legacy = path.replace("~tplv-obj.image", "~tplv-noop.jpeg")
            if (uri != null) result.add(URI(uri.scheme, uri.authority, legacy, uri.query, null).toASCIIString())
        }
        // A few older sources genuinely only serve HTTP. Try that last, after HTTPS.
        if (source.startsWith("http://", true)) result.add(source)
        return result.filter { it.startsWith("https://", true) || it.startsWith("http://", true) }
            .distinct()
    }

    private fun key(raw: String): String {
        // Keep query parameters in the key: some CDNs use them to select a rendition or signature.
        val stable = runCatching {
            val uri = URI(raw)
            URI(uri.scheme, uri.authority, uri.path, uri.query, null).toASCIIString()
        }.getOrDefault(raw)
        return MessageDigest.getInstance("SHA-1").digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
