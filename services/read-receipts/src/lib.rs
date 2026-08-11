use axum::{
    Extension, Json, Router,
    extract::{ConnectInfo, DefaultBodyLimit, Path, Query, State},
    http::{StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use chrono::Utc;
use libsql::{Builder, Connection, Database};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::HashMap,
    fmt,
    future::Future,
    net::{IpAddr, SocketAddr},
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use tracing::{error, info, warn};

const TRACKING_PIXEL: &[u8] = &[
    0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
    0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
    0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
    0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
    0x42, 0x60, 0x82,
];
const MAX_WX_ID_BYTES: usize = 128;
const MAX_CONTENT_BYTES: usize = 16 * 1024;
const MAX_MESSAGE_ID_BYTES: usize = 128;
const MAX_REQUEST_BODY_BYTES: usize = 20 * 1024;
const MAX_QUERY_BYTES: usize = 1024;
const RATE_WINDOW: Duration = Duration::from_secs(60);
const REGISTER_RATE_LIMIT: u32 = 30;
const COUNT_RATE_LIMIT: u32 = 120;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum RouteProfile {
    Standalone,
    Embedded,
}

#[derive(Clone, Debug)]
pub struct ServerConfig {
    pub database_path: PathBuf,
    pub bind_addr: IpAddr,
    pub bind_port: u16,
    pub route_profile: RouteProfile,
}

#[derive(Debug)]
pub enum ServerError {
    Database(libsql::Error),
    Io(std::io::Error),
    Task(tokio::task::JoinError),
}

impl fmt::Display for ServerError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Database(error) => write!(formatter, "database error: {error}"),
            Self::Io(error) => write!(formatter, "I/O error: {error}"),
            Self::Task(error) => write!(formatter, "server task error: {error}"),
        }
    }
}

impl std::error::Error for ServerError {}

impl From<libsql::Error> for ServerError {
    fn from(error: libsql::Error) -> Self {
        Self::Database(error)
    }
}

impl From<std::io::Error> for ServerError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

impl From<tokio::task::JoinError> for ServerError {
    fn from(error: tokio::task::JoinError) -> Self {
        Self::Task(error)
    }
}

pub struct ServerHandle {
    local_addr: SocketAddr,
    shutdown: Option<tokio::sync::oneshot::Sender<()>>,
    task: tokio::task::JoinHandle<Result<(), ServerError>>,
}

pub type BoundServer = ServerHandle;

impl ServerHandle {
    pub fn local_addr(&self) -> SocketAddr {
        self.local_addr
    }

    pub async fn shutdown(mut self) -> Result<(), ServerError> {
        if let Some(shutdown) = self.shutdown.take() {
            let _ = shutdown.send(());
        }
        self.task.await?
    }
}

pub struct AppState {
    db: Connection,
    rate_limits: Mutex<HashMap<(RateRoute, IpAddr), RateWindow>>,
}

