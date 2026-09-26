//! A64 entry relocation. This module only generates bytes; it never touches live code.
//!
//! PC-relative instructions are expanded into fixed-size slots, so forward and backward
//! branches into the overwritten interval have an unambiguous destination. Address
//! calculations retain the original PC. Literal loads retain live memory semantics;
//! only literals overlapping the overwritten bytes are snapshotted.
//!
//! Unlike an ABI veneer, a relocated intra-function branch cannot clobber IP0/IP1.
//! Out-of-range branches therefore save x17 and use a nearby restore-and-branch relay.

pub const BTI_C: u32 = 0xd503_245f;
pub const BTI_JC: u32 = 0xd503_24df;
pub const NOP: u32 = 0xd503_201f;
pub const SLOT_SIZE: usize = 64;
const SAVE_X17: u32 = 0xf81f_0ff1; // str x17, [sp, #-16]!
const RESTORE_X17: u32 = 0xf841_07f1; // ldr x17, [sp], #16

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    InvalidAddress,
    InvalidInstruction(u32),
    OutOfRange,
    Memory(String),
}

pub fn landing_pad(word: u32) -> bool {
    // PACIASP/PACIBSP are also guarded-page landing instructions. Replacing one
    // requires a BTI at the original address; signing itself runs in the backup.
    matches!(
        word,
        0xd503_241f | 0xd503_245f | 0xd503_249f | 0xd503_24df | 0xd503_233f | 0xd503_237f
    )
}

pub fn direct_branch(from: u64, to: u64, link: bool) -> Result<u32, Error> {
    let delta = to as i128 - from as i128;
    if from & 3 != 0 || to & 3 != 0 {
        return Err(Error::InvalidAddress);
    }
    if !(-(1i128 << 27)..(1i128 << 27)).contains(&delta) {
        return Err(Error::OutOfRange);
    }
    Ok((if link { 0x9400_0000 } else { 0x1400_0000 }) | ((delta >> 2) as u32 & 0x03ff_ffff))
}

pub fn words_bytes(words: &[u32]) -> Vec<u8> {
    words.iter().flat_map(|word| word.to_le_bytes()).collect()
}

/// Native entry veneer; x17 is caller-saved at a function entry (AAPCS64 IP1).
pub fn entry_patch(target: u64, replacement: u64, pad: bool) -> Result<Vec<u8>, Error> {
    if target & 3 != 0 || replacement & 3 != 0 || target == 0 || replacement == 0 {
        return Err(Error::InvalidAddress);
    }
    let mut code = Vec::new();
    if pad {
        emit(&mut code, BTI_JC);
    }
    let pc = target + code.len() as u64;
    if let Ok(word) = direct_branch(pc, replacement, false) {
        emit(&mut code, word);
    } else {
        emit(&mut code, 0x5800_0051); // ldr x17, .+8
        emit(&mut code, 0xd61f_0220); // br x17 (compatible with a BTI c destination)
        code.extend(replacement.to_le_bytes());
    }
    Ok(code)
}

pub fn relay_code(address: u64, destination: u64) -> Result<Vec<u8>, Error> {
    Ok(words_bytes(&[
        BTI_JC,
        RESTORE_X17,
        direct_branch(address + 8, destination, false)?,
    ]))
}

fn emit(code: &mut Vec<u8>, word: u32) {
    code.extend(word.to_le_bytes());
}
fn sx(value: u32, bits: u32) -> i64 {
    ((value as i64) << (64 - bits)) >> (64 - bits)
}
fn add(pc: u64, offset: i64) -> u64 {
    pc.wrapping_add_signed(offset)
}

fn mov_address(code: &mut Vec<u8>, register: u32, address: u64) {
    emit(
        code,
        0xd280_0000 | (((address & 0xffff) as u32) << 5) | register,
    );
    for shift in 1..4 {
        emit(
            code,
            0xf280_0000
                | (shift << 21)
                | ((((address >> (shift * 16)) & 0xffff) as u32) << 5)
                | register,
        );
    }
}

