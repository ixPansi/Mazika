package io.github.zyrouge.symphony

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zyrouge.symphony.services.AppMeta
import io.github.zyrouge.symphony.services.Permissions
import io.github.zyrouge.symphony.services.Settings
import io.github.zyrouge.symphony.services.database.Database
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.i18n.Translator
import io.github.zyrouge.symphony.services.radio.Radio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Symphony(application: Application) : AndroidViewModel(application), Symphony.Hooks {
    interface Hooks {
        fun onSymphonyReady() {}
        fun onSymphonyDestroy() {}
        fun onSymphonyActivityReady() {}
        fun onSymphonyActivityPause() {}
        fun onSymphonyActivityDestroy() {}
    }

    val permission = Permissions(this)
    val settings = Settings(this)
    val database = Database(this)
    val groove = Groove(this)
    val radio = Radio(this)
    val translator = Translator(this)

    var t by mutableStateOf(translator.getCurrentTranslation())

    var updateNotification by mutableStateOf<AppMeta.Release?>(null)
        private set

    val applicationContext get() = getApplication<Application>().applicationContext
    var closeApp: (() -> Unit)? = null
    @Volatile
    private var isReady = false
    private var hooks = listOf(this, radio, groove)
    private var updateCheckJob: Job? = null

    internal fun emitReady() {
        if (isReady) {
            return
        }
        isReady = true
        try {
            notifyHooks { onSymphonyReady() }
        } catch (err: Throwable) {
            // MAZIKA: this instance is process-scoped, so a half-initialised state
            // would otherwise persist for the lifetime of the process and every
            // later emitReady() would short-circuit, leaving the app permanently
            // broken. Clear the latch so initialisation can be retried.
            isReady = false
            throw err
        }
    }

    internal fun emitDestroy() {
        notifyHooks { onSymphonyDestroy() }
    }

    internal fun emitActivityReady() {
        emitReady()
        notifyHooks { onSymphonyActivityReady() }
    }

    internal fun emitActivityPause() {
        notifyHooks { onSymphonyActivityPause() }
    }

    internal fun emitActivityDestroy() {
        notifyHooks { onSymphonyActivityDestroy() }
    }

    override fun onSymphonyReady() {
        checkForUpdates()
        viewModelScope.launch {
            translator.onChange { nTranslation ->
                t = nTranslation
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        emitDestroy()
    }

    private fun notifyHooks(fn: Hooks.() -> Unit) {
        hooks.forEach { fn.invoke(it) }
    }

    /**
     * The automatic check, run once per process launch from `onSymphonyReady`.
     * Honours the user's preference; see [checkForUpdatesNow] for the manual one.
     */
    fun checkForUpdates() {
        if (!settings.checkForUpdates.value) {
            return
        }
        checkForUpdatesNow()
    }

    /**
     * MAZIKA: a check the user explicitly asked for.
     *
     * Deliberately ignores [Settings.checkForUpdates] — that setting governs the
     * automatic check at startup, and pressing the button is intent that overrides
     * it. [AppMeta.canCheckForUpdates] still gates it: with no repository slug
     * there is nothing to call. The in-flight guard is what keeps repeated taps
     * from hammering the GitHub API, which allows 60 unauthenticated requests an
     * hour per address.
     */
    fun checkForUpdatesNow() {
        if (!AppMeta.canCheckForUpdates || updateCheckJob?.isActive == true) {
            return
        }
        updateCheckJob = viewModelScope.launch {
            val release = withContext(Dispatchers.IO) {
                AppMeta.fetchLatestVersion()
            }
            settings.lastUpdateCheck.setValue(System.currentTimeMillis())
            if (release != null && settings.showUpdateToast.value) {
                updateNotification = release
            }
        }
    }

    fun consumeUpdateNotification(release: AppMeta.Release) {
        if (updateNotification == release) {
            updateNotification = null
        }
    }
}
