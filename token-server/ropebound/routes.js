/** Mount the Ropebound API independently from the voice-room token API. */
export function mountRopeboundRoutes(app, ropebound, requireRoomParticipant) {
  app.post('/games/join', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.join(roomCode, participant.identity, participant.name));
  });

  app.post('/games/leave', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    ropebound.leave(roomCode, participant.identity);
    return response.json({ ok: true });
  });

  app.post('/games/heartbeat', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.heartbeat(roomCode, participant.identity, request.body?.pingMs));
  });

  app.get('/games/state', (request, response) => {
    const roomCode = readText(request.query?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.snapshot(roomCode, participant.identity));
  });

  app.post('/games/team', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.chooseTeam(roomCode, participant.identity, request.body?.team));
  });

  app.post('/games/team-switching', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.setFreeTeamSwitching(roomCode, participant.identity, request.body?.enabled));
  });

  app.post('/games/swap-team', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    const firstPlayerId = readText(request.body?.playerId, 128);
    const secondPlayerId = readText(request.body?.otherPlayerId, 128);
    return response.json(ropebound.swapTeams(roomCode, participant.identity, firstPlayerId, secondPlayerId));
  });

  app.post('/games/ready', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.setReady(roomCode, participant.identity, request.body?.ready));
  });

  app.post('/games/start', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.start(roomCode, participant.identity));
  });

  app.post('/games/rematch', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    return response.json(ropebound.rematch(roomCode, participant.identity));
  });

  app.post('/games/input', (request, response) => {
    const roomCode = readText(request.body?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    ropebound.input(roomCode, participant.identity, request.body);
    return response.json({ ok: true });
  });

  // SSE carries filtered snapshots to each player; controls use POST /games/input.
  app.get('/games/events', (request, response) => {
    const roomCode = readText(request.query?.roomCode, 32).toUpperCase();
    const participant = requireRoomParticipant(request, roomCode);
    ropebound.snapshot(roomCode, participant.identity);
    response.status(200).set({
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    });
    response.flushHeaders();
    response.write('retry: 1500\n\n');
    const sendState = () => {
      if (response.destroyed || response.writableEnded) return;
      try {
        response.write(`data: ${JSON.stringify(ropebound.snapshot(roomCode, participant.identity))}\n\n`);
      } catch {
        response.end();
      }
    };
    sendState();
    const interval = setInterval(sendState, 100);
    // A GET request body completes immediately; the response stream is what
    // remains open for SSE. Listen to its close event so snapshots continue
    // until the client actually disconnects.
    response.on('close', () => clearInterval(interval));
  });

  const gameTick = setInterval(() => {
    for (const roomCode of ropebound.roomCodes()) ropebound.tick(roomCode, 0.05);
  }, 50);
  gameTick.unref();

  const gamePresenceCleanup = setInterval(() => ropebound.cleanupStalePlayers(), 2_000);
  gamePresenceCleanup.unref();
}

function readText(value, maximumLength) {
  if (typeof value !== 'string') return '';
  return value.trim().slice(0, maximumLength);
}
