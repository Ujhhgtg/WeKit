use axum::{
    Json, Router,
    extract::{ConnectInfo, Path, Query, State},
    http::{StatusCode, header},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use chrono::Utc;
use libsql::{Builder, Connection};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{collections::HashMap, net::SocketAddr, sync::Arc};
use std::sync::{Mutex, OnceLock};
use std::io::Write;
use rustyline::completion::{Completer, Pair};
use rustyline::highlight::Highlighter;
use rustyline::hint::Hinter;
use rustyline::validate::Validator;
use rustyline::{Helper, ExternalPrinter};
use std::borrow::Cow;
use tracing::{error, info, warn};

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
#[derive(Deserialize)]
struct RegisterRequest {
    #[serde(rename = "wxId")]
    wx_id: String,
    content: String,
    #[serde(rename = "createTime")]
    create_time: i64,
}

#[derive(Serialize)]
struct RegisterResponse {
    id: String,
}

/// Query parameters for the tracking pixel and count endpoints.
/// Both carry the sender `wxId` and the message `id` (no more uuid/msg).
#[derive(Deserialize)]
struct ReadParams {
    #[serde(rename = "wxId")]
    wx_id: Option<String>,
    id: Option<String>,
}

#[derive(Serialize)]
struct CountResponse {
    count: i64,
}

struct AppState {
    db: Connection,
}

/// One registered message plus its deduped-by-IP read count, for the dashboard.
#[derive(Serialize)]
struct MessageRecord {
    id: String,
    #[serde(rename = "wxId")]
    wx_id: String,
    content: String,
    reads: i64,
    timestamp: String,
}

/// One individual read event (pixel hit) for a message's detail view.
#[derive(Serialize)]
struct ReadRecord {
    ip: String,
    timestamp: String,
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
            && let Some(p) = opt.as_mut() {
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
                "/sql ", "/exit", "/help", "/status", 
                "/url ", "/tail ", "/query ", "/clear", "/open"
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
                "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE",
                "LIMIT", "ORDER BY", "DESC", "INTO", "VALUES", "CREATE TABLE",
                "IF NOT EXISTS", "AND", "OR", "JOIN", "ON", "GROUP BY", "COUNT", "DISTINCT",
                "messages", "reads", "id", "wx_id", "content", "ip", "timestamp"
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
            let other_cmds = &["/help", "/status", "/open", "/sql", "/url", "/tail", "/query"];
            for cmd in other_cmds {
                if highlighted.starts_with(cmd) {
                    highlighted = highlighted.replacen(cmd, &format!("\x1b[1;32m{}\x1b[0m", cmd), 1);
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
        "SELECT" | "INSERT" | "UPDATE" | "DELETE" | "FROM" | "WHERE" |
        "LIMIT" | "ORDER" | "BY" | "DESC" | "INTO" | "VALUES" | "CREATE" |
        "TABLE" | "IF" | "NOT" | "EXISTS" | "AND" | "OR" | "JOIN" | "ON" |
        "GROUP" | "COUNT" | "DISTINCT" => {
            format!("\x1b[1;36m{}\x1b[0m", word) // Bold Cyan
        }
        "messages" | "reads" => {
            format!("\x1b[1;35m{}\x1b[0m", word) // Bold Magenta
        }
        "id" | "wx_id" | "content" | "ip" | "timestamp" |
        "ID" | "WX_ID" | "CONTENT" | "IP" | "TIMESTAMP" => {
            format!("\x1b[1;34m{}\x1b[0m", word) // Bold Blue
        }
        _ => word.to_string(),
    }
}

async fn handle_sql_command(conn: &libsql::Connection, sql: &str) -> Result<(), Box<dyn std::error::Error>> {
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
    println!("  \x1b[1;32m/status\x1b[0m                     Show server stats (messages, unique senders, reads, unique reader IPs)");
    println!("  \x1b[1;32m/url <wxId> <message>\x1b[0m       Register a message & print its tracking URL + HTML tag");
    println!("  \x1b[1;32m/tail [count]\x1b[0m               Show the latest [count] (default 10) read events in real-time");
    println!("  \x1b[1;32m/query <wxId>\x1b[0m               Show all tracked messages for a sender with their read counts");
    println!("  \x1b[1;32m/clear\x1b[0m                      Clear all tracked messages and reads from the database");
    println!("  \x1b[1;32m/open\x1b[0m                       Open the web dashboard in your default browser");
    println!("  \x1b[1;32m/sql <query>\x1b[0m                Execute arbitrary SQL queries on the database");
    println!("  \x1b[1;32m/exit\x1b[0m                       Shutdown the server and exit the REPL");
}

async fn handle_status_command(conn: &libsql::Connection) -> Result<(), Box<dyn std::error::Error>> {
    async fn scalar(conn: &libsql::Connection, sql: &str) -> Result<i64, Box<dyn std::error::Error>> {
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

    let mut latest_rows = conn.query("SELECT timestamp FROM reads ORDER BY timestamp DESC LIMIT 1", ()).await?;
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
    println!("Unique reader IPs:     \x1b[1;33m{}\x1b[0m", unique_reader_ips);
    println!("Latest read time:      \x1b[1;33m{}\x1b[0m", latest_read);

    Ok(())
}

async fn handle_url_command(conn: &libsql::Connection, args: &str) -> Result<(), Box<dyn std::error::Error>> {
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
    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();

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
    println!("HTML Tag: \x1b[33m<img src=\"{}\" width=\"1\" height=\"1\" style=\"display:none;\" />\x1b[0m", url);
    Ok(())
}

async fn handle_tail_command(conn: &libsql::Connection, args: &str) -> Result<(), Box<dyn std::error::Error>> {
    let count: i64 = args.trim().parse().unwrap_or(10);

    let mut rows = conn.query(
        "SELECT r.timestamp, r.ip, r.wx_id, COALESCE(m.content, '') \
         FROM reads r LEFT JOIN messages m ON r.id = m.id \
         ORDER BY r.timestamp DESC LIMIT ?1",
        libsql::params![count]
    ).await?;

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

async fn handle_query_command(conn: &libsql::Connection, wx_id: &str) -> Result<(), Box<dyn std::error::Error>> {
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
            println!("Database wiped successfully! Wiped \x1b[1;31m{}\x1b[0m messages (and all their reads).", rows_affected);
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
    let _ = std::process::Command::new("cmd").args(["/C", "start", &url]).spawn();
}

async fn route_command(trimmed: &str, repl_conn: &libsql::Connection) -> Result<bool, Box<dyn std::error::Error>> {
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

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::io::IsTerminal;
    let is_terminal = std::io::stdin().is_terminal();

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

    let db = if db_url.starts_with("file:") {
        Builder::new_local(db_url.replace("file:", ""))
            .build()
            .await?
    } else {
        Builder::new_remote(db_url, auth_token).build().await?
    };

    let conn = db.connect()?;
    let repl_conn = db.connect()?;

    // messages: registered by the sender before tampering. PK = deterministic hash of (wx_id + content).
    conn.execute(
        "CREATE TABLE IF NOT EXISTS messages (
            id        TEXT PRIMARY KEY,
            wx_id     TEXT NOT NULL,
            content   TEXT NOT NULL,
            timestamp TEXT NOT NULL
        );",
        (),
    )
    .await?;

    // reads: one row per tracking-pixel hit. Reader identity is approximated by distinct IP.
    conn.execute(
        "CREATE TABLE IF NOT EXISTS reads (
            id        TEXT NOT NULL,
            wx_id     TEXT NOT NULL,
            ip        TEXT NOT NULL,
            timestamp TEXT NOT NULL
        );",
        (),
    )
    .await?;

    let app = Router::new()
        .route("/", get(serve_index))
        .route("/register", post(register_message))
        .route("/pixel", get(serve_tracking_pixel))
        .route("/count", get(read_count))
        .route("/messages", get(list_messages).delete(delete_all_messages))
        .route("/messages/{wx_id}", get(list_messages_for_sender).delete(delete_messages_for_sender))
        .route("/reads/{id}", get(list_reads_for_message))
        .with_state(Arc::new(AppState { db: conn }));

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
                    Err(rustyline::error::ReadlineError::Io(ref e)) if e.raw_os_error() == Some(25) => {
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
            use tokio::signal::unix::{signal, SignalKind};
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

/// Registers a message before it is tampered with. The server derives the
/// deterministic id from `(wxId, content)`, upserts the row (keeping the
/// original timestamp on re-registration), and returns the id to the client.
async fn register_message(
    State(state): State<Arc<AppState>>,
    Json(req): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, (StatusCode, String)> {
    if req.wx_id.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "wxId must not be empty".to_string()));
    }

    let id = compute_msg_id(&req.wx_id, &req.content, req.create_time);
    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();

    info!(
        "/register\nid = {id}, wxId = {}, createTime = {}, content = {}",
        req.wx_id, req.create_time, req.content
    );

    state
        .db
        .execute(
            "INSERT INTO messages (id, wx_id, content, timestamp) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(id) DO NOTHING",
            libsql::params![id.as_str(), req.wx_id.as_str(), req.content.as_str(), now],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("register failed: {e}")))?;

    Ok(Json(RegisterResponse { id }))
}

/// Serves the 1x1 transparent PNG and logs the reader's IP against the message
/// id. The reader's wxId is never observable here, so identity is approximated
/// by distinct IP at count time.
async fn serve_tracking_pixel(
    State(state): State<Arc<AppState>>,
    Query(params): Query<ReadParams>,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
) -> impl IntoResponse {
    let client_ip = remote_addr.ip().to_string();
    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();

    match (&params.wx_id, &params.id) {
        (Some(wx_id), Some(id)) => {
            info!("/pixel request\nid = {id}, wxId = {wx_id}, client_ip = {client_ip}");

            if let Err(e) = state
                .db
                .execute(
                    "INSERT INTO reads (id, wx_id, ip, timestamp) VALUES (?1, ?2, ?3, ?4)",
                    libsql::params![id.as_str(), wx_id.as_str(), client_ip, now],
                )
                .await
            {
                error!("failed to log read: {e}");
            }
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
        .body(axum::body::Body::from(TRACKING_PIXEL))
        .unwrap()
}

/// Returns the deduped-by-IP read count for a `(wxId, id)` pair. Polled by the
/// sender's client to render the live "已读 x 人" indicator.
async fn read_count(
    State(state): State<Arc<AppState>>,
    Query(params): Query<ReadParams>,
) -> Result<Json<CountResponse>, (StatusCode, String)> {
    let (wx_id, id) = match (params.wx_id, params.id) {
        (Some(w), Some(i)) => (w, i),
        _ => return Err((StatusCode::BAD_REQUEST, "wxId and id are required".to_string())),
    };

    let mut rows = state
        .db
        .query(
            "SELECT COUNT(DISTINCT ip) FROM reads WHERE id = ?1 AND wx_id = ?2",
            libsql::params![id, wx_id],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    let count = match rows.next().await.map_err(|e| {
        (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}"))
    })? {
        Some(row) => match row.get_value(0) {
            Ok(libsql::Value::Integer(n)) => n,
            _ => 0,
        },
        None => 0,
    };

    Ok(Json(CountResponse { count }))
}

/// Returns every registered message with its deduped-by-IP read count, newest first.
/// Supports optional `?q=` query parameter to filter by message content.
async fn list_messages(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<MessageRecord>>, (StatusCode, String)> {
    let q = params.get("q").map(|s| s.as_str()).unwrap_or("");

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT r.ip) FROM reads r WHERE r.id = m.id) AS reads
                 FROM messages m ORDER BY m.timestamp DESC",
                (),
            )
            .await
    } else {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT r.ip) FROM reads r WHERE r.id = m.id) AS reads
                 FROM messages m WHERE m.content LIKE ?1 ORDER BY m.timestamp DESC",
                libsql::params![format!("%{}%", q)],
            )
            .await
    }
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    collect_messages(&mut rows).await
}

