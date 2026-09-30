package com.banyawa.sitescanner.core.mesh

import com.banyawa.sitescanner.core.capture.Capture
import com.banyawa.sitescanner.core.capture.CaptureWriter
import com.banyawa.sitescanner.core.capture.ImageDecoder
import com.banyawa.sitescanner.core.capture.ImageEncoder
import com.banyawa.sitescanner.core.capture.Manifest
import com.banyawa.sitescanner.core.capture.ProgressListener
import com.banyawa.sitescanner.core.export.Glb
import com.banyawa.sitescanner.core.export.MeshPly
import com.banyawa.sitescanner.core.geometry.Vec3
import com.banyawa.sitescanner.core.pointcloud.CameraIntrinsics
import com.banyawa.sitescanner.core.pointcloud.RgbImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A walk through a 4 × 2.7 × 5 m box room whose walls each have their own colour and
 * stripes, with a cyan pillar standing in it, photographed from 80 poses. The room's mesh
 * is built exactly (a quad grid per surface), so the texture can be checked against the
 * true colour of every triangle.
 */
class MeshTexturerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val decoder = ImageDecoder { bytes ->
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        RgbImage(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
    }

    private val encoder = ImageEncoder { image, quality -> jpeg(image.pixels, image.width, image.height, quality) }

    @Test
    fun texturesARoomFromItsPhotos() {
        val k = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
        val capture = record(80, k, k)
        val room = roomMesh(0.03f)
        val stages = ArrayList<String>()
        val start = System.nanoTime()
        val result = MeshTexturer(decoder).textureWithStats(room.mesh, capture, ProgressListener { stage, done, total -> stages += "$stage $done/$total" })
        val ms = (System.nanoTime() - start) / 1_000_000
        assertNotNull(result)
        result!!
        val stats = result.stats
        println(
            "PERF texture: ${room.mesh.triangleCount} triangles, ${capture.frames.size} frames ${k.width}x${k.height}: $ms ms " +
                "(atlas ${stats.atlasSize}, scale ${stats.scale}, ${stats.charts} charts, ${stats.atlasRects.size / 4} rects, " +
                "${stats.framesUsed} of ${stats.framesConsidered} frames used, ${stats.fallbackTriangles} untextured)",
        )
        assertTrue(stages.first() == "texture 0/160")
        assertTrue(stages.last() == "texture 160/160")

        checkTexturing(room, result, minTextured = 0.95, minTrueColour = 0.97)

        // Each wall looks its colour.
        val t = result.textured
        fun mean(surface: Int): IntArray {
            val sum = LongArray(3)
            var n = 0
            for (tri in 0 until t.triangleCount) {
                if (room.surface[tri] != surface || stats.triangleRect[tri] < 0) continue
                val rgb = sampleAtCentroid(t, tri)
                sum[0] += (rgb shr 16 and 0xFF).toLong(); sum[1] += (rgb shr 8 and 0xFF).toLong(); sum[2] += (rgb and 0xFF).toLong()
                n++
            }
            return IntArray(3) { (sum[it] / max(1, n)).toInt() }
        }
        val red = mean(RED)
        val blue = mean(BLUE)
        val green = mean(GREEN)
        val yellow = mean(YELLOW)
        assertTrue("red wall ${red.toList()}", red[0] > red[1] + 60 && red[0] > red[2] + 60)
        assertTrue("blue wall ${blue.toList()}", blue[2] > blue[0] + 60 && blue[2] > blue[1] + 60)
        assertTrue("green wall ${green.toList()}", green[1] > green[0] + 40 && green[1] > green[2] + 40)
        assertTrue("yellow wall ${yellow.toList()}", yellow[0] > yellow[2] + 60 && yellow[1] > yellow[2] + 60)

        // The pillar hides parts of the walls from some frames: none of it is painted onto them.
        var pillarOnWalls = 0
        var wallTriangles = 0
        for (tri in 0 until t.triangleCount) {
            if (room.surface[tri] == PILLAR || stats.triangleRect[tri] < 0) continue
            wallTriangles++
            val rgb = sampleAtCentroid(t, tri)
            if ((rgb shr 16 and 0xFF) < 100 && (rgb shr 8 and 0xFF) > 140 && (rgb and 0xFF) > 140) pillarOnWalls++
        }
        assertTrue("pillar colour on $pillarOnWalls of $wallTriangles wall triangles", pillarOnWalls < wallTriangles / 500)

        // And the whole thing goes into a GLB.
        writeAndCheckGlb(t, "room")
    }

