package com.squadvoice.app.game

import android.content.Context
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.WindowManager
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private val IDENTITY = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

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
    qualityMode: RopeboundQualityMode = RopeboundQualityMode.AUTO,
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
                qualityMode = qualityMode,
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
            (localIndex - (team.players.size - 1) / 2f) * 1.28f + local.lateralOffset.coerceIn(-1.45f, 1.45f)
        } else 0f
        val focusY = if (hasLiveTelemetry) {
            telemetry.focusY
        } else {
            ((local?.height ?: 0f) / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
        }

        Matrix.perspectiveM(projection, 0, 56f, size.width / size.height.coerceAtLeast(1f), 0.1f, 220f)
        computeCameraLookAt(camera, cameraBlend, orbitYaw, orbitPitch, focusX, focusY)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, camera, 0)

        team.players.forEachIndexed { index, player ->
            val pProg = if (hasLiveTelemetry && index < telemetry.playerProgress.size) telemetry.playerProgress[index] else player.progress
            val pLat = if (hasLiveTelemetry && index < telemetry.playerLat.size) telemetry.playerLat[index] else player.lateralOffset
            val pHeight = if (hasLiveTelemetry && index < telemetry.playerHeight.size) telemetry.playerHeight[index] else player.height
            val z = (pProg - cameraProgress) * 100f * COURSE_SCALE
            val x = (index - (team.players.size - 1) / 2f) * 1.28f + pLat.coerceIn(-1.45f, 1.45f)
            val y = 2.46f + (pHeight / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
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
                player.pingMs == null || player.pingMs < 150 -> android.graphics.Color.rgb(155, 246, 90)
                player.pingMs < 300 -> android.graphics.Color.rgb(255, 202, 66)
                else -> android.graphics.Color.rgb(255, 110, 122)
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
                if (isLocal) PlaygroundTheme.StoneCardSelected else PlaygroundTheme.StoneDark,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
            )
            drawRoundRect(
                if (isLocal) PlaygroundTheme.MossEmerald else PlaygroundTheme.StoneBorder,
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (isLocal) 1.4.dp.toPx() else 0.9.dp.toPx()),
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
    val orbitYaw: Float = 0f,
    val orbitPitch: Float = 0f,
    val liveControlFeed: RopeboundLiveControlFeed? = null,
    val previewMode: Boolean = false,
    val qualityMode: RopeboundQualityMode = RopeboundQualityMode.AUTO,
)

