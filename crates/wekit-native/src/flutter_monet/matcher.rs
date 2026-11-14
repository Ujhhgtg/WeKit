use std::collections::{BTreeMap, HashMap, VecDeque};
use std::ops::Range;

use anyhow::{Result, bail, ensure};

use super::snapshot::{PoolEntry, Role, SnapshotInfo};
use super::stackmap;

mod brand;
mod derived;
mod family;
mod headroom;
mod palette_matcher;

const RETURN: u32 = 0xd65f03c0;
const PUSH_FRAME: u32 = 0xa9bf79fd;
const SET_FRAME: u32 = 0xaa0f03fd;
const RESTORE_STACK: u32 = 0xaa1d03ef;
const POP_FRAME: u32 = 0xa8c179fd;

#[derive(Clone, Debug)]
pub struct ColorFactory {
    pub allocation_call: usize,
    pub class_id: u32,
    pub frame_bytes: usize,
    pub constructor: usize,
}

#[derive(Clone, Copy, Debug)]
pub enum TargetKind {
    Return,
    FreshReturn {
        color_register: u8,
    },
    FreshBranch {
        color_register: u8,
        destination: usize,
    },
    FreshInstruction {
        color_register: u8,
        preserve_scratch: bool,
    },
}

#[derive(Clone, Debug)]
pub struct Target {
    pub offset: usize,
    pub role: Role,
    pub original: u32,
    pub kind: TargetKind,
}

pub struct Resolution {
    pub factory: ColorFactory,
    pub targets: Vec<Target>,
}

struct Image<'a> {
    bytes: &'a [u8],
    snapshot: &'a SnapshotInfo,
}

impl Image<'_> {
    fn word(&self, offset: usize) -> Option<u32> {
        Some(u32::from_le_bytes(
            self.bytes
                .get(offset..offset.checked_add(4)?)?
                .try_into()
                .ok()?,
        ))
    }

    fn range(&self, offset: usize) -> Option<Range<usize>> {
        let index = self
            .snapshot
            .ranges
            .partition_point(|range| range.start <= offset);
        let range = self.snapshot.ranges.get(index.checked_sub(1)?)?;
        range.contains(&offset).then(|| range.clone())
    }

    fn words(&self, offset: usize, values: &[u32]) -> bool {
        values
            .iter()
            .enumerate()
            .all(|(index, expected)| self.word(offset + index * 4) == Some(*expected))
    }

    fn pool_load(&self, offset: usize, register: u32, floating: bool) -> Option<(usize, usize)> {
        let mut cursor = offset;
        let first = self.word(cursor)?;
        let mut base_register = 27;
        let mut displacement = 0;
        if first & 0xffc003e0 == 0x91400360 {
            base_register = first & 31;
            displacement = ((first >> 10) & 0xfff) as usize * 4096;
            cursor += 4;
        }
        let load = self.word(cursor)?;
        let opcode = if floating { 0xfd400000 } else { 0xf9400000 };
        if load & 0xffc003ff != opcode | (base_register << 5) | register {
            return None;
        }
        displacement += ((load >> 10) & 0xfff) as usize * 8;
        Some((cursor + 4, displacement))
    }

    fn pool_value(&self, displacement: usize) -> Option<PoolEntry> {
        if displacement < 16 || !displacement.is_multiple_of(8) {
            return None;
        }
        self.snapshot.pool.get((displacement - 16) / 8).copied()
    }

    fn frame_size(&self, start: usize) -> Option<usize> {
        let range = self.range(start)?;
        if range.start != start {
            return None;
        }
        if !self.words(start, &[PUSH_FRAME, SET_FRAME]) {
            return None;
        }
        let third = self.word(start + 8)?;
        let locals = if third & 0xffc003ff == 0xd10001ef {
            ((third >> 10) & 0xfff) as usize
        } else {
            0
        };
        for offset in (start + 8..range.end.min(start + 48)).step_by(4) {
            let word = self.word(offset)?;
            if word & 0xffc003ff == 0xf9400350 {
                if offset + 12 > range.end
                    || self.word(offset + 4) != Some(0xeb1001ff)
                    || self.word(offset + 8)? & 0xff00001f != 0x54000009
                {
                    return None;
                }
                let slow = relative(self.word(offset + 8)?, 19, 5, offset + 8)?;
                return range.contains(&slow).then_some(16 + locals);
            }
            if call_target(word, offset).is_some()
                || branch_target(word, offset).is_some()
                || word & 0xff000010 == 0x54000000
                || word & 0x7e000000 == 0x34000000
                || word & 0x7e000000 == 0x36000000
                || word & 0xfe000000 == 0xd6000000
            {
                return None;
            }
        }
        None
    }

    fn local_frame(&self, start: usize) -> Option<usize> {
        if !self.words(start, &[PUSH_FRAME, SET_FRAME]) {
            return None;
        }
        let third = self.word(start + 8)?;
        Some(
            16 + if third & 0xffc003ff == 0xd10001ef {
                ((third >> 10) & 0xfff) as usize
            } else {
                0
            },
        )
    }
}

