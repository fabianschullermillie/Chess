# Inkcalidraw (prototype)

Native Android stylus drawing app that reads and writes `.excalidraw` files.

- Wet ink: Jetpack Ink (`androidx.ink`) front-buffered, low-latency rendering with motion prediction.
- Dry ink: a Kotlin port of perfect-freehand 1.2.0 with Excalidraw's exact freedraw options, so strokes
  render identically on Android and in Excalidraw (verified byte-identical SVG paths against `@excalidraw/excalidraw` 0.18.1).
- Unknown elements and fields are preserved verbatim on save.
- S Pen draws, side button / eraser tip erases, fingers pan & pinch-zoom.

Build: `./gradlew assembleDebug` (needs Android SDK 36, JDK 17+).
