package com.banyawa.sitescanner.core.export

import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.Intrinsics
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Camera poses of a recorded walk-through for the PC tools that turn photos into a
 * photo-quality model or a Gaussian splat (COLMAP, Postshot, nerfstudio).
 *
 * Those tools normally spend most of their time, and most of their failures, working out
 * where each photo was taken from. ARCore already measured that on the phone, in metres,
 * so we hand it over: [Colmap] writes a COLMAP text model, [Nerfstudio] a transforms.json.
 * Both refer to the images by the same names in the same image folder, so both can share
 * one export folder.
 *
 * The image folder (`imageDir`, relative to the export folder, `/`-separated) is `images/`,
 * where the writers copy the images, unless the caller keeps them where they are: exporting
 * into the capture folder itself with `copyImages = false, imageDir = "frames"` points the
 * files at the capture's own JPEGs instead of duplicating them. Images are always referred to
 * by file name alone, so with `copyImages = false` they must already sit in `outDir/imageDir`
 * under their capture file names.
 */
object PoseExports {
    /** Default image folder, relative to the export folder. */
    const val IMAGE_DIR = "images"
    const val README = "README.txt"

    private val WHITESPACE = Regex("\\s")

    /** One exported image: its name under [IMAGE_DIR], where it comes from, its pose and its camera. */
    internal class Frame(val name: String, val source: File, val cameraToWorld: FloatArray, val intrinsics: Intrinsics)

    /**
     * The frames worth exporting, sorted by image name. Frames whose image is missing or whose
     * pose is not finite (a tracking glitch) are left out, since the tools reject a whole
     * dataset over one such frame. Name order matters for COLMAP: its known-poses recipe wants
     * the image IDs of images.txt to match its database, and `feature_extractor` numbers the
     * images in name order, which is the order we number them in.
     */
    internal fun frames(capture: Capture): List<Frame> {
        val used = HashSet<String>()
        val out = ArrayList<Frame>()
        for (frame in capture.frames) {
            if (frame.cameraToWorld.size != 16) continue
            val pose = frame.pose()
            val source = File(capture.dir, frame.image)
            if (!source.isFile || pose.any { !it.isFinite() }) continue
            // COLMAP splits its lines on whitespace, so names must not contain any.
            val base = frame.image.substringAfterLast('/').replace(WHITESPACE, "_")
            val name = if (used.add(base)) base else "${frame.index}_$base".also { used.add(it) }
            out += Frame(name, source, pose, intrinsicsFor(frame.imageIntrinsics, source))
        }
        return out.sortedBy { it.name }
    }

    /**
     * [k] for the size the JPEG really has. The tools take width and height from cameras.txt /
     * transforms.json and trust them, so an image stored smaller than the camera frame must
     * bring scaled intrinsics along. One stored rotated cannot be fixed by scaling: that fails
     * loudly rather than giving a dataset whose poses silently do not fit its images.
     */
    internal fun intrinsicsFor(k: Intrinsics, image: File): Intrinsics {
        val size = JpegSize.read(image) ?: return k
        val (w, h) = size
        if (w == k.width && h == k.height) return k
        val aspect = k.width.toDouble() / k.height
        check(abs(w.toDouble() / h - aspect) <= 0.01 * aspect) {
            "${image.name} is ${w}x$h but its intrinsics are for ${k.width}x${k.height}"
        }
        return Intrinsics.of(k.toCore().scaledTo(w, h))
    }

    /** [imageDir] without surrounding slashes; "" means the export folder itself. */
    internal fun cleanDir(imageDir: String) = imageDir.trim('/')

    /** Path of an image relative to the export folder. */
    internal fun imagePath(imageDir: String, name: String): String =
        cleanDir(imageDir).let { if (it.isEmpty()) name else "$it/$name" }

    internal fun copyImages(frames: List<Frame>, outDir: File, imageDir: String) {
        val dir = cleanDir(imageDir).let { if (it.isEmpty()) outDir else File(outDir, it) }.apply { mkdirs() }
        for (f in frames) {
            val target = File(dir, f.name)
            // copyTo(overwrite) deletes the target first: never let that be the source itself.
            if (target.canonicalFile == f.source.canonicalFile) continue
            f.source.copyTo(target, overwrite = true)
        }
    }

    internal fun writeReadme(outDir: File, imageDir: String) {
        File(outDir.apply { mkdirs() }, README).writeText(readme(imageDir))
    }