impl AppState {
    pub fn new(db: Connection) -> Self {
        Self {
            db,
            rate_limits: Mutex::new(HashMap::new()),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
enum RateRoute {
    Register,
    Count,
}

struct RateWindow {
    started_at: Instant,
    requests: u32,
}

pub async fn initialize_database(database: &Database) -> Result<(), ServerError> {
    let connection = database.connect()?;
    connection
        .execute(
            "CREATE TABLE IF NOT EXISTS messages (
                id        TEXT PRIMARY KEY,
                wx_id     TEXT NOT NULL,
                content   TEXT NOT NULL,
                timestamp TEXT NOT NULL
            );",
            (),
        )
        .await?;
    connection
        .execute(
            "CREATE TABLE IF NOT EXISTS reads (
                id        TEXT NOT NULL,
                wx_id     TEXT NOT NULL,
                ip        TEXT NOT NULL,
                timestamp TEXT NOT NULL
            );",
            (),
        )
        .await?;
    Ok(())
}

pub async fn open_database(config: &ServerConfig) -> Result<Database, ServerError> {
    let database = Builder::new_local(&config.database_path).build().await?;
    initialize_database(&database).await?;
    Ok(database)
}

pub async fn bind_and_serve(
    config: ServerConfig,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> Result<BoundServer, ServerError> {
    let database = open_database(&config).await?;
    let state = Arc::new(AppState::new(database.connect()?));
    let router = build_router(&config, state);
    let listener = tokio::net::TcpListener::bind((config.bind_addr, config.bind_port)).await?;
    let local_addr = listener.local_addr()?;
    let (shutdown_sender, shutdown_receiver) = tokio::sync::oneshot::channel();
    let task = tokio::spawn(async move {
        axum::serve(
            listener,
            router.into_make_service_with_connect_info::<SocketAddr>(),
        )
        .with_graceful_shutdown(async move {
            tokio::select! {
                _ = shutdown => {}
                _ = shutdown_receiver => {}
            }
        })
        .await
        .map_err(ServerError::Io)
    });
    Ok(ServerHandle {
        local_addr,
        shutdown: Some(shutdown_sender),
        task,
    })
}

/// Computes the deterministic ID shared by the WeKit client and server.
pub fn compute_msg_id(wx_id: &str, content: &str, create_time: i64) -> String {
    let mut hasher = Sha256::new();
    hasher.update(wx_id.as_bytes());
    hasher.update([0]);
    hasher.update(content.as_bytes());
    hasher.update([0]);
    hasher.update(create_time.to_string().as_bytes());
    hex::encode(hasher.finalize())
}

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

#[derive(Serialize)]
struct MessageRecord {
    id: String,
    #[serde(rename = "wxId")]
    wx_id: String,
    content: String,
    reads: i64,
    timestamp: String,
}

#[derive(Serialize)]
struct ReadRecord {
    ip: String,
    timestamp: String,
}

pub fn build_router(config: &ServerConfig, state: Arc<AppState>) -> Router {
    let router = Router::new()
        .route("/register", post(register_message))
        .route("/pixel", get(serve_tracking_pixel))
        .route("/count", get(read_count));
    let router = match config.route_profile {
        RouteProfile::Standalone => router
            .route("/", get(serve_index))
            .route("/messages", get(list_messages).delete(delete_all_messages))
            .route(
                "/messages/{wx_id}",
                get(list_messages_for_sender).delete(delete_messages_for_sender),
            )
            .route("/reads/{id}", get(list_reads_for_message)),
        RouteProfile::Embedded => router.route("/health", get(health)),
    };
    router
        .layer(middleware::from_fn(limit_query_string))
        .layer(DefaultBodyLimit::max(MAX_REQUEST_BODY_BYTES))
        .layer(Extension(config.route_profile))
        .with_state(state)
}

async fn health() -> StatusCode {
    StatusCode::NO_CONTENT
}

async fn limit_query_string(request: axum::extract::Request, next: Next) -> Response {
    if request
        .uri()
        .query()
        .is_some_and(|query| query.len() > MAX_QUERY_BYTES)
    {
        return StatusCode::URI_TOO_LONG.into_response();
    }
    next.run(request).await
}

async fn serve_index() -> impl IntoResponse {
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "text/html; charset=utf-8")
        .body(axum::body::Body::from(include_str!("../index.html")))
        .unwrap()
}

async fn register_message(
    State(state): State<Arc<AppState>>,
    Extension(route_profile): Extension<RouteProfile>,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    Json(request): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, (StatusCode, String)> {
    enforce_rate_limit(
        &state,
        route_profile,
        RateRoute::Register,
        remote_addr.ip(),
        REGISTER_RATE_LIMIT,
    )?;
    if request.wx_id.is_empty() {
        return Err((StatusCode::BAD_REQUEST, "wxId must not be empty".to_owned()));
    }
    if request.wx_id.len() > MAX_WX_ID_BYTES || request.content.len() > MAX_CONTENT_BYTES {
        return Err((
            StatusCode::BAD_REQUEST,
            "request fields too long".to_owned(),
        ));
    }

    let id = compute_msg_id(&request.wx_id, &request.content, request.create_time);
    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();
    info!(
        "/register\nid = {id}, wxId = {}, createTime = {}",
        request.wx_id, request.create_time
    );
    state
        .db
        .execute(
            "INSERT INTO messages (id, wx_id, content, timestamp) VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(id) DO NOTHING",
            libsql::params![
                id.as_str(),
                request.wx_id.as_str(),
                request.content.as_str(),
                now
            ],
        )
        .await
        .map_err(database_response_error("register failed"))?;
    Ok(Json(RegisterResponse { id }))
}

async fn serve_tracking_pixel(
    State(state): State<Arc<AppState>>,
    Extension(route_profile): Extension<RouteProfile>,
    Query(params): Query<ReadParams>,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
) -> impl IntoResponse {
    let client_ip = remote_addr.ip().to_string();
    let now = Utc::now().format("%Y-%m-%d %H:%M:%S").to_string();
    match (&params.wx_id, &params.id) {
        (Some(wx_id), Some(id)) => {
            let should_log = match route_profile {
                RouteProfile::Standalone => valid_standalone_read_params(wx_id, id),
                RouteProfile::Embedded => {
                    valid_embedded_read_params(wx_id, id)
                        && message_exists(&state.db, wx_id, id).await
                }
            };
            if should_log {
                info!("/pixel request\nid = {id}, wxId = {wx_id}, client_ip = {client_ip}");
                if state
                    .db
                    .execute(
                        "INSERT INTO reads (id, wx_id, ip, timestamp) VALUES (?1, ?2, ?3, ?4)",
                        libsql::params![id.as_str(), wx_id.as_str(), client_ip, now],
                    )
                    .await
                    .is_err()
                {
                    error!("failed to log read");
                }
            }
        }
        _ => warn!("/pixel request missing 'wxId' or 'id' query parameter — read not logged"),
    }

    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "image/png")
        .header(header::CACHE_CONTROL, "no-cache, no-store, must-revalidate")
        .header(header::PRAGMA, "no-cache")
        .body(axum::body::Body::from(TRACKING_PIXEL))
        .unwrap()
}

async fn message_exists(connection: &Connection, wx_id: &str, id: &str) -> bool {
    let result = connection
        .query(
            "SELECT 1 FROM messages WHERE id = ?1 AND wx_id = ?2 LIMIT 1",
            libsql::params![id, wx_id],
        )
        .await;
    match result {
        Ok(mut rows) => matches!(rows.next().await, Ok(Some(_))),
        Err(_) => {
            error!("failed to validate pixel message");
            false
        }
    }
}

async fn read_count(
    State(state): State<Arc<AppState>>,
    Extension(route_profile): Extension<RouteProfile>,
    ConnectInfo(remote_addr): ConnectInfo<SocketAddr>,
    Query(params): Query<ReadParams>,
) -> Result<Json<CountResponse>, (StatusCode, String)> {
    enforce_rate_limit(
        &state,
        route_profile,
        RateRoute::Count,
        remote_addr.ip(),
        COUNT_RATE_LIMIT,
    )?;
    let (wx_id, id) = match (params.wx_id, params.id) {
        (Some(wx_id), Some(id)) => (wx_id, id),
        _ => {
            return Err((
                StatusCode::BAD_REQUEST,
                "wxId and id are required".to_owned(),
            ));
        }
    };
    if !valid_standalone_read_params(&wx_id, &id)
        || (route_profile == RouteProfile::Embedded && !valid_message_id(&id))
    {
        return Err((StatusCode::BAD_REQUEST, "invalid query fields".to_owned()));
    }
    let mut rows = state
        .db
        .query(
            "SELECT COUNT(DISTINCT ip) FROM reads WHERE id = ?1 AND wx_id = ?2",
            libsql::params![id, wx_id],
        )
        .await
        .map_err(database_response_error("query failed"))?;
    let count = match rows
        .next()
        .await
        .map_err(database_response_error("row read failed"))?
    {
        Some(row) => match row.get_value(0) {
            Ok(libsql::Value::Integer(count)) => count,
            _ => 0,
        },
        None => 0,
    };
    Ok(Json(CountResponse { count }))
}

fn valid_message_id(id: &str) -> bool {
    id.len() == 64
        && id
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

fn valid_wx_id(wx_id: &str) -> bool {
    !wx_id.is_empty() && wx_id.len() <= MAX_WX_ID_BYTES
}

fn valid_standalone_read_params(wx_id: &str, id: &str) -> bool {
    valid_wx_id(wx_id) && !id.is_empty() && id.len() <= MAX_MESSAGE_ID_BYTES
}

fn valid_embedded_read_params(wx_id: &str, id: &str) -> bool {
    valid_standalone_read_params(wx_id, id) && valid_message_id(id)
}

fn enforce_rate_limit(
    state: &AppState,
    route_profile: RouteProfile,
    route: RateRoute,
    peer_ip: IpAddr,
    limit: u32,
) -> Result<(), (StatusCode, String)> {
    if route_profile == RouteProfile::Standalone {
        return Ok(());
    }
    let now = Instant::now();
    let mut limits = state.rate_limits.lock().unwrap();
    limits.retain(|_, window| now.duration_since(window.started_at) < RATE_WINDOW);
    let window = limits.entry((route, peer_ip)).or_insert(RateWindow {
        started_at: now,
        requests: 0,
    });
    if now.duration_since(window.started_at) >= RATE_WINDOW {
        window.started_at = now;
        window.requests = 0;
    }
    if window.requests >= limit {
        return Err((
            StatusCode::TOO_MANY_REQUESTS,
            "rate limit exceeded".to_owned(),
        ));
    }
    window.requests += 1;
    Ok(())
}

async fn list_messages(
    State(state): State<Arc<AppState>>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<MessageRecord>>, (StatusCode, String)> {
    let query = params.get("q").map(String::as_str).unwrap_or("");
    let mut rows = if query.is_empty() {
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
                libsql::params![format!("%{query}%")],
            )
            .await
    }
    .map_err(database_response_error("query failed"))?;
    collect_messages(&mut rows).await
}

async fn list_messages_for_sender(
    State(state): State<Arc<AppState>>,
    Path(wx_id): Path<String>,
    Query(params): Query<HashMap<String, String>>,
) -> Result<Json<Vec<MessageRecord>>, (StatusCode, String)> {
    let query = params.get("q").map(String::as_str).unwrap_or("");
    let mut rows = if query.is_empty() {
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
                 FROM messages m WHERE m.wx_id = ?1 AND m.content LIKE ?2
                 ORDER BY m.timestamp DESC",
                libsql::params![wx_id, format!("%{query}%")],
            )
            .await
    }
    .map_err(database_response_error("query failed"))?;
    collect_messages(&mut rows).await
}

async fn collect_messages(
    rows: &mut libsql::Rows,
) -> Result<Json<Vec<MessageRecord>>, (StatusCode, String)> {
    let mut messages = Vec::new();
    while let Some(row) = rows
        .next()
        .await
        .map_err(database_response_error("row read failed"))?
    {
        messages.push(MessageRecord {
            id: row.get_str(0).unwrap_or_default().to_owned(),
            wx_id: row.get_str(1).unwrap_or_default().to_owned(),
            content: row.get_str(2).unwrap_or_default().to_owned(),
            timestamp: row.get_str(3).unwrap_or_default().to_owned(),
            reads: match row.get_value(4) {
                Ok(libsql::Value::Integer(count)) => count,
                _ => 0,
            },
        });
    }
    Ok(Json(messages))
}

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
        .map_err(database_response_error("query failed"))?;
    let mut reads = Vec::new();
    while let Some(row) = rows
        .next()
        .await
        .map_err(database_response_error("row read failed"))?
    {
        reads.push(ReadRecord {
            ip: row.get_str(0).unwrap_or_default().to_owned(),
            timestamp: row.get_str(1).unwrap_or_default().to_owned(),
        });
    }
    Ok(Json(reads))
}

async fn delete_all_messages(
    State(state): State<Arc<AppState>>,
) -> Result<Json<serde_json::Value>, (StatusCode, String)> {
    state
        .db
        .execute("DELETE FROM reads", ())
        .await
        .map_err(database_response_error("delete failed"))?;
    state
        .db
        .execute("DELETE FROM messages", ())
        .await
        .map_err(database_response_error("delete failed"))?;
    Ok(Json(serde_json::json!({"status": "ok"})))
}

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
        .map_err(database_response_error("delete failed"))?;
    state
        .db
        .execute(
            "DELETE FROM messages WHERE wx_id = ?1",
            libsql::params![wx_id],
        )
        .await
        .map_err(database_response_error("delete failed"))?;
    Ok(Json(serde_json::json!({"status": "ok"})))
}

fn database_response_error(
    operation: &'static str,
) -> impl FnOnce(libsql::Error) -> (StatusCode, String) {
    move |_| {
        error!("{operation}");
        (StatusCode::INTERNAL_SERVER_ERROR, operation.to_owned())
    }
}

#[cfg(test)]
mod lib_tests;
