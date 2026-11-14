use std::collections::{BTreeMap, BTreeSet, HashSet, VecDeque};
use std::ops::Range;

use anyhow::{Result, ensure};

use super::super::snapshot::{Metadata, PoolEntry, Role};
use super::derived::register_effects;
use super::palette_matcher::construction;
use super::{
    ColorFactory, Image, RETURN, Target, TargetKind, branch_target, call_target, relative,
    signed_slot,
};

fn movement(word: u32) -> Option<(u32, u32)> {
    (word & 0xffe0ffe0 == 0xaa0003e0).then_some((word & 31, (word >> 16) & 31))
}

fn frame_slot(word: u32, load: bool, register: u32, frame: usize) -> Option<i32> {
    let opcode = if load { 0xf8400000 } else { 0xf8000000 };
    if word & 0xffe00fff != opcode | (29 << 5) | register {
        return None;
    }
    let slot = signed_slot(word);
    (slot < 0 && slot % 8 == 0 && slot.unsigned_abs() as usize <= frame.saturating_sub(16))
        .then_some(slot)
}

fn jump_destination(word: u32, offset: usize) -> Option<usize> {
    branch_target(word, offset).or_else(|| {
        if word & 0xff000010 == 0x54000000 || word & 0x7e000000 == 0x34000000 {
            relative(word, 19, 5, offset)
        } else if word & 0x7e000000 == 0x36000000 {
            relative(word, 14, 5, offset)
        } else {
            None
        }
    })
}

fn exclusive(image: &Image<'_>, range: &Range<usize>, span: Range<usize>) -> bool {
    (range.start..range.end).step_by(4).all(|offset| {
        let Some(word) = image.word(offset) else {
            return false;
        };
        jump_destination(word, offset)
            .or_else(|| call_target(word, offset))
            .is_none_or(|destination| !span.contains(&destination))
    })
}

fn reachable(
    image: &Image<'_>,
    range: &Range<usize>,
    destination: usize,
    blocked: Option<usize>,
) -> Option<bool> {
    let mut pending = VecDeque::from([range.start]);
    let mut visited = HashSet::new();
    while let Some(offset) = pending.pop_front() {
        if Some(offset) == blocked || !range.contains(&offset) || !visited.insert(offset) {
            continue;
        }
        if visited.len() > 2048 {
            return None;
        }
        if offset == destination {
            return Some(true);
        }
        let word = image.word(offset)?;
        if word == RETURN {
            continue;
        }
        if word & 0xfffffc1f == 0xd61f0000 {
            return None;
        }
        if let Some(target) = jump_destination(word, offset) {
            pending.push_back(target);
            if branch_target(word, offset).is_some() {
                continue;
            }
        }
        pending.push_back(offset + 4);
    }
    Some(false)
}

fn dominates(
    image: &Image<'_>,
    range: &Range<usize>,
    checkpoint: usize,
    destination: usize,
) -> bool {
    reachable(image, range, destination, None) == Some(true)
        && reachable(image, range, destination, Some(checkpoint)) == Some(false)
}

fn overwrites_slot(word: u32, slot: i32) -> bool {
    if (word >> 5) & 31 != 29 {
        return false;
    }
    let unscaled = word & 0xffe00c00;
    let unsigned = word & 0xffc00000;
    let access = if matches!(unscaled, 0xf8000000 | 0xfc000000) {
        Some((signed_slot(word), 8))
    } else if matches!(unscaled, 0xb8000000 | 0xbc000000) {
        Some((signed_slot(word), 4))
    } else if matches!(unsigned, 0xf9000000 | 0xfd000000) {
        Some(((((word >> 10) & 0xfff) * 8) as i32, 8))
    } else if matches!(unsigned, 0xb9000000 | 0xbd000000) {
        Some(((((word >> 10) & 0xfff) * 4) as i32, 4))
    } else if matches!(unsigned, 0xa9000000 | 0x6d000000 | 0x29000000 | 0x2d000000) {
        let width = if matches!(unsigned, 0xa9000000 | 0x6d000000) {
            8
        } else {
            4
        };
        let displacement = ((word >> 15) as i32 & 0x7f) << 25 >> 25;
        Some((displacement * width, width * 2))
    } else {
        return word & 0x0a000000 == 0x08000000 && word & (1 << 22) == 0;
    };
    access.is_some_and(|(start, width)| start < slot + 8 && slot < start + width)
}

