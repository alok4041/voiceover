package com.squadvoice.app.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildPitHazard(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val midZ = (near + far) * 0.5f
    val span = (far - near).coerceAtLeast(0.5f)
    val pitPulse = 0.5f + 0.5f * sin(time * 3.6f)

    mesh.plane(-3.85f, 3.85f, near, far, -0.68f, Rgb(0.08f, 0.09f, 0.11f))
    mesh.box(Point3(-3.75f, -0.32f, midZ), 0.36f, 0.72f, span, Rgb(0.32f, 0.28f, 0.24f))
    mesh.box(Point3(3.75f, -0.32f, midZ), 0.36f, 0.72f, span, Rgb(0.32f, 0.28f, 0.24f))
    mesh.box(Point3(0f, -0.32f, near), 7.6f, 0.72f, 0.28f, Rgb(0.38f, 0.31f, 0.24f))
    mesh.box(Point3(0f, -0.32f, far), 7.6f, 0.72f, 0.28f, Rgb(0.38f, 0.31f, 0.24f))

    for (crack in 0..4) {
        val x = -2.6f + crack * 1.3f
        val crackColor = Rgb(1f, 0.42f + 0.28f * pitPulse, 0.10f)
        mesh.rod(Point3(x - 0.28f, -0.64f, near + 0.25f), Point3(x + 0.28f, -0.64f, far - 0.25f), 0.045f, crackColor, 5, emissive = true)
    }

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

    for (side in listOf(-1f, 1f)) {
        val postX = side * 3.65f
        mesh.box(Point3(postX, 0.72f, near - 0.25f), 0.22f, 1.44f, 0.22f, Rgb(0.42f, 0.26f, 0.15f))
        mesh.sphere(Point3(postX, 1.54f, near - 0.25f), 0.16f, Rgb(1f, 0.76f + 0.2f * pitPulse, 0.22f), 7, 5, emissive = true)
    }
}
