# rslog-android

RSLog Viewer for Android (Kotlin, Camera2, NDK). Manual exposure at the
sensor minimum, focus at infinity, 1080p at the highest frame rate, RGBA
capture when supported (YUV otherwise), shared C receiver from the git
submodule `core/` via JNI.

```sh
git submodule update --init
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
make apk        # build/RSLog-android-debug.apk
```
