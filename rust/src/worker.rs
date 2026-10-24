use crate::config::Config;
use base64::engine::general_purpose::STANDARD as B64;
use base64::Engine as _;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

pub const SAMPLE_RATE: usize = 16_000;
const CHUNK_SAMPLES: usize = 1_600; // 100 ms, as recommended by the Live API
const FRAME_MS: u32 = 20;
const MAX_SESSION: Duration = Duration::from_secs(9 * 60 + 20); // server limit is 10 min
const MAX_PENDING: usize = 48_000; // 3 s of audio while the session is being set up

const WS_URL: &str = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

pub enum Command {
    Audio(Vec<i16>),
    Stop,
    Shutdown,
}

pub enum End {
    Stopped,
    Reconnect(&'static str),
    Failed(String),
}

#[derive(Clone)]
pub struct EventSink {
    queue: std::sync::Arc<std::sync::Mutex<std::collections::VecDeque<String>>>,
}

impl EventSink {
    pub fn new() -> Self {
        Self {
            queue: std::sync::Arc::new(std::sync::Mutex::new(
                std::collections::VecDeque::new(),
            )),
        }
    }

    pub fn emit(&self, value: Value) {
        if let Ok(line) = serde_json::to_string(&value) {
            if let Ok(mut q) = self.queue.lock() {
                if q.len() > 256 {
                    q.pop_front();
                }
                q.push_back(line);
            }
        }
    }

    pub fn drain(&self) -> Option<String> {
        let mut q = self.queue.lock().ok()?;
        if q.is_empty() {
            return None;
        }
        let mut out = String::new();
        while let Some(line) = q.pop_front() {
            if !out.is_empty() {
                out.push('\n');
            }
            out.push_str(&line);
        }
        Some(out)
    }
}

pub async fn run(
    cfg: Config,
    mut rx: mpsc::UnboundedReceiver<Command>,
    sink: EventSink,
) {
    // rustls 0.23 requires an explicitly installed process crypto provider.
    let _ = rustls::crypto::ring::default_provider().install_default();

    if let Err(message) = cfg.validate() {
        sink.emit(json!({"type": "fatal", "message": message}));
        return;
    }

    let mut failures = 0u32;
    loop {
        // A stop may have been queued while we were disconnected.
        while let Ok(cmd) = rx.try_recv() {
            match cmd {
                Command::Stop | Command::Shutdown => {
                    sink.emit(json!({"type": "stopped"}));
                    return;
                }
                Command::Audio(_) => {}
            }
        }

        sink.emit(json!({"type": "connecting"}));
        match run_session(&cfg, &mut rx, &sink).await {
            End::Stopped => {
                sink.emit(json!({"type": "stopped"}));
                return;
            }
            End::Reconnect(reason) => {
                failures = 0;
                sink.emit(json!({"type": "reconnecting", "reason": reason}));
            }
            End::Failed(message) => {
                failures += 1;
                let fatal = failures >= 4 || is_auth_error(&message);
                sink.emit(json!({"type": "error", "message": friendly(&message)}));
                if fatal {
                    sink.emit(json!({"type": "fatal", "message": friendly(&message)}));
                    return;
                }
                let delay = Duration::from_millis(500 * 2u64.pow(failures.min(3)));
                sink.emit(json!({"type": "waiting", "ms": delay.as_millis()}));
                if wait_or_stop(&mut rx, delay).await {
                    sink.emit(json!({"type": "stopped"}));
                    return;
                }
            }
        }
    }
}

async fn wait_or_stop(rx: &mut mpsc::UnboundedReceiver<Command>, delay: Duration) -> bool {
    let deadline = tokio::time::sleep(delay);
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            _ = &mut deadline => return false,
            cmd = rx.recv() => match cmd {
                Some(Command::Stop) | Some(Command::Shutdown) | None => return true,
                Some(Command::Audio(_)) => {}
            }
        }
    }
}

fn is_auth_error(message: &str) -> bool {
    message.contains("401") || message.contains("403") || message.contains("API key")
}

fn friendly(message: &str) -> String {
    let lower = message.to_ascii_lowercase();
    if lower.contains("401") || lower.contains("403") {
        "Gemini rejected the API key. Open AI Keyboard settings and check it.".to_string()
    } else if lower.contains("429") {
        "Gemini rate limit reached. Wait a moment and try again.".to_string()
    } else if lower.contains("timed out") || lower.contains("timeout") {
        "Could not reach Gemini. Check your connection.".to_string()
    } else if lower.contains("dns") || lower.contains("connect") {
        format!("Network problem: {message}")
    } else {
        message.to_string()
    }
}

