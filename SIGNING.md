# Release signing (MAZIKA)

MAZIKA's `release`, `nightly` and `canary` build types read their signing config
from environment variables (inherited from Symphony's build script):

```kotlin
signingConfigs {
    register("release") {
        storeFile = System.getenv("SIGNING_KEYSTORE_FILE")?.let { rootProject.file(it) }
        storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("SIGNING_KEY_ALIAS")
        keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    }
}
```

If these variables are not set, `assembleRelease` produces an **unsigned** release
APK (`*-release-unsigned.apk`). Install the debug APK for testing.

## Generate a permanent MAZIKA release keystore

Do this once and keep the keystore and passwords safe and private. **Never commit
the keystore or passwords to the repository.**

```bash
keytool -genkeypair -v \
  -keystore mazika-release.jks \
  -alias mazika \
  -keyalg RSA -keysize 2048 -validity 10000
```

## Build a signed release APK

Set the environment variables to point at your keystore, then build. The
`SIGNING_KEYSTORE_FILE` path is resolved relative to the project root.

Linux/macOS:

```bash
export SIGNING_KEYSTORE_FILE="mazika-release.jks"
export SIGNING_KEYSTORE_PASSWORD="********"
export SIGNING_KEY_ALIAS="mazika"
export SIGNING_KEY_PASSWORD="********"
./gradlew assembleRelease
```

Windows (PowerShell):

```powershell
$env:SIGNING_KEYSTORE_FILE="mazika-release.jks"
$env:SIGNING_KEYSTORE_PASSWORD="********"
$env:SIGNING_KEY_ALIAS="mazika"
$env:SIGNING_KEY_PASSWORD="********"
.\gradlew.bat assembleRelease
```

## Verify the signature

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-universal-release.apk
```

## ⚠️ Important

- **Every future MAZIKA update must use the same application id
  (`com.mazika.musicplayer`) and the same signing key.** Android will refuse to
  update an installed app if the signing certificate changes. Losing the keystore
  means users cannot upgrade in place.
- Keep the keystore backed up securely and out of version control (it is not, and
  must not be, committed here).
