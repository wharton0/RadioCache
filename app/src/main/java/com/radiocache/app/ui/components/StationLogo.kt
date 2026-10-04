package com.radiocache.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.radiocache.app.R
import com.radiocache.app.ui.theme.StationColors

/**
 * Station artwork: bundled official logo when available, otherwise the
 * station's remote logo URL, otherwise the initial letter. Never all
 * three — a failed load falls back down the chain.
 *
 * Bundled drawables are why the grid renders correctly on first launch with
 * no network (Coil's disk cache can't help on a cold install).
 */
@Composable
fun StationLogo(
    stationId: String,
    stationName: String,
    logoUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    circular: Boolean = true,
) {
    val brand = StationColors.forStation(stationId)
    var remoteFailed by remember(logoUrl) { mutableStateOf(false) }
    val bundled = remember(stationId) { bundledLogoFor(stationId) }
    val shape = if (circular) CircleShape else RoundedCornerShape(24.dp)
    Box(
        modifier.then(
            Modifier.size(size).clip(shape).background(brand)
        ),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bundled != 0 -> Image(
                painter = painterResource(bundled),
                contentDescription = stationName,
                modifier = Modifier.matchParentSize().padding(2.dp),
                contentScale = ContentScale.Fit,
            )
            !logoUrl.isNullOrBlank() && !remoteFailed -> AsyncImage(
                model = logoUrl,
                contentDescription = stationName,
                modifier = Modifier.matchParentSize().clip(shape),
                contentScale = ContentScale.Crop,
                onError = { remoteFailed = true },
            )
            else -> Text(
                stationName.firstOrNull()?.uppercase() ?: "?",
                style = if (size >= 52.dp) MaterialTheme.typography.headlineSmall
                else MaterialTheme.typography.titleLarge,
                color = StationColors.onColor(brand),
            )
        }
    }
}

/** Station id → bundled drawable res id, or 0 when we ship none. */
private fun bundledLogoFor(stationId: String): Int = when (stationId) {
    "commons" -> R.drawable.station_logo_commons
    "librivox" -> R.drawable.station_logo_librivox
    else -> 0
}
