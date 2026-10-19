package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal val CHECKPOINT_METERS = floatArrayOf(8f, 19f, 30f, 40f, 50f, 64f, 80f)

internal fun drawMountainVista(
    mesh: TriangleMesh,
    time: Float,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    // 1. Billowy Sunlit Cumulus Cloud Banks along the Distant Horizon
    val cloudCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 9
        RopeboundQualityTier.MEDIUM -> 6
        RopeboundQualityTier.LOW -> 3
    }
    val cloudStep = 152f / cloudCount.coerceAtLeast(1)
    val cloudWhite = Rgb(0.98f, 0.99f, 1.0f)
    val cloudShadow = Rgb(0.84f, 0.93f, 0.99f)
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        for (cloud in 0 until cloudCount) {
            val cx = -76f + cloud * cloudStep + sin(cloud * 2.1f + time * 0.05f) * 3.5f
            val cz = 96f + (cloud % 3) * 8f
            val cy = 14.5f + (cloud % 3) * 3.2f
            mesh.ellipsoid(Point3(cx, cy - 0.8f, cz + 0.2f), 8.2f, 2.6f, 3.2f, cloudShadow, 8, 5, emissive = true)
            mesh.ellipsoid(Point3(cx, cy + 0.6f, cz), 6.4f, 3.8f, 3.0f, cloudWhite, 8, 6, emissive = true)
            if (qualityTier != RopeboundQualityTier.LOW) {
                mesh.ellipsoid(Point3(cx - 4.2f, cy - 0.2f, cz - 0.2f), 4.6f, 2.8f, 2.6f, cloudWhite, 7, 5, emissive = true)
                mesh.ellipsoid(Point3(cx + 4.4f, cy, cz - 0.2f), 4.8f, 3.0f, 2.6f, cloudWhite, 7, 5, emissive = true)
            }
        }
    }

    // 2. Jagged Multi-Ridged Distant Alpine Mountains in Atmospheric Blue Haze
    val peakCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 11
        RopeboundQualityTier.MEDIUM -> 9
        RopeboundQualityTier.LOW -> 7
    }
    val peakSpan = 190f / (peakCount - 1).coerceAtLeast(1)
    mesh.withMaterial(MatId.STONE, tileScale = 0.08f) {
        for (i in 0 until peakCount) {
            val cx = -95f + i * peakSpan
            val cz = 104f + (i % 2) * 6f
            val w = 16f + (i % 3) * 3f
            val h = 19f + (i % 4) * 4.5f
            val ridgeCol = if (i % 2 == 0) Rgb(0.62f, 0.78f, 0.94f) else Rgb(0.54f, 0.72f, 0.90f)
            drawSculptedAlpineMountain(mesh, cx, cz, w, h, ridgeCol, seed = i * 17 + 5)
        }
    }

    // 3. Twin Sheer Flat-Topped Rocky Cliff Mesas with Lush Forest Canopies & Roaring Waterfalls
    drawCliffMesaWithWaterfall(mesh, cx = -21f, cz = 58f, width = 24f, height = 11.2f, fallX = -13.5f, time = time, qualityTier = qualityTier)
    drawCliffMesaWithWaterfall(mesh, cx = 23f, cz = 68f, width = 26f, height = 12.6f, fallX = 14.2f, time = time, qualityTier = qualityTier)
}

private fun drawSculptedAlpineMountain(
    mesh: TriangleMesh,
    cx: Float,
    cz: Float,
    halfW: Float,
    height: Float,
    color: Rgb,
    seed: Int,
) {
    val baseY = -0.8f
    val apex = Point3(cx, height, cz)
    val leftShoulder = Point3(cx - halfW * 0.48f, height * 0.68f, cz - 2.2f)
    val rightShoulder = Point3(cx + halfW * 0.44f, height * 0.62f, cz - 1.8f)
    val baseLeft = Point3(cx - halfW, baseY, cz - 4f)
    val baseMid = Point3(cx + (seed % 3 - 1) * 1.8f, baseY, cz - 8.5f)
    val baseRight = Point3(cx + halfW, baseY, cz - 4f)

    mesh.triangle(baseLeft, baseMid, leftShoulder, color.shade(1.06f))
    mesh.triangle(baseMid, apex, leftShoulder, color.shade(1.12f))
    mesh.triangle(baseMid, rightShoulder, apex, color.shade(0.86f))
    mesh.triangle(baseMid, baseRight, rightShoulder, color.shade(0.82f))
}

