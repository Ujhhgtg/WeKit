//! Streaming parser for Qwen3.5-style generation output.
//!
//! The chat template makes every generation open with `<think>` (reasoning),
//! closed by `\n</think>\n\n`; tool calls render as
//! `<tool_call>\n<function=name>\n<parameter=key>value</parameter>\n</function>\n</tool_call>`.
//! The server feeds each generated token piece into [`ThinkToolParser::feed`]
//! and maps the returned [`OutEvent`]s to OpenAI streaming deltas.
//!
//! All matching is buffer-based and byte-offset safe: markers are pure ASCII,
//! and slices are only taken at marker boundaries, at the start of an ASCII
//! partial-marker tail, or snapped to a UTF-8 char boundary, so a multi-byte
//! char held in the buffer can never be split mid-char. A live partial-marker
//! tail is never emitted as text: each state holds back the bytes in which a
//! marker could still start and releases them only once they can no longer be
//! part of a marker.

use serde_json::Value;

/// Closes the reasoning block (Qwen3.5 template: `\n</think>\n\n`).
pub const THINK_CLOSE: &str = "\n</think>\n\n";
/// Opens a tool call block.
pub const TOOL_CALL_OPEN: &str = "<tool_call>";
/// Closes a tool call block.
pub const TOOL_CALL_CLOSE: &str = "</tool_call>";

/// A parsed piece of generation output, in stream order.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OutEvent {
    /// Reasoning (`<think>` block) text.
    Reasoning(String),
    /// Visible assistant content text.
    Content(String),
    /// A tool call block opened; `index` is 0-based per stream.
    ToolCallStart { index: usize },
    /// The tool function name for the call at `index`.
    ToolCallName { index: usize, name: String },
    /// Full arguments JSON object for the call at `index`, emitted once.
    ToolCallArg {
        index: usize,
        arguments_json: String,
    },
    /// The tool call block at `index` closed.
    ToolCallEnd { index: usize },
}

#[derive(Debug, PartialEq, Eq)]
enum PState {
    /// Inside the opening `<think>` block; everything is reasoning.
    Think,
    /// After `</think>` (or `new_no_think()`); text plus tool call markers.
    Content,
    /// Inside a `<tool_call>` block, accumulating until the close marker.
    ToolCall,
}

/// Streaming `<think>` / `<tool_call>` state machine.
///
/// `new()` starts in the Think state because the Qwen3.5 prompt ends with
/// `<think>\n`; `new_no_think()` treats the whole stream as content
/// (`enable_thinking=false` templates pre-fill an empty think block).
pub struct ThinkToolParser {
    state: PState,
    buf: String,
    next_tool_index: usize,
}

impl ThinkToolParser {
    /// Thinking mode: the stream starts in the reasoning state.
    pub fn new() -> Self {
        Self {
            state: PState::Think,
            buf: String::new(),
            next_tool_index: 0,
        }
    }

    /// `enable_thinking=false` mode: everything is content.
    pub fn new_no_think() -> Self {
        Self {
            state: PState::Content,
            buf: String::new(),
            next_tool_index: 0,
        }
    }

