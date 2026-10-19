package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

internal val CHECKPOINT_METERS = floatArrayOf(8f, 19f, 30f, 40f, 50f, 64f, 80f)

private val SKY_BANDS = arrayOf(
    Rgb(0.74f, 0.93f, 1.00f),
    Rgb(0.56f, 0.85f, 0.99f),
    Rgb(0.38f, 0.74f, 0.96f),
    Rgb(0.24f, 0.62f, 0.92f),
    Rgb(0.16f, 0.52f, 0.88f),
    Rgb(0.10f, 0.44f, 0.82f),
)

internal fun drawMountainVista(mesh: TriangleMesh, time: Float) {
    // 1. Vibrant Azure-to-Horizon Sky Backdrop
    for (band in SKY_BANDS.indices) {
        val y0 = -36f + band * 24f
        val y1 = y0 + 24f
        val skyZ = 152f
        mesh.quad(
            Point3(-210f, y0, skyZ),
            Point3(210f, y0, skyZ),
            Point3(210f, y1, skyZ),
            Point3(-210f, y1, skyZ),
            SKY_BANDS[band],
            emissive = true,
        )
    }

    // 2. Billowy Pure-White Sunlit Cumulus Cloud Banks along the Horizon (Emissive so never dark grey!)
    val cloudWhite = Rgb(0.98f, 0.99f, 1.0f)
    val cloudShadow = Rgb(0.84f, 0.93f, 0.99f)
    for (cloud in 0 until 9) {
        val cx = -76f + cloud * 19f + sin(cloud * 2.1f) * 3.5f
        val cz = 96f + (cloud % 3) * 8f
        val cy = 14.5f + (cloud % 3) * 3.2f
        mesh.ellipsoid(Point3(cx, cy - 0.8f, cz + 0.2f), 8.2f, 2.6f, 3.2f, cloudShadow, 8, 5, emissive = true)
        mesh.ellipsoid(Point3(cx, cy + 0.6f, cz), 6.4f, 3.8f, 3.0f, cloudWhite, 8, 6, emissive = true)
        mesh.ellipsoid(Point3(cx - 4.2f, cy - 0.2f, cz - 0.2f), 4.6f, 2.8f, 2.6f, cloudWhite, 7, 5, emissive = true)
        mesh.ellipsoid(Point3(cx + 4.4f, cy, cz - 0.2f), 4.8f, 3.0f, 2.6f, cloudWhite, 7, 5, emissive = true)
    }

    // 3. Jagged Multi-Ridged Distant Alpine Mountains in Atmospheric Blue Haze (No Cones!)
    for (i in 0 until 11) {
        val cx = -95f + i * 19f
        val cz = 104f + (i % 2) * 6f
        val w = 16f + (i % 3) * 3f
        val h = 19f + (i % 4) * 4.5f
        val ridgeCol = if (i % 2 == 0) Rgb(0.42f, 0.64f, 0.86f) else Rgb(0.36f, 0.58f, 0.82f)
        drawSculptedAlpineMountain(mesh, cx, cz, w, h, ridgeCol, seed = i * 17 + 5)
    }

    // 4. Twin Sheer Flat-Topped Rocky Cliff Mesas with Lush Forest Canopies & Roaring Waterfalls
    drawCliffMesaWithWaterfall(mesh, cx = -21f, cz = 58f, width = 24f, height = 11.2f, fallX = -13.5f, time = time)
    drawCliffMesaWithWaterfall(mesh, cx = 23f, cz = 68f, width = 26f, height = 12.6f, fallX = 14.2f, time = time)
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

    // Sunlit left faces & shaded right faces for crisp alpine ridge line
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
) {
    val rockCol = Rgb(0.42f, 0.47f, 0.54f)
    val turfCol = Rgb(0.32f, 0.68f, 0.24f)
    val depth = 16f

    // Stratified sheer rocky cliff mesa
    mesh.box(Point3(cx, height * 0.5f - 0.5f, cz), width, height, depth, rockCol)
    mesh.box(Point3(cx, height - 0.35f, cz), width + 0.8f, 0.75f, depth + 0.8f, turfCol)

    // Pine & oak forest canopy along top of the cliff mesa
    for (t in 0 until 7) {
        val tx = cx - width * 0.40f + t * (width * 0.13f)
        val tz = cz - depth * 0.32f + (t % 2) * 2.4f
        drawStylizedTree(mesh, tx, tz, species = t % 2, scale = 1.15f, time = time, baseY = height)
    }

    // Wide Multi-Stream Roaring Waterfall Pouring Down the Sheer Cliff Face into the River Gorge
    val fallZ = cz - depth * 0.52f
    mesh.box(Point3(fallX, height * 0.48f, fallZ), 3.6f, height * 0.98f, 0.45f, Rgb(0.48f, 0.86f, 0.98f), emissive = true)
    for (s in -2..2) {
        val streamX = fallX + s * 0.68f
        val pulse = 0.88f + 0.12f * sin(time * 5.5f + s * 1.4f)
        mesh.rod(
            Point3(streamX, height - 0.2f, fallZ - 0.15f),
            Point3(streamX, -0.75f, fallZ - 1.35f),
            0.34f * pulse,
            Rgb(0.92f, 0.98f, 1.0f),
            5,
            0.52f * pulse,
            emissive = true,
        )
    }
    // Billowing waterfall mist clouds at river surface
    for (m in -2..2) {
        val pulse = 0.85f + 0.20f * sin(time * 3.8f + m)
        mesh.sphere(
            Point3(fallX + m * 0.95f, 0.15f, fallZ - 1.6f),
            1.15f * pulse,
            Rgb(0.92f, 0.98f, 1.0f),
            7,
            5,
            emissive = true,
        )
    }
}

