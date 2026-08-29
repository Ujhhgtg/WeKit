//! axum OpenAI-compatible HTTP server around the llama.cpp engine.
//!
//! Runs inside the exec-isolated inference child ([`crate::exec_process`]) or
//! directly on the desktop CLI's tokio runtime. Single model, single session: a global
//! `tokio::sync::Mutex<Engine>` serializes inference; every request renders
//! its prompt on a fresh context and streams pieces through
//! [`crate::parse::ThinkToolParser`].
//!
//! Failure policy: the cdylib inherits the workspace `panic = "abort"`, so
//! every expected failure (bad request, missing model, engine load error)
//! is an `Err`/HTTP error — nothing here may panic. The engine and its
//! `LlamaBackend` are leaked into `&'static` storage once loaded (the
//! process serving them only ever exits; freeing them has no sound order
//! because `Engine<'a>` borrows the backend it was loaded with).

use std::convert::Infallible;
use std::net::Ipv4Addr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use axum::Json;
use axum::Router;
use axum::body::{Body, Bytes};
use axum::extract::State;
use axum::extract::rejection::JsonRejection;
use axum::http::{StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use llama_cpp_2::list_llama_ggml_backend_devices;
use llama_cpp_2::llama_backend::LlamaBackend;
use serde_json::{Value, json};
use tokio::sync::mpsc;
use tokio_stream::StreamExt;
use tokio_stream::wrappers::UnboundedReceiverStream;

use crate::llama::{Engine, EngineConfig, GenEvent, GenStats};
use crate::logi;
use crate::parse::{OutEvent, THINK_CLOSE, ThinkToolParser};
use crate::template;
use crate::truncate::{effective_max_tokens, prompt_token_budget, truncate_messages};
use crate::wire::{
    ChatRequest, WireFunction, WireTool, WireToolCall, chat_completion_json, effort_to_config,
    message_text, models_json, sse_delta, sse_done, sse_usage,
};

/// 400 body for any non-text message content: this is a text-only model.
const UNSUPPORTED_INPUT: &str = "model does not support image input";
/// Idle-exit check interval (the timeout itself comes from `EngineConfig`).
const IDLE_CHECK_INTERVAL: Duration = Duration::from_secs(30);

/// Everything [`serve`] needs; `bind_port == 0` picks an ephemeral port.
#[derive(Debug, Clone)]
pub struct HttpServerConfig {
    pub engine: EngineConfig,
    pub bind_port: u16,
}

/// Run the server (blocking) on the caller's tokio runtime.
///
/// Loads the model, binds `127.0.0.1:bind_port`, calls `on_ready(port)` once
/// the socket is live (the exec parent learns the port this way), then serves
/// until the process exits — on the idle timeout via
/// [`crate::exec_process::notify_idle_exit`] + `std::process::exit(0)`, otherwise by
/// dying. Returns `Err` for load/bind failures only.
pub async fn serve(
    cfg: HttpServerConfig,
    on_ready: impl FnOnce(u16) + Send + 'static,
) -> Result<(), String> {
    let engine_cfg = cfg.engine.clone();
    let loaded = tokio::task::spawn_blocking(move || load(engine_cfg))
        .await
        .map_err(|e| format!("model load task failed: {e}"))??;
    let Loaded { core, engine } = loaded;

    let listener = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, cfg.bind_port))
        .await
        .map_err(|e| format!("binding 127.0.0.1:{}: {e}", cfg.bind_port))?;
    let port = listener
        .local_addr()
        .map(|addr| addr.port())
        .map_err(|e| format!("reading bound address: {e}"))?;

    let state = Arc::new(ServerState::new(
        core,
        engine,
        port,
        cfg.engine.idle_timeout_secs,
    ));
    let backend = &state.core.backend_info;
    logi!(
        "LocalLlama: model '{}' loaded (requested {}, active {}, devices {:?}, \
         gpu layers {}/{}) on 127.0.0.1:{}",
        state.core.model_id,
        backend.requested,
        backend.active,
        backend.devices,
        backend.gpu_layers,
        backend.total_layers,
        port
    );
    on_ready(port);
    tokio::spawn(idle_watch(state.clone()));

    let app = Router::new()
        .route("/v1/chat/completions", post(chat_completions))
        .route("/v1/models", get(models))
        .route("/health", get(health))
        .with_state(state);
    axum::serve(listener, app)
        .await
        .map_err(|e| format!("server error: {e}"))
}

