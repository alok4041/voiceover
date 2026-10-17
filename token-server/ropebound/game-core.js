const MAX_PLAYERS = 10;
const MAX_PER_TEAM = 5;
const MIN_PER_TEAM_TO_START = 1;
const COURSE_LENGTH = 100;
const MAX_ROPE_GAP = 7.5;
const TEAM_START_SPACING = MAX_ROPE_GAP / (MAX_PER_TEAM - 1);
const RUN_ACCELERATION = 30;
const MAX_RUN_SPEED = 12;
const GRAVITY = 25;
const JUMP_IMPULSE = 16;
const INPUT_HOLD_MS = 700;
const PLAYER_OFFLINE_GRACE_MS = 8_000;
const PLAYER_RECORD_RETENTION_MS = 30_000;
const TEAM_RESCUES_PER_RACE = 3;
const SURGE_CHARGE_SECONDS = 1.2;
const SURGE_DURATION_MS = 2_400;
const SURGE_COOLDOWN_MS = 8_000;
const CHECKPOINTS = [8, 19, 30, 40, 50, 64, 80];
const RESPAWN_LANES = {
  8: { start: 0.5, end: 8 },
  19: { start: 19, end: 22.5 },
  30: { start: 30, end: 35.5 },
  40: { start: 40, end: 42.5 },
  50: { start: 50, end: 55.5 },
  64: { start: 64, end: 66.5 },
  80: { start: 80, end: 83.5 },
};
const PIT = { id: 'pit', label: 'Spike pit', start: 23, end: 29, leadIn: 4, action: 'jump', hint: 'Build speed and leap over the spikes.' };
const BOULDER = { id: 'boulder', label: 'Path boulder', start: 36, end: 39, leadIn: 4, action: 'jump', hint: 'Jump over the big stone blocking the path.' };
const RIVER_BRIDGE = { id: 'river-bridge', label: 'Rope bridge', start: 43, end: 50, leadIn: 0, action: 'run', hint: 'Keep running across the swaying bridge. Stop too long and it will break.' };
const RIVER_GAP = { id: 'river-gap', label: 'Open river', start: 56, end: 62, leadIn: 3, action: 'jump', hint: 'Run and jump across the open river. Falling in sends your team back to its checkpoint.' };
const WIND_TUNNEL = { id: 'wind-tunnel', label: 'Wind tunnel', start: 67, end: 75, leadIn: 3, action: 'brace', hint: 'Hold Brace together against the gusts.' };
const LAVA = { id: 'lava', label: 'Lava flow', start: 84, end: 92, leadIn: 4, action: 'jump', hint: 'Leap over the lava flow; do not land in it.' };
const HAZARDS = [
  PIT,
  BOULDER,
  RIVER_BRIDGE,
  RIVER_GAP,
  WIND_TUNNEL,
  LAVA,
];
// Keep every randomized hazard clear of checkpoint respawn lanes. Capacity is
// the largest obstacle width that fits in the safe corridor at each center.
const COURSE_SLOTS = [
  { center: 14, capacity: 9.8 },
  { center: 26.25, capacity: 6.8 },
  { center: 37.5, capacity: 4 },
  { center: 46.25, capacity: 7 },
  { center: 59.75, capacity: 8 },
  { center: 73, capacity: 12.8 },
  { center: 91.75, capacity: 16 },
];

/**
 * In-memory authoritative simulation rules for Ropebound.
 * Transport/authentication are deliberately separate so the game can later
 * be attached to an authenticated WebSocket without trusting client positions.
 */
export class RopeboundGameCore {
  #rooms = new Map();