    /// Feed one generated token piece; returns the events it completes.
    pub fn feed(&mut self, piece: &str) -> Vec<OutEvent> {
        self.buf.push_str(piece);
        let mut events = Vec::new();
        loop {
            match self.state {
                PState::Think => {
                    if let Some(i) = self.buf.find(THINK_CLOSE) {
                        push_str_event(&mut events, OutEvent::Reasoning, &self.buf[..i]);
                        self.buf.drain(..i + THINK_CLOSE.len());
                        self.state = PState::Content;
                        continue;
                    }
                    let tail = partial_marker_tail(&self.buf, THINK_CLOSE);
                    if tail > 0 {
                        // Keep only the partial-marker tail; the rest is
                        // reasoning text safe to emit now.
                        let emit_to = self.buf.len() - tail;
                        push_str_event(&mut events, OutEvent::Reasoning, &self.buf[..emit_to]);
                        self.buf.drain(..emit_to);
                    } else if self.buf.len() < THINK_CLOSE.len() {
                        // Too short to say anything yet; keep buffering.
                    } else {
                        push_str_event(&mut events, OutEvent::Reasoning, &self.buf);
                        self.buf.clear();
                    }
                    break;
                }
                PState::Content => {
                    if let Some(i) = self.buf.find(TOOL_CALL_OPEN) {
                        push_str_event(&mut events, OutEvent::Content, &self.buf[..i]);
                        self.buf.drain(..i + TOOL_CALL_OPEN.len());
                        self.state = PState::ToolCall;
                        continue;
                    }
                    let tail = partial_marker_tail(&self.buf, TOOL_CALL_OPEN);
                    if tail > 0 {
                        // A partial `<tool_call…` tail is live: never release
                        // it. A marker can only start within the last
                        // marker.len()-1 bytes, so keep that window buffered
                        // and emit what has fallen out of it, snapped down to
                        // a UTF-8 char boundary (the window edge may cut a
                        // multi-byte char in half).
                        let hold = self.buf.len().min(TOOL_CALL_OPEN.len() - 1);
                        let mut emit_to = self.buf.len() - hold;
                        while !self.buf.is_char_boundary(emit_to) {
                            emit_to -= 1;
                        }
                        push_str_event(&mut events, OutEvent::Content, &self.buf[..emit_to]);
                        self.buf.drain(..emit_to);
                    } else {
                        push_str_event(&mut events, OutEvent::Content, &self.buf);
                        self.buf.clear();
                    }
                    break;
                }
                PState::ToolCall => {
                    if let Some(i) = self.buf.find(TOOL_CALL_CLOSE) {
                        let block = self.buf[..i].to_owned();
                        self.buf.drain(..i + TOOL_CALL_CLOSE.len());
                        match parse_tool_call(&block) {
                            Some((name, params)) => {
                                let index = self.next_tool_index;
                                self.next_tool_index += 1;
                                events.push(OutEvent::ToolCallStart { index });
                                events.push(OutEvent::ToolCallName { index, name });
                                events.push(OutEvent::ToolCallArg {
                                    index,
                                    arguments_json: tool_arguments_json(&params),
                                });
                                events.push(OutEvent::ToolCallEnd { index });
                            }
                            // Malformed block: pass the raw text through as
                            // content instead of dropping it.
                            None => {
                                events.push(OutEvent::Content(format!(
                                    "{TOOL_CALL_OPEN}{block}{TOOL_CALL_CLOSE}"
                                )));
                            }
                        }
                        self.state = PState::Content;
                        continue;
                    }
                    // Accumulate; nothing is emitted from inside a tool block.
                    break;
                }
            }
        }
        events
    }

    /// Flush pending buffer state at end of stream; unterminated content is
    /// emitted as-is (an unterminated tool block re-gains its open marker).
    pub fn finish(mut self) -> Vec<OutEvent> {
        let mut events = Vec::new();
        if self.buf.is_empty() {
            return events;
        }
        let buf = std::mem::take(&mut self.buf);
        match self.state {
            PState::Think => events.push(OutEvent::Reasoning(buf)),
            PState::Content => events.push(OutEvent::Content(buf)),
            PState::ToolCall => events.push(OutEvent::Content(format!("{TOOL_CALL_OPEN}{buf}"))),
        }
        events
    }
}

impl Default for ThinkToolParser {
    fn default() -> Self {
        Self::new()
    }
}

fn push_str_event(events: &mut Vec<OutEvent>, make: fn(String) -> OutEvent, s: &str) {
    if !s.is_empty() {
        events.push(make(s.to_owned()));
    }
}

