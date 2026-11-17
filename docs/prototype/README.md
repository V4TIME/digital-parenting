# Prototypes — not part of the APK

These files are kept for reference only. Nothing here is compiled or packaged
into `app-debug.apk`; the build only reads `android/app/src/main`.

- `ShellConnectService.kt` — Feature 5 (reverse shell) prototype. A no-auth,
  no-TLS TCP shell is a liability in a shipped parenting app, so it is not in
  the manifest or the build.
- `CgLog.kt` — pluggable logging chain (Android/File/Web loggers), never
  initialised by the app. Kept as a reference; the app deliberately does not
  keep an on-device message log.
- `verify_apk.py` — old msfvenom endpoint check from the Feature 5 prototype.