    /**
     * The camera's exposure wandering from frame to frame (as a phone's auto exposure
     * does) makes every chart a different brightness: the seams show. Leveling removes
     * most of the difference, and the texture still shows the room's colours.
     */
    @Test
    fun levelsTheSeamsBetweenPhotosOfDifferentExposure() {
        val k = CameraIntrinsics(250f, 250f, 160f, 120f, 320, 240)
        val capture = record(40, k, k) { i -> 0.75f + 0.5f * ((i * 7) % 5) / 4f }
        val room = roomMesh(0.1f)
        val plain = MeshTexturer(decoder, TexturingOptions(levelSeams = false)).textureWithStats(room.mesh, capture)!!
        val levelled = MeshTexturer(decoder).textureWithStats(room.mesh, capture)!!
        // The same walk at one exposure, unlevelled: how far apart the sides of a seam are anyway (JPEG, alignment).
        val steady = MeshTexturer(decoder, TexturingOptions(levelSeams = false)).textureWithStats(room.mesh, record(40, k, k))!!
        assertNull(plain.stats.seams)
        val seams = levelled.stats.seams!!
        println("PERF seams: ${seams.charts} charts, ${seams.seamPairs} pairs, ${seams.seamDifferenceBefore} -> ${seams.seamDifferenceAfter} levels, max ${seams.maxCorrection}, ${seams.millis} ms")
        assertTrue("seams before ${seams.seamDifferenceBefore}", seams.seamDifferenceBefore > 8f)
        assertTrue("seams after ${seams.seamDifferenceAfter}", seams.seamDifferenceAfter < seams.seamDifferenceBefore / 3)
        // The step across a chart boundary, measured independently: the atlas colour of the
        // middle of every seam edge, as each side's chart shows it.
        val stepBefore = seamStep(plain)
        val stepAfter = seamStep(levelled)
        val stepSteady = seamStep(steady)
        println("PERF seam step: $stepBefore -> $stepAfter (steady exposure, unlevelled: $stepSteady)")
        assertTrue("seam step $stepBefore -> $stepAfter", stepAfter < stepBefore / 2)
        assertTrue("seam step $stepAfter, steady exposure $stepSteady", stepAfter < stepSteady * 1.3f + 1f)
        checkTexturing(room, levelled, minTextured = 0.8, minTrueColour = 0.9)
    }

    /** Mean colour step across the seams: for every edge shared by two charts, the atlas colour at its middle on each side. */
    private fun seamStep(result: TexturingResult): Float {
        val t = result.textured
        val mesh = t.mesh
        val rect = result.stats.triangleRect
        // Edges by their two positions, with the chart's inner sample point.
        val byEdge = HashMap<String, ArrayList<Pair<Int, FloatArray>>>()
        for (tri in 0 until t.triangleCount) {
            if (rect[tri] < 0) continue
            for (j in 0 until 3) {
                val a = mesh.indices[tri * 3 + j]
                val b = mesh.indices[tri * 3 + (j + 1) % 3]
                val key = listOf(a, b).map { v -> (0 until 3).map { mesh.positions[v * 3 + it] } }.sortedBy { it.toString() }.toString()
                val u = (t.uv[a * 2] + t.uv[b * 2]) / 2
                val v = (t.uv[a * 2 + 1] + t.uv[b * 2 + 1]) / 2
                byEdge.getOrPut(key) { ArrayList() }.add(rect[tri] to floatArrayOf(u, v))
            }
        }
        var sum = 0.0
        var count = 0
        for (sides in byEdge.values) {
            if (sides.size != 2 || sides[0].first == sides[1].first) continue
            val p = sample(t, sides[0].second[0], sides[0].second[1])
            val q = sample(t, sides[1].second[0], sides[1].second[1])
            for (shift in intArrayOf(16, 8, 0)) sum += abs((p shr shift and 0xFF) - (q shr shift and 0xFF))
            count += 3
        }
        assertTrue("seam edges: $count", count > 300)
        return (sum / count).toFloat()
    }