/// Returns all messages sent by a specific wxId with their read counts, newest first.
/// Supports optional `?q=` query parameter to filter by message content.
async fn list_messages_for_sender(
    State(state): State<Arc<AppState>>,
    Path(wx_id): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<MessageRecord>>, (StatusCode, String)> {
    let q = params.get("q").map(|s| s.as_str()).unwrap_or("");

    let mut rows = if q.is_empty() {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT r.ip) FROM reads r WHERE r.id = m.id) AS reads
                 FROM messages m WHERE m.wx_id = ?1 ORDER BY m.timestamp DESC",
                libsql::params![wx_id],
            )
            .await
    } else {
        state
            .db
            .query(
                "SELECT m.id, m.wx_id, m.content, m.timestamp,
                        (SELECT COUNT(DISTINCT r.ip) FROM reads r WHERE r.id = m.id) AS reads
                 FROM messages m WHERE m.wx_id = ?1 AND m.content LIKE ?2 ORDER BY m.timestamp DESC",
                libsql::params![wx_id, format!("%{}%", q)],
            )
            .await
    }
    .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    collect_messages(&mut rows).await
}

/// Drains a message result set (5 columns: id, wx_id, content, timestamp, reads) into [`MessageRecord`]s.
async fn collect_messages(
    rows: &mut libsql::Rows,
) -> Result<Json<Vec<MessageRecord>>, (StatusCode, String)> {
    let mut messages = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}"))
    })? {
        messages.push(MessageRecord {
            id: row.get_str(0).unwrap_or_default().to_string(),
            wx_id: row.get_str(1).unwrap_or_default().to_string(),
            content: row.get_str(2).unwrap_or_default().to_string(),
            timestamp: row.get_str(3).unwrap_or_default().to_string(),
            reads: match row.get_value(4) {
                Ok(libsql::Value::Integer(n)) => n,
                _ => 0,
            },
        });
    }
    Ok(Json(messages))
}

