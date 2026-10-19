package com.squadvoice.app.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildPitHazard(
    mesh: TriangleMesh,
    z1: Float,
    z2: Float,
    time: Float,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val midZ = (near + far) * 0.5f
    val span = (far - near).coerceAtLeast(0.5f)
    val pitPulse = 0.5f + 0.5f * sin(time * 3.6f)

    // 1. Deep Basalt Abyssal Floor & Lower Chasm Walls
    mesh.withMaterial(MatId.BASALT, tileScale = 0.42f) {
        mesh.plane(-4.4f, 4.4f, near, far, -0.78f, Rgb(0.32f, 0.36f, 0.40f))
        mesh.box(Point3(-4.35f, -0.42f, midZ), 0.48f, 0.68f, span, Rgb(0.68f, 0.70f, 0.74f))
        mesh.box(Point3(4.35f, -0.42f, midZ), 0.48f, 0.68f, span, Rgb(0.68f, 0.70f, 0.74f))
    }

    // 2. Stepped Ancient Ashlar Stone Ruin Walls on Near & Far Chasm Edges
    mesh.withMaterial(MatId.STONE, tileScale = 0.45f) {
        mesh.box(Point3(0f, -0.32f, near), 8.8f, 0.76f, 0.38f, Rgb(0.86f, 0.84f, 0.78f))
        mesh.box(Point3(0f, -0.32f, far), 8.8f, 0.76f, 0.38f, Rgb(0.86f, 0.84f, 0.78f))
    }

    // 3. Overhanging Mossy Rim Ledges + High-Contrast Golden Jump-Lip Readability Strip
    mesh.withMaterial(MatId.GRASS, tileScale = 0.48f) {
        mesh.box(Point3(0f, 0.06f, near - 0.08f), 8.9f, 0.11f, 0.32f, Rgb(0.84f, 0.98f, 0.62f))
        mesh.box(Point3(0f, 0.06f, far + 0.08f), 8.9f, 0.11f, 0.32f, Rgb(0.84f, 0.98f, 0.62f))
    }
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        val lipCol = Rgb(1.0f, 0.86f + 0.10f * pitPulse, 0.32f)
        mesh.box(Point3(0f, 0.115f, near - 0.16f), 6.8f, 0.025f, 0.10f, lipCol, emissive = true)
        mesh.box(Point3(0f, 0.115f, far + 0.16f), 6.8f, 0.025f, 0.10f, lipCol, emissive = true)
    }

    // 4. Jagged Basalt & Stone Stalagmite Spikes Rising from the Chasm Floor
    val spikeCols = if (qualityTier == RopeboundQualityTier.LOW) 5 else 8
    val colSpacing = 6.7f / (spikeCols - 1).coerceAtLeast(1)
    for (row in 0..2) {
        val rowZ = near + span * ((row + 0.5f) / 3f)
        for (i in 0 until spikeCols) {
            val x = -3.35f + i * colSpacing + (if (row % 2 == 0) 0f else 0.18f)
            if (x < 3.65f) {
                val height = 0.86f + ((i * 7 + row * 3) % 3) * 0.14f
                mesh.withMaterial(MatId.BASALT, tileScale = 0.5f) {
                    mesh.cone(x, -0.72f, rowZ, 0.32f, height * 0.68f, Rgb(0.74f, 0.76f, 0.80f), 6)
                }
                mesh.withMaterial(MatId.STONE, tileScale = 0.5f) {
                    mesh.cone(x, -0.72f + height * 0.58f, rowZ, 0.16f, height * 0.46f, Rgb(0.94f, 0.92f, 0.88f), 6)
                }
            }
        }
    }

    // 5. Rising Chasm Mist Wisps & Timber Warning Lantern Posts
    if (qualityTier != RopeboundQualityTier.LOW) {
        mesh.withMaterial(MatId.VERTEX_COLOR) {
            for (wisp in 0..3) {
                val cycle = (time * 0.45f + wisp * 0.25f) % 1f
                val wx = -2.4f + wisp * 1.6f + sin(time * 1.8f + wisp) * 0.25f
                val wy = -0.58f + cycle * 0.52f
                val wz = near + span * (0.25f + (wisp % 2) * 0.5f)
                mesh.ellipsoid(Point3(wx, wy, wz), 0.52f, 0.08f, 0.38f, Rgb(0.78f, 0.92f, 0.96f), 6, 3, emissive = true)
            }
        }
    }

    for (side in SIDES) {
        val postX = side * 4.25f
        mesh.withMaterial(MatId.WOOD, tileScale = 0.65f) {
            mesh.box(Point3(postX, 0.72f, near - 0.25f), 0.26f, 1.44f, 0.26f, Rgb(0.88f, 0.70f, 0.50f))
        }
        mesh.withMaterial(MatId.VERTEX_COLOR) {
            mesh.sphere(Point3(postX, 1.54f, near - 0.25f), 0.17f, Rgb(1f, 0.76f + 0.2f * pitPulse, 0.22f), 6, 5, emissive = true)
        }
    }
}
