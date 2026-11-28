# Releasing

Push a tag `vX.Y.Z` (a tag with a `-suffix`, such as `v0.2.0-rc1`, becomes a pre-release):

```bash
git tag v0.2.0 && git push origin v0.2.0
```

`.github/workflows/release.yml` then builds a signed APK and AAB, checks the APK with `apksigner verify`, and creates a GitHub Release with generated notes, both artifacts and `SHA256SUMS.txt`.
`versionName` comes from the tag and `versionCode` from the run number, so it rises on every release.

## One-time setup

| Secret | Required | Value |
|---|---|---|
| `RELEASE_KEYSTORE_BASE64` | yes | `base64 -w0 release.jks` |
| `RELEASE_KEYSTORE_PASSWORD` | yes | keystore password |
| `RELEASE_KEY_ALIAS` | yes | key alias |
| `RELEASE_KEY_PASSWORD` | yes | key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | no | Play Console service account JSON. When set, the AAB is also uploaded to the **internal** track. |

Create the keystore once and keep it out of the repo:

```bash
keytool -genkeypair -v -keystore release.jks -alias belllap -keyalg RSA -keysize 4096 -validity 10000
```

Losing the keystore means losing the ability to update the app. Back it up. The first Play upload needs the app created in Play Console, with the service account granted release access.

## Local signed build

```bash
BELLLAP_KEYSTORE_FILE=/path/release.jks BELLLAP_KEYSTORE_PASSWORD=... BELLLAP_KEY_ALIAS=... BELLLAP_KEY_PASSWORD=... \
  ./gradlew assembleRelease bundleRelease
```

Without `BELLLAP_KEYSTORE_FILE` the release build is unsigned, which is what PR builds get.
