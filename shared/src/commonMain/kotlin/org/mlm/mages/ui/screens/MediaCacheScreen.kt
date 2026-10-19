package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mages.shared.generated.resources.*
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.back
import mages.shared.generated.resources.media_cache
import mages.shared.generated.resources.media_cache_cache_limit
import mages.shared.generated.resources.media_cache_clear_confirm_body
import mages.shared.generated.resources.media_cache_clear_confirm_title
import mages.shared.generated.resources.media_cache_clear_media_cache
import mages.shared.generated.resources.media_cache_clear_older
import mages.shared.generated.resources.media_cache_clear_older_confirm
import mages.shared.generated.resources.media_cache_days
import mages.shared.generated.resources.media_cache_downloaded_media
import mages.shared.generated.resources.media_cache_keep_forever
import mages.shared.generated.resources.media_cache_keep_media
import mages.shared.generated.resources.media_cache_max_file
import mages.shared.generated.resources.media_cache_storage_used
import mages.shared.generated.resources.media_cache_thumbnails
import mages.shared.generated.resources.media_cache_unlimited
import mages.shared.generated.resources.messages
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.matrix.MediaRetentionPolicyInfo
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.util.formatBytes
import org.mlm.mages.ui.viewmodel.MediaCacheUiState
import org.mlm.mages.ui.viewmodel.MediaCacheViewModel

