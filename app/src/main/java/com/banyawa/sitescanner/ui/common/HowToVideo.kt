package com.banyawa.sitescanner.ui.common

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.banyawa.sitescanner.R
import kotlinx.coroutines.delay

/**
 * The short demonstration of how to walk a scan (res/raw/scan_howto.mp4, drawn by
 * docs/scan_howto_render.py): plays muted and looping, a tap pauses and resumes, and the
 * caption for the scene playing shows under it. [captionStartsMs] says where each caption
 * of [captions] begins.
 */
@Composable
fun HowToVideo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val captions = stringArrayResource(R.array.scan_video_captions)
    val player = remember { LoopingPlayer(context, R.raw.scan_howto) }
    var positionMs by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.positionMs()
            delay(250)
        }
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E2530))
                .clickable { paused = player.toggle() },
        ) {
            AndroidView(factory = { ctx -> player.view(ctx) }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            if (paused) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(56.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp),
                )
            }
        }
        val scene = SCENE_STARTS_MS.indexOfLast { it <= positionMs }.coerceIn(0, captions.size - 1)
        Text(
            captions.getOrElse(scene) { "" },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            minLines = 2,
        )
    }
}

/** Where each caption's scene starts in the video, in step with docs/scan_howto_render.py. */
private val SCENE_STARTS_MS = intArrayOf(0, 5_000, 12_000, 15_000, 21_000, 26_000, 31_000)

/** A MediaPlayer on a TextureView: silent, looping, started once the surface exists. */
private class LoopingPlayer(private val context: Context, @RawRes private val resource: Int) {
    private val player = MediaPlayer()
    private var prepared = false
    private var released = false
    private var wantPlaying = true

    fun view(ctx: Context): TextureView = TextureView(ctx).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (released) return
                try {
                    player.setSurface(Surface(surface))
                    if (!prepared) {
                        player.setDataSource(context, Uri.parse("android.resource://${context.packageName}/$resource"))
                        player.isLooping = true
                        player.setVolume(0f, 0f)
                        player.setOnPreparedListener {
                            prepared = true
                            if (wantPlaying && !released) it.start()
                        }
                        player.prepareAsync()
                    } else if (wantPlaying) {
                        player.start()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "How-to video not started", e)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                if (!released) runCatching { player.setSurface(null) }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
    }

    /** Pauses a playing video or resumes a paused one; returns whether it is now paused. */
    fun toggle(): Boolean {
        wantPlaying = !wantPlaying
        if (prepared && !released) runCatching { if (wantPlaying) player.start() else player.pause() }
        return !wantPlaying
    }

    fun positionMs(): Int = if (prepared && !released) runCatching { player.currentPosition }.getOrDefault(0) else 0

    fun release() {
        released = true
        runCatching { player.release() }
    }

    private companion object {
        const val TAG = "HowToVideo"
    }
}
