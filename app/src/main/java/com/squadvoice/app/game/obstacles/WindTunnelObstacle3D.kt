package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildWindTunnel(
    mesh: TriangleMesh,
    z1: Float,
    z2: Float,
    time: Float,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val span = (far - near).coerceAtLeast(0.5f)

    // 1. Ground-Inlaid Glowing Emerald-Gold Wind Rune Strips across the wide path
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        for (strip in 0..3) {
            val z = near + span * ((strip + 0.5f) / 4f)
            val pulse = 0.55f + 0.45f * sin(time * 4.5f - strip * 0.9f)
            val stripColor = Rgb(0.42f + 0.22f * pulse, 0.92f, 0.54f + 0.26f * pulse)
            mesh.box(Point3(0f, 0.022f, z), 6.8f, 0.026f, 0.24f, stripColor, emissive = true)
        }

        // 2. Sweeping 3D Headwind Gust Ribbons & Swirling Leaf Motes (Clear visual cue to Hold Brace)
        val gustCount = when (qualityTier) {
            RopeboundQualityTier.HIGH -> 7
            RopeboundQualityTier.MEDIUM -> 5
            RopeboundQualityTier.LOW -> 3
        }
        for (g in 0 until gustCount) {
            val cycle = (time * 1.45f + g * (1f / gustCount)) % 1f
            val gz = far - cycle * span
            val gx = -2.6f + (g % 4) * 1.72f + sin(time * 3.8f + g) * 0.35f
            val gy = 0.55f + (g % 3) * 0.48f + cos(time * 3.2f + g) * 0.14f
            val tailZ = (gz + 1.45f).coerceAtMost(far)
            mesh.rod(
                Point3(gx, gy, tailZ),
                Point3(gx + sin(time * 5f + g) * 0.18f, gy, gz),
                0.036f,
                Rgb(0.76f, 0.98f, 0.88f),
                5,
                0.012f,
                emissive = true,
            )
        }
    }

    if (qualityTier != RopeboundQualityTier.LOW) {
        mesh.withMaterial(MatId.FOLIAGE, tileScale = 0.6f) {
            for (leaf in 0..5) {
                val cycle = (time * 1.2f + leaf * 0.17f) % 1f
                val lz = far - cycle * span
                val lx = -2.4f + (leaf % 3) * 2.4f + cos(time * 4.2f + leaf) * 0.45f
                val ly = 0.48f + sin(time * 5.0f + leaf * 1.3f) * 0.28f
                mesh.triangle(
                    Point3(lx - 0.09f, ly, lz),
                    Point3(lx + 0.09f, ly + 0.05f, lz + 0.08f),
                    Point3(lx, ly + 0.12f, lz - 0.09f),
                    Rgb(0.72f, 0.96f, 0.48f),
                )
            }
        }
    }

    // 3. Carved Stone Wind-Totem Pillars with Spinning Wooden & Brass Windmill Vanes
    for (marker in 0..2) {
        val z = near + span * (marker / 2f)
        for (side in SIDES) {
            val tx = side * 4.35f
            mesh.withMaterial(MatId.STONE, tileScale = 0.48f) {
                mesh.box(Point3(tx, 0.12f, z), 0.68f, 0.68f, 0.68f, Rgb(0.86f, 0.84f, 0.78f))
            }
            mesh.withMaterial(MatId.GRASS, tileScale = 0.50f) {
                mesh.box(Point3(tx, 0.48f, z), 0.74f, 0.12f, 0.74f, Rgb(0.82f, 0.98f, 0.60f))
            }
            val hub = Point3(tx - side * 0.16f, 2.32f, z)
            mesh.withMaterial(MatId.WOOD, tileScale = 0.58f) {
                mesh.rod(Point3(tx, 0.48f, z), Point3(tx, 2.38f, z), 0.16f, Rgb(0.86f, 0.68f, 0.48f), 7, 0.11f)
                for (blade in 0..2) {
                    val angle = time * 6.5f + marker * 1.2f + blade * (2f * PI.toFloat() / 3f)
                    val tipY = hub.y + sin(angle) * 0.78f
                    val tipZ = hub.z + cos(angle) * 0.78f
                    mesh.rod(hub, Point3(hub.x, tipY, tipZ), 0.068f, Rgb(0.96f, 0.88f, 0.70f), 5, 0.028f)
                }
            }
            mesh.withMaterial(MatId.VERTEX_COLOR) {
                mesh.sphere(hub, 0.16f, Rgb(0.98f, 0.78f, 0.26f), 7, 5, emissive = true)
            }
        }
    }
}