/// Returns the individual read events (distinct by IP, newest first) for one message id.
async fn list_reads_for_message(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<Vec<ReadRecord>>, (StatusCode, String)> {
    let mut rows = state
        .db
        .query(
            "SELECT ip, MAX(timestamp) AS timestamp FROM reads WHERE id = ?1
             GROUP BY ip ORDER BY timestamp DESC",
            libsql::params![id],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("query failed: {e}")))?;

    let mut reads = Vec::new();
    while let Some(row) = rows.next().await.map_err(|e| {
        (StatusCode::INTERNAL_SERVER_ERROR, format!("row read failed: {e}"))
    })? {
        reads.push(ReadRecord {
            ip: row.get_str(0).unwrap_or_default().to_string(),
            timestamp: row.get_str(1).unwrap_or_default().to_string(),
        });
    }

    Ok(Json(reads))
}

/// Deletes ALL messages and their reads from the database.
async fn delete_all_messages(
    State(state): State<Arc<AppState>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    state
        .db
        .execute("DELETE FROM reads", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("delete failed: {e}")))?;
    state
        .db
        .execute("DELETE FROM messages", ())
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("delete failed: {e}")))?;

    Ok(Json(serde_json::json!({"status": "ok"})))
}

/// Deletes all messages sent by a specific wxId and their associated reads.
async fn delete_messages_for_sender(
    State(state): State<Arc<AppState>>,
    Path(wx_id): Path<String>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    state
        .db
        .execute(
            "DELETE FROM reads WHERE id IN (SELECT id FROM messages WHERE wx_id = ?1)",
            libsql::params![wx_id.clone()],
        )
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("delete failed: {e}")))?;
    state
        .db
        .execute("DELETE FROM messages WHERE wx_id = ?1", libsql::params![wx_id])
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, format!("delete failed: {e}")))?;

    Ok(Json(serde_json::json!({"status": "ok"})))
}
