# Ropebound: Squad Run

## Status

Design proposal only. This document describes a possible co-op game for SquadVoice; it does not start implementation.

## One-sentence pitch

Every player controls a small toy-like runner. An unbreakable rope connects the whole squad in order, and everyone must coordinate jumps, pulls, and pauses to get the entire line through obstacle-filled levels.

## Design goals

- Make voice communication useful and funny, not optional background noise.
- Let every person in the voice room play at once.
- Keep rounds short enough to play between matches.
- Make failure a shared setback rather than eliminating one player.
- Keep the rope connected for the whole game. **The rope can never break.**
- Fit the existing Android app and keep LiveKit voice working while the game is open.

## Players and session

- Support the same room capacity as SquadVoice: up to 10 players.
- Use the order shown in the game lobby to place runners in the rope line. The host can rearrange positions before starting.
- New players can join the voice room during a game, but join the next round or wait at the next checkpoint rather than appearing inside a moving obstacle.
- A normal level should take about 2–4 minutes. A short practice level teaches movement and rope tension.

## Core rules

1. The rope joins each adjacent pair of players from the front runner to the last runner. It stays visibly connected and is indestructible.
2. The rope has a comfortable slack length. When a pair moves too far apart, it becomes taut and smoothly slows the leader or pulls the trailing runner forward. It never snaps, cuts, or disappears.
3. The rope is a gameplay tether, not a physics object that wraps around scenery in the first version. This prevents it getting stuck on a corner or creating an impossible level.
4. Players share one squad goal: all runners must reach the exit. Reaching it alone does not finish the level.
5. A fall does not eliminate a player. If the player can be pulled back, teammates can rescue them. Otherwise, the full line returns to the latest checkpoint with a small time penalty; the rope remains intact.
6. The game never advances the camera so far that a teammate is silently left behind. It follows the group and eases out to keep the whole chain in view.

## Controls

Keep the first version to three large touch controls:

- **Move:** left/right thumb pad or two direction buttons.
- **Jump:** tap to jump; holding briefly can make a higher jump.
- **Brace:** hold to plant the runner’s feet and help the rope line stay steady while teammates cross a gap or climb a ledge.

The game should have a short tutorial with visual prompts. Voice is encouraged, but no essential instruction should be available only through audio.

## Rope mechanics that make teamwork fun

- **Chain jump:** a well-timed jump from the front sends a small pull through the rope, helping the next runner start their jump. Timing it together can carry the whole line over a wide gap.
- **Anchor and rescue:** a grounded player who braces can hold position while a dangling teammate climbs or gets pulled to safety.
- **Tension warning:** the taut section glows and gives a gentle vibration before the group reaches its spacing limit. This is feedback, never a countdown to breaking.
- **Shared momentum:** a fast runner can help pull a slower teammate, but rushing too far makes the front runner lose speed. The best pace is coordinated.
- **Checkpoint regroup:** if anyone falls too far to recover, everyone respawns together. No one waits alone or loses their character.

## Obstacles and level ideas

Start with a small set that teaches different kinds of coordination:

1. **Training Yard:** low steps, a short pit, and a checkpoint. Teaches movement, jumping, and spacing.
2. **Broken Bridge:** staggered platforms and a wider gap. Players call a jump rhythm; a braced teammate can stabilize the line.
3. **Moving Lift:** a platform travels between two ledges. The group must board fully, balance, then jump off together.
4. **Wind Tunnel:** gusts push runners in one direction. Bracing and moving as a group keeps the line from stretching.
5. **Mud and Ice:** mud slows the front; ice makes stopping harder. Players adjust pace so the line stays together.
6. **Drop Shafts:** safe-looking holes and alternate routes test who scouts ahead and who anchors the rear.
7. **Timed Gates:** the squad has to cross a gate in sequence without leaving a teammate behind.

All obstacles affect movement only. None can cut, burn, or disable the rope. Avoid random hazards that can make a checkpoint unwinnable.

## Progress and replay

