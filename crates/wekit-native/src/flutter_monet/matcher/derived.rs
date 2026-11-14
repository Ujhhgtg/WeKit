use std::collections::{BTreeMap, HashSet, VecDeque};

use anyhow::{Result, ensure};

use super::super::snapshot::{Metadata, PoolEntry, Role};
use super::palette_matcher::{construction, floating_immediate, simple_color};
use super::{
    ColorFactory, Image, Target, TargetKind, allocation_class, branch_target, call_target,
    fresh_getter, relative, signed_slot,
};

const SCRATCH: u32 = (1 << 16) | (1 << 17);

fn register_bit(register: u32) -> u32 {
    if register < 31 { 1 << register } else { 0 }
}

pub(super) fn register_effects(word: u32) -> Option<(u32, u32)> {
    let destination = register_bit(word & 31);
    let first = register_bit((word >> 5) & 31);
    let second = register_bit((word >> 16) & 31);
    let unscaled = word & 0xffe00c00;
    let unsigned = word & 0xffc00000;
    if matches!(unscaled, 0xf8400000 | 0xb8400000) || matches!(unsigned, 0xf9400000 | 0xb9400000) {
        return Some((first, destination));
    }
    if matches!(unscaled, 0xf8000000 | 0xb8000000) || matches!(unsigned, 0xf9000000 | 0xb9000000) {
        return Some((first | destination, 0));
    }
    if matches!(unscaled, 0xfc000000 | 0xfc400000) || matches!(unsigned, 0xfd000000 | 0xfd400000) {
        return Some((first, 0));
    }
    if unsigned == 0xa9400000 {
        return Some((first, destination | register_bit((word >> 10) & 31)));
    }
    if unsigned == 0xa9000000 {
        return Some((first | destination | register_bit((word >> 10) & 31), 0));
    }
    if word & 0x1f000000 == 0x11000000 {
        return Some((first, destination));
    }
    if word & 0x1f800000 == 0x12800000 {
        let reads = if (word >> 29) & 3 == 3 {
            destination
        } else {
            0
        };
        return Some((reads, destination));
    }
    if matches!(word & 0x1f000000, 0x0a000000 | 0x0b000000) {
        return Some((first | second, destination));
    }
    if word & 0x1f800000 == 0x12000000 {
        return Some((first, destination));
    }
    if word & 0x1f800000 == 0x13000000 {
        let reads = first
            | if (word >> 29) & 3 == 1 {
                destination
            } else {
                0
            };
        return Some((reads, destination));
    }
    if word & 0x1f800000 == 0x13800000 || word & 0x1fe00000 == 0x1a800000 {
        return Some((first | second, destination));
    }
    if word == 0xd503201f || word & 0xffe01fe0 == 0x1e601000 {
        return Some((0, 0));
    }
    None
}

pub(super) fn scratch_dead_before_read(image: &Image<'_>, start: usize, end: usize) -> bool {
    let mut pending = VecDeque::from([(start, SCRATCH)]);
    let mut visited = HashSet::new();
    while let Some((offset, live)) = pending.pop_front() {
        if live == 0 || !visited.insert((offset, live)) {
            continue;
        }
        if visited.len() > 512 || offset >= end {
            return false;
        }
        let Some(word) = image.word(offset) else {
            return false;
        };
        if let Some(target) = call_target(word, offset) {
            if allocation_class(image, target).is_none() {
                return false;
            }
            pending.push_back((offset + 4, live));
            continue;
        }
        if let Some(target) = branch_target(word, offset) {
            if target <= offset || target >= end {
                return false;
            }
            pending.push_back((target, live));
            continue;
        }
        let conditional = word & 0xff000010 == 0x54000000;
        let compare_branch = word & 0x7e000000 == 0x34000000;
        let test_branch = word & 0x7e000000 == 0x36000000;
        if conditional || compare_branch || test_branch {
            if (compare_branch || test_branch) && register_bit(word & 31) & live != 0 {
                return false;
            }
            let Some(target) = relative(word, if test_branch { 14 } else { 19 }, 5, offset) else {
                return false;
            };
            if target <= offset || target >= end {
                return false;
            }
            pending.push_back((offset + 4, live));
            pending.push_back((target, live));
            continue;
        }
        let Some((reads, writes)) = register_effects(word) else {
            return false;
        };
        if reads & live != 0 {
            return false;
        }
        pending.push_back((offset + 4, live & !writes));
    }
    true
}

