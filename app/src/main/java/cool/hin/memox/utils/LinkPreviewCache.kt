package cool.hin.memox.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches and caches link previews (page <title> + favicon) so a [LinkCardSpan] can render an
 * AnyType-style card. Previews are cached on disk under [Context.getCacheDir]/link_previews and
 * also kept in an in-memory map to avoid repeated network hits.
 *
 * Network failures are swallowed: the card simply falls back to a domain + URL placeholder.
 */
object LinkPreviewCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memCache = mutableMapOf<String, LinkPreview?>()

    private fun hash(url: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun dir(ctx: Context) =
        File(ctx.cacheDir, "link_previews").apply { if (!exists()) mkdirs() }

    fun getCached(ctx: Context, url: String): LinkPreview? {
        memCache[url]?.let { return it }
        val meta = File(dir(ctx), "${hash(url)}.json")
        if (meta.exists()) {
            runCatching {
                val j = JSONObject(meta.readText())
                val p = LinkPreview(
                    j.optString("title").takeIf { it.isNotEmpty() },
                    j.optString("favicon").takeIf { it.isNotEmpty() },
                )
                memCache[url] = p
                return p
            }
        }
        return null
    }

    fun fetch(ctx: Context, url: String, onUpdated: (LinkPreview?) -> Unit) {
        memCache[url]?.let { onUpdated(it); return }
        scope.launch {
            val result = runCatching { doFetch(ctx.applicationContext, url) }.getOrElse { null }
            memCache[url] = result
            withContext(Dispatchers.Main) { onUpdated(result) }
        }
    }

    private fun doFetch(ctx: Context, url: String): LinkPreview? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "memoX/1.0")
            setRequestProperty("Accept", "*/*")
        }
        conn.connect()
        val html = conn.inputStream.bufferedReader().use { it.readText() }.take(250_000)
        conn.disconnect()

        val title = Regex("(?is)<title[^>]*>(.*?)</title>")
            .find(html)?.groupValues?.get(1)
            ?.replace(Regex("(?is)<[^>]+>"), "")
            ?.trim()?.take(120)

        val favUrl = resolveFavicon(url, html)
        var favPath: String? = null
        if (favUrl != null) {
            runCatching {
                val fc = (URL(favUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "memoX/1.0")
                }
                fc.connect()
                fc.inputStream.use { ins ->
                    val bmp = BitmapFactory.decodeStream(ins)
                    if (bmp != null) {
                        val f = File(dir(ctx), "${hash(url)}.png")
                        f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                        favPath = f.absolutePath
                    }
                }
                fc.disconnect()
            }
        }

        val preview = LinkPreview(title, favPath)
        runCatching {
            File(dir(ctx), "${hash(url)}.json").writeText(
                JSONObject().put("title", title ?: "").put("favicon", favPath ?: "").toString(),
            )
        }
        return preview
    }

    private fun resolveFavicon(pageUrl: String, html: String): String? {
        Regex("(?is)<link[^>]+rel=[\"']?(?:shortcut )?icon[\"']?[^>]*href=[\"']([^\"']+)[\"']")
            .findAll(html)
            .forEach { m ->
                m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return absolutize(pageUrl, it) }
            }
        return absolutize(pageUrl, "/favicon.ico")
    }

    private fun absolutize(base: String, rel: String): String? = runCatching {
        if (rel.startsWith("http")) return rel
        val u = URL(base)
        when {
            rel.startsWith("//") -> "${u.protocol}:$rel"
            rel.startsWith("/") -> "${u.protocol}://${u.host}$rel"
            else -> "${u.protocol}://${u.host}/${rel.trimStart('/')}"
        }
    }.getOrNull()
}