- Give the squad a bronze/silver/gold result based on finish time and checkpoint resets.
- Track a personal stat for fun (best anchor, cleanest jumps, most rescues) without making it a required competition.
- Add optional collectible toy bolts on alternate paths. Missing one never blocks the exit.
- Generate a room share code for a level seed so friends can replay the same course and compare team times.
- Unlock cosmetic runner colors, hats, and rope styles. Cosmetics must not change speed, jump, or rope length.

## Screen flow

1. In an active voice room, the host taps **Play Together** and selects **Ropebound**.
2. The lobby shows everyone in rope order, their ready state, and a small course preview.
3. The host starts when everyone is ready. A short countdown synchronizes the start.
4. The game view shows the course, all connected runners, touch controls, checkpoint, and exit. Existing mute, speaker, and leave controls remain available.
5. At the exit, show the shared result and offer **Retry**, **Next Level**, or **Back to Voice Room**.

## Technical implementation plan (for a later approval)

### Fit with the current app

- Android UI is Kotlin with Jetpack Compose. A lightweight 2D scene could use Compose Canvas, avoiding the storage and APK-size cost of embedding a full game engine.
- The existing Node/Express service issues voice-room credentials; LiveKit carries voice. Keep game movement on a separate WebSocket path so game traffic never carries microphone audio.
- Keep the voice foreground service and notification behavior unchanged when navigating into the game. Leaving the game must not leave the voice room.
- Do not put LiveKit API secrets in the Android app. The game WebSocket should authenticate players through a short-lived game credential minted by the backend.

### Multiplayer model

- The server is authoritative for player positions, collisions, rope distance constraints, checkpoints, and finish state.
- Clients send compact inputs (left/right, jump pressed/released, brace) with a sequence number; they do not send trusted positions or scores.
- Run a fixed game simulation around 20–30 ticks per second. Broadcast snapshots around 10–20 times per second; render at the phone’s normal frame rate by interpolating remote players.
- Start without prediction if latency remains playable. Add local prediction only if play tests show controls feel sluggish, and reconcile it against server snapshots.
- Game rooms are ephemeral and keyed to the existing SquadVoice room. No account or permanent database is needed for the first version; store only best local/team time later if desired.

### Disconnect and fairness behavior

- If a player briefly disconnects, freeze their runner at a safe state and pause the whole line for a short reconnect window. The rope stays connected.
- If they do not return, let the group restart at its last checkpoint or end the game together. Do not silently remove them from the chain.
- If the host leaves, the game server continues the session and chooses a new lobby host; voice and game session state remain separate.
- Clamp input rates and validate room membership server-side. Only current voice-room members can send game inputs for that room.

## MVP scope

The first playable release should contain:

- 2–10 connected toy runners and one fixed rope order per round.
- One short tutorial level and two replayable levels.
- Move, jump, and brace controls.
- Visible slack/tension, unbreakable distance tether, pit recovery, and one checkpoint per level.
- Shared ready/countdown, finish state, retry, and voice remains connected throughout.
- One authoritative WebSocket game session per SquadVoice room.

Leave rope wrapping, user-made maps, global leaderboards, cosmetics shop, power-ups, and complex ragdoll simulation out of the MVP.

## Acceptance criteria for a future implementation

- Every room member up to the app’s 10-player limit is represented in the rope line.
- The rope never breaks, even if players are pulled apart, fall, reconnect, or touch a hazard.
- All players can move and jump at the same time and see the other runners move smoothly.
- The level cannot finish until every active runner reaches the exit or the team agrees to restart/end.
- A fall can be rescued or returns the entire squad to a checkpoint without leaving a player behind.
- Voice remains active while playing and after returning to the room screen.
- The APK remains practical to download on phones with limited storage; monitor size before selecting any game framework or large art/audio assets.

## Open decisions before coding

- Final game name and toy art style.
- Whether levels use landscape-only play or adapt to portrait.
- How long a disconnected runner can remain frozen before the group must restart.
- Whether the first release includes two or three levels.
- Whether player order is host-selected, randomized, or follows join order.

