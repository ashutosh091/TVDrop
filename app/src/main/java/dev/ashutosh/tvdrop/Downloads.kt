package dev.ashutosh.tvdrop

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Files on the box: in-flight downloads/uploads plus whatever already sits in the folder. */
object Downloads {
    enum class Status { DOWNLOADING, UPLOADING, DONE, FAILED }

    class Item(name: String, @Volatile var status: Status) {
        @Volatile var name = name
        @Volatile var done = 0L
        @Volatile var total = -1L
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        val file get() = File(dir, name)
        val isApk get() = name.endsWith(".apk", ignoreCase = true)
        val busy get() = status == Status.DOWNLOADING || status == Status.UPLOADING
        fun percent(): Int = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    }

    lateinit var dir: File
    private val active = LinkedHashMap<String, Item>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var lastNotify = 0L

    fun init(context: Context) {
        dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)

    private fun changed(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 300) return
        lastNotify = now
        listeners.forEach { it() }
    }

    /** In-flight first, then finished files newest first. */
    @Synchronized
    fun list(): List<Item> {
        val items = active.values.toMutableList()
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.name !in active }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { f -> items += Item(f.name, Status.DONE).apply { done = f.length(); total = f.length() } }
        return items
    }

    @Synchronized
    fun get(name: String): Item? =
        active[name] ?: File(dir, name).takeIf { it.isFile }
            ?.let { f -> Item(f.name, Status.DONE).apply { done = f.length(); total = f.length() } }

    @Synchronized
    fun delete(name: String): Boolean {
        active.remove(name)?.cancelled = true
        val ok = File(dir, name).delete()
        File(dir, "$name.part").delete()
        changed()
        return ok
    }

    fun cancel(name: String) {
        synchronized(this) { active[name] }?.cancelled = true
    }

    fun download(url: String): Item {
        val item = Item(nameFromUrl(url), Status.DOWNLOADING)
        begin(item)
        thread(isDaemon = true, name = "download") {
            runCatching {
                val conn = connect(url)
                item.total = conn.contentLengthLong
                // Prefer the name the server sends (e.g. GitHub release redirects).
                conn.getHeaderField("Content-Disposition")
                    ?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
                    ?.let { rename(item, sanitize(URLDecoder.decode(it.trim(), "UTF-8"))) }
                conn.inputStream.use { copy(it, item, item.total) }
            }.onFailure { fail(item, it) }
        }
        return item
    }

    /** Body of a PUT from the web page, exactly [length] bytes. */
    fun receive(name: String, input: InputStream, length: Long): Item {
        val item = Item(sanitize(name), Status.UPLOADING).apply { total = length }
        begin(item)
        runCatching { copy(input, item, length) }.onFailure { fail(item, it) }
        return item
    }

    @Synchronized
    private fun begin(item: Item) {
        // A new transfer of the same name replaces a stale or failed one.
        active.remove(item.name)?.cancelled = true
        active[item.name] = item
        changed()
    }

    @Synchronized
    private fun rename(item: Item, name: String) {
        if (name == item.name || name.isBlank()) return
        active.remove(item.name)
        item.name = name
        active[name] = item
    }

    private fun copy(input: InputStream, item: Item, limit: Long) {
        val part = File(dir, "${item.name}.part")
        val buf = ByteArray(64 * 1024)
        part.outputStream().use { out ->
            var remaining = if (limit >= 0) limit else Long.MAX_VALUE
            while (remaining > 0) {
                if (item.cancelled) throw IOException("cancelled")
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
                item.done += n
                changed(force = false)
            }
            if (limit >= 0 && remaining > 0) throw IOException("connection closed at ${item.done} of $limit bytes")
        }
        val target = item.file
        target.delete()
        if (!part.renameTo(target)) throw IOException("could not save ${target.name}")
        item.status = Status.DONE
        synchronized(this) { if (active[item.name] === item) active.remove(item.name) }
        changed()
    }

    private fun fail(item: Item, e: Throwable) {
        File(dir, "${item.name}.part").delete()
        synchronized(this) {
            if (item.cancelled) active.remove(item.name)
            else {
                item.status = Status.FAILED
                item.error = e.message ?: e.javaClass.simpleName
            }
        }
        changed()
    }

    private fun connect(start: String): HttpURLConnection {
        var url = start
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TVDrop/1.0")
            }
            val code = c.responseCode
            when {
                code in 300..399 -> {
                    val location = c.getHeaderField("Location") ?: throw IOException("HTTP $code without Location")
                    url = URL(URL(url), location).toString()
                    c.disconnect()
                }
                code >= 400 -> throw IOException("HTTP $code")
                else -> return c
            }
        }
        throw IOException("too many redirects")
    }

    private fun nameFromUrl(url: String): String =
        sanitize(runCatching { URLDecoder.decode(URL(url).path.substringAfterLast('/'), "UTF-8") }.getOrDefault(""))

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trimStart('.').take(120)
        return clean.ifBlank { "download-${System.currentTimeMillis() / 1000}" }
    }

    fun human(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1048576.0)
    }
}
