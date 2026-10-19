package com.squadvoice.app.game

import android.content.Context
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun Ropebound3DScene(
    team: RopeboundTeam,
    viewerIdentity: String,
    obstacles: List<RopeboundObstacle>,
    cameraBlend: Float,
    cameraOrbitYaw: Float = 0f,
    cameraOrbitPitch: Float = 0f,
    liveControlFeed: RopeboundLiveControlFeed? = null,
    previewMode: Boolean = false,
    onCameraOrbitDrag: ((Float, Float) -> Unit)? = null,
    showPlayerNameplates: Boolean = true,
    modifier: Modifier = Modifier,
    onReady: () -> Unit,
) {
    val context = LocalContext.current
    val view = remember(context) { Ropebound3DView(context) }
    var internalYaw by remember { mutableFloatStateOf(0f) }
    var internalPitch by remember { mutableFloatStateOf(0f) }

    val activeYaw = if (onCameraOrbitDrag != null) cameraOrbitYaw else internalYaw
    val activePitch = if (onCameraOrbitDrag != null) cameraOrbitPitch else internalPitch

    SideEffect {
        view.submit(
            Ropebound3DState(
                team = team,
                viewerIdentity = viewerIdentity,
                obstacles = obstacles,
                cameraBlend = cameraBlend,
                orbitYaw = activeYaw,
                orbitPitch = activePitch,
                liveControlFeed = liveControlFeed,
                previewMode = previewMode,
            ),
        )
    }

    DisposableEffect(view) {
        view.onResume()
        onDispose { view.onPause() }
    }

    val dragModifier = modifier
        .fillMaxSize()
        .pointerInput(onCameraOrbitDrag) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                val dyaw = -dragAmount.x * 0.0055f
                val dpitch = dragAmount.y * 0.0042f
                if (onCameraOrbitDrag != null) {
                    onCameraOrbitDrag(dyaw, dpitch)
                } else {
                    internalYaw = (internalYaw + dyaw) % (2f * PI.toFloat())
                    internalPitch = (internalPitch + dpitch).coerceIn(-0.25f, 0.58f)
                }
            }
        }

    AndroidView(factory = { view }, modifier = dragModifier)
    LaunchedEffect(view) {
        var waitedMs = 0
        while (view.telemetry.framesRendered == 0L && waitedMs < 220) {
            delay(16)
            waitedMs += 16
        }
        onReady()
    }
    if (showPlayerNameplates) {
        RopeboundPlayerNameplates(
            team = team,
            viewerIdentity = viewerIdentity,
            cameraBlend = cameraBlend,
            orbitYaw = activeYaw,
            orbitPitch = activePitch,
            telemetry = view.telemetry,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun RopeboundPlayerNameplates(
    team: RopeboundTeam,
    viewerIdentity: String,
    cameraBlend: Float,
    orbitYaw: Float,
    orbitPitch: Float,
    telemetry: RopeboundLiveRenderTelemetry,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val namePaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = with(density) { 9.5.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    val pingPaint = remember(density) {
        AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = with(density) { 8.sp.toPx() }
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
    }
    val projection = remember { FloatArray(16) }
    val camera = remember { FloatArray(16) }
    val viewProjection = remember { FloatArray(16) }
    val clip = remember { FloatArray(4) }
    val worldPt = remember { floatArrayOf(0f, 0f, 0f, 1f) }
    var frameTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos ->
                frameTick = nanos
            }
        }
    }
    Canvas(modifier = modifier) {
        if (frameTick < 0L) return@Canvas
        val hasLiveTelemetry = telemetry.cameraProgress >= 0f && telemetry.playerCount == team.players.size
        val localIndex = team.players.indexOfFirst { it.id == viewerIdentity }
        val local = if (localIndex >= 0) team.players[localIndex] else team.players.firstOrNull()
        val cameraProgress = if (hasLiveTelemetry) telemetry.cameraProgress else (local?.progress ?: team.progress)
        val focusX = if (hasLiveTelemetry) {
            telemetry.focusX
        } else if (local != null && localIndex >= 0) {
            (localIndex - (team.players.size - 1) / 2f) * 1.22f + local.lateralOffset.coerceIn(-1.25f, 1.25f)
        } else 0f
        val focusY = if (hasLiveTelemetry) {
            telemetry.focusY
        } else {
            ((local?.height ?: 0f) / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
        }

        Matrix.perspectiveM(projection, 0, 56f, size.width / size.height.coerceAtLeast(1f), 0.1f, 190f)
        computeCameraLookAt(camera, cameraBlend, orbitYaw, orbitPitch, focusX, focusY)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, camera, 0)

        team.players.forEachIndexed { index, player ->
            val pProg = if (hasLiveTelemetry && index < telemetry.playerProgress.size) telemetry.playerProgress[index] else player.progress
            val pLat = if (hasLiveTelemetry && index < telemetry.playerLat.size) telemetry.playerLat[index] else player.lateralOffset
            val pHeight = if (hasLiveTelemetry && index < telemetry.playerHeight.size) telemetry.playerHeight[index] else player.height
            val z = (pProg - cameraProgress) * 100f * COURSE_SCALE
            val x = (index - (team.players.size - 1) / 2f) * 1.22f + pLat.coerceIn(-1.25f, 1.25f)
            val y = 2.42f + (pHeight / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
            worldPt[0] = x; worldPt[1] = y; worldPt[2] = z; worldPt[3] = 1f
            Matrix.multiplyMV(clip, 0, viewProjection, 0, worldPt, 0)
            if (clip[3] <= 0.2f) return@forEachIndexed
            val ndcX = clip[0] / clip[3]
            val ndcY = clip[1] / clip[3]
            if (ndcX !in -1.15f..1.15f || ndcY !in -1.15f..1.15f) return@forEachIndexed
            val sx = (ndcX + 1f) * 0.5f * size.width
            val sy = (1f - ndcY) * 0.5f * size.height
            val ping = player.pingMs?.let { "${it}ms" } ?: "34ms"
            pingPaint.color = when {
                player.pingMs == null || player.pingMs < 150 -> android.graphics.Color.rgb(119, 232, 189)
                player.pingMs < 300 -> android.graphics.Color.rgb(255, 209, 102)
                else -> android.graphics.Color.rgb(255, 133, 133)
            }
            val displayName = player.name.ifBlank { "Player" }.take(12)
            val pad = 7.dp.toPx()
            val gap = 6.dp.toPx()
            val width = namePaint.measureText(displayName) + pingPaint.measureText(ping) + pad * 2 + gap
            val height = 18.dp.toPx()
            val left = (sx - width / 2f).coerceIn(2.dp.toPx(), size.width - width - 2.dp.toPx())
            val top = sy - 16.dp.toPx()
            if (top < 0f || top + height > size.height) return@forEachIndexed
            val isLocal = player.id == viewerIdentity
            drawRoundRect(
                if (isLocal) Color(0xEE0D2833) else Color(0xDC102028),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
            )
            if (isLocal) {
                drawRoundRect(
                    Color(0xFF48F0D8),
                    topLeft = androidx.compose.ui.geometry.Offset(left, top),
                    size = androidx.compose.ui.geometry.Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.1.dp.toPx()),
                )
            }
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
    val orbitYaw: Float = 0f,
    val orbitPitch: Float = 0f,
    val liveControlFeed: RopeboundLiveControlFeed? = null,
    val previewMode: Boolean = false,
)

private class Ropebound3DView(context: Context) : GLSurfaceView(context) {
    val telemetry = RopeboundLiveRenderTelemetry()
    private val gameRenderer = Ropebound3DRenderer(telemetry)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setPreserveEGLContextOnPause(true)
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun submit(state: Ropebound3DState) = gameRenderer.submit(state)
}

private class Ropebound3DRenderer(
    private val telemetry: RopeboundLiveRenderTelemetry,
) : GLSurfaceView.Renderer {
    @Volatile
    private var scene = Ropebound3DState(
        RopeboundTeam(0, 0f, 0f, false, 3, emptyList(), emptyList()),
        "",
        emptyList(),
        0f,
    )
    private val reusableMesh = TriangleMesh()
    private var program = 0
    private var positionHandle = 0
    private var normalHandle = 0
    private var colorHandle = 0
    private var mvpHandle = 0
    private var eyePosHandle = 0
    private var width = 1
    private var height = 1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val epoch = System.nanoTime()
    private var lastFrameAt = 0f
    private var smoothProgress = -1f
    private var smoothFocusX = 0f
    private var smoothFocusY = 0f
    private var predLocalProgress = -1f
    private var predLocalLat = 0f
    private var predForwardVel = 0f
    private var predLateralVel = 0f
    private var vertexBuffer: FloatBuffer? = null
    private val runnerTrackers = HashMap<String, RunnerMotionTracker>()
    private val cutStarts = FloatArray(12)
    private val cutEnds = FloatArray(12)
    private val riverStarts = FloatArray(8)
    private val riverEnds = FloatArray(8)
    private val anchorPoints = arrayOfNulls<Point3>(12)

    fun submit(next: Ropebound3DState) {
        scene = next
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.36f, 0.70f, 0.92f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        val vertexShader = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vWorldPos = aPosition;
                vNormal = aNormal;
                vColor = aColor;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform vec3 uEyePos;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            void main() {
                float nLen = length(vNormal);
                vec3 color = vColor;
                float dist = length(vWorldPos - uEyePos);

                if (nLen > 0.2) {
                    vec3 N = vNormal / nLen;
                    vec3 V = normalize(uEyePos - vWorldPos);
                    if (dot(N, V) < -0.15) {
                        N = -N;
                    }
                    vec3 L = normalize(vec3(0.42, 0.82, -0.36));
                    vec3 H = normalize(L + V);

                    float ndl = dot(N, L);
                    float diffuse = clamp(ndl * 0.52 + 0.48, 0.0, 1.0);
                    diffuse = diffuse * diffuse;

                    vec3 skyAmb = vec3(0.76, 0.88, 1.0);
                    vec3 groundAmb = vec3(0.42, 0.50, 0.38);
                    vec3 hemi = mix(groundAmb, skyAmb, clamp(N.y * 0.5 + 0.5, 0.0, 1.0));

                    float specBase = max(dot(N, H), 0.0);
                    float spec = pow(specBase, 26.0) * 0.24;
                    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.18 * max(N.y + 0.3, 0.0);

                    vec3 sunColor = vec3(1.04, 0.99, 0.92);
                    color = vColor * (hemi * 0.46 + sunColor * diffuse * 0.72) + vec3(spec + rim * 0.75);
                }

                if (vWorldPos.z < 138.0) {
                    float fogFactor = clamp((dist - 42.0) / 96.0, 0.0, 0.68);
                    vec3 fogColor = mix(vec3(0.58, 0.82, 0.95), vec3(0.42, 0.72, 0.90), clamp(vWorldPos.y / 28.0, 0.0, 1.0));
                    color = mix(color, fogColor, fogFactor * fogFactor);
                }

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        program = linkProgram(vertexShader, fragmentShader)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        normalHandle = GLES20.glGetAttribLocation(program, "aNormal")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpHandle = GLES20.glGetUniformLocation(program, "uMvp")
        eyePosHandle = GLES20.glGetUniformLocation(program, "uEyePos")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(projection, 0, 56f, this.width.toFloat() / this.height, 0.1f, 190f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = (System.nanoTime() - epoch) / 1_000_000_000f
        val dt = if (lastFrameAt == 0f) 1f / 60f else (now - lastFrameAt).coerceIn(0.004f, 0.045f)
        if (now - lastFrameAt < 1f / 62f) return
        lastFrameAt = now

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val current = scene
        val feed = current.liveControlFeed
        val liveMove = feed?.move ?: 0f
        val liveSteer = feed?.steer ?: 0f
        val liveBracing = feed?.bracing ?: false
        val jumpStartedMs = feed?.jumpStartedAtMs ?: 0L
        val jumpAgeSec = if (jumpStartedMs == 0L) Float.POSITIVE_INFINITY else (SystemClock.elapsedRealtime() - jumpStartedMs) / 1_000f
        val inLocalJump = jumpAgeSec in 0f..1.28f
        val localJumpHeight = if (inLocalJump) (16f * jumpAgeSec - 12.5f * jumpAgeSec * jumpAgeSec).coerceAtLeast(0f) else 0f

        val localIndex = current.team.players.indexOfFirst { it.id == current.viewerIdentity }
        val local = if (localIndex >= 0) current.team.players[localIndex] else current.team.players.firstOrNull()

        if (local != null && !current.previewMode && current.viewerIdentity.isNotBlank()) {
            if (predLocalProgress < 0f || abs(local.progress - predLocalProgress) > 0.055f) {
                predLocalProgress = local.progress
                predLocalLat = local.lateralOffset
                predForwardVel = 0f
                predLateralVel = 0f
            } else {
                val surgeMult = if (current.team.surgeActive && liveMove > 0.9f) 1.2f else 1f
                val tensionMult = if (current.team.ropeTension > 0.85f) 0.72f else 1f
                val accelScale = (if (liveBracing) 0.55f else 1f) * tensionMult * surgeMult
                predForwardVel += liveMove * 30f * accelScale * dt
                if (abs(liveMove) < 0.04f) predForwardVel *= (1f - 8f * dt).coerceAtLeast(0f)
                val maxSpeed = 12f * (if (current.team.surgeActive) 1.2f else 1f)
                predForwardVel = predForwardVel.coerceIn(-12f * 0.45f, maxSpeed)

                val currentMeters = predLocalProgress * 100f
                val windTunnel = current.obstacles.firstOrNull { it.id == "wind-tunnel" }
                if (windTunnel != null && currentMeters in windTunnel.start..windTunnel.end && !liveBracing) {
                    predForwardVel = (predForwardVel - 7f * dt).coerceIn(-12f * 0.45f, maxSpeed)
                }

                predLateralVel += liveSteer * 20f * dt
                if (abs(liveSteer) < 0.04f) predLateralVel *= (1f - 9f * dt).coerceAtLeast(0f)
                predLateralVel = predLateralVel.coerceIn(-2.6f, 2.6f)

                var nextMeters = (currentMeters + predForwardVel * dt).coerceIn(0f, 100f)
                val effectiveGap = if (inLocalJump) 9.7f else 7.5f
                if (localIndex > 0) {
                    val leaderMeters = current.team.players[localIndex - 1].progress * 100f
                    nextMeters = nextMeters.coerceIn(leaderMeters - effectiveGap, leaderMeters - 0.4f)
                }
                if (localIndex >= 0 && localIndex < current.team.players.lastIndex) {
                    val trailerMeters = current.team.players[localIndex + 1].progress * 100f
                    nextMeters = nextMeters.coerceAtMost(trailerMeters + effectiveGap)
                }
                val boulder = current.obstacles.firstOrNull { it.id == "boulder" }
                val effHeight = max(localJumpHeight, local.height)
                if (boulder != null && nextMeters in boulder.start..boulder.end && effHeight < 2.4f) {
                    nextMeters = boulder.start - 1.5f
                    predForwardVel = 0f
                }
                predLocalProgress = (nextMeters / 100f).coerceIn(0f, 1f)
                predLocalLat = (predLocalLat + predLateralVel * dt).coerceIn(-1.25f, 1.25f)

                val rttDelaySec = ((local.pingMs ?: 100).coerceIn(40, 260) / 1_000f) * 0.85f
                val rttCompensatedServerProgress = (local.progress + (predForwardVel * rttDelaySec) / 100f).coerceIn(0f, 1f)
                val drift = rttCompensatedServerProgress - predLocalProgress
                val blendRate = if (abs(liveMove) > 0.05f || inLocalJump) 2.2f * dt else 6.5f * dt
                predLocalProgress = (predLocalProgress + drift * blendRate).coerceIn(0f, 1f)
                predLocalLat += (local.lateralOffset - predLocalLat) * (3.5f * dt)
            }
        } else if (local != null) {
            predLocalProgress = local.progress
            predLocalLat = local.lateralOffset
        }

        val targetProgress = if (local != null && predLocalProgress >= 0f) predLocalProgress else (local?.progress ?: current.team.progress)
        if (smoothProgress < 0f || abs(targetProgress - smoothProgress) > 0.08f) {
            smoothProgress = targetProgress
        } else {
            smoothProgress += (targetProgress - smoothProgress) * 0.65f
        }

        val effectiveLocalLat = if (local != null && !current.previewMode) predLocalLat else (local?.lateralOffset ?: 0f)
        val effectiveLocalHeight = if (local != null) max(localJumpHeight, local.height) else 0f

        val targetFocusX = if (local != null && localIndex >= 0) {
            (localIndex - (current.team.players.size - 1) / 2f) * 1.22f + effectiveLocalLat.coerceIn(-1.25f, 1.25f)
        } else 0f
        val targetFocusY = (effectiveLocalHeight / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
        smoothFocusX += (targetFocusX - smoothFocusX) * 0.55f
        smoothFocusY += (targetFocusY - smoothFocusY) * 0.55f

        telemetry.cameraProgress = smoothProgress
        telemetry.focusX = smoothFocusX
        telemetry.focusY = smoothFocusY

        reusableMesh.reset()
        buildWorld(
            mesh = reusableMesh,
            state = current,
            cameraProgress = smoothProgress,
            liveMove = liveMove,
            liveSteer = liveSteer,
            liveBracing = liveBracing,
            inLocalJump = inLocalJump,
            localJumpHeight = localJumpHeight,
            time = now,
            dt = dt,
        )

        computeCameraLookAt(view, current.cameraBlend, current.orbitYaw, current.orbitPitch, smoothFocusX, smoothFocusY)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, IDENTITY, 0)

        val topBlend = current.cameraBlend.coerceIn(0f, 1f)
        val baseRadius = 7.2f + (15.5f - 7.2f) * topBlend
        val baseHeight = 3.35f + (17.8f - 3.35f) * topBlend
        val effectiveYaw = current.orbitYaw * (1f - topBlend * 0.65f)
        val effectivePitch = current.orbitPitch.coerceIn(-0.28f, 0.62f) * (1f - topBlend * 0.7f)
        val eyeX = smoothFocusX * 0.72f + sin(effectiveYaw) * baseRadius * cos(effectivePitch)
        val eyeY = (smoothFocusY * 0.35f + baseHeight + sin(effectivePitch) * baseRadius).coerceAtLeast(1.15f)
        val eyeZ = -cos(effectiveYaw) * baseRadius * cos(effectivePitch) + 0.6f

        val buffer = reusableMesh.fillBuffer(vertexBuffer)
        vertexBuffer = buffer

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvp, 0)
        GLES20.glUniform3f(eyePosHandle, eyeX, eyeY, eyeZ)

        val strideBytes = 9 * 4
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(normalHandle)
        GLES20.glEnableVertexAttribArray(colorHandle)

        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(normalHandle, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)
        buffer.position(6)
        GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, strideBytes, buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, reusableMesh.vertexCount)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(normalHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        telemetry.framesRendered++
    }

    private fun buildWorld(
        mesh: TriangleMesh,
        state: Ropebound3DState,
        cameraProgress: Float,
        liveMove: Float,
        liveSteer: Float,
        liveBracing: Boolean,
        inLocalJump: Boolean,
        localJumpHeight: Float,
        time: Float,
        dt: Float,
    ) {
        fun zAt(distanceMeters: Float) = (distanceMeters - cameraProgress * 100f) * COURSE_SCALE

        var cutCount = 0
        var riverCount = 0
        for (i in state.obstacles.indices) {
            val obs = state.obstacles[i]
            val id = obs.id
            val s = zAt(obs.start)
            val e = zAt(obs.end)
            if (id == "pit" || id == "river-bridge" || id == "river-gap" || id == "lava") {
                if (e > -18f && s < 96f && cutCount < cutStarts.size) {
                    cutStarts[cutCount] = s
                    cutEnds[cutCount] = e
                    cutCount++
                }
            }
            if ((id == "river-bridge" || id == "river-gap") && riverCount < riverStarts.size) {
                val rs = s.coerceAtLeast(-26f)
                val re = e.coerceAtMost(106f)
                if (re > rs) {
                    riverStarts[riverCount] = rs
                    riverEnds[riverCount] = re
                    riverCount++
                }
            }
        }

        // Insertion sort primitive arrays (<= 6 elements, 0 allocations)
        for (i in 1 until cutCount) {
            val ks = cutStarts[i]; val ke = cutEnds[i]
            var j = i - 1
            while (j >= 0 && cutStarts[j] > ks) {
                cutStarts[j + 1] = cutStarts[j]; cutEnds[j + 1] = cutEnds[j]
                j--
            }
            cutStarts[j + 1] = ks; cutEnds[j + 1] = ke
        }
        for (i in 1 until riverCount) {
            val ks = riverStarts[i]; val ke = riverEnds[i]
            var j = i - 1
            while (j >= 0 && riverStarts[j] > ks) {
                riverStarts[j + 1] = riverStarts[j]; riverEnds[j + 1] = riverEnds[j]
                j--
            }
            riverStarts[j + 1] = ks; riverEnds[j + 1] = ke
        }

        drawMountainVista(mesh, time)

        fun drawTerrainLayer(y: Float, color: Rgb) {
            var cursor = -26f
            for (i in 0 until riverCount) {
                val riverNear = riverStarts[i]
                val riverFar = riverEnds[i]
                if (riverNear > cursor) mesh.plane(-44f, 44f, cursor, riverNear, y, color)
                mesh.plane(-44f, -23f, riverNear, riverFar, y, color)
                mesh.plane(23f, 44f, riverNear, riverFar, y, color)
                cursor = max(cursor, riverFar)
            }
            if (cursor < 106f) mesh.plane(-44f, 44f, cursor, 106f, y, color)
        }
        drawTerrainLayer(-0.30f, Rgb(0.24f, 0.52f, 0.22f))
        drawTerrainLayer(-0.29f, Rgb(0.32f, 0.62f, 0.26f))

        drawValleySideHills(mesh, cameraProgress, riverCount, riverStarts, riverEnds)

        var roadZ = -20f
        for (i in 0 until cutCount) {
            val boundedStart = cutStarts[i].coerceIn(-20f, 96f)
            val boundedEnd = cutEnds[i].coerceIn(-20f, 96f)
            if (boundedStart > roadZ) drawTrail(mesh, roadZ, boundedStart)
            roadZ = max(roadZ, boundedEnd)
        }
        if (roadZ < 96f) drawTrail(mesh, roadZ, 96f)

        for (tree in 0 until 26) {
            val side = if (tree % 2 == 0) -1f else 1f
            val x = side * (6.6f + (tree % 5) * 2.15f)
            val z = -10f + (tree * 5.6f + cameraProgress * COURSE_SCALE * 0.35f) % 78f
            if (overlapsRiver(z, 1.2f, riverCount, riverStarts, riverEnds)) continue
            val species = tree % 3
            val scale = 0.86f + (tree % 4) * 0.14f
            drawStylizedTree(mesh, x, z, species, scale, time)
        }

        drawMeadows(mesh, cameraProgress, time, riverCount, riverStarts, riverEnds)

        for (i in state.obstacles.indices) {
            val obstacle = state.obstacles[i]
            val z1 = zAt(obstacle.start)
            val z2 = zAt(obstacle.end)
            if (max(z1, z2) < -22f || min(z1, z2) > 105f) continue
            when (obstacle.id) {
                "pit" -> buildPitHazard(mesh, z1, z2, time)
                "boulder" -> buildBoulderHazard(mesh, z1, z2, time)
                "river-bridge", "river-gap" -> {
                    buildRiver(mesh, z1, z2, obstacle.id == "river-bridge", time, state.team.players, cameraProgress)
                }
                "wind-tunnel" -> buildWindTunnel(mesh, z1, z2, time)
                "lava" -> buildLava(mesh, z1, z2, time)
            }
        }

        for (checkpoint in CHECKPOINT_METERS) {
            val z = zAt(checkpoint)
            if (z !in -18f..95f) continue
            val reached = cameraProgress * 100f >= checkpoint
            drawCheckpointGate(mesh, z, checkpoint, reached, time)
        }

        val finishZ = zAt(99.5f)
        if (finishZ in -20f..110f) {
            drawFinishArch(mesh, finishZ, time)
        }

        val playerCount = min(state.team.players.size, anchorPoints.size)
        for (playerIndex in 0 until playerCount) {
            val rawPlayer = state.team.players[playerIndex]
            val isLocalPlayer = rawPlayer.id == state.viewerIdentity
            val tracker = runnerTrackers.getOrPut(rawPlayer.id.ifBlank { "runner_$playerIndex" }) { RunnerMotionTracker() }

            val targetProg = if (isLocalPlayer && !state.previewMode && predLocalProgress >= 0f) predLocalProgress else rawPlayer.progress
            val targetLat = if (isLocalPlayer && !state.previewMode) predLocalLat else rawPlayer.lateralOffset
            val targetH = if (isLocalPlayer) max(localJumpHeight, rawPlayer.height) else rawPlayer.height

            if (tracker.renderProgress < 0f || abs(targetProg - tracker.renderProgress) > 0.055f || isLocalPlayer) {
                tracker.renderProgress = targetProg
                tracker.renderLat = targetLat
                tracker.renderHeight = targetH
            } else {
                val catchUp = (14f * dt).coerceIn(0.12f, 0.45f)
                tracker.renderProgress += (targetProg - tracker.renderProgress) * catchUp
                tracker.renderLat += (targetLat - tracker.renderLat) * catchUp
                tracker.renderHeight += (targetH - tracker.renderHeight) * (18f * dt).coerceIn(0.18f, 0.55f)
            }

            if (playerIndex < telemetry.playerProgress.size) {
                telemetry.playerProgress[playerIndex] = tracker.renderProgress
                telemetry.playerLat[playerIndex] = tracker.renderLat
                telemetry.playerHeight[playerIndex] = tracker.renderHeight
            }

            val z = (tracker.renderProgress - cameraProgress) * 100f * COURSE_SCALE
            val x = (playerIndex - (state.team.players.size - 1) / 2f) * 1.22f + tracker.renderLat.coerceIn(-1.25f, 1.25f)
            val jump = (tracker.renderHeight / 12f).coerceIn(-0.35f, 0.5f) * 2.6f

            anchorPoints[playerIndex] = drawRunner(
                mesh = mesh,
                playerId = rawPlayer.id,
                playerVelocity = rawPlayer.velocity,
                renderProgress = tracker.renderProgress,
                grounded = if (isLocalPlayer) !inLocalJump && rawPlayer.grounded else rawPlayer.grounded,
                jumping = if (isLocalPlayer) inLocalJump || rawPlayer.jumping else rawPlayer.jumping,
                bracing = if (isLocalPlayer) liveBracing || rawPlayer.bracing else rawPlayer.bracing,
                tracker = tracker,
                x = x,
                z = z,
                jump = jump,
                index = playerIndex,
                isLocalPlayer = isLocalPlayer,
                localMoveInput = if (isLocalPlayer) liveMove else 0f,
                localSteerInput = if (isLocalPlayer) liveSteer else 0f,
                surgeActive = state.team.surgeActive,
                time = time,
                dt = dt,
            )
        }
        telemetry.playerCount = playerCount

        drawSquadTensionRope(
            mesh = mesh,
            anchorPoints = anchorPoints,
            anchorCount = playerCount,
            ropeTension = state.team.ropeTension,
            surgeActive = state.team.surgeActive,
            time = time,
        )
    }

    private fun linkProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }

    private companion object {
        val IDENTITY = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    }
}
