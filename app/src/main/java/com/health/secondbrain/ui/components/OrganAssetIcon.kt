package com.health.secondbrain.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage

private val organAssetFile = mapOf(
    "heart" to "heart-organ.svg",
    "liver" to "stomach-organ.svg",
    "gut" to "intestine-organ.svg",
    "sleep" to "thymus-gland-organ.svg",
    "lungs" to "lungs-organ.svg",
    "kidney" to "kidney-organ.svg",
    "brain" to "brain-organ.svg",
)

@Composable
fun OrganAssetIcon(
    organId: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val fileName = organAssetFile[organId]
    if (fileName == null) return

    AsyncImage(
        model = "file:///android_asset/organs/$fileName",
        contentDescription = contentDescription,
        modifier = modifier.fillMaxSize(),
        contentScale = ContentScale.Fit,
        colorFilter = tint?.let { ColorFilter.tint(it) }
    )
}
