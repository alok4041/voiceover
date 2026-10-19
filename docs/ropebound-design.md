# Ropebound — SquadVoice Co-op Race

## Purpose and status

This document is the product and implementation reference for Ropebound, the 3D co-op race game inside SquadVoice. The Android client renders a 3D landscape race scene and camera views; the server remains authoritative for movement, hazards, and match snapshots. Match state resets if the token server restarts.

The current code and implemented rules are documented in `docs/ROPEBOUND-GAME-GUIDE.md`. This document records the product direction and a library of future obstacle and progression ideas; an idea below is not implemented unless the guide or source code says so.

The game should open from an active SquadVoice voice room. Players keep talking in that room while they play. The game is a coordinated race where each team controls a chain of toy runners joined by a rope that **must never break**.

## Game pitch

Two teams race through dangerous, toy-sized obstacle courses. Every player controls one runner in a connected line. The squad must move, jump, brace, and recover together: a fast player cannot simply leave the rest behind, and reaching the finish alone does not win. The team that gets every active runner through the exit first wins.

The fun should come from shouting timing cues, recovering from ridiculous near-falls, and adapting to obstacles together—not from punishing one teammate or relying on random luck.

## Visual format and current direction

- Ropebound is a 3D, landscape-first cooperative race for Android touch screens. The client renders a perspective world with readable toy-like runners, an always-attached rope, a clear route, recognizable hazards, and layered scenery.
- Entering the Ropebound lobby or race locks the Android activity to landscape; returning to the voice room restores its previous orientation setting.
- The Android game lives in `app/src/main/java/com/squadvoice/app/game/`. `RopeboundGameScreen.kt` owns lobby/race/results composition, input collection, and local camera settings. `Ropebound3DScene.kt` hosts the OpenGL ES 2.0 view; `game/render/` contains procedural geometry and runner/environment drawing. Compose draws the HUD, touch controls, nameplates, settings, and results over the 3D scene.
- The scene uses a simple world convention: course progress maps along world Z, lane/steer offset along world X, and jump height along world Y. The Android renderer interpolates server snapshots and may predict the local runner for responsive feel; this is visual smoothing only, not an alternate authority for collisions or match outcomes.
- The race camera is per-device presentation state. Players may drag across the playfield to rotate the view through a full horizontal 360° orbit and tilt up/down within a bounded range. Follow, orbit, and top views are camera presets. Camera movement is not sent to the server and cannot change another player's view or game state. Pitch is bounded so the camera cannot flip through the world.
- Movement uses a four-direction virtual joystick: up/down control forward/reverse travel; left/right steer across the lane. Both axes can be combined for a diagonal input, which is normalized to prevent faster diagonal movement. Jump and Brace remain separate touch actions. The HUD can be repositioned in its edit mode.
- The existing app shell keeps the LiveKit voice session and microphone foreground service alive while the game screen is shown. The game does not create a second voice connection.
- The token server has an in-memory Node.js game core and authenticated `/games/*` routes for lobby/team constraints, host start permission, bounded movement and steering inputs, server-owned progress, rope spacing, hazard recovery, rematches, and opponent progress filtering. Physics advances at 20 ticks per second; snapshots stream over the authenticated WebSocket at about 10 per second. A Render process restart clears in-memory game state.
- Keep the game inside the existing Android app so players can use the game and squad voice simultaneously.

## Design pillars

1. **The rope is permanent.** It remains attached, visible, and indestructible through falls, hazards, reconnects, and recovery.
2. **Coordination beats individual speed.** The rear runner naturally sets the group pace; good timing and clear calls help the whole line.
3. **Every player has something useful to do.** Front, middle, and rear positions create different opportunities, but no position should be permanently assigned a boring job.
4. **Failures are funny and recoverable.** A mistake costs time or a shared retry; it should not strand or eliminate a teammate.
5. **Voice stays simple.** Squad voice remains connected throughout the lobby, race, result screen, and return to the room.
6. **A round fits a break between matches.** Target about 2–4 minutes for a normal course, with a short tutorial and quick retry.
7. **Fairness is understandable.** Hazards telegraph what they will do, and losses should be traceable to a team decision rather than hidden randomness.

## Players, teams, and match rules

- Exactly two teams race at a time: Team A and Team B.
- Each team has 1–5 runners; the lobby supports up to 10 players total.
- At least 2 active players on each team (4 total) are required to start; every active player must be ready and only the host can start.
- Every runner is connected to adjacent runners in the team’s ordered rope chain.
- All active runners on a team must reach the exit for that team to finish.
- A team wins when its last active runner crosses the exit. The other team’s full race data is hidden during play; show only a low-rate progress marker on the shared track bar.
- A player whose connection goes offline is removed from the active race chain after the server's offline timeout and is shown as offline/spectating; the remaining active team can continue. A reconnect during a race remains spectating until the next round. AFK-only warnings and kick behavior are future work.
- Landscape is required for both the lobby and race because the game needs space for the runner chain, camera view, and touch controls.

