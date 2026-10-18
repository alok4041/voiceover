package com.squadvoice.app.game

import kotlin.math.sin

internal fun buildBoulderHazard(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
    val midZ = (z1 + z2) * 0.5f
    val pulse = 0.5f + 0.5f * sin(time * 3f)
    mesh.facetedRock(Point3(0f, 0.98f, midZ), 1.48f, 1.24f, 1.32f, Rgb(0.48f, 0.51f, 0.50f), 42)
    mesh.facetedRock(Point3(-1.35f, 0.62f, midZ + 0.18f), 0.92f, 0.78f, 0.86f, Rgb(0.44f, 0.47f, 0.46f), 17)
    mesh.facetedRock(Point3(1.38f, 0.66f, midZ - 0.14f), 0.96f, 0.82f, 0.88f, Rgb(0.46f, 0.49f, 0.48f), 77)
    mesh.facetedRock(Point3(-0.38f, 1.68f, midZ - 0.18f), 0.62f, 0.36f, 0.54f, Rgb(0.36f, 0.64f, 0.28f), 19)
    mesh.cone(-0.55f, 1.15f, midZ - 0.85f, 0.14f, 0.46f, Rgb(0.32f, 0.88f + 0.1f * pulse, 0.96f), 5, emissive = true)
    mesh.cone(0.62f, 0.95f, midZ - 0.88f, 0.12f, 0.38f, Rgb(0.32f, 0.88f + 0.1f * pulse, 0.96f), 5, emissive = true)
}
