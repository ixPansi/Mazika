# Testing MAZIKA's Android Auto support without a car

You do **not** need a car. Android Auto runs on your phone and projects to a screen —
that screen can be your PC. There are three ways to test, easiest first.

| Method | What it proves | Needs |
|---|---|---|
| 1. Media Controller Test | Browse tree, metadata, play/pause/next/seek | Phone or emulator only |
| 2. Desktop Head Unit (DHU) | The real Android Auto UI, exactly as in a car | Phone + USB + PC |
| 3. Automotive OS emulator | Built-in-car (AAOS) behaviour | Android Studio only |

MAZIKA exposes Android Auto through a `MediaBrowserServiceCompat`
(`services/radio/RadioBrowserService.kt`) that shares the phone's media session, so
methods 1 and 2 exercise the same code the car would.

---

## Method 1 — Media Controller Test app (fastest, no car, no DHU)

This is Google's official harness for media apps. It connects straight to MAZIKA's
media browser service and lets you walk the browse tree and fire transport controls.

1. Get the app (part of the `android/uamp` samples):
   ```bash
   git clone https://github.com/android/uamp.git
   cd uamp
   ./gradlew :media-controller-test:installDebug
   ```
   (Or install `MediaControllerTest` from the Play Store — "Media Controller Test".)

2. Install MAZIKA and grant it storage/media permission so the library is not empty:
   ```bash
   adb install -r artifacts/MAZIKA.apk
   ```

3. Open **Media Controller Test** → pick **MAZIKA** from the list of media apps.

4. What to verify:
   - The root shows **Songs / Albums / Artists / Playlists / Genres / Folders**.
   - Each category opens and lists items; albums/artists/playlists open to their songs.
   - Tapping a song starts playback and the **phone's notification updates too**
     (proves the shared session/queue).
   - Play / Pause / Skip next / Skip previous / Seek all work.
   - Custom playlist covers show as item artwork.
   - The `Search` box returns songs, albums, artists and playlists.

If the app does not appear in the list, the browser service is not being exported
correctly — check the merged manifest for the
`android.media.browse.MediaBrowserService` intent filter.

---

## Method 2 — Desktop Head Unit (the real Android Auto UI on your PC)

DHU renders the actual Android Auto interface on your desktop, driven by your phone.
This is the closest thing to a car.

### One-time setup