## The rope: rules and feedback

The rope links each adjacent pair in the team order. Each link has a comfortable length and a maximum allowed length. It does not wrap around scenery in the first version; level geometry must not trap the chain around corners.

| Link state | Player feedback | Gameplay effect |
| --- | --- | --- |
| Slack | Rope hangs naturally | No speed penalty |
| Tension | Rope brightens and gently vibrates near the limit | The leading runner slows smoothly; teammates can close the gap |
| Maximum stretch | Rope stays taut and glows strongly | The runner moving away from the chain is blocked from stretching farther |
| Recovery | Rope eases the team toward a safe position | A fall can be rescued, or the whole team returns to its checkpoint |

The MVP should prevent overextension by slowing/blocking the forward runner. Do not yank runners violently, launch players unpredictably, or let the rope slice through hazards. A little extra slack during a jump makes airborne movement feel natural. Never use a “rope health” meter, rope-cutting tools, or any break animation. Environmental fire such as a lava pool is a course hazard and must never damage the rope.

## Runner controls and camera

- **Four-way joystick:** up/down move forward/backward; left/right steer laterally. Diagonals are accepted and normalized. Releasing the joystick returns both axes to neutral; the server damps velocity.
- **Jump:** tap while grounded. The server applies a fixed jump impulse; holding does not increase jump height.
- **Brace:** hold to reduce movement acceleration and resist the wind hazard.
- **Camera:** drag the visible playfield to orbit horizontally through 360° and tilt within safe limits. Follow, 3D orbit, and top-view buttons restore known camera presets. Camera gestures are local and are not broadcast to teammates.
- **Team action (future idea):** a short, cooldown-limited group call such as “pull together” or “steady”. It should help recover coordination, not provide a speed boost that decides the race by itself.

Controls need generous touch targets, haptic feedback that can be disabled, and a practice segment. Every essential instruction must also be visual; players must not need to hear voice chat to understand a hazard.

## Future obstacle design toolkit

Each obstacle should teach or test one main coordination idea. Introduce hazards with safe examples before combining them. Use readable colors, motion previews, and enough warning to react. Obstacles can slow, bump, lift, or reposition runners, but can never detach the rope.

### Movement and timing

1. **Staggered gaps:** small platforms separated by gaps. The chain calls a jump rhythm so the last runner is not left behind.
2. **Crumbly tiles:** a tile flashes, cracks, then drops after a delay. Keep moving as a group; pauses must be deliberate.
3. **Rhythm doors:** doors open in a repeating pattern. The team times one crossing window and avoids a split across the chain.
4. **Spring pads:** launch a runner high or far. A teammate can brace to stop the launch from pulling the line out of position.
5. **Low ceilings:** jumping is unsafe under marked sections. Players call “stay low” and cross without breaking formation.
6. **Moving lifts:** a platform travels between ledges. The team must board fully, balance, and leave together.

### Environmental forces

7. **Wind tunnels:** gusts arrive in visible waves and push the whole line. Bracing together reduces drift; crossing between gusts is faster.
8. **Water jets:** floor vents spray on a cycle, briefly lifting or pushing runners. The spray direction is shown before activation.
9. **Sticky mud:** slows whoever enters first. The front runner may need to pause so the line does not stretch.
10. **Toy ice:** makes stopping and turning slippery. Small controlled inputs and a braced anchor prevent chain-wide sliding.
11. **Conveyor belts:** move runners forward or backward. The team chooses whether to ride the belt or brace and cross against it.
12. **Foam fans:** broad, gentle pushes can help or hinder depending on direction. Use them as a route choice, not a surprise knockout.

### Cooperative rope challenges

13. **Anchor-and-reach ledges:** one or more runners brace on a safe ledge while others cross or climb, then the anchor follows.
14. **Swing bridges:** the group’s movement makes a bridge sway. Coordinated pacing settles it; sprinting together creates a larger swing.
15. **Weighted seesaws:** runners distribute their weight to raise one end for teammates, then regroup on the far side.
16. **Tension gates:** a gate opens only while the rope chain holds a marked tension range. One player braces as the others pass, then rotates out.
17. **Rope-guided elevators:** the platform moves only when the chain is inside a safe spacing zone. The group manages slack rather than spamming jump.
18. **Pull bells / switches:** a runner reaches a safe handle while the rest brace, activating a bridge, elevator, or shortcut.

### Route choices and playful hazards

