mod config;
mod correct;
mod flow;
mod swipe;
mod worker;

use config::Config;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JShortArray, JString};
use jni::sys::jlong;
use jni::{EnvUnowned, Outcome};
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tokio::sync::mpsc;
use worker::{Command, EventSink};

const VERSION: &str = env!("CARGO_PKG_VERSION");

pub struct NativeSession {
    cfg: Mutex<Option<Config>>,
    config_error: Mutex<Option<String>>,
    tx: mpsc::UnboundedSender<Command>,
    rx: Mutex<Option<mpsc::UnboundedReceiver<Command>>>,
    events: EventSink,
    thread: Mutex<Option<std::thread::JoinHandle<()>>>,
    started: AtomicBool,
}

impl NativeSession {
    fn new(config_json: &str) -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        let (cfg, error) = match serde_json::from_str::<Config>(config_json) {
            Ok(cfg) => (Some(cfg), None),
            Err(err) => (None, Some(format!("Bad configuration: {err}"))),
        };
        Self {
            cfg: Mutex::new(cfg),
            config_error: Mutex::new(error),
            tx,
            rx: Mutex::new(Some(rx)),
            events: EventSink::new(),
            thread: Mutex::new(None),
            started: AtomicBool::new(false),
        }
    }

    fn start(&self) {
        if self.started.swap(true, Ordering::SeqCst) {
            return;
        }

        if let Some(message) = self.config_error.lock().ok().and_then(|mut e| e.take()) {
            self.events
                .emit(serde_json::json!({"type": "fatal", "message": message}));
            return;
        }

        let Some(cfg) = self.cfg.lock().ok().and_then(|mut c| c.take()) else {
            self.events.emit(
                serde_json::json!({"type": "fatal", "message": "Session already started."}),
            );
            return;
        };

        let Some(rx) = self.rx.lock().ok().and_then(|mut r| r.take()) else {
            return;
        };

        let events = self.events.clone();
        let spawned = std::thread::Builder::new()
            .name("gemini-live".into())
            .spawn(move || {
                let runtime = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                {
                    Ok(rt) => rt,
                    Err(err) => {
                        events.emit(serde_json::json!({
                            "type": "fatal",
                            "message": format!("Could not start network runtime: {err}")
                        }));
                        return;
                    }
                };

                let panic_events = events.clone();
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    runtime.block_on(worker::run(cfg, rx, events));
                }));
                if let Err(payload) = outcome {
                    let message = payload
                        .downcast_ref::<&str>()
                        .map(|s| (*s).to_string())
                        .or_else(|| payload.downcast_ref::<String>().cloned())
                        .unwrap_or_else(|| "unknown panic".to_string());
                    panic_events.emit(serde_json::json!({
                        "type": "fatal",
                        "message": format!("Transcription core stopped: {message}")
                    }));
                }
            });

        match spawned {
            Ok(handle) => {
                if let Ok(mut slot) = self.thread.lock() {
                    *slot = Some(handle);
                }
            }
            Err(err) => self.events.emit(serde_json::json!({
                "type": "fatal",
                "message": format!("Could not start worker thread: {err}")
            })),
        }
    }

    fn stop(&self) {
        let _ = self.tx.send(Command::Stop);
    }

    fn push_audio(&self, pcm: Vec<i16>) {
        let _ = self.tx.send(Command::Audio(pcm));
    }

    fn poll(&self) -> Option<String> {
        self.events.drain()
    }

    fn shutdown(&self) {
        let _ = self.tx.send(Command::Shutdown);
        if let Ok(mut slot) = self.thread.lock() {
            if let Some(handle) = slot.take() {
                let _ = handle.join();
            }
        }
    }
}

unsafe fn session<'a>(handle: jlong) -> Option<&'a NativeSession> {
    if handle == 0 {
        None
    } else {
        (handle as *const NativeSession).as_ref()
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeCreate<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_json: JString<'caller>,
) -> jlong {
    let outcome = unowned_env.with_env(|env| -> Result<jlong, jni::errors::Error> {
        if config_json.is_null() {
            return Ok(0);
        }
        let json = config_json.mutf8_chars(env)?.to_str().to_string();
        Ok(Box::into_raw(Box::new(NativeSession::new(&json))) as jlong)
    });

    match outcome.into_outcome() {
        Outcome::Ok(handle) => handle,
        _ => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeStart<'caller>(
    _unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    if let Some(session) = unsafe { session(handle) } {
        session.start();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeStop<'caller>(
    _unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    if let Some(session) = unsafe { session(handle) } {
        session.stop();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativePushAudio<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    pcm: JShortArray<'caller>,
) {
    let Some(session) = (unsafe { session(handle) }) else {
        return;
    };

    let outcome = unowned_env.with_env(|env| -> Result<(), jni::errors::Error> {
        if pcm.is_null() {
            return Ok(());
        }
        let len = pcm.len(env)?;
        if len <= 0 {
            return Ok(());
        }
        let mut samples = vec![0i16; len as usize];
        pcm.get_region(env, 0, &mut samples)?;
        session.push_audio(samples);
        Ok(())
    });
    let _ = outcome.resolve::<ThrowRuntimeExAndDefault>();
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativePoll<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) -> JString<'caller> {
    let Some(session) = (unsafe { session(handle) }) else {
        return JString::default();
    };

    let outcome = unowned_env.with_env(|env| -> Result<JString<'caller>, jni::errors::Error> {
        match session.poll() {
            Some(text) => JString::from_str(env, text),
            None => Ok(JString::default()),
        }
    });

    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        _ => JString::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeCorrect<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    config_json: JString<'caller>,
    text: JString<'caller>,
    context: JString<'caller>,
) -> JString<'caller> {
    let outcome = unowned_env.with_env(|env| -> Result<JString<'caller>, jni::errors::Error> {
        let config = config_json.mutf8_chars(env)?.to_str().to_string();
        let text = text.mutf8_chars(env)?.to_str().to_string();
        let context = context.mutf8_chars(env)?.to_str().to_string();

        let payload = match serde_json::from_str::<Config>(&config) {
            Ok(cfg) => match correct::run(&cfg, &text, &context) {
                Ok(fixed) => serde_json::json!({"ok": true, "text": fixed}),
                Err(error) => serde_json::json!({"ok": false, "error": error}),
            },
            Err(error) => {
                serde_json::json!({"ok": false, "error": format!("Bad configuration: {error}")})
            }
        };
        JString::from_str(env, payload.to_string())
    });

    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        _ => JString::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeDestroy<'caller>(
    _unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { Box::from_raw(handle as *mut NativeSession) };
    session.shutdown();
}

