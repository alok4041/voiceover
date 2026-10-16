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

/**
 * In-memory authoritative simulation rules for Ropebound.
 * Transport/authentication are deliberately separate so the game can later
 * be attached to an authenticated WebSocket without trusting client positions.
 */
export class RopeboundGameCore {
  #rooms = new Map();

  /** Adds a validated player to a lobby’s smaller team, or returns an existing player’s snapshot. */
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
    if (room.players.has(playerId)) return this.snapshot(code, playerId);
    if (room.phase !== 'lobby') throw new GameError('This race has already started.', 'race_started');
    if (room.players.size >= MAX_PLAYERS) throw new GameError('This game lobby is full.', 'lobby_full');

    const team = smallestTeam(room);
    const teamPosition = teamSize(room, team);
    const player = {
      id: playerId,
      name: playerName,
      team,
      order: room.nextOrder++,
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
      crumblyEnteredAt: null,
      finished: false,
    };
    room.players.set(playerId, player);
    if (!room.hostId) room.hostId = playerId;
    room.updatedAt = Date.now();
    return this.snapshot(code, playerId);
  }

  /** Removes a player, transfers host ownership, and ends a race if a team becomes empty. */
  leave(roomCode, identity) {
    const room = this.#rooms.get(cleanText(roomCode, 32));
    if (!room) return false;
    const removed = room.players.delete(cleanText(identity, 128));
    if (room.players.size === 0) this.#rooms.delete(room.code);
    else {
      if (room.hostId === cleanText(identity, 128)) {
        room.hostId = [...room.players.values()].sort((left, right) => left.order - right.order)[0].id;
      }
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
    }
    return removed;
  }

  /** Moves a lobby player to a team with capacity and clears their ready status. */
  chooseTeam(roomCode, identity, team) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    if (room.phase !== 'lobby') throw new GameError('Teams are locked after the race starts.', 'teams_locked');
    if (team !== 'A' && team !== 'B') throw new GameError('Choose team A or B.', 'invalid_team');
    if (player.team === team) return this.snapshot(room.code, player.id);
    if (teamSize(room, team) >= MAX_PER_TEAM) throw new GameError('That team is full.', 'team_full');
    player.team = team;
    player.ready = false;
    room.updatedAt = Date.now();
    return this.snapshot(room.code, player.id);
  }

  /** Sets a player’s readiness while the room is in the lobby. */
  setReady(roomCode, identity, ready) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    if (room.phase !== 'lobby') throw new GameError('The lobby is closed.', 'lobby_closed');
    player.ready = Boolean(ready);
    room.updatedAt = Date.now();
    return this.snapshot(room.code, player.id);
  }

  /** Starts a race for the host only when both teams have runners and everyone is ready. */
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
    return this.snapshot(room.code, identity);
  }

  /** Resets a completed race to the lobby for the host, preserving players and team assignments. */
  rematch(roomCode, identity) {
    const room = this.#requireRoom(roomCode);
    this.#requirePlayer(room, identity);
    if (room.phase !== 'results') throw new GameError('Finish the current race before starting a rematch.', 'race_not_finished');
    if (room.hostId !== cleanText(identity, 128)) throw new GameError('Only the game host can start a rematch.', 'host_required');

    const now = Date.now();
    for (const team of ['A', 'B']) {
      const members = teamPlayers(room, team);
      const positions = formationPositions(CHECKPOINTS[0], members.length);
      members.forEach((player, index) => {
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
    room.notice = 'Rematch ready. Teams are set — ready up when everyone is back.';
    room.updatedAt = now;
    return this.snapshot(room.code, identity);
  }

  /** Accept controls only; never accept a position, score, checkpoint, or finish claim. */
  input(roomCode, identity, payload) {
    const room = this.#requireRoom(roomCode);
    const player = this.#requirePlayer(room, identity);
    if (room.phase !== 'race') throw new GameError('The race is not running.', 'race_not_running');
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

        if (jumpPressed && player.grounded) {
          player.vy = JUMP_IMPULSE;
          player.grounded = false;
          room.notice = `${player.name} jumped.`;
        }

        const acceleration = input.move * RUN_ACCELERATION * (isBracing ? 0.55 : 1);
        player.vx += acceleration * dt;
        if (input.move === 0) player.vx *= Math.max(0, 1 - 8 * dt);
        const hazard = HAZARDS.find((item) => player.x >= item.start && player.x <= item.end);
        if (hazard?.action === 'brace' && !isBracing) player.vx -= 7 * dt;
        player.vx = clamp(player.vx, -MAX_RUN_SPEED * 0.45, MAX_RUN_SPEED);
        player.x = clamp(player.x + player.vx * dt, 0, COURSE_LENGTH);

        const onBoulder = player.x >= BOULDER.start && player.x <= BOULDER.end;
        if (onBoulder && player.y < 2.4) {
          // A low runner bumps off the boulder and must retry with a jump.
          // While bump recovery is active, keep the runner safely before it.
          player.x = BOULDER.start - 1.6;
          if (now - player.lastBoulderBumpAt >= 900) {
            player.vx = -1.5;
            player.lastBoulderBumpAt = now;
            room.notice = `${player.name} bumped the stone. Jump over it!`;
          } else {
            player.vx = 0;
          }
        }

        const inPit = player.x >= PIT.start && player.x <= PIT.end;
        const inWater = player.x >= RIVER_GAP.start && player.x <= RIVER_GAP.end;
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
            this.#resetTeam(room, team);
            room.notice = `${team}: a runner fell; the whole team returned to the checkpoint.`;
            break;
          }
          if (!inPit && !inWater && player.y <= 0) {
            player.y = 0;
            player.vy = 0;
            player.grounded = true;
          }
        }

        const onCrumblyTiles = player.x >= RIVER_BRIDGE.start && player.x <= RIVER_BRIDGE.end;
        if (onCrumblyTiles) {
          player.crumblyEnteredAt ??= now;
          if (now - player.crumblyEnteredAt >= 800) {
            emitEffect(room, team, player, 'bridge-break', now);
            this.#resetTeam(room, team);
            room.notice = `${team}: the bridge broke beneath a runner; everyone regrouped at the checkpoint.`;
            break;
          }
        } else {
          player.crumblyEnteredAt = null;
        }

        const inLava = player.x >= LAVA.start && player.x <= LAVA.end;
        if (inLava && player.grounded) {
          if (player.lavaEnteredAt === null) {
            emitEffect(room, team, player, 'lava-burst', now);
            room.notice = `${player.name} landed in the lava! Jump across it.`;
          }
          player.lavaEnteredAt ??= now;
          if (now - player.lavaEnteredAt >= 400) {
            this.#resetTeam(room, team);
            room.notice = `${team}: a runner landed in the lava; regroup at the checkpoint and jump together.`;
            break;
          }
        } else {
          player.lavaEnteredAt = null;
        }

      }
      this.#enforceRope(room, team);
      const groupProgress = members.length
        ? members.reduce((value, player) => Math.min(value, player.x), COURSE_LENGTH)
        : 0;
      const passedCheckpoint = [...CHECKPOINTS].reverse().find((point) => groupProgress >= point && point > room.checkpoints[team]);
      if (passedCheckpoint) {
        room.checkpoints[team] = passedCheckpoint;
        room.notice = `Checkpoint ${Math.round(passedCheckpoint)}m reached.`;
      }
    }
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
    room.updatedAt = now;
    return this.snapshot(room.code, null);
  }

  /** Returns viewer-filtered state, hiding rival runners during a race and refreshing viewer presence. */
  snapshot(roomCode, viewerIdentity) {
    const room = this.#requireRoom(roomCode);
    const viewer = viewerIdentity ? this.#requirePlayer(room, viewerIdentity) : null;
    if (viewer) viewer.lastSeenAt = Date.now();
    const ownTeam = viewer?.team;
    const teams = {};
    for (const team of ['A', 'B']) {
      const members = teamPlayers(room, team);
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
      ...(room.phase !== 'race' || ownTeam === team ? {
          players: members.map((player) => ({
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
          })),
          ropeLinks: Math.max(0, members.length - 1),
        } : {}),
      };
    }
    return {
      roomCode: room.code,
      phase: room.phase,
      teams,
      viewerTeam: ownTeam ?? null,
      isHost: viewerIdentity != null && room.hostId === cleanText(viewerIdentity, 128),
      checkpoints: { ...room.checkpoints },
      winner: room.winner,
      notice: room.notice,
      hazard: getHazardHint(room, ownTeam),
      effect: viewer && room.effect && room.effect.team === ownTeam && Date.now() - room.effect.at <= 1_200
        ? { id: room.effect.id, type: room.effect.type, progress: room.effect.progress }
        : null,
      serverTime: Date.now(),
    };
  }

  /** Deletes an in-memory game room and returns whether it existed. */
  deleteRoom(roomCode) {
    return this.#rooms.delete(cleanText(roomCode, 32));
  }

  /** Returns the codes of all in-memory game rooms for simulation scheduling. */
  roomCodes() {
    return [...this.#rooms.keys()];
  }

  /** Removes players beyond the presence grace period, reassigns hosts, and resolves empty-team races. */
  cleanupStalePlayers(now = Date.now(), graceMs = 30_000) {
    for (const room of this.#rooms.values()) {
      const stale = [...room.players.values()].filter((player) => now - player.lastSeenAt > graceMs);
      for (const player of stale) {
        room.players.delete(player.id);
        room.notice = `${player.name} reconnected too late and is spectating this round.`;
        if (room.hostId === player.id) {
          room.hostId = [...room.players.values()].sort((left, right) => left.order - right.order)[0]?.id ?? null;
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

  /** Returns the normalized room or throws game_not_found. */
  #requireRoom(code) {
    const room = this.#rooms.get(cleanText(code, 32));
    if (!room) throw new GameError('Game lobby not found.', 'game_not_found');
    return room;
  }

  /** Returns a room member by normalized identity or throws player_not_in_game. */
  #requirePlayer(room, identity) {
    const player = room.players.get(cleanText(identity, 128));
    if (!player) throw new GameError('Player is not in this game lobby.', 'player_not_in_game');
    return player;
  }

  /** Respawns a team in its checkpoint lane and clears motion, controls, and hazard timers. */
  #resetTeam(room, team) {
    const members = teamPlayers(room, team);
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

  /** Corrects adjacent runner positions and velocities to constrain rope gaps and preserve runner order. */
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
  /** Creates a game-rule error with a stable code for API error responses. */
  constructor(message, code) {
    super(message);
    this.name = 'GameError';
    this.code = code;
  }
}

/** Creates an empty lobby with initial checkpoints, timestamps, and effect state. */
function createRoom(code) {
  return {
    code,
    phase: 'lobby',
    players: new Map(),
    nextOrder: 0,
    checkpoints: { A: CHECKPOINTS[0], B: CHECKPOINTS[0] },
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

/** Stores the latest team-specific impact effect with an increasing ID and normalized position. */
function emitEffect(room, team, player, type, at) {
  room.effectSequence += 1;
  room.effect = {
    id: room.effectSequence,
    team,
    type,
    progress: Number(clamp(player.x / COURSE_LENGTH, 0, 1).toFixed(3)),
    at,
  };
}

/** Returns the next hazard relative to the team’s leading runner, or null if none is available. */
function getHazardHint(room, team) {
  if (!team) return null;
  const members = teamPlayers(room, team);
  // Warn for the leading runner so the whole team gets the cue before anyone
  // reaches the obstacle.
  const progress = members.length ? Math.max(...members.map((player) => player.x)) : 0;
  const next = HAZARDS.find((hazard) => hazard.end >= progress);
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

/** Returns leader-first respawn positions evenly spaced across the checkpoint’s safe lane. */
function formationPositions(checkpoint, count) {
  if (count <= 1) return [checkpoint];
  const lane = RESPAWN_LANES[checkpoint] ?? {
    start: Math.max(0, checkpoint - MAX_ROPE_GAP),
    end: checkpoint,
  };
  const spacing = (lane.end - lane.start) / (count - 1);
  return Array.from({ length: count }, (_, index) => Number((lane.end - index * spacing).toFixed(2)));
}

/** Returns a team’s players in their original join order for rope and formation calculations. */
function teamPlayers(room, team) {
  return [...room.players.values()]
    .filter((player) => player.team === team)
    .sort((left, right) => left.order - right.order);
}

/** Chooses a team with capacity, preferring A on ties, or throws when both teams are full. */
function smallestTeam(room) {
  const countA = teamSize(room, 'A');
  const countB = teamSize(room, 'B');
  if (countA >= MAX_PER_TEAM && countB >= MAX_PER_TEAM) throw new GameError('Both teams are full.', 'lobby_full');
  return countA <= countB && countA < MAX_PER_TEAM ? 'A' : 'B';
}

/** Counts the players currently assigned to a team. */
function teamSize(room, team) {
  let count = 0;
  for (const player of room.players.values()) if (player.team === team) count += 1;
  return count;
}

/** Trims and truncates string input, returning an empty string for other values. */
function cleanText(value, maximumLength) {
  return typeof value === 'string' ? value.trim().slice(0, maximumLength) : '';
}

/** Bounds a numeric value to the inclusive minimum and maximum. */
function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}
