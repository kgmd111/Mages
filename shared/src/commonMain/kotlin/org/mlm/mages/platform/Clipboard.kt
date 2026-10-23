package org.mlm.mages.platform

import androidx.compose.runtime.Composable

@Composable
expect fun rememberClipboardCopy(): (text: String, sensitive: Boolean) -> Unit
