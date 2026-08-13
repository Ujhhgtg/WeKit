use axum::{
    Json, Router,
    extract::{ConnectInfo, Path, Query, State, WebSocketUpgrade, ws::{WebSocket, Message as WsMessage}},
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use chrono::Utc;
use futures_util::{SinkExt, StreamExt};
use libsql::{Builder, Connection};
use maxminddb::Reader;
use rustyline::completion::{Completer, Pair};
use rustyline::highlight::Highlighter;
use rustyline::hint::Hinter;
use rustyline::validate::Validator;
use rustyline::{ExternalPrinter, Helper};
use serde::{Deserialize, Serialize};
use serde_json::Value as JsonValue;
use sha2::{Digest, Sha256};
use std::borrow::Cow;
use std::collections::HashMap;
use std::io::Write;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Mutex, OnceLock};
use std::{sync::Arc};
use tokio::sync::broadcast;
use tracing::{debug, error, info, warn};

// 1x1 transparent PNG file bytes to serve as the tracking pixel
const TRACKING_PIXEL: &[u8] = &[
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
    0x42, 0x60, 0x82,
];

/// Computes the deterministic message id shared by client and server:
/// `sha256(wx_id + '\0' + content + '\0' + create_time)` rendered as lowercase hex,
/// where `create_time` is the client-supplied epoch-millis as a decimal string.
/// The NUL separators prevent ambiguity between the fields; folding in create_time
/// keeps two identical-text messages from colliding onto the same id.
fn compute_msg_id(wx_id: &str, content: &str, create_time: i64) -> String {
    let mut hasher = Sha256::new();
    hasher.update(wx_id.as_bytes());
    hasher.update([0u8]);
    hasher.update(content.as_bytes());
    hasher.update([0u8]);
    hasher.update(create_time.to_string().as_bytes());
    hex::encode(hasher.finalize())
}

/// Body of `POST /register`: the sender's wxId, the plaintext message content,
/// and the client-assigned createTime. The server derives the id from all three.
///
/// `talker` / `chatName` are optional enrichment from the sender's client:
/// `talker` is the conversation id (e.g. `wxid_xxx` for a direct chat,
/// `xxxx@chatroom` for a group chat) and `chatName` is the human-readable
/// conversation name (remark/nickname for direct chats, group name for rooms).
#[derive(Deserialize)]
struct RegisterRequest {
    #[serde(rename = "wxId")]
    wx_id: String,
    content: String,
    #[serde(rename = "createTime")]
    create_time: i64,
    talker: Option<String>,
    #[serde(rename = "chatName")]
    chat_name: Option<String>,
}

#[derive(Serialize)]
struct RegisterResponse {
    id: String,
}

/// Query parameters for the tracking pixel and count endpoints.
/// Payload of `/read-report`: a WeKit client that RENDERED an incoming probing
/// message reports itself as the reader, so the dashboard can label the probed
/// IP with the reader's wxid + nickname (works for group chats too, where the
/// pixel URL alone can only name the room).
#[derive(Deserialize)]
struct ReadReportRequest {
    #[serde(rename = "msgId")]
    msg_id: String,
    #[serde(rename = "senderWxId")]
    sender_wx_id: String,
    #[serde(rename = "readerWxId")]
    reader_wx_id: String,
    #[serde(rename = "readerNickname")]
    reader_nickname: Option<String>,
    /// Conversation the read happened in (peer wxid or `xxx@chatroom`), decoded
    /// by the client from the pixel URL. Lets the dashboard pair each probed IP
    /// with the exact group member (reader wxid + 群昵称) who read it.
    talker: Option<String>,
    /// `"sender"` when this device is the probe's SENDER viewing their own
    /// outgoing message — labels the row as 发送者本人 instead of a bare group.
    role: Option<String>,
}

/// Both carry the sender `wxId` and the message `id` (no more uuid/msg).
#[derive(Deserialize)]
struct ReadParams {
    #[serde(rename = "wxId")]
    wx_id: Option<String>,
    id: Option<String>,
    #[serde(rename = "reader_wx_id")]
    reader_wx_id: Option<String>,
    /// Conversation id this read happened in (peer wxid for direct chats,
    /// `xxx@chatroom` for group chats). Carried on the pixel URL by the
    /// sender's client so the dashboard can label each probed IP with the
    /// conversation it was read in.
    talker: Option<String>,
    /// Human-readable conversation name (remark/nickname or group name).
    #[serde(rename = "chatName")]
    chat_name: Option<String>,
    #[serde(rename = "device_type")]
    device_type: Option<String>,
    #[serde(rename = "os")]
    os: Option<String>,
    #[serde(rename = "browser")]
    browser: Option<String>,
    referrer: Option<String>,
}

#[derive(Serialize)]
struct CountResponse {
    count: i64,
}

/// Global dashboard statistics
#[derive(Serialize)]
struct GlobalStatsResponse {
    total_messages: i64,
    unique_ips: i64,
    total_reads: i64,
    countries: i64,
    cities: i64,
}

/// Pagination query parameters
#[derive(Deserialize)]
struct PaginationParams {
    page: Option<u32>,
    page_size: Option<u32>,
}

impl PaginationParams {
    fn page(&self) -> u32 {
        self.page.unwrap_or(1).max(1)
    }
    fn page_size(&self) -> u32 {
        self.page_size.unwrap_or(20).clamp(1, 100)
    }
    fn offset(&self) -> u32 {
        (self.page() - 1) * self.page_size()
    }
}

/// Paginated response for messages
#[derive(Serialize)]
struct PaginatedMessages {
    messages: Vec<MessageRecord>,
    total: i64,
    page: u32,
    page_size: u32,
    total_pages: u32,
}

struct AppState {
    db: Connection,
    ws_tx: broadcast::Sender<String>,
    geoip: Arc<Mutex<Option<Reader>>>,
    http: reqwest::Client,
    /// Per-IP cache of third-party geolocation results (ip -> (loc, fetched_at)).
    ip_cache: Arc<Mutex<HashMap<String, (Option<ApiLocation>, i64)>>>,
    /// Per-IP cache of **street-level** (apizero) results triggered manually via
    /// the per-IP "街道级" button. Kept separate from the default chain so the
    /// on-demand street lookups (which have a limited daily quota) are reused
    /// across page loads without polluting the default district/city cache.
    street_cache: Arc<Mutex<HashMap<String, (Option<ApiLocation>, i64)>>>,
    /// Last apizero (street-level) call timestamp, used to respect its QPS limit.
    apizero_throttle: Arc<tokio::sync::Mutex<i64>>,
}

/// Enriched location data returned by the third-party IP geolocation chain.
#[derive(Clone, Default, Serialize)]
struct ApiLocation {
    country: String,
    province: String,
    city: String,
    district: String,
    street: String,
    /// Candidate street names for the same IP (from the street-level API).
    street_alternatives: Vec<String>,
    isp: String,
    latitude: f64,
    longitude: f64,
    /// Which tier resolved this IP: 街道级 / 市区级 / 市级别 (empty = local GeoIP).
    source: String,
}

/// One registered message plus its deduped read count, for the dashboard.
#[derive(Serialize)]
struct MessageRecord {
    id: String,
    #[serde(rename = "wxId")]
    wx_id: String,
    content: String,
    timestamp: String,
    reads: i64,
    /// Conversation id of the chat this message was sent in (empty when the
    /// sender's client predates the talker field). `@chatroom` suffix = group.
    talker: String,
    /// Human-readable conversation name (remark/nickname or group name).
    chat_name: String,
    countries: Vec<String>,
    cities: Vec<String>,
    devices: Vec<String>,
    os_list: Vec<String>,
    browsers: Vec<String>,
}

/// Individual read event record (one row per visitor / distinct IP)
#[derive(Serialize)]
struct ReadRecord {
    /// Sender wxId of the message this read belongs to (mirrors the reference
    /// read-receipt-tracker, where every read row carries the sender wxId so the
    /// dashboard can label each IP with "who sent the message that was read").
    #[serde(rename = "wxId")]
    wx_id: String,
    ip: String,
    /// Stable per-browser visitor id (empty for legacy rows, where identity
    /// falls back to the IP alone).
    visitor_id: String,
    /// All IPs observed for this visitor, comma-separated.
    all_ips: String,
    /// Earliest read time for this visitor.
    first_timestamp: String,
    timestamp: String,
    country: String,
    city: String,
    isp: String,
    device_type: String,
    os_name: String,
    os_version: String,
    browser_name: String,
    browser_version: String,
    referrer: String,
    reader_wx_id: String,
    /// Nickname of the reader (reported by the WeKit client that rendered the
    /// incoming probing message), so the dashboard can label each probed IP
    /// with both WHO read it (wxid) and their display name.
    #[serde(rename = "readerNickname")]
    reader_nickname: String,
    /// Conversation id this read happened in (peer wxid or chatroom id).
    talker: String,
    /// Human-readable conversation name (remark/nickname or group name).
    chat_name: String,
    load_count: i64,
    /// Third-party enrichment fields (may be empty when all APIs fail).
    province: String,
    district: String,
    street: String,
    latitude: f64,
    longitude: f64,
    loc_source: String,
    /// Complete human-readable address, e.g.
    /// "中国 广东省 汕头市 潮阳区 和平镇" (empty when only local GeoIP is available).
    full_address: String,
}

/// Message detail response with pagination
#[derive(Serialize)]
struct MessageDetailResponse {
    summary: MessageSummary,
    reads: Vec<ReadRecord>,
    total: i64,
    page: u32,
    page_size: u32,
    total_pages: u32,
    wx_id: String,
    content: String,
    timestamp: String,
    /// Conversation id / name of the chat this message was sent in.
    talker: String,
    chat_name: String,
}

#[derive(Serialize)]
struct MessageSummary {
    unique_ips: i64,
    countries: JsonValue,
    cities: JsonValue,
    readers: i64,
    total_reads: i64,
}

struct LocalTimer;

impl tracing_subscriber::fmt::time::FormatTime for LocalTimer {
    fn format_time(&self, w: &mut tracing_subscriber::fmt::format::Writer<'_>) -> std::fmt::Result {
        let now = chrono::Local::now();
        write!(w, "{}", now.format("%y/%m/%d %H:%M:%S"))
    }
}

static PRINTER: OnceLock<Mutex<Option<Box<dyn ExternalPrinter + Send + Sync>>>> = OnceLock::new();

/// The TCP port the server bound to, published so the REPL commands can render
/// correct URLs. Set once at startup from `main`.
static PORT: OnceLock<u16> = OnceLock::new();

/// Base URL the operator uses to reach the server locally. The bind host may be
/// `0.0.0.0`, which isn't a usable target, so we always print `localhost` and
/// only fold in the configured port.
fn base_url() -> String {
    format!("http://localhost:{}", PORT.get().copied().unwrap_or(8080))
}

struct ReplWriter;

impl std::io::Write for ReplWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let msg = String::from_utf8_lossy(buf);
        write_log(&msg);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        std::io::stdout().flush()
    }
}

fn write_log(msg: &str) {
    if let Some(mutex) = PRINTER.get()
        && let Ok(mut opt) = mutex.lock()
        && let Some(p) = opt.as_mut()
    {
        let _ = p.print(msg.to_string());
        return;
    }

    let mut stdout = std::io::stdout();
    let _ = write!(stdout, "{}", msg);
    let _ = stdout.flush();
}

struct ReplHelper;

impl Helper for ReplHelper {}

impl Completer for ReplHelper {
    type Candidate = Pair;

    fn complete(
        &self,
        line: &str,
        pos: usize,
        _ctx: &rustyline::Context<'_>,
    ) -> rustyline::Result<(usize, Vec<Pair>)> {
        let mut candidates = Vec::new();

        let (start, word) = get_word_at_pos(line, pos);

        if word.starts_with('/') {
            let commands = &[
                "/sql ", "/exit", "/help", "/status", "/url ", "/tail ", "/query ", "/clear",
                "/open",
            ];
            for cmd in commands {
                if cmd.starts_with(word) {
                    candidates.push(Pair {
                        display: cmd.trim().to_string(),
                        replacement: cmd.to_string(),
                    });
                }
            }
        } else if line.trim_start().starts_with("/sql") {
            let sql_keywords = &[
                "SELECT",
                "INSERT",
                "UPDATE",
                "DELETE",
                "FROM",
                "WHERE",
                "LIMIT",
                "ORDER BY",
                "DESC",
                "INTO",
                "VALUES",
                "CREATE TABLE",
                "IF NOT EXISTS",
                "AND",
                "OR",
                "JOIN",
                "ON",
                "GROUP BY",
                "COUNT",
                "DISTINCT",
                "messages",
                "reads",
                "id",
                "wx_id",
                "content",
                "ip",
                "timestamp",
            ];

            let word_lower = word.to_lowercase();
            for &keyword in sql_keywords {
                if keyword.to_lowercase().starts_with(&word_lower) {
                    candidates.push(Pair {
                        display: keyword.to_string(),
                        replacement: keyword.to_string(),
                    });
                }
            }
        }

        Ok((start, candidates))
    }
}

fn get_word_at_pos(line: &str, pos: usize) -> (usize, &str) {
    let slice = &line[..pos];
    let start = slice
        .rfind(|c: char| !c.is_alphanumeric() && c != '_' && c != '/' && c != '-')
        .map(|idx| idx + 1)
        .unwrap_or(0);
    (start, &slice[start..])
}

impl Hinter for ReplHelper {
    type Hint = String;
}

impl Highlighter for ReplHelper {
    fn highlight<'l>(&self, line: &'l str, _pos: usize) -> Cow<'l, str> {
        let mut highlighted = line.to_string();

        if highlighted.starts_with("/exit") {
            highlighted = highlighted.replace("/exit", "\x1b[1;31m/exit\x1b[0m");
        } else if highlighted.starts_with("/clear") {
            highlighted = highlighted.replace("/clear", "\x1b[1;31m/clear\x1b[0m");
        } else {
            let other_cmds = &[
                "/help", "/status", "/open", "/sql", "/url", "/tail", "/query",
            ];
            for cmd in other_cmds {
                if highlighted.starts_with(cmd) {
                    highlighted =
                        highlighted.replacen(cmd, &format!("\x1b[1;32m{}\x1b[0m", cmd), 1);
                    break;
                }
            }
        }

        if line.starts_with("/sql") && highlighted.len() > "\x1b[1;32m/sql\x1b[0m".len() {
            let prefix_len = "\x1b[1;32m/sql\x1b[0m".len();
            let (prefix, sql_part) = highlighted.split_at(prefix_len);
            let colored_sql = highlight_sql(sql_part);
            highlighted = format!("{}{}", prefix, colored_sql);
        }

        Cow::Owned(highlighted)
    }
}

impl Validator for ReplHelper {}

fn highlight_sql(sql: &str) -> String {
    let mut result = String::new();
    let mut current_word = String::new();
    let mut in_string = false;

    for c in sql.chars() {
        if c == '\'' {
            if !current_word.is_empty() {
                result.push_str(&color_word(&current_word));
                current_word.clear();
            }
            in_string = !in_string;
            if in_string {
                result.push_str("\x1b[33m'");
            } else {
                result.push_str("'\x1b[0m");
            }
            continue;
        }

        if in_string {
            result.push(c);
            continue;
        }

        if c.is_alphanumeric() || c == '_' || c == '-' {
            current_word.push(c);
        } else {
            if !current_word.is_empty() {
                result.push_str(&color_word(&current_word));
                current_word.clear();
            }
            result.push(c);
        }
    }

    if !current_word.is_empty() {
        result.push_str(&color_word(&current_word));
    }

    result
}

