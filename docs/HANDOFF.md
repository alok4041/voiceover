# SquadVoice Agent Handoff

This file is a working handoff for an agent continuing the app, token-server, website, or release work. It records repository facts and the current local state as of 2026-10-17. Treat dashboard state as unverified unless noted below.

## Project at a glance

- GitHub repository: <https://github.com/alok4041/voiceover>
- Default branch observed locally: `main`
- Last published source: `72d7ffc` (`v1.2.4: landscape controls, larger runner, clearer spikes, website fallback update`). Local v1.2.5 changes are being prepared on top of it; check `git status` before release.
- Android download website: <https://alok4041.github.io/voiceover/>
- Render token/game API URL: <https://squadvoice-api-alok1404-251015-0109.onrender.com>
- Render health check: <https://squadvoice-api-alok1404-251015-0109.onrender.com/health>
- Android release in preparation: `1.2.5` / version code `17`. GitHub has a published `v1.2.4` release with asset `SquadVoice-v1.2.4.apk`.

SquadVoice is an Android Kotlin/Jetpack Compose app. LiveKit carries voice audio; a Node.js/Express server issues room codes and LiveKit tokens and also serves the Ropebound game API. The website is a separate GitHub Pages deployment. The Render service also serves `token-server/public/index.html` at `/`.

## Important directories and files

- `app/src/main/java/com/squadvoice/app/` — Android app.
- `app/src/main/java/com/squadvoice/app/game/` — Ropebound game UI and Android HTTP/WebSocket API client.
- `app/src/main/java/com/squadvoice/app/voice/` — voice room/session implementation.
- `app/src/main/AndroidManifest.xml` — Android permissions, links, and cleartext policy.
- `app/build.gradle.kts` — Android version and `TOKEN_SERVER_URL` BuildConfig wiring.
- `gradle.properties` — currently contains the project token-server URL.
- `token-server/server.js` — Express app, health check, voice room APIs, static site serving, and game route mounting.
- `token-server/ropebound/routes.js` — Ropebound HTTP lobby endpoints, authenticated game WebSocket, and room-participant authorization.
- `token-server/ropebound/game-core.js` — authoritative game simulation, hazards, and course configuration.
- `token-server/public/index.html` — Render-served copy of the download site.
- `index.html` — GitHub Pages download site.
- `docs/ropebound-design.md` — game design reference.
- `docs/ROPEBOUND-GAME-GUIDE.md` — current game rules, API contract, actual-vs-planned behavior, and guidance for independent gameplay changes.
- Current game transport: lobby actions remain HTTP; live snapshots, controls, and one-second latency probes use `wss://<token-server>/games/realtime?roomCode=...`. Voice continues to use LiveKit unchanged. `ws` is a token-server dependency. Deploy the server and publish a matching Android APK together before players can use this transport; local build success does not mean Render is updated.
- `docs/SYSTEM-FLOW.md` — Mermaid diagrams for player/runtime flow and where each part is published.
- `README.md` — development setup; some URL guidance is stale (see below).

### Quick working-directory map

```text
voiceover/                         repository root; Android Gradle project + website
├── app/                            Android app source, resources, and build config
│   └── src/main/java/com/squadvoice/app/
│       ├── MainActivity.kt         app navigation and top-level screen wiring
│       ├── AppUpdateChecker.kt     website/release update check behavior
│       ├── game/                   Ropebound game UI, audio, and HTTP client
│       └── voice/                  voice UI, room API/session, services, overlay
├── token-server/                   Node.js backend deployed to Render
│   ├── server.js                   Express setup, room/token API, health, static page
│   ├── ropebound/                  game routes/auth and authoritative simulation
│   └── public/index.html           Render-hosted copy of the download website
├── index.html                      GitHub Pages download website
├── docs/                           design notes and agent handoff docs
├── gradle.properties               Gradle properties, including TOKEN_SERVER_URL
└── settings.gradle.kts             Android/Gradle project setup
```

Open these files first based on the task:

