use std::collections::BTreeSet;
use std::ffi::CString;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};

const PROC_ROOT: &str = "/proc";
const BOOT_ID_PATH: &str = "/proc/sys/kernel/random/boot_id";
const FREEZE_SCAN_LIMIT: usize = 32;
const MEMBER_WAIT: Duration = Duration::from_millis(2_500);
const SCAN_PAUSE: Duration = Duration::from_millis(25);

#[derive(Debug, PartialEq)]
pub struct CleanupRequest {
    pub pid: i32,
    pub start_time: u64,
    pub boot_id: String,
    pub marker: String,
    pub mount_namespace: u64,
    pub rootfs: PathBuf,
    pub mount_targets: Vec<PathBuf>,
}

pub fn parse_request(args: impl IntoIterator<Item = String>) -> Result<CleanupRequest, String> {
    let mut args = args.into_iter();
    if args.next().as_deref() != Some("cleanup") {
        return Err(
            "usage: chroot_cleanup cleanup PID START_TIME BOOT_ID MARKER MNT_NS ROOTFS MOUNT..."
                .into(),
        );
    }
    let pid = parse_positive_i32(args.next(), "PID")?;
    let start_time = parse_u64(args.next(), "start time")?;
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
    let mount_namespace = parse_u64(args.next(), "mount namespace identity")?;
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
        mount_namespace,
        rootfs,
        mount_targets,
    })
}

fn parse_positive_i32(value: Option<String>, name: &str) -> Result<i32, String> {
    let value = value.ok_or_else(|| format!("missing {name}"))?;
    let parsed = value
        .parse::<i32>()
        .map_err(|_| format!("invalid {name}"))?;
    (parsed > 0)
        .then_some(parsed)
        .ok_or_else(|| format!("invalid {name}"))
}

fn parse_u64(value: Option<String>, name: &str) -> Result<u64, String> {
    let value = value.ok_or_else(|| format!("missing {name}"))?;
    let parsed = value
        .parse::<u64>()
        .map_err(|_| format!("invalid {name}"))?;
    (parsed > 0)
        .then_some(parsed)
        .ok_or_else(|| format!("invalid {name}"))
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
    is_normal_absolute(path)
        && path
            .strip_prefix(rootfs)
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
    validate_boot_id(request, boot_id_path)?;
    let self_pid = std::process::id() as i32;
    verify_pidfd_support(self_pid)?;
    let self_namespace = namespace_inode(&proc_root.join("self/ns/mnt"))
        .map_err(|error| format!("cannot verify cleanup helper mount namespace: {error}"))?;
    if self_namespace == request.mount_namespace {
        return Err("cleanup helper is inside the target mount namespace".into());
    }

    let mut target_namespace = None;
    let mut stability = FreezeStability::new(FREEZE_SCAN_LIMIT);
    loop {
        let members = acquire_members(proc_root, request.mount_namespace, self_pid)?;
        validate_recorded_leader(request, proc_root, &members)?;
        preserve_namespace(&members, &mut target_namespace)?;
        signal_members(&members, libc::SIGSTOP)?;
        let rescanned = acquire_members(proc_root, request.mount_namespace, self_pid)?;
        preserve_namespace(&rescanned, &mut target_namespace)?;
        let stopped = members_stopped(proc_root, &rescanned)?;
        let before = members.iter().map(NamespaceMember::identity).collect();
        let after = rescanned.iter().map(NamespaceMember::identity).collect();
        if stability.observe(before, after, stopped)? {
            break;
        }
        thread::sleep(SCAN_PAUSE);
    }

    terminate_members(
        proc_root,
        request.mount_namespace,
        self_pid,
        libc::SIGTERM,
        true,
    )?;
    if !wait_for_empty(proc_root, request.mount_namespace, self_pid, MEMBER_WAIT)? {
        terminate_members(
            proc_root,
            request.mount_namespace,
            self_pid,
            libc::SIGKILL,
            false,
        )?;
        if !wait_for_empty(proc_root, request.mount_namespace, self_pid, MEMBER_WAIT)? {
            return Err("processes remain in mount namespace after SIGKILL".into());
        }
    }

    if let Some(namespace) = target_namespace {
        unmount_in_namespace(&namespace, &request.mount_targets, proc_root)?;
    }
    if !acquire_members(proc_root, request.mount_namespace, self_pid)?.is_empty() {
        return Err("process appeared in mount namespace after cleanup".into());
    }
    Ok(())
}