fn preserves_frame(word: u32, offset: usize) -> bool {
    call_target(word, offset).is_some()
        || jump_destination(word, offset).is_some()
        || word & 0xfffffc1f == 0xd63f0000
        || register_effects(word).is_some_and(|(_, writes)| writes & (1 << 29) == 0)
}

fn provider_return(
    image: &Image<'_>,
    range: &Range<usize>,
    store: usize,
    mut register: u32,
) -> Option<usize> {
    for distance in 1..=4 {
        let offset = store.checked_sub(distance * 4)?;
        if offset < range.start + 8 {
            return None;
        }
        let word = image.word(offset)?;
        if let Some(provider) = call_target(word, offset) {
            return (register == 0 && exclusive(image, range, offset + 4..store + 4))
                .then_some(provider);
        }
        if jump_destination(word, offset).is_some() {
            return None;
        }
        if let Some((destination, source)) = movement(word)
            && destination == register
            && source < 15
        {
            register = source;
            continue;
        }
        let (_, writes) = register_effects(word)?;
        if writes & ((1 << register) | (1 << 29)) != 0 {
            return None;
        }
    }
    None
}

fn themed(
    image: &Image<'_>,
    range: &Range<usize>,
    frame: usize,
    constructor: usize,
    join: usize,
    verified_theme: &impl Fn(usize, usize) -> bool,
) -> bool {
    for call in (join..range.end).step_by(4) {
        let Some(getter) = image.word(call).and_then(|word| call_target(word, call)) else {
            continue;
        };
        for load in (call.saturating_sub(16).max(join)..call).step_by(4) {
            let Some(slot) = image
                .word(load)
                .and_then(|word| frame_slot(word, true, 1, frame))
            else {
                continue;
            };
            if !dominates(image, range, join, load)
                || !(load + 4..call).step_by(4).all(|offset| {
                    image.word(offset).is_some_and(|word| {
                        register_effects(word).is_some_and(|(_, writes)| writes & (1 << 1) == 0)
                    })
                })
            {
                continue;
            }
            for store in (range.start + 8..constructor).step_by(4) {
                let Some(word) = image.word(store) else {
                    continue;
                };
                let register = word & 31;
                if register >= 15 || frame_slot(word, false, register, frame) != Some(slot) {
                    continue;
                }
                let Some(provider) = provider_return(image, range, store, register) else {
                    continue;
                };
                if !verified_theme(provider, getter)
                    || !dominates(image, range, store, constructor)
                    || !dominates(image, range, store, load)
                {
                    continue;
                }
                if (store + 4..load).step_by(4).all(|offset| {
                    image.word(offset).is_some_and(|word| {
                        !overwrites_slot(word, slot) && preserves_frame(word, offset)
                    })
                }) {
                    return true;
                }
            }
        }
    }
    false
}

fn default_branch(
    image: &Image<'_>,
    range: &Range<usize>,
    call: usize,
    factory: &ColorFactory,
    metadata: &Metadata,
    expected: [u64; 4],
) -> Option<Target> {
    let value = construction(image, call, range.end, factory, metadata)?;
    if value.color != expected || value.spill.is_some() {
        return None;
    }
    let mut branch = value.end;
    let mut register = value.register as u32;
    if let Some((destination, source)) = image.word(branch).and_then(movement) {
        if source != register || destination >= 15 {
            return None;
        }
        register = destination;
        branch += 4;
    }
    let original = image.word(branch)?;
    let join = branch_target(original, branch)?;
    if join != branch + 8 || !range.contains(&join) {
        return None;
    }
    let (destination, override_register) = image.word(branch + 4).and_then(movement)?;
    if destination != register || override_register >= 15 || override_register == register {
        return None;
    }
    let guard = call.checked_sub(4)?;
    let condition = image.word(guard)?;
    if condition & 0xff00001f != 0x54000001
        || relative(condition, 19, 5, guard) != Some(branch + 4)
        || image.word(call.checked_sub(8)?)? != 0x6b16001f | (override_register << 5)
        || !exclusive(image, range, call..branch + 4)
    {
        return None;
    }
    let load = image.word(call.checked_sub(16)?)?;
    let slot = signed_slot(load);
    if load & 0xffe00c1f != 0xb8400000 | override_register
        || (load >> 5) & 31 >= 15
        || slot < 7
        || (slot + 1) % 4 != 0
        || image.word(call.checked_sub(12)?)?
            != 0x8b1c8000 | (override_register << 5) | override_register
    {
        return None;
    }
    Some(Target {
        offset: branch,
        role: Role::Brand,
        original,
        kind: TargetKind::FreshBranch {
            color_register: register as u8,
            destination: join,
        },
    })
}