    /**
     * README.txt for an export folder: what the files are and how to turn them into a model,
     * with the commands pointing at [imageDir].
     */
    fun readme(imageDir: String = IMAGE_DIR): String {
        val images = cleanDir(imageDir).ifEmpty { "." }
        // A folder we did not fill ourselves (the capture's frames/) also holds depth files.
        val otherFiles = if (images == IMAGE_DIR) "" else
            "\n           If $images/ also holds files that are not images, COLMAP reports them as unreadable and skips them."
        return readmeText(images, "$images/".padEnd(18), otherFiles)
    }

    private fun readmeText(images: String, imagesEntry: String, otherFiles: String): String = """
        Site Scanner 3D: camera poses for photogrammetry and Gaussian splatting
        =======================================================================

        The camera images of one recorded walk-through, together with where the camera was and
        where it pointed for every image, as ARCore measured them on the phone. PC tools normally
        have to work the camera positions out from the photos; with these files they start from
        the measured ones. Units are metres in the phone's AR world: Y is up, and the origin is
        where the AR session started.

          ${imagesEntry}the camera images (JPEG)
          sparse/0/         COLMAP text model:
                              cameras.txt   the camera (PINHOLE: focal length and centre, no distortion)
                              images.txt    position and rotation of the camera for every image
                              points3D.txt  empty: no 3D points yet
          transforms.json   the same cameras for nerfstudio
          README.txt        this file

        Depending on the export, a folder holds the COLMAP part, transforms.json, or both.


        COLMAP: dense point cloud and mesh from the known poses
        -------------------------------------------------------
        Run the commands in this folder. The dense steps (patch_match_stereo) need a COLMAP build
        with CUDA and an NVIDIA graphics card.

        1. Triangulate 3D points for the known poses. COLMAP's dense step picks each image's
           neighbours and depth range from these points, so do not skip this step even though the
           poses are known.

           colmap feature_extractor --database_path database.db --image_path $images --ImageReader.single_camera 1 --ImageReader.camera_model PINHOLE --ImageReader.camera_params "FX,FY,CX,CY"
           colmap sequential_matcher --database_path database.db
           mkdir sparse/triangulated
           colmap point_triangulator --database_path database.db --image_path $images --input_path sparse/0 --output_path sparse/triangulated

           Replace FX,FY,CX,CY with the last four numbers of the camera line in sparse/0/cameras.txt.
           If cameras.txt lists more than one camera, leave out the three --ImageReader options.
           On the Windows command prompt, write the folder as sparse\triangulated for mkdir.$otherFiles
           exhaustive_matcher instead of sequential_matcher is slower, but also finds overlap
           between images taken far apart in time (for example when a room was walked twice).

        2. Dense point cloud and mesh:

           colmap image_undistorter --image_path $images --input_path sparse/triangulated --output_path dense --output_type COLMAP
           colmap patch_match_stereo --workspace_path dense --workspace_format COLMAP --PatchMatchStereo.geom_consistency true
           colmap stereo_fusion --workspace_path dense --workspace_format COLMAP --input_type geometric --output_path dense/fused.ply
           colmap poisson_mesher --input_path dense/fused.ply --output_path dense/meshed-poisson.ply

           The camera model has no lens distortion to remove (PINHOLE); image_undistorter is
           still needed because it sets up the dense workspace. dense/fused.ply is the coloured point
           cloud and dense/meshed-poisson.ply the mesh (MeshLab, CloudCompare, Blender).


        Postshot (Windows, NVIDIA graphics card): Gaussian splat
        --------------------------------------------------------
        Postshot can train on images that come with COLMAP camera poses instead of working the
        poses out itself: import the $images/ folder together with a COLMAP model. A splat starts
        from the model's 3D points and sparse/0 has none, so run COLMAP step 1 first and give
        Postshot the triangulated model. To put it in the usual sparse/0 place, in text form:

           colmap model_converter --input_path sparse/triangulated --output_path sparse/0 --output_type TXT

        (this replaces the pose-only files in sparse/0 with the triangulated model, points included).


        nerfstudio: Gaussian splat
        --------------------------
           ns-train splatfacto --data <this folder>

        reads transforms.json and the images directly. transforms.json holds no 3D points, so
        splatfacto starts from randomly placed points.
    """.trimIndent() + "\n"
}

/**
 * COLMAP text model of a capture: `sparse/0/cameras.txt`, `images.txt` and `points3D.txt`,
 * next to its image folder. It holds the known poses and no points, which is the starting
 * point COLMAP documents for "reconstruct from known camera poses"; Postshot and other
 * splat tools read the same layout.
 */
object Colmap {
    const val MODEL_DIR = "sparse/0"