fn color_word(word: &str) -> String {
    let word_upper = word.to_uppercase();
    match word_upper.as_str() {
        "SELECT" | "INSERT" | "UPDATE" | "DELETE" | "FROM" | "WHERE" | "LIMIT" | "ORDER" | "BY"
        | "DESC" | "INTO" | "VALUES" | "CREATE" | "TABLE" | "IF" | "NOT" | "EXISTS" | "AND"
        | "OR" | "JOIN" | "ON" | "GROUP" | "COUNT" | "DISTINCT" => {
            format!("\x1b[1;36m{}\x1b[0m", word) // Bold Cyan
        }
        "messages" | "reads" => {
            format!("\x1b[1;35m{}\x1b[0m", word) // Bold Magenta
        }
        "id" | "wx_id" | "content" | "ip" | "timestamp" | "ID" | "WX_ID" | "CONTENT" | "IP"
        | "TIMESTAMP" => {
            format!("\x1b[1;34m{}\x1b[0m", word) // Bold Blue
        }
        _ => word.to_string(),
    }
}

async fn handle_sql_command(
    conn: &libsql::Connection,
    sql: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let is_query = {
        let sql_lower = sql.trim().to_lowercase();
        sql_lower.starts_with("select")
            || sql_lower.starts_with("explain")
            || sql_lower.starts_with("pragma")
            || sql_lower.starts_with("with")
    };

    if is_query {
        let mut rows = conn.query(sql, ()).await?;
        let col_count = rows.column_count();
        if col_count == 0 {
            println!("Query returned 0 columns.");
            return Ok(());
        }

        let mut col_names = Vec::new();
        for i in 0..col_count {
            col_names.push(rows.column_name(i).unwrap_or("").to_string());
        }

        let mut all_rows = Vec::new();
        while let Some(row) = rows.next().await? {
            let mut row_vals = Vec::new();
            for i in 0..col_count {
                let val = row.get_value(i)?;
                let formatted = match val {
                    libsql::Value::Null => "NULL".to_string(),
                    libsql::Value::Integer(n) => n.to_string(),
                    libsql::Value::Real(f) => f.to_string(),
                    libsql::Value::Text(s) => s.clone(),
                    libsql::Value::Blob(b) => format!("BLOB ({} bytes)", b.len()),
                };
                row_vals.push(formatted);
            }
            all_rows.push(row_vals);
        }

        if all_rows.is_empty() {
            println!("0 rows returned.");
            return Ok(());
        }

        let mut col_widths = vec![0; col_count as usize];
        for i in 0..col_count as usize {
            col_widths[i] = col_names[i].len();
        }
        for row in &all_rows {
            for i in 0..col_count as usize {
                if row[i].len() > col_widths[i] {
                    col_widths[i] = row[i].len();
                }
            }
        }

        let print_separator = |col_widths: &[usize]| {
            print!("+");
            for &w in col_widths {
                print!("{}+", "-".repeat(w + 2));
            }
            println!();
        };

        print_separator(&col_widths);

        print!("|");
        for i in 0..col_count as usize {
            print!(" {:<width$} |", col_names[i], width = col_widths[i]);
        }
        println!();

        print_separator(&col_widths);

        for row in &all_rows {
            print!("|");
            for i in 0..col_count as usize {
                print!(" {:<width$} |", row[i], width = col_widths[i]);
            }
            println!();
        }

        print_separator(&col_widths);
        println!("{} rows in set", all_rows.len());
    } else {
        let rows_affected = conn.execute(sql, ()).await?;
        println!("Query OK, {rows_affected} rows affected");
    }

    Ok(())
}

fn handle_help_command() {
    println!("\x1b[1;36mAvailable commands:\x1b[0m");
    println!("  \x1b[1;32m/help\x1b[0m                       Show this help message");
    println!(
        "  \x1b[1;32m/status\x1b[0m                     Show server stats (messages, unique senders, reads, unique reader IPs)"
    );
    println!(
        "  \x1b[1;32m/url <wxId> <message>\x1b[0m       Register a message & print its tracking URL + HTML tag"
    );
    println!(
        "  \x1b[1;32m/tail [count]\x1b[0m               Show the latest [count] (default 10) read events in real-time"
    );
    println!(
        "  \x1b[1;32m/query <wxId>\x1b[0m               Show all tracked messages for a sender with their read counts"
    );
    println!(
        "  \x1b[1;32m/clear\x1b[0m                      Clear all tracked messages and reads from the database"
    );
    println!(
        "  \x1b[1;32m/open\x1b[0m                       Open the web dashboard in your default browser"
    );
    println!(
        "  \x1b[1;32m/sql <query>\x1b[0m                Execute arbitrary SQL queries on the database"
    );
    println!(
        "  \x1b[1;32m/exit\x1b[0m                       Shutdown the server and exit the REPL"
    );
}

async fn handle_status_command(
    conn: &libsql::Connection,
) -> Result<(), Box<dyn std::error::Error>> {
    async fn scalar(
        conn: &libsql::Connection,
        sql: &str,
    ) -> Result<i64, Box<dyn std::error::Error>> {
        let mut rows = conn.query(sql, ()).await?;
        Ok(match rows.next().await? {
            Some(row) => match row.get_value(0)? {
                libsql::Value::Integer(n) => n,
                _ => 0,
            },
            None => 0,
        })
    }

    let total_messages = scalar(conn, "SELECT COUNT(*) FROM messages").await?;
    let unique_senders = scalar(conn, "SELECT COUNT(DISTINCT wx_id) FROM messages").await?;
    let total_reads = scalar(conn, "SELECT COUNT(*) FROM reads").await?;
    let unique_reader_ips = scalar(conn, "SELECT COUNT(DISTINCT ip) FROM reads").await?;

    let mut latest_rows = conn
        .query(
            "SELECT timestamp FROM reads ORDER BY timestamp DESC LIMIT 1",
            (),
        )
        .await?;
    let latest_read = match latest_rows.next().await? {
        Some(row) => match row.get_value(0)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "N/A".to_string(),
        },
        None => "N/A".to_string(),
    };

    println!("\x1b[1;36m--- Server Status ---\x1b[0m");
    println!("Server address:        \x1b[1;32m{}\x1b[0m", base_url());
    println!("Tracked messages:      \x1b[1;33m{}\x1b[0m", total_messages);
    println!("Unique senders:        \x1b[1;33m{}\x1b[0m", unique_senders);
    println!("Total reads:           \x1b[1;33m{}\x1b[0m", total_reads);
    println!(
        "Unique reader IPs:     \x1b[1;33m{}\x1b[0m",
        unique_reader_ips
    );
    println!("Latest read time:      \x1b[1;33m{}\x1b[0m", latest_read);

    Ok(())
}

async fn handle_url_command(
    conn: &libsql::Connection,
    args: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let parts: Vec<&str> = args.splitn(2, char::is_whitespace).collect();
    if parts.len() < 2 || parts[0].is_empty() || parts[1].trim().is_empty() {
        println!("Usage: /url <wxId> <message>");
        return Ok(());
    }

    let wx_id = parts[0];
    let content = parts[1].trim();
    // Synthesize a createTime so re-running /url with identical text yields a fresh id.
    let create_time = Utc::now().timestamp_millis();
    let id = compute_msg_id(wx_id, content, create_time);
    let now = now_db_str();

    conn.execute(
        "INSERT INTO messages (id, wx_id, content, timestamp) VALUES (?1, ?2, ?3, ?4) \
         ON CONFLICT(id) DO NOTHING",
        (id.as_str(), wx_id, content, now),
    )
    .await?;

    let url = format!("{}/pixel?wxId={}&id={}", base_url(), wx_id, id);

    println!("\x1b[1;36mRegistered Tracking Message:\x1b[0m");
    println!("wxId:     \x1b[1;34m{}\x1b[0m", wx_id);
    println!("id:       \x1b[1;35m{}\x1b[0m", id);
    println!("URL:      \x1b[4;32m{}\x1b[0m", url);
    println!(
        "HTML Tag: \x1b[33m<img src=\"{}\" width=\"1\" height=\"1\" style=\"display:none;\" />\x1b[0m",
        url
    );
    Ok(())
}

async fn handle_tail_command(
    conn: &libsql::Connection,
    args: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    let count: i64 = args.trim().parse().unwrap_or(10);

    let mut rows = conn
        .query(
            "SELECT r.timestamp, r.ip, r.wx_id, COALESCE(m.content, '') \
         FROM reads r LEFT JOIN messages m ON r.id = m.id \
         ORDER BY r.timestamp DESC LIMIT ?1",
            libsql::params![count],
        )
        .await?;

    println!("\x1b[1;36m--- Latest {} Reads ---\x1b[0m", count);
    let mut found = 0;
    while let Some(row) = rows.next().await? {
        let timestamp = match row.get_value(0)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        let ip = match row.get_value(1)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        let wx_id = match row.get_value(2)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };
        let content = match row.get_value(3)? {
            libsql::Value::Text(s) => s.clone(),
            _ => "".to_string(),
        };

        println!(
            "\x1b[34m[{}]\x1b[0m ip: \x1b[32m{:<15}\x1b[0m | wxId: \x1b[35m{}\x1b[0m | msg: \x1b[33m{}\x1b[0m",
            timestamp, ip, wx_id, content
        );
        found += 1;
    }

    if found == 0 {
        println!("No reads recorded in the database.");
    }

    Ok(())
}

async fn handle_query_command(
    conn: &libsql::Connection,
    wx_id: &str,
) -> Result<(), Box<dyn std::error::Error>> {
    if wx_id.trim().is_empty() {
        println!("Usage: /query <wxId>");
        return Ok(());
    }
    let sql = format!(
        "SELECT m.timestamp, m.id, m.content, \
         (SELECT COUNT(DISTINCT r.ip) FROM reads r WHERE r.id = m.id) AS read_count \
         FROM messages m WHERE m.wx_id = '{}' ORDER BY m.timestamp DESC",
        wx_id.replace('\'', "''")
    );
    handle_sql_command(conn, &sql).await
}

async fn handle_clear_command(conn: &libsql::Connection) -> Result<(), Box<dyn std::error::Error>> {
    print!("Are you sure you want to clear all records? (y/N): ");
    let _ = std::io::stdout().flush();

    let mut response = String::new();
    if std::io::stdin().read_line(&mut response).is_ok() {
        let trimmed = response.trim().to_lowercase();
        if trimmed == "y" || trimmed == "yes" {
            conn.execute("DELETE FROM reads", ()).await?;
            let rows_affected = conn.execute("DELETE FROM messages", ()).await?;
            println!(
                "Database wiped successfully! Wiped \x1b[1;31m{}\x1b[0m messages (and all their reads).",
                rows_affected
            );
        } else {
            println!("Clear cancelled.");
        }
    }
    Ok(())
}

fn handle_open_command() {
    let url = format!("{}/", base_url());
    println!("Opening {url} in default browser...");
    #[cfg(target_os = "linux")]
    let _ = std::process::Command::new("xdg-open").arg(&url).spawn();
    #[cfg(target_os = "macos")]
    let _ = std::process::Command::new("open").arg(&url).spawn();
    #[cfg(target_os = "windows")]
    let _ = std::process::Command::new("cmd")
        .args(["/C", "start", &url])
        .spawn();
}

async fn route_command(
    trimmed: &str,
    repl_conn: &libsql::Connection,
) -> Result<bool, Box<dyn std::error::Error>> {
    if trimmed == "/exit" {
        return Ok(true);
    } else if trimmed == "/help" {
        handle_help_command();
    } else if trimmed == "/status" {
        if let Err(e) = handle_status_command(repl_conn).await {
            println!("Error showing status: {e}");
        }
    } else if trimmed == "/clear" {
        if let Err(e) = handle_clear_command(repl_conn).await {
            println!("Error clearing database: {e}");
        }
    } else if trimmed == "/open" {
        handle_open_command();
    } else if let Some(sql) = trimmed.strip_prefix("/sql ") {
        if let Err(e) = handle_sql_command(repl_conn, sql.trim()).await {
            println!("Error executing SQL: {e}");
        }
    } else if let Some(args) = trimmed.strip_prefix("/url ") {
        if let Err(e) = handle_url_command(repl_conn, args.trim()).await {
            println!("Error registering URL: {e}");
        }
    } else if let Some(args) = trimmed.strip_prefix("/tail") {
        if let Err(e) = handle_tail_command(repl_conn, args.trim()).await {
            println!("Error tailing hits: {e}");
        }
    } else if let Some(wx_id) = trimmed.strip_prefix("/query ") {
        if let Err(e) = handle_query_command(repl_conn, wx_id.trim()).await {
            println!("Error querying sender: {e}");
        }
    } else {
        println!("Unknown command. Type /help to list available commands.");
    }
    Ok(false)
}

/// Returns the current time formatted in UTC as `YYYY-MM-DD HH:MM:SS`.
/// The database always stores UTC; the dashboard converts to Beijing time
/// (UTC+8) at display time, so old and new rows stay consistent.
fn now_db_str() -> String {
    Utc::now().format("%Y-%m-%d %H:%M:%S").to_string()
}

// ---------------------------------------------------------------------------
// Third-party IP geolocation chain.
//
// Priority: street-level (apizero) -> city-district level (ip9) -> city level
// (lddgo card) -> local GeoIP data already stored at pixel time. Results are
// cached per-IP so the hot pixel path is never slowed down and API rate
// limits are respected.
// ---------------------------------------------------------------------------

const APIZERO_KEY: &str = "sk_test_bd3d93a4197def9c64491b660fcabe9c9b647551465d78a2";
/// apizero allows ~3 req/s; keep a 350ms floor between street-level calls.
const APIZERO_MIN_INTERVAL_MS: i64 = 350;
/// Successful lookups are cached for one hour.
const API_CACHE_TTL_SECS: i64 = 3600;
/// Failed lookups are cached for ten minutes to avoid hammering dead sources.
const API_NEGATIVE_TTL_SECS: i64 = 600;
/// Street-level (apizero) results fetched on demand via the "街道级" button are
/// cached for 24h — the street API has a limited daily quota, so reuse heavily.
const STREET_CACHE_TTL_SECS: i64 = 86400;
/// Failed street lookups are cached for ten minutes to avoid re-hitting a dead
/// source every time the button is clicked.
const STREET_NEGATIVE_TTL_SECS: i64 = 600;

/// Percent-encodes the few characters that are not safe in a query value.
/// IP literals normally need no encoding; this is a defensive no-op for them.
fn url_encode_ip(ip: &str) -> String {
    ip.replace('%', "%25")
        .replace('&', "%26")
        .replace('=', "%3D")
        .replace('#', "%23")
        .replace(' ', "%20")
}

/// Returns true when the string contains at least one CJK (Chinese) character.
/// Used to reject garbage fragments that some APIs stuff into Chinese-only
/// address fields — e.g. apizero puts the ISP name ("Neimeng") into
/// `district`, which must not appear in the displayed address.
fn contains_cjk(s: &str) -> bool {
    s.chars().any(|c| c > '\u{2FFF}')
}

