package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

internal data class RunnerMotionTracker(
    var lastX: Float = 0f,
    var lastZ: Float = 0f,
    var smoothYaw: Float = 0f,
    var smoothLean: Float = 0f,
    var smoothBank: Float = 0f,
    var animDistance: Float = 0f,
    var renderProgress: Float = -1f,
    var renderLat: Float = 0f,
    var renderHeight: Float = 0f,
    var initialized: Boolean = false,
)

internal fun drawRunner(
    mesh: TriangleMesh,
    player: RopeboundPlayer,
    tracker: RunnerMotionTracker,
    x: Float,
    z: Float,
    jump: Float,
    index: Int,
    isLocalPlayer: Boolean,
    localMoveInput: Float,
    localSteerInput: Float,
    surgeActive: Boolean,
    time: Float,
    dt: Float,
): Point3 {
    val worldZ = player.progress * 100f * COURSE_SCALE
    val vx: Float
    val vz: Float
    if (!tracker.initialized) {
        tracker.lastX = x
        tracker.lastZ = worldZ
        tracker.initialized = true
        vx = 0f
        vz = 0f
    } else {
        vx = ((x - tracker.lastX) / dt).coerceIn(-12f, 12f)
        vz = ((worldZ - tracker.lastZ) / dt).coerceIn(-15f, 15f)
        tracker.lastX = x
        tracker.lastZ = worldZ
    }

    val inputSpeed = if (isLocalPlayer) hypot(localSteerInput, localMoveInput) else 0f
    val effectiveVx = if (isLocalPlayer && inputSpeed > 0.10f) localSteerInput * 5.2f else vx
    val effectiveVz = if (isLocalPlayer && inputSpeed > 0.10f) localMoveInput * 9.5f else vz
    val planarSpeed = max(hypot(effectiveVx, effectiveVz), player.velocity * 0.65f)
    val isMoving = planarSpeed > 0.20f && !player.bracing

    val targetYaw = when {
        isLocalPlayer && inputSpeed > 0.10f -> atan2(localSteerInput, localMoveInput)
        hypot(vx, vz) > 0.25f -> atan2(vx, vz)
        !isMoving -> sin(time * 1.4f + index * 1.3f) * 0.18f
        else -> tracker.smoothYaw
    }

    var angleDiff = (targetYaw - tracker.smoothYaw) % (2f * PI.toFloat())
    if (angleDiff > PI.toFloat()) angleDiff -= 2f * PI.toFloat()
    if (angleDiff < -PI.toFloat()) angleDiff += 2f * PI.toFloat()
    tracker.smoothYaw += angleDiff * (dt * 14f).coerceIn(0.12f, 0.55f)

    val targetLean = when {
        player.bracing -> -0.18f
        player.jumping -> 0.10f
        isMoving -> (planarSpeed / 10f).coerceIn(0.06f, 0.24f)
        else -> 0f
    }
    val targetBank = (-effectiveVx * 0.045f).coerceIn(-0.20f, 0.20f)
    tracker.smoothLean += (targetLean - tracker.smoothLean) * 0.32f
    tracker.smoothBank += (targetBank - tracker.smoothBank) * 0.32f

    if (isMoving && player.grounded) {
        tracker.animDistance += planarSpeed * dt * 2.2f
    }

    val palette = listOf(
        Rgb(0.46f, 0.34f, 0.90f),
        Rgb(0.90f, 0.30f, 0.28f),
        Rgb(0.16f, 0.72f, 0.60f),
        Rgb(0.95f, 0.56f, 0.16f),
        Rgb(0.24f, 0.56f, 0.92f),
    )
    val suit = palette[index % palette.size]
    val pantsColor = Rgb(0.18f, 0.24f, 0.32f)
    val hairPalette = listOf(
        Rgb(0.14f, 0.10f, 0.08f),
        Rgb(0.36f, 0.18f, 0.09f),
        Rgb(0.76f, 0.42f, 0.16f),
        Rgb(0.16f, 0.20f, 0.26f),
    )
    val skinPalette = listOf(
        Rgb(0.96f, 0.72f, 0.54f),
        Rgb(0.80f, 0.52f, 0.36f),
        Rgb(0.98f, 0.78f, 0.62f),
        Rgb(0.58f, 0.36f, 0.26f),
    )
    val styleSeed = player.id.hashCode() and Int.MAX_VALUE
    val hair = hairPalette[styleSeed % hairPalette.size]
    val skin = skinPalette[(styleSeed / 3) % skinPalette.size]
    val packColor = palette[(index + 2) % palette.size].shade(0.88f)

    val runPhase = tracker.animDistance + time * (if (isMoving) 7.5f else 0f) + index * 0.8f
    val stride = when {
        player.jumping -> 0.42f
        isMoving && player.grounded -> sin(runPhase) * (0.34f + (planarSpeed / 18f).coerceAtMost(0.22f))
        else -> 0f
    }
    val bob = when {
        player.bracing -> -0.14f
        isMoving && player.grounded -> abs(sin(runPhase)) * 0.075f
        else -> sin(time * 2.4f + index) * 0.022f
    }
    val baseY = jump + bob

    val shadowScale = (1f - (jump / 3.5f).coerceIn(0f, 0.55f))
    mesh.ellipsoid(Point3(x, 0.014f, z), 0.52f * shadowScale, 0.02f, 0.44f * shadowScale, Rgb(0.10f, 0.18f, 0.14f), 8, 4)
    if (isLocalPlayer || surgeActive) {
        val ringColor = if (surgeActive) Rgb(0.28f, 0.98f, 0.86f) else Rgb(0.32f, 0.88f, 0.98f)
        val segments = 10
        val ringR = 0.56f + sin(time * 4f) * 0.03f
        for (s in 0 until segments) {
            val a0 = 2 * PI * s / segments
            val a1 = 2 * PI * (s + 1) / segments
            mesh.rod(
                Point3(x + cos(a0).toFloat() * ringR, 0.035f, z + sin(a0).toFloat() * ringR),
                Point3(x + cos(a1).toFloat() * ringR, 0.035f, z + sin(a1).toFloat() * ringR),
                0.022f,
                ringColor,
                5,
                emissive = true,
            )
        }
    }

    if (isMoving && player.grounded) {
        for (puff in 0..1) {
            val puffAge = (time * 3.8f + index * 0.5f + puff * 0.5f) % 1f
            val puffR = 0.08f * (1f - puffAge * 0.65f)
            val backDirX = -sin(tracker.smoothYaw)
            val backDirZ = -cos(tracker.smoothYaw)
            val sideX = cos(tracker.smoothYaw) * (if (puff == 0) -0.18f else 0.18f)
            val sideZ = -sin(tracker.smoothYaw) * (if (puff == 0) -0.18f else 0.18f)
            mesh.sphere(
                Point3(
                    x + sideX + backDirX * (0.25f + puffAge * 0.55f),
                    0.08f + puffAge * 0.22f,
                    z + sideZ + backDirZ * (0.25f + puffAge * 0.55f),
                ),
                puffR,
                Rgb(0.86f, 0.80f, 0.68f),
                6,
                4,
            )
        }
    }

    val pose = Pose3D(
        originX = x,
        originY = baseY,
        originZ = z,
        yaw = tracker.smoothYaw,
        pitch = tracker.smoothLean,
        roll = tracker.smoothBank,
    )

    for (side in listOf(-1f, 1f)) {
        val legPhase = if (side < 0f) stride else -stride
        val kneeLift = when {
            player.jumping -> 0.22f
            player.bracing -> 0.14f
            isMoving -> max(0f, -legPhase) * 0.35f
            else -> 0f
        }
        val hip = pose.p(side * 0.165f, 0.64f, 0f)
        val knee = pose.p(
            side * 0.175f,
            0.35f + kneeLift * 0.45f,
            legPhase * 0.48f + (if (player.jumping || player.bracing) 0.14f else 0.05f),
        )
        val ankle = pose.p(
            side * 0.18f,
            0.10f + kneeLift * 0.75f,
            legPhase * 0.85f - (if (player.jumping) 0.12f else 0f),
        )
        val bootToe = pose.p(
            side * 0.18f,
            0.06f + kneeLift * 0.75f,
            legPhase * 0.85f + 0.16f - (if (player.jumping) 0.10f else 0f),
        )
        val bootSole = pose.p(
            side * 0.18f,
            0.025f + kneeLift * 0.75f,
            legPhase * 0.85f + 0.06f - (if (player.jumping) 0.10f else 0f),
        )

        mesh.rod(hip, knee, 0.108f, pantsColor, 7, 0.092f)
        mesh.sphere(knee, 0.096f, Rgb(0.28f, 0.34f, 0.42f), 7, 5)
        mesh.rod(knee, ankle, 0.088f, pantsColor.shade(1.06f), 7, 0.074f)
        mesh.sphere(ankle, 0.082f, Rgb(0.88f, 0.84f, 0.74f), 6, 4)
        mesh.rod(ankle, bootToe, 0.095f, suit.shade(0.84f), 7, 0.085f)
        mesh.sphere(bootToe, 0.088f, Rgb(0.24f, 0.28f, 0.34f), 7, 5)
        mesh.sphere(bootSole, 0.092f, Rgb(0.92f, 0.84f, 0.64f), 7, 4)
    }

    mesh.orientedEllipsoid(pose, 0f, 0.64f, 0f, 0.25f, 0.14f, 0.19f, pantsColor.shade(0.92f), 8, 5)
    mesh.orientedEllipsoid(pose, 0f, 0.98f, 0f, 0.28f, 0.34f, 0.21f, suit, 9, 6)
    mesh.orientedBox(pose, 0f, 0.96f, 0f, 0.50f, 0.52f, 0.34f, suit.shade(0.92f))
    mesh.orientedBox(pose, 0f, 0.98f, 0.165f, 0.15f, 0.48f, 0.05f, Rgb(0.96f, 0.92f, 0.82f))
    mesh.orientedEllipsoid(pose, 0f, 1.25f, 0.02f, 0.21f, 0.08f, 0.19f, suit.shade(1.18f), 8, 5)

    mesh.orientedBox(pose, 0f, 0.72f, 0f, 0.53f, 0.08f, 0.38f, Rgb(0.22f, 0.26f, 0.30f))
    mesh.orientedBox(pose, 0f, 0.72f, 0.195f, 0.12f, 0.09f, 0.04f, Rgb(0.95f, 0.78f, 0.32f))

    mesh.orientedBox(pose, 0f, 1.03f, -0.25f, 0.42f, 0.48f, 0.20f, packColor)
    mesh.orientedBox(pose, 0f, 0.96f, -0.36f, 0.28f, 0.22f, 0.06f, packColor.shade(1.18f))
    mesh.orientedBox(pose, 0f, 1.24f, -0.25f, 0.44f, 0.10f, 0.22f, suit.shade(1.16f))
    mesh.rod(
        pose.p(-0.22f, 1.33f, -0.24f),
        pose.p(0.22f, 1.33f, -0.24f),
        0.075f,
        Rgb(0.92f, 0.76f, 0.44f),
        7,
    )
    for (side in listOf(-1f, 1f)) {
        mesh.rod(
            pose.p(side * 0.18f, 1.26f, -0.15f),
            pose.p(side * 0.19f, 1.26f, 0.15f),
            0.036f,
            Rgb(0.92f, 0.74f, 0.38f),
            5,
        )
        mesh.rod(
            pose.p(side * 0.19f, 1.26f, 0.15f),
            pose.p(side * 0.17f, 0.76f, 0.17f),
            0.032f,
            Rgb(0.92f, 0.74f, 0.38f),
            5,
        )
    }

    for (side in listOf(-1f, 1f)) {
        val armSwing = when {
            player.jumping -> 0.48f
            player.bracing -> -0.22f
            isMoving -> if (side < 0f) -stride * 0.95f else stride * 0.95f
            else -> sin(time * 2.2f + index + side) * 0.04f
        }
        val shoulder = pose.p(side * 0.30f, 1.18f, 0f)
        val elbow = pose.p(
            side * (0.38f + (if (player.bracing) 0.08f else 0f)),
            0.95f + (if (player.jumping) 0.18f else abs(armSwing) * 0.12f),
            armSwing * 0.42f - 0.06f,
        )
        val hand = pose.p(
            side * (0.40f + (if (player.bracing) 0.10f else 0f)),
            0.76f + (if (player.jumping) 0.36f else max(0f, armSwing) * 0.24f),
            armSwing * 0.82f + 0.12f,
        )
        mesh.sphere(shoulder, 0.105f, suit.shade(1.06f), 7, 5)
        mesh.rod(shoulder, elbow, 0.095f, suit, 7, 0.082f)
        mesh.sphere(elbow, 0.084f, suit.shade(0.88f), 7, 5)
        mesh.rod(elbow, hand, 0.078f, suit.shade(0.95f), 7, 0.068f)
        mesh.sphere(hand, 0.084f, skin, 7, 5)
    }

    val neckBase = pose.p(0f, 1.24f, 0f)
    mesh.rod(neckBase, pose.p(0f, 1.42f, 0.01f), 0.105f, skin, 7, 0.095f)
    mesh.orientedEllipsoid(pose, 0f, 1.60f, 0.02f, 0.265f, 0.30f, 0.255f, skin, 10, 7)

    for (side in listOf(-1f, 1f)) {
        mesh.sphere(pose.p(side * 0.255f, 1.58f, 0.01f), 0.068f, skin.shade(0.92f), 6, 4)
    }

    val irisColor = if (styleSeed % 3 == 0) Rgb(0.20f, 0.56f, 0.68f) else Rgb(0.36f, 0.22f, 0.12f)
    for (side in listOf(-1f, 1f)) {
        val ex = side * 0.098f
        mesh.sphere(pose.p(ex, 1.63f, 0.225f), 0.052f, Rgb(0.99f, 0.98f, 0.95f), 7, 5)
        mesh.sphere(pose.p(ex, 1.63f, 0.252f), 0.030f, irisColor, 6, 4)
        mesh.sphere(pose.p(ex, 1.63f, 0.268f), 0.017f, Rgb(0.05f, 0.06f, 0.08f), 5, 4)
        mesh.sphere(pose.p(ex - 0.010f, 1.645f, 0.278f), 0.008f, Rgb(1f, 1f, 1f), 5, 4, emissive = true)
        mesh.rod(
            pose.p(ex - 0.052f, 1.70f, 0.225f),
            pose.p(ex + 0.052f, 1.708f, 0.225f),
            0.020f,
            hair.shade(0.9f),
            5,
        )
    }
    mesh.sphere(pose.p(0f, 1.565f, 0.278f), 0.044f, skin.shade(0.94f), 7, 5)
    mesh.rod(
        pose.p(-0.068f, 1.485f, 0.242f),
        pose.p(0f, 1.468f, 0.258f),
        0.015f,
        Rgb(0.46f, 0.18f, 0.18f),
        5,
    )
    mesh.rod(
        pose.p(0f, 1.468f, 0.258f),
        pose.p(0.068f, 1.485f, 0.242f),
        0.015f,
        Rgb(0.46f, 0.18f, 0.18f),
        5,
    )

    mesh.orientedEllipsoid(pose, 0f, 1.76f, -0.01f, 0.28f, 0.16f, 0.275f, hair, 9, 6)
    mesh.orientedEllipsoid(pose, 0f, 1.60f, -0.09f, 0.265f, 0.26f, 0.20f, hair.shade(0.92f), 9, 6)
    for (side in listOf(-1f, 1f)) {
        mesh.orientedEllipsoid(pose, side * 0.205f, 1.64f, -0.04f, 0.095f, 0.21f, 0.16f, hair.shade(0.94f), 7, 5)
    }

    when (index % 4) {
        0 -> {
            mesh.orientedEllipsoid(pose, 0f, 1.82f, 0.02f, 0.285f, 0.11f, 0.28f, suit.shade(1.12f), 8, 5)
            mesh.orientedBox(pose, 0f, 1.76f, 0.24f, 0.34f, 0.035f, 0.18f, suit.shade(0.86f))
            val sway = stride * 0.35f
            val tie = pose.p(0f, 1.72f, -0.27f)
            val midTail = pose.p(sway * 0.6f, 1.54f, -0.38f)
            val tipTail = pose.p(sway, 1.36f, -0.35f)
            mesh.sphere(tie, 0.055f, Rgb(0.95f, 0.76f, 0.28f), 6, 4)
            mesh.rod(tie, midTail, 0.082f, hair, 6, 0.065f)
            mesh.rod(midTail, tipTail, 0.065f, hair.shade(0.90f), 6, 0.038f)
        }
        1 -> {
            mesh.orientedBox(pose, 0f, 1.75f, 0.23f, 0.36f, 0.085f, 0.07f, Rgb(0.24f, 0.28f, 0.34f))
            mesh.sphere(pose.p(-0.09f, 1.75f, 0.26f), 0.052f, Rgb(0.38f, 0.88f, 0.96f), 6, 4)
            mesh.sphere(pose.p(0.09f, 1.75f, 0.26f), 0.052f, Rgb(0.38f, 0.88f, 0.96f), 6, 4)
            for (tuft in -1..1) {
                val base = pose.p(tuft * 0.10f, 1.82f, -0.04f)
                val tip = pose.p(tuft * 0.13f, 1.98f, -0.10f)
                mesh.rod(base, tip, 0.075f, hair.shade(1.08f), 5, 0.02f)
            }
        }
        2 -> {
            mesh.rod(pose.p(-0.27f, 1.60f, 0.01f), pose.p(0.27f, 1.60f, 0.01f), 0.025f, Rgb(0.20f, 0.86f, 0.78f), 5)
            for (side in listOf(-1f, 1f)) {
                mesh.sphere(pose.p(side * 0.275f, 1.58f, 0.01f), 0.078f, Rgb(0.18f, 0.24f, 0.30f), 7, 5)
                mesh.rod(
                    pose.p(side * 0.21f, 1.56f, -0.12f),
                    pose.p(side * 0.24f, 1.32f, -0.15f),
                    0.075f,
                    hair,
                    6,
                    0.045f,
                )
            }
        }
        else -> {
            mesh.orientedEllipsoid(pose, 0f, 1.82f, -0.02f, 0.28f, 0.15f, 0.27f, packColor.shade(1.15f), 8, 5)
            mesh.sphere(pose.p(0f, 1.97f, -0.04f), 0.085f, Rgb(0.96f, 0.94f, 0.88f), 7, 5)
            var braidPrev = pose.p(0f, 1.68f, -0.26f)
            for (b in 1..3) {
                val braidNext = pose.p(sin(time * 3f + b) * 0.03f, 1.68f - b * 0.11f, -0.30f)
                mesh.rod(braidPrev, braidNext, 0.052f, hair.shade(if (b % 2 == 0) 1.06f else 0.88f), 5)
                braidPrev = braidNext
            }
        }
    }

    return pose.p(0f, 0.92f, -0.38f)
}

