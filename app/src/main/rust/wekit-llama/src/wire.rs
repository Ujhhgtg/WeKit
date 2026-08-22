//! OpenAI-compatible wire types, SSE event builders, and reasoning-effort
//! mapping for the local llama.cpp server.
//!
//! [`ChatRequest`] is what the Kotlin `OpenAiChatCompletionsClient` posts to
//! `/v1/chat/completions`; the SSE builders here produce the exact chunk
//! shapes that client parses:
//!
//! - delta chunks: `{"id", "object":"chat.completion.chunk", "created",
//!   "model", "choices":[{"index":0, "delta":…, "finish_reason":null|…}]}` —
//!   the server sends the first delta as `{"role":"assistant"}` and tool-call
//!   deltas as `{"tool_calls":[{"index":N,"type":"function","function":{…}}]}`
//!   (index-keyed so the Kotlin `ToolCallAccumulator` can accumulate
//!   `arguments` fragments);
//! - the usage chunk carries an empty `choices` array plus a `usage` object;
//! - `data: [DONE]\n\n` terminates the stream.

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

/// One chat message exactly as sent by the OpenAI-compatible client.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WireMessage {
    pub role: String,
    pub content: Option<Value>,
    pub tool_calls: Option<Vec<WireToolCall>>,
    pub tool_call_id: Option<String>,
}

/// A tool call inside an assistant message (stream fragments are index-keyed).
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WireToolCall {
    pub index: Option<usize>,
    pub function: WireFunction,
}

/// Tool call function name + arguments. `arguments` is a JSON *string*
/// (fragments accumulate in streaming), as in the OpenAI protocol.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WireFunction {
    pub name: String,
    pub arguments: Option<String>,
}

/// Incoming `/v1/chat/completions` request body.
#[derive(Debug, Deserialize)]
pub struct ChatRequest {
    pub model: Option<String>,
    pub messages: Vec<WireMessage>,
    pub tools: Option<Vec<WireTool>>,
    pub stream: Option<bool>,
    pub reasoning_effort: Option<String>,
    pub max_tokens: Option<u32>,
    pub max_completion_tokens: Option<u32>,
}

/// A tool definition from the request (`{"type":"function","function":{…}}`).
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WireTool {
    #[serde(rename = "type")]
    pub kind: String,
    pub function: WireToolSpec,
}

/// The `function` object of a tool definition.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct WireToolSpec {
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub parameters: Option<Value>,
}

/// Extract the text of a message `content`: either a plain string or an array
/// of content parts, concatenated. Non-text parts (e.g. `image_url`) and any
/// other JSON shape are rejected; `null`/absent content is the empty string
/// (tool-call-only assistant messages).
pub fn message_text(m: &WireMessage) -> Result<String, String> {
    match &m.content {
        None | Some(Value::Null) => Ok(String::new()),
        Some(Value::String(s)) => Ok(s.clone()),
        Some(Value::Array(parts)) => {
            let mut out = String::new();
            for part in parts {
                let obj = match part {
                    Value::Object(o) => o,
                    _ => return Err(format!("unsupported content part: {part}")),
                };
                if obj.get("type").and_then(Value::as_str) != Some("text") {
                    return Err(format!("unsupported content part: {part}"));
                }
                match obj.get("text") {
                    Some(Value::String(s)) => out.push_str(s),
                    _ => return Err(format!("text part without string text: {part}")),
                }
            }
            Ok(out)
        }
        Some(other) => Err(format!("unsupported content value: {other}")),
    }
}

/// Thinking configuration derived from `reasoning_effort`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EffortConfig {
    pub enable_thinking: bool,
    pub budget_tokens: Option<u64>,
}

