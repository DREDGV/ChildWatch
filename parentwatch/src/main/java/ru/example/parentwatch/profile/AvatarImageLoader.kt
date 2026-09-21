package ru.example.parentwatch.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.example.parentwatch.utils.ServerUrlResolver
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shows a profile picture the person uploaded to the server.
 *
 * A picture chosen from the family is stored in the profile as a path on the
 * server, such as `/avatars/ab12cd….jpg`, and not as an address. Drawing it
 * therefore needs two things the built-in presets never did: the address of the
 * server, and a fetch from the network, which must not happen on the main thread
 * because a list row is bound while the user is scrolling.
 *
 * This is deliberately a small loader rather than an image library. The child
 * application has no image library (Glide is declared in the parent application
 * only), and adding one to every install of a monitoring application for a
 * single picture is a poor trade. What is needed here is exactly: decode off the
 * main thread, keep a bounded memory and disk cache so a scrolling list does not
 * download the same picture again, and never touch a view that has already been
 * given a different person.
 *
 * Nothing here holds an Activity: only the application context is kept, and a
 * result is applied to a view only while that view still asks for that picture.
 */
object AvatarImageLoader {

    private const val TAG = "AvatarImageLoader"

    /** The directory the server serves uploaded pictures from. */
    private const val UPLOADED_AVATAR_PREFIX = "/avatars/"

    /** Only a few small pictures are on screen at once; three megabytes is ample. */
    private const val MEMORY_CACHE_BYTES = 3 * 1024 * 1024

    /** A stored value names one unchanging file, so the cache is bounded by size. */
    private const val DISK_CACHE_MAX_BYTES = 6L * 1024 * 1024

    /**
     * How long a downloaded picture is kept.
     *
     * There is nothing to expire for correctness — the file name changes whenever
     * a person changes their picture — but this stops pictures of people who are
     * no longer in the family from staying on the phone forever.
     */
    private const val DISK_CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * How long a failed address is left alone.
     *
     * Without this, every rebind of a list of people would start a fresh request
     * while the phone is offline, which is exactly when that hurts most.
     */
    private const val FAILURE_COOLDOWN_MS = 30_000L

