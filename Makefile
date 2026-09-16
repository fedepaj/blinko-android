JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
.PHONY: apk
apk:
	JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleDebug -q && cp app/build/outputs/apk/debug/app-debug.apk build/RSLog-android-debug.apk 2>/dev/null || (mkdir -p build && cp app/build/outputs/apk/debug/app-debug.apk build/RSLog-android-debug.apk) && ls -la build/RSLog-android-debug.apk
