use std::collections::BTreeMap;

use super::super::snapshot::{Metadata, PoolEntry};
use super::{
    ColorFactory, Image, POP_FRAME, PUSH_FRAME, RESTORE_STACK, RETURN, SET_FRAME, branch_target,
    call_target, relative, signed_slot,
};

type Color = [u64; 4];

#[derive(Clone, Copy, PartialEq)]
enum Value {
    Unknown,
    Receiver,
    Boolean(bool),
    Null,
    Integer(u64),
    Color(Color, Option<usize>),
}

pub(super) struct Construction {
    pub(super) end: usize,
    pub(super) register: usize,
    pub(super) color: Color,
    pub(super) spill: Option<i32>,
}

pub(super) fn floating_immediate(word: u32) -> Option<(usize, u64)> {
    if word & 0xffe01fe0 != 0x1e601000 {
        return None;
    }
    let immediate = (word >> 13) & 255;
    let repeated = u64::from((immediate >> 6) & 1);
    let bits = (u64::from(immediate & 128) << 56)
        | ((1 - repeated) << 62)
        | ((repeated * 255) << 54)
        | (u64::from((immediate >> 4) & 3) << 52)
        | (u64::from(immediate & 15) << 48);
    Some(((word & 31) as usize, bits))
}

fn normalized(bits: u64) -> bool {
    let value = f64::from_bits(bits);
    value.is_finite() && (0.0..=1.0).contains(&value)
}

fn floating_load(image: &Image<'_>, cursor: usize) -> Option<(usize, usize, u64)> {
    if let Some((register, bits)) = image.word(cursor).and_then(floating_immediate) {
        return Some((cursor + 4, register, bits));
    }
    for register in 0..4 {
        if let Some((end, pool)) = image.pool_load(cursor, register, true)
            && let Some(PoolEntry::Immediate(bits)) = image.pool_value(pool)
        {
            let first = image.word(cursor)?;
            if first & 0xffc003e0 == 0x91400360 && first & 31 != 17 {
                return None;
            }
            return Some((end, register as usize, bits as u64));
        }
    }
    None
}

fn frame_slot(word: u32, load: bool, register: usize) -> Option<i32> {
    let opcode = if load { 0xf8400000 } else { 0xf8000000 };
    (word & 0xffe00fff == opcode | (29 << 5) | register as u32)
        .then(|| signed_slot(word))
        .filter(|slot| *slot < 0 && slot % 8 == 0)
}

pub(super) fn construction(
    image: &Image<'_>,
    call: usize,
    limit: usize,
    factory: &ColorFactory,
    metadata: &Metadata,
) -> Option<Construction> {
    if call_target(image.word(call)?, call)? != factory.constructor {
        return None;
    }
    let mut cursor = call + 4;
    let mut register = 0;
    if let Some(word) = image.word(cursor)
        && word & 0xffffffe0 == 0xaa0003e0
    {
        register = (word & 31) as usize;
        if !(1..=3).contains(&register) {
            return None;
        }
        cursor += 4;
    }
    let mut spill = None;
    if let Some(slot) = image
        .word(cursor)
        .and_then(|word| frame_slot(word, false, register))
    {
        spill = Some(slot);
        cursor += 4;
    }
    let space_register = usize::from(register == 0);
    let first = image.word(cursor)?;
    if first & 0xffc003e0 == 0x91400360 && first & 31 == register as u32 {
        return None;
    }
    let (next, pool) = image.pool_load(cursor, space_register as u32, false)?;
    if image.pool_value(pool) != Some(PoolEntry::Tagged(metadata.color_space_reference)) {
        return None;
    }
    cursor = next;
    if let Some(slot) = image
        .word(cursor)
        .and_then(|word| frame_slot(word, false, register))
    {
        if spill.is_some() {
            return None;
        }
        spill = Some(slot);
        cursor += 4;
    }
    if image.word(cursor)? != 0xb8027000 | ((register as u32) << 5) | space_register as u32 {
        return None;
    }
    cursor += 4;
    let mut floating = [None; 4];
    let mut components = [None; 4];
    for _ in 0..32 {
        if cursor >= limit {
            return None;
        }
        let word = image.word(cursor)?;
        let base = ((word >> 5) & 31) as usize;
        if base == register && (word & 0xffe00c00 == 0xfc000000 || word & 0xffe00c1f == 0xf800001f)
        {
            let slot = signed_slot(word);
            if !(7..=31).contains(&slot) || (slot - 7) % 8 != 0 {
                return None;
            }
            let bits = if word & 0xffe00c1f == 0xf800001f {
                0
            } else {
                (*floating.get((word & 31) as usize)?)?
            };
            if !normalized(bits) {
                return None;
            }
            components[((slot - 7) / 8) as usize] = Some(bits);
            cursor += 4;
            if components.iter().all(Option::is_some) {
                return Some(Construction {
                    end: cursor,
                    register,
                    color: components.map(Option::unwrap),
                    spill,
                });
            }
        } else {
            let (end, destination, bits) = floating_load(image, cursor)?;
            if destination >= floating.len() || end > limit {
                return None;
            }
            floating[destination] = Some(bits);
            cursor = end;
        }
    }
    None
}