  join(roomCode, identity, name) {
    const code = cleanText(roomCode, 32);
    const playerId = cleanText(identity, 128);
    const playerName = cleanText(name, 24);
    if (!code || !playerId || !playerName) throw new GameError('Room, identity, and name are required.', 'invalid_player');

    let room = this.#rooms.get(code);
    if (!room) {
      room = createRoom(code);
      this.#rooms.set(code, room);
    }
    const existingPlayer = room.players.get(playerId);
    if (existingPlayer) {
      const wasOffline = !existingPlayer.online;
      existingPlayer.online = true;
      existingPlayer.lastSeenAt = Date.now();
      if (wasOffline) existingPlayer.pingMs = null;
      if (room.phase === 'lobby') {
        existingPlayer.inRace = true;
        if (wasOffline) existingPlayer.ready = false;
      } else if (room.phase === 'race' && !existingPlayer.inRace) {
        room.notice = `${existingPlayer.name} reconnected and is spectating until the next round.`;
      }
      if (!room.hostId && existingPlayer.inRace) room.hostId = existingPlayer.id;
      return this.snapshot(code, playerId);
    }
    if (room.phase !== 'lobby') throw new GameError('This race has already started.', 'race_started');
    if (room.players.size >= MAX_PLAYERS) throw new GameError('This game lobby is full.', 'lobby_full');

    const team = smallestTeam(room);
    const teamPosition = teamSize(room, team);
    const player = {
      id: playerId,
      name: playerName,
      team,
      order: room.nextOrder++,
      online: true,
      inRace: true,
      ready: false,
      x: Math.max(0, CHECKPOINTS[0] - teamPosition * TEAM_START_SPACING),
      y: 0,
      vx: 0,
      vy: 0,
      grounded: true,
      input: { seq: -1, move: 0, jump: false, brace: false },
      lastInputAt: 0,
      inputExpiresAt: 0,
      wasJumpPressed: false,
      lastBoulderBumpAt: 0,
      lavaEnteredAt: null,
      waterEnteredAt: null,
      lastSeenAt: Date.now(),
      pingMs: null,
      crumblyEnteredAt: null,
      finished: false,
    };
    room.players.set(playerId, player);
    if (!room.hostId) room.hostId = playerId;
    room.updatedAt = Date.now();
    return this.snapshot(code, playerId);
  }