private fun drawCliffMesaWithWaterfall(
    mesh: TriangleMesh,
    cx: Float,
    cz: Float,
    width: Float,
    height: Float,
    fallX: Float,
    time: Float,
    qualityTier: RopeboundQualityTier,
) {
    val rockCol = Rgb(0.82f, 0.80f, 0.76f)
    val basaltCol = Rgb(0.74f, 0.76f, 0.80f)
    val turfCol = Rgb(0.82f, 0.98f, 0.66f)
    val depth = 16f

    // Stratified sheer rocky cliff mesa (lower basalt base + upper ashlar stone face)
    mesh.withMaterial(MatId.BASALT, tileScale = 0.22f) {
        mesh.box(Point3(cx, height * 0.22f - 0.5f, cz), width + 0.4f, height * 0.45f, depth + 0.4f, basaltCol)
    }
    mesh.withMaterial(MatId.STONE, tileScale = 0.24f) {
        mesh.box(Point3(cx, height * 0.62f - 0.3f, cz), width, height * 0.68f, depth, rockCol)
    }
    mesh.withMaterial(MatId.GRASS, tileScale = 0.35f) {
        mesh.box(Point3(cx, height - 0.35f, cz), width + 0.8f, 0.75f, depth + 0.8f, turfCol)
    }

    // Pine & oak forest canopy along top of the cliff mesa
    val treeCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 7
        RopeboundQualityTier.MEDIUM -> 5
        RopeboundQualityTier.LOW -> 3
    }
    val treeSpacing = (width * 0.80f) / (treeCount - 1).coerceAtLeast(1)
    for (t in 0 until treeCount) {
        val tx = cx - width * 0.40f + t * treeSpacing
        val tz = cz - depth * 0.32f + (t % 2) * 2.4f
        drawStylizedTree(mesh, tx, tz, species = t % 2, scale = 1.15f, time = time, baseY = height, qualityTier = qualityTier)
    }

    // Wide Multi-Stream Roaring Waterfall Pouring Down the Sheer Cliff Face (MatId.WATERFALL with UV scrolling)
    val fallZ = cz - depth * 0.52f
    val fallHalfW = 1.95f
    val fallTopY = height - 0.15f
    val fallBotY = -0.78f
    mesh.withMaterial(MatId.WATERFALL, tileScale = 0.35f) {
        mesh.quadUv(
            a = Point3(fallX - fallHalfW, fallTopY, fallZ - 0.12f),
            b = Point3(fallX - fallHalfW * 1.12f, fallBotY, fallZ - 1.35f),
            c = Point3(fallX + fallHalfW * 1.12f, fallBotY, fallZ - 1.35f),
            d = Point3(fallX + fallHalfW, fallTopY, fallZ - 0.12f),
            u0 = 0.04f,
            v0 = 0f,
            u1 = 0.96f,
            v1 = 2.8f,
            color = Rgb(0.92f, 0.98f, 1.0f),
        )
        // Side white-water spray ribbons
        val sideStreamCount = if (qualityTier == RopeboundQualityTier.LOW) 2 else 3
        for (s in 0 until sideStreamCount) {
            val offset = (s - (sideStreamCount - 1) * 0.5f) * 1.15f
            val streamX = fallX + offset
            val pulse = 0.90f + 0.10f * sin(time * 5.5f + s * 1.4f)
            mesh.rod(
                Point3(streamX, fallTopY, fallZ - 0.18f),
                Point3(streamX, fallBotY, fallZ - 1.42f),
                0.32f * pulse,
                Rgb(0.95f, 0.99f, 1.0f),
                5,
                0.48f * pulse,
            )
        }
    }

    // Billowing waterfall mist clouds at river surface
    val mistCount = if (qualityTier == RopeboundQualityTier.LOW) 3 else 5
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        for (m in 0 until mistCount) {
            val mx = fallX + (m - (mistCount - 1) * 0.5f) * 0.95f
            val pulse = 0.85f + 0.20f * sin(time * 3.8f + m)
            mesh.sphere(
                Point3(mx, 0.15f, fallZ - 1.6f),
                1.15f * pulse,
                Rgb(0.92f, 0.98f, 1.0f),
                if (qualityTier == RopeboundQualityTier.LOW) 5 else 7,
                4,
                emissive = true,
            )
        }
    }
}

