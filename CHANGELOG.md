# Changelog

## 2026.7.120 — 2026-07-26

### Fixed
- **Custom covers set before 2026.7.118 stayed blank in Android Auto**, while covers set
  afterwards worked. The 118 fixes were correct; what remained was the car's cache. Auto
  stores browse icons keyed by their content URI, persistently and across reconnects, and
  does not retry a URI it has already failed to load. A cover's URI only changes when the
  file is re-saved, so every cover that failed *before* the fix kept the exact URI Auto had
  already written off — which is why re-picking an image appeared to be the only cure.
  Artwork URIs now carry a token derived from the file's modification time and size, so
  each one changes exactly once and then stays stable while the file does. The car
  re-fetches them and the covers appear, with nothing renamed on disk and no cover to
  re-pick.
  - The bare URI is granted alongside the versioned one, so a browser client that strips
    the query before opening still holds a matching permission.

## 2026.7.119 — 2026-07-26

### Added
- **Multi-select and bulk actions.** Long-press a song to start selecting, then tap rows
  to add or remove; the app bar becomes a selection bar showing the count. Available on
  Songs, Albums, Artists, Album artists, Genres and Folders. On a playlist the long press
  already belongs to drag-to-reorder, so selection starts from **⋮ → Select** there
  instead — the gesture is not taken away from reordering.
  - Selected songs can be played, shuffled, played next, added to the queue, added to a
    playlist, favourited or unfavourited, given one cover, had their custom covers
    cleared, or removed from the playlist you are looking at.
  - Selection is keyed on the row's list key rather than the song id, because a playlist
    may legitimately hold the same song twice. Selecting one copy selects exactly that
    copy, and removing acts on that position rather than on every occurrence.
  - Back exits the selection before it leaves the screen, and selections drop rows that
    disappear underneath them, so the count can never describe songs that are no longer
    on screen.

### Changed
- **Adding to a playlist no longer duplicates songs that are already in it**, which is
  easy to do by accident once whole albums can be selected at once. The dialog also shows
  a tick when everything selected is already in a playlist and a dash when only some of it
  is — previously it showed nothing at all unless exactly one song was being added.
- Favouriting several songs now performs a single write rather than rewriting the
  favourites playlist once per song.
- Applying one image to several songs decodes, orients, crops and compresses it **once**
  and copies the result, instead of repeating the whole bitmap pipeline per song. Each
  song still gets its own file, so clearing one song's cover cannot remove another's.

## 2026.7.118 — 2026-07-26

### Fixed
- **Custom covers were being deleted off the device.** `PlaylistRepository.fetch()` ran
  orphan cleanup *outside* the `try` that loads the playlists, so when a local `.m3u`
  failed to parse — which is exactly what happens when Android Auto cold-starts the
  process and the persisted document permission is unavailable — every playlist after the
  failing one was missing from the cache, and its cover file was treated as unreferenced
  and removed. Cleanup now runs only after a complete load, and a single unreadable
  playlist no longer aborts the rest of the loop. The same guard covers song covers.
- **Replaced covers vanished from Android Auto.** Each save writes a new file name, and
  the previous file was deleted after ten minutes. Auto caches the browse tree — and the
  content URIs inside it — across sessions and reconnects, so it would later ask for a
  file that no longer existed and draw nothing. Retired covers are now kept for a week.
- **Cover art in the car had no permission to be read.** The only grant in the app was a
  prefix grant on three directory roots, issued once at connect.
  `Context.grantUriPermission` does not honour `FLAG_GRANT_PREFIX_URI_PERMISSION` on every
  platform build — it is dependable only for grants carried on an intent — so on some
  devices the client held a grant for a directory, which is not a file, and every image
  was denied. Each icon URI is now granted explicitly as it is published, and the browse
  and search paths register their caller too rather than relying on `onGetRoot` alone.
- **MAZIKA went missing from Android Auto until something restarted.** Switching theme
  preset rewrote package component state six times — one enable plus five redundant
  disables — firing a burst of `ACTION_PACKAGE_CHANGED` for the app. Android Auto rebuilds
  its media-app list from those broadcasts, and the burst left it stale. Only aliases that
  are actually enabled are touched now, and the call no longer runs on the main thread
  during startup.
- The media browser service published its session token *after* work that could fail. If
  that work threw, the token was never set, so every connection attempt hung forever
  rather than failing — which from the car is indistinguishable from the app not existing.
  The token is now published first, and `onGetRoot` no longer throws across the binder.
