package com.squadvoice.app.game

import android.content.Context
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private fun setCameraMatrix(matrix: FloatArray, cameraBlend: Float) {
    val amount = cameraBlend.coerceIn(0f, 1f)
    fun mix(from: Float, to: Float) = from + (to - from) * amount
    Matrix.setLookAtM(
        matrix, 0,
        0f, mix(3.4f, 18f), mix(-6.5f, 0f),
        0f, mix(1.2f, 0f), mix(8f, 4f),
        0f, mix(1f, 0f), mix(0f, 1f),
    )
}

@Composable
internal fun Ropebound3DScene(
    team: RopeboundTeam,
    viewerIdentity: String,
    obstacles: List<RopeboundObstacle>,
    cameraBlend: Float,
    showPlayerNameplates: Boolean = true,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val view = remember(context) { Ropebound3DView(context) }
    SideEffect { view.submit(Ropebound3DState(team, viewerIdentity, obstacles, cameraBlend)) }

    DisposableEffect(view) {
        view.onResume()
        onDispose { view.onPause() }
    }
    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
    LaunchedEffect(view) {
        delay(350)
        onReady()
    }
    if (showPlayerNameplates) {
        RopeboundPlayerNameplates(team, viewerIdentity, cameraBlend, Modifier.fillMaxSize())
    }
}

@Composable
private fun RopeboundPlayerNameplates(team: RopeboundTeam, viewerIdentity: String, cameraBlend: Float, modifier: Modifier) {
    val density = LocalDensity.current
    val namePaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 9.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    val pingPaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 8.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    Canvas(modifier = modifier) {
        val local = team.players.firstOrNull { it.id == viewerIdentity }
        val cameraProgress = local?.progress ?: team.progress
        val projection = FloatArray(16)
        val camera = FloatArray(16)
        val viewProjection = FloatArray(16)
        Matrix.perspectiveM(projection, 0, 58f, size.width / size.height.coerceAtLeast(1f), .1f, 180f)
        setCameraMatrix(camera, cameraBlend)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, camera, 0)
        team.players.forEachIndexed { index, player ->
            val z = (player.progress - cameraProgress) * 100f * .80f
            val x = (index - (team.players.size - 1) / 2f) * 1.22f
            val y = 2.25f + (player.height / 12f).coerceIn(-.35f, .5f) * 2.6f
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, viewProjection, 0, floatArrayOf(x, y, z, 1f), 0)
            if (clip[3] <= .2f) return@forEachIndexed
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            if (ndcX !in -1.2f..1.2f || ndcY !in -1.2f..1.2f) return@forEachIndexed
            val sx = (ndcX + 1f) * .5f * size.width
            val sy = (1f - ndcY) * .5f * size.height
            val ping = player.pingMs?.let { "${it}ms" } ?: "—ms"
            pingPaint.color = when {
                player.pingMs == null -> android.graphics.Color.LTGRAY
                player.pingMs < 150 -> android.graphics.Color.rgb(119, 224, 181)
                player.pingMs < 300 -> android.graphics.Color.rgb(255, 209, 102)
                else -> android.graphics.Color.rgb(255, 133, 133)
            }
            val displayName = player.name.ifBlank { "Player" }.take(12)
            val pad = 7.dp.toPx()
            val gap = 6.dp.toPx()
            val width = namePaint.measureText(displayName) + pingPaint.measureText(ping) + pad * 2 + gap
            val height = 17.dp.toPx()
            val left = (sx - width / 2f).coerceIn(2.dp.toPx(), size.width - width - 2.dp.toPx())
            val top = sy - 14.dp.toPx()
            if (top < 0f || top + height > size.height) return@forEachIndexed
            drawRoundRect(
                Color(0xE6102028),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            )
            drawIntoCanvas { canvas ->
                val baseline = top + (height - (namePaint.ascent() + namePaint.descent())) / 2f
                canvas.nativeCanvas.drawText(displayName, left + pad, baseline, namePaint)
                canvas.nativeCanvas.drawText(ping, left + pad + namePaint.measureText(displayName) + gap, baseline, pingPaint)
            }
        }
    }
}

private data class Ropebound3DState(
    val team: RopeboundTeam,
    val viewerIdentity: String,
    val obstacles: List<RopeboundObstacle>,
    val cameraBlend: Float,
)

private class Ropebound3DView(context: Context) : GLSurfaceView(context) {
    private val gameRenderer = Ropebound3DRenderer()

    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun submit(state: Ropebound3DState) = gameRenderer.submit(state)
}

private data class Point3(val x: Float, val y: Float, val z: Float)
private data class Rgb(val r: Float, val g: Float, val b: Float) {
    fun shade(amount: Float) = Rgb((r * amount).coerceIn(0f, 1f), (g * amount).coerceIn(0f, 1f), (b * amount).coerceIn(0f, 1f))
}

private class TriangleMesh {
    private val values = ArrayList<Float>(48_000)
    val vertexCount get() = values.size / 6

    fun triangle(a: Point3, b: Point3, c: Point3, color: Rgb) {
        vertex(a, color); vertex(b, color); vertex(c, color)
    }

    fun quad(a: Point3, b: Point3, c: Point3, d: Point3, color: Rgb) {
        triangle(a, b, c, color)
        triangle(a, c, d, color)
    }

    private fun vertex(point: Point3, color: Rgb) {
        values += point.x; values += point.y; values += point.z
        values += color.r; values += color.g; values += color.b
    }

    fun asBuffer(): FloatBuffer = ByteBuffer.allocateDirect(values.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            values.forEach(::put)
            position(0)
        }

    fun box(center: Point3, width: Float, height: Float, depth: Float, color: Rgb) {
        val x0 = center.x - width / 2f; val x1 = center.x + width / 2f
        val y0 = center.y - height / 2f; val y1 = center.y + height / 2f
        val z0 = center.z - depth / 2f; val z1 = center.z + depth / 2f
        quad(Point3(x0,y1,z0), Point3(x1,y1,z0), Point3(x1,y1,z1), Point3(x0,y1,z1), color.shade(1.24f))
        quad(Point3(x0,y0,z1), Point3(x1,y0,z1), Point3(x1,y0,z0), Point3(x0,y0,z0), color.shade(.58f))
        quad(Point3(x0,y0,z1), Point3(x1,y0,z1), Point3(x1,y1,z1), Point3(x0,y1,z1), color)
        quad(Point3(x1,y0,z0), Point3(x0,y0,z0), Point3(x0,y1,z0), Point3(x1,y1,z0), color.shade(.78f))
        quad(Point3(x0,y0,z0), Point3(x0,y0,z1), Point3(x0,y1,z1), Point3(x0,y1,z0), color.shade(.86f))
        quad(Point3(x1,y0,z1), Point3(x1,y0,z0), Point3(x1,y1,z0), Point3(x1,y1,z1), color.shade(.70f))
    }

