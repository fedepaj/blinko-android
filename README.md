# blinko-android

Blinko Viewer for Android (Kotlin, Camera2, NDK). Manual exposure at the
sensor minimum, focus at infinity, 1080p at the highest frame rate, RGBA
capture when supported (YUV otherwise), shared C receiver from the git
submodule `core/` via JNI.

## Requirements

- Android SDK 36 and NDK 27.2.12479018 (Android Studio installs both), JDK 17.
- A phone with API 26+, arm64, and ideally the Camera2 `MANUAL_SENSOR`
  capability: without it only AE compensation is available, the chips come out
  blurred and the board needs a longer chip (`chip 60` or more).
- A board running the Blinko firmware to look at.

## Build and run

```sh
git submodule update --init
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
make apk        # build/Blinko-android-debug.apk
adb install -r build/Blinko-android-debug.apk
```

`make apk` calls `./gradlew assembleDebug`; set `JAVA_HOME` if your JDK is not
the one bundled with Android Studio.

## Using the app

Grant the camera permission and hold the phone 1–3 cm from the LED. The status
line shows the camera, fps, pkt/s, rows/chip, contrast, syncs, crc failures,
ROI, message count, scan axis, receiver mode, pilots and ISO. The profile view
under the preview draws the per-row profile; **tap it to switch the scan axis**
(rows ↔ columns, some sensors read out the other way round) and **long-press
to reset** the receiver. Decoded messages fill the console below, with a
button to filter by source; each tracked light gets a marker in the preview.

If `rows/chip` stays 0 while contrast is high, try the other axis first, then a
longer chip on the board (see `docs/CALIBRATION.md` in the umbrella repo).

## Remote session and replay

Both are iOS-only for now. The iPhone app opens a TCP server on port 7777
(Settings › Debug › Remote session) driven from a computer by
`ios/tools/rslive.py` — settings, stats, frames, recordings — and can replay a
`.rsrec` recording in-app from its Lab tab. On Android the equivalent is
offline: replay any recording through the same C receiver on a computer with

```sh
python core/tools/replay.py REC.rsrec --multi
```

which prints frames, fps, packets/s, messages, RGB lock and pilots, so a
decoder change can be checked against a real capture before rebuilding the APK.
