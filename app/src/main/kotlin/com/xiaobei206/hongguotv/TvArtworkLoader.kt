package com.xiaobei206.hongguotv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * TV poster pipeline with bounded memory/disk caches and separate preview/full-image lanes.
 *
 * - 96 MiB bitmap LRU and 256 MiB disk LRU.
 * - Six full-image workers plus a separate two-request preview lane.
 * - Same-URL downloads are shared; recycled ImageViews cannot receive stale results.
 * - Nearby cards are prefetched, but only one background prefetch may occupy a full-image lane.
 * - ByteDance novel posters show a small preview first, then replace it with the full image.
 */
class TvArtworkLoader(context: Context) {
    companion object {
        private const val TAG = "HongguoTVArtwork"
        private const val MAX_FILE_BYTES = 4L * 1024 * 1024
        private const val MAX_DISK_BYTES = 256L * 1024 * 1024
        private const val MAX_EDGE = 600
        private const val PREVIEW_EDGE = 120
        private const val MAX_PREFETCH_IN_FLIGHT = 18
    }

    private val appContext = context.applicationContext
    private val dir = File(appContext.cacheDir, "tv-covers").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val previewDispatcher = Dispatchers.IO.limitedParallelism(2)
    // Match the six-way full-image pipeline used by 开心果; previews use a separate small lane.
    private val imageDispatcher = Dispatchers.IO.limitedParallelism(6)
    private val requestLock = Any()
    private val diskLock = Any()
    /** Maintained under diskLock so normal writes do not rescan the entire cache directory. */
    private var diskBytesEstimate: Long? = null
    private val inFlight = HashMap<String, Deferred<Bitmap?>>()
    private val previewInFlight = HashMap<String, Deferred<Bitmap?>>()
    /** Only the latest viewport's pending prefetches are retained. */
    private val prefetchQueue = LinkedHashMap<String, List<String>>()
    private var prefetchPump: Job? = null
    private var activePrefetchKey: String? = null
    private val viewJobs = WeakHashMap<ImageView, Job>()
    private val fullDisplayed = WeakHashMap<ImageView, Boolean>()
    private val memory = object : LruCache<String, Bitmap>(96 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    /** Return the cached full-size JPEG, if it exists, for the phone handoff path. */
    fun cachedJpeg(raw: String): ByteArray? {
        if (raw.isBlank()) return null
        val first = candidateUrls(raw).firstOrNull() ?: return null
        val cached = file(key(first))
        return synchronized(diskLock) {
            if (cached.isFile && cached.length() > 0L && cached.length() <= MAX_FILE_BYTES) {
                runCatching { cached.readBytes() }.getOrNull()?.also {
                    cached.setLastModified(System.currentTimeMillis())
                }
            } else {
                if (cached.isFile && cached.length() > MAX_FILE_BYTES) cached.delete()
                null
            }
        }
    }

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
        val sameRequestRunning = synchronized(viewJobs) {
            image.tag == k && viewJobs[image]?.isActive == true
        }
        if (sameRequestRunning) return

        cancel(image)
        image.tag = k
        image.setImageDrawable(null)
        image.setBackgroundColor(placeholder)
        memory.get(k)?.let {
            synchronized(viewJobs) {
                if (image.tag == k) {
                    fullDisplayed[image] = true
                    image.setImageBitmap(it)
                }
            }
            return
        }

        val job = scope.launch {
            // Start the full request immediately so a slow preview never delays the final image.
            val fullRequest = requestFull(k, candidates)
            val hasFullDiskFile = synchronized(diskLock) {
                file(k).let { it.isFile && it.length() > 0L && it.length() <= MAX_FILE_BYTES }
            }
            val previewRequest = if (!hasFullDiskFile) {
                previewUrl(first)?.let { requestPreview(k, it) }
            } else null

            if (previewRequest != null) {
                scope.launch {
                    val preview = try {
                        previewRequest.await()
                    } catch (cancelled: CancellationException) {
                        return@launch
                    } catch (e: Exception) {
                        Log.d(TAG, "preview unavailable key=${k} type=${e.javaClass.simpleName}")
                        null
                    }
                    if (preview != null) displayIfCurrent(image, k, preview, full = false)
                }
            }

            val bitmap = try {
                fullRequest.await()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.w(TAG, "poster request failed key=${k} type=${e.javaClass.simpleName}", e)
                null
            }
            if (bitmap != null) {
                displayIfCurrent(image, k, bitmap, full = true)
            } else {
                Log.w(TAG, "poster unavailable key=${k} candidates=${candidates.size}")
            }
        }
        synchronized(viewJobs) { viewJobs[image] = job }
    }

