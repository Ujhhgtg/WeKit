use anyhow::{Context, Result, anyhow, bail, ensure};

const ELF_HEADER_SIZE: usize = 64;
#[cfg(any(test, not(target_os = "android")))]
const PROGRAM_HEADER_SIZE: usize = 56;
const SYMBOL_SIZE: usize = 24;
const PT_LOAD: u32 = 1;
const PT_DYNAMIC: u32 = 2;
const PF_READ: u32 = 4;
const PF_EXECUTE: u32 = 1;
const MAX_SYMBOLS: usize = 1 << 20;
const SNAPSHOT_SYMBOLS: [&[u8]; 4] = [
    b"_kDartVmSnapshotData",
    b"_kDartVmSnapshotInstructions",
    b"_kDartIsolateSnapshotData",
    b"_kDartIsolateSnapshotInstructions",
];

#[derive(Debug)]
pub struct SnapshotImage<'a> {
    pub data: &'a [u8],
    pub instructions: &'a [u8],
    pub code_base: usize,
}

#[derive(Clone, Copy)]
#[cfg(any(test, not(target_os = "android")))]
struct ProgramHeader {
    kind: u32,
    flags: u32,
    offset: usize,
    address: usize,
    file_size: usize,
    memory_size: usize,
}

struct Segment<'a> {
    address: usize,
    flags: u32,
    bytes: &'a [u8],
}

struct Image<'a> {
    segments: Vec<Segment<'a>>,
    dynamic_address: usize,
    dynamic_size: usize,
}

#[derive(Clone, Copy)]
struct Symbol {
    address: usize,
    size: usize,
    info: u8,
    visibility: u8,
    section: u16,
}

fn region(bytes: &[u8], offset: usize, length: usize) -> Result<&[u8]> {
    let end = offset
        .checked_add(length)
        .ok_or_else(|| anyhow!("ELF range overflows"))?;
    bytes
        .get(offset..end)
        .ok_or_else(|| anyhow!("ELF range is truncated"))
}

fn unsigned32(bytes: &[u8], offset: usize) -> Result<u32> {
    Ok(u32::from_le_bytes(region(bytes, offset, 4)?.try_into()?))
}

fn unsigned64(bytes: &[u8], offset: usize) -> Result<usize> {
    usize::try_from(u64::from_le_bytes(region(bytes, offset, 8)?.try_into()?))
        .context("ELF address exceeds the platform address space")
}

fn validate_header(bytes: &[u8]) -> Result<()> {
    let header = region(bytes, 0, ELF_HEADER_SIZE)?;
    ensure!(
        header[..7] == *b"\x7fELF\x02\x01\x01",
        "expected a little-endian ELF64 image"
    );
    let kind = u16::from_le_bytes(header[16..18].try_into()?);
    ensure!(kind == 2 || kind == 3, "unsupported ELF image type");
    ensure!(
        u16::from_le_bytes(header[18..20].try_into()?) == 183,
        "expected an ARM64 ELF image"
    );
    ensure!(unsigned32(header, 20)? == 1, "unsupported ELF version");
    ensure!(
        u16::from_le_bytes(header[52..54].try_into()?) as usize == ELF_HEADER_SIZE,
        "invalid ELF header size"
    );
    Ok(())
}

#[cfg(any(test, not(target_os = "android")))]
fn program_headers(bytes: &[u8]) -> Result<Vec<ProgramHeader>> {
    validate_header(bytes)?;
    let offset = unsigned64(bytes, 32)?;
    let entry_size = u16::from_le_bytes(region(bytes, 54, 2)?.try_into()?) as usize;
    let count = u16::from_le_bytes(region(bytes, 56, 2)?.try_into()?) as usize;
    ensure!(
        entry_size == PROGRAM_HEADER_SIZE && (1..=512).contains(&count),
        "invalid ELF program header table"
    );
    let table = region(bytes, offset, count * entry_size)?;
    table
        .as_chunks::<PROGRAM_HEADER_SIZE>()
        .0
        .iter()
        .map(|entry| {
            let header = ProgramHeader {
                kind: unsigned32(entry, 0)?,
                flags: unsigned32(entry, 4)?,
                offset: unsigned64(entry, 8)?,
                address: unsigned64(entry, 16)?,
                file_size: unsigned64(entry, 32)?,
                memory_size: unsigned64(entry, 40)?,
            };
            ensure!(
                header.file_size <= header.memory_size,
                "ELF segment file size exceeds memory size"
            );
            header
                .address
                .checked_add(header.memory_size)
                .ok_or_else(|| anyhow!("ELF segment address overflows"))?;
            Ok(header)
        })
        .collect()
}

