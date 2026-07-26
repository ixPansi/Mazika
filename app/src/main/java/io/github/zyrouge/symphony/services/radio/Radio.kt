package io.github.zyrouge.symphony.services.radio

import io.github.zyrouge.symphony.Symphony
import io.github.zyrouge.symphony.utils.Eventer
import io.github.zyrouge.symphony.utils.Logger
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Date
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean

class Radio(private val symphony: Symphony) : Symphony.Hooks {
    sealed class Events {
        sealed class Player : Events() {
            object Staged : Player()
            object Started : Player()
            object Stopped : Player()
            object Paused : Player()
            object Resumed : Player()
            object Seeked : Player()
            object Ended : Player()
        }

        sealed class Queue : Events() {
            object Modified : Queue()
            object IndexChanged : Queue()
            object Cleared : Queue()
        }

        sealed class QueueOption : Events() {
            object LoopModeChanged : QueueOption()
            object ShuffleModeChanged : QueueOption()
            object SleepTimerChanged : QueueOption()
            object SpeedChanged : QueueOption()
            object PitchChanged : QueueOption()
            object PauseOnCurrentSongEndChanged : QueueOption()
        }
    }

    data class SleepTimer(
        val duration: Long,
        val endsAt: Long,
        val timer: Timer,
        var quitOnEnd: Boolean,
    )

    val onUpdate = Eventer<Events>()
    val queue = RadioQueue(symphony)
    val shorty = RadioShorty(symphony)
    internal val artworkUris = ArtworkUriResolver(symphony)
    val session = RadioSession(symphony)
    var observatory = RadioObservatory(symphony)

    private val focus = RadioFocus(symphony)
    private val nativeReceiver = RadioNativeReceiver(symphony)
    private val transition = PlaybackTransition()
    private val playbackLock = Any()
    @Volatile
    private var player: RadioPlayer? = null
    @Volatile
    private var stagedPlayer: RadioPlayer? = null
    @Volatile
    private var outgoingPlayer: RadioPlayer? = null

    val hasPlayer get() = player != null
    val playWhenReady get() = transition.playWhenReady
    val isPlaying get() = player?.isPlaying == true || outgoingPlayer?.isPlaying == true
    val currentPlaybackPosition get() = player?.playbackPosition
    val currentSpeed get() = player?.speed ?: RadioPlayer.DEFAULT_SPEED
    val currentPitch get() = player?.pitch ?: RadioPlayer.DEFAULT_PITCH
    val audioSessionId get() = player?.audioSessionId
    val onPlaybackPositionUpdate = Eventer<RadioPlayer.PlaybackPosition>()

    var persistedSpeed = RadioPlayer.DEFAULT_SPEED
    var persistedPitch = RadioPlayer.DEFAULT_PITCH
    var sleepTimer: SleepTimer? = null
    var pauseOnCurrentSongEnd = false

    init {
        nativeReceiver.start()
        onUpdate.subscribe(this::watchQueueUpdates)
    }

    fun ready() {
        attachGrooveListener()
        session.start()
        observatory.start()
    }

    fun destroy() {
        stop()
        observatory.destroy()
        session.destroy()
        nativeReceiver.destroy()
    }

    data class PlayOptions(
        val index: Int = 0,
        val autostart: Boolean = true,
        val startPosition: Long? = null,
    )

    fun play(options: PlayOptions) = play(options, requestedSongId = null)