// ─────────────────────────────────────────────────────────────────────────────
// Server state
// ─────────────────────────────────────────────────────────────────────────────

/// Static resources loaded once and never freed (see the module doc).
struct Core {
    env: &'static minijinja::Environment<'static>,
    /// The GGUF chat template compiled once; every request renders through
    /// it instead of re-parsing the source string.
    template: minijinja::Template<'static, 'static>,
    model_id: String,
    n_ctx: u32,
    backend_info: BackendInfo,
}

struct Loaded {
    core: Core,
    engine: Engine<'static>,
}

/// `/health`'s backend summary, snapshotted once after backend init (the
/// ggml registry is static after that).
struct BackendInfo {
    requested: String,
    active: String,
    /// Only devices participating in the fitted placement (`"Vulkan · Adreno
    /// (TM) 740"` style, plus CPU for partial offload).
    devices: Vec<String>,
    /// Distinct registry names; always contains `"CPU"`.
    available: Vec<String>,
    gpu_layers: u32,
    total_layers: u32,
    fallback_reason: Option<String>,
}

fn summarize_devices(placement: &crate::llama::BackendPlacement) -> BackendInfo {
    let devices = list_llama_ggml_backend_devices();
    let mut available: Vec<String> = devices.iter().map(|d| d.backend.clone()).collect();
    available.sort();
    available.dedup();
    if !available.iter().any(|b| b == "CPU") {
        available.insert(0, "CPU".to_owned());
    }
    BackendInfo {
        requested: placement.requested.as_str().to_owned(),
        active: placement.active.clone(),
        devices: placement.devices.clone(),
        available,
        gpu_layers: placement.gpu_layers,
        total_layers: placement.total_layers,
        fallback_reason: placement.fallback_reason.clone(),
    }
}

/// Load the backend + model and compile the chat template.
///
/// `Box::leak` is deliberate: `Engine<'a>` borrows the `LlamaBackend` it was
/// loaded with and the process that reaches this point serves until exit, so
/// the simplest sound ownership is `&'static` for the backend, the minijinja
/// environment, and the template source.
fn load(engine_cfg: EngineConfig) -> Result<Loaded, String> {
    if engine_cfg.n_ctx == 0 {
        return Err(
            "n_ctx must be set explicitly: 0 would size the KV cache at the model's full \
             training context"
                .to_owned(),
        );
    }
    // llama-cpp-2 only debug-asserts file existence (a dev-profile panic);
    // check it here so every profile fails with a clean Err instead.
    if !std::path::Path::new(&engine_cfg.model_path).exists() {
        return Err(format!(
            "loading model {}: file does not exist",
            engine_cfg.model_path
        ));
    }
    let backend: &'static LlamaBackend = Box::leak(Box::new(
        LlamaBackend::init().map_err(|e| format!("llama backend init: {e:?}"))?,
    ));
    let engine = Engine::load(backend, &engine_cfg)?;
    let backend_info = summarize_devices(engine.placement());
    let env: &'static minijinja::Environment<'static> = Box::leak(Box::new(template::build_env()));
    let source: &'static str = Box::leak(engine.chat_template().to_owned().into_boxed_str());
    let compiled = env
        .template_from_str(source)
        .map_err(|e| format!("compiling chat template: {e}"))?;
    let core = Core {
        env,
        template: compiled,
        model_id: engine.model_id().to_owned(),
        n_ctx: engine_cfg.n_ctx,
        backend_info,
    };
    Ok(Loaded { core, engine })
}