/// Tier 1 — street-level lookup via 极数本源 (apizero). Uses the API key.
async fn street_lookup(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    // Serialize apizero calls and enforce the QPS floor.
    let mut throttle = state.apizero_throttle.lock().await;
    let now_ms = Utc::now().timestamp_millis();
    let wait = APIZERO_MIN_INTERVAL_MS - (now_ms - *throttle);
    if wait > 0 {
        tokio::time::sleep(std::time::Duration::from_millis(wait as u64)).await;
    }
    *throttle = Utc::now().timestamp_millis();

    let url = format!(
        "https://v1.apizero.cn/api/ip-pro?ip={}&key={}",
        url_encode_ip(ip),
        APIZERO_KEY
    );

    // Retry once on transient failures (rate limit / upstream hiccup), so a
    // single flaky response does not surface as an error to the user.
    let mut parsed: Option<serde_json::Value> = None;
    for attempt in 0..2 {
        match state.http.get(&url).send().await {
            Ok(resp) => match resp.json::<serde_json::Value>().await {
                Ok(body) => {
                    parsed = Some(body);
                    break;
                }
                Err(_) => {}
            },
            Err(_) => {}
        }
        if attempt == 0 {
            tokio::time::sleep(std::time::Duration::from_millis(400)).await;
        }
    }
    let body = parsed?;
    if body["code"].as_i64() != Some(0) {
        // quota exhausted / upstream unavailable / invalid key -> next tier
        return None;
    }
    let d = &body["data"];
    let country = d["country"].as_str().unwrap_or("").to_string();
    let province = d["province"].as_str().unwrap_or("").to_string();
    let city = d["city"].as_str().unwrap_or("").to_string();
    // Fall through to the next tier when no usable location was resolved.
    if country.is_empty() && province.is_empty() && city.is_empty() {
        return None;
    }
    let street_alternatives: Vec<String> = d["street_alternatives"]
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .filter(|s| contains_cjk(s))
                .collect()
        })
        .unwrap_or_default();
    // apizero sometimes stuffs the ISP ("Neimeng") into `district` and leaves
    // `street` empty; only trust Chinese-looking values for Chinese fields.
    let district = d["district"].as_str().unwrap_or("").to_string();
    let district = if contains_cjk(&district) {
        district
    } else {
        String::new()
    };
    let street = d["street"].as_str().unwrap_or("").to_string();
    let street = if contains_cjk(&street) {
        street
    } else {
        String::new()
    };
    Some(ApiLocation {
        country,
        province,
        city,
        district,
        street,
        street_alternatives,
        isp: d["isp"].as_str().unwrap_or("").to_string(),
        latitude: d["latitude"].as_f64().unwrap_or(0.0),
        longitude: d["longitude"].as_f64().unwrap_or(0.0),
        source: "街道级".to_string(),
    })
}

/// Tier 2 — city-district lookup via ip9.com.cn (free, no key, IPv4+IPv6).
async fn district_lookup(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    let url = format!("https://ip9.com.cn/get?ip={}", url_encode_ip(ip));
    let resp = state.http.get(&url).send().await.ok()?;
    let body: serde_json::Value = resp.json().await.ok()?;
    if body["ret"].as_i64() != Some(200) {
        return None;
    }
    let d = &body["data"];
    let province = d["prov"].as_str().unwrap_or("").to_string();
    let city = d["city"].as_str().unwrap_or("").to_string();
    // Fall through to the next tier when no usable location was resolved.
    if province.is_empty() && city.is_empty() {
        return None;
    }
    Some(ApiLocation {
        country: d["country"].as_str().unwrap_or("").to_string(),
        province,
        city,
        district: d["area"].as_str().unwrap_or("").to_string(),
        street: String::new(),
        street_alternatives: Vec::new(),
        isp: d["isp"].as_str().unwrap_or("").to_string(),
        latitude: d["lat"]
            .as_str()
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(0.0),
        longitude: d["lng"]
            .as_str()
            .and_then(|s| s.parse::<f64>().ok())
            .unwrap_or(0.0),
        source: "市区级".to_string(),
    })
}

/// Parses the lddgo IP-card SVG, extracting the `您来自:...` location text.
fn parse_lddgo_svg(svg: &str) -> Option<ApiLocation> {
    let marker = "您来自:";
    let idx = svg.find(marker)?;
    let rest = &svg[idx + marker.len()..];
    let end = rest.find('<').unwrap_or(rest.len());
    let location = rest[..end].trim();
    if location.is_empty() {
        return None;
    }

    // location looks like "福建省 福州市 永泰县" (or shorter when only city is known)
    let parts: Vec<&str> = location.split_whitespace().collect();
    let strip = |s: &str, suf: &str| s.strip_suffix(suf).unwrap_or(s).to_string();
    let single = parts.len() == 1;
    // A single-segment location like "汕头市" (common for IPv6 answers from
    // lddgo) is a city, not a province — keep province empty in that case.
    let province = if single {
        String::new()
    } else {
        parts.first().map(|s| strip(s, "省")).unwrap_or_default()
    };
    let city = if single {
        parts.first().map(|s| strip(s, "市")).unwrap_or_default()
    } else {
        parts.get(1).map(|s| strip(s, "市")).unwrap_or_default()
    };
    Some(ApiLocation {
        country: String::new(),
        province,
        city,
        district: parts.get(2).map(|s| strip(s, "县")).unwrap_or_default(),
        street: String::new(),
        street_alternatives: Vec::new(),
        isp: String::new(),
        latitude: 0.0,
        longitude: 0.0,
        source: "市级别".to_string(),
    })
}

/// Strips known province/city/district prefixes from an alternative address
/// string (e.g. "福建福州永泰城峰镇" -> "城峰镇") to derive candidate street names.
fn extract_street_candidate(alt: &str, province: &str, city: &str, district: &str) -> String {
    let mut s = alt.trim().to_string();
    for prefix in [province, city, district] {
        if prefix.is_empty() || s.is_empty() {
            continue;
        }
        if s.starts_with(prefix) {
            s = s[prefix.len()..].to_string();
            continue;
        }
        // Try matching without the administrative suffix (省/市/县/区).
        let mut matched = false;
        for suf in ["省", "市", "县", "区"] {
            if let Some(stripped) = prefix.strip_suffix(suf) {
                if !stripped.is_empty() && s.starts_with(stripped) {
                    s = s[stripped.len()..].to_string();
                    matched = true;
                    break;
                }
            }
        }
        if !matched {
            // Prefix not present; stop trimming to keep the remainder intact.
            break;
        }
    }
    s
}

/// Builds the street display: primary street plus candidate alternatives,
/// joined with "/" (e.g. "和平镇/大洋镇").
fn build_street_display(
    street: &str,
    alternatives: &[String],
    province: &str,
    city: &str,
    district: &str,
) -> String {
    let mut names: Vec<String> = Vec::new();
    if !street.is_empty() {
        names.push(street.to_string());
    }
    for alt in alternatives {
        let cand = extract_street_candidate(alt, province, city, district);
        if !cand.is_empty() && !names.contains(&cand) {
            names.push(cand);
        }
    }
    names.join("/")
}

/// Builds the complete human-readable address from every available level,
/// e.g. "中国 广东省 汕头市 潮阳区 和平镇/大洋镇". Levels that are unknown
/// are simply skipped, so the result is as complete as the data allows.
fn build_full_address(loc: &ApiLocation) -> String {
    let street_display = build_street_display(
        &loc.street,
        &loc.street_alternatives,
        &loc.province,
        &loc.city,
        &loc.district,
    );
    let mut parts: Vec<&str> = Vec::new();
    for p in [&loc.country, &loc.province, &loc.city, &loc.district] {
        if !p.is_empty() {
            parts.push(p.as_str());
        }
    }
    if !street_display.is_empty() {
        parts.push(&street_display);
    }
    parts.join(" ")
}

/// Tier 3 — city-level lookup via lddgo's IP address card. The endpoint returns
/// an SVG image; the location is embedded as a `您来自:...` text node.
/// Works for both IPv4 and IPv6 (verified: IPv6 like `240e:47f:...` resolves
/// to the correct city, which the street/district tiers mis-handle).
async fn city_lookup(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    let url = format!(
        "https://openapi.lddgo.net/base/gservice/api/v1/ip-card?ip={}",
        url_encode_ip(ip)
    );
    let resp = state.http.get(&url).send().await.ok()?;
    let svg = resp.text().await.ok()?;
    parse_lddgo_svg(&svg)
}

/// Returns true for IPv6 addresses in the `240e:47f::/32` block (China Telecom
/// mobile range). For these the street-level (apizero) and district-level (ip9)
/// APIs return wrong/random locations, while the city-level (lddgo) API is
/// reliable — so they are resolved straight to the city tier.
/// Extend this list with more prefixes if similar unreliable ranges show up.
fn is_special_ipv6(ip: &str) -> bool {
    ip.trim().to_lowercase().starts_with("240e:47f:")
}

/// Backfills missing country/province from the local GeoIP database when the
/// third-party API returned a partial answer:
/// - lddgo's single-city IPv6 answers (e.g. "汕头市") carry no country, while
///   the pixel-time GeoIP stored the English name ("China") — prefer the
///   Chinese name from the mmdb;
/// - the same single-city answers have no province; the local mmdb has no city
///   for these mobile IPv6 ranges, but its subdivision data is reliable at the
///   province level when present.
fn fill_missing_from_geoip(state: &Arc<AppState>, ip: &str, loc: &mut ApiLocation) {
    let Ok(ip_addr) = ip.parse::<IpAddr>() else { return };
    let guard = state.geoip.lock().unwrap();
    let Some(reader) = guard.as_ref() else { return };
    let Ok(geo) = reader.lookup::<serde_json::Value>(ip_addr) else { return };
    let names = |v: &serde_json::Value| -> Option<String> {
        v.get("names")
            .and_then(|n| n.get("zh-CN").or_else(|| n.get("en")))
            .and_then(|s| s.as_str())
            .map(|s| s.to_string())
    };

    // Country: fill when empty or when the stored/returned name is not Chinese
    // (e.g. GeoIP's "China" written at pixel time).
    if loc.country.is_empty() || !loc.country.chars().any(|c| c > '\u{2FFF}') {
        if let Some(c) = geo.get("country").and_then(names) {
            loc.country = c;
        }
    }
    // Province: only fill when the API gave us a city but no province.
    if loc.province.is_empty() && !loc.city.is_empty() {
        if let Some(subs) = geo.get("subdivisions").and_then(|s| s.as_array()) {
            if let Some(sub) = subs.first() {
                if let Some(p) = names(sub) {
                    loc.province = p;
                }
            }
        }
    }
}

/// Last-resort geolocation from the local GeoIP mmdb. Only used when the whole
/// third-party chain (district → city) failed, per the user's priority:
/// 市区级 → 市级 → 本地库(最后手段). The mmdb data is the least trusted
/// (it is what caused wrong locations before), so it is deliberately ranked
/// last and marked with `loc_source = 本地库`.
fn geoip_fallback(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    let Ok(ip_addr) = ip.parse::<IpAddr>() else { return None };
    let guard = state.geoip.lock().unwrap();
    let Some(reader) = guard.as_ref() else { return None };
    let Ok(geo) = reader.lookup::<serde_json::Value>(ip_addr) else { return None };
    let names = |v: &serde_json::Value| -> Option<String> {
        v.get("names")
            .and_then(|n| n.get("zh-CN").or_else(|| n.get("en")))
            .and_then(|s| s.as_str())
            .map(|s| s.to_string())
    };
    let country = geo.get("country").and_then(names).unwrap_or_default();
    let province = geo
        .get("subdivisions")
        .and_then(|s| s.as_array())
        .and_then(|a| a.first())
        .and_then(names)
        .unwrap_or_default();
    let city = geo.get("city").and_then(names).unwrap_or_default();
    if country.is_empty() && city.is_empty() {
        return None;
    }
    Some(ApiLocation {
        country,
        province,
        city,
        district: String::new(),
        street: String::new(),
        street_alternatives: Vec::new(),
        isp: geo
            .get("traits")
            .and_then(|t| t.get("isp"))
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        latitude: 0.0,
        longitude: 0.0,
        source: "本地库".to_string(),
    })
}

/// Normalizes a city name for comparison (strips 市/县 suffixes) so that
/// "广州市" from one API and "广州" from another are treated as equal.
/// Returns false when either side is empty after normalization.
fn same_city(a: &str, b: &str) -> bool {
    let norm = |s: &str| s.trim().trim_end_matches('市').trim_end_matches('县').to_string();
    let na = norm(a);
    let nb = norm(b);
    !na.is_empty() && na == nb
}

/// Runs the default geolocation chain for one IP with caching.
///
/// Priority (per user requirements, 2026-08-06):
/// 1. **市级** (lddgo) — resolves almost every IP to the city level;
/// 2. **市区级 as enrichment only** — query ip9 *after* the city tier and keep
///    its district/province/coords only when its city agrees with the
///    city-tier result; if it disagrees, discard it entirely (the city-tier
///    location is authoritative). Special mobile IPv6 is skipped here (ip9
///    returns random locations for those ranges);
/// 3. **本地库 mmdb** — last resort when the whole third-party chain fails.
///
/// Street-level (apizero) is NOT part of the default chain: it is fetched on
/// demand via the per-IP "街道级" button (limited daily quota). A street-level
/// result already fetched for this IP (cached in street_cache) takes priority,
/// so a manually refined address persists across page loads.
async fn enrich_ip(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    let now = Utc::now().timestamp();

    // 1. Street-level result previously fetched via the "街道级" button?
    {
        let cache = state.street_cache.lock().unwrap();
        if let Some((loc, fetched_at)) = cache.get(ip) {
            let ttl = if loc.is_some() {
                STREET_CACHE_TTL_SECS
            } else {
                STREET_NEGATIVE_TTL_SECS
            };
            if now - *fetched_at < ttl {
                return loc.clone();
            }
        }
    }

    // 2. Default chain cache hit?
    {
        let cache = state.ip_cache.lock().unwrap();
        if let Some((loc, fetched_at)) = cache.get(ip) {
            let ttl = if loc.is_some() {
                API_CACHE_TTL_SECS
            } else {
                API_NEGATIVE_TTL_SECS
            };
            if now - *fetched_at < ttl {
                return loc.clone();
            }
        }
    }

    // 3. City tier first — lddgo resolves almost every IP to the city level.
    let mut result = city_lookup(state, ip).await;

    // 4. District tier as an *enrichment* step only: query ip9 again after the
    //    city tier, and keep its data ONLY when its city agrees with the
    //    city-tier city. If it disagrees, discard it entirely — the city-tier
    //    location is authoritative. Special mobile IPv6 is skipped (ip9
    //    returns random locations for those ranges).
    if result.is_some() && !is_special_ipv6(ip) {
        if let Some(d) = district_lookup(state, ip).await {
            let city_a = result.as_ref().unwrap().city.clone();
            let city_b = d.city.clone();
            if !city_a.is_empty() && same_city(&city_a, &city_b) {
                let loc = result.as_mut().unwrap();
                if !d.district.is_empty() {
                    loc.district = d.district;
                    loc.source = "市级别+区级".to_string();
                }
                // The city-tier answer sometimes lacks the province (lddgo's
                // single-segment IPv6 answers); ip9 agreeing on the city makes
                // its province trustworthy — fill it in for a complete address.
                if loc.province.is_empty() && !d.province.is_empty() {
                    loc.province = d.province;
                }
                // Coordinates are only available from ip9 — adopt them when the
                // city-tier result has none.
                if loc.latitude == 0.0 && loc.longitude == 0.0 {
                    loc.latitude = d.latitude;
                    loc.longitude = d.longitude;
                }
                if loc.isp.is_empty() && !d.isp.is_empty() {
                    loc.isp = d.isp;
                }
            }
        }
    }

    // 5. Last resort: the local GeoIP mmdb (least trusted — only used when the
    //    whole third-party chain failed). Marked with loc_source = 本地库.
    if result.is_none() {
        result = geoip_fallback(state, ip);
    }

    // lddgo's single-city answers for IPv6 lack the province; backfill it
    // from the local GeoIP subdivision data.
    if let Some(loc) = result.as_mut() {
        fill_missing_from_geoip(state, ip, loc);
    }

    // Update default cache (including negative results) and keep it bounded.
    {
        let mut cache = state.ip_cache.lock().unwrap();
        cache.insert(ip.to_string(), (result.clone(), now));
        if cache.len() > 5000 {
            cache.retain(|_, (_, t)| now - *t < API_CACHE_TTL_SECS);
        }
    }
    result
}

