package cool.hin.memox.data.sync.onedrive

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Microsoft Graph API client for OneDrive, using the path-based DriveItem API.
 *
 * All paths are relative to the user's OneDrive root, e.g. `memoX/notes/foo.json`.
 * Path-based PUT/GET auto-create parent folders, so explicit folder creation is
 * only needed to pre-create empty directories before listing.
 *
 * Large files (>= 4 MB) are uploaded via an upload session in 5 MB chunks.
 */
class OneDriveClient(private val context: Context) {

    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    private val authHelper = OneDriveAuthHelper

    private val baseUrl = "https://graph.microsoft.com/v1.0/me/drive/root:"

    /** Returns a valid access token, or null if the user is not signed in / refresh failed. */
    private suspend fun token(): String? = authHelper.getValidAccessToken(context)

    /** Test connection: fetch the drive root to confirm the token works. */
    suspend fun testConnection(): Result<String> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        return try {
            val request =
                Request.Builder()
                    .url("https://graph.microsoft.com/v1.0/me/drive")
                    .header("Authorization", "Bearer $token")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success("OK")
                } else {
                    Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a folder at the given path (e.g. `memoX/notes`). Parent folders are created
     * automatically by Graph. A 409 (already exists) is treated as success.
     */
    suspend fun createDirectory(path: String): Result<Unit> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        val (parentPath, name) = splitParent(path)
        return try {
            val parentUrl = if (parentPath.isEmpty()) {
                "https://graph.microsoft.com/v1.0/me/drive/root/children"
            } else {
                "$baseUrl${encodePath(parentPath)}:/children"
            }
            val body = JSONObject().apply {
                put("name", name)
                put("folder", JSONObject())
                put("@microsoft.graph.conflictBehavior", "fail")
            }.toString()
            val request =
                Request.Builder()
                    .url(parentUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 409) {
                    Result.success(Unit)
                } else {
                    val msg = parseError(response.body?.string())
                    Result.failure(IOException("Create folder failed: ${response.code} $msg"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Check whether a file or folder exists at the given path. */
    suspend fun exists(path: String): Boolean {
        val token = token() ?: return false
        return try {
            val request =
                Request.Builder()
                    .url("$baseUrl${encodePath(path)}")
                    .header("Authorization", "Bearer $token")
                    .build()
            httpClient.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** Upload a file (small files < 4 MB use simple PUT; larger use an upload session). */
    suspend fun upload(path: String, data: ByteArray): Result<Unit> {
        return if (data.size < MAX_SIMPLE_UPLOAD) {
            uploadSimple(path, data)
        } else {
            uploadSession(path, data)
        }
    }

    private suspend fun uploadSimple(path: String, data: ByteArray): Result<Unit> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        return try {
            val request =
                Request.Builder()
                    .url("$baseUrl${encodePath(path)}:/content")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/octet-stream")
                    .put(data.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("Upload failed: ${response.code} ${parseError(response.body?.string())}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Upload a large file (> 4 MB) via a resumable upload session in 5 MB chunks. */
    private suspend fun uploadSession(path: String, data: ByteArray): Result<Unit> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        return try {
            // 1. Create the upload session
            val sessionBody = JSONObject().apply {
                put("@microsoft.graph.conflictBehavior", "replace")
                put("name", path.substringAfterLast('/'))
            }.toString()
            val sessionRequest =
                Request.Builder()
                    .url("$baseUrl${encodePath(path)}:/createUploadSession")
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .post(sessionBody.toRequestBody("application/json".toMediaType()))
                    .build()
            val uploadUrl: String = httpClient.newCall(sessionRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("Create upload session failed: ${response.code}"))
                }
                JSONObject(response.body?.string() ?: "{}").getString("uploadUrl")
            }

            // 2. Upload chunks
            val chunkSize = 5 * 1024 * 1024 // 5 MB
            var offset = 0
            while (offset < data.size) {
                val end = minOf(offset + chunkSize, data.size)
                val chunk = data.copyOfRange(offset, end)
                val rangeHeader = "bytes $offset-${end - 1}/${data.size}"
                val chunkRequest =
                    Request.Builder()
                        .url(uploadUrl)
                        .header("Content-Range", rangeHeader)
                        .put(chunk.toRequestBody("application/octet-stream".toMediaType()))
                        .build()
                httpClient.newCall(chunkRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        return Result.failure(IOException("Chunk upload failed at $rangeHeader: ${response.code}"))
                    }
                }
                offset = end
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Download a file as a byte array. */
    suspend fun download(path: String): Result<ByteArray> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        return try {
            val request =
                Request.Builder()
                    .url("$baseUrl${encodePath(path)}:/content")
                    .header("Authorization", "Bearer $token")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(response.body?.bytes() ?: ByteArray(0))
                } else {
                    Result.failure(IOException("Download failed: ${response.code} ${parseError(response.body?.string())}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Delete a file or folder at the given path. A 404 is treated as success. */
    suspend fun delete(path: String): Result<Unit> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        return try {
            val request =
                Request.Builder()
                    .url("$baseUrl${encodePath(path)}")
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 204 || response.code == 404) {
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("Delete failed: ${response.code} ${parseError(response.body?.string())}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** List the direct children of a folder path. */
    suspend fun listFiles(path: String): Result<List<OneDriveFile>> {
        val token = token() ?: return Result.failure(IOException("Not signed in"))
        return try {
            val request =
                Request.Builder()
                    .url("$baseUrl${encodePath(path)}:/children")
                    .header("Authorization", "Bearer $token")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val array = json.optJSONArray("value") ?: return@use Result.success(emptyList())
                    val files = mutableListOf<OneDriveFile>()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val name = item.optString("name")
                        val isFolder = item.has("folder")
                        val size = item.optLong("size", 0)
                        files.add(
                            OneDriveFile(
                                name = name,
                                path = "$path/$name",
                                isDirectory = isFolder,
                                size = size,
                            )
                        )
                    }
                    Result.success(files)
                } else {
                    Result.failure(IOException("List failed: ${response.code} ${parseError(response.body?.string())}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encodePath(path: String): String {
        // Graph path segments must be URL-encoded, but '/' separators preserved.
        return path.split('/').joinToString("/") { Uri.encode(it) }
    }

    private fun splitParent(path: String): Pair<String, String> {
        val idx = path.lastIndexOf('/')
        return if (idx < 0) Pair("", path) else Pair(path.substring(0, idx), path.substring(idx + 1))
    }

    private fun parseError(body: String?): String {
        if (body.isNullOrEmpty()) return ""
        return try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        private const val TAG = "OneDriveClient"
        private const val MAX_SIMPLE_UPLOAD = 4 * 1024 * 1024 // 4 MB
    }
}

data class OneDriveFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)
