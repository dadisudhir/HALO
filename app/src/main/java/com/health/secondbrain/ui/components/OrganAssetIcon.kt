package com.health.secondbrain.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest

@Composable
fun OrganAssetIcon(
    iconAsset: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val context = LocalContext.current
    if (iconAsset.isBlank()) {
        EmptyIcon(modifier)
        return
    }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    val request = remember(iconAsset, context) {
        val assetUri = if (iconAsset.startsWith("file://")) {
            iconAsset
        } else {
            "file:///android_asset/$iconAsset"
        }
        ImageRequest.Builder(context)
            .data(assetUri)
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }

    val painter = rememberAsyncImagePainter(
        model = request,
        imageLoader = imageLoader
    )

    if (painter.state is AsyncImagePainter.State.Error) {
        EmptyIcon(modifier)
    } else {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            colorFilter = tint?.let { ColorFilter.tint(it) }
        )
    }
}

@Composable
private fun EmptyIcon(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {}
}
