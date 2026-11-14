// companion — Zygisk root process request handler.
//
// Checks the injection allow-list, handles enable/disable queries,
// and negotiates a Telegram worker socket via double-fork.
// The worker is adopted by init; each connection checks the target
// is still enabled, then handles DISCOVER (/data/user/{uid} known
// Telegram packages) or COPY_DATABASE (cache4.db + wal + shm).

#![allow(clippy::unnecessary_cast)]

use crate::protocol::*;
use crate::{loge, logi, logw};
use libc::{AF_UNIX, SOCK_STREAM, c_int, sockaddr_un};
use std::{ffi::CString, fs};

const TARGETS_PATH: &str = "/data/adb/wekit_zygisk/injection-targets.tsv";
const APP_USER_RANGE: i32 = 100_000;

// ── Allow-list ────────────────────────────────────────────────────────────────

fn is_enabled_target(uid: i32, process_name: &str) -> bool {
    let user_id = uid / APP_USER_RANGE;
    let content = match fs::read_to_string(TARGETS_PATH) {
        Ok(s) => s,
        Err(_) => return false,
    };
    for line in content.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.splitn(4, '\t').collect();
        if parts.len() != 3 {
            continue;
        }
        let (row_user, pkg, enabled) = (parts[0], parts[1], parts[2]);
        if enabled != "1" {
            continue;
        }
        if !pkg.starts_with("com.tencent.mm") {
            continue;
        }
        let row_uid: i32 = match row_user.parse() {
            Ok(v) => v,
            Err(_) => continue,
        };
        if row_uid != user_id {
            continue;
        }
        if process_name == pkg || process_name.starts_with(&format!("{pkg}:")) {
            return true;
        }
    }
    false
}

// ── Telegram package identification ────────────────────────────────────────────

static KNOWN_TELEGRAM_PACKAGES: &[&str] = &[
    "org.telegram.messenger",
    "org.telegram.messenger.beta",
    "org.telegram.plus",
    "nekox.messenger",
    "com.jasonkhew96.pigeongram",
    "app.nicegram",
    "xyz.nextalone.nagram",
    "xyz.nextalone.nnngram",
    "com.xtaolabs.pagergram",
    "org.telegram.messenger.web",
    "com.cool2645.nekolite",
    "com.iMe.android",
    "org.telegram.BifToGram",
    "ua.itaysonlab.messenger",
    "org.forkclient.messenger.beta",
    "org.aka.messenger",
    "ellipi.messenger",
    "me.luvletter.nekox",
    "org.nift4.catox",
    "icu.ketal.yunigram",
    "icu.ketal.yunigram.lspatch",
    "icu.ketal.yunigram.beta",
    "icu.ketal.yunigram.lspatch.beta",
    "org.forkgram.messenger",
    "com.blxueya.gugugram",
    "com.radolyn.ayugram",
    "com.blxueya.gugugramx",
    "com.evildayz.code.telegraher",
    "com.exteragram.messenger",
];

fn is_valid_telegram_package(pkg: &str) -> bool {
    !pkg.is_empty()
        && pkg.len() <= 255
        && pkg
            .chars()
            .all(|c| c.is_alphanumeric() || c == '.' || c == '_')
}

fn is_known_telegram_package(pkg: &str) -> bool {
    if pkg.contains("gram") {
        return true;
    }
    KNOWN_TELEGRAM_PACKAGES.contains(&pkg)
}

// ── Telegram database path ────────────────────────────────────────────────────────

/// Parse shared_prefs/userconfing.xml for selectedAccount.
fn read_selected_account(app_dir: &str) -> i32 {
    let config = format!("{app_dir}/shared_prefs/userconfing.xml");
    let content = match fs::read_to_string(&config) {
        Ok(s) => s,
        Err(_) => return 0,
    };
    for line in content.lines() {
        if !line.contains("name=\"selectedAccount\"") {
            continue;
        }
        if let Some(v) = line.find("value=\"") {
            let begin = v + 7;
            if let Some(end) = line[begin..].find('"')
                && let Ok(n) = line[begin..begin + end].parse::<i32>()
            {
                return n;
            }
        }
    }
    0
}

/// Return the Telegram database path (cache4.db), or empty if not found.
/// Path: /data/user/{user_id}/{pkg}/files/[account{N}/]cache4.db
fn telegram_database_path(user_id: i32, pkg: &str) -> String {
    if user_id < 0 || !is_valid_telegram_package(pkg) {
        return String::new();
    }
    let app_dir = format!("/data/user/{user_id}/{pkg}");
    let account = read_selected_account(&app_dir);
    let db = if account == 0 {
        format!("{app_dir}/files/cache4.db")
    } else {
        format!("{app_dir}/files/account{account}/cache4.db")
    };
    let cdb = match CString::new(db.as_str()) {
        Ok(s) => s,
        Err(_) => return String::new(),
    };
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::stat(cdb.as_ptr(), &mut st) } == 0
        && (st.st_mode & libc::S_IFMT as u32) == libc::S_IFREG as u32
    {
        db
    } else {
        String::new()
    }
}

