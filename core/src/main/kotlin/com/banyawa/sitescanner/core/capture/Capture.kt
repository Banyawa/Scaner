package com.banyawa.sitescanner.core.capture

import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.ColorImage
import com.banyawa.sitescanner.core.pointcloud.DepthFrame
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A recorded walk-through: the camera image, pose and (when the phone has it) depth of
 * every keyframe, as taken by ARCore. Everything the 3D model is later generated from,
 * on the phone or on a PC, and small enough to send around.
 *
 * Layout of a capture folder:
 * ```
 * capture.json      summary, written when recording stops (Manifest)
 * frames.jsonl      one CaptureFrame per line, appended while recording
 * frames/000012.jpg camera image
 * frames/000012.depth  16-bit little-endian millimetres, depthWidth × depthHeight
 * frames/000012.conf   8-bit raw-depth confidence, same size (raw depth only)
 * ```
 */
@Serializable
data class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int) {
    fun toCore() = CameraIntrinsics(fx, fy, cx, cy, width, height)

    companion object {
        fun of(k: CameraIntrinsics) = Intrinsics(k.fx, k.fy, k.cx, k.cy, k.width, k.height)
    }
}

@Serializable
data class CaptureFrame(
    val index: Int,
    val timestampNs: Long,
    /** Column-major camera-to-world, OpenGL convention (camera looks down -Z, +Y up), metres. */
    val cameraToWorld: List<Float>,
    val image: String,
    val imageIntrinsics: Intrinsics,
    /** Depth for the point cloud: ARCore raw depth when the phone gives it, else smoothed depth. */
    val depth: String? = null,
    val depthIntrinsics: Intrinsics? = null,
    val confidence: String? = null,
    /** True when [depth] is ARCore raw depth (sparse, with [confidence]), false for smoothed depth. */
    val rawDepth: Boolean = false,
    /** ARCore smoothed depth (every pixel filled) for the surface model, when [depth] is raw. Same size as [depth]. */
    val smoothDepth: String? = null,
    /** The pose as recorded, when [cameraToWorld] was corrected afterwards from the tracker's refined map. */
    val recordedCameraToWorld: List<Float>? = null,
) {
    val hasDepth: Boolean get() = depth != null && depthIntrinsics != null
    fun pose(): FloatArray = FloatArray(16) { cameraToWorld[it] }
}

@Serializable
data class Manifest(
    val version: Int = 1,
    val app: String = "Site Scanner",
    val device: String = "",
    val frameCount: Int = 0,
    val durationSec: Int = 0,
    /** Height of the floor plane ARCore detected, if any (ARCore world, metres). */
    val floorY: Float? = null,
    /** Height of the ceiling plane ARCore detected, if any. */
    val ceilingY: Float? = null,
    val depthSupported: Boolean = false,
    val notes: String = "",
)

/** Decodes a compressed camera image (JPEG). Android and the JVM each have their own. */
fun interface ImageDecoder {
    fun decode(bytes: ByteArray): ColorImage
}

class Capture(val dir: File, val manifest: Manifest, val frames: List<CaptureFrame>) {
    fun imageBytes(frame: CaptureFrame): ByteArray = File(dir, frame.image).readBytes()

    fun depthMm(frame: CaptureFrame): ShortArray? {
        val name = frame.depth ?: return null
        val k = frame.depthIntrinsics ?: return null
        return readShorts(File(dir, name), k.width * k.height)
    }

    fun confidence(frame: CaptureFrame): ByteArray? = frame.confidence?.let { File(dir, it).readBytes() }

    fun smoothDepthMm(frame: CaptureFrame): ShortArray? {
        val name = frame.smoothDepth ?: return null
        val k = frame.depthIntrinsics ?: return null
        return readShorts(File(dir, name), k.width * k.height)
    }

