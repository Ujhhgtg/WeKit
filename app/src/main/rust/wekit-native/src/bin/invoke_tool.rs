use serde_json::{Value, json};
use std::env;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

const VERSION: &str = "WBT/1";
const MAX_PAYLOAD: usize = 1024 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(10 * 60);
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);

fn main() {
    if let Err(error) = run() {
        eprintln!("{{\"ok\":false,\"error\":\"client_error\",\"message\":{}}}", json!(error));
        std::process::exit(2);
    }
}

fn run() -> Result<(), String> {
    let port = env::var("WEAGENT_BRIDGE_PORT").map_err(|_| "WEAGENT_BRIDGE_PORT is not set")?;
    let token = env::var("WEAGENT_BRIDGE_TOKEN").map_err(|_| "WEAGENT_BRIDGE_TOKEN is not set")?;
    if token.len() != 64 || !token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err("invalid bridge token".into());
    }
    let mut args = env::args().skip(1);
    let operation = args.next().ok_or("usage: invoke_tool list|search|schema|call")?;
    let request = match operation.as_str() {
        "list" => {
            let mut request = json!({"op": "list"});
            if let Some(flag) = args.next() {
                if flag != "--provider" { return Err("expected --provider".into()); }
                request["provider"] = json!(args.next().ok_or("provider id is required")?);
            }
            request
        }
        "search" => json!({"op": "search", "keyword": args.next().ok_or("keyword is required")?}),
        "schema" => json!({"op": "schema", "name": args.next().ok_or("tool name is required")?}),
        "call" => {
            let name = args.next().ok_or("tool name is required")?;
            if args.next().as_deref() != Some("--json") { return Err("expected --json".into()); }
            let arguments: Value = serde_json::from_str(&args.next().ok_or("JSON arguments are required")?)
                .map_err(|error| format!("invalid JSON arguments: {error}"))?;
            json!({"op": "call", "name": name, "arguments": arguments})
        }
        _ => return Err("unknown operation".into()),
    };
    if args.next().is_some() { return Err("unexpected trailing arguments".into()); }

    let payload = request.to_string().into_bytes();
    if payload.len() > MAX_PAYLOAD { return Err("request too large".into()); }
    let port: u16 = port.parse().map_err(|_| "invalid bridge port")?;
    let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
    let mut socket = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT)
        .map_err(|error| format!("bridge unavailable: {error}"))?;
    socket.set_read_timeout(Some(READ_TIMEOUT)).map_err(|error| error.to_string())?;
    socket.set_write_timeout(Some(WRITE_TIMEOUT)).map_err(|error| error.to_string())?;
    writeln!(socket, "{VERSION} {token} {}", payload.len()).map_err(|error| error.to_string())?;
    socket.write_all(&payload).map_err(|error| error.to_string())?;
    let (response_token, response) = read_frame(&mut socket)?;
    if response_token != token { return Err("response token mismatch".into()); }
    let response = String::from_utf8(response).map_err(|_| "response is not UTF-8")?;
    println!("{response}");
    let json: Value = serde_json::from_str(&response).map_err(|_| "response is not JSON")?;
    if json.get("ok").and_then(Value::as_bool) == Some(false) {
        let exit_code = match json.get("error").and_then(Value::as_str).unwrap_or("") {
            "unauthorized" | "token_revoked" => 3,
            "unknown_tool" => 4,
            "approval_denied" => 5,
            "execution_failed" => 6,
            _ => 2,
        };
        std::process::exit(exit_code);
    }
    Ok(())
}

fn read_frame(stream: &mut TcpStream) -> Result<(String, Vec<u8>), String> {
    let mut header = Vec::new();
    loop {
        let mut byte = [0];
        stream.read_exact(&mut byte).map_err(|error| error.to_string())?;
        if byte[0] == b'\n' { break; }
        if header.len() == 128 || !byte[0].is_ascii() { return Err("invalid response header".into()); }
        header.push(byte[0]);
    }
    let header = String::from_utf8(header).map_err(|_| "invalid response header")?;
    let fields: Vec<_> = header.split(' ').collect();
    if fields.len() != 3 || fields[0] != VERSION { return Err("invalid response header".into()); }
    let length: usize = fields[2].parse().map_err(|_| "invalid response length")?;
    if length > MAX_PAYLOAD { return Err("response too large".into()); }
    let mut payload = vec![0; length];
    stream.read_exact(&mut payload).map_err(|error| error.to_string())?;
    Ok((fields[1].to_owned(), payload))
}