internal fun drawSquadTensionRope(
    mesh: TriangleMesh,
    anchorPoints: List<Point3>,
    ropeTension: Float,
    surgeActive: Boolean,
    time: Float,
) {
    for (index in 0 until anchorPoints.size - 1) {
        val anchor0 = anchorPoints[index]
        val anchor1 = anchorPoints[index + 1]
        val distanceMeters = abs(anchor0.z - anchor1.z) / COURSE_SCALE
        val sag = 0.06f + (1f - ropeTension.coerceIn(0f, 1f)) * 0.24f + (distanceMeters / 18f).coerceIn(0f, 0.16f)
        val ropeColor = when {
            surgeActive -> Rgb(0.32f, 0.98f, 0.88f)
            ropeTension > 0.85f -> Rgb(1f, 0.34f, 0.38f)
            ropeTension > 0.55f -> Rgb(1f, 0.84f, 0.28f)
            else -> Rgb(0.22f, 0.86f, 0.74f)
        }
        val ropeAccent = ropeColor.shade(0.78f)
        mesh.sphere(anchor0, 0.068f, Rgb(0.88f, 0.78f, 0.48f), 7, 5)
        mesh.sphere(anchor1, 0.068f, Rgb(0.88f, 0.78f, 0.48f), 7, 5)
        var previous = anchor0
        val segments = 9
        for (step in 1..segments) {
            val t = step.toFloat() / segments
            val vib = if (ropeTension > 0.7f) sin(time * 28f + step * 1.4f) * 0.018f else 0f
            val current = Point3(
                anchor0.x + (anchor1.x - anchor0.x) * t,
                anchor0.y + (anchor1.y - anchor0.y) * t - sin(t * PI).toFloat() * sag + vib,
                anchor0.z + (anchor1.z - anchor0.z) * t,
            )
            mesh.rod(previous, current, 0.038f, if (step % 2 == 0) ropeColor else ropeAccent, 6)
            previous = current
        }
    }
}