internal fun drawValleySideHills(
    mesh: TriangleMesh,
    cameraProgress: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    // 1. Deep Basalt Riverbed + Dual-Scrolling Turquoise Canyon River Surface (MatId.WATER)
    mesh.withMaterial(MatId.BASALT, tileScale = 0.18f) {
        mesh.plane(-48f, 48f, -26f, 108f, -1.12f, Rgb(0.45f, 0.56f, 0.68f))
    }
    mesh.withMaterial(MatId.WATER, tileScale = 0.22f) {
        mesh.plane(-48f, 48f, -26f, 108f, -0.74f, Rgb(0.88f, 0.98f, 1.0f))
    }

    // 2. Partially Submerged Canyon River Boulders (MatId.BASALT) & White-Water Foam Rapids
    val scrollZ = (cameraProgress * COURSE_SCALE * 14f) % 9.5f
    val boulderCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 28
        RopeboundQualityTier.MEDIUM -> 18
        RopeboundQualityTier.LOW -> 10
    }
    val boulderSpacing = 96f / boulderCount.coerceAtLeast(1)
    for (b in 0 until boulderCount) {
        val side = if (b % 2 == 0) -1f else 1f
        val bx = side * (6.2f + (b % 5) * 2.8f)
        val bz = -18f + (b * boulderSpacing - scrollZ + 96f) % 96f
        val scale = 0.72f + (b % 3) * 0.32f
        mesh.withMaterial(MatId.VERTEX_COLOR) {
            mesh.ellipsoid(
                Point3(bx, -0.72f, bz),
                scale * 1.35f,
                0.04f,
                scale * 1.25f,
                Rgb(0.92f, 0.98f, 1.0f),
                if (qualityTier == RopeboundQualityTier.LOW) 5 else 7,
                3,
                emissive = true,
            )
        }
        mesh.withMaterial(if (b % 2 == 0) MatId.BASALT else MatId.STONE, tileScale = 0.45f) {
            mesh.facetedRock(
                Point3(bx, -0.58f, bz),
                scale,
                0.48f + (b % 2) * 0.22f,
                scale * 0.92f,
                Rgb(0.80f, 0.82f, 0.84f),
                b * 23 + 7,
            )
        }
    }

    // 3. Stepped Mossy Stone Canyon Banks & Forested Ledges Flanking the River Gorge
    val bankCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 14
        RopeboundQualityTier.MEDIUM -> 10
        RopeboundQualityTier.LOW -> 6
    }
    val bankSpacing = 96f / bankCount.coerceAtLeast(1)
    for (bank in 0 until bankCount) {
        val side = if (bank % 2 == 0) -1f else 1f
        val bz = -16f + (bank * bankSpacing - scrollZ + 96f) % 96f
        val bx = side * (16.5f + (bank % 3) * 2.6f)
        val bw = 7.5f + (bank % 2) * 2.4f
        val bh = 2.4f + (bank % 3) * 1.1f
        val bd = 6.8f
        mesh.withMaterial(MatId.STONE, tileScale = 0.32f) {
            mesh.box(Point3(bx, -0.75f + bh * 0.5f, bz), bw, bh, bd, Rgb(0.86f, 0.84f, 0.78f))
        }
        mesh.withMaterial(MatId.GRASS, tileScale = 0.40f) {
            mesh.box(Point3(bx, -0.75f + bh + 0.14f, bz), bw + 0.45f, 0.32f, bd + 0.45f, Rgb(0.78f, 0.96f, 0.58f))
        }
        drawStylizedTree(
            mesh = mesh,
            x = bx - side * 1.4f,
            z = bz,
            species = bank % 2,
            scale = 1.18f,
            time = cameraProgress * 10f,
            baseY = -0.75f + bh + 0.28f,
            qualityTier = qualityTier,
        )
    }
}