    private fun play(options: PlayOptions, requestedSongId: String?) {
        // Register intent before constructing MediaPlayer. A pause that arrives while
        // setDataSource is blocking must win over this request's eventual prepare callback.
        val generation = transition.begin(options.autostart)
        val playTarget = when (requestedSongId) {
            null -> queue.resolvePlayTarget(options.index)
            else -> queue.resolvePlayTarget(requestedSongId, options.index)
        }
        val song = playTarget?.songId?.let { symphony.groove.song.get(it) }
        if (song == null) {
            val shouldContinue = transition.invalidateIfCurrent(generation)
            if (shouldContinue != null && playTarget != null) {
                removeFailedSong(playTarget, shouldContinue)
            } else if (shouldContinue != null) {
                if (requestedSongId == null) {
                    onSongFinish(SongFinishSource.Exception)
                } else {
                    restorePlaybackIntent(shouldContinue)
                }
            }
            return
        }
        var incoming: RadioPlayer? = null
        try {
            val target = adoptStagedPlayer(song.id) ?: RadioPlayer(symphony, song.id, song.uri)
            incoming = target
            var adopted = false
            transition.runIfCurrent(generation) {
                queue.withResolvedPlayTarget(playTarget) { committedIndex ->
                    synchronized(playbackLock) {
                        val previous = player
                        player = target
                        attachPlayerListeners(target, generation, options)
                        // Publishing the outgoing player in this same critical section means
                        // pause can never miss audio displaced by the handoff.
                        retirePlayer(previous, allowFade = transition.playWhenReady)
                    }
                    // Keep ownership, queue identity and preparation on one generation
                    // and one queue occurrence.
                    queue.currentSongIndex = committedIndex
                    target.prepare()
                    onUpdate.dispatch(Events.Player.Staged)
                    adopted = true
                }
            }
            if (!adopted) {
                disposePlayer(target)
                transition.invalidateIfCurrent(generation)?.let { shouldContinue ->
                    retryPlayTarget(playTarget, shouldContinue)
                }
                return
            }
        } catch (err: Exception) {
            val shouldContinue = transition.invalidateIfCurrent(generation)
            synchronized(playbackLock) {
                if (player === incoming) {
                    player = null
                }
            }
            incoming?.let(::disposePlayer)
            Logger.warn(
                "Radio",
                "skipping song ${song.id} (${options.index})",
                err,
            )
            if (shouldContinue != null) {
                removeFailedSong(playTarget, shouldContinue)
            }
        }
    }

    private fun attachPlayerListeners(
        target: RadioPlayer,
        generation: Long,
        options: PlayOptions,
    ) {
        target.clearListeners()
        target.setOnPreparedListener {
            transition.runIfCurrent(generation) prepared@{
                if (player !== target) {
                    return@prepared
                }
                options.startPosition?.let {
                    if (it > 0L) {
                        target.seek(it.toInt())
                        if (isCurrent(target, generation)) {
                            onUpdate.dispatch(Events.Player.Seeked)
                        }
                    }
                }
                if (!isCurrent(target, generation)) {
                    return@prepared
                }
                target.changeSpeed(persistedSpeed)
                onUpdate.dispatch(Events.QueueOption.SpeedChanged)
                if (!isCurrent(target, generation)) {
                    return@prepared
                }
                target.changePitch(persistedPitch)
                onUpdate.dispatch(Events.QueueOption.PitchChanged)
                if (transition.shouldStart(generation)) {
                    start(target, generation)
                }
            }
        }
        target.setOnPlaybackPositionListener {
            if (isCurrent(target, generation)) {
                onPlaybackPositionUpdate.dispatch(it)
            }
        }
        target.setOnFinishListener {
            transition.runIfCurrent(generation) finished@{
                if (player !== target) {
                    return@finished
                }
                onSongFinish(SongFinishSource.Finish)
            }
        }
        target.setOnErrorListener { what, extra ->
            transition.runIfCurrent(generation) failed@{
                if (player !== target) {
                    return@failed
                }
                Logger.warn(
                    "Radio",
                    "skipping song ${queue.currentSongId} (${queue.currentSongIndex}) due to $what + $extra"
                )
                when {
                    // happens when change playback params fail, we skip it since its non-critical
                    what == 1 && extra == -22 -> onSongFinish(SongFinishSource.Finish)
                    else -> removeFailedSong(target.id, transition.playWhenReady)
                }
            }
        }
    }

