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


## This repository already has a local release key

A release keystore has been generated for MAZIKA and lives at:

```
secrets/mazika-release.jks          # the key itself
secrets/keystore.env                # its credentials
```

Both are **git-ignored** (`.gitignore` ignores `secrets`) and are deliberately not
committed. `artifacts/MAZIKA.apk` is signed with this key.

Build a signed release with it:

```bash
set -a; source secrets/keystore.env; set +a
./gradlew assembleRelease          # Windows: gradlew.bat assembleRelease
```

When a keystore is configured, debug builds are signed with the same key too, so a
debug build can replace a release build (and vice versa) without uninstalling.

> **Back this folder up.** If `secrets/mazika-release.jks` is lost, you can never
> ship an update to an installed copy of MAZIKA - Android rejects an update whose
> signing certificate differs. Keep the same application id
> (`com.mazika.musicplayer`) and the same key for every future release.

## Generate a different keystore (if you ever need one)

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