    /**
     * The frame's depth as fusion takes it, with its pose and (given a [decoder]) its colours.
     * [smooth] picks the smoothed depth for the surface model; without one the point depth
     * serves both.
     */
    fun depthFrame(frame: CaptureFrame, decoder: ImageDecoder?, smooth: Boolean = false, color: ColorImage? = null): DepthFrame? {
        val k = frame.depthIntrinsics ?: return null
        val smoothed = if (smooth) smoothDepthMm(frame) else null
        val depth = smoothed ?: depthMm(frame) ?: return null
        val rgb = color ?: decoder?.decode(imageBytes(frame))
        return DepthFrame(
            width = k.width,
            height = k.height,
            depthMm = depth,
            confidence = if (smoothed != null) null else confidence(frame),
            intrinsics = k.toCore(),
            cameraToWorld = frame.pose(),
            color = rgb,
            colorIntrinsics = if (rgb != null) frame.imageIntrinsics.toCore().scaledTo(rgb.width, rgb.height) else null,
            timestampNs = frame.timestampNs,
        )
    }

    companion object {
        const val MANIFEST = "capture.json"
        const val FRAMES = "frames.jsonl"
        const val FRAME_DIR = "frames"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun open(dir: File): Capture {
            val manifestFile = File(dir, MANIFEST)
            val manifest = if (manifestFile.isFile) json.decodeFromString<Manifest>(manifestFile.readText()) else Manifest()
            val frames = File(dir, FRAMES).takeIf { it.isFile }?.useLines { lines ->
                lines.filter { it.isNotBlank() }.mapNotNull { runCatching { json.decodeFromString<CaptureFrame>(it) }.getOrNull() }.toList()
            }.orEmpty()
            return Capture(dir, manifest.copy(frameCount = frames.size), frames)
        }

        fun isCapture(dir: File) = File(dir, FRAMES).isFile

        /** The frames.jsonl content listing exactly [frames], for a thinned copy of a capture. */
        fun encodeFrames(frames: List<CaptureFrame>): String =
            frames.joinToString("") { json.encodeToString(it) + "\n" }

        internal fun readShorts(file: File, count: Int): ShortArray {
            val bytes = file.readBytes()
            if (bytes.size < count * 2) throw IOException("${file.name}: expected $count depth values")
            val out = ShortArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out
        }
    }
}

/**
 * Appends frames to a capture folder as they are taken. Each frame's files and its
 * manifest line are written before [add] returns, so a recording that stops early
 * (crash, battery) keeps every frame written so far.
 */
class CaptureWriter(val dir: File) {
    private val json = Json { encodeDefaults = true }
    private val lines = FileOutputStream(File(dir.apply { mkdirs() }, Capture.FRAMES), true).bufferedWriter()
    private val frameDir = File(dir, Capture.FRAME_DIR).apply { mkdirs() }

    var frameCount = 0
        private set

    private val written = ArrayList<CaptureFrame>()

    /** Frames whose pose [close] replaced with a corrected one. */
    var correctedFrames = 0
        private set

