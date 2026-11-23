package com.xiaobei206.hongguotv.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedPaginationTest {
    @Test
    fun skipsDuplicateAndEmptyPagesWhenCursorAdvances() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<String>()
        val feed = Feed(scope) { cursor ->
            requested += cursor
            when (cursor) {
                "" -> FeedPage(listOf(Card("a", "作品 A")), cursor = "c1", more = true)
                "c1" -> FeedPage(listOf(Card("a", "作品 A")), cursor = "c2", more = true)
                "c2" -> FeedPage(emptyList(), cursor = "c3", more = true)
                "c3" -> FeedPage(listOf(Card("b", "作品 B")), cursor = "c4", more = true)
                else -> FeedPage(cursor = cursor, more = false)
            }
        }

        try {
            feed.loadMore()
            assertEquals(listOf(""), requested)
            feed.loadMore()
            assertEquals(listOf("", "c1", "c2", "c3"), requested)
            assertEquals(listOf("a", "b"), feed.cards.map { it.id })
            assertTrue(feed.wantsMore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopsWhenServerClaimsMoreButDoesNotAdvanceCursor() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var requests = 0
        val feed = Feed(scope) { cursor ->
            requests++
            FeedPage(listOf(Card("a", "作品 A")), cursor = "", more = true)
        }

        try {
            feed.loadMore()
            assertEquals(1, requests)
            assertEquals(listOf("a"), feed.cards.map { it.id })
            assertFalse(feed.wantsMore)
        } finally {
            scope.cancel()
        }
    }
}
