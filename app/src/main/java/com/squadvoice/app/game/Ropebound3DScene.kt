package com.squadvoice.app.game

import android.content.Context
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val COURSE_SCALE = 0.80f

private fun computeCameraLookAt(
    matrix: FloatArray,
    cameraBlend: Float,
    orbitYaw: Float,
    orbitPitch: Float,
    focusX: Float,
    focusY: Float,
) {
    val topBlend = cameraBlend.coerceIn(0f, 1f)
    fun mix(from: Float, to: Float) = from + (to - from) * topBlend

    val baseRadius = mix(7.2f, 15.5f)
    val baseHeight = mix(3.35f, 17.8f)
    val baseLookAhead = mix(4.2f, 2.2f)

    val effectiveYaw = orbitYaw * (1f - topBlend * 0.65f)
    val effectivePitch = orbitPitch.coerceIn(-0.28f, 0.62f) * (1f - topBlend * 0.7f)

    val horizontalRadius = baseRadius * cos(effectivePitch)
    val eyeX = focusX * 0.72f + sin(effectiveYaw) * horizontalRadius
    val eyeY = (focusY * 0.35f + baseHeight + sin(effectivePitch) * baseRadius).coerceAtLeast(1.15f)
    val eyeZ = -cos(effectiveYaw) * horizontalRadius + mix(0.6f, 2.0f)

    val centerX = focusX * 0.88f
    val centerY = mix(1.25f + focusY * 0.45f, 0.2f)
    val centerZ = mix(baseLookAhead * cos(effectiveYaw * 0.5f), 2.8f)

    Matrix.setLookAtM(
        matrix, 0,
        eyeX, eyeY, eyeZ,
        centerX, centerY, centerZ,
        0f, 1f, 0f,
    )
}

@Composable
internal fun Ropebound3DScene(
    team: RopeboundTeam,
    viewerIdentity: String,
    obstacles: List<RopeboundObstacle>,
    cameraBlend: Float,
    cameraOrbitYaw: Float = 0f,
    cameraOrbitPitch: Float = 0f,
    localMoveInput: Float = 0f,
    localSteerInput: Float = 0f,
    onCameraOrbitDrag: ((Float, Float) -> Unit)? = null,
    showPlayerNameplates: Boolean = true,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val view = remember(context) { Ropebound3DView(context) }
    var internalYaw by remember { mutableFloatStateOf(0f) }
    var internalPitch by remember { mutableFloatStateOf(0f) }

    val activeYaw = if (onCameraOrbitDrag != null) cameraOrbitYaw else internalYaw
    val activePitch = if (onCameraOrbitDrag != null) cameraOrbitPitch else internalPitch

    SideEffect {
        view.submit(
            Ropebound3DState(
                team = team,
                viewerIdentity = viewerIdentity,
                obstacles = obstacles,
                cameraBlend = cameraBlend,
                orbitYaw = activeYaw,
                orbitPitch = activePitch,
                localMoveInput = localMoveInput,
                localSteerInput = localSteerInput,
            ),
        )
    }

    DisposableEffect(view) {
        view.onResume()
        onDispose { view.onPause() }
    }

    val dragModifier = modifier
        .fillMaxSize()
        .pointerInput(onCameraOrbitDrag) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                val dyaw = -dragAmount.x * 0.0055f
                val dpitch = dragAmount.y * 0.0042f
                if (onCameraOrbitDrag != null) {
                    onCameraOrbitDrag(dyaw, dpitch)
                } else {
                    internalYaw = (internalYaw + dyaw) % (2f * PI.toFloat())
                    internalPitch = (internalPitch + dpitch).coerceIn(-0.25f, 0.58f)
                }
            }
        }

    AndroidView(factory = { view }, modifier = dragModifier)
    LaunchedEffect(view) {
        delay(300)
        onReady()
    }
    if (showPlayerNameplates) {
        RopeboundPlayerNameplates(
            team = team,
            viewerIdentity = viewerIdentity,
            cameraBlend = cameraBlend,
            orbitYaw = activeYaw,
            orbitPitch = activePitch,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun RopeboundPlayerNameplates(
    team: RopeboundTeam,
    viewerIdentity: String,
    cameraBlend: Float,
    orbitYaw: Float,
    orbitPitch: Float,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val namePaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 9.5.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    val pingPaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 8.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    Canvas(modifier = modifier) {
        val localIndex = team.players.indexOfFirst { it.id == viewerIdentity }
        val local = if (localIndex >= 0) team.players[localIndex] else team.players.firstOrNull()
        val cameraProgress = local?.progress ?: team.progress
        val focusX = if (local != null && localIndex >= 0) {
            (localIndex - (team.players.size - 1) / 2f) * 1.22f + local.lateralOffset.coerceIn(-1.25f, 1.25f)
        } else 0f
        val focusY = ((local?.height ?: 0f) / 12f).coerceIn(-0.35f, 0.5f) * 2.6f

        val projection = FloatArray(16)
        val camera = FloatArray(16)
        val viewProjection = FloatArray(16)
        Matrix.perspectiveM(projection, 0, 56f, size.width / size.height.coerceAtLeast(1f), 0.1f, 190f)
        computeCameraLookAt(camera, cameraBlend, orbitYaw, orbitPitch, focusX, focusY)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, camera, 0)

        team.players.forEachIndexed { index, player ->
            val z = (player.progress - cameraProgress) * 100f * COURSE_SCALE
            val x = (index - (team.players.size - 1) / 2f) * 1.22f + player.lateralOffset.coerceIn(-1.25f, 1.25f)
            val y = 2.42f + (player.height / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(x, y, z, 1f), 0)
            if (clip[3] <= 0.2f) return@forEachIndexed
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            if (ndcX !in -1.15f..1.15f || ndcY !in -1.15f..1.15f) return@forEachIndexed
            val sx = (ndcX + 1f) * 0.5f * size.width
            val sy = (1f - ndcY) * 0.5f * size.height
            val ping = player.pingMs?.let { "${it}ms" } ?: "34ms"
            pingPaint.color = when {
                player.pingMs == null || player.pingMs < 150 -> android.graphics.Color.rgb(119, 232, 189)
                player.pingMs < 300 -> android.graphics.Color.rgb(255, 209, 102)
                else -> android.graphics.Color.rgb(255, 133, 133)
            }
            val displayName = player.name.ifBlank { "Player" }.take(12)
            val pad = 7.dp.toPx()
            val gap = 6.dp.toPx()
            val width = namePaint.measureText(displayName) + pingPaint.measureText(ping) + pad * 2 + gap
            val height = 18.dp.toPx()
            val left = (sx - width / 2f).coerceIn(2.dp.toPx(), size.width - width - 2.dp.toPx())
            val top = sy - 16.dp.toPx()
            if (top < 0f || top + height > size.height) return@forEachIndexed
            val isLocal = player.id == viewerIdentity
            drawRoundRect(
                if (isLocal) Color(0xEE0D2833) else Color(0xDC102028),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
            )
            if (isLocal) {
                drawRoundRect(
                    Color(0xFF48F0D8),
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1.dp.toPx()),
                )
            }
            drawIntoCanvas { canvas ->
                val baseline = top + (height - (namePaint.ascent() + namePaint.descent())) / 2f
                canvas.nativeCanvas.drawText(displayName, left + pad, baseline, namePaint)
                canvas.nativeCanvas.drawText(ping, left + pad + namePaint.measureText(displayName) + gap, baseline, pingPaint)
            }
        }
    }
}

private data class Ropebound3DState(
    val team: RopeboundTeam,
    val viewerIdentity: String,
    val obstacles: List<RopeboundObstacle>,
    val cameraBlend: Float,
    val orbitYaw: Float = 0f,
    val orbitPitch: Float = 0f,
    val localMoveInput: Float = 0f,
    val localSteerInput: Float = 0f,
)

private class Ropebound3DView(context: Context) : GLSurfaceView(context) {
    private val gameRenderer = Ropebound3DRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setPreserveEGLContextOnPause(true)
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun submit(state: Ropebound3DState) = gameRenderer.submit(state)
}

private data class Point3(val x: Float, val y: Float, val z: Float)
private data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun shade(amount: Float) = Rgb(
        (r * amount).coerceIn(0f, 1f),
        (g * amount).coerceIn(0f, 1f),
        (b * amount).coerceIn(0f, 1f),
    )
    fun mix(other: Rgb, t: Float): Rgb {
        val clamped = t.coerceIn(0f, 1f)
        return Rgb(
            r + (other.r - r) * clamped,
            g + (other.g - g) * clamped,
            b + (other.b - b) * clamped,
        )
    }
}

/**
 * 3D local-to-world pose transform for articulated 3D character models and props.
 */
private class Pose3D(
    val originX: Float,
    val originY: Float,
    val originZ: Float,
    yaw: Float,
    pitch: Float = 0f,
    roll: Float = 0f,
) {
    private val cy = cos(yaw)
    private val sy = sin(yaw)
    private val cp = cos(pitch)
    private val sp = sin(pitch)
    private val cr = cos(roll)
    private val sr = sin(roll)

    fun p(lx: Float, ly: Float, lz: Float): Point3 {
        // 1. Roll around Z, 2. Pitch around X, 3. Yaw around Y
        val rx = lx * cr - ly * sr
        val ry = lx * sr + ly * cr
        val rz = lz

        val px = rx
        val py = ry * cp - rz * sp
        val pz = ry * sp + rz * cp

        val wx = px * cy + pz * sy
        val wy = py
        val wz = -px * sy + pz * cy
        return Point3(originX + wx, originY + wy, originZ + wz)
    }
}

/**
 * High-performance 3D mesh builder with per-vertex 3D positions, normals, and colors.
 * Stride = 9 floats (36 bytes): [x, y, z, nx, ny, nz, r, g, b].
 * Normal length > 0.5 uses full 3D lighting (sun + hemisphere ambient + specular + rim).
 * Normal = (0, 0, 0) renders as pure emissive/sky color without shadow darkening.
 */
private class TriangleMesh {
    private val values = ArrayList<Float>(280_000)
    val vertexCount get() = values.size / 9

    fun triangle(a: Point3, b: Point3, c: Point3, color: Rgb, emissive: Boolean = false) {
        if (emissive) {
            val zero = Point3(0f, 0f, 0f)
            vertex(a, zero, color)
            vertex(b, zero, color)
            vertex(c, zero, color)
            return
        }
        val n = faceNormal(a, b, c)
        vertex(a, n, color)
        vertex(b, n, color)
        vertex(c, n, color)
    }

    fun triangleSmooth(
        a: Point3,
        b: Point3,
        c: Point3,
        na: Point3,
        nb: Point3,
        nc: Point3,
        color: Rgb,
    ) {
        vertex(a, na, color)
        vertex(b, nb, color)
        vertex(c, nc, color)
    }

    fun quad(a: Point3, b: Point3, c: Point3, d: Point3, color: Rgb, emissive: Boolean = false) {
        if (emissive) {
            val zero = Point3(0f, 0f, 0f)
            vertex(a, zero, color); vertex(b, zero, color); vertex(c, zero, color)
            vertex(a, zero, color); vertex(c, zero, color); vertex(d, zero, color)
            return
        }
        val n = faceNormal(a, b, c)
        vertex(a, n, color); vertex(b, n, color); vertex(c, n, color)
        vertex(a, n, color); vertex(c, n, color); vertex(d, n, color)
    }

