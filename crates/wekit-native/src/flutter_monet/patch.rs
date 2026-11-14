use std::collections::HashSet;
use std::sync::atomic::{AtomicPtr, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use anyhow::{Context, Result, anyhow, bail, ensure};
use wekit_native_common::memory;

use super::matcher::{ColorFactory, Target, TargetKind};
use super::snapshot::Role;

const RETURN: u32 = 0xd65f_03c0;
const ROLE_COUNT: usize = 13;

#[repr(C)]
struct PaletteColor {
    red: u64,
    green: u64,
    blue: u64,
    packed: u64,
}

#[repr(C)]
struct Palette {
    dark: u64,
    colors: [PaletteColor; ROLE_COUNT * 2],
}

static PALETTE: AtomicPtr<Palette> = AtomicPtr::new(std::ptr::null_mut());
static ENABLED: AtomicU32 = AtomicU32::new(0);

struct Patch {
    address: usize,
    original: u32,
    replacement: u32,
    _code: Arc<memory::CodeMemory>,
}

fn patches() -> &'static Mutex<Vec<Patch>> {
    static PATCHES: OnceLock<Mutex<Vec<Patch>>> = OnceLock::new();
    PATCHES.get_or_init(|| Mutex::new(Vec::new()))
}

pub fn update_palette(colors: &[i32], dark: bool) -> Result<()> {
    ensure!(
        colors.len() == ROLE_COUNT * 2,
        "Flutter palette needs 26 colors"
    );
    let palette = Box::new(Palette {
        dark: u64::from(dark),
        colors: std::array::from_fn(|index| {
            let packed = colors[index] as u32 & 0x00ff_ffff;
            PaletteColor {
                red: (f64::from((packed >> 16) & 255) / 255.0).to_bits(),
                green: (f64::from((packed >> 8) & 255) / 255.0).to_bits(),
                blue: (f64::from(packed & 255) / 255.0).to_bits(),
                packed: u64::from(packed),
            }
        }),
    });
    PALETTE.store(Box::into_raw(palette), Ordering::Release);
    Ok(())
}

pub fn set_enabled(enabled: bool) {
    ENABLED.store(u32::from(enabled), Ordering::Release);
}

fn checked_word(address: usize, regions: &[memory::Region]) -> Result<u32> {
    let end = address.checked_add(4).context("Flutter word overflows")?;
    ensure!(
        address & 3 == 0
            && regions.iter().any(|region| {
                region.start <= address
                    && end <= region.end
                    && region.protection & (libc::PROT_READ | libc::PROT_EXEC)
                        == libc::PROT_READ | libc::PROT_EXEC
            }),
        "Flutter word is outside readable executable memory"
    );
    Ok(unsafe { (address as *const u32).read_volatile() })
}

fn absolute(code_base: usize, offset: usize) -> Result<usize> {
    code_base
        .checked_add(offset)
        .context("Flutter code address overflows")
}

fn direct_branch(source: usize, destination: usize) -> Result<u32> {
    ensure!(
        source & 3 == 0 && destination & 3 == 0,
        "unaligned Flutter branch"
    );
    let displacement = destination as i128 - source as i128;
    ensure!(
        (-(1_i128 << 27)..(1_i128 << 27)).contains(&displacement),
        "Flutter stub is outside branch reach"
    );
    Ok(0x1400_0000 | ((displacement >> 2) as u32 & 0x03ff_ffff))
}