- `ArtworkProvider` answered `null` to metadata queries and described everything as
  `image/*`. Image loaders that probe `DISPLAY_NAME`/`SIZE` before opening got nothing and
  gave up without ever requesting the file.

### Security
- **Any app on the device could browse the entire music library.** `onGetRoot` accepted
  every caller without inspecting it, and handed each one read grants on the app's
  otherwise-unexported artwork provider. Callers are now checked: this app, holders of the
  system media-control permission, and known media hosts that are genuinely part of the
  system image. Rejections are logged so a wrong entry is diagnosable.

## 2026.7.117 — 2026-07-26

### Added
- **A manual update check**, in Settings → Updates, with the result actually shown. The
  automatic check runs once per process launch from `onSymphonyReady`, so a device that had
  no network at that moment never retried for the life of the process — and the screen
  rendered only `UpdateState.Available`, leaving `Idle`, `Checking`, `UpToDate` and `Failed`
  all drawing nothing. A check that ran and failed was indistinguishable from one that
  never ran.
  - `checkForUpdatesNow()` deliberately ignores the `checkForUpdates` preference: that
    setting governs the startup check, and pressing the button is intent that overrides it.
    The in-flight guard is retained, which is what stops repeated taps from spending the
    60-per-hour unauthenticated GitHub rate limit.
  - The tile reports Checking, up to date, or failed-tap-to-retry inline, plus a snackbar on
    completion — but only for a check started from that screen, so the startup one stays
    silent.
  - When idle it shows how long ago the last check finished, persisted in a new
    `last_update_check` preference. Relative time comes from the platform's
    `DateUtils.getRelativeTimeSpanString`, which is localised in all eighteen languages
    without needing plural forms per locale in the translation files.

### Changed
- The **About, Source code and License** chips moved from the bottom of Settings to
  directly under the app name and version, which is where Symphony puts them and where
  they are actually looked for.
- The automatic-check switch is now labelled **Check on startup**, describing what it does
  and leaving "Check for updates" free for the button that performs one.

## 2026.7.116 — 2026-07-26

First public release. Everything below, plus the fork baseline in the section after
it, ships together in this version.

### Added
- **Recently played** at the top of For You — the things you played *from*: playlists,
  albums, artists, album artists, genres, folders, and individual songs where there was
  no larger thing to point at. Each renders as the tile it already has elsewhere, so
  tapping opens it and the play button plays it. Hidden until something has been played.
  - Nothing previously knew what a queue came from: `playQueue` took a bare list of song
    ids and the context was dropped at the call site. It now takes an optional source,
    which the tiles, the detail views and the Android Auto browser all pass — so playing
    in the car shows up on the phone.
  - Recorded once a source has actually played for five seconds, so opening an album and
    backing straight out, or skipping through a queue, does not fill the row with things
    that were never listened to.
  - Stored one row per item in `played_items` (`PersistentDatabase` 3 → 4), so replaying
    something moves it to the front rather than duplicating it. Ids are chosen to survive
    a rescan: album ids are derived from name and album artists, artists/genres are names,
    folders are paths, and **songs are keyed by path** — song ids are regenerated by the
    scanner. Pruned to the newest 50.

### Fixed
- **Drag-to-reorder no longer runs away, stalls, or loses the row.** A touchscreen
  reports two to four times per drawn frame but a `LazyColumn` is measured once per
  frame, so after a swap every remaining touch event that frame still saw the *old*
  layout — the dragged row's midpoint was still inside the neighbour it had just passed,
  so it swapped again, and again. One movement across one boundary fired three or four
  swaps. The dragged row was also tracked by index, so once that cascade ran the index
  named a position the row was not at yet: the lift and offset landed on some other row,
  and if that position had scrolled out of view the row was not drawn at all. Rows are
  now tracked by their list **key**, which a stale layout cannot misresolve, and at most
  one move is applied per layout pass.
- **Drag-to-reorder no longer breaks mid-gesture.** The drag handles keyed their
  `pointerInput` on the row index. Compose cancels and relaunches a `pointerInput`
  block whenever its key changes, and a row's index changes on every swap — so the
  in-flight gesture was destroyed after moving a single position, in every list.
  The gesture is now keyed on the reorder state.
- Reordering in **Settings → Android Auto** was operating on the wrong rows: the
  screen emits a heading before its rows, so every data index was off by one against
  the lazy-list layout. `ReorderableState` now converts between data and lazy indices
  and clamps drop targets to the reorderable region.
