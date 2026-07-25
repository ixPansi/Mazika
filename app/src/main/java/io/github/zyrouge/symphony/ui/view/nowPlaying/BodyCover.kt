package io.github.zyrouge.symphony.ui.view.nowPlaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import coil.compose.AsyncImage
import io.github.zyrouge.symphony.services.groove.Song
import io.github.zyrouge.symphony.ui.components.KeepScreenAwake
import io.github.zyrouge.symphony.ui.components.LyricsText
import io.github.zyrouge.symphony.ui.components.TimedContentTextStyle
import io.github.zyrouge.symphony.ui.components.swipeable
import io.github.zyrouge.symphony.ui.helpers.FadeTransition
import io.github.zyrouge.symphony.ui.helpers.ScreenOrientation
import io.github.zyrouge.symphony.ui.helpers.ViewContext
import io.github.zyrouge.symphony.ui.view.AlbumViewRoute
import io.github.zyrouge.symphony.ui.view.LyricsViewRoute
import io.github.zyrouge.symphony.ui.view.NowPlayingData
import io.github.zyrouge.symphony.ui.view.NowPlayingDefaults
import io.github.zyrouge.symphony.ui.view.NowPlayingLyricsLayout
import io.github.zyrouge.symphony.ui.view.NowPlayingStates

@Composable
fun NowPlayingBodyCover(
    context: ViewContext,
    data: NowPlayingData,
    states: NowPlayingStates,
    orientation: ScreenOrientation,
) {
    val showLyrics by states.showLyrics.collectAsState()

    Box(modifier = Modifier.padding(defaultHorizontalPadding, 0.dp)) {
        AnimatedContent(
            label = "now-playing-body-cover",
            targetState = showLyrics,
            contentAlignment = Alignment.Center,
            transitionSpec = {
                val from = FadeTransition.enterTransition()
                val to = FadeTransition.exitTransition()
                from togetherWith to
            },
        ) { targetStateShowLyrics ->
            if (targetStateShowLyrics) {
                NowPlayingBodyCoverLyrics(context, orientation)
            } else {
                NowPlayingBodyCoverArtwork(
                    context,
                    data.song,
                    // MAZIKA: swiping the large cover downward opens lyrics using
                    // the same behaviour as the lyrics button in the bottom bar.
                    onOpenLyrics = {
                        when (data.lyricsLayout) {
                            NowPlayingLyricsLayout.ReplaceArtwork -> {
                                states.showLyrics.value = true
                                NowPlayingDefaults.showLyrics = true
                            }

                            NowPlayingLyricsLayout.SeparatePage -> {
                                context.navController.navigate(LyricsViewRoute)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NowPlayingBodyCoverLyrics(context: ViewContext, orientation: ScreenOrientation) {
    val keepScreenAwake by context.symphony.settings.lyricsKeepScreenAwake.flow.collectAsState()

    if (keepScreenAwake) {
        KeepScreenAwake()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                0.dp,
                if (orientation == ScreenOrientation.LANDSCAPE) 0.dp else 8.dp
            )
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        LyricsText(
            context,
            padding = PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp,
            ),
            style = TimedContentTextStyle.defaultStyle(
                textStyle = LocalTextStyle.current,
                contentColor = LocalContentColor.current,
            ),
        )
    }
}

@Composable
private fun NowPlayingBodyCoverArtwork(
    context: ViewContext,
    song: Song,
    onOpenLyrics: () -> Unit,
) {
    // MAZIKA: the song object is unchanged when only its cover is replaced, so the
    // artwork request is keyed on the cover signal as well to redraw straight away.
    val coverUpdateId by context.symphony.groove.song.customCoverUpdateId.collectAsState()

    BoxWithConstraints {
        val dimension = min(this@BoxWithConstraints.maxHeight, this@BoxWithConstraints.maxWidth)

        AnimatedContent(
            label = "now-playing-body-cover-artwork",
            modifier = Modifier.size(dimension),
            targetState = song,
            transitionSpec = {
                FadeTransition.enterTransition()
                    .togetherWith(FadeTransition.exitTransition())
            },
        ) { targetStateSong ->
            val artworkRequest = remember(targetStateSong.id, coverUpdateId) {
                targetStateSong.createArtworkImageRequest(context.symphony).build()
            }
            AsyncImage(
                artworkRequest,
                null,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .swipeable(
                        minimumDragAmount = 100f,
                        onSwipeLeft = {
                            if (context.symphony.radio.canJumpToNext()) {
                                context.symphony.radio.jumpToNext()
                            }
                        },
                        onSwipeRight = {
                            if (context.symphony.radio.canJumpToPrevious()) {
                                context.symphony.radio.jumpToPrevious()
                            }
                        },
                        // MAZIKA: deliberate downward swipe opens lyrics. The
                        // swipeable modifier only fires this once per gesture and
                        // only when the vertical drag dominates the horizontal one,
                        // so it will not conflict with the left/right track swipes.
                        onSwipeDown = onOpenLyrics,
                    )
                    .pointerInput(Unit) {
                        detectTapGestures { _ ->
                            context.symphony.groove.album
                                .getIdFromSong(song)
                                ?.let {
                                    context.navController.navigate(AlbumViewRoute(it))
                                }
                        }
                    }
            )
        }

    }
}