  heartbeat(roomCode, identity, reportedPingMs = null) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    const wasOffline = !player.online;
    player.online = true;
    player.lastSeenAt = Date.now();
    if (wasOffline) player.pingMs = null;
    if (Number.isFinite(reportedPingMs)) player.pingMs = Math.round(clamp(reportedPingMs, 0, 2_000));
    if (room.phase === 'lobby') {
      player.inRace = true;
      if (wasOffline) player.ready = false;
    } else if (room.phase === 'race' && !player.inRace) {
      room.notice = `${player.name} reconnected and is spectating until the next round.`;
    }
    if (!room.hostId && player.inRace) room.hostId = player.id;
    return this.snapshot(room.code, player.id);
  }

  leave(roomCode, identity) {
    const room = this.#rooms.get(cleanText(roomCode, 32));
    if (!room) return false;
    const removed = room.players.delete(cleanText(identity, 128));
    if (room.players.size === 0) this.#rooms.delete(room.code);
    else {
      if (room.phase === 'lobby') room.updatedAt = Date.now();
      else if (room.phase === 'race') {
        const countA = teamSize(room, 'A');
        const countB = teamSize(room, 'B');
        if (countA === 0 || countB === 0) {
          room.phase = 'results';
          room.winner = countA === 0 && countB === 0 ? null : countA === 0 ? 'B' : 'A';
          room.finishedAt = Date.now();
          room.notice = room.winner ? `Team ${room.winner} wins — the other team left the race.` : 'The race ended because everyone left.';
        }
      }
      if (room.hostId === cleanText(identity, 128)) {
        room.hostId = [...room.players.values()]
          .filter((player) => player.online && player.inRace)
          .sort((left, right) => left.order - right.order)[0]?.id ?? null;
      }
    }
    return removed;
  }

  chooseTeam(roomCode, identity, team) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    if (room.phase !== 'lobby') throw new GameError('Teams are locked after the race starts.', 'teams_locked');
    if (!player.online || !player.inRace) throw new GameError('Reconnect before changing teams.', 'player_offline');
    if (team !== 'A' && team !== 'B') throw new GameError('Choose team A or B.', 'invalid_team');
    if (player.team === team) return this.snapshot(room.code, player.id);
    if (teamSize(room, team) >= MAX_PER_TEAM) throw new GameError('That team is full.', 'team_full');
    player.team = team;
    player.ready = false;
    room.updatedAt = Date.now();
    return this.snapshot(room.code, player.id);
  }

  setReady(roomCode, identity, ready) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    if (room.phase !== 'lobby') throw new GameError('The lobby is closed.', 'lobby_closed');
    if (!player.online || !player.inRace) throw new GameError('Reconnect before readying up.', 'player_offline');
    player.ready = Boolean(ready);
    room.updatedAt = Date.now();
    return this.snapshot(room.code, player.id);
  }

  start(roomCode, identity) {
    const room = this.#requireRoom(roomCode);
    this.#requirePlayer(room, identity);
    if (room.phase !== 'lobby') throw new GameError('The game is not in the lobby.', 'not_in_lobby');
    if (room.hostId !== cleanText(identity, 128)) throw new GameError('Only the game host can start the race.', 'host_required');
    const teams = ['A', 'B'].map((team) => teamPlayers(room, team));
    if (teams.some((members) => members.length < MIN_PER_TEAM_TO_START)) {
      throw new GameError('Put at least one runner on each team before starting.', 'teams_not_ready');
    }
    if (teams.some((members) => members.some((player) => !player.ready))) {
      throw new GameError('Everyone must be ready before the host starts.', 'players_not_ready');
    }
    room.phase = 'race';
    room.startedAt = Date.now();
    room.updatedAt = room.startedAt;
    room.checkpoints = { A: CHECKPOINTS[0], B: CHECKPOINTS[0] };
    room.hazards = buildRandomCourse();
    room.surge = { A: createSurgeState(), B: createSurgeState() };
    room.surges = { A: 0, B: 0 };
    room.rescues = { A: TEAM_RESCUES_PER_RACE, B: TEAM_RESCUES_PER_RACE };
    return this.snapshot(room.code, identity);
  }

  rematch(roomCode, identity) {
    const room = this.#requireRoom(roomCode);
    this.#requirePlayer(room, identity);
    if (room.phase !== 'results') throw new GameError('Finish the current race before starting a rematch.', 'race_not_finished');
    if (room.hostId !== cleanText(identity, 128)) throw new GameError('Only the game host can start a rematch.', 'host_required');

    const now = Date.now();
    for (const team of ['A', 'B']) {
      const members = teamRoster(room, team);
      const positions = formationPositions(CHECKPOINTS[0], members.length);
      members.forEach((player, index) => {
        player.inRace = player.online;
        player.ready = false;
        player.x = positions[index];
        player.y = 0;
        player.vx = 0;
        player.vy = 0;
        player.grounded = true;
        player.input = { ...player.input, move: 0, jump: false, brace: false };
        player.lastInputAt = 0;
        player.inputExpiresAt = 0;
        player.wasJumpPressed = false;
        player.lastBoulderBumpAt = 0;
        player.lavaEnteredAt = null;
        player.waterEnteredAt = null;
        player.crumblyEnteredAt = null;
        player.finished = false;
      });
    }
    room.phase = 'lobby';
    room.winner = null;
    room.startedAt = null;
    room.finishedAt = null;
    room.effect = null;
    room.checkpoints = { A: CHECKPOINTS[0], B: CHECKPOINTS[0] };
    room.hazards = buildRandomCourse();
    room.surge = { A: createSurgeState(), B: createSurgeState() };
    room.surges = { A: 0, B: 0 };
    room.rescues = { A: TEAM_RESCUES_PER_RACE, B: TEAM_RESCUES_PER_RACE };
    room.notice = 'Rematch ready. Teams are set — ready up when everyone is back.';
    room.updatedAt = now;
    return this.snapshot(room.code, identity);
  }

  /** Accept controls only; never accept a position, score, checkpoint, or finish claim. */
  input(roomCode, identity, payload) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    if (room.phase !== 'race') throw new GameError('The race is not running.', 'race_not_running');
    if (!player.online || !player.inRace) throw new GameError('You are spectating this race. Rejoin the team in the next round.', 'player_spectating');
    const seq = Number(payload?.seq);
    const move = Number(payload?.move);
    const now = Date.now();
    if (now - player.lastInputAt < 30) throw new GameError('Controls are arriving too quickly.', 'input_rate_limited');
    if (!Number.isSafeInteger(seq) || seq <= player.input.seq) {
      throw new GameError('Input sequence must increase.', 'stale_input');
    }
    if (!Number.isFinite(move) || ![-1, 0, 1].includes(move)) {
      throw new GameError('Move must be -1, 0, or 1.', 'invalid_input');
    }
    player.input = { seq, move, jump: payload?.jump === true, brace: payload?.brace === true };
    player.lastInputAt = now;
    player.lastSeenAt = now;
    player.inputExpiresAt = now + INPUT_HOLD_MS;
    return true;
  }

  /** Advance one server simulation step. Call at a fixed interval (suggested 20 Hz). */
  tick(roomCode, elapsedSeconds, now = Date.now()) {
    const room = this.#requireRoom(roomCode);
    if (room.phase !== 'race') return null;
    const dt = clamp(Number(elapsedSeconds) || 0, 0, 0.05);

    for (const team of ['A', 'B']) {
      const members = teamPlayers(room, team);
      for (const player of members) {
        const oldX = player.x;
        const input = now <= player.inputExpiresAt ? player.input : { move: 0, jump: false, brace: false };
        const isBracing = input.brace;
        const jumpPressed = input.jump && !player.wasJumpPressed;
        player.wasJumpPressed = input.jump;
        const surgeActive = room.surge[team].activeUntil > now;

        if (jumpPressed && player.grounded) {
          player.vy = JUMP_IMPULSE;
          player.grounded = false;
          room.notice = `${player.name} jumped.`;
        }

        const surgeMultiplier = surgeActive && input.move === 1 ? 1.2 : 1;
        const acceleration = input.move * RUN_ACCELERATION * (isBracing ? 0.55 : 1) * surgeMultiplier;
        player.vx += acceleration * dt;
        if (input.move === 0) player.vx *= Math.max(0, 1 - 8 * dt);
        const hazard = room.hazards.find((item) => player.x >= item.start && player.x <= item.end);
        if (hazard?.action === 'brace' && !isBracing) player.vx -= 7 * dt;
        player.vx = clamp(player.vx, -MAX_RUN_SPEED * 0.45, MAX_RUN_SPEED * (surgeActive ? 1.2 : 1));
        player.x = clamp(player.x + player.vx * dt, 0, COURSE_LENGTH);

        const boulder = room.hazards.find((item) => item.id === BOULDER.id);
        const onBoulder = boulder && player.x >= boulder.start && player.x <= boulder.end;
        if (onBoulder && player.y < 2.4) {
          // A low runner bumps off the boulder and must retry with a jump.
          // While bump recovery is active, keep the runner safely before it.
          player.x = boulder.start - 1.6;
          if (now - player.lastBoulderBumpAt >= 900) {
            player.vx = -1.5;
            player.lastBoulderBumpAt = now;
            room.notice = `${player.name} bumped the stone. Jump over it!`;
          } else {
            player.vx = 0;
          }
        }

        const pit = room.hazards.find((item) => item.id === PIT.id);
        const riverGap = room.hazards.find((item) => item.id === RIVER_GAP.id);
        const inPit = pit && player.x >= pit.start && player.x <= pit.end;
        const inWater = riverGap && player.x >= riverGap.start && player.x <= riverGap.end;
        if (inPit && player.grounded) {
          player.grounded = false;
          player.vy = Math.min(player.vy, -1);
          room.notice = `${player.name} fell into the pit. Regrouping at the last checkpoint.`;
        }
        if (inWater && player.grounded) {
          player.grounded = false;
          player.vy = Math.min(player.vy, -1);
          player.waterEnteredAt = now;
          emitEffect(room, team, player, 'water-splash', now);
          room.notice = `${player.name} dove into the river! The team is regrouping at the checkpoint.`;
        }
        if (!player.grounded) {
          player.vy -= GRAVITY * dt;
          player.y += player.vy * dt;
          if (inWater && player.y <= 0 && player.vy < 0 && player.waterEnteredAt === null) {
            player.waterEnteredAt = now;
            emitEffect(room, team, player, 'water-splash', now);
            room.notice = `${player.name} dove into the river! The team is regrouping at the checkpoint.`;
          }
          if (player.y < -3) {
            this.#recoverTeam(room, team, now, `${player.name} fell off the trail.`, 'team-respawn', player.x / COURSE_LENGTH);
            break;
          }
          if (!inPit && !inWater && player.y <= 0) {
            player.y = 0;
            player.vy = 0;
            player.grounded = true;
          }
        }

        const bridge = room.hazards.find((item) => item.id === RIVER_BRIDGE.id);
        const onCrumblyTiles = bridge && player.x >= bridge.start && player.x <= bridge.end;
        if (onCrumblyTiles) {
          player.crumblyEnteredAt ??= now;
          if (now - player.crumblyEnteredAt >= 800) {
            this.#recoverTeam(room, team, now, 'The rope bridge broke beneath a runner.', 'bridge-break', player.x / COURSE_LENGTH);
            break;
          }
        } else {
          player.crumblyEnteredAt = null;
        }

        const lava = room.hazards.find((item) => item.id === LAVA.id);
        const inLava = lava && player.x >= lava.start && player.x <= lava.end;
        if (inLava && player.grounded) {
          if (player.lavaEnteredAt === null) {
            emitEffect(room, team, player, 'lava-burst', now);
            room.notice = `${player.name} landed in the lava! Jump across it.`;
          }
          player.lavaEnteredAt ??= now;
          if (now - player.lavaEnteredAt >= 400) {
            this.#recoverTeam(room, team, now, `${player.name} landed in the lava.`, 'lava-burst', player.x / COURSE_LENGTH);
            break;
          }
        } else {
          player.lavaEnteredAt = null;
        }

      }
      if (room.phase !== 'race') break;
      this.#enforceRope(room, team);
      this.#updateSquadSurge(room, team, members, dt, now);
      const groupProgress = members.length
        ? members.reduce((value, player) => Math.min(value, player.x), COURSE_LENGTH)
        : 0;
      const passedCheckpoint = [...CHECKPOINTS].reverse().find((point) => groupProgress >= point && point > room.checkpoints[team]);
      if (passedCheckpoint) {
        room.checkpoints[team] = passedCheckpoint;
        room.notice = `Checkpoint ${Math.round(passedCheckpoint)}m reached.`;
      }
    }
    if (room.phase === 'race') {
      const finishedTeams = ['A', 'B'].filter((team) => {
        const members = teamPlayers(room, team);
        return members.length > 0 && members.every((player) => player.x >= COURSE_LENGTH - 0.5);
      });
      if (finishedTeams.length > 0) {
        room.phase = 'results';
        room.winner = finishedTeams.length > 1 ? 'tie' : finishedTeams[0];
        room.finishedAt = now;
        room.notice = room.winner === 'tie' ? 'Both teams crossed the finish together. It is a tie!' : `Team ${room.winner} reached the finish first!`;
      }
    }
    room.updatedAt = now;
    return this.snapshot(room.code, null);
  }

  snapshot(roomCode, viewerIdentity) {
    const room = this.#requireRoom(roomCode);
    const viewer = viewerIdentity ? this.#requirePlayer(room, viewerIdentity) : null;
    const ownTeam = viewer?.team;
    const teams = {};
    for (const team of ['A', 'B']) {
      const members = teamPlayers(room, team);
      const spectators = teamRoster(room, team).filter((player) => !player.inRace);
      const averageProgress = members.length
        ? members.reduce((total, player) => total + player.x / COURSE_LENGTH, 0) / members.length
        : 0;
      const ropeTension = members.slice(1).reduce((maximum, player, index) => {
        const leader = members[index];
        const horizontalGap = leader.x - player.x;
        const verticalGap = leader.y - player.y;
        const length = Math.hypot(horizontalGap, verticalGap);
        return Math.max(maximum, length / MAX_ROPE_GAP);
      }, 0);
      teams[team] = {
        count: members.length,
        progress: Number(averageProgress.toFixed(3)),
        ropeTension: Number(clamp(ropeTension, 0, 1).toFixed(3)),
        surgeCharge: Number(clamp(room.surge[team].chargeSeconds / SURGE_CHARGE_SECONDS, 0, 1).toFixed(3)),
        surgeActive: room.surge[team].activeUntil > Date.now(),
        surgeCount: room.surges[team],
        rescues: room.rescues[team],
          players: room.phase !== 'race' || ownTeam === team ? members.map((player) => ({
            id: player.id,
            name: player.name,
            ready: player.ready,
            progress: Number((player.x / COURSE_LENGTH).toFixed(3)),
            height: Number(player.y.toFixed(2)),
            velocity: Number(player.vx.toFixed(2)),
            grounded: player.grounded,
            jumping: !player.grounded && player.y >= 0,
            bracing: player.input.brace && player.inputExpiresAt >= Date.now(),
            host: player.id === room.hostId,
            pingMs: player.pingMs,
          })) : [],
          ropeLinks: room.phase !== 'race' || ownTeam === team ? Math.max(0, members.length - 1) : 0,
          spectators: room.phase !== 'race' || ownTeam === team
            ? spectators.map((player) => `${player.name} · ${player.online ? 'next round' : 'offline'}`)
            : [],
      };
    }
    return {
      roomCode: room.code,
      phase: room.phase,
      teams,
      viewerTeam: ownTeam ?? null,
      viewerSpectating: room.phase === 'race' && viewer != null && !viewer.inRace,
      isHost: viewerIdentity != null && room.hostId === cleanText(viewerIdentity, 128),
      checkpoints: { ...room.checkpoints },
      winner: room.winner,
      notice: room.notice,
      hazard: getHazardHint(room, ownTeam),
      effect: viewer && room.effect && room.effect.team === ownTeam && Date.now() - room.effect.at <= 1_200
        ? { id: room.effect.id, type: room.effect.type, progress: room.effect.progress }
        : null,
      obstacles: room.phase === 'lobby' ? [] : room.hazards.map(({ id, label, action, hint, start, end }) => ({
        id,
        label,
        action,
        hint,
        start,
        end,
      })),
      durationSeconds: room.startedAt
        ? Math.max(0, Math.floor(((room.finishedAt ?? Date.now()) - room.startedAt) / 1_000))
        : 0,
      surges: { ...room.surges },
      serverTime: Date.now(),
    };
  }

  deleteRoom(roomCode) {
    return this.#rooms.delete(cleanText(roomCode, 32));
  }

  roomCodes() {
    return [...this.#rooms.keys()];
  }

  cleanupStalePlayers(now = Date.now(), graceMs = PLAYER_OFFLINE_GRACE_MS) {
    for (const room of this.#rooms.values()) {
      for (const player of room.players.values()) {
        if (player.online && now - player.lastSeenAt > graceMs) {
          player.online = false;
          player.inRace = false;
          player.ready = false;
          player.pingMs = null;
          player.input = { ...player.input, move: 0, jump: false, brace: false };
          player.inputExpiresAt = 0;
          player.vx = 0;
          player.vy = 0;
          room.notice = room.phase === 'race'
            ? `${player.name} disconnected. Their team can continue; they will spectate until the next round.`
            : `${player.name} disconnected and can reconnect before the race starts.`;
          if (room.hostId === player.id) {
            room.hostId = [...room.players.values()]
              .filter((candidate) => candidate.online && candidate.inRace)
              .sort((left, right) => left.order - right.order)[0]?.id ?? null;
          }
        } else if (!player.online && room.phase !== 'race' && now - player.lastSeenAt > PLAYER_RECORD_RETENTION_MS) {
          room.players.delete(player.id);
        }
      }
      if (room.players.size === 0) {
        this.#rooms.delete(room.code);
      } else if (room.phase === 'race') {
        const countA = teamSize(room, 'A');
        const countB = teamSize(room, 'B');
        if (countA === 0 || countB === 0) {
          room.phase = 'results';
          room.winner = countA === 0 && countB === 0 ? null : countA === 0 ? 'B' : 'A';
          room.finishedAt = now;
          room.notice = 'A team ran out of active runners.';
        }
      }
    }
  }

  #requireRoom(code) {
    const room = this.#rooms.get(cleanText(code, 32));
    if (!room) throw new GameError('Game lobby not found.', 'game_not_found');
    return room;
  }

  #updateSquadSurge(room, team, members, dt, now) {
    const surge = room.surge[team];
    if (surge.activeUntil > now) return;

    const inHazard = members.some((player) =>
      room.hazards.some((hazard) => player.x >= hazard.start && player.x <= hazard.end),
    );
    const ropeTogether = members.slice(1).every((player, index) =>
      Math.abs(members[index].x - player.x) <= MAX_ROPE_GAP * 0.68,
    );
    const runningTogether = members.length >= 2 && members.every((player) =>
      now <= player.inputExpiresAt && player.input.move === 1 && !player.input.brace && player.grounded,
    );

    if (now >= surge.cooldownUntil && runningTogether && ropeTogether && !inHazard) {
      surge.chargeSeconds = Math.min(SURGE_CHARGE_SECONDS, surge.chargeSeconds + dt);
      if (surge.chargeSeconds >= SURGE_CHARGE_SECONDS) {
        surge.chargeSeconds = 0;
        surge.activeUntil = now + SURGE_DURATION_MS;
        surge.cooldownUntil = now + SURGE_DURATION_MS + SURGE_COOLDOWN_MS;
        room.surges[team] += 1;
        const progress = members.reduce((minimum, player) => Math.min(minimum, player.x), COURSE_LENGTH) / COURSE_LENGTH;
        emitEffectAtProgress(room, team, 'team-surge', progress, now);
        room.notice = `Team ${team} found its rhythm! SQUAD SURGE — keep running together!`;
      }
    } else if (now < surge.cooldownUntil) {
      surge.chargeSeconds = 0;
    } else {
      surge.chargeSeconds = Math.max(0, surge.chargeSeconds - dt * 0.75);
    }
  }

  #recoverTeam(room, team, now, cause, effectType, effectProgress) {
    const remaining = room.rescues[team];
    if (remaining <= 0) {
      room.phase = 'results';
      room.winner = team === 'A' ? 'B' : 'A';
      room.finishedAt = now;
      room.notice = `${cause} Team ${team} ran out of rescues. Team ${room.winner} wins!`;
      emitEffectAtProgress(room, team, effectType, effectProgress, now);
      return false;
    }

    room.rescues[team] = remaining - 1;
    this.#resetTeam(room, team);
    emitEffectAtProgress(room, team, effectType, effectProgress, now);
    room.notice = `${cause} Team ${team} regrouped at checkpoint ${room.checkpoints[team]}m · ${room.rescues[team]} rescues left.`;
    return true;
  }

  #requirePlayer(room, identity) {
    const player = room.players.get(cleanText(identity, 128));
    if (!player) throw new GameError('Player is not in this game lobby.', 'player_not_in_game');
    return player;
  }

  #resetTeam(room, team) {
    const members = teamPlayers(room, team);
    room.surge[team] = createSurgeState();
    const positions = formationPositions(room.checkpoints[team], members.length);
    members.forEach((player, index) => {
      player.x = positions[index];
      player.y = 0;
      player.vx = 0;
      player.vy = 0;
      player.grounded = true;
      player.input = { ...player.input, move: 0, jump: false, brace: false };
      player.inputExpiresAt = 0;
      player.wasJumpPressed = false;
      player.crumblyEnteredAt = null;
      player.lavaEnteredAt = null;
      player.waterEnteredAt = null;
      player.finished = false;
    });
  }

  #enforceRope(room, team) {
    const members = teamPlayers(room, team);
    // Revisit adjacent links because correcting one pair can stretch the next.
    // A few constraint passes keep the whole chain inside its length limit.
    for (let pass = 0; pass < members.length; pass += 1) {
      for (let index = 1; index < members.length; index += 1) {
        const leader = members[index - 1];
        const runner = members[index];
        const verticalGap = leader.y - runner.y;
        const horizontalLimit = Math.sqrt(Math.max(0, MAX_ROPE_GAP ** 2 - verticalGap ** 2));
        const gap = leader.x - runner.x;
        if (gap < 0) {
          const overlap = -gap;
          leader.x -= overlap * 0.5;
          runner.x += overlap * 0.5;
          const sharedVelocity = (leader.vx + runner.vx) * 0.5;
          leader.vx = sharedVelocity;
          runner.vx = sharedVelocity;
        } else if (gap > horizontalLimit) {
          const stretch = gap - horizontalLimit;
          leader.x -= stretch * 0.75;
          runner.x += stretch * 0.25;
          const sharedVelocity = (leader.vx + runner.vx) * 0.5;
          leader.vx = Math.min(leader.vx, sharedVelocity);
          runner.vx = Math.max(runner.vx, sharedVelocity);
        }
      }
    }
    for (const player of members) player.x = clamp(player.x, 0, COURSE_LENGTH);
  }
}