    private fun prepareNextPlayer() {
        val current = synchronized(playbackLock) { player }
        if (!symphony.settings.gaplessPlayback.value || current == null) {
            clearStagedPlayer()
            return
        }
        val (nextSongIndex) = getNextSong(SongFinishSource.Finish)
        val song = queue.getSongIdAt(nextSongIndex)?.let { symphony.groove.song.get(it) }
        if (song == null) {
            clearStagedPlayer()
            return
        }
        if (synchronized(playbackLock) {
                stagedPlayer?.let { it.id == song.id && it.available }
            } == true
        ) {
            return
        }
        var candidate: RadioPlayer? = null
        try {
            val next = RadioPlayer(symphony, song.id, song.uri)
            candidate = next
            next.setOnErrorListener { what, extra ->
                val stillOwned = synchronized(playbackLock) {
                    if (stagedPlayer === next) {
                        stagedPlayer = null
                        true
                    } else {
                        false
                    }
                }
                if (!stillOwned) {
                    return@setOnErrorListener
                }
                disposePlayer(next)
                Logger.warn(
                    "Radio",
                    "unable to prepare next player ${song.id} (${nextSongIndex}) due to $what + $extra",
                )
            }
            var displaced: RadioPlayer? = null
            val adopted = synchronized(playbackLock) {
                val latestNextIndex = getNextSong(SongFinishSource.Finish).first
                val latestNextSongId = queue.getSongIdAt(latestNextIndex)
                if (
                    player !== current ||
                    !symphony.settings.gaplessPlayback.value ||
                    latestNextSongId != song.id
                ) {
                    false
                } else {
                    displaced = stagedPlayer
                    stagedPlayer = next
                    true
                }
            }
            if (!adopted) {
                disposePlayer(next)
                return
            }
            displaced?.takeIf { it !== next }?.let(::disposePlayer)
            next.prepare()
        } catch (err: Exception) {
            synchronized(playbackLock) {
                if (stagedPlayer === candidate) {
                    stagedPlayer = null
                }
            }
            candidate?.let(::disposePlayer)
            Logger.warn(
                "Radio",
                "unable to prepare next player ${song.id} (${nextSongIndex})",
                err,
            )
        }
    }

    fun resume() {
        val generation = transition.resume()
        val target = synchronized(playbackLock) { player }
        target ?: return
        start(target, generation)
    }

    private fun start(target: RadioPlayer, generation: Long) {
        if (!isCurrent(target, generation) || !transition.shouldStart(generation) || !target.usable) {
            return
        }
        // A fresh track start follows the main fade option; a resume additionally
        // requires the pause/resume fade option.
        val isResume = target.hasPlayedOnce
        val shouldFadeIn = PlaybackFade.shouldFade(
            mainFadeEnabled = target.fadePlayback,
            fadeOnPauseResume = symphony.settings.fadeOnPauseResume.value,
            isUserPauseResume = isResume,
        )
        transition.startIfReady(generation) {
            if (player !== target || !target.usable) {
                return@startIfReady false
            }
            val hasFocus = focus.requestFocus()
            if (symphony.settings.requireAudioFocus.value && !hasFocus) {
                return@startIfReady false
            }
            target.cancelVolumeChange()
            if (shouldFadeIn) {
                target.changeVolumeInstant(RadioPlayer.MIN_VOLUME)
                target.changeVolume(RadioPlayer.MAX_VOLUME) {}
            } else {
                target.changeVolumeInstant(RadioPlayer.MAX_VOLUME)
            }
            if (!target.start()) {
                return@startIfReady false
            }
            onUpdate.dispatch(
                when {
                    !isResume -> Events.Player.Started
                    else -> Events.Player.Resumed
                }
            )
            true
        }
    }

    fun pause() {
        val shouldFadeOut = PlaybackFade.shouldFade(
            mainFadeEnabled = symphony.settings.fadePlayback.value,
            fadeOnPauseResume = symphony.settings.fadeOnPauseResume.value,
            isUserPauseResume = true,
        )
        if (shouldFadeOut) {
            pause(forceFade = true) {}
        } else {
            pauseImmediately(abandonFocus = true)
        }
    }

    private fun pause(forceFade: Boolean = false, onFinish: () -> Unit) {
        val (generation, intentChanged) = transition.pause()
        val completionDelivered = AtomicBoolean(false)
        val finishOnce = {
            if (completionDelivered.compareAndSet(false, true)) {
                onFinish()
            }
        }
        var completeNow = false
        var abandonFocus = false
        var dispatchPaused = false
        val pauseApplied = transition.runIfPaused(generation) {
            synchronized(playbackLock) {
                val outgoing = takeOutgoingPlayer()
                val stoppedOutgoing = outgoing != null
                outgoing?.let(::disposePlayer)
                if (!intentChanged && !stoppedOutgoing) {
                    completeNow = true
                } else {
                    val target = player
                    if (target == null || !isCurrent(target, generation)) {
                        completeNow = true
                        abandonFocus = stoppedOutgoing
                        dispatchPaused = stoppedOutgoing
                    } else if (!target.isPlaying) {
                        target.cancelVolumeChange()
                        completeNow = true
                        abandonFocus = true
                        dispatchPaused = true
                    } else if (
                        target.volume == RadioPlayer.MIN_VOLUME ||
                        symphony.settings.fadePlaybackDuration.value <= 0
                    ) {
                        // Avoid a synchronous fader callback while holding playbackLock.
                        target.cancelVolumeChange()
                        target.changeVolumeInstant(RadioPlayer.MIN_VOLUME)
                        target.pause()
                        completeNow = true
                        abandonFocus = true
                        dispatchPaused = true
                    } else {
                        target.changeVolume(
                            to = RadioPlayer.MIN_VOLUME,
                            forceFade = forceFade,
                        ) { result ->
                            if (result == RadioEffects.Fader.Result.Cancelled) {
                                if (!transition.playWhenReady && !isPlaying) {
                                    finishOnce()
                                }
                                return@changeVolume
                            }
                            transition.completePause(generation) {
                                if (player !== target || !target.pause()) {
                                    if (!transition.playWhenReady && !isPlaying) {
                                        finishOnce()
                                    }
                                    return@completePause false
                                }
                                focus.abandonFocus()
                                finishOnce()
                                onUpdate.dispatch(Events.Player.Paused)
                                true
                            }
                        }
                    }
                }
            }
        }
        if (!pauseApplied) return
        if (completeNow) {
            transition.completePause(generation) {
                if (abandonFocus) {
                    focus.abandonFocus()
                }
                finishOnce()
                if (dispatchPaused) {
                    onUpdate.dispatch(Events.Player.Paused)
                }
                true
            }
        }
    }

