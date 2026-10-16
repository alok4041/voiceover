import { createHmac, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';
import { AccessToken, LiveKitAPI } from 'livekit-server-sdk';
import express from 'express';
import { fileURLToPath } from 'node:url';
import { GameError, RopeboundGameCore } from './ropebound/game-core.js';
import { mountRopeboundRoutes } from './ropebound/routes.js';

const requiredEnvironment = ['LIVEKIT_URL', 'LIVEKIT_API_KEY', 'LIVEKIT_API_SECRET'];
const missingEnvironment = requiredEnvironment.filter((key) => !process.env[key]);
if (missingEnvironment.length > 0) {
  console.error(`Missing required environment variables: ${missingEnvironment.join(', ')}`);
  process.exit(1);
}

const app = express();
const port = Number(process.env.PORT || 3000);
const roomCodeLifetimeMs = 12 * 60 * 60 * 1000;
const maximumParticipants = 10;
const codeAlphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const rooms = new Map();
const ropebound = new RopeboundGameCore();
const livekitApiHost = process.env.LIVEKIT_URL
  .replace(/^wss:/i, 'https:')
  .replace(/^ws:/i, 'http:');
const livekit = new LiveKitAPI({
  host: livekitApiHost,
  apiKey: process.env.LIVEKIT_API_KEY,
  secret: process.env.LIVEKIT_API_SECRET,
});

// Looks up a room in memory first. If the server slept or restarted (memory is
// empty), ask LiveKit whether the room is still live and restore it.
async function findRoom(code) {
  const cached = rooms.get(code);
  if (cached && Date.now() - cached.createdAt <= roomCodeLifetimeMs) return cached;
  rooms.delete(code);

  const found = await livekit.room.listRooms([code]);
  const liveRoom = Array.isArray(found) ? found[0] : found?.rooms?.[0];
  if (!liveRoom) return null;

  const restored = { roomName: liveRoom.metadata || code, createdAt: Date.now() };
  rooms.set(code, restored);
  return restored;
}

const roomCodeCleanup = setInterval(() => {
  const now = Date.now();
  for (const [code, room] of rooms) {
    if (now - room.createdAt > roomCodeLifetimeMs) rooms.delete(code);
  }
}, 10 * 60 * 1000);
roomCodeCleanup.unref();

app.disable('x-powered-by');
app.use(express.json({ limit: '10kb' }));
app.use(express.static(fileURLToPath(new URL('./public/', import.meta.url))));

app.get('/health', (_request, response) => response.json({ ok: true }));

// Mount the game feature independently. Its simulation is separate from the
// LiveKit voice-room session and keeps running while the foreground voice
// service maintains audio on Android.
mountRopeboundRoutes(app, ropebound, requireRoomParticipant);

app.post('/rooms/create', async (request, response) => {
  const roomName = readText(request.body?.roomName, 40);
  const userName = readText(request.body?.userName, 24);
  if (!roomName || !userName) {
    return response.status(400).json({ error: 'Enter a room name and your name.' });
  }

  try {
    const code = createRoomCode();
    await livekit.room.createRoom({
      name: code,
      emptyTimeout: 300,
      maxParticipants: maximumParticipants,
      metadata: roomName,
    });
    rooms.set(code, { roomName, createdAt: Date.now() });
    const participantToken = await createParticipantToken(code, userName);
    return response.status(201).json({
      code,
      roomName,
      serverUrl: process.env.LIVEKIT_URL,
      participantToken,
    });
  } catch (error) {
    console.error('Could not create room:', error);
    return response.status(503).json({ error: 'Could not create a room right now. Try again.' });
  }
});

app.post('/rooms/join', async (request, response) => {
  const code = readText(request.body?.code, 6).toUpperCase();
  const userName = readText(request.body?.userName, 24);
  if (!/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/.test(code)) {
    return response.status(400).json({ error: 'Enter a valid six-character room code.' });
  }
  if (!userName) {
    return response.status(400).json({ error: 'Enter your name before joining.' });
  }

  try {
    const room = await findRoom(code);
    if (!room) {
      return response.status(404).json({ error: 'Room not found. Check the code and try again.' });
    }
    const participantToken = await createParticipantToken(code, userName);
    return response.json({
      code,
      roomName: room.roomName,
      serverUrl: process.env.LIVEKIT_URL,
      participantToken,
    });
  } catch (error) {
    console.error('Could not create join token:', error);
    return response.status(503).json({ error: 'Could not join that room right now. Try again.' });
  }
});

app.use((error, _request, response, _next) => {
  if (error instanceof GameError) {
    const status = ['game_not_found', 'player_not_in_game'].includes(error.code)
      ? 404
      : ['invalid_room_code', 'invalid_team', 'invalid_input'].includes(error.code)
        ? 400
        : 409;
    return response.status(status).json({ error: error.message, code: error.code });
  }
  if (error instanceof AuthenticationError) {
    return response.status(401).json({ error: error.message, code: error.code });
  }
  if (error instanceof SyntaxError) {
    return response.status(400).json({ error: 'Request body must be valid JSON.' });
  }
  console.error('Unexpected request error:', error);
  return response.status(500).json({ error: 'Something went wrong. Try again.' });
});

app.listen(port, '0.0.0.0', () => {
  console.log(`SquadVoice token server listening on port ${port}`);
});

function createRoomCode() {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    let code = '';
    for (let index = 0; index < 6; index += 1) {
      code += codeAlphabet[randomInt(codeAlphabet.length)];
    }
    if (!rooms.has(code)) return code;
  }
  throw new Error('Could not allocate a unique room code.');
}

