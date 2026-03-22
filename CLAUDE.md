# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
./gradlew assembleDebug     # build debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease   # build release APK (requires signing secrets)
./gradlew lint              # run lint checks
```

No test infrastructure exists yet. To add it, configure `testImplementation` dependencies in `app/build.gradle.kts` and create `app/src/test/` (unit) or `app/src/androidTest/` (instrumented).

## Architecture

Single-module Android app (Java, traditional Views). Offline-first: map tiles and routing data are loaded from device storage.

- **Package:** `de.codevoid.aNavMode`
- **Min SDK:** 26 (Android 8), **Target/Compile SDK:** 36
- **Build:** Gradle 9.4.0 with Kotlin DSL; plugin versions in root `build.gradle.kts`
- **Map:** mapsforge 0.21.0 — renders offline `.map` vector files via `TileRendererLayer`
- **Routing:** BRouter via its localhost HTTP server (`localhost:17777`); requires BRouter app installed on device. `RoutingEngine` interface makes the backend swappable.

**Layer structure in `MapView`** (order matters — taps handled top-to-bottom in reverse):
1. `TileRendererLayer` — offline tile rendering
2. Route `Polyline`s — inserted dynamically by `WaypointLayer` just below itself
3. `WaypointLayer` — always last; draws markers, handles all map taps

**Interaction model** (crosshair + FAB):
- `CrosshairView` (ported from aWayToGo, CC0) is fixed at screen centre; touch events pass through to `MapView`
- Pan map to position crosshair, press `fabAddWaypoint` (bottom-left) → `WaypointLayer.addAtCenter()` reads `mapViewPosition.getCenter()` and adds the waypoint
- Tap an existing waypoint marker → removes it and re-routes affected segments
- Green = start, blue = via, red = end; segments routed serially (one at a time)

**Offline data paths** (no storage permission needed — app-specific external dir):
- Map file: `{externalFilesDir}/maps/default.map`
- BRouter segments: managed by BRouter app itself

**Debug FAB** (bottom-right) opens a `BottomSheetDialog` (`DebugSheet`) with stubs for map downloader, BRouter segment updater, and Clear Waypoints.

**TODO before first run:** `MapManager.setInitialPosition()` — set target lat/lon/zoom.

## CI/CD

Three GitHub Actions workflows:

- **build.yml** — triggered on push to `main` or PR labeled `run-build`; runs lint → assembleDebug → sign
- **release.yml** — manual dispatch; auto-increments patch version from latest git tag, builds signed release APK (`aNavMode-${VERSION}.apk`), creates a GitHub release draft. Requires secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
- **template-setup.yml** — removed; was a one-time fork initializer that already ran
