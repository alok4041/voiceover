package com.squadvoice.app.game

import kotlin.math.sin

internal fun buildBoulderHazard(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
    val midZ = (z1 + z2) * 0.5f
    val pulse = 0.5f + 0.5f * sin(time * 3f)
    val stone = Rgb(0.46f, 0.49f, 0.47f)
    val moss = Rgb(0.36f, 0.74f, 0.22f)

    // Center & Flanking Mossy Ancient Ruin Boulders across the wide path
    mesh.facetedRock(Point3(0f, 0.98f, midZ), 1.56f, 1.28f, 1.35f, stone, 42)
    mesh.facetedRock(Point3(-1.85f, 0.68f, midZ + 0.18f), 1.05f, 0.84f, 0.92f, stone.shade(0.92f), 17)
    mesh.facetedRock(Point3(1.88f, 0.72f, midZ - 0.14f), 1.08f, 0.86f, 0.94f, stone.shade(0.96f), 77)

    // Lush emerald moss caps & glowing ancient amber runes
    mesh.ellipsoid(Point3(0f, 1.72f, midZ), 0.95f, 0.24f, 0.85f, moss, 7, 4)
    mesh.ellipsoid(Point3(-1.85f, 1.18f, midZ + 0.18f), 0.65f, 0.18f, 0.58f, moss.shade(1.06f), 6, 4)
    mesh.ellipsoid(Point3(1.88f, 1.22f, midZ - 0.14f), 0.68f, 0.18f, 0.60f, moss, 6, 4)
    mesh.cone(-0.55f, 1.15f, midZ - 0.92f, 0.14f, 0.46f, Rgb(0.98f, 0.78f + 0.14f * pulse, 0.22f), 5, emissive = true)
    mesh.cone(0.62f, 0.95f, midZ - 0.94f, 0.12f, 0.38f, Rgb(0.98f, 0.78f + 0.14f * pulse, 0.22f), 5, emissive = true)
}
