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