internal fun overlapsRiver(
    z: Float,
    margin: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
): Boolean {
    for (i in 0 until riverCount) {
        if (z in (riverStarts[i] - margin)..(riverEnds[i] + margin)) return true
    }
    return false
}

internal fun drawStylizedTree(
    mesh: TriangleMesh,
    x: Float,
    z: Float,
    species: Int,
    scale: Float,
    time: Float,
    baseY: Float = 0.0f,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val sway = sin(time * 1.6f + x * 0.6f + z * 0.4f) * 0.08f
    val lowDetail = qualityTier == RopeboundQualityTier.LOW
    when (species) {
        0 -> {
            // Lush Multi-Cluster Sunlit Deciduous Oak Tree (Wood Trunk + Grass/Foliage Canopy)
            mesh.withMaterial(MatId.WOOD, tileScale = 0.65f) {
                mesh.rod(
                    Point3(x, baseY, z),
                    Point3(x + sway * 0.4f, baseY + 1.85f * scale, z),
                    0.32f * scale,
                    Rgb(0.84f, 0.68f, 0.52f),
                    if (lowDetail) 5 else 7,
                    0.20f * scale,
                )
            }
            val fy = baseY + 1.75f * scale
            val leafShadow = Rgb(0.56f, 0.80f, 0.52f)
            val leafMid = Rgb(0.74f, 0.94f, 0.58f)
            val leafSun = Rgb(0.92f, 1.00f, 0.64f)
            mesh.withMaterial(MatId.FOLIAGE, tileScale = 0.45f) {
                mesh.ellipsoid(
                    Point3(x + sway, fy + 0.55f * scale, z),
                    1.24f * scale,
                    0.94f * scale,
                    1.24f * scale,
                    leafMid,
                    if (lowDetail) 6 else 8,
                    if (lowDetail) 4 else 5,
                )
                if (!lowDetail) {
                    mesh.ellipsoid(
                        Point3(x + sway - 0.62f * scale, fy + 0.34f * scale, z + 0.28f * scale),
                        0.92f * scale,
                        0.76f * scale,
                        0.92f * scale,
                        leafShadow,
                        7,
                        5,
                    )
                    mesh.ellipsoid(
                        Point3(x + sway + 0.62f * scale, fy + 0.42f * scale, z - 0.26f * scale),
                        0.94f * scale,
                        0.78f * scale,
                        0.94f * scale,
                        leafMid.shade(1.06f),
                        7,
                        5,
                    )
                }
                mesh.ellipsoid(
                    Point3(x + sway * 1.2f, fy + 1.18f * scale, z),
                    0.88f * scale,
                    0.72f * scale,
                    0.88f * scale,
                    leafSun,
                    if (lowDetail) 5 else 7,
                    if (lowDetail) 4 else 5,
                )
            }
        }
        else -> {
            // Tall Layered Emerald Conifer / Alpine Pine Tree
            mesh.withMaterial(MatId.WOOD, tileScale = 0.65f) {
                mesh.rod(
                    Point3(x, baseY, z),
                    Point3(x + sway * 0.3f, baseY + 3.10f * scale, z),
                    0.24f * scale,
                    Rgb(0.80f, 0.64f, 0.48f),
                    if (lowDetail) 5 else 6,
                    0.10f * scale,
                )
            }
            val sides = if (lowDetail) 5 else 7
            mesh.withMaterial(MatId.FOLIAGE, tileScale = 0.48f) {
                mesh.cone(x + sway * 0.35f, baseY + 0.55f * scale, z, 1.28f * scale, 1.45f * scale, Rgb(0.58f, 0.82f, 0.56f), sides)
                mesh.cone(x + sway * 0.65f, baseY + 1.38f * scale, z, 1.04f * scale, 1.32f * scale, Rgb(0.70f, 0.92f, 0.60f), sides)
                mesh.cone(x + sway * 0.95f, baseY + 2.15f * scale, z, 0.78f * scale, 1.20f * scale, Rgb(0.86f, 0.98f, 0.66f), sides)
            }
        }
    }
}