fn stack_check(image: &Image<'_>, cursor: usize, range: &std::ops::Range<usize>) -> Option<usize> {
    if image.word(cursor)? & 0xffc003ff != 0xf9400350
        || image.word(cursor + 4)? != 0xeb1001ff
        || image.word(cursor + 8)? & 0xff00001f != 0x54000009
    {
        return None;
    }
    let slow = relative(image.word(cursor + 8)?, 19, 5, cursor + 8)?;
    (range.contains(&slow) && slow > cursor + 12).then_some(cursor + 12)
}

pub(super) fn simple_color(
    image: &Image<'_>,
    entry: usize,
    bit4: bool,
    factory: &ColorFactory,
    metadata: &Metadata,
    depth: usize,
) -> Option<Color> {
    if depth > 3 {
        return None;
    }
    let range = image.range(entry)?;
    if range.start != entry || range.len() > 384 || image.local_frame(entry)? != 16 {
        return None;
    }
    let mut values = [Value::Unknown; 32];
    values[1] = Value::Receiver;
    let mut cursor = entry + 8;
    for _ in 0..128 {
        if !range.contains(&cursor) {
            return None;
        }
        let word = image.word(cursor)?;
        if word == RESTORE_STACK {
            if cursor + 12 <= range.end && image.words(cursor, &[RESTORE_STACK, POP_FRAME, RETURN])
            {
                return match values[0] {
                    Value::Color(color, _) => Some(color),
                    _ => None,
                };
            }
            return None;
        }
        if let Some(end) = stack_check(image, cursor, &range) {
            cursor = end;
            continue;
        }
        if let Some(target) = call_target(word, cursor) {
            if target == factory.constructor {
                let color = construction(image, cursor, range.end, factory, metadata)?;
                if color.spill.is_some() {
                    return None;
                }
                values.fill(Value::Unknown);
                values[color.register] = Value::Color(color.color, None);
                cursor = color.end;
            } else {
                if values[1] != Value::Receiver {
                    return None;
                }
                let color = simple_color(image, target, bit4, factory, metadata, depth + 1)?;
                values.fill(Value::Unknown);
                values[0] = Value::Color(color, Some(target));
                cursor += 4;
            }
            continue;
        }
        if let Some(target) = branch_target(word, cursor) {
            cursor = target;
            continue;
        }
        if word & 0x7e000000 == 0x36000000 {
            let bit = ((word >> 19) & 31) | ((word >> 26) & 32);
            let Value::Boolean(value) = values[(word & 31) as usize] else {
                return None;
            };
            if bit != 4 {
                return None;
            }
            cursor = if value == (word & (1 << 24) != 0) {
                relative(word, 14, 5, cursor)?
            } else {
                cursor + 4
            };
            continue;
        }
        if word & 0xffe0ffe0 == 0xaa0003e0 {
            values[(word & 31) as usize] = values[((word >> 16) & 31) as usize];
            cursor += 4;
            continue;
        }
        if word & 0xffe00c00 == 0xb8400000
            && signed_slot(word) == 7
            && values[((word >> 5) & 31) as usize] == Value::Receiver
        {
            values[(word & 31) as usize] = Value::Boolean(bit4);
            cursor += 4;
            continue;
        }
        if word & 0xffe0fc00 == 0x8b008000 && (word >> 16) & 31 == 28 {
            let source = values[((word >> 5) & 31) as usize];
            if !matches!(source, Value::Boolean(_)) {
                return None;
            }
            values[(word & 31) as usize] = source;
            cursor += 4;
            continue;
        }
        if let Some((end, _, _)) = floating_load(image, cursor) {
            cursor = end;
            continue;
        }
        let mut loaded = false;
        for register in 0..4 {
            if let Some((end, pool)) = image.pool_load(cursor, register, false)
                && let Some(PoolEntry::Tagged(reference)) = image.pool_value(pool)
            {
                let first = image.word(cursor)?;
                if first & 0xffc003e0 == 0x91400360 {
                    values[(first & 31) as usize] = Value::Unknown;
                }
                values[register as usize] = metadata
                    .colors
                    .get(&reference)
                    .copied()
                    .map_or(Value::Unknown, |color| Value::Color(color, None));
                cursor = end;
                loaded = true;
                break;
            }
        }
        if !loaded {
            return None;
        }
    }
    None
}

