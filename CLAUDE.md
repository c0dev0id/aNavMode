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

Single-module Android app using Jetpack Compose with Material3.

- **Package:** `de.codevoid.aNavMode`
- **Min SDK:** 26 (Android 8), **Target/Compile SDK:** 36
- **Build:** Gradle 9.4.0 with Kotlin DSL; plugin versions managed centrally in root `build.gradle.kts`

Entry point is `MainActivity.kt` — a single Compose activity. Theme is defined in `res/values/themes.xml` as `Theme.App` extending Material Light.

## CI/CD

Three GitHub Actions workflows:

- **build.yml** — triggered on push to `main` or PR labeled `run-build`; runs lint → assembleDebug → sign
- **release.yml** — manual dispatch; auto-increments patch version from latest git tag, builds signed release APK, creates a GitHub release draft. Requires secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`. The APK is named `aNavMode-${VERSION}.apk` (the `aNavMode` placeholder in this file still needs to be updated to `aNavMode`).
- **template-setup.yml** — one-time initialization on fork; already ran (commit `3a44f55`)
