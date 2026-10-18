package com.squadvoice.app.game

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
internal fun GameSettingsButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC10212A))
            .border(1.dp, Color.White.copy(alpha = .6f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "Game audio settings"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = Color.White
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 1.8.dp.toPx()
            drawCircle(color, 5.dp.toPx(), Offset(cx, cy), style = Stroke(stroke))
            drawCircle(color, 1.4.dp.toPx(), Offset(cx, cy))
            repeat(8) { index ->
                val angle = index * PI / 4
                drawLine(
                    color,
                    Offset(cx + cos(angle).toFloat() * 6.dp.toPx(), cy + sin(angle).toFloat() * 6.dp.toPx()),
                    Offset(cx + cos(angle).toFloat() * 8.dp.toPx(), cy + sin(angle).toFloat() * 8.dp.toPx()),
                    stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
internal fun VirtualJoystick(
    move: Float,
    steer: Float,
    position: Offset,
    opacity: Float,
    editing: Boolean,
    maxWidthPx: Float,
    maxHeightPx: Float,
    controlSize: Dp,
    modifier: Modifier = Modifier,
    onPositionChange: (Offset) -> Unit,
    onInput: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val latestPosition by rememberUpdatedState(position)
    var activeMove by remember { mutableStateOf(move) }
    var activeSteer by remember { mutableStateOf(steer) }
    val sizePx = with(density) { controlSize.toPx() }

    Canvas(
        modifier = modifier.size(controlSize)
            .graphicsLayer { alpha = opacity }
            .semantics { contentDescription = if (editing) "Drag to move joystick" else "Four direction movement joystick" }
            .pointerInput(editing, maxWidthPx, maxHeightPx, sizePx) {
                fun updateAxes(position: Offset) {
                    val center = sizePx / 2f
                    val travel = sizePx * .28f
                    val rawX = (position.x - center) / travel
                    val rawY = (position.y - center) / travel
                    val rawMagnitude = hypot(rawX, rawY)
                    val deadZone = .12f
                    val inputScale = if (rawMagnitude <= deadZone || rawMagnitude == 0f) {
                        0f
                    } else {
                        ((rawMagnitude.coerceAtMost(1f) - deadZone) / (1f - deadZone)) / rawMagnitude
                    }
                    val horizontal = (rawX * inputScale).coerceIn(-1f, 1f)
                    val vertical = (rawY * inputScale).coerceIn(-1f, 1f)
                    activeMove = -vertical
                    activeSteer = horizontal
                    onInput(-vertical, horizontal)
                }
                if (editing) {
                    var dragPosition = latestPosition
                    detectDragGestures(onDragStart = { dragPosition = latestPosition }) { change, drag ->
                        change.consume()
                        val maxX = ((maxWidthPx - sizePx) / maxWidthPx).coerceAtLeast(0f)
                        val maxY = ((maxHeightPx - sizePx) / maxHeightPx).coerceAtLeast(0f)
                        dragPosition = Offset(
                            (dragPosition.x + drag.x / maxWidthPx).coerceIn(0f, maxX),
                            (dragPosition.y + drag.y / maxHeightPx).coerceIn(0f, maxY),
                        )
                        onPositionChange(dragPosition)
                    }
                } else {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        updateAxes(down.position)
                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull { it.pressed }
                            if (change == null || !change.pressed) {
                                activeMove = 0f
                                activeSteer = 0f
                                onInput(0f, 0f)
                                break
                            }
                            pointerId = change.id
                            change.consume()
                            updateAxes(change.position)
                        }
                    }
                }
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * .47f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xF11B3038), Color(0xD90B171D)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        drawCircle(
            if (editing) Color(0xFF48F0D8) else Color(0xFFE2F4F4).copy(alpha = .78f),
            radius,
            center,
            style = Stroke(if (editing) 2.6.dp.toPx() else 2.dp.toPx()),
        )
        drawCircle(Color(0xFF39D9D5).copy(alpha = .46f), radius * .72f, center, style = Stroke(1.dp.toPx()))

        fun arrow(dx: Float, dy: Float) {
            val point = Offset(center.x + dx * radius * .48f, center.y + dy * radius * .48f)
            val ux = dx
            val uy = dy
            val px = -dy
            val py = dx
            val tip = Offset(point.x + ux * radius * .105f, point.y + uy * radius * .105f)
            val tail = Offset(point.x - ux * radius * .105f, point.y - uy * radius * .105f)
            val ink = Color.White.copy(alpha = .78f)
            drawLine(ink, tail, tip, 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - ux * radius * .08f + px * radius * .065f, tip.y - uy * radius * .08f + py * radius * .065f), 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - ux * radius * .08f - px * radius * .065f, tip.y - uy * radius * .08f - py * radius * .065f), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        arrow(0f, -1f)
        arrow(0f, 1f)
        arrow(-1f, 0f)
        arrow(1f, 0f)

        val knobTravel = radius * .66f
        val knob = Offset(
            center.x + activeSteer * knobTravel,
            center.y - activeMove * knobTravel,
        )
        drawCircle(Color.Black.copy(alpha = .28f), radius * .30f, Offset(knob.x + 1.dp.toPx(), knob.y + 2.dp.toPx()))
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFC8E2E5), Color(0xFF677E87))), radius * .28f, knob)
        drawCircle(Color.White.copy(alpha = .5f), radius * .28f, knob, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White.copy(alpha = .24f), radius * .10f, Offset(knob.x - radius * .08f, knob.y - radius * .08f))
    }
}