/// Fetches the street-level (apizero) location for one IP on demand (the
/// "街道级" button), with its own cache so repeated clicks and page reloads
/// never burn the limited daily quota twice.
async fn street_lookup_cached(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    let now = Utc::now().timestamp();
    {
        let cache = state.street_cache.lock().unwrap();
        if let Some((loc, fetched_at)) = cache.get(ip) {
            let ttl = if loc.is_some() {
                STREET_CACHE_TTL_SECS
            } else {
                STREET_NEGATIVE_TTL_SECS
            };
            if now - *fetched_at < ttl {
                return loc.clone();
            }
        }
    }

    let result = street_lookup(state, ip).await;

    {
        let mut cache = state.street_cache.lock().unwrap();
        cache.insert(ip.to_string(), (result.clone(), now));
        if cache.len() > 5000 {
            cache.retain(|_, (_, t)| now - *t < STREET_CACHE_TTL_SECS);
        }
    }
    result
}

/// GET /locate/street?ip=... — triggered by the per-IP "街道级" button in the
/// message detail view. Runs the street-level (apizero) lookup on demand and
/// returns the refined address; the result is cached for 24h so the limited
/// daily quota is spent at most once per IP.
async fn street_locate_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let ip = params.get("ip").cloned().unwrap_or_default();
    if ip.trim().is_empty() {
        return Err((StatusCode::BAD_REQUEST, "missing `ip` query parameter".to_string()));
    }
    // Basic sanity check so we never pass garbage to the upstream API.
    if !ip.contains('.') && !ip.contains(':') {
        return Err((StatusCode::BAD_REQUEST, "invalid IP address".to_string()));
    }

    match street_lookup_cached(&state, &ip).await {
        Some(loc) => Ok(Json(serde_json::json!({
            "ok": true,
            "ip": ip,
            "full_address": build_full_address(&loc),
            "country": loc.country,
            "province": loc.province,
            "city": loc.city,
            "district": loc.district,
            "street": loc.street,
            "street_alternatives": loc.street_alternatives,
            "loc_source": loc.source,
            "latitude": loc.latitude,
            "longitude": loc.longitude,
        }))),
        None => Ok(Json(serde_json::json!({
            "ok": false,
            "ip": ip,
            "error": "街道级定位失败（接口限流、配额耗尽或该 IP 暂无街道数据），已保留原有定位",
        }))),
    }
}

/// Forces a fresh district-level (ip9) lookup for one IP (the "市区级" button).
/// The result is written back into the default `ip_cache`, so a subsequent
/// detail reload keeps showing the district-level answer until the user
/// re-resolves with another level (mirrors `city_locate_refresh`). Falls back
/// to the local GeoIP mmdb when ip9 fails.
async fn district_locate_refresh(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    // Special mobile IPv6 ranges (240e:47f::/32) return random/wrong answers
    // from ip9 — skip the district API for those and go straight to fallback.
    let mut result = if is_special_ipv6(ip) {
        None
    } else {
        district_lookup(state, ip).await
    };
    if result.is_none() {
        result = geoip_fallback(state, ip);
    }

    if let Some(loc) = result.as_mut() {
        if loc.source.is_empty() {
            loc.source = "市区级".to_string();
        }
    }

    let now = Utc::now().timestamp();
    let mut cache = state.ip_cache.lock().unwrap();
    cache.insert(ip.to_string(), (result.clone(), now));
    if cache.len() > 5000 {
        cache.retain(|_, (_, t)| now - *t < API_CACHE_TTL_SECS);
    }
    drop(cache);
    result
}

/// GET /locate/district?ip=... — triggered by the per-IP "市区级" button in the
/// message detail view. Resolves the district-level (ip9) location on demand
/// and refreshes the cached answer so the dashboard reflects the new state.
async fn district_locate_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let ip = params.get("ip").cloned().unwrap_or_default();
    if ip.trim().is_empty() {
        return Err((StatusCode::BAD_REQUEST, "missing `ip` query parameter".to_string()));
    }
    if !ip.contains('.') && !ip.contains(':') {
        return Err((StatusCode::BAD_REQUEST, "invalid IP address".to_string()));
    }

    // Special mobile IPv6: ip9 is unreliable for these ranges, refuse politely.
    if is_special_ipv6(&ip) {
        return Ok(Json(serde_json::json!({
            "ok": false,
            "ip": ip,
            "error": "该 IP 属于特殊移动 IPv6 段，市区级接口不可靠，建议使用市级定位",
        })));
    }

    match district_locate_refresh(&state, &ip).await {
        Some(loc) => Ok(Json(serde_json::json!({
            "ok": true,
            "ip": ip,
            "full_address": build_full_address(&loc),
            "country": loc.country,
            "province": loc.province,
            "city": loc.city,
            "district": loc.district,
            "loc_source": loc.source,
            "latitude": loc.latitude,
            "longitude": loc.longitude,
        }))),
        None => Ok(Json(serde_json::json!({
            "ok": false,
            "ip": ip,
            "error": "市区级定位失败（接口不可用），已保留原有定位",
        }))),
    }
}

/// Forces a fresh city-level (lddgo) lookup for one IP, bypassing the default
/// cache so the "市级" button really re-resolves the location. The result is
/// written back into the default `ip_cache`, so a subsequent detail reload uses
/// the refreshed answer too. Falls back to the local GeoIP mmdb when lddgo
/// fails, mirroring the default chain's last resort.
async fn city_locate_refresh(state: &Arc<AppState>, ip: &str) -> Option<ApiLocation> {
    let mut result = city_lookup(state, ip).await;
    if let Some(loc) = result.as_mut() {
        // lddgo's single-city IPv6 answers lack country/province; backfill from mmdb.
        fill_missing_from_geoip(state, ip, loc);
        if loc.source.is_empty() {
            loc.source = "市级别".to_string();
        }
    } else {
        result = geoip_fallback(state, ip);
    }

    let now = Utc::now().timestamp();
    let mut cache = state.ip_cache.lock().unwrap();
    cache.insert(ip.to_string(), (result.clone(), now));
    if cache.len() > 5000 {
        cache.retain(|_, (_, t)| now - *t < API_CACHE_TTL_SECS);
    }
    drop(cache);
    result
}

/// GET /locate/city?ip=... — triggered by the per-IP "市级" button in the
/// message detail view. Re-resolves the city-level location on demand and
/// refreshes the cached answer so the dashboard reflects the new state.
async fn city_locate_handler(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let ip = params.get("ip").cloned().unwrap_or_default();
    if ip.trim().is_empty() {
        return Err((StatusCode::BAD_REQUEST, "missing `ip` query parameter".to_string()));
    }
    if !ip.contains('.') && !ip.contains(':') {
        return Err((StatusCode::BAD_REQUEST, "invalid IP address".to_string()));
    }

    match city_locate_refresh(&state, &ip).await {
        Some(loc) => Ok(Json(serde_json::json!({
            "ok": true,
            "ip": ip,
            "full_address": build_full_address(&loc),
            "country": loc.country,
            "province": loc.province,
            "city": loc.city,
            "district": loc.district,
            "street": loc.street,
            "loc_source": loc.source,
            "latitude": loc.latitude,
            "longitude": loc.longitude,
        }))),
        None => Ok(Json(serde_json::json!({
            "ok": false,
            "ip": ip,
            "error": "市级定位失败（接口不可用），已保留原有定位",
        }))),
    }
}

/// Enriches a slice of read records in parallel using the cached API chain.
/// Existing country/city/isp values are kept when the APIs return nothing.
async fn enrich_reads(state: &Arc<AppState>, reads: &mut [ReadRecord]) {
    let ips: Vec<String> = reads.iter().map(|r| r.ip.clone()).collect();
    let results: Vec<Option<ApiLocation>> =
        futures_util::future::join_all(ips.iter().map(|ip| {
            let state = Arc::clone(state);
            let ip = ip.clone();
            async move { enrich_ip(&state, &ip).await }
        }))
        .await;

    for (rec, loc) in reads.iter_mut().zip(results.into_iter()) {
        let Some(loc) = loc else { continue };
        // Compute the complete address before any field is moved out of `loc`.
        let full_address = build_full_address(&loc);
        if !loc.country.is_empty() {
            rec.country = loc.country;
        }
        rec.province = loc.province;
        rec.district = loc.district;
        rec.street = loc.street;
        if !loc.city.is_empty() {
            rec.city = loc.city;
        }
        if !loc.isp.is_empty() {
            rec.isp = loc.isp;
        }
        rec.latitude = loc.latitude;
        rec.longitude = loc.longitude;
        rec.full_address = full_address;
        rec.loc_source = loc.source;
    }
}

/// Loads the optional GeoIP2 City database from the working directory.
/// Returns `None` (lookup silently disabled) when the file is absent.
fn load_geoip_reader() -> Option<Reader> {
    let candidates = ["GeoLite2-City.mmdb", "GeoLite2-City.mmdb.gz"];
    for path in candidates {
        match Reader::open(path) {
            Ok(reader) => {
                info!("GeoIP database loaded from {path}");
                return Some(reader);
            }
            Err(e) => {
                debug!("could not open {path}: {e}");
            }
        }
    }
    warn!("GeoLite2-City.mmdb not found in working directory — GeoIP lookup disabled");
    None
}

/// Adds any columns missing from an older `reads` table. Idempotent: columns
/// that already exist are left untouched.
async fn ensure_reads_columns(
    conn: &libsql::Connection,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut rows = conn.query("PRAGMA table_info(reads)", ()).await?;
    let mut existing = std::collections::HashSet::new();
    while let Some(row) = rows.next().await? {
        if let Ok(name) = row.get_str(1) {
            existing.insert(name.to_string());
        }
    }

    let wanted: &[(&str, &str)] = &[
        ("country", "TEXT"),
        ("city", "TEXT"),
        ("reader_wx_id", "TEXT"),
        ("reader_nickname", "TEXT"),
        ("talker", "TEXT"),
        ("chat_name", "TEXT"),
        ("device_type", "TEXT"),
        ("os_name", "TEXT"),
        ("os_version", "TEXT"),
        ("browser_name", "TEXT"),
        ("browser_version", "TEXT"),
        ("isp", "TEXT"),
        ("referrer", "TEXT"),
        ("msg_id", "TEXT"),
        ("created_at", "INTEGER"),
        ("visitor_id", "TEXT"),
    ];

    for (col, ty) in wanted {
        if !existing.contains(*col) {
            conn.execute(&format!("ALTER TABLE reads ADD COLUMN {col} {ty}"), ())
                .await?;
            info!("migrated reads table: added column {col}");
        }
    }
    Ok(())
}

