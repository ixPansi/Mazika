-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile

# MAZIKA: components the system instantiates by name. AGP generates keep rules from the
# merged manifest, so these are belt-and-braces - but a media browser or content provider
# that R8 renamed fails only in a minified build, and only once it is in a car, which is
# the worst possible place to discover it.
-keep class io.github.zyrouge.symphony.services.radio.RadioBrowserService { *; }
-keep class io.github.zyrouge.symphony.services.radio.RadioNotificationService { *; }
-keep class io.github.zyrouge.symphony.services.radio.ArtworkProvider { *; }
-keep class io.github.zyrouge.symphony.MediaSearchActivity { *; }

# The legacy media-compat stack reaches for these reflectively.
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }
