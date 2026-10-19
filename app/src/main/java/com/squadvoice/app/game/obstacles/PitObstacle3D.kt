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

    // Wide Stepped Mossy Stone Ruin Chasm
    mesh.plane(-4.4f, 4.4f, near, far, -0.72f, Rgb(0.09f, 0.11f, 0.09f))
    mesh.box(Point3(-4.35f, -0.32f, midZ), 0.44f, 0.76f, span, Rgb(0.38f, 0.40f, 0.37f))
    mesh.box(Point3(4.35f, -0.32f, midZ), 0.44f, 0.76f, span, Rgb(0.38f, 0.40f, 0.37f))
    mesh.box(Point3(0f, -0.32f, near), 8.8f, 0.76f, 0.34f, Rgb(0.44f, 0.46f, 0.43f))
    mesh.box(Point3(0f, -0.32f, far), 8.8f, 0.76f, 0.34f, Rgb(0.44f, 0.46f, 0.43f))

    // Mossy rim ledges on near & far edges
    mesh.box(Point3(0f, 0.06f, near - 0.06f), 8.8f, 0.10f, 0.28f, Rgb(0.38f, 0.78f, 0.22f))
    mesh.box(Point3(0f, 0.06f, far + 0.06f), 8.8f, 0.10f, 0.28f, Rgb(0.38f, 0.78f, 0.22f))

    for (row in 0..2) {
        val rowZ = near + span * ((row + 0.5f) / 3f)
        repeat(8) { i ->
            val x = -3.35f + i * 0.94f + (if (row % 2 == 0) 0f else 0.22f)
            if (x < 3.65f) {
                val height = 0.86f + ((i * 7 + row * 3) % 3) * 0.14f
                mesh.cone(x, -0.68f, rowZ, 0.32f, height * 0.72f, Rgb(0.38f, 0.42f, 0.40f), 6)
                mesh.cone(x, -0.68f + height * 0.62f, rowZ, 0.16f, height * 0.44f, Rgb(0.82f, 0.86f, 0.84f), 6)
            }
        }
    }

    for (side in SIDES) {
        val postX = side * 4.25f
        mesh.box(Point3(postX, 0.72f, near - 0.25f), 0.26f, 1.44f, 0.26f, Rgb(0.44f, 0.28f, 0.15f))
        mesh.sphere(Point3(postX, 1.54f, near - 0.25f), 0.17f, Rgb(1f, 0.76f + 0.2f * pitPulse, 0.22f), 6, 5, emissive = true)
    }
}
