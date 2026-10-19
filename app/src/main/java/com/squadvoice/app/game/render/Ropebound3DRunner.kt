package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// Fantasy Squad Palettes: 0 = Knight (Crimson/Steel), 1 = Ranger (Emerald/Wood), 2 = Wizard (Royal Purple/Gold), 3 = Explorer (Amber/Brass)
private val HERO_TUNIC_PALETTE = arrayOf(
    Rgb(0.85f, 0.22f, 0.18f),
    Rgb(0.20f, 0.60f, 0.24f),
    Rgb(0.42f, 0.25f, 0.66f),
    Rgb(0.90f, 0.60f, 0.15f),
)

private val HERO_HAIR_PALETTE = arrayOf(
    Rgb(0.18f, 0.12f, 0.09f),
    Rgb(0.88f, 0.42f, 0.14f),
    Rgb(0.94f, 0.96f, 0.99f),
    Rgb(0.42f, 0.24f, 0.11f),
)

private val HERO_SKIN_PALETTE = arrayOf(
    Rgb(0.98f, 0.78f, 0.62f),
    Rgb(0.96f, 0.74f, 0.56f),
    Rgb(0.92f, 0.70f, 0.54f),
    Rgb(0.80f, 0.54f, 0.38f),
)

private val PANTS_COLOR = Rgb(0.22f, 0.26f, 0.32f)

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
    playerId: String,
    playerVelocity: Float,
    renderProgress: Float,
    grounded: Boolean,
    jumping: Boolean,
    bracing: Boolean,
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
    val worldZ = renderProgress * 100f * COURSE_SCALE
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

    // 360-degree directional turning & velocity response from 4-way joystick
    val inputSpeed = if (isLocalPlayer) hypot(localSteerInput, localMoveInput) else 0f
    val effectiveVx = if (isLocalPlayer && inputSpeed > 0.08f) localSteerInput * 5.6f else vx
    val effectiveVz = if (isLocalPlayer && inputSpeed > 0.08f) localMoveInput * 9.8f else vz
    val planarSpeed = max(hypot(effectiveVx, effectiveVz), playerVelocity * 0.68f)
    val isMoving = planarSpeed > 0.18f && !bracing

    val targetYaw = when {
        isLocalPlayer && inputSpeed > 0.08f -> atan2(localSteerInput, localMoveInput)
        hypot(vx, vz) > 0.22f -> atan2(vx, vz)
        else -> tracker.smoothYaw
    }

    var angleDiff = (targetYaw - tracker.smoothYaw) % (2f * PI.toFloat())
    if (angleDiff > PI.toFloat()) angleDiff -= 2f * PI.toFloat()
    if (angleDiff < -PI.toFloat()) angleDiff += 2f * PI.toFloat()
    val turnAlpha = 1f - kotlin.math.exp(-16f * dt)
    tracker.smoothYaw += angleDiff * turnAlpha

    val targetLean = when {
        bracing -> -0.18f
        jumping -> 0.12f
        isMoving -> (planarSpeed / 10f).coerceIn(0.06f, 0.24f)
        else -> 0f
    }
    val targetBank = (-effectiveVx * 0.045f).coerceIn(-0.20f, 0.20f)
    tracker.smoothLean += (targetLean - tracker.smoothLean) * 0.32f
    tracker.smoothBank += (targetBank - tracker.smoothBank) * 0.32f

    if (isMoving && grounded) {
        tracker.animDistance += planarSpeed * dt * 2.35f
    }

    val role = index % 4
    val suit = HERO_TUNIC_PALETTE[role]
    val hair = HERO_HAIR_PALETTE[role]
    val skin = HERO_SKIN_PALETTE[role]

    val runPhase = tracker.animDistance + time * (if (isMoving) 7.8f else 0f) + index * 0.8f
    val stride = when {
        jumping -> 0.46f
        isMoving && grounded -> sin(runPhase) * (0.36f + (planarSpeed / 16f).coerceAtMost(0.24f))
        else -> 0f
    }
    val bob = when {
        bracing -> -0.14f
        isMoving && grounded -> abs(sin(runPhase)) * 0.08f
        else -> sin(time * 2.4f + index) * 0.02f
    }
    val baseY = jump + bob

    // Ground shadow & local player emerald ring
    val shadowScale = (1f - (jump / 3.5f).coerceIn(0f, 0.55f))
    mesh.ellipsoid(Point3(x, 0.015f, z), 0.54f * shadowScale, 0.02f, 0.46f * shadowScale, Rgb(0.10f, 0.16f, 0.10f), 8, 4)
    if (isLocalPlayer || surgeActive) {
        val ringColor = if (surgeActive) Rgb(0.45f, 0.98f, 0.42f) else Rgb(0.55f, 0.94f, 0.32f)
        val segments = 10
        val ringR = 0.58f + sin(time * 4f) * 0.03f
        for (s in 0 until segments) {
            val a0 = 2 * PI * s / segments
            val a1 = 2 * PI * (s + 1) / segments
            mesh.rod(
                Point3(x + cos(a0).toFloat() * ringR, 0.035f, z + sin(a0).toFloat() * ringR),
                Point3(x + cos(a1).toFloat() * ringR, 0.035f, z + sin(a1).toFloat() * ringR),
                0.024f,
                ringColor,
                5,
                emissive = true,
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

    // Articulated Legs & Leather Boots
    for (side in SIDES) {
        val legPhase = if (side < 0f) stride else -stride
        val kneeLift = when {
            jumping -> 0.24f
            bracing -> 0.14f
            isMoving -> max(0f, -legPhase) * 0.36f
            else -> 0f
        }
        val hip = pose.p(side * 0.165f, 0.64f, 0f)
        val knee = pose.p(side * 0.175f, 0.35f + kneeLift * 0.45f, legPhase * 0.48f + 0.05f)
        val ankle = pose.p(side * 0.18f, 0.10f + kneeLift * 0.75f, legPhase * 0.85f)
        val bootToe = pose.p(side * 0.18f, 0.06f + kneeLift * 0.75f, legPhase * 0.85f + 0.16f)

        mesh.rod(hip, knee, 0.11f, PANTS_COLOR, 7, 0.095f)
        mesh.rod(knee, ankle, 0.09f, PANTS_COLOR.shade(1.06f), 7, 0.078f)
        mesh.rod(ankle, bootToe, 0.098f, Rgb(0.44f, 0.25f, 0.12f), 7, 0.088f)
    }

    // Chibi Fantasy Tunic / Armor Torso & Belt
    mesh.orientedEllipsoid(pose, 0f, 0.96f, 0f, 0.30f, 0.36f, 0.23f, suit, 9, 6)
    mesh.orientedBox(pose, 0f, 0.95f, 0f, 0.50f, 0.50f, 0.34f, suit.shade(0.94f))
    if (role == 0) {
        // Knight silver steel breastplate
        mesh.orientedBox(pose, 0f, 1.02f, 0.04f, 0.46f, 0.32f, 0.32f, Rgb(0.76f, 0.80f, 0.85f))
    }
    mesh.orientedBox(pose, 0f, 0.72f, 0f, 0.53f, 0.08f, 0.38f, Rgb(0.26f, 0.16f, 0.09f))
    mesh.orientedBox(pose, 0f, 0.72f, 0.195f, 0.12f, 0.09f, 0.04f, Rgb(0.98f, 0.80f, 0.24f))

    // Role Backpack / Ranger Quiver
    if (role == 1) {
        mesh.orientedBox(pose, 0f, 1.04f, -0.24f, 0.26f, 0.48f, 0.18f, Rgb(0.52f, 0.28f, 0.12f))
        mesh.rod(pose.p(-0.06f, 1.24f, -0.24f), pose.p(-0.12f, 1.44f, -0.26f), 0.03f, Rgb(0.96f, 0.92f, 0.80f), 5)
        mesh.rod(pose.p(0.06f, 1.24f, -0.24f), pose.p(0.10f, 1.45f, -0.26f), 0.03f, Rgb(0.98f, 0.82f, 0.28f), 5)
    } else {
        mesh.orientedBox(pose, 0f, 1.02f, -0.25f, 0.42f, 0.46f, 0.20f, Rgb(0.58f, 0.34f, 0.15f))
        mesh.rod(pose.p(-0.22f, 1.31f, -0.24f), pose.p(0.22f, 1.31f, -0.24f), 0.08f, Rgb(0.84f, 0.28f, 0.22f), 7)
    }

    // Swinging Arms & Hands
    for (side in SIDES) {
        val armSwing = when {
            jumping -> 0.48f
            bracing -> -0.22f
            isMoving -> if (side < 0f) -stride * 0.95f else stride * 0.95f
            else -> sin(time * 2.2f + index + side) * 0.04f
        }
        val shoulder = pose.p(side * 0.30f, 1.18f, 0f)
        val elbow = pose.p(side * 0.38f, 0.95f + abs(armSwing) * 0.12f, armSwing * 0.42f - 0.04f)
        val hand = pose.p(side * 0.40f, 0.76f + max(0f, armSwing) * 0.24f, armSwing * 0.82f + 0.12f)
        mesh.rod(shoulder, elbow, 0.095f, suit, 7, 0.082f)
        mesh.rod(elbow, hand, 0.078f, suit.shade(0.95f), 7, 0.068f)
        mesh.sphere(hand, 0.085f, skin, 7, 5)
    }

    // Rounded Chibi Head & Expressive Eyes
    mesh.orientedEllipsoid(pose, 0f, 1.60f, 0.02f, 0.28f, 0.30f, 0.27f, skin, 10, 7)
    for (side in SIDES) {
        val ex = side * 0.10f
        mesh.sphere(pose.p(ex, 1.62f, 0.245f), 0.048f, Rgb(0.99f, 0.98f, 0.95f), 6, 5)
        mesh.sphere(pose.p(ex, 1.62f, 0.272f), 0.024f, Rgb(0.12f, 0.10f, 0.08f), 5, 4)
    }

    // Role-Specific 3D Headgear (0 = Knight Helmet & Plume, 1 = Ranger Hood, 2 = Wizard Pointed Star Hat, 3 = Explorer Goggles)
    when (role) {
        0 -> {
            mesh.orientedEllipsoid(pose, 0f, 1.72f, 0f, 0.30f, 0.22f, 0.29f, Rgb(0.76f, 0.81f, 0.86f), 9, 6)
            mesh.orientedBox(pose, 0f, 1.72f, 0.24f, 0.36f, 0.11f, 0.12f, Rgb(0.84f, 0.88f, 0.92f))
            val plumeSway = sin(time * 6f + runPhase) * 0.08f
            val p0 = pose.p(0f, 1.92f, -0.05f)
            val p1 = pose.p(plumeSway, 2.08f, -0.26f)
            val p2 = pose.p(plumeSway * 1.4f, 1.82f, -0.42f)
            mesh.rod(p0, p1, 0.095f, Rgb(0.90f, 0.18f, 0.16f), 6, 0.11f)
            mesh.rod(p1, p2, 0.11f, Rgb(0.84f, 0.14f, 0.14f), 6, 0.04f)
        }
        1 -> {
            mesh.orientedEllipsoid(pose, 0f, 1.72f, 0.02f, 0.28f, 0.18f, 0.27f, hair, 8, 5)
            mesh.orientedEllipsoid(pose, 0f, 1.76f, -0.04f, 0.31f, 0.24f, 0.30f, Rgb(0.22f, 0.62f, 0.24f), 9, 6)
            mesh.rod(pose.p(0f, 1.84f, -0.10f), pose.p(0f, 1.72f, -0.42f), 0.18f, Rgb(0.18f, 0.52f, 0.20f), 6, 0.03f)
        }
        2 -> {
            mesh.orientedEllipsoid(pose, 0f, 1.66f, -0.04f, 0.29f, 0.22f, 0.27f, hair, 8, 5)
            mesh.orientedEllipsoid(pose, 0f, 1.80f, 0f, 0.48f, 0.05f, 0.48f, Rgb(0.36f, 0.22f, 0.58f), 9, 4)
            val tipP = pose.p(sin(time * 5f + index) * 0.06f, 2.38f, -0.14f)
            mesh.rod(pose.p(0f, 1.82f, 0f), tipP, 0.26f, Rgb(0.44f, 0.28f, 0.68f), 7, 0.03f)
            mesh.sphere(pose.p(0f, 1.98f, 0.19f), 0.055f, Rgb(1f, 0.84f, 0.22f), 6, 4, emissive = true)
        }
        else -> {
            mesh.orientedEllipsoid(pose, 0f, 1.76f, -0.01f, 0.30f, 0.18f, 0.29f, hair, 9, 6)
            mesh.orientedBox(pose, 0f, 1.76f, 0.25f, 0.36f, 0.09f, 0.08f, Rgb(0.88f, 0.62f, 0.20f))
            mesh.sphere(pose.p(-0.09f, 1.76f, 0.28f), 0.055f, Rgb(0.42f, 0.90f, 0.98f), 6, 4, emissive = true)
            mesh.sphere(pose.p(0.09f, 1.76f, 0.28f), 0.055f, Rgb(0.42f, 0.90f, 0.98f), 6, 4, emissive = true)
        }
    }

    return pose.p(0f, 0.92f, -0.36f)
}

internal fun drawSquadTensionRope(
    mesh: TriangleMesh,
    anchorPoints: Array<Point3?>,
    anchorCount: Int,
    ropeTension: Float,
    surgeActive: Boolean,
    time: Float,
) {
    for (index in 0 until anchorCount - 1) {
        val anchor0 = anchorPoints[index] ?: continue
        val anchor1 = anchorPoints[index + 1] ?: continue
        val distanceMeters = abs(anchor0.z - anchor1.z) / COURSE_SCALE
        val sag = 0.08f + (1f - ropeTension.coerceIn(0f, 1f)) * 0.26f + (distanceMeters / 18f).coerceIn(0f, 0.16f)
        val ropeColor = when {
            surgeActive -> Rgb(0.48f, 0.98f, 0.38f)
            ropeTension > 0.85f -> Rgb(1f, 0.36f, 0.28f)
            else -> Rgb(0.88f, 0.72f, 0.44f)
        }
        val ropeAccent = ropeColor.shade(0.76f)
        mesh.sphere(anchor0, 0.072f, Rgb(0.68f, 0.46f, 0.22f), 6, 4)
        mesh.sphere(anchor1, 0.072f, Rgb(0.68f, 0.46f, 0.22f), 6, 4)
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
            mesh.rod(previous, current, 0.044f, if (step % 2 == 0) ropeColor else ropeAccent, 6)
            previous = current
        }
    }
}
