package com.squadvoice.app.game

import kotlin.math.min
import kotlin.math.sin

internal fun buildBoulderHazard(
    mesh: TriangleMesh,
    z1: Float,
    z2: Float,
    time: Float,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val near = min(z1, z2)
    val midZ = (z1 + z2) * 0.5f
    val pulse = 0.5f + 0.5f * sin(time * 3f)
    val stoneTint = Rgb(0.88f, 0.86f, 0.80f)
    val basaltTint = Rgb(0.78f, 0.80f, 0.84f)
    val mossTint = Rgb(0.82f, 0.98f, 0.60f)

    // 1. Center & Flanking Mossy Ancient Ruin Boulders (MatId.STONE & MatId.BASALT)
    mesh.withMaterial(MatId.STONE, tileScale = 0.42f) {
        mesh.facetedRock(Point3(0f, 0.98f, midZ), 1.56f, 1.28f, 1.35f, stoneTint, 42)
        mesh.facetedRock(Point3(1.88f, 0.72f, midZ - 0.14f), 1.08f, 0.86f, 0.94f, stoneTint.shade(0.96f), 77)
    }
    mesh.withMaterial(MatId.BASALT, tileScale = 0.45f) {
        mesh.facetedRock(Point3(-1.85f, 0.68f, midZ + 0.18f), 1.05f, 0.84f, 0.92f, basaltTint, 17)
    }

    // 2. Lush Overhanging Emerald Moss Caps & Hanging Ivy Tendrils (MatId.GRASS)
    mesh.withMaterial(MatId.GRASS, tileScale = 0.48f) {
        mesh.ellipsoid(Point3(0f, 1.72f, midZ), 0.95f, 0.24f, 0.85f, mossTint, 7, 4)
        mesh.ellipsoid(Point3(-1.85f, 1.18f, midZ + 0.18f), 0.65f, 0.18f, 0.58f, mossTint.shade(1.04f), 6, 4)
        mesh.ellipsoid(Point3(1.88f, 1.22f, midZ - 0.14f), 0.68f, 0.18f, 0.60f, mossTint, 6, 4)
        if (qualityTier != RopeboundQualityTier.LOW) {
            for (side in SIDES) {
                mesh.rod(
                    Point3(side * 0.65f, 1.62f, midZ - 0.82f),
                    Point3(side * 0.72f, 0.84f, midZ - 0.96f),
                    0.09f,
                    mossTint,
                    5,
                    0.04f,
                )
            }
        }
    }

    // 3. Glowing Ancient Amber Rune Crystals & Take-Off Jump Strip Before the Boulder
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        val amberGlow = Rgb(0.98f, 0.78f + 0.16f * pulse, 0.22f)
        mesh.cone(-0.55f, 1.15f, midZ - 0.92f, 0.14f, 0.46f, amberGlow, 5, emissive = true)
        mesh.cone(0.62f, 0.95f, midZ - 0.94f, 0.12f, 0.38f, amberGlow, 5, emissive = true)
        // High-contrast golden jump-timing line at the approach edge
        mesh.box(Point3(0f, 0.048f, near - 0.35f), 6.2f, 0.024f, 0.12f, amberGlow, emissive = true)

        if (qualityTier != RopeboundQualityTier.LOW) {
            for (mote in 0..3) {
                val cycle = (time * 0.65f + mote * 0.25f) % 1f
                val mx = -1.2f + mote * 0.8f + sin(time * 2.4f + mote) * 0.18f
                val my = 0.45f + cycle * 1.45f
                mesh.sphere(Point3(mx, my, midZ - 0.65f), 0.055f * (1f - cycle * 0.4f), amberGlow, 5, 4, emissive = true)
            }
        }
    }
}