    /**
     * Writes the model into [outDir] and, if [copyImages], the images into `outDir/imageDir`;
     * returns the number of images. Image names in images.txt are file names alone, because
     * COLMAP looks them up under the folder its commands get as `--image_path`.
     */
    fun write(capture: Capture, outDir: File, copyImages: Boolean = true, imageDir: String = PoseExports.IMAGE_DIR): Int {
        val frames = PoseExports.frames(capture)
        val model = File(File(outDir, "sparse"), "0").apply { mkdirs() }
        if (copyImages) PoseExports.copyImages(frames, outDir, imageDir)
        val cameras = cameraIds(frames)
        File(model, "cameras.txt").writeText(camerasText(cameras))
        File(model, "images.txt").writeText(imagesText(frames, cameras))
        File(model, "points3D.txt").writeText(POINTS_HEADER)
        PoseExports.writeReadme(outDir, imageDir)
        return frames.size
    }

    /** One COLMAP camera per distinct set of intrinsics, numbered from 1 in order of first use. */
    private fun cameraIds(frames: List<PoseExports.Frame>): Map<Intrinsics, Int> {
        val ids = LinkedHashMap<Intrinsics, Int>()
        for (f in frames) ids.getOrPut(f.intrinsics) { ids.size + 1 }
        return ids
    }

