import { WebSocketServer } from 'ws';

/** Mount the Ropebound API independently from the voice-room token API. */
export function mountRopeboundRoutes(app, ropebound, requireRoomParticipant, server) {
  attachRealtimeGameSocket(server, ropebound, requireRoomParticipant);
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

  // Advance physics at 50Hz (20ms) for crisp server responsiveness.
  const gameTick = setInterval(() => {
    for (const roomCode of ropebound.roomCodes()) ropebound.tick(roomCode, 0.020);
  }, 20);
  gameTick.unref();

  const gamePresenceCleanup = setInterval(() => ropebound.cleanupStalePlayers(), 2_000);
  gamePresenceCleanup.unref();
}

function attachRealtimeGameSocket(server, ropebound, requireRoomParticipant) {
  // Disable perMessageDeflate so small real-time JSON packets are never delayed by zlib buffering.
  const webSockets = new WebSocketServer({ noServer: true, maxPayload: 4_096, perMessageDeflate: false });

  server.on('upgrade', (request, socket, head) => {
    const requestUrl = new URL(request.url || '/', 'http://localhost');
    if (requestUrl.pathname !== '/games/realtime') {
      socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }

    const roomCode = readText(requestUrl.searchParams.get('roomCode'), 32).toUpperCase();
    let participant;
    try {
      participant = requireRoomParticipant(
        { get: (header) => request.headers[header.toLowerCase()] },
        roomCode,
      );
    } catch {
      socket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
      socket.destroy();
      return;
    }

    // Disable Nagle's algorithm (TCP_NODELAY) so small real-time WebSocket frames
    // (inputs, pings, and 40Hz snapshots) are sent immediately without 20-40ms TCP buffering.
    socket.setNoDelay?.(true);

    webSockets.handleUpgrade(request, socket, head, (client) => {
      webSockets.emit('connection', client, request, { roomCode, participant });
    });
  });

  webSockets.on('connection', (client, _request, { roomCode, participant }) => {
    client._socket?.setNoDelay?.(true);
    try {
      ropebound.heartbeat(roomCode, participant.identity);
    } catch {
      try {
        ropebound.join(roomCode, participant.identity, participant.name);
      } catch {
        // ignore join error if race already running or full
      }
    }

    const sendState = () => {
      if (client.readyState !== WebSocket.OPEN) return;
      try {
        client.send(JSON.stringify(ropebound.snapshot(roomCode, participant.identity)));
      } catch {
        client.close(1011, 'Game state is unavailable');
      }
    };

    sendState();
    const stateInterval = setInterval(sendState, 25);

    client.on('message', (raw) => {
      let message;
      try {
        message = JSON.parse(raw.toString());
      } catch {
        client.close(1007, 'Invalid game message');
        return;
      }

      try {
        if (message.type === 'ping') {
          const reportedPingMs = Number.isFinite(message.pingMs) ? message.pingMs : null;
          ropebound.heartbeat(roomCode, participant.identity, reportedPingMs);
          client.send(JSON.stringify({ type: 'pong', clientSentAt: message.clientSentAt }));
        } else if (message.type === 'input') {
          ropebound.input(roomCode, participant.identity, message);
          if (message.jump === true) {
            sendState();
          }
        }
      } catch (error) {
        client.send(JSON.stringify({
          type: 'error',
          message: error instanceof Error ? error.message : 'Game message could not be processed.',
        }));
      }
    });

    client.on('close', () => clearInterval(stateInterval));
    client.on('error', () => clearInterval(stateInterval));
  });
}

function readText(value, maximumLength) {
  if (typeof value !== 'string') return '';
  return value.trim().slice(0, maximumLength);
}
