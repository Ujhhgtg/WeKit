//! llama-cpp-2 engine wrapper: model load with device selection,
//! fresh-context-per-request generation, and thinking-budget enforcement.
//!
//! The engine is deliberately thin: it streams raw token pieces
//! ([`GenEvent::Piece`]) and leaves `<think>`/`<tool_call>` parsing to the
//! server layer ([`crate::parse::ThinkToolParser`]). Every request runs on a
//! brand-new [`LlamaContext`] (llama.cpp no longer context-shifts; the server
//! must budget the prompt itself via [`crate::truncate`]).
//!
//! Failure policy: the cdylib inherits the workspace `panic = "abort"`, so
//! expected failure paths return `Result<_, String>` (missing model file,
//! backend device not available, context-creation config errors — probed once
//! at [`Engine::load`] time). Inside [`Engine::generate`] a failure can only
//! mean a broken invariant (prompt tokenization of an already-rendered
//! template, or a decode overrun past `n_ctx` that server-side truncation
//! exists to prevent); those panic loudly rather than degrading to a silent
//! empty response.

use std::num::NonZeroU32;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use llama_cpp_2::context::LlamaContext;
use llama_cpp_2::context::params::{KvCacheType, LlamaContextParams};
use llama_cpp_2::list_llama_ggml_backend_devices;
use llama_cpp_2::llama_backend::LlamaBackend;
use llama_cpp_2::llama_batch::LlamaBatch;
use llama_cpp_2::model::params::LlamaModelParams;
use llama_cpp_2::model::{AddBos, LlamaModel};
use llama_cpp_2::sampling::LlamaSampler;

use crate::parse::THINK_CLOSE;
use crate::template::render_prompt;
use crate::wire::{WireMessage, WireTool};

/// Prompt decode batch capacity; also llama.cpp's default `n_batch`.
const BATCH_TOKENS: usize = 512;
/// Offload every layer (`n_gpu_layers` counts down from the output side, so
/// any value ≥ a real model's layer count means "all").
const ALL_GPU_LAYERS: u32 = 99;

/// Compute backend selection for model loading.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Backend {
    /// Whatever llama.cpp picks on its own (all layers offloaded, no device pin).
    Auto,
    /// CPU only (`n_gpu_layers = 0`).
    Cpu,
    /// First Vulkan device.
    Vulkan,
    /// First OpenCL device.
    Opencl,
}

impl Backend {
    /// Parse the CLI/config spelling (`auto|cpu|vulkan|opencl`).
    pub fn parse(s: &str) -> Option<Backend> {
        match s.to_ascii_lowercase().as_str() {
            "auto" => Some(Backend::Auto),
            "cpu" => Some(Backend::Cpu),
            "vulkan" => Some(Backend::Vulkan),
            "opencl" => Some(Backend::Opencl),
            _ => None,
        }
    }

    /// Canonical lowercase spelling (mirrors [`Backend::parse`]).
    pub fn as_str(&self) -> &'static str {
        match self {
            Backend::Auto => "auto",
            Backend::Cpu => "cpu",
            Backend::Vulkan => "vulkan",
            Backend::Opencl => "opencl",
        }
    }

    /// The ggml backend registry name for GPU variants (llama.cpp registers
    /// `"Vulkan"` and `"OpenCL"`); empty for the variants that never pin a device.
    const fn ggml_name(self) -> &'static str {
        match self {
            Backend::Vulkan => "Vulkan",
            Backend::Opencl => "OpenCL",
            Backend::Auto | Backend::Cpu => "",
        }
    }
}

/// Everything needed to load and run one engine.
#[derive(Debug, Clone)]
pub struct EngineConfig {
    pub model_path: String,
    /// Context size; `0` keeps the model's own default.
    pub n_ctx: u32,
    pub threads: i32,
    pub backend: Backend,
    pub temp: f32,
    pub top_p: f32,
    pub top_k: i32,
    pub idle_timeout_secs: u64,
}

