package org.mlm.mages.bookmarks

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.matrix.MatrixPort
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Global account data event type holding the account's bookmarks. */
const val BOOKMARKS_EVENT_TYPE = "org.mlm.mages.bookmarks"

@Serializable
data class Bookmark(
    val roomId: String,
    val roomName: String,
    val eventId: String,
    val sender: String?,
    val preview: String,
    val timestampMs: Long,
    val addedAtMs: Long,
)

@Serializable
private data class BookmarksContent(val bookmarks: List<Bookmark> = emptyList())

/**
 * Saved messages, held in account data so they follow the account across
 * devices rather than the installation.
 *
 * A write updates the local copy first, so the list reacts at once, and rolls
 * back when the request fails. The store keeps no queue: a bookmark made
 * offline is reported as failed rather than kept for later. Account data caps
 * an event at 64 KiB, so an outsized list stops syncing instead of growing
 * without bound.
 */
class BookmarksStore(
    private val accountStore: AccountStore,
    private val port: () -> MatrixPort?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    init {
        scope.launch {
            accountStore.activeAccountId.collect {
                _bookmarks.value = emptyList()
                load()
            }
        }
    }

    fun isBookmarked(roomId: String, eventId: String): Boolean =
        eventId.isNotBlank() && _bookmarks.value.any { it.roomId == roomId && it.eventId == eventId }

    /** Re-reads the account's bookmarks, picking up changes made elsewhere. */
    fun refresh() {
        scope.launch { load() }
    }

    @OptIn(ExperimentalTime::class)
    suspend fun toggle(
        roomId: String,
        roomName: String,
        eventId: String,
        sender: String?,
        preview: String,
        timestampMs: Long,
    ): Result<Unit> {
        if (eventId.isBlank()) return Result.failure(IllegalArgumentException("blank event id"))
        val current = _bookmarks.value
        val stored = current.firstOrNull { it.roomId == roomId && it.eventId == eventId }
        val updated = if (stored != null) {
            current - stored
        } else {
            current + Bookmark(
                roomId = roomId,
                roomName = roomName,
                eventId = eventId,
                sender = sender,
                preview = preview,
                timestampMs = timestampMs,
                addedAtMs = Clock.System.now().toEpochMilliseconds(),
            )
        }
        return write(updated)
    }

    suspend fun remove(bookmark: Bookmark): Result<Unit> {
        val updated = _bookmarks.value.filterNot {
            it.roomId == bookmark.roomId && it.eventId == bookmark.eventId
        }
        if (updated.size == _bookmarks.value.size) return Result.success(Unit)
        return write(updated)
    }

    private suspend fun write(updated: List<Bookmark>): Result<Unit> {
        val client = port() ?: return Result.failure(IllegalStateException("no active account"))
        val previous = _bookmarks.value
        _bookmarks.value = updated.sortedByDescending { it.addedAtMs }
        val result = client.setAccountData(
            BOOKMARKS_EVENT_TYPE,
            json.encodeToString(BookmarksContent(updated)),
        )
        if (result.isFailure) _bookmarks.value = previous
        return result
    }

    private suspend fun load() {
        val client = port() ?: return
        val raw = runCatching { client.accountData(BOOKMARKS_EVENT_TYPE) }.getOrNull() ?: return
        _bookmarks.value = decode(raw).sortedByDescending { it.addedAtMs }
    }

    private fun decode(raw: String): List<Bookmark> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<BookmarksContent>(raw).bookmarks
        } catch (e: Exception) {
            Logger.w { "BookmarksStore: corrupt bookmarks content (${raw.length} chars), ignoring: ${e.message}" }
            emptyList()
        }
    }
}
