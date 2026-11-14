use super::*;
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
    net::SocketAddr,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
};
use tower::ServiceExt;

#[test]
fn message_id_uses_nul_separated_utf8_fields_and_decimal_time() {
    let vectors = [
        (
            "wxid_example",
            "需要追踪的消息内容",
            1_785_859_200_000,
            "4d06ffb18a58874acfa6bdc89b2cc653b009b1618887b62fc77956b92f33835b",
        ),
        (
            "a",
            "bc",
            0,
            "ae75805c145371a180a19f8c7114594c1f21f51f860849713b35be2e551e4d2b",
        ),
        (
            "ab",
            "c",
            0,
            "25f558889bd7fe8cb0713c107e2b5ae31d04156481f98381f7197fa28ad31a3d",
        ),
        (
            "",
            "",
            -1,
            "05b54c889ce5912bbcc28ff985013037d08bc6249f09c7a1e554d3aeb3ee2c4b",
        ),
    ];

    for (wx_id, content, create_time, expected) in vectors {
        assert_eq!(compute_msg_id(wx_id, content, create_time), expected);
    }
}

struct TestDirectory(PathBuf);

impl TestDirectory {
    fn new() -> Self {
        static NEXT_ID: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "wekit-read-receipts-{}-{}",
            std::process::id(),
            NEXT_ID.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &std::path::Path {
        &self.0
    }
}

impl Drop for TestDirectory {
    fn drop(&mut self) {
        fs::remove_dir_all(&self.0).unwrap();
    }
}

async fn test_database(directory: &TestDirectory) -> Database {
    let database = libsql::Builder::new_local(directory.path().join("read-receipts.db"))
        .build()
        .await
        .unwrap();
    initialize_database(&database).await.unwrap();
    database
}

async fn test_router() -> (TestDirectory, Router) {
    let directory = TestDirectory::new();
    let database = test_database(&directory).await;
    let state = Arc::new(AppState::new(database.connect().unwrap()));
    (directory, build_router(state))
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

#[tokio::test]
async fn register_and_count_deduplicate_reads_by_peer_ip() {
    let (_directory, app) = test_router().await;
    let registration = json!({
        "wxId": "wxid_sender",
        "content": "hello",
        "createTime": 1_700_000_000_123_i64,
    });
    let response = request(
        &app,
        "POST",
        "/register",
        Body::from(registration.to_string()),
        "127.0.0.1:41000",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    let id = json_body(response).await["id"].as_str().unwrap().to_owned();
    assert_eq!(
        id,
        "68de1b0f00e04ead6cb3b1ea5677de4fc58df7570c803806b454ec2ada35e43a"
    );

    let pixel_uri = format!("/pixel?wxId=wxid_sender&id={id}");
    for peer in ["192.0.2.10:42000", "192.0.2.10:42001", "192.0.2.11:42002"] {
        let response = request(&app, "GET", &pixel_uri, Body::empty(), peer).await;
        assert_eq!(response.status(), StatusCode::OK);
    }

    let response = request(
        &app,
        "GET",
        &format!("/count?wxId=wxid_sender&id={id}"),
        Body::empty(),
        "127.0.0.1:41000",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(json_body(response).await, json!({"count": 2}));
}

#[tokio::test]
async fn server_reject_oversized_registration_fields() {
    let bodies = [
        json!({
            "wxId": "w".repeat(129),
            "content": "hello",
            "createTime": 1_i64,
        }),
        json!({
            "wxId": "wxid_sender",
            "content": "界".repeat(5_462),
            "createTime": 1_i64,
        }),
    ];

    let (_directory, app) = test_router().await;
    for body in &bodies {
        let response = request(
            &app,
            "POST",
            "/register",
            Body::from(body.to_string()),
            "127.0.0.1:41000",
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    }
}

#[tokio::test]
async fn server_cap_the_registration_body() {
    let body = json!({
        "wxId": "wxid_sender",
        "content": "x".repeat(21 * 1024),
        "createTime": 1_i64,
    });
    let (_directory, app) = test_router().await;
    let response = request(
        &app,
        "POST",
        "/register",
        Body::from(body.to_string()),
        "127.0.0.1:41000",
    )
    .await;

    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE,);
}

#[tokio::test]
async fn server_reject_oversized_query_fields() {
    let wx_id = "w".repeat(129);
    let id = "a".repeat(64);
    let (_directory, app) = test_router().await;
    let response = request(
        &app,
        "GET",
        &format!("/count?wxId={wx_id}&id={id}"),
        Body::empty(),
        "127.0.0.1:41000",
    )
    .await;

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn server_cap_the_raw_query_string() {
    let id = "a".repeat(64);
    let padding = "x".repeat(1025);
    let (_directory, app) = test_router().await;
    let response = request(
        &app,
        "GET",
        &format!("/count?wxId=wxid_sender&id={id}&ignored={padding}"),
        Body::empty(),
        "127.0.0.1:41000",
    )
    .await;

    assert_eq!(response.status(), StatusCode::URI_TOO_LONG);
}

#[tokio::test]
async fn forwarded_headers_do_not_override_the_direct_tcp_peer() {
    let (_directory, app) = test_router().await;
    let registration = json!({
        "wxId": "wxid_sender",
        "content": "hello",
        "createTime": 1_700_000_000_123_i64,
    });
    let response = request(
        &app,
        "POST",
        "/register",
        Body::from(registration.to_string()),
        "127.0.0.1:41000",
    )
    .await;
    let id = json_body(response).await["id"].as_str().unwrap().to_owned();

    for forwarded_ip in ["198.51.100.10", "198.51.100.11"] {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/pixel?wxId=wxid_sender&id={id}"))
                    .header("forwarded", format!("for={forwarded_ip}"))
                    .header("x-forwarded-for", forwarded_ip)
                    .header("cf-connecting-ip", forwarded_ip)
                    .extension(ConnectInfo(
                        "127.0.0.1:42000".parse::<SocketAddr>().unwrap(),
                    ))
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::OK);
    }

    let response = request(
        &app,
        "GET",
        &format!("/count?wxId=wxid_sender&id={id}"),
        Body::empty(),
        "127.0.0.1:41000",
    )
    .await;
    assert_eq!(json_body(response).await, json!({"count": 1}));
}

#[tokio::test]
async fn standalone_management_paths_enforce_protocol_bounds() {
    let (_directory, app) = test_router().await;
    let valid_wx_id = "w".repeat(128);
    let oversized_wx_id = "w".repeat(129);
    let valid_id = "a".repeat(128);
    let oversized_id = "a".repeat(129);

    for (method, uri, expected) in [
        ("GET", format!("/messages/{valid_wx_id}"), StatusCode::OK),
        (
            "GET",
            format!("/messages/{oversized_wx_id}"),
            StatusCode::BAD_REQUEST,
        ),
        (
            "DELETE",
            format!("/messages/{oversized_wx_id}"),
            StatusCode::BAD_REQUEST,
        ),
        ("GET", format!("/reads/{valid_id}"), StatusCode::OK),
        (
            "GET",
            format!("/reads/{oversized_id}"),
            StatusCode::BAD_REQUEST,
        ),
    ] {
        let response = request(&app, method, &uri, Body::empty(), "127.0.0.1:41000").await;
        assert_eq!(response.status(), expected, "{method} {uri}");
    }
}

#[tokio::test]
async fn pixel_stays_static_when_read_insertion_fails() {
    let directory = TestDirectory::new();
    let database = test_database(&directory).await;
    let connection = database.connect().unwrap();
    let id = "68de1b0f00e04ead6cb3b1ea5677de4fc58df7570c803806b454ec2ada35e43a";
    connection
        .execute(
            "INSERT INTO messages (id, wx_id, content, timestamp) VALUES (?1, ?2, ?3, ?4)",
            libsql::params![id, "wxid_sender", "hello", "2026-08-08 00:00:00"],
        )
        .await
        .unwrap();
    let app = build_router(Arc::new(AppState::new(database.connect().unwrap())));
    connection.execute("DROP TABLE reads", ()).await.unwrap();

    let response = request(
        &app,
        "GET",
        &format!("/pixel?wxId=wxid_sender&id={id}"),
        Body::empty(),
        "192.0.2.51:44001",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["content-type"], "image/png");
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");
}