fn validate_target(code_base: usize, target: &Target, regions: &[memory::Region]) -> Result<()> {
    let address = absolute(code_base, target.offset)?;
    ensure!(address & 3 == 0, "unaligned Flutter patch target");
    match target.kind {
        TargetKind::Return => ensure!(target.original == RETURN, "Flutter return is not RET x30"),
        TargetKind::FreshReturn { color_register } => {
            validate_color_register(color_register)?;
            ensure!(
                target.original == RETURN,
                "Flutter fresh return is not RET x30"
            );
        }
        TargetKind::FreshBranch {
            color_register,
            destination,
        } => {
            validate_color_register(color_register)?;
            ensure!(
                target.original == direct_branch(address, absolute(code_base, destination)?)?,
                "Flutter fresh branch changed its destination"
            );
        }
        TargetKind::FreshInstruction {
            color_register,
            preserve_scratch,
        } => {
            validate_color_register(color_register)?;
            let opcode = target.original & 0xffe0_0fe0;
            let register = target.original & 31;
            let displacement = ((target.original >> 12) as i32 & 0x1ff) << 23 >> 23;
            ensure!(
                (matches!(opcode, 0xf840_03a0 | 0xf800_03a0)
                    && displacement < 0
                    && displacement % 8 == 0
                    && !matches!(register, 15..=17 | 29..=31))
                    || (preserve_scratch
                        && (target.original & 0xffc0_03ff == 0x9140_0370
                            || target.original & 0xffc0_03ff == 0xf940_0370)),
                "Flutter continuation is not a supported frame or pool instruction"
            );
        }
    }
    ensure!(
        checked_word(address, regions)? == target.original,
        "Flutter patch target changed before installation"
    );
    Ok(())
}

fn validate_color_register(register: u8) -> Result<()> {
    ensure!(
        register < 31 && !matches!(register, 15..=17 | 29 | 30),
        "unsupported Flutter color register"
    );
    Ok(())
}

pub fn install(code_base: usize, targets: &[Target], factory: &ColorFactory) -> Result<usize> {
    ensure!(
        factory.frame_bytes == 32,
        "Flutter color factory frame must have four scalar slots"
    );
    ensure!(
        factory.class_id != 0 && factory.class_id < 1 << 20,
        "invalid Flutter Color class ID"
    );
    ensure!(!targets.is_empty(), "no Flutter patch targets");
    let regions = memory::mappings()?;
    let allocation_call = absolute(code_base, factory.allocation_call)?;
    ensure!(
        checked_word(allocation_call, &regions)? & 0xfc00_0000 == 0x9400_0000,
        "Flutter color factory allocation call changed"
    );
    let mut addresses = HashSet::new();
    for target in targets {
        validate_target(code_base, target, &regions)?;
        ensure!(
            addresses.insert(target.offset),
            "duplicate Flutter patch target"
        );
    }
    let page_size = memory::page_size()?;
    let mut pages = Vec::<(Arc<memory::CodeMemory>, Vec<u8>)>::new();
    let mut prepared = Vec::with_capacity(targets.len());
    for (index, target) in targets.iter().enumerate() {
        let address = absolute(code_base, target.offset)?;
        let palette_cell = &PALETTE as *const _ as usize;
        let enabled_cell = &ENABLED as *const _ as usize;
        let length = emit_stub(
            address,
            code_base,
            target,
            factory,
            palette_cell,
            enabled_cell,
        )?
        .len();
        ensure!(length <= page_size, "Flutter stub exceeds a code page");
        let outgoing = match target.kind {
            TargetKind::Return => Some(allocation_call),
            TargetKind::FreshBranch { destination, .. } => Some(absolute(code_base, destination)?),
            TargetKind::FreshInstruction { .. } => Some(address + 4),
            TargetKind::FreshReturn { .. } => None,
        };
        let reachable = |stub: usize| {
            direct_branch(address, stub).is_ok()
                && outgoing.is_none_or(|destination| {
                    direct_branch(stub, destination).is_ok()
                        && direct_branch(stub + length - 4, destination).is_ok()
                })
        };
        let existing = pages.iter().rposition(|(code, bytes)| {
            bytes.len() + length <= page_size && reachable(code.executable + bytes.len())
        });
        let page_index = if let Some(existing) = existing {
            existing
        } else {
            let code = memory::CodeMemory::allocate(Some(address), true).with_context(|| {
                format!(
                    "allocate Flutter {} stub {}/{}",
                    target.role.name(),
                    index + 1,
                    targets.len()
                )
            })?;
            ensure!(
                reachable(code.executable),
                "Flutter code page is outside branch reach"
            );
            pages.push((Arc::new(code), Vec::with_capacity(page_size)));
            pages.len() - 1
        };
        let (code, contents) = &mut pages[page_index];
        let stub = code.executable + contents.len();
        let bytes = emit_stub(stub, code_base, target, factory, palette_cell, enabled_cell)?;
        ensure!(bytes.len() == length, "Flutter stub size changed");
        contents.extend(bytes);
        prepared.push(Patch {
            address,
            original: target.original,
            replacement: direct_branch(address, stub)?,
            _code: Arc::clone(code),
        });
    }
    for (code, bytes) in pages {
        code.publish(&bytes)?;
    }
    let mut records = patches()
        .lock()
        .map_err(|_| anyhow!("Flutter patch registry is poisoned"))?;
    ensure!(
        prepared
            .iter()
            .all(|patch| records.iter().all(|record| record.address != patch.address)),
        "Flutter code is already patched"
    );
    records
        .try_reserve(prepared.len())
        .context("reserve Flutter patch ownership")?;
    let start = prepared.iter().map(|patch| patch.address).min().unwrap();
    let end = prepared.iter().map(|patch| patch.address).max().unwrap() + 4;
    let mut access =
        memory::Access::acquire(start, end - start, libc::PROT_READ | libc::PROT_WRITE, true)?;
    let mut installed = 0;
    for patch in prepared {
        let result = (|| {
            ensure!(
                unsafe { (patch.address as *const u32).read_volatile() } == patch.original,
                "Flutter target changed during installation"
            );
            unsafe { (patch.address as *mut u32).write_volatile(patch.replacement) };
            memory::synchronize(patch.address, patch.address, 4);
            records.push(patch);
            installed += 1;
            Ok::<_, anyhow::Error>(())
        })();
        if let Err(error) = result {
            set_enabled(false);
            bail!(
                "Flutter Monet installed {installed}/{} hooks before failure: {error:#}",
                targets.len()
            );
        }
    }
    if let Err(error) = access.restore() {
        crate::loge!("Flutter Monet: code page protection restoration failed: {error:#}");
    }
    Ok(installed)
}

