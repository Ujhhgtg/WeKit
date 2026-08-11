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
use tokio::io::{AsyncReadExt, AsyncWriteExt};
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

async fn test_router(route_profile: RouteProfile) -> (TestDirectory, Router) {
    let directory = TestDirectory::new();
    let config = ServerConfig {
        database_path: directory.path().join("read-receipts.db"),
        bind_addr: "127.0.0.1".parse().unwrap(),
        bind_port: 0,
        route_profile,
        connector_authenticator: None,
    };
    let database = open_database(&config).await.unwrap();
    let state = Arc::new(AppState::new(database.connect().unwrap()));
    (directory, build_router(&config, state))
}

async fn embedded_test_router() -> (TestDirectory, Router) {
    test_router(RouteProfile::Embedded).await
}

async fn embedded_test_router_with_authenticator(authenticator: &str) -> (TestDirectory, Router) {
    let directory = TestDirectory::new();
    let config = ServerConfig {
        database_path: directory.path().join("read-receipts.db"),
        bind_addr: "127.0.0.1".parse().unwrap(),
        bind_port: 0,
        route_profile: RouteProfile::Embedded,
        connector_authenticator: None,
    }
    .with_connector_authenticator(authenticator)
    .unwrap();
    let database = open_database(&config).await.unwrap();
    let state = Arc::new(AppState::new(database.connect().unwrap()));
    (directory, build_router(&config, state))
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
    let (_directory, app) = embedded_test_router().await;
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
async fn embedded_pixel_does_not_log_an_unknown_message() {
    let (_directory, app) = embedded_test_router().await;
    let id = "68de1b0f00e04ead6cb3b1ea5677de4fc58df7570c803806b454ec2ada35e43a";
    let response = request(
        &app,
        "GET",
        &format!("/pixel?wxId=wxid_sender&id={id}"),
        Body::empty(),
        "192.0.2.20:43000",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);

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

    let response = request(
        &app,
        "GET",
        &format!("/count?wxId=wxid_sender&id={id}"),
        Body::empty(),
        "127.0.0.1:41000",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(json_body(response).await, json!({"count": 0}));
}

#[tokio::test]
async fn embedded_count_rejects_malformed_message_ids() {
    let (_directory, app) = embedded_test_router().await;
    let invalid_ids = [
        "a".repeat(63),
        "a".repeat(65),
        format!("{}g", "a".repeat(63)),
        "A".repeat(64),
    ];

    for id in invalid_ids {
        let response = request(
            &app,
            "GET",
            &format!("/count?wxId=wxid_sender&id={id}"),
            Body::empty(),
            "127.0.0.1:41000",
        )
        .await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "id={id}");
    }
}

#[tokio::test]
async fn both_profiles_reject_oversized_registration_fields() {
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

    for profile in [RouteProfile::Standalone, RouteProfile::Embedded] {
        let (_directory, app) = test_router(profile).await;
        for body in &bodies {
            let response = request(
                &app,
                "POST",
                "/register",
                Body::from(body.to_string()),
                "127.0.0.1:41000",
            )
            .await;
            assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{profile:?}");
        }
    }
}

#[tokio::test]
async fn both_profiles_cap_the_registration_body() {
    let body = json!({
        "wxId": "wxid_sender",
        "content": "x".repeat(21 * 1024),
        "createTime": 1_i64,
    });
    for profile in [RouteProfile::Standalone, RouteProfile::Embedded] {
        let (_directory, app) = test_router(profile).await;
        let response = request(
            &app,
            "POST",
            "/register",
            Body::from(body.to_string()),
            "127.0.0.1:41000",
        )
        .await;

        assert_eq!(
            response.status(),
            StatusCode::PAYLOAD_TOO_LARGE,
            "{profile:?}"
        );
    }
}

#[tokio::test]
async fn both_profiles_reject_oversized_query_fields() {
    let wx_id = "w".repeat(129);
    let id = "a".repeat(64);
    for profile in [RouteProfile::Standalone, RouteProfile::Embedded] {
        let (_directory, app) = test_router(profile).await;
        let response = request(
            &app,
            "GET",
            &format!("/count?wxId={wx_id}&id={id}"),
            Body::empty(),
            "127.0.0.1:41000",
        )
        .await;

        assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{profile:?}");
    }
}

#[tokio::test]
async fn both_profiles_cap_the_raw_query_string() {
    let id = "a".repeat(64);
    let padding = "x".repeat(1025);
    for profile in [RouteProfile::Standalone, RouteProfile::Embedded] {
        let (_directory, app) = test_router(profile).await;
        let response = request(
            &app,
            "GET",
            &format!("/count?wxId=wxid_sender&id={id}&ignored={padding}"),
            Body::empty(),
            "127.0.0.1:41000",
        )
        .await;

        assert_eq!(response.status(), StatusCode::URI_TOO_LONG, "{profile:?}");
    }
}

#[tokio::test]
async fn forwarded_headers_do_not_override_the_direct_tcp_peer() {
    let (_directory, app) = embedded_test_router().await;
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
async fn authenticated_connector_metadata_counts_distinct_public_readers() {
    let authenticator = "0123456789abcdef0123456789abcdef";
    let (_directory, app) = embedded_test_router_with_authenticator(authenticator).await;
    let registration = json!({
        "wxId": "wxid_sender",
        "content": "hello",
        "createTime": 1_700_000_000_124_i64,
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

    for reader_ip in ["198.51.100.20", "2001:db8::20"] {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/pixel?wxId=wxid_sender&id={id}"))
                    .header("x-wekit-origin-authenticator", authenticator)
                    .header("x-wekit-reader-ip", reader_ip)
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
    assert_eq!(json_body(response).await, json!({"count": 2}));
}

#[tokio::test]
async fn direct_callers_cannot_opt_into_trusted_reader_metadata() {
    let authenticator = "0123456789abcdef0123456789abcdef";
    let (_directory, app) = embedded_test_router_with_authenticator(authenticator).await;
    let registration = json!({
        "wxId": "wxid_sender",
        "content": "hello",
        "createTime": 1_700_000_000_125_i64,
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

    for claimed_ip in ["198.51.100.30", "198.51.100.31"] {
        let response = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri(format!("/pixel?wxId=wxid_sender&id={id}"))
                    .header("x-wekit-origin-authenticator", "attacker-controlled")
                    .header("x-wekit-reader-ip", claimed_ip)
                    .header("cf-connecting-ip", claimed_ip)
                    .extension(ConnectInfo(
                        "127.0.0.1:43000".parse::<SocketAddr>().unwrap(),
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
    let (_directory, app) = test_router(RouteProfile::Standalone).await;
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
async fn embedded_health_is_metadata_free() {
    let (_directory, app) = embedded_test_router().await;
    let response = request(&app, "GET", "/health", Body::empty(), "127.0.0.1:41000").await;

    assert_eq!(response.status(), StatusCode::NO_CONTENT);
    assert!(!response.headers().contains_key("content-type"));
    assert!(
        to_bytes(response.into_body(), usize::MAX)
            .await
            .unwrap()
            .is_empty()
    );
}

#[tokio::test]
async fn route_profiles_keep_management_routes_standalone_only() {
    let (_embedded_directory, embedded) = test_router(RouteProfile::Embedded).await;
    for (method, uri) in [
        ("GET", "/"),
        ("GET", "/messages"),
        ("DELETE", "/messages"),
        ("GET", "/messages/wxid_sender"),
        ("DELETE", "/messages/wxid_sender"),
        ("GET", "/reads/message-id"),
    ] {
        let response = request(&embedded, method, uri, Body::empty(), "127.0.0.1:41000").await;
        assert_eq!(response.status(), StatusCode::NOT_FOUND, "{method} {uri}");
    }

    let (_standalone_directory, standalone) = test_router(RouteProfile::Standalone).await;
    for uri in [
        "/",
        "/messages",
        "/messages/wxid_sender",
        "/reads/message-id",
    ] {
        let response = request(&standalone, "GET", uri, Body::empty(), "127.0.0.1:41000").await;
        assert_eq!(response.status(), StatusCode::OK, "GET {uri}");
    }
    let response = request(
        &standalone,
        "GET",
        "/health",
        Body::empty(),
        "127.0.0.1:41000",
    )
    .await;
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn embedded_register_rate_limit_is_per_ip_and_standalone_is_unlimited() {
    let body = json!({
        "wxId": "wxid_sender",
        "content": "hello",
        "createTime": 1_i64,
    })
    .to_string();
    let (_embedded_directory, embedded) = test_router(RouteProfile::Embedded).await;
    for port in 42000..42030 {
        let response = request(
            &embedded,
            "POST",
            "/register",
            Body::from(body.clone()),
            &format!("192.0.2.30:{port}"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK, "port={port}");
    }
    let response = request(
        &embedded,
        "POST",
        "/register",
        Body::from(body.clone()),
        "192.0.2.30:42030",
    )
    .await;
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);

    let response = request(
        &embedded,
        "POST",
        "/register",
        Body::from(body.clone()),
        "192.0.2.31:42031",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);

    let (_standalone_directory, standalone) = test_router(RouteProfile::Standalone).await;
    for port in 42000..42031 {
        let response = request(
            &standalone,
            "POST",
            "/register",
            Body::from(body.clone()),
            &format!("192.0.2.30:{port}"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK, "standalone port={port}");
    }
}

#[tokio::test]
async fn embedded_count_rate_limit_bounds_polling_per_ip() {
    let (_directory, app) = test_router(RouteProfile::Embedded).await;
    let id = "a".repeat(64);
    for port in 43000..43120 {
        let response = request(
            &app,
            "GET",
            &format!("/count?wxId=wxid_sender&id={id}"),
            Body::empty(),
            &format!("192.0.2.40:{port}"),
        )
        .await;
        assert_eq!(response.status(), StatusCode::OK, "port={port}");
    }
    let response = request(
        &app,
        "GET",
        &format!("/count?wxId=wxid_sender&id={id}"),
        Body::empty(),
        "192.0.2.40:43120",
    )
    .await;
    assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn embedded_pixel_rejects_invalid_fields_but_still_returns_the_static_png() {
    let directory = TestDirectory::new();
    let config = ServerConfig {
        database_path: directory.path().join("read-receipts.db"),
        bind_addr: "127.0.0.1".parse().unwrap(),
        bind_port: 0,
        route_profile: RouteProfile::Embedded,
        connector_authenticator: None,
    };
    let database = open_database(&config).await.unwrap();
    let connection = database.connect().unwrap();
    let invalid_id = "f".repeat(63);
    connection
        .execute(
            "INSERT INTO messages (id, wx_id, content, timestamp) VALUES (?1, ?2, ?3, ?4)",
            libsql::params![
                invalid_id.clone(),
                "wxid_sender",
                "hello",
                "2026-08-08 00:00:00"
            ],
        )
        .await
        .unwrap();
    let app = build_router(
        &config,
        Arc::new(AppState::new(database.connect().unwrap())),
    );

    let response = request(
        &app,
        "GET",
        &format!("/pixel?wxId=wxid_sender&id={invalid_id}"),
        Body::empty(),
        "192.0.2.50:44000",
    )
    .await;
    assert_eq!(response.status(), StatusCode::OK);
    assert_eq!(response.headers()["content-type"], "image/png");
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    assert_eq!(&bytes[..8], b"\x89PNG\r\n\x1a\n");

    let mut rows = connection
        .query("SELECT COUNT(*) FROM reads", ())
        .await
        .unwrap();
    let row = rows.next().await.unwrap().unwrap();
    assert_eq!(row.get::<i64>(0).unwrap(), 0);
}

#[tokio::test]
async fn pixel_stays_static_when_read_insertion_fails() {
    let directory = TestDirectory::new();
    let config = ServerConfig {
        database_path: directory.path().join("read-receipts.db"),
        bind_addr: "127.0.0.1".parse().unwrap(),
        bind_port: 0,
        route_profile: RouteProfile::Embedded,
        connector_authenticator: None,
    };
    let database = open_database(&config).await.unwrap();
    let connection = database.connect().unwrap();
    let id = "68de1b0f00e04ead6cb3b1ea5677de4fc58df7570c803806b454ec2ada35e43a";
    connection
        .execute(
            "INSERT INTO messages (id, wx_id, content, timestamp) VALUES (?1, ?2, ?3, ?4)",
            libsql::params![id, "wxid_sender", "hello", "2026-08-08 00:00:00"],
        )
        .await
        .unwrap();
    let app = build_router(
        &config,
        Arc::new(AppState::new(database.connect().unwrap())),
    );
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

#[tokio::test]
async fn bind_and_serve_reports_the_bound_address_and_shuts_down() {
    let directory = TestDirectory::new();
    let config = ServerConfig {
        database_path: directory.path().join("read-receipts.db"),
        bind_addr: "127.0.0.1".parse().unwrap(),
        bind_port: 0,
        route_profile: RouteProfile::Embedded,
        connector_authenticator: None,
    };
    let server = bind_and_serve(config, std::future::pending())
        .await
        .unwrap();
    let local_addr = server.local_addr();
    assert_eq!(
        local_addr.ip(),
        "127.0.0.1".parse::<std::net::IpAddr>().unwrap()
    );
    assert_ne!(local_addr.port(), 0);

    let mut stream = tokio::net::TcpStream::connect(local_addr).await.unwrap();
    stream
        .write_all(b"GET /health HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
        .await
        .unwrap();
    let mut response = Vec::new();
    stream.read_to_end(&mut response).await.unwrap();
    assert!(response.starts_with(b"HTTP/1.1 204 No Content\r\n"));

    server.shutdown().await.unwrap();
    assert!(tokio::net::TcpStream::connect(local_addr).await.is_err());
}