- Rows that a move displaces now animate into place instead of teleporting, and only
  the two rows whose dragged state actually changes recompose (it was every visible
  row, on every swap). The slide is deliberately slower than Compose's default, which
  snaps rows past each other faster than the eye follows.
- Auto-scroll at the list edges no longer spawns a coroutine per pointer event; one
  frame-driven loop runs for the duration of the drag.
- **Edge auto-scroll now runs at one speed in both directions.** Dragging towards the
  bottom used to run away while dragging towards the top felt slow and stuck, because
  the reorder check only ran on finger movement: hold still at an edge and the list
  scrolled under a pinned row without swapping anything, then a single twitch moved it
  one position. The check now runs every frame the list is scrolling. Alongside that:
  the rate is a fixed dp-per-second integrated over real frame time (it was pixels per
  *frame*, so a 120Hz screen scrolled at double the rate of a 60Hz one, and it varied
  with display density), the trigger band is identical at both ends, and the loop stops
  pushing once the list reaches an end instead of grinding against it.
- The drop-target test used an inclusive range, so a row landing exactly on a boundary
  matched two rows and always resolved upwards.
- **A row being dragged no longer vanishes at the top or bottom of the list.** Nothing
  stopped it being pushed past either end of the viewport; its slot went out with it,
  and a `LazyColumn` disposes anything whose slot is off screen, so the row simply
  stopped being drawn. Travel is now clamped to the visible area — the finger can still
  move past the edge, the row just stays where it can be seen — and the drawn offset
  falls back to its last measured value instead of collapsing to zero on the frames
  where the slot is unmeasurable.
- Reordered rows keep their identity when the list is rebuilt from the persisted
  order, so a drag no longer ends in a visible rebuild of the list.
- Releasing a drag that moved nothing no longer persists or rewrites the queue.
- The seek-forward duration had been sharing the seek-back preference key.

### Android Auto
- **Artwork in the queue** — the queue reached from the Queue button on the playback
  screen now shows cover art, custom song covers included. Sent as content URIs
  rather than bitmaps, because a full queue of bitmaps in one parcel exceeds the
  binder transaction limit; the connected browser client is granted prefix read on
  the artwork directories so it can resolve them.

#### Not implemented, and why
- **A queue page on Android Auto's home screen.** The "For you" card on the Auto home
  screen belongs to Android Auto, not to the app: its pages, their order and their
  contents are Google's, populated from installed media apps. There is no API for an
  app to add a page to it or to reorder it. The queue is reachable from the **Queue**
  button on MAZIKA's playback screen, which is the surface the app does control.
- **Lyrics in the car.** Briefly added as a player custom action plus a browse
  category, then removed at the user's request. Android Auto media apps cannot draw a
  custom screen, so neither form was a real lyrics view — which is what made them not
  worth keeping.

### Changed
- Fresh installs now use the Sunset preset, lossless artwork, six-second track fades
  without pause/resume fading, the requested Home and Now Playing layouts, and a
  Playlists-first Android Auto category order.
- Android Auto artwork now validates missing files, falls back consistently, grants
  connected clients before republishing existing queues, and refreshes browse/queue
  metadata when custom covers change.
- Update checks now target the repository configured at build time instead of the
  upstream project, compare release versions numerically, and open the exact GitHub
  release from an actionable in-app notice.
- Removed the open-beta dialog, contribution banners, and inherited community/store
  links; public release artifacts and store metadata now use MAZIKA branding.
- **A song's favourite toggle is now on the row itself**, immediately before the
  overflow menu — filled when favourited, outlined when not. The heart used to appear
  only once a song was *already* a favourite, which made it an un-favourite button and
  nothing else; favouriting still meant opening the menu.
- **A new song cover appears immediately**, without relaunching the app. Setting or
  clearing one now emits its own update signal, which the song rows, the Now Playing
  cover, the mini player, the folder view and playlist tiles key their artwork on.
  The signal is separate from the general library one, which ticks once per song during
  a scan and would have rebuilt every image request thousands of times mid-scan. The
  media session also drops its decoded bitmap for that song, so the notification, lock
  screen and Android Auto update at once instead of at the next track change.
- Custom **song** covers, with the same pick/crop/replace/remove flow as playlist
  covers. Stored keyed by song path, not song id — ids are regenerated by the
  scanner and the songs table lives in the cache database, so an id-keyed cover
  would vanish on the next rescan. Room `PersistentDatabase` migrated 2 → 3.

## 2024.12.115 — fork baseline (never published)

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