impl Core {
    /// Render the conversation through the precompiled template
    /// (`crate::template::render_prompt` equivalent for the hot path; both
    /// build their context with [`template::template_context`]).
    fn render(
        &self,
        messages: &[crate::wire::WireMessage],
        tools: Option<&[WireTool]>,
        enable_thinking: bool,
    ) -> Result<String, String> {
        let ctx = template::template_context(messages, tools, enable_thinking);
        self.template
            .render(ctx)
            .map_err(|e| format!("rendering chat template: {e}"))
    }
}

/// Shared per-process server state (behind an `Arc`).
struct ServerState {
    core: Core,
    /// The global inference lock: one request generates at a time. `Arc` +
    /// `lock_owned()` so the (movable, `'static`) guard can travel into the
    /// `spawn_blocking` generation task.
    engine: Arc<tokio::sync::Mutex<Engine<'static>>>,
    port: u16,
    idle_timeout_secs: u64,
    started: Instant,
    /// Unix seconds of the last accepted request; powers the idle exit.
    last_request_at: AtomicU64,
    /// Prompt + completion tokens of the last completed request.
    ctx_used: AtomicU64,
    /// Cross-request tokens/s EMA as `f32::to_bits`; `0` = no sample yet.
    tps_ema: AtomicU32,
}

impl ServerState {
    fn new(core: Core, engine: Engine<'static>, port: u16, idle_timeout_secs: u64) -> Self {
        Self {
            idle_timeout_secs,
            core,
            engine: Arc::new(tokio::sync::Mutex::new(engine)),
            port,
            started: Instant::now(),
            last_request_at: AtomicU64::new(unix_now()),
            ctx_used: AtomicU64::new(0),
            tps_ema: AtomicU32::new(0),
        }
    }

    /// Refresh the idle timer. Called at request acceptance, on every
    /// generated piece (both request paths — so a single generation that
    /// legitimately runs longer than the timeout, e.g. max_tokens 8192 at
    /// ~8 tok/s ≈ 17 min, is never classified idle while tokens flow), and
    /// at every completion. The idle exit therefore measures genuine
    /// silence: no token flow and no new request.
    fn touch(&self) {
        self.last_request_at.store(unix_now(), Ordering::Relaxed);
    }

    /// Post-completion bookkeeping: context footprint + tokens/s EMA
    /// (alpha 0.5, seeded by the first request's value).
    fn record(&self, stats: &GenStats) {
        logi!(
            "LocalLlama: request completed: prompt {} tok, completion {} tok, tps {:.1}, ctx {}/{}",
            stats.prompt_tokens,
            stats.completion_tokens,
            stats.tps,
            stats.prompt_tokens + stats.completion_tokens,
            self.core.n_ctx
        );
        self.touch();
        self.ctx_used.store(
            stats.prompt_tokens + stats.completion_tokens,
            Ordering::Relaxed,
        );
        if stats.tps <= 0.0 {
            return;
        }
        let mut bits = self.tps_ema.load(Ordering::Relaxed);
        loop {
            let ema = if bits == 0 {
                stats.tps
            } else {
                f32::from_bits(bits) * 0.5 + stats.tps * 0.5
            };
            match self.tps_ema.compare_exchange_weak(
                bits,
                ema.to_bits(),
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => return,
                Err(current) => bits = current,
            }
        }
    }
}

fn unix_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

// ─────────────────────────────────────────────────────────────────────────────
// Piece → OutEvent pipeline (think-close synthesis)
// ─────────────────────────────────────────────────────────────────────────────

/// Feeds engine pieces through [`ThinkToolParser`] and applies the
/// think-close synthesis contract: the engine force-injects the
/// `"\n</think>\n\n"` tokens once the thinking budget is reached but never
/// streams them, so while the parser is still in the think state this side
/// counts the pieces fed and injects the literal [`THINK_CLOSE`] string into
/// the parser itself at the same token index. Both sides count one piece per
/// token, so the two mechanisms agree; without this, post-budget answer text
/// would be misclassified as reasoning.
struct PieceSplitter {
    parser: ThinkToolParser,
    /// `Some` while a thinking budget is active and the think block is still
    /// open — the only window where synthesis may be needed.
    tracking: Option<ThinkTrack>,
}