internal enum class GameHudIcon { Follow, Orbit3D, TopView, Settings, Microphone, Voice }

@Composable
internal fun HudActionButton(
    icon: GameHudIcon,
    label: String?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    description: String = label ?: "Game action",
    onClick: () -> Unit,
) {
    val accent = Color(0xFF16D9E7)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 5.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Color(0xE51B4C58) else Color(0xE6102630),
            contentColor = Color.White,
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = if (selected) .9f else .52f)),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(18.dp)) {
                val c = accent
                val cx = size.width / 2f
                val cy = size.height / 2f
                val stroke = 2.dp.toPx()
                when (icon) {
                    GameHudIcon.Follow -> {
                        drawCircle(c, 2.6.dp.toPx(), Offset(cx, cy - 4.dp.toPx()))
                        drawCircle(c, 2.dp.toPx(), Offset(cx - 5.dp.toPx(), cy - 2.dp.toPx()))
                        drawCircle(c, 2.dp.toPx(), Offset(cx + 5.dp.toPx(), cy - 2.dp.toPx()))
                        drawRoundRect(c, Offset(cx - 6.dp.toPx(), cy + 1.dp.toPx()), Size(12.dp.toPx(), 5.dp.toPx()), CornerRadius(3.dp.toPx()))
                        drawRoundRect(c, Offset(cx - 9.dp.toPx(), cy + 2.dp.toPx()), Size(5.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                        drawRoundRect(c, Offset(cx + 4.dp.toPx(), cy + 2.dp.toPx()), Size(5.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
                    }
                    GameHudIcon.Orbit3D -> {
                        val r = 6.dp.toPx()
                        drawCircle(c, r, Offset(cx, cy), style = Stroke(stroke))
                        drawArc(c, 0f, 360f, false, Offset(cx - r, cy - r * .45f), Size(r * 2f, r * .9f), style = Stroke(stroke * .85f))
                        drawCircle(c, 1.8.dp.toPx(), Offset(cx, cy))
                    }
                    GameHudIcon.TopView -> {
                        drawRoundRect(c, Offset(cx - 8.dp.toPx(), cy - 5.dp.toPx()), Size(16.dp.toPx(), 11.dp.toPx()), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
                        drawCircle(c, 2.5.dp.toPx(), Offset(cx, cy + .5f.dp.toPx()), style = Stroke(stroke))
                        drawLine(c, Offset(cx - 4.dp.toPx(), cy - 7.dp.toPx()), Offset(cx + 4.dp.toPx(), cy - 7.dp.toPx()), stroke, cap = StrokeCap.Round)
                    }
                    GameHudIcon.Settings -> {
                        drawCircle(c, 5.dp.toPx(), Offset(cx, cy), style = Stroke(stroke))
                        drawCircle(c, 1.5.dp.toPx(), Offset(cx, cy))
                        repeat(8) { i ->
                            val angle = i * PI / 4
                            val inner = 6.dp.toPx()
                            val outer = 8.dp.toPx()
                            drawLine(
                                c,
                                Offset(cx + cos(angle).toFloat() * inner, cy + sin(angle).toFloat() * inner),
                                Offset(cx + cos(angle).toFloat() * outer, cy + sin(angle).toFloat() * outer),
                                stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                    GameHudIcon.Microphone -> {
                        drawRoundRect(c, Offset(cx - 3.dp.toPx(), cy - 8.dp.toPx()), Size(6.dp.toPx(), 11.dp.toPx()), CornerRadius(3.dp.toPx()))
                        drawArc(c, 180f, 180f, false, Offset(cx - 6.dp.toPx(), cy - 4.dp.toPx()), Size(12.dp.toPx(), 10.dp.toPx()), style = Stroke(stroke))
                        drawLine(c, Offset(cx, cy + 6.dp.toPx()), Offset(cx, cy + 8.dp.toPx()), stroke, cap = StrokeCap.Round)
                        drawLine(c, Offset(cx - 3.dp.toPx(), cy + 8.dp.toPx()), Offset(cx + 3.dp.toPx(), cy + 8.dp.toPx()), stroke, cap = StrokeCap.Round)
                    }
                    GameHudIcon.Voice -> {
                        val heights = listOf(4f, 8f, 12f, 6f, 10f, 5f)
                        heights.forEachIndexed { index, height ->
                            val x = cx + (index - 2.5f) * 2.6.dp.toPx()
                            drawLine(c, Offset(x, cy - height.dp.toPx() / 2f), Offset(x, cy + height.dp.toPx() / 2f), stroke, cap = StrokeCap.Round)
                        }
                    }
                }
            }
            if (label != null) {
                Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun GameSettingsOverlay(
    soundEnabled: Boolean,
    ambienceVolume: Float,
    sfxVolume: Float,
    hudOpacity: Float,
    hudEditMode: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onAmbienceVolumeChange: (Float) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
    onHudOpacityChange: (Float) -> Unit,
    onHudEditModeChange: (Boolean) -> Unit,
    onResetHud: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xB807151C)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.88f),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xF0182C35),
            border = BorderStroke(1.dp, Color(0xFF66E0CB).copy(alpha = .75f)),
            shadowElevation = 16.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ROPEBOUND · IN GAME", color = Color(0xFF66E0CB), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("Game settings", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.height(40.dp).semantics { contentDescription = "Close game settings" },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = .55f)),
                    ) { Text("×", fontSize = 21.sp) }
                }

                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    val centerY = size.height / 2f
                    drawLine(Color(0xFF66E0CB).copy(alpha = .55f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.dp.toPx())
                    val spacing = size.width / 5f
                    for (index in 1..4) drawCircle(Color(0xFF9DE4E8), 3.dp.toPx(), Offset(spacing * index, centerY))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Game audio", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Soft game music under voice chat, plus effects", color = Color(0xFFB7CACE), fontSize = 12.sp)
                    }
                    Switch(checked = soundEnabled, onCheckedChange = onSoundEnabledChange)
                }
                Text("Background music · ${(ambienceVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = ambienceVolume,
                    onValueChange = onAmbienceVolumeChange,
                    enabled = soundEnabled,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF66E0CB), activeTrackColor = Color(0xFF66E0CB)),
                )
                Text("Gameplay effects · ${(sfxVolume * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = sfxVolume,
                    onValueChange = onSfxVolumeChange,
                    enabled = soundEnabled,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF66E0CB), activeTrackColor = Color(0xFF66E0CB)),
                )
                Text("HUD transparency · ${(hudOpacity * 100).toInt()}%", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = hudOpacity,
                    onValueChange = onHudOpacityChange,
                    valueRange = .35f..1f,
                    colors = SliderDefaults.colors(thumbColor = Color(0xFF66E0CB), activeTrackColor = Color(0xFF66E0CB)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize HUD", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text("Move the joystick, jump, and brace controls after closing settings", color = Color(0xFFB7CACE), fontSize = 11.sp)
                    }
                    Switch(checked = hudEditMode, onCheckedChange = onHudEditModeChange)
                }
                Text(
                    "Voice chat stays connected. Your controls are released while settings are open.",
                    color = Color(0xFFB7CACE),
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onReset) { Text("Reset audio") }
                        OutlinedButton(onClick = onResetHud) { Text("Reset HUD") }
                    }
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF66E0CB), contentColor = Color(0xFF082521)),
                    ) { Text("OK · Resume", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
internal fun MatchLoadingOverlay(teamSize: Int, worldReady: Boolean, logoPulse: Float, runnerTravel: Float) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val trackProgress = if (runnerTravel < .78f) .72f + runnerTravel * .22f else .89f + (runnerTravel - .78f) * .35f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color(0xB307151D),
                        .30f to Color(0x2607151D),
                        .62f to Color(0x1607151D),
                        1f to Color(0xD907151D),
                    ),
                ),
            )
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "SQUADVOICE",
                    color = Color.White.copy(alpha = .78f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    "ROPEBOUND",
                    modifier = Modifier.graphicsLayer { scaleX = logoPulse; scaleY = logoPulse },
                    color = Color(0xFF7BF1DC),
                    fontSize = if (isLandscape) 28.sp else 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.5.sp,
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(if (isLandscape) .56f else .94f),
                shape = RoundedCornerShape(22.dp),
                color = Color(0xD9122730),
                border = BorderStroke(1.dp, Color(0xFF73E6D7).copy(alpha = .52f)),
                shadowElevation = 14.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("LOADING PLAYGROUND", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                    Text(
                        if (worldReady) "Course ready · joining with $teamSize runners…"
                        else "Syncing your squad and setting the bridge…",
                        color = Color(0xFFC2D8DA),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                        val barHeight = size.height * .48f
                        drawRoundRect(Color.White.copy(alpha = .18f), Offset.Zero, size, CornerRadius(size.height / 2f))
                        drawRoundRect(
                            Color(0xFF59E1CB),
                            Offset.Zero,
                            Size(size.width * trackProgress, barHeight),
                            CornerRadius(barHeight / 2f),
                        )
                        val headX = size.width * trackProgress
                        drawCircle(Color.White.copy(alpha = .92f), barHeight * .52f, Offset(headX.coerceAtMost(size.width - barHeight), size.height / 2f))
                        val runnerColors = listOf(Color(0xFFB9A3FF), Color(0xFFFF927F), Color(0xFF62E2C9), Color(0xFFFFCB67))
                        runnerColors.forEachIndexed { index, color ->
                            val x = size.width * ((runnerTravel + index * .19f) % 1f)
                            if (x < headX) drawCircle(color, barHeight * .28f, Offset(x, size.height / 2f))
                        }
                    }
                    Text(
                        "Stay together to keep the rope steady.",
                        color = Color(0xFF83E9D8),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                if (worldReady) "YOUR TEAM IS READY" else "YOUR TEAM IS GETTING READY",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = Color.White.copy(alpha = .72f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
        }
    }
}

@Composable
internal fun HoldBraceButton(onBrace: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    LaunchedEffect(pressed) { onBrace(pressed) }
    GameControlButton(
        icon = GameControlIcon.Brace,
        description = "Brace",
        modifier = modifier,
        pressed = pressed,
        interactionSource = interactionSource,
    )
}

internal enum class GameControlIcon { Left, Right, Up, Down, Jump, Brace }

@Composable
internal fun GameActionButton(
    onClick: () -> Unit,
    description: String,
    icon: GameControlIcon,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    GameControlButton(
        icon = icon,
        description = description,
        modifier = modifier,
        pressed = false,
        highlighted = highlighted,
        onClick = onClick,
    )
}

@Composable
internal fun GameControlButton(
    icon: GameControlIcon,
    description: String,
    modifier: Modifier = Modifier,
    pressed: Boolean,
    highlighted: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit = {},
    onPressedChange: ((Boolean) -> Unit)? = null,
) {
    val active = pressed || highlighted
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics {
            contentDescription = description
        },
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) Color(0xFF66E0CB) else Color(0xE6102028),
            contentColor = if (active) Color(0xFF082521) else Color.White,
        ),
        border = BorderStroke(1.5.dp, if (active) Color.White.copy(alpha = .92f) else Color.White.copy(alpha = .72f)),
    ) {
        Canvas(Modifier.size(25.dp)) {
            val ink = if (active) Color(0xFF082521) else Color.White
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 2.7.dp.toPx()
            when (icon) {
                GameControlIcon.Left, GameControlIcon.Right, GameControlIcon.Up, GameControlIcon.Down -> {
                    val unit = when (icon) {
                        GameControlIcon.Left -> Offset(-1f, 0f)
                        GameControlIcon.Right -> Offset(1f, 0f)
                        GameControlIcon.Up -> Offset(0f, -1f)
                        else -> Offset(0f, 1f)
                    }
                    val perpendicular = Offset(-unit.y, unit.x)
                    val arrowLength = 7.dp.toPx()
                    val headLength = 4.dp.toPx()
                    val headWidth = 4.dp.toPx()
                    val start = Offset(cx - unit.x * arrowLength, cy - unit.y * arrowLength)
                    val tip = Offset(cx + unit.x * arrowLength, cy + unit.y * arrowLength)
                    drawLine(ink, start, tip, stroke, cap = StrokeCap.Round)
                    drawLine(
                        ink,
                        tip,
                        Offset(tip.x - unit.x * headLength + perpendicular.x * headWidth, tip.y - unit.y * headLength + perpendicular.y * headWidth),
                        stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        ink,
                        tip,
                        Offset(tip.x - unit.x * headLength - perpendicular.x * headWidth, tip.y - unit.y * headLength - perpendicular.y * headWidth),
                        stroke,
                        cap = StrokeCap.Round,
                    )
                }
                GameControlIcon.Jump -> {
                    drawLine(ink, Offset(cx, cy + 8.dp.toPx()), Offset(cx, cy - 7.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - 7.dp.toPx()), Offset(cx - 6.dp.toPx(), cy - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - 7.dp.toPx()), Offset(cx + 6.dp.toPx(), cy - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx - 9.dp.toPx(), cy + 9.dp.toPx()), Offset(cx + 9.dp.toPx(), cy + 9.dp.toPx()), stroke, cap = StrokeCap.Round)
                }
                GameControlIcon.Brace -> {
                    val shield = Path().apply {
                        moveTo(cx, cy - 10.dp.toPx())
                        lineTo(cx + 8.dp.toPx(), cy - 6.dp.toPx())
                        lineTo(cx + 7.dp.toPx(), cy + 2.dp.toPx())
                        quadraticTo(cx + 5.dp.toPx(), cy + 7.dp.toPx(), cx, cy + 10.dp.toPx())
                        quadraticTo(cx - 5.dp.toPx(), cy + 7.dp.toPx(), cx - 7.dp.toPx(), cy + 2.dp.toPx())
                        lineTo(cx - 8.dp.toPx(), cy - 6.dp.toPx())
                        close()
                    }
                    drawPath(shield, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    drawLine(ink, Offset(cx - 3.dp.toPx(), cy), Offset(cx - 0.5f.dp.toPx(), cy + 3.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx - 0.5f.dp.toPx(), cy + 3.dp.toPx()), Offset(cx + 4.dp.toPx(), cy - 3.dp.toPx()), stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
    if (onPressedChange != null) LaunchedEffect(pressed) { onPressedChange(pressed) }
}

@Composable
internal fun RaceMarker(
    label: String,
    progress: Float,
    modifier: Modifier = Modifier,
    valueColor: Color = Color(0xFF66E0CB),
    isTension: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = .35.sp,
            color = Color.White.copy(alpha = .82f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
            fontSize = 12.sp,
            color = if (isTension && progress >= .8f) Color(0xFFFF9B57) else valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HudStatDivider() {
    Box(
        Modifier.padding(horizontal = 5.dp).width(1.dp).height(20.dp)
            .background(Color(0xFF28C8D5).copy(alpha = .38f)),
    )
}
