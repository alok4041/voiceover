package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

internal val CHECKPOINT_METERS = floatArrayOf(8f, 19f, 30f, 40f, 50f, 64f, 80f)

private val SKY_BANDS = arrayOf(
    Rgb(0.58f, 0.84f, 0.96f),
    Rgb(0.46f, 0.76f, 0.93f),
    Rgb(0.34f, 0.67f, 0.89f),
    Rgb(0.25f, 0.57f, 0.84f),
    Rgb(0.18f, 0.47f, 0.78f),
    Rgb(0.12f, 0.38f, 0.70f),
)

internal fun drawMountainVista(mesh: TriangleMesh, time: Float) {
    for (band in SKY_BANDS.indices) {
        val y0 = -36f + band * 24f
        val y1 = y0 + 24f
        val skyZ = 152f
        mesh.quad(
            Point3(-210f, y0, skyZ),
            Point3(210f, y0, skyZ),
            Point3(210f, y1, skyZ),
            Point3(-210f, y1, skyZ),
            SKY_BANDS[band],
            emissive = true,
        )
    }

    mesh.sphere(Point3(28f, 24f, 122f), 4.2f, Rgb(1f, 0.96f, 0.76f), 10, 7, emissive = true)
    mesh.sphere(Point3(28f, 24f, 122f), 5.6f, Rgb(1f, 0.88f, 0.52f), 9, 6, emissive = true)

    for (cloud in 0 until 8) {
        val x = -48f + (cloud % 4) * 26f + sin(cloud * 1.9f) * 4f
        val z = 28f + (cloud * 13.7f + time * 0.42f) % 84f
        val y = 13.0f + (cloud % 3) * 2.6f
        val puff = Rgb(0.95f, 0.98f, 1f)
        mesh.ellipsoid(Point3(x, y, z), 3.6f, 1.35f, 2.3f, puff, 8, 6)
        mesh.ellipsoid(Point3(x - 2.2f, y - 0.15f, z + 0.35f), 2.4f, 1.02f, 1.7f, puff.shade(0.97f), 7, 5)
        mesh.ellipsoid(Point3(x + 2.1f, y - 0.10f, z - 0.30f), 2.5f, 1.10f, 1.8f, puff.shade(0.95f), 7, 5)
    }

    for (i in 0 until 13) {
        val centerX = -108f + i * 18f + (if (i % 2 == 0) 2.5f else -2.0f)
        val centerZ = 98f + (i % 3) * 6.5f
        val radiusX = 13.5f + (i % 3) * 2.2f
        val radiusZ = 11.5f + (i % 2) * 2.5f
        val peakY = 16.5f + (i % 4) * 3.8f
        val rockColor = if (i % 2 == 0) Rgb(0.38f, 0.49f, 0.60f) else Rgb(0.32f, 0.43f, 0.54f)
        draw3DMountainPeak(mesh, centerX, centerZ, radiusX, radiusZ, peakY, rockColor, hasSnowCap = true, seed = i * 17 + 3)
    }

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
    val snowRatio = 0.36f
    for (s in 0 until sides) {
        val next = (s + 1) % sides
        val a0 = 2f * PI.toFloat() * s / sides + (seed % 5) * 0.12f
        val a1 = 2f * PI.toFloat() * next / sides + (seed % 5) * 0.12f
        val j0 = 0.88f + ((seed + s * 7) % 5) * 0.06f
        val j1 = 0.88f + ((seed + next * 7) % 5) * 0.06f
        val bp0 = Point3(cx + cos(a0) * rx * j0, baseY, cz + sin(a0) * rz * j0)
        val bp1 = Point3(cx + cos(a1) * rx * j1, baseY, cz + sin(a1) * rz * j1)
        val shade = 0.88f + (s % 3) * 0.08f
        if (hasSnowCap) {
            val sp0 = Point3(
                apex.x + (bp0.x - apex.x) * snowRatio,
                apex.y + (bp0.y - apex.y) * snowRatio - ((seed + bp0.x.toInt()) % 3) * 0.28f,
                apex.z + (bp0.z - apex.z) * snowRatio,
            )
            val sp1 = Point3(
                apex.x + (bp1.x - apex.x) * snowRatio,
                apex.y + (bp1.y - apex.y) * snowRatio - ((seed + bp1.x.toInt()) % 3) * 0.28f,
                apex.z + (bp1.z - apex.z) * snowRatio,
            )
            mesh.quad(bp0, bp1, sp1, sp0, baseColor.shade(shade))
            val snowColor = if (s % 2 == 0) Rgb(0.95f, 0.98f, 1f) else Rgb(0.86f, 0.92f, 0.98f)
            mesh.triangle(sp0, sp1, apex, snowColor)
        } else {
            mesh.triangle(bp0, bp1, apex, baseColor.shade(shade))
        }
    }
}

