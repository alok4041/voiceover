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
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val riverLength = (far - near).coerceAtLeast(0.5f)
    val midZ = (near + far) * 0.5f

    // 1. Dual-Scrolling Turquoise Crossing River Gorge Surface (MatId.WATER) + Cascading Rapids
    mesh.withMaterial(MatId.WATER, tileScale = 0.28f) {
        mesh.plane(-12f, 12f, near - 0.2f, far + 0.2f, -0.71f, Rgb(0.92f, 0.99f, 1.0f))
    }

    val foamCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 14
        RopeboundQualityTier.MEDIUM -> 10
        RopeboundQualityTier.LOW -> 6
    }
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        for (foam in 0..foamCount) {
            val fx = -14f + (foam % 7) * 4.6f + sin(time * 1.4f + foam * 1.7f) * 0.65f
            val travel = (time * 0.38f + foam * 0.13f) % 1f
            val fz = near + (riverLength - 0.3f).coerceAtLeast(0.1f) * travel
            mesh.rod(
                Point3(fx - 0.95f, -0.69f, fz),
                Point3(fx + 0.95f, -0.69f, fz + 0.22f),
                0.055f,
                Rgb(0.94f, 0.99f, 1.0f),
                5,
                emissive = true,
            )
        }
    }

    for (rb in -2..2) {
        val rbx = rb * 3.2f + 0.6f
        val rbz = midZ + (rb % 2) * 0.85f
        mesh.withMaterial(MatId.VERTEX_COLOR) {
            mesh.ellipsoid(Point3(rbx, -0.70f, rbz), 0.92f, 0.04f, 0.85f, Rgb(0.94f, 0.99f, 1.0f), 6, 3, emissive = true)
            // Animated water splash droplets leaping off river boulders
            if (qualityTier != RopeboundQualityTier.LOW) {
                val splashCycle = (time * 2.2f + rb * 0.37f) % 1f
                val dropY = -0.62f + sin(splashCycle * PI).toFloat() * 0.42f
                mesh.sphere(
                    Point3(rbx + (splashCycle - 0.5f) * 0.55f, dropY, rbz),
                    0.085f * (1f - splashCycle * 0.4f),
                    Rgb(0.92f, 0.99f, 1.0f),
                    5,
                    4,
                    emissive = true,
                )
            }
        }
        mesh.withMaterial(if (rb % 2 == 0) MatId.BASALT else MatId.STONE, tileScale = 0.46f) {
            mesh.facetedRock(Point3(rbx, -0.58f, rbz), 0.68f, 0.42f, 0.62f, Rgb(0.82f, 0.84f, 0.86f), rb * 19 + 31)
        }
    }

    // 2. Stepped Mossy Ashlar Cliff Abutments on Both Riverbanks
    for (bankIndex in 0..1) {
        val bankZ = if (bankIndex == 0) near else far
        val landSign = if (bankIndex == 0) -1f else 1f
        val stoneCol = Rgb(0.88f, 0.86f, 0.80f)
        val mossCol = Rgb(0.82f, 0.98f, 0.60f)

        mesh.withMaterial(MatId.STONE, tileScale = 0.42f) {
            mesh.box(Point3(0f, -0.46f, bankZ + landSign * 0.42f), 9.6f, 0.98f, 0.88f, stoneCol)
        }
        mesh.withMaterial(MatId.GRASS, tileScale = 0.46f) {
            mesh.box(Point3(0f, 0.04f, bankZ + landSign * 0.44f), 9.8f, 0.12f, 0.94f, mossCol)
        }
        mesh.withMaterial(MatId.DIRT, tileScale = 0.40f) {
            mesh.box(Point3(0f, 0.06f, bankZ + landSign * 0.42f), 6.8f, 0.08f, 0.88f, Rgb(0.94f, 0.86f, 0.74f))
        }
        if (!bridge) {
            // High-visibility cyan-gold jump lip on river-gap banks
            mesh.withMaterial(MatId.VERTEX_COLOR) {
                mesh.box(
                    Point3(0f, 0.11f, bankZ + landSign * 0.06f),
                    6.6f,
                    0.025f,
                    0.10f,
                    Rgb(0.56f, 0.96f, 1.0f),
                    emissive = true,
                )
            }
        }
    }

    if (bridge) {
        val bridgeWidth = 6.2f
        val planks = 13
        val logDark = Rgb(0.78f, 0.60f, 0.42f)
        val logWarm = Rgb(0.90f, 0.72f, 0.52f)
        val ropeGold = Rgb(0.94f, 0.78f, 0.50f)

        // 3. Weathered Timber Log A-Frame Trestle Piers, Diagonal Knee Braces & Coiled Hemp Rope Lashings
        mesh.withMaterial(MatId.WOOD, tileScale = 0.58f) {
            for (endIdx in 0..1) {
                val endZ = if (endIdx == 0) near + 0.18f else far - 0.18f
                val bankSign = if (endIdx == 0) -1f else 1f
                for (side in SIDES) {
                    val postX = side * 2.95f
                    mesh.rod(Point3(postX, -0.82f, endZ), Point3(postX, 0.82f, endZ), 0.22f, logDark, 7, 0.19f)
                    mesh.rod(
                        Point3(postX, -0.58f, endZ),
                        Point3(postX, 0.02f, endZ - bankSign * 0.95f),
                        0.14f,
                        logWarm,
                        6,
                    )
                    mesh.rod(
                        Point3(postX, -0.52f, endZ),
                        Point3(side * 1.35f, 0.04f, endZ),
                        0.12f,
                        logWarm,
                        6,
                    )
                    for (wrap in 0..2) {
                        val wy = -0.18f + wrap * 0.36f
                        mesh.ellipsoid(Point3(postX, wy, endZ), 0.27f, 0.07f, 0.27f, ropeGold, 7, 4)
                    }
                }
            }

            // 4. Sagging Hemp Handrail Ropes & Twin Underside Support Cables
            for (side in SIDES) {
                val railX = side * 2.95f
                var prevHand = Point3(railX, 0.72f, near + 0.18f)
                var prevUnder = Point3(railX * 0.82f, 0.02f, near + 0.18f)
                val steps = if (qualityTier == RopeboundQualityTier.LOW) 10 else 16
                for (s in 1..steps) {
                    val t = s.toFloat() / steps
                    val z = near + 0.18f + (riverLength - 0.36f) * t
                    val sag = -sin(t * PI).toFloat() * 0.24f
                    val curHand = Point3(railX, 0.72f + sag, z)
                    val curUnder = Point3(railX * 0.82f, 0.02f + sag * 0.65f, z)
                    mesh.rod(prevHand, curHand, 0.052f, ropeGold, 6)
                    mesh.rod(prevUnder, curUnder, 0.045f, ropeGold.shade(0.84f), 5)
                    prevHand = curHand
                    prevUnder = curUnder
                }
            }

            // 5. Rough-Hewn Weathered Timber Planks with Wood Grain, Gaps, Tilted Planks & Tumbling Splinters
            fun footstepsAt(progress: Float, radius: Float): Float {
                var flex = 0f
                for (playerIndex in players.indices) {
                    val player = players[playerIndex]
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
                val sag = -sin(t * PI).toFloat() * 0.14f
                val plankProgress = ((z / COURSE_SCALE) + cameraProgress * 100f) / 100f
                val stepFlex = footstepsAt(plankProgress, 2.6f)
                val wobble = sin(time * 2.2f + index * 0.68f) * 0.018f + stepFlex
                val roll = sin(time * 2.7f + index * 0.54f) * 0.028f + (if (index == 6) -0.09f else 0f) + stepFlex * 0.55f
                val wood = when (index % 3) {
                    0 -> Rgb(0.92f, 0.74f, 0.54f)
                    1 -> Rgb(0.82f, 0.64f, 0.46f)
                    else -> Rgb(0.96f, 0.80f, 0.58f)
                }
                val plankY = 0.08f + sag + wobble
                val plankDepth = (riverLength / planks) * 0.78f

                mesh.bridgePlank(Point3(0f, plankY, z), bridgeWidth, 0.16f, plankDepth, roll, wood)

                if (index % 2 == 0) {
                    for (side in SIDES) {
                        val railY = 0.72f - sin(t * PI).toFloat() * 0.24f
                        mesh.rod(Point3(side * 2.92f, plankY + 0.04f, z), Point3(side * 2.95f, railY, z), 0.032f, ropeGold, 5)
                        mesh.ellipsoid(Point3(side * 2.88f, plankY + 0.05f, z), 0.12f, 0.09f, 0.12f, ropeGold.shade(0.9f), 5, 4)
                    }
                }
            }

            // Tumbling wooden splinter fragments mid-air around the middle of the bridge
            for (splinter in 0..1) {
                val fallCycle = (time * 0.75f + splinter * 0.5f) % 1f
                val sx = if (splinter == 0) 1.4f else -1.1f
                val sy = (0.35f - fallCycle * 0.95f).coerceAtLeast(-0.68f)
                val sz = midZ + (splinter - 0.5f) * 0.65f
                mesh.bridgePlank(Point3(sx, sy, sz), 0.72f, 0.10f, 0.24f, time * 3.5f + splinter, logWarm)
            }
        }
    }
}