fn relative(word: u32, bits: u32, shift: u32, address: usize) -> Option<usize> {
    let value = ((word >> shift) & ((1 << bits) - 1)) as i32;
    let signed = (value << (32 - bits)) >> (32 - bits);
    address.checked_add_signed(signed as isize * 4)
}

fn call_target(word: u32, address: usize) -> Option<usize> {
    (word & 0xfc000000 == 0x94000000)
        .then(|| relative(word, 26, 0, address))
        .flatten()
}

fn branch_target(word: u32, address: usize) -> Option<usize> {
    (word & 0xfc000000 == 0x14000000)
        .then(|| relative(word, 26, 0, address))
        .flatten()
}

fn allocation_class(image: &Image<'_>, address: usize) -> Option<(u32, usize)> {
    let first = image.word(address)?;
    let second = image.word(address + 4)?;
    if first & 0xffe0001f != 0xd2800002 || second & 0xffe0001f != 0xf2a00002 {
        return None;
    }
    let tags = ((first >> 5) & 0xffff) | (((second >> 5) & 0xffff) << 16);
    let size = ((tags >> 8) & 15) as usize * 16;
    if size == 0 || tags & 0xff != 0x1c {
        return None;
    }
    let target = branch_target(image.word(address + 8)?, address + 8)?;
    if !image.words(
        target,
        &[
            0xd3482c44, 0xd37cec84, 0xa9451740, 0x8b040003, 0xeb0300bf, 0x54000169, 0xf9002b43,
            0xf9000002, 0x91002004, 0x92407ed0, 0xaa108210, 0xa8814090, 0xeb03009f, 0x54ffffc3,
            0x91000400, RETURN, 0xaa1603e1, 0xf9412b43, 0xd61f0060,
        ],
    ) {
        return None;
    }
    Some((tags >> 12, size))
}

fn constructor_class(image: &Image<'_>, address: usize) -> Option<u32> {
    allocation_class(image, address).and_then(|(class_id, size)| (size == 48).then_some(class_id))
}

fn factory_suffix(image: &Image<'_>, allocation_call: usize) -> Option<usize> {
    let (mut cursor, colorspace) = image.pool_load(allocation_call + 4, 1, false)?;
    if !matches!(image.pool_value(colorspace), Some(PoolEntry::Tagged(_))) {
        return None;
    }
    let first = [
        0xb8027001, 0xfc5e03a0, 0xfc007000, 0xf85f03a1, 0xd3407c21, 0x12001c22, 0xd3407c42,
        0x9e620040,
    ];
    if !image.words(cursor, &first) {
        return None;
    }
    cursor += first.len() * 4;
    let (next, denominator) = image.pool_load(cursor, 1, true)?;
    if image.pool_value(denominator) != Some(PoolEntry::Immediate(255_f64.to_bits() as i64)) {
        return None;
    }
    cursor = next;
    let rest = [
        0x1e611802,
        0xfc00f002,
        0xf85f83a1,
        0xd3407c21,
        0x12001c22,
        0xd3407c42,
        0x9e620040,
        0x1e611802,
        0xfc017002,
        0xf85e83a1,
        0xd3407c21,
        0x12001c22,
        0xd3407c42,
        0x9e620040,
        0x1e611802,
        0xfc01f002,
        RESTORE_STACK,
        POP_FRAME,
        RETURN,
    ];
    image
        .words(cursor, &rest)
        .then_some(cursor + rest.len() * 4)
}

