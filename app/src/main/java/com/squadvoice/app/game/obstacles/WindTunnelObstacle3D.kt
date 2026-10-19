package com.squadvoice.app.game

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal fun buildWindTunnel(mesh: TriangleMesh, z1: Float, z2: Float, time: Float) {
    val near = min(z1, z2)
    val far = max(z1, z2)
    val span = (far - near).coerceAtLeast(0.5f)

    // Ground-Inlaid Glowing Emerald-Gold Wind Rune Strips across the wide path
    for (strip in 0..3) {
        val z = near + span * ((strip + 0.5f) / 4f)
        val pulse = 0.55f + 0.45f * sin(time * 4.5f - strip * 0.9f)
        val stripColor = Rgb(0.42f + 0.22f * pulse, 0.92f, 0.54f + 0.26f * pulse)
        mesh.box(Point3(0f, 0.014f, z), 7.2f, 0.026f, 0.24f, stripColor, emissive = true)
    }

    // Carved Stone Wind-Totem Pillars with Spinning Wooden & Brass Windmill Vanes
    for (marker in 0..2) {
        val z = near + span * (marker / 2f)
        for (side in SIDES) {
            val tx = side * 4.35f
            mesh.box(Point3(tx, 0.12f, z), 0.68f, 0.68f, 0.68f, Rgb(0.46f, 0.49f, 0.47f))
            mesh.box(Point3(tx, 0.48f, z), 0.74f, 0.12f, 0.74f, Rgb(0.36f, 0.76f, 0.22f))
            mesh.rod(Point3(tx, 0.48f, z), Point3(tx, 2.38f, z), 0.16f, Rgb(0.52f, 0.32f, 0.16f), 7, 0.11f)
            val hub = Point3(tx - side * 0.16f, 2.32f, z)
            mesh.sphere(hub, 0.16f, Rgb(0.94f, 0.72f, 0.24f), 7, 5)
            for (blade in 0..2) {
                val angle = time * 6.5f + marker * 1.2f + blade * (2f * PI.toFloat() / 3f)
                val tipY = hub.y + sin(angle) * 0.78f
                val tipZ = hub.z + cos(angle) * 0.78f
                mesh.rod(hub, Point3(hub.x, tipY, tipZ), 0.062f, Rgb(0.92f, 0.84f, 0.66f), 5, 0.025f)
            }
        }
    }
}