impl<'a> Image<'a> {
    fn read(&self, address: usize, length: usize, flags: u32) -> Result<&'a [u8]> {
        for segment in &self.segments {
            if segment.flags & flags != flags {
                continue;
            }
            if let Some(offset) = address.checked_sub(segment.address)
                && let Ok(bytes) = region(segment.bytes, offset, length)
            {
                return Ok(bytes);
            }
        }
        bail!("ELF address {address:#x} has no matching readable segment")
    }

    fn available(&self, address: usize) -> Result<usize> {
        self.segments
            .iter()
            .filter(|segment| segment.flags & PF_READ != 0)
            .filter_map(|segment| {
                address
                    .checked_sub(segment.address)
                    .and_then(|offset| segment.bytes.len().checked_sub(offset))
            })
            .max()
            .ok_or_else(|| anyhow!("ELF address {address:#x} is outside readable segments"))
    }

    fn symbol_count(&self, sysv_hash: usize, gnu_hash: usize) -> Result<usize> {
        if sysv_hash != 0 {
            let header = self.read(sysv_hash, 8, PF_READ)?;
            let buckets = unsigned32(header, 0)? as usize;
            let count = unsigned32(header, 4)? as usize;
            ensure!(
                buckets != 0 && (1..=MAX_SYMBOLS).contains(&count),
                "invalid ELF SysV symbol hash"
            );
            let length = buckets
                .checked_add(count)
                .and_then(|words| words.checked_mul(4))
                .and_then(|length| length.checked_add(8))
                .ok_or_else(|| anyhow!("ELF SysV hash size overflows"))?;
            self.read(sysv_hash, length, PF_READ)?;
            return Ok(count);
        }
        ensure!(gnu_hash != 0, "ELF has no supported dynamic symbol hash");
        let header = self.read(gnu_hash, 16, PF_READ)?;
        let bucket_count = unsigned32(header, 0)? as usize;
        let first_symbol = unsigned32(header, 4)? as usize;
        let bloom_size = unsigned32(header, 8)? as usize;
        ensure!(
            (1..=MAX_SYMBOLS).contains(&bucket_count)
                && first_symbol <= MAX_SYMBOLS
                && (1..=MAX_SYMBOLS).contains(&bloom_size),
            "invalid ELF GNU symbol hash"
        );
        let bucket_address = gnu_hash
            .checked_add(16 + bloom_size * 8)
            .ok_or_else(|| anyhow!("ELF GNU hash address overflows"))?;
        self.read(gnu_hash, 16 + bloom_size * 8, PF_READ)?;
        let buckets = self.read(bucket_address, bucket_count * 4, PF_READ)?;
        let mut last_bucket = 0;
        for bucket in buckets.as_chunks::<4>().0 {
            let index = unsigned32(bucket, 0)? as usize;
            ensure!(
                index == 0 || (first_symbol..MAX_SYMBOLS).contains(&index),
                "ELF GNU hash bucket is out of range"
            );
            last_bucket = last_bucket.max(index);
        }
        if last_bucket == 0 {
            return Ok(first_symbol);
        }
        let chain_address = bucket_address
            .checked_add(bucket_count * 4)
            .and_then(|address| address.checked_add((last_bucket - first_symbol) * 4))
            .ok_or_else(|| anyhow!("ELF GNU hash chain address overflows"))?;
        let chain_length = (self.available(chain_address)? / 4).min(MAX_SYMBOLS - last_bucket);
        let chain = self.read(chain_address, chain_length * 4, PF_READ)?;
        for (index, word) in chain.as_chunks::<4>().0.iter().enumerate() {
            if unsigned32(word, 0)? & 1 != 0 {
                return Ok(last_bucket + index + 1);
            }
        }
        bail!("ELF GNU hash chain is unterminated")
    }

    fn snapshot(&self, candidate: &mut bool) -> Result<Option<SnapshotImage<'a>>> {
        ensure!(
            self.dynamic_size.is_multiple_of(16),
            "invalid ELF dynamic table size"
        );
        let dynamic = self.read(self.dynamic_address, self.dynamic_size, PF_READ)?;
        let mut symbol_address = 0;
        let mut symbol_size = 0;
        let mut string_address = 0;
        let mut string_size = 0;
        let mut sysv_hash = 0;
        let mut gnu_hash = 0;
        let mut terminated = false;
        for entry in dynamic.as_chunks::<16>().0 {
            let tag = unsigned64(entry, 0)?;
            let value = unsigned64(entry, 8)?;
            match tag {
                0 => {
                    terminated = true;
                    break;
                }
                4 => sysv_hash = value,
                5 => string_address = value,
                6 => symbol_address = value,
                10 => string_size = value,
                11 => symbol_size = value,
                0x6ffffef5 => gnu_hash = value,
                _ => {}
            }
        }
        ensure!(terminated, "ELF dynamic table is unterminated");
        ensure!(
            symbol_size == SYMBOL_SIZE && symbol_address != 0 && string_address != 0,
            "invalid ELF dynamic symbol table"
        );
        let strings = self.read(string_address, string_size, PF_READ)?;
        *candidate = strings
            .split(|byte| *byte == 0)
            .any(|name| name == SNAPSHOT_SYMBOLS[2] || name == SNAPSHOT_SYMBOLS[3]);
        if !*candidate {
            return Ok(None);
        }
        let count = self.symbol_count(sysv_hash, gnu_hash)?;
        let symbols = self.read(symbol_address, count * SYMBOL_SIZE, PF_READ)?;
        let mut snapshots = [None; 4];
        for symbol in symbols.as_chunks::<SYMBOL_SIZE>().0 {
            let name_offset = unsigned32(symbol, 0)? as usize;
            let Some(name) = strings.get(name_offset..) else {
                bail!("ELF symbol name is outside its string table");
            };
            let Some(index) = SNAPSHOT_SYMBOLS.iter().position(|expected| {
                name.get(..expected.len()) == Some(*expected)
                    && name.get(expected.len()) == Some(&0)
            }) else {
                continue;
            };
            ensure!(
                snapshots[index].is_none(),
                "ELF snapshot symbol is duplicated"
            );
            snapshots[index] = Some(Symbol {
                address: unsigned64(symbol, 8)?,
                size: unsigned64(symbol, 16)?,
                info: symbol[4],
                visibility: symbol[5] & 3,
                section: u16::from_le_bytes(symbol[6..8].try_into()?),
            });
        }
        if snapshots[2].is_none() && snapshots[3].is_none() {
            return Ok(None);
        }
        let mut blobs = [&[][..]; 4];
        for (index, symbol) in snapshots.into_iter().enumerate() {
            let symbol = symbol.ok_or_else(|| {
                anyhow!(
                    "ELF snapshot symbol {} is missing",
                    String::from_utf8_lossy(SNAPSHOT_SYMBOLS[index])
                )
            })?;
            ensure!(
                symbol.info & 15 == 1
                    && matches!(symbol.info >> 4, 1 | 2)
                    && matches!(symbol.visibility, 0 | 3)
                    && symbol.section != 0
                    && symbol.size != 0,
                "ELF snapshot symbol has invalid type, visibility, or size"
            );
            let flags = if index % 2 == 1 {
                PF_READ | PF_EXECUTE
            } else {
                PF_READ
            };
            blobs[index] = self.read(symbol.address, symbol.size, flags)?;
        }
        Ok(Some(SnapshotImage {
            data: blobs[2],
            instructions: blobs[3],
            code_base: 0,
        }))
    }
}