fn find_factory(image: &Image<'_>, data: &[u8]) -> Result<ColorFactory> {
    let mut candidates = Vec::new();
    let mut frames = 0;
    let mut constructors = 0;
    let mut continuations = 0;
    for range in &image.snapshot.ranges {
        if !(160..=512).contains(&range.len())
            || !image.words(range.start, &[PUSH_FRAME, SET_FRAME, 0xd10081ef])
        {
            continue;
        }
        frames += 1;
        for offset in (range.start + 12..range.end).step_by(4) {
            let Some(constructor) = image
                .word(offset)
                .and_then(|word| call_target(word, offset))
            else {
                continue;
            };
            let Some(class_id) = constructor_class(image, constructor) else {
                continue;
            };
            constructors += 1;
            let Some(end) = factory_suffix(image, offset) else {
                continue;
            };
            continuations += 1;
            if end > range.end
                || !stackmap::has_unboxed_frame(
                    data,
                    &image.snapshot.instruction_table,
                    range.start,
                    offset + 4,
                    4,
                )?
            {
                continue;
            }
            candidates.push(ColorFactory {
                allocation_call: offset,
                class_id,
                frame_bytes: 32,
                constructor,
            });
        }
    }
    ensure!(
        !candidates.is_empty(),
        "no Color allocation continuation with verified scalar stack map ({frames} frames, {constructors} constructors, {continuations} continuations)"
    );
    let class_id = candidates[0].class_id;
    ensure!(
        candidates
            .iter()
            .all(|factory| factory.class_id == class_id),
        "ambiguous Color allocation classes"
    );
    Ok(candidates.remove(0))
}

type GetterExits = Vec<(usize, bool, usize)>;
type GetterMemo = HashMap<usize, Option<GetterExits>>;

fn signed_slot(word: u32) -> i32 {
    ((word >> 12) as i32 & 0x1ff) << 23 >> 23
}

fn fresh_color_case(image: &Image<'_>, call: usize, end: usize) -> bool {
    if image.word(call + 4) != Some(0xaa0003e1) {
        return false;
    }
    let start = call + 8;
    if image
        .word(start)
        .is_some_and(|word| word & 0xffc003e0 == 0x91400360 && word & 31 == 1)
    {
        return false;
    }
    let Some((mut cursor, colorspace)) = image.pool_load(start, 0, false) else {
        return false;
    };
    if !matches!(image.pool_value(colorspace), Some(PoolEntry::Tagged(_)))
        || image.word(cursor) != Some(0xb8027020)
    {
        return false;
    }
    cursor += 4;
    let mut fields = 0_u8;
    while cursor < end {
        let Some(word) = image.word(cursor) else {
            return false;
        };
        if word & 0xffe00fe0 == 0xfc000020 && word & 31 < 4 || word & 0xffe00fff == 0xf800003f {
            let slot = signed_slot(word);
            if !(7..=31).contains(&slot) || (slot - 7) % 8 != 0 {
                return false;
            }
            fields |= 1 << ((slot - 7) / 8);
            cursor += 4;
        } else if word & 0xffe01fe0 == 0x1e601000 && word & 31 < 4 {
            cursor += 4;
        } else {
            if word & 0xffc003e0 == 0x91400360 && word & 31 != 17 {
                return false;
            }
            let Some(next) = (0..4).find_map(|register| {
                image
                    .pool_load(cursor, register, true)
                    .map(|(next, _)| next)
            }) else {
                return false;
            };
            cursor = next;
        }
    }
    cursor == end && fields == 15
}

