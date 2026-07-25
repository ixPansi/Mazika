package io.github.zyrouge.symphony.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.utils.Logger
import io.github.zyrouge.symphony.utils.PlaylistCovers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * MAZIKA: lets the user frame a playlist cover before it is saved.
 *
 * The image is shown inside a square viewport; dragging pans it and pinching
 * zooms, exactly like a normal photo cropper, and "Center" resets the framing.
 * The visible square is converted into a [PlaylistCovers.CropRegion] in source-image
 * fractions, so the saved file matches what was on screen no matter what sample
 * size the decoder used.
 *
 * Implemented with plain Compose gestures — no cropping dependency is pulled in.
 */
@Composable
fun PlaylistCoverCropDialog(
    context: ViewContext,
    uri: Uri,
    onDismissRequest: () -> Unit,
    onConfirm: (PlaylistCovers.CropRegion) -> Unit,
) {
    val localContext = LocalContext.current

    // Source aspect ratio, needed to map the viewport back onto image pixels.
    var imageAspect by remember { mutableStateOf<Float?>(null) }
    var failed by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableFloatStateOf(0f) }

    val recenter = {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    LaunchedEffect(uri) {
        val aspect = withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                localContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                when {
                    options.outWidth > 0 && options.outHeight > 0 ->
                        options.outWidth.toFloat() / options.outHeight
                    else -> null
                }
            } catch (err: Exception) {
                Logger.error("PlaylistCoverCrop", "unable to read image bounds", err)
                null
            }
        }
        imageAspect = aspect
        failed = aspect == null
    }

    // Uses the app's own ScaffoldDialog rather than a bare AlertDialog, so it
    // matches every other dialog in MAZIKA (title bar, divider, action row).
    ScaffoldDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(context.symphony.t.ChangePlaylistCover) },
        content = {
            Column(modifier = Modifier.padding(16.dp, 12.dp)) {
                if (failed) {
                    Text(context.symphony.t.UnableToSavePlaylistCover)
                } else {
                    Text(
                        context.symphony.t.CropCoverHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(imageAspect) {
                                viewportPx = size.width.toFloat()
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val aspect = imageAspect ?: return@detectTransformGestures
                                    scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                                    val bounds = maxOffsets(size.width.toFloat(), aspect, scale)
                                    offsetX = (offsetX + pan.x)
                                        .coerceIn(-bounds.first, bounds.first)
                                    offsetY = (offsetY + pan.y)
                                        .coerceIn(-bounds.second, bounds.second)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = context.symphony.t.ChangePlaylistCover,
                            // Crop fills the square, so the visible area is exactly
                            // what gets saved.
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = recenter) {
                            Icon(
                                Icons.Filled.CenterFocusStrong,
                                null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                context.symphony.t.Center,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        },
        actions = {
            TextButton(onClick = onDismissRequest) {
                Text(context.symphony.t.Cancel)
            }
            TextButton(
                enabled = !failed && imageAspect != null,
                onClick = {
                    val aspect = imageAspect ?: return@TextButton
                    onConfirm(
                        toCropRegion(
                            aspect = aspect,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            viewportPx = viewportPx.takeIf { it > 0f } ?: 1f,
                        )
                    )
                },
            ) {
                Text(context.symphony.t.SetAsCover)
            }
        },
    )
}

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f

/**
 * How far the image may be panned before its edges would enter the viewport.
 */
private fun maxOffsets(viewport: Float, aspect: Float, scale: Float): Pair<Float, Float> {
    // ContentScale.Crop already covers the square, so the drawn size before the
    // graphicsLayer scale is viewport x viewport, enlarged along the longer axis.
    val drawnWidth = viewport * max(1f, aspect) * scale
    val drawnHeight = viewport * max(1f, 1f / aspect) * scale
    return ((drawnWidth - viewport) / 2f).coerceAtLeast(0f) to
            ((drawnHeight - viewport) / 2f).coerceAtLeast(0f)
}

/**
 * Converts the on-screen framing into fractions of the source image.
 *
 * Visible for testing.
 */
internal fun toCropRegion(
    aspect: Float,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    viewportPx: Float,
): PlaylistCovers.CropRegion {
    // Fraction of the source covered by the viewport: with Crop the shorter side
    // exactly fills it, so at scale 1 the square spans 1/aspect of the width for a
    // landscape image (and the whole width for a portrait one).
    val widthFraction = (1f / max(1f, aspect) / scale).coerceIn(0.01f, 1f)
    val heightFraction = (1f / max(1f, 1f / aspect) / scale).coerceIn(0.01f, 1f)

    // Panning right (positive offset) reveals content further left.
    val drawnWidth = viewportPx * max(1f, aspect) * scale
    val drawnHeight = viewportPx * max(1f, 1f / aspect) * scale
    val freeX = ((drawnWidth - viewportPx) / 2f).coerceAtLeast(0f)
    val freeY = ((drawnHeight - viewportPx) / 2f).coerceAtLeast(0f)

    val centerX = 0.5f - if (freeX > 0f) (offsetX / drawnWidth) else 0f
    val centerY = 0.5f - if (freeY > 0f) (offsetY / drawnHeight) else 0f

    val left = (centerX - widthFraction / 2f).coerceIn(0f, 1f - widthFraction)
    val top = (centerY - heightFraction / 2f).coerceIn(0f, 1f - heightFraction)
    return PlaylistCovers.CropRegion(
        left = left,
        top = top,
        size = min(widthFraction, 1f),
    )
}
