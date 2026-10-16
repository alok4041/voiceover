package com.squadvoice.app.game

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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

internal data class RopeboundPlayer(
    val id: String,
    val name: String,
    val ready: Boolean,
    val progress: Float,
    val height: Float,
    val velocity: Float,
    val grounded: Boolean,
    val jumping: Boolean,
    val bracing: Boolean,
    val host: Boolean,
)

internal data class RopeboundTeam(
    val count: Int,
    val progress: Float,
    val ropeTension: Float,
    val players: List<RopeboundPlayer>,
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
    val isHost: Boolean,
    val checkpointA: Float,
    val checkpointB: Float,
    val winner: String?,
    val notice: String,
    val hazard: RopeboundHazard?,
    val effect: RopeboundEffect?,
)

internal class RopeboundApi(
    private val baseUrl: String = BuildConfig.TOKEN_SERVER_URL,
) {
    suspend fun join(roomCode: String, participantToken: String): RopeboundSnapshot =
        post("/games/join", roomCode, participantToken)

    suspend fun state(roomCode: String, participantToken: String): RopeboundSnapshot =
        request("GET", "/games/state?roomCode=${roomCode.encodeUrl()}", roomCode, participantToken)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun events(roomCode: String, participantToken: String): Flow<RopeboundSnapshot> = callbackFlow {
        val connectionRef = AtomicReference<HttpURLConnection?>(null)
        val readerJob = launch(Dispatchers.IO) {
            try {
                val connection = URL(
                    baseUrl.trimEnd('/') + "/games/events?roomCode=${roomCode.encodeUrl()}",
                ).openConnection() as HttpURLConnection
                connectionRef.set(connection)
                connection.requestMethod = "GET"
                connection.connectTimeout = 5_000
                connection.readTimeout = 0
                connection.setRequestProperty("Authorization", "Bearer $participantToken")
                connection.setRequestProperty("Accept", "text/event-stream")
                val status = connection.responseCode
                if (status !in 200..299) {
                    val message = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    val responseJson = runCatching { JSONObject(message.orEmpty()) }.getOrNull()
                    throw IllegalStateException(responseJson?.optString("error") ?: "The game update stream could not connect.")
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("data:")) {
                            val eventJson = JSONObject(line.removePrefix("data:").trim())
                            trySend(parseSnapshot(eventJson))
                        }
                    }
                }
                if (isActive) throw IllegalStateException("The game update stream closed.")
            } catch (failure: Throwable) {
                if (isActive) close(failure)
            } finally {
                connectionRef.getAndSet(null)?.disconnect()
            }
        }
        awaitClose {
            connectionRef.getAndSet(null)?.disconnect()
            readerJob.cancel()
        }
    }

    suspend fun chooseTeam(roomCode: String, participantToken: String, team: String): RopeboundSnapshot =
        post("/games/team", roomCode, participantToken, JSONObject().put("team", team))

    suspend fun setReady(roomCode: String, participantToken: String, ready: Boolean): RopeboundSnapshot =
        post("/games/ready", roomCode, participantToken, JSONObject().put("ready", ready))

    suspend fun start(roomCode: String, participantToken: String): RopeboundSnapshot =
        post("/games/start", roomCode, participantToken)

    suspend fun rematch(roomCode: String, participantToken: String): RopeboundSnapshot =
        post("/games/rematch", roomCode, participantToken)

    suspend fun input(
        roomCode: String,
        participantToken: String,
        seq: Long,
        move: Int = 0,
        jump: Boolean = false,
        brace: Boolean = false,
    ) {
        requestJson(
            "POST",
            "/games/input",
            roomCode,
            participantToken,
            JSONObject().put("seq", seq).put("move", move).put("jump", jump).put("brace", brace),
        )
    }

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
                .getOrElse { throw IllegalStateException("The game server returned an invalid response.") }
            if (status !in 200..299) {
                throw IllegalStateException(json.optString("error", "Couldn't connect to the game lobby."))
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
        return RopeboundSnapshot(
            phase = json.optString("phase", "lobby"),
            teamA = parseTeam(teams.optJSONObject("A") ?: JSONObject()),
            teamB = parseTeam(teams.optJSONObject("B") ?: JSONObject()),
            viewerTeam = json.optString("viewerTeam").takeIf { it == "A" || it == "B" },
            isHost = json.optBoolean("isHost"),
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
                            height = player.optDouble("height").toFloat(),
                            velocity = player.optDouble("velocity").toFloat(),
                            grounded = player.optBoolean("grounded", true),
                            jumping = player.optBoolean("jumping"),
                            bracing = player.optBoolean("bracing"),
                            host = player.optBoolean("host"),
                        ),
                    )
                }
            }
        }
        return RopeboundTeam(
            count = json.optInt("count", players.size),
            progress = json.optDouble("progress").toFloat(),
            ropeTension = json.optDouble("ropeTension").toFloat(),
            players = players,
        )
    }
}

private fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

internal fun participantIdentity(token: String): String = runCatching {
    val payload = token.split('.')[1]
    JSONObject(String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).optString("sub")
}.getOrDefault("")
