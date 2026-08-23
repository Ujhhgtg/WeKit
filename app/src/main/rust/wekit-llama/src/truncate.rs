//! History truncation planner for per-request contexts.
//!
//! llama.cpp no longer performs context shift, so the server must fit the
//! rendered prompt itself: `budget = n_ctx - generation reserve`. The planner
//! keeps the leading `system` messages (tool definitions and instructions)
//! plus the longest contiguous newest suffix whose total token count fits the
//! budget. When the suffix boundary lands between an assistant tool-call
//! message and its `tool` responses, the orphan `tool` messages are skipped
//! along with their assistant (a tool response without its call would confuse
//! the template and the model).

use crate::wire::WireMessage;

/// Tokens kept free for forced control tokens and llama.cpp bookkeeping.
pub const CONTEXT_HEADROOM_TOKENS: u32 = 64;
/// A large requested output cap must still leave a useful completion window
/// after retaining the newest prompt history.
const MIN_COMPLETION_RESERVE_TOKENS: u32 = 256;

/// Maximum rendered-prompt size to retain before the exact prompt token count
/// is known. `requested_max_tokens` is an upper cap, not a fixed reservation:
/// large requests reserve a useful minimum completion and are clamped after
/// rendering by [`effective_max_tokens`].
pub fn prompt_token_budget(n_ctx: u32, requested_max_tokens: u32) -> Result<usize, String> {
    if requested_max_tokens == 0 {
        return Err("max_tokens must be at least 1".to_owned());
    }
    let completion_reserve = requested_max_tokens.min(MIN_COMPLETION_RESERVE_TOKENS);
    let reserved = completion_reserve + CONTEXT_HEADROOM_TOKENS;
    let prompt_budget = n_ctx
        .checked_sub(reserved)
        .filter(|value| *value > 0)
        .ok_or_else(|| {
            format!("context window {n_ctx} is too small for a useful completion reserve")
        })?;
    Ok(prompt_budget as usize)
}

/// Clamp the request's output ceiling to the space remaining after the real
/// retained prompt and headroom. The returned value is the one generation and
/// finish accounting must use downstream.
pub fn effective_max_tokens(
    n_ctx: u32,
    requested_max_tokens: u32,
    prompt_tokens: usize,
) -> Result<u32, String> {
    if requested_max_tokens == 0 {
        return Err("max_tokens must be at least 1".to_owned());
    }
    let prompt_tokens = u32::try_from(prompt_tokens)
        .map_err(|_| format!("rendered prompt is too large: {prompt_tokens} tokens"))?;
    let used = prompt_tokens
        .checked_add(CONTEXT_HEADROOM_TOKENS)
        .ok_or_else(|| "rendered prompt token count overflow".to_owned())?;
    let available = n_ctx
        .checked_sub(used)
        .filter(|value| *value > 0)
        .ok_or_else(|| {
            format!("rendered prompt {prompt_tokens} leaves no generation space in context {n_ctx}")
        })?;
    Ok(requested_max_tokens.min(available))
}

/// The truncation outcome: the kept window and how many messages were dropped.
pub struct TruncationResult {
    pub messages: Vec<WireMessage>,
    pub dropped: usize,
}

/// Shrink `messages` until `count_tokens(prefix + suffix)` fits `budget`.
///
/// `count_tokens` receives candidate windows; implementations typically
/// tokenize the concatenated text (the real counter is supplied by the engine
/// task). Returns `Err` when even the leading system messages plus the final
/// message exceed the budget — the caller surfaces that error instead of
/// silently dropping output.
pub fn truncate_messages(
    messages: &[WireMessage],
    count_tokens: &dyn Fn(&[WireMessage]) -> usize,
    budget: usize,
) -> Result<TruncationResult, String> {
    let prefix_len = messages.iter().take_while(|m| m.role == "system").count();
    // Linear shrink of the suffix start (pivot); orphan tool messages are
    // skipped past whenever the pivot lands on them.
    let mut pivot = prefix_len;
    loop {
        while pivot < messages.len() && messages[pivot].role == "tool" {
            pivot += 1;
        }
        if pivot >= messages.len() {
            return Err(format!(
                "cannot fit history: {} messages, budget {} tokens exceeded",
                messages.len(),
                budget
            ));
        }
        let window: Vec<WireMessage> = messages[..prefix_len]
            .iter()
            .chain(messages[pivot..].iter())
            .cloned()
            .collect();
        if count_tokens(&window) <= budget {
            return Ok(TruncationResult {
                messages: window,
                dropped: pivot - prefix_len,
            });
        }
        pivot += 1;
    }
}
