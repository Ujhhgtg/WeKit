use std::collections::HashMap;
use std::ops::Range;

use anyhow::{Context, Result, anyhow, bail, ensure};
use memchr::{memchr, memmem};

mod metadata;

pub use metadata::{ClassInfo, Metadata, parse_metadata};

#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
pub enum Role {
    Bg0,
    Bg1,
    Bg2,
    Bg3,
    Bg4,
    Bg5,
    Fg0,
    Fg0Half,
    Fg1,
    Fg2,
    Fg3,
    Fg4,
    Brand,
}

impl Role {
    pub fn name(self) -> &'static str {
        match self {
            Self::Bg0 => "BG_0",
            Self::Bg1 => "BG_1",
            Self::Bg2 => "BG_2",
            Self::Bg3 => "BG_3",
            Self::Bg4 => "BG_4",
            Self::Bg5 => "BG_5",
            Self::Fg0 => "FG_0",
            Self::Fg0Half => "FG_0_5",
            Self::Fg1 => "FG_1",
            Self::Fg2 => "FG_2",
            Self::Fg3 => "FG_3",
            Self::Fg4 => "FG_4",
            Self::Brand => "Brand",
        }
    }

    fn from_bytes(value: &[u8]) -> Option<Self> {
        Some(match value {
            b"BG_0" => Self::Bg0,
            b"BG_1" => Self::Bg1,
            b"BG_2" => Self::Bg2,
            b"BG_3" => Self::Bg3,
            b"BG_4" => Self::Bg4,
            b"BG_5" => Self::Bg5,
            b"FG_0" => Self::Fg0,
            b"FG_0_5" => Self::Fg0Half,
            b"FG_1" => Self::Fg1,
            b"FG_2" => Self::Fg2,
            b"FG_3" => Self::Fg3,
            b"FG_4" => Self::Fg4,
            b"Brand" => Self::Brand,
            _ => return None,
        })
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum PoolEntry {
    Tagged(u32),
    Immediate(i64),
    Native,
    Reset,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RolePoolEntry {
    pub role: Role,
    pub index: usize,
}

impl RolePoolEntry {
    pub fn pp_offset(self) -> usize {
        16 + self.index * 8
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct InstructionEntry {
    pub pc_offset: usize,
    pub stack_map_offset: u32,
}

#[derive(Debug)]
pub struct InstructionTable {
    pub data_offset: usize,
    pub canonical_stack_map_offset: u32,
    pub entries: Vec<InstructionEntry>,
}

#[derive(Debug)]
pub struct SnapshotInfo {
    pub object_count: u32,
    pub pool: Vec<PoolEntry>,
    pub roles: Vec<RolePoolEntry>,
    pub ranges: Vec<Range<usize>>,
    pub instruction_table: InstructionTable,
}

struct Reader<'a> {
    data: &'a [u8],
    position: usize,
}

impl<'a> Reader<'a> {
    fn new(data: &'a [u8], position: usize) -> Self {
        Self { data, position }
    }

    fn bytes(&mut self, length: usize) -> Result<&'a [u8]> {
        let end = self
            .position
            .checked_add(length)
            .ok_or_else(|| anyhow!("snapshot read overflows"))?;
        let value = self
            .data
            .get(self.position..end)
            .ok_or_else(|| anyhow!("snapshot is truncated"))?;
        self.position = end;
        Ok(value)
    }

    fn byte(&mut self) -> Result<u8> {
        Ok(self.bytes(1)?[0])
    }

    fn unsigned(&mut self) -> Result<u64> {
        let mut value = 0_u128;
        for group in 0..10 {
            let current = self.byte()?;
            if current >= 128 {
                let last = current - 128;
                ensure!(group == 0 || last != 0, "noncanonical unsigned integer");
                value |= u128::from(last) << (group * 7);
                return u64::try_from(value).context("unsigned integer overflows");
            }
            value |= u128::from(current) << (group * 7);
        }
        bail!("unterminated unsigned integer")
    }

    fn signed(&mut self, width: u32) -> Result<i64> {
        let mut value = 0_i128;
        for group in 0..10 {
            let current = self.byte()?;
            if current >= 128 {
                value |= (i128::from(current) - 192) << (group * 7);
                let value = i64::try_from(value).context("signed integer overflows")?;
                ensure!(
                    width == 64 || i32::try_from(value).is_ok(),
                    "signed integer exceeds its field width"
                );
                let mut remainder = value;
                let mut groups = 1;
                while !(-64..64).contains(&remainder) {
                    remainder >>= 7;
                    groups += 1;
                }
                ensure!(groups == group + 1, "noncanonical signed integer");
                return Ok(value);
            }
            value |= i128::from(current) << (group * 7);
        }
        bail!("unterminated signed integer")
    }

    fn reference(&mut self) -> Result<u32> {
        let start = self.position;
        let mut value = 0_i64;
        for group in 0..5 {
            let current = self.byte()?;
            value = value * 128 + i64::from(current as i8);
            if current >= 128 {
                ensure!(
                    group == 0 || self.data[start] != 0,
                    "noncanonical object reference"
                );
                return u32::try_from(value + 128).context("object reference overflows");
            }
        }
        bail!("unterminated object reference")
    }

    fn count(&mut self) -> Result<usize> {
        usize::try_from(self.unsigned()?).context("snapshot count overflows")
    }
}

struct ScanBudget {
    remaining: usize,
}

impl ScanBudget {
    fn charge(&mut self, amount: usize) -> Result<()> {
        self.remaining = self
            .remaining
            .checked_sub(amount)
            .ok_or_else(|| anyhow!("snapshot candidate scan exceeds its work bound"))?;
        Ok(())
    }
}

fn unsigned_bytes(mut value: u64) -> Vec<u8> {
    let mut result = Vec::new();
    while value >= 128 {
        result.push((value & 127) as u8);
        value >>= 7;
    }
    result.push(value as u8 + 128);
    result
}

fn string_bytes(encoded: u32) -> Result<usize> {
    let length = (encoded >> 1) as usize;
    length
        .checked_mul(if encoded & 1 == 0 { 1 } else { 2 })
        .ok_or_else(|| anyhow!("snapshot string length overflows"))
}

fn match_strings(reader: &mut Reader<'_>, lengths: &[u32]) -> Result<()> {
    for &expected in lengths {
        ensure!(
            reader.unsigned()? == u64::from(expected),
            "snapshot string lengths differ"
        );
        reader.bytes(string_bytes(expected)?)?;
    }
    Ok(())
}

fn locate_strings(
    data: &[u8],
    allocation_end: usize,
    lengths: &[u32],
    budget: &mut ScanBudget,
) -> Result<Range<usize>> {
    let first_length = unsigned_bytes(u64::from(lengths[0]));
    let mut found = None;
    let prefix_length = lengths.len().min(8);
    for relative in memmem::find_iter(&data[allocation_end..], &first_length) {
        let start = allocation_end + relative;
        let mut reader = Reader::new(data, start);
        let prefix = match_strings(&mut reader, &lengths[..prefix_length]);
        budget.charge(reader.position - start + 1)?;
        if prefix.is_err() {
            continue;
        }
        let remainder_start = reader.position;
        let remainder = match_strings(&mut reader, &lengths[prefix_length..]);
        budget.charge(reader.position - remainder_start + 1)?;
        if remainder.is_err() {
            continue;
        }
        ensure!(found.is_none(), "snapshot string fill is ambiguous");
        found = Some(start..reader.position);
    }
    found.ok_or_else(|| anyhow!("snapshot string fill was not found"))
}

fn decode_pool(
    reader: &mut Reader<'_>,
    length: usize,
    object_count: u32,
    output: Option<&mut Vec<PoolEntry>>,
) -> Result<()> {
    ensure!(reader.count()? == length, "snapshot pool lengths differ");
    ensure!(
        length <= reader.data.len() - reader.position,
        "snapshot pool exceeds its data span"
    );
    let mut output = output;
    for _ in 0..length {
        let bits = reader.byte()?;
        let behavior = bits >> 5;
        let entry_type = bits & 15;
        let entry = match behavior {
            0 => match entry_type {
                0 => PoolEntry::Immediate(reader.signed(64)?),
                1 => {
                    let reference = reader.reference()?;
                    ensure!(
                        (1..=object_count).contains(&reference),
                        "snapshot pool reference is outside its object range"
                    );
                    PoolEntry::Tagged(reference)
                }
                2 => PoolEntry::Native,
                _ => bail!("unsupported snapshot pool entry type"),
            },
            1..=4 => PoolEntry::Reset,
            _ => bail!("unsupported snapshot pool behavior"),
        };
        if let Some(entries) = output.as_deref_mut() {
            entries.push(entry);
        }
    }
    Ok(())
}

fn locate_pool(
    data: &[u8],
    allocation_start: usize,
    strings: &Range<usize>,
    object_count: u32,
    budget: &mut ScanBudget,
) -> Result<Vec<PoolEntry>> {
    let mut found = None;
    for relative in memmem::find_iter(&data[allocation_start..strings.start], b"\x00\x60\xc5\x81") {
        let candidate = allocation_start + relative;
        if candidate > allocation_start && data[candidate - 1] & 128 == 0 {
            continue;
        }
        let allocation = candidate + 4;
        let mut allocation_reader = Reader::new(&data[..strings.start], allocation);
        let Ok(length) = allocation_reader.count() else {
            continue;
        };
        if length == 0 || length > data.len() {
            continue;
        }
        let encoded_length = unsigned_bytes(length as u64);
        budget.charge(data.len() - strings.end)?;
        for relative in memmem::find_iter(&data[strings.end..], &encoded_length) {
            let start = strings.end + relative;
            let mut reader = Reader::new(data, start);
            let decoded = decode_pool(&mut reader, length, object_count, None);
            budget.charge(reader.position - start + 1)?;
            if decoded.is_err() {
                continue;
            }
            ensure!(found.is_none(), "snapshot object pool is ambiguous");
            found = Some((start, length));
        }
    }
    let (start, length) = found.ok_or_else(|| anyhow!("snapshot object pool was not found"))?;
    let mut pool = Vec::with_capacity(length);
    decode_pool(
        &mut Reader::new(data, start),
        length,
        object_count,
        Some(&mut pool),
    )?;
    Ok(pool)
}

fn read_u32(data: &[u8], offset: usize) -> Result<u32> {
    let bytes = Reader::new(data, offset).bytes(4)?;
    Ok(u32::from_le_bytes(bytes.try_into().unwrap()))
}

fn instructions(
    data: &[u8],
    snapshot_size: usize,
    table_offset: usize,
    retained_code_count: usize,
    instructions_len: usize,
) -> Result<(InstructionTable, Vec<Range<usize>>)> {
    let image_start = snapshot_size
        .checked_add(63)
        .map(|value| value & !63)
        .ok_or_else(|| anyhow!("snapshot image alignment overflows"))?;
    let data_offset = image_start
        .checked_add(table_offset)
        .and_then(|value| value.checked_add(16))
        .ok_or_else(|| anyhow!("snapshot instruction table offset overflows"))?;
    let mut reader = Reader::new(data, data_offset);
    let header = reader.bytes(16)?;
    let canonical_stack_map_offset = read_u32(header, 0)?;
    let length = read_u32(header, 4)? as usize;
    let first_code = read_u32(header, 8)? as usize;
    ensure!(
        length > 0 && length.checked_sub(first_code) == Some(retained_code_count),
        "snapshot instruction table dimensions differ"
    );
    let bytes = length
        .checked_mul(8)
        .ok_or_else(|| anyhow!("snapshot instruction table size overflows"))?;
    let table_bytes = reader.bytes(bytes)?;
    let remaining = data.len() - data_offset;
    ensure!(
        canonical_stack_map_offset == 0 || (canonical_stack_map_offset as usize) < remaining,
        "canonical stack map is outside its data image"
    );
    let mut entries = Vec::with_capacity(length);
    let mut offsets = Vec::with_capacity(length);
    for record in table_bytes.as_chunks::<8>().0 {
        let pc_offset = read_u32(record, 0)? as usize;
        let stack_map_offset = read_u32(record, 4)?;
        ensure!(
            pc_offset != 0 && pc_offset.is_multiple_of(4) && pc_offset < instructions_len,
            "snapshot instruction entry is outside its aligned text image"
        );
        ensure!(
            stack_map_offset == 0 || (stack_map_offset as usize) < remaining,
            "snapshot stack map is outside its data image"
        );
        entries.push(InstructionEntry {
            pc_offset,
            stack_map_offset,
        });
        offsets.push(pc_offset);
    }
    offsets.sort_unstable();
    offsets.dedup();
    let ranges = offsets
        .iter()
        .copied()
        .zip(offsets.iter().copied().skip(1).chain([instructions_len]))
        .map(|(start, end)| start..end)
        .collect();
    Ok((
        InstructionTable {
            data_offset,
            canonical_stack_map_offset,
            entries,
        },
        ranges,
    ))
}

pub fn parse(data: &[u8], instructions_len: usize) -> Result<SnapshotInfo> {
    let mut header = Reader::new(data, 0);
    ensure!(
        header.bytes(4)? == b"\xf5\xf5\xdc\xdc",
        "invalid snapshot magic"
    );
    let stored_size = u64::from_le_bytes(header.bytes(8)?.try_into().unwrap());
    let snapshot_size = usize::try_from(stored_size)
        .ok()
        .and_then(|value| value.checked_add(4))
        .filter(|&value| value <= data.len())
        .ok_or_else(|| anyhow!("snapshot size is outside its mapped data"))?;
    let kind = u64::from_le_bytes(header.bytes(8)?.try_into().unwrap());
    ensure!(kind == 3, "snapshot is not FullAOT");
    header.bytes(32)?;
    let snapshot = data
        .get(..snapshot_size)
        .ok_or_else(|| anyhow!("snapshot data is truncated"))?;
    let feature_bytes = snapshot
        .get(header.position..)
        .ok_or_else(|| anyhow!("snapshot feature header is truncated"))?;
    let feature_length =
        memchr(0, feature_bytes).ok_or_else(|| anyhow!("snapshot features are unterminated"))?;
    let features = std::str::from_utf8(&feature_bytes[..feature_length])
        .context("snapshot features are not UTF-8")?;
    for required in ["product", "arm64", "android", "compressed-pointers"] {
        ensure!(
            features
                .split_ascii_whitespace()
                .any(|value| value == required),
            "unsupported snapshot features: missing {required}"
        );
    }
    let mut reader = Reader::new(snapshot, header.position + feature_length + 1);
    let base_objects = reader.unsigned()?;
    let object_count = u32::try_from(reader.unsigned()?).context("too many snapshot objects")?;
    let cluster_count = reader.count()?;
    let retained_code_count = reader.count()?;
    let table_offset = reader.count()?;
    ensure!(
        base_objects < u64::from(object_count)
            && cluster_count > 0
            && cluster_count <= snapshot.len()
            && retained_code_count > 0,
        "invalid snapshot clustered header"
    );
    let allocation_start = reader.position;
    let tag = u32::try_from(reader.signed(32)?).context("invalid snapshot string cluster tag")?;
    ensure!(
        tag >> 12 == 93 && tag & 0x42 == 0x42,
        "unsupported first snapshot cluster"
    );
    let string_count = reader.count()?;
    ensure!(
        string_count > 0
            && string_count <= reader.data.len() - reader.position
            && string_count as u64 <= u64::from(object_count) - base_objects,
        "invalid snapshot string count"
    );
    let mut lengths = Vec::with_capacity(string_count);
    for _ in 0..string_count {
        let encoded = u32::try_from(reader.unsigned()?).context("snapshot string is too large")?;
        ensure!(
            string_bytes(encoded)? <= snapshot.len(),
            "snapshot string exceeds its data span"
        );
        lengths.push(encoded);
    }
    let mut budget = ScanBudget {
        remaining: snapshot.len().saturating_mul(16),
    };
    let strings = locate_strings(snapshot, reader.position, &lengths, &mut budget)?;
    let mut role_refs = HashMap::new();
    let mut string_reader = Reader::new(snapshot, strings.start);
    for (index, &encoded) in lengths.iter().enumerate() {
        string_reader.unsigned()?;
        let bytes = string_reader.bytes(string_bytes(encoded)?)?;
        if encoded & 1 == 0
            && let Some(role) = Role::from_bytes(bytes)
        {
            let reference = u32::try_from(base_objects + 1 + index as u64)
                .context("snapshot string reference overflows")?;
            ensure!(
                !role_refs.values().any(|&existing| existing == role),
                "snapshot canonical role string is duplicated"
            );
            role_refs.insert(reference, role);
        }
    }
    let pool = locate_pool(
        snapshot,
        allocation_start,
        &strings,
        object_count,
        &mut budget,
    )?;
    let roles = pool
        .iter()
        .enumerate()
        .filter_map(|(index, entry)| match entry {
            PoolEntry::Tagged(reference) => role_refs
                .get(reference)
                .map(|&role| RolePoolEntry { role, index }),
            _ => None,
        })
        .collect();
    let (instruction_table, ranges) = instructions(
        data,
        snapshot_size,
        table_offset,
        retained_code_count,
        instructions_len,
    )?;
    Ok(SnapshotInfo {
        object_count,
        pool,
        roles,
        ranges,
        instruction_table,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn signed_bytes(mut value: i64) -> Vec<u8> {
        let mut result = Vec::new();
        while !(-64..64).contains(&value) {
            result.push((value & 127) as u8);
            value >>= 7;
        }
        result.push((value + 192) as u8);
        result
    }

    fn fixture(duplicate_strings: bool, duplicate_pool: bool) -> Vec<u8> {
        let mut data = Vec::from(b"\xf5\xf5\xdc\xdc".as_slice());
        data.extend([0; 8]);
        data.extend(3_u64.to_le_bytes());
        data.extend([b'0'; 32]);
        data.extend(b"product arm64 android compressed-pointers\0");
        for value in [2, 32, 2, 2, 64] {
            data.extend(unsigned_bytes(value));
        }
        data.extend(signed_bytes((93 << 12) | 0x42));
        data.extend(unsigned_bytes(3));
        for value in [b"BG_0".as_slice(), b"FG_1", b"Brand"] {
            data.extend(unsigned_bytes(value.len() as u64 * 2));
        }
        data.extend(b"\x00\x60\xc5\x81");
        data.extend(unsigned_bytes(7));
        data.extend([0x7f; 4]);
        let mut strings = Vec::new();
        for value in [b"BG_0".as_slice(), b"FG_1", b"Brand"] {
            strings.extend(unsigned_bytes(value.len() as u64 * 2));
            strings.extend(value);
        }
        data.extend(&strings);
        if duplicate_strings {
            data.extend(&strings);
        }
        let mut pool = unsigned_bytes(7);
        for reference in [3, 4, 5] {
            pool.extend([1, reference + 128]);
        }
        pool.push(0);
        pool.extend(signed_bytes(-7));
        pool.extend([2, 0x60, 0]);
        pool.extend(signed_bytes(0x3ff0_0000_0000_0000));
        data.extend(&pool);
        if duplicate_pool {
            data.extend(&pool);
        }
        let size = data.len();
        data[4..12].copy_from_slice(&(size as u64 - 4).to_le_bytes());
        let table_start = ((size + 63) & !63) + 64 + 16;
        data.resize(table_start, 0);
        for value in [0_u32, 3, 1, 0] {
            data.extend(value.to_le_bytes());
        }
        for offset in [0xc0_u32, 0x40, 0x80] {
            data.extend(offset.to_le_bytes());
            data.extend(0_u32.to_le_bytes());
        }
        data
    }

    #[test]
    fn decodes_vm_integers_and_reference_boundaries() {
        for value in [0, 63, 64, 127, 128, 16_383, 16_384, u64::MAX] {
            assert_eq!(
                Reader::new(&unsigned_bytes(value), 0).unsigned().unwrap(),
                value
            );
        }
        for value in [i64::MIN, -129, -65, -64, -1, 0, 63, 64, i64::MAX] {
            assert_eq!(
                Reader::new(&signed_bytes(value), 0).signed(64).unwrap(),
                value
            );
        }
        for (bytes, value) in [
            (b"\xff".as_slice(), 127),
            (b"\x01\x80", 128),
            (b"\x7f\xff", 16_383),
            (b"\x01\x00\x80", 16_384),
            (b"\x0f\x7f\x7f\x7f\xff", u32::MAX),
        ] {
            assert_eq!(Reader::new(bytes, 0).reference().unwrap(), value);
        }
        assert!(Reader::new(b"\x00\x80", 0).unsigned().is_err());
        assert!(Reader::new(b"\x00\xc0", 0).signed(64).is_err());
        assert!(Reader::new(b"\x00\x81", 0).reference().is_err());
        assert!(Reader::new(b"\x01", 0).reference().is_err());
        assert!(Reader::new(&signed_bytes(i64::MAX), 0).signed(32).is_err());
    }

    #[test]
    fn locates_complete_blocks_and_preserves_pool_values() {
        let info = parse(&fixture(false, false), 0x100).unwrap();
        assert_eq!(
            info.pool,
            vec![
                PoolEntry::Tagged(3),
                PoolEntry::Tagged(4),
                PoolEntry::Tagged(5),
                PoolEntry::Immediate(-7),
                PoolEntry::Native,
                PoolEntry::Reset,
                PoolEntry::Immediate(0x3ff0_0000_0000_0000),
            ]
        );
        assert_eq!(info.ranges, vec![0x40..0x80, 0x80..0xc0, 0xc0..0x100]);
        assert_eq!(
            info.roles,
            vec![
                RolePoolEntry {
                    role: Role::Bg0,
                    index: 0
                },
                RolePoolEntry {
                    role: Role::Fg1,
                    index: 1
                },
                RolePoolEntry {
                    role: Role::Brand,
                    index: 2
                },
            ]
        );
    }

    #[test]
    fn rejects_ambiguous_string_and_pool_fills() {
        let strings = parse(&fixture(true, false), 0x100).unwrap_err();
        assert!(strings.to_string().contains("string fill is ambiguous"));
        let pool = parse(&fixture(false, true), 0x100).unwrap_err();
        assert!(pool.to_string().contains("object pool is ambiguous"));
    }

    #[test]
    fn rejects_pool_signature_inside_a_longer_cluster_tag() {
        let mut data = vec![0x81, 0x02, 0x00, 0x60, 0xc5, 0x81, 0xc2, 0xc4];
        data.extend(b"\x00\x60\xc5\x81\x83");
        let strings = data.len()..data.len();
        data.push(0xc2);
        data.extend([0x60; 66]);
        data.extend([0x83, 1, 0x81, 1, 0x82, 1, 0x83]);
        let mut budget = ScanBudget { remaining: 4096 };
        let pool = locate_pool(&data, 1, &strings, 32, &mut budget).unwrap();
        assert_eq!(
            pool,
            vec![
                PoolEntry::Tagged(1),
                PoolEntry::Tagged(2),
                PoolEntry::Tagged(3)
            ]
        );
    }

    #[test]
    fn rejects_every_truncated_prefix_and_out_of_bounds_code() {
        let data = fixture(false, false);
        for end in 0..data.len() {
            assert!(parse(&data[..end], 0x100).is_err(), "accepted prefix {end}");
        }
        assert!(parse(&data, 0xc0).is_err());
    }
}