    /** Replace pending work with the posters immediately ahead of the current viewport. */
    fun preload(raw: String) = preloadRange(listOf(raw))

    fun preloadRange(raws: List<String>) {
        val desired = LinkedHashMap<String, List<String>>()
        for (raw in raws) {
            if (raw.isBlank()) continue
            val candidates = candidateUrls(raw)
            val first = candidates.firstOrNull() ?: continue
            val k = key(first)
            if (desired.containsKey(k) || memory.get(k) != null) continue

            val cached = file(k)
            val diskHit = synchronized(diskLock) {
                if (cached.isFile && cached.length() > 0L && cached.length() <= MAX_FILE_BYTES) {
                    cached.setLastModified(System.currentTimeMillis())
                    true
                } else {
                    false
                }
            }
            if (!diskHit) desired[k] = candidates
            if (desired.size >= MAX_PREFETCH_IN_FLIGHT) break
        }

        synchronized(requestLock) {
            // Discard stale queued URLs on every scroll update. One already-started
            // background request may finish, but old rows cannot fill the queue.
            prefetchQueue.clear()
            for ((k, candidates) in desired) {
                if (k == activePrefetchKey || inFlight[k]?.isActive == true) continue
                prefetchQueue[k] = candidates
                if (prefetchQueue.size >= MAX_PREFETCH_IN_FLIGHT) break
            }
            if (prefetchQueue.isNotEmpty() && prefetchPump?.isActive != true) {
                val pump = scope.launch(start = CoroutineStart.LAZY) { drainPrefetchQueue() }
                prefetchPump = pump
                pump.start()
            }
        }
    }

