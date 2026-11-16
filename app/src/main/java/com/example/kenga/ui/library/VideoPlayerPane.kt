package com.example.kenga.ui.library

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.view.WindowManager
import com.example.kenga.R
import com.example.kenga.data.local.VideoEntity
import com.example.kenga.player.PlaybackResolution
import com.example.kenga.player.prepareWith
import com.example.kenga.player.togglePlayPause

/**
 * Phase 3 player pane for the video detail screen (Octo `PlayerScreen` port,
 * minus session timers/finish logic — Kenga videos are reference material).
 *
 * - DOWNLOADED row with a resolvable local file → embedded 16:9 [PlayerView]
 *   (stock Media3 controls) + fullscreen toggle. Fullscreen hides the system
 *   bars, draws edge-to-edge, and swaps to tap-to-toggle + a plain controller.
 * - PENDING_DOWNLOAD / DOWNLOADING / FAILED → download status pane with a
 *   retry action.
 *
 * The [ExoPlayer] instance is owned by this composable and released on dispose;
 * playback is fully offline (local file or merged dual-stream source).
 */
@Composable
fun VideoPlayerPane(
    video: VideoEntity,
    progressPercent: Int?,
    onRetryDownload: () -> Unit,
    playerManager: com.example.kenga.player.PlayerManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf(false) }

    val player = remember(video.videoId) {
        playerManager.resolve(video).let { resolution ->
            if (resolution is PlaybackResolution.Ready) {
                playerManager.createPlayer().apply {
                    prepareWith(resolution.source)
                }
            } else {
                null
            }
        }
    }
    DisposableEffect(video.videoId) {
        onDispose { player?.release() }
    }

    // KeepScreenOn while playing.
    var isPlaying by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            player?.pause()
        }
    }
    DisposableEffect(isPlaying, fullscreen) {
        val window = (context as? Activity)?.window
        if (isPlaying && fullscreen) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    when {
        player != null -> {
            Box(modifier = modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            this.player = player
                        }
                    },
                    update = { view ->
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                    onRelease = { view -> view.player = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                )
                IconButton(
                    onClick = { fullscreen = true },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.Fullscreen,
                        contentDescription = stringResource(R.string.player_enter_fullscreen),
                        tint = Color.White,
                    )
                }
            }
        }

        progressPercent != null || video.analysisState == com.example.kenga.data.local.AnalysisState.DOWNLOADING -> {
            DownloadingPane(progressPercent, modifier)
        }

        else -> MissingFilesPane(onRetryDownload, modifier)
    }

    if (fullscreen && player != null) {
        // Full-bleed dialog so the overlay covers the whole screen even though
        // this pane lives inside the detail screen's padded scroll column.
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            FullscreenPlayerOverlay(
                player = player,
                title = video.title,
                onExit = { fullscreen = false },
            )
        }
    }
}

@Composable
private fun DownloadingPane(progressPercent: Int?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (progressPercent != null && progressPercent >= 0) {
                stringResource(R.string.player_downloading_percent, progressPercent)
            } else {
                stringResource(R.string.player_downloading)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MissingFilesPane(onRetryDownload: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🦘", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.player_missing_files),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetryDownload) {
            Text(stringResource(R.string.player_retry_download))
        }
    }
}

/**
 * True fullscreen overlay: system bars hidden (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE),
 * content edge-to-edge, tap = play/pause toggle, stock controller on top.
 * All immersive state restored on dispose.
 */
@Composable
private fun FullscreenPlayerOverlay(
    player: ExoPlayer,
    title: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    this.player = player
                }
            },
            update = { view ->
                view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { player.togglePlayPause() })
                },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 24.dp),
        ) {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.Filled.FullscreenExit,
                    contentDescription = stringResource(R.string.player_exit_fullscreen),
                    tint = Color.White,
                )
            }
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 16.dp),
            )
        }
    }
}