fn selection_tail(image: &Image<'_>, cursor: usize, end: usize) -> bool {
    let words = [
        0xf27c005f,
        0x9a9f07e1,
        0xd37ff821,
        0x93417c22,
        0x91000841,
        0x8b010810,
        0xb840f202,
        0x8b1c8042,
        0xaa0203e0,
        RESTORE_STACK,
        POP_FRAME,
        RETURN,
    ];
    cursor + words.len() * 4 <= end && image.words(cursor, &words)
}

fn array_allocator(image: &Image<'_>, entry: usize) -> bool {
    let Some(range) = image
        .range(entry)
        .filter(|range| range.start == entry && range.len() <= 512)
    else {
        return false;
    };
    let Some(first) = image
        .word(entry)
        .filter(|word| word & 0xfff8001f == 0x37000002)
    else {
        return false;
    };
    let Some(slow) = relative(first, 14, 5, entry) else {
        return false;
    };
    slow + 20 <= range.end
        && image.words(
            slow,
            &[PUSH_FRAME, SET_FRAME, 0xf81f8dff, 0xf81f8de2, 0xf81f8de1],
        )
        && (entry..slow.saturating_sub(4))
            .step_by(4)
            .any(|offset| image.words(offset, &[0xb8007001, 0xb800b002]))
}

fn list_selector(image: &Image<'_>, entry: usize) -> bool {
    let Some(range) = image
        .range(entry)
        .filter(|range| range.start == entry && range.len() <= 192)
    else {
        return false;
    };
    if !image.words(entry, &[PUSH_FRAME, SET_FRAME, 0xd10081ef, 0xf81f03a1]) {
        return false;
    }
    let Some(body) = stack_check(image, entry + 16, &range) else {
        return false;
    };
    let first = [
        0xf27c005f, 0x9a9f07e3, 0xd37ff863, 0xf81f83a3, 0xf85ff020, 0xd34c7c00, 0xf90001e1,
    ];
    if !image.words(body, &first) {
        return false;
    }
    let first_dispatch = body + first.len() * 4;
    if image
        .word(first_dispatch)
        .is_none_or(|word| word & 0xffc003ff != 0xd100001e)
        || !image.words(first_dispatch + 4, &[0xf87e7abe, 0xd63f03c0, 0x7100201f])
    {
        return false;
    }
    let condition = first_dispatch + 16;
    let Some(branch) = image
        .word(condition)
        .filter(|word| word & 0xff00001f == 0x54000001)
    else {
        return false;
    };
    let fallback = condition + 20;
    if relative(branch, 19, 5, condition) != Some(fallback)
        || !image.words(condition + 4, &[0xf85f83a0, 0x93417c01, 0x91000820])
        || image
            .word(condition + 16)
            .and_then(|word| branch_target(word, condition + 16))
            != Some(fallback + 12)
        || !image.words(
            fallback,
            &[
                0xf85f83a0, 0x93417c01, 0xaa0103e0, 0xf85f03a1, 0xd37ff802, 0xf85ff020, 0xd34c7c00,
                0xa90005e2,
            ],
        )
    {
        return false;
    }
    let last_dispatch = fallback + 32;
    last_dispatch + 24 <= range.end
        && image
            .word(last_dispatch)
            .is_some_and(|word| word & 0xffc003ff == 0xd100001e)
        && image.words(
            last_dispatch + 4,
            &[0xf87e7abe, 0xd63f03c0, RESTORE_STACK, POP_FRAME, RETURN],
        )
}

