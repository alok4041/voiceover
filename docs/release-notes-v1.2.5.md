# SquadVoice v1.2.5 — Ropebound 3D and real-time game networking

This update refreshes Ropebound's playable game scene and moves frequent game traffic to a persistent WebSocket connection. Voice chat remains on LiveKit.

## What's new

- Adds a 3D follow-camera course with a top-view camera option.
- Adds a moving river, curved rope bridge, animated bridge planks, course hazards, rocks, trees, grass, and other scene details.
- Improves the rear view of runners with fuller hair silhouettes, textured hair locks, several hairstyle shapes, names, and ping labels.
- Sends live game snapshots, player controls, and one-second latency probes over an authenticated WebSocket. Lobby actions continue to use HTTP.
- Keeps the game preview accessible with four local demo runners when a full multiplayer squad is not available.
- Updates both website download-page fallbacks to v1.2.5.

## Install

Download `SquadVoice-v1.2.5.apk` and install it on Android 8.0 or newer. Install over the previous SquadVoice APK; the debug signing key on this build host is unchanged. This is a direct APK release, not a Google Play release.

## Release checklist

- Build and verify the v1.2.5 APK, then attach it to the `v1.2.5` GitHub Release as `SquadVoice-v1.2.5.apk`.
- Deploy the matching token-server changes to Render so `/games/realtime` is available before users install this APK.
- Publish the updated website fallback on GitHub Pages and Render, then verify the APK download and in-app update check.