struct ThinkTrack {
    fed: u64,
    budget: u64,
    /// Raw think-phase text for detecting the model closing the block itself
    /// (freed as soon as the block closes).
    raw: String,
}

impl PieceSplitter {
    fn new(enable_thinking: bool, budget: Option<u64>) -> Self {
        Self {
            parser: if enable_thinking {
                ThinkToolParser::new()
            } else {
                ThinkToolParser::new_no_think()
            },
            tracking: budget.filter(|_| enable_thinking).map(|budget| ThinkTrack {
                fed: 0,
                budget,
                raw: String::new(),
            }),
        }
    }

    /// Feed one engine piece, returning the parser events it completes.
    fn piece(&mut self, piece: &str) -> Vec<OutEvent> {
        let mut events = Vec::new();
        if let Some(track) = &self.tracking
            && track.fed >= track.budget
        {
            // The budget was reached with the think block still open: the
            // engine has force-fed the close tokens on its side; synthesize
            // the same transition into the parser before this piece.
            events.extend(self.parser.feed(THINK_CLOSE));
            self.tracking = None;
        }
        if let Some(track) = &mut self.tracking {
            track.fed += 1;
            track.raw.push_str(piece);
            if track.raw.contains(THINK_CLOSE) {
                // The model closed the block itself.
                self.tracking = None;
            }
        }
        events.extend(self.parser.feed(piece));
        events
    }

    /// End of stream: flush the parser's held-back tail.
    fn flush(&mut self) -> Vec<OutEvent> {
        self.tracking = None;
        std::mem::take(&mut self.parser).finish()
    }
}

/// `GenEvent::Piece` → channel message for the SSE body task.
enum GenMsg {
    Piece(String),
    Done(GenStats),
}

