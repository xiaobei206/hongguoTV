package com.xiaobei206.hongguotv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
 * 开心果 CoverStore loading model, adapted to RecyclerView ImageViews:
 * independent poster loads, one shared request per URL, six IO workers,
 * a 96 MiB bitmap LRU, and scaled JPEG files on disk.
 *
 * TV/CDN URL fallbacks remain for decoder compatibility. There are no preview
 * requests or speculative prefetches outside the cards bound by RecyclerView.
 */
class TvArtworkLoader(context: Context) {
    companion object {
        private const val MAX_EDGE = 600
    }

    private val dir = File(context.applicationContext.cacheDir, "tv-covers").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(96 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val loading = HashMap<String, Deferred<Bitmap?>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val io = Dispatchers.IO.limitedParallelism(6)
    private val viewJobs = WeakHashMap<ImageView, Job>()

    /** Return the cached JPEG when present, for phone handoff. */
    fun cachedJpeg(raw: String): ByteArray? {
        if (raw.isBlank()) return null
        val first = candidateUrls(raw).firstOrNull() ?: return null
        val cached = file(key(first))
        return if (cached.isFile) runCatching { cached.readBytes() }.getOrNull() else null
    }

    /** Each ImageView requests its own poster; identical URLs share one download. */
    fun load(raw: String, image: ImageView, placeholder: Int = 0xff243449.toInt()) {
        if (raw.isBlank()) {
            cancel(image)
            image.setImageDrawable(null)
            image.setBackgroundColor(placeholder)
            return
        }
        val candidates = candidateUrls(raw)
        val first = candidates.firstOrNull()
        if (first == null) {
            cancel(image)
            image.setImageDrawable(null)
            image.setBackgroundColor(placeholder)
            return
        }
        val k = key(first)
        if (image.tag == k && viewJobs[image]?.isActive == true) return

        cancel(image)
        image.tag = k
        image.setImageDrawable(null)
        image.setBackgroundColor(placeholder)
        memory.get(k)?.let {
            image.setImageBitmap(it)
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val bitmap = try {
                image(candidates)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (bitmap != null && image.tag == k) image.setImageBitmap(bitmap)
        }
        viewJobs[image] = job
        job.start()
    }

    /** Cancel this view's waiter; the shared image task continues for other cards. */
    fun cancel(image: ImageView) {
        viewJobs.remove(image)?.cancel()
        image.tag = null
    }

    private suspend fun image(candidates: List<String>): Bitmap? {
        val first = candidates.firstOrNull() ?: return null
        val k = key(first)
        memory.get(k)?.let { return it }

        val task = synchronized(loading) {
            val existing = loading[k]
            if (existing != null && (existing.isActive || existing.isCompleted)) {
                existing
            } else {
                val request = scope.async(io, start = CoroutineStart.LAZY) {
                    loadFromDiskOrNetwork(k, candidates)
                }
                loading[k] = request
                request.invokeOnCompletion {
                    synchronized(loading) {
                        if (loading[k] === request) loading.remove(k)
                    }
                }
                request.start()
                request
            }
        }
        val bitmap = try {
            task.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) memory.put(k, bitmap)
        return bitmap
    }

    private fun loadFromDiskOrNetwork(k: String, candidates: List<String>): Bitmap? {
        val cached = file(k)
        if (cached.isFile) {
            BitmapFactory.decodeFile(cached.path)?.let { return it }
            cached.delete()
        }
        for (candidate in candidates) {
            val bytes = download(candidate) ?: continue
            val bitmap = decode(bytes) ?: continue
            save(bitmap, cached)
            return bitmap
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = MAX_EDGE.toDouble() / max(info.size.width, info.size.height)
                    if (scale < 1.0) {
                        decoder.setTargetSize(
                            (info.size.width * scale).roundToInt().coerceAtLeast(1),
                            (info.size.height * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            }
        } catch (_: OutOfMemoryError) {
            memory.evictAll()
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun download(raw: String): ByteArray? {
        val connection = runCatching { URL(raw).openConnection() as HttpURLConnection }.getOrNull() ?: return null
        return try {
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/*,*/*;q=0.8")
            connection.setRequestProperty("User-Agent", "hongguoTV/" + BuildConfig.VERSION_NAME)
            if (connection.responseCode != 200) null else connection.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun save(bitmap: Bitmap, target: File) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            val success = FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (success && !temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            temp.delete()
        } catch (_: Exception) {
            temp.delete()
        }
    }

    private fun file(key: String) = File(dir, "$key.jpg")

    private fun candidateUrls(raw: String): List<String> {
        val source = raw.trim()
        if (source.isEmpty()) return emptyList()
        val first = if (source.startsWith("http://", true)) {
            "https://" + source.substringAfter("://")
        } else source
        val uri = runCatching { URI(first) }.getOrNull()
        val host = uri?.host.orEmpty().lowercase()
        val path = uri?.path.orEmpty()
        val result = LinkedHashSet<String>()

        // Prefer a decoder-friendly JPEG rendition for older TV devices.
        if ((host.endsWith(".fqnovelpic.com") || host.endsWith(".byteimg.com")) &&
            (path.contains("/novel-pic/") || path.contains("/novel-static/"))) {
            val segment = if (path.contains("/novel-pic/")) "/novel-pic/" else "/novel-static/"
            val id = path.substringAfter(segment).substringBefore("~").substringBefore(".")
            if (id.matches(Regex("[a-fA-F0-9]{32}"))) {
                result.add("https://p3-novel.byteimg.com/img" + segment + id + "~tplv-noop.jpeg")
            }
        }

        if ((host.endsWith(".byteimg.com") || host.endsWith(".fqnovelpic.com")) &&
            path.contains("~tplv-obj.image")) {
            val legacy = path.replace("~tplv-obj.image", "~tplv-noop.jpeg")
            if (uri != null) {
                result.add(URI(uri.scheme, uri.authority, legacy, uri.query, null).toASCIIString())
            }
        }

        // Keep the server URL as a fallback for sources without a JPEG variant.
        result.add(first)
        if (source.startsWith("http://", true)) result.add(source)
        return result.filter {
            it.startsWith("https://", true) || it.startsWith("http://", true)
        }.distinct()
    }

    /** Match 开心果: identify a cover by host + path, ignoring volatile query signatures. */
    private fun key(raw: String): String {
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        val name = if (uri?.host != null) uri.host + uri.path.orEmpty() else raw.substringBefore("?")
        return MessageDigest.getInstance("SHA-1").digest(name.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
