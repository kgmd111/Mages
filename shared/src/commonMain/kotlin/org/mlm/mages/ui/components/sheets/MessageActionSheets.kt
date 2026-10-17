package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.message_info
import mages.shared.generated.resources.retry
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.MessageEvent
import org.mlm.mages.matrix.EventType
import org.mlm.mages.matrix.ImagePackSummary
import org.mlm.mages.matrix.SendState
import org.mlm.mages.ui.displayPreview
import org.mlm.mages.ui.hasCaption
import org.mlm.mages.ui.isForwardable
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.theme.Limits
import org.mlm.mages.ui.util.formatTime

private val quickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🔥", "💀")

@Composable
fun MessageActionSheet(
    event: MessageEvent,
    isMine: Boolean,
    canDeleteOthers: Boolean = false,
    canPin: Boolean = false,
    isPinned: Boolean = false,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onEditCaption: (() -> Unit)? = null,
    onRemoveCaption: (() -> Unit)? = null,
    onEditPoll: (() -> Unit)? = null,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onPin: (() -> Unit)? = null,
    onUnpin: (() -> Unit)? = null,
    isBookmarked: Boolean = false,
    onBookmark: (() -> Unit)? = null,
    onReport: (() -> Unit)? = null,
    onShowMessageInfo: (() -> Unit)? = null,
    onReact: (String) -> Unit,
    onMarkReadHere: () -> Unit,
    onReplyInThread: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onForward: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    reactionImagePacks: List<ImagePackSummary> = emptyList(),
    isEncryptedRoom: Boolean = false,
    resolveReactionPreview: suspend (thumbnailMxcUri: String?, mxcUrl: String) -> String? = { _, _ -> null },
) {
    val clipboardManager = LocalClipboardManager.current
    val isRedacted = event.isRedacted
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showImagePicker by remember { mutableStateOf(false) }

    if (showImagePicker) {
        ReactionImagePickerSheet(
            packs = reactionImagePacks,
            isEncryptedRoom = isEncryptedRoom,
            resolvePreview = resolveReactionPreview,
            onImageSelected = { ref ->
                onReact(ref.mxcUri)
                onDismiss()
            },
            onDismiss = { showImagePicker = false }
        )
        return
    }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onEmojiSelected = { emoji -> onReact(emoji); onDismiss() },
            onDismiss = { showEmojiPicker = false }
        )
        return
    }

    ModalBottomSheet(
        modifier = Modifier.fillMaxHeight(),
        onDismissRequest = onDismiss
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl)
        ) {
            MessagePreview(event)
            if (!isRedacted) {
                Spacer(Modifier.height(Spacing.lg))
                QuickReactionsRow(
                    onReact = { emoji -> onReact(emoji); onDismiss() },
                    onOpenPicker = { showEmojiPicker = true },
                    onOpenImagePicker = { showImagePicker = true }
                )
            }
            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
            Spacer(Modifier.height(Spacing.sm))

            if (onShowMessageInfo != null) {
                ActionItem(Icons.Default.Info, stringResource(Res.string.message_info)) {
                    onShowMessageInfo()
                    onDismiss()
                }
            }
            ActionItem(Icons.Default.ContentCopy, stringResource(Res.string.copy)) {
                clipboardManager.setText(AnnotatedString(event.body))
                onDismiss()
            }
            if (onShare != null) {
                ActionItem(Icons.Default.Share,
                    if (currentPlatform == SettingPlatform.WEB) stringResource(Res.string.download) else stringResource(Res.string.share)) { onShare(); onDismiss() }
            }
            if (onForward != null && event.isForwardable()) {
                ActionItem(Icons.AutoMirrored.Filled.Forward, stringResource(Res.string.forward)) { onForward(); onDismiss() }
            }
            if (!isRedacted) {
                ActionItem(Icons.AutoMirrored.Filled.Reply, stringResource(Res.string.reply_action)) { onReply(); onDismiss() }
                if (onReplyInThread != null) {
                    ActionItem(Icons.Default.Forum, stringResource(Res.string.reply_in_thread)) { onReplyInThread(); onDismiss() }
                }
            }
            if (isMine && event.sendState == SendState.Failed && onRetry != null) {
                ActionItem(Icons.Default.Refresh, stringResource(Res.string.retry)) { onRetry(); onDismiss() }
            }
            if (onBookmark != null && !isRedacted && event.eventId.isNotBlank()) {
                ActionItem(
                    if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    stringResource(if (isBookmarked) Res.string.remove_bookmark else Res.string.bookmark)
                ) {
                    onBookmark()
                    onDismiss()
                }
            }
            ActionItem(Icons.Default.Done, stringResource(Res.string.mark_as_read_here)) { onMarkReadHere(); onDismiss() }
            if (isMine && !isRedacted && event.sendState != SendState.Failed && event.eventId.isNotBlank()) {
                if (event.pollData != null) {
                    if (event.pollData?.isEnded == false && onEditPoll != null) {
                        ActionItem(Icons.Default.Poll, stringResource(Res.string.edit_poll)) { onEditPoll(); onDismiss() }
                    }
                } else if (event.attachment != null) {
                    if (onEditCaption != null) {
                        ActionItem(
                            Icons.Default.Edit,
                            if (event.hasCaption()) stringResource(Res.string.edit_caption) else stringResource(Res.string.add_caption),
                        ) { onEditCaption(); onDismiss() }
                    }
                    if (event.hasCaption() && onRemoveCaption != null) {
                        ActionItem(
                            Icons.Default.Close,
                            stringResource(Res.string.remove_caption),
                            MaterialTheme.colorScheme.error,
                        ) { onRemoveCaption(); onDismiss() }
                    }
                } else if (event.sticker == null &&
                    event.eventType != EventType.Location &&
                    event.eventType != EventType.LiveLocation
                ) {
                    ActionItem(Icons.Default.Edit, stringResource(Res.string.edit)) { onEdit(); onDismiss() }
                }
            }
            if (isMine || (canDeleteOthers && event.eventId.isNotBlank())) {
                ActionItem(Icons.Default.Delete, stringResource(Res.string.delete), MaterialTheme.colorScheme.error) { onDelete(); onDismiss() }
            }
            if (canPin && event.eventId.isNotBlank() && (!isRedacted || isPinned)) {
                if (isPinned && onUnpin != null) {
                    ActionItem(Icons.Default.PushPin, stringResource(Res.string.unpin)) { onUnpin(); onDismiss() }
                } else if (!isPinned && onPin != null) {
                    ActionItem(Icons.Default.PushPin, stringResource(Res.string.pin)) { onPin(); onDismiss() }
                }
            }
            ActionItem(Icons.Default.Deselect, stringResource(Res.string.select)) { onSelect(); onDismiss() }
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
            if (onReport != null && event.eventId.isNotBlank()) {
                ActionItem(Icons.Default.Flag, stringResource(Res.string.report), MaterialTheme.colorScheme.error) { onReport(); onDismiss() }
            }
        }
    }
}

@Composable
private fun MessagePreview(event: MessageEvent) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    formatTime(event.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(event.displayPreview().take(Limits.previewCharsLong), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun QuickReactionsRow(
    onReact: (String) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenImagePicker: () -> Unit
) {
    Text(stringResource(Res.string.quick_reactions), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.lg), fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(Spacing.sm))
    LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        items(quickReactions) { emoji ->
            Surface(onClick = { onReact(emoji) }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 20.sp) }
            }
        }
        item {
            Surface(onClick = onOpenPicker, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.more_emoji), modifier = Modifier.size(20.dp))
                }
            }
        }
        item {
            Surface(onClick = onOpenImagePicker, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Image, contentDescription = stringResource(Res.string.react_with_an_image), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(text, color = color) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
