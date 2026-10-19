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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
            .background(PlaygroundTheme.StoneCard)
            .border(1.2.dp, PlaygroundTheme.MossEmerald.copy(alpha = .68f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Game audio settings" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val color = PlaygroundTheme.TimberGold
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
    var activeMove by remember { mutableFloatStateOf(0f) }
    var activeSteer by remember { mutableFloatStateOf(0f) }
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
        // Carved Mossy Stone & Timber Outer Base
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xF2344236), Color(0xE6182019)),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        drawCircle(
            if (editing) PlaygroundTheme.TimberGold else PlaygroundTheme.MossEmerald.copy(alpha = .85f),
            radius,
            center,
            style = Stroke(if (editing) 2.8.dp.toPx() else 2.2.dp.toPx()),
        )
        drawCircle(PlaygroundTheme.TimberWarm.copy(alpha = .48f), radius * .72f, center, style = Stroke(1.2.dp.toPx()))

        fun arrow(dx: Float, dy: Float) {
            val point = Offset(center.x + dx * radius * .48f, center.y + dy * radius * .48f)
            val px = -dy
            val py = dx
            val tip = Offset(point.x + dx * radius * .105f, point.y + dy * radius * .105f)
            val tail = Offset(point.x - dx * radius * .105f, point.y - dy * radius * .105f)
            val ink = PlaygroundTheme.TimberGold.copy(alpha = .88f)
            drawLine(ink, tail, tip, 2.2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - dx * radius * .08f + px * radius * .065f, tip.y - dy * radius * .08f + py * radius * .065f), 2.2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, tip, Offset(tip.x - dx * radius * .08f - px * radius * .065f, tip.y - dy * radius * .08f - py * radius * .065f), 2.2.dp.toPx(), cap = StrokeCap.Round)
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
        drawCircle(Color.Black.copy(alpha = .34f), radius * .30f, Offset(knob.x + 1.dp.toPx(), knob.y + 2.dp.toPx()))
        drawCircle(Brush.verticalGradient(listOf(Color(0xFF9DF85A), Color(0xFF389824))), radius * .28f, knob)
        drawCircle(Color.White.copy(alpha = .65f), radius * .28f, knob, style = Stroke(1.2.dp.toPx()))
        drawCircle(Color.White.copy(alpha = .32f), radius * .10f, Offset(knob.x - radius * .08f, knob.y - radius * .08f))
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
    val accent = if (selected) PlaygroundTheme.TimberGold else PlaygroundTheme.MossEmerald
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(38.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) PlaygroundTheme.StoneCardSelected else PlaygroundTheme.StoneDark,
            contentColor = PlaygroundTheme.TextPrimary,
        ),
        border = BorderStroke(1.2.dp, accent.copy(alpha = if (selected) .95f else .58f)),
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
                        val heights = floatArrayOf(4f, 8f, 12f, 6f, 10f, 5f)
                        for (index in heights.indices) {
                            val height = heights[index]
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
    qualityMode: RopeboundQualityMode = RopeboundQualityMode.AUTO,
    onSoundEnabledChange: (Boolean) -> Unit,
    onAmbienceVolumeChange: (Float) -> Unit,
    onSfxVolumeChange: (Float) -> Unit,
    onHudOpacityChange: (Float) -> Unit,
    onHudEditModeChange: (Boolean) -> Unit,
    onQualityModeChange: (RopeboundQualityMode) -> Unit = {},
    onResetHud: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xBA0D140E)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(.88f),
            shape = RoundedCornerShape(24.dp),
            color = PlaygroundTheme.StoneCard,
            border = BorderStroke(1.5.dp, PlaygroundTheme.MossEmerald.copy(alpha = .78f)),
            shadowElevation = 18.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("TEAM VS TEAM · IN GAME", color = PlaygroundTheme.MossEmerald, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text("Game settings", color = PlaygroundTheme.TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.height(40.dp).semantics { contentDescription = "Close game settings" },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = PlaygroundTheme.StoneRow, contentColor = PlaygroundTheme.TextPrimary),
                        border = BorderStroke(1.dp, PlaygroundTheme.StoneBorder),
                    ) { Text("×", fontSize = 21.sp) }
                }

                Canvas(modifier = Modifier.fillMaxWidth().height(10.dp)) {
                    val centerY = size.height / 2f
                    drawLine(PlaygroundTheme.MossEmerald.copy(alpha = .58f), Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.dp.toPx())
                    val spacing = size.width / 5f
                    for (index in 1..4) drawCircle(PlaygroundTheme.TimberGold, 3.dp.toPx(), Offset(spacing * index, centerY))
                }

                Text("Graphics quality", color = PlaygroundTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Auto adapts to display refresh rate (1.6x vsync step-down, 1.15x step-up)",
                    color = PlaygroundTheme.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val modes = listOf(
                        RopeboundQualityMode.AUTO to "Auto",
                        RopeboundQualityMode.LOW to "Low",
                        RopeboundQualityMode.MEDIUM to "Medium",
                        RopeboundQualityMode.HIGH to "High",
                    )
                    for ((mode, label) in modes) {
                        val selected = qualityMode == mode
                        OutlinedButton(
                            onClick = { onQualityModeChange(mode) },
                            modifier = Modifier.weight(1f).height(38.dp).semantics { contentDescription = "Graphics quality $label" },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (selected) PlaygroundTheme.StoneCardSelected else PlaygroundTheme.StoneRow,
                                contentColor = if (selected) PlaygroundTheme.TimberGold else PlaygroundTheme.TextPrimary,
                            ),
                            border = BorderStroke(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) PlaygroundTheme.MossEmerald else PlaygroundTheme.StoneBorder,
                            ),
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                maxLines = 1,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Game audio", color = PlaygroundTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Soft game music under voice chat, plus effects", color = PlaygroundTheme.TextSecondary, fontSize = 12.sp)
                    }
                    Switch(
                        checked = soundEnabled,
                        onCheckedChange = onSoundEnabledChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PlaygroundTheme.StoneDark,
                            checkedTrackColor = PlaygroundTheme.MossEmerald,
                        ),
                    )
                }
                Text("Background music · ${(ambienceVolume * 100).toInt()}%", color = PlaygroundTheme.TextPrimary, fontSize = 12.sp)
                Slider(
                    value = ambienceVolume,
                    onValueChange = onAmbienceVolumeChange,
                    enabled = soundEnabled,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = PlaygroundTheme.TimberGold,
                        activeTrackColor = PlaygroundTheme.MossEmerald,
                        inactiveTrackColor = PlaygroundTheme.TrackInactive,
                    ),
                )
                Text("Gameplay effects · ${(sfxVolume * 100).toInt()}%", color = PlaygroundTheme.TextPrimary, fontSize = 12.sp)
                Slider(
                    value = sfxVolume,
                    onValueChange = onSfxVolumeChange,
                    enabled = soundEnabled,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = PlaygroundTheme.TimberGold,
                        activeTrackColor = PlaygroundTheme.MossEmerald,
                        inactiveTrackColor = PlaygroundTheme.TrackInactive,
                    ),
                )
                Text("HUD transparency · ${(hudOpacity * 100).toInt()}%", color = PlaygroundTheme.TextPrimary, fontSize = 12.sp)
                Slider(
                    value = hudOpacity,
                    onValueChange = onHudOpacityChange,
                    valueRange = .35f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = PlaygroundTheme.TimberGold,
                        activeTrackColor = PlaygroundTheme.MossEmerald,
                        inactiveTrackColor = PlaygroundTheme.TrackInactive,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Customize HUD", color = PlaygroundTheme.TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Move the joystick, jump, and brace controls after closing settings", color = PlaygroundTheme.TextSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = hudEditMode,
                        onCheckedChange = onHudEditModeChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = PlaygroundTheme.StoneDark,
                            checkedTrackColor = PlaygroundTheme.MossEmerald,
                        ),
                    )
                }
                Text(
                    "Voice chat stays connected. Your controls are released while settings are open.",
                    color = PlaygroundTheme.TextSecondary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = onReset,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PlaygroundTheme.TextPrimary),
                            border = BorderStroke(1.dp, PlaygroundTheme.StoneBorder),
                        ) { Text("Reset audio") }
                        OutlinedButton(
                            onClick = onResetHud,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PlaygroundTheme.TextPrimary),
                            border = BorderStroke(1.dp, PlaygroundTheme.StoneBorder),
                        ) { Text("Reset HUD") }
                    }
                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PlaygroundTheme.MossEmerald,
                            contentColor = PlaygroundTheme.StoneDark,
                        ),
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
                        0f to Color(0xB30E1810),
                        .30f to Color(0x260E1810),
                        .62f to Color(0x160E1810),
                        1f to Color(0xD90E1810),
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
                    "ONLINE MULTIPLAYER",
                    color = PlaygroundTheme.TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    "TEAM VS TEAM",
                    modifier = Modifier.graphicsLayer { scaleX = logoPulse; scaleY = logoPulse },
                    color = PlaygroundTheme.MossEmerald,
                    fontSize = if (isLandscape) 26.sp else 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth(if (isLandscape) .56f else .94f),
                shape = RoundedCornerShape(22.dp),
                color = PlaygroundTheme.StoneCard,
                border = BorderStroke(1.2.dp, PlaygroundTheme.MossEmerald.copy(alpha = .62f)),
                shadowElevation = 14.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("LOADING PLAYGROUND", color = PlaygroundTheme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 1.1.sp)
                    Text(
                        if (worldReady) "Course ready · joining with $teamSize runners…"
                        else "Syncing your squad and setting the bridge…",
                        color = PlaygroundTheme.TextSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                        val barHeight = size.height * .48f
                        drawRoundRect(Color.White.copy(alpha = .18f), Offset.Zero, size, CornerRadius(size.height / 2f))
                        drawRoundRect(
                            PlaygroundTheme.MossEmerald,
                            Offset.Zero,
                            Size(size.width * trackProgress, barHeight),
                            CornerRadius(barHeight / 2f),
                        )
                        val headX = size.width * trackProgress
                        drawCircle(PlaygroundTheme.TimberGold, barHeight * .52f, Offset(headX.coerceAtMost(size.width - barHeight), size.height / 2f))
                    }
                    Text(
                        "Stay together to keep the rope steady.",
                        color = PlaygroundTheme.MossBright,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Text(
                if (worldReady) "YOUR TEAM IS READY" else "YOUR TEAM IS GETTING READY",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = PlaygroundTheme.TextSecondary,
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

internal enum class GameControlIcon { Jump, Brace }

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
) {
    val active = pressed || highlighted
    val shieldPath = remember { Path() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        interactionSource = interactionSource,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) PlaygroundTheme.MossEmerald else PlaygroundTheme.StoneDark,
            contentColor = if (active) PlaygroundTheme.StoneDark else PlaygroundTheme.TextPrimary,
        ),
        border = BorderStroke(
            1.8.dp,
            if (active) PlaygroundTheme.TimberGold else PlaygroundTheme.StoneBorder,
        ),
    ) {
        Canvas(Modifier.size(25.dp)) {
            val ink = if (active) PlaygroundTheme.StoneDark else PlaygroundTheme.TimberGold
            val cx = size.width / 2f
            val cy = size.height / 2f
            val stroke = 2.7.dp.toPx()
            when (icon) {
                GameControlIcon.Jump -> {
                    drawLine(ink, Offset(cx, cy + 8.dp.toPx()), Offset(cx, cy - 7.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - 7.dp.toPx()), Offset(cx - 6.dp.toPx(), cy - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx, cy - 7.dp.toPx()), Offset(cx + 6.dp.toPx(), cy - 1.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx - 9.dp.toPx(), cy + 9.dp.toPx()), Offset(cx + 9.dp.toPx(), cy + 9.dp.toPx()), stroke, cap = StrokeCap.Round)
                }
                GameControlIcon.Brace -> {
                    shieldPath.reset()
                    shieldPath.moveTo(cx, cy - 10.dp.toPx())
                    shieldPath.lineTo(cx + 8.dp.toPx(), cy - 6.dp.toPx())
                    shieldPath.lineTo(cx + 7.dp.toPx(), cy + 2.dp.toPx())
                    shieldPath.quadraticTo(cx + 5.dp.toPx(), cy + 7.dp.toPx(), cx, cy + 10.dp.toPx())
                    shieldPath.quadraticTo(cx - 5.dp.toPx(), cy + 7.dp.toPx(), cx - 7.dp.toPx(), cy + 2.dp.toPx())
                    shieldPath.lineTo(cx - 8.dp.toPx(), cy - 6.dp.toPx())
                    shieldPath.close()
                    drawPath(shieldPath, ink, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    drawLine(ink, Offset(cx - 3.dp.toPx(), cy), Offset(cx - 0.5f.dp.toPx(), cy + 3.dp.toPx()), stroke, cap = StrokeCap.Round)
                    drawLine(ink, Offset(cx - 0.5f.dp.toPx(), cy + 3.dp.toPx()), Offset(cx + 4.dp.toPx(), cy - 3.dp.toPx()), stroke, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
internal fun RaceMarker(
    label: String,
    progress: Float,
    modifier: Modifier = Modifier,
    valueColor: Color = PlaygroundTheme.MossEmerald,
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
            color = PlaygroundTheme.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
            fontSize = 12.sp,
            color = if (isTension && progress >= .8f) PlaygroundTheme.RivalCoral else valueColor,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HudStatDivider() {
    Box(
        Modifier.padding(horizontal = 5.dp).width(1.dp).height(20.dp)
            .background(PlaygroundTheme.StoneBorder.copy(alpha = .52f)),
    )
}
