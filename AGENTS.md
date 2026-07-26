# Repository Guide

## Setup And Commands

- This is a two-module Android project: `:app` is the Compose application and `:metaphony` is a JNI/TagLib metadata library. Initialize its nested native sources with `git submodule update --init --recursive`.
- Use JDK 17, Android SDK 35, and CMake 3.22.1. Configure the SDK in the ignored `local.properties`; do not commit machine-specific paths.
- Install Node tooling with `npm ci`. Node is only used for repository tooling and translation generation.
- Run `npm run prebuild` before Gradle builds, lint, or tests on a fresh checkout and after any `i18n/` or `.phrasey/` change. It generates ignored JSON assets plus `Translation.g.kt` and `Translations.g.kt`; edit the TOML/schema inputs, never the generated files.
- `npm run build-apk` runs a clean debug build and locates a suitable JDK, but regenerates i18n only when `app/src/main/assets/i18n` is absent. After translation edits, run `npm run prebuild` explicitly first.
- Update checks use `MAZIKA_GITHUB_REPOSITORY=owner/repository` (environment variable or Gradle property); GitHub Actions falls back to its automatic `GITHUB_REPOSITORY`. Unconfigured local builds intentionally disable update traffic.
- Windows debug build: `.\gradlew.bat assembleDebug`. Universal and ABI-split APKs land under `app/build/outputs/apk/debug/`.
- CI lint equivalent: `npm run prebuild`, then `.\gradlew.bat lintRelease`.
- JVM unit suite: `.\gradlew.bat :app:testDebugUnitTest`.
- Focus one JUnit 5 class or method with `.\gradlew.bat :app:testDebugUnitTest --tests "io.github.zyrouge.symphony.services.radio.MediaIdTest"` or append `.songWithoutContext_roundTrips`.
- Native parser tests are device instrumentation tests under `metaphony/src/androidTest`, not JVM tests. `.run/Test Metaphony.run.xml` points at the wrong module/package and is stale.

## Architecture

- The installed app is MAZIKA (`com.mazika.musicplayer`), but the Gradle root, Kotlin namespace, source packages, and many class names intentionally remain Symphony. Do not mass-rename `io.github.zyrouge.symphony` as branding cleanup.
- `MainActivity` hosts Compose and `ui/view/Base.kt` owns navigation. `Symphony` manually constructs settings, databases, library scanning (`services/groove`), playback/media session (`services/radio`), and i18n; there is no DI framework or custom `Application`.
- `SymphonyProvider` owns the process-scoped `Symphony`. The phone UI, notification, Bluetooth, voice search, and `RadioBrowserService` must continue sharing that one playback session and queue; Android Auto can start without `MainActivity`.
- `services/database` separates a replaceable media `CacheDatabase` from user-owned `PersistentDatabase`. For Room entity changes, bump the correct version, supply a migration for persistent data, and retain the generated schema snapshots in `app/room-schemas/`.
- `MainActivity` is not the launcher component. Theme-specific activity aliases in `AndroidManifest.xml` are toggled by `ui/theme/ThemeIcons.kt`; exactly one must stay enabled.
- Android Auto discovery depends on `com.google.android.gms.car.application` using `android:resource="@xml/automotive_app_desc"`, not `android:value`. Its browser service and artwork provider wiring also live in the manifest.

## Release And Device Checks

- Debug and release use the same application ID with no debug suffix. If signing variables are supplied, both use the release key; otherwise release APKs are unsigned. Never commit or replace files under ignored `secrets/`; preserving the application ID and signing key is required for in-place upgrades.
- JVM tests cover pure logic only. Use `TESTING.md` for UI/audio migration checks and `ANDROID_AUTO_TESTING.md` for Media Controller Test, DHU, and device prerequisites.

## Release Process

- Versions are ZemVer `<year>.<month>.<code>`, e.g. `2026.7.117`. `versionCode` **must** equal the third component: `cli/helpers/version.ts` throws otherwise, and `AppMeta.stableVersionPattern` matches the same shape when comparing releases. Change both in `app/build.gradle.kts`, then confirm with `npx tsx cli/version/print.ts`.
- Never ship different binaries under an already-published version — bump instead. The in-app update check assumes a version names exactly one build.
- Every release needs `metadata/en-US/changelogs/<versionCode>.txt`, at most 540 characters. Check with `npx tsx cli/changelogs/fastlane-character-limit.ts`.
- Build with `set -a; source secrets/keystore.env; set +a`, `export APP_VERSION_NAME=$(npx tsx cli/version/print.ts)`, `./gradlew clean assembleRelease bundleRelease`, then `npm run android:move-outputs`. `bundleRelease` is not optional — the move script moves the `.aab` non-skippably and fails without it.
- **Signing is conditional and fails silently.** With no `SIGNING_KEYSTORE_FILE` in the environment the build produces *unsigned* APKs instead of erroring. Always verify: `apksigner verify --print-certs dist/mazika-v<version>-universal.apk` must report certificate SHA-256 `5c37cba494d09b6026c5ec6d53464caa94b9818bc94f80305ba2b131ee7e4189`. A different key forces every existing user to uninstall before updating.
- `npm run android:move-outputs` writes named artifacts to the git-ignored `dist/`. Publish the five APKs as GitHub release assets; never commit binaries, and do not reintroduce a hand-copied `artifacts/` directory. Keep the `.aab`, `mapping.zip` and `native-debug-symbols.zip` local rather than publishing the deobfuscation map for a minified build.
- Tag `v<version>` and create the release with `--draft`; the owner reviews and publishes it.
- `main` is protected by a ruleset that gets enabled and disabled, including `required_signatures`. Run `gh api repos/ixPansi/Mazika/rules/branches/main` before assuming a push will land.
