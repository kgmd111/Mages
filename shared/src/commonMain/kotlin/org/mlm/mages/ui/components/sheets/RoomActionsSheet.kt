package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

@Composable
fun RoomActionsSheet(
    roomName: String,
    roomId: String,
    isFavourite: Boolean,
    isLowPriority: Boolean,
    onDismiss: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleFavourite: () -> Unit,
    onToggleLowPriority: () -> Unit,
    onSelect: () -> Unit,
    onWallpaper: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl)
        ) {
            RoomPreview(roomName, roomId)
            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(Modifier.padding(horizontal = Spacing.lg))
            Spacer(Modifier.height(Spacing.sm))

            ActionItem(Icons.Default.Bookmark, stringResource(Res.string.mark_read)) {
                onMarkRead()
                onDismiss()
            }

            // ActionItem(
            //     Icons.Default.Notifications,
            //     stringResource(Res.string.mute)
            // ) {
            //     onDismiss()
            // }

            ActionItem(
                if (isFavourite) Icons.Filled.Star else Icons.Default.Star,
                if (isFavourite) stringResource(Res.string.remove_from_favorites) else stringResource(Res.string.add_to_favorites)
            ) {
                onToggleFavourite()
                onDismiss()
            }

            ActionItem(
                if (isLowPriority) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                if (isLowPriority) stringResource(Res.string.remove_from_low_priority) else stringResource(Res.string.low_priority)
            ) {
                onToggleLowPriority()
                onDismiss()
            }

            ActionItem(Icons.Default.Wallpaper, stringResource(Res.string.setting_chat_wallpaper)) {
                onWallpaper()
                onDismiss()
            }

            ActionItem(Icons.Default.Checklist, stringResource(Res.string.select)) {
                onDismiss()
                onSelect()
            }
        }
    }
}

@Composable
private fun RoomPreview(roomName: String, roomId: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Text(
                text = roomName,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = roomId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(text) },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