- Android screen, navigation, install/update flow: `app/src/main/java/com/squadvoice/app/MainActivity.kt`, `AppUpdateChecker.kt`.
- Voice connection, create/join room, microphone lifecycle: `voice/VoiceRoomScreen.kt`, `RoomApi.kt`, `VoiceSessionManager.kt`, `VoiceRoomService.kt`.
- Ropebound visuals and controls: `game/RopeboundGameScreen.kt`; game HTTP calls: `game/RopeboundApi.kt`; game sounds: `game/RopeboundGameAudio.kt`.
- Backend room/token behavior: `token-server/server.js`.
- Ropebound endpoints and room authorization: `token-server/ropebound/routes.js`.
- Course hazards and game simulation: `token-server/ropebound/game-core.js`.
- User-facing download page: root `index.html`; Render-hosted copy: `token-server/public/index.html`.

### Local run/build commands

Run Android commands from the repository root. Run Node commands from `token-server/`:

```sh
# Android debug APK
./gradlew :app:assembleDebug

# Local token server (requires token-server/.env)
cd token-server
npm install
npm start
```

The local token server listens on port 3000 by default. Its health check is `/health`. No `AGENTS.md` instruction file was found in the checked repository paths when this guide was prepared.

## Current source and local changes

The v1.2.4 fixes for the `/games/input` room-code body, dismissible game errors, runner ground anchor/rope drawing, shorter server hazard spans, and landscape controls are committed in `72d7ffc` on `main`.

The room-code fix addresses the screenshot error “Enter a valid six-character voice room code.” Voice chat can already be connected while the game API separately rejects input: the game route reads `roomCode` from the request body to validate the participant's room.

The v1.2.5 local changes include the 3D Ropebound scene, preview runners, character styling, WebSocket game transport, and updated website fallbacks. These changes are not published until committed and pushed. The WebSocket server endpoint must be deployed to Render before users install the matching APK. Voice remains on LiveKit.

The v1.2.4 release fixes the room-code error, message dismissal, spike readability, runner grounding, and touch controls. The v1.2.5 release adds the changes listed in `docs/release-notes-v1.2.5.md`.

### Transfer warning for a separate agent

The v1.2.4 source is on GitHub `main`. The v1.2.5 source is still local until pushed. Check the latest commit and deployment status before updating this handoff.

## Build and URL behavior

`app/build.gradle.kts` reads Gradle property `TOKEN_SERVER_URL`, falling back to the same Render URL. The tracked root `gradle.properties` currently sets:

```properties
TOKEN_SERVER_URL=https://squadvoice-api-alok1404-251015-0109.onrender.com
```

That Render URL was provided by the user. A physical phone must use a reachable server URL; `10.0.2.2` is an Android-emulator alias for the host computer and will fail on a physical phone. The earlier phone screenshot showed a connection attempt to `10.0.2.2:3000`.

Build a debug APK from the repository root:

```sh
./gradlew :app:assembleDebug
```

To override the server URL for one build:

```sh
./gradlew :app:assembleDebug -PTOKEN_SERVER_URL=https://your-server.example
```

The debug APK output is `app/build/outputs/apk/debug/app-debug.apk`; release upload filename is `SquadVoice-v1.2.5.apk`. The current v1.2.5 APK was built successfully, verified as package version code `17` / version name `1.2.5`, embeds the Render URL, and installs over the previous debug build on the connected emulator. Its SHA-256 is `b1c41b95d1e83fe1b85b51fa0d170ded6908d306b12aa6e5a99b95ed1b54b779`. Never put LiveKit API secrets in Android code, Gradle properties, or the APK.

**README mismatch:** `README.md` still says the default URL is `http://10.0.2.2:3000`, but the current Gradle build default and root property point to Render. The README example for a local phone build is still useful when intentionally running a local server. Update documentation only when it is in scope and keep the emulator-vs-phone distinction clear.

## Local token server

From `token-server/`, `npm start` runs `node --env-file=.env server.js`. The server requires `LIVEKIT_URL`, `LIVEKIT_API_KEY`, and `LIVEKIT_API_SECRET`; local `.env` is ignored by Git and must stay private. The default local port is `3000`; the server listens on `0.0.0.0` so a phone on the same Wi-Fi can reach the laptop's LAN IP if the firewall allows it. `GET /health` returns `{ "ok": true }`.

For local development, put a phone-reachable LAN URL in the Gradle property (example only): `http://192.168.x.x:3000`. On a phone, `127.0.0.1` means the phone itself, and `10.0.2.2` is for Android emulators.

## Render deployment notes

