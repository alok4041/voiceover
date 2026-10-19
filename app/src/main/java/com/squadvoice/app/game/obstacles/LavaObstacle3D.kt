package com.squadvoice.app.game

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildLava(
    mesh: TriangleMesh,
    z1: Float,
    z2: Float,
    time: Float,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val canyonLength = (far - near).coerceAtLeast(0.5f)
    val pulse = 0.5f + 0.5f * sin(time * 3.6f)

    // 1. Charred Volcanic Basalt Fissure Walls & Rim Ledges (MatId.BASALT & MatId.STONE)
    mesh.withMaterial(MatId.BASALT, tileScale = 0.44f) {
        mesh.plane(-4.6f, 4.6f, near, far, -0.58f, Rgb(0.28f, 0.22f, 0.20f))
        for (side in SIDES) {
            val wallX = side * 3.92f
            mesh.box(Point3(wallX, -0.22f, (near + far) * 0.5f), 0.86f, 0.72f, canyonLength, Rgb(0.56f, 0.48f, 0.46f))
        }
        mesh.box(Point3(0f, -0.28f, near), 8.2f, 0.62f, 0.36f, Rgb(0.62f, 0.52f, 0.48f))
        mesh.box(Point3(0f, -0.28f, far), 8.2f, 0.62f, 0.36f, Rgb(0.62f, 0.52f, 0.48f))
    }

    // 2. High-Contrast Glowing Magma Jump Lip Along Near & Far Edges
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        val rimGlow = Rgb(1.0f, 0.64f + 0.22f * pulse, 0.18f)
        mesh.box(Point3(0f, 0.055f, near - 0.12f), 6.8f, 0.028f, 0.12f, rimGlow, emissive = true)
        mesh.box(Point3(0f, 0.055f, far + 0.12f), 6.8f, 0.028f, 0.12f, rimGlow, emissive = true)
    }

    // 3. Boiling Animated Magma Wave Grid (Scaled by Quality Tier)
    val nx = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 18
        RopeboundQualityTier.MEDIUM -> 14
        RopeboundQualityTier.LOW -> 9
    }
    val nz = if (qualityTier == RopeboundQualityTier.LOW) 4 else 6
    fun lavaLevel(x: Float, z: Float): Float {
        val boil1 = sin(time * 3.4f + x * 0.75f + z * 1.8f) * 0.055f
        val boil2 = sin(time * 4.6f - x * 0.98f + z * 1.2f) * 0.030f
        return -0.24f + boil1 + boil2
    }

    mesh.withMaterial(MatId.VERTEX_COLOR) {
        for (ix in 0 until nx) {
            for (iz in 0 until nz) {
                val x0 = -3.45f + 6.9f * ix / nx
                val x1 = -3.45f + 6.9f * (ix + 1) / nx
                val a = near + canyonLength * iz / nz
                val b = near + canyonLength * (iz + 1) / nz
                val y00 = lavaLevel(x0, a); val y01 = lavaLevel(x0, b)
                val y11 = lavaLevel(x1, b); val y10 = lavaLevel(x1, a)
                val heatPulse = 0.5f + 0.5f * sin(time * 4f + ix * 0.8f + iz * 0.9f)
                val magmaColor = when {
                    heatPulse > 0.72f -> Rgb(1f, 0.86f + 0.12f * pulse, 0.24f)
                    heatPulse > 0.35f -> Rgb(1f, 0.42f + 0.12f * pulse, 0.06f)
                    else -> Rgb(0.86f, 0.16f, 0.04f)
                }
                mesh.quad(
                    Point3(x0, y00, a),
                    Point3(x0, y01, b),
                    Point3(x1, y11, b),
                    Point3(x1, y10, a),
                    magmaColor,
                    emissive = true,
                )
            }
        }
    }

    // 4. Floating Obsidian Basalt Crust Plates (MatId.BASALT)
    val plateCount = if (qualityTier == RopeboundQualityTier.LOW) 4 else 6
    mesh.withMaterial(MatId.BASALT, tileScale = 0.55f) {
        for (plate in 0 until plateCount) {
            val px = -2.4f + (plate % 3) * 2.2f + sin(time * 0.8f + plate) * 0.18f
            val pz = near + canyonLength * ((plate * 0.31f + time * 0.08f) % 1f)
            val py = lavaLevel(px, pz) - 0.02f
            val plateSize = 0.68f + (plate % 3) * 0.16f
            mesh.facetedRock(Point3(px, py, pz), plateSize, 0.16f, plateSize * 0.85f, Rgb(0.52f, 0.44f, 0.42f), plate * 19 + 7)
        }
    }

    // 5. Erupting Flame Plumes & Rising Glowing Ember Sparks
    val flameCount = if (qualityTier == RopeboundQualityTier.LOW) 4 else 6
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        for (flame in 0 until flameCount) {
            val fx = -2.5f + flame * (5.0f / (flameCount - 1).coerceAtLeast(1)) + sin(time * 2f + flame) * 0.12f
            val phase = time * 4.4f + flame * 1.8f
            val burstHeight = 0.34f + (0.45f + 0.5f * sin(phase)) * 0.95f
            val fz = near + canyonLength * ((flame % 4) / 4f + 0.12f)
            val baseY = lavaLevel(fx, fz) - 0.05f
            mesh.cone(fx, baseY, fz, 0.34f, burstHeight, Rgb(1f, 0.28f, 0.04f), 6, emissive = true)
            mesh.cone(fx, baseY + 0.02f, fz, 0.20f, burstHeight * 0.70f, Rgb(1f, 0.78f, 0.16f), 5, emissive = true)
        }

        val emberCount = when (qualityTier) {
            RopeboundQualityTier.HIGH -> 10
            RopeboundQualityTier.MEDIUM -> 6
            RopeboundQualityTier.LOW -> 3
        }
        for (ember in 0 until emberCount) {
            val cycle = (time * 0.85f + ember * (1f / emberCount)) % 1f
            val ex = -2.8f + (ember % 5) * 1.4f + cos(time * 3.2f + ember) * 0.24f
            val ez = near + canyonLength * ((ember * 0.29f) % 1f)
            val ey = -0.10f + cycle * 2.15f
            val er = (1f - cycle * 0.65f) * 0.065f
            val emberCol = if (ember % 2 == 0) Rgb(1.0f, 0.88f, 0.28f) else Rgb(1.0f, 0.46f, 0.10f)
            mesh.sphere(Point3(ex, ey, ez), er, emberCol, 5, 4, emissive = true)
        }
    }
}
