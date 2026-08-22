//! Integration tests for the streaming `<think>` / `<tool_call>` parser.
//!
//! The first six tests are taken verbatim from the task brief; the rest
//! cover feed-boundary, UTF-8, and block-tolerance edge cases.

use wekit_llama::parse::*;

#[test]
fn reasoning_then_content_split_across_feeds() {
    let mut p = ThinkToolParser::new();
    let ev = p.feed("let me think");
    assert_eq!(ev, vec![OutEvent::Reasoning("let me think".into())]);
    let ev = p.feed(" more\n</th"); // marker split mid-way
    assert_eq!(ev, vec![OutEvent::Reasoning(" more".into())]);
    let ev = p.feed("ink>\n\nHello"); // marker completes
    assert_eq!(ev, vec![OutEvent::Content("Hello".into())]);
}

#[test]
fn no_think_mode_is_all_content() {
    let mut p = ThinkToolParser::new_no_think();
    assert_eq!(
        p.feed("hi</think>"),
        vec![OutEvent::Content("hi</think>".into())]
    );
}

#[test]
fn single_tool_call() {
    let mut p = ThinkToolParser::new_no_think();
    let mut all = p.feed("<tool_call>\n<function=get_weather>\n<parameter=city>Tokyo</parameter>\n</function>\n</tool_call>");
    all.extend(p.feed("done"));
    assert!(all.contains(&OutEvent::ToolCallName {
        index: 0,
        name: "get_weather".into()
    }));
    let arg = all
        .iter()
        .find_map(|e| match e {
            OutEvent::ToolCallArg {
                index: 0,
                arguments_json,
            } => Some(arguments_json.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(arg, r#"{"city":"Tokyo"}"#);
    assert!(all.last().unwrap() == &OutEvent::Content("done".into()));
}

#[test]
fn two_tool_calls_and_split_markers() {
    let mut p = ThinkToolParser::new_no_think();
    let mut all = vec![];
    for piece in [
        "<tool_call>\n<function=a>\n<parameter=x>1</parameter>\n</function>\n</tool_call>\n",
        "<tool_call>\n<function=b>\n<parameter=y>[1, 2]</parameter>\n</function>\n</tool_call>",
    ] {
        all.extend(p.feed(piece));
    }
    assert!(all.contains(&OutEvent::ToolCallStart { index: 0 }));
    assert!(all.contains(&OutEvent::ToolCallName {
        index: 1,
        name: "b".into()
    }));
    let args: Vec<_> = all
        .iter()
        .filter_map(|e| {
            if let OutEvent::ToolCallArg {
                index,
                arguments_json,
            } = e
            {
                Some((*index, arguments_json.clone()))
            } else {
                None
            }
        })
        .collect();
    assert_eq!(
        args,
        vec![
            (0, r#"{"x":1}"#.to_string()),
            (1, r#"{"y":[1, 2]}"#.to_string())
        ]
    );
}

#[test]
fn marker_like_text_passes_through_after_holdback() {
    let mut p = ThinkToolParser::new_no_think();
    let ev = p.feed("a<tool_ca");
    assert!(ev.is_empty()); // partial marker tail held
    let ev = p.feed("ll");
    assert_eq!(ev, vec![OutEvent::Content("a".into())]); // "a" emitted; "<tool_call" still held
}

#[test]
fn real_marker_split_across_feeds_opens_tool_call() {
    let mut p = ThinkToolParser::new_no_think();
    assert!(p.feed("x<tool_ca").is_empty());
    let ev = p.feed("ll>\n<function=f>\n<parameter=k>v</parameter>\n</function>\n</tool_call>");
    assert!(ev.contains(&OutEvent::Content("x".into())));
    assert!(ev.contains(&OutEvent::ToolCallName {
        index: 0,
        name: "f".into()
    }));
    let arg = ev
        .iter()
        .find_map(|e| match e {
            OutEvent::ToolCallArg {
                index: 0,
                arguments_json,
            } => Some(arguments_json.clone()),
            _ => None,
        })
        .unwrap();
    assert_eq!(arg, r#"{"k":"v"}"#);
}

#[test]
fn finish_flushes_unterminated_tail() {
    let mut p = ThinkToolParser::new();
    p.feed("partial");
    assert_eq!(p.finish(), vec![OutEvent::Reasoning("partial".into())]);
}

// ---------------------------------------------------------------------------
// Edge cases beyond the brief
// ---------------------------------------------------------------------------

#[test]
fn tool_arguments_json_value_types() {
    // Spec examples from the brief.
    assert_eq!(
        tool_arguments_json(&[("x".into(), "1".into())]),
        r#"{"x":1}"#
    );
    assert_eq!(
        tool_arguments_json(&[("y".into(), "[1, 2]".into())]),
        r#"{"y":[1, 2]}"#
    );
    assert_eq!(
        tool_arguments_json(&[("city".into(), "Tokyo".into())]),
        r#"{"city":"Tokyo"}"#
    );
    // Bools, floats, objects pass through as raw JSON (formatting preserved).
    assert_eq!(
        tool_arguments_json(&[("flag".into(), "true".into())]),
        r#"{"flag":true}"#
    );
    assert_eq!(
        tool_arguments_json(&[("n".into(), "2.5".into())]),
        r#"{"n":2.5}"#
    );
    assert_eq!(
        tool_arguments_json(&[("cfg".into(), r#"{"a": 1}"#.into())]),
        r#"{"cfg":{"a": 1}}"#
    );
    // Non-JSON text is emitted as a JSON string (with escaping).
    assert_eq!(
        tool_arguments_json(&[("q".into(), "say \"hi\"".into())]),
        r#"{"q":"say \"hi\""}"#
    );
    // No parameters, and insertion order preserved.
    assert_eq!(tool_arguments_json(&[]), "{}");
    assert_eq!(
        tool_arguments_json(&[("b".into(), "2".into()), ("a".into(), "1".into())]),
        r#"{"b":2,"a":1}"#
    );
}

#[test]
fn tool_call_block_tolerates_whitespace_and_blank_lines() {
    let mut p = ThinkToolParser::new_no_think();
    let ev = p.feed(
        "<tool_call>\n\n   <function=ws>   \n\n<parameter=a>1</parameter>\n\n\n<parameter=b>[1, 2]</parameter>\n\n   </function>   \n\n</tool_call>",
    );
    assert_eq!(
        ev,
        vec![
            OutEvent::ToolCallStart { index: 0 },
            OutEvent::ToolCallName {
                index: 0,
                name: "ws".into()
            },
            OutEvent::ToolCallArg {
                index: 0,
                arguments_json: r#"{"a":1,"b":[1, 2]}"#.into()
            },
            OutEvent::ToolCallEnd { index: 0 },
        ]
    );
}

#[test]
fn content_between_and_after_tool_calls() {
    let mut p = ThinkToolParser::new_no_think();
    let ev = p.feed("before<tool_call>\n<function=f>\n</function>\n</tool_call>after");
    assert_eq!(
        ev,
        vec![
            OutEvent::Content("before".into()),
            OutEvent::ToolCallStart { index: 0 },
            OutEvent::ToolCallName {
                index: 0,
                name: "f".into()
            },
            OutEvent::ToolCallArg {
                index: 0,
                arguments_json: "{}".into()
            },
            OutEvent::ToolCallEnd { index: 0 },
            OutEvent::Content("after".into()),
        ]
    );
}

#[test]
fn malformed_tool_block_passes_through_as_content() {
    let mut p = ThinkToolParser::new_no_think();
    let ev = p.feed("<tool_call>junk</tool_call>rest");
    assert_eq!(
        ev,
        vec![
            OutEvent::Content("<tool_call>junk</tool_call>".into()),
            OutEvent::Content("rest".into()),
        ]
    );
}

#[test]
fn unterminated_tool_block_flushes_raw_on_finish() {
    let mut p = ThinkToolParser::new_no_think();
    assert!(
        p.feed("<tool_call>\n<function=f>\n<parameter=x>1")
            .is_empty()
    );
    assert_eq!(
        p.finish(),
        vec![OutEvent::Content(
            "<tool_call>\n<function=f>\n<parameter=x>1".into()
        )]
    );
}

#[test]
fn utf8_text_around_split_markers_does_not_panic() {
    // Think state: multi-byte reasoning text right before a split marker.
    let mut p = ThinkToolParser::new();
    assert_eq!(
        p.feed("思考\n</t"),
        vec![OutEvent::Reasoning("思考".into())]
    );
    assert_eq!(
        p.feed("hink>\n\n答案"),
        vec![OutEvent::Content("答案".into())]
    );

    // Content state: multi-byte text, then a partial marker; the live tail is
    // never released (pre-tail text flushes one feed later, snapped to a char
    // boundary), and a dead tail passes everything through unharmed.
    let mut p = ThinkToolParser::new_no_think();
    assert_eq!(p.feed("你好<tool_ca"), vec![OutEvent::Content("你".into())]);
    assert_eq!(p.feed("ll"), vec![OutEvent::Content("好".into())]);
    assert_eq!(p.feed("！"), vec![OutEvent::Content("<tool_call！".into())]);
    assert!(p.finish().is_empty());
}

#[test]
fn marker_final_byte_in_own_feed_still_detected() {
    // The marker's `>` arrives in a later piece than `x<tool_call`: the live
    // tail must stay held and complete into a real tool call.
    let mut p = ThinkToolParser::new_no_think();
    assert_eq!(p.feed("x<tool_call"), vec![OutEvent::Content("x".into())]);
    let ev = p.feed(">\n<function=f>\n</function>\n</tool_call>");
    assert_eq!(
        ev,
        vec![
            OutEvent::ToolCallStart { index: 0 },
            OutEvent::ToolCallName {
                index: 0,
                name: "f".into()
            },
            OutEvent::ToolCallArg {
                index: 0,
                arguments_json: "{}".into()
            },
            OutEvent::ToolCallEnd { index: 0 },
        ]
    );
}

#[test]
fn empty_feed_returns_no_events() {
    let mut p = ThinkToolParser::new();
    assert!(p.feed("").is_empty());
    let mut p = ThinkToolParser::new_no_think();
    assert!(p.feed("").is_empty());
}

#[test]
fn char_by_char_feeding_matches_single_feed() {
    let doc = "let me think more\n</think>\n\nAnswer<tool_call>\n<function=f>\n<parameter=x>1</parameter>\n</function>\n</tool_call>tail";

    let mut one_shot = ThinkToolParser::new();
    let one_shot_events = one_shot.feed(doc);

    let mut charred = ThinkToolParser::new();
    let mut char_events = vec![];
    for ch in doc.chars() {
        char_events.extend(charred.feed(&ch.to_string()));
    }
    char_events.extend(charred.finish());

    fn concat(events: &[OutEvent], pick: fn(&OutEvent) -> Option<&str>) -> String {
        events.iter().filter_map(pick).collect::<Vec<_>>().concat()
    }
    fn reasoning(e: &OutEvent) -> Option<&str> {
        match e {
            OutEvent::Reasoning(s) => Some(s.as_str()),
            _ => None,
        }
    }
    fn content(e: &OutEvent) -> Option<&str> {
        match e {
            OutEvent::Content(s) => Some(s.as_str()),
            _ => None,
        }
    }
    fn tools(events: &[OutEvent]) -> Vec<OutEvent> {
        events
            .iter()
            .filter(|e| {
                matches!(
                    e,
                    OutEvent::ToolCallStart { .. }
                        | OutEvent::ToolCallName { .. }
                        | OutEvent::ToolCallArg { .. }
                        | OutEvent::ToolCallEnd { .. }
                )
            })
            .cloned()
            .collect()
    }

    assert_eq!(concat(&one_shot_events, reasoning), "let me think more");
    assert_eq!(concat(&char_events, reasoning), "let me think more");
    assert_eq!(concat(&one_shot_events, content), "Answertail");
    assert_eq!(concat(&char_events, content), "Answertail");
    assert_eq!(tools(&one_shot_events), tools(&char_events));
    assert_eq!(
        tools(&one_shot_events),
        vec![
            OutEvent::ToolCallStart { index: 0 },
            OutEvent::ToolCallName {
                index: 0,
                name: "f".into()
            },
            OutEvent::ToolCallArg {
                index: 0,
                arguments_json: r#"{"x":1}"#.into()
            },
            OutEvent::ToolCallEnd { index: 0 },
        ]
    );
}
