//! Desktop CLI for the WeKit llama server.
//!
//! ```text
//! llama_server <model.gguf> [--ctx N] [--threads N] [--backend auto|cpu|vulkan|opencl] [--port P] [--fork]
//! ```
//!
//! Direct mode runs [`serve`] on this process's runtime; `--fork` runs the
//! server in a forked child (the production Android topology) and the main
//! thread blocks on the child. Defaults: ctx 4096, threads = detected
//! performance cores, backend auto, ephemeral port, sampling preset
//! temp 0.6 / top_p 0.95 / top_k 20, idle exit after 600s.

use std::sync::Arc;

use wekit_llama::fork::{self, ChildEvent};
use wekit_llama::llama::{Backend, EngineConfig, detect_threads};
use wekit_llama::server::{HttpServerConfig, serve};

const USAGE: &str = "usage: llama_server <model.gguf> [--ctx N] [--threads N] \
                     [--backend auto|cpu|vulkan|opencl] [--port P] [--fork]";

fn main() {
    let mut model_path: Option<String> = None;
    let mut n_ctx: u32 = 4096;
    let mut threads: Option<i32> = None;
    let mut backend = Backend::Auto;
    let mut bind_port: u16 = 0;
    let mut do_fork = false;

    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--ctx" => n_ctx = parse_value(&mut args, &arg),
            "--threads" => threads = Some(parse_value(&mut args, &arg)),
            "--backend" => {
                let value: String = parse_value(&mut args, &arg);
                backend = Backend::parse(&value).unwrap_or_else(|| {
                    eprintln!("unknown backend '{value}' (auto|cpu|vulkan|opencl)");
                    std::process::exit(2);
                });
            }
            "--port" => bind_port = parse_value(&mut args, &arg),
            "--fork" => do_fork = true,
            other => {
                if other.starts_with("--") || model_path.is_some() {
                    eprintln!("{USAGE}");
                    std::process::exit(2);
                }
                model_path = Some(other.to_owned());
            }
        }
    }
    let Some(model_path) = model_path else {
        eprintln!("{USAGE}");
        std::process::exit(2);
    };

    let cfg = HttpServerConfig {
        engine: EngineConfig {
            model_path,
            n_ctx,
            threads: threads.unwrap_or_else(detect_threads),
            backend,
            temp: 0.6,
            top_p: 0.95,
            top_k: 20,
            idle_timeout_secs: 600,
        },
        bind_port,
    };

    if do_fork {
        run_forked(cfg);
    } else {
        run_direct(cfg);
    }
}

fn parse_value<T: std::str::FromStr>(args: &mut impl Iterator<Item = String>, flag: &str) -> T {
    let value = args.next().unwrap_or_else(|| {
        eprintln!("missing value for {flag}\n{USAGE}");
        std::process::exit(2);
    });
    value.parse().unwrap_or_else(|_| {
        eprintln!("invalid value for {flag}: {value}");
        std::process::exit(2);
    })
}

fn run_direct(cfg: HttpServerConfig) {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("wekit-llama-io")
        .enable_all()
        .build()
        .unwrap_or_else(|e| {
            eprintln!("llama_server: tokio runtime: {e}");
            std::process::exit(1);
        });
    if let Err(e) = runtime.block_on(serve(cfg, |port| {
        println!("llama_server listening on 127.0.0.1:{port}");
    })) {
        eprintln!("llama_server: {e}");
        std::process::exit(1);
    }
}

fn run_forked(cfg: HttpServerConfig) {
    let on_event = Arc::new(|event: ChildEvent| match event {
        ChildEvent::Ready { port } => println!("llama_server child ready port={port}"),
        ChildEvent::Exiting { reason } => println!("llama_server child exiting: {reason}"),
        ChildEvent::Died { reason } => eprintln!("llama_server child died: {reason}"),
    });
    let child = fork::fork_server(cfg, on_event).unwrap_or_else(|e| {
        eprintln!("llama_server: {e}");
        std::process::exit(1);
    });
    println!("ready port={} pid={}", child.port, child.pid);
    // The watchdog thread reports child events; the main thread waits for
    // the child itself (blocking waitpid; the reap in the watchdog either
    // wins — then this returns ECHILD — or this wins).
    let status = unsafe { libc::waitpid(child.pid, std::ptr::null_mut(), 0) };
    if status < 0 && std::io::Error::last_os_error().raw_os_error() != Some(libc::ECHILD) {
        let err = std::io::Error::last_os_error();
        eprintln!("llama_server: waiting for child: {err}");
        std::process::exit(1);
    }
    println!("llama_server child finished");
}
