package org.mlm.mages.widget

import android.content.Context
import org.json.JSONArray
import java.io.File

data class WidgetRoom(
    val roomId: String,
    val name: String,
    val unread: Int,
    val markedUnread: Boolean,
    val lastTs: Long,
)

/**
 * Reads the room-list snapshot the Rust side persists per account
 * (`room_list_cache.json`) so the widget can render without booting the
 * Matrix stack. Picks the most recently written snapshot, which is the
 * active account's.
 */
object MagesWidgetData {

    private const val CACHE_FILE = "room_list_cache.json"
    private const val STORE_DIR = "mages_store"
    private const val MAX_ROOMS = 8

    fun loadUnreadRooms(context: Context, limit: Int = MAX_ROOMS): List<WidgetRoom> {
        val cache = newestCacheFile(context) ?: return emptyList()
        return runCatching {
            val array = JSONArray(cache.readText())
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optString("membership") != "Joined") continue
                    if (obj.optBoolean("is_invited")) continue
                    val unread = maxOf(
                        obj.optInt("notifications"),
                        obj.optInt("messages"),
                        obj.optInt("mentions"),
                    )
                    val markedUnread = obj.optBoolean("marked_unread")
                    if (unread <= 0 && !markedUnread) continue
                    val roomId = obj.optString("room_id")
                    if (roomId.isBlank()) continue
                    add(
                        WidgetRoom(
                            roomId = roomId,
                            name = obj.optString("name").ifBlank { roomId },
                            unread = unread,
                            markedUnread = markedUnread,
                            lastTs = obj.optLong("last_ts"),
                        )
                    )
                }
            }.sortedByDescending { it.lastTs }.take(limit)
        }.getOrDefault(emptyList())
    }

    private fun newestCacheFile(context: Context): File? =
        File(context.filesDir, STORE_DIR)
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter { it.isFile && it.name == CACHE_FILE }
            ?.maxByOrNull { it.lastModified() }
}
