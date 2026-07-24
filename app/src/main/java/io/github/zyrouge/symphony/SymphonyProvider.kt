package io.github.zyrouge.symphony

import android.app.Application

/**
 * MAZIKA: process-scoped provider for the single [Symphony] instance.
 *
 * Symphony holds the one playback engine and media session (the single playback
 * source of truth). It used to be created only by MainActivity via viewModels(),
 * which the Android Auto media browser service — which can be started by the
 * system without any activity — cannot reach. Both MainActivity and
 * [io.github.zyrouge.symphony.services.radio.RadioBrowserService] now obtain the
 * same instance from here, so the phone UI, notification, Bluetooth and Android
 * Auto all drive the same session and queue.
 */
object SymphonyProvider {
    @Volatile
    private var instance: Symphony? = null

    fun get(application: Application): Symphony =
        instance ?: synchronized(this) {
            instance ?: Symphony(application).also { instance = it }
        }
}
