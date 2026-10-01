# blinko-android

Blinko Viewer for Android (Kotlin, Camera2, NDK). Manual exposure at the
sensor minimum, focus at infinity, 1080p / 4K YUV or **RAW sensor capture**
(Settings › Resolution), shared C receiver from the git submodule `core/` via
JNI.

## Requirements

- Android SDK 36 and NDK 27.2.12479018 (Android Studio installs both), JDK 17.
- A phone with API 26+, arm64, and ideally the Camera2 `MANUAL_SENSOR`
  capability: without it only AE compensation is available, the chips come out
  blurred and the board needs a longer T (`chip 90`, `rep 2`).
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

Same four tabs as the iOS app. **Live**: preview with a ring and label at
every tracked light (id, mode, packets, last message), a stats capsule (fps,
pkt/s, rows/chip, contrast, mode, peak, pilots, messages), the per-row profile
with decoded packet spans, a "Hold still" warning while the phone moves, and
the last message. **Tap the profile to switch the scan axis** (rows ↔ columns,
some sensors read out the other way round), **long-press to reset** the
receiver. **Console**: messages newest first with level, source (board id
once announced), slot and time; a FAULT/FATAL banner on top; filter by source,
share as text, swipe a row to delete it, trash icon to clear. **Lab**: strobe
calibration (row time and frame readout), in-app replay of recordings kept on
the phone (share / delete), live profile, camera details. **Settings**:
camera (every camera with focal length and minimum exposure), frame rate,
exposure, ISO, focus, zoom, scan axis, min contrast, multi-source, remote
session and recording mode.

If `rows/chip` stays 0 while contrast is high, try the other axis first, then
RAW capture, then a longer T with repetition on the board (`chip 90`, `rep 2`;
see `docs/CALIBRATION.md` in the umbrella repo).

## Remote session and replay

The app opens a TCP server on port 7777 (Settings › Remote session, on by
default) driven from a computer by `ios/tools/rslive.py`, the same client and
wire protocol as the iOS app: settings, stats, frames, recordings, replay.
Over USB:

```sh
adb forward tcp:7778 tcp:7777              # or: make android-forward (umbrella repo)
python ios/tools/rslive.py --port 7778 get                     # camera, exposure range, cameras
python ios/tools/rslive.py --port 7778 stats                   # one stats sample; `watch` streams them
python ios/tools/rslive.py --port 7778 set camera Ultra        # Wide | Ultra | Front | camera id
python ios/tools/rslive.py --port 7778 set exposure_us 60      # or exposure 0..1, iso, lensPosition, zoom, axis, minContrast, multiSource, labMode, strobeHz, rowUs, note
python ios/tools/rslive.py --port 7778 set resolution RAW      # 1080p | 4K | RAW (RAW: `frame` returns the three per-row profiles as PROFILES)
python ios/tools/rslive.py --port 7778 frame out.png           # one BGRA frame, columns /4
python ios/tools/rslive.py --port 7778 record --seconds 2 --note "S21 R4" --out testdata/android
python ios/tools/rslive.py --port 7778 files | pull NAME | replay NAME | delete NAME
adb logcat -s Blinko                                           # app log
```

Over Wi-Fi use `--host` with the address shown in Settings. Recordings are
`.rsrec` files (packed BGRA, every 4th column, raw frames) readable by
`core/tools/rsrec.py`; **Settings › Recording mode** adds a Rec button for
recordings without a computer, and the Recordings list can replay, share or
delete them. In-app replay runs a recording through the multi-source receiver
(messages are tagged `replay:`); it needs raw frames, so LZ4 recordings from
the iOS app are replayed on the computer instead:

```sh
python core/tools/replay.py REC.rsrec --multi
```

which prints frames, fps, packets/s, messages, RGB lock and pilots, so a
decoder change can be checked against a real capture before rebuilding the APK.

## Camera notes

Regular Camera2 sessions on most phones are capped at 30 fps; the 120/240 fps
constrained high-speed sessions refuse manual exposure and only feed
preview/encoder surfaces, so the app stays at 30 fps and works per frame. What
matters is the sensor's minimum exposure (shown in Settings): it should stay
below the board's T. **RAW capture** reads the Bayer mosaic itself: per-row
R/G/B profiles from the 2×2 blocks under the LED, clipped blocks dropped, the
green profile from the Gr rows only (Gr and Gb differ under a narrow-band
LED), rows averaged in pairs before the receiver (a 3000-row frame would cost
three times a 1080p one). Use it when the YUV path shows a peak that never
reaches 255 while nothing decodes: the ISP compresses highlights and hides the
saturation. **Strobe calibration** (Lab) measures the row time per resolution:
send `strobe 2000` to the board, enable the mode, read the row time on the
status line, then `strobe 0`. The row time feeds the exposure-in-rows the
decoder uses; the clock reported by decoded packets (`rows/chip` × T/3) is the
more exact figure if the two disagree.