    private fun faceNormal(a: Point3, b: Point3, c: Point3): Point3 {
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-5f) return Point3(0f, 1f, 0f)
        nx /= len; ny /= len; nz /= len
        return Point3(nx, ny, nz)
    }

    private fun normalize(x: Float, y: Float, z: Float): Point3 {
        val len = sqrt(x * x + y * y + z * z)
        return if (len < 1e-5f) Point3(0f, 1f, 0f) else Point3(x / len, y / len, z / len)
    }

    private fun vertex(point: Point3, normal: Point3, color: Rgb) {
        values += point.x; values += point.y; values += point.z
        values += normal.x; values += normal.y; values += normal.z
        values += color.r; values += color.g; values += color.b
    }

    fun fillBuffer(existing: FloatBuffer?): FloatBuffer {
        val neededFloats = values.size
        val buffer = if (existing == null || existing.capacity() < neededFloats) {
            ByteBuffer.allocateDirect(max(neededFloats * 4, 320_000 * 4))
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        } else {
            existing.clear()
            existing
        }
        val array = FloatArray(neededFloats)
        for (i in 0 until neededFloats) array[i] = values[i]
        buffer.put(array, 0, neededFloats)
        buffer.position(0)
        return buffer
    }

    fun box(center: Point3, width: Float, height: Float, depth: Float, color: Rgb) {
        val x0 = center.x - width / 2f; val x1 = center.x + width / 2f
        val y0 = center.y - height / 2f; val y1 = center.y + height / 2f
        val z0 = center.z - depth / 2f; val z1 = center.z + depth / 2f
        // Top (+Y)
        quad(Point3(x0, y1, z0), Point3(x0, y1, z1), Point3(x1, y1, z1), Point3(x1, y1, z0), color.shade(1.08f))
        // Bottom (-Y)
        quad(Point3(x0, y0, z1), Point3(x0, y0, z0), Point3(x1, y0, z0), Point3(x1, y0, z1), color.shade(0.68f))
        // Front (+Z)
        quad(Point3(x0, y0, z1), Point3(x1, y0, z1), Point3(x1, y1, z1), Point3(x0, y1, z1), color)
        // Back (-Z)
        quad(Point3(x1, y0, z0), Point3(x0, y0, z0), Point3(x0, y1, z0), Point3(x1, y1, z0), color.shade(0.88f))
        // Left (-X)
        quad(Point3(x0, y0, z0), Point3(x0, y0, z1), Point3(x0, y1, z1), Point3(x0, y1, z0), color.shade(0.92f))
        // Right (+X)
        quad(Point3(x1, y0, z1), Point3(x1, y0, z0), Point3(x1, y1, z0), Point3(x1, y1, z1), color.shade(0.84f))
    }

    fun orientedBox(
        pose: Pose3D,
        lx: Float,
        ly: Float,
        lz: Float,
        width: Float,
        height: Float,
        depth: Float,
        color: Rgb,
    ) {
        val x0 = lx - width * 0.5f; val x1 = lx + width * 0.5f
        val y0 = ly - height * 0.5f; val y1 = ly + height * 0.5f
        val z0 = lz - depth * 0.5f; val z1 = lz + depth * 0.5f
        val p000 = pose.p(x0, y0, z0); val p100 = pose.p(x1, y0, z0)
        val p010 = pose.p(x0, y1, z0); val p110 = pose.p(x1, y1, z0)
        val p001 = pose.p(x0, y0, z1); val p101 = pose.p(x1, y0, z1)
        val p011 = pose.p(x0, y1, z1); val p111 = pose.p(x1, y1, z1)
        quad(p010, p011, p111, p110, color.shade(1.08f))
        quad(p001, p000, p100, p101, color.shade(0.68f))
        quad(p001, p101, p111, p011, color)
        quad(p100, p000, p010, p110, color.shade(0.88f))
        quad(p000, p001, p011, p010, color.shade(0.92f))
        quad(p101, p100, p110, p111, color.shade(0.84f))
    }

    fun bridgePlank(center: Point3, width: Float, thickness: Float, length: Float, roll: Float, color: Rgb) {
        val halfWidth = width * 0.5f
        val halfLength = length * 0.5f
        val rise = sin(roll) * halfWidth
        val leftNear = Point3(center.x - halfWidth, center.y - rise, center.z - halfLength)
        val rightNear = Point3(center.x + halfWidth, center.y + rise, center.z - halfLength)
        val rightFar = Point3(center.x + halfWidth, center.y + rise, center.z + halfLength)
        val leftFar = Point3(center.x - halfWidth, center.y - rise, center.z + halfLength)
        val leftNearBottom = Point3(leftNear.x, leftNear.y - thickness, leftNear.z)
        val rightNearBottom = Point3(rightNear.x, rightNear.y - thickness, rightNear.z)
        val rightFarBottom = Point3(rightFar.x, rightFar.y - thickness, rightFar.z)
        val leftFarBottom = Point3(leftFar.x, leftFar.y - thickness, leftFar.z)
        quad(leftNear, leftFar, rightFar, rightNear, color.shade(1.08f))
        quad(leftNearBottom, rightNearBottom, rightFarBottom, leftFarBottom, color.shade(0.62f))
        quad(leftNear, leftNearBottom, leftFarBottom, leftFar, color.shade(0.86f))
        quad(rightNear, rightFar, rightFarBottom, rightNearBottom, color.shade(0.80f))
        quad(leftNearBottom, leftNear, rightNear, rightNearBottom, color)
        quad(leftFar, leftFarBottom, rightFarBottom, rightFar, color.shade(0.92f))
    }

    fun plane(x0: Float, x1: Float, z0: Float, z1: Float, y: Float, color: Rgb, emissive: Boolean = false) {
        quad(Point3(x0, y, z0), Point3(x0, y, z1), Point3(x1, y, z1), Point3(x1, y, z0), color, emissive)
    }

    fun sphere(center: Point3, radius: Float, color: Rgb, slices: Int = 10, rings: Int = 7, emissive: Boolean = false) {
        ellipsoid(center, radius, radius, radius, color, slices, rings, smoothShading = true, emissive = emissive)
    }

    fun ellipsoid(
        center: Point3,
        radiusX: Float,
        radiusY: Float,
        radiusZ: Float,
        color: Rgb,
        slices: Int = 10,
        rings: Int = 7,
        smoothShading: Boolean = true,
        emissive: Boolean = false,
    ) {
        val zero = Point3(0f, 0f, 0f)
        for (ring in 0 until rings) {
            val phi0 = -PI / 2 + PI * ring / rings
            val phi1 = -PI / 2 + PI * (ring + 1) / rings
            for (slice in 0 until slices) {
                val t0 = 2 * PI * slice / slices
                val t1 = 2 * PI * (slice + 1) / slices
                fun point(phi: Double, theta: Double) = Point3(
                    center.x + radiusX * cos(phi).toFloat() * cos(theta).toFloat(),
                    center.y + radiusY * sin(phi).toFloat(),
                    center.z + radiusZ * cos(phi).toFloat() * sin(theta).toFloat(),
                )
                fun norm(phi: Double, theta: Double) = if (emissive) zero else normalize(
                    cos(phi).toFloat() * cos(theta).toFloat() / radiusX.coerceAtLeast(0.01f),
                    sin(phi).toFloat() / radiusY.coerceAtLeast(0.01f),
                    cos(phi).toFloat() * sin(theta).toFloat() / radiusZ.coerceAtLeast(0.01f),
                )
                val a = point(phi0, t0); val b = point(phi1, t0)
                val c = point(phi1, t1); val d = point(phi0, t1)
                if (smoothShading || emissive) {
                    val na = norm(phi0, t0); val nb = norm(phi1, t0)
                    val nc = norm(phi1, t1); val nd = norm(phi0, t1)
                    triangleSmooth(a, c, b, na, nc, nb, color)
                    triangleSmooth(a, d, c, na, nd, nc, color)
                } else {
                    quad(a, d, c, b, color)
                }
            }
        }
    }

    fun orientedEllipsoid(
        pose: Pose3D,
        lx: Float,
        ly: Float,
        lz: Float,
        radiusX: Float,
        radiusY: Float,
        radiusZ: Float,
        color: Rgb,
        slices: Int = 10,
        rings: Int = 7,
    ) {
        val centerWorld = pose.p(lx, ly, lz)
        for (ring in 0 until rings) {
            val phi0 = -PI / 2 + PI * ring / rings
            val phi1 = -PI / 2 + PI * (ring + 1) / rings
            for (slice in 0 until slices) {
                val t0 = 2 * PI * slice / slices
                val t1 = 2 * PI * (slice + 1) / slices
                fun point(phi: Double, theta: Double) = pose.p(
                    lx + radiusX * cos(phi).toFloat() * cos(theta).toFloat(),
                    ly + radiusY * sin(phi).toFloat(),
                    lz + radiusZ * cos(phi).toFloat() * sin(theta).toFloat(),
                )
                fun norm(p: Point3) = normalize(
                    p.x - centerWorld.x,
                    p.y - centerWorld.y,
                    p.z - centerWorld.z,
                )
                val a = point(phi0, t0); val b = point(phi1, t0)
                val c = point(phi1, t1); val d = point(phi0, t1)
                val na = norm(a); val nb = norm(b); val nc = norm(c); val nd = norm(d)
                triangleSmooth(a, c, b, na, nc, nb, color)
                triangleSmooth(a, d, c, na, nd, nc, color)
            }
        }
    }

    fun facetedRock(center: Point3, radiusX: Float, radiusY: Float, radiusZ: Float, color: Rgb, seed: Int) {
        val sides = 7
        val ringHeights = floatArrayOf(-0.72f, -0.34f, 0.18f, 0.62f, 0.85f)
        val rings = ringHeights.mapIndexed { ring, height ->
            List(sides) { slice ->
                val angle = 2f * PI.toFloat() * slice / sides + (ring % 2) * 0.19f
                val wobble = 1f + sin(seed * 1.73f + ring * 3.1f + slice * 5.7f) * 0.12f
                val rScale = when (ring) {
                    0 -> 0.85f
                    1 -> 1.04f
                    2 -> 1.0f
                    3 -> 0.78f
                    else -> 0.42f
                }
                Point3(
                    center.x + cos(angle) * radiusX * wobble * rScale,
                    center.y + height * radiusY,
                    center.z + sin(angle) * radiusZ * wobble * rScale,
                )
            }
        }
        for (ring in 0 until rings.lastIndex) {
            for (slice in 0 until sides) {
                val next = (slice + 1) % sides
                val a = rings[ring][slice]
                val b = rings[ring][next]
                val c = rings[ring + 1][next]
                val d = rings[ring + 1][slice]
                val facet = 0.88f + ((seed * 13 + ring * 7 + slice * 11) % 7) * 0.035f
                triangle(a, c, b, color.shade(facet))
                triangle(a, d, c, color.shade(facet * 1.03f))
            }
        }
        val top = rings.last()
        val apex = Point3(center.x, center.y + radiusY * 0.96f, center.z)
        for (slice in 0 until sides) {
            triangle(top[slice], apex, top[(slice + 1) % sides], color.shade(1.12f))
        }
    }

    fun cone(x: Float, y: Float, z: Float, radius: Float, height: Float, color: Rgb, sides: Int = 7, emissive: Boolean = false) {
        val tip = Point3(x, y + height, z)
        val baseCenter = Point3(x, y, z)
        for (side in 0 until sides) {
            val a = 2 * PI * side / sides
            val b = 2 * PI * (side + 1) / sides
            val p0 = Point3(x + cos(a).toFloat() * radius, y, z + sin(a).toFloat() * radius)
            val p1 = Point3(x + cos(b).toFloat() * radius, y, z + sin(b).toFloat() * radius)
            triangle(p0, tip, p1, color.shade(if (side % 2 == 0) 1.05f else 0.94f), emissive)
            triangle(baseCenter, p0, p1, color.shade(0.72f), emissive)
        }
    }

    fun rod(
        from: Point3,
        to: Point3,
        radiusFrom: Float,
        color: Rgb,
        sides: Int = 8,
        radiusTo: Float = radiusFrom,
        emissive: Boolean = false,
    ) {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
        val ux = dx / length; val uy = dy / length; val uz = dz / length
        var ax = uy; var ay = -ux; var az = 0f
        var al = sqrt(ax * ax + ay * ay + az * az)
        if (al < 0.001f) { ax = 1f; ay = 0f; az = 0f; al = 1f }
        ax /= al; ay /= al; az /= al
        val bx = uy * az - uz * ay
        val by = uz * ax - ux * az
        val bz = ux * ay - uy * ax
        val zero = Point3(0f, 0f, 0f)
        for (side in 0 until sides) {
            val a = 2 * PI * side / sides
            val b = 2 * PI * (side + 1) / sides
            fun radialNormal(angle: Double): Point3 {
                if (emissive) return zero
                val ca = cos(angle).toFloat()
                val sa = sin(angle).toFloat()
                return normalize(ax * ca + bx * sa, ay * ca + by * sa, az * ca + bz * sa)
            }
            fun ring(center: Point3, r: Float, n: Point3) = Point3(
                center.x + r * n.x,
                center.y + r * n.y,
                center.z + r * n.z,
            )
            val n0 = if (emissive) {
                val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
                Point3(ax * ca + bx * sa, ay * ca + by * sa, az * ca + bz * sa)
            } else radialNormal(a)
            val n1 = if (emissive) {
                val cb = cos(b).toFloat(); val sb = sin(b).toFloat()
                Point3(ax * cb + bx * sb, ay * cb + by * sb, az * cb + bz * sb)
            } else radialNormal(b)

            val p0 = ring(from, radiusFrom, n0)
            val p1 = ring(from, radiusFrom, n1)
            val p2 = ring(to, radiusTo, n1)
            val p3 = ring(to, radiusTo, n0)
            if (emissive) {
                quad(p0, p1, p2, p3, color, emissive = true)
            } else {
                triangleSmooth(p0, p2, p1, n0, n1, n1, color)
                triangleSmooth(p0, p3, p2, n0, n0, n1, color)
            }
        }
    }
}