/// Adds any columns missing from an older `messages` table. Idempotent.
async fn ensure_messages_columns(
    conn: &libsql::Connection,
) -> Result<(), Box<dyn std::error::Error>> {
    let mut rows = conn.query("PRAGMA table_info(messages)", ()).await?;
    let mut existing = std::collections::HashSet::new();
    while let Some(row) = rows.next().await? {
        if let Ok(name) = row.get_str(1) {
            existing.insert(name.to_string());
        }
    }

    let wanted: &[(&str, &str)] = &[
        ("talker", "TEXT"),
        ("chat_name", "TEXT"),
    ];

    for (col, ty) in wanted {
        if !existing.contains(*col) {
            conn.execute(&format!("ALTER TABLE messages ADD COLUMN {col} {ty}"), ())
                .await?;
            info!("migrated messages table: added column {col}");
        }
    }
    Ok(())
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::io::IsTerminal;
    let is_terminal = std::io::stdin().is_terminal();

    // Auto-backup the local database at startup (timestamped copy) so that an
    // accidental mass-delete can always be rolled back from backups/.
    fn backup_db_on_startup(path: &str) {
        if !std::path::Path::new(path).exists() {
            return;
        }
        let dir = std::path::Path::new(path)
            .parent()
            .unwrap_or_else(|| std::path::Path::new("."))
            .join("backups");
        if std::fs::create_dir_all(&dir).is_err() {
            return;
        }
        let ts = chrono::Utc::now().format("%Y%m%d_%H%M%S");
        let dest = dir.join(format!("read_receipts_{}.db", ts));
        match std::fs::copy(path, &dest) {
            Ok(_) => info!("database backup saved to {}", dest.display()),
            Err(e) => warn!("database backup failed: {e}"),
        }
    }

    let rl = if is_terminal {
        match rustyline::Editor::<ReplHelper, rustyline::history::FileHistory>::new() {
            Ok(mut r) => {
                r.set_helper(Some(ReplHelper));
                if let Ok(printer) = r.create_external_printer() {
                    let _ = PRINTER.set(Mutex::new(Some(Box::new(printer))));
                }
                Some(r)
            }
            Err(_) => None,
        }
    } else {
        None
    };

    tracing_subscriber::fmt()
        .with_writer(|| ReplWriter)
        .with_timer(LocalTimer)
        .with_target(false)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "debug".into())
                .add_directive("rustyline=warn".parse().unwrap()),
        )
        .init();

    let db_url =
        std::env::var("TURSO_DATABASE_URL").unwrap_or_else(|_| "file:read_receipts.db".to_string());
    let auth_token = std::env::var("TURSO_AUTH_TOKEN").unwrap_or_default();
    let is_local = db_url.starts_with("file:");
    let db_path = db_url.replace("file:", "");

    let db = if is_local {
        Builder::new_local(db_path.clone()).build().await?
    } else {
        Builder::new_remote(db_url, auth_token).build().await?
    };

    let conn = db.connect()?;
    let repl_conn = db.connect()?;

    // Snapshot the current database before the server starts serving traffic,
    // so any accidental mass-delete later can be rolled back from backups/.
    if is_local {
        backup_db_on_startup(&db_path);
    }

    // messages: registered by the sender before tampering. PK = deterministic hash of (wx_id + content).
    conn.execute(
        "CREATE TABLE IF NOT EXISTS messages (
            id          TEXT PRIMARY KEY,
            wx_id       TEXT NOT NULL,
            content     TEXT NOT NULL,
            timestamp   TEXT NOT NULL,
            create_time INTEGER,
            created_at  INTEGER,
            talker      TEXT,
            chat_name   TEXT
        );",
        (),
    )
    .await?;

    // reads: one row per tracking-pixel hit. Reader identity is approximated by distinct IP.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS reads (
            id               TEXT NOT NULL,
            wx_id            TEXT NOT NULL,
            ip               TEXT NOT NULL,
            timestamp        TEXT NOT NULL,
            country          TEXT,
            city             TEXT,
            reader_wx_id     TEXT,
            reader_nickname  TEXT,
            device_type      TEXT,
            os_name          TEXT,
            os_version       TEXT,
            browser_name     TEXT,
            browser_version  TEXT,
            isp              TEXT,
            referrer         TEXT,
            msg_id           TEXT,
            created_at       INTEGER,
            visitor_id       TEXT
        );",
        (),
    )
    .await?;

    // Databases created by older server versions only have the 4 base columns;
    // add any missing columns so the rich pixel logging keeps working.
    ensure_reads_columns(&conn).await?;
    ensure_messages_columns(&conn).await?;

    // One-time cleanup of legacy duplicates. Before the dedup logic below
    // existed, three things produced duplicate rows for one real read:
    //   a) an empty-visitor /read-report row + a cookie /pixel row for the same
    //      (msg_id, ip);
    //   b) a reader-less row duplicating a row that carries reader info for the
    //      same (msg_id, ip);
    //   c) WeChat's built-in browser mints a fresh visitor id per image request
    //      (it does not persist cookies), so repeated opens used to create N
    //      rows for the same (msg_id, ip).
    // Keep at most ONE row per (msg_id, ip), preferring reader info.
    conn.execute(
        "DELETE FROM reads WHERE rowid IN (
            SELECT r.rowid FROM reads r
            WHERE (r.reader_wx_id IS NULL OR r.reader_wx_id = '')
              AND EXISTS (
                SELECT 1 FROM reads r2
                WHERE r2.msg_id = r.msg_id AND r2.ip = r.ip AND r2.rowid != r.rowid
                  AND r2.reader_wx_id IS NOT NULL AND r2.reader_wx_id != ''
              )
        )",
        (),
    )
    .await?;
    conn.execute(
        "DELETE FROM reads WHERE rowid NOT IN (
            SELECT r.rowid FROM reads r
            WHERE r.rowid IN (SELECT MAX(rowid) FROM reads GROUP BY msg_id, ip)
        )",
        (),
    )
    .await?;
    conn.execute(
        "DELETE FROM reads WHERE rowid IN (
            SELECT r.rowid FROM reads r
            WHERE (r.visitor_id IS NULL OR r.visitor_id = '')
              AND EXISTS (
                SELECT 1 FROM reads r2
                WHERE r2.msg_id = r.msg_id AND r2.ip = r.ip AND r2.rowid != r.rowid
                  AND r2.visitor_id IS NOT NULL AND r2.visitor_id != ''
              )
        )",
        (),
    )
    .await?;
    info!("legacy duplicate read rows cleaned");

    // Broadcast channel used to push real-time updates to dashboard clients.
    let (ws_tx, _ws_rx) = broadcast::channel::<String>(1024);

    // Optional GeoIP2 City database for country/city/ISP enrichment.
    let geoip = Arc::new(Mutex::new(load_geoip_reader()));

    // HTTP client for the third-party IP geolocation chain.
    let http = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(8))
        .user_agent("wekit-read-receipts-server/0.1")
        .build()
        .map_err(|e| format!("failed to build HTTP client: {e}"))?;

    let ip_cache = Arc::new(Mutex::new(HashMap::new()));
    let street_cache = Arc::new(Mutex::new(HashMap::new()));
    let apizero_throttle = Arc::new(tokio::sync::Mutex::new(0i64));

    let app = Router::new()
        .route("/", get(serve_index))
        .route("/register", post(register_message))
        .route("/read-report", post(read_report))
        .route("/pixel", get(serve_tracking_pixel))
        .route("/count", get(read_count))
        .route("/health", get(health_check))
        .route("/batch-status", get(batch_status))
        .route("/stats", get(global_stats))
        .route("/messages", get(list_messages).delete(delete_all_messages))
        .route("/messages/delete", post(delete_messages_batch))
        .route(
            "/messages/{wx_id}",
            get(list_messages_for_sender).delete(delete_messages_for_sender),
        )
        .route("/messages/{id}/detail", get(message_detail))
        .route("/reads/{id}", get(list_reads_for_message))
        .route("/locate/street", get(street_locate_handler))
        .route("/locate/city", get(city_locate_handler))
        .route("/locate/district", get(district_locate_handler))
        .route("/ws", get(ws_handler))
        .route("/media/bgm.mp3", get(serve_bgm))
        .with_state(Arc::new(AppState {
            db: conn,
            ws_tx,
            geoip,
            http,
            ip_cache,
            street_cache,
            apizero_throttle,
        }));

    // Bind host/port are configurable via env vars, falling back to 0.0.0.0:8080.
    // BIND_ADDR must parse as an IP address; PORT as a u16.
    let bind_host: std::net::IpAddr = std::env::var("BIND_ADDR")
        .unwrap_or_else(|_| "0.0.0.0".to_string())
        .parse()
        .map_err(|e| format!("invalid BIND_ADDR: {e}"))?;
    let bind_port: u16 = match std::env::var("PORT") {
        Ok(p) => p.parse().map_err(|e| format!("invalid PORT: {e}"))?,
        Err(_) => 8080,
    };
    let _ = PORT.set(bind_port);

    let addr = SocketAddr::from((bind_host, bind_port));
    info!("server launching on http://{addr}");

    let listener = tokio::net::TcpListener::bind(addr).await?;
    let (shutdown_tx, shutdown_rx) = tokio::sync::oneshot::channel::<()>();

    let server_handle = tokio::spawn(async move {
        if let Err(e) = axum::serve(
            listener,
            app.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(async move {
            let _ = shutdown_rx.await;
            info!("received shutdown signal, shutting down axum gracefully...");
        })
        .await
        {
            error!("server error: {e}");
        }
    });

    let mut run_fallback = !is_terminal;

    if is_terminal {
        if let Some(mut rl) = rl {
            loop {
                let readline = rl.readline(">> ");
                match readline {
                    Ok(line) => {
                        let trimmed = line.trim();
                        if trimmed.is_empty() {
                            continue;
                        }

                        let _ = rl.add_history_entry(line.as_str());

                        if route_command(trimmed, &repl_conn).await? {
                            break;
                        }
                    }
                    Err(rustyline::error::ReadlineError::Interrupted) => {
                        break;
                    }
                    Err(rustyline::error::ReadlineError::Eof) => {
                        break;
                    }
                    Err(rustyline::error::ReadlineError::Io(ref e))
                        if e.raw_os_error() == Some(25) =>
                    {
                        run_fallback = true;
                        break;
                    }
                    Err(err) => {
                        println!("Error: {:?}", err);
                        break;
                    }
                }
            }
        } else {
            run_fallback = true;
        }
    }

    if run_fallback {
        // No interactive terminal (e.g. running under systemd). There is no
        // usable stdin to drive the REPL, so instead of reading stdin — which
        // would hit EOF immediately and tear the server down — we park here
        // until the process receives a shutdown signal.
        #[cfg(unix)]
        {
            use tokio::signal::unix::{SignalKind, signal};
            let mut sigterm = signal(SignalKind::terminate())?;
            let mut sigint = signal(SignalKind::interrupt())?;
            tokio::select! {
                _ = sigterm.recv() => info!("received SIGTERM"),
                _ = sigint.recv() => info!("received SIGINT"),
            }
        }
        #[cfg(not(unix))]
        {
            let _ = tokio::signal::ctrl_c().await;
            info!("received ctrl-c");
        }
    }

    info!("exiting REPL, stopping server...");
    let _ = shutdown_tx.send(());
    let _ = server_handle.await;

    Ok(())
}

/// Serves the static index HTML page.
async fn serve_index() -> impl IntoResponse {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/html; charset=utf-8")
        .body(axum::body::Body::from(include_str!("../index.html")))
        .unwrap()
}

/// Background music embedded into the binary (glgl.tv-style). Served with a
/// long cache lifetime so the browser can loop it cheaply.
const BGM_MP3: &[u8] = include_bytes!("../media/bgm.mp3");

async fn serve_bgm() -> impl IntoResponse {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "audio/mpeg")
        .header(header::CACHE_CONTROL, "public, max-age=86400")
        .body(axum::body::Body::from(BGM_MP3.to_vec()))
        .unwrap()
}

/// Registers a message before it is tampered with. The server derives the
/// deterministic id from `(wxId, content)`, upserts the row (keeping the
/// original timestamp on re-registration), and returns the id to the client.
async fn register_message(
    State(state): State<Arc<AppState>>,
    body: String,
) -> Result<Json<RegisterResponse>, (StatusCode, String)> {
    // Diagnostic: log the RAW request body so we can see exactly what the
    // client uploaded (field names & values), incl. whether `content` is set.
    info!("/register RAW BODY: {body}");
    let req: RegisterRequest = serde_json::from_str(&body).map_err(|e| {
        (
            StatusCode::BAD_REQUEST,
            format!("invalid register JSON: {e}, body = {body}"),
        )
    })?;
    if req.wx_id.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "wxId must not be empty".to_string(),
        ));
    }

    let id = compute_msg_id(&req.wx_id, &req.content, req.create_time);
    let now = now_db_str();

    info!(
        "/register\nid = {id}, wxId = {}, createTime = {}, content = {}",
        req.wx_id, req.create_time, req.content
    );

    state
        .db
        .execute(
            "INSERT INTO messages (id, wx_id, content, timestamp, create_time, talker, chat_name) VALUES (?1, ?2, ?3, ?4, ?7, ?5, ?6)
             ON CONFLICT(id) DO UPDATE SET
                content = COALESCE(NULLIF(excluded.content, ''), messages.content),
                create_time = COALESCE(excluded.create_time, messages.create_time),
                talker = COALESCE(NULLIF(excluded.talker, ''), messages.talker),
                chat_name = COALESCE(NULLIF(excluded.chat_name, ''), messages.chat_name)",
            libsql::params![
                id.as_str(),
                req.wx_id.as_str(),
                req.content.as_str(),
                now,
                req.talker.as_deref().unwrap_or(""),
                req.chat_name.as_deref().unwrap_or(""),
                req.create_time
            ],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("register failed: {e}"),
            )
        })?;

    // Broadcast new message event to WebSocket clients
    let _ = state.ws_tx.send(serde_json::json!({
        "type": "new_message",
        "id": id
    }).to_string());

    // Broadcast updated stats
    broadcast_stats(&state).await;

    Ok(Json(RegisterResponse { id }))
}

/// GeoIP lookup result
struct GeoInfo {
    country: String,
    city: String,
    isp: String,
}

/// Performs GeoIP lookup for the given IP address.
fn lookup_geoip(geoip: &Arc<Mutex<Option<Reader>>>, ip: &str) -> GeoInfo {
    let mut result = GeoInfo {
        country: String::new(),
        city: String::new(),
        isp: String::new(),
    };

    // Parse IP address
    let ip_addr: IpAddr = match ip.parse() {
        Ok(addr) => addr,
        Err(_) => return result,
    };

    // Skip private IPs (check for IPv4 private ranges)
    let is_private = match ip_addr {
        IpAddr::V4(ipv4) => ipv4.is_loopback() || ipv4.is_private() || ipv4.is_link_local(),
        IpAddr::V6(ipv6) => ipv6.is_loopback() || ipv6.to_ipv4().map(|v4| v4.is_private()).unwrap_or(false),
    };
    if is_private {
        return result;
    }

    let guard = geoip.lock().unwrap();
    if let Some(reader) = guard.as_ref() {
        match reader.lookup::<serde_json::Value>(ip_addr) {
            Ok(geo) => {
                // Extract country
                if let Some(country) = geo.get("country") {
                    if let Some(names) = country.get("names") {
                        if let Some(en) = names.get("en") {
                            if let Some(country_str) = en.as_str() {
                                result.country = country_str.to_string();
                            }
                        }
                    }
                }
                // Extract city
                if let Some(city) = geo.get("city") {
                    if let Some(names) = city.get("names") {
                        if let Some(en) = names.get("en") {
                            if let Some(city_str) = en.as_str() {
                                result.city = city_str.to_string();
                            }
                        }
                    }
                }
                // Extract ISP (from traits)
                if let Some(traits) = geo.get("traits") {
                    if let Some(isp) = traits.get("isp") {
                        if let Some(isp_str) = isp.as_str() {
                            result.isp = isp_str.to_string();
                        }
                    }
                }
            }
            Err(e) => {
                debug!("GeoIP lookup failed for {}: {}", ip, e);
            }
        }
    }

    result
}

/// Parse user agent string for device/OS/browser info
fn parse_user_agent(ua: &str) -> (Option<String>, Option<String>, Option<String>, Option<String>, Option<String>) {
    let ua_lower = ua.to_lowercase();
    let mut device_type = None;
    let mut os_name = None;
    let mut os_version = None;
    let mut browser_name = None;
    let mut browser_version = None;

    // Device type
    if ua_lower.contains("mobile") || ua_lower.contains("android") || ua_lower.contains("iphone") {
        device_type = Some("mobile".to_string());
    } else if ua_lower.contains("tablet") || ua_lower.contains("ipad") {
        device_type = Some("tablet".to_string());
    } else {
        device_type = Some("desktop".to_string());
    }

    // OS
    if ua_lower.contains("windows nt 10.0") { os_name = Some("Windows".to_string()); os_version = Some("10".to_string()); }
    else if ua_lower.contains("windows nt 6.3") { os_name = Some("Windows".to_string()); os_version = Some("8.1".to_string()); }
    else if ua_lower.contains("windows nt 6.2") { os_name = Some("Windows".to_string()); os_version = Some("8".to_string()); }
    else if ua_lower.contains("windows nt 6.1") { os_name = Some("Windows".to_string()); os_version = Some("7".to_string()); }
    else if ua_lower.contains("mac os x") { os_name = Some("macOS".to_string()); }
    else if ua_lower.contains("iphone os") { os_name = Some("iOS".to_string()); }
    else if ua_lower.contains("android") { os_name = Some("Android".to_string()); }
    else if ua_lower.contains("linux") { os_name = Some("Linux".to_string()); }

    // Browser
    if ua_lower.contains("edg/") { browser_name = Some("Edge".to_string()); }
    else if ua_lower.contains("chrome/") || ua_lower.contains("crios/") { browser_name = Some("Chrome".to_string()); }
    else if ua_lower.contains("firefox/") || ua_lower.contains("fxios/") { browser_name = Some("Firefox".to_string()); }
    else if ua_lower.contains("safari/") { browser_name = Some("Safari".to_string()); }
    else if ua_lower.contains("opera/") || ua_lower.contains("opr/") { browser_name = Some("Opera".to_string()); }

    (device_type, os_name, os_version, browser_name, browser_version)
}

/// Helper to extract client IP with X-Forwarded-For support
fn extract_client_ip(headers: &HeaderMap, remote_addr: SocketAddr) -> String {
    if let Some(forwarded) = headers.get("x-forwarded-for") {
        if let Ok(forwarded_str) = forwarded.to_str() {
            if let Some(first_ip) = forwarded_str.split(',').next() {
                return first_ip.trim().to_string();
            }
        }
    }
    if let Some(real_ip) = headers.get("x-real-ip") {
        if let Ok(ip_str) = real_ip.to_str() {
            return ip_str.to_string();
        }
    }
    remote_addr.ip().to_string()
}

/// Best-effort scalar count helper used by the WebSocket stats broadcaster.
/// Returns 0 on any query/row error (broadcasting must never fail the request).
async fn scalar_count(db: &libsql::Connection, sql: &str) -> i64 {
    match db.query(sql, ()).await {
        Ok(mut rows) => match rows.next().await {
            Ok(Some(row)) => match row.get_value(0) {
                Ok(libsql::Value::Integer(n)) => n,
                _ => 0,
            },
            _ => 0,
        },
        Err(_) => 0,
    }
}

/// Compute and broadcast global stats to WebSocket clients
async fn broadcast_stats(state: &Arc<AppState>) {
    let total_messages = scalar_count(&state.db, "SELECT COUNT(*) FROM messages").await;
    let unique_ips = scalar_count(&state.db, "SELECT COUNT(DISTINCT COALESCE(visitor_id, ip)) FROM reads").await;
    let total_reads = scalar_count(&state.db, "SELECT COUNT(*) FROM reads").await;
    let countries = scalar_count(
        &state.db,
        "SELECT COUNT(DISTINCT country) FROM reads WHERE country != '' AND country IS NOT NULL",
    )
    .await;
    let cities = scalar_count(
        &state.db,
        "SELECT COUNT(DISTINCT city) FROM reads WHERE city != '' AND city IS NOT NULL",
    )
    .await;

    let _ = state.ws_tx.send(serde_json::json!({
        "type": "stats_update",
        "total_messages": total_messages,
        "unique_ips": unique_ips,
        "total_reads": total_reads,
        "countries": countries,
        "cities": cities,
    }).to_string());
}