fn reachable_instructions(original: &[u32], source: u64) -> [bool; 5] {
    let mut reached = [false; 5];
    let mut pending = vec![0];
    while let Some(index) = pending.pop() {
        if index >= original.len() || reached[index] {
            continue;
        }
        reached[index] = true;
        let word = original[index];
        let pc = source + index as u64 * 4;
        let immediate = if word & 0x7c00_0000 == 0x1400_0000 {
            Some(sx(word & 0x03ff_ffff, 26) * 4)
        } else if word & 0xff00_0000 == 0x5400_0000 || word & 0x7e00_0000 == 0x3400_0000 {
            Some(sx((word >> 5) & 0x7ffff, 19) * 4)
        } else if word & 0x7e00_0000 == 0x3600_0000 {
            Some(sx((word >> 5) & 0x3fff, 14) * 4)
        } else {
            None
        };
        if let Some(offset) = immediate {
            let target = add(pc, offset);
            if (source..source + original.len() as u64 * 4).contains(&target) {
                pending.push(((target - source) / 4) as usize);
            }
        }
        // A64 AL/NV conditions are both always true, for B.cond and BC.cond.
        // Keep following their target, but do not interpret skipped inline data
        // as fallthrough code. Other incoming edges can still reach that code.
        let branch = word & 0xfc00_0000 == 0x1400_0000
            || (word & 0xff00_0000 == 0x5400_0000 && word & 0xe == 0xe);
        let register_branch = word & 0xfe00_0000 == 0xd600_0000;
        let register_call = matches!((word >> 21) & 15, 1 | 9); // BLR / BLRAA / BLRAB
        if !branch && (!register_branch || register_call) {
            pending.push(index + 1);
        }
    }
    reached
}

fn jump(
    code: &mut Vec<u8>,
    base: u64,
    destination: u64,
    relay: &mut impl FnMut(u64) -> Result<u64, Error>,
) -> Result<(), Error> {
    if let Ok(word) = direct_branch(base + code.len() as u64, destination, false) {
        emit(code, word);
    } else {
        let address = relay(destination)?;
        emit(code, SAVE_X17);
        emit(code, 0x5800_0051); // literal at current PC + 8
        emit(code, 0xd61f_0220);
        code.extend(address.to_le_bytes());
    }
    Ok(())
}