fn provider_adjacent(
    image: &Image<'_>,
    range: &Range<usize>,
    call: usize,
    factory: &ColorFactory,
    metadata: &Metadata,
    expected: [u64; 4],
    verified_provider: &impl Fn(usize) -> bool,
) -> Option<Target> {
    let provider_call = call.checked_sub(4)?;
    let provider = call_target(image.word(provider_call)?, provider_call)?;
    if !verified_provider(provider) {
        return None;
    }
    let value = construction(image, call, range.end, factory, metadata)?;
    if value.color != expected || value.spill.is_some() {
        return None;
    }
    let point = value.end;
    let original = image.word(point)?;
    if !matches!(original & 0xffc003ff, 0x91400370 | 0xf9400370) {
        return None;
    }
    let (end, pool) = image.pool_load(point, 16, false)?;
    if end + 4 > range.end
        || !matches!(image.pool_value(pool), Some(PoolEntry::Tagged(_)))
        || image.word(end)? != 0xa90041e0 | value.register as u32
        || !exclusive(image, range, call..end + 4)
        || !dominates(image, range, provider_call, point)
    {
        return None;
    }
    Some(Target {
        offset: point,
        role: Role::Brand,
        original,
        kind: TargetKind::FreshInstruction {
            color_register: value.register as u8,
            preserve_scratch: true,
        },
    })
}

pub(super) fn targets(
    image: &Image<'_>,
    factory: &ColorFactory,
    metadata: &Metadata,
    anchors: &[Target],
    verified_theme: impl Fn(usize, usize) -> bool,
    verified_provider: impl Fn(usize) -> bool,
) -> Result<Vec<Target>> {
    let mut colors = BTreeSet::new();
    for anchor in anchors.iter().filter(|target| target.role == Role::Brand) {
        let TargetKind::FreshBranch { color_register, .. } = anchor.kind else {
            continue;
        };
        let Some(range) = image.range(anchor.offset) else {
            continue;
        };
        let definitions: Vec<_> = (range.start..anchor.offset)
            .step_by(4)
            .filter_map(|call| {
                let color = construction(image, call, anchor.offset, factory, metadata)?;
                (color.end == anchor.offset
                    && color.register == usize::from(color_register)
                    && color.spill.is_none())
                .then_some(color.color)
            })
            .collect();
        ensure!(
            definitions.len() == 1,
            "named Brand construction is not unique at {:#x}",
            anchor.offset
        );
        colors.insert(definitions[0]);
    }
    ensure!(colors.len() <= 1, "named Brand constructions disagree");
    let Some(&expected) = colors.first() else {
        return Ok(Vec::new());
    };
    let mut result = BTreeMap::new();
    for range in &image.snapshot.ranges {
        if !(96..=4096).contains(&range.len()) {
            continue;
        }
        let Some(frame) = image.frame_size(range.start) else {
            continue;
        };
        for call in (range.start + 20..range.end).step_by(4) {
            if image.word(call).and_then(|word| call_target(word, call))
                != Some(factory.constructor)
            {
                continue;
            }
            if let Some(target) = provider_adjacent(
                image,
                range,
                call,
                factory,
                metadata,
                expected,
                &verified_provider,
            ) {
                result.insert(target.offset, target);
            }
            let Some(target) = default_branch(image, range, call, factory, metadata, expected)
            else {
                continue;
            };
            let TargetKind::FreshBranch { destination, .. } = target.kind else {
                unreachable!();
            };
            if themed(image, range, frame, call, destination, &verified_theme) {
                result.insert(target.offset, target);
            }
        }
    }
    Ok(result.into_values().collect())
}