fn fresh_initializer(image: &Image<'_>, call: usize, end: usize, factory: &ColorFactory) -> bool {
    if image.word(call).and_then(|word| call_target(word, call)) != Some(factory.constructor)
        || end <= call + 4
    {
        return false;
    }
    if image.word(call + 4) == Some(0xaa0003e1) {
        return image.word(end - 4) == Some(0xaa0103e0) && fresh_color_case(image, call, end - 4);
    }
    if image
        .word(call + 4)
        .is_some_and(|word| word & 0xffc003e0 == 0x91400360 && word & 31 == 0)
    {
        return false;
    }
    let Some((mut cursor, colorspace)) = image.pool_load(call + 4, 1, false) else {
        return false;
    };
    if !matches!(image.pool_value(colorspace), Some(PoolEntry::Tagged(_)))
        || image.word(cursor) != Some(0xb8027001)
    {
        return false;
    }
    cursor += 4;
    let mut fields = 0_u8;
    while cursor < end {
        let Some(word) = image.word(cursor) else {
            return false;
        };
        if word & 0xffe00fe0 == 0xfc000000 && word & 31 < 4 || word & 0xffe00fff == 0xf800001f {
            let slot = signed_slot(word);
            if !(7..=31).contains(&slot) || (slot - 7) % 8 != 0 {
                return false;
            }
            fields |= 1 << ((slot - 7) / 8);
            cursor += 4;
        } else if word & 0xffe01fe0 == 0x1e601000 && word & 31 < 4 {
            cursor += 4;
        } else {
            if word & 0xffc003e0 == 0x91400360 && word & 31 != 17 {
                return false;
            }
            let Some(next) = (0..4).find_map(|register| {
                image
                    .pool_load(cursor, register, true)
                    .map(|(next, _)| next)
            }) else {
                return false;
            };
            cursor = next;
        }
    }
    cursor == end && fields == 15
}

fn fresh_getter(image: &Image<'_>, entry: usize, factory: &ColorFactory, depth: usize) -> bool {
    if depth > 3 {
        return false;
    }
    let Some(range) = image
        .range(entry)
        .filter(|range| range.start == entry && range.len() <= 160)
    else {
        return false;
    };
    if image.local_frame(entry) != Some(16) {
        return false;
    }
    let mut cursor = entry + 8;
    if image.frame_size(entry).is_some() {
        cursor += 12;
    }
    if image
        .word(cursor)
        .and_then(|word| call_target(word, cursor))
        == Some(factory.constructor)
    {
        return (cursor + 4..range.end.saturating_sub(8))
            .step_by(4)
            .find(|&offset| image.words(offset, &[RESTORE_STACK, POP_FRAME, RETURN]))
            .is_some_and(|epilogue| fresh_initializer(image, cursor, epilogue, factory));
    }
    if !image.words(cursor, &[0xb8407020, 0x8b1c8000]) {
        return false;
    }
    cursor += 8;
    let Some(condition) = image
        .word(cursor)
        .filter(|word| word & 0xfff8001f == 0x37200000)
    else {
        return false;
    };
    let Some(second) = relative(condition, 14, 5, cursor) else {
        return false;
    };
    let first = cursor + 4;
    let Some(first_callee) = image.word(first).and_then(|word| call_target(word, first)) else {
        return false;
    };
    if first_callee == factory.constructor {
        if second <= first + 4 || !range.contains(&second) {
            return false;
        }
        let Some(join) = image
            .word(second - 4)
            .and_then(|word| branch_target(word, second - 4))
        else {
            return false;
        };
        if join <= second
            || join + 12 > range.end
            || !image.words(join, &[RESTORE_STACK, POP_FRAME, RETURN])
            || !fresh_initializer(image, first, second - 4, factory)
        {
            return false;
        }
        let mut second_call = second;
        if image
            .word(second_call)
            .is_some_and(|word| word & 0xffe01fe0 == 0x1e601000 && word & 31 < 4)
        {
            second_call += 4;
        }
        if let Some((next, pool)) = image.pool_load(second_call, 0, false) {
            if !matches!(image.pool_value(pool), Some(PoolEntry::Tagged(_))) {
                return false;
            }
            second_call = next;
        }
        return second_call < join && fresh_initializer(image, second_call, join, factory);
    }
    let Some(second_callee) = image
        .word(second)
        .and_then(|word| call_target(word, second))
    else {
        return false;
    };
    let Some(join) = image
        .word(first + 4)
        .and_then(|word| branch_target(word, first + 4))
    else {
        return false;
    };
    second == first + 8
        && join == second + 4
        && join + 12 <= range.end
        && image.words(join, &[RESTORE_STACK, POP_FRAME, RETURN])
        && fresh_getter(image, first_callee, factory, depth + 1)
        && fresh_getter(image, second_callee, factory, depth + 1)
}

