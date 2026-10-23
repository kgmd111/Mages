package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.ui.SearchUiState

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val service: MatrixService,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val scopedRoomId: String? = null,
    private val scopedRoomName: String? = null
) : BaseViewModel<SearchUiState>(
    SearchUiState(
        scopedRoomId = scopedRoomId,
        scopedRoomName = scopedRoomName
    )
) {

    sealed class Event {
        data class OpenResult(val roomId: String, val eventId: String, val roomName: String) : Event()
        data class ShowError(val message: String) : Event()
    }

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _queryFlow = MutableStateFlow("")
    private val historyJson = Json { ignoreUnknownKeys = true }
    private val historyAccountId: String by lazy {
        runCatching { service.port.whoami() }.getOrNull().orEmpty()
    }

    init {
        launch {
            val raw = runCatching { settingsRepository.flow.first().searchHistoryJson }.getOrNull()
            updateState { copy(history = decodeHistory(raw)) }
        }
    }

    val searchResults: Flow<PagingData<SearchHit>> = _queryFlow
        .debounce(300)
        .filter { it.length >= 2 }
        .flatMapLatest { query ->
            Pager(
                config = PagingConfig(
                    pageSize = 30,
                    enablePlaceholders = false,
                    prefetchDistance = 5
                )
            ) {
                SearchPagingSource(service, scopedRoomId, query)
            }.flow
        }
        .cachedIn(viewModelScope)

    fun setQuery(query: String) {
        updateState { copy(query = query, error = null) }
        _queryFlow.value = query
    }

    fun openResult(hit: SearchHit) {
        recordSearch(currentState.query)
        launch {
            val roomName = if (hit.roomId == scopedRoomId) {
                scopedRoomName ?: hit.roomId
            } else {
                runSafe { service.port.roomProfile(hit.roomId)?.name } ?: hit.roomId
            }
            _events.send(Event.OpenResult(hit.roomId, hit.eventId, roomName))
        }
    }

    fun recordSearch(query: String) {
        if (scopedRoomId != null) return
        val normalized = query.trim()
        if (normalized.length < 2) return
        val updated = (listOf(normalized) + currentState.history.filterNot {
            it.equals(normalized, ignoreCase = true)
        }).take(MAX_HISTORY)
        if (updated == currentState.history) return
        updateState { copy(history = updated) }
        persistHistory(updated)
    }

    fun removeHistory(query: String) {
        val updated = currentState.history.filterNot { it == query }
        updateState { copy(history = updated) }
        persistHistory(updated)
    }

    fun clearHistory() {
        updateState { copy(history = emptyList()) }
        persistHistory(emptyList())
    }

    private fun persistHistory(history: List<String>) {
        val accountId = historyAccountId
        if (accountId.isBlank()) return
        launch {
            settingsRepository.update { current ->
                val byAccount = decodeHistoryMap(current.searchHistoryJson)
                val updated = if (history.isEmpty()) {
                    byAccount - accountId
                } else {
                    byAccount + (accountId to history)
                }
                current.copy(searchHistoryJson = historyJson.encodeToString(updated))
            }
        }
    }

    private fun decodeHistory(raw: String?): List<String> {
        val accountId = historyAccountId
        if (accountId.isBlank()) return emptyList()
        return sanitizeHistory(decodeHistoryMap(raw)[accountId].orEmpty())
    }

    private fun decodeHistoryMap(raw: String?): Map<String, List<String>> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            historyJson.decodeFromString<Map<String, List<String>>>(raw)
        }.getOrDefault(emptyMap())
    }

    private fun sanitizeHistory(history: List<String>): List<String> {
        return history
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase() }
            .take(MAX_HISTORY)
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
