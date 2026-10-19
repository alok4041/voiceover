package com.squadvoice.app.game

import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal const val COURSE_SCALE = 0.80f
internal val SIDES = floatArrayOf(-1f, 1f)

/**
 * Lock-free live control feed from touch inputs directly to the 60 FPS OpenGL thread.
 */
internal class RopeboundLiveControlFeed {
    @Volatile var move: Float = 0f
    @Volatile var steer: Float = 0f
    @Volatile var bracing: Boolean = false
    @Volatile var jumpStartedAtMs: Long = 0L
}

/**
 * Lock-free 60 FPS render telemetry shared from the OpenGL thread to the 2D Nameplate overlay.
 */
internal class RopeboundLiveRenderTelemetry {
    @Volatile var framesRendered: Long = 0L
    @Volatile var cameraProgress: Float = -1f
    @Volatile var focusX: Float = 0f
    @Volatile var focusY: Float = 0f
    @Volatile var playerCount: Int = 0
    val playerProgress = FloatArray(12)
    val playerLat = FloatArray(12)
    val playerHeight = FloatArray(12)
}

internal fun computeCameraLookAt(
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

internal data class Point3(val x: Float, val y: Float, val z: Float)

internal data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun shade(amount: Float) = Rgb(
        (r * amount).coerceIn(0f, 1f),
        (g * amount).coerceIn(0f, 1f),
        (b * amount).coerceIn(0f, 1f),
    )
}

internal class Pose3D(
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
        val rx = lx * cr - ly * sr
        val ry = lx * sr + ly * cr
        val py = ry * cp - lz * sp
        val pz = ry * sp + lz * cp
        return Point3(
            originX + rx * cy + pz * sy,
            originY + py,
            originZ - rx * sy + pz * cy,
        )
    }
}

/**
 * Zero-allocation primitive FloatArray 3D mesh builder.
 * Stride = 9 floats (36 bytes): [x, y, z, nx, ny, nz, r, g, b].
 */
internal class TriangleMesh {
    private var data = FloatArray(320_000)
    private var size = 0
    private val rockRingX = FloatArray(35)
    private val rockRingY = FloatArray(35)
    private val rockRingZ = FloatArray(35)

    val vertexCount get() = size / 9

    fun reset() {
        size = 0
    }

    private fun ensureCapacity(extraFloats: Int) {
        if (size + extraFloats > data.size) {
            data = data.copyOf(max(data.size * 2, size + extraFloats))
        }
    }

    private fun putVertex(
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float,
    ) {
        val i = size
        val arr = data
        arr[i] = px; arr[i + 1] = py; arr[i + 2] = pz
        arr[i + 3] = nx; arr[i + 4] = ny; arr[i + 5] = nz
        arr[i + 6] = r; arr[i + 7] = g; arr[i + 8] = b
        size = i + 9
    }