fn finish_reason(saw_tool_call: bool, completion: u64, max_tokens: u32) -> &'static str {
    if saw_tool_call {
        "tool_calls"
    } else if completion < u64::from(max_tokens) {
        // The EOG token ends generation before the cap.
        "stop"
    } else {
        "length"
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Endpoints
// ─────────────────────────────────────────────────────────────────────────────

fn ok_json(body: String) -> Response {
    ([(header::CONTENT_TYPE, "application/json")], body).into_response()
}

fn error_json(status: StatusCode, message: &str) -> Response {
    let body = json!({ "error": { "message": message } }).to_string();
    (status, [(header::CONTENT_TYPE, "application/json")], body).into_response()
}

async fn models(State(st): State<Arc<ServerState>>) -> Response {
    ok_json(models_json(&[&st.core.model_id]))
}

async fn health(State(st): State<Arc<ServerState>>) -> Response {
    let ema_bits = st.tps_ema.load(Ordering::Relaxed);
    let body = json!({
        "state": "ready",
        "model": st.core.model_id,
        "port": st.port,
        "uptimeSec": st.started.elapsed().as_secs(),
        "rssBytes": rss_bytes(),
        "ctxUsed": st.ctx_used.load(Ordering::Relaxed),
        "ctxTotal": st.core.n_ctx,
        "tokensPerSec": if ema_bits == 0 { 0.0 } else { f32::from_bits(ema_bits) },
        "backend": {
            "requested": st.core.backend_info.requested,
            "active": st.core.backend_info.active,
            "devices": st.core.backend_info.devices,
            "available": st.core.backend_info.available,
            "gpuLayers": st.core.backend_info.gpu_layers,
            "totalLayers": st.core.backend_info.total_layers,
            "fallbackReason": st.core.backend_info.fallback_reason,
        },
    });
    ok_json(body.to_string())
}

/// `VmRSS:` from `/proc/self/status`, in bytes (`0` when unreadable).
fn rss_bytes() -> u64 {
    let Ok(status) = std::fs::read_to_string("/proc/self/status") else {
        return 0;
    };
    status
        .lines()
        .find_map(|line| line.strip_prefix("VmRSS:"))
        .and_then(|value| {
            value
                .trim_end_matches("kB")
                .trim()
                .parse::<u64>()
                .ok()
                .map(|kb| kb * 1024)
        })
        .unwrap_or(0)
}

async fn chat_completions(
    State(st): State<Arc<ServerState>>,
    payload: Result<Json<ChatRequest>, JsonRejection>,
) -> Response {
    st.touch();
    let req = match payload {
        Ok(Json(req)) => req,
        Err(rejection) => return error_json(StatusCode::BAD_REQUEST, &rejection.body_text()),
    };

    // Text-only model: reject any non-string/ non-text-part content shape.
    for message in &req.messages {
        if message_text(message).is_err() {
            return error_json(StatusCode::BAD_REQUEST, UNSUPPORTED_INPUT);
        }
    }

    let effort = effort_to_config(req.reasoning_effort.as_deref());
    let requested_max_tokens = req.max_tokens.or(req.max_completion_tokens).unwrap_or(4096);
    let budget = match prompt_token_budget(st.core.n_ctx, requested_max_tokens) {
        Ok(budget) => budget,
        Err(error) => return error_json(StatusCode::BAD_REQUEST, &error),
    };
    let tools = req.tools.as_deref();
    logi!(
        "LocalLlama: chat request: {} messages, stream {}, max_tokens {requested_max_tokens}, \
         prompt budget {budget}",
        req.messages.len(),
        req.stream.unwrap_or(false)
    );

    // Truncation counting and rendering both need the engine; the same lock
    // then serializes generation (single-session server).
    let engine = st.engine.clone().lock_owned().await;
    let kept = match truncate_messages(
        &req.messages,
        &|ms: &[crate::wire::WireMessage]| engine.count_message_tokens(st.core.env, ms, tools),
        budget,
    ) {
        Ok(truncation) => truncation.messages,
        Err(e) => return error_json(StatusCode::BAD_REQUEST, &e),
    };
    let prompt = match st.core.render(&kept, tools, effort.enable_thinking) {
        Ok(prompt) => prompt,
        Err(e) => return error_json(StatusCode::BAD_REQUEST, &e),
    };
    let prompt_tokens = match engine.count_prompt_tokens(&prompt) {
        Ok(tokens) => tokens,
        Err(error) => return error_json(StatusCode::BAD_REQUEST, &error),
    };
    let max_tokens = match effective_max_tokens(st.core.n_ctx, requested_max_tokens, prompt_tokens)
    {
        Ok(max_tokens) => max_tokens,
        Err(error) => return error_json(StatusCode::BAD_REQUEST, &error),
    };
    logi!(
        "LocalLlama: generation starting: prompt {prompt_tokens} tok, max_tokens {max_tokens}, \
         thinking {}",
        effort.enable_thinking
    );

    let id = format!("chatcmpl-{:x}", unix_nanos());
    let model = st.core.model_id.clone();
    if req.stream.unwrap_or(false) {
        stream_response(st, engine, prompt, id, model, max_tokens, effort)
    } else {
        blocking_response(st, engine, prompt, id, model, max_tokens, effort).await
    }
}

fn stream_response(
    st: Arc<ServerState>,
    engine: tokio::sync::OwnedMutexGuard<Engine<'static>>,
    prompt: String,
    id: String,
    model: String,
    max_tokens: u32,
    effort: crate::wire::EffortConfig,
) -> Response {
    let (tx, rx) = mpsc::unbounded_channel::<GenMsg>();
    let budget = effort.budget_tokens;
    let gen_st = st.clone();
    tokio::task::spawn_blocking(move || {
        let stats = engine.generate(&prompt, max_tokens, budget, |ev| {
            let GenEvent::Piece(piece) = ev;
            // Every piece refreshes the idle timer: a single generation may
            // legitimately run longer than the timeout (max_tokens 8192 at
            // ~8 tok/s ≈ 17 min) and must never be classified idle while
            // tokens are flowing.
            gen_st.touch();
            tx.send(GenMsg::Piece(piece)).is_ok()
        });
        // Err only when the body stream is already gone (client disconnect).
        let _ = tx.send(GenMsg::Done(stats));
    });

    let mut splitter = PieceSplitter::new(effort.enable_thinking, effort.budget_tokens);
    let mut sink = SseSink {
        id,
        model,
        max_tokens,
        saw_tool: false,
        pending_tool: None,
        sent_role: false,
        st,
    };
    let stream = UnboundedReceiverStream::new(rx).map(move |msg| {
        let mut out = String::new();
        if !sink.sent_role {
            out.push_str(&sse_delta(
                &sink.id,
                &sink.model,
                json!({ "role": "assistant" }),
                None,
            ));
            sink.sent_role = true;
        }
        match msg {
            GenMsg::Piece(piece) => {
                for ev in splitter.piece(&piece) {
                    sink.map_event(ev, &mut out);
                }
            }
            GenMsg::Done(stats) => {
                for ev in splitter.flush() {
                    sink.map_event(ev, &mut out);
                }
                out.push_str(&sink.terminal(&stats));
            }
        }
        Ok::<Bytes, Infallible>(Bytes::from(out))
    });

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/event-stream")
        .header(header::CACHE_CONTROL, "no-cache")
        .body(Body::from_stream(stream))
        .unwrap()
}

/// Streaming delta state: maps parser events to OpenAI wire chunks.
struct SseSink {
    id: String,
    model: String,
    max_tokens: u32,
    saw_tool: bool,
    /// `ToolCallStart` + `ToolCallName` awaiting their `ToolCallArg`; the
    /// name and the full arguments object go out in one delta (compatible
    /// with OpenAI streaming, where `function.name` arrives once).
    pending_tool: Option<(usize, String)>,
    sent_role: bool,
    st: Arc<ServerState>,
}

impl SseSink {
    fn delta(&self, body: Value, out: &mut String) {
        out.push_str(&sse_delta(&self.id, &self.model, body, None));
    }

    fn map_event(&mut self, ev: OutEvent, out: &mut String) {
        match ev {
            OutEvent::Reasoning(text) => self.delta(json!({ "reasoning": text }), out),
            OutEvent::Content(text) => self.delta(json!({ "content": text }), out),
            OutEvent::ToolCallStart { index } => self.pending_tool = Some((index, String::new())),
            OutEvent::ToolCallName { index, name } => {
                if self.pending_tool.as_ref().is_some_and(|(i, _)| *i == index) {
                    self.pending_tool = Some((index, name));
                }
            }
            OutEvent::ToolCallArg {
                index,
                arguments_json,
            } => {
                if let Some((i, name)) = self.pending_tool.take()
                    && i == index
                    && !name.is_empty()
                {
                    self.saw_tool = true;
                    self.delta(
                        json!({ "tool_calls": [{
                            "index": i,
                            "type": "function",
                            "function": { "name": name, "arguments": arguments_json },
                        }]}),
                        out,
                    );
                }
            }
            // OpenAI streaming has no per-call close marker.
            OutEvent::ToolCallEnd { .. } => {}
        }
    }

    /// Finish chunk + usage chunk + `[DONE]`, and metric bookkeeping.
    fn terminal(&mut self, stats: &GenStats) -> String {
        let mut out = String::new();
        if let Some((i, name)) = self.pending_tool.take()
            && !name.is_empty()
        {
            // A tool call that opened but never completed: emit it with
            // empty arguments rather than dropping it.
            self.saw_tool = true;
            self.delta(
                json!({ "tool_calls": [{
                    "index": i,
                    "type": "function",
                    "function": { "name": name, "arguments": "{}" },
                }]}),
                &mut out,
            );
        }
        let finish = finish_reason(self.saw_tool, stats.completion_tokens, self.max_tokens);
        out.push_str(&sse_delta(&self.id, &self.model, json!({}), Some(finish)));
        out.push_str(&sse_usage(
            &self.id,
            &self.model,
            stats.prompt_tokens,
            stats.completion_tokens,
        ));
        out.push_str(sse_done());
        self.st.record(stats);
        out
    }
}

async fn blocking_response(
    st: Arc<ServerState>,
    engine: tokio::sync::OwnedMutexGuard<Engine<'static>>,
    prompt: String,
    id: String,
    model: String,
    max_tokens: u32,
    effort: crate::wire::EffortConfig,
) -> Response {
    let budget = effort.budget_tokens;
    let gen_st = st.clone();
    let result = tokio::task::spawn_blocking(move || {
        let mut agg = Aggregate {
            splitter: PieceSplitter::new(effort.enable_thinking, effort.budget_tokens),
            reasoning: String::new(),
            content: String::new(),
            tools: Vec::new(),
        };
        let stats = engine.generate(&prompt, max_tokens, budget, |ev| {
            let GenEvent::Piece(piece) = ev;
            // Per-piece idle refresh, same as the streaming path: in-flight
            // generation is never idle.
            gen_st.touch();
            for out in agg.splitter.piece(&piece) {
                agg.apply(out);
            }
            true
        });
        for out in agg.splitter.flush() {
            agg.apply(out);
        }
        (agg, stats)
    })
    .await;
    let (agg, stats) = match result {
        Ok(joined) => joined,
        Err(e) => {
            return error_json(
                StatusCode::INTERNAL_SERVER_ERROR,
                &format!("generation task failed: {e}"),
            );
        }
    };
    st.record(&stats);
    let finish = finish_reason(!agg.tools.is_empty(), stats.completion_tokens, max_tokens);
    ok_json(chat_completion_json(
        &id,
        &model,
        &agg.content,
        &agg.reasoning,
        &agg.tools,
        finish,
        stats.prompt_tokens,
        stats.completion_tokens,
    ))
}

/// Non-streaming aggregation of one generation.
struct Aggregate {
    splitter: PieceSplitter,
    reasoning: String,
    content: String,
    tools: Vec<WireToolCall>,
}

impl Aggregate {
    fn apply(&mut self, ev: OutEvent) {
        match ev {
            OutEvent::Reasoning(text) => self.reasoning.push_str(&text),
            OutEvent::Content(text) => self.content.push_str(&text),
            OutEvent::ToolCallStart { index } => self.tools.push(WireToolCall {
                index: Some(index),
                function: WireFunction {
                    name: String::new(),
                    arguments: None,
                },
            }),
            OutEvent::ToolCallName { index, name } => {
                if let Some(call) = self.tools.iter_mut().rev().find(|c| c.index == Some(index)) {
                    call.function.name = name;
                }
            }
            OutEvent::ToolCallArg {
                index,
                arguments_json,
            } => {
                if let Some(call) = self.tools.iter_mut().rev().find(|c| c.index == Some(index)) {
                    call.function.arguments = Some(arguments_json);
                }
            }
            OutEvent::ToolCallEnd { .. } => {}
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Idle exit
// ─────────────────────────────────────────────────────────────────────────────

async fn idle_watch(st: Arc<ServerState>) {
    let mut tick = tokio::time::interval(IDLE_CHECK_INTERVAL);
    loop {
        tick.tick().await;
        let last = st.last_request_at.load(Ordering::Relaxed);
        if unix_now().saturating_sub(last) > st.idle_timeout_secs {
            logi!("LocalLlama: idle timeout reached, exiting child server");
            // app_process mode reports the exit over the control pipe first;
            // the direct CLI just exits.
            crate::exec_process::notify_idle_exit();
            std::process::exit(0);
        }
    }
}

fn unix_nanos() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos()
}
