//! Bounded parsers for borrowed file descriptors. No image/video payload crosses JNI.
mod bridge;
pub mod jpeg;
pub mod mp4;

use std::cell::RefCell;

pub type Result<T> = std::result::Result<T, String>;

struct Cache {
    start: u64,
    bytes: Vec<u8>,
}

pub struct Reader {
    fd: i32,
    len: u64,
    cache_size: usize,
    cache: RefCell<Cache>,
}

impl Reader {
    /// The caller owns the descriptor and keeps it open for this synchronous operation.
    pub fn new(fd: i32, len: u64) -> Self {
        Self::with_cache_size(fd, len, 65_536)
    }

    /// Classification reads only JPEG headers, so avoid fetching a full scan buffer.
    pub fn with_header_cache(fd: i32, len: u64) -> Self {
        Self::with_cache_size(fd, len, 4096)
    }

    fn with_cache_size(fd: i32, len: u64, cache_size: usize) -> Self {
        Self {
            fd,
            len,
            cache_size,
            cache: RefCell::new(Cache {
                start: 0,
                bytes: Vec::new(),
            }),
        }
    }

    pub fn len(&self) -> u64 {
        self.len
    }

    pub fn read(&self, offset: u64, length: usize) -> Result<Vec<u8>> {
        if offset
            .checked_add(length as u64)
            .is_none_or(|end| end > self.len || end > i64::MAX as u64)
        {
            return Err("Truncated file".into());
        }
        let mut bytes = vec![0; length];
        self.read_into(offset, &mut bytes)?;
        Ok(bytes)
    }

    fn read_into(&self, offset: u64, bytes: &mut [u8]) -> Result<()> {
        let end = offset
            .checked_add(bytes.len() as u64)
            .ok_or("File offset overflow")?;
        if end > self.len || end > i64::MAX as u64 {
            return Err("Truncated file".into());
        }
        if bytes.is_empty() {
            return Ok(());
        }
        if bytes.len() > self.cache_size {
            return self.pread(offset, bytes);
        }
        let mut cache = self.cache.borrow_mut();
        if offset < cache.start || end > cache.start + cache.bytes.len() as u64 {
            cache.start = offset;
            cache
                .bytes
                .resize((self.len - offset).min(self.cache_size as u64) as usize, 0);
            self.pread(offset, &mut cache.bytes)?;
        }
        let start = (offset - cache.start) as usize;
        bytes.copy_from_slice(&cache.bytes[start..start + bytes.len()]);
        Ok(())
    }

    fn pread(&self, offset: u64, bytes: &mut [u8]) -> Result<()> {
        let mut done = 0;
        while done < bytes.len() {
            // pread leaves the Java FileChannel's position unchanged and never owns its fd.
            let count = unsafe {
                libc::pread(
                    self.fd,
                    bytes[done..].as_mut_ptr().cast(),
                    bytes.len() - done,
                    (offset + done as u64) as libc::off_t,
                )
            };
            if count < 0 {
                let error = std::io::Error::last_os_error();
                if error.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(format!("Cannot read photo: {error}"));
            }
            if count == 0 {
                return Err("Truncated file".into());
            }
            done += count as usize;
        }
        Ok(())
    }

    pub fn byte(&self, offset: u64) -> Result<u8> {
        let mut bytes = [0];
        self.read_into(offset, &mut bytes)?;
        Ok(bytes[0])
    }
    pub fn u16(&self, offset: u64) -> Result<u16> {
        let mut bytes = [0; 2];
        self.read_into(offset, &mut bytes)?;
        Ok(u16::from_be_bytes(bytes))
    }
    pub fn u32(&self, offset: u64) -> Result<u32> {
        let mut bytes = [0; 4];
        self.read_into(offset, &mut bytes)?;
        Ok(u32::from_be_bytes(bytes))
    }
    pub fn u64(&self, offset: u64) -> Result<u64> {
        let mut bytes = [0; 8];
        self.read_into(offset, &mut bytes)?;
        Ok(u64::from_be_bytes(bytes))
    }
}

#[cfg(test)]
pub fn fixture_reader(bytes: &[u8]) -> (std::fs::File, Reader) {
    use std::io::{Seek, Write};
    use std::os::fd::AsRawFd;
    let path = std::env::temp_dir().join(format!(
        "wekit-parser-{}-{}",
        std::process::id(),
        std::sync::atomic::AtomicU64::fetch_add(
            &FIXTURE_ID,
            1,
            std::sync::atomic::Ordering::Relaxed
        )
    ));
    let mut file = std::fs::OpenOptions::new()
        .create_new(true)
        .read(true)
        .write(true)
        .open(&path)
        .unwrap();
    std::fs::remove_file(path).unwrap();
    file.write_all(bytes).unwrap();
    file.rewind().unwrap();
    let reader = Reader::new(file.as_raw_fd(), bytes.len() as u64);
    (file, reader)
}

#[cfg(test)]
static FIXTURE_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