    private fun camerasText(cameras: Map<Intrinsics, Int>): String {
        val sb = StringBuilder()
        sb.append("# Camera list with one line of data per camera:\n")
        sb.append("#   CAMERA_ID, MODEL, WIDTH, HEIGHT, PARAMS[]\n")
        sb.append("# Number of cameras: ").append(cameras.size).append('\n')
        for ((k, id) in cameras) {
            // PINHOLE params: fx, fy, cx, cy. ARCore describes its camera as a plain pinhole.
            sb.append(id).append(" PINHOLE ").append(k.width).append(' ').append(k.height)
            for (v in floatArrayOf(k.fx, k.fy, k.cx, k.cy)) Numbers.appendFixed(sb.append(' '), v.toDouble(), 4)
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun imagesText(frames: List<PoseExports.Frame>, cameras: Map<Intrinsics, Int>): String {
        val sb = StringBuilder()
        sb.append("# Image list with two lines of data per image:\n")
        sb.append("#   IMAGE_ID, QW, QX, QY, QZ, TX, TY, TZ, CAMERA_ID, NAME\n")
        sb.append("#   POINTS2D[] as (X, Y, POINT3D_ID)\n")
        sb.append("# Number of images: ").append(frames.size).append(", mean observations per image: 0\n")
        frames.forEachIndexed { i, f ->
            val (q, t) = PoseMath.worldToCameraColmap(f.cameraToWorld)
            sb.append(i + 1)
            for (v in q) Numbers.appendFixed(sb.append(' '), v, 9)
            for (v in t) Numbers.appendFixed(sb.append(' '), v, 9)
            sb.append(' ').append(cameras.getValue(f.intrinsics)).append(' ').append(f.name).append('\n')
            // The second line lists the image's 2D points; there are none, but COLMAP reads it
            // as the line after the image line, so it must be there, empty.
            sb.append('\n')
        }
        return sb.toString()
    }

    private const val POINTS_HEADER =
        "# 3D point list with one line of data per point:\n" +
            "#   POINT3D_ID, X, Y, Z, R, G, B, ERROR, TRACK[] as (IMAGE_ID, POINT2D_IDX)\n" +
            "# Number of points: 0, mean track length: 0\n"
}

/**
 * nerfstudio `transforms.json` of a capture, next to its image folder, for
 * `ns-train splatfacto --data <folder>`. nerfstudio's camera-to-world convention is
 * OpenGL's (camera looks down -Z, +Y up), the same as ARCore's, so the poses go in unchanged;
 * only the layout differs: nested rows (`transform_matrix[row][col]`) instead of our
 * column-major array.
 */
object Nerfstudio {
    const val TRANSFORMS = "transforms.json"

    private val json = Json { prettyPrint = true }

    /**
     * Writes transforms.json into [outDir] and, if [copyImages], the images into
     * `outDir/imageDir`; returns the number of images. Each frame's `file_path` is
     * `imageDir/<file name>`, relative to [outDir] as nerfstudio resolves it.
     */
    fun write(capture: Capture, outDir: File, copyImages: Boolean = true, imageDir: String = PoseExports.IMAGE_DIR): Int {
        val frames = PoseExports.frames(capture)
        outDir.mkdirs()
        if (copyImages) PoseExports.copyImages(frames, outDir, imageDir)
        File(outDir, TRANSFORMS).writeText(json.encodeToString(JsonObject.serializer(), transforms(frames, imageDir)))
        PoseExports.writeReadme(outDir, imageDir)
        return frames.size
    }

    /** Intrinsics go at the top when every frame shares them (the usual case), else on each frame. */
    internal fun transforms(frames: List<PoseExports.Frame>, imageDir: String = PoseExports.IMAGE_DIR): JsonObject {
        val shared = frames.map { it.intrinsics }.distinct().singleOrNull()
        return buildJsonObject {
            put("camera_model", "OPENCV")
            if (shared != null) putIntrinsics(shared)
            // OPENCV's distortion terms, all zero: ARCore describes its camera as a plain pinhole.
            for (key in listOf("k1", "k2", "p1", "p2")) put(key, 0.0)
            putJsonArray("frames") {
                for (f in frames) {
                    addJsonObject {
                        put("file_path", PoseExports.imagePath(imageDir, f.name))
                        if (shared == null) putIntrinsics(f.intrinsics)
                        put("transform_matrix", rowMajor(f.cameraToWorld))
                    }
                }
            }
        }
    }

    private fun JsonObjectBuilder.putIntrinsics(k: Intrinsics) {
        put("fl_x", k.fx)
        put("fl_y", k.fy)
        put("cx", k.cx)
        put("cy", k.cy)
        put("w", k.width)
        put("h", k.height)
    }

    /** Column-major 4x4 (element (r, c) at `c * 4 + r`) as a list of rows. */
    private fun rowMajor(m: FloatArray) =
        JsonArray((0 until 4).map { r -> JsonArray((0 until 4).map { c -> JsonPrimitive(m[c * 4 + r]) }) })
}

/**
 * Our camera convention, COLMAP's, and the conversion between them.
 *
 * Ours (ARCore / OpenGL, also nerfstudio's): column-major camera-to-world; the camera looks
 * down its -Z axis with +Y up and +X right. The pixel (u, v), v down, at depth d is the camera
 * point (d·(u - cx)/fx, -d·(v - cy)/fy, -d).
 *
 * COLMAP: the camera looks down its +Z axis with +Y down and +X right, and images.txt stores
 * world-to-camera, `X_cam = R·X_world + t`, with R as a Hamilton quaternion (w, x, y, z).
 * The two cameras sit at the same place and differ by a half turn about their X axis, which
 * flips Y and Z: `R_c2w(colmap) = R_c2w(ours)·diag(1, -1, -1)`.
 *
 * The two projections are here so the conversion can be checked against its definition.
 */
object PoseMath {
    /**
     * COLMAP world-to-camera of our camera-to-world [cameraToWorld]: the quaternion
     * (w, x, y, z), unit length with w ≥ 0, and the translation t.
     */
    fun worldToCameraColmap(cameraToWorld: FloatArray): Pair<DoubleArray, DoubleArray> {
        require(cameraToWorld.size == 16)
        val m = cameraToWorld
        // R_w2c = (R_gl·diag(1, -1, -1))ᵀ: row i is our camera's axis i in world, Y and Z negated.
        val r = DoubleArray(9)
        for (i in 0 until 3) {
            val sign = if (i == 0) 1.0 else -1.0
            for (j in 0 until 3) r[i * 3 + j] = sign * m[i * 4 + j]
        }
        val q = quaternionFromRotation(r)
        // t = -R·C with R rebuilt from the normalised quaternion, so that the camera centre
        // COLMAP recovers (-Rᵀ·t) is exactly ours even when the float rotation is not quite
        // orthonormal.
        val rq = rotationFromQuaternion(q)
        val c = doubleArrayOf(m[12].toDouble(), m[13].toDouble(), m[14].toDouble())
        val t = DoubleArray(3) { i -> -(rq[i * 3] * c[0] + rq[i * 3 + 1] * c[1] + rq[i * 3 + 2] * c[2]) }
        return q to t
    }

    /** Row-major 3x3 rotation to a unit Hamilton quaternion (w, x, y, z) with w ≥ 0 (Shepperd's method). */
    fun quaternionFromRotation(r: DoubleArray): DoubleArray {
        require(r.size == 9)
        val r00 = r[0]; val r01 = r[1]; val r02 = r[2]
        val r10 = r[3]; val r11 = r[4]; val r12 = r[5]
        val r20 = r[6]; val r21 = r[7]; val r22 = r[8]
        val trace = r00 + r11 + r22
        // Divide by the largest of 4w², 4x², 4y², 4z² to stay accurate near half turns.
        val q = when {
            trace > 0 -> {
                val s = sqrt(trace + 1.0) * 2
                doubleArrayOf(0.25 * s, (r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s)
            }
            r00 > r11 && r00 > r22 -> {
                val s = sqrt(1.0 + r00 - r11 - r22) * 2
                doubleArrayOf((r21 - r12) / s, 0.25 * s, (r01 + r10) / s, (r02 + r20) / s)
            }
            r11 > r22 -> {
                val s = sqrt(1.0 + r11 - r00 - r22) * 2
                doubleArrayOf((r02 - r20) / s, (r01 + r10) / s, 0.25 * s, (r12 + r21) / s)
            }
            else -> {
                val s = sqrt(1.0 + r22 - r00 - r11) * 2
                doubleArrayOf((r10 - r01) / s, (r02 + r20) / s, (r12 + r21) / s, 0.25 * s)
            }
        }
        val n = sqrt(q.sumOf { it * it }) * (if (q[0] < 0) -1 else 1)
        for (i in q.indices) q[i] /= n
        return q
    }

    /** Unit Hamilton quaternion (w, x, y, z) to a row-major 3x3 rotation. */
    fun rotationFromQuaternion(q: DoubleArray): DoubleArray {
        require(q.size == 4)
        val n = sqrt(q.sumOf { it * it })
        val w = q[0] / n; val x = q[1] / n; val y = q[2] / n; val z = q[3] / n
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
            2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
            2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
        )
    }

    /** Pixel (u, v) at which our camera [cameraToWorld] sees world point [p], or null when it is not in front. */
    fun projectOpenGl(cameraToWorld: FloatArray, k: CameraIntrinsics, p: DoubleArray): DoubleArray? {
        require(cameraToWorld.size == 16 && p.size == 3)
        val m = cameraToWorld
        val d = DoubleArray(3) { p[it] - m[12 + it] }
        // Inverse of a rigid transform: camera coordinates are the offset along each camera axis.
        fun axis(a: Int) = m[a * 4] * d[0] + m[a * 4 + 1] * d[1] + m[a * 4 + 2] * d[2]
        val xc = axis(0)
        val yc = axis(1)
        val zc = axis(2)
        if (zc >= 0) return null
        return doubleArrayOf(k.fx * (xc / -zc) + k.cx, k.fy * (-yc / -zc) + k.cy)
    }

    /** Pixel (u, v) at which a COLMAP camera ([q], [t]) sees world point [p], or null when Z ≤ 0. */
    fun projectColmap(q: DoubleArray, t: DoubleArray, k: CameraIntrinsics, p: DoubleArray): DoubleArray? {
        require(t.size == 3 && p.size == 3)
        val r = rotationFromQuaternion(q)
        val x = DoubleArray(3) { i -> r[i * 3] * p[0] + r[i * 3 + 1] * p[1] + r[i * 3 + 2] * p[2] + t[i] }
        if (x[2] <= 0) return null
        return doubleArrayOf(k.fx * x[0] / x[2] + k.cx, k.fy * x[1] / x[2] + k.cy)
    }
}

/**
 * Image size from a JPEG's frame header, read without decoding the image (and without
 * Android's or the JDK's image classes, which core cannot rely on).
 */
internal object JpegSize {
    /** (width, height), or null when [file] is not a JPEG with a readable frame header. */
    fun read(file: File): IntArray? = try {
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 13)).use { read(it) }
    } catch (e: IOException) {
        null
    }

    private fun read(input: DataInputStream): IntArray? {
        if (input.readUnsignedShort() != 0xFFD8) return null
        while (true) {
            if (input.readUnsignedByte() != 0xFF) return null
            var marker = input.readUnsignedByte()
            while (marker == 0xFF) marker = input.readUnsignedByte() // fill bytes
            when (marker) {
                0xD9, 0xDA -> return null // end of image or start of scan, and no frame header yet
                0x01, in 0xD0..0xD7 -> continue // markers without a length
            }
            val length = input.readUnsignedShort()
            if (length < 2) return null
            // SOF0..SOF15 carry the size; C4 (DHT), C8 (JPG) and CC (DAC) share the range but do not.
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                input.readUnsignedByte() // sample precision
                val height = input.readUnsignedShort()
                val width = input.readUnsignedShort()
                return if (width > 0 && height > 0) intArrayOf(width, height) else null
            }
            var skip = length - 2
            while (skip > 0) {
                val n = input.skipBytes(skip)
                if (n <= 0) throw EOFException()
                skip -= n
            }
        }
    }
}
