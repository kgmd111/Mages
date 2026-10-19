package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.viewmodel.RoomsViewModel

private const val MIN_ROOM_LIST_PERCENT = 15
private const val MAX_ROOM_LIST_PERCENT = 50

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
    onOpenWallpaper: (String) -> Unit,
    roomListPercent: Int = 40,
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
                    onOpenWallpaper = onOpenWallpaper,
                )
            }
        } else {
            val inboxWeight = roomListPercent.coerceIn(MIN_ROOM_LIST_PERCENT, MAX_ROOM_LIST_PERCENT).toFloat()
            val chatWeight = 100f - inboxWeight

            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(inboxWeight)
                        .fillMaxHeight()
                ) {
                    RoomsScreen(
                        viewModel = roomsViewModel,
                        onOpenSecurity = onOpenSecurity,
                        onOpenStartChat = onOpenStartChat,
                        onOpenSpaces = onOpenSpaces,
                        onOpenSearch = onOpenSearch,
                        onOpenAccountSwitcher = onOpenAccountSwitcher,
                        onOpenWallpaper = onOpenWallpaper,
                    )
                }
                VerticalDivider()
                Box(
                    modifier = Modifier
                        .weight(chatWeight)
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