#[cfg(any(test, not(target_os = "android")))]
pub fn from_file(bytes: &[u8]) -> Result<SnapshotImage<'_>> {
    let headers = program_headers(bytes)?;
    let dynamic = headers
        .iter()
        .find(|header| header.kind == PT_DYNAMIC)
        .ok_or_else(|| anyhow!("ELF image has no dynamic table"))?;
    let segments = headers
        .iter()
        .filter(|header| header.kind == PT_LOAD)
        .map(|header| {
            Ok(Segment {
                address: header.address,
                flags: header.flags,
                bytes: region(bytes, header.offset, header.file_size)?,
            })
        })
        .collect::<Result<Vec<_>>>()?;
    let mut candidate = false;
    Image {
        segments,
        dynamic_address: dynamic.address,
        dynamic_size: dynamic.file_size,
    }
    .snapshot(&mut candidate)?
    .ok_or_else(|| anyhow!("ELF image does not export Dart AOT snapshot symbols"))
}

pub fn loaded() -> Result<Vec<SnapshotImage<'static>>> {
    struct Search {
        images: Vec<SnapshotImage<'static>>,
        error: Option<anyhow::Error>,
    }

    unsafe extern "C" fn visit(
        information: *mut libc::dl_phdr_info,
        _size: usize,
        state: *mut libc::c_void,
    ) -> libc::c_int {
        let search = unsafe { &mut *state.cast::<Search>() };
        let information = unsafe { &*information };
        if information.dlpi_phdr.is_null() || !(1..=512).contains(&information.dlpi_phnum) {
            return 0;
        }
        let headers = unsafe {
            std::slice::from_raw_parts(information.dlpi_phdr, information.dlpi_phnum as usize)
        };
        let Some(dynamic) = headers.iter().find(|header| header.p_type == PT_DYNAMIC) else {
            return 0;
        };
        let mut segments = Vec::new();
        for header in headers {
            if header.p_type != PT_LOAD || header.p_flags & PF_READ == 0 {
                continue;
            }
            let Some(address) =
                (information.dlpi_addr as usize).checked_add(header.p_vaddr as usize)
            else {
                return 0;
            };
            let length = header.p_memsz as usize;
            if address == 0 || length > isize::MAX as usize || address.checked_add(length).is_none()
            {
                return 0;
            }
            segments.push(Segment {
                address: header.p_vaddr as usize,
                flags: header.p_flags,
                bytes: unsafe { std::slice::from_raw_parts(address as *const u8, length) },
            });
        }
        let image = Image {
            segments,
            dynamic_address: dynamic.p_vaddr as usize,
            dynamic_size: dynamic.p_filesz as usize,
        };
        let Some(header) = headers
            .iter()
            .find(|header| header.p_type == PT_LOAD && header.p_offset == 0)
        else {
            return 0;
        };
        if image
            .read(header.p_vaddr as usize, ELF_HEADER_SIZE, PF_READ)
            .and_then(validate_header)
            .is_err()
        {
            return 0;
        }
        let mut candidate = false;
        match image.snapshot(&mut candidate) {
            Ok(Some(mut snapshot)) => {
                snapshot.code_base = snapshot.instructions.as_ptr() as usize;
                search.images.push(snapshot);
            }
            Ok(None) => {}
            Err(error) if candidate => {
                search.error = Some(error.context("loaded Flutter AOT image is invalid"));
                return 1;
            }
            Err(_) => {}
        }
        0
    }

    let mut search = Search {
        images: Vec::new(),
        error: None,
    };
    unsafe {
        libc::dl_iterate_phdr(Some(visit), (&mut search as *mut Search).cast());
    }
    if let Some(error) = search.error {
        return Err(error);
    }
    Ok(search.images)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> Vec<u8> {
        let mut bytes = vec![0; 0x400];
        bytes[..7].copy_from_slice(b"\x7fELF\x02\x01\x01");
        bytes[16..18].copy_from_slice(&3u16.to_le_bytes());
        bytes[18..20].copy_from_slice(&183u16.to_le_bytes());
        bytes[20..24].copy_from_slice(&1u32.to_le_bytes());
        bytes[32..40].copy_from_slice(&64u64.to_le_bytes());
        bytes[52..54].copy_from_slice(&64u16.to_le_bytes());
        bytes[54..56].copy_from_slice(&56u16.to_le_bytes());
        bytes[56..58].copy_from_slice(&2u16.to_le_bytes());
        bytes[64..68].copy_from_slice(&PT_LOAD.to_le_bytes());
        bytes[68..72].copy_from_slice(&(PF_READ | PF_EXECUTE).to_le_bytes());
        bytes[96..104].copy_from_slice(&0x400u64.to_le_bytes());
        bytes[104..112].copy_from_slice(&0x400u64.to_le_bytes());
        bytes[120..124].copy_from_slice(&PT_DYNAMIC.to_le_bytes());
        bytes[128..136].copy_from_slice(&0x100u64.to_le_bytes());
        bytes[136..144].copy_from_slice(&0x100u64.to_le_bytes());
        bytes[152..160].copy_from_slice(&96u64.to_le_bytes());
        bytes[160..168].copy_from_slice(&96u64.to_le_bytes());
        for (index, (tag, value)) in [
            (4u64, 0x160u64),
            (5, 0x190),
            (6, 0x220),
            (10, 0x90),
            (11, 24),
            (0, 0),
        ]
        .into_iter()
        .enumerate()
        {
            let offset = 0x100 + index * 16;
            bytes[offset..offset + 8].copy_from_slice(&tag.to_le_bytes());
            bytes[offset + 8..offset + 16].copy_from_slice(&value.to_le_bytes());
        }
        bytes[0x160..0x164].copy_from_slice(&1u32.to_le_bytes());
        bytes[0x164..0x168].copy_from_slice(&5u32.to_le_bytes());
        let mut name_offset = 1;
        for (index, name) in SNAPSHOT_SYMBOLS.iter().enumerate() {
            bytes[0x190 + name_offset..0x190 + name_offset + name.len()].copy_from_slice(name);
            let symbol = 0x220 + (index + 1) * SYMBOL_SIZE;
            bytes[symbol..symbol + 4].copy_from_slice(&(name_offset as u32).to_le_bytes());
            bytes[symbol + 4] = 0x11;
            bytes[symbol + 6..symbol + 8].copy_from_slice(&1u16.to_le_bytes());
            bytes[symbol + 8..symbol + 16]
                .copy_from_slice(&(0x300u64 + index as u64 * 32).to_le_bytes());
            bytes[symbol + 16..symbol + 24].copy_from_slice(&32u64.to_le_bytes());
            name_offset += name.len() + 1;
        }
        bytes
    }

    #[test]
    fn rejects_every_truncation_before_snapshot_end() {
        let bytes = fixture();
        assert_eq!(from_file(&bytes).unwrap().instructions.len(), 32);
        for length in 0..bytes.len() {
            assert!(from_file(&bytes[..length]).is_err(), "length {length}");
        }
    }

    #[test]
    fn rejects_symbol_outside_its_segment() {
        let mut bytes = fixture();
        let symbol = 0x220 + 4 * SYMBOL_SIZE;
        bytes[symbol + 16..symbol + 24].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(from_file(&bytes).is_err());
    }

    #[test]
    fn rejects_missing_and_undefined_snapshot_symbols() {
        let mut bytes = fixture();
        let symbol = 0x220 + 3 * SYMBOL_SIZE;
        bytes[symbol + 6..symbol + 8].fill(0);
        assert!(from_file(&bytes).is_err());
        bytes[symbol..symbol + 4].fill(0);
        assert!(from_file(&bytes).is_err());
    }

    #[test]
    fn rejects_unterminated_dynamic_table_and_invalid_hash_count() {
        let mut bytes = fixture();
        bytes[0x150..0x158].copy_from_slice(&7u64.to_le_bytes());
        assert!(from_file(&bytes).is_err());
        bytes[0x150..0x158].fill(0);
        bytes[0x164..0x168].copy_from_slice(&u32::MAX.to_le_bytes());
        assert!(from_file(&bytes).is_err());
    }

    #[test]
    fn rejects_wrong_machine_and_nonexecutable_instructions() {
        let mut bytes = fixture();
        bytes[18..20].copy_from_slice(&62u16.to_le_bytes());
        assert!(from_file(&bytes).is_err());
        bytes[18..20].copy_from_slice(&183u16.to_le_bytes());
        bytes[68..72].copy_from_slice(&PF_READ.to_le_bytes());
        assert!(from_file(&bytes).is_err());
    }

    #[test]
    fn rejects_gnu_hash_bucket_outside_its_image() {
        let mut bytes = fixture();
        bytes[0x100..0x108].copy_from_slice(&0x6ffffef5u64.to_le_bytes());
        bytes[0x160..0x190].fill(0);
        bytes[0x160..0x164].copy_from_slice(&1u32.to_le_bytes());
        bytes[0x164..0x168].copy_from_slice(&1u32.to_le_bytes());
        bytes[0x168..0x16c].copy_from_slice(&1u32.to_le_bytes());
        bytes[0x178..0x17c].copy_from_slice(&1u32.to_le_bytes());
        for offset in [0x17c, 0x180, 0x184] {
            bytes[offset..offset + 4].copy_from_slice(&2u32.to_le_bytes());
        }
        bytes[0x188..0x18c].copy_from_slice(&3u32.to_le_bytes());
        assert_eq!(from_file(&bytes).unwrap().instructions.len(), 32);
        bytes[0x178..0x17c].copy_from_slice(&((MAX_SYMBOLS - 1) as u32).to_le_bytes());
        assert!(from_file(&bytes).is_err());
    }
}
