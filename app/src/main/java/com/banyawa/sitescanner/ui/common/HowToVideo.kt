package com.banyawa.sitescanner.ui.common

import android.content.Context
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.banyawa.sitescanner.R
import com.banyawa.sitescanner.scan.ScanGuidePrefs
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The demonstration of how to walk a scan, drawn frame by frame (so it plays on every
 * phone, needs no codec and no file): a person with a phone in a room seen from above,
 * the surfaces the camera has covered turning green. Loops; a tap pauses and resumes; the
 * caption of the scene playing shows under it and, unless muted, the phone's text-to-speech
 * reads it out (in the app's language when it has that voice, else in English).
 * docs/scan_howto_render.py draws the same scenes to an MP4 for sharing outside the app.
 */
@Composable
fun HowToVideo(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val captions = stringArrayResource(R.array.scan_video_captions)
    var time by remember { mutableFloatStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var narrating by remember { mutableStateOf(ScanGuidePrefs.narrationWanted(context)) }
    var voice by remember { mutableStateOf(Narrator.Voice.STARTING) }
    val narrator = remember { Narrator(context, captions) { voice = it } }
    DisposableEffect(narrator) { onDispose { narrator.release() } }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time = (time + (now - last) / 1e9f) % HowToScenes.TOTAL_SEC
                last = now
            }
        }
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(HowToScenes.BG)
                .clickable { paused = !paused },
        ) {
            Canvas(Modifier.fillMaxSize()) { HowToScenes.draw(this, time) }
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(time / HowToScenes.TOTAL_SEC)
                    .height(3.dp)
                    .background(HowToScenes.GREEN),
            )
            if (paused) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(56.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(8.dp),
                )
            }
            IconButton(
                onClick = {
                    narrating = !narrating
                    ScanGuidePrefs.setNarrationWanted(context, narrating)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp),
            ) {
                Icon(
                    if (narrating) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = stringResource(if (narrating) R.string.howto_narration_off else R.string.howto_narration_on),
                    tint = Color.White,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(6.dp),
                )
            }
        }
        val scene = HowToScenes.sceneAt(time).coerceIn(0, captions.size - 1)
        // Read each scene's caption as it starts; silence while paused or muted.
        LaunchedEffect(scene, paused, narrating, voice) {
            if (!paused && narrating && voice == Narrator.Voice.READY) narrator.speak(scene) else narrator.stop()
        }
        Text(
            captions.getOrElse(scene) { "" },
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            minLines = 2,
        )
        if (narrating && voice == Narrator.Voice.NONE) {
            Text(
                stringResource(R.string.howto_no_voice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Reads the captions with the phone's text-to-speech engine: in the app's language when
 * the engine has that voice, else in English (the captions' English text), else not at all.
 */
private class Narrator(private val context: Context, private val captions: Array<String>, private val onVoice: (Voice) -> Unit) {
    enum class Voice { STARTING, READY, NONE }

    private var engine: TextToSpeech? = null
    private var english = false
    private var released = false

    init {
        engine = TextToSpeech(context) { status ->
            if (released) return@TextToSpeech
            val tts = engine
            if (status != TextToSpeech.SUCCESS || tts == null) {
                onVoice(Voice.NONE)
                return@TextToSpeech
            }
            val wanted = Locale.getDefault()
            val chosen = listOf(wanted, Locale.ENGLISH).firstOrNull { available(tts, it) }
            if (chosen == null) {
                onVoice(Voice.NONE)
            } else {
                runCatching { tts.setLanguage(chosen) }
                english = chosen.language == Locale.ENGLISH.language && wanted.language != Locale.ENGLISH.language
                onVoice(Voice.READY)
            }
        }
    }

    private fun available(tts: TextToSpeech, locale: Locale): Boolean =
        runCatching { tts.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)

    /** The English captions, for a phone that speaks English but not the app's language. */
    private val englishCaptions: Array<String> by lazy {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.ENGLISH)
        context.createConfigurationContext(configuration).resources.getStringArray(R.array.scan_video_captions)
    }

    fun speak(scene: Int) {
        val tts = engine ?: return
        val lines = if (english) englishCaptions else captions
        val text = lines.getOrNull(scene) ?: return
        runCatching { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "howto$scene") }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }

    fun release() {
        released = true
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
    }
}

/** The scenes, in a 960 × 540 design space scaled to the canvas; times in seconds. */
internal object HowToScenes {
    const val TOTAL_SEC = 39f

    /** Where each scene (and its caption) starts: posture, walk, spin, corners, door, glass, save. */
    private val STARTS = floatArrayOf(0f, 6f, 13f, 17.5f, 23.5f, 28.5f, 34f)

    val BG = Color(0xFF1E2530)
    val GREEN = Color(0xFF4CFF7A)
    private val FLOOR = Color(0xFF2C3440)
    private val WALL = Color(0xFFD8DEE9)
    private val WALL_DONE = Color(0xFF4CDC7A)
    private val PERSON = Color(0xFFFFA000)
    private val PHONE = Color.White
    private val CONE = Color(0x464CC3FF)
    private val CONE_EDGE = Color(0xA04CC3FF)
    private val RED = Color(0xFFFF5252)
    private val FURNITURE = Color(0xFF786450)
    private val GLASS = Color(0xFF78B4FF)
    private val LAMP = Color(0xFFFFE678)

    private const val PX = 100f // pixels per metre
    private const val SEGMENTS = 40 // coverage resolution per wall
    private const val STEP_SEC = 0.1f // simulation step for what the camera has covered

    fun sceneAt(time: Float): Int = STARTS.indexOfLast { it <= time }.coerceAtLeast(0)

    fun draw(scope: DrawScope, time: Float) = with(scope) {
        val s = size.width / 960f
        withTransform({ scale(s, s, Offset.Zero) }) {
            val i = sceneAt(time)
            val t = time - STARTS[i]
            when (i) {
                0 -> posture(t)
                1 -> roomScene(t, spin = false)
                2 -> roomScene(t, spin = true)
                3 -> corners(t)
                4 -> door(t)
                5 -> glass(t)
                else -> save(t)
            }
        }
    }

    // ------------------------------------------------------------ the room

    private class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)
    private class Furniture(val x: Float, val y: Float, val w: Float, val h: Float)
    private class Pose(val x: Float, val y: Float, val ang: Float, val reach: Float = 2.6f)

    private class Room(val ox: Float, val oy: Float, val walls: List<Seg>, val furniture: List<Furniture> = emptyList()) {
        fun p(x: Float, y: Float) = Offset(ox + x * PX, oy + y * PX)
    }

    private val ROOM = Room(
        180f, 90f,
        listOf(Seg(0f, 0f, 6f, 0f), Seg(6f, 0f, 6f, 3.6f), Seg(6f, 3.6f, 0f, 3.6f), Seg(0f, 3.6f, 0f, 0f)),
        listOf(Furniture(3.6f, 0.3f, 1.4f, 0.8f)),
    )
    private val ROOMS = Room(
        130f, 90f,
        listOf(
            Seg(0f, 0f, 7f, 0f), Seg(7f, 0f, 7f, 3.6f), Seg(7f, 3.6f, 0f, 3.6f), Seg(0f, 3.6f, 0f, 0f),
            Seg(3.5f, 0f, 3.5f, 1.3f), Seg(3.5f, 2.3f, 3.5f, 3.6f),
        ),
    )

    /** Which wall segments (and furniture) the camera has had inside its cone along [poses]. */
    private class Coverage(room: Room) {
        val walls = BooleanArray(room.walls.size * SEGMENTS)
        val furniture = BooleanArray(room.furniture.size)
        private val room = room

        fun add(pose: Pose, fov: Float = (PI / 3).toFloat()) {
            for ((i, w) in room.walls.withIndex()) {
                for (k in 0 until SEGMENTS) {
                    val f = (k + 0.5f) / SEGMENTS
                    val dx = w.x1 + (w.x2 - w.x1) * f - pose.x
                    val dy = w.y1 + (w.y2 - w.y1) * f - pose.y
                    if (hypot(dx, dy) > pose.reach) continue
                    if (abs(angleDiff(atan2(dy, dx), pose.ang)) < fov / 2) walls[i * SEGMENTS + k] = true
                }
            }
            for ((j, f) in room.furniture.withIndex()) {
                val dx = f.x + f.w / 2 - pose.x
                val dy = f.y + f.h / 2 - pose.y
                if (hypot(dx, dy) < 1.6f && abs(angleDiff(atan2(dy, dx), pose.ang)) < fov / 2) furniture[j] = true
            }
        }

        private fun angleDiff(a: Float, b: Float): Float {
            var d = (a - b) % (2 * PI.toFloat())
            if (d > PI) d -= 2 * PI.toFloat()
            if (d < -PI) d += 2 * PI.toFloat()
            return d
        }
    }

    private fun DrawScope.drawRoom(room: Room, cover: Coverage?) {
        val xs = room.walls.flatMap { listOf(it.x1, it.x2) }
        val ys = room.walls.flatMap { listOf(it.y1, it.y2) }
        val a = room.p(xs.min(), ys.min())
        val b = room.p(xs.max(), ys.max())
        drawRect(FLOOR, a, Size(b.x - a.x, b.y - a.y))
        for ((j, f) in room.furniture.withIndex()) {
            val done = cover?.furniture?.get(j) == true
            val tl = room.p(f.x, f.y)
            drawRect(if (done) WALL_DONE else FURNITURE, tl, Size(f.w * PX, f.h * PX))
        }
        for ((i, w) in room.walls.withIndex()) {
            drawLine(WALL, room.p(w.x1, w.y1), room.p(w.x2, w.y2), strokeWidth = 10f)
            if (cover == null) continue
            for (k in 0 until SEGMENTS) {
                if (!cover.walls[i * SEGMENTS + k]) continue
                val f0 = k.toFloat() / SEGMENTS
                val f1 = (k + 1f) / SEGMENTS
                drawLine(WALL_DONE, room.p(w.x1 + (w.x2 - w.x1) * f0, w.y1 + (w.y2 - w.y1) * f0), room.p(w.x1 + (w.x2 - w.x1) * f1, w.y1 + (w.y2 - w.y1) * f1), strokeWidth = 10f)
            }
        }
    }

    private fun DrawScope.drawPerson(room: Room, pose: Pose, cone: Boolean = true, fov: Float = (PI / 3).toFloat()) {
        val c = room.p(pose.x, pose.y)
        if (cone) {
            val path = Path()
            path.moveTo(c.x, c.y)
            for (k in 0..20) {
                val a = pose.ang - fov / 2 + fov * k / 20
                path.lineTo(c.x + cos(a) * pose.reach * PX, c.y + sin(a) * pose.reach * PX)
            }
            path.close()
            drawPath(path, CONE)
            drawPath(path, CONE_EDGE, style = Stroke(width = 2f))
        }
        drawCircle(PERSON, 16f, c)
        drawCircle(Color.Black, 16f, c, style = Stroke(width = 2f))
        // the phone, held out in front
        val f = Offset(c.x + cos(pose.ang) * 22f, c.y + sin(pose.ang) * 22f)
        val n = Offset(-sin(pose.ang) * 9f, cos(pose.ang) * 9f)
        drawLine(PHONE, f + n, f - n, strokeWidth = 5f)
    }

    private fun DrawScope.mark(ok: Boolean, x: Float, y: Float, r: Float = 34f) {
        drawCircle(if (ok) GREEN else RED, r, Offset(x, y))
        if (ok) {
            drawLine(Color.Black, Offset(x - r * 0.45f, y), Offset(x - r * 0.1f, y + r * 0.4f), strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(Color.Black, Offset(x - r * 0.1f, y + r * 0.4f), Offset(x + r * 0.5f, y - r * 0.4f), strokeWidth = 8f, cap = StrokeCap.Round)
        } else {
            drawLine(Color.White, Offset(x - r * 0.4f, y - r * 0.4f), Offset(x + r * 0.4f, y + r * 0.4f), strokeWidth = 8f, cap = StrokeCap.Round)
            drawLine(Color.White, Offset(x - r * 0.4f, y + r * 0.4f), Offset(x + r * 0.4f, y - r * 0.4f), strokeWidth = 8f, cap = StrokeCap.Round)
        }
    }

    /** The blinking recording dot. */
    private fun DrawScope.rec(t: Float) {
        if ((t * 2).toInt() % 2 == 0) drawCircle(RED, 14f, Offset(38f, 38f))
        drawCircle(RED, 14f, Offset(38f, 38f), style = Stroke(width = 3f))
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3 - 2 * x)
    }

    // ------------------------------------------------------------ scenes

    /** Holding the phone upright at chest height; then, briefly, how not to. */
    private fun DrawScope.posture(t: Float) {
        drawLine(WALL, Offset(0f, 470f), Offset(960f, 470f), strokeWidth = 6f)
        val x0 = 330f
        drawCircle(PERSON, 40f, Offset(x0, 160f))
        drawLine(PERSON, Offset(x0, 200f), Offset(x0, 380f), strokeWidth = 22f, cap = StrokeCap.Round)
        drawLine(PERSON, Offset(x0, 380f), Offset(x0 - 50f, 470f), strokeWidth = 18f, cap = StrokeCap.Round)
        drawLine(PERSON, Offset(x0, 380f), Offset(x0 + 50f, 470f), strokeWidth = 18f, cap = StrokeCap.Round)
        if (t <= 3.8f) {
            drawLine(PERSON, Offset(x0, 240f), Offset(x0 + 110f, 260f), strokeWidth = 18f, cap = StrokeCap.Round)
            drawRect(PHONE, Offset(x0 + 110f, 220f), Size(22f, 80f))
            val cone = Path().apply { moveTo(x0 + 132f, 260f); lineTo(900f, 130f); lineTo(900f, 390f); close() }
            drawPath(cone, CONE)
            drawPath(cone, CONE_EDGE, style = Stroke(width = 2f))
            drawLine(WALL, Offset(x0 - 120f, 260f), Offset(x0 - 60f, 260f), strokeWidth = 3f)
            mark(true, 860f, 80f)
        } else {
            drawLine(PERSON, Offset(x0, 240f), Offset(x0 + 100f, 330f), strokeWidth = 18f, cap = StrokeCap.Round)
            val phone = Path().apply { moveTo(x0 + 100f, 320f); lineTo(x0 + 170f, 350f); lineTo(x0 + 160f, 372f); lineTo(x0 + 90f, 342f); close() }
            drawPath(phone, PHONE)
            val cone = Path().apply { moveTo(x0 + 165f, 360f); lineTo(700f, 470f); lineTo(420f, 470f); close() }
            drawPath(cone, RED.copy(alpha = 0.25f))
            drawPath(cone, RED.copy(alpha = 0.6f), style = Stroke(width = 2f))
            mark(false, 860f, 80f)
        }
    }

    /** Walking sideways along the bottom wall 1.5 m from it (or, in the spin scene, turning on the spot). */
    private fun walkPose(t: Float): Pose = Pose(0.6f + 4.8f * ease(t / 6.5f), 3.6f - 1.5f, (PI / 2).toFloat())

    private fun DrawScope.roomScene(t: Float, spin: Boolean) {
        val cover = Coverage(ROOM)
        var u = 0f
        while (u <= (if (spin) 6.5f else minOf(t, 6.5f))) {
            cover.add(walkPose(u))
            u += STEP_SEC
        }
        drawRoom(ROOM, cover)
        if (!spin) {
            val pose = walkPose(t)
            drawPerson(ROOM, pose)
            // the gap to the wall
            val gx = ROOM.p(pose.x, pose.y).x + 60f
            drawLine(Color.White, Offset(gx, ROOM.p(0f, pose.y).y), Offset(gx, ROOM.p(0f, 3.6f).y - 5f), strokeWidth = 2f)
            rec(t)
            mark(true, 900f, 470f)
        } else {
            val pose = Pose(3f, 1.8f, (PI / 2).toFloat() + t * 2.2f, reach = 1.4f)
            drawPerson(ROOM, pose)
            val c = ROOM.p(pose.x, pose.y)
            drawArc(RED, startAngle = -20f + t * 120f, sweepAngle = 220f, useCenter = false, topLeft = Offset(c.x - 60f, c.y - 60f), size = Size(120f, 120f), style = Stroke(width = 6f))
            rec(t)
            mark(false, 900f, 470f)
        }
    }

    /** The corner and under the table get their own slow look. */
    private fun cornerPose(t: Float): Pose = when {
        t < 2.5f -> {
            val u = ease(t / 2.5f)
            Pose(1.3f, 1.3f, (-PI * 0.75).toFloat() + (PI / 2).toFloat() * sin(u * PI.toFloat()), reach = 2.4f)
        }
        t < 4.5f -> {
            val u = ease((t - 2.5f) / 2f)
            Pose(1.3f + 1.6f * u, 1.3f + 0.7f * u, (-PI / 4).toFloat() + (PI / 4).toFloat() * u, reach = 2.2f)
        }
        else -> {
            val u = ease((t - 4.5f) / 1.5f)
            Pose(2.9f, 2f, (-PI / 3).toFloat() - 0.5f * sin(u * PI.toFloat()), reach = 1.6f)
        }
    }

    private fun DrawScope.corners(t: Float) {
        val cover = Coverage(ROOM)
        var u = 0f
        while (u <= 6.5f) {
            cover.add(walkPose(u))
            u += STEP_SEC
        }
        u = 0f
        while (u <= t) {
            cover.add(cornerPose(u))
            u += STEP_SEC
        }
        drawRoom(ROOM, cover)
        drawPerson(ROOM, cornerPose(t))
        rec(t)
        mark(true, 900f, 470f)
    }

    /** Through the door into the next room without stopping the recording. */
    private fun doorPose(t: Float): Pose {
        val u = ease(t / 5f)
        val ang = if (u < 0.55f) 0f else (PI / 2).toFloat() * ease((u - 0.55f) / 0.45f)
        return Pose(1.2f + 4.6f * u, 1.8f, ang)
    }

    private fun DrawScope.door(t: Float) {
        val cover = Coverage(ROOMS)
        var u = 0f
        while (u <= t) {
            cover.add(doorPose(u))
            u += STEP_SEC
        }
        drawRoom(ROOMS, cover)
        drawLine(WALL, ROOMS.p(3.5f, 1.3f), ROOMS.p(4.4f, 1.8f), strokeWidth = 6f)
        drawPerson(ROOMS, doorPose(t))
        rec(t)
        mark(true, 900f, 470f)
    }

    /** Glass and mirrors mislead the camera; light helps. */
    private fun DrawScope.glass(t: Float) {
        drawRoom(ROOM, null)
        val tl = ROOM.p(5.94f, 0.6f)
        drawRect(GLASS, tl, Size(0.18f * PX, 1.6f * PX))
        drawPerson(ROOM, Pose(4.4f, 1.4f, 0f, reach = 1.8f))
        if (t < 3f) {
            val m = ROOM.p(6f, 1.4f)
            mark(false, m.x + 60f, m.y)
        } else {
            val l = ROOM.p(2f, 0.9f)
            drawCircle(LAMP, 26f, l)
            for (k in 0 until 8) {
                val a = k * (PI / 4).toFloat()
                drawLine(LAMP, Offset(l.x + cos(a) * 36f, l.y + sin(a) * 36f), Offset(l.x + cos(a) * 54f, l.y + sin(a) * 54f), strokeWidth = 5f, cap = StrokeCap.Round)
            }
            mark(true, l.x + 90f, l.y)
        }
        rec(t)
    }

    /** Press the check, the model generates, the room appears in 3D. */
    private fun DrawScope.save(t: Float) {
        when {
            t < 1.5f -> mark(true, 480f, 270f, r = 60f + 12f * sin(minOf(t, 1f) * PI.toFloat()))
            t < 3.5f -> {
                val u = (t - 1.5f) / 2f
                drawRoundRect(Color(0xFF3C4655), Offset(230f, 250f), Size(500f, 40f), CornerRadius(20f))
                drawRoundRect(GREEN, Offset(230f, 250f), Size(500f * u, 40f), CornerRadius(20f))
            }
            else -> {
                val u = ease((t - 3.5f) / 1.2f)
                val cx = 480f
                val cy = 290f
                val a = 220f
                val b = 130f
                val c = 150f * u
                val floor = Path().apply { moveTo(cx, cy + b); lineTo(cx + a, cy); lineTo(cx, cy - b); lineTo(cx - a, cy); close() }
                val left = Path().apply { moveTo(cx - a, cy); lineTo(cx, cy - b); lineTo(cx, cy - b - c); lineTo(cx - a, cy - c); close() }
                val right = Path().apply { moveTo(cx, cy - b); lineTo(cx + a, cy); lineTo(cx + a, cy - c); lineTo(cx, cy - b - c); close() }
                drawPath(floor, Color(0xFF3C7850))
                drawPath(left, Color(0xFF5AAA6E))
                drawPath(right, Color(0xFF468C5F))
                for (p in listOf(floor, left, right)) drawPath(p, WALL_DONE, style = Stroke(width = 2f))
                if (u >= 1f) mark(true, 860f, 80f)
            }
        }
    }
}
