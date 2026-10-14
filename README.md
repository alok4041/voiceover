# SquadVoice

SquadVoice is a small Android voice-room app for gaming squads. It uses Kotlin and Jetpack Compose on Android, LiveKit for realtime voice, and a Node.js token server for room codes and access tokens.

## What is included

- Create a temporary room or join with a six-character code.
- See who is in the room and who is speaking.
- Mute or unmute, or enable hold-to-talk.
- Keep a room connected with an Android microphone foreground service and notification controls.
- Optionally show a floating mute control over a game.
- Choose speaker output or let Android and LiveKit automatically prefer connected headphones.
- Share a room code through the Android share sheet.

The app publishes microphone audio only. It does not capture the game's audio mix. LiveKit uses WebRTC's voice processing and Opus audio pipeline. Headphone routing and audio focus behavior still vary by phone and game.

## Requirements

- Android Studio with Android SDK 37 installed.
- A JDK 17 or newer compatible with Gradle 9.6.
- Node.js 20 or newer for the token server.
- A LiveKit Cloud project or a self-hosted LiveKit server.

## 1. Configure LiveKit and start the token server

From the `token-server` directory, copy `.env.example` to `.env` and fill in the LiveKit project values. Keep the API secret on the server; never put it in the Android app.

```sh
cd token-server
cp .env.example .env
npm install
npm start
```

The API server listens on port 3000. Its room codes are held in memory for up to 12 hours, so restarting the token server clears them. LiveKit rooms have a 5-minute empty timeout and a 10-player cap. Use LiveKit's `wss://` URL in `.env`; the token server converts it to `https://` for room-management API calls.

## 2. Run the Android app

Open this project in Android Studio and sync Gradle. The default token-server URL is `http://10.0.2.2:3000`, which reaches your computer from the Android emulator. Start the token server before creating or joining a room.

For a physical phone on the same Wi-Fi, pass your computer's local network address to Gradle:

```sh
./gradlew :app:assembleDebug -PTOKEN_SERVER_URL=http://192.168.1.20:3000
```

Replace the example address with your computer's actual LAN address. Debug builds allow cleartext HTTP for local development. Release builds block cleartext traffic; use an HTTPS token-server URL for release builds.

## 3. Try a voice room

Install the debug app on two Android devices or emulators. Create a room on one, then share or copy its code and join on the other. Grant microphone access when prompted. To use the floating control, tap **Game overlay** and enable SquadVoice in Android's overlay settings.

## Audio and game compatibility

SquadVoice starts its microphone foreground service while the app is visible so the room can stay active when a game is in front. It does not request audio focus, which helps the game keep playing its footsteps and effects, and it sends only the microphone to the room. Android and the game still control audio routing and can behave differently across phones; wired or USB headphones usually preserve game audio best.

Android cannot guarantee that the game and SquadVoice both receive the phone microphone at once. Turn off the game's built-in voice chat/mic and use SquadVoice for talking; leave the game's sound effects enabled. This must be checked on each target game and phone.

## Project layout

- `app/` — Android UI, LiveKit room session, foreground service, and floating control.
- `token-server/` — room-code API and LiveKit token issuer.

This starter keeps room codes in memory and does not include accounts, friends, saved rooms, or production abuse controls. Add persistent storage and server-side rate limits before opening the service to the public.