internal fun drawMeadows(
    mesh: TriangleMesh,
    cameraProgress: Float,
    time: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    // 1. Ancient Ivy-Covered 3D Stone Ruin Archways along the Right & Left Cliff Ledges
    val ruinCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 6
        RopeboundQualityTier.MEDIUM -> 5
        RopeboundQualityTier.LOW -> 4
    }
    val ruinStep = 84f / ruinCount.coerceAtLeast(1)
    for (ruin in 0 until ruinCount) {
        val side = if (ruin % 3 == 0) -1f else 1f
        val rz = -6f + (ruin * ruinStep + cameraProgress * COURSE_SCALE * 0.4f) % 84f
        if (overlapsRiver(rz, 2.8f, riverCount, riverStarts, riverEnds)) continue
        val rx = side * 5.35f
        mesh.stoneArchRuin(cx = rx, baseY = 0.0f, cz = rz, spanZ = 3.8f, height = 3.4f)
    }

    // 2. Gnarled Canopy Trees & Shrubs Perched on the Stepped Stone Cliff Ledges
    val treeCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 18
        RopeboundQualityTier.MEDIUM -> 12
        RopeboundQualityTier.LOW -> 7
    }
    val treeStep = 82f / treeCount.coerceAtLeast(1)
    for (tree in 0 until treeCount) {
        val side = if (tree % 2 == 0) -1f else 1f
        val tz = -10f + (tree * treeStep + cameraProgress * COURSE_SCALE * 0.35f) % 82f
        if (overlapsRiver(tz, 2.2f, riverCount, riverStarts, riverEnds)) continue
        val tx = side * (5.1f + (tree % 2) * 0.8f)
        drawStylizedTree(
            mesh = mesh,
            x = tx,
            z = tz,
            species = tree % 2,
            scale = 0.92f + (tree % 3) * 0.14f,
            time = time,
            baseY = 0.05f,
            qualityTier = qualityTier,
        )
    }

    // 3. Lush Overhanging Grass Tufts & Wildflowers Along the Cliff Ledges
    val tuftCount = when (qualityTier) {
        RopeboundQualityTier.HIGH -> 56
        RopeboundQualityTier.MEDIUM -> 34
        RopeboundQualityTier.LOW -> 16
    }
    val tuftStep = 68f / tuftCount.coerceAtLeast(1)
    for (tuft in 0 until tuftCount) {
        val side = if (tuft % 2 == 0) -1f else 1f
        val x = side * (4.15f + (tuft % 5) * 0.32f)
        val z = -12f + (tuft * tuftStep + cameraProgress * COURSE_SCALE * 0.75f) % 68f
        if (overlapsRiver(z, 0.8f, riverCount, riverStarts, riverEnds)) continue
        val windWave = sin(time * 2.5f + x * 0.4f + z * 0.6f) * 0.12f
        val green = if (tuft % 2 == 0) Rgb(0.84f, 0.98f, 0.62f) else Rgb(0.70f, 0.90f, 0.54f)
        val tip = Point3(x + windWave, 0.32f + (tuft % 4) * 0.04f, z)
        mesh.withMaterial(MatId.GRASS, tileScale = 0.55f) {
            mesh.triangle(Point3(x - 0.08f, 0.04f, z - 0.03f), Point3(x + 0.08f, 0.04f, z + 0.03f), tip, green)
        }
        if (tuft % 4 == 0 && qualityTier != RopeboundQualityTier.LOW) {
            val flowerColor = when ((tuft / 4) % 3) {
                0 -> Rgb(0.98f, 0.82f, 0.20f)
                1 -> Rgb(0.96f, 0.42f, 0.62f)
                else -> Rgb(0.72f, 0.48f, 0.96f)
            }
            mesh.withMaterial(MatId.VERTEX_COLOR) {
                mesh.sphere(Point3(x + 0.12f, 0.22f, z), 0.095f, flowerColor, 5, 4)
            }
        }
    }
}