fn analyze_getter(
    image: &Image<'_>,
    entry: usize,
    factory: &ColorFactory,
    _depth: usize,
    memo: &mut GetterMemo,
) -> Option<GetterExits> {
    if let Some(result) = memo.get(&entry) {
        return result.clone();
    }
    memo.insert(entry, None);
    let range = image.range(entry)?;
    if range.start != entry || range.len() > 1536 {
        return None;
    }
    let frame = image.local_frame(entry)?;
    let mut states = HashMap::<usize, usize>::new();
    let mut queue = VecDeque::new();
    let body = entry + if frame > 16 { 12 } else { 8 };
    states.insert(body, image.frame_size(entry).unwrap_or(0));
    queue.push_back(body);
    let mut returns = BTreeMap::<usize, (bool, usize)>::new();
    let mut steps = 0;
    while let Some(offset) = queue.pop_front() {
        steps += 1;
        if steps > range.len() * 32 {
            return None;
        }
        let mut headroom = *states.get(&offset)?;
        let word = image.word(offset)?;
        if word == RESTORE_STACK {
            if offset + 12 > range.end || !image.words(offset, &[RESTORE_STACK, POP_FRAME, RETURN])
            {
                return None;
            }
            let current = (fresh_getter(image, entry, factory, 0), headroom);
            returns
                .entry(offset + 8)
                .and_modify(|previous| {
                    previous.0 &= current.0;
                    previous.1 = previous.1.min(current.1);
                })
                .or_insert(current);
            continue;
        }
        let mut successors = vec![offset + 4];
        if let Some(target) = call_target(word, offset) {
            let child_frame = image.frame_size(target).unwrap_or(0);
            if child_frame != 0 {
                headroom = headroom.max(frame + child_frame);
            }
        } else if let Some(target) = branch_target(word, offset) {
            successors = vec![target];
        } else if word & 0xff000010 == 0x54000000 || word & 0x7e000000 == 0x34000000 {
            successors.push(relative(word, 19, 5, offset)?);
        } else if word & 0x7e000000 == 0x36000000 {
            successors.push(relative(word, 14, 5, offset)?);
        } else if word & 0xfffffc1f == 0xd63f0000 {
        } else if word & 0xfffffc1f == 0xd61f0000
            || word == RETURN
            || word == POP_FRAME
            || word == PUSH_FRAME
            || word == SET_FRAME
        {
            return None;
        } else if word & 0x1e000000 == 0x0a000000
            || word & 0x1c000000 == 0x10000000
            || word & 0x1f000000 == 0x1a000000
        {
            if matches!(word & 31, 15 | 29 | 30) {
                return None;
            }
        } else if word == 0xd503201f
            || word & 0xffe01fe0 == 0x1e601000
            || word & 0xffe0fc00 == 0x1e601800
            || word & 0xffe0fc1f == 0x1e602000
            || word & 0xffe00c00 == 0xf8000000
            || word & 0xffe00c00 == 0xf8400000
            || word & 0xffe00c00 == 0xb8000000
            || word & 0xffe00c00 == 0xb8400000
            || word & 0xffe00c00 == 0xfc000000
            || word & 0xffe00c00 == 0xfc400000
            || word & 0xffc00000 == 0xfd000000
            || word & 0xffc00000 == 0xfd400000
            || word & 0xffc00000 == 0xa9000000
            || word & 0xffc00000 == 0xf9000000
            || word & 0xffc00000 == 0xf9400000
        {
            if word & (1 << 22) != 0 && word & (1 << 26) == 0 && matches!(word & 31, 15 | 29) {
                return None;
            }
        } else {
            return None;
        }
        for successor in successors {
            if !range.contains(&successor) || successor < body || successor % 4 != 0 {
                return None;
            }
            if let Some(existing) = states.get_mut(&successor) {
                if headroom < *existing {
                    *existing = headroom;
                    queue.push_back(successor);
                }
            } else {
                states.insert(successor, headroom);
                queue.push_back(successor);
            }
        }
    }
    if returns.is_empty() {
        return None;
    }
    let result: Vec<_> = returns
        .into_iter()
        .map(|(offset, (fresh, headroom))| (offset, fresh, headroom))
        .collect();
    memo.insert(entry, Some(result.clone()));
    Some(result)
}

