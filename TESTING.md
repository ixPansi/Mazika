# MAZIKA manual test cases

Automated unit tests cover the pure decision logic (`./gradlew testDebugUnitTest`).
The cases below are the manual/device checks for behaviour that needs the UI, audio
hardware or Android Auto. Install `app/build/outputs/apk/debug/app-universal-debug.apk` and grant the
music-library permission first.

## Phone playback (regression)
- [ ] First launch requests media permission; music scans and appears.
- [ ] Songs, Albums, Artists, Playlists, Folders, Genres, Search all work.
- [ ] Play / pause / next / previous / seek / shuffle / repeat work.
- [ ] Gapless playback and track transitions work.
- [ ] Notification, lock-screen, Bluetooth and wired-headset controls work.
- [ ] Audio focus: playback ducks/pauses for other audio and phone calls.
- [ ] Sleep timer stops playback (with its fade-out intact).
- [ ] Mini-player and full Now Playing screen work; theme modes and rotation OK.
- [ ] Settings persist; queue is restored after process recreation.

## Fade on pause/resume (Settings → Player)
- [ ] "Fade on pause and resume" appears nested under "Fade playback".
- [ ] With "Fade playback" OFF: the option is greyed out / non-interactive, and
      pause and resume are immediate.
- [ ] With "Fade playback" ON and the dependent option OFF: pause and resume are
      immediate, but starting a new track still fades in.
- [ ] With both ON: pause fades out and resume fades in.
- [ ] Toggling the master option off then on restores the dependent option's stored
      value (fresh installs default to off).
- [ ] Sleep-timer fade-out still happens regardless of the settings.
- [ ] The behaviour is the same from the player, mini-player, notification, lock
      screen, headset, Bluetooth and Android Auto.

## Lyrics swipe gesture
- [ ] A deliberate downward swipe on the large Now Playing cover opens lyrics.
- [ ] A short drag or an upward swipe does not open lyrics.
- [ ] A mostly-horizontal swipe changes track (does not open lyrics).
- [ ] The cover tap (open album) still works; the lyrics button still works.
- [ ] The mini-player does not trigger the gesture.
- [ ] A song with no lyrics shows the existing no-lyrics state (no crash).

## Custom playlist covers
- [ ] Playlist menu shows "Change cover"; picking an image shows a square preview
      and a confirm button.
- [ ] Confirming sets the cover; it appears in the grid, detail header, search and
      home.
- [ ] The cover persists after restarting the app.
- [ ] Replacing the cover updates it immediately (no stale cached image).
- [ ] "Remove custom cover" is shown only when a custom cover exists; removing it
      restores the default artwork.
- [ ] Deleting the playlist removes its cover file (verify no orphan remains).
- [ ] A corrupt/oversized image produces an error toast, not a crash; a very large
      image is downscaled.
- [ ] Existing (pre-upgrade) playlists still load after the DB migration.

## Android Auto (Desktop Head Unit / real car / Media Controller Test)
- [ ] MAZIKA appears in the Android Auto media apps list.
- [ ] Root defaults to Playlists / Songs / Artists / Genres / Albums; Folders is
      disabled until enabled in Settings -> Android Auto.
- [ ] Each category browses; opening an album/artist/playlist/folder lists songs.
- [ ] Playing a song from an album/playlist builds the correct queue.
- [ ] Custom playlist covers and artwork appear.
- [ ] Play/pause/next/previous/seek work and stay in sync with the phone UI and
      notification.
- [ ] Pause/resume obeys the fade setting.
- [ ] Voice/text search returns songs/artists/albums/playlists and plays a result.
- [ ] An empty library does not crash; a large library stays responsive.
- [ ] Locking the phone does not stop playback; disconnecting Auto does not corrupt
      playback state.

## Upgrade / migration
- [ ] Install over a previous MAZIKA build: playlists, settings and queue survive.
- [ ] Room migrates 1 → 2 without data loss.

## Backup / restore
- [ ] Exporting and importing playlists (.m3u) works. Note: custom covers are stored
      as app-private files and are not embedded in `.m3u` exports (see
      IMPLEMENTATION_REPORT.md).

## Accessibility
- [ ] New controls have content descriptions and correct enabled/disabled semantics.
- [ ] The disabled dependent fade tile is conveyed to TalkBack (not colour-only).
- [ ] Large font sizes and light/dark/black/Material You themes render correctly.