internal fun drawTrail(
    mesh: TriangleMesh,
    z0: Float,
    z1: Float,
    qualityTier: RopeboundQualityTier = RopeboundQualityTier.HIGH,
) {
    val length = (z1 - z0).coerceAtLeast(0.1f)
    val midZ = (z0 + z1) * 0.5f

    val basaltTint = Rgb(0.78f, 0.80f, 0.84f)
    val stoneMid = Rgb(0.88f, 0.86f, 0.80f)
    val stoneWarm = Rgb(0.96f, 0.92f, 0.84f)
    val mossBright = Rgb(0.86f, 0.98f, 0.64f)
    val mossMid = Rgb(0.74f, 0.92f, 0.56f)
    val dirtWarm = Rgb(0.96f, 0.90f, 0.80f)

    // 1. Stepped Basalt & Ashlar Stone Cliff Core Rising Out of the Turquoise River Gorge
    mesh.withMaterial(MatId.BASALT, tileScale = 0.32f) {
        mesh.box(Point3(0f, -0.58f, midZ), 10.6f, 0.88f, length, basaltTint)
    }
    mesh.withMaterial(MatId.STONE, tileScale = 0.38f) {
        mesh.box(Point3(0f, -0.18f, midZ), 9.8f, 0.42f, length, stoneMid)
    }

    // 2. Overhanging Lush Emerald-Lime Moss Turf Cushions & Golden-Earth Footpath
    mesh.withMaterial(MatId.GRASS, tileScale = 0.42f) {
        mesh.box(Point3(0f, -0.02f, midZ), 9.4f, 0.10f, length, mossMid)
        for (side in SIDES) {
            mesh.box(Point3(side * 4.15f, 0.05f, midZ), 1.45f, 0.14f, length, mossBright)
        }
    }
    mesh.withMaterial(MatId.DIRT, tileScale = 0.36f) {
        mesh.box(Point3(0f, 0.015f, midZ), 6.9f, 0.05f, length, dirtWarm)
    }

    // 3. Individual Sculpted 3D Beveled Ashlar Stone Blocks, Basalt Columns & Hanging Ivy Vines Along Cliff Sides
    val blockStep = if (qualityTier == RopeboundQualityTier.LOW) 2.75f else 1.85f
    var bz = (ceil(z0 / blockStep) * blockStep).coerceAtLeast(z0)
    var idx = 0
    while (bz + 0.8f < z1) {
        val cz = bz + 0.45f
        for (side in SIDES) {
            val cliffX = side * 4.85f
            val stepH = if (idx % 3 == 0) 0.42f else 0.12f
            val blockCol = if ((idx + (if (side > 0) 1 else 0)) % 2 == 0) stoneWarm else stoneMid

            mesh.withMaterial(MatId.STONE, tileScale = 0.46f) {
                mesh.box(Point3(cliffX, -0.12f + stepH * 0.5f, cz), 1.25f, 0.56f + stepH, 1.68f, blockCol)
            }
            mesh.withMaterial(MatId.GRASS, tileScale = 0.48f) {
                mesh.box(Point3(cliffX, 0.18f + stepH, cz), 1.34f, 0.14f, 1.74f, mossBright)
                if (idx % 2 == 0 && qualityTier != RopeboundQualityTier.LOW) {
                    mesh.rod(
                        Point3(cliffX - side * 0.55f, 0.16f + stepH, cz),
                        Point3(cliffX - side * 0.62f, -0.48f, cz + 0.12f),
                        0.11f,
                        mossMid,
                        5,
                        0.05f,
                    )
                }
            }
            val basaltX = side * 5.45f
            mesh.withMaterial(MatId.BASALT, tileScale = 0.42f) {
                mesh.box(Point3(basaltX, -0.52f, cz + 0.25f), 0.92f, 0.64f, 1.12f, basaltTint)
            }
            if (qualityTier != RopeboundQualityTier.LOW) {
                mesh.withMaterial(MatId.VERTEX_COLOR) {
                    mesh.ellipsoid(
                        Point3(basaltX + side * 0.35f, -0.72f, cz + 0.25f),
                        0.65f,
                        0.03f,
                        0.75f,
                        Rgb(0.92f, 0.98f, 1.0f),
                        6,
                        3,
                        emissive = true,
                    )
                }
            }
        }

        // Weathered Flagstone Pavers on the Golden Dirt Footpath
        val laneOffset = when (idx % 3) {
            0 -> -1.85f
            1 -> 0f
            else -> 1.85f
        }
        mesh.withMaterial(MatId.STONE, tileScale = 0.52f) {
            mesh.box(Point3(laneOffset, 0.028f, cz), 1.18f, 0.038f, 0.82f, if (idx % 2 == 0) stoneWarm else stoneMid)
        }

        bz += blockStep
        idx++
    }
}

