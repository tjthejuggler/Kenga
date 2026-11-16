package com.example.kenga.youtube

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.kenga.R
import com.example.kenga.KengaApplication
import com.example.kenga.work.DownloadVideoWorker
import kotlinx.coroutines.launch

/**
 * ACTION_SEND trampoline (Octo `ShareIngestActivity` pattern): receives the
 * YouTube app's shared text, extracts the first video URL, inserts a
 * PENDING_DOWNLOAD library row (with best-effort oEmbed metadata) and toasts
 * — then finishes immediately. Declared with the translucent/noHistory/
 * excludeFromRecents theme so it is invisible.
 */
class ShareIngestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else -> ""
        }

        // Fast reject without touching the DB / network.
        if (YoutubeUrlParser.parse(sharedText) == null) {
            Toast.makeText(this, R.string.share_invalid_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val container = (application as KengaApplication).appContainer
        lifecycleScope.launch {
            val row = container.videoRepository.addSharedUrl(sharedText)
            if (row != null) {
                // Phase 3: kick the download worker immediately (unique-KEEP — a
                // re-share never restarts an in-flight download).
                DownloadVideoWorker.enqueue(this@ShareIngestActivity, row.videoId)
                Toast.makeText(this@ShareIngestActivity, R.string.share_added, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ShareIngestActivity, R.string.share_invalid_url, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }
}
