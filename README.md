# AI Keyboard

A voice-first Android keyboard built around **Gemini Live transcription**.

The normal surface is a Gboard-style QWERTY keyboard with an **AI** button in the top
center. Tapping AI clears the current field and starts listening. Speak naturally;
text is committed as you pause. When an utterance ends (800 ms of silence by
default), a Gemini polish pass runs automatically for grammar, punctuation, names
and homophones, then the session closes. You can also tap **STOP LISTENING** to
cancel.

Other features:

- Inline autofill (passwords/logins via Bitwarden and other providers)
- Paste chip in the suggestion strip
- Swipe-to-type (glide typing)
- Auto number pad for PIN/number fields
- Caps lock via double-tap shift
- Adjustable keyboard and letter size

## Build

Prerequisites: JDK 21, Android SDK (platform 34), Rust with `aarch64-linux-android`
target + `cargo-ndk`, Android NDK 27.

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell ime enable com.k3rn3l.aikeyboard/.ime.AiKeyboardService
adb shell ime set com.k3rn3l.aikeyboard/.ime.AiKeyboardService
```

Run the flow tests:

```bash
cd rust && cargo test
```

## About

See [ABOUT.md](ABOUT.md).

## License

MIT
