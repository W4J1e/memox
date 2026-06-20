package cool.hin.memox.data.sync.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lightweight WebDAV client using OkHttp.
 * Supports: PROPFIND, GET, PUT, MKCOL, DELETE, HEAD
 */
class WebDavClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
) {

    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    private val authHeader: String by lazy { Credentials.basic(username, password) }

    private fun buildUrl(path: String): String {
        val base = serverUrl.trimEnd('/')
        val relativePath = path.trimStart('/')
        return "$base/$relativePath"
    }

    private fun buildRequest(method: String, path: String, body: String? = null): Request {
        val url = buildUrl(path)
        val builder =
            Request.Builder()
                .url(url)
                .header("Authorization", authHeader)
                .method(method, body?.toRequestBody())

        if (method == "PROPFIND") {
            builder.header("Depth", "1")
            builder.header("Content-Type", "application/xml; charset=utf-8")
        } else if (method == "PUT") {
            builder.header("Content-Type", "application/octet-stream")
        } else if (method == "MKCOL") {
            builder.header("Content-Type", "application/xml; charset=utf-8")
        }

        return builder.build()
    }

    /** Test connection by doing PROPFIND on the root path */
    fun testConnection(): Result<String> {
        return try {
            val request = buildRequest("PROPFIND", "")
            val response = httpClient.newCall(request).execute()
            when {
                response.isSuccessful || response.code == 207 -> Result.success("OK")
                response.code == 401 -> Result.failure(IOException("Authentication failed"))
                response.code == 404 -> Result.failure(IOException("Directory not found"))
                else ->
                    Result.failure(
                        IOException("Server returned ${response.code}: ${response.message}")
                    )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Create a directory (MKCOL) */
    fun createDirectory(path: String): Result<Unit> {
        return try {
            val request = buildRequest("MKCOL", path)
            val response = httpClient.newCall(request).execute()
            when {
                response.isSuccessful || response.code == 201 -> Result.success(Unit)
                response.code == 405 -> Result.success(Unit) // Already exists
                else ->
                    Result.failure(
                        IOException("MKCOL failed: ${response.code} ${response.message}")
                    )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Check if a file/directory exists (HEAD) */
    fun exists(path: String): Boolean {
        return try {
            val request = buildRequest("HEAD", path)
            val response = httpClient.newCall(request).execute()
            response.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    /** Upload a file (PUT) */
    fun upload(path: String, data: ByteArray): Result<Unit> {
        return try {
            val url = buildUrl(path)
            val body = data.toRequestBody("application/octet-stream".toMediaType())
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/octet-stream")
                    .put(body)
                    .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 201 || response.code == 204) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Upload failed: ${response.code} ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Upload from InputStream */
    fun upload(path: String, inputStream: InputStream, contentLength: Long = -1): Result<Unit> {
        return try {
            val url = buildUrl(path)
            val body =
                inputStream.use { stream ->
                    val bytes = stream.readBytes()
                    bytes.toRequestBody("application/octet-stream".toMediaType())
                }
            val request =
                Request.Builder()
                    .url(url)
                    .header("Authorization", authHeader)
                    .header("Content-Type", "application/octet-stream")
                    .put(body)
                    .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 201 || response.code == 204) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Upload failed: ${response.code} ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Download a file (GET), returns byte array */
    fun download(path: String): Result<ByteArray> {
        return try {
            val request = buildRequest("GET", path)
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val bytes = response.body?.bytes() ?: ByteArray(0)
                Result.success(bytes)
            } else {
                Result.failure(IOException("Download failed: ${response.code} ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Delete a file or directory (DELETE) */
    fun delete(path: String): Result<Unit> {
        return try {
            val request = buildRequest("DELETE", path)
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 204) {
                Result.success(Unit)
            } else {
                Result.failure(IOException("Delete failed: ${response.code} ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** List files in a directory (PROPFIND) */
    fun listFiles(path: String): Result<List<WebDavFile>> {
        return try {
            val propfindBody =
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                    <d:prop>
                        <d:getlastmodified/>
                        <d:getcontentlength/>
                        <d:resourcetype/>
                    </d:prop>
                </d:propfind>
                """
                    .trimIndent()

            val request = buildRequest("PROPFIND", path, propfindBody)
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 207) {
                val xml = response.body?.string() ?: ""
                val files = parseMultiStatus(xml, path)
                Result.success(files)
            } else {
                Result.failure(
                    IOException("PROPFIND failed: ${response.code} ${response.message}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseMultiStatus(xml: String, basePath: String): List<WebDavFile> {
        val files = mutableListOf<WebDavFile>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc: Document = builder.parse(xml.byteInputStream())

            val responses: NodeList =
                doc.getElementsByTagNameNS("DAV:", "response") ?: return files

            val basePathNormalized = basePath.trimEnd('/')

            for (i in 0 until responses.length) {
                val responseElem = responses.item(i) as Element
                val hrefElem =
                    responseElem.getElementsByTagNameNS("DAV:", "href").item(0) ?: continue
                val href = hrefElem.textContent ?: continue

                // Decode URL path
                val decodedPath = java.net.URLDecoder.decode(href, "UTF-8")

                // Skip the directory itself
                val decodedNormalized = decodedPath.trimEnd('/')
                if (decodedNormalized.endsWith(basePathNormalized)) continue

                // Extract filename
                val fileName = decodedNormalized.substringAfterLast('/')

                // Check if it's a directory
                val resourcetypeElem =
                    responseElem.getElementsByTagNameNS("DAV:", "resourcetype").item(0)
                        as? Element
                val isDirectory =
                    resourcetypeElem?.getElementsByTagNameNS("DAV:", "collection")?.length ?: 0 > 0

                // Get last modified
                val lastModifiedElem =
                    responseElem.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)
                val lastModified = lastModifiedElem?.textContent

                // Get content length
                val contentLengthElem =
                    responseElem.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)
                val contentLength = contentLengthElem?.textContent?.toLongOrNull() ?: 0L

                files.add(
                    WebDavFile(
                        name = fileName,
                        path = decodedNormalized,
                        isDirectory = isDirectory,
                        lastModified = lastModified,
                        size = contentLength,
                    )
                )
            }
        } catch (_: Exception) {
            // Parse error, return empty
        }
        return files
    }
}

data class WebDavFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val lastModified: String?,
    val size: Long,
)
