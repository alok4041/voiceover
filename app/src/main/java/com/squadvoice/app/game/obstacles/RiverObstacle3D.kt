package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildRiver(
    mesh: TriangleMesh,
    z1: Float,
    z2: Float,
    bridge: Boolean,
    time: Float,
    players: List<RopeboundPlayer>,
    cameraProgress: Float,
) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val riverLength = (far - near).coerceAtLeast(0.5f)
    val nx = 28
    val nz = 8

    fun waterLevel(x: Float, z: Float): Float {
        val wave1 = sin(time * 2.4f + x * 0.36f + z * 1.2f) * 0.055f
        val wave2 = sin(time * 3.2f - x * 0.46f + z * 0.82f) * 0.032f
        return (-0.64f + wave1 + wave2).coerceIn(-0.75f, -0.52f)
    }

    mesh.plane(-23f, 23f, near, far, -0.98f, Rgb(0.08f, 0.20f, 0.26f))

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

    for (foam in 0..10) {
        val laneX = -19f + (foam % 5) * 9.5f + sin(time * 1.3f + foam * 1.7f) * 0.65f
        val travel = (time * 0.30f + foam * 0.143f) % 1f
        val z = near + (riverLength - 0.25f).coerceAtLeast(0.1f) * travel
        val startX = laneX - 0.82f; val endX = laneX + 0.82f
        val startY = waterLevel(startX, z) + 0.008f
        val endY = waterLevel(endX, z + 0.18f) + 0.008f
        val sprayColor = if (foam % 2 == 0) Rgb(0.90f, 0.98f, 1f) else Rgb(0.72f, 0.94f, 0.98f)
        mesh.rod(Point3(startX, startY, z), Point3(endX, endY, z + 0.18f), 0.018f, sprayColor, 5, emissive = true)
    }

    for ((bankIndex, bankZ) in listOf(near, far).withIndex()) {
        val landSign = if (bankIndex == 0) -1f else 1f
        val shoreColor = Rgb(0.66f, 0.58f, 0.42f)
        val shoreTopZ = bankZ + landSign * 0.85f
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

        // Deep stone pier wall anchored all the way to the riverbed (-0.98f) so road ends never float
        mesh.box(Point3(0f, -0.48f, bankZ + landSign * 0.38f), 8.3f, 1.02f, 0.82f, Rgb(0.46f, 0.48f, 0.50f))
        mesh.box(Point3(0f, 0.04f, bankZ + landSign * 0.42f), 6.4f, 0.09f, 0.88f, Rgb(0.58f, 0.42f, 0.26f))

        for (rock in 0..14) {
            val rawX = -21f + rock * 2.85f
            if (abs(rawX) < 4.55f) continue
            val rz = bankZ + landSign * (0.95f + sin(rock * 1.8f + bankIndex) * 0.22f)
            val scale = 0.58f + (rock % 4) * 0.14f
            val rockColor = if (rock % 2 == 0) Rgb(0.52f, 0.55f, 0.53f) else Rgb(0.60f, 0.58f, 0.52f)
            mesh.facetedRock(Point3(rawX, -0.32f, rz), scale, 0.38f + (rock % 3) * 0.08f, 0.48f + scale * 0.15f, rockColor, rock + bankIndex * 37)
        }
    }

    if (bridge) {
        val bridgeWidth = 5.8f
        val planks = 12

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
            val z = near + riverLength * t
            val arch = sin(t * PI).toFloat() * 0.18f
            val plankProgress = ((z / COURSE_SCALE) + cameraProgress * 100f) / 100f
            val stepFlex = footstepsAt(plankProgress, 2.6f)
            val wobble = sin(time * 2.2f + index * 0.68f) * 0.018f + stepFlex
            val roll = sin(time * 2.7f + index * 0.54f) * 0.020f + stepFlex * 0.55f
            val wood = if (index % 2 == 0) Rgb(0.66f, 0.42f, 0.23f) else Rgb(0.76f, 0.50f, 0.28f)
            val plankY = 0.08f + arch + wobble

            mesh.bridgePlank(Point3(0f, plankY, z), bridgeWidth, 0.18f, riverLength / planks * 0.88f, roll, wood)

            for (side in listOf(-1f, 1f)) {
                val railY = 0.92f + arch * 0.6f + wobble * 0.75f
                mesh.rod(Point3(side * 2.76f, plankY + 0.08f, z), Point3(side * 2.76f, railY, z), 0.034f, Rgb(0.84f, 0.70f, 0.46f), 5)
            }
        }

        for (endZ in listOf(near, far)) {
            for (side in listOf(-1f, 1f)) {
                val postX = side * 2.82f
                mesh.box(Point3(postX, 0.42f, endZ), 0.36f, 2.48f, 0.36f, Rgb(0.44f, 0.25f, 0.13f))
                mesh.box(Point3(postX, 1.70f, endZ), 0.44f, 0.16f, 0.44f, Rgb(0.62f, 0.38f, 0.20f))
            }
        }

        for (side in listOf(-1f, 1f)) {
            var previous = Point3(side * 2.82f, 1.52f, near)
            for (step in 1..18) {
                val t = step / 18f
                val sag = -sin(t * PI).toFloat() * 0.52f
                val current = Point3(side * 2.82f, 1.52f + sag, near + riverLength * t)
                mesh.rod(previous, current, 0.055f, Rgb(0.56f, 0.36f, 0.20f), 6)
                previous = current
            }
        }
    }
}