@Composable
fun MediaCacheRoute(onBack: () -> Unit) {
    val viewModel: MediaCacheViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    MediaCacheScreen(
        state = state,
        onBack = onBack,
        onClearCache = viewModel::clearCache,
        onClearOlderThan = viewModel::clearCacheOlderThan,
        onKeepMediaChange = viewModel::setKeepMediaFor,
        onCacheLimitChange = viewModel::setCacheLimit,
        onMaxFileSizeChange = viewModel::setMaxFileSize,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCacheScreen(
    state: MediaCacheUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onClearOlderThan: (Long) -> Unit,
    onKeepMediaChange: (Long?) -> Unit,
    onCacheLimitChange: (ULong?) -> Unit,
    onMaxFileSizeChange: (ULong?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showOlderMenu by remember { mutableStateOf(false) }
    var pendingOlderThan by remember { mutableStateOf<Long?>(null) }

    if (showConfirm) {
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_confirm_title),
            message = stringResource(Res.string.media_cache_clear_confirm_body),
            onConfirm = {
                showConfirm = false
                onClearCache()
            },
            onDismiss = { showConfirm = false },
        )
    }

    pendingOlderThan?.let { days ->
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_older_confirm, days.toInt()),
            message = stringResource(Res.string.media_cache_clear_confirm_body),
            onConfirm = {
                pendingOlderThan = null
                onClearOlderThan(days)
            },
            onDismiss = { pendingOlderThan = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.media_cache)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {

            ListItem(
                headlineContent = {
                    Text(stringResource(Res.string.media_cache_storage_used))
                },
                trailingContent = {
                    val totalBytes = state.overview?.takeIf { it.storeBytes != null }?.totalBytes
                    when {
                        state.isLoading -> CircularWavyProgressIndicator()
                        totalBytes == null -> Text(
                            text = "-",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Text(
                            text = formatBytes(totalBytes.toLong()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            )

            val overview = state.overview
            if (!state.isLoading && overview != null) {
                overview.storeBytes?.let { bytes ->
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.media_cache_downloaded_media))
                        },
                        trailingContent = {
                            Text(
                                text = formatBytes(bytes.toLong()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    )
                }

                overview.eventBytes?.let { bytes ->
                    ListItem(
                        headlineContent = {
                            Text(stringResource(Res.string.messages))
                        },
                        trailingContent = {
                            Text(
                                text = formatBytes(bytes.toLong()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    )
                }

                ListItem(
                    headlineContent = {
                        Text(stringResource(Res.string.media_cache_thumbnails))
                    },
                    trailingContent = {
                        Text(
                            text = formatBytes(overview.thumbnailBytes.toLong()),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                )
            }

            val policy = state.policy ?: MediaRetentionPolicyInfo()
            val policyEnabled = !state.isLoading && !state.isSavingPolicy && !state.isClearing

            val keepOptions: List<Pair<String, Long?>> = listOf(
                stringResource(Res.string.media_cache_keep_forever) to null,
                stringResource(Res.string.media_cache_days, 7) to 7L,
                stringResource(Res.string.media_cache_days, 30) to 30L,
                stringResource(Res.string.media_cache_days, 90) to 90L,
            )

            val cacheLimitOptions: List<Pair<String, ULong?>> = listOf(
                stringResource(Res.string.media_cache_unlimited) to null,
                formatBytes(200L * 1024 * 1024) to 200uL * 1024uL * 1024uL,
                formatBytes(500L * 1024 * 1024) to 500uL * 1024uL * 1024uL,
                formatBytes(1024L * 1024 * 1024) to 1024uL * 1024uL * 1024uL,
                formatBytes(2048L * 1024 * 1024) to 2048uL * 1024uL * 1024uL,
            )

            val maxFileOptions: List<Pair<String, ULong?>> = listOf(
                stringResource(Res.string.media_cache_unlimited) to null,
                formatBytes(10L * 1024 * 1024) to 10uL * 1024uL * 1024uL,
                formatBytes(25L * 1024 * 1024) to 25uL * 1024uL * 1024uL,
                formatBytes(50L * 1024 * 1024) to 50uL * 1024uL * 1024uL,
                formatBytes(100L * 1024 * 1024) to 100uL * 1024uL * 1024uL,
            )

            HorizontalDivider()

            OptionRow(
                label = stringResource(Res.string.media_cache_keep_media),
                value = policy.lastAccessExpiryDays?.toLong(),
                options = keepOptions,
                fallback = policy.lastAccessExpiryDays
                    ?.let { days -> stringResource(Res.string.media_cache_days, days.toInt()) }
                    ?: stringResource(Res.string.media_cache_keep_forever),
                enabled = policyEnabled,
                onSelect = onKeepMediaChange,
            )

            OptionRow(
                label = stringResource(Res.string.media_cache_cache_limit),
                value = policy.maxCacheSizeBytes,
                options = cacheLimitOptions,
                fallback = policy.maxCacheSizeBytes
                    ?.let { bytes -> formatBytes(bytes.toLong()) }
                    ?: stringResource(Res.string.media_cache_unlimited),
                enabled = policyEnabled,
                onSelect = onCacheLimitChange,
            )

            OptionRow(
                label = stringResource(Res.string.media_cache_max_file),
                value = policy.maxFileSizeBytes,
                options = maxFileOptions,
                fallback = policy.maxFileSizeBytes
                    ?.let { bytes -> formatBytes(bytes.toLong()) }
                    ?: stringResource(Res.string.media_cache_unlimited),
                enabled = policyEnabled,
                onSelect = onMaxFileSizeChange,
            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { showOlderMenu = true },
                        enabled = !state.isClearing && !state.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.media_cache_clear_older))
                    }

                    DropdownMenu(
                        expanded = showOlderMenu,
                        onDismissRequest = { showOlderMenu = false },
                    ) {
                        listOf(7L, 30L, 90L).forEach { days ->
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.media_cache_days, days.toInt())) },
                                onClick = {
                                    showOlderMenu = false
                                    pendingOlderThan = days
                                },
                            )
                        }
                    }
                }

                Button(
                    onClick = { showConfirm = true },
                    enabled = !state.isClearing && !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (state.isClearing) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                    Text(stringResource(Res.string.media_cache_clear_media_cache))
                }

                Spacer(Modifier.padding(6.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.Center) { Text(stringResource(Res.string.room_listings_will_be_added_later)) }

                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> OptionRow(
    label: String,
    value: T,
    options: List<Pair<String, T>>,
    fallback: String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(label) },
        trailingContent = {
            Box {
                TextButton(onClick = { expanded = true }, enabled = enabled) {
                    Text(options.firstOrNull { it.second == value }?.first ?: fallback)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }

                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (text, option) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            onClick = {
                                expanded = false
                                if (option != value) onSelect(option)
                            },
                        )
                    }
                }
            }
        },
    )
}