fn canonical_palette(image: &Image<'_>, entry: usize, metadata: &Metadata) -> Option<[Color; 4]> {
    let range = image.range(entry)?;
    if range.start != entry || range.len() > 256 || !image.words(entry, &[PUSH_FRAME, SET_FRAME]) {
        return None;
    }
    let frame = image.local_frame(entry)?;
    let call = if frame == 32
        && image.words(entry + 12, &[0xd2800100, 0xaa0103e3, 0xf81f83a1])
        && stack_check(image, entry + 24, &range) == Some(entry + 36)
        && image.words(entry + 36, &[0xaa0003e2, 0xaa1603e1])
    {
        entry + 44
    } else if frame == 24
        && image.words(
            entry + 12,
            &[0xaa0103e0, 0xf81f83a1, 0xaa1603e1, 0xd2800102],
        )
    {
        entry + 28
    } else {
        return None;
    };
    if !array_allocator(image, call_target(image.word(call)?, call)?) {
        return None;
    }
    let mut cursor = call + 4;
    let array_spill = image
        .word(cursor)
        .and_then(|word| frame_slot(word, false, 0));
    if array_spill.is_some() {
        cursor += 4;
    }
    let mut colors = [[0; 4]; 4];
    for (index, color) in colors.iter_mut().enumerate() {
        let first = image.word(cursor)?;
        if first & 0xffc003e0 == 0x91400360 && !matches!(first & 31, 16 | 17) {
            return None;
        }
        let (next, pool) = image.pool_load(cursor, 16, false)?;
        let PoolEntry::Tagged(reference) = image.pool_value(pool)? else {
            return None;
        };
        *color = *metadata.colors.get(&reference)?;
        if image.word(next)? != 0xb8000010 | (((15 + index * 4) as u32) << 12) {
            return None;
        }
        cursor = next + 4;
    }
    if array_spill.is_none() {
        return (image.words(cursor, &[0xf85f83a1, 0xb8407022, 0x8b1c8042])
            && selection_tail(image, cursor + 12, range.end))
        .then_some(colors);
    }
    let (next, pool) = image.pool_load(cursor, 1, false)?;
    if !matches!(image.pool_value(pool), Some(PoolEntry::Tagged(_)))
        || image
            .word(next)
            .and_then(|word| call_target(word, next))
            .is_none()
        || image.word(next + 4)? != 0xaa0003e1
        || image
            .word(next + 8)
            .and_then(|word| frame_slot(word, true, 0))
            != array_spill
        || !image.words(
            next + 12,
            &[
                0xb800f020, 0xd2800100, 0xb800b020, 0xf85f83a0, 0xb8407002, 0x8b1c8042,
            ],
        )
    {
        return None;
    }
    let select_call = next + 36;
    let selector = call_target(image.word(select_call)?, select_call)?;
    (select_call + 16 <= range.end
        && list_selector(image, selector)
        && image.words(select_call + 4, &[RESTORE_STACK, POP_FRAME, RETURN]))
    .then_some(colors)
}