internal fun drawValleySideHills(
    mesh: TriangleMesh,
    cameraProgress: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
) {
    for (hill in 0 until 10) {
        val side = if (hill % 2 == 0) -1f else 1f
        val z = -12f + (hill * 9.5f + cameraProgress * COURSE_SCALE * 0.25f) % 92f
        if (overlapsRiver(z, 5f, riverCount, riverStarts, riverEnds)) continue
        val x = side * (17.5f + (hill % 3) * 4.5f)
        val rx = 6.5f + (hill % 3) * 1.8f
        val ry = 2.6f + (hill % 4) * 0.95f
        val rz = 6.2f + (hill % 2) * 1.6f
        val hillColor = if (hill % 2 == 0) Rgb(0.28f, 0.56f, 0.25f) else Rgb(0.34f, 0.60f, 0.27f)
        mesh.ellipsoid(Point3(x, -0.35f, z), rx, ry, rz, hillColor, 8, 5)
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

internal fun overlapsRiver(
    z: Float,
    margin: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
): Boolean {
    for (i in 0 until riverCount) {
        if (z in (riverStarts[i] - margin)..(riverEnds[i] + margin)) return true
    }
    return false
}

internal fun drawStylizedTree(mesh: TriangleMesh, x: Float, z: Float, species: Int, scale: Float, time: Float) {
    val sway = sin(time * 1.6f + x * 0.6f + z * 0.4f) * 0.08f
    mesh.ellipsoid(Point3(x, -0.27f, z), 0.95f * scale, 0.02f, 0.95f * scale, Rgb(0.16f, 0.34f, 0.14f), 7, 4)
    when (species) {
        0 -> {
            mesh.rod(Point3(x, -0.28f, z), Point3(x + sway * 0.4f, 1.45f * scale, z), 0.28f * scale, Rgb(0.42f, 0.25f, 0.14f), 7, 0.18f * scale)
            val foliageBaseY = 1.40f * scale
            val leafDark = Rgb(0.18f, 0.48f, 0.22f)
            val leafMid = Rgb(0.28f, 0.66f, 0.26f)
            val leafBright = Rgb(0.46f, 0.78f, 0.30f)
            mesh.ellipsoid(Point3(x + sway, foliageBaseY + 0.58f * scale, z), 1.02f * scale, 0.88f * scale, 1.02f * scale, leafMid, 8, 5)
            mesh.ellipsoid(Point3(x + sway - 0.48f * scale, foliageBaseY + 0.35f * scale, z + 0.26f * scale), 0.78f * scale, 0.70f * scale, 0.78f * scale, leafDark, 7, 5)
            mesh.ellipsoid(Point3(x + sway + 0.48f * scale, foliageBaseY + 0.44f * scale, z - 0.24f * scale), 0.82f * scale, 0.74f * scale, 0.82f * scale, leafMid.shade(1.06f), 7, 5)
            mesh.ellipsoid(Point3(x + sway * 1.2f, foliageBaseY + 1.18f * scale, z), 0.74f * scale, 0.66f * scale, 0.74f * scale, leafBright, 7, 5)
        }
        1 -> {
            mesh.rod(Point3(x, -0.28f, z), Point3(x + sway * 0.3f, 2.85f * scale, z), 0.22f * scale, Rgb(0.36f, 0.20f, 0.11f), 6, 0.10f * scale)
            val pineBase = Rgb(0.14f, 0.42f, 0.30f)
            val pineMid = Rgb(0.18f, 0.50f, 0.34f)
            val pineTop = Rgb(0.24f, 0.60f, 0.38f)
            mesh.cone(x + sway * 0.35f, 0.52f * scale, z, 1.22f * scale, 1.35f * scale, pineBase, 7)
            mesh.cone(x + sway * 0.65f, 1.28f * scale, z, 0.98f * scale, 1.25f * scale, pineMid, 7)
            mesh.cone(x + sway * 0.95f, 2.02f * scale, z, 0.74f * scale, 1.15f * scale, pineTop, 7)
            mesh.cone(x + sway * 1.15f, 2.68f * scale, z, 0.44f * scale, 0.72f * scale, Rgb(0.90f, 0.96f, 0.98f), 6)
        }
        else -> {
            mesh.rod(Point3(x, -0.28f, z), Point3(x + sway * 0.4f, 1.75f * scale, z), 0.20f * scale, Rgb(0.90f, 0.87f, 0.80f), 6, 0.12f * scale)
            val amberGold = Rgb(0.95f, 0.64f, 0.18f)
            val apricot = Rgb(0.90f, 0.44f, 0.16f)
            val crownGold = Rgb(0.98f, 0.78f, 0.26f)
            val baseY = 1.48f * scale
            mesh.ellipsoid(Point3(x + sway, baseY + 0.55f * scale, z), 0.95f * scale, 0.84f * scale, 0.95f * scale, amberGold, 8, 5)
            mesh.ellipsoid(Point3(x + sway - 0.38f * scale, baseY + 0.32f * scale, z + 0.22f * scale), 0.72f * scale, 0.64f * scale, 0.72f * scale, apricot, 7, 5)
            mesh.ellipsoid(Point3(x + sway + 0.40f * scale, baseY + 0.42f * scale, z - 0.22f * scale), 0.75f * scale, 0.68f * scale, 0.75f * scale, crownGold, 7, 5)
        }
    }
}

internal fun drawMeadows(
    mesh: TriangleMesh,
    cameraProgress: Float,
    time: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
) {
    for (tuft in 0 until 78) {
        val side = if (tuft % 2 == 0) -1f else 1f
        val x = side * (4.45f + (tuft % 8) * 0.55f)
        val z = -10f + (tuft * 3.4f + cameraProgress * COURSE_SCALE * 0.75f) % 64f
        if (overlapsRiver(z, 0.8f, riverCount, riverStarts, riverEnds)) continue
        val windWave = sin(time * 2.5f + x * 0.4f + z * 0.6f) * 0.14f
        val green = when (tuft % 4) {
            0 -> Rgb(0.58f, 0.80f, 0.26f)
            1 -> Rgb(0.32f, 0.68f, 0.24f)
            2 -> Rgb(0.42f, 0.76f, 0.28f)
            else -> Rgb(0.68f, 0.76f, 0.30f)
        }
        for (blade in 0..1) {
            val bx = x + (blade - 0.5f) * 0.12f
            val tip = Point3(bx + windWave, 0.22f + (tuft % 5) * 0.04f, z)
            val base0 = Point3(bx - 0.06f, -0.26f, z - 0.03f)
            val base1 = Point3(bx + 0.06f, -0.26f, z + 0.03f)
            mesh.triangle(base0, base1, tip, green.shade(if (blade % 2 == 0) 1.08f else 0.90f))
        }

        if (tuft % 9 == 0) {
            val flowerColor = when ((tuft / 9) % 3) {
                0 -> Rgb(0.98f, 0.84f, 0.20f)
                1 -> Rgb(0.96f, 0.32f, 0.36f)
                else -> Rgb(0.44f, 0.78f, 0.98f)
            }
            mesh.sphere(Point3(x + 0.14f + windWave * 0.5f, 0.18f, z), 0.095f, flowerColor, 6, 4)
        }
    }
}

internal fun drawTrail(mesh: TriangleMesh, z0: Float, z1: Float) {
    val length = (z1 - z0).coerceAtLeast(0.1f)
    val midZ = (z0 + z1) * 0.5f

    mesh.box(Point3(0f, -0.48f, midZ), 8.5f, 0.72f, length, Rgb(0.38f, 0.36f, 0.34f))
    mesh.box(Point3(0f, -0.15f, midZ), 8.2f, 0.26f, length, Rgb(0.46f, 0.35f, 0.22f))
    mesh.box(Point3(0f, -0.03f, midZ), 7.2f, 0.06f, length, Rgb(0.64f, 0.50f, 0.32f))

    val stoneStart = (ceil(z0 / 2.2f) * 2.2f).coerceAtLeast(z0)
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
        z += 2.2f
        stoneIndex++
    }

    for (side in SIDES) {
        val borderX = side * 3.72f
        mesh.box(Point3(borderX, 0.06f, midZ), 0.22f, 0.16f, length, Rgb(0.40f, 0.27f, 0.16f))
    }
}

internal fun drawCheckpointGate(mesh: TriangleMesh, z: Float, checkpoint: Float, reached: Boolean, time: Float) {
    val flagColor = if (reached) Rgb(0.22f, 0.94f, 0.68f) else Rgb(1f, 0.76f, 0.24f)
    for (side in SIDES) {
        val px = side * 3.75f
        mesh.box(Point3(px, 0.18f, z), 0.38f, 0.36f, 0.38f, Rgb(0.46f, 0.48f, 0.52f))
        mesh.rod(Point3(px, 0.32f, z), Point3(px, 2.55f, z), 0.075f, Rgb(0.92f, 0.86f, 0.72f), 6, 0.055f)
        mesh.sphere(Point3(px, 2.62f, z), 0.12f, flagColor, 7, 5, emissive = reached)
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

internal fun drawFinishArch(mesh: TriangleMesh, finishZ: Float, time: Float) {
    val pulse = 0.5f + 0.5f * sin(time * 4f)
    for (side in SIDES) {
        val px = side * 3.85f
        mesh.box(Point3(px, 0.35f, finishZ), 0.62f, 0.70f, 0.62f, Rgb(0.38f, 0.42f, 0.48f))
        mesh.rod(Point3(px, 0.65f, finishZ), Point3(px, 3.65f, finishZ), 0.18f, Rgb(0.22f, 0.28f, 0.34f), 7, 0.14f)
        mesh.sphere(Point3(px, 3.82f, finishZ), 0.22f, Rgb(1f, 0.78f + 0.18f * pulse, 0.22f), 7, 5, emissive = true)
    }
    mesh.box(Point3(0f, 3.55f, finishZ), 8.2f, 0.58f, 0.32f, Rgb(0.16f, 0.82f, 0.72f))
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
