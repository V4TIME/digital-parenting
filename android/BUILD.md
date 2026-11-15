# Building the ChildGuard Android app

The project is a standard Kotlin/AGP app. To produce the installable monitor
APK you need a machine that can run Android's build tools (aapt2 is shipped
only for x86_64; it does **not** run on aarch64 hosts without binfmt
emulation):

| Host | Build possible? |
|---|---|
| x86_64 Linux / macOS / Windows | yes |
| aarch64 (ARM) Linux (e.g. Apple-Silicon-style boxes, ARM VPS) | no for AGP resource compilation (aapt2 x86_64 only) |

## Steps (on an x86_64 machine)

```bash
# 1. JDK 17 + Android SDK (platform 35, build-tools 34+) + Gradle 8.10+
# 2. point the SDK:
echo "sdk.dir=/path/to/android-sdk" > android/local.properties
# 3. build
cd android
gradle :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is auto-signed (debug keystore) and sideload-installable
(Settings → Unknown sources).

## Runtime test (Android 14/15)

See `RUNTIME_TEST.md` for the exact on-phone checklist and the evidence we
expect the parent server/dashboard to produce.