export class GameError extends Error {
  constructor(message, code) {
    super(message);
    this.name = 'GameError';
    this.code = code;
  }
}

function createRoom(code) {
  return {
    code,
    phase: 'lobby',
    players: new Map(),
    nextOrder: 0,
    checkpoints: { A: CHECKPOINTS[0], B: CHECKPOINTS[0] },
    hazards: [],
    surge: { A: createSurgeState(), B: createSurgeState() },
    surges: { A: 0, B: 0 },
    rescues: { A: TEAM_RESCUES_PER_RACE, B: TEAM_RESCUES_PER_RACE },
    hostId: null,
    winner: null,
    notice: 'Waiting for teams to ready up.',
    effectSequence: 0,
    effect: null,
    startedAt: null,
    finishedAt: null,
    updatedAt: Date.now(),
  };
}

function emitEffect(room, team, player, type, at) {
  emitEffectAtProgress(room, team, type, player.x / COURSE_LENGTH, at);
}

function emitEffectAtProgress(room, team, type, progress, at) {
  room.effectSequence += 1;
  room.effect = {
    id: room.effectSequence,
    team,
    type,
    progress: Number(clamp(progress, 0, 1).toFixed(3)),
    at,
  };
}

function createSurgeState() {
  return { chargeSeconds: 0, activeUntil: 0, cooldownUntil: 0 };
}