// ---------------------------------------------------------------------------
// UI flow controller
// ---------------------------------------------------------------------------

struct FlowState {
    flow: flow::Flow,
    effects: VecDeque<String>,
}

pub struct FlowSession {
    state: Mutex<FlowState>,
}

impl FlowSession {
    fn new() -> Self {
        Self {
            state: Mutex::new(FlowState {
                flow: flow::Flow::new(),
                effects: VecDeque::new(),
            }),
        }
    }

    fn event(&self, json: &str) {
        let Ok(event) = serde_json::from_str::<flow::Event>(json) else {
            return;
        };
        if let Ok(mut state) = self.state.lock() {
            let effects = state.flow.handle(event);
            for effect in effects {
                if let Ok(line) = serde_json::to_string(&effect) {
                    state.effects.push_back(line);
                }
            }
        }
    }

    fn poll(&self) -> Option<String> {
        let mut state = self.state.lock().ok()?;
        if state.effects.is_empty() {
            return None;
        }
        let mut out = String::new();
        while let Some(line) = state.effects.pop_front() {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(&line);
        }
        Some(out)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeSwipeDecode<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    path_json: JString<'caller>,
    keys_json: JString<'caller>,
    extra_json: JString<'caller>,
) -> JString<'caller> {
    let outcome = unowned_env.with_env(|env| -> Result<JString<'caller>, jni::errors::Error> {
        let path_raw = path_json.mutf8_chars(env)?.to_str().to_string();
        let keys_raw = keys_json.mutf8_chars(env)?.to_str().to_string();
        let extra_raw = extra_json.mutf8_chars(env)?.to_str().to_string();
        let path: Vec<[f32; 2]> = serde_json::from_str(&path_raw).unwrap_or_default();
        let keys: Vec<swipe::Key> = serde_json::from_str(&keys_raw).unwrap_or_default();
        let extra: Vec<String> = serde_json::from_str(&extra_raw).unwrap_or_default();
        let result = swipe::decode(&path, &keys, &extra);
        let payload = match result {
            Some((word, score)) => serde_json::json!({
                "word": word,
                "score": score
            }),
            None => serde_json::json!({}),
        };
        JString::from_str(env, payload.to_string())
    });
    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        _ => JString::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeFlowCreate<'caller>(
    _unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> jlong {
    Box::into_raw(Box::new(FlowSession::new())) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeFlowEvent<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
    event_json: JString<'caller>,
) {
    if handle == 0 || event_json.is_null() {
        return;
    }
    let outcome = unowned_env.with_env(|env| -> Result<(), jni::errors::Error> {
        let json = event_json.mutf8_chars(env)?.to_str().to_string();
        if let Some(session) = unsafe { (handle as *const FlowSession).as_ref() } {
            session.event(&json);
        }
        Ok(())
    });
    let _ = outcome.resolve::<ThrowRuntimeExAndDefault>();
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeFlowPoll<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) -> JString<'caller> {
    if handle == 0 {
        return JString::default();
    }
    let outcome = unowned_env.with_env(|env| -> Result<JString<'caller>, jni::errors::Error> {
        match unsafe { (handle as *const FlowSession).as_ref() } {
            Some(session) => match session.poll() {
                Some(text) => JString::from_str(env, text),
                None => Ok(JString::default()),
            },
            None => Ok(JString::default()),
        }
    });
    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        _ => JString::default(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeFlowDestroy<'caller>(
    _unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let session = unsafe { Box::from_raw(handle as *mut FlowSession) };
    drop(session);
}

#[no_mangle]
pub extern "system" fn Java_com_k3rn3l_aikeyboard_core_NativeCore_nativeVersion<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    _class: JClass<'caller>,
) -> JString<'caller> {
    let outcome = unowned_env.with_env(|env| -> Result<JString<'caller>, jni::errors::Error> {
        JString::from_str(env, VERSION)
    });
    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        _ => JString::default(),
    }
}