/// Length of the longest suffix of `buf` that is a proper prefix of `marker`
/// (0 if none). Both are compared as bytes; markers are pure ASCII.
fn partial_marker_tail(buf: &str, marker: &str) -> usize {
    let marker = marker.as_bytes();
    for k in (1..marker.len()).rev() {
        if buf.as_bytes().ends_with(&marker[..k]) {
            return k;
        }
    }
    0
}

/// Parse the inside of a `<tool_call>`…`</tool_call>` block:
/// `<function=NAME>` followed by `<parameter=KEY>VALUE</parameter>` lines and
/// a closing `</function>`. Tolerates surrounding whitespace and empty lines.
/// Returns `None` for anything else (the caller then passes the raw text
/// through as content).
fn parse_tool_call(block: &str) -> Option<(String, Vec<(String, String)>)> {
    let mut name: Option<String> = None;
    let mut params: Vec<(String, String)> = Vec::new();
    for line in block.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = line.strip_prefix("<function=") {
            let n = rest.strip_suffix('>')?.trim().to_owned();
            // The function line must come first and only once.
            if name.is_some() || !params.is_empty() || n.is_empty() {
                return None;
            }
            name = Some(n);
        } else if let Some(rest) = line.strip_prefix("<parameter=") {
            // rest is `KEY>VALUE</parameter>`
            let (key, value) = rest.split_once('>')?;
            let value = value.strip_suffix("</parameter>")?;
            let key = key.trim();
            if name.is_none() || key.is_empty() {
                return None;
            }
            params.push((key.to_owned(), value.to_owned()));
        } else if line == "</function>" {
            // Closing marker; nothing to collect.
        } else {
            return None;
        }
    }
    Some((name?, params))
}

/// Build the tool-call arguments JSON object from `KEY -> VALUE` pairs.
///
/// A value that parses as a JSON object/array/number/bool is embedded
/// verbatim (formatting preserved, e.g. `[1, 2]`); anything else becomes a
/// JSON string (`city=Tokyo` → `{"city":"Tokyo"}`).
pub fn tool_arguments_json(params: &[(String, String)]) -> String {
    let mut out = String::from("{");
    for (i, (key, value)) in params.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        // serde_json string encoding handles escaping for keys and
        // non-JSON values; to_string of a &String cannot fail.
        out.push_str(&serde_json::to_string(key).unwrap());
        out.push(':');
        out.push_str(&raw_json_value(value));
    }
    out.push('}');
    out
}

/// A value that parses as a JSON object/array/number/bool keeps its raw text;
/// everything else is encoded as a JSON string.
fn raw_json_value(value: &str) -> String {
    match serde_json::from_str::<Value>(value) {
        Ok(v) if v.is_object() || v.is_array() || v.is_number() || v.is_boolean() => {
            value.to_owned()
        }
        _ => serde_json::to_string(value).unwrap(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn partial_marker_tail_finds_longest_prefix_suffix() {
        assert_eq!(partial_marker_tail(" more\n</th", THINK_CLOSE), 5);
        assert_eq!(partial_marker_tail("a<tool_ca", TOOL_CALL_OPEN), 8);
        assert_eq!(partial_marker_tail("a<tool_call", TOOL_CALL_OPEN), 10);
        // A full marker suffix never reaches the tail helper (find() wins),
        // but it must still not report marker.len().
        assert_eq!(partial_marker_tail("<tool_call>", TOOL_CALL_OPEN), 0);
        assert_eq!(partial_marker_tail("plain text", THINK_CLOSE), 0);
    }

    #[test]
    fn parse_tool_call_rejects_malformed_blocks() {
        assert_eq!(
            parse_tool_call("\n<function=f>\n<parameter=k>v</parameter>\n</function>\n"),
            Some(("f".to_owned(), vec![("k".to_owned(), "v".to_owned())]))
        );
        assert_eq!(parse_tool_call("junk"), None);
        assert_eq!(
            parse_tool_call("\n<function=f>\nnot a parameter\n</function>\n"),
            None
        );
        assert_eq!(parse_tool_call("\n<parameter=k>v</parameter>\n"), None);
    }
}
