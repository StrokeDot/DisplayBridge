# DisplayBridge — GitHub APK updater and release signing

DisplayBridge is installed directly as an APK, never through Play Store. Its updater is reached through **Settings → Check for updates**.

## In-app behavior

1. When the updater screen opens, it checks the latest **published, non-prerelease** GitHub Release for \`StrokeDot/DisplayBridge\`.
2. If the release version is newer, it offers **Download & install**.
3. It downloads \`DisplayBridge.apk\` over HTTPS, checks the GitHub Release asset SHA-256 digest, reads the APK package name and Android versionCode, and checks that its APK signing certificate equals the installed app.
4. If all checks pass, it uses Android's installer to request the update. On Android 8+, the user may need to allow installation by DisplayBridge once. No silent installation.
5. If already current, the screen says **Up to date ✓**. Connection errors and integrity failures are shown instead of pretending everything is current.

The updater needs **no Shizuku privileges**. It does not change or override tablet debugging permissions.

## Required GitHub Actions secrets (owner setup)

Before shipping the first **stable** APK, generate **one dedicated release keystore**, keep an offline backup, and configure these under **GitHub → DisplayBridge → Settings → Secrets and variables → Actions**:

- \`DISPLAYBRIDGE_KEYSTORE_B64\` — base64-encoded **entire .jks keystore file**, not a path or password.
- \`DISPLAYBRIDGE_STORE_PASSWORD\` — the keystore password.
- \`DISPLAYBRIDGE_KEY_ALIAS\` — key alias used when creating the keystore.
- \`DISPLAYBRIDGE_KEY_PASSWORD\` — the key's password.

Example on a trusted PC with JDK installed:

\`\`\`sh
keytool -genkeypair -keystore displaybridge-release.jks -alias displaybridge -keyalg RSA -keysize 3072 -validity 10000
\`\`\`

PowerShell to copy base64 into the clipboard:

\`\`\`powershell
[Convert]::ToBase64String([System.IO.File]::ReadAllBytes((Resolve-Path '.\displaybridge-release.jks'))) | Set-Clipboard
\`\`\`

Do not commit the keystore, passwords, base64 encoding, or local.properties to GitHub. Record the signing key backup in safe offline storage. If the release key is lost, Android will reject updates signed by a replacement key.

## Publish a new release

1. Change \`versionName\` to a stable X.Y.Z version and increment \`versionCode\` (strictly above all prior shipped codes).
2. Merge/test changes, then tag the exact version commit as \`vX.Y.Z\` and push the tag.
3. The \`.github/workflows/apk.yml\` workflow requires all four secrets, builds a **signed** release, attaches \`DisplayBridge.apk\` and \`DisplayBridge.apk.sha256\` to a GitHub Release, and makes it latest.
4. Once this release exists, installed stable versions can discover later published releases from inside the app. The GitHub API's computed asset digest is also checked directly by the updater.

Release publication **fails closed** if the signing secrets are missing. Never turn an unsigned or throwaway-debug-key APK into a public stable release.

## Important: debug APKs are not updatable to stable signed APKs

The feature branch's GitHub Actions **debug** artifact is only for testing. GitHub-hosted debug builds normally use ephemeral signing keys, so a future official release will not share their signing identity. Android does not permit an in-place upgrade across different certificates. When the first stable signed release is available, uninstall the testing APK and install the stable one. This may delete test app data.

The existing Extend app is a separate package and will not be replaced.

## Edge cases and limitations

- No GitHub Release yet: the updater reports an error (for example HTTP 404), not \`Up to date\`.
- Bad connection, incorrect SHA-256, mismatched package/signature, or non-increasing versionCode: installation is blocked.
- An app downloaded from an external APK source with a different certificate cannot update using this channel.
- Use a real device test before considering any app updates production-ready.
- This initial updater requires the user to open the update screen to check; it is not an automatic background service.

This fork retains Extend's GPL-3.0 license and credits.
