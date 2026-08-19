use std::ffi::CString;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::path::{Path, PathBuf};
use std::time::Duration;

const PROC_ROOT: &str = "/proc";
const BOOT_ID_PATH: &str = "/proc/sys/kernel/random/boot_id";

#[derive(Debug, PartialEq)]
pub struct CleanupRequest {
    pub pid: i32,
    pub start_time: u64,
    pub boot_id: String,
    pub marker: String,
    pub rootfs: PathBuf,
    pub mount_targets: Vec<PathBuf>,
}

pub fn parse_request(args: impl IntoIterator<Item = String>) -> Result<CleanupRequest, String> {
    let mut args = args.into_iter();
    if args.next().as_deref() != Some("cleanup") {
        return Err(
            "usage: chroot_cleanup cleanup PID START_TIME BOOT_ID MARKER ROOTFS MOUNT...".into(),
        );
    }
    let pid = args
        .next()
        .ok_or("missing PID")?
        .parse::<i32>()
        .map_err(|_| "invalid PID")?;
    if pid <= 0 {
        return Err("invalid PID".into());
    }
    let start_time = args
        .next()
        .ok_or("missing start time")?
        .parse::<u64>()
        .map_err(|_| "invalid start time")?;
    let boot_id = args.next().ok_or("missing boot ID")?;
    if !is_uuid(&boot_id) {
        return Err("invalid boot ID".into());
    }
    let marker = args.next().ok_or("missing marker")?;
    if !marker
        .strip_prefix("wekit-chroot-run-")
        .is_some_and(is_uuid)
    {
        return Err("invalid run marker".into());
    }
    let rootfs = PathBuf::from(args.next().ok_or("missing rootfs")?);
    if !is_normal_absolute(&rootfs) {
        return Err("rootfs must be an absolute normalized path".into());
    }
    let mount_targets = args.map(PathBuf::from).collect::<Vec<_>>();
    if mount_targets.is_empty()
        || mount_targets
            .iter()
            .any(|path| !is_approved_mount(&rootfs, path))
    {
        return Err(
            "mount targets must be normalized rootfs proc, sys, dev, or storage paths".into(),
        );
    }
    Ok(CleanupRequest {
        pid,
        start_time,
        boot_id,
        marker,
        rootfs,
        mount_targets,
    })
}

fn is_normal_absolute(path: &Path) -> bool {
    path.is_absolute()
        && !path.components().any(|component| {
            matches!(
                component,
                std::path::Component::ParentDir | std::path::Component::CurDir
            )
        })
}

fn is_approved_mount(rootfs: &Path, path: &Path) -> bool {
    if !is_normal_absolute(path) {
        return false;
    }
    path.strip_prefix(rootfs)
        .ok()
        .and_then(|relative| relative.components().next())
        .is_some_and(|component| {
            matches!(
                component.as_os_str().to_str(),
                Some("proc" | "sys" | "dev" | "storage")
            )
        })
}

fn is_uuid(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
}

pub fn run(request: &CleanupRequest) -> Result<(), String> {
    run_with_paths(request, Path::new(PROC_ROOT), Path::new(BOOT_ID_PATH))
}

