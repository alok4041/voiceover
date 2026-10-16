# Ropebound — SquadVoice Co-op Race

## Purpose and status

This document is the product and implementation reference for the 2D Ropebound game inside SquadVoice. The local working tree contains an early playable implementation: Android lobby and landscape race screen, server-authoritative movement and hazards, and streamed game snapshots. It has not been pushed, deployed, or validated on connected phones. Match state resets if the token server restarts.

The game should open from an active SquadVoice voice room. Players keep talking in that room while they play. The game is a coordinated race where each team controls a chain of toy runners joined by a rope that **must never break**.

## Game pitch

Two teams race through dangerous, toy-sized obstacle courses. Every player controls one runner in a connected line. The squad must move, jump, brace, and recover together: a fast player cannot simply leave the rest behind, and reaching the finish alone does not win. The team that gets every active runner through the exit first wins.

The fun should come from shouting timing cues, recovering from ridiculous near-falls, and adapting to obstacles together—not from punishing one teammate or relying on random luck.

## Visual format and first implementation

- The initial game is 2D, side-on, landscape-oriented, and designed for touch screens. Use readable toy-like silhouettes, layered backgrounds, a clear course line, large hazards, and a rope drawn between every adjacent teammate.
- Entering the Ropebound lobby or race locks the Android activity to sensor landscape; returning to the voice room restores the activity's previous orientation setting.
- Ropebound Android UI/client files live in `app/src/main/java/com/squadvoice/app/game/`. Voice UI, LiveKit lifecycle, room service, room API, and microphone controls live in `app/src/main/java/com/squadvoice/app/voice/`. The app shell switches screens, while the voice foreground service/session continues independently when the game UI is shown; the game can therefore run alongside the existing voice chat.
- The current Android game UI uses Kotlin and Jetpack Compose Canvas for animated characters, ropes, flags, a spike pit, a boulder to jump over, a fragile plank bridge, an open river gap, a wind tunnel, a lava crossing, and the finish arch. River waves and lava flames animate continuously; server-triggered splash and lava-burst particles animate when a runner falls in. Its scenic Training Yard background is stored at `app/src/main/res/drawable-nodpi/ropebound_training_yard_v2.png`; the game draws dynamic course objects over it so their positions stay aligned with physics. Each phone follows its own authenticated player with a smooth side-scrolling camera, while the scenery drifts at a slower parallax rate. It includes team selection, ready state, host-controlled start, coalesced held run/brace controls, jump, checkpoints, finish, results, and a host-controlled rematch. It streams state updates and sends controls; the existing LiveKit session remains connected.
- The token server has an in-memory Node.js game core and authenticated `/games/*` routes for lobby/team constraints, host start permission, bounded control inputs, server-owned progress, rope spacing, hazard recovery, rematches, and opponent progress filtering. Movement uses acceleration, velocity damping, jump impulse, gravity, and repeated rope-length constraint passes. Falling into the spike pit or open river, landing in lava, or staying on the bridge too long resets the full team into a safe staging lane at its latest checkpoint; a grounded runner bumps off the boulder and must jump over it. Android receives viewer-filtered server-sent events from `/games/events`; physics advances at 20 ticks per second and snapshots stream at about 10 per second.
- Keep the first version 2D. Make course geometry, runner spacing, obstacle timing, and collision feedback fun before evaluating 3D as a separate future project.
- Keep the game in the existing Android app so the current LiveKit voice session remains alive while the game screen is shown.

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
- At least 2 players (1 on each team) are required to start a race, so a duo can play 1v1.
- Every runner is connected to adjacent runners in the team’s ordered rope chain.
- All active runners on a team must reach the exit for that team to finish.
- A team wins when its last active runner crosses the exit. The other team’s full race data is hidden during play; show only a low-rate progress marker on the shared track bar.
- A player who is AFK receives a warning after 15 seconds. If still inactive after a clearly shown grace period, the player becomes a spectator for that round. The rope reconnects the remaining active runners across the skipped position; no rope segment breaks.
- If a player disconnects, pause or safely freeze their runner for a short reconnect window. If they do not return, let the team choose a checkpoint restart or finish together with that runner spectating.
- Landscape is the initial target because the race needs space for the whole chain and touch controls.

## The rope: rules and feedback

The rope links each adjacent pair in the team order. Each link has a comfortable length and a maximum allowed length. It does not wrap around scenery in the first version; level geometry must not trap the chain around corners.

| Link state | Player feedback | Gameplay effect |
| --- | --- | --- |
| Slack | Rope hangs naturally | No speed penalty |
| Tension | Rope brightens and gently vibrates near the limit | The leading runner slows smoothly; teammates can close the gap |
| Maximum stretch | Rope stays taut and glows strongly | The runner moving away from the chain is blocked from stretching farther |
| Recovery | Rope eases the team toward a safe position | A fall can be rescued, or the whole team returns to its checkpoint |

The MVP should prevent overextension by slowing/blocking the forward runner. Do not yank runners violently, launch players unpredictably, or let the rope slice through hazards. A little extra slack during a jump makes airborne movement feel natural. Never use a “rope health” meter, rope-cutting tools, or any break animation. Environmental fire such as a lava pool is a course hazard and must never damage the rope.

## Runner controls and teamwork actions

- **Move:** two large direction buttons or a thumb pad.
- **Jump:** tap for a normal jump; a short hold may increase height, with a clear maximum.
- **Brace:** hold to plant the runner and resist a push or stabilize the rope chain.
- **Team action (optional after the basic controls feel good):** a short, cooldown-limited group call such as “pull together” or “steady”. It should help recover coordination, not provide a speed boost that decides the race by itself.

