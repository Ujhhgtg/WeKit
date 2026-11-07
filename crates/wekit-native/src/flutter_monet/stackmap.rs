use anyhow::{Context, Result, anyhow, bail, ensure};

use super::snapshot::InstructionTable;

struct Reader<'a> {
    data: &'a [u8],
    position: usize,
}

impl<'a> Reader<'a> {
    fn bytes(&mut self, length: usize) -> Result<&'a [u8]> {
        let end = self
            .position
            .checked_add(length)
            .ok_or_else(|| anyhow!("stack map read overflows"))?;
        let value = self
            .data
            .get(self.position..end)
            .ok_or_else(|| anyhow!("stack map is truncated"))?;
        self.position = end;
        Ok(value)
    }

    fn unsigned(&mut self) -> Result<u64> {
        let mut value = 0_u128;
        for group in 0..10 {
            let current = self.bytes(1)?[0];
            let payload = current & 127;
            value |= u128::from(payload) << (group * 7);
            if current & 128 == 0 {
                ensure!(group == 0 || payload != 0, "noncanonical stack map LEB128");
                return u64::try_from(value).context("stack map LEB128 overflows");
            }
        }
        bail!("unterminated stack map LEB128")
    }

    fn bitmap(&mut self) -> Result<Bitmap<'a>> {
        let spills =
            usize::try_from(self.unsigned()?).context("stack map spill count overflows")?;
        let saved = usize::try_from(self.unsigned()?).context("stack map saved count overflows")?;
        let byte_count = spills
            .checked_add(saved)
            .and_then(|value| value.checked_add(7))
            .map(|value| value / 8)
            .ok_or_else(|| anyhow!("stack map bitmap size overflows"))?;
        let bits = self.bytes(byte_count)?;
        Ok(Bitmap {
            spills,
            saved,
            bits,
        })
    }
}

struct Bitmap<'a> {
    spills: usize,
    saved: usize,
    bits: &'a [u8],
}

struct Payload<'a> {
    flags: u8,
    body: &'a [u8],
}

fn payload(data: &[u8], base: usize, offset: u32) -> Result<Payload<'_>> {
    let position = base
        .checked_add(offset as usize)
        .ok_or_else(|| anyhow!("stack map payload offset overflows"))?;
    let mut reader = Reader { data, position };
    let flags_and_size = u32::from_le_bytes(reader.bytes(4)?.try_into().unwrap());
    let flags = (flags_and_size & 3) as u8;
    ensure!(flags != 3, "stack map has incompatible table flags");
    let body = reader.bytes((flags_and_size >> 2) as usize)?;
    Ok(Payload { flags, body })
}

pub fn has_unboxed_frame(
    data: &[u8],
    table: &InstructionTable,
    range_start: usize,
    return_pc: usize,
    spill_slots: usize,
) -> Result<bool> {
    let Some(target_pc) = return_pc
        .checked_sub(range_start)
        .filter(|&value| value != 0)
    else {
        return Ok(false);
    };
    let mut selected_offset = None;
    for entry in &table.entries {
        if entry.pc_offset != range_start {
            continue;
        }
        if let Some(previous) = selected_offset {
            ensure!(
                previous == entry.stack_map_offset,
                "physical code entry has ambiguous stack maps"
            );
        }
        selected_offset = Some(entry.stack_map_offset);
    }
    let Some(offset) = selected_offset.filter(|&value| value != 0) else {
        return Ok(false);
    };
    let maps = payload(data, table.data_offset, offset)?;
    if maps.flags == 1 {
        return Ok(false);
    }
    let global = if maps.flags == 2 {
        ensure!(
            table.canonical_stack_map_offset != 0,
            "stack map refers to a missing global table"
        );
        let global = payload(data, table.data_offset, table.canonical_stack_map_offset)?;
        ensure!(
            global.flags == 1,
            "referenced stack map is not a global table"
        );
        Some(global)
    } else {
        None
    };
    let mut reader = Reader {
        data: maps.body,
        position: 0,
    };
    let mut pc_offset = 0_u32;
    let mut matched = None;
    while reader.position < reader.data.len() {
        let delta = u32::try_from(reader.unsigned()?).context("stack map PC delta overflows")?;
        ensure!(delta != 0, "stack map PCs are not strictly increasing");
        pc_offset = pc_offset
            .checked_add(delta)
            .ok_or_else(|| anyhow!("stack map PC offset overflows"))?;
        let bitmap = if let Some(global) = &global {
            let position =
                usize::try_from(reader.unsigned()?).context("global stack map offset overflows")?;
            let mut global_reader = Reader {
                data: global.body,
                position,
            };
            global_reader.bitmap()?
        } else {
            reader.bitmap()?
        };
        if pc_offset as usize == target_pc {
            ensure!(
                matched.is_none(),
                "allocator safepoint stack map is ambiguous"
            );
            matched = Some(
                bitmap.spills == spill_slots
                    && bitmap.saved == 0
                    && bitmap.bits.iter().all(|&value| value == 0),
            );
        }
    }
    Ok(matched.unwrap_or(false))
}