    private fun sample(t: TexturedMesh, u: Float, v: Float): Int {
        val atlas = t.atlas
        val fx = u * atlas.width - 0.5f
        val fy = v * atlas.height - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            fun at(x: Int, y: Int) = atlas.pixels[y.coerceIn(0, atlas.height - 1) * atlas.width + x.coerceIn(0, atlas.width - 1)] shr shift and 0xFF
            val top = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx
            val bottom = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx
            out = (out shl 8) or (top * (1 - ty) + bottom * ty).roundToInt().coerceIn(0, 255)
        }
        return out
    }

    @Test
    fun imagesStoredSmallerThanRecordedStillLineUp() {
        // Intrinsics recorded for 640 × 480 but the JPEGs are half that: coordinates scale.
        val recorded = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
        val stored = recorded.scaledTo(320, 240)
        val capture = record(30, recorded, stored)
        val room = roomMesh(0.1f)
        val result = MeshTexturer(decoder).textureWithStats(room.mesh, capture)!!
        checkTexturing(room, result, minTextured = 0.8, minTrueColour = 0.93)
    }

    @Test
    fun smallAtlasScalesTheChartsDown() {
        val k = CameraIntrinsics(250f, 250f, 160f, 120f, 320, 240)
        val capture = record(30, k, k)
        val room = roomMesh(0.1f)
        val result = MeshTexturer(decoder, TexturingOptions(atlasSize = 512)).textureWithStats(room.mesh, capture)!!
        assertEquals(512, result.textured.atlas.width)
        assertTrue("scale ${result.stats.scale}", result.stats.scale < 1f)
        checkTexturing(room, result, minTextured = 0.8, minTrueColour = 0.8)
        writeAndCheckGlb(result.textured, "small-atlas")
    }

    @Test
    fun longWalksAreThinnedToSpreadKeyframes() {
        val k = CameraIntrinsics(250f, 250f, 160f, 120f, 320, 240)
        val capture = record(40, k, k)
        val room = roomMesh(0.1f)
        val all = MeshTexturer(decoder).textureWithStats(room.mesh, capture)!!
        val result = MeshTexturer(decoder, TexturingOptions(maxFrames = 16)).textureWithStats(room.mesh, capture)!!
        assertEquals(40, all.stats.framesConsidered)
        assertTrue("${result.stats.framesConsidered} frames", result.stats.framesConsidered in 8..16)
        // Chosen for what they see, 16 frames texture nearly as much of the room as all 40
        // (evenly spaced ones or ones spread by pose alone managed three quarters).
        val share = result.stats.texturedTriangles.toDouble() / all.stats.texturedTriangles
        assertTrue("16 frames texture $share of what 40 do", share > 0.9)
        checkTexturing(room, result, minTextured = 0.85, minTrueColour = 0.93)
    }

    @Test
    fun nothingInViewGivesNoTexture() {
        val k = CameraIntrinsics(250f, 250f, 160f, 120f, 320, 240)
        val capture = record(5, k, k)
        // A mesh high above the room, where no camera looks.
        val far = roomMesh(0.5f).mesh.mapPositions { x, y, z, out, o -> out[o] = x; out[o + 1] = y + 100f; out[o + 2] = z }
        assertNull(MeshTexturer(decoder).texture(far, capture))
        assertNull(MeshTexturer(decoder).texture(TriangleMesh.EMPTY, capture))
    }

    @Test
    fun cancellingGivesNoTexture() {
        val k = CameraIntrinsics(250f, 250f, 160f, 120f, 320, 240)
        val capture = record(10, k, k)
        var calls = 0
        assertNull(MeshTexturer(decoder).texture(roomMesh(0.2f).mesh, capture, isCancelled = { ++calls > 3 }))
    }

    // ------------------------------------------------------------------ checks

    /**
     * The texturing is consistent (uvs in range, triangles in order and inside their
     * rectangle, one rectangle per vertex) and shows the room's true colours.
     */
    private fun checkTexturing(room: Room, result: TexturingResult, minTextured: Double, minTrueColour: Double) {
        val t = result.textured
        val stats = result.stats
        val mesh = t.mesh
        val size = t.atlas.width
        assertEquals(size, t.atlas.height)
        assertEquals(room.mesh.triangleCount, t.triangleCount)
        for (uv in t.uv) assertTrue("uv $uv", uv in 0f..1f)

        // Same triangles, same order, same vertex colours.
        for (tri in 0 until t.triangleCount) {
            for (j in 0 until 3) {
                val a = room.mesh.indices[tri * 3 + j]
                val b = mesh.indices[tri * 3 + j]
                for (c in 0 until 3) {
                    assertEquals(room.mesh.positions[a * 3 + c], mesh.positions[b * 3 + c], 0f)
                    assertEquals(room.mesh.colors[a * 3 + c], mesh.colors[b * 3 + c])
                }
            }
        }

        // Each triangle's corners lie in the atlas rectangle of its chart; each vertex in one rectangle.
        val rectOfVertex = IntArray(mesh.vertexCount) { Int.MIN_VALUE }
        for (tri in 0 until t.triangleCount) {
            val r = stats.triangleRect[tri]
            for (j in 0 until 3) {
                val v = mesh.indices[tri * 3 + j]
                if (rectOfVertex[v] == Int.MIN_VALUE) rectOfVertex[v] = r
                assertEquals("vertex $v shared across charts", rectOfVertex[v], r)
                val x = t.uv[v * 2] * size
                val y = t.uv[v * 2 + 1] * size
                if (r >= 0) {
                    val rx = stats.atlasRects[r * 4]; val ry = stats.atlasRects[r * 4 + 1]
                    val rw = stats.atlasRects[r * 4 + 2]; val rh = stats.atlasRects[r * 4 + 3]
                    assertTrue("triangle $tri corner ($x, $y) outside rect $rx $ry $rw $rh", x >= rx && x <= rx + rw && y >= ry && y <= ry + rh)
                }
            }
            if (r < 0) {
                // A solid patch: all three corners on one spot, in the triangle's vertex colour.
                val a = mesh.indices[tri * 3]; val b = mesh.indices[tri * 3 + 1]; val c = mesh.indices[tri * 3 + 2]
                assertEquals(t.uv[a * 2], t.uv[b * 2], 0f); assertEquals(t.uv[a * 2], t.uv[c * 2], 0f)
                val rgb = sampleAtCentroid(t, tri)
                for ((ch, shift) in listOf(16, 8, 0).withIndex()) {
                    val mean = (0 until 3).sumOf { mesh.colors[mesh.indices[tri * 3 + it] * 3 + ch].toInt() and 0xFF } / 3
                    assertTrue("patch colour", abs((rgb shr shift and 0xFF) - mean) <= 20)
                }
            }
        }
        // No two rectangles overlap.
        val rects = stats.atlasRects
        val covered = BooleanArray(size * size)
        for (r in 0 until rects.size / 4) {
            assertTrue(rects[r * 4] + rects[r * 4 + 2] <= size && rects[r * 4 + 1] + rects[r * 4 + 3] <= size)
            for (y in rects[r * 4 + 1] until rects[r * 4 + 1] + rects[r * 4 + 3]) {
                for (x in rects[r * 4] until rects[r * 4] + rects[r * 4 + 2]) {
                    if (covered[y * size + x]) throw AssertionError("rectangle $r overlaps another at $x, $y")
                    covered[y * size + x] = true
                }
            }
        }

        val textured = stats.texturedTriangles.toDouble() / t.triangleCount
        assertEquals(t.triangleCount, stats.texturedTriangles + stats.fallbackTriangles)
        assertTrue("textured: $textured", textured > minTextured)

        // The atlas shows each triangle's true colour (away from colour edges, which JPEG and filtering blur).
        var checked = 0
        var right = 0
        val p = FloatArray(3)
        for (tri in 0 until t.triangleCount) {
            if (stats.triangleRect[tri] < 0) continue
            for (c in 0 until 3) p[c] = (0 until 3).sumOf { mesh.positions[mesh.indices[tri * 3 + it] * 3 + c].toDouble() }.toFloat() / 3f
            val s = room.surface[tri]
            if (nearColourEdge(s, p[0], p[1], p[2])) continue
            checked++
            val truth = surfaceColor(s, p[0], p[1], p[2])
            val got = sampleAtCentroid(t, tri)
            val diff = maxOf(abs((truth shr 16 and 0xFF) - (got shr 16 and 0xFF)), abs((truth shr 8 and 0xFF) - (got shr 8 and 0xFF)), abs((truth and 0xFF) - (got and 0xFF)))
            if (diff <= 40) right++
        }
        println("true colour: $right of $checked checked triangles; textured ${"%.3f".format(textured)}")
        stats.seams?.let { println("PERF seams: ${it.charts} charts, ${it.seamPairs} pairs, ${it.seamDifferenceBefore} -> ${it.seamDifferenceAfter} levels, max ${it.maxCorrection}, ${it.millis} ms") }
        assertTrue("true colour: $right of $checked", checked > t.triangleCount / 3 && right >= checked * minTrueColour)
    }

    private fun writeAndCheckGlb(textured: TexturedMesh, name: String) {
        val dir = System.getenv("TEXTURE_TEST_OUT")?.let { File(it).apply { mkdirs() } } ?: tmp.root
        val file = File(dir, "textured-$name.glb")
        file.outputStream().use { Glb.write(textured, it, name, encoder) }
        println("GLB written to ${file.path} (${file.length() / 1024} KB)")
        if (dir != tmp.root) {
            // To look at: the mesh with its texture coordinates and the atlas.
            MeshPly.write(textured.mesh, File(dir, "textured-$name.ply"), uv = textured.uv)
            File(dir, "textured-$name-atlas.jpg").writeBytes(encoder.encodeJpeg(textured.atlas, 90))
        }
        val bytes = file.readBytes()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(Glb.MAGIC, b.getInt(0))
        assertEquals(2, b.getInt(4))
        assertEquals(bytes.size, b.getInt(8))
        val jsonLength = b.getInt(12)
        assertEquals(Glb.CHUNK_JSON, b.getInt(16))
        assertEquals(0, jsonLength % 4)
        val json = Json.parseToJsonElement(String(bytes, 20, jsonLength, Charsets.UTF_8)).jsonObject
        val binLength = b.getInt(20 + jsonLength)
        assertEquals(Glb.CHUNK_BIN, b.getInt(24 + jsonLength))
        assertEquals(0, binLength % 4)
        assertEquals(bytes.size, 28 + jsonLength + binLength)
        val bin = 28 + jsonLength
        val declared = json["buffers"]!!.jsonArray[0].jsonObject["byteLength"]!!.jsonPrimitive.int
        assertTrue(declared <= binLength && binLength - declared < 4)
        val views = json["bufferViews"]!!.jsonArray.map { it.jsonObject }
        for (view in views) {
            val offset = view["byteOffset"]!!.jsonPrimitive.int
            assertEquals(0, offset % 4)
            assertTrue(offset + view["byteLength"]!!.jsonPrimitive.int <= declared)
        }

        val primitive = json["meshes"]!!.jsonArray[0].jsonObject["primitives"]!!.jsonArray[0].jsonObject
        val attributes = primitive["attributes"]!!.jsonObject
        val accessors = json["accessors"]!!.jsonArray.map { it.jsonObject }
        val uvAccessor = accessors[attributes["TEXCOORD_0"]!!.jsonPrimitive.int]
        assertEquals("VEC2", uvAccessor["type"]!!.jsonPrimitive.content)
        assertEquals(textured.vertexCount, uvAccessor["count"]!!.jsonPrimitive.int)
        val uvOffset = bin + views[uvAccessor["bufferView"]!!.jsonPrimitive.int]["byteOffset"]!!.jsonPrimitive.int
        for (i in 0 until minOf(1000, textured.uv.size)) assertEquals(textured.uv[i], b.getFloat(uvOffset + i * 4), 0f)
        assertTrue(attributes.containsKey("POSITION") && attributes.containsKey("NORMAL"))
        // Vertex colours are kept, but not as COLOR_0, which would tint the photos.
        assertTrue(attributes.containsKey("_COLOR_0") && !attributes.containsKey("COLOR_0"))

        val material = json["materials"]!!.jsonArray[primitive["material"]!!.jsonPrimitive.int].jsonObject
        val textureIndex = material["pbrMetallicRoughness"]!!.jsonObject["baseColorTexture"]!!.jsonObject["index"]!!.jsonPrimitive.int
        assertEquals(0, textureIndex)
        val texture = json["textures"]!!.jsonArray[textureIndex].jsonObject
        assertEquals(0, texture["sampler"]!!.jsonPrimitive.int)
        val image = json["images"]!!.jsonArray[texture["source"]!!.jsonPrimitive.int].jsonObject
        assertEquals("image/jpeg", image["mimeType"]!!.jsonPrimitive.content)
        val imageView = views[image["bufferView"]!!.jsonPrimitive.int]
        val imageOffset = bin + imageView["byteOffset"]!!.jsonPrimitive.int
        val imageLength = imageView["byteLength"]!!.jsonPrimitive.int
        assertEquals(0xFF, bytes[imageOffset].toInt() and 0xFF)
        assertEquals(0xD8, bytes[imageOffset + 1].toInt() and 0xFF)
        val atlas = ImageIO.read(ByteArrayInputStream(bytes, imageOffset, imageLength))
        assertEquals(textured.atlas.width, atlas.width)
        // The JPEG shows what the atlas holds: photos closely, the solid patches exactly (no hue from their neighbours).
        var close = 0
        var patches = 0
        for (tri in 0 until textured.triangleCount) {
            val v = IntArray(3) { textured.mesh.indices[tri * 3 + it] }
            val u = v.sumOf { textured.uv[it * 2].toDouble() } / 3
            val w = v.sumOf { textured.uv[it * 2 + 1].toDouble() } / 3
            val x = (u * atlas.width).toInt().coerceIn(0, atlas.width - 1)
            val y = (w * atlas.height).toInt().coerceIn(0, atlas.height - 1)
            val want = textured.atlas.pixels[y * atlas.width + x]
            val got = atlas.getRGB(x, y)
            val diff = maxOf(abs((want shr 16 and 0xFF) - (got shr 16 and 0xFF)), abs((want shr 8 and 0xFF) - (got shr 8 and 0xFF)), abs((want and 0xFF) - (got and 0xFF)))
            if (diff <= 30) close++
            if (textured.uv[v[0] * 2] == textured.uv[v[1] * 2] && textured.uv[v[0] * 2] == textured.uv[v[2] * 2]) {
                patches++
                assertTrue("patch colour ${Integer.toHexString(want)} encoded as ${Integer.toHexString(got and 0xFFFFFF)}", diff <= 8)
            }
        }
        println("JPEG atlas: $close of ${textured.triangleCount} triangles close, $patches solid patches")
        assertTrue(close >= textured.triangleCount * 0.97)

        // The Khronos validator, where installed: GLTF_VALIDATOR=<script printing {"errors": n, ...}> (run with node).
        val validator = System.getenv("GLTF_VALIDATOR")?.let { File(it) }
        if (validator != null && validator.isFile) {
            val process = ProcessBuilder("node", validator.path, file.path).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            println("glTF validator on ${file.name}: $output")
            assertTrue(output, Regex("\"errors\":\\s*0\\b").containsMatchIn(output))
        }
    }

    private fun sampleAtCentroid(t: TexturedMesh, tri: Int): Int {
        var u = 0f
        var v = 0f
        for (j in 0 until 3) {
            val vertex = t.mesh.indices[tri * 3 + j]
            u += t.uv[vertex * 2] / 3f
            v += t.uv[vertex * 2 + 1] / 3f
        }
        val atlas = t.atlas
        val fx = u * atlas.width - 0.5f
        val fy = v * atlas.height - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            fun at(x: Int, y: Int) = atlas.pixels[y.coerceIn(0, atlas.height - 1) * atlas.width + x.coerceIn(0, atlas.width - 1)] shr shift and 0xFF
            val top = at(x0, y0) * (1 - tx) + at(x0 + 1, y0) * tx
            val bottom = at(x0, y0 + 1) * (1 - tx) + at(x0 + 1, y0 + 1) * tx
            out = (out shl 8) or (top * (1 - ty) + bottom * ty).roundToInt().coerceIn(0, 255)
        }
        return out
    }

    // ------------------------------------------------------------------ the room

    private class Room(val mesh: TriangleMesh, val surface: IntArray)

    /** Quad grids of about [cell] metres on every surface, facing into the room (the pillar's facing out). */
    private fun roomMesh(cell: Float): Room {
        val positions = ArrayList<Float>()
        val colors = ArrayList<Byte>()
        val indices = ArrayList<Int>()
        val surfaces = ArrayList<Int>()
        fun grid(surface: Int, o: Vec3, u: Vec3, lu: Float, v: Vec3, lv: Float, skip: (Vec3) -> Boolean = { false }) {
            val nu = max(1, (lu / cell).roundToInt())
            val nv = max(1, (lv / cell).roundToInt())
            val base = positions.size / 3
            for (j in 0..nv) for (i in 0..nu) {
                val p = o + u * (lu * i / nu) + v * (lv * j / nv)
                positions += p.x; positions += p.y; positions += p.z
                val rgb = surfaceColor(surface, p.x, p.y, p.z)
                colors += (rgb shr 16).toByte(); colors += (rgb shr 8).toByte(); colors += rgb.toByte()
            }
            for (j in 0 until nv) for (i in 0 until nu) {
                val centre = o + u * (lu * (i + 0.5f) / nu) + v * (lv * (j + 0.5f) / nv)
                if (skip(centre)) continue
                val p00 = base + j * (nu + 1) + i
                val p10 = p00 + 1
                val p01 = p00 + nu + 1
                val p11 = p01 + 1
                indices += listOf(p00, p10, p11, p00, p11, p01)
                surfaces += surface; surfaces += surface
            }
        }
        val x = Vec3(1f, 0f, 0f)
        val y = Vec3(0f, 1f, 0f)
        val z = Vec3(0f, 0f, 1f)
        val underPillar = { p: Vec3 -> p.x in PILLAR_X0..PILLAR_X1 && p.z in PILLAR_Z0..PILLAR_Z1 }
        grid(RED, Vec3(0f, 0f, -5f), y, H, z, 5f)
        grid(BLUE, Vec3(4f, 0f, -5f), z, 5f, y, H)
        grid(GREEN, Vec3(0f, 0f, 0f), y, H, x, 4f)
        grid(YELLOW, Vec3(0f, 0f, -5f), x, 4f, y, H)
        grid(FLOOR, Vec3(0f, 0f, -5f), z, 5f, x, 4f, underPillar)
        grid(CEILING, Vec3(0f, H, -5f), x, 4f, z, 5f, underPillar)
        val pw = PILLAR_X1 - PILLAR_X0
        val pd = PILLAR_Z1 - PILLAR_Z0
        grid(PILLAR, Vec3(PILLAR_X0, 0f, PILLAR_Z0), z, pd, y, H)
        grid(PILLAR, Vec3(PILLAR_X1, 0f, PILLAR_Z0), y, H, z, pd)
        grid(PILLAR, Vec3(PILLAR_X0, 0f, PILLAR_Z0), y, H, x, pw)
        grid(PILLAR, Vec3(PILLAR_X0, 0f, PILLAR_Z1), x, pw, y, H)
        return Room(TriangleMesh(positions.toFloatArray(), colors.toByteArray(), indices.toIntArray()), surfaces.toIntArray())
    }

    /** Wall colours with 25 cm stripes (10 cm dark), a checkered floor, a plain ceiling and a banded pillar. */
    private fun surfaceColor(surface: Int, x: Float, y: Float, z: Float): Int = when (surface) {
        RED -> stripes(0xD02828, z)
        BLUE -> stripes(0x2838D0, z)
        GREEN -> stripes(0x30B040, x)
        YELLOW -> stripes(0xD0C030, x)
        FLOOR -> if ((floor(x / 0.5f).toInt() + floor(z / 0.5f).toInt()) % 2 == 0) 0x909090 else 0x585858
        CEILING -> 0xE8E8E8
        else -> stripes(0x30C8C8, y)
    }

    private fun stripes(rgb: Int, h: Float): Int {
        val phase = h / 0.25f - floor(h / 0.25f)
        if (phase >= 0.4f) return rgb
        fun ch(shift: Int) = ((rgb shr shift and 0xFF) * 0.55f).toInt()
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Within 4 cm of a stripe or checker edge, or of another surface. */
    private fun nearColourEdge(surface: Int, x: Float, y: Float, z: Float): Boolean {
        val m = 0.04f
        val c = surfaceColor(surface, x, y, z)
        for ((dx, dy, dz) in listOf(Triple(m, 0f, 0f), Triple(-m, 0f, 0f), Triple(0f, m, 0f), Triple(0f, -m, 0f), Triple(0f, 0f, m), Triple(0f, 0f, -m))) {
            if (surfaceColor(surface, x + dx, y + dy, z + dz) != c) return true
        }
        if (surface != RED && x < m || surface != BLUE && x > 4f - m) return true
        if (surface != FLOOR && y < m || surface != CEILING && y > H - m) return true
        if (surface != GREEN && z > -m || surface != YELLOW && z < -5f + m) return true
        val nearPillar = x in PILLAR_X0 - m..PILLAR_X1 + m && z in PILLAR_Z0 - m..PILLAR_Z1 + m
        if (surface != PILLAR) return nearPillar
        val nearX = abs(x - PILLAR_X0) < m || abs(x - PILLAR_X1) < m
        val nearZ = abs(z - PILLAR_Z0) < m || abs(z - PILLAR_Z1) < m
        return nearX && nearZ
    }

    /** The surface a ray from the eye meets first, and its colour at the hit. */
    private fun trace(ex: Float, ey: Float, ez: Float, dx: Float, dy: Float, dz: Float): Int {
        var best = Float.MAX_VALUE
        var surface = -1
        fun plane(o: Float, d: Float, p: Float, s: Int) {
            if (d == 0f) return
            val t = (p - o) / d
            if (t > 1e-4f && t < best) { best = t; surface = s }
        }
        if (dx < 0f) plane(ex, dx, 0f, RED) else plane(ex, dx, 4f, BLUE)
        if (dz > 0f) plane(ez, dz, 0f, GREEN) else plane(ez, dz, -5f, YELLOW)
        if (dy < 0f) plane(ey, dy, 0f, FLOOR) else plane(ey, dy, H, CEILING)
        // The pillar, a box: slab test for where the ray enters it.
        var tNear = -Float.MAX_VALUE
        var tFar = Float.MAX_VALUE
        var hits = true
        fun slab(o: Float, d: Float, lo: Float, hi: Float) {
            if (d == 0f) {
                if (o < lo || o > hi) hits = false
                return
            }
            val t1 = (lo - o) / d
            val t2 = (hi - o) / d
            tNear = max(tNear, minOf(t1, t2))
            tFar = minOf(tFar, max(t1, t2))
        }
        slab(ex, dx, PILLAR_X0, PILLAR_X1)
        slab(ey, dy, 0f, H)
        slab(ez, dz, PILLAR_Z0, PILLAR_Z1)
        if (hits && tNear > 1e-4f && tNear <= tFar && tNear < best) { best = tNear; surface = PILLAR }
        return surfaceColor(surface, ex + dx * best, ey + dy * best, ez + dz * best)
    }

    private fun lookAt(eye: Vec3, target: Vec3): FloatArray {
        val f = (target - eye).normalized()
        val r = (f cross Vec3.UP).normalized()
        val u = r cross f
        return floatArrayOf(r.x, r.y, r.z, 0f, u.x, u.y, u.z, 0f, -f.x, -f.y, -f.z, 0f, eye.x, eye.y, eye.z, 1f)
    }

    /**
     * [frames] photos along a loop through the room, turning twice around and tilting up
     * and down; the poses and [recorded] intrinsics are stored, the images rendered with
     * [stored] (their actual size).
     */
    private fun record(frames: Int, recorded: CameraIntrinsics, stored: CameraIntrinsics, exposure: (Int) -> Float = { 1f }): Capture {
        val dir = tmp.newFolder()
        val writer = CaptureWriter(dir)
        val pixels = IntArray(stored.width * stored.height)
        for (i in 0 until frames) {
            val a = i * 2.0 * Math.PI / frames
            val eye = Vec3(2f + 0.9f * cos(a).toFloat(), 1.35f + 0.15f * sin(3 * a).toFloat(), -2.5f + 1.3f * sin(a).toFloat())
            val yaw = 2 * a + 0.3
            val pitch = 0.95 * sin(7 * a)
            val forward = Vec3((sin(yaw) * cos(pitch)).toFloat(), sin(pitch).toFloat(), (cos(yaw) * cos(pitch)).toFloat())
            val pose = lookAt(eye, eye + forward)
            val k = stored
            for (v in 0 until k.height) for (u in 0 until k.width) {
                // As DepthUnprojector: pixel centres at integer coordinates, image y down.
                val nx = (u - k.cx) / k.fx
                val ny = (v - k.cy) / k.fy
                val dx = pose[0] * nx - pose[4] * ny - pose[8]
                val dy = pose[1] * nx - pose[5] * ny - pose[9]
                val dz = pose[2] * nx - pose[6] * ny - pose[10]
                pixels[v * k.width + u] = exposed(trace(eye.x, eye.y, eye.z, dx, dy, dz), exposure(i))
            }
            // 0.3 s apart: turning at about half a radian a second, as someone scanning a room.
            writer.add(i * 300_000_000L, pose, jpeg(pixels, k.width, k.height, 92), recorded)
        }
        writer.close(Manifest(device = "synthetic", floorY = 0f))
        return Capture.open(dir)
    }

    /** [rgb] photographed at [gain] times the exposure. */
    private fun exposed(rgb: Int, gain: Float): Int {
        if (gain == 1f) return rgb
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) out = (out shl 8) or ((rgb shr shift and 0xFF) * gain).roundToInt().coerceIn(0, 255)
        return out
    }

    private fun jpeg(pixels: IntArray, w: Int, h: Int, quality: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, pixels, 0, w)
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = quality / 100f
        }
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }

    private companion object {
        const val RED = 0
        const val BLUE = 1
        const val GREEN = 2
        const val YELLOW = 3
        const val FLOOR = 4
        const val CEILING = 5
        const val PILLAR = 6
        const val H = 2.7f
        const val PILLAR_X0 = 2.7f
        const val PILLAR_X1 = 3.1f
        const val PILLAR_Z0 = -0.9f
        const val PILLAR_Z1 = -0.5f
    }
}
