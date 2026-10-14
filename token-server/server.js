import { randomInt, randomUUID } from 'node:crypto';
import { AccessToken, LiveKitAPI } from 'livekit-server-sdk';
import express from 'express';

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
const livekit = new LiveKitAPI({
  host: process.env.LIVEKIT_URL,
  apiKey: process.env.LIVEKIT_API_KEY,
  secret: process.env.LIVEKIT_API_SECRET,
});

app.disable('x-powered-by');
app.use(express.json({ limit: '10kb' }));

app.get('/health', (_request, response) => response.json({ ok: true }));

app.post('/rooms/create', async (request, response) => {
  const roomName = readText(request.body?.roomName, 40);
  const userName = readText(request.body?.userName, 24);
  if (!roomName || !userName) {
    return response.status(400).json({ error: 'Enter a room name and your name.' });
  }

  try {
    const code = await createRoomCode();
    await livekit.room.createRoom({
      name: code,
      emptyTimeout: 300,
      maxParticipants: maximumParticipants,
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

  const room = rooms.get(code);
  if (!room || Date.now() - room.createdAt > roomCodeLifetimeMs) {
    rooms.delete(code);
    return response.status(404).json({ error: 'Room not found. Check the code and try again.' });
  }

  try {
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
  if (error instanceof SyntaxError) {
    return response.status(400).json({ error: 'Request body must be valid JSON.' });
  }
  console.error('Unexpected request error:', error);
  return response.status(500).json({ error: 'Something went wrong. Try again.' });
});

app.listen(port, '0.0.0.0', () => {
  console.log(`SquadVoice token server listening on port ${port}`);
});

async function createRoomCode() {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    let code = '';
    for (let index = 0; index < 6; index += 1) {
      code += codeAlphabet[randomInt(codeAlphabet.length)];
    }
    if (!rooms.has(code)) return code;
  }
  throw new Error('Could not allocate a unique room code.');
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
