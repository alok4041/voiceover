package com.squadvoice.app.game

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.squadvoice.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal sealed interface RopeboundRealtimeEvent {
    data class State(val snapshot: RopeboundSnapshot) : RopeboundRealtimeEvent
    data class Ping(val roundTripMs: Int) : RopeboundRealtimeEvent
    data class ServerError(val message: String) : RopeboundRealtimeEvent
}

internal data class RopeboundPlayer(
    val id: String,
    val name: String,
    val ready: Boolean,
    val progress: Float,
    val lateralOffset: Float = 0f,
    val height: Float,
    val velocity: Float,
    val grounded: Boolean,
    val jumping: Boolean,
    val bracing: Boolean,
    val host: Boolean,
    val pingMs: Int?,
    val online: Boolean = true,
)

internal data class RopeboundTeam(
    val count: Int,
    val progress: Float,
    val ropeTension: Float,
    val surgeCharge: Float,
    val surgeActive: Boolean,
    val surgeCount: Int,
    val rescues: Int,
    val players: List<RopeboundPlayer>,
    val spectators: List<String>,
)

internal data class RopeboundObstacle(
    val id: String,
    val label: String,
    val action: String,
    val hint: String,
    val start: Float,
    val end: Float,
)

internal data class RopeboundHazard(
    val label: String,
    val action: String,
    val hint: String,
    val distance: Float,
    val active: Boolean,
)

internal data class RopeboundEffect(
    val id: Long,
    val type: String,
    val progress: Float,
)

internal data class RopeboundSnapshot(
    val phase: String,
    val teamA: RopeboundTeam,
    val teamB: RopeboundTeam,
    val viewerTeam: String?,
    val viewerSpectating: Boolean,
    val isHost: Boolean,
    val freeTeamSwitching: Boolean,
    val checkpointA: Float,
    val checkpointB: Float,
    val winner: String?,
    val notice: String,
    val hazard: RopeboundHazard?,
    val effect: RopeboundEffect?,
    val obstacles: List<RopeboundObstacle>,
    val durationSeconds: Int,
    val surgesA: Int,
    val surgesB: Int,
)

