package com.opencode.remote.data.download

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log

/**
 * Watches for [DownloadManager.ACTION_DOWNLOAD_COMPLETE] and, when the
 * finished download is the OConnector update APK, launches the system
 * package installer for it.
 *
 * Registered in the manifest with `RECEIVE_BOOT_COMPLETED`-style export that
 * allows receiver to run while the app is backgrounded (Android's
 * `ACTION_DOWNLOAD`-complete broadcast is sent to receivers that declared it
 * in the manifest, no runtime registration needed).
 */
class DownloadCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            return
        }
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return
        Log.d(TAG, "Download complete: id=$downloadId")

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return
        val localUri = queryDownloadUri(manager, downloadId) ?: return

        val file = if (localUri.scheme == "file") {
            java.io.File(localUri.path)
        } else {
            val filePath = queryDownloadFilePath(manager, downloadId)
            if (filePath != null) java.io.File(filePath) else null
        } ?: return
        if (!file.exists() || !file.name.endsWith(".apk")) {
            Log.w(TAG, "Completed download is not an APK at ${file.absolutePath}")
            return
        }
        Log.d(TAG, "Installing update APK: ${file.absolutePath}")
        try {
            InstallHelper.installApk(context, file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch installer", e)
        }
    }

    private fun queryDownloadUri(manager: DownloadManager, downloadId: Long): android.net.Uri? {
        val cursor: Cursor? = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor?.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    return android.net.Uri.parse(it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)))
                }
            }
        }
        return null
    }

    private fun queryDownloadFilePath(manager: DownloadManager, downloadId: Long): String? {
        val cursor: Cursor? = manager.query(DownloadManager.Query().setFilterById(downloadId))
        cursor?.use {
            if (it.moveToFirst()) {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    return it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_FILENAME))
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "DownloadCompleteReceiver"
    }
}