private data class RunnerMotionTracker(
    var lastX: Float = 0f,
    var lastZ: Float = 0f,
    var smoothYaw: Float = 0f,
    var smoothLean: Float = 0f,
    var smoothBank: Float = 0f,
    var animDistance: Float = 0f,
    var initialized: Boolean = false,
)

private class Ropebound3DRenderer : GLSurfaceView.Renderer {
    @Volatile
    private var scene = Ropebound3DState(
        RopeboundTeam(0, 0f, 0f, 0f, false, 0, 3, emptyList(), emptyList()),
        "",
        emptyList(),
        0f,
    )
    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var eyePosHandle = 0
    private var timeHandle = 0
    private var width = 1
    private var height = 1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val epoch = System.nanoTime()
    private var lastFrameAt = 0f
    private var smoothProgress = -1f
    private var smoothFocusX = 0f
    private var smoothFocusY = 0f
    private var vertexBuffer: FloatBuffer? = null
    private val runnerTrackers = HashMap<String, RunnerMotionTracker>()

    fun submit(next: Ropebound3DState) {
        scene = next
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.36f, 0.70f, 0.92f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        val vertexShader = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vWorldPos = aPosition;
                vNormal = aNormal;
                vColor = aColor;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform vec3 uEyePos;
            uniform float uTime;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                float nLen = length(vNormal);
                vec3 color = vColor;
                float dist = length(vWorldPos - uEyePos);

                if (nLen > 0.2) {
                    vec3 N = vNormal / nLen;
                    vec3 V = normalize(uEyePos - vWorldPos);
                    if (dot(N, V) < -0.15) {
                        N = -N;
                    }
                    vec3 L = normalize(vec3(0.42, 0.82, -0.36));
                    vec3 H = normalize(L + V);

                    // Half-Lambert warm directional sunlight
                    float ndl = dot(N, L);
                    float diffuse = clamp(ndl * 0.52 + 0.48, 0.0, 1.0);
                    diffuse = diffuse * diffuse;

                    // Sky-to-ground hemisphere ambient light
                    vec3 skyAmb = vec3(0.76, 0.88, 1.0);
                    vec3 groundAmb = vec3(0.42, 0.50, 0.38);
                    vec3 hemi = mix(groundAmb, skyAmb, clamp(N.y * 0.5 + 0.5, 0.0, 1.0));

                    // Blinn-Phong specular highlight + 3D Fresnel rim light
                    float specBase = max(dot(N, H), 0.0);
                    float spec = pow(specBase, 26.0) * 0.24;
                    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.18 * max(N.y + 0.3, 0.0);

                    vec3 sunColor = vec3(1.04, 0.99, 0.92);
                    color = vColor * (hemi * 0.46 + sunColor * diffuse * 0.72) + vec3(spec + rim * 0.75);
                }

                // Atmospheric horizon fog for 3D depth perception
                if (vWorldPos.z < 138.0) {
                    float fogFactor = clamp((dist - 42.0) / 96.0, 0.0, 0.68);
                    vec3 fogColor = mix(vec3(0.58, 0.82, 0.95), vec3(0.42, 0.72, 0.90), clamp(vWorldPos.y / 28.0, 0.0, 1.0));
                    color = mix(color, fogColor, fogFactor * fogFactor);
                }

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        program = linkProgram(vertexShader, fragmentShader)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        eyePosHandle = GLES20.glGetUniformLocation(program, "uEyePos")
        timeHandle = GLES20.glGetUniformLocation(program, "uTime")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(projection, 0, 56f, this.width.toFloat() / this.height, 0.1f, 190f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = (System.nanoTime() - epoch) / 1_000_000_000f
        val dt = if (lastFrameAt == 0f) 1f / 60f else (now - lastFrameAt).coerceIn(0.004f, 0.05f)
        if (now - lastFrameAt < 1f / 62f) return
        lastFrameAt = now

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = scene
        val localIndex = current.team.players.indexOfFirst { it.id == current.viewerIdentity }
        val local = if (localIndex >= 0) current.team.players[localIndex] else current.team.players.firstOrNull()
        val targetProgress = local?.progress ?: current.team.progress

        if (smoothProgress < 0f || abs(targetProgress - smoothProgress) > 0.08f) {
            smoothProgress = targetProgress
        } else {
            // Fast 60 FPS tracking so local predicted movement has zero camera lag
            smoothProgress += (targetProgress - smoothProgress) * 0.55f
        }

        val targetFocusX = if (local != null && localIndex >= 0) {
            (localIndex - (current.team.players.size - 1) / 2f) * 1.22f + local.lateralOffset.coerceIn(-1.25f, 1.25f)
        } else 0f
        val targetFocusY = ((local?.height ?: 0f) / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
        smoothFocusX += (targetFocusX - smoothFocusX) * 0.45f
        smoothFocusY += (targetFocusY - smoothFocusY) * 0.45f

        val mesh = TriangleMesh()
        buildWorld(mesh, current, smoothProgress, now, dt)

        computeCameraLookAt(view, current.cameraBlend, current.orbitYaw, current.orbitPitch, smoothFocusX, smoothFocusY)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, IDENTITY, 0)

        val topBlend = current.cameraBlend.coerceIn(0f, 1f)
        val baseRadius = 7.2f + (15.5f - 7.2f) * topBlend
        val baseHeight = 3.35f + (17.8f - 3.35f) * topBlend
        val effectiveYaw = current.orbitYaw * (1f - topBlend * 0.65f)
        val effectivePitch = current.orbitPitch.coerceIn(-0.28f, 0.62f) * (1f - topBlend * 0.7f)
        val eyeX = smoothFocusX * 0.72f + sin(effectiveYaw) * baseRadius * cos(effectivePitch)
        val eyeY = (smoothFocusY * 0.35f + baseHeight + sin(effectivePitch) * baseRadius).coerceAtLeast(1.15f)
        val eyeZ = -cos(effectiveYaw) * baseRadius * cos(effectivePitch) + 0.6f

        val buffer = mesh.fillBuffer(vertexBuffer)
        vertexBuffer = buffer

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform3f(eyePosHandle, eyeX, eyeY, eyeZ)
        GLES20.glUniform1f(timeHandle, now)

        val strideBytes = 9 * 4
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)

        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)
        buffer.position(6)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun buildWorld(
        mesh: TriangleMesh,
        state: Ropebound3DState,
        cameraProgress: Float,
        time: Float,
        dt: Float,
    ) {
        // Align obstacle Z coordinates 1:1 with runner progress (0m..100m) so visual jump gaps match physics exactly
        fun zAt(distanceMeters: Float) = (distanceMeters - cameraProgress * 100f) * COURSE_SCALE

        val cuts = state.obstacles
            .filter { it.id in setOf("pit", "river-bridge", "river-gap", "lava") }
            .map { zAt(it.start) to zAt(it.end) }
            .filter { it.second > -18f && it.first < 96f }
            .sortedBy { it.first }

        // 1. Volumetric 3D Sky Dome, Sun & 3D Mountain Ranges
        drawMountainVista(mesh, time)

        // 2. Sculpted 3D Ground Terrain & Valley Hills
        val riverSections = state.obstacles
            .filter { it.id == "river-bridge" || it.id == "river-gap" }
            .map { obstacle ->
                (zAt(obstacle.start) - 1.2f).coerceAtLeast(-26f) to
                    (zAt(obstacle.end) + 1.2f).coerceAtMost(106f)
            }
            .filter { it.second > it.first }
            .sortedBy { it.first }

        fun drawTerrainLayer(y: Float, color: Rgb) {
            var cursor = -26f
            riverSections.forEach { (riverNear, riverFar) ->
                if (riverNear > cursor) mesh.plane(-44f, 44f, cursor, riverNear, y, color)
                mesh.plane(-44f, -23f, riverNear, riverFar, y, color)
                mesh.plane(23f, 44f, riverNear, riverFar, y, color)
                cursor = max(cursor, riverFar)
            }
            if (cursor < 106f) mesh.plane(-44f, 44f, cursor, 106f, y, color)
        }
        drawTerrainLayer(-0.30f, Rgb(0.24f, 0.52f, 0.22f))
        drawTerrainLayer(-0.29f, Rgb(0.32f, 0.62f, 0.26f))

        // 3D Roadside Valley Ridges & Boulders flanking the course
        drawValleySideHills(mesh, cameraProgress, time, riverSections)

        // 3. 3D Cobblestone & Timber Trail Pathway with clean cuts for hazards
        var roadZ = -20f
        cuts.forEach { (start, end) ->
            val boundedStart = start.coerceIn(-20f, 96f)
            val boundedEnd = end.coerceIn(-20f, 96f)
            if (boundedStart > roadZ) drawTrail(mesh, roadZ, boundedStart)
            roadZ = max(roadZ, boundedEnd)
        }
        if (roadZ < 96f) drawTrail(mesh, roadZ, 96f)

        // 4. Multi-Species 3D Trees & Forest Clusters
        for (tree in 0 until 28) {
            val side = if (tree % 2 == 0) -1f else 1f
            val x = side * (6.6f + (tree % 5) * 2.15f)
            val z = -10f + (tree * 5.6f + cameraProgress * COURSE_SCALE * 0.35f) % 78f
            if (riverSections.any { z in (it.first - 1.2f)..(it.second + 1.2f) }) continue
            val species = tree % 3
            val scale = 0.86f + (tree % 4) * 0.14f
            drawStylizedTree(mesh, x, z, species, scale, time)
        }

        // 5. Waving 3D Grassland, Bush Clusters & Wildflowers
        drawMeadows(mesh, cameraProgress, time, riverSections)

        // 6. Detailed 3D Obstacles & Hazards
        state.obstacles.forEach { obstacle ->
            val z1 = zAt(obstacle.start)
            val z2 = zAt(obstacle.end)
            if (max(z1, z2) < -22f || min(z1, z2) > 105f) return@forEach
            when (obstacle.id) {
                "pit" -> buildPitHazard(mesh, z1, z2, time)
                "boulder" -> buildBoulderHazard(mesh, z1, z2, time)
                "river-bridge", "river-gap" -> {
                    buildRiver(mesh, z1, z2, obstacle.id == "river-bridge", time, state.team.players, cameraProgress)
                }
                "wind-tunnel" -> buildWindTunnel(mesh, z1, z2, time)
                "lava" -> buildLava(mesh, z1, z2, time)
            }
        }

        // 7. 3D Checkpoint Gates with Fluttering Banners
        for (checkpoint in listOf(8f, 19f, 30f, 40f, 50f, 64f, 80f)) {
            val z = zAt(checkpoint)
            if (z !in -18f..95f) continue
            val reached = state.team.progress * 100f >= checkpoint
            drawCheckpointGate(mesh, z, checkpoint, reached, time)
        }

        // 8. Grand 3D Stadium Finish Arch
        val finishZ = zAt(99.5f)
        if (finishZ in -20f..110f) {
            drawFinishArch(mesh, finishZ, time)
        }

        // 9. Articulated 3D Character Models + 10. 3D Braided Tension Rope
        val anchorPoints = ArrayList<Point3>(state.team.players.size)
        for (playerIndex in state.team.players.indices) {
            val player = state.team.players[playerIndex]
            val z = (player.progress - cameraProgress) * 100f * COURSE_SCALE
            val x = (playerIndex - (state.team.players.size - 1) / 2f) * 1.22f + player.lateralOffset.coerceIn(-1.25f, 1.25f)
            val jump = (player.height / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
            val isLocalPlayer = player.id == state.viewerIdentity
            val harnessAnchor = drawRunner(
                mesh = mesh,
                player = player,
                x = x,
                z = z,
                jump = jump,
                index = playerIndex,
                isLocalPlayer = isLocalPlayer,
                localMoveInput = if (isLocalPlayer) state.localMoveInput else 0f,
                localSteerInput = if (isLocalPlayer) state.localSteerInput else 0f,
                surgeActive = state.team.surgeActive,
                time = time,
                dt = dt,
            )
            anchorPoints += harnessAnchor
        }

        // 10. 3D Braided Squad Tension Rope connecting each runner's 3D harness carabiner
        for (index in 0 until anchorPoints.size - 1) {
            val anchor0 = anchorPoints[index]
            val anchor1 = anchorPoints[index + 1]
            val first = state.team.players[index]
            val second = state.team.players[index + 1]
            val distance = abs(first.progress - second.progress)
            val sag = 0.06f + (1f - state.team.ropeTension.coerceIn(0f, 1f)) * 0.24f + (distance / 18f).coerceIn(0f, 0.16f)
            val ropeColor = when {
                state.team.surgeActive -> Rgb(0.32f, 0.98f, 0.88f)
                state.team.ropeTension > 0.85f -> Rgb(1f, 0.34f, 0.38f)
                state.team.ropeTension > 0.55f -> Rgb(1f, 0.84f, 0.28f)
                else -> Rgb(0.22f, 0.86f, 0.74f)
            }
            val ropeAccent = ropeColor.shade(0.78f)
            mesh.sphere(anchor0, 0.068f, Rgb(0.88f, 0.78f, 0.48f), 8, 5)
            mesh.sphere(anchor1, 0.068f, Rgb(0.88f, 0.78f, 0.48f), 8, 5)
            var previous = anchor0
            val segments = 10
            for (step in 1..segments) {
                val t = step.toFloat() / segments
                val vib = if (state.team.ropeTension > 0.7f) sin(time * 28f + step * 1.4f) * 0.018f else 0f
                val current = Point3(
                    anchor0.x + (anchor1.x - anchor0.x) * t,
                    anchor0.y + (anchor1.y - anchor0.y) * t - sin(t * PI).toFloat() * sag + vib,
                    anchor0.z + (anchor1.z - anchor0.z) * t,
                )
                mesh.rod(previous, current, 0.038f, if (step % 2 == 0) ropeColor else ropeAccent, 6)
                previous = current
            }
        }
    }

    private fun drawMountainVista(mesh: TriangleMesh, time: Float) {
        // Smooth atmospheric sky gradient bands (emissive so sun lighting doesn't darken sky)
        val skyBands = listOf(
            Rgb(0.58f, 0.84f, 0.96f),
            Rgb(0.46f, 0.76f, 0.93f),
            Rgb(0.34f, 0.67f, 0.89f),
            Rgb(0.25f, 0.57f, 0.84f),
            Rgb(0.18f, 0.47f, 0.78f),
            Rgb(0.12f, 0.38f, 0.70f),
        )
        for (band in skyBands.indices) {
            val y0 = -36f + band * 24f
            val y1 = y0 + 24f
            val skyZ = 152f
            mesh.quad(
                Point3(-210f, y0, skyZ),
                Point3(210f, y0, skyZ),
                Point3(210f, y1, skyZ),
                Point3(-210f, y1, skyZ),
                skyBands[band],
                emissive = true,
            )
        }

        // Radiant 3D Sun & Halo
        mesh.sphere(Point3(28f, 24f, 122f), 4.2f, Rgb(1f, 0.96f, 0.76f), 12, 8, emissive = true)
        mesh.sphere(Point3(28f, 24f, 122f), 5.6f, Rgb(1f, 0.88f, 0.52f), 10, 6, emissive = true)

        // Sculpted 3D Volumetric Cloud Clusters with smooth normals
        for (cloud in 0 until 9) {
            val x = -48f + (cloud % 5) * 24f + sin(cloud * 1.9f) * 4f
            val z = 28f + (cloud * 13.7f + time * 0.42f) % 84f
            val y = 13.0f + (cloud % 3) * 2.6f
            val puff = Rgb(0.95f, 0.98f, 1f)
            mesh.ellipsoid(Point3(x, y, z), 3.6f, 1.35f, 2.3f, puff, 10, 7, smoothShading = true)
            mesh.ellipsoid(Point3(x - 2.2f, y - 0.15f, z + 0.35f), 2.4f, 1.02f, 1.7f, puff.shade(0.97f), 9, 6, smoothShading = true)
            mesh.ellipsoid(Point3(x + 2.1f, y - 0.10f, z - 0.30f), 2.5f, 1.10f, 1.8f, puff.shade(0.95f), 9, 6, smoothShading = true)
            mesh.ellipsoid(Point3(x + 0.4f, y + 0.65f, z - 0.15f), 2.1f, 0.95f, 1.5f, Rgb(1f, 1f, 1f), 8, 5, smoothShading = true)
        }

        // True 3D Multi-Faceted Alpine Mountain Range (Back Tier)
        for (i in 0 until 13) {
            val centerX = -108f + i * 18f + (if (i % 2 == 0) 2.5f else -2.0f)
            val centerZ = 98f + (i % 3) * 6.5f
            val radiusX = 13.5f + (i % 3) * 2.2f
            val radiusZ = 11.5f + (i % 2) * 2.5f
            val peakY = 16.5f + (i % 4) * 3.8f
            val rockColor = if (i % 2 == 0) Rgb(0.38f, 0.49f, 0.60f) else Rgb(0.32f, 0.43f, 0.54f)
            draw3DMountainPeak(mesh, centerX, centerZ, radiusX, radiusZ, peakY, rockColor, hasSnowCap = true, seed = i * 17 + 3)
        }

        // True 3D Forested Foothill Range (Mid Tier)
        for (i in 0 until 14) {
            val centerX = -98f + i * 15f
            val centerZ = 76f + (i % 3) * 4.5f
            val radiusX = 10.5f + (i % 3) * 1.8f
            val radiusZ = 9.0f + (i % 2) * 2.0f
            val peakY = 9.2f + (i % 4) * 2.2f
            val ridgeColor = if (i % 2 == 0) Rgb(0.24f, 0.48f, 0.36f) else Rgb(0.20f, 0.42f, 0.32f)
            draw3DMountainPeak(mesh, centerX, centerZ, radiusX, radiusZ, peakY, ridgeColor, hasSnowCap = i % 3 == 0, seed = i * 29 + 11)
        }
    }

    private fun draw3DMountainPeak(
        mesh: TriangleMesh,
        cx: Float,
        cz: Float,
        rx: Float,
        rz: Float,
        height: Float,
        baseColor: Rgb,
        hasSnowCap: Boolean,
        seed: Int,
    ) {
        val baseY = -0.32f
        val sides = 6
        val apex = Point3(cx + sin(seed * 1.3f) * 1.1f, height, cz + cos(seed * 0.9f) * 0.8f)
        val basePts = List(sides) { s ->
            val angle = 2f * PI.toFloat() * s / sides + (seed % 5) * 0.12f
            val jitter = 0.88f + ((seed + s * 7) % 5) * 0.06f
            Point3(cx + cos(angle) * rx * jitter, baseY, cz + sin(angle) * rz * jitter)
        }
        val snowRatio = 0.36f
        val snowPts = basePts.map { bp ->
            Point3(
                apex.x + (bp.x - apex.x) * snowRatio,
                apex.y + (bp.y - apex.y) * snowRatio - ((seed + bp.x.toInt()) % 3) * 0.28f,
                apex.z + (bp.z - apex.z) * snowRatio,
            )
        }
        for (s in 0 until sides) {
            val next = (s + 1) % sides
            val shade = 0.88f + (s % 3) * 0.08f
            if (hasSnowCap) {
                mesh.quad(basePts[s], basePts[next], snowPts[next], snowPts[s], baseColor.shade(shade))
                val snowColor = if (s % 2 == 0) Rgb(0.95f, 0.98f, 1f) else Rgb(0.86f, 0.92f, 0.98f)
                mesh.triangle(snowPts[s], snowPts[next], apex, snowColor)
            } else {
                mesh.triangle(basePts[s], basePts[next], apex, baseColor.shade(shade))
            }
        }
    }

    private fun drawValleySideHills(
        mesh: TriangleMesh,
        cameraProgress: Float,
        time: Float,
        riverSections: List<Pair<Float, Float>>,
    ) {
        for (hill in 0 until 12) {
            val side = if (hill % 2 == 0) -1f else 1f
            val z = -12f + (hill * 9.5f + cameraProgress * COURSE_SCALE * 0.25f) % 92f
            if (riverSections.any { z in (it.first - 5f)..(it.second + 5f) }) continue
            val x = side * (17.5f + (hill % 3) * 4.5f)
            val rx = 6.5f + (hill % 3) * 1.8f
            val ry = 2.6f + (hill % 4) * 0.95f
            val rz = 6.2f + (hill % 2) * 1.6f
            val hillColor = if (hill % 2 == 0) Rgb(0.28f, 0.56f, 0.25f) else Rgb(0.34f, 0.60f, 0.27f)
            mesh.ellipsoid(Point3(x, -0.35f, z), rx, ry, rz, hillColor, 10, 6, smoothShading = true)
            if (hill % 3 == 0) {
                mesh.facetedRock(
                    Point3(x - side * (rx * 0.45f), 0.35f, z + 1.2f),
                    1.15f,
                    0.85f,
                    1.05f,
                    Rgb(0.50f, 0.53f, 0.52f),
                    hill * 19 + 5,
                )
            }
        }
    }

    private fun drawStylizedTree(mesh: TriangleMesh, x: Float, z: Float, species: Int, scale: Float, time: Float) {
        val sway = sin(time * 1.6f + x * 0.6f + z * 0.4f) * 0.08f
        // 3D Ground shadow under tree
        mesh.ellipsoid(Point3(x, -0.27f, z), 0.95f * scale, 0.02f, 0.95f * scale, Rgb(0.16f, 0.34f, 0.14f), 8, 4)
        when (species) {
            0 -> {
                // Lush 3D Oak Tree with tapered trunk and volumetric canopy spheres
                mesh.rod(Point3(x, -0.28f, z), Point3(x + sway * 0.4f, 1.45f * scale, z), 0.28f * scale, Rgb(0.42f, 0.25f, 0.14f), 8, 0.18f * scale)
                mesh.rod(Point3(x + sway * 0.2f, 0.85f * scale, z), Point3(x - 0.42f * scale + sway, 1.45f * scale, z + 0.18f * scale), 0.11f * scale, Rgb(0.38f, 0.22f, 0.12f), 6, 0.06f * scale)
                mesh.rod(Point3(x + sway * 0.2f, 0.95f * scale, z), Point3(x + 0.44f * scale + sway, 1.52f * scale, z - 0.16f * scale), 0.11f * scale, Rgb(0.38f, 0.22f, 0.12f), 6, 0.06f * scale)
                val foliageBaseY = 1.40f * scale
                val leafDark = Rgb(0.18f, 0.48f, 0.22f)
                val leafMid = Rgb(0.28f, 0.66f, 0.26f)
                val leafBright = Rgb(0.46f, 0.78f, 0.30f)
                mesh.ellipsoid(Point3(x + sway, foliageBaseY + 0.58f * scale, z), 1.02f * scale, 0.88f * scale, 1.02f * scale, leafMid, 9, 6)
                mesh.ellipsoid(Point3(x + sway - 0.48f * scale, foliageBaseY + 0.35f * scale, z + 0.26f * scale), 0.78f * scale, 0.70f * scale, 0.78f * scale, leafDark, 8, 5)
                mesh.ellipsoid(Point3(x + sway + 0.48f * scale, foliageBaseY + 0.44f * scale, z - 0.24f * scale), 0.82f * scale, 0.74f * scale, 0.82f * scale, leafMid.shade(1.06f), 8, 5)
                mesh.ellipsoid(Point3(x + sway * 1.2f, foliageBaseY + 1.18f * scale, z), 0.74f * scale, 0.66f * scale, 0.74f * scale, leafBright, 8, 5)
            }
            1 -> {
                // Layered 3D Alpine Pine Tree
                mesh.rod(Point3(x, -0.28f, z), Point3(x + sway * 0.3f, 2.85f * scale, z), 0.22f * scale, Rgb(0.36f, 0.20f, 0.11f), 7, 0.10f * scale)
                val pineBase = Rgb(0.14f, 0.42f, 0.30f)
                val pineMid = Rgb(0.18f, 0.50f, 0.34f)
                val pineTop = Rgb(0.24f, 0.60f, 0.38f)
                mesh.cone(x + sway * 0.35f, 0.52f * scale, z, 1.22f * scale, 1.35f * scale, pineBase, 8)
                mesh.cone(x + sway * 0.65f, 1.28f * scale, z, 0.98f * scale, 1.25f * scale, pineMid, 8)
                mesh.cone(x + sway * 0.95f, 2.02f * scale, z, 0.74f * scale, 1.15f * scale, pineTop, 8)
                mesh.cone(x + sway * 1.15f, 2.68f * scale, z, 0.44f * scale, 0.72f * scale, Rgb(0.90f, 0.96f, 0.98f), 7)
            }
            else -> {
                // Golden Autumn Birch with white bark and glowing amber canopy
                mesh.rod(Point3(x, -0.28f, z), Point3(x + sway * 0.4f, 1.75f * scale, z), 0.20f * scale, Rgb(0.90f, 0.87f, 0.80f), 7, 0.12f * scale)
                val amberGold = Rgb(0.95f, 0.64f, 0.18f)
                val apricot = Rgb(0.90f, 0.44f, 0.16f)
                val crownGold = Rgb(0.98f, 0.78f, 0.26f)
                val baseY = 1.48f * scale
                mesh.ellipsoid(Point3(x + sway, baseY + 0.55f * scale, z), 0.95f * scale, 0.84f * scale, 0.95f * scale, amberGold, 9, 6)
                mesh.ellipsoid(Point3(x + sway - 0.38f * scale, baseY + 0.32f * scale, z + 0.22f * scale), 0.72f * scale, 0.64f * scale, 0.72f * scale, apricot, 8, 5)
                mesh.ellipsoid(Point3(x + sway + 0.40f * scale, baseY + 0.42f * scale, z - 0.22f * scale), 0.75f * scale, 0.68f * scale, 0.75f * scale, crownGold, 8, 5)
            }
        }
    }

    private fun drawMeadows(
        mesh: TriangleMesh,
        cameraProgress: Float,
        time: Float,
        riverSections: List<Pair<Float, Float>>,
    ) {
        for (tuft in 0 until 96) {
            val side = if (tuft % 2 == 0) -1f else 1f
            val x = side * (4.45f + (tuft % 8) * 0.55f)
            val z = -10f + (tuft * 3.4f + cameraProgress * COURSE_SCALE * 0.75f) % 64f
            if (riverSections.any { z in (it.first - 0.8f)..(it.second + 0.8f) }) continue
            val windWave = sin(time * 2.5f + x * 0.4f + z * 0.6f) * 0.14f
            val green = when (tuft % 4) {
                0 -> Rgb(0.58f, 0.80f, 0.26f)
                1 -> Rgb(0.32f, 0.68f, 0.24f)
                2 -> Rgb(0.42f, 0.76f, 0.28f)
                else -> Rgb(0.68f, 0.76f, 0.30f)
            }
            for (blade in 0..2) {
                val bx = x + (blade - 1) * 0.11f
                val tip = Point3(bx + windWave, 0.22f + (tuft % 5) * 0.04f, z)
                val base0 = Point3(bx - 0.06f, -0.26f, z - 0.03f)
                val base1 = Point3(bx + 0.06f, -0.26f, z + 0.03f)
                mesh.triangle(base0, base1, tip, green.shade(if (blade % 2 == 0) 1.08f else 0.90f))
            }

            // 3D Wildflower blossoms & rounded meadow shrubs
            if (tuft % 9 == 0) {
                val flowerColor = when ((tuft / 9) % 3) {
                    0 -> Rgb(0.98f, 0.84f, 0.20f)
                    1 -> Rgb(0.96f, 0.32f, 0.36f)
                    else -> Rgb(0.44f, 0.78f, 0.98f)
                }
                mesh.rod(Point3(x + 0.14f, -0.26f, z), Point3(x + 0.14f + windWave * 0.5f, 0.16f, z), 0.022f, Rgb(0.26f, 0.56f, 0.22f), 5)
                mesh.sphere(Point3(x + 0.14f + windWave * 0.5f, 0.18f, z), 0.095f, flowerColor, 7, 5)
            }
            if (tuft % 16 == 0) {
                val bx = side * (5.2f + (tuft % 5) * 0.9f)
                mesh.ellipsoid(Point3(bx, -0.05f, z), 0.52f, 0.36f, 0.48f, Rgb(0.24f, 0.58f, 0.24f), 8, 5)
                mesh.ellipsoid(Point3(bx + 0.22f, -0.08f, z + 0.18f), 0.38f, 0.28f, 0.36f, Rgb(0.32f, 0.66f, 0.26f), 7, 5)
            }
        }
    }

    private fun drawTrail(mesh: TriangleMesh, z0: Float, z1: Float) {
        val length = (z1 - z0).coerceAtLeast(0.1f)
        val midZ = (z0 + z1) * 0.5f

        // 3D Raised Roadbed embankment with sloped gravel shoulders
        mesh.box(Point3(0f, -0.15f, midZ), 8.2f, 0.26f, length, Rgb(0.46f, 0.35f, 0.22f))
        mesh.box(Point3(0f, -0.03f, midZ), 7.2f, 0.06f, length, Rgb(0.64f, 0.50f, 0.32f))

        // 3D Sculpted Cobblestones along the trail
        val stoneStart = (kotlin.math.ceil(z0 / 1.8f) * 1.8f).coerceAtLeast(z0)
        var z = stoneStart
        var stoneIndex = 0
        while (z + 0.7f < z1) {
            val laneOffset = when (stoneIndex % 3) {
                0 -> -1.45f
                1 -> 0f
                else -> 1.45f
            }
            val stoneColor = if (stoneIndex % 2 == 0) Rgb(0.76f, 0.68f, 0.54f) else Rgb(0.70f, 0.62f, 0.48f)
            mesh.box(Point3(laneOffset, 0.008f, z + 0.35f), 0.88f, 0.035f, 0.68f, stoneColor)
            z += 1.8f
            stoneIndex++
        }

        // 3D Timber Curb Rails & Roadside Wooden Fence Posts
        for (side in listOf(-1f, 1f)) {
            val borderX = side * 3.72f
            mesh.box(Point3(borderX, 0.06f, midZ), 0.22f, 0.16f, length, Rgb(0.40f, 0.27f, 0.16f))
            var postZ = (kotlin.math.ceil(z0 / 4.5f) * 4.5f).coerceAtLeast(z0 + 0.4f)
            while (postZ < z1 - 0.4f) {
                mesh.box(Point3(borderX, 0.26f, postZ), 0.26f, 0.38f, 0.26f, Rgb(0.48f, 0.32f, 0.18f))
                mesh.box(Point3(borderX, 0.46f, postZ), 0.30f, 0.06f, 0.30f, Rgb(0.68f, 0.52f, 0.32f))
                postZ += 4.5f
            }
        }
    }

    private fun buildPitHazard(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
        val near = min(z1, z2)
        val far = max(z1, z2)
        val midZ = (near + far) * 0.5f
        val span = (far - near).coerceAtLeast(0.5f)
        val pitPulse = 0.5f + 0.5f * sin(time * 3.6f)

        // 3D Stone Trench Walls & Pit Floor
        mesh.plane(-3.85f, 3.85f, near, far, -0.68f, Rgb(0.08f, 0.09f, 0.11f))
        mesh.box(Point3(-3.75f, -0.32f, midZ), 0.36f, 0.72f, span, Rgb(0.32f, 0.28f, 0.24f))
        mesh.box(Point3(3.75f, -0.32f, midZ), 0.36f, 0.72f, span, Rgb(0.32f, 0.28f, 0.24f))
        mesh.box(Point3(0f, -0.32f, near), 7.6f, 0.72f, 0.28f, Rgb(0.38f, 0.31f, 0.24f))
        mesh.box(Point3(0f, -0.32f, far), 7.6f, 0.72f, 0.28f, Rgb(0.38f, 0.31f, 0.24f))

        // Glowing Magma Cracks along Pit Floor
        for (crack in 0..5) {
            val x = -2.7f + crack * 1.08f
            val crackColor = Rgb(1f, 0.42f + 0.28f * pitPulse, 0.10f)
            mesh.rod(Point3(x - 0.28f, -0.64f, near + 0.25f), Point3(x + 0.28f, -0.64f, far - 0.25f), 0.045f, crackColor, 6, emissive = true)
        }

        // 3D Forged Steel & Stone Spikes with Metallic Tips
        for (row in 0..2) {
            val rowZ = near + span * ((row + 0.5f) / 3f)
            repeat(8) { i ->
                val x = -2.95f + i * 0.84f + (if (row % 2 == 0) 0f else 0.22f)
                if (x < 3.25f) {
                    val height = 0.86f + ((i * 7 + row * 3) % 3) * 0.14f
                    val baseY = -0.64f
                    val baseCol = Rgb(0.38f, 0.42f, 0.46f)
                    val tipCol = Rgb(0.84f, 0.88f, 0.92f)
                    mesh.cone(x, baseY, rowZ, 0.30f, height * 0.72f, baseCol, 6)
                    mesh.cone(x, baseY + height * 0.62f, rowZ, 0.15f, height * 0.44f, tipCol, 6)
                }
            }
        }

        // 3D Warning Lantern Posts at Pit Edge
        for (side in listOf(-1f, 1f)) {
            val postX = side * 3.65f
            mesh.box(Point3(postX, 0.72f, near - 0.25f), 0.22f, 1.44f, 0.22f, Rgb(0.42f, 0.26f, 0.15f))
            mesh.sphere(Point3(postX, 1.54f, near - 0.25f), 0.16f, Rgb(1f, 0.76f + 0.2f * pitPulse, 0.22f), 8, 6, emissive = true)
        }
    }

    private fun buildBoulderHazard(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
        val midZ = (z1 + z2) * 0.5f
        val pulse = 0.5f + 0.5f * sin(time * 3f)
        // Main 3D Craggy Boulder Cluster + Moss & Glowing Amber Crystal Veins
        mesh.facetedRock(Point3(0f, 0.98f, midZ), 1.48f, 1.24f, 1.32f, Rgb(0.48f, 0.51f, 0.50f), 42)
        mesh.facetedRock(Point3(-1.35f, 0.62f, midZ + 0.18f), 0.92f, 0.78f, 0.86f, Rgb(0.44f, 0.47f, 0.46f), 17)
        mesh.facetedRock(Point3(1.38f, 0.66f, midZ - 0.14f), 0.96f, 0.82f, 0.88f, Rgb(0.46f, 0.49f, 0.48f), 77)
        mesh.facetedRock(Point3(-0.38f, 1.68f, midZ - 0.18f), 0.62f, 0.36f, 0.54f, Rgb(0.36f, 0.64f, 0.28f), 19)
        // Glowing 3D Crystal Shards on the boulder
        mesh.cone(-0.55f, 1.15f, midZ - 0.85f, 0.14f, 0.46f, Rgb(0.32f, 0.88f + 0.1f * pulse, 0.96f), 5, emissive = true)
        mesh.cone(0.62f, 0.95f, midZ - 0.88f, 0.12f, 0.38f, Rgb(0.32f, 0.88f + 0.1f * pulse, 0.96f), 5, emissive = true)
    }

    private fun buildWindTunnel(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
        val near = min(z1, z2)
        val far = max(z1, z2)
        // 3D Wind Turbine Towers on both sides of the trail with rotating 3D rotor blades
        for (marker in 0..2) {
            val z = near + (far - near) * (marker / 2f)
            for (side in listOf(-1f, 1f)) {
                val tx = side * 3.85f
                mesh.box(Point3(tx, 0.22f, z), 0.48f, 0.44f, 0.48f, Rgb(0.38f, 0.44f, 0.48f))
                mesh.rod(Point3(tx, 0.40f, z), Point3(tx, 2.35f, z), 0.12f, Rgb(0.76f, 0.82f, 0.86f), 8, 0.08f)
                val hub = Point3(tx - side * 0.14f, 2.32f, z)
                mesh.sphere(hub, 0.15f, Rgb(0.24f, 0.78f, 0.88f), 8, 6)
                for (blade in 0..2) {
                    val angle = time * 6.5f + marker * 1.2f + blade * (2f * PI.toFloat() / 3f)
                    val tipY = hub.y + sin(angle) * 0.78f
                    val tipZ = hub.z + cos(angle) * 0.78f
                    mesh.rod(hub, Point3(hub.x - side * 0.06f, tipY, tipZ), 0.055f, Rgb(0.88f, 0.95f, 0.98f), 5, 0.02f)
                }
            }
        }
        // Volumetric 3D Swirling Wind Stream Ribbons across the lane
        for (ribbon in 0..9) {
            val travel = (time * 0.72f + ribbon * 0.11f) % 1f
            val z = near + (far - near) * travel
            val y = 0.42f + (ribbon % 4) * 0.38f + sin(time * 3.2f + ribbon) * 0.16f
            val x = -2.8f + (ribbon % 3) * 2.4f + cos(time * 3.4f + ribbon) * 0.32f
            mesh.rod(
                Point3(x - 1.25f, y, z),
                Point3(x + 1.25f, y + 0.16f, z + 0.55f),
                0.044f,
                Rgb(0.58f, 0.94f, 0.98f),
                6,
                0.018f,
                emissive = true,
            )
        }
    }

    private fun buildRiver(
        mesh: TriangleMesh,
        z1: Float,
        z2: Float,
        bridge: Boolean,
        time: Float,
        players: List<RopeboundPlayer>,
        cameraProgress: Float,
    ) {
        val near = min(z1, z2) - 1.2f
        val far = max(z1, z2) + 1.2f
        val riverLength = far - near
        val nx = 38
        val nz = 10

        fun waterLevel(x: Float, z: Float): Float {
            val wave1 = sin(time * 2.4f + x * 0.36f + z * 1.2f) * 0.055f
            val wave2 = sin(time * 3.2f - x * 0.46f + z * 0.82f) * 0.032f
            return (-0.64f + wave1 + wave2).coerceIn(-0.75f, -0.52f)
        }

        // Deep riverbed
        mesh.plane(-23f, 23f, near, far, -0.98f, Rgb(0.08f, 0.20f, 0.26f))

        // 1. Dynamic 3D Water Wave Mesh with 3D surface normals for sun specular reflections
        for (ix in 0 until nx) {
            for (iz in 0 until nz) {
                val x0 = -23f + 46f * ix / nx
                val x1 = -23f + 46f * (ix + 1) / nx
                val a = near + riverLength * iz / nz
                val b = near + riverLength * (iz + 1) / nz

                val y00 = waterLevel(x0, a); val y01 = waterLevel(x0, b)
                val y11 = waterLevel(x1, b); val y10 = waterLevel(x1, a)

                val p0 = Point3(x0, y00, a); val p1 = Point3(x0, y01, b)
                val p2 = Point3(x1, y11, b); val p3 = Point3(x1, y10, a)

                val waveHeight = (y00 + y01 + y11 + y10) * 0.25f
                val crest = ((waveHeight + 0.64f) / 0.09f).coerceIn(-1f, 1f)
                val caustic = 0.5f + 0.5f * sin(ix * 0.42f - iz * 0.28f + time * 1.9f)
                val shimmer = 0.82f + (crest + 1f) * 0.07f + caustic * 0.09f
                val waterColor = Rgb(0.06f * shimmer, 0.44f * shimmer, 0.68f * shimmer)
                mesh.quad(p0, p1, p2, p3, waterColor)
            }
        }

        // 2. Flowing Whitewater Foam Streaks
        for (foam in 0..15) {
            val laneX = -19f + (foam % 5) * 9.5f + sin(time * 1.3f + foam * 1.7f) * 0.65f
            val travel = (time * 0.30f + foam * 0.143f) % 1f
            val z = near + riverLength * travel
            val startX = laneX - 0.88f; val endX = laneX + 0.88f
            val startY = waterLevel(startX, z) + 0.015f
            val endY = waterLevel(endX, z + 0.22f) + 0.015f
            val sprayColor = if (foam % 2 == 0) Rgb(0.90f, 0.98f, 1f) else Rgb(0.72f, 0.94f, 0.98f)
            mesh.rod(Point3(startX, startY, z), Point3(endX, endY, z + 0.22f), 0.022f, sprayColor, 5, emissive = true)
        }

        // 3. Sculpted Riverbanks & Rocks OUTSIDE the road corridor (x < -4.2f and x > 4.2f)
        for ((bankIndex, bankZ) in listOf(near, far).withIndex()) {
            val landSign = if (bankIndex == 0) -1f else 1f
            val shoreColor = Rgb(0.66f, 0.58f, 0.42f)
            val shoreTopZ = bankZ + landSign * 0.85f
            // Left and right sloped shores leaving the center bridge abutment clear
            mesh.quad(
                Point3(-23f, -0.66f, bankZ),
                Point3(-23f, -0.28f, shoreTopZ),
                Point3(-3.9f, -0.28f, shoreTopZ),
                Point3(-3.9f, -0.66f, bankZ),
                shoreColor,
            )
            mesh.quad(
                Point3(3.9f, -0.66f, bankZ),
                Point3(3.9f, -0.28f, shoreTopZ),
                Point3(23f, -0.28f, shoreTopZ),
                Point3(23f, -0.66f, bankZ),
                shoreColor,
            )

            // Stone Bridge Abutment & Timber Approach Ramp in the center road corridor (-3.9..3.9)
            mesh.box(Point3(0f, -0.32f, bankZ + landSign * 0.35f), 7.8f, 0.68f, 0.85f, Rgb(0.46f, 0.48f, 0.50f))
            mesh.box(Point3(0f, 0.04f, bankZ + landSign * 0.42f), 6.2f, 0.09f, 0.92f, Rgb(0.58f, 0.42f, 0.26f))

            // Riverbank boulders placed strictly on the left/right riverbanks (never on the road!)
            for (rock in 0..18) {
                val rawX = -21f + rock * 2.25f
                if (abs(rawX) < 4.55f) continue
                val rz = bankZ + landSign * (0.95f + sin(rock * 1.8f + bankIndex) * 0.22f)
                val scale = 0.58f + (rock % 4) * 0.14f
                val rockColor = if (rock % 2 == 0) Rgb(0.52f, 0.55f, 0.53f) else Rgb(0.60f, 0.58f, 0.52f)
                mesh.facetedRock(Point3(rawX, -0.08f, rz), scale, 0.36f + (rock % 3) * 0.08f, 0.48f + scale * 0.15f, rockColor, rock + bankIndex * 37)
            }
        }

        // 4. 3D Wooden Suspension Bridge with Dynamic Flex
        if (bridge) {
            val bridgeWidth = 5.8f
            val bridgeLength = far - near
            val planks = 14

            fun footstepsAt(progress: Float, radius: Float): Float {
                var flex = 0f
                players.forEachIndexed { playerIndex, player ->
                    val influence = (1f - abs(player.progress - progress) / radius).coerceIn(0f, 1f)
                    val footfall = if (player.velocity > 0.12f) {
                        sin(time * (7.8f + player.velocity.coerceAtMost(3f)) + playerIndex * PI.toFloat()) * 0.075f
                    } else 0f
                    val landing = if (player.jumping) 0.038f else 0f
                    flex += influence * (footfall + landing)
                }
                return flex.coerceIn(-0.12f, 0.12f)
            }

            for (index in 0 until planks) {
                val t = (index + 0.5f) / planks
                val z = near + bridgeLength * t
                val arch = sin(t * PI).toFloat() * 0.18f
                val plankProgress = ((z / COURSE_SCALE) + cameraProgress * 100f) / 100f
                val stepFlex = footstepsAt(plankProgress, 2.6f)
                val wobble = sin(time * 2.2f + index * 0.68f) * 0.018f + stepFlex
                val roll = sin(time * 2.7f + index * 0.54f) * 0.020f + stepFlex * 0.55f
                val wood = if (index % 2 == 0) Rgb(0.66f, 0.42f, 0.23f) else Rgb(0.76f, 0.50f, 0.28f)
                val plankY = 0.08f + arch + wobble

                mesh.bridgePlank(Point3(0f, plankY, z), bridgeWidth, 0.18f, bridgeLength / planks * 0.88f, roll, wood)

                for (side in listOf(-1f, 1f)) {
                    val boltX = side * 2.62f
                    mesh.sphere(Point3(boltX, plankY + 0.10f, z), 0.045f, Rgb(0.84f, 0.76f, 0.54f), 6, 4)
                    val railY = 0.92f + arch * 0.6f + wobble * 0.75f
                    mesh.rod(Point3(side * 2.76f, plankY + 0.08f, z), Point3(side * 2.76f, railY, z), 0.034f, Rgb(0.84f, 0.70f, 0.46f), 5)
                }
            }

            // 3D Timber Suspension Towers & Crossbeams at both ends
            for (endZ in listOf(near, far)) {
                for (side in listOf(-1f, 1f)) {
                    val postX = side * 2.82f
                    mesh.box(Point3(postX, 0.82f, endZ), 0.36f, 1.68f, 0.36f, Rgb(0.44f, 0.25f, 0.13f))
                    mesh.box(Point3(postX, 1.70f, endZ), 0.44f, 0.16f, 0.44f, Rgb(0.62f, 0.38f, 0.20f))
                }
            }

            // Catenary 3D Main Cables
            for (side in listOf(-1f, 1f)) {
                var previous = Point3(side * 2.82f, 1.52f, near)
                for (step in 1..24) {
                    val t = step / 24f
                    val sag = -sin(t * PI).toFloat() * 0.52f
                    val current = Point3(side * 2.82f, 1.52f + sag, near + bridgeLength * t)
                    mesh.rod(previous, current, 0.055f, Rgb(0.56f, 0.36f, 0.20f), 6)
                    previous = current
                }
            }
        }
    }

    private fun buildLava(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
        val near = min(z1, z2) - 0.8f
        val far = max(z1, z2) + 0.8f
        val canyonLength = far - near
        val pulse = 0.5f + 0.5f * sin(time * 3.6f)

        mesh.plane(-4.6f, 4.6f, near, far, -0.46f, Rgb(0.12f, 0.07f, 0.06f))
        for (side in listOf(-1f, 1f)) {
            val wallX = side * 3.92f
            mesh.box(Point3(wallX, -0.14f, (near + far) * 0.5f), 0.86f, 0.56f, canyonLength, Rgb(0.22f, 0.14f, 0.12f))
        }

        val nx = 24
        val nz = 8
        fun lavaLevel(x: Float, z: Float): Float {
            val boil1 = sin(time * 3.4f + x * 0.75f + z * 1.8f) * 0.065f
            val boil2 = sin(time * 4.6f - x * 0.98f + z * 1.2f) * 0.035f
            return -0.18f + boil1 + boil2
        }

        for (ix in 0 until nx) {
            for (iz in 0 until nz) {
                val x0 = -3.45f + 6.9f * ix / nx
                val x1 = -3.45f + 6.9f * (ix + 1) / nx
                val a = near + canyonLength * iz / nz
                val b = near + canyonLength * (iz + 1) / nz
                val y00 = lavaLevel(x0, a); val y01 = lavaLevel(x0, b)
                val y11 = lavaLevel(x1, b); val y10 = lavaLevel(x1, a)
                val p0 = Point3(x0, y00, a); val p1 = Point3(x0, y01, b)
                val p2 = Point3(x1, y11, b); val p3 = Point3(x1, y10, a)
                val heatPulse = 0.5f + 0.5f * sin(time * 4f + ix * 0.8f + iz * 0.9f)
                val magmaColor = when {
                    heatPulse > 0.72f -> Rgb(1f, 0.86f + 0.12f * pulse, 0.24f)
                    heatPulse > 0.35f -> Rgb(1f, 0.42f + 0.12f * pulse, 0.06f)
                    else -> Rgb(0.86f, 0.16f, 0.04f)
                }
                mesh.quad(p0, p1, p2, p3, magmaColor, emissive = true)
            }
        }

        for (plate in 0..7) {
            val px = -2.4f + (plate % 3) * 2.2f + sin(time * 0.8f + plate) * 0.18f
            val pz = near + canyonLength * ((plate * 0.31f + time * 0.08f) % 1f)
            val py = lavaLevel(px, pz) + 0.03f
            val plateSize = 0.68f + (plate % 3) * 0.16f
            mesh.facetedRock(Point3(px, py, pz), plateSize, 0.16f, plateSize * 0.85f, Rgb(0.18f, 0.12f, 0.11f), plate * 19 + 7)
        }

        for (flame in 0..6) {
            val fx = -2.6f + flame * 0.86f + sin(time * 2f + flame) * 0.12f
            val phase = time * 4.4f + flame * 1.8f
            val burstHeight = 0.38f + (0.45f + 0.5f * sin(phase)) * 1.15f
            val fz = near + canyonLength * ((flame % 4) / 4f + 0.12f)
            mesh.cone(fx, -0.14f, fz, 0.38f, burstHeight, Rgb(1f, 0.28f, 0.04f), 6, emissive = true)
            mesh.cone(fx, -0.10f, fz, 0.22f, burstHeight * 0.72f, Rgb(1f, 0.78f, 0.16f), 5, emissive = true)
        }
    }

    private fun drawCheckpointGate(mesh: TriangleMesh, z: Float, checkpoint: Float, reached: Boolean, time: Float) {
        val flagColor = if (reached) Rgb(0.22f, 0.94f, 0.68f) else Rgb(1f, 0.76f, 0.24f)
        for (side in listOf(-1f, 1f)) {
            val px = side * 3.75f
            mesh.box(Point3(px, 0.18f, z), 0.38f, 0.36f, 0.38f, Rgb(0.46f, 0.48f, 0.52f))
            mesh.rod(Point3(px, 0.32f, z), Point3(px, 2.55f, z), 0.075f, Rgb(0.92f, 0.86f, 0.72f), 7, 0.055f)
            mesh.sphere(Point3(px, 2.62f, z), 0.12f, flagColor, 8, 5, emissive = reached)
            val flutter = sin(time * 4.5f + checkpoint + side) * 0.15f
            val dir = -side
            mesh.triangle(
                Point3(px, 2.48f, z),
                Point3(px + dir * 1.15f, 2.18f + flutter * 0.3f, z + flutter),
                Point3(px, 1.85f, z),
                flagColor,
                emissive = reached,
            )
        }
    }

    private fun drawFinishArch(mesh: TriangleMesh, finishZ: Float, time: Float) {
        val pulse = 0.5f + 0.5f * sin(time * 4f)
        for (side in listOf(-1f, 1f)) {
            val px = side * 3.85f
            mesh.box(Point3(px, 0.35f, finishZ), 0.62f, 0.70f, 0.62f, Rgb(0.38f, 0.42f, 0.48f))
            mesh.rod(Point3(px, 0.65f, finishZ), Point3(px, 3.85f, finishZ), 0.18f, Rgb(0.96f, 0.80f, 0.32f), 8, 0.14f)
            mesh.sphere(Point3(px, 4.02f, finishZ), 0.26f, Rgb(1f, 0.88f + 0.1f * pulse, 0.32f), 9, 6, emissive = true)
        }
        mesh.box(Point3(0f, 3.55f, finishZ), 8.2f, 0.58f, 0.32f, Rgb(0.16f, 0.82f, 0.72f))
        // 3D Checkered Finish Banner tiles
        for (col in 0 until 12) {
            for (row in 0..1) {
                val tx = -3.3f + col * 0.60f
                val ty = 3.42f + row * 0.24f
                val tileColor = if ((col + row) % 2 == 0) Rgb(0.98f, 0.98f, 1f) else Rgb(0.12f, 0.16f, 0.22f)
                mesh.box(Point3(tx, ty, finishZ + 0.17f), 0.56f, 0.22f, 0.04f, tileColor)
                mesh.box(Point3(tx, ty, finishZ - 0.17f), 0.56f, 0.22f, 0.04f, tileColor)
            }
        }
    }

    /**
     * Full 3D Articulated Character Model with 3D yaw rotation toward movement direction,
     * pitch lean, turn banking, 2-joint arms & legs, sculpted head/face/eyes, backpack, and climbing harness.
     * Returns the world-space 3D position of the harness carabiner where the team rope attaches.
     */
    private fun drawRunner(
        mesh: TriangleMesh,
        player: RopeboundPlayer,
        x: Float,
        z: Float,
        jump: Float,
        index: Int,
        isLocalPlayer: Boolean,
        localMoveInput: Float,
        localSteerInput: Float,
        surgeActive: Boolean,
        time: Float,
        dt: Float,
    ): Point3 {
        val tracker = runnerTrackers.getOrPut(player.id.ifBlank { "runner_$index" }) { RunnerMotionTracker() }
        val worldZ = player.progress * 100f * COURSE_SCALE
        val vx: Float
        val vz: Float
        if (!tracker.initialized) {
            tracker.lastX = x
            tracker.lastZ = worldZ
            tracker.initialized = true
            vx = 0f
            vz = 0f
        } else {
            vx = ((x - tracker.lastX) / dt).coerceIn(-12f, 12f)
            vz = ((worldZ - tracker.lastZ) / dt).coerceIn(-15f, 15f)
            tracker.lastX = x
            tracker.lastZ = worldZ
        }

        val inputSpeed = if (isLocalPlayer) hypot(localSteerInput, localMoveInput) else 0f
        val effectiveVx = if (isLocalPlayer && inputSpeed > 0.12f) localSteerInput * 4.5f else vx
        val effectiveVz = if (isLocalPlayer && inputSpeed > 0.12f) localMoveInput * 8.5f else vz
        val planarSpeed = max(hypot(effectiveVx, effectiveVz), player.velocity * 0.65f)
        val isMoving = planarSpeed > 0.22f && !player.bracing

        // Compute target 3D heading yaw so the 3D character rotates to face their movement direction!
        val targetYaw = when {
            isLocalPlayer && inputSpeed > 0.12f -> atan2(localSteerInput, localMoveInput)
            hypot(vx, vz) > 0.28f -> atan2(vx, vz)
            !isMoving -> sin(time * 1.4f + index * 1.3f) * 0.18f
            else -> tracker.smoothYaw
        }

        // Smooth shortest-angle interpolation for 3D character rotation
        var angleDiff = (targetYaw - tracker.smoothYaw) % (2f * PI.toFloat())
        if (angleDiff > PI.toFloat()) angleDiff -= 2f * PI.toFloat()
        if (angleDiff < -PI.toFloat()) angleDiff += 2f * PI.toFloat()
        tracker.smoothYaw += angleDiff * (dt * 11f).coerceIn(0.08f, 0.45f)

        val targetLean = when {
            player.bracing -> -0.18f
            player.jumping -> 0.10f
            isMoving -> (planarSpeed / 10f).coerceIn(0.06f, 0.24f)
            else -> 0f
        }
        val targetBank = (-effectiveVx * 0.045f).coerceIn(-0.20f, 0.20f)
        tracker.smoothLean += (targetLean - tracker.smoothLean) * 0.25f
        tracker.smoothBank += (targetBank - tracker.smoothBank) * 0.25f

        if (isMoving && player.grounded) {
            tracker.animDistance += planarSpeed * dt * 2.1f
        }

        val palette = listOf(
            Rgb(0.46f, 0.34f, 0.90f),
            Rgb(0.90f, 0.30f, 0.28f),
            Rgb(0.16f, 0.72f, 0.60f),
            Rgb(0.95f, 0.56f, 0.16f),
            Rgb(0.24f, 0.56f, 0.92f),
        )
        val suit = palette[index % palette.size]
        val pantsColor = Rgb(0.18f, 0.24f, 0.32f)
        val hairPalette = listOf(
            Rgb(0.14f, 0.10f, 0.08f),
            Rgb(0.36f, 0.18f, 0.09f),
            Rgb(0.76f, 0.42f, 0.16f),
            Rgb(0.16f, 0.20f, 0.26f),
        )
        val skinPalette = listOf(
            Rgb(0.96f, 0.72f, 0.54f),
            Rgb(0.80f, 0.52f, 0.36f),
            Rgb(0.98f, 0.78f, 0.62f),
            Rgb(0.58f, 0.36f, 0.26f),
        )
        val styleSeed = player.id.hashCode() and Int.MAX_VALUE
        val hair = hairPalette[styleSeed % hairPalette.size]
        val skin = skinPalette[(styleSeed / 3) % skinPalette.size]
        val packColor = palette[(index + 2) % palette.size].shade(0.88f)

        val runPhase = tracker.animDistance + time * (if (isMoving) 7.5f else 0f) + index * 0.8f
        val stride = when {
            player.jumping -> 0.42f
            isMoving && player.grounded -> sin(runPhase) * (0.34f + (planarSpeed / 18f).coerceAtMost(0.22f))
            else -> 0f
        }
        val bob = when {
            player.bracing -> -0.14f
            isMoving && player.grounded -> abs(sin(runPhase)) * 0.075f
            else -> sin(time * 2.4f + index) * 0.022f
        }
        val baseY = jump + bob

        // 1. 3D Ground Shadow & Local Player / Squad Surge 3D Selection Ring
        val shadowScale = (1f - (jump / 3.5f).coerceIn(0f, 0.55f))
        mesh.ellipsoid(Point3(x, 0.014f, z), 0.52f * shadowScale, 0.02f, 0.44f * shadowScale, Rgb(0.10f, 0.18f, 0.14f), 10, 4)
        if (isLocalPlayer || surgeActive) {
            val ringColor = if (surgeActive) Rgb(0.28f, 0.98f, 0.86f) else Rgb(0.32f, 0.88f, 0.98f)
            val segments = 12
            val ringR = 0.56f + sin(time * 4f) * 0.03f
            for (s in 0 until segments) {
                val a0 = 2 * PI * s / segments
                val a1 = 2 * PI * (s + 1) / segments
                mesh.rod(
                    Point3(x + cos(a0).toFloat() * ringR, 0.035f, z + sin(a0).toFloat() * ringR),
                    Point3(x + cos(a1).toFloat() * ringR, 0.035f, z + sin(a1).toFloat() * ringR),
                    0.022f,
                    ringColor,
                    5,
                    emissive = true,
                )
            }
        }

        // 3D Footstep Dust Puffs when sprinting on ground
        if (isMoving && player.grounded) {
            for (puff in 0..1) {
                val puffAge = (time * 3.8f + index * 0.5f + puff * 0.5f) % 1f
                val puffR = 0.08f * (1f - puffAge * 0.65f)
                val backDirX = -sin(tracker.smoothYaw)
                val backDirZ = -cos(tracker.smoothYaw)
                val sideX = cos(tracker.smoothYaw) * (if (puff == 0) -0.18f else 0.18f)
                val sideZ = -sin(tracker.smoothYaw) * (if (puff == 0) -0.18f else 0.18f)
                mesh.sphere(
                    Point3(
                        x + sideX + backDirX * (0.25f + puffAge * 0.55f),
                        0.08f + puffAge * 0.22f,
                        z + sideZ + backDirZ * (0.25f + puffAge * 0.55f),
                    ),
                    puffR,
                    Rgb(0.86f, 0.80f, 0.68f),
                    6,
                    4,
                )
            }
        }

        // Pose3D transforms all character body parts by the runner's 3D heading yaw, lean, and bank!
        val pose = Pose3D(
            originX = x,
            originY = baseY,
            originZ = z,
            yaw = tracker.smoothYaw,
            pitch = tracker.smoothLean,
            roll = tracker.smoothBank,
        )

        // 2. Articulated 2-Joint 3D Legs, Knees & Sculpted Trail Boots
        for (side in listOf(-1f, 1f)) {
            val legPhase = if (side < 0f) stride else -stride
            val kneeLift = when {
                player.jumping -> 0.22f
                player.bracing -> 0.14f
                isMoving -> max(0f, -legPhase) * 0.35f
                else -> 0f
            }
            val hip = pose.p(side * 0.165f, 0.64f, 0f)
            val knee = pose.p(
                side * 0.175f,
                0.35f + kneeLift * 0.45f,
                legPhase * 0.48f + (if (player.jumping || player.bracing) 0.14f else 0.05f),
            )
            val ankle = pose.p(
                side * 0.18f,
                0.10f + kneeLift * 0.75f,
                legPhase * 0.85f - (if (player.jumping) 0.12f else 0f),
            )
            val bootToe = pose.p(
                side * 0.18f,
                0.06f + kneeLift * 0.75f,
                legPhase * 0.85f + 0.16f - (if (player.jumping) 0.10f else 0f),
            )
            val bootSole = pose.p(
                side * 0.18f,
                0.025f + kneeLift * 0.75f,
                legPhase * 0.85f + 0.06f - (if (player.jumping) 0.10f else 0f),
            )

            // Upper thigh, 3D knee pad, lower shin
            mesh.rod(hip, knee, 0.108f, pantsColor, 8, 0.092f)
            mesh.sphere(knee, 0.096f, Rgb(0.28f, 0.34f, 0.42f), 8, 6)
            mesh.rod(knee, ankle, 0.088f, pantsColor.shade(1.06f), 8, 0.074f)
            // Ankle cuff, 3D hiking boot upper, toe cap, and lugged sole
            mesh.sphere(ankle, 0.082f, Rgb(0.88f, 0.84f, 0.74f), 7, 5)
            mesh.rod(ankle, bootToe, 0.095f, suit.shade(0.84f), 8, 0.085f)
            mesh.sphere(bootToe, 0.088f, Rgb(0.24f, 0.28f, 0.34f), 8, 5)
            mesh.sphere(bootSole, 0.092f, Rgb(0.92f, 0.84f, 0.64f), 8, 4)
        }

        // 3. 3D Pelvis, Climbing Harness & Sculpted Torso Jacket
        mesh.orientedEllipsoid(pose, 0f, 0.64f, 0f, 0.25f, 0.14f, 0.19f, pantsColor.shade(0.92f), 10, 6)
        mesh.orientedEllipsoid(pose, 0f, 0.98f, 0f, 0.28f, 0.34f, 0.21f, suit, 12, 8)
        mesh.orientedBox(pose, 0f, 0.96f, 0f, 0.50f, 0.52f, 0.34f, suit.shade(0.92f))
        // Front zipper / shirt panel & collar
        mesh.orientedBox(pose, 0f, 0.98f, 0.165f, 0.15f, 0.48f, 0.05f, Rgb(0.96f, 0.92f, 0.82f))
        mesh.orientedEllipsoid(pose, 0f, 1.25f, 0.02f, 0.21f, 0.08f, 0.19f, suit.shade(1.18f), 10, 5)

        // 3D Climbing Harness Belt & Metallic Carabiner Rings (Front + Back)
        mesh.orientedBox(pose, 0f, 0.72f, 0f, 0.53f, 0.08f, 0.38f, Rgb(0.22f, 0.26f, 0.30f))
        mesh.orientedBox(pose, 0f, 0.72f, 0.195f, 0.12f, 0.09f, 0.04f, Rgb(0.95f, 0.78f, 0.32f))

        // 4. 3D Expedition Backpack with Bedroll Cylinder, Side Pouches & Shoulder Straps
        mesh.orientedBox(pose, 0f, 1.03f, -0.25f, 0.42f, 0.48f, 0.20f, packColor)
        mesh.orientedBox(pose, 0f, 0.96f, -0.36f, 0.28f, 0.22f, 0.06f, packColor.shade(1.18f))
        mesh.orientedBox(pose, 0f, 1.24f, -0.25f, 0.44f, 0.10f, 0.22f, suit.shade(1.16f))
        // Strapped 3D Sleeping Roll on top of backpack
        mesh.rod(
            pose.p(-0.22f, 1.33f, -0.24f),
            pose.p(0.22f, 1.33f, -0.24f),
            0.075f,
            Rgb(0.92f, 0.76f, 0.44f),
            8,
        )
        // Shoulder straps wrapping from pack over shoulders to chest
        for (side in listOf(-1f, 1f)) {
            mesh.rod(
                pose.p(side * 0.18f, 1.26f, -0.15f),
                pose.p(side * 0.19f, 1.26f, 0.15f),
                0.036f,
                Rgb(0.92f, 0.74f, 0.38f),
                6,
            )
            mesh.rod(
                pose.p(side * 0.19f, 1.26f, 0.15f),
                pose.p(side * 0.17f, 0.76f, 0.17f),
                0.032f,
                Rgb(0.92f, 0.74f, 0.38f),
                6,
            )
        }

        // 5. Articulated 2-Joint 3D Arms, Elbows, Forearms & Hands
        for (side in listOf(-1f, 1f)) {
            val armSwing = when {
                player.jumping -> 0.48f
                player.bracing -> -0.22f
                isMoving -> if (side < 0f) -stride * 0.95f else stride * 0.95f
                else -> sin(time * 2.2f + index + side) * 0.04f
            }
            val shoulder = pose.p(side * 0.30f, 1.18f, 0f)
            val elbow = pose.p(
                side * (0.38f + (if (player.bracing) 0.08f else 0f)),
                0.95f + (if (player.jumping) 0.18f else abs(armSwing) * 0.12f),
                armSwing * 0.42f - 0.06f,
            )
            val hand = pose.p(
                side * (0.40f + (if (player.bracing) 0.10f else 0f)),
                0.76f + (if (player.jumping) 0.36f else max(0f, armSwing) * 0.24f),
                armSwing * 0.82f + 0.12f,
            )
            mesh.sphere(shoulder, 0.105f, suit.shade(1.06f), 8, 6)
            mesh.rod(shoulder, elbow, 0.095f, suit, 8, 0.082f)
            mesh.sphere(elbow, 0.084f, suit.shade(0.88f), 8, 5)
            mesh.rod(elbow, hand, 0.078f, suit.shade(0.95f), 8, 0.068f)
            mesh.sphere(hand, 0.084f, skin, 8, 6)
        }

        // 6. Sculpted 3D Neck, Head, Ears, Nose, Eyes & Expression
        val neckBase = pose.p(0f, 1.24f, 0f)
        val headCenter = pose.p(0f, 1.60f, 0.02f)
        mesh.rod(neckBase, pose.p(0f, 1.42f, 0.01f), 0.105f, skin, 8, 0.095f)
        mesh.orientedEllipsoid(pose, 0f, 1.60f, 0.02f, 0.265f, 0.30f, 0.255f, skin, 14, 10)

        // 3D Ears
        for (side in listOf(-1f, 1f)) {
            mesh.sphere(pose.p(side * 0.255f, 1.58f, 0.01f), 0.068f, skin.shade(0.92f), 7, 5)
        }

        // 3D Eyes (Sclera, Colored Iris, Pupil & Catchlight) + Eyebrows + 3D Nose + Smile
        val irisColor = if (styleSeed % 3 == 0) Rgb(0.20f, 0.56f, 0.68f) else Rgb(0.36f, 0.22f, 0.12f)
        for (side in listOf(-1f, 1f)) {
            val ex = side * 0.098f
            mesh.sphere(pose.p(ex, 1.63f, 0.225f), 0.052f, Rgb(0.99f, 0.98f, 0.95f), 8, 6)
            mesh.sphere(pose.p(ex, 1.63f, 0.252f), 0.030f, irisColor, 7, 5)
            mesh.sphere(pose.p(ex, 1.63f, 0.268f), 0.017f, Rgb(0.05f, 0.06f, 0.08f), 6, 4)
            mesh.sphere(pose.p(ex - 0.010f, 1.645f, 0.278f), 0.008f, Rgb(1f, 1f, 1f), 5, 4, emissive = true)
            // Eyebrow
            mesh.rod(
                pose.p(ex - 0.052f, 1.70f, 0.225f),
                pose.p(ex + 0.052f, 1.708f, 0.225f),
                0.020f,
                hair.shade(0.9f),
                6,
            )
        }
        // Sculpted 3D Nose & Smile
        mesh.sphere(pose.p(0f, 1.565f, 0.278f), 0.044f, skin.shade(0.94f), 8, 5)
        mesh.rod(
            pose.p(-0.068f, 1.485f, 0.242f),
            pose.p(0f, 1.468f, 0.258f),
            0.015f,
            Rgb(0.46f, 0.18f, 0.18f),
            5,
        )
        mesh.rod(
            pose.p(0f, 1.468f, 0.258f),
            pose.p(0.068f, 1.485f, 0.242f),
            0.015f,
            Rgb(0.46f, 0.18f, 0.18f),
            5,
        )

        // 7. Full 360° 3D Hair Shell & Distinct Character Headgear/Hairstyles
        mesh.orientedEllipsoid(pose, 0f, 1.76f, -0.01f, 0.28f, 0.16f, 0.275f, hair, 12, 8)
        mesh.orientedEllipsoid(pose, 0f, 1.60f, -0.09f, 0.265f, 0.26f, 0.20f, hair.shade(0.92f), 12, 8)
        for (side in listOf(-1f, 1f)) {
            mesh.orientedEllipsoid(pose, side * 0.205f, 1.64f, -0.04f, 0.095f, 0.21f, 0.16f, hair.shade(0.94f), 8, 6)
        }

        when (index % 4) {
            0 -> {
                // Explorer Visor Cap + Dynamic 3D Ponytail
                mesh.orientedEllipsoid(pose, 0f, 1.82f, 0.02f, 0.285f, 0.11f, 0.28f, suit.shade(1.12f), 10, 6)
                mesh.orientedBox(pose, 0f, 1.76f, 0.24f, 0.34f, 0.035f, 0.18f, suit.shade(0.86f))
                val sway = stride * 0.35f
                val tie = pose.p(0f, 1.72f, -0.27f)
                val midTail = pose.p(sway * 0.6f, 1.54f, -0.38f)
                val tipTail = pose.p(sway, 1.36f, -0.35f)
                mesh.sphere(tie, 0.055f, Rgb(0.95f, 0.76f, 0.28f), 7, 5)
                mesh.rod(tie, midTail, 0.082f, hair, 7, 0.065f)
                mesh.rod(midTail, tipTail, 0.065f, hair.shade(0.90f), 7, 0.038f)
            }
            1 -> {
                // Adventurer Goggles on Forehead + Textured 3D Spiked Crown
                mesh.orientedBox(pose, 0f, 1.75f, 0.23f, 0.36f, 0.085f, 0.07f, Rgb(0.24f, 0.28f, 0.34f))
                mesh.sphere(pose.p(-0.09f, 1.75f, 0.26f), 0.052f, Rgb(0.38f, 0.88f, 0.96f), 7, 5)
                mesh.sphere(pose.p(0.09f, 1.75f, 0.26f), 0.052f, Rgb(0.38f, 0.88f, 0.96f), 7, 5)
                for (tuft in -1..1) {
                    val base = pose.p(tuft * 0.10f, 1.82f, -0.04f)
                    val tip = pose.p(tuft * 0.13f, 1.98f, -0.10f)
                    mesh.rod(base, tip, 0.075f, hair.shade(1.08f), 6, 0.02f)
                }
            }
            2 -> {
                // Squad Headset + Twin 3D Shoulder Locks
                mesh.rod(pose.p(-0.27f, 1.60f, 0.01f), pose.p(0.27f, 1.60f, 0.01f), 0.025f, Rgb(0.20f, 0.86f, 0.78f), 6)
                for (side in listOf(-1f, 1f)) {
                    mesh.sphere(pose.p(side * 0.275f, 1.58f, 0.01f), 0.078f, Rgb(0.18f, 0.24f, 0.30f), 8, 5)
                    mesh.rod(
                        pose.p(side * 0.21f, 1.56f, -0.12f),
                        pose.p(side * 0.24f, 1.32f, -0.15f),
                        0.075f,
                        hair,
                        7,
                        0.045f,
                    )
                }
            }
            else -> {
                // Mountaineer Beanie with Pom-Pom + Braided Back Tail
                mesh.orientedEllipsoid(pose, 0f, 1.82f, -0.02f, 0.28f, 0.15f, 0.27f, packColor.shade(1.15f), 10, 6)
                mesh.sphere(pose.p(0f, 1.97f, -0.04f), 0.085f, Rgb(0.96f, 0.94f, 0.88f), 8, 5)
                var braidPrev = pose.p(0f, 1.68f, -0.26f)
                for (b in 1..3) {
                    val braidNext = pose.p(sin(time * 3f + b) * 0.03f, 1.68f - b * 0.11f, -0.30f)
                    mesh.rod(braidPrev, braidNext, 0.052f, hair.shade(if (b % 2 == 0) 1.06f else 0.88f), 6)
                    braidPrev = braidNext
                }
            }
        }

        // Return the 3D world position of the runner's harness carabiner clip
        return pose.p(0f, 0.92f, -0.38f)
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }

    private companion object {
        val IDENTITY = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    }
}
