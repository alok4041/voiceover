# SquadVoice — Project Handoff and Working Guide

This file is the project handoff for Codex, Antigravity, and other contributors. Read it before changing code or deployment settings. Treat the repository and all files served by the public site as public.

Last reviewed: 2026-10-16 (Asia/Kolkata). Recheck the live site and service before assuming they are currently healthy; deployment status can change.

## Project and public endpoints

- GitHub repository: <https://github.com/alok4041/voiceover>
- Android/download website (GitHub Pages): <https://alok4041.github.io/voiceover/>
- Render token API: <https://squadvoice-api-alok1404-251015-0109.onrender.com>
- API health check: <https://squadvoice-api-alok1404-251015-0109.onrender.com/health>
- The app website is public. The Render service is the app's token/API backend; it is not a private server.
- These are the configured URLs known for this project. Do not claim a shorter `squadvoice.com` domain is configured unless the owner registers and connects it.

The website reads published GitHub Releases to list APK versions and download links. The Android update checker reads the latest GitHub Release. The web page also has a saved fallback release card in case GitHub's release API is unavailable. The root `index.html` is used for GitHub Pages; `token-server/public/index.html` is a copy served by Express. Keep the copies aligned if changing the website.

The Android app is at version `1.2.1`, `versionCode` 13. Check GitHub Releases before telling users which APK is newest; the website and in-app checker use the public Releases API.

## What the app does

SquadVoice is an Android voice-room app for gaming groups. People create a room and share a six-character code; teammates join with that code and talk over LiveKit/WebRTC. It includes room/participant UI, microphone controls, an Android foreground service and persistent room notification, optional game overlay controls, per-participant playback volume, day/night appearance, accent themes, per-device Normal/Krisp microphone filter selection, app update checking, and an About section.

Voice is sent from the phone microphone. The app does not capture or transmit game audio. Audio routing and whether a particular game shares the microphone are controlled by Android, the device manufacturer, and the game.

## Architecture and important paths

- `app/` — Kotlin Android app, Jetpack Compose interface, LiveKit client, room/session management, foreground service, and overlay.
- `app/src/main/java/com/squadvoice/app/MainActivity.kt` — screens, settings, user interactions, and theme.
- `app/src/main/java/com/squadvoice/app/VoiceSessionManager.kt` — LiveKit room lifecycle, participants, microphone state, and per-person output volume.
- `app/src/main/java/com/squadvoice/app/MicNoiseMode.kt` — saved microphone filter choice and compatibility with the earlier Boolean preference.
- `app/src/main/java/com/squadvoice/app/AppUpdateChecker.kt` — looks up the latest public GitHub Release for the in-app update prompt.
- `app/build.gradle.kts` — Android SDK, app version, LiveKit dependency, and token-server URL build setting.
- `token-server/` — Node.js/Express API that creates rooms and issues short-lived LiveKit participant tokens.
- `token-server/server.js` — API routes and token generation. `/health` is a simple public health route; `/rooms/create` and `/rooms/join` handle room creation and joining.
- `token-server/.env.example` — placeholder names for local configuration. It must contain no real keys.
- `index.html` — GitHub Pages download website and release list.
- `token-server/public/index.html` — duplicate static page served by the Render service.
- `assets/squadvoice-app-icon.png` — app/site artwork.
- `docs/ropebound-game-design.md` — separate game design proposal; it is not part of the voice-room app runtime.

The server holds room-code metadata in memory for up to 12 hours. A server restart clears that map, so outstanding codes stop working. LiveKit rooms are configured with a 5-minute empty timeout and a maximum of 10 participants. The API does not currently provide accounts or durable room storage. Do not promise that rooms or codes survive a server restart.

## Local development

### Token server

Use Node.js 20.6 or newer. From the repository root:

```sh
cd token-server
cp .env.example .env
npm install
npm start
```

Fill in `.env` on the developer's machine with the LiveKit values. Never commit or share that file. Local server listens on port 3000 by default. Check it with `http://localhost:3000/health`.

### Android app

Use Android Studio / Android SDK 37 and JDK 17 with the checked-in Gradle wrapper. The default URL is the configured Render API. For local API testing, override it at build time:

Android emulator:

```sh
./gradlew :app:assembleDebug -PTOKEN_SERVER_URL=http://10.0.2.2:3000
```

Physical Android device connected to the same Mac by USB (with USB debugging authorized):

```sh
adb reverse tcp:3000 tcp:3000
./gradlew :app:assembleDebug -PTOKEN_SERVER_URL=http://127.0.0.1:3000
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The local `build/` output is ignored by Git. The APK currently packages `arm64-v8a` and `armeabi-v7a`; x86 Android emulators are excluded to keep the shared APK focused on Android phones. Do not remove these ABI filters without checking the APK size and intended devices. Do not install it on the user's connected phone or interrupt a live room without the user's direct approval.

For local build checks, use `./gradlew :app:compileDebugKotlin` or `./gradlew :app:assembleDebug`. Do not add or run tests unless the user asks. A successful build does not prove real-device audio behavior; test room join, leaving, notification actions, and audio with consent on target phones before publishing.

## Git workflow

Protect the user's work. Before editing, inspect:

```sh
git status --short --branch
git diff --stat
git diff
```

Never run destructive cleanup (`git reset --hard`, `git clean`, forced checkout, or force-push) unless the user explicitly asks. Do not overwrite unrelated modified files. Stage named files instead of blindly running `git add .`.

This is one project. Use the existing `main` branch for routine requested work; do not create a worktree or feature branch by default. Use a short-lived branch only when the user asks for isolation or a change needs separate review.

Recommended contribution flow:

```sh
git status --short --branch
# edit only the requested files
git diff --check
git status --short
git diff
git add path/to/reviewed-file
git commit -m "Describe the change"
# For a requested publication, push the committed main branch and verify the live result.
```

The live Render service and GitHub Pages are connected to the `main` branch in the known deployment setup. A push to `main` publishes the website and may trigger a Render deployment. When the user directly asks to publish a website or app change, treat that as authorization for the requested publication; do not ask a redundant second time whether to update the site. Complete the work, review it, commit it, publish it, and verify the live result. If publication may interrupt the live API or an active room, ask only about that specific interruption unless the user has already authorized it. Never install or update the user's phone unless they directly ask.

## Publishing a website change

The configured GitHub Pages site is served from the repository root. Check **GitHub → Settings → Pages** if its publishing source ever changes. A change to root `index.html` becomes public after it is pushed to the configured branch (`main` in the known setup). If editing the website, update `token-server/public/index.html` too so the Render static copy stays aligned. Review the diff and links, then publish when the user has requested the website change; do not stop after a local commit.

Do not deploy the website through Render as a substitute for GitHub Pages; the canonical download site URL above is the GitHub Pages URL.

## Publishing an Android APK

APK distribution is through a public GitHub Release, not by committing large APK files into the Git repository. The website lists releases that have an `.apk` asset; the in-app update checker also relies on the GitHub Releases API.

### Required release sequence

1. Inspect and review the exact source changes. Do not build or publish from uncommitted app changes.
2. Commit the app changes and version bump to a named Git branch. Record the commit SHA and branch. A release branch commit is a commit, but it does not update `main`; state this distinction plainly.
3. Build the APK from that committed source revision, not from a different checkout. Verify the APK package name, `versionCode`, `versionName`, signing certificate, size, and SHA-256. The release tag must point to the same source commit as the APK.
4. Push the source branch and matching tag, then create the GitHub Release and upload `app-debug.apk`. Never describe a local build or a pushed tag alone as a published release.
5. Verify GitHub's latest-release API returns the new tag and APK asset. Then reload the live website and confirm its version card and download link show the same release. The website has a saved fallback list; if it is showing the fallback or an older version, the site is not verified as updated. Investigate the browser/API cache or update the site fallback in both `index.html` and `token-server/public/index.html`, then deploy that site change through the configured Pages source.
6. Report each state separately: local commit, pushed branch, tag, published release, live website, and device installation. A commit made in an isolated worktree does not clear uncommitted changes in the user's original checkout. Never imply the user's checkout is clean unless you verified it.

Keep the APK out of Git history. Do not install a release on a connected phone unless the user directly asks; publishing a release does not mean installing it on the user's device.

Before a new app release:

1. Finish the requested code changes and review `git diff`.
2. Increase both `versionCode` and `versionName` in `app/build.gradle.kts`. Never reuse a `versionCode` for a published build. Use a matching Git tag such as `v1.2.1` for version `1.2.1`.
3. Build the APK with the intended production token URL and verify the generated artifact. Current default URL is the Render API listed above.
4. Preserve the APK signing identity between updates. Replacing or losing the signing key can prevent an in-place update; users may have to uninstall first, which can erase app data. The current shared APK flow has used the debug APK, which is for direct testing and is not a Google Play release. Do not casually switch signing keys or claim Play Store readiness. The shared APK currently targets ARM phones, not x86 Android emulators.
5. Create a GitHub Release for the matching tag and attach the APK using the expected filename `app-debug.apk`; write clear notes and label a preview as pre-release where appropriate.
6. Check the Release entry, website download buttons, and update checker after GitHub publishes it.

The user has explicitly asked to be consulted before publishing a new Android APK release. A direct request to publish a website update authorizes the needed website publication. Ask before a deployment step that may interrupt the live API unless the user has authorized that interruption. Never install the APK on the user's phone unless asked.

## Render API deployment

The Render service runs the Node token server. `server.js` listens on `process.env.PORT` and binds to `0.0.0.0`, as Render expects. The Node package requires Node.js `>=20.6`; the start command shown in deployment logs was `node server.js`. The service needs these Render Environment variables:

- `LIVEKIT_URL` — LiveKit Cloud WebSocket URL (`wss://...`).
- `LIVEKIT_API_KEY` — secret server-side API key.
- `LIVEKIT_API_SECRET` — secret server-side API secret.