fn verify_pidfd_support(self_pid: i32) -> Result<(), String> {
    let pidfd = pidfd_open(self_pid).map_err(|error| {
        unsupported_pidfd_error(&error)
            .unwrap_or_else(|| format!("pidfd_open capability check failed: {error}"))
    })?;
    pidfd_send_signal(pidfd.as_raw_fd(), 0)
}

fn validate_boot_id(request: &CleanupRequest, boot_id_path: &Path) -> Result<(), String> {
    let boot_id = fs::read_to_string(boot_id_path)
        .map_err(|error| format!("cannot read boot ID: {error}"))?;
    if boot_id.trim() != request.boot_id {
        Err("process identity mismatch: boot ID".into())
    } else {
        Ok(())
    }
}

struct NamespaceMember {
    tgid: i32,
    tid: i32,
    pidfd: OwnedFd,
    namespace: OwnedFd,
}

impl NamespaceMember {
    fn identity(&self) -> TaskIdentity {
        TaskIdentity {
            tgid: self.tgid,
            tid: self.tid,
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, Ord, PartialEq, PartialOrd)]
struct TaskIdentity {
    tgid: i32,
    tid: i32,
}

fn namespace_member_tasks(
    proc_root: &Path,
    expected: u64,
    self_pid: i32,
) -> Result<BTreeSet<TaskIdentity>, String> {
    let mut members = BTreeSet::new();
    let entries = fs::read_dir(proc_root).map_err(|error| format!("cannot scan /proc: {error}"))?;
    for entry in entries {
        let entry = entry.map_err(|error| format!("cannot scan /proc entry: {error}"))?;
        let Some(pid) = entry
            .file_name()
            .to_str()
            .and_then(|name| name.parse::<i32>().ok())
        else {
            continue;
        };
        if pid <= 0 || pid == self_pid {
            continue;
        }
        let task_root = entry.path().join("task");
        let tasks = match fs::read_dir(&task_root) {
            Ok(tasks) => tasks,
            Err(error) if process_disappeared(&error) => continue,
            Err(error) => return Err(format!("cannot scan tasks for process {pid}: {error}")),
        };
        for task in tasks {
            let task = task.map_err(|error| format!("cannot scan task for process {pid}: {error}"))?;
            let Some(tid) = task.file_name().to_str().and_then(|name| name.parse::<i32>().ok()) else {
                continue;
            };
            match namespace_inode(&task.path().join("ns/mnt")) {
                Ok(identity) if identity == expected => {
                    members.insert(TaskIdentity { tgid: pid, tid });
                }
                Ok(_) => {}
                Err(error) if process_disappeared(&error) => {}
                Err(error) => {
                    return Err(format!(
                        "cannot verify mount namespace for task {pid}/{tid}: {error}"
                    ));
                }
            }
        }
    }
    Ok(members)
}

fn acquire_members(
    proc_root: &Path,
    expected: u64,
    self_pid: i32,
) -> Result<Vec<NamespaceMember>, String> {
    let mut members = Vec::new();
    for task in namespace_member_tasks(proc_root, expected, self_pid)? {
        if let Some(member) = bind_member(proc_root, task, expected)? {
            members.push(member);
        }
    }
    Ok(members)
}

fn bind_member(
    proc_root: &Path,
    task: TaskIdentity,
    expected: u64,
) -> Result<Option<NamespaceMember>, String> {
    let pidfd = match pidfd_open(task.tgid) {
        Ok(fd) => fd,
        Err(error) if error.raw_os_error() == Some(libc::ESRCH) => return Ok(None),
        Err(error) if unsupported_pidfd_error(&error).is_some() => {
            return Err(unsupported_pidfd_error(&error).unwrap());
        }
        Err(error) => return Err(format!("pidfd_open failed for process {}: {error}", task.tgid)),
    };
    if wait_pidfd(pidfd.as_raw_fd(), Duration::ZERO)? {
        return Ok(None);
    }
    let path = proc_root
        .join(task.tgid.to_string())
        .join("task")
        .join(task.tid.to_string())
        .join("ns/mnt");
    let namespace = match open_read_only(&path) {
        Ok(fd) => fd,
        Err(error)
            if process_disappeared(&error) && wait_pidfd(pidfd.as_raw_fd(), Duration::ZERO)? =>
        {
            return Ok(None);
        }
        Err(error) => {
            return Err(format!(
                "cannot bind mount namespace for task {}/{}: {error}", task.tgid, task.tid
            ));
        }
    };
    let actual = namespace_inode_fd(namespace.as_raw_fd())
        .map_err(|error| format!("cannot verify bound mount namespace for task {}/{}: {error}", task.tgid, task.tid))?;
    if wait_pidfd(pidfd.as_raw_fd(), Duration::ZERO)? {
        return Ok(None);
    }
    if actual != expected {
        return Ok(None);
    }
    Ok(Some(NamespaceMember {
        tgid: task.tgid,
        tid: task.tid,
        pidfd,
        namespace,
    }))
}

fn validate_recorded_leader(
    request: &CleanupRequest,
    proc_root: &Path,
    members: &[NamespaceMember],
) -> Result<(), String> {
    if members.iter().all(|member| member.tgid != request.pid) {
        return Ok(());
    }
    let process = proc_root.join(request.pid.to_string());
    let stat = fs::read_to_string(process.join("stat"))
        .map_err(|error| format!("cannot read leader process stat: {error}"))?;
    let fields = stat
        .rsplit_once(") ")
        .ok_or("invalid leader process stat")?
        .1
        .split_whitespace()
        .collect::<Vec<_>>();
    let start_time = fields
        .get(19)
        .ok_or("leader process stat has no start time")?
        .parse::<u64>()
        .map_err(|_| "invalid leader process start time")?;
    if start_time != request.start_time {
        return Err("process identity mismatch: start time".into());
    }
    let cmdline = fs::read(process.join("cmdline"))
        .map_err(|error| format!("cannot read leader process cmdline: {error}"))?;
    if !cmdline
        .split(|byte| *byte == 0)
        .any(|arg| arg == request.marker.as_bytes())
    {
        return Err("process identity mismatch: command line".into());
    }
    Ok(())
}

fn preserve_namespace(
    members: &[NamespaceMember],
    saved: &mut Option<OwnedFd>,
) -> Result<(), String> {
    if saved.is_none() {
        if let Some(member) = members.first() {
            *saved = Some(
                member
                    .namespace
                    .try_clone()
                    .map_err(|error| format!("cannot preserve mount namespace: {error}"))?,
            );
        }
    }
    Ok(())
}

fn signal_members(members: &[NamespaceMember], signal: i32) -> Result<(), String> {
    let mut signalled = BTreeSet::new();
    for member in members {
        if signalled.insert(member.tgid) {
            pidfd_send_signal(member.pidfd.as_raw_fd(), signal)?;
        }
    }
    Ok(())
}

fn members_stopped(proc_root: &Path, members: &[NamespaceMember]) -> Result<bool, String> {
    for member in members {
        let stat_path = proc_root
            .join(member.tgid.to_string())
            .join("task")
            .join(member.tid.to_string())
            .join("stat");
        let stat = match fs::read_to_string(stat_path) {
            Ok(stat) => stat,
            Err(error)
                if process_disappeared(&error)
                    && wait_pidfd(member.pidfd.as_raw_fd(), Duration::ZERO)? =>
            {
                return Ok(false);
            }
            Err(error) => {
                return Err(format!(
                    "cannot verify stopped state for task {}/{}: {error}",
                    member.tgid, member.tid
                ));
            }
        };
        let state = stat
            .rsplit_once(") ")
            .and_then(|(_, fields)| fields.as_bytes().first().copied())
            .ok_or_else(|| format!("invalid process stat for task {}/{}", member.tgid, member.tid))?;
        if !matches!(state, b'T' | b't') {
            return Ok(false);
        }
        if wait_pidfd(member.pidfd.as_raw_fd(), Duration::ZERO)? {
            return Ok(false);
        }
    }
    Ok(true)
}

fn terminate_members(
    proc_root: &Path,
    expected: u64,
    self_pid: i32,
    signal: i32,
    resume: bool,
) -> Result<(), String> {
    let members = acquire_members(proc_root, expected, self_pid)?;
    signal_members(&members, signal)?;
    if resume {
        signal_members(&members, libc::SIGCONT)?;
    }
    Ok(())
}

fn wait_for_empty(
    proc_root: &Path,
    expected: u64,
    self_pid: i32,
    timeout: Duration,
) -> Result<bool, String> {
    let deadline = Instant::now() + timeout;
    loop {
        let members = acquire_members(proc_root, expected, self_pid)?;
        if members.is_empty() {
            return Ok(true);
        }
        if Instant::now() >= deadline {
            return Ok(false);
        }
        thread::sleep(SCAN_PAUSE);
    }
}

struct FreezeStability {
    scans: usize,
    limit: usize,
}

impl FreezeStability {
    fn new(limit: usize) -> Self {
        Self {
            scans: 0,
            limit,
        }
    }

