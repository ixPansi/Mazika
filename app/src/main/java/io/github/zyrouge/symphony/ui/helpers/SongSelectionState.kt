package io.github.zyrouge.symphony.ui.helpers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import io.github.zyrouge.symphony.ui.components.ReorderableEntry

/**
 * MAZIKA: which songs the user has picked out of a list.
 *
 * Selection is keyed on [ReorderableEntry.uid] - `"<songId>#<occurrence>"` - rather than
 * on the song id. A playlist may legitimately hold the same song more than once, and the
 * lists already key their lazy items on `uid` for that reason; a set of song ids could
 * not express "the second copy is selected", and acting on it would hit both.
 *
 * Song ids and source indices are derived on demand from the entry list that is on
 * screen, so the state never holds a stale view of a list that has since been resorted,
 * filtered or reordered.
 */
@Stable
class SongSelectionState(initialUids: List<String> = emptyList()) {
    private val uids: SnapshotStateList<String> = initialUids.toMutableStateList()

    var isActive by mutableStateOf(initialUids.isNotEmpty())
        private set

    /**
     * The rows currently on screen, in display order, published by the list that owns
     * this selection. Keeping it here rather than passing it around means a selection bar
     * hoisted away from the list - as the home tabs' is, into `HomeView` - can still
     * resolve selected uids without knowing which tab produced them.
     */
    var entries: List<ReorderableEntry<String>> by mutableStateOf(emptyList())
        internal set

    val count get() = uids.size

    fun contains(uid: String) = uids.contains(uid)

    /** Enters selection mode with [uid] picked. */
    fun start(uid: String) {
        isActive = true
        if (!uids.contains(uid)) {
            uids.add(uid)
        }
    }

    /**
     * Enters selection mode with nothing picked, for screens where the long press is
     * already taken by drag-to-reorder and selection starts from a menu instead.
     */
    fun startEmpty() {
        isActive = true
    }

    /**
     * Adds or removes [uid]. Emptying the selection by hand leaves the mode active, so
     * the bar does not vanish mid-gesture; [clear] is what exits.
     */
    fun toggle(uid: String) {
        if (!uids.remove(uid)) {
            uids.add(uid)
        }
    }

    /** Selects everything currently on screen, or clears if it is already all selected. */
    fun toggleAll(allUids: List<String>) {
        when {
            uids.containsAll(allUids) && allUids.isNotEmpty() -> uids.clear()
            else -> {
                uids.clear()
                uids.addAll(allUids)
            }
        }
    }

    /** Leaves selection mode. */
    fun clear() {
        uids.clear()
        isActive = false
    }

    /**
     * Selected song ids, in the order they appear in [entries]. Duplicates are preserved:
     * selecting both copies of a song in a playlist really does mean two songs.
     */
    fun songIds(entries: List<ReorderableEntry<String>> = this.entries): List<String> =
        entries.filter { uids.contains(it.uid) }.map { it.value }

    /**
     * Indices into the caller's original list, which is what removing from a playlist
     * needs - the display order may be sorted differently from the stored order.
     */
    fun sourceIndices(entries: List<ReorderableEntry<String>> = this.entries): List<Int> =
        entries.filter { uids.contains(it.uid) }.map { it.sourceIndex }

    /** Drops selections whose rows are no longer present, e.g. after a removal. */
    fun retain(entries: List<ReorderableEntry<String>>) {
        val present = entries.mapTo(HashSet()) { it.uid }
        uids.retainAll(present)
    }

    companion object {
        val Saver = listSaver<SongSelectionState, String>(
            save = { it.uids.toList() },
            restore = { SongSelectionState(it) },
        )
    }
}

@Composable
fun rememberSongSelectionState(): SongSelectionState =
    rememberSaveable(saver = SongSelectionState.Saver) { SongSelectionState() }

/** Non-persisted variant for dialogs and other short-lived surfaces. */
@Composable
fun rememberTransientSongSelectionState(): SongSelectionState =
    remember { SongSelectionState() }
