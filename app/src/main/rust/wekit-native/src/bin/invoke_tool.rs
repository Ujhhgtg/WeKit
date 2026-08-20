use serde::Deserializer;
use serde::de::{self, MapAccess, Visitor};
use serde_json::{Value, json};
use std::env;
use std::fmt;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

const VERSION: &str = "WBT/1";
const MAX_PAYLOAD: usize = 1024 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const READ_TIMEOUT: Duration = Duration::from_secs(10 * 60);
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug)]
enum ClientError {
    InvalidRequest(String),
    BridgeUnavailable(String),
}

impl ClientError {
    fn exit_code(&self) -> i32 {
        match self {
            Self::InvalidRequest(_) => 2,
            Self::BridgeUnavailable(_) => 7,
        }
    }

    fn message(&self) -> &str {
        match self {
            Self::InvalidRequest(message) | Self::BridgeUnavailable(message) => message,
        }
    }
}

fn main() {
    if let Err(error) = run() {
        println!(
            "{}",
            json!({
                "ok": false,
                "error": "client_error",
                "message": error.message(),
            })
        );
        std::process::exit(error.exit_code());
    }
}

fn run() -> Result<(), ClientError> {
    let request = parse_request(env::args().skip(1))?;
    let port = env::var("WEAGENT_BRIDGE_PORT")
        .map_err(|_| ClientError::BridgeUnavailable("WEAGENT_BRIDGE_PORT is not set".into()))?;
    let token = env::var("WEAGENT_BRIDGE_TOKEN")
        .map_err(|_| ClientError::BridgeUnavailable("WEAGENT_BRIDGE_TOKEN is not set".into()))?;
    if token.len() != 64 || !token.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return Err(ClientError::BridgeUnavailable(
            "invalid bridge token".into(),
        ));
    }
    let payload = request.to_string().into_bytes();
    if payload.len() > MAX_PAYLOAD {
        return Err(ClientError::InvalidRequest("request too large".into()));
    }
    let port: u16 = port
        .parse()
        .map_err(|_| ClientError::BridgeUnavailable("invalid bridge port".into()))?;
    let address = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
    let mut socket = TcpStream::connect_timeout(&address, CONNECT_TIMEOUT)
        .map_err(|error| ClientError::BridgeUnavailable(format!("bridge unavailable: {error}")))?;
    socket
        .set_read_timeout(Some(READ_TIMEOUT))
        .map_err(|error| ClientError::BridgeUnavailable(error.to_string()))?;
    socket
        .set_write_timeout(Some(WRITE_TIMEOUT))
        .map_err(|error| ClientError::BridgeUnavailable(error.to_string()))?;
    writeln!(socket, "{VERSION} {token} {}", payload.len())
        .map_err(|error| ClientError::BridgeUnavailable(error.to_string()))?;
    socket
        .write_all(&payload)
        .map_err(|error| ClientError::BridgeUnavailable(error.to_string()))?;
    let (response_token, response) =
        read_frame(&mut socket).map_err(ClientError::BridgeUnavailable)?;
    if response_token != token {
        return Err(ClientError::BridgeUnavailable(
            "response token mismatch".into(),
        ));
    }
    let response = String::from_utf8(response)
        .map_err(|_| ClientError::BridgeUnavailable("response is not UTF-8".into()))?;
    let exit_code = response_exit_code(&response)?;
    println!("{response}");
    if exit_code != 0 {
        std::process::exit(exit_code);
    }
    Ok(())
}

fn response_exit_code(response: &str) -> Result<i32, ClientError> {
    struct ResponseContract {
        ok: bool,
        error: Option<String>,
    }

    struct ResponseVisitor;

    impl<'de> Visitor<'de> for ResponseVisitor {
        type Value = ResponseContract;

        fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
            formatter.write_str("a bridge response object")
        }

        fn visit_map<A>(self, mut map: A) -> Result<Self::Value, A::Error>
        where
            A: MapAccess<'de>,
        {
            let mut ok = None;
            let mut error = None;
            while let Some(key) = map.next_key::<String>()? {
                match key.as_str() {
                    "ok" => {
                        if ok.is_some() {
                            return Err(de::Error::duplicate_field("ok"));
                        }
                        ok = Some(map.next_value::<bool>()?);
                    }
                    "error" => {
                        if error.is_some() {
                            return Err(de::Error::duplicate_field("error"));
                        }
                        error = Some(map.next_value::<String>()?);
                    }
                    _ => {
                        map.next_value::<Value>()?;
                    }
                }
            }
            Ok(ResponseContract {
                ok: ok.ok_or_else(|| de::Error::missing_field("ok"))?,
                error,
            })
        }
    }

    let mut deserializer = serde_json::Deserializer::from_str(response);
    let contract = deserializer
        .deserialize_map(ResponseVisitor)
        .and_then(|contract| {
            deserializer.end()?;
            Ok(contract)
        })
        .map_err(|_| ClientError::BridgeUnavailable("invalid bridge response JSON".into()))?;
    if contract.ok {
        return Ok(0);
    }
    let error = contract
        .error
        .ok_or_else(|| ClientError::BridgeUnavailable("invalid bridge response JSON".into()))?;
    Ok(match error.as_str() {
        "unauthorized" | "token_revoked" | "authentication_failed" => 3,
        "unknown_tool" | "tool_disabled" | "disabled_tool" => 4,
        "approval_denied" => 5,
        "execution_failed" => 6,
        _ => 2,
    })
}