/// Serves the 1x1 transparent PNG and logs the reader's IP against the message
/// id. The reader's wxId is never observable here, so identity is approximated
/// by a per-browser visitor cookie (falling back to distinct IP for legacy
/// clients that do not store cookies).
/// `/read-report`: a WeKit client that rendered an INCOMING probing message
/// reports itself as the reader, so the dashboard can label the probed IP with
/// the reader's wxid + nickname (covers group chats too, where the pixel URL
/// alone can only name the room). We first try to enrich an existing pixel
/// record for the same message+IP (keeps its visitor id, so the identity lands
/// on the same dashboard row); if none exists yet, a standalone read record is
/// inserted (no visitor cookie, so it groups with other rows from the same IP).
async fn read_report(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    body: String,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let req: ReadReportRequest = serde_json::from_str(&body).map_err(|e| {
        (
            StatusCode::BAD_REQUEST,
            format!("invalid read-report JSON: {e}"),
        )
    })?;
    if req.msg_id.is_empty() || req.reader_wx_id.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "msgId and readerWxId must not be empty".to_string(),
        ));
    }
    let client_ip = extract_client_ip(&headers, remote_addr);
    let now_str = now_db_str();
    let now_ms = Utc::now().timestamp_millis();
    let nickname = req.reader_nickname.as_deref().unwrap_or("").to_string();
    let talker = req.talker.as_deref().unwrap_or("").to_string();

    let geo = lookup_geoip(&state.geoip, &client_ip);

    info!(
        "/read-report\nmsg_id = {}, sender = {}, reader = {} ({}), talker = {}, role = {}, ip = {}",
        req.msg_id, req.sender_wx_id, req.reader_wx_id, nickname, talker,
        req.role.as_deref().unwrap_or(""), client_ip
    );

    // 0) Sender-view report: the probe's sender re-opened their own outgoing
    //    message (e.g. to check the live count). Label that IP as the sender
    //    themselves so the dashboard shows 发送者本人 rather than a bare group
    //    name / anonymous row.
    if req.role.as_deref() == Some("sender") {
        let sender_label = format!("{}", req.reader_nickname.as_deref().unwrap_or("发送者本人"));
        let sender_updated = state
            .db
            .execute(
                "UPDATE reads SET reader_wx_id = ?1, reader_nickname = ?2, talker = ?3
                 WHERE msg_id = ?4 AND ip = ?5",
                libsql::params![
                    req.sender_wx_id.as_str(),
                    sender_label.as_str(),
                    talker.as_str(),
                    req.msg_id.as_str(),
                    client_ip.as_str()
                ],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("read-report(sender) update failed: {e}"),
                )
            })?;
        if sender_updated == 0 {
            let report_visitor = format!("v-report-{}", client_ip.replace(':', "_"));
            state
                .db
                .execute(
                    "INSERT INTO reads (id, wx_id, ip, timestamp, country, city, reader_wx_id, reader_nickname, device_type, os_name, os_version, browser_name, browser_version, isp, referrer, created_at, msg_id, visitor_id, talker)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, '', '', '', '', '', ?9, '', ?10, ?1, ?11, ?12)",
                    libsql::params![
                        req.msg_id.as_str(),
                        req.sender_wx_id.as_str(),
                        client_ip,
                        now_str,
                        geo.country,
                        geo.city,
                        req.sender_wx_id.as_str(),
                        sender_label.as_str(),
                        geo.isp,
                        now_ms,
                        report_visitor.as_str(),
                        talker.as_str()
                    ],
                )
                .await
                .map_err(|e| {
                    (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        format!("read-report(sender) insert failed: {e}"),
                    )
                })?;
        }
        let _ = state
            .ws_tx
            .send(serde_json::json!({ "type": "read_update", "msg_id": req.msg_id }).to_string());
        broadcast_stats(&state).await;
        return Ok(Json(serde_json::json!({ "ok": true, "msg_id": req.msg_id, "role": "sender" })));
    }

    // 1) Prefer enriching an existing pixel row for the same message+IP so the
    //    reader identity lands on the same dashboard row (keeps visitor id).
    let updated = state
        .db
        .execute(
            "UPDATE reads SET reader_wx_id = ?1, reader_nickname = ?2, talker = ?3
             WHERE msg_id = ?4 AND ip = ?5 AND (reader_wx_id IS NULL OR reader_wx_id = '')",
            libsql::params![
                req.reader_wx_id.as_str(),
                nickname.as_str(),
                talker.as_str(),
                req.msg_id.as_str(),
                client_ip.as_str()
            ],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("read-report update failed: {e}"),
            )
        })?;

    if updated == 0 {
        // 2) No pixel row to enrich yet — insert a standalone read record.
        //    visitor_id is a STABLE `v-report-<ip>` token: repeated reports from
        //    the same IP merge into one dashboard row (GROUP BY visitor_id), so
        //    the same reader opening a message multiple times is deduplicated.
        let report_visitor = format!("v-report-{}", client_ip.replace(':', "_"));
        state
            .db
            .execute(
                "INSERT INTO reads (id, wx_id, ip, timestamp, country, city, reader_wx_id, reader_nickname, device_type, os_name, os_version, browser_name, browser_version, isp, referrer, created_at, msg_id, visitor_id, talker)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, '', '', '', '', '', ?9, '', ?10, ?1, ?11, ?12)",
                libsql::params![
                    req.msg_id.as_str(),
                    req.sender_wx_id.as_str(),
                    client_ip,
                    now_str,
                    geo.country,
                    geo.city,
                    req.reader_wx_id.as_str(),
                    nickname.as_str(),
                    geo.isp,
                    now_ms,
                    report_visitor.as_str(),
                    talker.as_str()
                ],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("read-report insert failed: {e}"),
                )
            })?;
    }

    // Broadcast real-time update to WebSocket clients
    let _ = state
        .ws_tx
        .send(serde_json::json!({ "type": "read_update", "msg_id": req.msg_id }).to_string());
    broadcast_stats(&state).await;

    Ok(Json(serde_json::json!({ "ok": true, "msg_id": req.msg_id })))
}

async fn serve_tracking_pixel(
    State(state): State<Arc<AppState>>,
    Query(params): Query<ReadParams>,
    headers: HeaderMap,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
) -> impl IntoResponse {
    let client_ip = extract_client_ip(&headers, remote_addr);
    let now_str = now_db_str();
    let now_ms = Utc::now().timestamp_millis();

    // Stable per-browser visitor identity: reuse the `rr_vid` cookie when the
    // client already has one, otherwise fall back to a STABLE `v-ip-<ip>`
    // token. The cookie path covers normal browsers (same person opening the
    // message again from another IP/network counts once). The IP fallback is
    // the critical dedup for WeChat's built-in browser, which does NOT persist
    // cookies for image requests — without it, the same person re-opening the
    // same message would mint a fresh visitor id every time and show up as
    // duplicate "reads".
    let cookie_header = headers
        .get(header::COOKIE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("");
    let visitor_id = extract_cookie(cookie_header, "rr_vid")
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| format!("v-ip-{}", client_ip.replace(':', "_")));
    let set_cookie = format!(
        "rr_vid={visitor_id}; Max-Age=31536000; Path=/; SameSite=Lax"
    );

    // Parse user agent
    let ua = headers.get("user-agent").and_then(|v| v.to_str().ok()).unwrap_or("");
    let (device_type, os_name, os_version, browser_name, browser_version) = parse_user_agent(ua);
    
    // Get referrer
    let referrer = headers.get("referer").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();

    // GeoIP lookup
    let geo = lookup_geoip(&state.geoip, &client_ip);

    match (&params.wx_id, &params.id) {
        (Some(wx_id), Some(id)) => {
            info!("/pixel request\nid = {id}, wxId = {wx_id}, client_ip = {client_ip}, visitor = {visitor_id}, country = {}, city = {}", geo.country, geo.city);

            // Dedup with a prior /read-report row: if one already exists for this
            // message+IP (stable `v-report-*` visitor token), upgrade it into a
            // pixel row (cookie visitor id + latest UA/geo) instead of inserting
            // a duplicate — one person = one dashboard row.
            let merged = state
                .db
                .execute(
                    "UPDATE reads SET visitor_id = ?1, timestamp = ?2, created_at = ?3,
                            country = ?4, city = ?5, isp = ?6,
                            device_type = ?7, os_name = ?8, os_version = ?9,
                            browser_name = ?10, browser_version = ?11, referrer = ?12
                     WHERE msg_id = ?13 AND ip = ?14 AND (visitor_id LIKE 'v-report-%' OR visitor_id LIKE 'v-ip-%')",
                    libsql::params![
                        visitor_id.as_str(),
                        now_str.as_str(),
                        now_ms,
                        geo.country.as_str(),
                        geo.city.as_str(),
                        geo.isp.as_str(),
                        device_type.as_deref().unwrap_or(""),
                        os_name.as_deref().unwrap_or(""),
                        os_version.as_deref().unwrap_or(""),
                        browser_name.as_deref().unwrap_or(""),
                        browser_version.as_deref().unwrap_or(""),
                        referrer.as_str(),
                        id.as_str(),
                        client_ip.as_str()
                    ],
                )
                .await;
            let merged_rows = match merged {
                Ok(n) => n,
                Err(e) => {
                    error!("failed to merge read-report row: {e}");
                    0
                }
            };

            if merged_rows == 0 {
                if let Err(e) = state
                    .db
                    .execute(
                        "INSERT INTO reads (id, wx_id, ip, timestamp, country, city, reader_wx_id, talker, chat_name, device_type, os_name, os_version, browser_name, browser_version, isp, referrer, created_at, msg_id, visitor_id) 
                         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)",
                        libsql::params![
                            id.as_str(), 
                            wx_id.as_str(), 
                            client_ip, 
                            now_str,
                            geo.country,
                            geo.city,
                            params.reader_wx_id.as_deref().unwrap_or(""),
                            params.talker.as_deref().unwrap_or(""),
                            params.chat_name.as_deref().unwrap_or(""),
                            device_type.as_deref().unwrap_or(""),
                            os_name.as_deref().unwrap_or(""),
                            os_version.as_deref().unwrap_or(""),
                            browser_name.as_deref().unwrap_or(""),
                            browser_version.as_deref().unwrap_or(""),
                            geo.isp,
                            referrer,
                            now_ms,
                            id.as_str(),
                            visitor_id.as_str()
                        ],
                    )
                    .await
                {
                    error!("failed to log read: {e}");
                }
            }

            // Broadcast real-time update to WebSocket clients (insert or merge)
            let _ = state.ws_tx.send(serde_json::json!({
                "type": "read_update",
                "msg_id": id
            }).to_string());
            
            // Broadcast updated stats
            broadcast_stats(&state).await;
        }
        _ => {
            warn!("/pixel request missing 'wxId' or 'id' query parameter — read not logged");
        }
    }

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "image/png")
        .header(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .header(header::PRAGMA, "no-cache")
        .header(header::SET_COOKIE, set_cookie)
        .body(axum::body::Body::from(TRACKING_PIXEL))
        .unwrap()
}

/// Extracts a cookie value by name from a raw `Cookie` header.
fn extract_cookie(cookie_header: &str, name: &str) -> Option<String> {
    cookie_header.split(';').find_map(|part| {
        let part = part.trim();
        if let Some((k, v)) = part.split_once('=') {
            if k.trim() == name {
                return Some(v.trim().to_string());
            }
        }
        None
    })
}

/// Generates a fresh visitor id using std-only randomness (no extra crates).
fn generate_visitor_id() -> String {
    use std::hash::{BuildHasher, Hasher};
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let mut hasher = std::collections::hash_map::RandomState::new().build_hasher();
    hasher.write_u128(nanos);
    hasher.write_u64(std::process::id() as u64);
    format!("v{:016x}", hasher.finish())
}

/// Returns the deduped-by-IP read count for a message. `wxId` is optional:
/// when provided (and non-empty) it narrows the match, otherwise the count is
/// derived purely from `msg_id`. Polled by the sender's client and by the
/// dashboard to render the live "已读 x 人" / badge numbers.
async fn read_count(
    State(state): State<Arc<AppState>>,
    Query(params): Query<ReadParams>,
) -> Result<Json<CountResponse>, (StatusCode, String)> {
    let id = match params.id {
        Some(i) => i,
        _ => {
            return Err((
                StatusCode::BAD_REQUEST,
                "id is required".to_string(),
            ));
        }
    };

    let wx_id = params.wx_id.as_deref().map(str::trim).unwrap_or("");

    let mut rows = if wx_id.is_empty() {
        state
            .db
            .query(
                "SELECT COUNT(DISTINCT COALESCE(visitor_id, ip)) FROM reads WHERE msg_id = ?1",
                libsql::params![id],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    } else {
        state
            .db
            .query(
                "SELECT COUNT(DISTINCT COALESCE(visitor_id, ip)) FROM reads WHERE msg_id = ?1 AND wx_id = ?2",
                libsql::params![id, wx_id],
            )
            .await
            .map_err(|e| {
                (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    format!("query failed: {e}"),
                )
            })?
    };

    let count = match rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        Some(row) => match row.get_value(0) {
            Ok(libsql::Value::Integer(n)) => n,
            _ => 0,
        },
        None => 0,
    };

    Ok(Json(CountResponse { count }))
}

/// Returns global dashboard statistics for the summary cards.
async fn global_stats(
    State(state): State<Arc<AppState>>,
) -> Result<Json<GlobalStatsResponse>, (StatusCode, String)> {
    let total_messages: i64 = state
        .db
        .query("SELECT COUNT(*) FROM messages", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let unique_ips: i64 = state
        .db
        .query("SELECT COUNT(DISTINCT COALESCE(visitor_id, ip)) FROM reads", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let total_reads: i64 = state
        .db
        .query("SELECT COUNT(*) FROM reads", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let countries: i64 = state
        .db
        .query("SELECT COUNT(DISTINCT country) FROM reads WHERE country != '' AND country IS NOT NULL", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let cities: i64 = state
        .db
        .query("SELECT COUNT(DISTINCT city) FROM reads WHERE city != '' AND city IS NOT NULL", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    Ok(Json(GlobalStatsResponse {
        total_messages,
        unique_ips,
        total_reads,
        countries,
        cities,
    }))
}

/// Liveness probe (mirrors the reference read-receipt-tracker `/health`).
async fn health_check() -> Json<serde_json::Value> {
    Json(serde_json::json!({
        "status": "ok",
        "service": "wekit-read-receipts-server"
    }))
}

/// Batch read-count lookup for multiple message ids, e.g.
/// `GET /batch-status?ids=id1,id2,id3` → `{ "statuses": { id1: 3, id2: 0 } }`.
/// Mirrors the reference read-receipt-tracker `/batch-status` so WeKit clients
/// can refresh a whole conversation's badges in one round trip.
async fn batch_status(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let ids_raw = params.get("ids").cloned().unwrap_or_default();
    let ids: Vec<String> = ids_raw
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    if ids.is_empty() {
        return Err((
            StatusCode::BAD_REQUEST,
            "ids required (comma-separated)".to_string(),
        ));
    }

    let ph = vec!["?"; ids.len()].join(",");
    let sql = format!(
        "SELECT msg_id, COUNT(DISTINCT COALESCE(visitor_id, ip)) AS cnt \
         FROM reads WHERE msg_id IN ({ph}) GROUP BY msg_id"
    );
    let params: Vec<libsql::Value> = ids.iter().map(|s| libsql::Value::Text(s.clone())).collect();
    let mut rows = state
        .db
        .query(&sql, libsql::params_from_iter(params))
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("query failed: {e}"),
            )
        })?;

    let mut statuses: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        let mid = row.get_str(0).unwrap_or_default().to_string();
        let cnt = match row.get_value(1) {
            Ok(libsql::Value::Integer(n)) => n,
            _ => 0,
        };
        statuses.insert(mid, cnt);
    }
    // Messages with zero reads are absent from the GROUP BY; report 0 explicitly.
    for mid in &ids {
        statuses.entry(mid.to_string()).or_insert(0);
    }

    Ok(Json(serde_json::json!({ "statuses": statuses })))
}

/// Returns every registered message with its deduped-by-IP read count, newest first.
/// Supports pagination via ?page= and ?page_size=, and optional ?q= for content filtering.
async fn list_messages(
    State(state): State<Arc<AppState>>,
    Query(params): Query<PaginationParams>,
    Query(filter): Query<HashMap<String, String>>,
) -> Result<Json<PaginatedMessages>, (StatusCode, String)> {
    let page = params.page();
    let page_size = params.page_size();
    let offset = params.offset();
    let q = filter.get("q").map(|s| s.as_str()).unwrap_or("");

    // Get total count. `q` matches the sender wxId OR the message content,
    // so the dashboard search box can filter by conversation / sender.
    let total: i64 = if q.is_empty() {
        state
            .db
            .query("SELECT COUNT(*) FROM messages", ())
            .await
    } else {
        state
            .db
            .query(
                "SELECT COUNT(*) FROM messages WHERE wx_id LIKE ?1 OR content LIKE ?1",
                libsql::params![format!("%{}%", q)],
            )
            .await
    }
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("count query failed: {e}")))?
    .next()
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("count row failed: {e}")))?
    .and_then(|row| row.get_value(0).ok())
    .and_then(|v| match v {
        libsql::Value::Integer(n) => Some(n),
        _ => None,
    })
    .unwrap_or(0);

    // Get paginated messages
    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT COALESCE(r.visitor_id, r.ip)) FROM reads r WHERE r.msg_id = m.id) AS reads,
                        COALESCE(m.talker, ''), COALESCE(m.chat_name, '')
                 FROM messages m ORDER BY m.timestamp DESC LIMIT ?1 OFFSET ?2",
                libsql::params![page_size as i64, offset as i64],
            )
            .await
    } else {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT COALESCE(r.visitor_id, r.ip)) FROM reads r WHERE r.msg_id = m.id) AS reads,
                        COALESCE(m.talker, ''), COALESCE(m.chat_name, '')
                 FROM messages m WHERE m.wx_id LIKE ?1 OR m.content LIKE ?1 ORDER BY m.timestamp DESC LIMIT ?2 OFFSET ?3",
                libsql::params![format!("%{}%", q), page_size as i64, offset as i64],
            )
            .await
    }
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    let mut messages = collect_messages(&mut rows, &state.db).await?;

    // Align the list's country/city tags with the detail view's enriched
    // geolocation chain (fresh API data instead of the pixel-time DB values).
    enrich_message_locations(&state, &mut messages).await;

    let total_pages = if total > 0 { ((total as f64) / (page_size as f64)).ceil() as u32 } else { 1 };

    Ok(Json(PaginatedMessages {
        messages,
        total,
        page,
        page_size,
        total_pages,
    }))
}