#[cfg(test)]
mod tests {
    use super::super::snapshot::InstructionEntry;
    use super::*;

    fn write_payload(data: &mut Vec<u8>, offset: usize, flags: u32, body: &[u8]) {
        data.resize(data.len().max(offset + 4 + body.len()), 0);
        let header = (body.len() as u32 * 4) | flags;
        data[offset..offset + 4].copy_from_slice(&header.to_le_bytes());
        data[offset + 4..offset + 4 + body.len()].copy_from_slice(body);
    }

    fn table() -> InstructionTable {
        InstructionTable {
            data_offset: 8,
            canonical_stack_map_offset: 0x80,
            entries: vec![InstructionEntry {
                pc_offset: 0x100,
                stack_map_offset: 0x20,
            }],
        }
    }

    #[test]
    fn requires_exact_standalone_safepoint_and_unboxed_frame() {
        let mut data = Vec::new();
        write_payload(
            &mut data,
            0x28,
            0,
            &[0x20, 4, 0, 0, 0x4c, 4, 0, 0, 0x7c, 4, 21, 0, 0, 0x80, 0],
        );
        assert!(has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).unwrap());
        assert!(!has_unboxed_frame(&data, &table(), 0x100, 0x170, 4).unwrap());
        assert!(!has_unboxed_frame(&data, &table(), 0x100, 0x16c, 5).unwrap());
        assert!(!has_unboxed_frame(&data, &table(), 0x100, 0x1e8, 4).unwrap());
        data[0x28 + 4 + 7] = 1;
        assert!(!has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).unwrap());
    }

    #[test]
    fn resolves_global_bitmap_offsets_and_saved_slots() {
        let mut data = Vec::new();
        write_payload(&mut data, 0x28, 2, &[0x20, 0, 0x4c, 0, 0x7c, 3]);
        write_payload(&mut data, 0x88, 1, &[4, 0, 0, 4, 21, 0, 0, 0x80, 0]);
        assert!(has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).unwrap());
        assert!(!has_unboxed_frame(&data, &table(), 0x100, 0x1e8, 4).unwrap());
        data[0x88 + 4 + 2] = 4;
        assert!(!has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).unwrap());
    }

    #[test]
    fn rejects_truncated_maps_bad_leb_and_invalid_global_tables() {
        let mut data = Vec::new();
        write_payload(&mut data, 0x28, 2, &[0x6c, 0]);
        write_payload(&mut data, 0x88, 1, &[4, 0, 0]);
        for end in 0..data.len() {
            assert!(has_unboxed_frame(&data[..end], &table(), 0x100, 0x16c, 4).is_err());
        }
        write_payload(&mut data, 0x28, 2, &[0x6c, 4]);
        assert!(has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).is_err());
        write_payload(&mut data, 0x28, 0, &[0x6c, 4, 0, 0, 0x80]);
        assert!(has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).is_err());
        write_payload(&mut data, 0x28, 0, &[0xec, 0, 4, 0, 0]);
        assert!(has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).is_err());
        write_payload(&mut data, 0x28, 2, &[0x6c, 0]);
        write_payload(&mut data, 0x88, 0, &[4, 0, 0]);
        assert!(has_unboxed_frame(&data, &table(), 0x100, 0x16c, 4).is_err());
    }

    #[test]
    fn missing_or_conflicting_physical_maps_never_approve_a_frame() {
        assert!(!has_unboxed_frame(&[], &table(), 0x200, 0x26c, 4).unwrap());
        let mut no_map = table();
        no_map.entries[0].stack_map_offset = 0;
        assert!(!has_unboxed_frame(&[], &no_map, 0x100, 0x16c, 4).unwrap());
        let mut conflicting = table();
        conflicting.entries.push(InstructionEntry {
            pc_offset: 0x100,
            stack_map_offset: 0x30,
        });
        assert!(has_unboxed_frame(&[], &conflicting, 0x100, 0x16c, 4).is_err());
    }
}