/// Opens one Live API session and pumps audio until it ends.
async fn run_session(
    cfg: &Config,
    rx: &mut mpsc::UnboundedReceiver<Command>,
    sink: &EventSink,
) -> End {
    let url = format!("{}?key={}", WS_URL, urlencode(cfg.api_key.trim()));
    let connect = tokio::time::timeout(Duration::from_secs(12), tokio_tungstenite::connect_async(url));
    tokio::pin!(connect);

    let ws = loop {
        tokio::select! {
            result = &mut connect => {
                match result {
                    Ok(Ok((ws, _))) => break ws,
                    Ok(Err(err)) => return End::Failed(err.to_string()),
                    Err(_) => return End::Failed("Connection timed out".into()),
                }
            }
            cmd = rx.recv() => {
                match cmd {
                    Some(Command::Stop) | Some(Command::Shutdown) | None => {
                        sink.emit(json!({"type": "stopped"}));
                        return End::Stopped;
                    }
                    Some(Command::Audio(_)) => {}
                }
            }
        }
    };

    let (mut write, mut read) = ws.split();

    if let Err(err) = write.send(Message::text(setup_json(cfg))).await {
        return End::Failed(err.to_string());
    }

    let deadline = tokio::time::sleep(MAX_SESSION);
    tokio::pin!(deadline);

    let mut ready = false;
    let mut pending: Vec<i16> = Vec::new();
    let mut chunk: Vec<i16> = Vec::with_capacity(CHUNK_SAMPLES);
    let mut vad = Vad::new(cfg);
    let mut last_level = Instant::now();
    let mut last_final = String::new();
    let mut frame_count = 0u64;

    loop {
        tokio::select! {
            incoming = read.next() => {
                match incoming {
                    Some(Ok(Message::Text(text))) => {
                        if let Some(end) = handle_server(&text, sink, &mut ready, &mut last_final) {
                            return end;
                        }
                    }
                    Some(Ok(Message::Binary(bytes))) => {
                        if let Ok(text) = std::str::from_utf8(&bytes) {
                            if let Some(end) = handle_server(text, sink, &mut ready, &mut last_final) {
                                return end;
                            }
                        }
                    }
                    Some(Ok(Message::Ping(payload))) => {
                        let _ = write.send(Message::Pong(payload)).await;
                    }
                    Some(Ok(Message::Close(frame))) => {
                        let reason = frame
                            .map(|f| f.reason.to_string())
                            .filter(|r| !r.is_empty())
                            .unwrap_or_else(|| "connection closed".into());
                        return End::Failed(reason);
                    }
                    Some(Err(err)) => return End::Failed(err.to_string()),
                    None => return End::Failed("connection closed".into()),
                    _ => {}
                }
            }

            cmd = rx.recv() => {
                match cmd {
                    Some(Command::Audio(samples)) => {
                        if !ready {
                            if pending.len() + samples.len() <= MAX_PENDING {
                                pending.extend_from_slice(&samples);
                            }
                            continue;
                        }
                        if let Err(end) = ingest_audio(
                            &samples, &mut chunk, &mut vad, &mut write, sink,
                            &mut last_level, &mut frame_count, cfg,
                        ).await {
                            return end;
                        }
                    }
                    Some(Command::Stop) | Some(Command::Shutdown) | None => {
                        // Flush whatever is queued, then ask the server to finalize.
                        if ready {
                            flush_chunk(&mut chunk, &mut write).await.ok();
                            let _ = write.send(Message::text(json!({
                                "realtimeInput": {"audioStreamEnd": true}
                            }).to_string())).await;

                            // Give the server a moment to emit the final transcript.
                            let grace = tokio::time::sleep(Duration::from_millis(1_200));
                            tokio::pin!(grace);
                            loop {
                                tokio::select! {
                                    _ = &mut grace => break,
                                    incoming = read.next() => {
                                        match incoming {
                                            Some(Ok(Message::Text(text))) => {
                                                if let Some(End::Failed(m)) =
                                                    handle_server(&text, sink, &mut ready, &mut last_final)
                                                {
                                                    sink.emit(json!({"type": "error", "message": m}));
                                                    break;
                                                }
                                            }
                                            Some(Ok(Message::Binary(bytes))) => {
                                                if let Ok(text) = std::str::from_utf8(&bytes) {
                                                    if let Some(End::Failed(m)) =
                                                        handle_server(text, sink, &mut ready, &mut last_final)
                                                    {
                                                        sink.emit(json!({"type": "error", "message": m}));
                                                        break;
                                                    }
                                                }
                                            }
                                            _ => break,
                                        }
                                    }
                                }
                            }
                            let _ = write.close().await;
                        }
                        sink.emit(json!({"type": "stopped"}));
                        return End::Stopped;
                    }
                }
            }

            _ = &mut deadline => {
                sink.emit(json!({"type": "reconnecting", "reason": "session limit"}));
                return End::Reconnect("session limit");
            }
        }

        // Audio captured before setup completed is replayed once we are ready.
        if ready && !pending.is_empty() {
            let buffered = std::mem::take(&mut pending);
            if let Err(end) = ingest_audio(
                &buffered, &mut chunk, &mut vad, &mut write, sink,
                &mut last_level, &mut frame_count, cfg,
            ).await {
                return end;
            }
        }
    }
}

