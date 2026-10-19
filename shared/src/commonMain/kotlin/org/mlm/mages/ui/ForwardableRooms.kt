package org.mlm.mages.ui

import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.RoomListMembership

/**
 * Rooms offered as share and forward destinations. The room list cache comes
 * first so the pickers open without waiting for a sync, and it is the only
 * source carrying the peer avatar of a DM.
 */
suspend fun loadForwardableRooms(
    service: MatrixService,
    excludeRoomId: String? = null,
): List<ForwardableRoom> {
    val port = service.portOrNull ?: return emptyList()

    val cached = runCatching { port.loadRoomListCache() }
        .getOrNull()
        .orEmpty()
        .filter { it.membership == RoomListMembership.Joined && it.roomId != excludeRoomId }

    if (cached.isNotEmpty()) {
        return cached
            .map { ForwardableRoom(it.roomId, it.name, it.avatarUrl, it.isDm, it.lastTs.toLong()) }
            .sortedByDescending { it.lastActivity }
    }

    return runCatching { port.listRooms() }
        .getOrNull()
        .orEmpty()
        .filter { it.id != excludeRoomId }
        .map { ForwardableRoom(it.id, it.name, it.avatarUrl, it.isDm, 0L) }
        .sortedBy { it.name.lowercase() }
}
