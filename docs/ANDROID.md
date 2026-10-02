# Android app: how it is built

Kotlin with Android views (AppCompat / Material, no Compose), Camera2, and the
shared C receiver from the `core/` submodule behind one JNI file. One activity,
portrait, arm64 only, API 28+. The README covers building and using it; this
page describes the inside.

## Source layout

```
app/src/main/cpp/
  CMakeLists.txt     libblinko.so = blinko_jni.c + the receiver sources of core/ (-O2, RS_DEC_THREADS)
  blinko_jni.c       frame conversion, profiles, the calls into rs_rx / rs_multi, the lock
app/src/main/java/com/federicopaglioni/blinko/
  MainActivity.kt    binds the four tabs (Live, Console, Lab, Settings) to a Session
  Session.kt         the model: owns everything below, handles the remote commands
  CameraController.kt  Camera2 device, capture session, manual exposure / ISO / focus / zoom
  Pipeline.kt        per frame: convert, record, decode, collect messages, publish a Snapshot
  RsCore.kt          the Kotlin side of the JNI bridge
  Recorder.kt        .rsrec writer            ReplayEngine.kt   .rsrec through the receiver
  RemoteServer.kt    TCP server               Console.kt        message history
  Settings.kt        SharedPreferences        MotionMonitor.kt  gyroscope / accelerometer
  LabTab.kt, SettingsTab.kt, Forms.kt, MarkerView.kt, ProfileView.kt, MessageAdapter.kt   views
```

`Session` is created in `MainActivity.onCreate` and closed in `onDestroy`
(camera thread, recorder thread, stats tick and server port are released);
`onResume` / `onPause` open and close the camera.

## Threads

| thread | what runs on it |
|---|---|
| main | UI, every `Session` callback, the remote command handler, the 5 Hz stats broadcast |
| `blinko.camera` | Camera2 callbacks and the whole per-frame pipeline: conversion, recording hand-off, decoding. Urgent-display priority |
| decode workers | short-lived pthreads made by the JNI layer inside one decode call (the receiver's parallel hook): a light's three colour channels and the sync candidates of a profile |
| `blinko.recorder` | writes queued frames to the recording file and ends a recording at its deadline |
| `blinko.replay` | exists while a replay runs: reads a recording and feeds the receiver |
| `blinko.remote`, `blinko.remote.client` | accept loop, one reader per client; each client also has a single-thread writer |

The camera thread publishes an immutable `Snapshot` (stats, profile, packet
spans, tracks) a few times a second and posts messages; the main thread only
reads what it is handed.

The receiver is process-wide state in the native library. The camera thread
decodes with it, the main thread resets it and changes its settings, the
replay thread does both: every native entry point that touches it holds one
mutex for the whole call. A replay pauses the live pipeline first, waits for
the frame in flight, resets the receiver, and resets it again when it ends.

## The JNI layer

`RsCore` is an `object` of `external` functions:

- **convert**: `convertYuvToBgra`, `convertRgbaToBgra`, `convertRawToBgra`
  turn the camera buffer into a packed BGRA image in a direct `ByteBuffer`.
- **decode**: `processFrameBgraMulti` runs the multi-source receiver
  (`rs_multi`: segmentation, tracking, one `rs_rx` per light) and returns the
  tracks; `processFrameBgra` runs the single receiver (`rs_rx`) on the R, G, B
  profiles of the frame's bright region; `profileBgra` computes that profile
  and the frame figures without decoding, for the chart while the multi-source
  receiver owns the frame; `processFrameRaw` is the strobe calibration's path
  on a RAW frame.
- **settings**: `setExposureRows`, `setRowTime`, `setMinContrast`, `reset`.
  The JNI layer keeps the values and installs them again after every reset.
- **messages**: `pollMessage()` returns slot, level, source and the text
  (bytes decoded as UTF-8), from the multi-source receiver's queue or, when
  the single receiver decoded the last frame, from its queue (source 0).

Outputs come back in arrays the caller owns: `stats` (15 floats, indices
`RsCore.ST_*`), `profile`, `packets` (start, end, slot, channel), `tracks`
(id, x, y, radius, mode, packets, messages, group).

## Capture formats and what the receiver gets

Whatever the format, the pipeline makes one BGRA image per frame with every
row kept (rows are the time axis) and about 480 columns (`step` = width / 480).
That image is what the receiver decodes, what the recorder stores and what the
remote `frame` command returns.

| Settings › Resolution | camera stream | conversion | rows of the BGRA image |
|---|---|---|---|
| 1080p, 4K | `RGBA_8888` when the camera offers it, else `YUV_420_888` | columns thinned by `step`; YUV with full-range BT.601 | sensor rows of that stream |
| RAW | `RAW_SENSOR`, largest size | one pixel from a 2×2 Bayer block per 4 sensor rows, G = (Gr + Gb) / 2, scaled by black / white level, block columns thinned by `step` | one per 4 sensor rows |

A resolution the camera lacks falls back to 1080p; `CameraInfo.mode` is the
mode actually running.

With **Multi-source** on, the image goes to the multi-source receiver; when it
tracks no light, and with Multi-source off, the single receiver decodes the
bright region instead. Both are told the exposure in rows and the row time of
this image: `exposure_us / rowUs` and `rowUs`, where `rowUs` is stored per
capture mode and comes from the strobe calibration (defaults: the S21 FE's).

**Strobe calibration mode** decodes nothing useful: it takes the profile and
measures the band period by autocorrelation (`Pipeline.period`), which with
the strobe frequency gives the row time. In 1080p / 4K the profile is the BGRA
image's. In RAW it is computed from the mosaic at full resolution
(`processFrameRaw`), so the measured row time is the sensor's and the stored
`rowUs` is that times 4.

## Remote session

`RemoteServer` listens on TCP port 7777 while **Settings › Remote session** is
on. It binds 127.0.0.1, which `adb forward tcp:7778 tcp:7777` reaches over
USB; with **Allow Wi-Fi (LAN) connections** on it binds every interface.
There is no authentication.

Framing in both directions: `u32` big-endian length, `u8` kind (0 JSON,
1 binary), payload; a binary payload is announced by the JSON before it. An
incoming frame is at most 1 MB, a longer one closes the connection. Commands
(`get`, `set`, `stats`, `messages`, `reset`, `frame`, `record`, `files`,
`pull`, `delete`, `replay`) are handled by `Session.handleRemote` on the main
thread; `set` validates the value before it is stored. Connected clients also
receive `stats` five times a second and every message as it is decoded. The
protocol is the iOS app's, the client is `ios/tools/rslive.py`.

## Recordings and other files

Everything is in the app's internal storage (`Context.filesDir`):

- `recordings/rec-YYYYMMDD-HHMMSS.rsrec`: `"RSREC001"`, a JSON header (size,
  `columnStep`, camera, exposure, note), then per frame a timestamp, gyroscope
  and accelerometer samples and the raw BGRA image. Written by `Recorder`,
  read by `core/tools/rsrec.py`, the iOS app and `ReplayEngine`. They leave
  the phone through the remote session (`record`, `pull`) or the share sheet
  (a `FileProvider`).
- `history.json`: the console's message history and the board ids.
- Settings are in the `blinko` SharedPreferences.
