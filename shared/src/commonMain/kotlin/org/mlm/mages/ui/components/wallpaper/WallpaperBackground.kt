package org.mlm.mages.ui.components.wallpaper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.wallpaper.WallpaperConfig

/** Server-side thumbnail size, which doubles as the cached copy's size. */
private const val WALLPAPER_PX = 1280

/**
 * Draws the account's chat wallpaper behind a screen's content. Nothing is
 * drawn when there is no wallpaper configured, when the image cannot be
 * resolved, or while it is still loading, so the theme background stays
 * visible in every failure case.
 */
@Composable
fun WallpaperBackground(
    config: WallpaperConfig?,
    modifier: Modifier = Modifier,
) {
    val url = config?.url?.takeIf { it.isNotBlank() } ?: return
    val service: MatrixService = koinInject()
    var path by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(url) {
        path = runCatching { service.avatars.resolve(url, px = WALLPAPER_PX, crop = false) }.getOrNull()
    }

    val resolved = path ?: return
    AsyncImage(
        model = resolved,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxSize()
            .then(
                if (config.blur > 0f) Modifier.blur(config.blur.dp) else Modifier
            )
            .alpha(config.opacity.coerceIn(WallpaperConfig.MIN_OPACITY, WallpaperConfig.MAX_OPACITY))
    )
}