Controls need generous touch targets, haptic feedback that can be disabled, and a practice segment. Every essential instruction must also be visual; players must not need to hear voice chat to understand a hazard.

## Obstacle design toolkit

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

## Course and level progression

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
3. **Ready and countdown:** show missing players or readiness blockers. Start a synchronized countdown only when both teams have at least two players and everyone is ready.
4. **Race:** show the player’s own lane, all connected teammates, rope tension states, checkpoint, exit, movement controls, Jump, Brace, voice/mute access, and a compact opponent progress bar. Do not reveal the opponent lane or their runner positions.
5. **Pause/reconnect:** explain who is reconnecting or what caused a pause; keep the rope and voice state clear.
6. **Results:** show winner, both team times, checkpoint resets, team achievement, and Retry / Next Level / Back to Voice Room. Voice remains connected.

Design for landscape phones first, large touch targets, high contrast, color plus text/icon cues, and dynamic text scaling. Provide settings for haptics, reduced motion, and control layout. Keep important action buttons clear of Android system bars and any existing SquadVoice overlay controls.

## Technical shape

- **Android app (Kotlin/Compose Canvas):** renders the 2D lobby and race, sends input, displays authoritative snapshots, and keeps existing LiveKit voice controls available. It does not decide positions, rope physics, scores, or winners. Movement is sent while Run/Back is held; Jump and Brace are discrete actions. The artwork is drawn using Compose Canvas vectors rather than external Canva assets.
- **Token/API service (Node):** verifies the signature, expiry, and room scope of the existing LiveKit participant token before allowing game API calls. The same process currently runs the game simulation; it does not yet confirm the participant is actively connected to LiveKit.
- **Game server:** authoritative lobby, teams, ready state, movement, collisions, rope spacing, checkpoints, idle cleanup, and win/lose. The current `/games/*` implementation is in-memory and shares the token-server process; it resets on restart and needs persistence, monitoring, and API-wide rate controls before production use. It advances at 20 simulation ticks per second and streams snapshots around 10 times per second.
- **LiveKit:** voice only. Race voice defaults to team chat; an explicit push-to-talk action can temporarily enable all-player chat if desired.
- **Redis:** room/lobby metadata with TTL and atomic player-slot reservations. Do not store every physics tick in Redis. Match state may live in game-server memory initially and recover at the last checkpoint after a restart.
- **Level data:** shared, versioned tile maps/configuration, validated by the game server. Client renders but cannot change collision or scoring rules.
- **Hosting:** the game server needs an always-on or otherwise reliable WebSocket host. A sleeping free-tier process risks disconnecting a race. Do not assume the existing Render token server’s in-memory maps are durable or that its current service is suitable for real-time simulation without checking.

### Trust and anti-cheat boundaries

- The client sends only allowed input (move, jump, brace) with a sequence number and timestamp.
- Server validates membership, match state, input rate, bounds, and timing; clients never send trusted positions, scores, team switches, or finish events.
- A team switch is decided server-side. Reject switches into a full team. Host actions are authorized server-side.
- Opponent full snapshots are never sent to a client; send only coarse progress and checkpoint state.
- Game credentials are short-lived and scoped to the room/match. LiveKit API secrets remain only in server environment variables.

## Build order

1. Confirm the product decisions and draw the screen flow.
2. Prototype single-device movement and rope spacing with two runners; validate that the unbreakable rope feels fair.
3. Improve Android/server controls and validate the race on multiple phones while voice remains connected.
4. Add active LiveKit membership checks, reconnect handling, persistence, monitoring, and API-wide rate controls before enabling public matches.
5. Add checkpoint recovery, disconnect handling, AFK behavior, and race completion.
6. Build one tutorial and one replayable level with three obstacle families.
7. Keep LiveKit voice connected while navigating into and out of the game.
8. Playtest on target Android phones with 4–10 people; tune controls, network tolerance, obstacle readability, and level duration before adding more hazards.

## MVP acceptance criteria

- A game can be launched from an existing voice room and exited back to that room without dropping voice.
- Two valid teams can form, ready up, start together, and finish together.
- All connected players control a runner, and every runner remains connected by the unbreakable rope.
- Server authority governs movement, collisions, rope limits, team actions, checkpoint state, progress, and results.
- The team cannot win until all active runners reach the exit.
- Falls can be rescued or return the whole team to a checkpoint; nobody is silently abandoned.
- Hazards are explained visually, predictable enough to learn, and have safe recovery paths.
- Opponent privacy rules hold: only progress-level data is sent to the other team’s clients.
- The game remains usable with mute/voice controls and Android touch/navigation UI.
- Build size and device performance are checked before choosing an engine or adding large art/audio assets.

## Decisions to make before implementation

- Final title, art style, and whether the runners have distinct but purely cosmetic looks.
- Whether the initial race always requires 2 vs 2, or supports uneven teams with an explicit fairness rule.
- How the lobby host is selected and what happens if they disconnect.
- Reconnect grace duration and whether a match pauses for a reconnect.
- Whether all-player voice during races is push-to-talk or a separate room option.
- Whether a small team can continue alone after an opponent disconnects or must restart.
- Exact Bronze/Silver/Gold thresholds after real playtests.
- Which game-server hosting and persistent store are approved; assess operating cost and expected concurrent rooms first.

## Explicit non-goals for the first release

- A rope that can break, burn, cut, or detach.
- Rope wrapping around arbitrary scenery or complex ragdoll physics.
- User-created maps, global matchmaking, public leaderboards, or a cosmetics shop.
- Client-authoritative physics or revealing opponents’ full state.
- A separate voice stack inside the game.
- Deploying a real-time game server or changing the current token API without a separately reviewed implementation and deployment plan.
