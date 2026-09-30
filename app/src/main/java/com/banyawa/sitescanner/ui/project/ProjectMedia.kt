package com.banyawa.sitescanner.ui.project

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.core.project.MediaItem
import com.banyawa.sitescanner.core.project.MediaKind
import com.banyawa.sitescanner.ui.common.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Thumbnails per row of the media grid on the project screen. */
const val MEDIA_COLUMNS = 3

/** The "Photos & videos" section header: the count and the three ways to add one. */
@Composable
fun MediaActions(count: Int, onTakePhoto: () -> Unit, onRecordVideo: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.media_section_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (count > 0) {
                Text(
                    pluralStringResource(R.plurals.media_count, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTakePhoto) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_take_photo))
            }
            OutlinedButton(onClick = onRecordVideo) {
                Icon(Icons.Filled.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_record_video))
            }
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_import))
            }
        }
        if (count == 0) {
            Text(
                stringResource(R.string.media_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One row of the media grid: up to [columns] square thumbnails, padded so partial rows keep the same cell width. */
@Composable
fun MediaRow(
    items: List<MediaItem>,
    columns: Int,
    fileOf: (MediaItem) -> File,
    onOpen: (MediaItem) -> Unit,
    onShare: (MediaItem) -> Unit,
    onDelete: (MediaItem) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            MediaThumbnail(
                item = item,
                file = fileOf(item),
                modifier = Modifier.weight(1f),
                onOpen = { onOpen(item) },
                onShare = { onShare(item) },
                onDelete = { onDelete(item) },
            )
        }
        repeat(columns - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A square thumbnail: tap opens the file, long-press or the corner menu shares or deletes it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    item: MediaItem,
    file: File,
    modifier: Modifier,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb by produceState(initialValue = MediaThumbnails.peek(file), file.path) {
        value = MediaThumbnails.load(file, item.kind)
    }
    var menuOpen by remember { mutableStateOf(false) }
    val isVideo = item.kind == MediaKind.VIDEO
    val description = stringResource(if (isVideo) R.string.media_video else R.string.media_photo)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true }),
    ) {
        val bitmap = thumb?.bitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = description,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (isVideo) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(36.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp),
                    tint = Color.White,
                )
            }
        } else {
            Icon(
                if (isVideo) Icons.Filled.Videocam else Icons.Filled.PhotoCamera,
                contentDescription = description,
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        thumb?.durationSec?.let { sec ->
            Text(
                formatDuration(sec),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.media_more),
                    modifier = Modifier.size(20.dp),
                    tint = if (bitmap != null) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open)) },
                    leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_share)) },
                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** A decoded thumbnail, with the length of a video when its file says. */
class MediaThumb(val bitmap: Bitmap, val durationSec: Int?)

/**
 * Small square thumbnails of the project's photos and videos, decoded off the main thread
 * and kept in memory (keyed by file and modification) so scrolling back does not decode again.
 */
object MediaThumbnails {
    private const val SIZE_PX = 256

    private val cache = object : LruCache<String, MediaThumb>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: MediaThumb): Int = value.bitmap.byteCount
    }

    private fun key(file: File) = "${file.path}:${file.lastModified()}:${file.length()}"

    /** The thumbnail if it was decoded before, without waiting. */
    fun peek(file: File): MediaThumb? = cache.get(key(file))

    /** Decodes (or returns the cached) thumbnail; null when the file cannot be read as a photo / video. */
    suspend fun load(file: File, kind: MediaKind): MediaThumb? {
        val key = key(file)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bitmap = runCatching { if (kind == MediaKind.VIDEO) videoFrame(file) else photo(file) }.getOrNull()
                ?: return@withContext null
            val duration = if (kind == MediaKind.VIDEO) videoDurationSec(file) else null
            MediaThumb(bitmap, duration).also { cache.put(key, it) }
        }
    }

    /** Decodes the photo about [SIZE_PX] wide (sub-sampled, so a 12 MP photo does not fill memory), cropped square and upright. */
    private fun photo(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE_PX && bounds.outHeight / (sample * 2) >= SIZE_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val square = ThumbnailUtils.extractThumbnail(decoded, SIZE_PX, SIZE_PX, ThumbnailUtils.OPTIONS_RECYCLE_INPUT) ?: return null
        return rotated(square, exifRotation(file))
    }

    /** Camera apps store many photos sideways and mark the rotation in EXIF. */
    private fun exifRotation(file: File): Int = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val out = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (out !== bitmap) bitmap.recycle()
        return out
    }

    @Suppress("DEPRECATION")
    private fun videoFrame(file: File): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createVideoThumbnail(file, Size(SIZE_PX, SIZE_PX), null)
        } else {
            ThumbnailUtils.createVideoThumbnail(file.path, MediaStore.Images.Thumbnails.MINI_KIND)
        }

    private fun videoDurationSec(file: File): Int? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ((it + 500) / 1000).toInt() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/** The MIME type of [item]'s file from its extension, or the kind's wildcard when unknown. */
fun mediaMimeType(item: MediaItem): String {
    val extension = item.fileName.substringAfterLast('.', "").lowercase(Locale.US)
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: if (item.kind == MediaKind.VIDEO) "video/*" else "image/*"
}

/** A content URI other apps (the camera, viewers, share targets) can read [file] through. */
fun mediaUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

/** Shows [file] in the phone's photo / video viewer; false when there is no app for it. */
fun openMedia(context: Context, item: MediaItem, file: File): Boolean {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(mediaUri(context, file), mediaMimeType(item))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(view)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Opens the share sheet for [file] (LINE, Gmail, Drive, ...). */
fun shareMedia(context: Context, item: MediaItem, file: File) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mediaMimeType(item))
        .putExtra(Intent.EXTRA_STREAM, mediaUri(context, file))
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val chooser = Intent.createChooser(send, context.getString(R.string.media_share_title))
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