fn run_with_paths(
    request: &CleanupRequest,
    proc_root: &Path,
    boot_id_path: &Path,
) -> Result<(), String> {
    let pidfd = match pidfd_open(request.pid) {
        Ok(fd) => fd,
        Err(error) if error.raw_os_error() == Some(libc::ESRCH) => return Ok(()),
        Err(error) if unsupported_pidfd_error(&error).is_some() => {
            return Err(unsupported_pidfd_error(&error).unwrap());
        }
        Err(error) => return Err(format!("pidfd_open failed: {error}")),
    };
    let process = proc_root.join(request.pid.to_string());
    let namespace = open_read_only(&process.join("ns/mnt"))
        .map_err(|error| format!("cannot bind mount namespace: {error}"))?;
    validate_identity(request, &process, boot_id_path)?;
    if wait_pidfd(pidfd.as_raw_fd(), Duration::ZERO)? {
        return Err("process exited during identity validation; cleanup was not attempted".into());
    }

    pidfd_send_signal(pidfd.as_raw_fd(), libc::SIGTERM)?;
    unsafe {
        if libc::setns(namespace.as_raw_fd(), libc::CLONE_NEWNS) < 0 {
            return Err(format!("setns failed: {}", io::Error::last_os_error()));
        }
    }
    for target in &request.mount_targets {
        let target = CString::new(target.as_os_str().as_encoded_bytes())
            .map_err(|_| "mount target contains NUL")?;
        if unsafe { libc::umount2(target.as_ptr(), libc::MNT_DETACH) } < 0 {
            let error = io::Error::last_os_error();
            if !matches!(
                error.raw_os_error(),
                Some(libc::EINVAL) | Some(libc::ENOENT)
            ) {
                return Err(format!("mount cleanup failed: {error}"));
            }
        }
    }
    if !wait_pidfd(pidfd.as_raw_fd(), Duration::from_millis(2_500))? {
        pidfd_send_signal(pidfd.as_raw_fd(), libc::SIGKILL)?;
        if !wait_pidfd(pidfd.as_raw_fd(), Duration::from_millis(2_500))? {
            return Err("namespace process did not exit after SIGKILL".into());
        }
    }
    Ok(())
}

fn validate_identity(
    request: &CleanupRequest,
    process: &Path,
    boot_id_path: &Path,
) -> Result<(), String> {
    let boot_id = fs::read_to_string(boot_id_path)
        .map_err(|error| format!("cannot read boot ID: {error}"))?;
    if boot_id.trim() != request.boot_id {
        return Err("process identity mismatch: boot ID".into());
    }
    let stat = fs::read_to_string(process.join("stat"))
        .map_err(|error| format!("cannot read process stat: {error}"))?;
    let fields = stat
        .rsplit_once(") ")
        .ok_or("invalid process stat")?
        .1
        .split_whitespace()
        .collect::<Vec<_>>();
    let start_time = fields
        .get(19)
        .ok_or("process stat has no start time")?
        .parse::<u64>()
        .map_err(|_| "invalid process start time")?;
    if start_time != request.start_time {
        return Err("process identity mismatch: start time".into());
    }
    let cmdline = fs::read(process.join("cmdline"))
        .map_err(|error| format!("cannot read process cmdline: {error}"))?;
    if !cmdline
        .split(|byte| *byte == 0)
        .any(|arg| arg == request.marker.as_bytes())
    {
        return Err("process identity mismatch: command line".into());
    }
    Ok(())
}

fn open_read_only(path: &Path) -> io::Result<OwnedFd> {
    let path = CString::new(path.as_os_str().as_encoded_bytes())
        .map_err(|_| io::Error::from(io::ErrorKind::InvalidInput))?;
    let fd = unsafe { libc::open(path.as_ptr(), libc::O_RDONLY | libc::O_CLOEXEC) };
    if fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

fn pidfd_open(pid: i32) -> io::Result<OwnedFd> {
    let fd = unsafe { libc::syscall(libc::SYS_pidfd_open, pid, 0) as i32 };
    if fd < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { OwnedFd::from_raw_fd(fd) })
    }
}

fn unsupported_pidfd_error(error: &io::Error) -> Option<String> {
    (error.raw_os_error() == Some(libc::ENOSYS))
        .then(|| "pidfd_open is unsupported by this kernel; cleanup was not attempted".to_owned())
}

fn pidfd_send_signal(pidfd: i32, signal: i32) -> Result<(), String> {
    let result = unsafe {
        libc::syscall(
            libc::SYS_pidfd_send_signal,
            pidfd,
            signal,
            std::ptr::null::<libc::siginfo_t>(),
            0,
        )
    };
    if result == 0 {
        return Ok(());
    }
    let error = io::Error::last_os_error();
    if error.raw_os_error() == Some(libc::ESRCH) {
        Ok(())
    } else if error.raw_os_error() == Some(libc::ENOSYS) {
        Err("pidfd_send_signal is unsupported by this kernel; cleanup was not attempted".into())
    } else {
        Err(format!("pidfd_send_signal failed: {error}"))
    }
}