#[derive(Clone, Copy)]
struct Label(usize);

struct Fixup {
    instruction: usize,
    label: Label,
    bits: u32,
    shift: u32,
}

struct Emitter {
    base: usize,
    words: Vec<u32>,
    labels: Vec<Option<usize>>,
    fixups: Vec<Fixup>,
}

impl Emitter {
    fn new(base: usize) -> Self {
        Self {
            base,
            words: Vec::new(),
            labels: Vec::new(),
            fixups: Vec::new(),
        }
    }

    fn emit(&mut self, instruction: u32) {
        self.words.push(instruction);
    }

    fn label(&mut self) -> Label {
        let label = Label(self.labels.len());
        self.labels.push(None);
        label
    }

    fn bind(&mut self, label: Label) {
        self.labels[label.0] = Some(self.words.len());
    }

    fn branch(&mut self, instruction: u32, label: Label, bits: u32, shift: u32) {
        self.fixups.push(Fixup {
            instruction: self.words.len(),
            label,
            bits,
            shift,
        });
        self.emit(instruction);
    }

    fn jump(&mut self, label: Label) {
        self.branch(0x1400_0000, label, 26, 0);
    }

    fn zero(&mut self, register: u8, nonzero: bool, wide: bool, label: Label) {
        self.branch(
            0x3400_0000
                | (u32::from(wide) << 31)
                | (u32::from(nonzero) << 24)
                | u32::from(register),
            label,
            19,
            5,
        );
    }

    fn bit(&mut self, register: u8, bit: u8, nonzero: bool, label: Label) {
        self.branch(
            0x3600_0000
                | (u32::from(bit >> 5) << 31)
                | (u32::from(nonzero) << 24)
                | (u32::from(bit & 31) << 19)
                | u32::from(register),
            label,
            14,
            5,
        );
    }

