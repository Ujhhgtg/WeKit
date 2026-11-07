use std::collections::{HashMap, HashSet, VecDeque};

use super::{
    Image, POP_FRAME, PUSH_FRAME, RESTORE_STACK, RETURN, SET_FRAME, branch_target, call_target,
    relative, signed_slot,
};

fn frame_register(register: u32) -> bool {
    matches!(register, 15 | 29)
}

fn stack_adjustment(word: u32) -> Option<i32> {
    if matches!(word, PUSH_FRAME | POP_FRAME | SET_FRAME | RESTORE_STACK) {
        return None;
    }
    if matches!(word & 0xffc003ff, 0xd10001ef | 0x910001ef) {
        let amount = ((word >> 10) & 0xfff) as i32;
        return Some(if word & (1 << 30) != 0 {
            amount
        } else {
            -amount
        });
    }
    if word & 0x3a000000 == 0x28000000 {
        let floating = word & (1 << 26) != 0;
        let size = (word >> 30) & 3;
        if size == 3
            || (!floating && size == 1 && word & (1 << 22) == 0)
            || (!floating
                && word & (1 << 22) != 0
                && (frame_register(word & 31) || frame_register((word >> 10) & 31)))
        {
            return None;
        }
        let mode = (word >> 23) & 3;
        if matches!(mode, 1 | 3) {
            let base = (word >> 5) & 31;
            if base == 29 {
                return None;
            }
            if base == 15 {
                if !floating && (word & 31 == 15 || (word >> 10) & 31 == 15) {
                    return None;
                }
                let displacement = (((word >> 15) & 127) as i32) << 25 >> 25;
                let scale = if floating {
                    4 << size
                } else {
                    4 << (size >> 1)
                };
                return Some(-displacement * scale);
            }
        }
        return Some(0);
    }
    if word & 0x3b200000 == 0x38000000 || word & 0x3b000000 == 0x39000000 {
        let floating = word & (1 << 26) != 0;
        if !floating && (word >> 22) & 3 != 0 && frame_register(word & 31) {
            return None;
        }
        if word & 0x3b200000 == 0x38000000 && matches!((word >> 10) & 3, 1 | 3) {
            let base = (word >> 5) & 31;
            if base == 29 {
                return None;
            }
            if base == 15 {
                if !floating && word & 31 == 15 {
                    return None;
                }
                return Some(-signed_slot(word));
            }
        }
        return Some(0);
    }
    if word & 0x3b000000 == 0x18000000 {
        return (word & (1 << 26) != 0 || !frame_register(word & 31)).then_some(0);
    }
    if word & 0x1e000000 == 0x0a000000
        || word & 0x1c000000 == 0x10000000
        || matches!(word & 0x1f000000, 0x1a000000 | 0x1b000000)
        || matches!(word & 0x1e000000, 0x0e000000 | 0x1e000000)
    {
        return (!frame_register(word & 31)).then_some(0);
    }
    (word == 0xd503201f).then_some(0)
}

fn checked_child_frame(image: &Image<'_>, entry: usize) -> Option<usize> {
    let frame = image.frame_size(entry)?;
    if frame > 4096 || !frame.is_multiple_of(8) {
        return None;
    }
    let body = entry + if frame > 16 { 12 } else { 8 };
    let range = image.range(entry)?;
    for offset in (body..range.end.min(entry + 48)).step_by(4) {
        let word = image.word(offset)?;
        if word & 0xffc003ff == 0xf9400350 {
            return Some(frame);
        }
        if stack_adjustment(word)? != 0 {
            return None;
        }
    }
    None
}

fn no_return_stub(image: &Image<'_>, entry: usize) -> bool {
    let Some(range) = image
        .range(entry)
        .filter(|range| range.start == entry && range.len() <= 256 && range.len() >= 12)
    else {
        return false;
    };
    if image.word(range.end - 4) != Some(0xd4200000) {
        return false;
    }
    for offset in (entry..range.end - 4).step_by(4) {
        let Some(word) = image.word(offset) else {
            return false;
        };
        if call_target(word, offset).is_some() || word & 0xfffffc1f == 0xd63f0000 {
            continue;
        }
        if branch_target(word, offset).is_some()
            || word & 0xff000010 == 0x54000000
            || word & 0x7e000000 == 0x34000000
            || word & 0x7e000000 == 0x36000000
            || word & 0xfe000000 == 0xd6000000
            || (word & 0x1c000000 == 0x14000000 && word != 0xd503201f)
        {
            return false;
        }
    }
    true
}