    fn observe(
        &mut self,
        before_stop: BTreeSet<TaskIdentity>,
        after_stop: BTreeSet<TaskIdentity>,
        stopped: bool,
    ) -> Result<bool, String> {
        self.scans += 1;
        if self.scans > self.limit {
            return Err("mount namespace membership did not stabilize while freezing".into());
        }
        Ok(stopped && before_stop == after_stop)
    }
}

fn unmount_in_namespace(
    namespace: &OwnedFd,
    mount_targets: &[PathBuf],
    proc_root: &Path,
) -> Result<(), String> {
    let original = open_read_only(&proc_root.join("self/ns/mnt"))
        .map_err(|error| format!("cannot preserve cleanup helper namespace: {error}"))?;
    setns(namespace.as_raw_fd()).map_err(|error| format!("setns failed: {error}"))?;
    let cleanup_result = mount_targets.iter().try_for_each(|target| {
        let target = CString::new(target.as_os_str().as_encoded_bytes())
            .map_err(|_| "mount target contains NUL".to_owned())?;
        if unsafe { libc::umount2(target.as_ptr(), libc::MNT_DETACH) } == 0 {
            Ok(())
        } else {
            let error = io::Error::last_os_error();
            if matches!(
                error.raw_os_error(),
                Some(libc::EINVAL) | Some(libc::ENOENT)
            ) {
                Ok(())
            } else {
                Err(format!("mount cleanup failed: {error}"))
            }
        }
    });
    let restore_result = setns(original.as_raw_fd())
        .map_err(|error| format!("cannot restore cleanup helper namespace: {error}"));
    restore_result?;
    cleanup_result
}

fn setns(namespace: i32) -> io::Result<()> {
    if unsafe { libc::setns(namespace, libc::CLONE_NEWNS) } < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn namespace_inode(path: &Path) -> io::Result<u64> {
    fs::metadata(path).map(|metadata| metadata.ino())
}

fn namespace_inode_fd(fd: i32) -> io::Result<u64> {
    let mut stat = std::mem::MaybeUninit::<libc::stat>::uninit();
    if unsafe { libc::fstat(fd, stat.as_mut_ptr()) } < 0 {
        Err(io::Error::last_os_error())
    } else {
        Ok(unsafe { stat.assume_init() }.st_ino)
    }
}

fn process_disappeared(error: &io::Error) -> bool {
    matches!(error.raw_os_error(), Some(libc::ENOENT) | Some(libc::ESRCH))
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

    fn request_args() -> Vec<String> {
        [
            "cleanup",
            "42",
            "98765",
            "22222222-2222-2222-2222-222222222222",
            "wekit-chroot-run-11111111-1111-1111-1111-111111111111",
            "4026533001",
            "/rootfs",
            "/rootfs/dev",
            "/rootfs/proc",
        ]
        .map(str::to_owned)
        .to_vec()
    }

    #[test]
    fn parses_only_cleanup_operation_and_exact_namespace_identity() {
        let request = parse_request(request_args()).unwrap();
        assert_eq!(request.pid, 42);
        assert_eq!(request.start_time, 98765);
        assert_eq!(request.mount_namespace, 4026533001);
        assert_eq!(
            request.mount_targets,
            [PathBuf::from("/rootfs/dev"), PathBuf::from("/rootfs/proc")]
        );
        assert!(parse_request(["shell"].map(str::to_owned)).is_err());
        let mut invalid = request_args();
        invalid[5] = "mnt:[4026533001]".into();
        assert!(
            parse_request(invalid)
                .unwrap_err()
                .contains("mount namespace")
        );
    }

    #[test]
    fn rejects_non_absolute_mounts_and_inexact_marker() {
        let mut invalid = request_args();
        invalid[4] = "other-11111111-1111-1111-1111-111111111111".into();
        assert!(parse_request(invalid).is_err());
        let mut invalid = request_args();
        invalid[7] = "/outside/proc".into();
        assert!(parse_request(invalid).is_err());
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
    fn selects_thread_members_with_distinct_mount_namespaces_and_excludes_self() {
        let root = temp_root("selection");
        fs::create_dir_all(&root).unwrap();
        let expected = root.join("expected");
        let other = root.join("other");
        fs::write(&expected, "expected").unwrap();
        fs::write(&other, "other").unwrap();
        for (tgid, tid, namespace) in [
            (41, 41, &other),
            (41, 45, &expected),
            (42, 42, &expected),
            (43, 43, &other),
        ] {
            let ns = root
                .join(tgid.to_string())
                .join("task")
                .join(tid.to_string())
                .join("ns");
            fs::create_dir_all(&ns).unwrap();
            fs::hard_link(namespace, ns.join("mnt")).unwrap();
        }
        let identity = namespace_inode(&expected).unwrap();
        assert_eq!(
            namespace_member_tasks(&root, identity, 42).unwrap(),
            BTreeSet::from([TaskIdentity { tgid: 41, tid: 45 }])
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn freeze_requires_post_stop_rescan_without_new_or_running_members() {
        let first = TaskIdentity { tgid: 1, tid: 1 };
        let second = TaskIdentity { tgid: 2, tid: 2 };
        let mut stability = FreezeStability::new(3);
        assert!(!stability
            .observe(BTreeSet::from([first]), BTreeSet::from([first, second]), false)
            .unwrap());
        assert!(stability
            .observe(BTreeSet::from([first, second]), BTreeSet::from([first, second]), true)
            .unwrap());

        let mut changing = FreezeStability::new(1);
        assert!(!changing
            .observe(BTreeSet::from([first]), BTreeSet::from([first, second]), true)
            .unwrap());
        assert!(changing
            .observe(BTreeSet::from([first, second]), BTreeSet::from([first, second]), true)
            .is_err());
    }

    #[test]
    fn validates_exact_boot_start_time_and_cmdline_identity() {
        let root = temp_root("identity");
        let process = root.join("proc/42");
        fs::create_dir_all(&process).unwrap();
        let boot_id_path = root.join("boot-id");
        let request = parse_request(request_args()).unwrap();
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
        validate_boot_id(&request, &boot_id_path).unwrap();
        let dummy = open_read_only(&process.join("stat")).unwrap();
        let members = [NamespaceMember {
            tgid: 42,
            tid: 42,
            pidfd: dummy.try_clone().unwrap(),
            namespace: dummy,
        }];
        assert_eq!(
            validate_recorded_leader(&request, &root.join("proc"), &members),
            Ok(())
        );
        fs::write(
            process.join("cmdline"),
            format!("/system/bin/sh\0{}-suffix\0", request.marker),
        )
        .unwrap();
        assert!(
            validate_recorded_leader(&request, &root.join("proc"), &members)
                .unwrap_err()
                .contains("command line")
        );
        fs::remove_dir_all(root).unwrap();
    }

    fn temp_root(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "wekit-chroot-cleanup-{label}-{}",
            std::process::id()
        ))
    }
}
