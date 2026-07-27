package io.github.zyrouge.symphony.services.groove

/**
 * MAZIKA: applies a user-defined order to whatever currently exists.
 *
 * Shared by every repository with a `SortBy.CUSTOM`, all of which used to return their input
 * untouched - "Custom" was a label for "unsorted".
 *
 * The stored order is deliberately **partial**, and that is the whole subtlety here. The
 * library is rescanned, playlists are created, albums appear and disappear between one read
 * and the next, so a stored order can neither be treated as the complete list nor rewritten
 * every time something changes:
 *
 * - ids missing from [storedOrder] still have to appear, and belong at the **end** - that is
 *   where something newly added should show up, not somewhere in the middle of an order the
 *   user arranged by hand. [sortedBy] is stable, so they keep their relative order rather
 *   than shuffling amongst themselves.
 * - ids in [storedOrder] that no longer exist are simply never matched.
 *
 * With nothing stored the result is [pinnedFirst] followed by everything else in its incoming
 * order, which preserves each repository's previous behaviour. Once an order exists it wins
 * outright: a pinned id is then just an id in the list, so the user can drag it anywhere -
 * otherwise the pin would look like a bug the first time they tried to move it.
 */
internal fun applyStoredOrder(
    ids: List<String>,
    storedOrder: List<String>,
    pinnedFirst: List<String> = emptyList(),
): List<String> {
    if (storedOrder.isEmpty()) {
        if (pinnedFirst.isEmpty()) return ids
        val pinned = pinnedFirst.filter(ids::contains)
        return pinned + ids.filterNot(pinned::contains)
    }
    val ranks = HashMap<String, Int>(storedOrder.size)
    storedOrder.forEachIndexed { index, id -> ranks.putIfAbsent(id, index) }
    return ids.sortedBy { ranks[it] ?: Int.MAX_VALUE }
}
