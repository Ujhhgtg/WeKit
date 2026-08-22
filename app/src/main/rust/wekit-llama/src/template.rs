//! minijinja-based rendering of GGUF-embedded chat templates.
//!
//! llama-cpp-2's `apply_chat_template` is a non-jinja simplification without
//! tools or `enable_thinking` support, so the server renders the template
//! string itself. HF transformers templates assume a jinja2 environment with
//! `raise_exception`, `strftime_now`, and `tojson` available; [`build_env`]
//! provides those on top of minijinja's standard library (macros, `namespace`,
//! `trim`/`safe`/`string`/`items` filters, slicing — the Qwen3.5 template uses
//! all of them).

use std::time::{SystemTime, UNIX_EPOCH};

use minijinja::{Environment, Error, ErrorKind, Value};
use serde_json::{Value as Json, json};

use crate::wire::{WireMessage, WireTool, message_text};

/// Build the jinja environment used to render chat templates.
///
/// Uses the full builtin environment (`namespace`, `trim`, `safe`, `string`,
/// `items`, `loop.previtem/nextitem`, slicing, … — chat templates assume
/// jinja2's standard library) rather than `Environment::empty()`, which ships
/// none of them. Auto-escaping stays off: `render_str` names the template
/// `<string>`, which has no html/xml extension.
pub fn build_env() -> Environment<'static> {
    let mut env = Environment::new();
    env.add_filter("tojson", tojson);
    env.add_function("raise_exception", raise_exception);
    env.add_function("strftime_now", strftime_now);
    env.set_unknown_method_callback(python_string_method);
    env
}

/// Bridge the Python string methods the Qwen chat template calls in method
/// syntax (`content.startswith(...)`, `content.split(...)[0]`, `rstrip`,
/// `lstrip`), which minijinja does not implement natively; anything else
/// stays an `UnknownMethod` error. `rstrip`/`lstrip` follow Python char-set
/// semantics (any of the given characters), not suffix stripping.
fn python_string_method(
    _state: &minijinja::State,
    value: &Value,
    method: &str,
    args: &[Value],
) -> Result<Value, Error> {
    let Some(s) = value.as_str() else {
        return Err(Error::from(ErrorKind::UnknownMethod));
    };
    let arg_str = || -> Result<&str, Error> {
        args.first().and_then(Value::as_str).ok_or_else(|| {
            Error::new(
                ErrorKind::MissingArgument,
                format!("{method} expects a string argument"),
            )
        })
    };
    match method {
        "startswith" => Ok(Value::from(s.starts_with(arg_str()?))),
        "endswith" => Ok(Value::from(s.ends_with(arg_str()?))),
        "split" => {
            let sep = arg_str()?;
            if sep.is_empty() {
                return Err(Error::new(
                    ErrorKind::InvalidOperation,
                    "split with empty separator",
                ));
            }
            Ok(Value::from(
                s.split(sep).map(Value::from).collect::<Vec<_>>(),
            ))
        }
        "rstrip" => {
            let set = arg_str()?;
            Ok(Value::from(s.trim_end_matches(|c| set.contains(c))))
        }
        "lstrip" => {
            let set = arg_str()?;
            Ok(Value::from(s.trim_start_matches(|c| set.contains(c))))
        }
        _ => Err(Error::from(ErrorKind::UnknownMethod)),
    }
}

/// `tojson` filter: explicit serde_json serialization. minijinja's builtin
/// `tojson` requires the non-default `json` feature, so register our own to
/// stay independent of feature flags.
fn tojson(value: Value) -> Result<String, Error> {
    serde_json::to_string(&value)
        .map_err(|e| Error::new(ErrorKind::BadSerialization, e.to_string()))
}

/// `raise_exception(msg)`: fail the render with the given message, exactly
/// like HF transformers' helper.
fn raise_exception(msg: String) -> Result<(), Error> {
    Err(Error::new(ErrorKind::InvalidOperation, msg))
}

