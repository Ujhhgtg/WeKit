//! Integration tests for the OpenAI-compatible wire types, SSE builders, and
//! reasoning-effort mapping.
//!
//! The first three tests are taken verbatim from the task brief; the rest
//! cover the full effort table, message content shapes, and the
//! non-streaming/usage/models JSON envelopes.

use serde_json::json;
use wekit_llama::wire::*;

#[test]
fn effort_mapping_table() {
    assert_eq!(
        effort_to_config(None),
        EffortConfig {
            enable_thinking: false,
            budget_tokens: None
        }
    );
    assert_eq!(
        effort_to_config(Some("off")),
        EffortConfig {
            enable_thinking: false,
            budget_tokens: None
        }
    );
    assert_eq!(
        effort_to_config(Some("medium")),
        EffortConfig {
            enable_thinking: true,
            budget_tokens: Some(4096)
        }
    );
    assert_eq!(
        effort_to_config(Some("max")),
        EffortConfig {
            enable_thinking: true,
            budget_tokens: Some(32768)
        }
    );
    assert_eq!(effort_to_config(Some("bogus")).budget_tokens, Some(4096));
}

#[test]
fn content_string_and_parts() {
    let m: WireMessage = serde_json::from_value(json!({"role":"user","content":"hi"})).unwrap();
    assert_eq!(message_text(&m).unwrap(), "hi");
    let m: WireMessage = serde_json::from_value(
        json!({"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}),
    )
    .unwrap();
    assert_eq!(message_text(&m).unwrap(), "ab");
    let m: WireMessage = serde_json::from_value(
        json!({"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}),
    )
    .unwrap();
    assert!(message_text(&m).is_err());
}

#[test]
fn sse_shapes() {
    let d = sse_delta("1", "m", json!({"content":"x"}), None);
    assert!(d.starts_with("data: {") && d.ends_with("\n\n"));
    let v: serde_json::Value = serde_json::from_str(d.trim_start_matches("data: ").trim()).unwrap();
    assert_eq!(v["object"], "chat.completion.chunk");
    assert_eq!(v["choices"][0]["delta"]["content"], "x");
    assert_eq!(sse_done(), "data: [DONE]\n\n");
}

// ---------------------------------------------------------------------------
// Edge cases beyond the brief
// ---------------------------------------------------------------------------

#[test]
fn effort_table_full_coverage() {
    for (effort, thinking, budget) in [
        (Some(""), false, None),
        (Some("none"), false, None),
        (Some("minimal"), true, Some(512)),
        (Some("low"), true, Some(1024)),
        (Some("medium"), true, Some(4096)),
        (Some("high"), true, Some(8192)),
        (Some("xhigh"), true, Some(16384)),
        (Some("max"), true, Some(32768)),
    ] {
        assert_eq!(
            effort_to_config(effort),
            EffortConfig {
                enable_thinking: thinking,
                budget_tokens: budget
            }
        );
    }
    // Unknown tiers fall back to medium (thinking on), never fail.
    assert_eq!(
        effort_to_config(Some("extreme")),
        EffortConfig {
            enable_thinking: true,
            budget_tokens: Some(4096)
        }
    );
}

#[test]
fn message_text_null_absent_and_invalid_content() {
    // JSON null content (tool-call-only assistant messages) and an absent
    // content key both mean "no text".
    let m: WireMessage =
        serde_json::from_value(json!({"role":"assistant","content":null})).unwrap();
    assert_eq!(message_text(&m).unwrap(), "");
    let m: WireMessage = serde_json::from_value(json!({"role":"assistant"})).unwrap();
    assert_eq!(message_text(&m).unwrap(), "");
    // Non-string scalar content is malformed per the OpenAI schema.
    let m: WireMessage = serde_json::from_value(json!({"role":"user","content":42})).unwrap();
    assert!(message_text(&m).is_err());
}

#[test]
fn sse_delta_envelope_fields() {
    let d = sse_delta("chatcmpl-7", "qwen", json!({"role":"assistant"}), None);
    let v: serde_json::Value =
        serde_json::from_str(d.strip_prefix("data: ").unwrap().trim()).unwrap();
    assert_eq!(v["id"], "chatcmpl-7");
    assert_eq!(v["model"], "qwen");
    assert_eq!(v["choices"][0]["index"], 0);
    assert_eq!(v["choices"][0]["finish_reason"], serde_json::Value::Null);
    assert!(v["created"].is_u64());

    let d = sse_delta("chatcmpl-7", "qwen", json!({}), Some("stop"));
    let v: serde_json::Value =
        serde_json::from_str(d.strip_prefix("data: ").unwrap().trim()).unwrap();
    assert_eq!(v["choices"][0]["finish_reason"], "stop");
}

#[test]
fn sse_usage_chunk_shape() {
    let d = sse_usage("id-1", "m", 11, 22);
    assert!(d.starts_with("data: {") && d.ends_with("\n\n"));
    let v: serde_json::Value =
        serde_json::from_str(d.strip_prefix("data: ").unwrap().trim()).unwrap();
    assert_eq!(v["object"], "chat.completion.chunk");
    assert_eq!(v["choices"].as_array().unwrap().len(), 0);
    assert_eq!(v["usage"]["prompt_tokens"], 11);
    assert_eq!(v["usage"]["completion_tokens"], 22);
    assert_eq!(v["usage"]["total_tokens"], 33);
}

#[test]
fn chat_completion_json_shapes() {
    let s = chat_completion_json("id-2", "m", "hello", "because", &[], "stop", 5, 7);
    let v: serde_json::Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["object"], "chat.completion");
    let choice = &v["choices"][0];
    assert_eq!(choice["finish_reason"], "stop");
    assert_eq!(choice["message"]["role"], "assistant");
    assert_eq!(choice["message"]["content"], "hello");
    assert_eq!(choice["message"]["reasoning_content"], "because");
    assert_eq!(v["usage"]["total_tokens"], 12);

    // Tool-call-only response: empty content → null, tool_calls serialized
    // OpenAI-style with id/type/function.
    let tc = WireToolCall {
        index: Some(0),
        function: WireFunction {
            name: "get_weather".to_owned(),
            arguments: Some(r#"{"city":"Tokyo"}"#.to_owned()),
        },
    };
    let s = chat_completion_json("id-3", "m", "", "thought", &[tc], "tool_calls", 9, 4);
    let v: serde_json::Value = serde_json::from_str(&s).unwrap();
    let msg = &v["choices"][0]["message"];
    assert_eq!(msg["content"], serde_json::Value::Null);
    assert_eq!(v["choices"][0]["finish_reason"], "tool_calls");
    assert_eq!(msg["tool_calls"][0]["type"], "function");
    assert_eq!(msg["tool_calls"][0]["function"]["name"], "get_weather");
    assert_eq!(
        msg["tool_calls"][0]["function"]["arguments"],
        r#"{"city":"Tokyo"}"#
    );
    assert!(
        msg["tool_calls"][0]["id"]
            .as_str()
            .unwrap()
            .starts_with("call_")
    );
    // No reasoning, no tool calls → neither key present.
    let s = chat_completion_json("id-4", "m", "plain", "", &[], "stop", 1, 1);
    let v: serde_json::Value = serde_json::from_str(&s).unwrap();
    assert!(
        v["choices"][0]["message"]
            .get("reasoning_content")
            .is_none()
    );
    assert!(v["choices"][0]["message"].get("tool_calls").is_none());
}

#[test]
fn models_json_shape() {
    let s = models_json(&["qwen3.8-4b-distill-q4km", "other"]);
    let v: serde_json::Value = serde_json::from_str(&s).unwrap();
    assert_eq!(v["object"], "list");
    let data = v["data"].as_array().unwrap();
    assert_eq!(data.len(), 2);
    assert_eq!(data[0]["id"], "qwen3.8-4b-distill-q4km");
    assert_eq!(data[0]["object"], "model");
    assert_eq!(data[1]["id"], "other");
}

#[test]
fn chat_request_and_tool_deserialization() {
    let req: ChatRequest = serde_json::from_value(json!({
        "model": "qwen",
        "messages": [
            {"role":"system","content":"sys"},
            {"role":"assistant","content":null,"tool_calls":[
                {"index":0,"function":{"name":"f","arguments":"{\"a\":1}"}}
            ]},
            {"role":"tool","content":"result","tool_call_id":"call_0"}
        ],
        "tools": [{"type":"function","function":{"name":"f"}}],
        "stream": true,
        "reasoning_effort": "high",
        "max_completion_tokens": 128
    }))
    .unwrap();
    assert_eq!(req.model.as_deref(), Some("qwen"));
    assert_eq!(req.messages.len(), 3);
    assert_eq!(
        req.messages[1].tool_calls.as_ref().unwrap()[0]
            .function
            .name,
        "f"
    );
    assert_eq!(req.messages[2].tool_call_id.as_deref(), Some("call_0"));
    assert_eq!(req.tools.as_ref().unwrap()[0].kind, "function");
    // `description`/`parameters` are serde defaults; `max_tokens` stays None.
    assert_eq!(req.tools.as_ref().unwrap()[0].function.description, "");
    assert!(req.tools.as_ref().unwrap()[0].function.parameters.is_none());
    assert!(req.max_tokens.is_none());
    assert_eq!(req.max_completion_tokens, Some(128));
    assert_eq!(req.reasoning_effort.as_deref(), Some("high"));
}