    fun bridgePlank(center: Point3, width: Float, thickness: Float, length: Float, roll: Float, color: Rgb) {
        val halfWidth = width * .5f
        val halfLength = length * .5f
        val rise = sin(roll) * halfWidth
        val leftNear = Point3(center.x-halfWidth, center.y-rise, center.z-halfLength)
        val rightNear = Point3(center.x+halfWidth, center.y+rise, center.z-halfLength)
        val rightFar = Point3(center.x+halfWidth, center.y+rise, center.z+halfLength)
        val leftFar = Point3(center.x-halfWidth, center.y-rise, center.z+halfLength)
        val leftNearBottom = Point3(leftNear.x,leftNear.y-thickness,leftNear.z)
        val rightNearBottom = Point3(rightNear.x,rightNear.y-thickness,rightNear.z)
        val rightFarBottom = Point3(rightFar.x,rightFar.y-thickness,rightFar.z)
        val leftFarBottom = Point3(leftFar.x,leftFar.y-thickness,leftFar.z)
        quad(leftNear,rightNear,rightFar,leftFar,color.shade(1.16f))
        quad(leftNearBottom,leftFarBottom,rightFarBottom,rightNearBottom,color.shade(.55f))
        quad(leftNear,leftFar,leftFarBottom,leftNearBottom,color.shade(.82f))
        quad(rightNearBottom,rightFarBottom,rightFar,rightNear,color.shade(.76f))
        quad(leftNearBottom,rightNearBottom,rightNear,leftNear,color)
        quad(leftFar,rightFar,rightFarBottom,leftFarBottom,color.shade(.9f))
    }

    fun plane(x0: Float, x1: Float, z0: Float, z1: Float, y: Float, color: Rgb) {
        quad(Point3(x0,y,z0), Point3(x0,y,z1), Point3(x1,y,z1), Point3(x1,y,z0), color)
    }

    fun sphere(center: Point3, radius: Float, color: Rgb, slices: Int = 9, rings: Int = 6) {
        ellipsoid(center,radius,radius,radius,color,slices,rings)
    }

    fun ellipsoid(
        center: Point3,
        radiusX: Float,
        radiusY: Float,
        radiusZ: Float,
        color: Rgb,
        slices: Int = 9,
        rings: Int = 6,
        smoothShading: Boolean = false,
    ) {
        for (ring in 0 until rings) {
            val phi0 = -PI / 2 + PI * ring / rings
            val phi1 = -PI / 2 + PI * (ring + 1) / rings
            for (slice in 0 until slices) {
                val t0 = 2 * PI * slice / slices
                val t1 = 2 * PI * (slice + 1) / slices
                fun point(phi: Double, theta: Double) = Point3(
                    center.x + radiusX * cos(phi).toFloat() * cos(theta).toFloat(),
                    center.y + radiusY * sin(phi).toFloat(),
                    center.z + radiusZ * cos(phi).toFloat() * sin(theta).toFloat(),
                )
                val a = point(phi0,t0); val b = point(phi1,t0); val c = point(phi1,t1); val d = point(phi0,t1)
                val light = if (smoothShading) {
                    if (ring % 2 == 0) 1.025f else .975f
                } else if (ring % 2 == 0) 1.08f else .92f
                quad(a,b,c,d,color.shade(light))
            }
        }
    }

    fun facetedRock(center: Point3, radiusX: Float, radiusY: Float, radiusZ: Float, color: Rgb, seed: Int) {
        val sides = 7
        val ringHeights = floatArrayOf(-.72f, -.34f, .18f, .62f, .82f)
        val rings = ringHeights.mapIndexed { ring, height ->
            List(sides) { slice ->
                val angle = 2f * PI.toFloat() * slice / sides + (ring % 2) * .17f
                val wobble = 1f + sin((seed * 1.73f + ring * 3.1f + slice * 5.7f)) * .105f
                Point3(
                    center.x + cos(angle) * radiusX * wobble,
                    center.y + height * radiusY,
                    center.z + sin(angle) * radiusZ * wobble,
                )
            }
        }
        for (ring in 0 until rings.lastIndex) {
            for (slice in 0 until sides) {
                val next = (slice + 1) % sides
                val a = rings[ring][slice]
                val b = rings[ring][next]
                val c = rings[ring + 1][next]
                val d = rings[ring + 1][slice]
                val facet = .76f + ((seed * 13 + ring * 7 + slice * 11) % 9) * .055f
                if ((ring + slice) % 2 == 0) {
                    triangle(a, b, c, color.shade(facet))
                    triangle(a, c, d, color.shade(facet * 1.04f))
                } else {
                    triangle(a, b, d, color.shade(facet * .96f))
                    triangle(b, c, d, color.shade(facet * 1.06f))
                }
            }
        }
        val top = rings.last()
        for (slice in 0 until sides) {
            triangle(Point3(center.x, center.y + radiusY * .88f, center.z), top[slice], top[(slice + 1) % sides], color.shade(1.16f))
        }
    }

    fun cone(x: Float, y: Float, z: Float, radius: Float, height: Float, color: Rgb, sides: Int = 6) {
        val tip = Point3(x, y + height, z)
        for (side in 0 until sides) {
            val a = 2 * PI * side / sides
            val b = 2 * PI * (side + 1) / sides
            val p0 = Point3(x + cos(a).toFloat() * radius, y, z + sin(a).toFloat() * radius)
            val p1 = Point3(x + cos(b).toFloat() * radius, y, z + sin(b).toFloat() * radius)
            triangle(p0,p1,tip,color.shade(if (side % 2 == 0) 1.12f else .82f))
            triangle(Point3(x,y,z),p1,p0,color.shade(.65f))
        }
    }

    fun rod(from: Point3, to: Point3, radius: Float, color: Rgb, sides: Int = 6) {
        val dx = to.x-from.x; val dy = to.y-from.y; val dz = to.z-from.z
        val length = kotlin.math.sqrt(dx*dx+dy*dy+dz*dz).coerceAtLeast(.001f)
        val ux=dx/length; val uy=dy/length; val uz=dz/length
        var ax = uy; var ay = -ux; var az = 0f
        var al = kotlin.math.sqrt(ax*ax+ay*ay+az*az)
        if (al < .001f) { ax=1f; ay=0f; az=0f; al=1f }
        ax/=al; ay/=al; az/=al
        val bx=uy*az-uz*ay; val by=uz*ax-ux*az; val bz=ux*ay-uy*ax
        for (side in 0 until sides) {
            val a=2*PI*side/sides; val b=2*PI*(side+1)/sides
            fun ring(center: Point3, angle: Double) = Point3(
                center.x+radius*(ax*cos(angle).toFloat()+bx*sin(angle).toFloat()),
                center.y+radius*(ay*cos(angle).toFloat()+by*sin(angle).toFloat()),
                center.z+radius*(az*cos(angle).toFloat()+bz*sin(angle).toFloat()),
            )
            val p0=ring(from,a); val p1=ring(from,b); val p2=ring(to,b); val p3=ring(to,a)
            quad(p0,p1,p2,p3,color.shade(if(side%2==0)1.08f else .78f))
        }
    }
}

private class Ropebound3DRenderer : GLSurfaceView.Renderer {
    @Volatile private var scene = Ropebound3DState(
        RopeboundTeam(0,0f,0f,0f,false,0,3, emptyList(), emptyList()),
        "",
        emptyList(),
        0f,
    )
    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var width = 1
    private var height = 1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val epoch = System.nanoTime()
    private var lastFrameAt = 0f

