#[path = "../chroot_cleanup.rs"]
mod chroot_cleanup;

fn main() {
    let result = chroot_cleanup::parse_request(std::env::args().skip(1))
        .and_then(|request| chroot_cleanup::run(&request));
    if let Err(error) = result {
        eprintln!("{error}");
        std::process::exit(75);
    }
}
