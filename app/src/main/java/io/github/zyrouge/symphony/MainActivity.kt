package io.github.zyrouge.symphony

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.github.zyrouge.symphony.ui.view.BaseView
import io.github.zyrouge.symphony.utils.Logger
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {
    private var gSymphony: Symphony? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ignition: ActivityIgnition by viewModels()
        if (savedInstanceState == null) {
            installSplashScreen().apply {
                setKeepOnScreenCondition { !ignition.ready.value }
            }
        }

        // MAZIKA: report the crash, then hand over to the platform handler so the
        // process actually dies. Previously this handler swallowed the kill: on a
        // main-thread throw the exception escaped Looper.loop(), ActivityThread.main()
        // unwound and the main thread died, but the process stayed alive. ErrorActivity
        // could then never be created (its host thread was gone) and the splash screen's
        // keep-on-screen condition never cleared, so every startup crash presented as an
        // app frozen forever on the splash with no crash dialog and no way to recover.
        val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            Logger.error("MainActivity", "uncaught exception", err)
            try {
                ErrorActivity.start(this, err)
                finish()
            } catch (_: Throwable) {
                // Reporting must never mask the original failure.
            }
            defaultExceptionHandler?.uncaughtException(thread, err)
                ?: exitProcess(1)
        }

        // MAZIKA: obtain the single process-scoped Symphony so the phone UI and the
        // Android Auto media browser service share one playback source of truth.
        val symphony = SymphonyProvider.get(application)
        symphony.permission.handle(this)
        gSymphony = symphony
        symphony.emitActivityReady()
        attachHandlers()

        enableEdgeToEdge()
        setContent {
            LaunchedEffect(LocalContext.current) {
                ignition.emitReady()
            }
            BaseView(symphony = symphony, activity = this)
        }
    }

    override fun onPause() {
        super.onPause()
        gSymphony?.emitActivityPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        gSymphony?.emitActivityDestroy()
    }

    private fun attachHandlers() {
        gSymphony?.closeApp = {
            finish()
        }
    }
}
