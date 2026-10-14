package com.squadvoice.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RoomCredentials(
    val code: String,
    val roomName: String,
    val serverUrl: String,
    val participantToken: String,
)

class RoomApi(
    private val baseUrl: String = BuildConfig.TOKEN_SERVER_URL,
) {
    suspend fun createRoom(roomName: String, userName: String): RoomCredentials =
        post("/rooms/create", JSONObject().put("roomName", roomName).put("userName", userName))

    suspend fun joinRoom(code: String, userName: String): RoomCredentials =
        post("/rooms/join", JSONObject().put("code", code).put("userName", userName))

    private suspend fun post(path: String, payload: JSONObject): RoomCredentials =
        withContext(Dispatchers.IO) {
            val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload.toString()) }

                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val response = runCatching { JSONObject(responseText) }
                    .getOrElse { throw IllegalStateException("The room server returned an invalid response.") }

                if (status !in 200..299) {
                    throw IllegalStateException(response.optString("error", "Could not reach that room."))
                }

                RoomCredentials(
                    code = response.getString("code"),
                    roomName = response.getString("roomName"),
                    serverUrl = response.getString("serverUrl"),
                    participantToken = response.getString("participantToken"),
                )
            } finally {
                connection.disconnect()
            }
        }
}
