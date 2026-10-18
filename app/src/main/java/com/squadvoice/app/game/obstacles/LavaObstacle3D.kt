package com.squadvoice.app.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildLava(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val canyonLength = (far - near).coerceAtLeast(0.5f)
    val pulse = 0.5f + 0.5f * sin(time * 3.6f)

    mesh.plane(-4.6f, 4.6f, near, far, -0.58f, Rgb(0.12f, 0.07f, 0.06f))
    for (side in listOf(-1f, 1f)) {
        val wallX = side * 3.92f
        mesh.box(Point3(wallX, -0.22f, (near + far) * 0.5f), 0.86f, 0.72f, canyonLength, Rgb(0.22f, 0.14f, 0.12f))
    }
    mesh.box(Point3(0f, -0.28f, near), 8.2f, 0.62f, 0.36f, Rgb(0.26f, 0.16f, 0.14f))
    mesh.box(Point3(0f, -0.28f, far), 8.2f, 0.62f, 0.36f, Rgb(0.26f, 0.16f, 0.14f))

    val nx = 20
    val nz = 6
    fun lavaLevel(x: Float, z: Float): Float {
        val boil1 = sin(time * 3.4f + x * 0.75f + z * 1.8f) * 0.055f
        val boil2 = sin(time * 4.6f - x * 0.98f + z * 1.2f) * 0.030f
        return -0.24f + boil1 + boil2
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

    for (plate in 0..5) {
        val px = -2.4f + (plate % 3) * 2.2f + sin(time * 0.8f + plate) * 0.18f
        val pz = near + canyonLength * ((plate * 0.31f + time * 0.08f) % 1f)
        val py = lavaLevel(px, pz) - 0.02f
        val plateSize = 0.68f + (plate % 3) * 0.16f
        mesh.facetedRock(Point3(px, py, pz), plateSize, 0.16f, plateSize * 0.85f, Rgb(0.18f, 0.12f, 0.11f), plate * 19 + 7)
    }

    for (flame in 0..5) {
        val fx = -2.5f + flame * 0.95f + sin(time * 2f + flame) * 0.12f
        val phase = time * 4.4f + flame * 1.8f
        val burstHeight = 0.34f + (0.45f + 0.5f * sin(phase)) * 0.95f
        val fz = near + canyonLength * ((flame % 4) / 4f + 0.12f)
        val baseY = lavaLevel(fx, fz) - 0.05f
        mesh.cone(fx, baseY, fz, 0.34f, burstHeight, Rgb(1f, 0.28f, 0.04f), 6, emissive = true)
        mesh.cone(fx, baseY + 0.02f, fz, 0.20f, burstHeight * 0.70f, Rgb(1f, 0.78f, 0.16f), 5, emissive = true)
    }
}
