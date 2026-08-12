package com.opencode.remote.data.download

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests for [DownloadCompleteReceiver] guards. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DownloadCompleteReceiverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `ignores unrelated broadcasts`() {
        val receiver = DownloadCompleteReceiver()
        // Should silently return — no DownloadManager interaction → no crash.
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
    }

    @Test
    fun `ignores download complete with missing id`() {
        val receiver = DownloadCompleteReceiver()
        val intent = Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // No EXTRA_DOWNLOAD_ID → early return
        receiver.onReceive(context, intent)
    }

    @Test
    fun `handles download complete that is not found by manager`() {
        val receiver = DownloadCompleteReceiver()
        val intent = Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).apply {
            putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 99999L)
        }
        // Should not throw even though no such download exists.
        receiver.onReceive(context, intent)
        Assert.assertTrue(true)
    }
}