fn copyable_frame_instruction(word: u32, frame_bytes: usize) -> bool {
    matches!(word & 0xffe00c00, 0xf8400000 | 0xf8000000)
        && (word >> 5) & 31 == 29
        && word & 31 <= 14
        && signed_slot(word) < 0
        && signed_slot(word) % 8 == 0
        && signed_slot(word).unsigned_abs() as usize <= frame_bytes.saturating_sub(16)
}

fn frame_slot(word: u32, load: bool, frame: usize) -> Option<(u32, i32)> {
    let opcode = if load { 0xf8400000 } else { 0xf8000000 };
    (word & 0xffe00c00 == opcode && copyable_frame_instruction(word, frame))
        .then_some((word & 31, signed_slot(word)))
}

fn dominates(
    image: &Image<'_>,
    range: &std::ops::Range<usize>,
    source: usize,
    site: usize,
) -> bool {
    let mut pending = VecDeque::from([range.start]);
    let mut visited = HashSet::new();
    while let Some(offset) = pending.pop_front() {
        if offset == source || !visited.insert(offset) {
            continue;
        }
        if offset == site || visited.len() > 1024 {
            return false;
        }
        if !range.contains(&offset) {
            continue;
        }
        let Some(word) = image.word(offset) else {
            return false;
        };
        if word == super::RETURN {
            continue;
        }
        if let Some(target) = branch_target(word, offset) {
            pending.push_back(target);
            continue;
        }
        if word & 0xff000010 == 0x54000000 || word & 0x7e000000 == 0x34000000 {
            let Some(target) = relative(word, 19, 5, offset) else {
                return false;
            };
            pending.push_back(target);
        } else if word & 0x7e000000 == 0x36000000 {
            let Some(target) = relative(word, 14, 5, offset) else {
                return false;
            };
            pending.push_back(target);
        } else if word & 0xfe000000 == 0xd6000000 && word & 0xfffffc1f != 0xd63f0000 {
            return false;
        }
        pending.push_back(offset + 4);
    }
    true
}

fn frame_slots_unchanged(image: &Image<'_>, start: usize, end: usize, slots: &[i32]) -> bool {
    (start..end).step_by(4).all(|offset| {
        let Some(word) = image.word(offset) else {
            return false;
        };
        if (word >> 5) & 31 != 29 || word & 0x0a000000 != 0x08000000 {
            let branch = branch_target(word, offset).or_else(|| {
                if word & 0xff000010 == 0x54000000 || word & 0x7e000000 == 0x34000000 {
                    relative(word, 19, 5, offset)
                } else if word & 0x7e000000 == 0x36000000 {
                    relative(word, 14, 5, offset)
                } else {
                    None
                }
            });
            if let Some(target) = branch {
                return (start..=end).contains(&target);
            }
            return call_target(word, offset).is_some()
                || register_effects(word).is_some_and(|(_, writes)| writes & (1 << 29) == 0);
        }
        let opcode = word & 0xffe00c00;
        if matches!(opcode, 0xf8000000 | 0xb8000000 | 0xfc000000 | 0xbc000000) {
            let written = signed_slot(word);
            let bytes = if opcode & (1 << 30) == 0 { 4 } else { 8 };
            return slots
                .iter()
                .all(|slot| written >= slot + 8 || written + bytes <= *slot);
        }
        matches!(opcode, 0xf8400000 | 0xb8400000 | 0xfc400000 | 0xbc400000)
            && (opcode & (1 << 26) != 0 || word & 31 != 29)
    })
}

fn field_boolean(word: u32, receiver: u32, boolean: u32) -> bool {
    receiver <= 14
        && boolean <= 14
        && receiver != boolean
        && word & 0xffe00fff == 0xb8400000 | (receiver << 5) | boolean
        && signed_slot(word) == 7
}

fn decompressed_boolean(word: u32, boolean: u32) -> bool {
    word == 0x8b1c8000 | (boolean << 5) | boolean
}

