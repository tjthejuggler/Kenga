package com.example.kenga.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import com.example.kenga.KengaApplication
import com.example.kenga.ui.body.KangarooBodyGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Read-only provider that serves the LIVE kangaroo-man figure to the Tail
 * app's combined scene widget (4x2). Same art the Kenga home widget shows —
 * today's set-count tier colours, cropped to alpha bounds, transparent.
 *
 * Protocol (mirrors Tail's integration family; identical twin lives in
 * Octo as com.example.octo.figure):
 *   URI      content://com.example.kenga.figure/v1/figure
 *   Columns  figure  (Blob, PNG bytes; null when nothing rendered yet)
 *            hash    (Text, SHA-256 of the PNG; "empty" when no figure)
 *            updated (Long, epoch ms of the render)
 *            zones   (Int, DISTINCT muscle areas with sets logged today —
 *                    Tail's "confidence" gate: ≥4 → he steps in front of
 *                    the lizard on the scene widget)
 *   Access   readPermission = com.example.tail.permission.TAIL_INTEGRATION
 *            (signature-level; Tail already holds it — no new wiring)
 *
 * Cheap/hash-first contract: Tail queries the `hash` column first and only
 * opens the stream when it changed, so a refresh ping costs a hash query
 * plus (on change) one PNG transfer. The PNG render is memoized per day +
 * set-count fingerprint in memory; the provider NEVER blocks on the DB for
 * the hash path (last rendered state is cached).
 */
class FigureSnapshotProvider : ContentProvider() {

    companion object {
        private const val TAG = "KengaFigureProvider"

        /** Binder-friendly figure budget: 160k ARGB px ≈ 640 KB (widget rule). */
        private const val MAX_PIXELS = 160_000

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** One rendered figure + its served metadata. */
        private data class RenderedFigure(
            val png: ByteArray,
            val hash: String,
            val zonesToday: Int,
            val updated: Long,
        )

        // Last rendered figure state (memory mirror so the hash path never
        // touches the DB): (png, hash, zones, updated) + its data fingerprint.
        @Volatile
        private var cached: RenderedFigure? = null
        @Volatile
        private var cachedFingerprint: String? = null

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * Re-renders the figure for today (if the set-count fingerprint
         * changed) and notifies Tail's scene widget. Called from
         * MainActivity.onResume alongside KengaWidgetProvider.requestUpdate.
         */
        fun refreshAndPing(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                try {
                    ensureFresh(appContext)
                    appContext.sendBroadcast(
                        Intent("com.example.tail.ACTION_SCENE_FIGURES_CHANGED")
                            .setPackage("com.example.tail"),
                        "com.example.tail.permission.TAIL_INTEGRATION"
                    )
                } catch (e: Exception) {
                    Log.i(TAG, "scene ping skipped (Tail absent?): ${e.message}")
                }
            }
        }

        /** Renders today's figure when the data fingerprint changed. */
        private suspend fun ensureFresh(context: Context) {
            val app = context.applicationContext as KengaApplication
            val today = LocalDate.now().toString()
            val counts: Map<String, Int> = app.appContainer.pbRepository
                .observeTodaySetCounts(today)
                .first()
            val fingerprint = today + ":" + counts.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }
            if (fingerprint == cachedFingerprint && cached != null) return

            val zonesToday = KangarooBodyGeometry.coloredZoneCount(counts)
            val cropped = cropToContent(
                KengaWidgetRenderer.render(
                    context, KangarooBodyGeometry.Side.FRONT, counts,
                    900, 1350,
                )
            )
            val scaled = if (cropped.width * cropped.height > MAX_PIXELS) {
                val k = kotlin.math.sqrt(
                    MAX_PIXELS.toDouble() / (cropped.width * cropped.height)
                ).toFloat()
                Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width * k).toInt().coerceAtLeast(1),
                    (cropped.height * k).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                cropped
            }
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val bytes = bos.toByteArray()
            cached = RenderedFigure(bytes, sha256(bytes), zonesToday, System.currentTimeMillis())
            cachedFingerprint = fingerprint
        }

        /** Alpha-bounds crop (identical rule to KengaWidgetProvider). */
        private fun cropToContent(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (px[row + x] ushr 24 != 0) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) return src
            return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
        }
    }

    override fun onCreate(): Boolean = true

    /**
     * Byte-stream endpoint for contentResolver.openInputStream (Tail's
     * pull path). Serves the memoized PNG via a cache file — the
     * canonical ContentProvider way to hand out bytes.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.path != "/v1/figure") {
            throw FileNotFoundException(uri.toString())
        }
        val ctx = context ?: throw FileNotFoundException("no context")
        if (mode != "r") throw FileNotFoundException("read-only provider")
        runBlocking { ensureFresh(ctx) }
        val bytes = cached?.png
            ?: throw FileNotFoundException("figure not rendered yet")
        val serve = File(ctx.cacheDir, "scene_figure_serve_kenga.png")
        serve.writeBytes(bytes)
        return ParcelFileDescriptor.open(
            serve, ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.path != "/v1/figure") return null
        val ctx = context ?: return null
        // Blocking render for the query path: callers (Tail) are already on
        // IO dispatchers. The fingerprint memo makes repeat queries ~free.
        runBlocking { ensureFresh(ctx) }
        val state = cached
        val cols = projection ?: arrayOf("figure", "hash", "updated", "zones")
        val cursor = MatrixCursor(cols, 1)
        if (state == null) {
            // Nothing rendered yet (no data today): advertise empty.
            cursor.addRow(arrayOf(null, "empty", 0L, 0))
            return cursor
        }
        val row = arrayOfNulls<Any>(cols.size)
        cols.forEachIndexed { i, col ->
            row[i] = when (col) {
                "figure" -> state.png
                "hash" -> state.hash
                "updated" -> state.updated
                "zones" -> state.zonesToday
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    // Read-only provider: writes unsupported (Tail integration convention).
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only figure provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only figure provider")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only figure provider")

    override fun getType(uri: Uri): String? = null
}