    fun pauseInstant() {
        pauseImmediately(abandonFocus = false)
    }

    private fun pauseImmediately(abandonFocus: Boolean) {
        val (generation, intentChanged) = transition.pause()
        transition.runIfPaused(generation) {
            synchronized(playbackLock) {
                val outgoing = takeOutgoingPlayer()
                val stoppedOutgoing = outgoing != null
                outgoing?.let(::disposePlayer)
                val target = player
                val pausedCurrent = target != null && isCurrent(target, generation) && target.pause()
                if (!intentChanged && !stoppedOutgoing && !pausedCurrent) {
                    return@synchronized
                }
                if (abandonFocus) {
                    focus.abandonFocus()
                }
                onUpdate.dispatch(Events.Player.Paused)
            }
        }
    }

    fun stop(ended: Boolean = true) {
        var ownedPlayers = emptyList<RadioPlayer>()
        transition.invalidate {
            ownedPlayers = synchronized(playbackLock) {
                val current = player
                val staged = stagedPlayer
                player = null
                stagedPlayer = null
                val outgoing = takeOutgoingPlayer()
                linkedSetOf(current, staged, outgoing).filterNotNull()
            }
            queue.reset()
        }
        ownedPlayers.forEach(::disposePlayer)
        focus.abandonFocus()
        clearSleepTimer()
        persistedSpeed = RadioPlayer.DEFAULT_SPEED
        persistedPitch = RadioPlayer.DEFAULT_PITCH
        when {
            ended -> onUpdate.dispatch(Events.Player.Ended)
            ownedPlayers.isNotEmpty() -> onUpdate.dispatch(Events.Player.Stopped)
        }
    }

    fun jumpTo(index: Int) = play(PlayOptions(index = index))
    fun jumpToPrevious() = jumpTo(queue.currentSongIndex - 1)
    fun jumpToNext() = jumpTo(queue.currentSongIndex + 1)
    fun canJumpToPrevious() = queue.hasSongAt(queue.currentSongIndex - 1)
    fun canJumpToNext() = queue.hasSongAt(queue.currentSongIndex + 1)

    fun seek(position: Long) {
        player?.let {
            it.seek(position.toInt())
            onUpdate.dispatch(Events.Player.Seeked)
        }
    }

    fun duck() {
        player?.let {
            it.changeVolume(RadioPlayer.DUCK_VOLUME) {}
        }
    }

    fun restoreVolume() {
        player?.let {
            it.changeVolume(RadioPlayer.MAX_VOLUME) {}
        }
    }

    fun setSpeed(speed: Float, persist: Boolean) {
        player?.let {
            it.changeSpeed(speed)
            if (persist) {
                persistedSpeed = speed
            }
            onUpdate.dispatch(Events.QueueOption.SpeedChanged)
        }
    }

    fun setPitch(pitch: Float, persist: Boolean) {
        player?.let {
            it.changePitch(pitch)
            if (persist) {
                persistedPitch = pitch
            }
            onUpdate.dispatch(Events.QueueOption.PitchChanged)
        }
    }

