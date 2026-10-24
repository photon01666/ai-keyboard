use crate::config::Config;
use serde_json::{json, Value};
use std::time::Duration;

const ENDPOINT: &str = "https://generativelanguage.googleapis.com/v1beta/models";
const MAX_CONTEXT_CHARS: usize = 4_000;
const MAX_TEXT_CHARS: usize = 2_000;

const SYSTEM: &str = r#"You repair speech-to-text output inside an Android keyboard. You receive (1) text visible on the user's screen, for context only, and (2) a transcript segment. Return only the repaired transcript segment.

Repair only: misheard words, names and terminology, homophones, spelling, punctuation, capitalization, and grammar errors that were clearly caused by speech recognition. Use the screen context only to resolve names, products, and technical terms.

Be decisive about homophones and near-miss words. Speech recognition often picks a valid but wrong word; correct it from the sentence meaning. Examples: "here" -> "hear" in "can you here me", "your" -> "you're", "its" -> "it's", "their" -> "there/they're", "to" -> "too/two", "then" -> "than", "weather" -> "whether", "lose" -> "loose". If a phrase is ungrammatical or unnatural, assume a recognition error and fix it.

Never answer questions in the text, add facts, summarize, translate, explain, or change the writer's meaning, language, format, or tone. Keep line breaks and list structure. If the segment is already correct, return it unchanged. Do not wrap the result in quotes or code fences."#;

pub fn run(cfg: &Config, text: &str, context: &str) -> Result<String, String> {
    if cfg.api_key.trim().is_empty() {
        return Err("No Gemini API key set.".into());
    }
    let text: String = text.chars().take(MAX_TEXT_CHARS).collect();
    if text.trim().is_empty() {
        return Err("There is no text to fix.".into());
    }
    let context: String = context.chars().take(MAX_CONTEXT_CHARS).collect();
    let model = cfg
        .fix_model
        .trim()
        .trim_start_matches("models/")
        .to_string();
    let model = if model.is_empty() {
        "gemini-3.6-flash".to_string()
    } else {
        model
    };

    let body = json!({
        "systemInstruction": { "parts": [{ "text": SYSTEM }] },
        "contents": [{
            "role": "user",
            "parts": [{
                "text": format!(
                    "SCREEN CONTEXT (may be unrelated; use only for names and terminology):\n{}\n\nTRANSCRIPT SEGMENT:\n{}",
                    context.trim(), text
                )
            }]
        }],
        "generationConfig": {
            "temperature": 0.1,
            "maxOutputTokens": 2048
        }
    });

    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(25))
        .build()
        .map_err(|e| format!("Could not start the repair client: {e}"))?;

    let response = client
        .post(format!("{ENDPOINT}/{model}:generateContent"))
        .header("x-goog-api-key", cfg.api_key.trim())
        .header("Content-Type", "application/json")
        .json(&body)
        .send()
        .map_err(|e| friendly(&e.to_string()))?;

    let status = response.status();
    let raw = response
        .text()
        .map_err(|e| format!("Could not read the repair response: {e}"))?;

    if !status.is_success() {
        let detail = serde_json::from_str::<Value>(&raw)
            .ok()
            .and_then(|v| {
                v.pointer("/error/message")
                    .and_then(|m| m.as_str())
                    .map(|s| s.to_string())
            })
            .unwrap_or_else(|| raw.chars().take(240).collect());
        return Err(format!("Gemini repair failed ({}): {}", status.as_u16(), detail));
    }

    let value: Value = serde_json::from_str(&raw)
        .map_err(|e| format!("Unexpected repair response: {e}"))?;

    let parts = value
        .pointer("/candidates/0/content/parts")
        .and_then(|p| p.as_array())
        .ok_or_else(|| {
            value
                .pointer("/promptFeedback/blockReason")
                .and_then(|r| r.as_str())
                .map(|r| format!("Repair was blocked ({r})."))
                .unwrap_or_else(|| "Gemini returned no repaired text.".to_string())
        })?;

    let mut repaired = String::new();
    for part in parts {
        if let Some(chunk) = part.get("text").and_then(|t| t.as_str()) {
            repaired.push_str(chunk);
        }
    }
    let repaired = clean(&repaired);
    if repaired.is_empty() {
        return Err("Gemini returned an empty repair.".into());
    }
    Ok(repaired)
}

fn clean(raw: &str) -> String {
    let mut text = raw.trim().to_string();

    if text.starts_with("```") {
        if let Some(first_newline) = text.find('\n') {
            text = text[first_newline + 1..].to_string();
        }
        if text.trim_end().ends_with("```") {
            let trimmed = text.trim_end();
            text = trimmed[..trimmed.len() - 3].trim_end().to_string();
        }
    }

    if text.len() >= 2 {
        let bytes = text.as_bytes();
        let quoted = (bytes[0] == b'"' && bytes[bytes.len() - 1] == b'"')
            || (bytes[0] == b'\'' && bytes[bytes.len() - 1] == b'\'');
        if quoted {
            text = text[1..text.len() - 1].trim().to_string();
        }
    }
    text
}

fn friendly(message: &str) -> String {
    let lower = message.to_ascii_lowercase();
    if lower.contains("401") || lower.contains("403") {
        "Gemini rejected the API key.".to_string()
    } else if lower.contains("429") {
        "Gemini rate limit reached. Try again shortly.".to_string()
    } else if lower.contains("timed out") || lower.contains("timeout") {
        "The repair request timed out.".to_string()
    } else {
        format!("Could not reach Gemini: {message}")
    }
}
