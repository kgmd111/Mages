package org.mlm.mages.ui.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MediaCacheOverview
import org.mlm.mages.matrix.MediaRetentionPolicyInfo

data class MediaCacheUiState(
    val isLoading: Boolean = true,
    val overview: MediaCacheOverview? = null,
    val policy: MediaRetentionPolicyInfo? = null,
    val isClearing: Boolean = false,
    val isSavingPolicy: Boolean = false,
    val error: String? = null,
)

class MediaCacheViewModel(
    private val service: MatrixService,
) : BaseViewModel<MediaCacheUiState>(MediaCacheUiState()) {

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            updateState { copy(isLoading = true, error = null) }
            runCatching { service.port.mediaCacheOverview() }
                .onSuccess { updateState { copy(isLoading = false, overview = it) } }
                .onFailure { updateState { copy(isLoading = false, error = it.message) } }
            runCatching { service.port.mediaRetentionPolicy() }
                .onSuccess { updateState { copy(policy = it) } }
        }
    }

    fun setKeepMediaFor(days: Long?) = updatePolicy { it.copy(lastAccessExpiryDays = days?.toULong()) }

    fun setCacheLimit(bytes: ULong?) = updatePolicy { it.copy(maxCacheSizeBytes = bytes) }

    fun setMaxFileSize(bytes: ULong?) = updatePolicy { it.copy(maxFileSizeBytes = bytes) }

    fun clearCache() {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaCache()
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    fun clearCacheOlderThan(days: Long) {
        viewModelScope.launch {
            updateState { copy(isClearing = true, error = null) }
            service.port.clearMediaCacheOlderThan(days)
                .onSuccess {
                    updateState { copy(isClearing = false) }
                    load()
                }
                .onFailure { updateState { copy(isClearing = false, error = it.message) } }
        }
    }

    private fun updatePolicy(transform: (MediaRetentionPolicyInfo) -> MediaRetentionPolicyInfo) {
        val next = transform(currentState.policy ?: MediaRetentionPolicyInfo())
        viewModelScope.launch {
            updateState { copy(isSavingPolicy = true, error = null) }
            service.port.setMediaRetentionPolicy(next)
                .onSuccess {
                    updateState { copy(isSavingPolicy = false, policy = next) }
                    load()
                }
                .onFailure { updateState { copy(isSavingPolicy = false, error = it.message) } }
        }
    }
}