    private suspend fun drainPrefetchQueue() {
        val thisJob = currentCoroutineContext()[Job]
        try {
            while (true) {
                val next = synchronized(requestLock) {
                    val entry = prefetchQueue.entries.firstOrNull()
                    if (entry == null) {
                        if (prefetchPump === thisJob) prefetchPump = null
                        null
                    } else {
                        prefetchQueue.remove(entry.key)
                        activePrefetchKey = entry.key
                        entry.key to entry.value
                    }
                } ?: return

                val (k, candidates) = next
                try {
                    if (memory.get(k) == null) {
                        val cached = file(k)
                        val diskHit = synchronized(diskLock) {
                            if (cached.isFile && cached.length() > 0L && cached.length() <= MAX_FILE_BYTES) {
                                cached.setLastModified(System.currentTimeMillis())
                                true
                            } else false
                        }
                        if (!diskHit) requestFull(k, candidates).await()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    Log.d(TAG, "poster prefetch failed key=${k} type=${e.javaClass.simpleName}")
                } finally {
                    synchronized(requestLock) {
                        if (activePrefetchKey == k) activePrefetchKey = null
                    }
                }
            }
        } finally {
            synchronized(requestLock) {
                if (prefetchPump === thisJob) prefetchPump = null
                // Recover if cancellation happened after a new viewport queued work.
                if (prefetchPump == null && prefetchQueue.isNotEmpty()) {
                    val pump = scope.launch(start = CoroutineStart.LAZY) { drainPrefetchQueue() }
                    prefetchPump = pump
                    pump.start()
                }
            }
        }
    }

    /** Call when RecyclerView releases a holder. Shared downloads keep running for other waiters. */
    fun cancel(image: ImageView) {
        val old = synchronized(viewJobs) {
            fullDisplayed.remove(image)
            viewJobs.remove(image)
        }
        old?.cancel()
        image.tag = null
    }

    private fun displayIfCurrent(image: ImageView, k: String, bitmap: Bitmap, full: Boolean) {
        synchronized(viewJobs) {
            if (image.tag != k) return
            if (!full && fullDisplayed[image] == true) return
            if (full) fullDisplayed[image] = true
            image.setImageBitmap(bitmap)
        }
    }

    private fun requestFull(k: String, candidates: List<String>): Deferred<Bitmap?> =
        synchronized(requestLock) {
            val active = inFlight[k]
            if (active?.isActive == true) {
                active
            } else {
                val request = scope.async(imageDispatcher, start = CoroutineStart.LAZY) {
                    getBitmap(k, candidates)
                }
                inFlight[k] = request
                request.invokeOnCompletion {
                    synchronized(requestLock) {
                        if (inFlight[k] === request) inFlight.remove(k)
                    }
                }
                request.start()
                request
            }
        }

    private fun requestPreview(k: String, url: String): Deferred<Bitmap?> =
        synchronized(requestLock) {
            val active = previewInFlight[k]
            if (active?.isActive == true) {
                active
            } else {
                val request = scope.async(previewDispatcher, start = CoroutineStart.LAZY) {
                    getPreviewBitmap(k, url)
                }
                previewInFlight[k] = request
                request.invokeOnCompletion {
                    synchronized(requestLock) {
                        if (previewInFlight[k] === request) previewInFlight.remove(k)
                    }
                }
                request.start()
                request
            }
        }

    private fun getBitmap(k: String, candidates: List<String>): Bitmap? {
        memory.get(k)?.let { return it }
        val cached = file(k)
        if (cached.isFile) {
            val bytes = synchronized(diskLock) {
                if (cached.length() > 0L && cached.length() <= MAX_FILE_BYTES) {
                    runCatching { cached.readBytes() }.getOrNull()
                } else null
            }
            if (bytes != null) {
                decode(bytes, "disk")?.let {
                    touch(cached)
                    memory.put(k, it)
                    return it
                }
            }
            synchronized(diskLock) { cached.delete() }
        }

        for (candidate in candidates) {
            val bytes = download(candidate) ?: continue
            val bitmap = decode(bytes, runCatching { URL(candidate).host }.getOrDefault("unknown")) ?: continue
            memory.put(k, bitmap)
            save(bitmap, cached, 84)
            return bitmap
        }
        return null
    }

    private fun getPreviewBitmap(k: String, url: String): Bitmap? {
        val memoryKey = previewMemoryKey(k)
        memory.get(memoryKey)?.let { return it }
        val cached = previewFile(k)
        if (cached.isFile) {
            val bytes = synchronized(diskLock) {
                if (cached.length() > 0L && cached.length() <= MAX_FILE_BYTES) {
                    runCatching { cached.readBytes() }.getOrNull()
                } else null
            }
            if (bytes != null) {
                decode(bytes, "preview-disk", PREVIEW_EDGE)?.let {
                    touch(cached)
                    memory.put(memoryKey, it)
                    return it
                }
            }
            synchronized(diskLock) { cached.delete() }
        }

        val bytes = download(url) ?: return null
        val bitmap = decode(bytes, "preview", PREVIEW_EDGE) ?: return null
        memory.put(memoryKey, bitmap)
        save(bitmap, cached, 76)
        return bitmap
    }

    @Suppress("NewApi")
    private fun decode(bytes: ByteArray, source: String, targetEdge: Int = MAX_EDGE): Bitmap? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val scale = minOf(1.0, targetEdge.toDouble() / max(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM)
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    Log.w(TAG, "unsupported image source=${source} bytes=${bytes.size}")
                    return null
                }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > targetEdge) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                })
            }
            if (bitmap == null) Log.w(TAG, "decoder returned null source=${source} bytes=${bytes.size}")
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "poster decode ran out of memory source=${source} bytes=${bytes.size}")
            memory.evictAll()
            null
        } catch (e: Exception) {
            Log.w(TAG, "poster decode failed source=${source} bytes=${bytes.size} type=${e.javaClass.simpleName}")
            null
        }
    }

    private fun download(raw: String): ByteArray? {
        val connection = runCatching { URL(raw).openConnection() as HttpURLConnection }.getOrElse {
            Log.w(TAG, "invalid poster URL", it)
            return null
        }
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/avif,image/*;q=0.8,*/*;q=0.5")
            connection.setRequestProperty("User-Agent", "hongguoTV/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            if (code !in 200..299) {
                Log.w(TAG, "poster HTTP=${code} host=${connection.url.host} type=${type}")
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
                    Log.i(TAG, "poster content-type=${type} host=${connection.url.host}; trying decoder")
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

    private fun save(bitmap: Bitmap, target: File, quality: Int) {
        val temp = File(target.parentFile, target.name + ".tmp")
        try {
            // JPEG encoding can be CPU-heavy. Do not hold the global cache lock while encoding;
            // otherwise every other image's disk read waits behind this one file.
            val compressed = FileOutputStream(temp).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
            }
            if (!compressed) {
                temp.delete()
                Log.w(TAG, "poster cache compression returned false")
                return
            }

            val saved = synchronized(diskLock) {
                val beforeTotal = diskBytesEstimate ?: (dir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                    ?.sumOf { it.length() } ?: 0L)
                val oldLength = if (target.isFile) target.length() else 0L
                var moved = temp.renameTo(target)
                if (!moved) {
                    target.delete()
                    moved = temp.renameTo(target)
                }
                if (moved) {
                    diskBytesEstimate = (beforeTotal - oldLength + target.length()).coerceAtLeast(0L)
                    true
                } else {
                    diskBytesEstimate = (beforeTotal - oldLength).coerceAtLeast(0L)
                    temp.delete()
                    Log.w(TAG, "poster cache replace failed")
                    false
                }
            }
            if (saved) trim()
        } catch (e: Exception) {
            synchronized(diskLock) { temp.delete() }
            Log.w(TAG, "poster cache write failed", e)
        }
    }

    private fun touch(target: File) {
        synchronized(diskLock) {
            if (target.isFile) target.setLastModified(System.currentTimeMillis())
        }
    }

    private fun trim() {
        synchronized(diskLock) {
            val estimated = diskBytesEstimate ?: (dir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.sumOf { it.length() } ?: return)
            diskBytesEstimate = estimated
            // Most writes are well under the cap. Avoid a full directory scan/sort for each poster.
            if (estimated <= MAX_DISK_BYTES) return

            val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (item in files) {
                if (total <= MAX_DISK_BYTES) break
                total -= item.length()
                item.delete()
            }
            diskBytesEstimate = total.coerceAtLeast(0L)
        }
    }

    private fun file(key: String) = File(dir, "$key.jpg")
    private fun previewFile(key: String) = File(dir, "$key.preview.jpg")
    private fun previewMemoryKey(key: String) = "$key#preview"

    /** A preview rendition is currently supported only by the novel-image CDN. */
    private fun previewUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.host.equals("p3-novel.byteimg.com", ignoreCase = true)) return null
        val path = uri.path ?: return null
        if (!path.startsWith("/img/novel-pic/")) return null
        val base = path.substringBefore("~")
        val id = base.substringAfterLast('/').substringBefore('.')
        if (!id.matches(Regex("[a-fA-F0-9]{32}"))) return null
        return "https://p3-novel.byteimg.com" + base + "~tplv-shrink:${PREVIEW_EDGE}:0.jpeg"
    }

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

    private fun key(raw: String): String {
        // Keep query parameters: some CDNs use them to select a rendition or signature.
        val stable = runCatching {
            val uri = URI(raw)
            URI(uri.scheme, uri.authority, uri.path, uri.query, null).toASCIIString()
        }.getOrDefault(raw)
        return MessageDigest.getInstance("SHA-1").digest(stable.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}