    fun triangle(a: Point3, b: Point3, c: Point3, color: Rgb, emissive: Boolean = false) {
        ensureCapacity(27)
        if (emissive) {
            putVertex(a.x, a.y, a.z, 0f, 0f, 0f, color.r, color.g, color.b)
            putVertex(b.x, b.y, b.z, 0f, 0f, 0f, color.r, color.g, color.b)
            putVertex(c.x, c.y, c.z, 0f, 0f, 0f, color.r, color.g, color.b)
            return
        }
        val ux = b.x - a.x; val uy = b.y - a.y; val uz = b.z - a.z
        val vx = c.x - a.x; val vy = c.y - a.y; val vz = c.z - a.z
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len > 1e-5f) {
            val inv = 1f / len
            nx *= inv; ny *= inv; nz *= inv
        } else {
            nx = 0f; ny = 1f; nz = 0f
        }
        putVertex(a.x, a.y, a.z, nx, ny, nz, color.r, color.g, color.b)
        putVertex(b.x, b.y, b.z, nx, ny, nz, color.r, color.g, color.b)
        putVertex(c.x, c.y, c.z, nx, ny, nz, color.r, color.g, color.b)
    }

    fun quad(a: Point3, b: Point3, c: Point3, d: Point3, color: Rgb, emissive: Boolean = false) {
        triangle(a, b, c, color, emissive)
        triangle(a, c, d, color, emissive)
    }

    fun fillBuffer(existing: FloatBuffer?): FloatBuffer {
        val neededFloats = size
        val buffer = if (existing == null || existing.capacity() < neededFloats) {
            ByteBuffer.allocateDirect(max(neededFloats * 4, 320_000 * 4))
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        } else {
            existing.clear()
            existing
        }
        buffer.put(data, 0, neededFloats)
        buffer.position(0)
        return buffer
    }

    fun box(center: Point3, width: Float, height: Float, depth: Float, color: Rgb) {
        val x0 = center.x - width * 0.5f; val x1 = center.x + width * 0.5f
        val y0 = center.y - height * 0.5f; val y1 = center.y + height * 0.5f
        val z0 = center.z - depth * 0.5f; val z1 = center.z + depth * 0.5f
        quad(Point3(x0, y1, z0), Point3(x0, y1, z1), Point3(x1, y1, z1), Point3(x1, y1, z0), color.shade(1.08f))
        quad(Point3(x0, y0, z1), Point3(x0, y0, z0), Point3(x1, y0, z0), Point3(x1, y0, z1), color.shade(0.68f))
        quad(Point3(x0, y0, z1), Point3(x1, y0, z1), Point3(x1, y1, z1), Point3(x0, y1, z1), color)
        quad(Point3(x1, y0, z0), Point3(x0, y0, z0), Point3(x0, y1, z0), Point3(x1, y1, z0), color.shade(0.88f))
        quad(Point3(x0, y0, z0), Point3(x0, y0, z1), Point3(x0, y1, z1), Point3(x0, y1, z0), color.shade(0.92f))
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

    fun sphere(center: Point3, radius: Float, color: Rgb, slices: Int = 8, rings: Int = 6, emissive: Boolean = false) {
        ellipsoid(center, radius, radius, radius, color, slices, rings, emissive)
    }

    fun ellipsoid(
        center: Point3,
        radiusX: Float,
        radiusY: Float,
        radiusZ: Float,
        color: Rgb,
        slices: Int = 8,
        rings: Int = 6,
        emissive: Boolean = false,
    ) {
        ensureCapacity(rings * slices * 54)
        for (ring in 0 until rings) {
            val phi0 = (-PI * 0.5 + PI * ring / rings).toFloat()
            val phi1 = (-PI * 0.5 + PI * (ring + 1) / rings).toFloat()
            val cp0 = cos(phi0); val sp0 = sin(phi0)
            val cp1 = cos(phi1); val sp1 = sin(phi1)
            for (slice in 0 until slices) {
                val t0 = (2.0 * PI * slice / slices).toFloat()
                val t1 = (2.0 * PI * (slice + 1) / slices).toFloat()
                val ct0 = cos(t0); val st0 = sin(t0)
                val ct1 = cos(t1); val st1 = sin(t1)

                val ax = center.x + radiusX * cp0 * ct0; val ay = center.y + radiusY * sp0; val az = center.z + radiusZ * cp0 * st0
                val bx = center.x + radiusX * cp1 * ct0; val by = center.y + radiusY * sp1; val bz = center.z + radiusZ * cp1 * st0
                val cx = center.x + radiusX * cp1 * ct1; val cy = center.y + radiusY * sp1; val cz = center.z + radiusZ * cp1 * st1
                val dx = center.x + radiusX * cp0 * ct1; val dy = center.y + radiusY * sp0; val dz = center.z + radiusZ * cp0 * st1

                if (emissive) {
                    putVertex(ax, ay, az, 0f, 0f, 0f, color.r, color.g, color.b)
                    putVertex(cx, cy, cz, 0f, 0f, 0f, color.r, color.g, color.b)
                    putVertex(bx, by, bz, 0f, 0f, 0f, color.r, color.g, color.b)
                    putVertex(ax, ay, az, 0f, 0f, 0f, color.r, color.g, color.b)
                    putVertex(dx, dy, dz, 0f, 0f, 0f, color.r, color.g, color.b)
                    putVertex(cx, cy, cz, 0f, 0f, 0f, color.r, color.g, color.b)
                } else {
                    val nax = cp0 * ct0; val nay = sp0; val naz = cp0 * st0
                    val nbx = cp1 * ct0; val nby = sp1; val nbz = cp1 * st0
                    val ncx = cp1 * ct1; val ncy = sp1; val ncz = cp1 * st1
                    val ndx = cp0 * ct1; val ndy = sp0; val ndz = cp0 * st1
                    putVertex(ax, ay, az, nax, nay, naz, color.r, color.g, color.b)
                    putVertex(cx, cy, cz, ncx, ncy, ncz, color.r, color.g, color.b)
                    putVertex(bx, by, bz, nbx, nby, nbz, color.r, color.g, color.b)
                    putVertex(ax, ay, az, nax, nay, naz, color.r, color.g, color.b)
                    putVertex(dx, dy, dz, ndx, ndy, ndz, color.r, color.g, color.b)
                    putVertex(cx, cy, cz, ncx, ncy, ncz, color.r, color.g, color.b)
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
        slices: Int = 8,
        rings: Int = 6,
    ) {
        ensureCapacity(rings * slices * 54)
        val centerWorld = pose.p(lx, ly, lz)
        for (ring in 0 until rings) {
            val phi0 = (-PI * 0.5 + PI * ring / rings).toFloat()
            val phi1 = (-PI * 0.5 + PI * (ring + 1) / rings).toFloat()
            val cp0 = cos(phi0); val sp0 = sin(phi0)
            val cp1 = cos(phi1); val sp1 = sin(phi1)
            for (slice in 0 until slices) {
                val t0 = (2.0 * PI * slice / slices).toFloat()
                val t1 = (2.0 * PI * (slice + 1) / slices).toFloat()
                val ct0 = cos(t0); val st0 = sin(t0)
                val ct1 = cos(t1); val st1 = sin(t1)

                val a = pose.p(lx + radiusX * cp0 * ct0, ly + radiusY * sp0, lz + radiusZ * cp0 * st0)
                val b = pose.p(lx + radiusX * cp1 * ct0, ly + radiusY * sp1, lz + radiusZ * cp1 * st0)
                val c = pose.p(lx + radiusX * cp1 * ct1, ly + radiusY * sp1, lz + radiusZ * cp1 * st1)
                val d = pose.p(lx + radiusX * cp0 * ct1, ly + radiusY * sp0, lz + radiusZ * cp0 * st1)

                putVertex(a.x, a.y, a.z, a.x - centerWorld.x, a.y - centerWorld.y, a.z - centerWorld.z, color.r, color.g, color.b)
                putVertex(c.x, c.y, c.z, c.x - centerWorld.x, c.y - centerWorld.y, c.z - centerWorld.z, color.r, color.g, color.b)
                putVertex(b.x, b.y, b.z, b.x - centerWorld.x, b.y - centerWorld.y, b.z - centerWorld.z, color.r, color.g, color.b)
                putVertex(a.x, a.y, a.z, a.x - centerWorld.x, a.y - centerWorld.y, a.z - centerWorld.z, color.r, color.g, color.b)
                putVertex(d.x, d.y, d.z, d.x - centerWorld.x, d.y - centerWorld.y, d.z - centerWorld.z, color.r, color.g, color.b)
                putVertex(c.x, c.y, c.z, c.x - centerWorld.x, c.y - centerWorld.y, c.z - centerWorld.z, color.r, color.g, color.b)
            }
        }
    }

    fun facetedRock(center: Point3, radiusX: Float, radiusY: Float, radiusZ: Float, color: Rgb, seed: Int) {
        val sides = 7
        val ringHeights = floatArrayOf(-0.72f, -0.34f, 0.18f, 0.62f, 0.85f)
        val ringScales = floatArrayOf(0.85f, 1.04f, 1.0f, 0.78f, 0.42f)
        for (ring in 0 until 5) {
            val h = ringHeights[ring]
            val rScale = ringScales[ring]
            for (slice in 0 until sides) {
                val idx = ring * sides + slice
                val angle = 2f * PI.toFloat() * slice / sides + (ring % 2) * 0.19f
                val wobble = 1f + sin(seed * 1.73f + ring * 3.1f + slice * 5.7f) * 0.12f
                rockRingX[idx] = center.x + cos(angle) * radiusX * wobble * rScale
                rockRingY[idx] = center.y + h * radiusY
                rockRingZ[idx] = center.z + sin(angle) * radiusZ * wobble * rScale
            }
        }
        for (ring in 0 until 4) {
            for (slice in 0 until sides) {
                val next = (slice + 1) % sides
                val i0 = ring * sides + slice
                val i1 = ring * sides + next
                val i2 = (ring + 1) * sides + next
                val i3 = (ring + 1) * sides + slice
                val a = Point3(rockRingX[i0], rockRingY[i0], rockRingZ[i0])
                val b = Point3(rockRingX[i1], rockRingY[i1], rockRingZ[i1])
                val c = Point3(rockRingX[i2], rockRingY[i2], rockRingZ[i2])
                val d = Point3(rockRingX[i3], rockRingY[i3], rockRingZ[i3])
                val facet = 0.88f + ((seed * 13 + ring * 7 + slice * 11) % 7) * 0.035f
                triangle(a, c, b, color.shade(facet))
                triangle(a, d, c, color.shade(facet * 1.03f))
            }
        }
        val apex = Point3(center.x, center.y + radiusY * 0.96f, center.z)
        val topShade = color.shade(1.12f)
        for (slice in 0 until sides) {
            val i0 = 4 * sides + slice
            val i1 = 4 * sides + (slice + 1) % sides
            triangle(
                Point3(rockRingX[i0], rockRingY[i0], rockRingZ[i0]),
                apex,
                Point3(rockRingX[i1], rockRingY[i1], rockRingZ[i1]),
                topShade,
            )
        }
    }

    fun cone(x: Float, y: Float, z: Float, radius: Float, height: Float, color: Rgb, sides: Int = 6, emissive: Boolean = false) {
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
        sides: Int = 7,
        radiusTo: Float = radiusFrom,
        emissive: Boolean = false,
    ) {
        ensureCapacity(sides * 54)
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
        for (side in 0 until sides) {
            val a = (2.0 * PI * side / sides).toFloat()
            val b = (2.0 * PI * (side + 1) / sides).toFloat()
            val ca = cos(a); val sa = sin(a)
            val cb = cos(b); val sb = sin(b)
            val n0x = ax * ca + bx * sa; val n0y = ay * ca + by * sa; val n0z = az * ca + bz * sa
            val n1x = ax * cb + bx * sb; val n1y = ay * cb + by * sb; val n1z = az * cb + bz * sb

            val p0x = from.x + radiusFrom * n0x; val p0y = from.y + radiusFrom * n0y; val p0z = from.z + radiusFrom * n0z
            val p1x = from.x + radiusFrom * n1x; val p1y = from.y + radiusFrom * n1y; val p1z = from.z + radiusFrom * n1z
            val p2x = to.x + radiusTo * n1x; val p2y = to.y + radiusTo * n1y; val p2z = to.z + radiusTo * n1z
            val p3x = to.x + radiusTo * n0x; val p3y = to.y + radiusTo * n0y; val p3z = to.z + radiusTo * n0z

            if (emissive) {
                putVertex(p0x, p0y, p0z, 0f, 0f, 0f, color.r, color.g, color.b)
                putVertex(p2x, p2y, p2z, 0f, 0f, 0f, color.r, color.g, color.b)
                putVertex(p1x, p1y, p1z, 0f, 0f, 0f, color.r, color.g, color.b)
                putVertex(p0x, p0y, p0z, 0f, 0f, 0f, color.r, color.g, color.b)
                putVertex(p3x, p3y, p3z, 0f, 0f, 0f, color.r, color.g, color.b)
                putVertex(p2x, p2y, p2z, 0f, 0f, 0f, color.r, color.g, color.b)
            } else {
                putVertex(p0x, p0y, p0z, n0x, n0y, n0z, color.r, color.g, color.b)
                putVertex(p2x, p2y, p2z, n1x, n1y, n1z, color.r, color.g, color.b)
                putVertex(p1x, p1y, p1z, n1x, n1y, n1z, color.r, color.g, color.b)
                putVertex(p0x, p0y, p0z, n0x, n0y, n0z, color.r, color.g, color.b)
                putVertex(p3x, p3y, p3z, n0x, n0y, n0z, color.r, color.g, color.b)
                putVertex(p2x, p2y, p2z, n1x, n1y, n1z, color.r, color.g, color.b)
            }
        }
    }
}