/// `strftime_now(fmt)`: format the current UTC time. Implemented by hand
/// (no chrono dependency); supports `%Y`, `%m`, `%d`, and `%%`.
fn strftime_now(fmt: String) -> Result<String, Error> {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs();
    let (year, month, day) = civil_from_unix_days((secs / 86_400) as i64);
    let mut out = String::new();
    let mut chars = fmt.chars();
    while let Some(c) = chars.next() {
        if c != '%' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('Y') => out.push_str(&year.to_string()),
            Some('m') => out.push_str(&format!("{month:02}")),
            Some('d') => out.push_str(&format!("{day:02}")),
            Some('%') => out.push('%'),
            Some(other) => {
                return Err(Error::new(
                    ErrorKind::InvalidOperation,
                    format!("strftime_now: unsupported specifier %{other}"),
                ));
            }
            None => {
                return Err(Error::new(
                    ErrorKind::InvalidOperation,
                    "strftime_now: dangling format specifier",
                ));
            }
        }
    }
    Ok(out)
}

/// Days since the Unix epoch → (year, month, day) civil date (UTC),
/// via Howard Hinnant's `civil_from_days` algorithm.
fn civil_from_unix_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365; // [0, 399]
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100); // [0, 365]
    let mp = (5 * doy + 2) / 153; // [0, 11]
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32; // [1, 31]
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32; // [1, 12]
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// The template render context (the HF transformers chat-template
/// convention) for a conversation: `messages` (string `content`,
/// `tool_calls` whose `arguments` are re-parsed into objects so templates
/// can iterate them), `tools` in the OpenAI
/// `{"type":"function","function":{…}}` shape, `add_generation_prompt`, and
/// `enable_thinking`.
///
/// Public so the server can feed a *precompiled* template
/// (`Environment::template_from_str`) instead of re-parsing the source on
/// every request the way [`render_prompt`] does; the two paths build the
/// exact same context.
pub fn template_context(
    messages: &[WireMessage],
    tools: Option<&[WireTool]>,
    enable_thinking: bool,
) -> Json {
    json!({
        "messages": messages.iter().map(template_message).collect::<Vec<_>>(),
        "tools": tools
            .map(|t| t.iter().map(template_tool).collect::<Vec<_>>())
            .unwrap_or_default(),
        "add_generation_prompt": true,
        "enable_thinking": enable_thinking,
    })
}

/// Render the chat template into the final prompt string.
///
/// Re-parses `template` on every call; template errors (including
/// `raise_exception`) surface as `Err` with the rendered error message.
pub fn render_prompt(
    env: &Environment,
    template: &str,
    messages: &[WireMessage],
    tools: Option<&[WireTool]>,
    enable_thinking: bool,
) -> Result<String, String> {
    env.render_str(template, template_context(messages, tools, enable_thinking))
        .map_err(|e| e.to_string())
}

/// Map one wire message into the template shape. `content` is flattened to
/// text (unsupported parts degrade to the empty string — images are not
/// supported and the template's own `raise_exception` remains the loud
/// failure path for malformed input).
fn template_message(m: &WireMessage) -> Json {
    let mut v = json!({
        "role": m.role,
        "content": message_text(m).unwrap_or_default(),
    });
    if let Some(calls) = &m.tool_calls {
        v["tool_calls"] = json!(
            calls
                .iter()
                .map(|c| {
                    json!({
                        "function": {
                            "name": c.function.name,
                            "arguments": arguments_value(&c.function.arguments),
                        }
                    })
                })
                .collect::<Vec<_>>()
        );
    }
    v
}

/// Assistant tool-call `arguments` arrive as a JSON *string*; templates
/// iterate them as an object, so parse them back. Non-object or unparsable
/// arguments pass through as the raw string (rendering then fails loudly
/// inside the template rather than being silently dropped).
fn arguments_value(args: &Option<String>) -> Json {
    match args {
        None => json!({}),
        Some(s) => serde_json::from_str::<Json>(s)
            .ok()
            .filter(|v| v.is_object())
            .unwrap_or_else(|| json!(s)),
    }
}

/// Map a tool definition to the `{"type","function":{name,description,
/// parameters}}` shape templates expect to `tojson`.
fn template_tool(t: &WireTool) -> Json {
    json!({
        "type": t.kind,
        "function": {
            "name": t.function.name,
            "description": t.function.description,
            "parameters": t.function.parameters.clone().unwrap_or_else(|| json!({})),
        }
    })
}
