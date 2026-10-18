package com.squadvoice.app.game

internal fun localPreviewSnapshot(): RopeboundSnapshot {
    val names = listOf("Alex", "Jordan", "Sam", "Morgan")
    val players = names.mapIndexed { index, name ->
        RopeboundPlayer(
            id = "preview${index + 1}",
            name = name,
            ready = true,
            progress = .448f - index * .011f,
            lateralOffset = listOf(-.18f, .16f, -.14f, .20f)[index],
            height = 0f,
            velocity = 1.4f,
            grounded = true,
            jumping = false,
            bracing = false,
            host = index == 0,
            pingMs = listOf(32, 45, 38, 52)[index],
        )
    }
    val ownTeam = RopeboundTeam(
        count = players.size,
        progress = .445f,
        ropeTension = .24f,
        surgeCharge = .42f,
        surgeActive = false,
        surgeCount = 0,
        rescues = 3,
        players = players,
        spectators = emptyList(),
    )
    val rivals = ownTeam.copy(
        progress = .39f,
        players = emptyList(),
    )
    return RopeboundSnapshot(
        phase = "race",
        teamA = ownTeam,
        teamB = rivals,
        viewerTeam = "A",
        viewerSpectating = false,
        isHost = true,
        freeTeamSwitching = false,
        checkpointA = 40f,
        checkpointB = 30f,
        winner = null,
        notice = "Local 3D preview · move with joystick & drag screen to orbit 360°",
        hazard = null,
        effect = null,
        obstacles = listOf(
            RopeboundObstacle("pit", "Spike pit", "jump", "Jump across the spike pit", 23f, 27.5f),
            RopeboundObstacle("boulder", "Path boulder", "jump", "Jump over the stone boulder", 36f, 38.5f),
            RopeboundObstacle("river-bridge", "Rope bridge", "balance", "Cross the moving bridge", 43f, 49f),
            RopeboundObstacle("river-gap", "River gap", "jump", "Leap over the river gap", 56f, 61f),
            RopeboundObstacle("wind-tunnel", "Wind tunnel", "brace", "Brace against the gusts", 67f, 73.5f),
            RopeboundObstacle("lava", "Fire pit", "jump", "Jump past the lava", 84f, 90.5f),
        ),
        durationSeconds = 0,
        surgesA = 0,
        surgesB = 0,
    )
}

internal fun localPreviewLobbySnapshot(): RopeboundSnapshot {
    val race = localPreviewSnapshot()
    val demoPlayers = race.teamA.players
    val teamAPlayers = demoPlayers.take(2)
    val teamBPlayers = demoPlayers.drop(2)
    return race.copy(
        phase = "lobby",
        teamA = race.teamA.copy(count = teamAPlayers.size, players = teamAPlayers),
        teamB = race.teamB.copy(count = teamBPlayers.size, players = teamBPlayers),
        viewerTeam = "A",
        freeTeamSwitching = true,
        notice = "Local preview · team changes are simulated",
    )
}

internal fun movePreviewPlayer(state: RopeboundSnapshot, playerId: String, targetTeam: String): RopeboundSnapshot {
    if (targetTeam != "A" && targetTeam != "B") return state
    val inA = state.teamA.players.firstOrNull { it.id == playerId }
    val inB = state.teamB.players.firstOrNull { it.id == playerId }
    if (inA != null && targetTeam == "B") return state.copy(
        teamA = state.teamA.copy(count = (state.teamA.count - 1).coerceAtLeast(0), players = state.teamA.players - inA),
        teamB = state.teamB.copy(count = state.teamB.count + 1, players = state.teamB.players + inA),
        viewerTeam = targetTeam,
    )
    if (inB != null && targetTeam == "A") return state.copy(
        teamB = state.teamB.copy(count = (state.teamB.count - 1).coerceAtLeast(0), players = state.teamB.players - inB),
        teamA = state.teamA.copy(count = state.teamA.count + 1, players = state.teamA.players + inB),
        viewerTeam = targetTeam,
    )
    return state
}

internal fun swapPreviewPlayers(state: RopeboundSnapshot, playerId: String, otherPlayerId: String): RopeboundSnapshot {
    val playerA = state.teamA.players.firstOrNull { it.id == playerId }
    val playerB = state.teamB.players.firstOrNull { it.id == playerId }
    val otherA = state.teamA.players.firstOrNull { it.id == otherPlayerId }
    val otherB = state.teamB.players.firstOrNull { it.id == otherPlayerId }
    if (playerA != null && otherB != null) return state.copy(
        teamA = state.teamA.copy(players = state.teamA.players.map { if (it.id == playerId) otherB else it }),
        teamB = state.teamB.copy(players = state.teamB.players.map { if (it.id == otherPlayerId) playerA else it }),
    )
    if (playerB != null && otherA != null) return state.copy(
        teamB = state.teamB.copy(players = state.teamB.players.map { if (it.id == playerId) otherA else it }),
        teamA = state.teamA.copy(players = state.teamA.players.map { if (it.id == otherPlayerId) playerB else it }),
    )
    return state
}

internal fun setPreviewReady(state: RopeboundSnapshot, playerId: String, ready: Boolean): RopeboundSnapshot = state.copy(
    teamA = state.teamA.copy(players = state.teamA.players.map { if (it.id == playerId) it.copy(ready = ready) else it }),
    teamB = state.teamB.copy(players = state.teamB.players.map { if (it.id == playerId) it.copy(ready = ready) else it }),
)
