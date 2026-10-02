JAVA_HOME ?= /Applications/Android Studio.app/Contents/jbr/Contents/Home
.PHONY: apk
# One command per line, so a failed gradle build stops the recipe: in a single `a && cp || (cp) && ls` chain
# the right-hand side also ran when gradle failed, copied the previous build's APK and exited 0.
apk:
	JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleDebug -q
	mkdir -p build
	cp app/build/outputs/apk/debug/app-debug.apk build/Blinko-android-debug.apk
	ls -la build/Blinko-android-debug.apk