/// Scan /data/user/{user_id}/ for known Telegram packages with a valid database.
fn discover_telegram_packages(user_id: i32) -> Vec<String> {
    let user_dir = format!("/data/user/{user_id}");
    let mut packages = Vec::new();
    if let Ok(entries) = fs::read_dir(&user_dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if !is_valid_telegram_package(&name) || !is_known_telegram_package(&name) {
                continue;
            }
            if !telegram_database_path(user_id, &name).is_empty() {
                packages.push(name);
            }
        }
    }
    packages.sort();
    packages.dedup();
    packages.truncate(64);
    packages
}

// ── File sending ──────────────────────────────────────────────────────────────────

/// Send file contents: write u64 size then file bytes; required=false sends 0 on ENOENT.
fn send_file(sock: c_int, path: &str, required: bool) -> bool {
    let cpath = match CString::new(path) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    if unsafe { libc::stat(cpath.as_ptr(), &mut st) } != 0 {
        if !required && std::io::Error::last_os_error().raw_os_error().unwrap_or(0) == libc::ENOENT
        {
            let _ = write_u64_to_fd(sock, 0);
            return true;
        }
        loge!(
            "Zygisk: companion: stat {} failed: {}",
            path,
            std::io::Error::last_os_error()
        );
        return false;
    }
    if (st.st_mode & libc::S_IFMT as u32) != libc::S_IFREG as u32 {
        loge!("Zygisk: companion: {} is not a regular file", path);
        return false;
    }
    let size = st.st_size as u64;
    if write_u64_to_fd(sock, size).is_err() {
        return false;
    }
    if size == 0 {
        return true;
    }
    let fd = unsafe {
        libc::open(
            cpath.as_ptr(),
            libc::O_RDONLY | libc::O_CLOEXEC | libc::O_NOFOLLOW,
        )
    };
    if fd < 0 {
        loge!(
            "Zygisk: companion: open {} failed: {}",
            path,
            std::io::Error::last_os_error()
        );
        return false;
    }
    let mut remaining = size;
    let mut buf = [0u8; 65536];
    let mut ok = true;
    while remaining > 0 {
        let chunk = remaining.min(buf.len() as u64) as usize;
        let n = loop {
            let r = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), chunk) };
            if r < 0 && std::io::Error::last_os_error().raw_os_error().unwrap_or(0) == libc::EINTR {
                continue;
            }
            break r;
        };
        if n == 0 {
            logw!(
                "Zygisk: companion: unexpected EOF reading {} ({} bytes remaining)",
                path,
                remaining
            );
            ok = false;
            break;
        }
        if n < 0 {
            ok = false;
            break;
        }
        let mut written = 0usize;
        while written < n as usize {
            let w =
                unsafe { libc::write(sock, buf[written..].as_ptr().cast(), n as usize - written) };
            if w <= 0 {
                ok = false;
                break;
            }
            written += w as usize;
        }
        if !ok {
            break;
        }
        remaining -= n as u64;
    }
    unsafe { libc::close(fd) };
    ok
}

fn write_error_response(sock: c_int, msg: &str) {
    let _ = write_u8_to_fd(sock, TELEGRAM_RESPONSE_ERROR);
    let _ = write_string_to_fd(sock, msg);
}

// ── Telegram worker ────────────────────────────────────────────────────────────

/// Handle a single Telegram request from a connected client.
fn handle_telegram_request(client: c_int, user_id: i32) -> bool {
    let op = match read_u8_from_fd(client) {
        Ok(v) => v,
        Err(_) => {
            logw!("Zygisk: companion: failed to read Telegram request");
            return false;
        }
    };
    if op == TELEGRAM_REQUEST_DISCOVER {
        let packages = discover_telegram_packages(user_id);
        if packages.is_empty() {
            write_error_response(
                client,
                "未找到 cache4.db，请确认 Telegram 已登录并至少启动过一次",
            );
            return true;
        }
        let count = packages.len().min(64) as u16;
        let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_OK);
        let _ = write_u16_to_fd(client, count);
        for pkg in &packages {
            if write_string_to_fd(client, pkg).is_err() {
                return false;
            }
        }
        return true;
    }
    if op != TELEGRAM_REQUEST_COPY_DATABASE {
        write_error_response(client, "不支持的 Telegram Root 请求");
        return true;
    }
    let pkg = match read_string_from_fd(client) {
        Ok(p) if is_known_telegram_package(&p) => p,
        _ => {
            write_error_response(client, "Telegram 包名无效");
            return true;
        }
    };
    let db = telegram_database_path(user_id, &pkg);
    if db.is_empty() {
        write_error_response(client, "所选 Telegram 实例的 cache4.db 不可读");
        return true;
    }
    let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_OK);
    // required=true for main db, optional for wal/shm
    send_file(client, &db, true)
        && send_file(client, &format!("{db}-wal"), false)
        && send_file(client, &format!("{db}-shm"), false)
}

