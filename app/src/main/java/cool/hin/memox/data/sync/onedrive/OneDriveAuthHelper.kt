package cool.hin.memox.data.sync.onedrive

import android.content.Context
import android.net.Uri
import android.util.Base64
import cool.hin.memox.presentation.viewmodel.preference.MemoXPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles OAuth2 PKCE flow against the Microsoft identity platform (v2.0 endpoint),
 * plus access-token storage and automatic refresh.
 *
 * The redirect URI is the custom scheme `memox://onedrive-auth`, which is registered
 * as an intent-filter on MainActivity. When the browser redirects back, MainActivity
 * forwards the URI to [handleRedirect].
 */
object OneDriveAuthHelper {

    const val REDIRECT_URI = "memox://onedrive-auth"

    private const val CLIENT_ID = "770514b5-57d6-4cef-9789-bd2a181865d0"
    private const val SCOPES = "User.Read Files.ReadWrite offline_access"
    private const val AUTH_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
    private const val TOKEN_URL = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
    private const val GRAPH_ME_URL = "https://graph.microsoft.com/v1.0/me"

    private val httpClient: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /** Whether the user currently has a (possibly expired) refresh token stored. */
    fun isLoggedIn(context: Context): Boolean {
        val prefs = MemoXPreferences.getInstance(context)
        return prefs.onedriveRefreshToken.value.isNotEmpty()
    }

    /**
     * Build the Microsoft consent URL and stash the PKCE verifier + state so that
     * [handleRedirect] can complete the exchange. Open the returned URL in a Custom Tab.
     */
    fun buildAuthUrl(context: Context): String {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()

        // Persist verifier + state so the exchange survives even if the process is killed.
        val prefs = MemoXPreferences.getInstance(context)
        prefs.onedrivePkceVerifier.save(verifier)
        prefs.onedriveOauthState.save(state)

        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("prompt", "select_account")
            .build()
            .toString()
    }

    /**
     * Called by MainActivity when it receives the `memox://onedrive-auth` redirect.
     * Exchanges the authorization code for tokens, stores them, and fetches the account name.
     * Returns an error message on failure, or null on success.
     */
    suspend fun handleRedirect(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val errorDesc = uri.getQueryParameter("error_description")

        if (error != null) return@withContext "$error: $errorDesc"
        if (code == null) return@withContext "No authorization code in redirect"

        val prefs = MemoXPreferences.getInstance(context)
        val expectedState = prefs.onedriveOauthState.value
        if (state == null || state != expectedState) return@withContext "State mismatch (possible CSRF)"

        val verifier = prefs.onedrivePkceVerifier.value
        if (verifier.isEmpty()) return@withContext "Missing PKCE verifier"

        try {
            val tokenResponse = exchangeCodeForTokens(code, verifier)
            storeTokenResponse(context, tokenResponse)
            // Clean up PKCE state
            prefs.onedrivePkceVerifier.save("")
            prefs.onedriveOauthState.save("")
            // Fetch and store the display account name
            fetchAndStoreAccountName(context)
            null
        } catch (e: Exception) {
            e.message ?: "Token exchange failed"
        }
    }

    /**
     * Returns a valid access token, refreshing it first if it is about to expire.
     * Returns null if no refresh token is available or the refresh fails.
     */
    suspend fun getValidAccessToken(context: Context): String? = withContext(Dispatchers.IO) {
        val prefs = MemoXPreferences.getInstance(context)
        val refreshToken = prefs.onedriveRefreshToken.value
        if (refreshToken.isEmpty()) return@withContext null

        val now = System.currentTimeMillis()
        val expiresAt = prefs.onedriveTokenExpiresAt.value
        // Refresh if token is missing, expired, or will expire within 60s
        val accessToken = prefs.onedriveAccessToken.value
        if (accessToken.isNotEmpty() && now < expiresAt - 60_000) {
            return@withContext accessToken
        }

        try {
            val tokenResponse = refreshTokens(refreshToken)
            storeTokenResponse(context, tokenResponse)
            prefs.onedriveAccessToken.value
        } catch (_: Exception) {
            null
        }
    }

    /** Clears all stored OneDrive credentials. */
    fun signOut(context: Context) {
        val prefs = MemoXPreferences.getInstance(context)
        prefs.onedriveAccessToken.save("")
        prefs.onedriveRefreshToken.save("")
        prefs.onedriveTokenExpiresAt.save(0L)
        prefs.onedriveAccount.save("")
        prefs.onedrivePkceVerifier.save("")
        prefs.onedriveOauthState.save("")
    }

    private suspend fun exchangeCodeForTokens(code: String, verifier: String): JSONObject {
        val body = buildString {
            append("client_id=").append(CLIENT_ID)
            append("&grant_type=authorization_code")
            append("&code=").append(Uri.encode(code))
            append("&redirect_uri=").append(Uri.encode(REDIRECT_URI))
            append("&code_verifier=").append(Uri.encode(verifier))
            append("&scope=").append(Uri.encode(SCOPES))
        }
        return postTokenRequest(body)
    }

    private suspend fun refreshTokens(refreshToken: String): JSONObject {
        val body = buildString {
            append("client_id=").append(CLIENT_ID)
            append("&grant_type=refresh_token")
            append("&refresh_token=").append(Uri.encode(refreshToken))
            append("&scope=").append(Uri.encode(SCOPES))
        }
        return postTokenRequest(body)
    }

    private fun postTokenRequest(body: String): JSONObject {
        val request =
            Request.Builder()
                .url(TOKEN_URL)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw Exception("Empty token response")
            if (!response.isSuccessful) {
                val error = try {
                    JSONObject(responseBody).optString("error_description")
                        .takeIf { it.isNotEmpty() }
                        ?: JSONObject(responseBody).optString("error")
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }
                throw Exception(error)
            }
            return JSONObject(responseBody)
        }
    }

    private fun storeTokenResponse(context: Context, json: JSONObject) {
        val prefs = MemoXPreferences.getInstance(context)
        val accessToken = json.getString("access_token")
        val expiresIn = json.optLong("expires_in", 3600)
        val refreshToken = json.optString("refresh_token", "")
            .takeIf { it.isNotEmpty() }

        prefs.onedriveAccessToken.save(accessToken)
        prefs.onedriveTokenExpiresAt.save(System.currentTimeMillis() + expiresIn * 1000)
        if (refreshToken != null) {
            prefs.onedriveRefreshToken.save(refreshToken)
        }
    }

    private suspend fun fetchAndStoreAccountName(context: Context) {
        val token = MemoXPreferences.getInstance(context).onedriveAccessToken.value
        if (token.isEmpty()) return
        try {
            val request =
                Request.Builder()
                    .url(GRAPH_ME_URL)
                    .header("Authorization", "Bearer $token")
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val mail = json.optString("mail").ifEmpty { json.optString("userPrincipalName") }
                    val name = json.optString("displayName")
                    val account = if (name.isNotEmpty() && mail.isNotEmpty()) {
                        "$name ($mail)"
                    } else if (mail.isNotEmpty()) {
                        mail
                    } else if (name.isNotEmpty()) {
                        name
                    } else {
                        "Microsoft account"
                    }
                    MemoXPreferences.getInstance(context).onedriveAccount.save(account)
                }
            }
        } catch (_: Exception) {
            // Non-fatal: account name is only used for display
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateState(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