/// Size of the top frequency cluster: how many cores run at the fastest
/// `cpuinfo_max_freq` — the performance-core count to size `threads` with.
///
/// Empty input → 1; an all-zero cluster-info read → half the cores (a sane
/// default for big.LITTLE layouts where the little cluster usually outnumbers
/// the big one), never fewer than 1.
pub fn perf_core_count(max_freqs: &[u64]) -> usize {
    if max_freqs.is_empty() {
        return 1;
    }
    let max = *max_freqs.iter().max().unwrap();
    if max == 0 {
        return (max_freqs.len() / 2).max(1);
    }
    max_freqs.iter().filter(|&&f| f == max).count()
}

/// Auto-detect the generation thread count: read every
/// `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq` and take the
/// performance-core cluster size; when cpufreq is unavailable, fall back to
/// the parallelism Rust sees. Never fewer than 1.
pub fn detect_threads() -> i32 {
    let mut freqs = Vec::new();
    if let Ok(cpus) = std::fs::read_dir("/sys/devices/system/cpu") {
        for entry in cpus.flatten() {
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
                continue;
            };
            let Some(id) = name.strip_prefix("cpu") else {
                continue;
            };
            if !id.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            if let Ok(freq) = std::fs::read_to_string(entry.path().join("cpufreq/cpuinfo_max_freq"))
                && let Ok(value) = freq.trim().parse::<u64>()
            {
                freqs.push(value);
            }
        }
    }
    let threads = if freqs.is_empty() {
        std::thread::available_parallelism().map_or(1, |n| n.get())
    } else {
        perf_core_count(&freqs)
    };
    threads.min(i32::MAX as usize) as i32
}

/// One streamed generation event. Raw token pieces only — thinking/tool-call
/// parsing is combined in the server layer.
#[derive(Debug, Clone)]
pub enum GenEvent {
    /// A decoded token piece.
    Piece(String),
}

/// Per-request generation accounting.
#[derive(Debug, Clone, Copy)]
pub struct GenStats {
    pub prompt_tokens: u64,
    pub completion_tokens: u64,
    /// Decoding throughput for this request (`n_eval / t_eval` from
    /// `ctx.timings()`); the server keeps the cross-request EMA.
    pub tps: f32,
}

/// A loaded model plus its sampling parameters.
///
/// Borrows the [`LlamaBackend`] it was loaded with (`new_context` requires
/// proof of backend initialization even though contexts only borrow the model).
#[derive(Debug)]
pub struct Engine<'a> {
    model: LlamaModel,
    backend: &'a LlamaBackend,
    n_ctx: u32,
    threads: i32,
    temp: f32,
    top_p: f32,
    top_k: i32,
    template: String,
    model_id: String,
}

/// Model-load parameters implementing the [`Backend`] device policy.
fn model_params(backend: Backend) -> Result<LlamaModelParams, String> {
    let base = LlamaModelParams::default().with_use_mmap(true);
    match backend {
        Backend::Auto => Ok(base.with_n_gpu_layers(ALL_GPU_LAYERS)),
        Backend::Cpu => Ok(base.with_n_gpu_layers(0)),
        Backend::Vulkan | Backend::Opencl => {
            let want = backend.ggml_name();
            let devices = list_llama_ggml_backend_devices();
            let Some(dev) = devices
                .iter()
                .find(|d| d.backend.eq_ignore_ascii_case(want))
            else {
                let have: Vec<&str> = devices.iter().map(|d| d.backend.as_str()).collect();
                return Err(format!(
                    "backend {want} not available; have [{}]",
                    have.join(", ")
                ));
            };
            base.with_n_gpu_layers(ALL_GPU_LAYERS)
                .with_devices(&[dev.index])
                .map_err(|e| format!("selecting {want} device {}: {e}", dev.index))
        }
    }
}

/// Per-request context parameters (Q8_0 KV cache; quantized V requires flash
/// attention, which llama.cpp enables by default via `AUTO` on the CPU and
/// Vulkan backends this project ships). `no_perf = false` because llama.cpp
/// now defaults it to `true`, which would zero `ctx.timings()` — and with it
/// the server's tokens/s accounting.
fn context_params(n_ctx: u32, threads: i32) -> LlamaContextParams {
    LlamaContextParams::default()
        .with_n_ctx(NonZeroU32::new(n_ctx))
        .with_n_threads(threads)
        .with_n_threads_batch(threads)
        .with_type_k(KvCacheType::Q8_0)
        .with_type_v(KvCacheType::Q8_0)
        .with_no_perf(false)
}