    fun setSleepTimer(
        duration: Long,
        quitOnEnd: Boolean,
    ) {
        val endsAt = System.currentTimeMillis() + duration
        val timer = Timer()
        timer.schedule(
            kotlin.concurrent.timerTask {
                val shouldQuit = sleepTimer?.quitOnEnd ?: quitOnEnd
                clearSleepTimer()
                pause(forceFade = true) {
                    if (shouldQuit) {
                        symphony.closeApp?.invoke()
                    }
                }
            },
            Date.from(Instant.ofEpochMilli(endsAt)),
        )
        clearSleepTimer()
        sleepTimer = SleepTimer(
            duration = duration,
            endsAt = endsAt,
            timer = timer,
            quitOnEnd = quitOnEnd,
        )
        onUpdate.dispatch(Events.QueueOption.SleepTimerChanged)
    }

    fun clearSleepTimer() {
        sleepTimer?.timer?.cancel()
        sleepTimer = null
        onUpdate.dispatch(Events.QueueOption.SleepTimerChanged)
    }

    @JvmName("setPauseOnCurrentSongEndTo")
    fun setPauseOnCurrentSongEnd(value: Boolean) {
        pauseOnCurrentSongEnd = value
        onUpdate.dispatch(Events.QueueOption.PauseOnCurrentSongEndChanged)
    }

    private fun adoptStagedPlayer(songId: String): RadioPlayer? {
        val staged = synchronized(playbackLock) {
            stagedPlayer.also { stagedPlayer = null }
        }
        if (staged == null) {
            return null
        }
        staged.clearListeners()
        return when {
            staged.id == songId && staged.available -> staged
            else -> {
                disposePlayer(staged)
                null
            }
        }
    }

    private fun clearStagedPlayer() {
        val staged = synchronized(playbackLock) {
            stagedPlayer.also { stagedPlayer = null }
        }
        staged?.let(::disposePlayer)
    }

    private fun retirePlayer(retiring: RadioPlayer?, allowFade: Boolean) {
        if (retiring == null) {
            return
        }
        retiring.clearListeners()
        if (!allowFade || !retiring.isPlaying) {
            disposePlayer(retiring)
            onUpdate.dispatch(Events.Player.Stopped)
            return
        }
        val displaced = synchronized(playbackLock) {
            val previous = outgoingPlayer
            outgoingPlayer = retiring
            previous
        }
        if (displaced != null && displaced !== retiring) {
            disposePlayer(displaced)
        }
        retiring.changeVolume(RadioPlayer.MIN_VOLUME) { result ->
            if (result != RadioEffects.Fader.Result.Completed) {
                return@changeVolume
            }
            val stillOwned = synchronized(playbackLock) {
                if (outgoingPlayer === retiring) {
                    outgoingPlayer = null
                    true
                } else {
                    false
                }
            }
            if (stillOwned) {
                disposePlayer(retiring)
                onUpdate.dispatch(Events.Player.Stopped)
            }
        }
    }

    private fun takeOutgoingPlayer(): RadioPlayer? = synchronized(playbackLock) {
        outgoingPlayer.also { outgoingPlayer = null }
    }

    private fun disposeOutgoingPlayer(): Boolean {
        val outgoing = takeOutgoingPlayer() ?: return false
        disposePlayer(outgoing)
        return true
    }

    private fun disposePlayer(target: RadioPlayer) {
        target.clearListeners()
        target.pause()
        target.destroy()
    }

    private fun removeFailedSong(songId: String, shouldContinue: Boolean) {
        val removedIndex = queue.removeCurrentWithoutPlayback(songId)
        if (removedIndex != null) {
            continueAfterRemoval(removedIndex, shouldContinue)
        }
    }

    private fun removeFailedSong(target: RadioQueue.PlayTarget, shouldContinue: Boolean) {
        val removedIndex = queue.removeWithoutPlayback(target)
            ?: queue.removeSongWithoutPlayback(target.songId, target.preferredIndex)
        if (removedIndex == null) {
            restorePlaybackIntent(shouldContinue)
        } else {
            continueAfterRemoval(removedIndex, shouldContinue)
        }
    }

    private fun continueAfterRemoval(index: Int, shouldContinue: Boolean) {
        val replacementIndex = replacementIndexAfterRemoval(index, queue.currentQueue.size)
        if (replacementIndex == null) {
            onSongFinish(SongFinishSource.Exception)
        } else {
            play(
                PlayOptions(
                    index = replacementIndex,
                    autostart = shouldContinue,
                )
            )
        }
    }