    /** Downloading happens off the main thread; four at a time is plenty. */
    private val ioExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "avatar-image-io").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * A plain HTTP client.
     *
     * Uploaded pictures are served as static files and need no token. Using the
     * application's authenticated client would attach a token to fetching an
     * image and turn a refusal into a token refresh.
     */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Decoded pictures, by the address they came from. */
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Which address each view is waiting for. */
    private val pendingByView = IdentityHashMap<ImageView, String>()

    /** Which views are waiting for each address, so one fetch serves a whole list. */
    private val pendingViewsByUrl = mutableMapOf<String, MutableList<ImageView>>()

    /** Addresses that failed recently, with the time of the failure. */
    private val recentFailures = ConcurrentHashMap<String, Long>()

    /** The application cache directory, taken once so an Activity is never kept. */
    @Volatile
    private var applicationCacheDir: File? = null

    private val cacheDirectoryReady = AtomicBoolean(false)

    /**
     * Draws the uploaded picture stored in [avatarValue], if it is one.
     *
     * @param onResult called on the main thread with the decoded picture, or with
     *        null when it cannot be shown — while it is still loading, when the
     *        phone is offline, or when the picture is no longer on the server.
     *        The caller draws its letter avatar in that case, so a row is never
     *        left empty.
     */
    fun load(view: ImageView, avatarValue: String?, onResult: (Bitmap?) -> Unit) {
        val url = resolveUrl(view.context, avatarValue)
        if (url == null) {
            forget(view)
            onResult(null)
            return
        }

        memoryCache.get(url)?.let { bitmap ->
            forget(view)
            onResult(bitmap)
            return
        }

        val failedAt = recentFailures[url]
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) {
            forget(view)
            onResult(null)
            return
        }

        // register() also answers whether this view already waits for this
        // address, so a rebind of the same row does not queue a second fetch.
        if (!register(view, url)) {
            if (!isStillWanted(view, url)) onResult(null)
            return
        }

        val context = view.context.applicationContext
        ioExecutor.execute {
            val bitmap = readFromCache(context, url) ?: download(context, url)
            mainHandler.post {
                val waiting = unregister(url)
                if (bitmap != null && memoryCache.get(url) == null) {
                    memoryCache.put(url, bitmap)
                } else if (bitmap == null) {
                    recentFailures[url] = System.currentTimeMillis()
                }
                waiting.forEach { waitingView ->
                    // A recycled row must never receive somebody else's picture.
                    if (isStillWanted(waitingView, url)) {
                        if (bitmap != null) waitingView.setImageBitmap(bitmap) else onResult(null)
                    }
                }
            }
        }
    }

    /**
     * Whether a stored value is a picture on the server.
     *
     * The value is a path, never an address, so that the server can move without
     * breaking the picture of every family member.
     */
    fun isUploadedPicture(avatarValue: String?): Boolean =
        avatarValue?.trim().orEmpty().startsWith(UPLOADED_AVATAR_PREFIX)

    /** The address a stored value is fetched from, or null when it is not one of these pictures. */
    fun resolveUrl(context: Context, avatarValue: String?): String? {
        if (!isUploadedPicture(avatarValue)) return null
        val path = avatarValue!!.trim()
        val serverUrl = runCatching {
            ServerUrlResolver.getServerUrl(context.applicationContext)
        }.getOrNull()
        if (serverUrl.isNullOrBlank()) return null
        return serverUrl.trimEnd('/') + path
    }

    /**
     * The decoded picture for a stored value, when it is already cached.
     *
     * Callers that draw on the main thread and cannot wait for a fetch use this;
     * it never touches the network, so it stays fast enough for a list row or a
     * map marker.
     */
    fun cachedBitmap(context: Context, avatarValue: String?): Bitmap? {
        val url = resolveUrl(context, avatarValue) ?: return null
        return memoryCache.get(url)
    }

    /**
     * Fetches pictures into the cache without showing them.
     *
     * A map draws its markers from a drawable, which cannot wait for a network
     * answer, so a picture has to be cached before the map is opened. This is the
     * same fetch made early, from values the family directory already holds.
     */
    fun preload(context: Context, avatarValues: Collection<String?>) {
        val applicationContext = context.applicationContext
        avatarValues
            .mapNotNull { resolveUrl(applicationContext, it) }
            .distinct()
            .forEach { url ->
                if (memoryCache.get(url) != null) return@forEach
                if (!registerPlaceholder(url)) return@forEach
                ioExecutor.execute {
                    val bitmap = readFromCache(applicationContext, url)
                        ?: download(applicationContext, url)
                    mainHandler.post {
                        if (bitmap != null) memoryCache.put(url, bitmap)
                        else recentFailures[url] = System.currentTimeMillis()
                        unregisterPlaceholder(url)
                    }
                }
            }
    }

    /** Drops a picture from both caches, for example after the person replaced it. */
    fun invalidate(context: Context, avatarValue: String?) {
        val url = resolveUrl(context, avatarValue) ?: return
        memoryCache.remove(url)
        recentFailures.remove(url)
        runCatching { diskCacheFile(context, url).takeIf { it.isFile }?.delete() }
    }

    /**
     * Forgets this view.
     *
     * A view being recycled must stop being a target, otherwise a slow fetch
     * would draw the previous person's picture into the row that has just been
     * handed to somebody else.
     */
    fun forget(view: ImageView) {
        synchronized(pendingByView) {
            val url = pendingByView.remove(view) ?: return
            pendingViewsByUrl[url]?.remove(view)
            if (pendingViewsByUrl[url]?.isEmpty() == true) pendingViewsByUrl.remove(url)
        }
    }

    // ==================== keeping track of waiting views ====================

    /** @return true when this call owns the fetch for [url]. */
    private fun register(view: ImageView, url: String): Boolean = synchronized(pendingByView) {
        val previous = pendingByView.remove(view)
        if (previous != null) {
            pendingViewsByUrl[previous]?.remove(view)
            if (pendingViewsByUrl[previous]?.isEmpty() == true) pendingViewsByUrl.remove(previous)
        } else if (pendingViewsByUrl.containsKey(url)) {
            // The same row was bound again with the same picture; leave it alone.
            return false
        }
        val waiting = pendingViewsByUrl[url]
        if (waiting != null) {
            // A fetch for this address is already running; join it instead of
            // downloading the same picture once per row.
            pendingByView[view] = url
            waiting += view
            return false
        }
        pendingByView[view] = url
        pendingViewsByUrl[url] = mutableListOf(view)
        return true
    }

    private fun registerPlaceholder(url: String): Boolean = synchronized(pendingByView) {
        if (pendingViewsByUrl.containsKey(url)) return false
        pendingViewsByUrl[url] = mutableListOf()
        return true
    }

    private fun unregister(url: String): List<ImageView> = synchronized(pendingByView) {
        val waiting = pendingViewsByUrl.remove(url).orEmpty()
        waiting.forEach { pendingByView.remove(it) }
        waiting
    }

    private fun unregisterPlaceholder(url: String) = synchronized(pendingByView) {
        pendingViewsByUrl.remove(url)
    }

    private fun isStillWanted(view: ImageView, url: String): Boolean =
        synchronized(pendingByView) { pendingByView[view] == url }

    // ==================== reading bytes ====================

    private fun download(context: Context, url: String): Bitmap? {
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Picture not available: http=${response.code} $url")
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) return null
                writeToCache(context, url, bytes)
                decode(bytes)
            }
        } catch (error: IOException) {
            Log.d(TAG, "Could not fetch picture: ${error.message}")
            null
        } catch (error: Exception) {
            Log.w(TAG, "Could not read picture", error)
            null
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()

    // ==================== disk cache ====================

    /**
     * The directory downloaded pictures are kept in.
     *
     * Taken from the application context and remembered: a view holds an Activity
     * context, and keeping that in a cache keyed by address would outlive the
     * screen it came from.
     */
    private fun cacheDir(context: Context): File {
        val root = applicationCacheDir ?: context.applicationContext.cacheDir.also {
            applicationCacheDir = it
        }
        return File(root, "avatars").apply {
            if (cacheDirectoryReady.compareAndSet(false, true)) {
                mkdirs()
                prune(this)
            }
        }
    }

    private fun diskCacheFile(context: Context, url: String): File =
        File(cacheDir(context), "avatar-" + url.hashCode().toLong().and(0xffffffffL).toString(16))

    private fun readFromCache(context: Context, url: String): Bitmap? {
        val file = diskCacheFile(context, url)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) {
            runCatching { file.delete() }
            return null
        }
        file.setLastModified(System.currentTimeMillis())
        return decode(bytes)
    }

    private fun writeToCache(context: Context, url: String, bytes: ByteArray) {
        runCatching {
            val file = diskCacheFile(context, url)
            file.writeBytes(bytes)
            trimToLimit(file.parentFile ?: return)
        }.onFailure { Log.d(TAG, "Could not cache picture on disk: ${it.message}") }
    }

    /** Removes expired pictures, then the least recently used ones until it fits. */
    private fun prune(directory: File) {
        val files = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrNull()
        if (files.isNullOrEmpty()) return
        val expiry = System.currentTimeMillis() - DISK_CACHE_MAX_AGE_MS
        files.filter { it.isFile && it.lastModified() < expiry }.forEach { it.delete() }
        trimToLimit(directory)
    }

    private fun trimToLimit(directory: File) {
        val files = runCatching { directory.listFiles()?.filter { it.isFile }.orEmpty() }
            .getOrNull()
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_MAX_BYTES) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= DISK_CACHE_MAX_BYTES) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }
}