pub fn resolve(instructions: &[u8], data: &[u8], snapshot: &SnapshotInfo) -> Result<Resolution> {
    ensure!(
        !snapshot.roles.is_empty(),
        "snapshot contains no pooled semantic color roles"
    );
    let image = Image {
        bytes: instructions,
        snapshot,
    };
    let factory = find_factory(&image, data)?;
    let roles: HashMap<usize, Role> = snapshot
        .roles
        .iter()
        .map(|entry| (entry.pp_offset(), entry.role))
        .collect();
    let mut candidates: BTreeMap<usize, Vec<Role>> = BTreeMap::new();
    let mut inline_targets = Vec::new();
    for range in &snapshot.ranges {
        if range.len() < 48 {
            continue;
        }
        for offset in (range.start..range.end.saturating_sub(16)).step_by(4) {
            let Some((after_load, pool_offset)) = image.pool_load(offset, 16, false) else {
                continue;
            };
            let Some(role) = roles.get(&pool_offset).copied() else {
                continue;
            };
            let mut cursor = after_load;
            if image
                .word(cursor)
                .is_some_and(|word| word & 0xffe00c1f == 0xf840001e)
            {
                cursor += 4;
            }
            if !image
                .word(cursor)
                .is_some_and(|word| word & 0xffc003e0 == 0xa90001e0 && (word >> 10) & 31 == 16)
            {
                continue;
            }
            cursor += 4;
            if image
                .word(cursor)
                .and_then(|word| call_target(word, cursor))
                .is_none()
            {
                continue;
            }
            cursor += 4;
            let Some(condition) = image.word(cursor) else {
                continue;
            };
            if condition & 0xfff8001f != 0x37200000 {
                continue;
            }
            let Some(case_end) = relative(condition, 14, 5, cursor) else {
                continue;
            };
            cursor += 4;
            if case_end <= cursor || case_end > range.end || case_end - cursor > 96 {
                continue;
            }
            let calls: Vec<_> = (cursor..case_end)
                .step_by(4)
                .filter_map(|site| {
                    image
                        .word(site)
                        .and_then(|word| call_target(word, site))
                        .map(|target| (site, target))
                })
                .collect();
            if calls.len() != 1 {
                continue;
            }
            let (call, target) = calls[0];
            if target == factory.constructor {
                if role != Role::Brand || !image.words(call + 4, &[0xaa0003e1]) {
                    continue;
                }
                let Some(last) = case_end.checked_sub(4) else {
                    continue;
                };
                let Some(original) = image.word(last) else {
                    continue;
                };
                let Some(destination) = branch_target(original, last) else {
                    continue;
                };
                if !range.contains(&destination) || !image.words(destination, &[0x6b16003f]) {
                    continue;
                }
                if !fresh_color_case(&image, call, last) {
                    continue;
                }
                inline_targets.push(Target {
                    offset: last,
                    role,
                    original,
                    kind: TargetKind::FreshBranch {
                        color_register: 1,
                        destination,
                    },
                });
            } else if image
                .range(target)
                .is_some_and(|target_range| target_range.start == target)
            {
                let Some((after_pool, _)) = image.pool_load(call + 8, 0, false) else {
                    if image
                        .word(call + 4)
                        .and_then(|word| branch_target(word, call + 4))
                        .is_some()
                    {
                        candidates.entry(target).or_default().push(role);
                    }
                    continue;
                };
                if image.word(call + 4) != Some(0xaa0003e1)
                    || after_pool + 4 != case_end
                    || image
                        .word(after_pool)
                        .and_then(|word| branch_target(word, after_pool))
                        .is_none()
                {
                    continue;
                }
                candidates.entry(target).or_default().push(role);
            }
        }
    }
    let expansion = family::related_getters(&image, data, &factory, &candidates, &inline_targets)?;
    let mut targets = inline_targets;
    targets.extend(expansion.targets);
    for (entry, role) in expansion.getters {
        candidates.entry(entry).or_default().push(role);
    }
    let mut memo = HashMap::new();
    for (entry, assigned) in candidates {
        let role = assigned[0];
        ensure!(
            assigned.iter().all(|candidate| *candidate == role),
            "physical getter aliases conflicting semantic roles at {entry:#x}"
        );
        let Some(exits) = analyze_getter(&image, entry, &factory, 0, &mut memo) else {
            continue;
        };
        if exits
            .iter()
            .any(|exit| !exit.1 && exit.2 < factory.frame_bytes + 16)
        {
            continue;
        }
        for (offset, fresh, _) in exits {
            targets.push(Target {
                offset,
                role,
                original: RETURN,
                kind: if fresh {
                    TargetKind::FreshReturn { color_register: 0 }
                } else {
                    TargetKind::Return
                },
            });
        }
    }
    targets.sort_by_key(|target| target.offset);
    targets.dedup_by(|right, left| right.offset == left.offset && right.role == left.role);
    for pair in targets.windows(2) {
        ensure!(
            pair[0].offset != pair[1].offset,
            "conflicting semantic return patches"
        );
    }
    targets.retain_mut(|target| {
        let Some(range) = image.range(target.offset) else {
            return false;
        };
        match &mut target.kind {
            TargetKind::FreshBranch { .. } => {
                headroom::allows_scratch_save(&image, range.start, target.offset)
            }
            TargetKind::FreshInstruction {
                preserve_scratch, ..
            } => {
                let requires_save = target.original & 0xffc0_03ff == 0x9140_0370
                    || target.original & 0xffc0_03ff == 0xf940_0370;
                *preserve_scratch =
                    headroom::allows_scratch_save(&image, range.start, target.offset);
                *preserve_scratch
                    || (!requires_save
                        && derived::scratch_dead_before_read(&image, target.offset + 4, range.end))
            }
            _ => true,
        }
    });
    if targets.is_empty() {
        bail!("no semantic color getter passes ABI and allocation checks");
    }
    ensure!(
        targets.iter().any(|target| target.role == Role::Bg0),
        "no verified BG_0 background getter"
    );
    ensure!(
        targets
            .iter()
            .any(|target| matches!(target.role, Role::Fg0 | Role::Fg1)),
        "no verified foreground getter"
    );
    Ok(Resolution { factory, targets })
}
