package cool.hin.memox.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import cool.hin.memox.R

/**
 * Downloads the update APK in-app via [DownloadManager] and auto-prompts install when the
 * download finishes. If no direct APK url is provided, it falls back to opening the release page
 * in the browser. A system notification is always shown on completion as a fallback install path.
 */
object UpdateDownloader {

    @Volatile
    private var pendingId: Long = -1

    fun download(activity: FragmentActivity, apkUrl: String?, releaseUrl: String?) {
        if (apkUrl.isNullOrBlank()) {
            openRelease(activity, releaseUrl)
            return
        }
        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("memoX")
            setDescription(activity.getString(R.string.update_downloading))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(
                activity,
                Environment.DIRECTORY_DOWNLOADS,
                "memoX-update.apk",
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        pendingId = dm.enqueue(request)
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
    }

    fun registerReceiver(activity: FragmentActivity) {
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregisterReceiver(activity: FragmentActivity) {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id != pendingId) return
            val ctx = context ?: return
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) {
                openRelease(ctx, null)
                return
            }
            install(ctx, uri)
        }
    }

    private fun install(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            openRelease(context, null)
        }
    }

    fun openRelease(context: Context, releaseUrl: String?) {
        val url = releaseUrl ?: context.getString(R.string.update_release_url)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }
}
