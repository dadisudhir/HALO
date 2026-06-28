package com.health.secondbrain.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
    val context = LocalContext.current
    val fileName = organAssetFile[organId]
    if (fileName == null) {
        FallbackIcon(organId, modifier)
        return
    }

    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }

    val request = remember(fileName, context) {
        ImageRequest.Builder(context)
            .data("file:///android_asset/organs/$fileName")
            .decoderFactory(SvgDecoder.Factory())
            .crossfade(false)
            .build()
    }

    val painter = rememberAsyncImagePainter(
        model = request,
        imageLoader = imageLoader
    )

    if (painter.state is AsyncImagePainter.State.Error) {
        FallbackIcon(organId, modifier)
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
private fun FallbackIcon(organId: String, modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = organId.take(1).uppercase(),
            color = Color.White.copy(alpha = 0.92f)
        )
    }
}