fn saved_boolean(
    image: &Image<'_>,
    range: &std::ops::Range<usize>,
    guard: usize,
    frame: usize,
    boolean_slot: i32,
    receiver_slot: i32,
) -> bool {
    for store in (range.start + 16..guard.saturating_sub(8)).step_by(4) {
        let Some((boolean, slot)) = image
            .word(store)
            .and_then(|word| frame_slot(word, false, frame))
        else {
            continue;
        };
        let Some(field) = image.word(store - 8) else {
            continue;
        };
        let receiver = (field >> 5) & 31;
        if slot != boolean_slot
            || !field_boolean(field, receiver, boolean)
            || !image
                .word(store - 4)
                .is_some_and(|word| decompressed_boolean(word, boolean))
        {
            continue;
        }
        for source in [store - 12, store - 16] {
            if image
                .word(source)
                .and_then(|word| frame_slot(word, true, frame))
                != Some((receiver, receiver_slot))
                || !frame_slots_unchanged(image, source + 4, store - 8, &[receiver_slot])
                || !frame_slots_unchanged(image, store + 4, guard, &[boolean_slot, receiver_slot])
                || !dominates(image, range, source, store)
                || !dominates(image, range, store, guard)
            {
                continue;
            }
            if (source + 4..store - 8).step_by(4).all(|offset| {
                image.word(offset).is_some_and(|word| {
                    frame_slot(word, false, frame).is_some()
                        && register_effects(word).is_some_and(|(_, writes)| {
                            writes & (register_bit(receiver) | register_bit(boolean)) == 0
                        })
                })
            }) {
                return true;
            }
        }
    }
    false
}

struct ThemeGuard {
    offset: usize,
    proof_start: usize,
    inline_bit4: bool,
}

fn theme_guard(
    image: &Image<'_>,
    range: &std::ops::Range<usize>,
    call: usize,
    frame: usize,
) -> Option<ThemeGuard> {
    for offset in [call - 4, call - 8] {
        let word = image.word(offset)?;
        if !matches!(word & 0xfff80000, 0x36200000 | 0x37200000) {
            continue;
        }
        let boolean = word & 31;
        if boolean > 14 {
            continue;
        }
        let proof_start = if offset == call - 4 {
            if !field_boolean(image.word(offset - 8)?, 1, boolean)
                || !decompressed_boolean(image.word(offset - 4)?, boolean)
            {
                continue;
            }
            offset - 8
        } else {
            let receiver_load = image.word(call - 4)?;
            let receiver = (receiver_load >> 16) & 31;
            if receiver_load & 0xffe0ffff == 0xaa0003e1 {
                if !field_boolean(image.word(offset - 12)?, receiver, boolean)
                    || !decompressed_boolean(image.word(offset - 8)?, boolean)
                    || image
                        .word(offset - 4)
                        .and_then(|word| frame_slot(word, false, frame))
                        .is_none_or(|(register, _)| register != boolean)
                {
                    continue;
                }
                offset - 12
            } else {
                let (argument, receiver_slot) = frame_slot(receiver_load, true, frame)?;
                let (loaded, boolean_slot) = frame_slot(image.word(offset - 8)?, true, frame)?;
                if argument != 1
                    || loaded != boolean
                    || frame_slot(image.word(offset - 4)?, false, frame).is_none()
                    || !saved_boolean(image, range, offset, frame, boolean_slot, receiver_slot)
                {
                    continue;
                }
                offset - 8
            }
        };
        return Some(ThemeGuard {
            offset,
            proof_start,
            inline_bit4: word & (1 << 24) != 0,
        });
    }
    None
}

fn helper_continuation(
    image: &Image<'_>,
    call: usize,
    expected: [u64; 4],
    metadata: &Metadata,
) -> Option<(usize, usize, u32)> {
    let moved = image.word(call + 4)?;
    let register = moved & 31;
    if moved & 0xffffffe0 != 0xaa0003e0 || !(1..=3).contains(&register) {
        return None;
    }
    let mut cursor = call + 8;
    if branch_target(image.word(cursor)?, cursor).is_none() {
        if image.word(cursor).and_then(floating_immediate) != Some((0, expected[0])) {
            return None;
        }
        cursor += 4;
        let first = image.word(cursor)?;
        if first & 0xffc003e0 == 0x91400360 && !matches!(first & 31, 0 | 17) {
            return None;
        }
        let (next, pool) = image.pool_load(cursor, 0, false)?;
        if image.pool_value(pool) != Some(PoolEntry::Tagged(metadata.color_space_reference)) {
            return None;
        }
        cursor = next;
        let first = image.word(cursor)?;
        if first & 0xffc003e0 == 0x91400360 && first & 31 != 17 {
            return None;
        }
        let (next, pool) = image.pool_load(cursor, 1, true)?;
        if image.pool_value(pool) != Some(PoolEntry::Immediate(expected[1] as i64)) {
            return None;
        }
        cursor = next;
    }
    Some((
        cursor,
        branch_target(image.word(cursor)?, cursor)?,
        register,
    ))
}

