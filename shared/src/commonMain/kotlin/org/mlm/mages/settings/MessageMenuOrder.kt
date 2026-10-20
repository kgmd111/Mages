package org.mlm.mages.settings

import kotlinx.serialization.Serializable

@Serializable
enum class MessageMenuAction {
    Reply,
    ReplyInThread,
    Edit,
    Copy,
    Forward,
    Share,
    Pin,
    Bookmark,
    MarkRead,
    Select,
    Info,
}

fun parseMessageMenuOrder(raw: String): List<MessageMenuAction> {
    val configured = raw.split(',')
        .mapNotNull { name -> MessageMenuAction.entries.firstOrNull { it.name == name.trim() } }
        .distinct()
    return configured + MessageMenuAction.entries.filterNot(configured::contains)
}

fun encodeMessageMenuOrder(order: List<MessageMenuAction>): String =
    order.joinToString(",") { it.name }
