package org.mlm.mages.bookmarks

import co.touchlab.kermit.Logger
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mlm.mages.accounts.AccountStore
import org.mlm.mages.settings.AppSettings
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@Serializable
data class Bookmark(
    val accountId: String,
    val roomId: String,
    val roomName: String,
    val eventId: String,
    val sender: String?,
    val preview: String,
    val timestampMs: Long,
    val addedAtMs: Long,
)

/**
 * Saved messages, held in settings rather than room data, so a bookmark
 * survives leaving the room and has no server-side counterpart to wait on.
 * The list is scoped to the active account.
 */
class BookmarksStore(
    private val accountStore: AccountStore,
    private val settingsRepository: SettingsRepository<AppSettings>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    init {
        scope.launch {
            combine(settingsRepository.flow, accountStore.activeAccountId) { settings, accountId ->
                decode(settings.bookmarksJson)
                    .filter { it.accountId == accountId }
                    .sortedByDescending { it.addedAtMs }
            }.collect { _bookmarks.value = it }
        }
    }

    fun isBookmarked(roomId: String, eventId: String): Boolean =
        eventId.isNotBlank() && _bookmarks.value.any { it.roomId == roomId && it.eventId == eventId }

    @OptIn(ExperimentalTime::class)
    fun toggle(
        roomId: String,
        roomName: String,
        eventId: String,
        sender: String?,
        preview: String,
        timestampMs: Long,
    ) {
        if (eventId.isBlank()) return
        val accountId = accountStore.activeAccountId.value ?: return
        scope.launch {
            settingsRepository.update { settings ->
                val current = decode(settings.bookmarksJson)
                val stored = current.firstOrNull { it.matches(accountId, roomId, eventId) }
                val updated = if (stored != null) {
                    current - stored
                } else {
                    current + Bookmark(
                        accountId = accountId,
                        roomId = roomId,
                        roomName = roomName,
                        eventId = eventId,
                        sender = sender,
                        preview = preview,
                        timestampMs = timestampMs,
                        addedAtMs = Clock.System.now().toEpochMilliseconds(),
                    )
                }
                settings.copy(bookmarksJson = json.encodeToString(updated))
            }
        }
    }

    fun remove(bookmark: Bookmark) {
        scope.launch {
            settingsRepository.update { settings ->
                val current = decode(settings.bookmarksJson)
                val updated = current.filterNot {
                    it.matches(bookmark.accountId, bookmark.roomId, bookmark.eventId)
                }
                if (updated.size == current.size) settings
                else settings.copy(bookmarksJson = json.encodeToString(updated))
            }
        }
    }

    private fun Bookmark.matches(accountId: String, roomId: String, eventId: String): Boolean =
        this.accountId == accountId && this.roomId == roomId && this.eventId == eventId

    private fun decode(raw: String?): List<Bookmark> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString<List<Bookmark>>(raw)
        } catch (e: Exception) {
            Logger.w { "BookmarksStore: corrupt bookmarksJson (${raw.length} chars), resetting: ${e.message}" }
            emptyList()
        }
    }
}
