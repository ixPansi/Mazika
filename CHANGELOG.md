# Changelog

## MAZIKA 2024.12.115 (initial MAZIKA release)

Based on Symphony upstream commit `dd04b872b8b4e6dd56172c053a5776c4d56ad080`.

### Rebranding
- Renamed the app to **MAZIKA** (launcher label, About screen, notification
  channel, translated strings).
- New original MAZIKA launcher icon (bold "M" wordmark, adaptive foreground +
  monochrome/themed variant).
- Application id changed to `com.mazika.musicplayer` so MAZIKA installs alongside
  the original Symphony app. The debug/canary variants are suffixed accordingly.
- Upstream Symphony attribution retained in About and `LICENSE`.

### New features
- **Android Auto** — a `MediaBrowserServiceCompat` that shares the existing media
  session and playback engine. Browse Songs, Albums, Artists, Playlists, Genres and
  Folders, play items (with the correct queue built from the browse context), and
  search by voice/text. Custom playlist covers and artwork are shown via a
  read-only content provider. No lyrics are shown on the driving screen.
- **Swipe down on the Now Playing cover to open lyrics** — reuses the existing
  lyrics view/route; only on the full-screen cover, not the mini-player; the lyrics
  button remains.
- **Fade on pause and resume** — a dependent option under "Fade playback"
  (Settings → Player), default on, disabled when the master option is off. Controls
  only user-initiated pause/resume fades; track-transition and sleep-timer fades are
  unchanged.
- **Custom playlist covers** — pick, preview, replace and remove a square custom
  image per playlist from the playlist menu. Stored as an optimised WebP in internal
  storage, shown across the whole app and in Android Auto, with safe fallback,
  atomic replacement and cleanup.

### Technical
- `Symphony` is now process-scoped (`SymphonyProvider`) so the Android Auto media
  browser service shares one playback source of truth even when started with no
  activity.
- Room `PersistentDatabase` migrated 1 → 2 (nullable `customCoverPath` column);
  existing playlists are unaffected.
- Added JUnit 5 unit tests for the fade decision, swipe-gesture decision, Android
  Auto media-id encoding and playlist-cover sampling.