    fn constant(&mut self, register: u8, value: u64) {
        self.emit(0xd280_0000 | (((value & 0xffff) as u32) << 5) | u32::from(register));
        for shift in 1..4 {
            let part = ((value >> (shift * 16)) & 0xffff) as u32;
            if part != 0 {
                self.emit(0xf280_0000 | (shift << 21) | (part << 5) | u32::from(register));
            }
        }
    }

    fn load(&mut self, register: u8, base: u8, offset: usize) {
        self.emit(
            0xf940_0000
                | ((offset as u32 / 8) << 10)
                | (u32::from(base) << 5)
                | u32::from(register),
        );
    }

    fn unscaled(&mut self, register: u8, base: u8, offset: i32, load: bool) {
        self.emit(
            (if load { 0xf840_0000 } else { 0xf800_0000 })
                | (((offset as u32) & 0x1ff) << 12)
                | (u32::from(base) << 5)
                | u32::from(register),
        );
    }

    fn extract(&mut self, destination: u8, source: u8, shift: u32, width: u32) {
        self.emit(
            0xd340_0000
                | (shift << 16)
                | ((shift + width - 1) << 10)
                | (u32::from(source) << 5)
                | u32::from(destination),
        );
    }

    fn palette(&mut self, palette_cell: usize, offset: usize, missing: Label) {
        self.constant(16, palette_cell as u64);
        self.emit(0xc8df_fe11);
        self.zero(17, false, true, missing);
        self.emit(0x9100_0000 | ((offset as u32) << 10) | (17 << 5) | 17);
    }

    fn external(&mut self, destination: usize) -> Result<()> {
        self.emit(direct_branch(
            self.base + self.words.len() * 4,
            destination,
        )?);
        Ok(())
    }

    fn finish(mut self) -> Result<Vec<u8>> {
        for fixup in self.fixups {
            let destination = self.labels[fixup.label.0].context("unbound Flutter stub label")?;
            let displacement = destination as i64 - fixup.instruction as i64;
            let limit = 1_i64 << (fixup.bits - 1);
            ensure!(
                (-limit..limit).contains(&displacement),
                "Flutter stub branch overflows"
            );
            self.words[fixup.instruction] |=
                ((displacement as u32) & ((1 << fixup.bits) - 1)) << fixup.shift;
        }
        Ok(self.words.into_iter().flat_map(u32::to_le_bytes).collect())
    }
}