    private fun retryPlayTarget(target: RadioQueue.PlayTarget, shouldContinue: Boolean) {
        play(
            PlayOptions(index = target.preferredIndex, autostart = shouldContinue),
            requestedSongId = target.songId,
        )
    }

    private fun restorePlaybackIntent(shouldContinue: Boolean) {
        if (!hasPlayer) return
        if (shouldContinue) {
            resume()
        } else {
            pauseImmediately(abandonFocus = true)
        }
    }

    private fun isCurrent(target: RadioPlayer, generation: Long) =
        player === target && transition.isCurrent(generation)

    private enum class SongFinishSource {
        Finish,
        Exception,
    }

    private fun onSongFinish(source: SongFinishSource) {
        val shouldContinue = transition.playWhenReady
        if (queue.isEmpty()) {
            transition.invalidate()
            val finished = player
            player = null
            finished?.let(::disposePlayer)
            clearStagedPlayer()
            disposeOutgoingPlayer()
            queue.currentSongIndex = -1
            return
        }
        var (nextSongIndex, autostart) = getNextSong(source)
        autostart = autostart && shouldContinue
        if (pauseOnCurrentSongEnd) {
            autostart = false
            setPauseOnCurrentSongEnd(false)
        }
        play(PlayOptions(nextSongIndex, autostart = autostart))
    }

    private fun getNextSong(source: SongFinishSource): Pair<Int, Boolean> {
        if (queue.isEmpty()) {
            return -1 to false
        }
        var autostart: Boolean
        var nextSongIndex: Int
        when (queue.currentLoopMode) {
            RadioQueue.LoopMode.Song -> {
                nextSongIndex = queue.currentSongIndex
                autostart = source == SongFinishSource.Finish
                if (!queue.hasSongAt(nextSongIndex)) {
                    nextSongIndex = 0
                    autostart = false
                }
            }

            else -> {
                nextSongIndex = when (source) {
                    SongFinishSource.Finish -> queue.currentSongIndex + 1
                    SongFinishSource.Exception -> queue.currentSongIndex
                }
                autostart = true
                if (!queue.hasSongAt(nextSongIndex)) {
                    nextSongIndex = 0
                    autostart = queue.currentLoopMode == RadioQueue.LoopMode.Queue
                }
            }
        }
        return nextSongIndex to autostart
    }

    private fun attachGrooveListener() {
        symphony.groove.coroutineScope.launch {
            symphony.groove.readyDeferred.await()
            restorePreviousQueue()
        }
    }

    private fun restorePreviousQueue() {
        if (!queue.isEmpty()) {
            return
        }
        symphony.settings.previousSongQueue.value?.let { previous ->
            var currentSongIndex = previous.currentSongIndex
            var playedDuration = previous.playedDuration
            val originalQueue = mutableListOf<String>()
            val currentQueue = mutableListOf<String>()
            previous.originalQueue.forEach { songId ->
                if (symphony.groove.song.get(songId) != null) {
                    originalQueue.add(songId)
                }
            }
            previous.currentQueue.forEachIndexed { i, songId ->
                if (symphony.groove.song.get(songId) != null) {
                    currentQueue.add(songId)
                } else {
                    if (i < currentSongIndex) currentSongIndex--
                }
            }
            if (originalQueue.isEmpty() || hasPlayer) {
                return@let
            }
            if (currentSongIndex >= originalQueue.size) {
                currentSongIndex = 0
                playedDuration = 0
            }
            queue.restore(
                RadioQueue.Serialized(
                    currentSongIndex = currentSongIndex,
                    playedDuration = playedDuration,
                    originalQueue = originalQueue,
                    currentQueue = currentQueue,
                    shuffled = previous.shuffled,
                )
            )
        }
    }

    internal fun watchQueueUpdates(event: Events) {
        if (event !is Events.Queue) {
            return
        }
        prepareNextPlayer()
    }

    override fun onSymphonyReady() {
        ready()
    }

    override fun onSymphonyDestroy() {
        saveCurrentQueue()
        destroy()
    }

    override fun onSymphonyActivityPause() {
        saveCurrentQueue()
    }

    override fun onSymphonyActivityDestroy() {
        saveCurrentQueue()
    }

    private fun saveCurrentQueue() {
        if (queue.isEmpty()) {
            return
        }
        symphony.settings.previousSongQueue.setValue(
            RadioQueue.Serialized.create(
                queue = queue,
                playbackPosition = currentPlaybackPosition ?: RadioPlayer.PlaybackPosition.zero
            )
        )
    }
}
