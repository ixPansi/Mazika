package io.github.zyrouge.symphony.ui.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * MAZIKA lyrics-gesture tests — the swipe-direction decision used by the Now
 * Playing cover to open lyrics on a deliberate downward swipe.
 */
class SwipeDirectionTest {
    private val threshold = 100f

    @Test
    fun deliberateDownwardSwipe_resolvesDown() {
        assertEquals(SwipeDirection.DOWN, resolveSwipeDirection(dx = 6f, dy = 160f, minimumDragAmount = threshold))
    }

    @Test
    fun shortDrag_doesNotResolve() {
        assertNull(resolveSwipeDirection(dx = 12f, dy = 40f, minimumDragAmount = threshold))
    }

    @Test
    fun upwardSwipe_doesNotResolveDown() {
        assertEquals(SwipeDirection.UP, resolveSwipeDirection(dx = 0f, dy = -160f, minimumDragAmount = threshold))
    }

    @Test
    fun mostlyHorizontalSwipe_resolvesHorizontalNotDown() {
        assertEquals(SwipeDirection.RIGHT, resolveSwipeDirection(dx = 200f, dy = 90f, minimumDragAmount = threshold))
        assertEquals(SwipeDirection.LEFT, resolveSwipeDirection(dx = -220f, dy = 50f, minimumDragAmount = threshold))
    }

    @Test
    fun verticalDominatesWhenLargerThanHorizontal() {
        // Both axes exceed the threshold, but vertical is larger -> DOWN wins.
        assertEquals(SwipeDirection.DOWN, resolveSwipeDirection(dx = 120f, dy = 220f, minimumDragAmount = threshold))
    }
}