/// Splits incoming PCM into 100 ms chunks, runs local VAD and forwards audio.
async fn ingest_audio(
    samples: &[i16],
    chunk: &mut Vec<i16>,
    vad: &mut Vad,
    write: &mut futures_util::stream::SplitSink<
        tokio_tungstenite::WebSocketStream<
            tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
        >,
        Message,
    >,
    sink: &EventSink,
    last_level: &mut Instant,
    frame_count: &mut u64,
    cfg: &Config,
) -> Result<(), End> {
    chunk.extend_from_slice(samples);

    // 20 ms frames for level metering and local VAD.
    let mut offset = 0usize;
    let frame_len = SAMPLE_RATE / 1000 * FRAME_MS as usize;
    while offset + frame_len <= samples.len() {
        let frame = &samples[offset..offset + frame_len];
        *frame_count += 1;
        let rms = rms(frame);
        let voiced = rms > cfg.vad_threshold;

        if last_level.elapsed() >= Duration::from_millis(50) {
            let level = ((rms * 9.0).sqrt()).min(1.0);
            sink.emit(json!({"type": "level", "value": level}));
            *last_level = Instant::now();
        }

        if let Some(event) = vad.update(voiced, FRAME_MS) {
            match event {
                VadEvent::SpeechStart => sink.emit(json!({"type": "speech", "active": true})),
                VadEvent::SpeechEnd => {
                    sink.emit(json!({"type": "speech", "active": false}));
                    if cfg.hybrid_vad {
                        flush_chunk(chunk, write).await?;
                        write
                            .send(Message::text(
                                json!({"realtimeInput": {"audioStreamEnd": true}}).to_string(),
                            ))
                            .await
                            .map_err(|e| End::Failed(e.to_string()))?;
                    }
                }
            }
        }
        offset += frame_len;
    }

    while chunk.len() >= CHUNK_SAMPLES {
        let data: Vec<i16> = chunk.drain(..CHUNK_SAMPLES).collect();
        send_audio(&data, write).await?;
    }
    Ok(())
}

async fn flush_chunk(
    chunk: &mut Vec<i16>,
    write: &mut futures_util::stream::SplitSink<
        tokio_tungstenite::WebSocketStream<
            tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
        >,
        Message,
    >,
) -> Result<(), End> {
    if chunk.is_empty() {
        return Ok(());
    }
    let data = std::mem::take(chunk);
    send_audio(&data, write).await
}

async fn send_audio(
    samples: &[i16],
    write: &mut futures_util::stream::SplitSink<
        tokio_tungstenite::WebSocketStream<
            tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>,
        >,
        Message,
    >,
) -> Result<(), End> {
    let mut bytes = Vec::with_capacity(samples.len() * 2);
    for sample in samples {
        bytes.extend_from_slice(&sample.to_le_bytes());
    }
    let payload = json!({
        "realtimeInput": {
            "audio": {
                "data": B64.encode(bytes),
                "mimeType": "audio/pcm;rate=16000"
            }
        }
    });
    write
        .send(Message::text(payload.to_string()))
        .await
        .map_err(|e| End::Failed(e.to_string()))
}

