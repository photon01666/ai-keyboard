use serde::{Deserialize, Serialize};

/// Authoritative listen -> polish -> done state machine.
///
/// Kotlin forwards user/session events here and executes the returned effects.
/// It keeps no rules of its own about when Done is visible, when to polish, or
/// when to stop.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Idle,
    Connecting,
    Listening,
    Polishing,
    Finishing,
    Error,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum Event {
    Start {
        #[serde(default = "default_polish")]
        polish: bool,
    },
    Ready,
    Interim,
    Final,
    Speech {
        active: bool,
    },
    Done,
    Stop,
    PolishFinished {
        ok: bool,
    },
    Closed,
    Hidden,
    Error {
        message: String,
    },
}

fn default_polish() -> bool {
    true
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum Effect {
    ClearField,
    ShowPanel,
    StartSession,
    HidePanel,
    ShowListening,
    ShowDone,
    ShowStop,
    ShowPolishing,
    Polish,
    StopSession,
    CancelSession,
    Hint { text: String },
    Toast { text: String },
    ShowError { text: String },
}

fn hint(text: &str) -> Effect {
    Effect::Hint {
        text: text.to_string(),
    }
}

fn toast(text: &str) -> Effect {
    Effect::Toast {
        text: text.to_string(),
    }
}

#[derive(Debug)]
pub struct Flow {
    phase: Phase,
    polish_enabled: bool,
    done_visible: bool,
    speech_during_polish: bool,
    final_during_polish: bool,
    pending_hide: bool,
    restart_pending: bool,
    stop_requested: bool,
    pending_error: Option<String>,
    session_closed: bool,
}

impl Default for Flow {
    fn default() -> Self {
        Self {
            phase: Phase::Idle,
            polish_enabled: true,
            done_visible: false,
            speech_during_polish: false,
            final_during_polish: false,
            pending_hide: false,
            restart_pending: false,
            stop_requested: false,
            pending_error: None,
            session_closed: false,
        }
    }
}

impl Flow {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn phase(&self) -> Phase {
        self.phase
    }

    fn reset_for_start(&mut self, polish: bool) {
        self.phase = Phase::Connecting;
        self.polish_enabled = polish;
        self.done_visible = false;
        self.speech_during_polish = false;
        self.final_during_polish = false;
        self.pending_hide = false;
        self.restart_pending = false;
        self.stop_requested = false;
        self.pending_error = None;
        self.session_closed = false;
    }

    pub fn handle(&mut self, event: Event) -> Vec<Effect> {
        use Effect::*;

        match event {
            Event::Start { polish } => match self.phase {
                Phase::Idle | Phase::Error => {
                    self.reset_for_start(polish);
                    vec![ClearField, ShowPanel, StartSession]
                }
                Phase::Finishing => {
                    self.restart_pending = true;
                    vec![hint("Starting…")]
                }
                _ => Vec::new(),
            },

            Event::Ready => {
                if self.phase == Phase::Connecting {
                    self.phase = Phase::Listening;
                    vec![ShowListening]
                } else {
                    Vec::new()
                }
            }

            Event::Interim => Vec::new(),

            Event::Final => match self.phase {
                Phase::Listening => {
                    self.done_visible = true;
                    vec![ShowDone]
                }
                Phase::Polishing => {
                    self.final_during_polish = true;
                    Vec::new()
                }
                _ => Vec::new(),
            },

            Event::Speech { active } => {
                if !active {
                    return Vec::new();
                }
                match self.phase {
                    Phase::Listening => {
                        if self.done_visible {
                            self.done_visible = false;
                            vec![ShowStop]
                        } else {
                            Vec::new()
                        }
                    }
                    Phase::Polishing => {
                        self.speech_during_polish = true;
                        Vec::new()
                    }
                    _ => Vec::new(),
                }
            }

            Event::Done => {
                if self.phase != Phase::Listening || !self.done_visible {
                    return Vec::new();
                }
                self.done_visible = false;

                if self.polish_enabled {
                    self.phase = Phase::Polishing;
                    self.speech_during_polish = false;
                    self.final_during_polish = false;
                    self.pending_hide = false;
                    self.stop_requested = false;
                    self.pending_error = None;
                    self.session_closed = false;
                    vec![ShowPolishing, Polish]
                } else {
                    self.phase = Phase::Finishing;
                    vec![StopSession, HidePanel, hint("Tap AI to talk")]
                }
            }

            Event::Stop => match self.phase {
                Phase::Listening | Phase::Connecting => {
                    self.phase = Phase::Finishing;
                    vec![StopSession, HidePanel, hint("Tap AI to talk")]
                }
                Phase::Polishing => {
                    // The UI disables Stop during polish. Do not let any other
                    // caller cancel the correction mid-flight.
                    self.stop_requested = true;
                    vec![hint("Polishing — one moment")]
                }
                Phase::Error => {
                    self.phase = Phase::Idle;
                    vec![HidePanel, hint("Tap AI to talk")]
                }
                _ => Vec::new(),
            },

            Event::PolishFinished { ok } => {
                if self.phase != Phase::Polishing {
                    return Vec::new();
                }

                if let Some(message) = self.pending_error.take() {
                    self.phase = Phase::Error;
                    self.done_visible = false;
                    self.pending_hide = false;
                    return vec![ShowError { text: message }];
                }

                if self.pending_hide {
                    self.phase = Phase::Idle;
                    self.done_visible = false;
                    self.pending_hide = false;
                    self.final_during_polish = false;
                    let mut effects = Vec::new();
                    if !self.session_closed {
                        effects.push(CancelSession);
                    }
                    effects.push(HidePanel);
                    effects.push(hint("Tap AI to talk"));
                    self.session_closed = false;
                    return effects;
                }

                if self.stop_requested {
                    self.stop_requested = false;
                    self.phase = Phase::Finishing;
                    let mut effects = Vec::new();
                    if !self.session_closed {
                        effects.push(StopSession);
                    }
                    effects.push(HidePanel);
                    effects.push(hint(if ok { "Polished ✓" } else { "Tap AI to talk" }));
                    if !ok {
                        effects.push(toast("Polish failed — stopped listening"));
                    }
                    self.done_visible = false;
                    return effects;
                }

                if self.speech_during_polish {
                    self.phase = Phase::Listening;
                    self.done_visible = self.final_during_polish;
                    self.speech_during_polish = false;
                    self.final_during_polish = false;
                    let mut effects = vec![ShowListening];
                    if self.done_visible {
                        effects.push(ShowDone);
                    }
                    return effects;
                }

                self.phase = Phase::Finishing;
                self.done_visible = false;
                let mut effects = Vec::new();
                if !self.session_closed {
                    effects.push(StopSession);
                }
                effects.push(HidePanel);
                effects.push(hint(if ok { "Polished ✓" } else { "Tap AI to talk" }));
                if !ok {
                    effects.push(toast("Polish failed — stopped listening"));
                }
                effects
            }

            Event::Closed => {
                self.session_closed = true;
                match self.phase {
                    Phase::Polishing => Vec::new(),

                    Phase::Finishing => {
                        self.phase = Phase::Idle;
                        self.session_closed = false;
                        if self.restart_pending {
                            self.restart_pending = false;
                            let polish = self.polish_enabled;
                            self.reset_for_start(polish);
                            vec![ClearField, ShowPanel, StartSession]
                        } else {
                            vec![HidePanel, hint("Tap AI to talk")]
                        }
                    }

                    Phase::Error => Vec::new(),

                    Phase::Idle => {
                        self.session_closed = false;
                        Vec::new()
                    }

                    Phase::Listening | Phase::Connecting => {
                        self.phase = Phase::Idle;
                        self.session_closed = false;
                        vec![HidePanel, hint("Tap AI to talk")]
                    }
                }
            }

            Event::Hidden => match self.phase {
                Phase::Polishing => {
                    self.pending_hide = true;
                    Vec::new()
                }
                Phase::Listening | Phase::Connecting | Phase::Error => {
                    self.phase = Phase::Idle;
                    self.done_visible = false;
                    self.session_closed = false;
                    vec![CancelSession, HidePanel, hint("Tap AI to talk")]
                }
                _ => Vec::new(),
            },

            Event::Error { message } => match self.phase {
                Phase::Polishing => {
                    // Let the correction finish; show the error afterward.
                    self.pending_error = Some(message);
                    Vec::new()
                }
                Phase::Finishing | Phase::Idle => Vec::new(),
                _ => {
                    self.phase = Phase::Error;
                    self.done_visible = false;
                    vec![ShowError { text: message }]
                }
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn started() -> Flow {
        let mut flow = Flow::new();
        assert_eq!(
            flow.handle(Event::Start { polish: true }),
            vec![
                Effect::ClearField,
                Effect::ShowPanel,
                Effect::StartSession
            ]
        );
        assert_eq!(flow.handle(Event::Ready), vec![Effect::ShowListening]);
        assert_eq!(flow.phase(), Phase::Listening);
        flow
    }

    #[test]
    fn start_clears_field_before_starting() {
        let mut flow = Flow::new();
        assert_eq!(
            flow.handle(Event::Start { polish: true }),
            vec![
                Effect::ClearField,
                Effect::ShowPanel,
                Effect::StartSession
            ]
        );
        assert_eq!(flow.phase(), Phase::Connecting);
    }

    #[test]
    fn done_appears_after_final_and_late_interim_keeps_it() {
        let mut flow = started();
        assert_eq!(flow.handle(Event::Final), vec![Effect::ShowDone]);
        assert_eq!(flow.handle(Event::Interim), Vec::<Effect>::new());
        assert_eq!(flow.handle(Event::Done), vec![Effect::ShowPolishing, Effect::Polish]);
        assert_eq!(flow.phase(), Phase::Polishing);
    }

    #[test]
    fn second_utterance_restores_done_after_speech_start() {
        let mut flow = started();
        flow.handle(Event::Final);
        assert_eq!(flow.handle(Event::Speech { active: true }), vec![Effect::ShowStop]);
        assert_eq!(flow.handle(Event::Final), vec![Effect::ShowDone]);
        assert_eq!(
            flow.handle(Event::Done),
            vec![Effect::ShowPolishing, Effect::Polish]
        );
    }

    #[test]
    fn final_during_polish_brings_done_back() {
        let mut flow = started();
        flow.handle(Event::Final);
        flow.handle(Event::Done);
        flow.handle(Event::Speech { active: true });
        flow.handle(Event::Final);
        assert_eq!(
            flow.handle(Event::PolishFinished { ok: true }),
            vec![Effect::ShowListening, Effect::ShowDone]
        );
        assert_eq!(flow.phase(), Phase::Listening);
    }

    #[test]
    fn polish_success_stops_and_hides() {
        let mut flow = started();
        flow.handle(Event::Final);
        flow.handle(Event::Done);
        assert_eq!(
            flow.handle(Event::PolishFinished { ok: true }),
            vec![
                Effect::StopSession,
                Effect::HidePanel,
                Effect::Hint {
                    text: "Polished ✓".into()
                }
            ]
        );
        assert_eq!(flow.phase(), Phase::Finishing);
        assert_eq!(
            flow.handle(Event::Closed),
            vec![
                Effect::HidePanel,
                Effect::Hint {
                    text: "Tap AI to talk".into()
                }
            ]
        );
        assert_eq!(flow.phase(), Phase::Idle);
    }

    #[test]
    fn hidden_during_polish_defers_cancel() {
        let mut flow = started();
        flow.handle(Event::Final);
        flow.handle(Event::Done);
        assert_eq!(flow.handle(Event::Hidden), Vec::<Effect>::new());
        assert_eq!(
            flow.handle(Event::PolishFinished { ok: true }),
            vec![
                Effect::CancelSession,
                Effect::HidePanel,
                Effect::Hint {
                    text: "Tap AI to talk".into()
                }
            ]
        );
        assert_eq!(flow.phase(), Phase::Idle);
    }

    #[test]
    fn restart_is_queued_while_finishing() {
        let mut flow = started();
        assert_eq!(
            flow.handle(Event::Stop),
            vec![
                Effect::StopSession,
                Effect::HidePanel,
                Effect::Hint {
                    text: "Tap AI to talk".into()
                }
            ]
        );
        assert_eq!(flow.phase(), Phase::Finishing);
        assert_eq!(
            flow.handle(Event::Start { polish: true }),
            vec![Effect::Hint {
                text: "Starting…".into()
            }]
        );
        assert_eq!(
            flow.handle(Event::Closed),
            vec![
                Effect::ClearField,
                Effect::ShowPanel,
                Effect::StartSession
            ]
        );
        assert_eq!(flow.phase(), Phase::Connecting);
    }

    #[test]
    fn polish_disabled_makes_done_close_immediately() {
        let mut flow = Flow::new();
        flow.handle(Event::Start { polish: false });
        flow.handle(Event::Ready);
        flow.handle(Event::Final);
        assert_eq!(
            flow.handle(Event::Done),
            vec![
                Effect::StopSession,
                Effect::HidePanel,
                Effect::Hint {
                    text: "Tap AI to talk".into()
                }
            ]
        );
        assert_eq!(flow.phase(), Phase::Finishing);
    }

    #[test]
    fn done_before_final_is_ignored() {
        let mut flow = started();
        assert_eq!(flow.handle(Event::Done), Vec::<Effect>::new());
        assert_eq!(flow.phase(), Phase::Listening);
    }

    #[test]
    fn error_during_polish_is_shown_after_polish() {
        let mut flow = started();
        flow.handle(Event::Final);
        flow.handle(Event::Done);
        assert_eq!(
            flow.handle(Event::Error {
                message: "boom".into()
            }),
            Vec::<Effect>::new()
        );
        assert_eq!(
            flow.handle(Event::PolishFinished { ok: true }),
            vec![Effect::ShowError {
                text: "boom".into()
            }]
        );
        assert_eq!(flow.phase(), Phase::Error);
    }

    #[test]
    fn hidden_while_listening_cancels() {
        let mut flow = started();
        assert_eq!(
            flow.handle(Event::Hidden),
            vec![
                Effect::CancelSession,
                Effect::HidePanel,
                Effect::Hint {
                    text: "Tap AI to talk".into()
                }
            ]
        );
        assert_eq!(flow.phase(), Phase::Idle);
    }

    #[test]
    fn error_keeps_panel_visible_after_close() {
        let mut flow = started();
        assert_eq!(
            flow.handle(Event::Error {
                message: "no key".into()
            }),
            vec![Effect::ShowError {
                text: "no key".into()
            }]
        );
        assert_eq!(flow.handle(Event::Closed), Vec::<Effect>::new());
        assert_eq!(flow.phase(), Phase::Error);
        assert_eq!(
            flow.handle(Event::Start { polish: true }),
            vec![
                Effect::ClearField,
                Effect::ShowPanel,
                Effect::StartSession
            ]
        );
        assert_eq!(flow.phase(), Phase::Connecting);
    }
}
