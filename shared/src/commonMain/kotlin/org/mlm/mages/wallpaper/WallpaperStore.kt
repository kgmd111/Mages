package org.mlm.mages.wallpaper

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

/** Global account data event type holding the chat wallpaper. */
const val WALLPAPER_EVENT_TYPE = "org.mlm.mages.wallpaper"

/**
 * How the message list draws [url] behind its messages. A null [url] means no
 * wallpaper, which is what an account without the event carries.
 */
@Serializable
data class WallpaperConfig(
    val url: String? = null,
    val opacity: Float = DEFAULT_OPACITY,
    val blur: Float = 0f,
) {
    companion object {
        /** Readable over most photos without washing them out. */
        const val DEFAULT_OPACITY = 0.35f
        const val MIN_OPACITY = 0.05f
        const val MAX_OPACITY = 1f
        const val MIN_BLUR = 0f
        const val MAX_BLUR = 25f
    }
}

private fun WallpaperConfig.clamped() = copy(
    opacity = opacity.coerceIn(WallpaperConfig.MIN_OPACITY, WallpaperConfig.MAX_OPACITY),
    blur = blur.coerceIn(WallpaperConfig.MIN_BLUR, WallpaperConfig.MAX_BLUR),
)

/**
 * Chat wallpaper, held in account data so it follows the account across
 * devices. The image itself lives in the media repository; only its `mxc://`
 * URI and the display tweaks are stored here.
 *
 * A write updates the local copy first and rolls back when the request fails,
 * and [refresh] picks up changes made elsewhere.
 */
class WallpaperStore(
    private val accountStore: AccountStore,
    private val port: () -> MatrixPort?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _wallpaper = MutableStateFlow<WallpaperConfig?>(null)
    val wallpaper: StateFlow<WallpaperConfig?> = _wallpaper.asStateFlow()

    init {
        scope.launch {
            accountStore.activeAccountId.collect {
                _wallpaper.value = null
                load()
            }
        }
    }

    /** Re-reads the account's wallpaper, picking up changes made elsewhere. */
    fun refresh() {
        scope.launch { load() }
    }

    suspend fun setImage(mxcUrl: String): Result<Unit> {
        if (!mxcUrl.startsWith("mxc://")) {
            return Result.failure(IllegalArgumentException("not an mxc uri: $mxcUrl"))
        }
        val current = _wallpaper.value
        return write(current?.copy(url = mxcUrl) ?: WallpaperConfig(url = mxcUrl))
    }

    suspend fun setOpacity(opacity: Float): Result<Unit> = update { it.copy(opacity = opacity) }

    suspend fun setBlur(blur: Float): Result<Unit> = update { it.copy(blur = blur) }

    suspend fun remove(): Result<Unit> {
        if (_wallpaper.value == null) return Result.success(Unit)
        return write(WallpaperConfig())
    }

    private suspend fun update(transform: (WallpaperConfig) -> WallpaperConfig): Result<Unit> {
        val current = _wallpaper.value ?: return Result.success(Unit)
        return write(transform(current))
    }

    private suspend fun write(updated: WallpaperConfig): Result<Unit> {
        val client = port() ?: return Result.failure(IllegalStateException("no active account"))
        val previous = _wallpaper.value
        val next = updated.clamped().takeIf { it.url != null }
        _wallpaper.value = next
        val result = client.setAccountData(
            WALLPAPER_EVENT_TYPE,
            json.encodeToString(updated.clamped()),
        )
        if (result.isFailure) _wallpaper.value = previous
        return result
    }

    private suspend fun load() {
        val client = port() ?: return
        val raw = runCatching { client.accountData(WALLPAPER_EVENT_TYPE) }.getOrNull() ?: return
        _wallpaper.value = decode(raw)
    }

    private fun decode(raw: String): WallpaperConfig? {
        if (raw.isBlank()) return null
        return try {
            json.decodeFromString<WallpaperConfig>(raw)
                .clamped()
                .takeIf { !it.url.isNullOrBlank() }
        } catch (e: Exception) {
            Logger.w { "WallpaperStore: corrupt wallpaper content (${raw.length} chars), ignoring: ${e.message}" }
            null
        }
    }
}