    /**
     * @param jpeg the camera image, JPEG-encoded
     * @param depthMm optional depth, row-major millimetres
     * @param confidence optional raw-depth confidence, same size as [depthMm]
     */
    @Synchronized
    fun add(
        timestampNs: Long,
        cameraToWorld: FloatArray,
        jpeg: ByteArray,
        imageIntrinsics: CameraIntrinsics,
        depthMm: ShortArray? = null,
        depthIntrinsics: CameraIntrinsics? = null,
        confidence: ByteArray? = null,
        rawDepth: Boolean = false,
        smoothDepthMm: ShortArray? = null,
    ): CaptureFrame {
        require(cameraToWorld.size == 16)
        val index = frameCount
        val stem = String.format(java.util.Locale.US, "%06d", index)
        val imageName = "${Capture.FRAME_DIR}/$stem.jpg"
        File(dir, imageName).writeBytes(jpeg)
        var depthName: String? = null
        var confName: String? = null
        var smoothName: String? = null
        if (depthMm != null && depthIntrinsics != null) {
            val n = depthIntrinsics.width * depthIntrinsics.height
            depthName = "${Capture.FRAME_DIR}/$stem.depth"
            writeShorts(File(dir, depthName), depthMm, n)
            if (confidence != null) {
                confName = "${Capture.FRAME_DIR}/$stem.conf"
                File(dir, confName).writeBytes(confidence.copyOf(n))
            }
            if (smoothDepthMm != null && rawDepth) {
                smoothName = "${Capture.FRAME_DIR}/$stem.smooth"
                writeShorts(File(dir, smoothName), smoothDepthMm, n)
            }
        }
        val frame = CaptureFrame(
            index = index,
            timestampNs = timestampNs,
            cameraToWorld = cameraToWorld.toList(),
            image = imageName,
            imageIntrinsics = Intrinsics.of(imageIntrinsics),
            depth = depthName,
            depthIntrinsics = depthIntrinsics?.let { Intrinsics.of(it) },
            confidence = confName,
            rawDepth = rawDepth,
            smoothDepth = smoothName,
        )
        lines.write(json.encodeToString(frame))
        lines.newLine()
        lines.flush()
        written += frame
        frameCount++
        return frame
    }

    /**
     * Writes the summary and closes the recording. [correctedPoses] (by frame timestamp)
     * replace the poses recorded live: the tracker refines its map as the walk goes on, and
     * poses read back through its anchors at the end line up frames from the start and the
     * end of the walk that drifted apart meanwhile. The recorded pose is kept alongside.
     */
    @Synchronized
    fun close(manifest: Manifest, correctedPoses: Map<Long, FloatArray> = emptyMap()) {
        lines.close()
        if (correctedPoses.isNotEmpty()) {
            for (i in written.indices) {
                val f = written[i]
                val p = correctedPoses[f.timestampNs]?.takeIf { it.size == 16 } ?: continue
                written[i] = f.copy(cameraToWorld = p.toList(), recordedCameraToWorld = f.recordedCameraToWorld ?: f.cameraToWorld)
                correctedFrames++
            }
            if (correctedFrames > 0) {
                val tmp = File(dir, Capture.FRAMES + ".tmp")
                tmp.writeText(Capture.encodeFrames(written))
                if (!tmp.renameTo(File(dir, Capture.FRAMES))) {
                    File(dir, Capture.FRAMES).delete()
                    tmp.renameTo(File(dir, Capture.FRAMES))
                }
            }
        }
        File(dir, Capture.MANIFEST).writeText(json.encodeToString(manifest.copy(frameCount = frameCount)))
    }

    private fun writeShorts(file: File, values: ShortArray, count: Int) {
        val buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(values, 0, count)
        file.writeBytes(buf.array())
    }
}

/** A capture folder as one zip file (and back), for sending to a PC or another person. */
object CaptureZip {
    /**
     * Zips [dir]'s contents (paths relative to it) that pass [include], plus [extra] files
     * (path in zip → content), which take precedence over files of the same path.
     */
    fun write(dir: File, out: OutputStream, extra: Map<String, ByteArray> = emptyMap(), include: (String) -> Boolean = { true }) {
        ZipOutputStream(BufferedOutputStream(out, 1 shl 16)).use { zip ->
            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.path }
            for (f in files) {
                val path = f.relativeTo(dir).path.replace(File.separatorChar, '/')
                if (path in extra || !include(path)) continue
                zip.putNextEntry(ZipEntry(path))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for ((name, bytes) in extra) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Unzips into [dir]; entries outside it are refused. */
    fun read(input: InputStream, dir: File) {
        dir.mkdirs()
        val root = dir.canonicalFile
        ZipInputStream(BufferedInputStream(input, 1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = File(root, entry.name).canonicalFile
                if (!target.path.startsWith(root.path + File.separator)) throw IOException("Bad zip entry ${entry.name}")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }
}