19. **Forked routes:** a short, risky lane and a longer, safer lane. The whole line chooses together; the rope must not force impossible geometry.
20. **Toy trains and carts:** predictable moving obstacles cross the lane. Players can wait, duck into a marked alcove, or use a cart as a moving platform.
21. **Rolling balls:** large foam or toy balls roll along a visible track. A team can dodge into safe pockets or brace behind a marked barrier.
22. **Friendly bumpers:** soft bumpers bounce runners a short distance. They can create funny saves, but should not throw someone beyond rope limits.
23. **Fake floors:** a few tiles look unstable but are safe, while others are marked to drop. Use sparingly so observation matters without feeling deceptive.
24. **Moving walls:** walls slide between clearly marked safe bays. The chain pauses inside the bay, then passes in one coordinated burst.
25. **Collectible detours:** optional bolts or stars sit on side platforms. Collecting them never blocks the exit or gives a race-breaking advantage.

### Late-game combinations

26. **Wind plus staggered gaps:** jump between gusts while the rear controls the line’s pace.
27. **Mud plus a timed door:** decide whether to push through the current opening or regroup for the next cycle.
28. **Moving lift plus falling tiles:** board the lift as a team and leave before the boarding ledge expires.
29. **Rolling ball plus forked routes:** choose a route while keeping everyone in range and watching the opponent progress marker.
30. **Checkpoint gauntlet:** combine two familiar obstacles, then place a checkpoint before the harder sequence. Do not introduce a new mechanic inside a punishing combo.

## Rescue, checkpoints, and failure

- A pit or fall should first create a visible recovery state, not an immediate elimination.
- Teammates can brace to stabilize the line while the fallen player climbs or is pulled to a safe ledge.
- If recovery cannot complete safely, return all active runners to the latest checkpoint together and add a small time penalty.
- A checkpoint should save team progress and restore each runner to a valid position with safe rope spacing.
- A team-wide reset must not change the rope order, remove a runner, or leave someone behind.
- Avoid lives or revives that single out one player in the initial release.

## Reward, replay, and competitive balance

- Award Bronze, Silver, or Gold based on completion time and checkpoint resets. Optional collectibles can add a separate badge, not an easier win.
- Keep a team best time and a personal “good teammate” highlight such as most successful braces, cleanest jump timing, or most rescues. Rotate categories so every role can shine.
- Use cosmetic runner colors, hats, emotes, and rope appearances only; cosmetics cannot change speed, jump, weight, or rope length.
- A rematch should reuse the course quickly. Later, offer a daily seed or shareable course code so friends can replay the same layout.
- Do not add pay-to-win items, random paid rewards, or team buffs tied to spending.

## Future course themes and level progression

1. **Training Yard:** safe ledges, a short pit, one checkpoint; teaches movement, jumping, bracing, and rope tension.
2. **Broken Bridge:** staggered platforms, crumbly tiles, a safe anchor ledge.
3. **Toy Factory:** conveyor belts, rhythm doors, moving lifts, and optional collectible detours.
4. **Wind-Up Canyon:** wind tunnels, rolling foam balls, and sheltered bays.
5. **Icebox:** slippery floor, low ceilings, and a seesaw that rewards deliberate spacing.
6. **Clockwork Carnival:** spring pads, moving walls, swing bridge, and a final timing gauntlet.

Each level should have a short checkpointed route, a clear beginning and exit, and at least one optional safer/slower choice or collectible route. The first tutorial should be playable solo for control practice, but actual team races require valid teams.

## Screen flow and UI direction

Use the supplied UI concept as a starting point, not as a frozen implementation specification:

1. **Voice room:** host taps **Play Together** and selects Ropebound. Joining the game must not leave the voice room.
2. **Lobby:** show room/game status, two team columns, player names, ready/mute state, open slots, host controls, auto-balance, Free Switch, and a course preview. Clearly show team imbalance and the 2–5-per-team limits.
3. **Ready and countdown:** show missing players or readiness blockers. Start only when both teams have at least two players, everyone is ready, and the host starts the match.
4. **Race:** show the player's team and rope, checkpoint, exit, the four-way joystick, Jump, Brace, camera presets, voice/mute access, and a compact opponent progress bar. Let each player rotate their own camera; do not reveal the opponent lane or runner positions.
5. **Pause/reconnect:** explain who is reconnecting or what caused a pause; keep the rope and voice state clear.
6. **Results:** show winner, both team times, checkpoint resets, team achievement, and Retry / Next Level / Back to Voice Room. Voice remains connected.

Design for landscape phones first, large touch targets, high contrast, color plus text/icon cues, and dynamic text scaling. Provide settings for haptics, reduced motion, and control layout. Keep important action buttons clear of Android system bars and any existing SquadVoice overlay controls.

## System architecture

