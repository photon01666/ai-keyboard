# AI Keyboard

A voice-first Android keyboard built around **Gemini Live transcription**.

The normal surface is a Gboard-style QWERTY keyboard with an **AI** pill in the top-right.
Tapping AI clears the current text field and turns the keyboard into a full-screen
listening surface. Speak naturally; text is committed as you pause. When an utterance
is finalized, the main button becomes **DONE**. Tapping Done runs a Gemini polish pass
(grammar, punctuation, names, homophones), then closes the session automatically.

There is no timed auto-stop and no timed polish. Done is the trigger.

## What is Rust and what is Kotlin

Almost all of the logic is Rust:

- `rust/src/lib.rs` — JNI surface
- `rust/src/worker.rs` — rustls WebSocket, Gemini Live protocol, 16 kHz PCM chunking,
  local VAD, reconnects, event queue
- `rust/src/correct.rs` — the one-shot Gemini repair/polish request
- `rust/src/config.rs` — session configuration

Kotlin is only the Android shell:

- `AiKeyboardService` — `InputMethodService`, commits text, clears the field
- `GboardView` — canvas-drawn QWERTY, AI/Fix toolbar
- `AiPanelView` — listening/polishing surface
- `AudioCapture` — `AudioRecord` at 16 kHz mono PCM16
- `LiveClient` / `TextFixer` — JNI polling and background repair bridge
- `ScreenTextService` — optional Accessibility-based screen context

## Build

Prerequisites:

- JDK 21 (Gradle 8.9 does not run on JDK 27)
- Android SDK with platform 34 and build-tools 34
- Rust with `aarch64-linux-android` target and `cargo-ndk`
- Android NDK 27

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.k3rn3l.aikeyboard/.ime.AiKeyboardService
adb shell ime set com.k3rn3l.aikeyboard/.ime.AiKeyboardService
```

The Gradle `cargoBuild` task cross-compiles the Rust core into
`app/src/main/jniLibs/arm64-v8a/` before packaging. Only `arm64-v8a` is configured;
add another `abiFilters` entry and a matching `cargo ndk -t` target for other ABIs.

Machine-specific paths live in `gradle.properties` (`org.gradle.java.home`) and
`local.properties` (`sdk.dir`).

## Use

1. Open **AI Keyboard**, grant the microphone, enable/select the IME.
2. Add a Gemini API key in **Settings** (stored in app-private SharedPreferences).
3. Focus any text field. The keyboard shows QWERTY with **AI** at the top-right.
4. Tap **AI** — the current field is cleared and listening starts.
5. Speak. Interim words show in the transcript card; finalized text is committed.
6. When you stop, the big button becomes **DONE**. Tap it to polish and close.
   - **STOP LISTENING** (before any speech) aborts without polishing.
   - While polishing, the button is locked, a spinner replaces the live dot, and the
     Back key is consumed. Hiding the keyboard defers teardown until the polish ends.
   - If you resume speaking while a polish is in flight, the keyboard stays live and
     offers Done again after the next utterance.
7. The **Fix** pill in the QWERTY toolbar repairs the whole field on demand.

## Settings

- Gemini API key and transcribe model (`gemini-3.5-transcribe-live` by default)
- Language hints (empty = automatic multilingual detection)
- SMART vs verbatim transcription
- Custom vocabulary
- Fast local finalization (hybrid VAD) and silence time
- Repair model (`gemini-3.6-flash` by default) and Polish-on-Done toggle
- Optional Accessibility screen context

## Cost

Gemini 3.5 Transcribe Live is roughly **$0.009/min** (about **$0.54/hour**) on the paid
tier: $0.005/min audio input + $0.004/min text output. The repair pass uses Gemini 3.6
Flash at $0.75/$3.75 per 1M input/output tokens, typically **$0.001–0.002 per polish**.

Billing accrues while the Live session is open, including silence. Since sessions now
end after Done, an average message costs well under a cent plus a fraction of a cent
for the polish. Leaving the AI panel open without speaking still incurs audio cost.

## Privacy

- Microphone audio is streamed to Google's Gemini Live API.
- The API key stays in app-private storage on the device.
- No audio is written to disk.
- Screen context is optional and only read on demand for Fix/Polish. Password fields
  and hidden nodes are skipped. It can be blocked by a device admin; the app shows
  "Blocked" in that case. Polish still works from the editor text.
- The field is cleared on every AI start by design.

## Known limits

- x86_64/emulator ABI is not configured.
- The API key is stored with plain app-private SharedPreferences, not a Keystore-wrapped
  store.
- The canvas keyboard does not expose per-key Accessibility nodes.
- Gemini Live sessions have a 10-minute server limit; the Rust worker reconnects before
  that, but the Done flow normally ends sessions much sooner.
