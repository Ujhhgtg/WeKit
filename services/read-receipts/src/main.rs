use chrono::Utc;
use libsql::Builder;
use rustyline::completion::{Completer, Pair};
use rustyline::highlight::Highlighter;
use rustyline::hint::Hinter;
use rustyline::validate::Validator;
use rustyline::{ExternalPrinter, Helper};
use std::borrow::Cow;
use std::io::Write;
use std::sync::{Mutex, OnceLock};
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tracing::{error, info};
use wekit_read_receipts_server::{
    AppState, RouteProfile, ServerConfig, build_router, compute_msg_id, initialize_database,
};

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

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::io::IsTerminal;
    if std::env::args().any(|argument| argument == "--help" || argument == "-h") {
        println!("WeKit read receipts reference server");
        println!();
        println!("Configuration is read from environment variables:");
        println!("  BIND_ADDR              bind IP address (default: 0.0.0.0)");
        println!("  PORT                   bind port (default: 8080)");
        println!(
            "  TURSO_DATABASE_URL     local or remote libSQL URL (default: file:read_receipts.db)"
        );
        println!("  TURSO_AUTH_TOKEN       remote Turso authentication token");
        println!("  RUST_LOG               tracing filter (default: debug)");
        return Ok(());
    }
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

    let database_path = db_url.strip_prefix("file:").map(PathBuf::from);
    let db = if let Some(path) = &database_path {
        Builder::new_local(path).build().await?
    } else {
        Builder::new_remote(db_url, auth_token).build().await?
    };

    initialize_database(&db).await?;
    let conn = db.connect()?;
    let repl_conn = db.connect()?;

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

    let config = ServerConfig {
        database_path: database_path.unwrap_or_default(),
        bind_addr: bind_host,
        bind_port,
        route_profile: RouteProfile::Standalone,
        connector_authenticator: None,
    };
    let app = build_router(&config, Arc::new(AppState::new(conn)));

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