fn parse_request(mut args: impl Iterator<Item = String>) -> Result<Value, ClientError> {
    let operation = args.next().ok_or_else(|| {
        ClientError::InvalidRequest("usage: invoke_tool list|search|schema|call".into())
    })?;
    let request = match operation.as_str() {
        "list" => {
            let mut request = json!({"op": "list"});
            if let Some(flag) = args.next() {
                if flag != "--provider" {
                    return Err(ClientError::InvalidRequest("expected --provider".into()));
                }
                request["provider"] = json!(args.next().ok_or_else(|| {
                    ClientError::InvalidRequest("provider id is required".into())
                })?);
            }
            request
        }
        "search" => json!({
            "op": "search",
            "keyword": args.next().ok_or_else(|| {
                ClientError::InvalidRequest("keyword is required".into())
            })?
        }),
        "schema" => json!({
            "op": "schema",
            "name": args.next().ok_or_else(|| {
                ClientError::InvalidRequest("tool name is required".into())
            })?
        }),
        "call" => {
            let name = args
                .next()
                .ok_or_else(|| ClientError::InvalidRequest("tool name is required".into()))?;
            if args.next().as_deref() != Some("--json") {
                return Err(ClientError::InvalidRequest("expected --json".into()));
            }
            let arguments: Value = serde_json::from_str(&args.next().ok_or_else(|| {
                ClientError::InvalidRequest("JSON arguments are required".into())
            })?)
            .map_err(|error| {
                ClientError::InvalidRequest(format!("invalid JSON arguments: {error}"))
            })?;
            json!({"op": "call", "name": name, "arguments": arguments})
        }
        _ => return Err(ClientError::InvalidRequest("unknown operation".into())),
    };
    if args.next().is_some() {
        return Err(ClientError::InvalidRequest(
            "unexpected trailing arguments".into(),
        ));
    }
    Ok(request)
}

fn read_frame(stream: &mut TcpStream) -> Result<(String, Vec<u8>), String> {
    let mut header = Vec::new();
    loop {
        let mut byte = [0];
        stream
            .read_exact(&mut byte)
            .map_err(|error| error.to_string())?;
        if byte[0] == b'\n' {
            break;
        }
        if header.len() == 128 || !byte[0].is_ascii() {
            return Err("invalid response header".into());
        }
        header.push(byte[0]);
    }
    let header = String::from_utf8(header).map_err(|_| "invalid response header")?;
    let fields: Vec<_> = header.split(' ').collect();
    if fields.len() != 3 || fields[0] != VERSION {
        return Err("invalid response header".into());
    }
    let length: usize = fields[2].parse().map_err(|_| "invalid response length")?;
    if length > MAX_PAYLOAD {
        return Err("response too large".into());
    }
    let mut payload = vec![0; length];
    stream
        .read_exact(&mut payload)
        .map_err(|error| error.to_string())?;
    Ok((fields[1].to_owned(), payload))
}

#[cfg(test)]
mod tests {
    use super::{parse_request, response_exit_code};
    use serde_json::json;

    #[test]
    fn parser_rejects_invalid_arguments_without_bridge_access() {
        let error = parse_request(
            ["call", "tool", "--json", "{"]
                .into_iter()
                .map(String::from),
        )
        .unwrap_err();
        assert_eq!(error.exit_code(), 2);
    }

    #[test]
    fn response_parser_rejects_invalid_contracts() {
        for response in [
            "",
            "{malformed",
            "[]",
            "true",
            "{}",
            r#"{"message":"missing ok"}"#,
            r#"{"ok":null}"#,
            r#"{"ok":[]}"#,
            r#"{"ok":"true"}"#,
            r#"{"ok":false}"#,
            r#"{"ok":false,"error":null}"#,
            r#"{"ok":false,"error":7}"#,
            r#"{"ok":true,"ok":false,"error":"unauthorized"}"#,
            r#"{"ok":false,"error":"execution_failed","error":"unauthorized"}"#,
        ] {
            let error = response_exit_code(response).unwrap_err();
            assert_eq!(error.exit_code(), 7, "response={response}");
        }
    }

    #[test]
    fn response_errors_use_shared_exit_contract() {
        for (error, exit_code) in [
            ("unauthorized", 3),
            ("token_revoked", 3),
            ("authentication_failed", 3),
            ("unknown_tool", 4),
            ("tool_disabled", 4),
            ("disabled_tool", 4),
            ("approval_denied", 5),
            ("execution_failed", 6),
            ("invalid_json", 2),
        ] {
            assert_eq!(
                response_exit_code(&format!(r#"{{"ok":false,"error":"{error}"}}"#)).unwrap(),
                exit_code
            );
        }
        assert_eq!(
            response_exit_code(
                r#" { "ok" : true, "result" : {"ok":false,"error":"unauthorized"} } "#
            )
            .unwrap(),
            0
        );
        assert_eq!(
            parse_request(["list"].into_iter().map(String::from)).unwrap(),
            json!({"op": "list"})
        );
    }
}