- **Android game client:** Kotlin and Jetpack Compose own screens, HUD, four-way joystick, Jump/Brace actions, camera gestures, camera presets, audio, local animation, and snapshot interpolation. `Ropebound3DScene.kt` renders the 3D playground with OpenGL ES 2.0; `game/render/` builds procedural meshes and scene details. The camera and visual effects are local to each phone.
- **Input contract:** the client sends a monotonically sequenced input containing normalized `move` (forward/back), `steer` (left/right), `jump`, and `brace` over the authenticated WebSocket. Client-side prediction may smooth the local runner between packets, but the client never sends trusted world coordinates or course progress.
- **Node token/game service:** authenticates the existing LiveKit participant JWT and its room scope, serves HTTP lobby operations, and owns the in-memory game simulation. The game core validates input bounds and sequence numbers, simulates movement/steering, jump/gravity, rope constraints, randomized obstacle positions, checkpoints, rescues, progress, disconnects, and results. It streams viewer-filtered snapshots over WebSocket. A Render process restart clears active match state.
- **LiveKit:** carries voice only. The current game screen shares the existing room session and does not start a separate voice connection.
- **World and camera boundary:** server coordinates are gameplay truth. Progress is longitudinal, steering is lateral, and jump is vertical. The camera applies a local view transform after snapshot interpolation; camera yaw/pitch must never be added to networked player physics, collision, team state, or opponent snapshots.
- **Level and visual data:** obstacle spans and actions come from server snapshots; the client renders matching geometry and effects. Cosmetic scenery may be procedural or image-based, but cannot redefine collision or scoring. New obstacles must be added coherently to server rules, snapshot data, and the 3D renderer.
- **Scaling work (not implemented):** game state is currently process memory. Persistence, multi-instance coordination, rate controls, and production-scale match hosting require a separate design before claiming durable or horizontally scaled matches.

### Trust and anti-cheat boundaries

- The client sends only allowed input (`move`, `steer`, `jump`, `brace`) with a sequence number and timing metadata.
- Server validates membership, match state, input rate, bounds, and timing; clients never send trusted positions, scores, team switches, or finish events.
- A team switch is decided server-side. Reject switches into a full team. Host actions are authorized server-side.
- Opponent full snapshots are never sent to a client; send only coarse progress and checkpoint state.
- Game credentials are short-lived and scoped to the room/match. LiveKit API secrets remain only in server environment variables.

## Implementation and playtest priorities

1. Keep the server `move`/`steer` input contract aligned with the Android four-way joystick. Verify that releasing the joystick neutralizes both axes and that diagonal input is normalized.
2. Keep camera orbit local to each phone. Test a full horizontal camera rotation and bounded tilt without camera gestures swallowing joystick, Jump, Brace, microphone, or settings input.
3. Playtest on target Android phones with 4–10 people while voice remains connected. Tune touch feel, camera comfort, network tolerance, obstacle readability, and course duration.
4. Add new obstacles only by changing server collision/rules and the matching Android 3D geometry/effects together.
5. Treat persistent match state, multi-instance coordination, stronger rate controls, and production-scale hosting as separate architecture work; the current server keeps game state in memory.

## Current baseline acceptance criteria

- A game can be launched from an existing voice room and exited back to that room without dropping voice.
- Two valid teams can form, ready up, start together, and finish together.
- All connected players control a runner, and every runner remains connected by the unbreakable rope.
- Each runner can use the four-way joystick for forward/back and lateral steering; camera orbit changes only the local view.
- The camera can orbit a full horizontal 360° with bounded pitch and usable Follow/Orbit/Top presets.
- Server authority governs movement, collisions, rope limits, team actions, checkpoint state, progress, and results.
- The team cannot win until all active runners reach the exit.
- Falls can be rescued or return the whole team to a checkpoint; nobody is silently abandoned.
- Hazards are explained visually, predictable enough to learn, and have safe recovery paths.
- Opponent privacy rules hold: only progress-level data is sent to the other team’s clients.
- The game remains usable with mute/voice controls and Android touch/navigation UI.
- Build size and device performance are checked before choosing an engine or adding large art/audio assets.

## Open design work

- Tune camera sensitivity and pitch limits from phone playtests; keep the camera local and avoid full free-flight controls that hide the course.
- Decide race behavior for host loss, reconnects, and whether a team can continue after a player disconnects; describe current behavior separately until those rules change.
- Set Bronze/Silver/Gold thresholds after timing real matches.
- Evaluate persistence, rate limits, and server hosting before increasing concurrent game rooms.

## Explicit non-goals for the first release

- A rope that can break, burn, cut, or detach.
- Rope wrapping around arbitrary scenery or complex ragdoll physics.
- User-created maps, global matchmaking, public leaderboards, or a cosmetics shop.
- Client-authoritative physics or revealing opponents’ full state.
- A separate voice stack inside the game.
- Deploying a real-time game server or changing the current token API without a separately reviewed implementation and deployment plan.
