package io.github.zyrouge.symphony.services.radio

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.zyrouge.symphony.utils.Logger

/**
 * MAZIKA: decides which apps may browse the media library.
 *
 * [RadioBrowserService] is exported, because Android Auto has to be able to bind it.
 * Before this existed, `onGetRoot` accepted every caller unconditionally - so any app on
 * the device could enumerate the whole library and, through
 * `RadioSession.registerBrowserClient`, be handed read grants on the otherwise unexported
 * artwork provider.
 *
 * The check is deliberately permissive about *which* system component is asking, because
 * getting this wrong makes the app vanish from the car entirely, which is a far worse
 * failure than an over-broad browse. A caller is allowed when it is this app, when it
 * holds a system media-control permission, or when it is a known media host that is
 * genuinely part of the system image. That last clause is what stops the allowlist from
 * being defeated by a sideloaded app simply naming itself after Android Auto.
 */
class PackageValidator(private val context: Context) {
    private val packageManager = context.packageManager
    private val cache = mutableMapOf<String, Boolean>()

    fun isKnownCaller(callerPackageName: String, callerUid: Int): Boolean {
        if (callerUid == android.os.Process.myUid()) {
            return true
        }
        return cache.getOrPut(callerPackageName) {
            resolve(callerPackageName, callerUid).also { allowed ->
                // Rejections are logged, and only rejections: this is the line to look
                // for with `adb logcat` if MAZIKA stops appearing in the car.
                if (!allowed) {
                    Logger.warn(
                        TAG,
                        "rejecting browser client $callerPackageName (uid $callerUid); " +
                                "add it to KNOWN_MEDIA_HOSTS if this is a real media host",
                    )
                }
            }
        }
    }

    private fun resolve(callerPackageName: String, callerUid: Int): Boolean {
        if (callerPackageName == context.packageName) {
            return true
        }
        // Anything the platform trusts to drive arbitrary media sessions - system media
        // controllers, the notification shade, assistant surfaces.
        val hasMediaControl = packageManager.checkPermission(
            Manifest.permission.MEDIA_CONTENT_CONTROL,
            callerPackageName,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMediaControl) {
            return true
        }
        if (callerUid == android.os.Process.SYSTEM_UID) {
            return true
        }
        return callerPackageName in KNOWN_MEDIA_HOSTS && isSystemPackage(callerPackageName)
    }

    /**
     * True when the package ships in the system image, or is a system app that has since
     * been updated from a store. A sideloaded impostor satisfies neither.
     */
    private fun isSystemPackage(packageName: String): Boolean = runCatching {
        val flags = packageManager.getApplicationInfo(packageName, 0).flags
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updatedSystem = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        system || updatedSystem
    }.getOrElse { false }

    companion object {
        private const val TAG = "PackageValidator"

        /** Media hosts that legitimately browse a music app's library. */
        private val KNOWN_MEDIA_HOSTS = setOf(
            // Android Auto, phone screen and projected head unit.
            "com.google.android.projection.gearhead",
            // Android Automotive OS media host.
            "com.android.car.media",
            "com.google.android.car.media",
            // Assistant / voice search, which issues playFromSearch.
            "com.google.android.googlequicksearchbox",
            "com.google.android.carassistant",
            // Wear OS companion.
            "com.google.android.wearable.app",
            // System UI media controls and the Bluetooth stack's AVRCP browsing.
            "com.android.systemui",
            "com.android.bluetooth",
            // Google's own Media Controller Test app, used in ANDROID_AUTO_TESTING.md.
            "com.example.android.mediacontroller",
        )
    }
}