function requireRoomParticipant(request, roomCode) {
  if (!/^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}$/.test(roomCode)) {
    throw new GameError('Enter a valid six-character voice room code.', 'invalid_room_code');
  }
  const authorization = request.get('authorization') || '';
  const match = authorization.match(/^Bearer\s+(.+)$/i);
  if (!match) throw new AuthenticationError('A voice-room participant token is required.', 'missing_token');
  const payload = verifyParticipantToken(match[1]);
  if (payload.video?.roomJoin !== true || payload.video?.room !== roomCode) {
    throw new AuthenticationError('This token is not authorized for that voice room.', 'wrong_room');
  }
  return {
    identity: payload.sub,
    name: payload.name || 'Player',
  };
}

function verifyParticipantToken(token) {
  const parts = token.split('.');
  if (parts.length !== 3) throw new AuthenticationError('The voice-room token is invalid.', 'invalid_token');
  let header;
  let payload;
  try {
    header = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
    payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  } catch {
    throw new AuthenticationError('The voice-room token is invalid.', 'invalid_token');
  }
  if (header.alg !== 'HS256' || !payload.sub || !Number.isFinite(payload.exp) || payload.exp <= Math.floor(Date.now() / 1000)) {
    throw new AuthenticationError('The voice-room token is expired or invalid.', 'expired_token');
  }
  const expected = createHmac('sha256', process.env.LIVEKIT_API_SECRET)
    .update(`${parts[0]}.${parts[1]}`)
    .digest();
  const actual = Buffer.from(parts[2], 'base64url');
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) {
    throw new AuthenticationError('The voice-room token signature is invalid.', 'invalid_signature');
  }
  return payload;
}

async function createParticipantToken(roomCode, userName) {
  const token = new AccessToken(process.env.LIVEKIT_API_KEY, process.env.LIVEKIT_API_SECRET, {
    identity: randomUUID(),
    name: userName,
    ttl: '1h',
  });
  token.addGrant({
    roomJoin: true,
    room: roomCode,
    canPublish: true,
    canSubscribe: true,
  });
  return token.toJwt();
}

function readText(value, maximumLength) {
  if (typeof value !== 'string') return '';
  return value.trim().slice(0, maximumLength);
}

class AuthenticationError extends Error {
  constructor(message, code) {
    super(message);
    this.name = 'AuthenticationError';
    this.code = code;
  }
}