Render supplies `PORT`. The local `.env` is for development only; production values belong in Render's private Environment settings. Do not place secrets in build arguments, GitHub Actions logs, screenshots, source code, website JavaScript, app resources, or GitHub Pages.

Render has been configured to build from this GitHub repository, branch `main`, with the `token-server` directory as the Node service root in the known setup. The previous deployment logs showed a successful Node server start and the health endpoint returned `{"ok":true}` in the user's browser. Confirm current Render settings and logs before changing them. A `main` push may cause an automatic deployment that briefly interrupts room creation/joining; if the user requested only a website publication and has not authorized that interruption, ask about this specific impact before pushing.

The backend currently has no persistent store or request rate limiter. It is reachable publicly. Before significantly widening distribution, consider abuse/rate-limit protections and monitoring; make and deploy such changes only with approval.

## Security and privacy rules

- The GitHub repository and Pages website are public. Assume anyone can read source, APKs, release notes, commit history, and website content.
- Keep `token-server/.env`, `local.properties`, keystores, signing passwords, LiveKit API secret, and any access token out of Git. `.gitignore` currently excludes local properties, `.env`, build output, `node_modules`, and common Android signing files; recheck ignore rules before staging.
- Never embed `LIVEKIT_API_SECRET` in the APK. The Android app receives scoped, short-lived participant JWTs from the token server; the API key/secret stay on Render.
- `.env.example` must only contain dummy placeholders. Do not paste actual secret values into this guide or chat.
- If a credential is exposed, removing it from the latest commit is not enough because Git history and public copies may remain. Rotate/revoke it in the issuing service, update Render's environment, and then address repository history as a separate user-approved security operation.
- Do not publish personal contact details or private account information without the owner's instruction. Treat room codes and participant tokens as private while active.
- Do not ask the user to send keys in chat. Explain where to set them in Render or a local ignored `.env` file.

## Rules for future contributors

- Start with `git status`; preserve existing changes.
- Make only the requested change and explain the files touched.
- Keep secrets out of tracked files and logs.
- Carry out changes and publication directly requested by the user without redundant confirmation. Ask before installing on devices, changing an active room, stopping services, or causing a likely interruption unless the user explicitly authorized that impact.
- Before a release, show the concrete version, APK artifact, signing implications, release notes, and deployment plan, then ask for approval.
- Verify health and release links after an approved deployment; never infer success merely from a local build.