fn fresh_palette(
    image: &Image<'_>,
    entry: usize,
    bit4: bool,
    factory: &ColorFactory,
    metadata: &Metadata,
) -> Option<[(Color, Option<usize>); 4]> {
    let range = image.range(entry)?;
    if range.start != entry || range.len() > 512 || image.local_frame(entry)? != 56 {
        return None;
    }
    let mut values = [Value::Unknown; 32];
    values[1] = Value::Receiver;
    let mut spills = BTreeMap::new();
    let mut cursor = entry + 12;
    for _ in 0..128 {
        if !range.contains(&cursor) {
            return None;
        }
        let word = image.word(cursor)?;
        if let Some(next) = stack_check(image, cursor, &range) {
            cursor = next;
            continue;
        }
        if let Some(target) = call_target(word, cursor) {
            if target == factory.constructor {
                let color = construction(image, cursor, range.end, factory, metadata)?;
                if let Some(slot) = color.spill {
                    spills.insert(slot, Value::Color(color.color, None));
                }
                values.fill(Value::Unknown);
                values[color.register] = Value::Color(color.color, None);
                cursor = color.end;
                continue;
            }
            if array_allocator(image, target) {
                if values[1] != Value::Null || values[2] != Value::Integer(8) {
                    return None;
                }
                let mut colors = [([0; 4], None); 4];
                cursor += 4;
                for (index, color) in colors.iter_mut().enumerate() {
                    let slot = frame_slot(image.word(cursor)?, true, 1)?;
                    let Value::Color(value, helper) = spills.get(&slot)? else {
                        return None;
                    };
                    *color = (*value, *helper);
                    if image.word(cursor + 4)? != 0xb8000001 | (((15 + index * 4) as u32) << 12) {
                        return None;
                    }
                    cursor += 8;
                }
                let receiver = frame_slot(image.word(cursor)?, true, 1)?;
                if spills.get(&receiver) != Some(&Value::Receiver)
                    || !image.words(cursor + 4, &[0xb8407022, 0x8b1c8042])
                    || !selection_tail(image, cursor + 12, range.end)
                {
                    return None;
                }
                return Some(colors);
            }
            if values[1] != Value::Receiver {
                return None;
            }
            let color = simple_color(image, target, bit4, factory, metadata, 0)?;
            values.fill(Value::Unknown);
            values[0] = Value::Color(color, Some(target));
            cursor += 4;
            continue;
        }
        if word & 0xffe0ffe0 == 0xaa0003e0 {
            let source = (word >> 16) & 31;
            values[(word & 31) as usize] = if source == 22 {
                Value::Null
            } else {
                values[source as usize]
            };
        } else if word & 0xffe00000 == 0xd2800000 {
            values[(word & 31) as usize] = Value::Integer(u64::from((word >> 5) & 0xffff));
        } else if let Some(slot) = frame_slot(word, false, (word & 31) as usize) {
            spills.insert(slot, values[(word & 31) as usize]);
        } else {
            let slot = frame_slot(word, true, (word & 31) as usize)?;
            values[(word & 31) as usize] = *spills.get(&slot)?;
        }
        cursor += 4;
    }
    None
}

pub(super) fn declared_palette(
    image: &Image<'_>,
    entry: usize,
    factory: &ColorFactory,
    metadata: &Metadata,
) -> Option<[[u64; 4]; 4]> {
    if let Some(colors) = canonical_palette(image, entry, metadata) {
        return Some(colors);
    }
    let light = fresh_palette(image, entry, false, factory, metadata)?;
    let dark = fresh_palette(image, entry, true, factory, metadata)?;
    (light[..2] == dark[..2]).then_some([light[0].0, light[1].0, light[2].0, dark[3].0])
}

pub(super) fn selected_helpers(
    image: &Image<'_>,
    entry: usize,
    factory: &ColorFactory,
    metadata: &Metadata,
) -> Vec<usize> {
    let Some(light) = fresh_palette(image, entry, false, factory, metadata) else {
        return Vec::new();
    };
    let Some(dark) = fresh_palette(image, entry, true, factory, metadata) else {
        return Vec::new();
    };
    if light[..2] != dark[..2] {
        return Vec::new();
    }
    let mut result = Vec::new();
    for helper in [light[2].1, dark[3].1].into_iter().flatten() {
        if result.contains(&helper)
            || simple_color(image, helper, false, factory, metadata, 0) != Some(light[2].0)
            || simple_color(image, helper, true, factory, metadata, 0) != Some(dark[3].0)
        {
            continue;
        }
        result.push(helper);
    }
    result.sort_unstable();
    result
}
