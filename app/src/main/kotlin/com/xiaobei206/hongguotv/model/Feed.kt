package com.xiaobei206.hongguotv.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Feed is a list of series loaded a page at a time. It belongs to the main
 * thread: its scope runs there, and the views read its state there.
 */
class Feed(
    private val scope: CoroutineScope,
    private val fetch: (suspend (cursor: String) -> FeedPage)?,
) {
    var cards by mutableStateOf(emptyList<Card>())
        private set
    private var seen = HashSet<String>()
    private var cursor = ""
    var more by mutableStateOf(true)
        private set
    var started by mutableStateOf(false)
        internal set
    var loading by mutableStateOf(false)
        private set

    /** The cards came from the disk cache: the first page replaces them. */
    var stale by mutableStateOf(false)
        internal set
    var error by mutableStateOf<Throwable?>(null)
        private set
    private var errorAt = 0L // System.nanoTime of the last failure

    /** Retries made by themselves since the last success. */
    private var tries = 0

    /** Whether a retry is on its way by itself. */
    var retrying by mutableStateOf(false)
        private set

    internal var onPage: ((FeedPage) -> Unit)? = null

    /** Sorts the cards after each page, when set. */
    internal var order: ((List<Card>) -> List<Card>)? = null

    internal fun add(p: FeedPage): Int {
        var incoming = p.cards
        if (stale) {
            incoming = incoming + cards
            cards = emptyList()
            seen = HashSet()
            stale = false
        }
        var list = ArrayList(cards)
        var added = 0
        for (k in incoming) {
            if (k.id.isNotEmpty() && seen.add(k.id)) {
                list += k
                added++
            }
        }
        cursor = p.cursor
        more = p.more
        order?.let { list = ArrayList(it(list)) }
        if (added == 0 && p.cards.isNotEmpty()) more = false
        cards = list
        return added
    }

    /**
     * Applies metadata fetched after a page was already displayed. Existing card
     * order, title, chart rank and non-empty fields remain authoritative.
     */
    internal fun updateCards(updates: List<Card>): Int {
        if (updates.isEmpty() || cards.isEmpty()) return 0
        val byId = updates.associateBy { it.id }
        var changed = 0
        val updated = cards.map { current ->
            val richer = byId[current.id] ?: return@map current
            val merged = current.copy(
                cover = current.cover.ifBlank { richer.cover },
                sub = current.sub.ifBlank { richer.sub },
                rank = if (current.rank > 0) current.rank else richer.rank,
                episodes = if (current.episodes > 0) current.episodes else richer.episodes,
            )
            if (merged != current) changed++
            merged
        }
        if (changed > 0) cards = updated
        return changed
    }

    /** Whether the grid should ask for the next page. */
    val wantsMore: Boolean get() = fetch != null && (stale || more || !started)

    /** Fetches the next page, unless one is on its way, the feed has no more, or the last try failed a moment ago. */
    fun loadMore() = loadPage()

    private fun loadPage() {
        val fetch = fetch ?: return
        if (loading || (started && !stale && !more)) return
        if (error != null && System.nanoTime() - errorAt < 5_000_000_000L) return
        loading = true
        started = true
        val requested = if (stale) "" else cursor
        scope.launch {
            try {
                val page = fetch(requested)
                val added = add(page)
                error = null
                tries = 0
                onPage?.invoke(page)
                debugLog {
                    "feed page requestCursorLength=${requested.length} responseCursorLength=${page.cursor.length} " +
                        "cursorAdvanced=${page.cursor.isNotBlank() && page.cursor != requested} " +
                        "rawCards=${page.cards.size} added=$added total=${cards.size} hasMore=${more}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
                errorAt = System.nanoTime()
                debugLog { "feed page failed requestCursorLength=${requested.length}: $e" }
                scheduleRetry()
            } finally {
                loading = false
            }
        }
    }

    /**
     * Tries again by itself after 2, 4, 8 and 16 seconds, while the feed
     * has nothing to show; then it waits for the viewer.
     */
    private fun scheduleRetry() {
        if (cards.isNotEmpty() || tries >= 4) {
            retrying = false
            return
        }
        val wait = 2000L shl tries
        tries++
        retrying = true
        scope.launch {
            delay(wait)
            retrying = false
            error = null
            loadMore()
        }
    }

    /** Fetches again after an error, now. */
    fun retry() {
        tries = 0
        error = null
        loadMore()
    }

    /**
     * Fetches the first page again, as the viewer pulls the grid down.
     * Merging keeps the cards there are after the fresh ones; else the
     * fresh page replaces them.
     */
    suspend fun refresh(merging: Boolean) {
        val fetch = fetch ?: return
        if (loading) return
        loading = true
        try {
            val p = fetch("")
            val had = cards.isNotEmpty()
            val oldCursor = cursor
            val oldMore = more
            if (merging) {
                stale = true
            } else {
                cards = emptyList()
                seen = HashSet()
            }
            error = null
            tries = 0
            add(p)
            if (merging && had) {
                // The grid goes on from where it had got to, not from the
                // fresh page's second.
                cursor = oldCursor
                more = oldMore
            }
            onPage?.invoke(p)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e
            errorAt = System.nanoTime()
        } finally {
            loading = false
        }
    }

    companion object {
        /** A feed of these cards alone, with nothing to fetch. */
        fun of(scope: CoroutineScope, cards: List<Card>): Feed = Feed(scope, null).apply {
            add(FeedPage(cards))
            started = true
            more = false
        }
    }
}
