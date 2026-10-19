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
    for (strip in 0..3) {
        val z = near + span * ((strip + 0.5f) / 4f)
        val pulse = 0.55f + 0.45f * sin(time * 4.5f - strip * 0.9f)
        val stripColor = Rgb(0.24f + 0.26f * pulse, 0.82f + 0.14f * pulse, 0.94f)
        mesh.box(Point3(0f, 0.012f, z), 5.8f, 0.024f, 0.22f, stripColor)
    }
    for (marker in 0..2) {
        val z = near + span * (marker / 2f)
        for (side in SIDES) {
            val tx = side * 3.92f
            mesh.box(Point3(tx, -0.04f, z), 0.56f, 0.56f, 0.56f, Rgb(0.38f, 0.44f, 0.48f))
            mesh.rod(Point3(tx, 0.18f, z), Point3(tx, 2.28f, z), 0.13f, Rgb(0.76f, 0.82f, 0.86f), 7, 0.085f)
            val hub = Point3(tx - side * 0.14f, 2.25f, z)
            mesh.sphere(hub, 0.15f, Rgb(0.24f, 0.78f, 0.88f), 7, 5)
            for (blade in 0..2) {
                val angle = time * 6.5f + marker * 1.2f + blade * (2f * PI.toFloat() / 3f)
                val tipY = hub.y + sin(angle) * 0.72f
                val tipZ = hub.z + cos(angle) * 0.72f
                mesh.rod(hub, Point3(hub.x, tipY, tipZ), 0.052f, Rgb(0.88f, 0.95f, 0.98f), 5, 0.02f)
            }
        }
    }
}