internal class RopeboundApi(
    private val baseUrl: String = BuildConfig.TOKEN_SERVER_URL,
) {
    private val realtimeSocket = AtomicReference<WebSocket?>(null)

    companion object {
        private val realtimeClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun join(roomCode: String, participantToken: String): RopeboundSnapshot =
        post("/games/join", roomCode, participantToken)

    suspend fun state(roomCode: String, participantToken: String): RopeboundSnapshot =
        request("GET", "/games/state?roomCode=${roomCode.encodeUrl()}", roomCode, participantToken)

    suspend fun heartbeat(roomCode: String, participantToken: String, pingMs: Int?): RopeboundSnapshot {
        val body = JSONObject()
        if (pingMs != null) body.put("pingMs", pingMs.coerceIn(0, 2_000))
        return post("/games/heartbeat", roomCode, participantToken, body)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(roomCode: String, participantToken: String): Flow<RopeboundRealtimeEvent> = callbackFlow {
        val socketBase = baseUrl.trimEnd('/')
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        val request = Request.Builder()
            .url("$socketBase/games/realtime?roomCode=${roomCode.encodeUrl()}")
            .header("Authorization", "Bearer $participantToken")
            .build()
        val socket = realtimeClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    realtimeSocket.set(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        when (message.optString("type")) {
                            "pong" -> {
                                val sentAt = message.optLong("clientSentAt", -1L)
                                if (sentAt >= 0L) {
                                    val elapsed = (SystemClock.elapsedRealtime() - sentAt).coerceIn(0L, 5_000L).toInt()
                                    trySend(RopeboundRealtimeEvent.Ping(elapsed))
                                }
                            }
                            "error" -> trySend(
                                RopeboundRealtimeEvent.ServerError(
                                    message.optString("message", "The game server rejected an update."),
                                ),
                            )
                            else -> trySend(RopeboundRealtimeEvent.State(parseSnapshot(message)))
                        }
                    } catch (failure: Throwable) {
                        close(IllegalStateException("The game server sent an invalid real-time update.", failure))
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    realtimeSocket.compareAndSet(webSocket, null)
                    if (isActive) close(IllegalStateException("The game update connection closed."))
                }

                override fun onFailure(webSocket: WebSocket, failure: Throwable, response: Response?) {
                    realtimeSocket.compareAndSet(webSocket, null)
                    if (isActive) close(failure)
                }
            },
        )
        realtimeSocket.set(socket)
        awaitClose {
            realtimeSocket.compareAndSet(socket, null)
            socket.close(1000, "Leaving game screen")
        }
    }

    fun heartbeat(pingMs: Int?): Boolean = realtimeSocket.get()?.send(
        JSONObject()
            .put("type", "ping")
            .put("clientSentAt", SystemClock.elapsedRealtime())
            .put("pingMs", pingMs ?: JSONObject.NULL)
            .toString(),
    ) == true

    suspend fun chooseTeam(roomCode: String, participantToken: String, team: String): RopeboundSnapshot =
        post("/games/team", roomCode, participantToken, JSONObject().put("team", team))

    suspend fun setFreeTeamSwitching(roomCode: String, participantToken: String, enabled: Boolean): RopeboundSnapshot =
        post("/games/team-switching", roomCode, participantToken, JSONObject().put("enabled", enabled))

    suspend fun swapPlayers(
        roomCode: String,
        participantToken: String,
        playerId: String,
        otherPlayerId: String,
    ): RopeboundSnapshot = post(
        "/games/swap-team",
        roomCode,
        participantToken,
        JSONObject().put("playerId", playerId).put("otherPlayerId", otherPlayerId),
    )

    suspend fun setReady(roomCode: String, participantToken: String, ready: Boolean): RopeboundSnapshot =
        post("/games/ready", roomCode, participantToken, JSONObject().put("ready", ready))

    suspend fun start(roomCode: String, participantToken: String): RopeboundSnapshot =
        post("/games/start", roomCode, participantToken)

    suspend fun rematch(roomCode: String, participantToken: String): RopeboundSnapshot =
        post("/games/rematch", roomCode, participantToken)

    fun input(
        seq: Long,
        move: Float = 0f,
        steer: Float = 0f,
        jump: Boolean = false,
        brace: Boolean = false,
    ): Boolean = realtimeSocket.get()?.send(
        JSONObject()
                .put("type", "input")
                .put("seq", seq)
                .put("move", move.coerceIn(-1f, 1f))
                .put("steer", steer.coerceIn(-1f, 1f))
                .put("jump", jump)
                .put("brace", brace)
                .toString(),
    ) == true

    suspend fun leave(roomCode: String, participantToken: String) {
        requestJson("POST", "/games/leave", roomCode, participantToken, JSONObject().put("roomCode", roomCode))
    }

    private suspend fun post(
        path: String,
        roomCode: String,
        participantToken: String,
        extra: JSONObject = JSONObject(),
    ): RopeboundSnapshot {
        extra.put("roomCode", roomCode)
        return request("POST", path, roomCode, participantToken, extra)
    }

    private suspend fun request(
        method: String,
        path: String,
        roomCode: String,
        participantToken: String,
        body: JSONObject? = null,
    ): RopeboundSnapshot = parseSnapshot(requestJson(method, path, roomCode, participantToken, body))

    private suspend fun requestJson(
        method: String,
        path: String,
        roomCode: String,
        participantToken: String,
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Authorization", "Bearer $participantToken")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }
                .getOrNull()
            if (json == null) {
                val message = when {
                    status == 404 -> "The game API route was not found (HTTP 404). Render may need the latest game-server deployment."
                    status in 500..599 -> "The game server is unavailable (HTTP $status). Try again shortly or check its Render deployment."
                    status in 200..299 -> "The game server returned an unexpected response (HTTP $status). Check that Render has the latest game-server deployment."
                    else -> "The game server returned a non-JSON response (HTTP $status). Check the Render game-server deployment."
                }
                throw IllegalStateException(message)
            }
            if (status !in 200..299) {
                throw IllegalStateException(
                    json.optString("error").takeIf(String::isNotBlank)
                        ?: "Couldn't connect to the game lobby (HTTP $status).",
                )
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSnapshot(json: JSONObject): RopeboundSnapshot {
        val teams = json.getJSONObject("teams")
        val checkpoints = json.optJSONObject("checkpoints") ?: JSONObject()
        val hazardJson = json.optJSONObject("hazard")
        val effectJson = json.optJSONObject("effect")
        val obstaclesJson = json.optJSONArray("obstacles")
        val obstacles = buildList {
            if (obstaclesJson != null) {
                for (index in 0 until obstaclesJson.length()) {
                    val obstacle = obstaclesJson.optJSONObject(index) ?: continue
                    add(
                        RopeboundObstacle(
                            id = obstacle.optString("id"),
                            label = obstacle.optString("label", "Obstacle"),
                            action = obstacle.optString("action"),
                            hint = obstacle.optString("hint"),
                            start = obstacle.optDouble("start").toFloat(),
                            end = obstacle.optDouble("end").toFloat(),
                        ),
                    )
                }
            }
        }
        return RopeboundSnapshot(
            phase = json.optString("phase", "lobby"),
            teamA = parseTeam(teams.optJSONObject("A") ?: JSONObject()),
            teamB = parseTeam(teams.optJSONObject("B") ?: JSONObject()),
            viewerTeam = json.optString("viewerTeam").takeIf { it == "A" || it == "B" },
            viewerSpectating = json.optBoolean("viewerSpectating"),
            isHost = json.optBoolean("isHost"),
            freeTeamSwitching = json.optBoolean("freeTeamSwitching"),
            checkpointA = checkpoints.optDouble("A", 8.0).toFloat(),
            checkpointB = checkpoints.optDouble("B", 8.0).toFloat(),
            winner = json.optString("winner").takeIf { it == "A" || it == "B" || it == "tie" },
            notice = json.optString("notice"),
            hazard = hazardJson?.let {
                RopeboundHazard(
                    label = it.optString("label", "Obstacle"),
                    action = it.optString("action"),
                    hint = it.optString("hint"),
                    distance = it.optDouble("distance").toFloat(),
                    active = it.optBoolean("active"),
                )
            },
            effect = effectJson?.let {
                RopeboundEffect(
                    id = it.optLong("id"),
                    type = it.optString("type"),
                    progress = it.optDouble("progress").toFloat(),
                )
            },
            obstacles = obstacles,
            durationSeconds = json.optInt("durationSeconds"),
            surgesA = json.optJSONObject("surges")?.optInt("A") ?: 0,
            surgesB = json.optJSONObject("surges")?.optInt("B") ?: 0,
        )
    }

    private fun parseTeam(json: JSONObject): RopeboundTeam {
        val playersJson = json.optJSONArray("players")
        val players = buildList {
            if (playersJson != null) {
                for (index in 0 until playersJson.length()) {
                    val player = playersJson.optJSONObject(index) ?: continue
                    add(
                        RopeboundPlayer(
                            id = player.optString("id"),
                            name = player.optString("name", "Player"),
                            ready = player.optBoolean("ready"),
                            progress = player.optDouble("progress").toFloat(),
                            lateralOffset = player.optDouble("lateralOffset").toFloat(),
                            height = player.optDouble("height").toFloat(),
                            velocity = player.optDouble("velocity").toFloat(),
                            grounded = player.optBoolean("grounded", true),
                            jumping = player.optBoolean("jumping"),
                            bracing = player.optBoolean("bracing"),
                            host = player.optBoolean("host"),
                            pingMs = if (player.has("pingMs") && !player.isNull("pingMs")) {
                                player.optInt("pingMs").coerceIn(0, 2_000)
                            } else null,
                            online = player.optBoolean("online", true),
                        ),
                    )
                }
            }
        }
        return RopeboundTeam(
            count = json.optInt("count", players.size),
            progress = json.optDouble("progress").toFloat(),
            ropeTension = json.optDouble("ropeTension").toFloat(),
            surgeCharge = json.optDouble("surgeCharge").toFloat(),
            surgeActive = json.optBoolean("surgeActive"),
            surgeCount = json.optInt("surgeCount"),
            rescues = json.optInt("rescues", 3),
            players = players,
            spectators = json.optJSONArray("spectators")?.let { list ->
                buildList {
                    for (index in 0 until list.length()) add(list.optString(index))
                }
            }.orEmpty(),
        )
    }
}

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

internal fun participantIdentity(token: String): String = runCatching {
    val payload = token.split('.')[1]
    JSONObject(String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).optString("sub")
}.getOrDefault("")