/// Background worker: loop accept, check is_enabled_target per connection, serve or reject.
fn telegram_worker(server_fd: c_int, uid: i32, process_name: String) {
    let user_id = uid / APP_USER_RANGE;
    loop {
        let client = unsafe {
            loop {
                let r = libc::accept(server_fd, std::ptr::null_mut(), std::ptr::null_mut());
                if r < 0
                    && std::io::Error::last_os_error().raw_os_error().unwrap_or(0) == libc::EINTR
                {
                    continue;
                }
                break r;
            }
        };
        if client < 0 {
            break;
        }
        if !is_enabled_target(uid, &process_name) {
            let _ = write_u8_to_fd(client, TELEGRAM_RESPONSE_DISABLED);
            unsafe { libc::close(client) };
            break;
        }
        handle_telegram_request(client, user_id);
        unsafe { libc::close(client) };
    }
    unsafe { libc::close(server_fd) };
}

// ── Request header ─────────────────────────────────────────────────────────────────

struct RequestHeader {
    request_type: u8,
    uid: i32,
    process_name: String,
}

fn read_header(sock: c_int) -> Option<RequestHeader> {
    let request_type = read_u8_from_fd(sock).ok()?;
    let uid = read_i32_from_fd(sock).ok()?;
    let process_name = read_string_from_fd(sock).ok()?;
    Some(RequestHeader {
        request_type,
        uid,
        process_name,
    })
}

fn random_nonce() -> u32 {
    let mut buf = [0u8; 4];
    let fd = unsafe { libc::open(c"/dev/urandom".as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd >= 0 {
        unsafe {
            libc::read(fd, buf.as_mut_ptr().cast(), 4);
            libc::close(fd);
        }
    }
    u32::from_ne_bytes(buf)
}

// ── Entry point ──────────────────────────────────────────────────────────────────────

/// Called from zygisk_companion_entry in lib.rs.
pub fn handle(sock: c_int) {
    let header = match read_header(sock) {
        Some(h) => h,
        None => {
            loge!("Zygisk: companion: failed to read request identity");
            return;
        }
    };
    let enabled = is_enabled_target(header.uid, &header.process_name);
    if header.request_type == COMPANION_REQUEST_ENABLED {
        let status = if enabled {
            COMPANION_ENABLED
        } else {
            COMPANION_DISABLED
        };
        if write_u8_to_fd(sock, status).is_err() {
            logw!("Zygisk: companion: failed to return target status");
        }
        return;
    }
    if header.request_type != COMPANION_REQUEST_TELEGRAM_SESSION {
        logw!(
            "Zygisk: companion: unsupported request type {:#x}",
            header.request_type
        );
        return;
    }
    if !enabled {
        let _ = write_u8_to_fd(sock, COMPANION_DISABLED);
        return;
    }

    // Create abstract Unix socket
    let fd = unsafe { libc::socket(AF_UNIX, SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        loge!(
            "Zygisk: companion: failed to create Telegram worker socket: {}",
            std::io::Error::last_os_error()
        );
        let _ = write_u8_to_fd(sock, COMPANION_DISABLED);
        return;
    }
    let nonce = random_nonce();
    let name = format!("wekit-tg-{}-{:08x}", header.uid, nonce);
    let name_bytes = name.as_bytes();
    let mut addr: sockaddr_un = unsafe { std::mem::zeroed() };
    addr.sun_family = AF_UNIX as u16;
    for (i, &b) in name_bytes.iter().enumerate() {
        addr.sun_path[1 + i] = b as libc::c_char;
    }
    let slen = (std::mem::size_of::<libc::sa_family_t>() + 1 + name_bytes.len()) as libc::socklen_t;
    if unsafe { libc::bind(fd, &addr as *const _ as *const libc::sockaddr, slen) } != 0
        || unsafe { libc::listen(fd, 8) } != 0
    {
        loge!(
            "Zygisk: companion: failed to bind/listen Telegram worker socket: {}",
            std::io::Error::last_os_error()
        );
        unsafe { libc::close(fd) };
        let _ = write_u8_to_fd(sock, COMPANION_DISABLED);
        return;
    }

    // Double-fork: intermediate child exits, grandchild adopted by init
    let uid = header.uid;
    let process_name = header.process_name.clone();
    let mid = unsafe { libc::fork() };
    if mid < 0 {
        loge!(
            "Zygisk: companion: fork for Telegram worker failed: {}",
            std::io::Error::last_os_error()
        );
        unsafe { libc::close(fd) };
        let _ = write_u8_to_fd(sock, COMPANION_DISABLED);
        return;
    }
    if mid == 0 {
        // Intermediate child
        let worker = unsafe { libc::fork() };
        if worker < 0 {
            unsafe { libc::_exit(1) };
        }
        if worker == 0 {
            // Grandchild (real worker)
            telegram_worker(fd, uid, process_name);
            unsafe { libc::_exit(0) };
        }
        unsafe { libc::_exit(0) }; // intermediate exits immediately
    }
    // Parent: wait for intermediate child, close our copy of server_fd
    unsafe {
        let mut status = 0i32;
        libc::waitpid(mid, &mut status, 0);
        libc::close(fd);
    }
    logi!("Zygisk: Telegram worker socket ready: {name}");
    let _ = write_u8_to_fd(sock, COMPANION_ENABLED);
    let _ = write_string_to_fd(sock, &name);
}