1. Install the DHU through the Android SDK:
   ```bash
   sdkmanager --install "extras;google;auto"
   ```
   It lands in `$ANDROID_HOME/extras/google/auto/`.
   On this machine that is `C:\Users\Pc\Android\Sdk\extras\google\auto\`.

2. On the **phone** (DHU needs a real device — it does not work with an emulator):
   - Install **Android Auto** from the Play Store (on Android 10+ it is built in;
     open Settings → Apps → Android Auto).
   - Open Android Auto settings and tap the **Version** entry ~10 times to unlock
     **Developer settings**.
   - In the ⋮ menu choose **Start head unit server**.
   - Enable **Unknown sources** in Android Auto's developer settings, otherwise your
     locally-built debug APK will not be listed.

3. Enable USB debugging and connect the phone by USB.

### Each run

```bash
adb forward tcp:5277 tcp:5277
cd "C:\Users\Pc\Android\Sdk\extras\google\auto"
desktop-head-unit.exe        # Linux/macOS: ./desktop-head-unit
```

The DHU window opens. Go to the media apps list, pick **MAZIKA**, and test the same
checklist as Method 1 — plus:

- Locking the phone must **not** stop playback.
- Disconnecting (close DHU) must not corrupt playback state — the phone keeps playing.
- Pause/resume from the car UI must honour the **Fade on pause and resume** setting.
- The playback screen shows **seek back / seek forward** and **Lyrics** buttons. These
  are media-session *custom actions*, so they appear on the full player only - Android
  Auto's dashboard media card next to the map keeps just play/pause and
  next/previous, which is the intended driver-safe behaviour.
- The **Queue** category is first, so the pane you land on when you swipe away from the
  player is the queue rather than the song list. Entries show cover art and jump to
  that position when tapped. The native **Queue** button on the player shows the same
  thing with artwork.
- The root screen order follows **Settings -> Android Auto** on the phone, so you can
  put Playlists (or anything else) first.

### Lyrics in the car

Android Auto media apps cannot draw a custom screen — the template gives you a browse
list and a playback screen and nothing else — so a player button cannot open a lyrics
page. MAZIKA takes the two shapes that *are* expressible:

- The **Lyrics button on the player** puts the current lyric line where the artist name
  normally sits and keeps it in step with playback. One line at a time, which is the
  least distracting form this can take. It needs timed (`.lrc`) lyrics; for untimed
  lyrics the artist line is left alone.
- The **Lyrics browse category** lists the playing song's full lyrics as rows, which
  works for untimed lyrics too. Tapping a timed line seeks to it.

Two things to be aware of. The lyric line is published as session metadata, which every
controller reads — so the **lock screen shows it too** while the toggle is on. It is off
by default and resets when playback stops. And the browse category is a scrollable wall
of text, which is exactly what Android Auto's driver-distraction guidance warns about;
it is listed last by default and can be switched off in **Settings -> Android Auto**.

Useful DHU keys: `?` for help, and you can simulate driving with the day/night and
distraction-mode toggles.

---

## Method 3 — Automotive OS emulator (built-in car system)

This is *Android Automotive OS* (the OS in the dashboard), not phone-projected
Android Auto. Useful as a sanity check that the media service behaves in a car-native
environment.

In Android Studio: **Device Manager → Create Device → Automotive** → pick an
"Automotive with Play Store" system image. Launch it and install MAZIKA:

```bash
adb install -r artifacts/MAZIKA.apk
```

Note MAZIKA targets phone Android Auto; it declares `<uses name="media"/>` in
`res/xml/automotive_app_desc.xml` but is not published as an AAOS-native app, so treat
this as a smoke test only.

---

## Quick checks you can run right now, with no extra tooling

Confirm the Android Auto surface is present and wired up:

```bash
# The browser service must be discoverable
adb shell cmd package query-services --brief -a android.media.browse.MediaBrowserService | grep mazika

# The media session must be registered once playback starts
adb shell dumpsys media_session | grep -i mazika

# Watch for browse errors while testing
adb logcat | grep -iE "RadioBrowser|MAZIKALogger"
```

## Troubleshooting

- **MAZIKA missing from the car/DHU app list (and from "Customise")** — work through
  these in order:

  1. **Use a build from 2026-07-25 or later.** Earlier builds declared the Auto
     descriptor with `android:value` instead of `android:resource`. Android Auto reads
     that metadata as a resource id, so it could not resolve the descriptor and never
     registered MAZIKA as a media app. Verify your APK:
     ```bash
     aapt2 dump xmltree --file AndroidManifest.xml artifacts/MAZIKA.apk | grep -A2 car.application
     ```
     You must see `android:resource(0x01010025)=@0x...`, **not** `android:value`.
  2. **Enable *Unknown sources*** in Android Auto -> Developer settings. Sideloaded
     builds are hidden without it.
  3. **Clear Android Auto's cache** - it caches the media-app list aggressively:
     ```bash
     adb shell pm clear com.google.android.projection.gearhead
     ```
     Then reopen Android Auto and redo *Start head unit server*.
  4. **Launch MAZIKA once on the phone** and grant media permission, so it is not an
     app that has never run.
  5. Confirm the service is exported and discoverable:
     ```bash
     adb shell cmd package query-services --brief -a android.media.browse.MediaBrowserService | grep mazika
     ```
- **Empty categories** — the library has not been scanned yet. Open MAZIKA on the
  phone, grant media permission and let it scan; browse requests wait up to 10s for
  the scan and then return what exists.
- **No artwork in the car** — artwork is served through a non-exported
  `ArtworkProvider` with per-URI grants; check logcat for `ArtworkProvider` errors.
- **DHU cannot connect** — re-run `adb forward tcp:5277 tcp:5277`, and make sure
  *Start head unit server* is active in Android Auto's developer settings.
