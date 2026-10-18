package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.util.contentMaxWidth
import org.mlm.mages.ui.viewmodel.RoomsViewModel

private const val INBOX_WEIGHT = 38
private const val CHAT_WEIGHT = 62

data class ChatSelection(val roomId: String, val name: String)

@Composable
fun DesktopChatHost(
    roomsViewModel: RoomsViewModel,
    selection: ChatSelection?,
    onSelectionChange: (ChatSelection?) -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenStartChat: () -> Unit,
    onOpenSpaces: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    maxWidthInbox: Int = 0,
    maxWidthConversation: Int = 0,
    detailContent: @Composable (ChatSelection) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth < 840.dp) {
            if (selection != null) {
                ChatDetailPane(selection = selection, detailContent = detailContent)
            } else {
                RoomsScreen(
                    viewModel = roomsViewModel,
                    onOpenSecurity = onOpenSecurity,
                    onOpenStartChat = onOpenStartChat,
                    onOpenSpaces = onOpenSpaces,
                    onOpenSearch = onOpenSearch,
                    onOpenAccountSwitcher = onOpenAccountSwitcher,
                )
            }
        } else {
            val density = LocalDensity.current
            val totalPx = with(density) { maxWidth.toPx() }
            val inboxCapPx = contentMaxWidth(maxWidthInbox)?.let { with(density) { it.toPx() } }
            val chatCapPx = contentMaxWidth(maxWidthConversation)?.let { with(density) { it.toPx() } }

            // Weighted split where a pane narrower than its share frees the rest for the other pane.
            var inboxCapped = false
            var chatCapped = false
            repeat(3) {
                val freePx = totalPx -
                        (if (inboxCapped) inboxCapPx ?: 0f else 0f) -
                        (if (chatCapped) chatCapPx ?: 0f else 0f)
                val weightSum = (if (inboxCapped) 0 else INBOX_WEIGHT) + (if (chatCapped) 0 else CHAT_WEIGHT)
                if (weightSum == 0) return@repeat
                if (!inboxCapped && inboxCapPx != null && inboxCapPx < freePx * INBOX_WEIGHT / weightSum) {
                    inboxCapped = true
                }
                if (!chatCapped && chatCapPx != null && chatCapPx < freePx * CHAT_WEIGHT / weightSum) {
                    chatCapped = true
                }
            }
            val freePx = totalPx -
                    (if (inboxCapped) inboxCapPx ?: 0f else 0f) -
                    (if (chatCapped) chatCapPx ?: 0f else 0f)
            val weightSum = (if (inboxCapped) 0 else INBOX_WEIGHT) + (if (chatCapped) 0 else CHAT_WEIGHT)
            val inboxPx = if (inboxCapped) inboxCapPx ?: 0f else freePx * INBOX_WEIGHT / weightSum
            val chatPx = if (chatCapped) chatCapPx ?: 0f else freePx * CHAT_WEIGHT / weightSum
            // With both panes capped, spread the leftover evenly around them.
            val gapPx = if (inboxCapped && chatCapped) (totalPx - inboxPx - chatPx) / 4f else 0f

            val gap = with(density) { gapPx.toDp() }
            val inboxWidth = with(density) { inboxPx.toDp() }
            val chatWidth = with(density) { chatPx.toDp() }

            Row(modifier = Modifier.fillMaxSize()) {
                if (gapPx > 0f) Spacer(Modifier.width(gap))
                Box(
                    modifier = Modifier
                        .width(inboxWidth)
                        .fillMaxHeight()
                ) {
                    RoomsScreen(
                        viewModel = roomsViewModel,
                        onOpenSecurity = onOpenSecurity,
                        onOpenStartChat = onOpenStartChat,
                        onOpenSpaces = onOpenSpaces,
                        onOpenSearch = onOpenSearch,
                        onOpenAccountSwitcher = onOpenAccountSwitcher,
                    )
                }
                if (gapPx > 0f) Spacer(Modifier.width(gap))
                VerticalDivider()
                if (gapPx > 0f) Spacer(Modifier.width(gap))
                Box(
                    modifier = Modifier
                        .width(chatWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    if (selection != null) {
                        ChatDetailPane(selection = selection, detailContent = detailContent)
                    } else {
                        EmptyState(
                            icon = Icons.Default.ChatBubbleOutline,
                            title = stringResource(Res.string.select_conversation_title),
                            subtitle = stringResource(Res.string.select_conversation_subtitle)
                        )
                    }
                }
                if (gapPx > 0f) Spacer(Modifier.width(gap))
            }
        }
    }
}

@Composable
private fun ChatDetailPane(
    selection: ChatSelection,
    detailContent: @Composable (ChatSelection) -> Unit
) {
    key(selection.roomId) {
        val owner = remember(selection.roomId) {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(selection.roomId) {
            onDispose { owner.viewModelStore.clear() }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            detailContent(selection)
        }
    }
}