    fun submit(next: Ropebound3DState) { scene = next }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(.42f,.75f,.91f,1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        val vertex = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); vColor = aColor; }
        """.trimIndent()
        val fragment = """
            precision mediump float;
            varying vec3 vColor;
            void main() { gl_FragColor = vec4(vColor, 1.0); }
        """.trimIndent()
        program = linkProgram(vertex, fragment)
        positionHandle = GLES20.glGetAttribLocation(program,"aPosition")
        colorHandle = GLES20.glGetAttribLocation(program,"aColor")
        mvpHandle = GLES20.glGetUniformLocation(program,"uMvp")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1); this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0,0,this.width,this.height)
        Matrix.perspectiveM(projection,0,58f,this.width.toFloat()/this.height,.1f,180f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = (System.nanoTime()-epoch)/1_000_000_000f
        if (now - lastFrameAt < 1f / 30f) return
        lastFrameAt = now
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = scene
        val local = current.team.players.firstOrNull { it.id == current.viewerIdentity }
        val progress = local?.progress ?: current.team.progress
        val mesh = TriangleMesh()
        buildWorld(mesh,current,progress,now)
        setCameraMatrix(view,current.cameraBlend)
        Matrix.multiplyMM(vp,0,projection,0,view,0)
        Matrix.multiplyMM(mvp,0,vp,0,IDENTITY,0)
        val buffer = mesh.asBuffer()
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle,3,GLES20.GL_FLOAT,false,24,buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle,3,GLES20.GL_FLOAT,false,24,buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    private fun buildWorld(mesh: TriangleMesh, state: Ropebound3DState, cameraProgress: Float, time: Float) {
        val courseScale = .80f
        // API player progress is normalized to 0..1; obstacle/checkpoint positions are meters.
        fun zAt(distanceMeters: Float) = (distanceMeters-cameraProgress*100f)*courseScale+4f
        val cuts = state.obstacles.filter { it.id in setOf("pit","river-bridge","river-gap","lava") }
            .map { zAt(it.start) to zAt(it.end) }.filter { it.second > -14f && it.first < 90f }.sortedBy { it.first }
        // A depth-layered sky, overlapping mountain ridges, and volumetric clouds form a real 3D backdrop.
        val skyBands=listOf(
            Rgb(.63f,.84f,.90f),Rgb(.52f,.78f,.88f),Rgb(.39f,.69f,.84f),
            Rgb(.29f,.60f,.79f),Rgb(.22f,.52f,.74f),Rgb(.17f,.45f,.68f),
        )
        for(band in skyBands.indices) {
            val y0=-48f+band*25f; val y1=y0+25f; val skyZ=150f
            mesh.quad(Point3(-180f,y0,skyZ),Point3(-180f,y1,skyZ),Point3(180f,y1,skyZ),Point3(180f,y0,skyZ),skyBands[band])
        }
        mesh.plane(-36f,36f,-24f,100f,-.30f,Rgb(.22f,.48f,.20f))
        mesh.plane(-36f,36f,-24f,100f,-.31f,Rgb(.32f,.57f,.24f))
        var roadZ = -18f
        cuts.forEach { (start,end) ->
            val boundedStart=start.coerceIn(-18f,92f); val boundedEnd=end.coerceIn(-18f,92f)
            if (boundedStart>roadZ) drawTrail(mesh,roadZ,boundedStart)
            roadZ=max(roadZ,boundedEnd)
        }
        if (roadZ<92f) drawTrail(mesh,roadZ,92f)

        // Distant low-poly hills, trees, stones, and layered grass make a volumetric landscape.
        for (hill in 0 until 9) {
            val x=-34f+hill*8.5f
            mesh.cone(x,-.2f,52f+(hill%3)*5f,5f,5f+(hill%4)*1.5f,Rgb(.22f,.48f,.55f),5)
        }
        for(layer in 0..1) {
            val baseZ=if(layer==0)72f else 91f
            for(peakIndex in 0 until 18) {
                val x0=-108f+peakIndex*12f
                val x1=x0+12f
                val peakX=(x0+x1)*.5f+if(peakIndex%2==0)1.8f else -1.5f
                val peakY=if(layer==0)7.5f+(peakIndex%5)*1.8f else 10f+(peakIndex%4)*2.3f
                val peakZ=baseZ+(peakIndex%3)*2.2f
                val baseColor=if(layer==0)Rgb(.27f,.50f,.56f) else Rgb(.34f,.59f,.62f)
                mesh.triangle(Point3(x0,-.24f,baseZ),Point3(peakX,peakY,peakZ),Point3(x1,-.24f,baseZ),baseColor.shade(if(peakIndex%2==0)1.05f else .88f))
                mesh.triangle(Point3(x0,-.24f,baseZ),Point3(x1,-.24f,baseZ),Point3((x0+x1)*.5f,-.24f,baseZ+5f),baseColor.shade(.68f))
            }
        }
        for(cloud in 0 until 9) {
            val x=-30f+(cloud%5)*15f
            val z=35f+(cloud*13.7f)%68f
            val y=11f+(cloud%3)*2.3f
            val puff=Rgb(.87f,.94f,.93f)
            mesh.ellipsoid(Point3(x,y,z),2.8f,1.05f,1.65f,puff,8,5)
            mesh.ellipsoid(Point3(x-1.8f,y-.15f,z+.3f),1.75f,.83f,1.25f,puff.shade(.97f),8,5)
            mesh.ellipsoid(Point3(x+1.7f,y-.08f,z-.25f),2.0f,.92f,1.35f,puff.shade(.92f),8,5)
        }
        mesh.sphere(Point3(23f,20f,104f),3.2f,Rgb(1f,.88f,.58f),10,6)
        for (tree in 0 until 18) {
            val side=if(tree%2==0)-1f else 1f
            val x=side*(7.0f+(tree%5)*1.7f)
            val z=-3f+(tree*7.1f+cameraProgress*courseScale*.35f)%62f
            mesh.box(Point3(x,.65f,z),.35f,1.3f,.35f,Rgb(.34f,.20f,.11f))
            mesh.cone(x,.62f,z,.98f,2.1f,Rgb(.15f,.39f,.23f),6)
            mesh.cone(x,1.55f,z,.78f,1.95f,Rgb(.19f,.48f,.26f),6)
            mesh.cone(x,2.40f,z,.57f,1.7f,Rgb(.25f,.57f,.30f),6)
            mesh.ellipsoid(Point3(x,.38f,z),.34f,.25f,.34f,Rgb(.41f,.55f,.28f),7,4)
        }
        for (tuft in 0 until 110) {
            val side=if(tuft%2==0)-1f else 1f
            val x=side*(3.8f+(tuft%9)*.43f)
            val z=-8f+(tuft*3.17f+cameraProgress*courseScale*.75f)%58f
            val sway=sin(time*1.8f+tuft*.71f)*.13f
            val green=when(tuft%4){0->Rgb(.69f,.80f,.30f);1->Rgb(.34f,.68f,.26f);2->Rgb(.46f,.76f,.29f);else->Rgb(.77f,.75f,.33f)}
            for(blade in 0..2) {
                val bx=x+(blade-1)*.11f
                val tip=Point3(bx+sway,.22f+(tuft%5)*.035f,z)
                val base0=Point3(bx-.07f,0f,z-.03f); val base1=Point3(bx+.07f,0f,z+.03f)
                mesh.triangle(base0,base1,tip,green.shade(if(blade%2==0)1.12f else .88f))
            }
            if(tuft%13==0) mesh.sphere(Point3(x,.08f,z),.12f,Rgb(.75f,.67f,.43f),7,4)
            if(tuft%19==0) mesh.sphere(Point3(x+.2f,.11f,z+.1f),.07f,Rgb(.92f,.73f,.75f),7,4)
        }

        state.obstacles.forEach { obstacle ->
            val z1=zAt(obstacle.start); val z2=zAt(obstacle.end)
            when(obstacle.id) {
                "pit" -> {
                    // A deep, rocky trench with several staggered spike rows makes the jump hazard
                    // readable from both the gameplay camera and the loading panorama.
                    mesh.plane(-3.8f,3.8f,z1,z2,-.48f,Rgb(.075f,.085f,.09f))
                    mesh.plane(-3.65f,-3.35f,z1,z2,-.18f,Rgb(.29f,.25f,.20f))
                    mesh.plane(3.35f,3.65f,z1,z2,-.18f,Rgb(.34f,.28f,.20f))
                    for (row in 0..2) {
                        val rowZ=z1+(z2-z1)*((row+.5f)/3f)
                        repeat(9){i->
                            val x=-3.15f+i*.79f+(if(row%2==0)0f else .37f)
                            if(x<3.45f) {
                                val height=.72f+((i*7+row*3)%4)*.12f
                                val baseY=-.39f
                                val spikeColor=if((i+row)%3==0)Rgb(.54f,.57f,.57f) else Rgb(.69f,.67f,.60f)
                                mesh.cone(x,baseY,rowZ,.31f,height,spikeColor,5)
                                mesh.cone(x,baseY+height*.78f,rowZ,.11f,height*.34f,Rgb(.86f,.82f,.70f),5)
                                mesh.sphere(Point3(x,baseY+.06f,rowZ),.34f,Rgb(.22f,.12f,.10f),6,3)
                            }
                        }
                    }
                    // Warm warning cracks on the pit floor distinguish it from an ordinary gap.
                    for (crack in 0..4) {
                        val x=-2.6f+crack*1.3f
                        mesh.rod(Point3(x-.30f,-.43f,z1+.15f),Point3(x+.25f,-.42f,z2-.12f),.025f,Rgb(.95f,.34f,.08f),5)
                    }
                }
                "boulder" -> {
                    mesh.sphere(Point3(0f,1f,(z1+z2)*.5f),1.25f,Rgb(.43f,.48f,.49f),10,7)
                    mesh.sphere(Point3(-.38f,1.55f,(z1+z2)*.5f-.24f),.16f,Rgb(.70f,.74f,.70f),7,4)
                }
                "river-bridge","river-gap" -> {
                    buildRiver(mesh,z1,z2,obstacle.id=="river-bridge",time,state.team.players,cameraProgress)
                }
                "wind-tunnel" -> {
                    for(ribbon in 0..6){
                        val travel=(time*.52f+ribbon*.17f)%1f
                        val z=z1+(z2-z1)*travel
                        val y=.45f+ribbon*.29f+sin(time*2+ribbon)*.12f
                        val x=-2.8f+(ribbon%3)*2.8f
                        mesh.rod(Point3(x-1.2f,y,z),Point3(x+1.2f,y+.20f,z+.55f),.045f,Rgb(.48f,.87f,.91f),5)
                    }
                    for(marker in 0..3) {
                        val z=z1+(z2-z1)*(marker/3f)
                        mesh.rod(Point3(-3.35f,.12f,z),Point3(-3.35f,2.15f,z),.045f,Rgb(.62f,.72f,.70f),5)
                        mesh.rod(Point3(3.35f,.12f,z),Point3(3.35f,2.15f,z),.045f,Rgb(.62f,.72f,.70f),5)
                    }
                }
                "lava" -> buildLava(mesh,z1,z2,time)
            }
        }

        for(checkpoint in listOf(8f,19f,30f,40f,50f,64f,80f)) {
            val z=zAt(checkpoint)
            mesh.rod(Point3(-3.7f,0f,z),Point3(-3.7f,2.2f,z),.055f,Rgb(.89f,.86f,.70f),6)
            mesh.triangle(Point3(-3.65f,2.1f,z),Point3(-2.7f,1.85f,z),Point3(-3.65f,1.58f,z),Rgb(.26f,.82f,.65f))
        }
        val finishZ=zAt(99.5f)
        mesh.rod(Point3(-3.8f,0f,finishZ),Point3(-3.8f,3.2f,finishZ),.10f,Rgb(.95f,.77f,.28f),7)
        mesh.rod(Point3(3.8f,0f,finishZ),Point3(3.8f,3.2f,finishZ),.10f,Rgb(.95f,.77f,.28f),7)
        mesh.rod(Point3(-3.8f,3.2f,finishZ),Point3(3.8f,3.2f,finishZ),.12f,Rgb(.98f,.80f,.32f),7)
        for(playerIndex in state.team.players.indices) {
            val player=state.team.players[playerIndex]
            val z=(player.progress-cameraProgress)*100f*courseScale
            val x=(playerIndex-(state.team.players.size-1)/2f)*1.22f
            val jump=(player.height/12f).coerceIn(-.35f,.5f)*2.6f
            drawRunner(mesh,player,x,z,jump,playerIndex,time)
        }
        state.team.players.zipWithNext().forEachIndexed { index,pair ->
            val first=pair.first; val second=pair.second
            val z0=(first.progress-cameraProgress)*100f*courseScale
            val z1=(second.progress-cameraProgress)*100f*courseScale
            val x0=(index-(state.team.players.size-1)/2f)*1.22f
            val x1=(index+1-(state.team.players.size-1)/2f)*1.22f
            val distance=abs(first.progress-second.progress)
            val jump0=(first.height/12f).coerceIn(-.35f,.5f)*2.6f
            val jump1=(second.height/12f).coerceIn(-.35f,.5f)*2.6f
            val anchor0=Point3(x0,1.05f+jump0,z0-.47f)
            val anchor1=Point3(x1,1.05f+jump1,z1-.47f)
            val sag=.07f+(1f-state.team.ropeTension.coerceIn(0f,1f))*.26f+(distance/18f).coerceIn(0f,.18f)
            mesh.sphere(anchor0,.07f,Rgb(.78f,.72f,.54f),7,4)
            mesh.sphere(anchor1,.07f,Rgb(.78f,.72f,.54f),7,4)
            var previous=anchor0
            for(step in 1..8){
                val t=step/8f
                val current=Point3(
                    anchor0.x+(anchor1.x-anchor0.x)*t,
                    anchor0.y+(anchor1.y-anchor0.y)*t-sin(t*PI).toFloat()*sag,
                    anchor0.z+(anchor1.z-anchor0.z)*t,
                )
                mesh.rod(previous,current,.035f,Rgb(.13f,.69f,.61f),5)
                previous=current
            }
        }
    }

    private fun drawTrail(mesh: TriangleMesh,z0:Float,z1:Float) {
        mesh.plane(-3.9f,3.9f,z0,z1,-.04f,Rgb(.53f,.40f,.24f))
        mesh.plane(-3.55f,3.55f,z0,z1,-.015f,Rgb(.66f,.52f,.32f))
        val stripeStart=(kotlin.math.ceil(z0/2.8f)*2.8f).coerceAtLeast(z0)
        var z=stripeStart
        while(z<z1){
            mesh.plane(-.055f,.055f,z,min(z+.9f,z1),.002f,Rgb(.89f,.76f,.48f))
            z+=2.8f
        }
        for(side in listOf(-1f,1f)) {
            mesh.box(Point3(side*3.8f,.02f,(z0+z1)/2f),.12f,.12f,z1-z0,Rgb(.37f,.30f,.21f))
        }
    }

    private fun buildRiver(
        mesh:TriangleMesh,
        z1:Float,
        z2:Float,
        bridge:Boolean,
        time:Float,
        players:List<RopeboundPlayer>,
        cameraProgress:Float,
    ) {
        val near=min(z1,z2)-1.2f; val far=max(z1,z2)+1.2f
        val nx=38; val nz=8
        for(ix in 0 until nx) for(iz in 0 until nz) {
            val x0=-22f+44f*ix/nx; val x1=-22f+44f*(ix+1)/nx
            val a=near+(far-near)*iz/nz; val b=near+(far-near)*(iz+1)/nz
            fun level(x:Float,z:Float)= -.12f+
                sin(time*1.7f+x*.22f+z*1.2f)*.13f+
                sin(time*2.25f-x*.31f+z*.78f)*.065f
            val p0=Point3(x0,level(x0,a),a); val p1=Point3(x0,level(x0,b),b)
            val p2=Point3(x1,level(x1,b),b); val p3=Point3(x1,level(x1,a),a)
            val tint= .82f+ .18f*sin(ix*.8f+iz*.7f+time*1.6f)
            val color=if((ix+iz)%5==0) Rgb(.07f*tint,.50f*tint,.63f*tint) else Rgb(.035f*tint,.37f*tint,.53f*tint)
            mesh.quad(p0,p1,p2,p3,color)
        }
        // Foam streaks move downstream so the river reads as flowing water, not a static blue floor.
        for(foam in 0..8) {
            val x=-17f+(foam%3)*16f+sin(time*.9f+foam)*.35f
            val travel=(time*.22f+foam*.137f)%1f
            val z=near+(far-near)*travel
            val y=-.12f+sin(time*1.7f+x*.22f+z*1.2f)*.13f+
                sin(time*2.25f-x*.31f+z*.78f)*.065f+.018f
            mesh.rod(Point3(x-.65f,y,z),Point3(x+.62f,y+.012f,z+.42f),.018f,Rgb(.65f,.91f,.89f),5)
        }
        for((bankIndex,bankZ) in listOf(near,far).withIndex()) {
            val landSign=if(bankIndex==0)-1f else 1f
            val grass=if(bankIndex==0)Rgb(.43f,.66f,.25f) else Rgb(.37f,.60f,.23f)
            mesh.plane(-23f,23f,bankZ+landSign*2.3f,bankZ+landSign*.28f,.035f,grass)
            // Angular shoreline stones form a broken, layered cliff edge like the reference.
            for(rock in 0..20) {
                val x=-21f+rock*2.05f+sin(rock*2.17f+bankIndex)*.38f
                val stagger=sin(rock*1.61f+bankIndex*2f)*.28f
                val rz=bankZ+landSign*(.10f+stagger)
                val scale=.68f+(rock%4)*.16f
                val color=when(rock%4) {
                    0 -> Rgb(.54f,.55f,.48f)
                    1 -> Rgb(.65f,.61f,.50f)
                    2 -> Rgb(.47f,.51f,.48f)
                    else -> Rgb(.59f,.57f,.49f)
                }
                mesh.facetedRock(Point3(x,-.02f,rz),scale,.35f+(rock%3)*.08f,.48f+scale*.18f,color,rock+bankIndex*31)
                if(rock%2==0) {
                    mesh.facetedRock(
                        Point3(x+.46f,-.18f,rz+landSign*.24f),
                        scale*.48f,.22f,scale*.42f,
                        color.shade(.92f),rock+bankIndex*57+9,
                    )
                }
            }
            for(reed in 0..11) {
                val side=if(reed%2==0)-1f else 1f
                val x=side*(4.8f+(reed/2)*1.05f)
                val z=bankZ+side*.28f
                val sway=sin(time*2.2f+reed*.8f)*.09f
                val height=.42f+(reed%4)*.12f
                val green=if(reed%3==0)Rgb(.38f,.63f,.28f) else Rgb(.24f,.53f,.27f)
                mesh.rod(Point3(x,0f,z),Point3(x+sway,height,z+.04f),.025f,green,5)
                mesh.rod(Point3(x,0f,z),Point3(x-sway*.7f,height*.8f,z-.03f),.022f,green.shade(.86f),5)
            }
            for(foam in 0..3){
                val x=-1.2f+foam*.8f+sin(time*2+foam)*.12f
                mesh.rod(Point3(x,-.06f,bankZ-.14f),Point3(x+.35f,-.05f,bankZ+.14f),.018f,Rgb(.68f,.92f,.87f),5)
            }
        }
        if(bridge) {
            val width=5.8f; val length=far-near; val planks=10
            fun footstepsAt(progress:Float,radius:Float):Float {
                var flex=0f
                players.forEachIndexed { playerIndex,player ->
                    val influence=(1f-abs(player.progress-progress)/radius).coerceIn(0f,1f)
                    val footfall=if(player.velocity>.12f) {
                        sin(time*(7.5f+player.velocity.coerceAtMost(3f))+playerIndex*PI.toFloat())*.075f
                    } else 0f
                    val landing=if(player.jumping) .035f else 0f
                    flex+=influence*(footfall+landing)
                }
                return flex.coerceIn(-.10f,.10f)
            }
            for(index in 0 until planks) {
                val t=(index+.5f)/planks
                val z=near+length*t
                val arch=-sin(t*PI).toFloat()*.28f
                val plankProgress=(((z-4f)/.80f)+cameraProgress*100f)/100f
                val stepFlex=footstepsAt(plankProgress,2.8f)
                val wobble=sin(time*2.1f+index*.72f)*.022f+stepFlex
                val roll=sin(time*2.8f+index*.58f)*.025f+stepFlex*.7f
                val wood=if(index%2==0)Rgb(.63f,.38f,.20f) else Rgb(.75f,.49f,.28f)
                val plankY=.12f+arch+wobble
                mesh.bridgePlank(Point3(0f,plankY,z),width,.20f,length/planks*.90f,roll,wood)
                // Raised grain marks and little iron nails make each warm timber plank legible.
                for(grain in 0..2) {
                    val zOffset=(grain-1)*length/planks*.20f
                    val slope=sin(roll)*2.88f
                    val grainColor=if(grain==1)wood.shade(.72f) else wood.shade(1.18f)
                    mesh.rod(
                        Point3(-2.45f,plankY+.105f-slope,z+zOffset),
                        Point3(2.45f,plankY+.105f+slope,z+zOffset),
                        .012f,grainColor,5,
                    )
                }
                for(side in listOf(-1f,1f)) {
                    mesh.sphere(Point3(side*2.62f,plankY+.108f+side*roll*.25f,z-length/planks*.31f),.035f,Rgb(.80f,.69f,.48f),6,4)
                    mesh.sphere(Point3(side*2.62f,plankY+.108f+side*roll*.25f,z+length/planks*.31f),.035f,Rgb(.80f,.69f,.48f),6,4)
                }
                mesh.rod(Point3(-2.4f,plankY+.11f+roll*.24f,z),Point3(2.4f,plankY+.11f-roll*.24f,z),.025f,Rgb(.91f,.69f,.43f),5)
                for(side in listOf(-1f,1f)) {
                    val railY=.72f+arch+wobble*.8f
                    mesh.rod(Point3(side*2.7f,plankY,z),Point3(side*2.7f,railY,z),.045f,Rgb(.45f,.27f,.16f),5)
                }
            }
            // Thick timber posts with rope turns anchor the handlines at both river banks.
            for(endZ in listOf(near,far)) for(side in listOf(-1f,1f)) {
                val postX=side*2.75f
                mesh.box(Point3(postX,.67f,endZ),.28f,1.34f,.30f,Rgb(.39f,.21f,.105f))
                mesh.box(Point3(postX,.69f,endZ+.012f),.19f,1.02f,.018f,Rgb(.52f,.30f,.15f))
                mesh.box(Point3(postX,.1f,endZ),.39f,.15f,.39f,Rgb(.43f,.27f,.14f))
                mesh.box(Point3(postX,1.38f,endZ),.34f,.12f,.36f,Rgb(.58f,.34f,.17f))
                for(turn in 0..2) {
                    val y=.97f+turn*.075f
                    mesh.rod(Point3(postX-.17f,y,endZ-.17f),Point3(postX+.17f,y,endZ-.17f),.035f,Rgb(.76f,.58f,.34f),6)
                    mesh.rod(Point3(postX-.17f,y+.018f,endZ+.17f),Point3(postX+.17f,y+.018f,endZ+.17f),.028f,Rgb(.85f,.68f,.42f),6)
                }
            }
            for(side in listOf(-1f,1f)) {
                var previous=Point3(side*2.7f,.78f,near)
                for(step in 1..24) {
                    val t=step/24f
                    val plankProgress=(((near+length*t-4f)/.80f)+cameraProgress*100f)/100f
                    val playerRipple=footstepsAt(plankProgress,3.2f)*.48f
                    val curve=-sin(t*PI).toFloat()*.28f
                    val current=Point3(side*2.7f,.78f+curve+playerRipple,near+length*t)
                    mesh.rod(previous,current,.055f,Rgb(.47f,.29f,.17f),6)
                    val deckY=.22f+curve+playerRipple
                    mesh.rod(Point3(side*2.7f,.22f+curve,previous.z),Point3(side*2.7f,deckY,current.z),.024f,Rgb(.68f,.46f,.28f),5)
                    previous=current
                }
            }
        }
    }

    private fun buildLava(mesh:TriangleMesh,z1:Float,z2:Float,time:Float) {
        val near=min(z1,z2); val far=max(z1,z2)
        val pulse=.5f+.5f*sin(time*3.2f)
        mesh.plane(-3.8f,3.8f,near,far,-.30f,Rgb(.18f,.09f,.085f))
        mesh.plane(-3.48f,3.48f,near+.08f,far-.08f,-.12f,Rgb(.55f,.095f,.035f))
        mesh.plane(-3.20f,3.20f,near+.18f,far-.18f,-.075f,Rgb(1f,.20f+.08f*pulse,.025f))
        for(i in 0..10) {
            val x=-3.05f+i*.61f
            val z=near+(far-near)*((i*.29f+time*.11f)%1f)
            val y=-.015f+sin(time*3.4f+i*.8f)*.025f
            mesh.rod(Point3(x-.31f,y,z),Point3(x+.36f,y+.018f,z+.35f),.045f,Rgb(1f,.73f+.18f*pulse,.17f),5)
        }
        for(flame in 0..8) {
            val x=-3.1f+flame*.78f
            val phase=time*4f+flame*1.7f
            val y=.22f+(.45f+.5f*sin(phase))*.78f
            val z=near+(far-near)*(flame%4)/4f+.2f
            mesh.cone(x,-.035f,z,.36f,y,Rgb(1f,.24f,.035f),6)
            mesh.cone(x,-.025f,z-.025f,.20f,y*.70f,Rgb(1f,.72f,.12f),5)
            mesh.cone(x,-.015f,z-.04f,.095f,y*.43f,Rgb(1f,.94f,.48f),5)
        }
        for(ember in 0..16) {
            val phase=(time*1.1f+ember*.173f)%1f
            val x=-3f+(ember*1.37f)%6f+sin(time*2f+ember)*.12f
            val y=.25f+phase*(1.6f+(ember%4)*.25f)
            val z=near+(far-near)*((ember*.31f)%1f)
            mesh.sphere(Point3(x,y,z),.045f+(ember%3)*.012f,Rgb(1f,.63f,.13f),6,4)
        }
    }

    private fun drawRunner(mesh:TriangleMesh,player:RopeboundPlayer,x:Float,z:Float,jump:Float,index:Int,time:Float) {
        val palette=listOf(Rgb(.48f,.34f,.86f),Rgb(.86f,.31f,.29f),Rgb(.17f,.67f,.58f),Rgb(.93f,.56f,.16f),Rgb(.27f,.51f,.86f))
        val suit=palette[index%palette.size]
        val hairPalette=listOf(Rgb(.10f,.075f,.065f),Rgb(.28f,.14f,.075f),Rgb(.72f,.40f,.15f),Rgb(.12f,.16f,.20f))
        val skinPalette=listOf(Rgb(.96f,.66f,.45f),Rgb(.76f,.43f,.28f),Rgb(.98f,.76f,.57f),Rgb(.53f,.31f,.23f))
        val styleSeed=player.id.hashCode() and Int.MAX_VALUE
        val hair=hairPalette[styleSeed%hairPalette.size]
        val skin=skinPalette[(styleSeed/3)%skinPalette.size]
        val pack=palette[(index+2)%palette.size].shade(.84f)
        val running=player.velocity>.35f && player.grounded && !player.bracing
        val phase=time*9f+index*.7f
        val stride=if(running) sin(phase)*.28f else 0f
        val idleBreath=if(running) abs(cos(phase))*.025f else sin(time*2.2f+index)*.018f
        val base=jump+idleBreath
        val kneeBend=if(player.jumping) .15f else 0f
        mesh.ellipsoid(Point3(x,.012f,z),.52f,.025f,.34f,Rgb(.11f,.22f,.16f),8,4)

        // Compact expedition backpack, bright pocket and shoulder straps.
        mesh.box(Point3(x,1.12f+base,z-.34f),.47f,.57f,.24f,pack)
        mesh.box(Point3(x,1.06f+base,z-.48f),.29f,.21f,.035f,pack.shade(1.23f))
        mesh.box(Point3(x,1.31f+base,z-.48f),.28f,.10f,.035f,suit.shade(1.2f))
        mesh.rod(Point3(x-.22f,1.43f+base,z-.22f),Point3(x-.17f,.70f+base,z-.20f),.034f,Rgb(.91f,.73f,.39f),5)
        mesh.rod(Point3(x+.22f,1.43f+base,z-.22f),Point3(x+.17f,.70f+base,z-.20f),.034f,Rgb(.91f,.73f,.39f),5)
        mesh.sphere(Point3(x,1.08f+base,z-.57f),.075f,Rgb(.90f,.80f,.59f),7,4)

        // Slight forward lean, soft torso bounce and layered shirt/shorts.
        val lean=if(running) .035f else 0f
        mesh.box(Point3(x,.95f+base,z-lean),.58f,.76f,.40f,suit.shade(.86f))
        mesh.ellipsoid(Point3(x,1.04f+base,z+.015f-lean),.32f,.43f,.24f,suit,10,7)
        mesh.ellipsoid(Point3(x,1.25f+base,z+.205f-lean),.205f,.22f,.045f,suit.shade(1.22f),8,5)
        mesh.box(Point3(x,.49f+jump,z),.48f,.18f,.43f,Rgb(.16f,.22f,.27f))
        mesh.box(Point3(x,.98f+base,z+.228f-lean),.16f,.49f,.027f,Rgb(.97f,.88f,.69f))
        mesh.rod(Point3(x-.24f,.47f+jump,z+.23f),Point3(x+.24f,.47f+jump,z+.23f),.026f,Rgb(.91f,.71f,.36f),5)

        // Neck, head and a full occipital hair shell so the hairstyle reads from the follow camera.
        mesh.rod(Point3(x,1.34f+base,z-lean),Point3(x,1.51f+base,z-lean),.12f,skin,8)
        mesh.ellipsoid(Point3(x,1.70f+base,z-lean),.285f,.34f,.265f,skin,14,10)
        mesh.ellipsoid(Point3(x,1.91f+base,z-.035f-lean),.295f,.15f,.285f,hair,16,10,true)
        // The back cap covers the rear of the skull rather than leaving a skin-colored bald patch.
        mesh.ellipsoid(Point3(x,1.70f+base,z-.125f-lean),.272f,.285f,.195f,hair.shade(.91f),16,11,true)
        mesh.ellipsoid(Point3(x-.215f,1.75f+base,z-.075f-lean),.105f,.245f,.17f,hair,12,8,true)
        mesh.ellipsoid(Point3(x+.215f,1.75f+base,z-.075f-lean),.105f,.245f,.17f,hair.shade(.88f),12,8,true)
        // Raised, tapered locks break up the rounded cap and stay visible in the rear camera.
        for (lock in -1..1) {
            val lockX=x+lock*.105f
            val lockZ=z-.30f-lean-(if(lock==0) .025f else 0f)
            mesh.ellipsoid(
                Point3(lockX,1.68f+base,lockZ),
                .047f,.195f,.052f,
                hair.shade(if(lock==0) 1.10f else .94f),
                10,8,true,
            )
            mesh.rod(
                Point3(lockX-.012f,1.83f+base,lockZ-.045f),
                Point3(lockX-.008f,1.58f+base,lockZ-.045f),
                .014f,
                hair.shade(1.22f),
                6,
            )
        }
        // Three softly raised locks add visible texture from behind without looking like spikes.
        for (lock in -1..1) {
            val lockX = x + lock * .105f
            val lockBack = if (lock == 0) .318f else .296f
            val lockColor = hair.shade(if (lock == 0) 1.12f else .98f + lock * .035f)
            mesh.rod(
                Point3(lockX * .72f + x * .28f,1.90f+base,z-lockBack*.76f-lean),
                Point3(lockX,1.70f+base,z-lockBack-lean),
                .022f,
                lockColor,
                7,
            )
            mesh.rod(
                Point3(lockX,1.70f+base,z-lockBack-lean),
                Point3(x + lockX - x,1.49f+base,z-lockBack*.82f-lean),
                .018f,
                hair.shade(.82f),
                7,
            )
        }
        // Rounded outer ears with a visible inner fold make the side silhouette read as a face.
        for (side in listOf(-1f, 1f)) {
            val earX = x + side * .268f
            mesh.ellipsoid(Point3(earX,1.68f+base,z-.005f-lean),.075f,.115f,.062f,skin.shade(.92f),9,6)
            mesh.ellipsoid(Point3(earX+side*.012f,1.68f+base,z+.052f-lean),.027f,.055f,.018f,skin.shade(.72f),7,5)
        }
        // Large layered eyes, colored irises, pupils and catchlights stay readable at phone scale.
        val iris = if (styleSeed % 3 == 0) Rgb(.22f,.49f,.56f) else Rgb(.35f,.22f,.13f)
        for (side in listOf(-1f, 1f)) {
            val eyeX = x + side * .105f
            mesh.ellipsoid(Point3(eyeX,1.735f+base,z+.226f-lean),.070f,.052f,.035f,Rgb(.99f,.96f,.86f),10,7)
            mesh.sphere(Point3(eyeX,1.735f+base,z+.257f-lean),.032f,iris,8,6)
            mesh.sphere(Point3(eyeX,1.735f+base,z+.282f-lean),.018f,Rgb(.035f,.045f,.052f),7,5)
            mesh.sphere(Point3(eyeX-.008f,1.751f+base,z+.297f-lean),.008f,Rgb(1f,1f,.96f),6,4)
            mesh.rod(
                Point3(eyeX-.064f,1.805f+base,z+.239f-lean),
                Point3(eyeX+.064f,1.814f+base,z+.239f-lean),
                .024f,
                hair.shade(.9f),
                6,
            )
        }
        // Nose bridge and rounded tip project forward; a small curved smile sits below it.
        mesh.ellipsoid(Point3(x,1.674f+base,z+.275f-lean),.052f,.078f,.075f,skin.shade(.94f),9,6)
        mesh.sphere(Point3(x,1.642f+base,z+.327f-lean),.045f,skin.shade(1.02f),8,5)
        mesh.rod(Point3(x-.078f,1.565f+base,z+.278f-lean),Point3(x,1.548f+base,z+.294f-lean),.014f,Rgb(.43f,.15f,.15f),5)
        mesh.rod(Point3(x,1.548f+base,z+.294f-lean),Point3(x+.078f,1.565f+base,z+.278f-lean),.014f,Rgb(.43f,.15f,.15f),5)
        // Give the squad different readable cuts from the rear: ponytail, cropped layers,
        // shoulder-length waves, and a short braid. The tie uses a warm accent against the hair.
        when (styleSeed % 4) {
            0 -> {
                val sway = stride * .40f
                mesh.sphere(Point3(x-.12f,1.92f+base,z-.265f-lean),.105f,hair,8,5)
                mesh.sphere(Point3(x-.12f,1.91f+base,z-.345f-lean),.045f,Rgb(.91f,.70f,.31f),7,4)
                mesh.rod(Point3(x-.12f,1.89f+base,z-.34f-lean),Point3(x-.23f+sway,1.68f+base,z-.43f-lean),.075f,hair,7)
                mesh.rod(Point3(x-.23f+sway,1.68f+base,z-.43f-lean),Point3(x-.20f+sway*1.4f,1.48f+base,z-.40f-lean),.052f,hair.shade(.92f),7)
                mesh.sphere(Point3(x-.20f+sway*1.4f,1.48f+base,z-.40f-lean),.052f,hair.shade(1.08f),7,4)
            }
            1 -> {
                mesh.ellipsoid(Point3(x,1.91f+base,z-.16f-lean),.235f,.105f,.19f,hair.shade(1.04f),10,6)
                for (tuft in -1..1) {
                    mesh.cone(x+tuft*.105f,1.84f+base,z-.10f-lean,.075f,.12f,hair.shade(if(tuft==0) 1.12f else .92f),7)
                }
            }
            2 -> {
                for (side in listOf(-1f,1f)) {
                    mesh.ellipsoid(Point3(x+side*.225f,1.58f+base,z-.145f-lean),.092f,.20f,.115f,hair.shade(if(side<0f) 1.08f else .88f),9,6)
                    mesh.ellipsoid(Point3(x+side*.245f,1.48f+base,z-.17f-lean),.07f,.11f,.09f,hair,8,5)
                }
            }
            else -> {
                mesh.sphere(Point3(x,1.91f+base,z-.27f-lean),.13f,hair.shade(1.08f),8,5)
                var braidPoint=Point3(x,1.83f+base,z-.32f-lean)
                for (bead in 0..3) {
                    val next=Point3(x+if(bead%2==0) -.035f else .035f,1.72f+base-bead*.085f,z-.37f-lean)
                    mesh.rod(braidPoint,next,.047f,hair.shade(if(bead%2==0) 1.08f else .82f),7)
                    mesh.sphere(next,.047f,hair.shade(if(bead%2==0) .96f else 1.10f),7,5)
                    braidPoint=next
                }
                mesh.sphere(Point3(x,1.37f+base,z-.36f-lean),.055f,Rgb(.91f,.70f,.31f),7,4)
            }
        }

        // Alternating arm/leg stride follows the forward axis; elbows and knees bend naturally.
        val leftSwing=if(running) stride else if(player.bracing) -.06f else 0f
        val rightSwing=if(running) -stride else if(player.bracing) -.06f else 0f
        val shoulderY=1.22f+base
        val leftShoulder=Point3(x-.31f,shoulderY,z-lean)
        val rightShoulder=Point3(x+.31f,shoulderY,z-lean)
        val leftElbow=Point3(x-.44f,1.00f+base+abs(leftSwing)*.20f,z-leftSwing*.72f)
        val rightElbow=Point3(x+.44f,1.00f+base+abs(rightSwing)*.20f,z-rightSwing*.72f)
        val leftHand=Point3(x-.47f, .77f+base+abs(leftSwing)*.18f, z-leftSwing*1.25f)
        val rightHand=Point3(x+.47f, .77f+base+abs(rightSwing)*.18f, z-rightSwing*1.25f)
        mesh.rod(leftShoulder,leftElbow,.105f,suit,7)
        mesh.rod(leftElbow,leftHand,.078f,suit.shade(.92f),7)
        mesh.rod(rightShoulder,rightElbow,.105f,suit,7)
        mesh.rod(rightElbow,rightHand,.078f,suit.shade(.92f),7)
        mesh.sphere(leftElbow,.095f,suit.shade(.84f),7,5)
        mesh.sphere(rightElbow,.095f,suit.shade(.84f),7,5)
        mesh.sphere(leftHand,.09f,skin,7,5)
        mesh.sphere(rightHand,.09f,skin,7,5)
        val leftKnee=Point3(x-.17f,.29f+jump,z+stride-kneeBend*.45f)
        val rightKnee=Point3(x+.17f,.29f+jump,z-stride-kneeBend*.45f)
        val leftFoot=Point3(x-.18f,.10f+jump,z+stride*1.65f-kneeBend)
        val rightFoot=Point3(x+.18f,.10f+jump,z-stride*1.65f-kneeBend)
        mesh.rod(Point3(x-.17f,.53f+jump,z),leftKnee,.105f,Rgb(.18f,.23f,.28f),7)
        mesh.rod(leftKnee,leftFoot,.085f,Rgb(.19f,.24f,.28f),7)
        mesh.rod(Point3(x+.17f,.53f+jump,z),rightKnee,.105f,Rgb(.18f,.23f,.28f),7)
        mesh.rod(rightKnee,rightFoot,.085f,Rgb(.19f,.24f,.28f),7)
        mesh.sphere(leftKnee,.095f,Rgb(.24f,.30f,.34f),7,5)
        mesh.sphere(rightKnee,.095f,Rgb(.24f,.30f,.34f),7,5)
        mesh.ellipsoid(Point3(leftFoot.x,.075f+jump,leftFoot.z+.11f),.15f,.09f,.23f,suit.shade(.82f),8,5)
        mesh.ellipsoid(Point3(rightFoot.x,.075f+jump,rightFoot.z+.11f),.15f,.09f,.23f,suit.shade(.82f),8,5)
        mesh.box(Point3(leftFoot.x,.025f+jump,leftFoot.z+.12f),.20f,.045f,.29f,Rgb(.90f,.84f,.65f))
        mesh.box(Point3(rightFoot.x,.025f+jump,rightFoot.z+.12f),.20f,.045f,.29f,Rgb(.90f,.84f,.65f))
    }

    private fun linkProgram(vertexSource:String,fragmentSource:String):Int {
        fun shader(type:Int,source:String):Int {
            val shader=GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader,source); GLES20.glCompileShader(shader)
            val status=IntArray(1); GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,status,0)
            if(status[0]==0) throw IllegalStateException("3D game shader failed: ${GLES20.glGetShaderInfoLog(shader)}")
            return shader
        }
        val vertex=shader(GLES20.GL_VERTEX_SHADER,vertexSource)
        val fragment=shader(GLES20.GL_FRAGMENT_SHADER,fragmentSource)
        val linked=GLES20.glCreateProgram()
        GLES20.glAttachShader(linked,vertex); GLES20.glAttachShader(linked,fragment); GLES20.glLinkProgram(linked)
        val status=IntArray(1); GLES20.glGetProgramiv(linked,GLES20.GL_LINK_STATUS,status,0)
        if(status[0]==0) throw IllegalStateException("3D game shader link failed: ${GLES20.glGetProgramInfoLog(linked)}")
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        return linked
    }

    companion object {
        private val IDENTITY=FloatArray(16).apply { Matrix.setIdentityM(this,0) }
    }
}