private class Ropebound3DView(context: Context) : GLSurfaceView(context) {
    val telemetry = RopeboundLiveRenderTelemetry()
    private val gameRenderer = Ropebound3DRenderer(context.applicationContext, telemetry) { readDisplayRefreshRate() }

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setPreserveEGLContextOnPause(true)
        setRenderer(gameRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @Suppress("DEPRECATION")
    private fun readDisplayRefreshRate(): Float {
        val rate = display?.refreshRate
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.refreshRate
            ?: 60f
        return rate.coerceIn(30f, 144f)
    }

    override fun onResume() {
        super.onResume()
        gameRenderer.notifyResume(readDisplayRefreshRate())
    }

    fun submit(state: Ropebound3DState) = gameRenderer.submit(state)
}

private class ShaderHandles(
    val program: Int,
    val posLoc: Int,
    val normLoc: Int,
    val colLoc: Int,
    val uvLoc: Int,
    val mvpLoc: Int,
    val eyeLoc: Int,
    val texLoc: Int,
    val scrollLoc: Int,
)

private class Ropebound3DRenderer(
    context: Context,
    private val telemetry: RopeboundLiveRenderTelemetry,
    private val refreshRateProvider: () -> Float,
) : GLSurfaceView.Renderer {
    @Volatile
    private var scene = Ropebound3DState(
        RopeboundTeam(0, 0f, 0f, false, 3, emptyList(), emptyList()),
        "",
        emptyList(),
        0f,
    )
    private val textureBank = RopeboundTextureBank(context)
    private val reusableMesh = TriangleMesh()

    private var skyShader: ShaderHandles? = null
    private var litShader: ShaderHandles? = null
    private var waterShader: ShaderHandles? = null

    private var width = 1
    private var height = 1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val epoch = System.nanoTime()
    private var lastFrameAt = 0f

    // Refresh-rate-relative Auto quality hysteresis state
    @Volatile private var vsyncPeriodMs = 16.67f
    @Volatile private var warmupUntilSec = 1.5f
    private var cooldownUntilSec = 0f
    private var overBudgetAccumSec = 0f
    private var underBudgetAccumSec = 0f
    private var emaFrameMs = 16.67f
    private var autoTier = RopeboundQualityTier.HIGH

    private var smoothProgress = -1f
    private var smoothFocusX = 0f
    private var smoothFocusY = 0f
    private var predLocalProgress = -1f
    private var predLocalLat = 0f
    private var predForwardVel = 0f
    private var predLateralVel = 0f
    private val runnerTrackers = HashMap<String, RunnerMotionTracker>()
    private val cutStarts = FloatArray(12)
    private val cutEnds = FloatArray(12)
    private val anchorPoints = arrayOfNulls<Point3>(12)

    private val opaqueBatches = intArrayOf(
        MatId.STONE,
        MatId.WOOD,
        MatId.DIRT,
        MatId.GRASS,
        MatId.BASALT,
        MatId.FOLIAGE,
        MatId.VERTEX_COLOR,
    )

    fun submit(next: Ropebound3DState) {
        scene = next
    }

    fun notifyResume(refreshRateHz: Float) {
        vsyncPeriodMs = 1000f / refreshRateHz.coerceIn(30f, 144f)
        val now = (System.nanoTime() - epoch) / 1_000_000_000f
        warmupUntilSec = now + 1.5f
        overBudgetAccumSec = 0f
        underBudgetAccumSec = 0f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.24f, 0.64f, 0.94f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        notifyResume(refreshRateProvider())
        textureBank.release()

        val commonVs = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            attribute vec2 aUv;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vWorldPos = aPosition;
                vNormal = aNormal;
                vColor = aColor;
                vUv = aUv;
            }
        """.trimIndent()

        // 1. Skydome program: unlit, no fog, drawn with depth-writes OFF centered on the camera
        val skyFs = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec3 vColor;
            varying vec2 vUv;
            void main() {
                vec4 tex = texture2D(uTexture, vUv);
                gl_FragColor = vec4(clamp(tex.rgb * vColor, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        // 2. Unified branchless Painterly Toon + Soft Rim + Height Fog program for all opaque batches & 1x1 white vertex-colored batch
        val litFs = """
            precision mediump float;
            uniform vec3 uEyePos;
            uniform sampler2D uTexture;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying vec2 vUv;
            void main() {
                vec4 tex = texture2D(uTexture, vUv);
                vec3 albedo = tex.rgb * vColor;
                float nLen = length(vNormal);
                vec3 N = (nLen > 0.2) ? (vNormal / nLen) : vec3(0.0, 1.0, 0.0);
                vec3 V = normalize(uEyePos - vWorldPos);
                N = mix(N, -N, step(dot(N, V), -0.15));

                vec3 L = normalize(vec3(0.45, 0.84, -0.32));
                vec3 H = normalize(L + V);
                float ndl = dot(N, L) * 0.5 + 0.5;
                float cel = mix(0.48, 0.82, smoothstep(0.36, 0.48, ndl));
                cel = mix(cel, 1.08, smoothstep(0.68, 0.82, ndl));

                vec3 skyAmb = vec3(0.76, 0.90, 1.0);
                vec3 mossAmb = vec3(0.36, 0.52, 0.36);
                vec3 hemi = mix(mossAmb, skyAmb, clamp(N.y * 0.5 + 0.5, 0.0, 1.0));

                float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0) * 0.22 * max(N.y + 0.25, 0.0);
                float spec = pow(max(dot(N, H), 0.0), 24.0) * 0.16;
                vec3 sunColor = vec3(1.04, 0.98, 0.88);

                vec3 litCol = albedo * (hemi * 0.44 + sunColor * cel * 0.74) + vec3(spec + rim * 0.75);
                float emissiveMask = step(nLen, 0.2);
                vec3 color = mix(litCol, albedo, emissiveMask);

                float dist = length(vWorldPos - uEyePos);
                float fogFactor = clamp((dist - 40.0) / 96.0, 0.0, 0.72);
                vec3 fogColor = mix(vec3(0.56, 0.84, 0.98), vec3(0.38, 0.70, 0.94), clamp(vWorldPos.y / 26.0, 0.0, 1.0));
                color = mix(color, fogColor, fogFactor * fogFactor);

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        // 3. Branchless scrolling water & waterfall program
        val waterFs = """
            precision mediump float;
            uniform vec3 uEyePos;
            uniform sampler2D uTexture;
            uniform vec2 uScroll;
            varying vec3 vWorldPos;
            varying vec3 vNormal;
            varying vec3 vColor;
            varying vec2 vUv;
            void main() {
                vec2 uvA = vUv + uScroll;
                vec2 uvB = vUv * 1.45 + vec2(-uScroll.x * 0.65, uScroll.y * 1.25);
                vec3 texA = texture2D(uTexture, uvA).rgb;
                vec3 texB = texture2D(uTexture, uvB).rgb;
                vec3 waterCol = (texA * 0.58 + texB * 0.42) * vColor;

                vec3 V = normalize(uEyePos - vWorldPos);
                vec3 L = normalize(vec3(0.45, 0.84, -0.32));
                vec3 H = normalize(L + V);
                float spec = pow(max(H.y, 0.0), 38.0) * 0.36;
                float foam = smoothstep(0.72, 0.90, (texA.r + texB.g) * 0.5);
                vec3 color = mix(waterCol, vec3(0.95, 0.99, 1.0), foam * 0.65) + vec3(spec);

                float dist = length(vWorldPos - uEyePos);
                float fogFactor = clamp((dist - 40.0) / 96.0, 0.0, 0.72);
                vec3 fogColor = mix(vec3(0.56, 0.84, 0.98), vec3(0.38, 0.70, 0.94), clamp(vWorldPos.y / 26.0, 0.0, 1.0));
                color = mix(color, fogColor, fogFactor * fogFactor);

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

        skyShader = buildShaderHandles(commonVs, skyFs)
        litShader = buildShaderHandles(commonVs, litFs)
        waterShader = buildShaderHandles(commonVs, waterFs)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, this.width, this.height)
        Matrix.perspectiveM(projection, 0, 56f, this.width.toFloat() / this.height, 0.1f, 220f)
        vsyncPeriodMs = 1000f / refreshRateProvider().coerceIn(30f, 144f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = (System.nanoTime() - epoch) / 1_000_000_000f
        val rawDt = if (lastFrameAt == 0f) vsyncPeriodMs / 1000f else (now - lastFrameAt)
        lastFrameAt = now

        val current = scene
        val activeTier = updateQualityHysteresis(now, rawDt, current.qualityMode)
        telemetry.activeQualityTier = activeTier
        telemetry.emaFrameMs = emaFrameMs
        textureBank.ensureLoaded(activeTier)

        val dt = rawDt.coerceIn(0.002f, 0.045f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

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
                predLateralVel = predLateralVel.coerceIn(-2.8f, 2.8f)

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
                predLocalLat = (predLocalLat + predLateralVel * dt).coerceIn(-1.45f, 1.45f)

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
            (localIndex - (current.team.players.size - 1) / 2f) * 1.28f + effectiveLocalLat.coerceIn(-1.45f, 1.45f)
        } else 0f
        val targetFocusY = (effectiveLocalHeight / 12f).coerceIn(-0.35f, 0.5f) * 2.6f
        smoothFocusX += (targetFocusX - smoothFocusX) * 0.55f
        smoothFocusY += (targetFocusY - smoothFocusY) * 0.55f

        telemetry.cameraProgress = smoothProgress
        telemetry.focusX = smoothFocusX
        telemetry.focusY = smoothFocusY
        telemetry.playerCount = current.team.players.size

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

        reusableMesh.reset()
        // Build camera-centered skydome into MatId.SKY
        reusableMesh.skydome(eyeX, eyeY, eyeZ, radius = 124f)

        buildWorld(
            mesh = reusableMesh,
            state = current,
            cameraProgress = smoothProgress,
            liveMove = liveMove,
            liveSteer = liveSteer,
            liveBracing = liveBracing,
            inLocalJump = inLocalJump,
            localJumpHeight = localJumpHeight,
            qualityTier = activeTier,
            time = now,
            dt = dt,
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // Pass 1: Skydome (depth writes OFF, centered on camera, no fog)
        skyShader?.let { sh ->
            GLES20.glDepthMask(false)
            drawBatch(sh, reusableMesh.batch(MatId.SKY), textureBank.textureId(MatId.SKY), eyeX, eyeY, eyeZ, 0f, 0f)
            GLES20.glDepthMask(true)
        }

        // Pass 2: Opaque textured batches + 1x1 white vertex-colored batch using the single unified litShader
        litShader?.let { sh ->
            for (matId in opaqueBatches) {
                drawBatch(sh, reusableMesh.batch(matId), textureBank.textureId(matId), eyeX, eyeY, eyeZ, 0f, 0f)
            }
        }

        // Pass 3: Scrolling water & waterfall batches
        waterShader?.let { sh ->
            drawBatch(
                sh,
                reusableMesh.batch(MatId.WATER),
                textureBank.textureId(MatId.WATER),
                eyeX,
                eyeY,
                eyeZ,
                scrollU = (now * 0.08f) % 1f,
                scrollV = -(now * 0.14f) % 1f,
            )
            drawBatch(
                sh,
                reusableMesh.batch(MatId.WATERFALL),
                textureBank.textureId(MatId.WATERFALL),
                eyeX,
                eyeY,
                eyeZ,
                scrollU = 0f,
                scrollV = -(now * 0.65f) % 1f,
            )
        }

        telemetry.framesRendered++
    }

    /**
     * Refresh-rate-relative Auto quality hysteresis:
     * - Ignores first 1.5 s after surface create/resume and any frame > 250 ms.
     * - Steps DOWN 1 tier when EMA frame time > 1.6x vsync period for 3.0 s (starts 15 s cooldown).
     * - Steps UP 1 tier when EMA frame time < 1.15x vsync period for 10.0 s (after cooldown).
     */
    private fun updateQualityHysteresis(
        nowSec: Float,
        rawDtSec: Float,
        mode: RopeboundQualityMode,
    ): RopeboundQualityTier {
        when (mode) {
            RopeboundQualityMode.LOW -> return RopeboundQualityTier.LOW
            RopeboundQualityMode.MEDIUM -> return RopeboundQualityTier.MEDIUM
            RopeboundQualityMode.HIGH -> return RopeboundQualityTier.HIGH
            RopeboundQualityMode.AUTO -> Unit
        }
        if (nowSec < warmupUntilSec || rawDtSec <= 0f || rawDtSec > 0.25f) {
            return autoTier
        }
        val frameMs = rawDtSec * 1000f
        emaFrameMs = emaFrameMs * 0.92f + frameMs * 0.08f

        val stepDownThresholdMs = vsyncPeriodMs * 1.60f
        val stepUpThresholdMs = vsyncPeriodMs * 1.15f

        if (emaFrameMs > stepDownThresholdMs) {
            overBudgetAccumSec += rawDtSec
            underBudgetAccumSec = 0f
            if (overBudgetAccumSec >= 3.0f) {
                overBudgetAccumSec = 0f
                cooldownUntilSec = nowSec + 15.0f
                autoTier = when (autoTier) {
                    RopeboundQualityTier.HIGH -> RopeboundQualityTier.MEDIUM
                    RopeboundQualityTier.MEDIUM, RopeboundQualityTier.LOW -> RopeboundQualityTier.LOW
                }
            }
        } else if (emaFrameMs < stepUpThresholdMs && nowSec >= cooldownUntilSec) {
            underBudgetAccumSec += rawDtSec
            overBudgetAccumSec = 0f
            if (underBudgetAccumSec >= 10.0f) {
                underBudgetAccumSec = 0f
                autoTier = when (autoTier) {
                    RopeboundQualityTier.LOW -> RopeboundQualityTier.MEDIUM
                    RopeboundQualityTier.MEDIUM, RopeboundQualityTier.HIGH -> RopeboundQualityTier.HIGH
                }
            }
        } else {
            overBudgetAccumSec = max(0f, overBudgetAccumSec - rawDtSec * 0.5f)
            underBudgetAccumSec = max(0f, underBudgetAccumSec - rawDtSec * 0.5f)
        }
        return autoTier
    }

    private fun drawBatch(
        sh: ShaderHandles,
        batch: MaterialBatch,
        texId: Int,
        eyeX: Float,
        eyeY: Float,
        eyeZ: Float,
        scrollU: Float,
        scrollV: Float,
    ) {
        val count = batch.vertexCount
        if (count <= 0) return
        val buffer = batch.prepareBuffer()

        GLES20.glUseProgram(sh.program)
        GLES20.glUniformMatrix4fv(sh.mvpLoc, 1, false, mvp, 0)
        if (sh.eyeLoc >= 0) GLES20.glUniform3f(sh.eyeLoc, eyeX, eyeY, eyeZ)
        if (sh.scrollLoc >= 0) GLES20.glUniform2f(sh.scrollLoc, scrollU, scrollV)
        if (sh.texLoc >= 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(sh.texLoc, 0)
        }

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(sh.posLoc)
        GLES20.glVertexAttribPointer(sh.posLoc, 3, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buffer)

        if (sh.normLoc >= 0) {
            buffer.position(3)
            GLES20.glEnableVertexAttribArray(sh.normLoc)
            GLES20.glVertexAttribPointer(sh.normLoc, 3, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buffer)
        }
        if (sh.colLoc >= 0) {
            buffer.position(6)
            GLES20.glEnableVertexAttribArray(sh.colLoc)
            GLES20.glVertexAttribPointer(sh.colLoc, 3, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buffer)
        }
        if (sh.uvLoc >= 0) {
            buffer.position(9)
            GLES20.glEnableVertexAttribArray(sh.uvLoc)
            GLES20.glVertexAttribPointer(sh.uvLoc, 2, GLES20.GL_FLOAT, false, VERTEX_STRIDE_BYTES, buffer)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)

        GLES20.glDisableVertexAttribArray(sh.posLoc)
        if (sh.normLoc >= 0) GLES20.glDisableVertexAttribArray(sh.normLoc)
        if (sh.colLoc >= 0) GLES20.glDisableVertexAttribArray(sh.colLoc)
        if (sh.uvLoc >= 0) GLES20.glDisableVertexAttribArray(sh.uvLoc)
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
        qualityTier: RopeboundQualityTier,
        time: Float,
        dt: Float,
    ) {
        fun zAt(distanceMeters: Float) = (distanceMeters - cameraProgress * 100f) * COURSE_SCALE

        var cutCount = 0
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
        }

        for (i in 1 until cutCount) {
            val ks = cutStarts[i]; val ke = cutEnds[i]
            var j = i - 1
            while (j >= 0 && cutStarts[j] > ks) {
                cutStarts[j + 1] = cutStarts[j]; cutEnds[j + 1] = cutEnds[j]
                j--
            }
            cutStarts[j + 1] = ks; cutEnds[j + 1] = ke
        }

        drawMountainVista(mesh, time, qualityTier)
        drawValleySideHills(mesh, cameraProgress, cutCount, cutStarts, cutEnds, qualityTier)

        var roadZ = -20f
        for (i in 0 until cutCount) {
            val boundedStart = cutStarts[i].coerceIn(-20f, 96f)
            val boundedEnd = cutEnds[i].coerceIn(-20f, 96f)
            if (boundedStart > roadZ) drawTrail(mesh, roadZ, boundedStart, qualityTier)
            roadZ = max(roadZ, boundedEnd)
        }
        if (roadZ < 96f) drawTrail(mesh, roadZ, 96f, qualityTier)

        drawMeadows(mesh, cameraProgress, time, cutCount, cutStarts, cutEnds, qualityTier)

        for (i in state.obstacles.indices) {
            val obstacle = state.obstacles[i]
            val z1 = zAt(obstacle.start)
            val z2 = zAt(obstacle.end)
            if (max(z1, z2) < -22f || min(z1, z2) > 105f) continue
            val nearEdge = min(z1, z2)
            if (nearEdge - 1.15f in -18f..95f) {
                drawHazardWarningTelegraph(mesh, nearEdge - 1.15f, obstacle.action, time, qualityTier)
            }
            when (obstacle.id) {
                "pit" -> buildPitHazard(mesh, z1, z2, time, qualityTier)
                "boulder" -> buildBoulderHazard(mesh, z1, z2, time, qualityTier)
                "river-bridge", "river-gap" -> {
                    buildRiver(mesh, z1, z2, obstacle.id == "river-bridge", time, state.team.players, cameraProgress, qualityTier)
                }
                "wind-tunnel" -> buildWindTunnel(mesh, z1, z2, time, qualityTier)
                "lava" -> buildLava(mesh, z1, z2, time, qualityTier)
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
            val x = (playerIndex - (state.team.players.size - 1) / 2f) * 1.28f + tracker.renderLat.coerceIn(-1.45f, 1.45f)
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
                ropeTension = state.team.ropeTension,
                time = time,
                dt = dt,
            )
        }

        drawSquadTensionRope(
            mesh = mesh,
            anchorPoints = anchorPoints,
            anchorCount = playerCount,
            ropeTension = state.team.ropeTension,
            surgeActive = state.team.surgeActive,
            time = time,
        )
    }

    private fun buildShaderHandles(vertexSource: String, fragmentSource: String): ShaderHandles {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        return ShaderHandles(
            program = program,
            posLoc = GLES20.glGetAttribLocation(program, "aPosition"),
            normLoc = GLES20.glGetAttribLocation(program, "aNormal"),
            colLoc = GLES20.glGetAttribLocation(program, "aColor"),
            uvLoc = GLES20.glGetAttribLocation(program, "aUv"),
            mvpLoc = GLES20.glGetUniformLocation(program, "uMvp"),
            eyeLoc = GLES20.glGetUniformLocation(program, "uEyePos"),
            texLoc = GLES20.glGetUniformLocation(program, "uTexture"),
            scrollLoc = GLES20.glGetUniformLocation(program, "uScroll"),
        )
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}