/// Map `reasoning_effort` to thinking mode + token budget.
///
/// `None`/`""`/`"off"`/`"none"` disable thinking; the tiers map to
/// minimal 512 / low 1024 / medium 4096 / high 8192 / xhigh 16384 /
/// max 32768. Unknown tiers fall back to medium rather than failing.
pub fn effort_to_config(effort: Option<&str>) -> EffortConfig {
    let off = EffortConfig {
        enable_thinking: false,
        budget_tokens: None,
    };
    let on = |budget: u64| EffortConfig {
        enable_thinking: true,
        budget_tokens: Some(budget),
    };
    match effort {
        None | Some("") | Some("off") | Some("none") => off,
        Some("minimal") => on(512),
        Some("low") => on(1024),
        Some("medium") => on(4096),
        Some("high") => on(8192),
        Some("xhigh") => on(16384),
        Some("max") => on(32768),
        Some(_) => on(4096),
    }
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn sse(v: &Value) -> String {
    // The value is built from json! — to_string cannot fail.
    format!("data: {}\n\n", serde_json::to_string(v).unwrap())
}

/// One streaming delta event (`data: {…}\n\n`).
///
/// `delta_json` is the raw delta object (e.g. `{"role":"assistant"}`,
/// `{"content":"tok"}`, `{"reasoning":"…"}`, or a `tool_calls` fragment);
/// `finish` is `None` until the terminal chunk.
pub fn sse_delta(id: &str, model: &str, delta_json: Value, finish: Option<&str>) -> String {
    sse(&json!({
        "id": id,
        "object": "chat.completion.chunk",
        "created": unix_now(),
        "model": model,
        "choices": [{
            "index": 0,
            "delta": delta_json,
            "finish_reason": finish,
        }],
    }))
}

/// The trailing usage event (`choices` empty, `usage` filled).
pub fn sse_usage(id: &str, model: &str, prompt: u64, completion: u64) -> String {
    sse(&json!({
        "id": id,
        "object": "chat.completion.chunk",
        "created": unix_now(),
        "model": model,
        "choices": [],
        "usage": {
            "prompt_tokens": prompt,
            "completion_tokens": completion,
            "total_tokens": prompt + completion,
        },
    }))
}

/// The SSE stream terminator.
pub fn sse_done() -> &'static str {
    "data: [DONE]\n\n"
}

/// The complete non-streaming `/v1/chat/completions` response body.
///
/// `finish` is `"stop" | "tool_calls" | "length"`. Empty `content` becomes
/// `null` (tool-call-only responses); `reasoning_content` and `tool_calls`
/// are included in the message only when non-empty.
// The parameter list mirrors the OpenAI response fields the callers fill in.
#[allow(clippy::too_many_arguments)]
pub fn chat_completion_json(
    id: &str,
    model: &str,
    content: &str,
    reasoning: &str,
    tool_calls: &[WireToolCall],
    finish: &str,
    prompt: u64,
    completion: u64,
) -> String {
    let mut message = json!({
        "role": "assistant",
        "content": if content.is_empty() { Value::Null } else { json!(content) },
    });
    if !reasoning.is_empty() {
        message["reasoning_content"] = json!(reasoning);
    }
    if !tool_calls.is_empty() {
        message["tool_calls"] = json!(tool_calls
            .iter()
            .enumerate()
            .map(|(i, tc)| {
                json!({
                    "id": format!("call_{i}"),
                    "type": "function",
                    "function": {
                        "name": tc.function.name,
                        "arguments": tc.function.arguments.clone().unwrap_or_else(|| "{}".to_owned()),
                    },
                })
            })
            .collect::<Vec<_>>());
    }
    let body = json!({
        "id": id,
        "object": "chat.completion",
        "created": unix_now(),
        "model": model,
        "choices": [{
            "index": 0,
            "message": message,
            "finish_reason": finish,
        }],
        "usage": {
            "prompt_tokens": prompt,
            "completion_tokens": completion,
            "total_tokens": prompt + completion,
        },
    });
    serde_json::to_string(&body).unwrap()
}

/// The `/v1/models` response body.
pub fn models_json(model_ids: &[&str]) -> String {
    let body = json!({
        "object": "list",
        "data": model_ids
            .iter()
            .map(|id| {
                json!({
                    "id": id,
                    "object": "model",
                    "created": unix_now(),
                    "owned_by": "wekit",
                })
            })
            .collect::<Vec<_>>(),
    });
    serde_json::to_string(&body).unwrap()
}