fn emit_stub(
    stub_base: usize,
    code_base: usize,
    target: &Target,
    factory: &ColorFactory,
    palette_cell: usize,
    enabled_cell: usize,
) -> Result<Vec<u8>> {
    let color_register = match target.kind {
        TargetKind::Return => 0,
        TargetKind::FreshReturn { color_register }
        | TargetKind::FreshBranch { color_register, .. }
        | TargetKind::FreshInstruction { color_register, .. } => color_register,
    };
    validate_color_register(color_register)?;
    let mut emitter = Emitter::new(stub_base);
    let passthrough = emitter.label();
    let light = emitter.label();
    let dark = emitter.label();
    let selected = emitter.label();
    let preserve_scratch = matches!(
        target.kind,
        TargetKind::FreshBranch { .. }
            | TargetKind::FreshInstruction {
                preserve_scratch: true,
                ..
            }
    );
    emitter.emit(0xd503_24df);
    if preserve_scratch {
        emitter.emit(0xa9bf_45f0);
    }
    emitter.bit(color_register, 0, false, passthrough);
    emitter.unscaled(16, color_register, -1, true);
    emitter.extract(16, 16, 12, 20);
    emitter.constant(17, u64::from(factory.class_id));
    emitter.emit(0xca11_0210);
    emitter.zero(16, true, true, passthrough);
    emitter.constant(16, enabled_cell as u64);
    emitter.emit(0x88df_fe11);
    emitter.zero(17, false, false, passthrough);
    if target.role == Role::Brand {
        emitter.constant(16, palette_cell as u64);
        emitter.emit(0xc8df_fe11);
        emitter.zero(17, false, true, passthrough);
        emitter.load(16, 17, 0);
        emitter.zero(16, true, true, dark);
        emitter.jump(light);
    } else {
        let background = matches!(
            target.role,
            Role::Bg0 | Role::Bg1 | Role::Bg2 | Role::Bg3 | Role::Bg4 | Role::Bg5
        );
        let black = if background { dark } else { light };
        let bright = if background { light } else { dark };
        emitter.unscaled(16, color_register, 15, true);
        emitter.bit(16, 63, true, black);
        emitter.constant(17, 0.5_f64.to_bits());
        emitter.emit(0xcb11_0210);
        emitter.bit(16, 63, true, black);
        emitter.jump(bright);
    }
    let role_offset = std::mem::offset_of!(Palette, colors)
        + target.role as usize * 2 * size_of::<PaletteColor>();
    emitter.bind(light);
    emitter.palette(palette_cell, role_offset, passthrough);
    emitter.jump(selected);
    emitter.bind(dark);
    emitter.palette(
        palette_cell,
        role_offset + size_of::<PaletteColor>(),
        passthrough,
    );
    emitter.bind(selected);
    match target.kind {
        TargetKind::Return => {
            emitter.unscaled(16, 0, 7, true);
            emitter.emit(0xa9bf_79fd);
            emitter.emit(0xaa0f_03fd);
            emitter.emit(0xd100_81ef);
            emitter.unscaled(16, 29, -32, false);
            emitter.load(17, 17, std::mem::offset_of!(PaletteColor, packed));
            for (shift, offset) in [(8, -8), (16, -16), (0, -24)] {
                emitter.extract(16, 17, shift, 8);
                emitter.unscaled(16, 29, offset, false);
            }
            emitter.external(absolute(code_base, factory.allocation_call)?)?;
        }
        TargetKind::FreshReturn { .. }
        | TargetKind::FreshBranch { .. }
        | TargetKind::FreshInstruction { .. } => {
            for (palette_offset, color_offset) in [(0, 15), (8, 23), (16, 31)] {
                emitter.load(16, 17, palette_offset);
                emitter.unscaled(16, color_register, color_offset, false);
            }
        }
    }
    emitter.bind(passthrough);
    if preserve_scratch {
        emitter.emit(0xa8c1_45f0);
    }
    match target.kind {
        TargetKind::Return | TargetKind::FreshReturn { .. } => emitter.emit(RETURN),
        TargetKind::FreshBranch { destination, .. } => {
            emitter.external(absolute(code_base, destination)?)?
        }
        TargetKind::FreshInstruction { .. } => {
            emitter.emit(target.original);
            emitter.external(absolute(code_base, target.offset + 4)?)?;
        }
    }
    emitter.finish()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn read_word(address: usize) -> Result<u32> {
        checked_word(address, &memory::mappings()?)
    }

    #[test]
    fn installs_one_instruction_and_refuses_reinstallation() {
        let source = memory::CodeMemory::allocate(None, false).unwrap();
        let words = [RETURN, 0xd503_201f, 0x9400_0000, 0xd503_201f];
        source
            .publish(
                &words
                    .into_iter()
                    .flat_map(u32::to_le_bytes)
                    .collect::<Vec<_>>(),
            )
            .unwrap();
        let target = Target {
            offset: 0,
            role: Role::Bg0,
            original: RETURN,
            kind: TargetKind::Return,
        };
        let factory = ColorFactory {
            allocation_call: 8,
            class_id: 33942,
            frame_bytes: 32,
            constructor: 0,
        };
        assert_eq!(
            install(source.executable, std::slice::from_ref(&target), &factory).unwrap(),
            1
        );
        let replacement = read_word(source.executable).unwrap();
        assert_eq!(replacement & 0xfc00_0000, 0x1400_0000);
        assert_eq!(read_word(source.executable + 4).unwrap(), words[1]);
        assert!(install(source.executable, &[target], &factory).is_err());
        assert_eq!(read_word(source.executable).unwrap(), replacement);
        std::mem::forget(source);
    }

    #[test]
    fn validates_all_targets_before_changing_code() {
        let source = memory::CodeMemory::allocate(None, false).unwrap();
        let words = [RETURN, 0xd503_201f, 0x9400_0000, 0xd503_201f];
        source
            .publish(
                &words
                    .into_iter()
                    .flat_map(u32::to_le_bytes)
                    .collect::<Vec<_>>(),
            )
            .unwrap();
        let targets: Vec<_> = [0, 4]
            .into_iter()
            .map(|offset| Target {
                offset,
                role: Role::Bg0,
                original: RETURN,
                kind: TargetKind::Return,
            })
            .collect();
        let factory = ColorFactory {
            allocation_call: 8,
            class_id: 33942,
            frame_bytes: 32,
            constructor: 0,
        };
        assert!(install(source.executable, &targets, &factory).is_err());
        assert_eq!(read_word(source.executable).unwrap(), RETURN);
        assert_eq!(read_word(source.executable + 4).unwrap(), words[1]);
    }

    #[test]
    fn packs_multiple_stubs_into_one_sealed_code_page() {
        let source = memory::CodeMemory::allocate(None, false).unwrap();
        let mut words = vec![RETURN; 8];
        words.push(0x9400_0000);
        source
            .publish(
                &words
                    .iter()
                    .flat_map(|word| word.to_le_bytes())
                    .collect::<Vec<_>>(),
            )
            .unwrap();
        let targets: Vec<_> = (0..8)
            .map(|index| Target {
                offset: index * 4,
                role: Role::Bg0,
                original: RETURN,
                kind: TargetKind::Return,
            })
            .collect();
        let factory = ColorFactory {
            allocation_call: 32,
            class_id: 33942,
            frame_bytes: 32,
            constructor: 0,
        };
        assert_eq!(install(source.executable, &targets, &factory).unwrap(), 8);
        let records = patches().lock().unwrap();
        let installed: Vec<_> = records
            .iter()
            .filter(|patch| (source.executable..source.executable + 32).contains(&patch.address))
            .collect();
        assert_eq!(installed.len(), 8);
        assert!(
            installed
                .iter()
                .all(|patch| Arc::ptr_eq(&patch._code, &installed[0]._code))
        );
        assert_eq!(read_word(source.executable + 32).unwrap(), 0x9400_0000);
        std::mem::forget(source);
    }

    #[test]
    fn refuses_direct_reads_outside_executable_regions() {
        let word = Box::new(RETURN);
        let regions = memory::mappings().unwrap();
        assert!(checked_word(&*word as *const u32 as usize, &regions).is_err());
        assert!(checked_word(0, &regions).is_err());
        assert!(checked_word(usize::MAX, &regions).is_err());
    }

    #[test]
    fn palette_snapshots_preserve_live_previous_versions() {
        let colors: Vec<_> = (0..26)
            .map(|index| (0xff00_0000_u32 | (index << 16) | (37 << 8) | 91) as i32)
            .collect();
        update_palette(&colors, true).unwrap();
        let first = PALETTE.load(Ordering::Acquire);
        let previous = unsafe { (*first).colors[25].packed };
        update_palette(&[0xff11_2233_u32 as i32; 26], false).unwrap();
        let second = PALETTE.load(Ordering::Acquire);
        assert_ne!(first, second);
        unsafe {
            assert_eq!((*first).dark, 1);
            assert_eq!((*first).colors[25].packed, previous);
            assert_eq!((*second).dark, 0);
        }
        assert!(update_palette(&colors[..25], false).is_err());
    }
}
