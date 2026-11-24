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
    fun eachLoadDisplaysOnePageWithoutWaitingForTheRequestedTarget() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<String>()
        val feed = Feed(scope) { cursor ->
            requested += cursor
            val page = requested.size
            FeedPage(
                cards = (0 until 5).map { Card("card-${(page - 1) * 5 + it}", "作品") },
                cursor = "cursor-$page",
                more = true,
            )
        }

        try {
            feed.loadMore(100)
            assertEquals(listOf(""), requested)
            assertEquals(5, feed.cards.size)
            assertFalse(feed.loading)

            feed.loadMore(100)
            assertEquals(listOf("", "cursor-1"), requested)
            assertEquals(10, feed.cards.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopsWhenPageHasNoNewCardsEvenIfCursorAdvances() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<String>()
        val feed = Feed(scope) { cursor ->
            requested += cursor
            when (cursor) {
                "" -> FeedPage(listOf(Card("a", "作品 A")), cursor = "c1", more = true)
                "c1" -> FeedPage(listOf(Card("a", "作品 A")), cursor = "c2", more = true)
                else -> FeedPage(listOf(Card("b", "作品 B")), cursor = "c3", more = true)
            }
        }

        try {
            feed.loadMore()
            assertEquals(listOf(""), requested)
            assertEquals(listOf("a"), feed.cards.map { it.id })
            assertTrue(feed.wantsMore)

            feed.loadMore()
            assertEquals(listOf("", "c1"), requested)
            assertEquals(listOf("a"), feed.cards.map { it.id })
            assertFalse(feed.wantsMore)

            feed.loadMore()
            assertEquals(listOf("", "c1"), requested)
            assertEquals(listOf("a"), feed.cards.map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun emptyCursorAdvancingPagesCanBeLoadedOneAtATime() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<String>()
        val feed = Feed(scope) { cursor ->
            requested += cursor
            val page = requested.size
            if (page <= 6) {
                FeedPage(emptyList(), cursor = "cursor-$page", more = true)
            } else {
                FeedPage(listOf(Card("found", "终于找到作品")), cursor = "cursor-$page", more = true)
            }
        }

        try {
            repeat(7) { feed.loadMore(100) }
            assertEquals(7, requested.size)
            assertEquals("found", feed.cards.single().id)
            assertTrue(feed.wantsMore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun callerCanAccumulateCardsAcrossSeveralImmediatePages() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requested = mutableListOf<String>()
        val feed = Feed(scope) { cursor ->
            requested += cursor
            val page = requested.size
            FeedPage(
                cards = listOf(Card("card-$page", "作品 $page")),
                cursor = "cursor-$page",
                more = true,
            )
        }

        try {
            repeat(3) { feed.loadMore(3) }
            assertEquals(listOf("", "cursor-1", "cursor-2"), requested)
            assertEquals(listOf("card-1", "card-2", "card-3"), feed.cards.map { it.id })
            assertTrue(feed.wantsMore)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopsWhenServerClaimsMoreButDoesNotAdvanceCursor() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var requests = 0
        val feed = Feed(scope) { _ ->
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

    @Test
    fun enrichesMissingCardMetadataWithoutChangingExistingValuesOrOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val feed = Feed.of(scope, listOf(
            Card("a", "原始标题"),
            Card("b", "已有封面", cover = "existing.jpg", sub = "12 集", episodes = 12),
        ))

        try {
            val changed = feed.updateCards(listOf(
                Card("a", "详情标题", cover = "a.jpg", sub = "30 集", episodes = 30),
                Card("b", "新标题", cover = "replacement.jpg", sub = "80 集", episodes = 80),
            ))
            assertEquals(1, changed)
            assertEquals(listOf("a", "b"), feed.cards.map { it.id })
            assertEquals("原始标题", feed.cards[0].title)
            assertEquals("a.jpg", feed.cards[0].cover)
            assertEquals("30 集", feed.cards[0].sub)
            assertEquals("existing.jpg", feed.cards[1].cover)
            assertEquals("12 集", feed.cards[1].sub)
        } finally {
            scope.cancel()
        }
    }
}
