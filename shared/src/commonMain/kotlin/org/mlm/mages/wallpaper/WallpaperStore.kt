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

/** Account data event type holding per-room wallpaper overrides, keyed by room id. */
const val WALLPAPER_ROOMS_EVENT_TYPE = "org.mlm.mages.wallpaper.rooms"

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

    private val _roomWallpapers = MutableStateFlow<Map<String, WallpaperConfig>>(emptyMap())
    val roomWallpapers: StateFlow<Map<String, WallpaperConfig>> = _roomWallpapers.asStateFlow()

    init {
        scope.launch {
            accountStore.activeAccountId.collect {
                _wallpaper.value = null
                _roomWallpapers.value = emptyMap()
                load()
            }
        }
    }

    /** Re-reads the account's wallpaper, picking up changes made elsewhere. */
    fun refresh() {
        scope.launch { load() }
    }

    suspend fun setImage(mxcUrl: String, roomId: String? = null): Result<Unit> {
        if (!mxcUrl.startsWith("mxc://")) {
            return Result.failure(IllegalArgumentException("not an mxc uri: $mxcUrl"))
        }
        if (roomId == null) {
            val current = _wallpaper.value
            return write(current?.copy(url = mxcUrl) ?: WallpaperConfig(url = mxcUrl))
        }
        val override = _roomWallpapers.value[roomId]
        val base = override?.takeIf { it.url != null } ?: _wallpaper.value ?: WallpaperConfig()
        return writeRoom(roomId, base.copy(url = mxcUrl))
    }

    suspend fun setOpacity(opacity: Float, roomId: String? = null): Result<Unit> =
        update(roomId) { it.copy(opacity = opacity) }

    suspend fun setBlur(blur: Float, roomId: String? = null): Result<Unit> =
        update(roomId) { it.copy(blur = blur) }

    /** Removes the global wallpaper, or a room's override when [roomId] is given. */
    suspend fun remove(roomId: String? = null): Result<Unit> {
        if (roomId == null) {
            if (_wallpaper.value == null) return Result.success(Unit)
            return write(WallpaperConfig())
        }
        val previous = _roomWallpapers.value
        if (roomId !in previous) return Result.success(Unit)
        return commitRooms(previous, previous - roomId)
    }

    /** Keeps the wallpaper hidden in one room even when a global one is set. */
    suspend fun disableForRoom(roomId: String): Result<Unit> {
        val previous = _roomWallpapers.value
        val existing = previous[roomId]
        if (existing != null && existing.url == null) return Result.success(Unit)
        return commitRooms(previous, previous + (roomId to WallpaperConfig()))
    }

    private suspend fun update(
        roomId: String?,
        transform: (WallpaperConfig) -> WallpaperConfig,
    ): Result<Unit> {
        val current = if (roomId == null) {
            _wallpaper.value
        } else {
            _roomWallpapers.value[roomId] ?: _wallpaper.value
        }
        val resolved = current ?: return Result.success(Unit)
        val updated = transform(resolved)
        return if (roomId == null) write(updated) else writeRoom(roomId, updated)
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

    private suspend fun writeRoom(roomId: String, updated: WallpaperConfig): Result<Unit> {
        val previous = _roomWallpapers.value
        return commitRooms(previous, previous + (roomId to updated.clamped()))
    }

    private suspend fun commitRooms(
        previous: Map<String, WallpaperConfig>,
        next: Map<String, WallpaperConfig>,
    ): Result<Unit> {
        val client = port() ?: return Result.failure(IllegalStateException("no active account"))
        _roomWallpapers.value = next
        val result = client.setAccountData(
            WALLPAPER_ROOMS_EVENT_TYPE,
            json.encodeToString(next),
        )
        if (result.isFailure) _roomWallpapers.value = previous
        return result
    }

    private suspend fun load() {
        val client = port() ?: return
        val rawRooms = runCatching { client.accountData(WALLPAPER_ROOMS_EVENT_TYPE) }.getOrNull()
        _roomWallpapers.value = decodeRooms(rawRooms)
        val raw = runCatching { client.accountData(WALLPAPER_EVENT_TYPE) }.getOrNull()
        _wallpaper.value = raw?.let { decode(it) }
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

    private fun decodeRooms(raw: String?): Map<String, WallpaperConfig> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString<Map<String, WallpaperConfig>>(raw)
                .mapValues { it.value.clamped() }
        } catch (e: Exception) {
            Logger.w { "WallpaperStore: corrupt room wallpaper content (${raw.length} chars), ignoring: ${e.message}" }
            emptyMap()
        }
    }
}
