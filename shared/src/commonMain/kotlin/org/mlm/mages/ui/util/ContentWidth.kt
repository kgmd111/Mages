package org.mlm.mages.ui.util

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

fun contentMaxWidth(maxWidthPx: Int): Dp? = if (maxWidthPx > 0) maxWidthPx.dp else null

fun Modifier.contentWidth(maxWidthPx: Int, enabled: Boolean = true): Modifier {
    if (!enabled) return this
    val max = contentMaxWidth(maxWidthPx) ?: return this
    return this
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = max)
}