fn wait_pidfd(pidfd: i32, timeout: Duration) -> Result<bool, String> {
    let mut pollfd = libc::pollfd {
        fd: pidfd,
        events: libc::POLLIN,
        revents: 0,
    };
    let result = unsafe { libc::poll(&mut pollfd, 1, timeout.as_millis() as i32) };
    if result < 0 {
        Err(format!("pidfd poll failed: {}", io::Error::last_os_error()))
    } else {
        Ok(result > 0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_only_cleanup_operation_and_exact_identity() {
        let request = parse_request(
            [
                "cleanup",
                "42",
                "98765",
                "22222222-2222-2222-2222-222222222222",
                "wekit-chroot-run-11111111-1111-1111-1111-111111111111",
                "/rootfs",
                "/rootfs/dev",
                "/rootfs/proc",
            ]
            .map(str::to_owned),
        )
        .unwrap();
        assert_eq!(request.pid, 42);
        assert_eq!(request.start_time, 98765);
        assert_eq!(
            request.mount_targets,
            [PathBuf::from("/rootfs/dev"), PathBuf::from("/rootfs/proc")]
        );
        assert!(parse_request(["shell"].map(str::to_owned)).is_err());
    }

    #[test]
    fn rejects_non_absolute_mounts_and_inexact_marker() {
        let base = [
            "cleanup",
            "42",
            "98765",
            "22222222-2222-2222-2222-222222222222",
        ];
        assert!(
            parse_request(
                base.into_iter()
                    .chain([
                        "other-11111111-1111-1111-1111-111111111111",
                        "/rootfs",
                        "/rootfs/proc"
                    ])
                    .map(str::to_owned)
            )
            .is_err()
        );
        assert!(
            parse_request(
                base.into_iter()
                    .chain([
                        "wekit-chroot-run-11111111-1111-1111-1111-111111111111",
                        "/rootfs",
                        "/outside/proc"
                    ])
                    .map(str::to_owned)
            )
            .is_err()
        );
    }

    #[test]
    fn unsupported_pidfd_has_explicit_error() {
        let error = io::Error::from_raw_os_error(libc::ENOSYS);
        assert_eq!(
            unsupported_pidfd_error(&error).unwrap(),
            "pidfd_open is unsupported by this kernel; cleanup was not attempted"
        );
        assert_eq!(
            unsupported_pidfd_error(&io::Error::from_raw_os_error(libc::EPERM)),
            None
        );
    }

    #[test]
    fn validates_exact_boot_start_time_and_cmdline_identity() {
        let root =
            std::env::temp_dir().join(format!("wekit-chroot-cleanup-{}", std::process::id()));
        let process = root.join("proc/42");
        fs::create_dir_all(&process).unwrap();
        let boot_id_path = root.join("boot-id");
        let request = CleanupRequest {
            pid: 42,
            start_time: 98765,
            boot_id: "22222222-2222-2222-2222-222222222222".into(),
            marker: "wekit-chroot-run-11111111-1111-1111-1111-111111111111".into(),
            rootfs: PathBuf::from("/rootfs"),
            mount_targets: vec![PathBuf::from("/rootfs/proc")],
        };
        fs::write(&boot_id_path, format!("{}\n", request.boot_id)).unwrap();
        fs::write(
            process.join("stat"),
            format!(
                "42 (name with ) paren) S {} 98765\n",
                (4..22).map(|_| "0").collect::<Vec<_>>().join(" ")
            ),
        )
        .unwrap();
        fs::write(
            process.join("cmdline"),
            format!("/system/bin/sh\0{}\0", request.marker),
        )
        .unwrap();
        assert_eq!(validate_identity(&request, &process, &boot_id_path), Ok(()));

        fs::write(
            process.join("cmdline"),
            format!("/system/bin/sh\0{}-suffix\0", request.marker),
        )
        .unwrap();
        assert!(
            validate_identity(&request, &process, &boot_id_path)
                .unwrap_err()
                .contains("command line")
        );
        fs::write(
            process.join("cmdline"),
            format!("/system/bin/sh\0{}\0", request.marker),
        )
        .unwrap();
        fs::write(&boot_id_path, "33333333-3333-3333-3333-333333333333\n").unwrap();
        assert!(
            validate_identity(&request, &process, &boot_id_path)
                .unwrap_err()
                .contains("boot ID")
        );
        fs::remove_dir_all(root).unwrap();
    }
}