fn prove(image: &Image<'_>, entry: usize, site: usize) -> Option<bool> {
    let range = image.range(entry)?;
    if range.start != entry
        || range.len() > 8192
        || !site.is_multiple_of(4)
        || !range.contains(&site)
    {
        return None;
    }
    let frame = image.local_frame(entry)?;
    if frame > 4096 || !frame.is_multiple_of(8) {
        return None;
    }
    let body = entry + if frame > 16 { 12 } else { 8 };
    if site < body {
        return None;
    }
    let mut edges = HashMap::<usize, Vec<usize>>::new();
    let mut predecessors = HashMap::<usize, Vec<usize>>::new();
    let mut reachable = HashSet::new();
    let mut pending = VecDeque::from([body]);
    while let Some(offset) = pending.pop_front() {
        if !reachable.insert(offset) || offset == site {
            continue;
        }
        let word = image.word(offset)?;
        if word == RETURN || word & 0xffe0001f == 0xd4200000 {
            continue;
        }
        let successors = if let Some(target) = call_target(word, offset) {
            if no_return_stub(image, target) {
                continue;
            }
            vec![offset + 4]
        } else if let Some(target) = branch_target(word, offset) {
            vec![target]
        } else if word & 0xff000010 == 0x54000000 || word & 0x7e000000 == 0x34000000 {
            vec![offset + 4, relative(word, 19, 5, offset)?]
        } else if word & 0x7e000000 == 0x36000000 {
            vec![offset + 4, relative(word, 14, 5, offset)?]
        } else if word & 0xfffffc1f == 0xd63f0000 {
            vec![offset + 4]
        } else {
            if word & 0xfe000000 == 0xd6000000
                || (word & 0x1c000000 == 0x14000000 && word != 0xd503201f)
            {
                return None;
            }
            vec![offset + 4]
        };
        for &successor in &successors {
            if successor < body || !range.contains(&successor) || !successor.is_multiple_of(4) {
                return None;
            }
            predecessors.entry(successor).or_default().push(offset);
            pending.push_back(successor);
        }
        edges.insert(offset, successors);
    }
    if !reachable.contains(&site) {
        return None;
    }
    let mut relevant = HashSet::new();
    let mut pending = VecDeque::from([site]);
    while let Some(offset) = pending.pop_front() {
        if relevant.insert(offset)
            && let Some(previous) = predecessors.get(&offset)
        {
            pending.extend(previous);
        }
    }
    let mut states = HashMap::from([(body, (0_i32, 0_usize))]);
    let mut pending = VecDeque::from([body]);
    let mut iterations = 0;
    while let Some(offset) = pending.pop_front() {
        iterations += 1;
        if iterations > range.len() * 16 {
            return None;
        }
        let (mut extra, mut checked) = *states.get(&offset)?;
        if offset == site {
            continue;
        }
        let word = image.word(offset)?;
        if let Some(target) = call_target(word, offset) {
            if let Some(child_frame) = checked_child_frame(image, target) {
                checked = checked.max(frame + extra as usize + child_frame);
            }
        } else if branch_target(word, offset).is_none()
            && word & 0xff000010 != 0x54000000
            && word & 0x7e000000 != 0x34000000
            && word & 0x7e000000 != 0x36000000
            && word & 0xfffffc1f != 0xd63f0000
        {
            extra = extra.checked_add(stack_adjustment(word)?)?;
            if !(0..=4096).contains(&extra) {
                return None;
            }
        }
        for &successor in edges.get(&offset)? {
            if !relevant.contains(&successor) {
                continue;
            }
            if let Some(previous) = states.get_mut(&successor) {
                if extra != previous.0 {
                    return None;
                }
                if checked < previous.1 {
                    previous.1 = checked;
                    pending.push_back(successor);
                }
            } else {
                states.insert(successor, (extra, checked));
                pending.push_back(successor);
            }
        }
    }
    let &(extra, checked) = states.get(&site)?;
    Some(extra == 0 && checked >= frame + 16)
}

pub(super) fn allows_scratch_save(image: &Image<'_>, entry: usize, site: usize) -> bool {
    prove(image, entry, site).unwrap_or(false)
}
