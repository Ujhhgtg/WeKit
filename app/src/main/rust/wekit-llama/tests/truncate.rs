//! Integration tests for the history-truncation planner.
//!
//! The first three tests are taken verbatim from the task brief (with a fake
//! token counter `|ms| ms.len()`); the rest cover fit-without-drop,
//! tool/assistant pair retention, prefix-only failure, and dropped counts.

use wekit_llama::truncate::*;
use wekit_llama::wire::WireMessage;

fn m(role: &str) -> WireMessage {
    serde_json::from_value(serde_json::json!({"role":role,"content":"c"})).unwrap()
}

#[test]
fn keeps_system_and_newest_suffix() {
    let msgs = vec![
        m("system"),
        m("user"),
        m("assistant"),
        m("user"),
        m("assistant"),
        m("user"),
    ];
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 4).unwrap();
    assert_eq!(r.dropped, 2);
    assert_eq!(r.messages.first().unwrap().role, "system");
    assert_eq!(r.messages.last().unwrap().role, "user");
}

#[test]
fn skips_orphan_tool_responses() {
    let msgs = vec![
        m("system"),
        m("user"),
        m("tool"),
        m("tool"),
        m("assistant"),
        m("user"),
    ];
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 4).unwrap();
    assert_eq!(r.messages.iter().filter(|x| x.role == "tool").count(), 0);
}

#[test]
fn errors_when_impossible() {
    let msgs = vec![m("system"), m("user")];
    assert!(truncate_messages(&msgs, &|_: &[WireMessage]| 99, 4).is_err());
}

// ---------------------------------------------------------------------------
// Edge cases beyond the brief
// ---------------------------------------------------------------------------

#[test]
fn no_truncation_when_already_within_budget() {
    let msgs = vec![m("system"), m("user"), m("assistant")];
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 10).unwrap();
    assert_eq!(r.dropped, 0);
    assert_eq!(r.messages.len(), 3);
    assert_eq!(r.messages[1].role, "user");
}

#[test]
fn tool_pair_stays_with_its_assistant_when_possible() {
    // user + assistant(tc) + tool responses + user: dropping the oldest user
    // keeps the newest contiguous suffix, so the assistant stays with its
    // tool responses intact.
    let msgs = vec![
        m("system"),
        m("user"),
        m("assistant"),
        m("tool"),
        m("tool"),
        m("user"),
    ];
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 5).unwrap();
    assert_eq!(r.dropped, 1);
    assert_eq!(r.messages.len(), 5);
    let roles: Vec<&str> = r.messages.iter().map(|x| x.role.as_str()).collect();
    assert_eq!(roles, vec!["system", "assistant", "tool", "tool", "user"]);
}

#[test]
fn shrinks_to_prefix_plus_last_message() {
    let msgs = vec![m("system"), m("user"), m("assistant"), m("user")];
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 2).unwrap();
    assert_eq!(r.dropped, 2);
    assert_eq!(r.messages.len(), 2);
    assert_eq!(r.messages[0].role, "system");
    assert_eq!(r.messages[1].role, "user");
}

#[test]
fn errors_when_even_last_message_exceeds_budget() {
    let msgs = vec![m("system"), m("user"), m("assistant"), m("user")];
    let r = truncate_messages(&msgs, &|_: &[WireMessage]| 99, 1);
    assert!(r.is_err());
}

#[test]
fn errors_with_only_system_prefix() {
    // Only system messages: no suffix to keep, prefix itself exceeds budget.
    let msgs = vec![m("system"), m("system")];
    assert!(truncate_messages(&msgs, &|_: &[WireMessage]| 99, 1).is_err());
    // Degenerate empty history also cannot satisfy the template.
    assert!(truncate_messages(&[], &|_: &[WireMessage]| 0, 100).is_err());
}

#[test]
fn errors_when_all_non_prefix_messages_are_orphan_tools() {
    let msgs = vec![m("system"), m("assistant"), m("tool")];
    // Budget that forces dropping the assistant leaves only an orphan tool.
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 2);
    assert!(r.is_err());
}

#[test]
fn result_is_a_verbatim_subsequence_of_the_input() {
    let msgs = vec![
        m("system"),
        m("user"),
        m("assistant"),
        m("tool"),
        m("user"),
        m("assistant"),
        m("user"),
    ];
    let r = truncate_messages(&msgs, &|ms: &[WireMessage]| ms.len(), 5).unwrap();
    // The result is exactly leading system prefix + messages[pivot..], with
    // every kept message a verbatim clone of the original.
    let prefix = msgs.iter().take_while(|x| x.role == "system").count();
    let pivot = prefix + r.dropped;
    let expected: Vec<serde_json::Value> = msgs[..prefix]
        .iter()
        .chain(msgs[pivot..].iter())
        .map(|wm| serde_json::to_value(wm).unwrap())
        .collect();
    let got: Vec<serde_json::Value> = r
        .messages
        .iter()
        .map(|wm| serde_json::to_value(wm).unwrap())
        .collect();
    assert_eq!(got, expected);
    // Here: [system, user, assistant, tool, user, assistant, user] with
    // budget 5 drops user/assistant/tool (the orphan-tool skip moves the
    // pivot past `tool`), keeping [system, user, assistant, user].
    assert_eq!(r.dropped, 3);
    assert_eq!(r.messages.len(), 4);
}

#[test]
fn context_4096_clamps_requested_8192_to_a_valid_effective_budget() {
    let prompt_budget = prompt_token_budget(4096, 8192).unwrap();
    let effective_max_tokens = effective_max_tokens(4096, 8192, 1024).unwrap();

    assert_eq!(prompt_budget, 3776);
    assert_eq!(effective_max_tokens, 3008);
}