fn exclusive_join(
    image: &Image<'_>,
    range: &std::ops::Range<usize>,
    guard: &ThemeGuard,
    branch: usize,
    join: usize,
) -> bool {
    for offset in (range.start..range.end).step_by(4) {
        let Some(word) = image.word(offset) else {
            return false;
        };
        let target = branch_target(word, offset)
            .or_else(|| call_target(word, offset))
            .or_else(|| {
                if word & 0xff000010 == 0x54000000 || word & 0x7e000000 == 0x34000000 {
                    relative(word, 19, 5, offset)
                } else if word & 0x7e000000 == 0x36000000 {
                    relative(word, 14, 5, offset)
                } else {
                    None
                }
            });
        if let Some(target) = target
            && (guard.proof_start + 4..=join).contains(&target)
            && !((offset == guard.offset && target == branch + 4)
                || (offset == branch && target == join))
        {
            return false;
        }
    }
    true
}

pub(super) fn targets(
    image: &Image<'_>,
    factory: &ColorFactory,
    metadata: &Metadata,
    helpers: &BTreeMap<usize, Vec<Role>>,
) -> Result<Vec<Target>> {
    let mut result = BTreeMap::<usize, Target>::new();
    for range in &image.snapshot.ranges {
        if !(64..=4096).contains(&range.len()) {
            continue;
        }
        let Some(frame) = image.local_frame(range.start) else {
            continue;
        };
        for call in (range.start + 20..range.end.saturating_sub(16)).step_by(4) {
            let Some(helper) = image.word(call).and_then(|word| call_target(word, call)) else {
                continue;
            };
            let Some(roles) = helpers.get(&helper) else {
                continue;
            };
            let Some(&role) = roles.first() else {
                continue;
            };
            ensure!(
                roles.iter().all(|candidate| *candidate == role),
                "inline twin helper has ambiguous semantic roles at {helper:#x}"
            );
            if !fresh_getter(image, helper, factory, 0) {
                continue;
            }
            let Some(guard) = theme_guard(image, range, call, frame) else {
                continue;
            };
            let Some(expected) =
                simple_color(image, helper, guard.inline_bit4, factory, metadata, 0)
            else {
                continue;
            };
            if simple_color(image, helper, !guard.inline_bit4, factory, metadata, 0).is_none() {
                continue;
            }
            let Some((branch, join, register)) =
                helper_continuation(image, call, expected, metadata)
            else {
                continue;
            };
            if join <= branch + 4
                || join >= range.end
                || image
                    .word(guard.offset)
                    .and_then(|word| relative(word, 14, 5, guard.offset))
                    != Some(branch + 4)
            {
                continue;
            }
            let Some(inline) = construction(image, branch + 4, join, factory, metadata) else {
                continue;
            };
            let joined = inline.end == join && inline.register == register as usize
                || inline.end + 4 == join
                    && image.word(inline.end)
                        == Some(0xaa0003e0 | ((inline.register as u32) << 16) | register);
            if !joined
                || inline.spill.is_some()
                || inline.color != expected
                || !exclusive_join(image, range, &guard, branch, join)
            {
                continue;
            }
            let Some(original) = image.word(join) else {
                continue;
            };
            if !copyable_frame_instruction(original, frame) {
                continue;
            }
            if let Some(previous) = result.get(&join) {
                ensure!(
                    previous.role == role,
                    "inline twin has conflicting semantic roles at {join:#x}"
                );
            } else {
                result.insert(
                    join,
                    Target {
                        offset: join,
                        role,
                        original,
                        kind: TargetKind::FreshInstruction {
                            color_register: register as u8,
                            preserve_scratch: false,
                        },
                    },
                );
            }
        }
    }
    Ok(result.into_values().collect())
}

#[cfg(test)]
mod tests {
    use super::super::super::snapshot::{InstructionTable, SnapshotInfo};
    use super::*;