function buildRandomCourse() {
  const shuffled = [...HAZARDS];
  for (let index = shuffled.length - 1; index > 0; index -= 1) {
    const swapIndex = Math.floor(Math.random() * (index + 1));
    [shuffled[index], shuffled[swapIndex]] = [shuffled[swapIndex], shuffled[index]];
  }
  // Place the largest obstacles first so every obstacle fits. Choosing among
  // valid safe corridors randomizes the route without spawning a team inside
  // a hazard after it uses a checkpoint rescue.
  shuffled.sort((left, right) => (right.end - right.start) - (left.end - left.start));
  const availableSlots = [...COURSE_SLOTS];
  return shuffled.map((hazard) => {
    const length = hazard.end - hazard.start;
    const eligible = availableSlots.filter((slot) => slot.capacity >= length);
    const slot = eligible[Math.floor(Math.random() * eligible.length)];
    if (!slot) throw new Error(`No safe course slot fits ${hazard.id}.`);
    availableSlots.splice(availableSlots.indexOf(slot), 1);
    const center = slot.center;
    const start = Number((center - length / 2).toFixed(1));
    return { ...hazard, start, end: Number((start + length).toFixed(1)) };
  }).sort((left, right) => left.start - right.start);
}

function getHazardHint(room, team) {
  if (!team) return null;
  const members = teamPlayers(room, team);
  // Warn for the leading runner so the whole team gets the cue before anyone
  // reaches the obstacle.
  const progress = members.length ? Math.max(...members.map((player) => player.x)) : 0;
  const next = room.hazards.find((hazard) => hazard.end >= progress);
  if (!next) return null;
  return {
    id: next.id,
    label: next.label,
    action: next.action,
    hint: next.hint,
    distance: Math.max(0, Number((next.start - progress).toFixed(1))),
    active: progress >= next.start && progress <= next.end,
  };
}

function formationPositions(checkpoint, count) {
  if (count <= 1) return [checkpoint];
  const lane = RESPAWN_LANES[checkpoint] ?? {
    start: Math.max(0, checkpoint - MAX_ROPE_GAP),
    end: checkpoint,
  };
  const spacing = (lane.end - lane.start) / (count - 1);
  return Array.from({ length: count }, (_, index) => Number((lane.end - index * spacing).toFixed(2)));
}

function teamPlayers(room, team) {
  return teamRoster(room, team).filter((player) => player.inRace && player.online);
}

function teamRoster(room, team) {
  return [...room.players.values()]
    .filter((player) => player.team === team)
    .sort((left, right) => left.order - right.order);
}

function smallestTeam(room) {
  const countA = teamSize(room, 'A');
  const countB = teamSize(room, 'B');
  if (countA >= MAX_PER_TEAM && countB >= MAX_PER_TEAM) throw new GameError('Both teams are full.', 'lobby_full');
  return countA <= countB && countA < MAX_PER_TEAM ? 'A' : 'B';
}

function teamSize(room, team) {
  return teamPlayers(room, team).length;
}

function cleanText(value, maximumLength) {
  return typeof value === 'string' ? value.trim().slice(0, maximumLength) : '';
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}