internal fun drawValleySideHills(
    mesh: TriangleMesh,
    cameraProgress: Float,
    riverCount: Int,
    riverStarts: FloatArray,
    riverEnds: FloatArray,
) {
    // 1. Rushing Turquoise Canyon River Gorge Filling the Entire Valley Floor (No Flat Green Lawn!)
    mesh.plane(-48f, 48f, -26f, 108f, -1.05f, Rgb(0.04f, 0.34f, 0.62f))
    mesh.plane(-48f, 48f, -26f, 108f, -0.74f, Rgb(0.08f, 0.58f, 0.86f))

    // 2. Partially Submerged Canyon River Boulders & White-Water Foam Rapids
    val scrollZ = (cameraProgress * COURSE_SCALE * 14f) % 9.5f
    for (b in 0 until 28) {
        val side = if (b % 2 == 0) -1f else 1f
        val bx = side * (6.2f + (b % 5) * 2.8f)
        val bz = -18f + (b * 4.2f - scrollZ + 96f) % 96f
        val scale = 0.72f + (b % 3) * 0.32f
        // White foam ring around each river boulder
        mesh.ellipsoid(Point3(bx, -0.72f, bz), scale * 1.35f, 0.04f, scale * 1.25f, Rgb(0.92f, 0.98f, 1.0f), 7, 3, emissive = true)
        mesh.facetedRock(
            Point3(bx, -0.58f, bz),
            scale,
            0.48f + (b % 2) * 0.22f,
            scale * 0.92f,
            Rgb(0.42f, 0.45f, 0.47f),
            b * 23 + 7,
        )
    }

    // 3. Stepped Mossy Stone Canyon Banks & Forested Ledges Flanking the River Gorge
    for (bank in 0 until 14) {
        val side = if (bank % 2 == 0) -1f else 1f
        val bz = -16f + (bank * 7.8f - scrollZ + 96f) % 96f
        val bx = side * (16.5f + (bank % 3) * 2.6f)
        val bw = 7.5f + (bank % 2) * 2.4f
        val bh = 2.4f + (bank % 3) * 1.1f
        val bd = 6.8f
        mesh.box(Point3(bx, -0.75f + bh * 0.5f, bz), bw, bh, bd, Rgb(0.46f, 0.45f, 0.42f))
        mesh.box(Point3(bx, -0.75f + bh + 0.14f, bz), bw + 0.45f, 0.32f, bd + 0.45f, Rgb(0.38f, 0.78f, 0.20f))
        drawStylizedTree(mesh, bx - side * 1.4f, bz, species = bank % 2, scale = 1.18f, time = cameraProgress * 10f, baseY = -0.75f + bh + 0.28f)
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
) {
    val sway = sin(time * 1.6f + x * 0.6f + z * 0.4f) * 0.08f
    when (species) {
        0 -> {
            // Lush Multi-Cluster Sunlit Deciduous Oak Tree (Matching Reference Canopy Trees)
            mesh.rod(Point3(x, baseY, z), Point3(x + sway * 0.4f, baseY + 1.85f * scale, z), 0.32f * scale, Rgb(0.38f, 0.23f, 0.12f), 7, 0.20f * scale)
            val fy = baseY + 1.75f * scale
            val leafShadow = Rgb(0.16f, 0.48f, 0.18f)
            val leafMid = Rgb(0.28f, 0.68f, 0.22f)
            val leafSun = Rgb(0.54f, 0.86f, 0.24f)
            mesh.ellipsoid(Point3(x + sway, fy + 0.55f * scale, z), 1.24f * scale, 0.94f * scale, 1.24f * scale, leafMid, 8, 5)
            mesh.ellipsoid(Point3(x + sway - 0.62f * scale, fy + 0.34f * scale, z + 0.28f * scale), 0.92f * scale, 0.76f * scale, 0.92f * scale, leafShadow, 7, 5)
            mesh.ellipsoid(Point3(x + sway + 0.62f * scale, fy + 0.42f * scale, z - 0.26f * scale), 0.94f * scale, 0.78f * scale, 0.94f * scale, leafMid.shade(1.08f), 7, 5)
            mesh.ellipsoid(Point3(x + sway * 1.2f, fy + 1.18f * scale, z), 0.88f * scale, 0.72f * scale, 0.88f * scale, leafSun, 7, 5)
        }
        else -> {
            // Tall Layered Emerald Conifer / Pine Tree
            mesh.rod(Point3(x, baseY, z), Point3(x + sway * 0.3f, baseY + 3.10f * scale, z), 0.24f * scale, Rgb(0.36f, 0.20f, 0.11f), 6, 0.10f * scale)
            mesh.cone(x + sway * 0.35f, baseY + 0.55f * scale, z, 1.28f * scale, 1.45f * scale, Rgb(0.14f, 0.44f, 0.24f), 7)
            mesh.cone(x + sway * 0.65f, baseY + 1.38f * scale, z, 1.04f * scale, 1.32f * scale, Rgb(0.20f, 0.56f, 0.28f), 7)
            mesh.cone(x + sway * 0.95f, baseY + 2.15f * scale, z, 0.78f * scale, 1.20f * scale, Rgb(0.30f, 0.68f, 0.32f), 7)
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
) {
    // 1. Ancient Ivy-Covered 3D Stone Ruin Archways & Stepped Masonry Walls along the Right & Left Cliff Ledges
    for (ruin in 0 until 6) {
        val side = if (ruin % 3 == 0) -1f else 1f
        val rz = -6f + (ruin * 14.5f + cameraProgress * COURSE_SCALE * 0.4f) % 84f
        if (overlapsRiver(rz, 2.8f, riverCount, riverStarts, riverEnds)) continue
        val rx = side * 5.35f
        mesh.stoneArchRuin(cx = rx, baseY = 0.0f, cz = rz, spanZ = 3.8f, height = 3.4f)
    }

    // 2. Gnarled Canopy Trees & Shrubs Perched on the Stepped Stone Cliff Ledges
    for (tree in 0 until 18) {
        val side = if (tree % 2 == 0) -1f else 1f
        val tz = -10f + (tree * 5.2f + cameraProgress * COURSE_SCALE * 0.35f) % 82f
        if (overlapsRiver(tz, 2.2f, riverCount, riverStarts, riverEnds)) continue
        val tx = side * (5.1f + (tree % 2) * 0.8f)
        drawStylizedTree(mesh, tx, tz, species = tree % 2, scale = 0.92f + (tree % 3) * 0.14f, time = time, baseY = 0.05f)
    }

    // 3. Lush Overhanging Grass Tufts & Wildflowers Along the Cliff Ledges
    for (tuft in 0 until 56) {
        val side = if (tuft % 2 == 0) -1f else 1f
        val x = side * (4.15f + (tuft % 5) * 0.32f)
        val z = -12f + (tuft * 3.1f + cameraProgress * COURSE_SCALE * 0.75f) % 68f
        if (overlapsRiver(z, 0.8f, riverCount, riverStarts, riverEnds)) continue
        val windWave = sin(time * 2.5f + x * 0.4f + z * 0.6f) * 0.12f
        val green = if (tuft % 2 == 0) Rgb(0.46f, 0.82f, 0.18f) else Rgb(0.32f, 0.70f, 0.16f)
        val tip = Point3(x + windWave, 0.32f + (tuft % 4) * 0.04f, z)
        mesh.triangle(Point3(x - 0.08f, 0.04f, z - 0.03f), Point3(x + 0.08f, 0.04f, z + 0.03f), tip, green)
        if (tuft % 4 == 0) {
            val flowerColor = when ((tuft / 4) % 3) {
                0 -> Rgb(0.98f, 0.82f, 0.20f)
                1 -> Rgb(0.96f, 0.42f, 0.62f)
                else -> Rgb(0.72f, 0.48f, 0.96f)
            }
            mesh.sphere(Point3(x + 0.12f, 0.22f, z), 0.095f, flowerColor, 5, 4)
        }
    }
}

internal fun drawTrail(mesh: TriangleMesh, z0: Float, z1: Float) {
    val length = (z1 - z0).coerceAtLeast(0.1f)
    val midZ = (z0 + z1) * 0.5f

    // 1. Stepped Ashlar Stone Cliff Core Rising Out of the Turquoise River Gorge
    val stoneDark = Rgb(0.42f, 0.41f, 0.38f)
    val stoneMid = Rgb(0.50f, 0.49f, 0.45f)
    val stoneWarm = Rgb(0.56f, 0.54f, 0.49f)
    val mossBright = Rgb(0.44f, 0.82f, 0.18f)
    val mossMid = Rgb(0.30f, 0.68f, 0.16f)

    mesh.box(Point3(0f, -0.58f, midZ), 10.6f, 0.88f, length, stoneDark)
    mesh.box(Point3(0f, -0.18f, midZ), 9.8f, 0.42f, length, stoneMid)

    // 2. Overhanging Lush Emerald-Lime Moss Turf Cushions & Golden-Earth Footpath
    mesh.box(Point3(0f, -0.02f, midZ), 9.4f, 0.10f, length, mossMid)
    for (side in SIDES) {
        mesh.box(Point3(side * 4.15f, 0.05f, midZ), 1.45f, 0.14f, length, mossBright)
    }
    mesh.box(Point3(0f, 0.015f, midZ), 6.9f, 0.05f, length, Rgb(0.78f, 0.58f, 0.34f))

    // 3. Individual Sculpted 3D Beveled Ashlar Stone Blocks, Basalt Columns & Hanging Ivy Vines Along Cliff Sides
    val blockStep = 1.85f
    var bz = (ceil(z0 / blockStep) * blockStep).coerceAtLeast(z0)
    var idx = 0
    while (bz + 0.8f < z1) {
        val cz = bz + 0.45f
        for (side in SIDES) {
            val cliffX = side * 4.85f
            val stepH = if (idx % 3 == 0) 0.42f else 0.12f
            val blockCol = if ((idx + (if (side > 0) 1 else 0)) % 2 == 0) stoneWarm else stoneMid
            // Upper ashlar block + moss turf cap
            mesh.box(Point3(cliffX, -0.12f + stepH * 0.5f, cz), 1.25f, 0.56f + stepH, 1.68f, blockCol)
            mesh.box(Point3(cliffX, 0.18f + stepH, cz), 1.34f, 0.14f, 1.74f, mossBright)
            // Lower columnar basalt rock pillar at the turquoise river waterline + white foam splash
            val basaltX = side * 5.45f
            mesh.box(Point3(basaltX, -0.52f, cz + 0.25f), 0.92f, 0.64f, 1.12f, stoneDark)
            mesh.ellipsoid(Point3(basaltX + side * 0.35f, -0.72f, cz + 0.25f), 0.65f, 0.03f, 0.75f, Rgb(0.92f, 0.98f, 1.0f), 6, 3, emissive = true)

            // Hanging 3D ivy vine draping down the stone block face
            if (idx % 2 == 0) {
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

        // Weathered Flagstone Pavers on the Golden Dirt Footpath
        val laneOffset = when (idx % 3) {
            0 -> -1.85f
            1 -> 0f
            else -> 1.85f
        }
        mesh.box(Point3(laneOffset, 0.028f, cz), 1.18f, 0.038f, 0.82f, if (idx % 2 == 0) stoneWarm else stoneMid)

        bz += blockStep
        idx++
    }
}

internal fun drawCheckpointGate(mesh: TriangleMesh, z: Float, checkpoint: Float, reached: Boolean, time: Float) {
    // Weathered Wooden Right-Arrow Signpost & Stone Torch Brazier on the Mossy Ledge
    val signX = 4.25f
    mesh.rod(Point3(signX, 0.05f, z), Point3(signX, 1.15f, z), 0.09f, Rgb(0.42f, 0.24f, 0.12f), 6)
    mesh.box(Point3(signX, 0.92f, z), 0.82f, 0.38f, 0.12f, Rgb(0.58f, 0.34f, 0.16f))
    mesh.box(
        Point3(signX, 0.92f, z - 0.07f),
        0.48f,
        0.12f,
        0.04f,
        if (reached) Rgb(0.48f, 0.96f, 0.38f) else Rgb(0.96f, 0.90f, 0.76f),
        emissive = reached,
    )

    val torchX = -4.25f
    mesh.box(Point3(torchX, 0.52f, z), 0.32f, 0.95f, 0.32f, Rgb(0.48f, 0.47f, 0.44f))
    mesh.box(Point3(torchX, 1.04f, z), 0.48f, 0.14f, 0.48f, Rgb(0.28f, 0.25f, 0.22f))
    val flicker = 0.38f + 0.08f * sin(time * 8f + checkpoint)
    mesh.cone(torchX, 1.10f, z, 0.20f, flicker, Rgb(1.0f, 0.56f, 0.10f), 6, emissive = true)
    mesh.cone(torchX, 1.12f, z, 0.11f, flicker * 0.68f, Rgb(1.0f, 0.90f, 0.28f), 5, emissive = true)
}

internal fun drawFinishArch(mesh: TriangleMesh, finishZ: Float, time: Float) {
    // Grand Ancient Ivy-Covered Stone Ruin Archway spanning the Finish Line
    val stone = Rgb(0.52f, 0.50f, 0.46f)
    val moss = Rgb(0.42f, 0.80f, 0.18f)
    for (side in SIDES) {
        val px = side * 4.25f
        for (r in 0 until 6) {
            mesh.box(Point3(px, 0.35f + r * 0.62f, finishZ), 1.05f, 0.58f, 1.05f, if (r % 2 == 0) stone else stone.shade(0.92f))
        }
    }
    mesh.box(Point3(0f, 3.85f, finishZ), 9.6f, 0.68f, 0.95f, stone.shade(1.05f))
    mesh.ellipsoid(Point3(0f, 4.28f, finishZ), 4.9f, 0.26f, 0.62f, moss, 8, 4)
}