/// Returns all messages sent by a specific wxId with their read counts, newest first.
/// Supports pagination via ?page= and ?page_size=, and optional ?q= for content filtering.
async fn list_messages_for_sender(
    State(state): State<Arc<AppState>>,
    Path(wx_id): Path<String>,
    Query(params): Query<PaginationParams>,
    Query(filter): Query<HashMap<String, String>>,
) -> Result<Json<PaginatedMessages>, (StatusCode, String)> {
    let page = params.page();
    let page_size = params.page_size();
    let offset = params.offset();
    let q = filter.get("q").map(|s| s.as_str()).unwrap_or("");

    let total: i64 = if q.is_empty() {
        state
            .db
            .query("SELECT COUNT(*) FROM messages WHERE wx_id = ?1", libsql::params![wx_id.clone()])
            .await
    } else {
        state
            .db
            .query(
                "SELECT COUNT(*) FROM messages WHERE wx_id = ?1 AND content LIKE ?2",
                libsql::params![wx_id.clone(), format!("%{}%", q)],
            )
            .await
    }
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("count query failed: {e}")))?
    .next()
    .await
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("count row failed: {e}")))?
    .and_then(|row| row.get_value(0).ok())
    .and_then(|v| match v {
        libsql::Value::Integer(n) => Some(n),
        _ => None,
    })
    .unwrap_or(0);

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT COALESCE(r.visitor_id, r.ip)) FROM reads r WHERE r.msg_id = m.id) AS reads,
                        COALESCE(m.talker, ''), COALESCE(m.chat_name, '')
                 FROM messages m WHERE m.wx_id = ?1 ORDER BY m.timestamp DESC LIMIT ?2 OFFSET ?3",
                libsql::params![wx_id.clone(), page_size as i64, offset as i64],
            )
            .await
    } else {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT COALESCE(r.visitor_id, r.ip)) FROM reads r WHERE r.msg_id = m.id) AS reads,
                        COALESCE(m.talker, ''), COALESCE(m.chat_name, '')
                 FROM messages m WHERE m.wx_id = ?1 AND m.content LIKE ?2 ORDER BY m.timestamp DESC LIMIT ?3 OFFSET ?4",
                libsql::params![wx_id.clone(), format!("%{}%", q), page_size as i64, offset as i64],
            )
            .await
    }
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    let mut messages = collect_messages(&mut rows, &state.db).await?;

    // Align the list's country/city tags with the detail view's enriched chain.
    enrich_message_locations(&state, &mut messages).await;

    let total_pages = if total > 0 { ((total as f64) / (page_size as f64)).ceil() as u32 } else { 1 };

    Ok(Json(PaginatedMessages {
        messages,
        total,
        page,
        page_size,
        total_pages,
    }))
}

/// Drains a message result set (5 columns: id, wx_id, content, timestamp, reads) into [`MessageRecord`]s.
async fn collect_messages(
    rows: &mut libsql::Rows,
    db: &libsql::Connection,
) -> Result<Vec<MessageRecord>, (StatusCode, String)> {
    let mut messages = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        let msg_id = row.get_str(0).unwrap_or_default().to_string();
        
        // Fetch aggregated data for this message
        let countries = fetch_distinct_values(db, &msg_id, "country").await.unwrap_or_default();
        let cities = fetch_distinct_values(db, &msg_id, "city").await.unwrap_or_default();
        let devices = fetch_distinct_values(db, &msg_id, "device_type").await.unwrap_or_default();
        let os_list = fetch_distinct_values(db, &msg_id, "os_name").await.unwrap_or_default();
        let browsers = fetch_distinct_values(db, &msg_id, "browser_name").await.unwrap_or_default();
        
        messages.push(MessageRecord {
            id: msg_id,
            wx_id: row.get_str(1).unwrap_or_default().to_string(),
            content: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
            reads: match row.get_value(4) {
                Ok(libsql::Value::Integer(n)) => n,
                _ => 0,
            },
            talker: row.get_str(5).unwrap_or_default().to_string(),
            chat_name: row.get_str(6).unwrap_or_default().to_string(),
            countries,
            cities,
            devices,
            os_list,
            browsers,
        });
    }
    Ok(messages)
}

/// Recomputes the country/city distinct lists for a set of messages using the
/// SAME enriched geolocation chain as the detail view (`enrich_ip`), so the
/// dashboard list matches the detail modal exactly (source of truth = detail).
/// The database `country`/`city` columns are only what was written at pixel
/// time (often English / stale GeoIP values); the detail view replaces them
/// with fresh API data, and so must the list.
async fn enrich_message_locations(state: &Arc<AppState>, messages: &mut [MessageRecord]) {
    if messages.is_empty() {
        return;
    }

    // 1. Collect every distinct IP referenced by any read of these messages.
    let mut ip_set: std::collections::HashSet<String> = std::collections::HashSet::new();
    for m in messages.iter() {
        if let Ok(mut rows) = state
            .db
            .query(
                "SELECT DISTINCT COALESCE(r.ip, '') FROM reads r WHERE r.msg_id = ?1 AND r.ip IS NOT NULL AND r.ip != ''",
                libsql::params![m.id.clone()],
            )
            .await
        {
            while let Ok(Some(row)) = rows.next().await {
                if let Ok(ip) = row.get_str(0) {
                    if !ip.is_empty() {
                        ip_set.insert(ip.to_string());
                    }
                }
            }
        }
    }
    let ips: Vec<String> = ip_set.into_iter().collect();
    if ips.is_empty() {
        return;
    }

    // 2. Enrich concurrently in small batches (reuses the shared ip_cache /
    //    street_cache, so repeated page loads are cheap).
    let mut ip_loc: std::collections::HashMap<String, (String, String)> =
        std::collections::HashMap::new();
    for chunk in ips.chunks(16) {
        let results: Vec<Option<ApiLocation>> =
            futures_util::future::join_all(chunk.iter().map(|ip| {
                let state = Arc::clone(state);
                let ip = ip.clone();
                async move { enrich_ip(&state, &ip).await }
            }))
            .await;
        for (ip, loc) in chunk.iter().zip(results.into_iter()) {
            if let Some(loc) = loc {
                let country = if !loc.country.is_empty() {
                    loc.country
                } else {
                    String::new()
                };
                let city = if !loc.city.is_empty() {
                    loc.city
                } else {
                    String::new()
                };
                ip_loc.insert(ip.clone(), (country, city));
            }
        }
    }

    // 3. Rebuild each message's countries/cities from the enriched map.
    for m in messages.iter_mut() {
        let mut countries: Vec<String> = Vec::new();
        let mut cities: Vec<String> = Vec::new();
        let mut seen_c: std::collections::HashSet<String> = std::collections::HashSet::new();
        let mut seen_t: std::collections::HashSet<String> = std::collections::HashSet::new();
        if let Ok(mut rows) = state
            .db
            .query(
                "SELECT DISTINCT COALESCE(r.ip, '') FROM reads r WHERE r.msg_id = ?1 AND r.ip IS NOT NULL AND r.ip != ''",
                libsql::params![m.id.clone()],
            )
            .await
        {
            while let Ok(Some(row)) = rows.next().await {
                if let Ok(ip) = row.get_str(0) {
                    if let Some((country, city)) = ip_loc.get(ip) {
                        if !country.is_empty() && seen_c.insert(country.clone()) {
                            countries.push(country.clone());
                        }
                        if !city.is_empty() && seen_t.insert(city.clone()) {
                            cities.push(city.clone());
                        }
                    }
                }
            }
        }
        // Keep the existing database values only when enrichment found nothing.
        if !countries.is_empty() {
            m.countries = countries;
        }
        if !cities.is_empty() {
            m.cities = cities;
        }
    }
}

/// Fetch distinct non-empty values for a column from reads table for a specific message
async fn fetch_distinct_values(
    db: &libsql::Connection,
    msg_id: &str,
    column: &str,
) -> Result<Vec<String>, libsql::Error> {
    let query = format!(
        "SELECT DISTINCT {} FROM reads WHERE msg_id = ?1 AND {} != '' AND {} IS NOT NULL ORDER BY {}",
        column, column, column, column
    );
    let mut rows = db.query(&query, libsql::params![msg_id]).await?;
    let mut values = Vec::new();
    while let Some(row) = rows.next().await? {
        if let Ok(val) = row.get_str(0) {
            if !val.is_empty() {
                values.push(val.to_string());
            }
        }
    }
    Ok(values)
}