- The Render service URL is `https://squadvoice-api-alok1404-251015-0109.onrender.com`.
- The service runs from the `token-server/` subtree. A prior Render log showed the package path `/opt/render/project/src/token-server/package.json`, `npm install`, and Node `26.12.0`.
- `token-server/package.json` uses `npm start` and requires Node `>=20.6`.
- Configure Render's environment variables (`LIVEKIT_URL`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`) in Render's Environment settings; do not copy their values into this document or commit them.
- Verify the live deployment with `/health` and by trying voice room creation/join and the game endpoints.
- Render free instances may spin down with inactivity, as shown in the user's dashboard screenshot.
- An earlier Render dashboard inspection showed the service still live on commit `f41b796`, older than the Ropebound fixes. The dashboard was not accessible during this publish check; verify the latest deploy and logs before treating the backend fixes as live.
- The root website and Render-served website are separate copies (`index.html` vs `token-server/public/index.html`). Keep both aligned if the Render root page is intended to match GitHub Pages.

## Website and GitHub APK release

- GitHub Pages site: <https://alok4041.github.io/voiceover/>.
- This is the public place users visit to download/install the Android app. It is a website, not the Android app itself.
- The Android app source is in this repository under `app/`. The installable file is an APK attached to a GitHub Release; the website links users to that release asset.
- The backend is published separately as the Render web service at <https://squadvoice-api-alok1404-251015-0109.onrender.com>. Render is not where users download the APK.
- No Google Play listing or Play Store publishing setup was found in the checked repository. The observed distribution path is direct APK download from the site/GitHub Release.
- Where code changes go: the GitHub repository `alok4041/voiceover`, normally its `main` branch. Confirm the repository's Pages settings before relying on a push to `main` to update the website.
- Where the APK goes: GitHub repository → **Releases** → publish tag `v1.2.5` and attach `SquadVoice-v1.2.5.apk`.
- Where backend changes go: the same GitHub repository's `token-server/` folder, then deploy the Render service. Check the Render service's connected repo/branch and root/build/start settings before deploying.
- Both homepage copies are updated locally to a static `v1.2.5` fallback and JavaScript that requests GitHub releases at `https://api.github.com/repos/alok4041/voiceover/releases?per_page=100`.
- The v1.2.5 fallback URL is `https://github.com/alok4041/voiceover/releases/download/v1.2.5/SquadVoice-v1.2.5.apk`.
- No `.github` workflow was found during the repository check. Do not assume APK builds, releases, or Render deploys are automated; inspect GitHub and Render settings before promising automation.
- To publish a new APK, first review the source changes, rebuild, and install/test the APK. Then follow the repository owner's GitHub Release process, using the expected tag and APK asset name if that convention is retained. If the GitHub API is unavailable or there is no release yet, the static fallback can remain stale, so consider updating both HTML fallbacks as part of a website release change.
- `app-debug.apk` is a debug build. Before replacing an APK already installed on a user's phone, ensure the new APK has a compatible signing certificate; Android refuses in-place updates signed by a different key. Keep the signing key private and backed up. Do not claim a Play Store-ready release unless a proper release signing/distribution setup is added and verified.
- The user authorized publishing the updated work in this task. Confirm before any materially different external action.

## Recommended continuation checklist

1. Start with `git status --short` and `git diff`; confirm the latest commit and release state.
2. Read `docs/ropebound-design.md`, `token-server/ropebound/routes.js`, and the relevant game UI/API code before changing behavior.
3. Confirm the Render service is healthy and its environment variables are configured without exposing secret values.
4. Install the rebuilt APK on a physical phone and confirm the BuildConfig URL is the Render HTTPS endpoint.
5. Check whether the pushed token-server changes are live on Render; confirm `/health` and game API behavior.
6. Deploy the backend WebSocket endpoint to Render, verify it, publish the v1.2.5 release with the APK, then verify both download-page copies and the in-app update check.
7. Report exactly what was changed, tested, deployed, and left pending.

## Copy-paste prompt for the next agent

> Continue the SquadVoice v1.2.5 release in `/Users/alok1404/voiceover`. Read `docs/release-notes-v1.2.5.md`, then inspect `git status --short`. Published source is v1.2.4 at `72d7ffc`; v1.2.5 contains 3D Ropebound and WebSocket game transport. Voice remains on LiveKit. Deploy and verify the new server endpoint before publishing the APK. Render URL: `https://squadvoice-api-alok1404-251015-0109.onrender.com`. Keep LiveKit secrets private.
