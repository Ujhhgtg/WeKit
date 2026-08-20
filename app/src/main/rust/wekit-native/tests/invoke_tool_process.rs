use serde_json::Value;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::process::{Command, Output};
use std::thread;

const TOKEN: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

#[test]
fn local_failures_are_parseable_json() {
    let output = Command::new(env!("CARGO_BIN_EXE_invoke_tool"))
        .args(["call", "tool", "--json", "{"])
        .output()
        .unwrap();

    assert_eq!(output.status.code(), Some(2));
    assert_local_error(&output);
}

#[test]
fn invalid_bridge_contracts_exit_seven_with_parseable_json() {
    for response in [
        "",
        "{malformed",
        "[]",
        "true",
        "{}",
        r#"{"message":"missing ok"}"#,
        r#"{"ok":"true"}"#,
        r#"{"ok":false}"#,
        r#"{"ok":false,"error":false}"#,
    ] {
        let output = invoke(response);
        assert_eq!(output.status.code(), Some(7), "response={response}");
        assert_local_error(&output);
    }
}

#[test]
fn valid_bridge_contracts_map_status_and_preserve_output() {
    for (error, exit_code) in [
        ("unauthorized", 3),
        ("token_revoked", 3),
        ("authentication_failed", 3),
        ("unknown_tool", 4),
        ("tool_disabled", 4),
        ("disabled_tool", 4),
        ("approval_denied", 5),
        ("execution_failed", 6),
        ("other", 2),
    ] {
        let response = format!(r#"{{"ok":false,"error":"{error}"}}"#);
        let output = invoke(&response);
        assert_eq!(output.status.code(), Some(exit_code), "response={response}");
        assert_eq!(output.stdout, format!("{response}\n").as_bytes());
    }

    let response = r#" { "ok" : true, "result" : {"ok":false,"error":"unauthorized"} } "#;
    let output = invoke(response);
    assert_eq!(output.status.code(), Some(0));
    assert_eq!(output.stdout, format!("{response}\n").as_bytes());
}

fn invoke(response: &str) -> Output {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let response = response.as_bytes().to_vec();
    let server = thread::spawn(move || {
        let (mut socket, _) = listener.accept().unwrap();
        let mut header = Vec::new();
        loop {
            let mut byte = [0];
            socket.read_exact(&mut byte).unwrap();
            header.push(byte[0]);
            if byte[0] == b'\n' {
                break;
            }
        }
        let header = String::from_utf8(header).unwrap();
        let length = header.trim().rsplit(' ').next().unwrap().parse().unwrap();
        let mut request = vec![0; length];
        socket.read_exact(&mut request).unwrap();
        write!(socket, "WBT/1 {TOKEN} {}\n", response.len()).unwrap();
        socket.write_all(&response).unwrap();
    });

    let output = Command::new(env!("CARGO_BIN_EXE_invoke_tool"))
        .arg("list")
        .env("WEAGENT_BRIDGE_PORT", port.to_string())
        .env("WEAGENT_BRIDGE_TOKEN", TOKEN)
        .output()
        .unwrap();
    server.join().unwrap();
    output
}

fn assert_local_error(output: &Output) {
    let json: Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(json.get("ok"), Some(&Value::Bool(false)));
    assert_eq!(
        json.get("error").and_then(Value::as_str),
        Some("client_error")
    );
}
