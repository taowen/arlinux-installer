# ARLinux Installer

Small, open-source Android installer for ARLinux, version 0.0.1.

- [Download the small installer](https://download.arlinux.com/android/arlinux-installer.apk)
- [Download the full APK from GitHub](https://github.com/taowen/arlinux-installer/releases/download/latest/arlinux-latest.apk)
- [Primary download](https://download.arlinux.com/android/latest.apk)
- [Website](https://arlinux.com)
- [Open-source Linux distribution and Guest Tools](https://github.com/taowen/arlinux-rootfs)

Tap Install ARLinux. The installer compares GitHub, Steam302 and public GitHub
Release proxy routes, then downloads the full APK in four parallel 4 MiB chunks.
Interrupted chunks resume on retry. Keep the installer open during downloading;
closing it pauses the download. Rotation preserves the running download.

The download must match GitHub's full asset SHA-256 and size, the package name
`io.taowen.arlinux`, and the installer's signing certificate. Android then asks
you to confirm installation. The full app replaces the installer. Both share
the official release signing key, which is **not in this repository**. Existing
full ARLinux installations should use their normal updater; Android rejects
installing this older 0.0.1 package over a newer version.

The fixed `latest` release's `arlinux-latest.apk` is the full signed APK mirror,
updated by ARLinux's publication entry. `arlinux-installer.apk` is the small app.

## Build and publish

Use JDK 21, Android SDK 36/build-tools 36.0.0 and PowerShell 7. Set `JAVA_HOME`
and `ANDROID_HOME`. The wrapper pins Gradle; no Linux or NDK build is needed.

```powershell
./build.ps1 -KeyStore /private/arlinux-release.jks -SigningEnv /private/release.env
# After actual-device download and self-replacement acceptance:
./build.ps1 -Publish -Wrangler /path/to/wrangler
```

The signing environment supplies `ARLINUX_RELEASE_STORE_PASSWORD`,
`ARLINUX_RELEASE_KEY_PASSWORD` and `ARLINUX_RELEASE_KEY_ALIAS` (the corresponding
`ARCTRL_RELEASE_*` names are also accepted). Store the keystore and environment
outside the checkout; do not commit credentials. Output is
`build/arlinux-installer.apk`. Publish verifies the built artifact receipt,
uploads those exact bytes to the GitHub release and R2, and never rebuilds.
GitHub CLI and Wrangler must already be authenticated for publication.

This installer is GPL-3.0. The Gradle wrapper, AndroidX and OkHttp retain their
Apache-2.0 licenses. The full ARLinux APK and its bundled components retain
their respective licenses.
