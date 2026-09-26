#![cfg(feature = "cli")]

use axum::{
    Router,
    body::{Body, to_bytes},
    extract::ConnectInfo,
    http::{Request, StatusCode},
    response::Response,
};
use serde_json::{Value, json};
use std::{
    fs,
    io::Write,
    net::SocketAddr,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};
use tower::ServiceExt;
use wekit_read_receipts_server::{AppState, build_router, initialize_database};

struct TestDirectory(PathBuf);

impl TestDirectory {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!("wekit-pixel-logging-{}", std::process::id()));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TestDirectory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

struct CapturedLogWriter(Arc<Mutex<Vec<u8>>>);

impl Write for CapturedLogWriter {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(buffer);
        Ok(buffer.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

async fn request(app: &Router, method: &str, uri: &str, body: Body, peer: &str) -> Response {
    app.clone()
        .oneshot(
            Request::builder()
                .method(method)
                .uri(uri)
                .header("content-type", "application/json")
                .extension(ConnectInfo(peer.parse::<SocketAddr>().unwrap()))
                .body(body)
                .unwrap(),
        )
        .await
        .unwrap()
}

async fn json_body(response: Response) -> Value {
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    serde_json::from_slice(&bytes).unwrap()
}

#[test]
fn pixel_info_logs_include_message_and_direct_peer() {
    let captured = Arc::new(Mutex::new(Vec::new()));
    let writer = Arc::clone(&captured);
    let subscriber = tracing_subscriber::fmt()
        .without_time()
        .with_ansi(false)
        .with_target(false)
        .with_level(false)
        .with_writer(move || CapturedLogWriter(Arc::clone(&writer)))
        .finish();

    tracing::subscriber::with_default(subscriber, || {
        tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap()
            .block_on(async {
                let directory = TestDirectory::new();
                let database =
                    libsql::Builder::new_local(directory.path().join("read-receipts.db"))
                        .build()
                        .await
                        .unwrap();
                initialize_database(&database).await.unwrap();
                let state = Arc::new(AppState::new(database.connect().unwrap()));
                let app = build_router(state);

                let registration = json!({
                    "wxId": "wxid_sender",
                    "content": "known",
                    "createTime": 1_700_000_000_456_i64,
                });
                let response = request(
                    &app,
                    "POST",
                    "/register",
                    Body::from(registration.to_string()),
                    "127.0.0.1:41000",
                )
                .await;
                let known_id = json_body(response).await["id"].as_str().unwrap().to_owned();
                let response = request(
                    &app,
                    "GET",
                    &format!("/pixel?wxId=wxid_sender&id={known_id}"),
                    Body::empty(),
                    "192.0.2.61:45001",
                )
                .await;
                assert_eq!(response.status(), StatusCode::OK);

                let response = request(
                    &app,
                    "GET",
                    "/pixel?wxId=wxid_standalone&id=standalone-unknown",
                    Body::empty(),
                    "192.0.2.62:45002",
                )
                .await;
                assert_eq!(response.status(), StatusCode::OK);

                let logs = String::from_utf8(captured.lock().unwrap().clone()).unwrap();
                assert!(
                    logs.contains(&format!(
                        "id = {known_id}, wxId = wxid_sender, client_ip = 192.0.2.61"
                    )),
                    "{logs}"
                );
                assert!(
                    logs.contains(
                        "id = standalone-unknown, wxId = wxid_standalone, client_ip = 192.0.2.62"
                    ),
                    "{logs}"
                );
            });
    });
}