/// `relay` allocates a relay near its destination and emits `relay_code` there.
/// `read_literal` reads the original bytes before entry publication.
pub fn relocate(
    original: &[u32],
    source: u64,
    destination: u64,
    mut relay: impl FnMut(u64) -> Result<u64, Error>,
    mut read_literal: impl FnMut(u64, usize) -> Result<Vec<u8>, Error>,
) -> Result<Vec<u8>, Error> {
    let source_end = source
        .checked_add(original.len() as u64 * 4)
        .ok_or(Error::InvalidAddress)?;
    if original.is_empty() || original.len() > 5 || source & 3 != 0 || destination & 3 != 0 {
        return Err(Error::InvalidAddress);
    }
    let branch_target = |address: u64| -> Result<u64, Error> {
        if address & 3 != 0 {
            return Err(Error::InvalidAddress);
        }
        Ok(if (source..source_end).contains(&address) {
            destination + 4 + (address - source) / 4 * SLOT_SIZE as u64
        } else {
            address
        })
    };
    let reachable = reachable_instructions(original, source);
    let mut code = words_bytes(&[BTI_C]);
    for (index, &word) in original.iter().enumerate() {
        let pc = source + index as u64 * 4;
        let start = code.len();
        let end = start + SLOT_SIZE;
        // Absolute entry veneers commonly contain literal data after BR. Such
        // bytes are not instructions; their consumers snapshot them below.
        if !reachable[index] {
            while code.len() < end {
                emit(&mut code, NOP);
            }
            continue;
        }
        if word & 0x7c00_0000 == 0x1400_0000 {
            // B / BL. A BL to the immediately following instruction is the
            // conventional get-PC idiom: preserve its original LR value.
            let target = add(pc, sx(word & 0x03ff_ffff, 26) * 4);
            let link = word >> 31 != 0;
            if link && target == pc + 4 {
                mov_address(&mut code, 30, target);
            } else {
                let target = branch_target(target)?;
                if link {
                    if let Ok(branch) = direct_branch(destination + code.len() as u64, target, true)
                    {
                        emit(&mut code, branch);
                    } else {
                        mov_address(&mut code, 30, destination + end as u64);
                        jump(&mut code, destination, target, &mut relay)?;
                    }
                } else {
                    jump(&mut code, destination, target, &mut relay)?;
                }
            }
        } else if word & 0x1f00_0000 == 0x1000_0000 {
            // ADR / ADRP. The ADRP architectural page is always 4 KiB,
            // independently of the operating system's mapping page size.
            let immediate = sx(((word >> 5) & 0x7ffff) << 2 | ((word >> 29) & 3), 21);
            let address = if word >> 31 != 0 {
                add(pc & !4095, immediate * 4096)
            } else {
                add(pc, immediate)
            };
            mov_address(&mut code, word & 31, address);
        } else if word & 0x3b00_0000 == 0x1800_0000 {
            let address = add(pc, sx((word >> 5) & 0x7ffff, 19) * 4);
            let register = word & 31;
            let vector = word & (1 << 26) != 0;
            let opc = word >> 30;
            let (load, size) = match (vector, opc) {
                (false, 0) => (0xb940_0000, 4), // LDR W
                (false, 1) => (0xf940_0000, 8), // LDR X
                (false, 2) => (0xb980_0000, 4), // LDRSW X
                (false, 3) => (0xf980_0000, 0), // PRFM
                (true, 0) => (0xbd40_0000, 4),  // LDR S
                (true, 1) => (0xfd40_0000, 8),  // LDR D
                (true, 2) => (0x3dc0_0000, 16), // LDR Q
                _ => return Err(Error::InvalidInstruction(word)),
            };
            // A literal in the overwritten interval must be kept intact in a
            // local pool. Ordinary literals are loaded from their live address.
            if size != 0 && address < source_end && address.saturating_add(size as u64) > source {
                let data = read_literal(address, size)?;
                if data.len() != size {
                    return Err(Error::InvalidAddress);
                }
                emit(&mut code, (word & !0x00ff_ffe0) | (2 << 5)); // literal at +8
                let branch = direct_branch(
                    destination + code.len() as u64,
                    destination + end as u64,
                    false,
                )?;
                emit(&mut code, branch);
                code.extend(data);
            } else {
                // Reuse the destination GPR where possible. For SIMD, PRFM,
                // and zero-register loads preserve our address scratch register.
                let scratch = vector || opc == 3 || register == 31;
                let base = if scratch { 17 } else { register };
                if scratch {
                    emit(&mut code, SAVE_X17);
                }
                mov_address(&mut code, base, address);
                emit(&mut code, load | (base << 5) | register);
                if scratch {
                    emit(&mut code, RESTORE_X17);
                }
            }
        } else if word & 0xff00_0000 == 0x5400_0000
            || word & 0x7e00_0000 == 0x3400_0000
            || word & 0x7e00_0000 == 0x3600_0000
        {
            // B.cond / BC.cond, CBZ / CBNZ, TBZ / TBNZ. Keep the predicate
            // itself (including AL/NV) rather than assuming it can be inverted.
            let bits = if word & 0x7e00_0000 == 0x3600_0000 {
                14
            } else {
                19
            };
            let mask = ((1 << bits) - 1) << 5;
            let target = branch_target(add(pc, sx((word & mask) >> 5, bits) * 4))?;
            emit(&mut code, (word & !mask) | (2 << 5));
            let branch = direct_branch(
                destination + code.len() as u64,
                destination + end as u64,
                false,
            )?;
            emit(&mut code, branch);
            jump(&mut code, destination, target, &mut relay)?;
        } else {
            // The other A64 instruction classes have no immediate PC-relative
            // operand: arithmetic, register loads/stores, SIMD/SVE, system and
            // register branches (including PAC authentication) retain their bits.
            // Reject the permanently undefined/UDF instruction class.
            if word >> 16 == 0 {
                return Err(Error::InvalidInstruction(word));
            }
            emit(&mut code, word);
        }
        if code.len() > end {
            return Err(Error::OutOfRange);
        }
        // Fixed addresses simplify internal fixups; do not execute the padding.
        if code.len() + 4 <= end {
            let branch = direct_branch(
                destination + code.len() as u64,
                destination + end as u64,
                false,
            )?;
            emit(&mut code, branch);
        }
        while code.len() < end {
            emit(&mut code, NOP);
        }
    }
    jump(&mut code, destination, source_end, &mut relay)?;
    Ok(code)
}