/// Returns detailed read events for a specific message with pagination.
async fn message_detail(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    Query(params): Query<PaginationParams>,
) -> Result<Json<MessageDetailResponse>, (StatusCode, String)> {
    let page = params.page();
    let page_size = params.page_size();
    let offset = params.offset();
    let msg_id = id.clone();

    // Get message info
    let mut msg_rows = state
        .db
        .query(
            "SELECT wx_id, content, timestamp, COALESCE(talker, ''), COALESCE(chat_name, '') FROM messages WHERE id = ?1",
            libsql::params![msg_id.clone()],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    let (wx_id, content, timestamp, talker, chat_name) = match msg_rows.next().await {
        Ok(Some(row)) => (
            row.get_str(0).unwrap_or_default().to_string(),
            row.get_str(1).unwrap_or_default().to_string(),
            row.get_str(2).unwrap_or_default().to_string(),
            row.get_str(3).unwrap_or_default().to_string(),
            row.get_str(4).unwrap_or_default().to_string(),
        ),
        _ => {
            // The messages row can be absent (e.g. reads arrived for a message
            // whose /register call never succeeded). Fall back to deriving the
            // header from the reads themselves so the detail view still works.
            fallback_message_info(&state.db, &msg_id).await?
        }
    };

    // Get summary data
    let summary = get_detail_summary(&state.db, &state, &msg_id).await?;

    // Get paginated reads, one row per visitor (stable cookie id, falling back
    // to the IP for legacy rows). `ip` is the most recent address seen and
    // `all_ips` lists every address the visitor used.
    let mut rows = state
        .db
        .query(
            "SELECT MAX(ip) AS ip,
                    GROUP_CONCAT(DISTINCT ip) AS all_ips,
                    MIN(timestamp) AS first_timestamp,
                    MAX(timestamp) AS timestamp, 
                    MAX(wx_id) AS wx_id,
                    MAX(country) AS country, MAX(city) AS city, MAX(isp) AS isp,
                    MAX(device_type) AS device_type, MAX(os_name) AS os_name, MAX(os_version) AS os_version,
                    MAX(browser_name) AS browser_name, MAX(browser_version) AS browser_version,
                    MAX(referrer) AS referrer, MAX(reader_wx_id) AS reader_wx_id,
                    MAX(reader_nickname) AS reader_nickname,
                    MAX(talker) AS talker, MAX(chat_name) AS chat_name, COUNT(*) AS load_count,
                    MAX(visitor_id) AS visitor_id
             FROM reads WHERE msg_id = ?1
             GROUP BY COALESCE(visitor_id, ip) ORDER BY timestamp DESC LIMIT ?2 OFFSET ?3",
            libsql::params![msg_id.clone(), page_size as i64, offset as i64],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    let mut reads = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        reads.push(ReadRecord {
            wx_id: row.get_str(4).unwrap_or_default().to_string(),
            ip: row.get_str(0).unwrap_or_default().to_string(),
            all_ips: row.get_str(1).unwrap_or_default().to_string(),
            first_timestamp: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
            country: row.get_str(5).unwrap_or_default().to_string(),
            city: row.get_str(6).unwrap_or_default().to_string(),
            isp: row.get_str(7).unwrap_or_default().to_string(),
            device_type: row.get_str(8).unwrap_or_default().to_string(),
            os_name: row.get_str(9).unwrap_or_default().to_string(),
            os_version: row.get_str(10).unwrap_or_default().to_string(),
            browser_name: row.get_str(11).unwrap_or_default().to_string(),
            browser_version: row.get_str(12).unwrap_or_default().to_string(),
            referrer: row.get_str(13).unwrap_or_default().to_string(),
            reader_wx_id: row.get_str(14).unwrap_or_default().to_string(),
            reader_nickname: row.get_str(15).unwrap_or_default().to_string(),
            talker: row.get_str(16).unwrap_or_default().to_string(),
            chat_name: row.get_str(17).unwrap_or_default().to_string(),
            load_count: match row.get_value(18) {
                Ok(libsql::Value::Integer(n)) => n,
                _ => 0,
            },
            visitor_id: row.get_str(19).unwrap_or_default().to_string(),
            province: String::new(),
            district: String::new(),
            street: String::new(),
            latitude: 0.0,
            longitude: 0.0,
            loc_source: String::new(),
            full_address: String::new(),
        });
    }

    // Enrich with third-party geolocation (street/district/city chain).
    enrich_reads(&state, &mut reads).await;

    // Get total count of distinct visitors
    let total: i64 = state
        .db
        .query(
            "SELECT COUNT(DISTINCT COALESCE(visitor_id, ip)) FROM reads WHERE msg_id = ?1",
            libsql::params![msg_id],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("count query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("count row failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let total_pages = if total > 0 { ((total as f64) / (page_size as f64)).ceil() as u32 } else { 1 };

    Ok(Json(MessageDetailResponse {
        summary,
        reads,
        total,
        page,
        page_size,
        total_pages,
        wx_id,
        content,
        timestamp,
        talker,
        chat_name,
    }))
}

/// Derives a `(wx_id, content, timestamp, talker, chat_name)` header from the
/// reads table when the `messages` row is missing, so the detail page can
/// still render. Talker/chat name are unknown in this fallback path.
async fn fallback_message_info(
    db: &libsql::Connection,
    msg_id: &str,
) -> Result<(String, String, String, String, String), (StatusCode, String)> {
    let mut rows = db
        .query(
            "SELECT wx_id, MAX(timestamp) FROM reads WHERE msg_id = ?1",
            libsql::params![msg_id],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    match rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        Some(row) => Ok((
            row.get_str(0).unwrap_or_default().to_string(),
            String::new(),
            row.get_str(1).unwrap_or_default().to_string(),
            String::new(),
            String::new(),
        )),
        None => Err((StatusCode::NOT_FOUND, "Message not found".to_string())),
    }
}

async fn get_detail_summary(
    db: &libsql::Connection,
    state: &Arc<AppState>,
    msg_id: &str,
) -> Result<MessageSummary, (StatusCode, String)> {
    let unique_ips: i64 = db
        .query("SELECT COUNT(DISTINCT COALESCE(visitor_id, ip)) FROM reads WHERE msg_id = ?1", libsql::params![msg_id])
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let total_reads: i64 = db
        .query("SELECT COUNT(*) FROM reads WHERE msg_id = ?1", libsql::params![msg_id])
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?
        .next()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}")))?
        .and_then(|row| row.get_value(0).ok())
        .and_then(|v| match v {
            libsql::Value::Integer(n) => Some(n),
            _ => None,
        })
        .unwrap_or(0);

    let mut country_map = serde_json::Map::new();
    let mut city_map = serde_json::Map::new();

    // Fetch one row per read, enrich each distinct IP through the third-party
    // chain (cached), then aggregate read counts by the best available names.
    // Falls back to the locally-stored country/city when all APIs fail.
    let mut ips: Vec<String> = Vec::new();
    let mut stored: Vec<(String, String)> = Vec::new();

    let mut rows = db
        .query(
            "SELECT ip, country, city FROM reads WHERE msg_id = ?1",
            libsql::params![msg_id],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    while let Some(row) = rows.next().await.map_err(|e| {
        (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}"))
    })? {
        let ip: String = row.get_str(0).unwrap_or_default().to_string();
        let country: String = row.get_str(1).unwrap_or_default().to_string();
        let city: String = row.get_str(2).unwrap_or_default().to_string();
        ips.push(ip);
        stored.push((country, city));
    }

    // Enrich each distinct IP in parallel (page-level cache makes repeats free).
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    let distinct_ips: Vec<String> = ips
        .iter()
        .filter(|ip| seen.insert((*ip).clone()))
        .cloned()
        .collect();
    let results: Vec<Option<ApiLocation>> =
        futures_util::future::join_all(distinct_ips.iter().map(|ip| {
            let state = Arc::clone(state);
            let ip = ip.clone();
            async move { enrich_ip(&state, &ip).await }
        }))
        .await;
    let enriched: HashMap<String, Option<ApiLocation>> =
        distinct_ips.into_iter().zip(results.into_iter()).collect();

    let mut country_counts: HashMap<String, i64> = HashMap::new();
    let mut city_counts: HashMap<String, i64> = HashMap::new();
    for (ip, (country, city)) in ips.iter().zip(stored.iter()) {
        let loc = enriched.get(ip).and_then(|l| l.as_ref());
        let best_country = loc
            .and_then(|l| if l.country.is_empty() { None } else { Some(l.country.clone()) })
            .unwrap_or_else(|| country.clone());
        let best_city = loc
            .and_then(|l| if l.city.is_empty() { None } else { Some(l.city.clone()) })
            .unwrap_or_else(|| city.clone());
        if !best_country.is_empty() {
            *country_counts.entry(best_country).or_insert(0) += 1;
        }
        if !best_city.is_empty() {
            *city_counts.entry(best_city).or_insert(0) += 1;
        }
    }

    for (k, v) in country_counts {
        country_map.insert(k, serde_json::Value::Number(v.into()));
    }
    for (k, v) in city_counts {
        city_map.insert(k, serde_json::Value::Number(v.into()));
    }

    Ok(MessageSummary {
        unique_ips,
        countries: serde_json::Value::Object(country_map),
        cities: serde_json::Value::Object(city_map),
        readers: unique_ips,
        total_reads,
    })
}

/// Returns the individual read events (distinct by IP, newest first) for one message id.
async fn list_reads_for_message(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Vec<ReadRecord>>, (StatusCode, String)> {
    let mut rows = state
        .db
        .query(
            "SELECT MAX(ip) AS ip,
                    GROUP_CONCAT(DISTINCT ip) AS all_ips,
                    MIN(timestamp) AS first_timestamp,
                    MAX(timestamp) AS timestamp, 
                    MAX(wx_id) AS wx_id,
                    MAX(country) AS country, MAX(city) AS city, MAX(isp) AS isp,
                    MAX(device_type) AS device_type, MAX(os_name) AS os_name, MAX(os_version) AS os_version,
                    MAX(browser_name) AS browser_name, MAX(browser_version) AS browser_version,
                    MAX(referrer) AS referrer, MAX(reader_wx_id) AS reader_wx_id,
                    MAX(reader_nickname) AS reader_nickname,
                    MAX(talker) AS talker, MAX(chat_name) AS chat_name, COUNT(*) AS load_count,
                    MAX(visitor_id) AS visitor_id
             FROM reads WHERE msg_id = ?1
             GROUP BY COALESCE(visitor_id, ip) ORDER BY timestamp DESC",
            libsql::params![id],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("query failed: {e}"),
            )
        })?;

    let mut reads = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("row read failed: {e}"),
        )
    })? {
        reads.push(ReadRecord {
            wx_id: row.get_str(4).unwrap_or_default().to_string(),
            ip: row.get_str(0).unwrap_or_default().to_string(),
            all_ips: row.get_str(1).unwrap_or_default().to_string(),
            first_timestamp: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
            country: row.get_str(5).unwrap_or_default().to_string(),
            city: row.get_str(6).unwrap_or_default().to_string(),
            isp: row.get_str(7).unwrap_or_default().to_string(),
            device_type: row.get_str(8).unwrap_or_default().to_string(),
            os_name: row.get_str(9).unwrap_or_default().to_string(),
            os_version: row.get_str(10).unwrap_or_default().to_string(),
            browser_name: row.get_str(11).unwrap_or_default().to_string(),
            browser_version: row.get_str(12).unwrap_or_default().to_string(),
            referrer: row.get_str(13).unwrap_or_default().to_string(),
            reader_wx_id: row.get_str(14).unwrap_or_default().to_string(),
            reader_nickname: row.get_str(15).unwrap_or_default().to_string(),
            talker: row.get_str(16).unwrap_or_default().to_string(),
            chat_name: row.get_str(17).unwrap_or_default().to_string(),
            load_count: match row.get_value(18) {
                Ok(libsql::Value::Integer(n)) => n,
                _ => 0,
            },
            visitor_id: row.get_str(19).unwrap_or_default().to_string(),
            province: String::new(),
            district: String::new(),
            street: String::new(),
            latitude: 0.0,
            longitude: 0.0,
            loc_source: String::new(),
            full_address: String::new(),
        });
    }

    // Enrich with third-party geolocation (street/district/city chain).
    enrich_reads(&state, &mut reads).await;

    Ok(Json(reads))
}

/// WebSocket endpoint: streams `read_update`, `new_message` and `stats_update`
/// events to connected dashboards in real time.
async fn ws_handler(
    ws: WebSocketUpgrade,
    State(state): State<Arc<AppState>>,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_ws(socket, state))
}

async fn handle_ws(socket: WebSocket, state: Arc<AppState>) {
    let mut rx = state.ws_tx.subscribe();
    let (mut sink, mut stream) = socket.split();

    loop {
        tokio::select! {
            broadcast_msg = rx.recv() => {
                match broadcast_msg {
                    Ok(payload) => {
                        if sink.send(WsMessage::Text(payload.into())).await.is_err() {
                            break; // client disconnected
                        }
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                }
            }
            client_frame = stream.next() => {
                // We never expect meaningful frames from the dashboard; a
                // closed connection surfaces as None / Err and ends the task.
                match client_frame {
                    Some(Ok(_)) => {}
                    _ => break,
                }
            }
        }
    }
}

/// Deletes ALL messages and their reads from the database.
/// Body of `POST /messages/delete`: the ids of the messages to remove.
#[derive(Deserialize)]
struct BatchDeleteRequest {
    ids: Vec<String>,
}

/// Deletes a set of messages by id together with ALL their read events
/// (`reads` rows whose `msg_id` matches). Each deletion is scoped to that
/// message's own id, so other messages and their detail views are untouched.
async fn delete_messages_batch(
    State(state): State<Arc<AppState>>,
    Json(req): Json<BatchDeleteRequest>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    let ids: Vec<String> = req.ids.into_iter().filter(|s| !s.is_empty()).collect();
    let n = ids.len();
    if n == 0 {
        return Ok(Json(serde_json::json!({"status": "ok", "deleted": 0})));
    }
    info!(
        "/messages/delete requested {} id(s): {}",
        n,
        ids.iter().map(|s| &s[..s.len().min(12)]).collect::<Vec<_>>().join(", ")
    );
    let tx = state.db.transaction().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("begin delete transaction failed: {e}"),
        )
    })?;
    for id in &ids {
        tx.execute(
            "DELETE FROM reads WHERE msg_id = ?1",
            libsql::params![id.as_str()],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete reads failed: {e}"),
            )
        })?;
        tx.execute(
            "DELETE FROM messages WHERE id = ?1",
            libsql::params![id.as_str()],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete message failed: {e}"),
            )
        })?;
    }
    tx.commit().await.map_err(|e| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("commit delete transaction failed: {e}"),
        )
    })?;
    info!("/messages/delete committed: deleted {n} message(s)");

    broadcast_stats(&state).await;

    Ok(Json(serde_json::json!({"status": "ok", "deleted": n})))
}

async fn delete_all_messages(
    State(state): State<Arc<AppState>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    warn!("DELETE /messages (delete ALL) requested");
    state
        .db
        .execute("DELETE FROM reads", ())
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete failed: {e}"),
            )
        })?;
    state
        .db
        .execute("DELETE FROM messages", ())
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete failed: {e}"),
            )
        })?;

    Ok(Json(serde_json::json!({"status": "ok"})))
}

/// Deletes all messages sent by a specific wxId and their associated reads.
async fn delete_messages_for_sender(
    State(state): State<Arc<AppState>>,
    Path(wx_id): Path<String>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    warn!("DELETE /messages/{} (delete by sender) requested", wx_id);
    state
        .db
        .execute(
            "DELETE FROM reads WHERE id IN (SELECT id FROM messages WHERE wx_id = ?1)",
            libsql::params![wx_id.clone()],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete failed: {e}"),
            )
        })?;
    state
        .db
        .execute(
            "DELETE FROM messages WHERE wx_id = ?1",
            libsql::params![wx_id],
        )
        .await
        .map_err(|e| {
            (
                StatusCode::INTERNAL_SERVER_ERROR,
                format!("delete failed: {e}"),
            )
        })?;

    Ok(Json(serde_json::json!({"status": "ok"})))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lddgo_svg_parse_full() {
        // Format observed from the real lddgo endpoint.
        let svg = r##"<svg xmlns="http://www.w3.org/2000/svg"><text x="10" y="25" fill="#333">您来自:福建省 福州市 永泰县</text><text>IP:110.87.41.14</text></svg>"##;
        let loc = parse_lddgo_svg(svg).expect("should parse");
        assert_eq!(loc.province, "福建");
        assert_eq!(loc.city, "福州");
        assert_eq!(loc.district, "永泰");
        assert_eq!(loc.source, "市级别");
    }

    #[test]
    fn lddgo_svg_parse_city_only() {
        let svg = r#"<text>您来自:广东省 深圳市</text>"#;
        let loc = parse_lddgo_svg(svg).expect("should parse");
        assert_eq!(loc.province, "广东");
        assert_eq!(loc.city, "深圳");
        assert_eq!(loc.district, "");
    }

    #[test]
    fn lddgo_svg_parse_no_location() {
        assert!(parse_lddgo_svg("<text>您来自:</text>").is_none());
        assert!(parse_lddgo_svg("<svg></svg>").is_none());
    }

    #[test]
    fn lddgo_svg_parse_municipality() {
        // Direct-administered municipality: "北京市" has no 省 suffix.
        let svg = r#"<text>您来自:北京市 北京市</text>"#;
        let loc = parse_lddgo_svg(svg).expect("should parse");
        assert_eq!(loc.province, "北京市");
        assert_eq!(loc.city, "北京");
    }

    #[test]
    fn lddgo_svg_parse_city_single_segment() {
        // IPv6 answers from lddgo often come as a single city segment.
        let svg = r#"<text>您来自:汕头市</text>"#;
        let loc = parse_lddgo_svg(svg).expect("should parse");
        assert_eq!(loc.province, "");
        assert_eq!(loc.city, "汕头");
        assert_eq!(loc.district, "");
    }

    #[test]
    fn full_address_merges_all_levels() {
        let loc = ApiLocation {
            country: "中国".to_string(),
            province: "广东省".to_string(),
            city: "汕头市".to_string(),
            district: "潮阳区".to_string(),
            street: "和平镇".to_string(),
            ..Default::default()
        };
        assert_eq!(build_full_address(&loc), "中国 广东省 汕头市 潮阳区 和平镇");
    }

    #[test]
    fn full_address_skips_missing_levels_and_merges_alternatives() {
        let loc = ApiLocation {
            country: "中国".to_string(),
            province: "福建省".to_string(),
            city: "福州市".to_string(),
            district: "永泰县".to_string(),
            street: String::new(),
            street_alternatives: vec![
                "福建福州永泰城峰镇".to_string(),
                "福建福州永泰大洋镇".to_string(),
            ],
            ..Default::default()
        };
        assert_eq!(
            build_full_address(&loc),
            "中国 福建省 福州市 永泰县 城峰镇/大洋镇"
        );
    }

    #[test]
    fn full_address_city_only() {
        let loc = ApiLocation {
            country: "中国".to_string(),
            province: "广东省".to_string(),
            city: "深圳市".to_string(),
            ..Default::default()
        };
        assert_eq!(build_full_address(&loc), "中国 广东省 深圳市");
    }

    #[test]
    fn special_ipv6_detection() {
        assert!(is_special_ipv6("240e:47f:4458:3295:d01d:32ff:fe28:73d6"));
        assert!(is_special_ipv6("240E:47F:9240:B8DC:9E:29FF:FE17:9B58"));
        // Same /32 block but different second hextet — not special (kept for
        // ip9 district lookups, which are reliable there).
        assert!(!is_special_ipv6("240e:47c:c90:2ace:f8dc:bf7c:f6fb:6e98"));
        // Plain IPv4 must never match.
        assert!(!is_special_ipv6("46.17.107.199"));
        assert!(!is_special_ipv6(""));
    }

    #[test]
    fn same_city_matching() {
        // "广州市" (ip9) vs "广州" (lddgo) — same city.
        assert!(same_city("广州市", "广州"));
        assert!(same_city("广州", "广州市"));
        // Identical strings.
        assert!(same_city("汕头", "汕头"));
        // Different cities — never match.
        assert!(!same_city("广州", "惠州"));
        assert!(!same_city("广州市", "惠州市"));
        // Empty side — never match.
        assert!(!same_city("", "广州"));
        assert!(!same_city("广州", ""));
        assert!(!same_city("", ""));
    }
}
