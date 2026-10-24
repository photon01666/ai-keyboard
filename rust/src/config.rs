use serde::Deserialize;

fn default_model() -> String {
    "gemini-3.5-transcribe-live".to_string()
}
fn default_mode() -> String {
    "SMART".to_string()
}
fn default_silence_ms() -> u64 {
    800
}
fn default_threshold() -> f32 {
    0.014
}
fn default_true() -> bool {
    true
}
fn default_fix_model() -> String {
    "gemini-3.6-flash".to_string()
}

#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    #[serde(default)]
    pub api_key: String,
    #[serde(default = "default_model")]
    pub model: String,
    #[serde(default)]
    pub language_codes: Vec<String>,
    #[serde(default = "default_mode")]
    pub mode: String,
    #[serde(default)]
    pub vocabulary: Vec<String>,
    #[serde(default = "default_true")]
    pub hybrid_vad: bool,
    #[serde(default = "default_silence_ms")]
    pub vad_silence_ms: u64,
    #[serde(default = "default_threshold")]
    pub vad_threshold: f32,
    #[serde(default = "default_fix_model")]
    pub fix_model: String,
}

impl Config {
    pub fn model_id(&self) -> String {
        self.model.trim().trim_start_matches("models/").to_string()
    }

    pub fn smart(&self) -> bool {
        self.mode.eq_ignore_ascii_case("SMART")
    }

    /// Reasons the configuration cannot open a session, if any.
    pub fn validate(&self) -> Result<(), String> {
        if self.api_key.trim().is_empty() {
            return Err("No Gemini API key set. Open AI Keyboard settings to add one.".into());
        }
        if self.model_id().is_empty() {
            return Err("No transcription model configured.".into());
        }
        Ok(())
    }
}