    fn inspect(words: &[u32], action: impl FnOnce(&Image<'_>) -> bool) -> bool {
        let bytes: Vec<_> = words.iter().flat_map(|word| word.to_le_bytes()).collect();
        let snapshot = SnapshotInfo {
            object_count: 0,
            pool: Vec::new(),
            roles: Vec::new(),
            ranges: std::iter::once(0..bytes.len()).collect(),
            instruction_table: InstructionTable {
                data_offset: 0,
                canonical_stack_map_offset: 0,
                entries: Vec::new(),
            },
        };
        action(&Image {
            bytes: &bytes,
            snapshot: &snapshot,
        })
    }

    fn scratch_proof(words: &[u32]) -> bool {
        inspect(words, |image| {
            scratch_dead_before_read(image, 0, image.bytes.len())
        })
    }

    #[test]
    fn rejects_scratch_reads_and_partial_overwrites() {
        assert!(!scratch_proof(&[0xaa1003e0, 0xd2800010, 0xd2800011]));
        assert!(!scratch_proof(&[0xf2800010, 0xd2800011]));
        assert!(!scratch_proof(&[0xf9400210, 0xd2800011]));
        assert!(!scratch_proof(&[
            0xfc5f83b0, 0xaa1003e0, 0xd2800010, 0xd2800011,
        ]));
        assert!(scratch_proof(&[0xd2800010, 0xf2800030, 0xd2800011]));
    }

    #[test]
    fn checks_all_scratch_successors_and_rejects_unknown_calls() {
        assert!(scratch_proof(&[
            0x36000060, 0xd2800010, 0x14000002, 0xd2800010, 0xd2800011,
        ]));
        assert!(!scratch_proof(&[
            0x36000060, 0xd2800010, 0x14000002, 0xaa1003e0, 0xd2800011,
        ]));
        assert!(!scratch_proof(&[0x94000001, 0xd2800010, 0xd2800011]));
        assert!(!scratch_proof(&[0x14000000, 0xd2800010, 0xd2800011]));
        assert!(!scratch_proof(&[0x37200051, 0xd2800010, 0xd2800011]));
    }

    #[test]
    fn rejects_paths_bypassing_the_boolean_source() {
        let mut words = [
            0xd503201f,
            0xd503201f,
            0x36000040,
            0xd503201f,
            super::super::RETURN,
        ];
        assert!(inspect(&words, |image| dominates(image, &(0..20), 4, 16)));
        words[0] = 0x14000002;
        assert!(!inspect(&words, |image| dominates(image, &(0..20), 4, 16)));
    }

    #[test]
    fn rejects_overlapping_frame_writes_and_unknown_provenance() {
        assert!(inspect(&[0xf81d83a3, 0xf85f03a2], |image| {
            frame_slots_unchanged(image, 0, 8, &[-16])
        }));
        for word in [0xf81f03a2, 0xb81f43a3, 0xaa0003fd, 0xffffffff, 0x14000003] {
            assert!(!inspect(&[word, 0xd503201f], |image| {
                frame_slots_unchanged(image, 0, 8, &[-16])
            }));
        }
    }

    #[test]
    fn requires_the_same_saved_receiver_and_an_untouched_boolean() {
        let mut words = [
            0xd503201f,
            0xf85d83a0,
            0xb8407002,
            0x8b1c8042,
            0xf81f03a2,
            0xd503201f,
            0xf85f03a0,
            0xf81d03a3,
            0x37200040,
            0xf85d83a1,
            super::super::RETURN,
        ];
        assert!(inspect(&words, |image| saved_boolean(
            image,
            &(0..44),
            32,
            96,
            -16,
            -40
        )));
        assert!(!inspect(&words, |image| saved_boolean(
            image,
            &(0..44),
            32,
            96,
            -16,
            -48
        )));
        words[5] = 0xf81f03a3;
        assert!(!inspect(&words, |image| saved_boolean(
            image,
            &(0..44),
            32,
            96,
            -16,
            -40
        )));
    }

    #[test]
    fn rejects_calls_entering_after_the_color_proof() {
        let mut words = [
            0xd503201f,
            0x37200060,
            0x94000008,
            0x14000002,
            0xd503201f,
            0xf85f83a0,
            super::super::RETURN,
        ];
        let guard = ThemeGuard {
            offset: 4,
            proof_start: 0,
            inline_bit4: true,
        };
        assert!(inspect(&words, |image| exclusive_join(
            image,
            &(0..28),
            &guard,
            12,
            20
        )));
        words[0] = 0x94000005;
        assert!(!inspect(&words, |image| exclusive_join(
            image,
            &(0..28),
            &guard,
            12,
            20
        )));
    }
}