impl<'a> Engine<'a> {
    /// Load a model and validate its context configuration.
    ///
    /// A throwaway probe context is created (and dropped) so KV-quantization
    /// / flash-attention mismatches and unusable `n_ctx` fail here with a
    /// clean `Err` instead of aborting the inference child on its first
    /// request (workspace `panic = "abort"`).
    pub fn load(backend: &'a LlamaBackend, cfg: &EngineConfig) -> Result<Engine<'a>, String> {
        let params = model_params(cfg.backend)?;
        let model = LlamaModel::load_from_file(backend, &cfg.model_path, &params)
            .map_err(|e| format!("loading model {}: {e}", cfg.model_path))?;
        drop(
            model
                .new_context(backend, context_params(cfg.n_ctx, cfg.threads))
                .map_err(|e| format!("creating probe context: {e}"))?,
        );
        let template = model
            .chat_template(None)
            .map_err(|e| format!("reading chat template: {e}"))?
            .to_string()
            .map_err(|e| format!("chat template is not UTF-8: {e}"))?;
        let model_id = Path::new(&cfg.model_path)
            .file_stem()
            .and_then(|s| s.to_str())
            .ok_or_else(|| format!("model path has no file stem: {}", cfg.model_path))?
            .to_owned();
        Ok(Engine {
            model,
            backend,
            n_ctx: cfg.n_ctx,
            threads: cfg.threads,
            temp: cfg.temp,
            top_p: cfg.top_p,
            top_k: cfg.top_k,
            template,
            model_id,
        })
    }

    /// The GGUF file stem (the id reported by `/v1/models`).
    pub fn model_id(&self) -> &str {
        &self.model_id
    }

    /// The GGUF-embedded chat template (rendered by [`crate::template`]).
    pub fn chat_template(&self) -> &str {
        &self.template
    }

    /// Token count of the fully rendered conversation (thinking-on variant,
    /// `add_generation_prompt` included) — the count the truncation planner
    /// budgets with. Render or tokenize failures return `usize::MAX`, which
    /// drives `truncate_messages` into its own `Err` path instead of
    /// silently mis-truncating.
    pub fn count_message_tokens(
        &self,
        env: &minijinja::Environment,
        messages: &[WireMessage],
        tools: Option<&[WireTool]>,
    ) -> usize {
        let prompt = match render_prompt(env, &self.template, messages, tools, true) {
            Ok(p) => p,
            Err(_) => return usize::MAX,
        };
        self.model
            .str_to_token(&prompt, AddBos::Always)
            .map(|tokens| tokens.len())
            .unwrap_or(usize::MAX)
    }

    /// Generate one turn on a fresh context.
    ///
    /// `thinking_budget` bounds the `<think>` phase: once the completion
    /// reaches the budget while the model has not closed the block itself,
    /// the `"\n</think>\n\n"` tokens are force-fed through decode + sampler
    /// (so the model continues in answer mode) but — per the controller
    /// ruling — are **not** sent to `on_event`. The callback returns `false`
    /// to abort; the stats of everything generated so far are returned.
    pub fn generate<F: FnMut(GenEvent) -> bool>(
        &self,
        prompt: &str,
        max_tokens: u32,
        thinking_budget: Option<u64>,
        mut on_event: F,
    ) -> GenStats {
        let mut ctx = self.new_ctx();
        let tokens = self
            .model
            .str_to_token(prompt, AddBos::Always)
            .unwrap_or_else(|e| panic!("prompt tokenization failed: {e}"));

        // Prompt phase: decode in n_batch-sized chunks; only the final token
        // of the final chunk produces logits (the canonical 0.1.154 shape).
        let mut batch = LlamaBatch::new(BATCH_TOKENS, 1);
        let mut n_cur: i32 = 0;
        for chunk in tokens.chunks(BATCH_TOKENS) {
            let is_last_chunk = n_cur as usize + chunk.len() == tokens.len();
            batch.clear();
            for (i, token) in chunk.iter().enumerate() {
                let is_last = is_last_chunk && i + 1 == chunk.len();
                batch
                    .add(*token, n_cur + i as i32, &[0], is_last)
                    .expect("token fits the batch by construction");
            }
            ctx.decode(&mut batch)
                .unwrap_or_else(|e| panic!("prompt decode failed: {e}"));
            n_cur += chunk.len() as i32;
        }

        let mut decoder = encoding_rs::UTF_8.new_decoder();
        // Per-request seed: identical requests should not deterministically
        // repeat (an agent retry loop would then never make progress).
        let seed = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos() as u32;
        let mut sampler = LlamaSampler::chain(
            [
                LlamaSampler::top_k(self.top_k),
                LlamaSampler::top_p(self.top_p, 1),
                LlamaSampler::temp(self.temp),
                LlamaSampler::dist(seed),
            ],
            false,
        );

        // Marker tracking without parsing the stream: while a thinking
        // budget is active and the think block still open, accumulate pieces
        // and watch for the model closing the block itself; the string is
        // freed once the block is closed.
        let mut generated = String::new();
        let mut think_closed = false;
        let mut completion: u64 = 0;

        while completion < u64::from(max_tokens) {
            if let Some(budget) = thinking_budget
                && !think_closed
                && completion >= budget
            {
                completion += self.force_think_close(
                    &mut ctx,
                    &mut batch,
                    &mut sampler,
                    &mut decoder,
                    &mut n_cur,
                );
                think_closed = true;
                generated = String::new();
            }

            let token = sampler.sample(&ctx, batch.n_tokens() - 1);
            sampler.accept(token);

            // End of generation: the EOG token is sampled but never decoded.
            if self.model.is_eog_token(token) {
                break;
            }

            let piece = self
                .model
                .token_to_piece(token, &mut decoder, true, None)
                .unwrap_or_else(|e| panic!("detokenizing sampled token failed: {e}"));
            if thinking_budget.is_some() && !think_closed {
                generated.push_str(&piece);
                think_closed = generated.contains(THINK_CLOSE);
            }

            let keep_going = on_event(GenEvent::Piece(piece));

            batch.clear();
            batch
                .add(token, n_cur, &[0], true)
                .expect("single token fits the batch by construction");
            n_cur += 1;
            ctx.decode(&mut batch)
                .unwrap_or_else(|e| panic!("decode failed: {e}"));
            completion += 1;

            if !keep_going {
                break;
            }
        }

        let timings = ctx.timings();
        let t_eval_ms = timings.t_eval_ms();
        let tps = if t_eval_ms > 0.0 {
            (f64::from(timings.n_eval()) * 1000.0 / t_eval_ms) as f32
        } else {
            0.0
        };
        GenStats {
            prompt_tokens: tokens.len() as u64,
            completion_tokens: completion,
            tps,
        }
    }

    /// Fresh per-request context; cannot fail after the load-time probe.
    fn new_ctx(&self) -> LlamaContext<'_> {
        self.model
            .new_context(self.backend, context_params(self.n_ctx, self.threads))
            .unwrap_or_else(|e| panic!("context creation failed: {e}"))
    }

    /// Feed the `"\n</think>\n\n"` tokens through decode + sampler so the
    /// model continues in answer mode, and return how many tokens were fed.
    /// Pieces are decoded (to keep the UTF-8 decoder consistent) but not
    /// emitted.
    fn force_think_close(
        &self,
        ctx: &mut LlamaContext<'_>,
        batch: &mut LlamaBatch<'_>,
        sampler: &mut LlamaSampler,
        decoder: &mut encoding_rs::Decoder,
        n_cur: &mut i32,
    ) -> u64 {
        let close_tokens = self
            .model
            .str_to_token(THINK_CLOSE, AddBos::Never)
            .unwrap_or_else(|e| panic!("tokenizing think-close failed: {e}"));
        for token in &close_tokens {
            let _piece = self
                .model
                .token_to_piece(*token, decoder, true, None)
                .unwrap_or_else(|e| panic!("detokenizing think-close failed: {e}"));
            batch.clear();
            batch
                .add(*token, *n_cur, &[0], true)
                .expect("single token fits the batch by construction");
            ctx.decode(&mut *batch)
                .unwrap_or_else(|e| panic!("think-close decode failed: {e}"));
            sampler.accept(*token);
            *n_cur += 1;
        }
        close_tokens.len() as u64
    }
}