fn rms(frame: &[i16]) -> f32 {
    if frame.is_empty() {
        return 0.0;
    }
    let sum: f64 = frame
        .iter()
        .map(|s| {
            let v = *s as f64 / 32768.0;
            v * v
        })
        .sum();
    (sum / frame.len() as f64).sqrt() as f32
}

fn setup_json(cfg: &Config) -> String {
    let mut transcription = json!({
        "languageCodes": cfg.language_codes,
    });
    if cfg.smart() {
        transcription["mode"] = json!("SMART");
    }
    if !cfg.vocabulary.is_empty() {
        transcription["customVocabulary"] = json!(cfg.vocabulary);
    }

    json!({
        "setup": {
            "model": format!("models/{}", cfg.model_id()),
            "generationConfig": {
                "responseModalities": ["TEXT"]
            },
            "inputAudioTranscription": transcription,
            "realtimeInputConfig": {
                "automaticActivityDetection": {
                    "disabled": false
                }
            }
        }
    })
    .to_string()
}

/// Returns an early termination reason when the server asks us to stop/reconnect.
fn handle_server(
    raw: &str,
    sink: &EventSink,
    ready: &mut bool,
    last_final: &mut String,
) -> Option<End> {
    let value: Value = serde_json::from_str(raw).ok()?;

    if value.get("setupComplete").is_some() {
        *ready = true;
        sink.emit(json!({"type": "ready", "model": ""}));
        return None;
    }

    if let Some(go_away) = value.get("goAway") {
        let time_left = go_away
            .get("timeLeft")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string();
        return Some(End::Reconnect(if time_left.is_empty() {
            "server goAway"
        } else {
            "server goAway"
        }));
    }

    if let Some(error) = value.get("error") {
        let message = error
            .get("message")
            .and_then(|v| v.as_str())
            .unwrap_or("Gemini returned an error")
            .to_string();
        return Some(End::Failed(message));
    }

    if let Some(content) = value.get("serverContent") {
        if let Some(interim) = content
            .get("interimInputTranscription")
            .or_else(|| content.get("interim_input_transcription"))
            .and_then(|t| t.get("text"))
            .and_then(|t| t.as_str())
        {
            if !interim.is_empty() {
                sink.emit(json!({"type": "interim", "text": interim}));
            }
        }

        if let Some(final_text) = content
            .get("inputTranscription")
            .or_else(|| content.get("input_transcription"))
            .and_then(|t| t.get("text"))
            .and_then(|t| t.as_str())
        {
            let trimmed = final_text.trim();
            if !trimmed.is_empty() && trimmed != last_final.trim() {
                *last_final = final_text.to_string();
                sink.emit(json!({"type": "final", "text": final_text}));
            }
        }

        if content.get("turnComplete").and_then(|v| v.as_bool()) == Some(true) {
            last_final.clear();
            sink.emit(json!({"type": "turn_end"}));
        }
    }

    if value.get("errorMessage").is_some() {
        return Some(End::Failed(raw.to_string()));
    }

    None
}

enum VadEvent {
    SpeechStart,
    SpeechEnd,
}

struct Vad {
    silence_ms: u32,
    silence_for: u32,
    speaking: bool,
    speech_ms: u32,
    enabled: bool,
}

impl Vad {
    fn new(cfg: &Config) -> Self {
        Self {
            silence_ms: cfg.vad_silence_ms as u32,
            silence_for: 0,
            speaking: false,
            speech_ms: 0,
            enabled: cfg.hybrid_vad,
        }
    }

    fn update(&mut self, voiced: bool, frame_ms: u32) -> Option<VadEvent> {
        if !self.enabled {
            return None;
        }
        if voiced {
            self.speech_ms = self.speech_ms.saturating_add(frame_ms);
            self.silence_for = 0;
            if !self.speaking && self.speech_ms >= 120 {
                self.speaking = true;
                return Some(VadEvent::SpeechStart);
            }
        } else if self.speaking {
            self.silence_for = self.silence_for.saturating_add(frame_ms);
            if self.silence_for >= self.silence_ms {
                self.speaking = false;
                self.speech_ms = 0;
                self.silence_for = 0;
                return Some(VadEvent::SpeechEnd);
            }
        } else {
            self.speech_ms = 0;
        }
        None
    }
}

fn urlencode(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for byte in input.bytes() {
        match byte {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(byte as char)
            }
            _ => out.push_str(&format!("%{byte:02X}")),
        }
    }
    out
}
