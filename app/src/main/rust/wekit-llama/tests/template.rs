//! Integration tests for the minijinja chat-template renderer against the
//! real Qwen3.5 chat template fixture.
//!
//! The first test is taken verbatim from the task brief; the rest cover
//! tool-response turns, assistant tool-call history, the `raise_exception`
//! path, and the custom `tojson`/`strftime_now` env helpers.

use wekit_llama::template::*;
use wekit_llama::wire::*;

fn msg(v: serde_json::Value) -> WireMessage {
    serde_json::from_value(v).unwrap()
}

#[test]
fn renders_tools_and_thinking_modes() {
    let tpl = include_str!("fixtures/qwen35_chat_template.j2");
    let env = build_env();
    let msgs = vec![
        msg(serde_json::json!({"role":"system","content":"You are helpful."})),
        msg(serde_json::json!({"role":"user","content":"weather?"})),
    ];
    let tools = vec![serde_json::from_value::<WireTool>(serde_json::json!({
        "type":"function","function":{"name":"get_weather","description":"d",
        "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}})).unwrap()];
    let on = render_prompt(&env, tpl, &msgs, Some(&tools), true).unwrap();
    assert!(on.contains("<|im_start|>system"));
    assert!(on.contains("<tools>"));
    assert!(on.contains("get_weather"));
    assert!(on.trim_end().ends_with("<think>")); // 思考模式：生成前缀以 <think>\n 结尾
    let off = render_prompt(&env, tpl, &msgs, Some(&tools), false).unwrap();
    assert!(off.contains("<think>\n\n</think>\n\n")); // 软关闭：预填空 think 块
    assert!(off.contains("<|im_end|>"));
}

// ---------------------------------------------------------------------------
// Edge cases beyond the brief
// ---------------------------------------------------------------------------

#[test]
fn renders_without_tools_as_plain_system_block() {
    let tpl = include_str!("fixtures/qwen35_chat_template.j2");
    let env = build_env();
    let msgs = vec![
        msg(serde_json::json!({"role":"system","content":"You are helpful."})),
        msg(serde_json::json!({"role":"user","content":"hi"})),
    ];
    let out = render_prompt(&env, tpl, &msgs, None, false).unwrap();
    assert!(out.contains("<|im_start|>system\nYou are helpful.<|im_end|>\n"));
    assert!(!out.contains("<tools>"));
    assert!(out.contains("<|im_start|>user\nhi<|im_end|>\n"));
}

#[test]
fn renders_full_tool_call_round_trip() {
    // system → user → assistant(tool_calls) → tool → assistant → user.
    let tpl = include_str!("fixtures/qwen35_chat_template.j2");
    let env = build_env();
    let msgs = vec![
        msg(serde_json::json!({"role":"system","content":"S"})),
        msg(serde_json::json!({"role":"user","content":"weather?"})),
        msg(
            serde_json::json!({"role":"assistant","content":null,"tool_calls":[
                {"index":0,"function":{"name":"get_weather","arguments":"{\"city\":\"Tokyo\"}"}}
            ]}),
        ),
        msg(serde_json::json!({"role":"tool","content":"sunny","tool_call_id":"call_0"})),
        msg(serde_json::json!({"role":"assistant","content":"It is sunny."})),
        msg(serde_json::json!({"role":"user","content":"thanks"})),
    ];
    let tools = vec![
        serde_json::from_value::<WireTool>(serde_json::json!({
        "type":"function","function":{"name":"get_weather","parameters":{
            "type":"object","properties":{"city":{"type":"string"}}}}}))
        .unwrap(),
    ];
    let out = render_prompt(&env, tpl, &msgs, Some(&tools), true).unwrap();
    // Assistant tool-call history renders in the Qwen XML style with the
    // arguments object re-expanded into parameter tags.
    assert!(out.contains("<|im_start|>assistant\n<tool_call>\n<function=get_weather>\n<parameter=city>\nTokyo\n</parameter>\n</function>\n</tool_call><|im_end|>\n"));
    // Tool responses wrap into a synthetic user turn.
    assert!(out.contains("<|im_start|>user\n<tool_response>\nsunny\n</tool_response><|im_end|>\n"));
    // Plain assistant turns stay in the pre-query form (no think block).
    assert!(out.contains("<|im_start|>assistant\nIt is sunny.<|im_end|>\n"));
    assert!(out.contains("<|im_start|>user\nthanks<|im_end|>\n"));
}

#[test]
fn unknown_role_surfaced_as_template_error() {
    let tpl = include_str!("fixtures/qwen35_chat_template.j2");
    let env = build_env();
    let msgs = vec![
        msg(serde_json::json!({"role":"user","content":"hi"})),
        msg(serde_json::json!({"role":"bogus","content":"x"})),
    ];
    let err = render_prompt(&env, tpl, &msgs, None, false).unwrap_err();
    assert!(err.contains("Unexpected message role."), "err was: {err}");
}

#[test]
fn env_registers_tojson_strftime_now_and_raise_exception() {
    let env = build_env();
    // tojson: JSON serialization of the value (single key — minijinja maps
    // iterate in sorted key order without the preserve_order feature).
    let out = env
        .render_str("{{ d | tojson }}", serde_json::json!({"d": {"a": 1}}))
        .unwrap();
    assert_eq!(out, r#"{"a":1}"#);
    // strftime_now: manual %Y-%m-%d via SystemTime.
    let out = env
        .render_str(r#"{{ strftime_now("%Y-%m-%d") }}"#, ())
        .unwrap();
    let parts: Vec<&str> = out.split('-').collect();
    assert_eq!(parts.len(), 3, "out was: {out}");
    assert!(parts[0].len() == 4 && parts[0].chars().all(|c| c.is_ascii_digit()));
    assert!(
        parts
            .iter()
            .skip(1)
            .all(|p| p.len() == 2 && p.chars().all(|c| c.is_ascii_digit()))
    );
    // raise_exception fails the render with the given message.
    let err = env
        .render_str("{{ raise_exception('boom') }}", ())
        .unwrap_err();
    assert!(err.to_string().contains("boom"), "err was: {err}");
}