internal fun drawCheckpointGate(mesh: TriangleMesh, z: Float, checkpoint: Float, reached: Boolean, time: Float) {
    // Weathered Wooden Signpost & Stone Torch Brazier on the Mossy Ledge
    val signX = 4.25f
    mesh.withMaterial(MatId.WOOD, tileScale = 0.60f) {
        mesh.rod(Point3(signX, 0.05f, z), Point3(signX, 1.15f, z), 0.09f, Rgb(0.84f, 0.68f, 0.52f), 6)
        mesh.box(Point3(signX, 0.92f, z), 0.82f, 0.38f, 0.12f, Rgb(0.92f, 0.76f, 0.58f))
    }
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        mesh.box(
            Point3(signX, 0.92f, z - 0.07f),
            0.48f,
            0.12f,
            0.04f,
            if (reached) Rgb(0.48f, 0.96f, 0.38f) else Rgb(0.96f, 0.90f, 0.76f),
            emissive = reached,
        )
    }

    val torchX = -4.25f
    mesh.withMaterial(MatId.STONE, tileScale = 0.50f) {
        mesh.box(Point3(torchX, 0.52f, z), 0.32f, 0.95f, 0.32f, Rgb(0.88f, 0.86f, 0.80f))
    }
    mesh.withMaterial(MatId.BASALT, tileScale = 0.50f) {
        mesh.box(Point3(torchX, 1.04f, z), 0.48f, 0.14f, 0.48f, Rgb(0.72f, 0.74f, 0.78f))
    }
    val flicker = 0.38f + 0.08f * sin(time * 8f + checkpoint)
    mesh.withMaterial(MatId.VERTEX_COLOR) {
        mesh.cone(torchX, 1.10f, z, 0.20f, flicker, Rgb(1.0f, 0.56f, 0.10f), 6, emissive = true)
        mesh.cone(torchX, 1.12f, z, 0.11f, flicker * 0.68f, Rgb(1.0f, 0.90f, 0.28f), 5, emissive = true)
    }
}

internal fun drawFinishArch(mesh: TriangleMesh, finishZ: Float, time: Float) {
    // Grand Ancient Ivy-Covered Stone Ruin Archway spanning the Finish Line
    val stone = Rgb(0.90f, 0.88f, 0.82f)
    val moss = Rgb(0.82f, 0.98f, 0.62f)
    mesh.withMaterial(MatId.STONE, tileScale = 0.44f) {
        for (side in SIDES) {
            val px = side * 4.25f
            for (r in 0 until 6) {
                mesh.box(Point3(px, 0.35f + r * 0.62f, finishZ), 1.05f, 0.58f, 1.05f, if (r % 2 == 0) stone else stone.shade(0.92f))
            }
        }
        mesh.box(Point3(0f, 3.85f, finishZ), 9.6f, 0.68f, 0.95f, stone.shade(1.05f))
    }
    mesh.withMaterial(MatId.WOOD, tileScale = 0.55f) {
        mesh.box(Point3(0f, 3.42f, finishZ), 8.2f, 0.22f, 0.42f, Rgb(0.88f, 0.72f, 0.54f))
    }
    mesh.withMaterial(MatId.GRASS, tileScale = 0.45f) {
        mesh.ellipsoid(Point3(0f, 4.28f, finishZ), 4.9f, 0.26f, 0.62f, moss, 8, 4)
        for (v in -3..3) {
            val vx = v * 1.15f
            val sway = sin(time * 2.1f + v) * 0.08f
            mesh.rod(
                Point3(vx, 3.82f, finishZ - 0.46f),
                Point3(vx + sway, 2.95f - (abs(v) % 2) * 0.35f, finishZ - 0.50f),
                0.10f,
                moss,
                5,
                0.04f,
            )
        }
    }
}
