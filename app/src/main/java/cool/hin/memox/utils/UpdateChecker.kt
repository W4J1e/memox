package cool.hin.memox.utils

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cool.hin.memox.BuildConfig
import cool.hin.memox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Startup update checker. Fetches [R.string.update_check_url] (an `update.json` hosted in the
 * repo) and compares its `versionCode` against [BuildConfig.VERSION_CODE]. When a newer version
 * exists, [state] holds it and the UI shows a subtle "new" indicator (drawer / About screen)
 * instead of an intrusive startup banner. Tapping the About item (or the indicator) opens a
 * changelog dialog from which the user can download in-app or open the release page.
 */
object UpdateChecker {

    data class UpdateInfo(
        val version: String,
        val versionCode: Int,
        val changelog: String,
        val apkUrl: String?,
        val releaseUrl: String?,
    )

    /** Latest fetched update info. UI observes this to toggle the "new" indicator. */
    val state = MutableLiveData<UpdateInfo?>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS = "memox_update"
    private const val KEY_SEEN = "seen_version_code"

    fun isNewer(info: UpdateInfo): Boolean = BuildConfig.VERSION_CODE < info.versionCode

    /** Show the indicator only when there is a newer version the user hasn't acknowledged yet. */
    fun shouldShow(context: Context, info: UpdateInfo): Boolean =
        isNewer(info) && seenCode(context) < info.versionCode

    private fun seenCode(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SEEN, 0)

    fun markSeen(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_SEEN, info.versionCode).apply()
    }

    fun checkForUpdates(context: Context) {
        if (state.value != null) return // already checked in this process
        val url = context.getString(R.string.update_check_url)
        scope.launch {
            val info = runCatching { fetch(context.applicationContext, url) }.getOrNull()
            withContext(Dispatchers.Main) { state.value = info }
        }
    }

    private fun fetch(context: Context, url: String): UpdateInfo? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "memoX/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
        conn.connect()
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val j = JSONObject(text)
        val version = j.optString("version")
        val versionCode = j.optInt("versionCode", -1)
        if (version.isBlank() || versionCode < 0) return null
        return UpdateInfo(
            version = version,
            versionCode = versionCode,
            changelog = j.optString("changelog", ""),
            apkUrl = j.optString("apkUrl").takeIf { it.isNotBlank() },
            releaseUrl = j.optString("releaseUrl").takeIf { it.isNotBlank() }
                ?: context.getString(R.string.update_release_url),
        )
    }

    fun showUpdateDialog(activity: FragmentActivity, info: UpdateInfo) {
        markSeen(activity, info)
        // Re-emit so observers (drawer badge / About badge) recompute visibility now that it's seen.
        state.value = state.value?.copy()

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.new_version_available, info.version))
            .setMessage(info.changelog.ifBlank { activity.getString(R.string.update_changelog_title) })
            .setNegativeButton(R.string.update_open_release) { _, _ ->
                UpdateDownloader.openRelease(activity, info.releaseUrl)
            }
            .setNeutralButton(R.string.update_later, null)
        // 仅当存在可直接下载的 APK 地址时才提供「下载更新」按钮；否则只给浏览器入口，
        // 避免无 apkUrl 时「下载」也跳浏览器造成困惑。
        if (!info.apkUrl.isNullOrBlank()) {
            builder.setPositiveButton(R.string.update_download) { _, _ ->
                UpdateDownloader.download(activity, info.apkUrl, info.releaseUrl)
            }
        }
        builder.show()
    }

    /** 手动检查（如「检查更新」按钮）。始终重新拉取，结果通过回调返回。 */
    fun checkNow(context: Context, onResult: (UpdateInfo?) -> Unit) {
        val url = context.getString(R.string.update_check_url)
        scope.launch {
            val info = runCatching { fetch(context.applicationContext, url) }.getOrNull()
            withContext(Dispatchers.Main) {
                state.value = info
                onResult(info)
            }
        }
    }
}
