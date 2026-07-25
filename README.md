<h1 align="center">MAZIKA</h1>

<p align="center">🎵 A lightweight, offline, local music player for Android.</p>

<p align="center"><b>MAZIKA is a customised Android-only fork of <a href="https://github.com/zyrouge/symphony">Symphony</a> by Zyrouge, distributed under the AGPL-3.0 license.</b></p>

---

## About

MAZIKA is an offline, local music player built on top of the open-source
[Symphony](https://github.com/zyrouge/symphony) project. It keeps Symphony's fast,
elegant, Jetpack Compose experience and adds a small set of Android-focused
features while remaining fully offline-first (no analytics, no accounts, no
streaming).

It installs with its own application id (`com.mazika.musicplayer`) so it can live
side by side with the original Symphony app.

### Attribution & license

MAZIKA is a **modified fork** of Symphony. It is **not** an official Symphony
release and is not affiliated with or endorsed by the Symphony authors. The
original project, its copyright notices and third-party library licenses are
preserved. Upstream attribution (author, source repository) remains available in
the app's **Settings → About** section and in the [`LICENSE`](./LICENSE) file.

Licensed under [AGPL-3.0](./LICENSE), the same license as upstream Symphony.

## MAZIKA features (on top of Symphony)

1. **MAZIKA rebranding** — name, launcher icon and application id, installable
   alongside Symphony.
2. **Android Auto** — browse Songs / Albums / Artists / Playlists / Genres /
   Folders and play them in the car, backed by the same playback engine and
   session as the phone. Voice/media search is supported. (No CarPlay / iOS.)
3. **Swipe down on the Now Playing cover to open lyrics** — a shortcut to the
   existing lyrics view; the lyrics button remains as well.
4. **Configurable pause/resume fade** — a dependent "Fade on pause and resume"
   option nested under "Fade playback" (Settings → Player).
5. **Custom playlist covers** — pick, preview, replace and remove a square custom
   image for any playlist; it appears everywhere including Android Auto.

All existing Symphony functionality is preserved.

## Requirements

- **Android 9 (API 28) or later** (unchanged from Symphony).
- Build toolchain: JDK 17, Node.js 18+, Android SDK 35 (build-tools 35.0.0),
  Android NDK `27.0.12077973`, CMake `3.22.1`. The Gradle wrapper (8.10.2) fetches
  Gradle itself.

## Building

MAZIKA has a native module (`metaphony`, a TagLib wrapper) so the NDK/CMake and the
TagLib git submodule are required.

```bash
# 1. Fetch the native submodule (TagLib)
git submodule update --init --recursive

# 2. Install the Node tooling (used for the translation/i18n generation)
npm install

# 3. Generate translation resources (REQUIRED before every build — the generated
#    files under app/src/main/assets/i18n and *.g.kt are git-ignored)
npm run prebuild

# 4a. Debug build (Windows)
gradlew.bat assembleDebug
# 4b. Debug build (Linux/macOS)
./gradlew assembleDebug
```

Point Gradle at your SDK via `local.properties` (use forward slashes on Windows):

```
sdk.dir=C:/Users/<you>/Android/Sdk
```

APKs are written to `app/build/outputs/apk/debug/` (ABI splits plus a
`app-universal-debug.apk`).

**The ready-to-install app is [`artifacts/MAZIKA.apk`](./artifacts/)** - a signed,
minified release build (~14 MB). MAZIKA has a single identity across build types:
application id `com.mazika.musicplayer`, label `MAZIKA`, with no `.debug` suffix or
"(Debug)" label, so every build installs and presents as the finished app.

### Release build

```bash
gradlew.bat assembleRelease
```

Release builds are minified (R8) and signed with a keystore supplied via environment
variables. A local release key already exists at `secrets/mazika-release.jks`
(git-ignored); load it and build with:

```bash
set -a; source secrets/keystore.env; set +a
gradlew.bat assembleRelease
```

See [`SIGNING.md`](./SIGNING.md). Without a keystore an unsigned release APK is
produced instead of the build failing.

## Android Auto testing

Android Auto cannot be verified from a headless build. To test on real hardware:

1. Install `artifacts/MAZIKA.apk` on a phone and grant the music-library permission.
2. Enable **Developer settings** in the Android Auto app and turn on **Unknown
   sources** so a sideloaded build is visible.
3. Connect to the **Desktop Head Unit (DHU)** or a real Android Auto head unit and
   open MAZIKA from the media apps list.
4. Alternatively, use the **Media Controller Test** app to exercise the
   `MediaBrowserService` browse tree and transport controls without a car.

See [`ANDROID_AUTO_TESTING.md`](./ANDROID_AUTO_TESTING.md) for the full walkthrough.

## Playlist cover storage

Selected covers are processed (EXIF-oriented, centre-cropped to a square, scaled to
at most 1024×1024) and stored as WebP inside the app's private internal storage at
`files/playlist_covers/`. The playlist stores only a relative file name. Replacing a
cover writes a new file and deletes the old one; removing a cover or deleting a
playlist deletes the file; orphaned files are cleaned up on library refresh. The
user's original gallery image is never modified or deleted.

## Documentation

- [`CHANGELOG.md`](./CHANGELOG.md) — what changed in MAZIKA.
- [`IMPLEMENTATION_REPORT.md`](./IMPLEMENTATION_REPORT.md) — architecture, files
  changed, migrations, tests, APKs, limitations.
- [`TESTING.md`](./TESTING.md) — manual test cases.
- [`SIGNING.md`](./SIGNING.md) — release signing.
- [`ANDROID_AUTO_TESTING.md`](./ANDROID_AUTO_TESTING.md) — testing Android Auto without a car.

## License

[AGPL-3.0](./LICENSE) — inherited from Symphony. Based on
[zyrouge/symphony](https://github.com/zyrouge/symphony).
