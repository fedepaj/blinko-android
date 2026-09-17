# Android

**Stato**: app minima funzionante in `android/Blinko` (Kotlin, Camera2,
core C via NDK/JNI), APK di debug in `build/Blinko-android-debug.apk`
(`make android`). Non ancora provata su un telefono reale (nessun Android
collegato al Mac durante lo sviluppo).

Uso: aprire, concedere la fotocamera, avvicinare il LED a 1–3 cm. Tocco sul
grafico = inverte l'asse di scansione (righe/colonne), pressione lunga =
azzera. La riga di stato mostra `manual=true/false` (capability
MANUAL_SENSOR), esposizione minima, fps, pacchetti/s e righe/chip.

Se `manual=false` il telefono non permette l'esposizione manuale: si usa la
compensazione AE al minimo e i chip risultano sfumati; servirà `chip 60` o più
sulla scheda. Se `rows/chip` resta 0 con contrasto alto, provare l'altro asse.

## Piano originale

Il core C (`core/rs_decoder.c`, `rs_assembler.c`, `rs_proto.h`) è già
portabile e senza dipendenze: su Android si compila con l'NDK e si chiama via
JNI. Cambia solo il guscio (camera + UI).

## Architettura

```
app/
  src/main/cpp/          CMakeLists.txt → libcore (core/*.c) + blinko_jni.cpp
  src/main/java/.../
    CameraController.kt  Camera2: formato YUV_420_888 1080p, esposizione manuale
    FrameProcessor.kt    piano Y → ROI → profilo per riga (RenderScript no; loop Kotlin/NDK)
    Decoder.kt           JNI: decode(profile) → packets; feed(packet) → message
    ui/                  Compose: Live, Console, Lab, Settings
```

## Punti chiave Camera2 (equivalenti AVFoundation)

| iOS | Android Camera2 |
|---|---|
| `setExposureModeCustom(duration, iso)` | `CONTROL_AE_MODE = OFF`, `SENSOR_EXPOSURE_TIME` (ns), `SENSOR_SENSITIVITY` |
| `setFocusModeLocked(lensPosition: 1)` | `CONTROL_AF_MODE = OFF`, `LENS_FOCUS_DISTANCE = 0` (infinito → LED vicino sfocato) |
| esposizione minima | `SENSOR_INFO_EXPOSURE_TIME_RANGE.lower` (spesso 10–100 µs) |
| 420f Y plane | `ImageReader` `YUV_420_888`, `planes[0]` con `rowStride` |
| `videoRotationAngle = 0` | il buffer è già nell'orientamento nativo del sensore |
| 60/120/240 fps | `CONTROL_AE_TARGET_FPS_RANGE` + `StreamConfigurationMap.getHighSpeedVideoFpsRanges` (constrained high speed session per >60 fps) |
| stabilizzazione off | `CONTROL_VIDEO_STABILIZATION_MODE = OFF`, `LENS_OPTICAL_STABILIZATION_MODE = OFF` |

Requisiti: capability `MANUAL_SENSOR` (`REQUEST_AVAILABLE_CAPABILITIES`),
presente sulla maggior parte dei telefoni di fascia media/alta; senza di essa
si può solo usare `CONTROL_AE_EXPOSURE_COMPENSATION` al minimo (funziona
peggio, chip più lunghi).

Attenzione all'asse di scansione: alcuni sensori Android leggono le righe in
verticale nel buffer nativo; il **Lab mode** (strobe + autocorrelazione sui due
assi) risolve il dubbio, esattamente come sull'iPhone.

## JNI

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_..._Decoder_decode(JNIEnv *env, jobject, jfloatArray profile, jint n, jobject outBuffer) {
    jfloat *p = env->GetFloatArrayElements(profile, nullptr);
    rs_packet_t out[64]; rs_dec_stats_t st;
    int k = rs_decode_profile(p, n, &cfg, out, 64, &st);
    // copia out[] in un ByteBuffer direct (struct packed) o in un FloatArray
    env->ReleaseFloatArrayElements(profile, p, JNI_ABORT);
    return k;
}
```
Lo stato dell'assembler (`rs_asm_t`) vive nel nativo; `rs_asm_feed` restituisce
il testo completo come `jstring`.

## Passi

1. progetto Android Studio (Kotlin, Compose, minSdk 26), modulo NDK con
   `core/` (CMake `add_library(rscore STATIC ../../../../core/rs_decoder.c ...)`);
2. Camera2 con esposizione manuale minima, fuoco a infinito, 1080p60,
   `ImageReader` YUV → profilo per riga (stesso algoritmo di `FrameProcessor.swift`);
3. decoder JNI + assembler + console; haptics (`Vibrator`), accelerometro
   (`SensorManager`) per "tieni fermo";
4. Lab mode per `t_row`; tabella dei telefoni testati in `docs/CALIBRATION.md`;
5. test con i frame `.pgm` salvati dall'app iOS: `tools/decode_image.py` è il
   riferimento per confrontare i risultati.
