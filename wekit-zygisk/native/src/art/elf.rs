// Cached symbol lookup in the actual loaded ART library, including its optional
// XZ-compressed .gnu_debugdata mini ELF. LSPlant requests exact and prefix
// lookups separately; exact lookup must never select an overloaded symbol.

use crate::loge;
use libc::c_int;
use std::{
    ffi::{CStr, c_void},
    sync::{Once, OnceLock},
};

#[cfg(target_pointer_width = "64")]
mod elf_types {
    pub type Half = u16;
    pub type Word = u32;
    pub type Xword = u64;
    pub type Addr = u64;
    pub type Off = u64;
    pub const SHT_SYMTAB: Word = 2;
    pub const SHT_DYNSYM: Word = 11;
    #[derive(Clone, Copy)]
    #[repr(C)]
    pub struct Ehdr {
        pub e_ident: [u8; 16],
        pub e_type: Half,
        pub e_machine: Half,
        pub e_version: Word,
        pub e_entry: Addr,
        pub e_phoff: Off,
        pub e_shoff: Off,
        pub e_flags: Word,
        pub e_ehsize: Half,
        pub e_phentsize: Half,
        pub e_phnum: Half,
        pub e_shentsize: Half,
        pub e_shnum: Half,
        pub e_shstrndx: Half,
    }
    #[derive(Clone, Copy)]
    #[repr(C)]
    pub struct Shdr {
        pub sh_name: Word,
        pub sh_type: Word,
        pub sh_flags: Xword,
        pub sh_addr: Addr,
        pub sh_offset: Off,
        pub sh_size: Xword,
        pub sh_link: Word,
        pub sh_info: Word,
        pub sh_addralign: Xword,
        pub sh_entsize: Xword,
    }
    #[derive(Clone, Copy)]
    #[repr(C)]
    pub struct Sym {
        pub st_name: Word,
        pub st_info: u8,
        pub st_other: u8,
        pub st_shndx: Half,
        pub st_value: Addr,
        pub st_size: Xword,
    }
}

#[cfg(target_pointer_width = "32")]
mod elf_types {
    pub type Half = u16;
    pub type Word = u32;
    pub type Xword = u32;
    pub type Addr = u32;
    pub type Off = u32;
    pub const SHT_SYMTAB: Word = 2;
    pub const SHT_DYNSYM: Word = 11;
    #[derive(Clone, Copy)]
    #[repr(C)]
    pub struct Ehdr {
        pub e_ident: [u8; 16],
        pub e_type: Half,
        pub e_machine: Half,
        pub e_version: Word,
        pub e_entry: Addr,
        pub e_phoff: Off,
        pub e_shoff: Off,
        pub e_flags: Word,
        pub e_ehsize: Half,
        pub e_phentsize: Half,
        pub e_phnum: Half,
        pub e_shentsize: Half,
        pub e_shnum: Half,
        pub e_shstrndx: Half,
    }
    #[derive(Clone, Copy)]
    #[repr(C)]
    pub struct Shdr {
        pub sh_name: Word,
        pub sh_type: Word,
        pub sh_flags: Xword,
        pub sh_addr: Addr,
        pub sh_offset: Off,
        pub sh_size: Xword,
        pub sh_link: Word,
        pub sh_info: Word,
        pub sh_addralign: Xword,
        pub sh_entsize: Xword,
    }
    #[derive(Clone, Copy)]
    #[repr(C)]
    pub struct Sym {
        pub st_name: Word,
        pub st_info: u8,
        pub st_other: u8,
        pub st_shndx: Half,
        pub st_value: Addr,
        pub st_size: Xword,
    }
}

use elf_types::{Ehdr, SHT_DYNSYM, SHT_SYMTAB, Shdr, Sym};

/// A bounded, borrowed ELF view. Records are copied with unaligned reads because
/// neither Vec<u8> nor section offsets guarantee ELF record alignment.
struct ElfFile<'a> {
    data: &'a [u8],
    header: Ehdr,
}

impl<'a> ElfFile<'a> {
    fn from_slice(data: &'a [u8]) -> Option<Self> {
        // All fields of the three ELF record types are integers, so every bit
        // pattern is valid. No record read is permitted outside the byte slice.
        let header: Ehdr = unsafe { read_record(data, 0)? };
        let ident = &header.e_ident;
        let class = if cfg!(target_pointer_width = "64") {
            2
        } else {
            1
        };
        let endian = if cfg!(target_endian = "little") { 1 } else { 2 };
        if ident[..4] != *b"\x7fELF"
            || ident[4] != class
            || ident[5] != endian
            || ident[6] != 1
            || header.e_version != 1
            || usize::from(header.e_ehsize) < size_of::<Ehdr>()
            || usize::from(header.e_shentsize) < size_of::<Shdr>()
            || header.e_shnum == 0
        {
            return None;
        }
        let section_start = usize::try_from(header.e_shoff).ok()?;
        let section_size =
            usize::from(header.e_shentsize).checked_mul(usize::from(header.e_shnum))?;
        data.get(section_start..section_start.checked_add(section_size)?)?;
        Some(Self { data, header })
    }

    fn shdr(&self, index: usize) -> Option<Shdr> {
        if index >= usize::from(self.header.e_shnum) {
            return None;
        }
        let offset = usize::try_from(self.header.e_shoff)
            .ok()?
            .checked_add(index.checked_mul(usize::from(self.header.e_shentsize))?)?;
        // SAFETY: Shdr contains only integer fields; read_record checks bounds.
        unsafe { read_record(self.data, offset) }
    }

    fn section_data(&self, section: &Shdr) -> Option<&'a [u8]> {
        let start = usize::try_from(section.sh_offset).ok()?;
        let size = usize::try_from(section.sh_size).ok()?;
        self.data.get(start..start.checked_add(size)?)
    }

    fn find_section(&self, name: &str) -> Option<&'a [u8]> {
        let names = self.section_data(&self.shdr(usize::from(self.header.e_shstrndx))?)?;
        for index in 0..usize::from(self.header.e_shnum) {
            let section = self.shdr(index)?;
            if string_at(names, section.sh_name as usize) == Some(name.as_bytes()) {
                return self.section_data(&section);
            }
        }
        None
    }

    fn find_symbol(&self, target: &str, prefix: bool) -> Option<Sym> {
        // Prefer exported symbols, then search the full/local symbol table.
        for kind in [SHT_DYNSYM, SHT_SYMTAB] {
            for index in 0..usize::from(self.header.e_shnum) {
                let section = self.shdr(index)?;
                if section.sh_type != kind {
                    continue;
                }
                if let Some(symbol) = self.scan_symtab(&section, target.as_bytes(), prefix) {
                    return Some(symbol);
                }
            }
        }
        None
    }

    fn scan_symtab(&self, section: &Shdr, target: &[u8], prefix: bool) -> Option<Sym> {
        const SHT_STRTAB: u32 = 3;
        const SHN_UNDEF: u16 = 0;
        const SHN_ABS: u16 = 0xfff1;
        let strings = self.shdr(usize::try_from(section.sh_link).ok()?)?;
        if strings.sh_type != SHT_STRTAB {
            return None;
        }
        let names = self.section_data(&strings)?;
        let symbols = self.section_data(section)?;
        let stride = usize::try_from(section.sh_entsize).ok()?;
        if stride < size_of::<Sym>() || symbols.len() % stride != 0 {
            return None;
        }
        for offset in (0..symbols.len()).step_by(stride) {
            // SAFETY: Sym contains only integer fields; read_record checks bounds.
            let symbol: Sym = unsafe { read_record(symbols, offset)? };
            if symbol.st_value == 0
                || symbol.st_shndx == SHN_UNDEF
                || (usize::from(symbol.st_shndx) >= usize::from(self.header.e_shnum)
                    && symbol.st_shndx != SHN_ABS)
            {
                continue;
            }
            let Some(name) = string_at(names, symbol.st_name as usize) else {
                continue;
            };
            if if prefix {
                name.starts_with(target)
            } else {
                name == target
            } {
                return Some(symbol);
            }
        }
        None
    }
}

/// Caller must use a record type whose fields permit every bit pattern.
unsafe fn read_record<T: Copy>(data: &[u8], offset: usize) -> Option<T> {
    let bytes = data.get(offset..offset.checked_add(size_of::<T>())?)?;
    Some(unsafe { bytes.as_ptr().cast::<T>().read_unaligned() })
}

fn string_at(strings: &[u8], offset: usize) -> Option<&[u8]> {
    let tail = strings.get(offset..)?;
    Some(&tail[..tail.iter().position(|&byte| byte == 0)?])
}

/// Own the ELF bytes for the resolver's lifetime and decompress mini debug info
/// at most once. Vec and OnceLock provide Send + Sync without raw-pointer owners.
pub struct ArtSymbolResolver {
    base: usize,
    data: Vec<u8>,
    debug_data: OnceLock<Option<Vec<u8>>>,
}

impl ArtSymbolResolver {
    pub fn load() -> Option<Self> {
        let library = find_art_library()?;
        let data = std::fs::read(&library.path).ok()?;
        ElfFile::from_slice(&data)?;
        Some(Self {
            base: library.base,
            data,
            debug_data: OnceLock::new(),
        })
    }

    /// Return zero when unavailable. All relative values are relocated against
    /// this same loaded ART image; unrelated on-disk paths and RTLD_DEFAULT are
    /// deliberately excluded to avoid returning a symbol from another library.
    pub fn resolve(&self, name: &str, prefix: bool) -> usize {
        if name.is_empty() {
            return 0;
        }
        let Some(elf) = ElfFile::from_slice(&self.data) else {
            return 0;
        };
        let symbol = elf.find_symbol(name, prefix).or_else(|| {
            let bytes = self.debug_data.get_or_init(|| {
                let compressed = elf.find_section(".gnu_debugdata")?;
                let bytes = decompress_xz(compressed)?;
                ElfFile::from_slice(&bytes)?;
                Some(bytes)
            });
            ElfFile::from_slice(bytes.as_ref()?)?.find_symbol(name, prefix)
        });
        let Some(symbol) = symbol else {
            return 0;
        };
        let value = symbol.st_value as usize;
        if symbol.st_shndx == 0xfff1 {
            // SHN_ABS: no load bias applies.
            value
        } else {
            self.base.checked_add(value).unwrap_or(0)
        }
    }
}

struct ArtLibrary {
    base: usize,
    path: String,
}

extern "C" fn phdr_callback(
    info: *mut libc::dl_phdr_info,
    _size: libc::size_t,
    data: *mut c_void,
) -> c_int {
    // SAFETY: dl_iterate_phdr supplies a valid info record and receives the live
    // Option<ArtLibrary> below as its callback context.
    let result = unsafe { &mut *(data as *mut Option<ArtLibrary>) };
    let info = unsafe { &*info };
    if info.dlpi_name.is_null() {
        return 0;
    }
    let Ok(path) = (unsafe { CStr::from_ptr(info.dlpi_name) }).to_str() else {
        return 0;
    };
    let name = path.rsplit('/').next().unwrap_or(path);
    if name == "libart.so" || name == "libartd.so" {
        *result = Some(ArtLibrary {
            base: info.dlpi_addr as usize,
            path: path.to_owned(),
        });
        return 1;
    }
    0
}

fn find_art_library() -> Option<ArtLibrary> {
    let mut result: Option<ArtLibrary> = None;
    unsafe { libc::dl_iterate_phdr(Some(phdr_callback), &mut result as *mut _ as *mut c_void) };
    result
}

// ABI from the pinned XZ Embedded xz.h.
#[repr(C)]
struct XzBuffer {
    input: *const u8,
    in_pos: usize,
    in_size: usize,
    output: *mut u8,
    out_pos: usize,
    out_size: usize,
}

unsafe extern "C" {
    fn xz_crc32_init();
    fn xz_crc64_init();
    fn xz_dec_init(mode: c_int, dict_max: u32) -> *mut c_void;
    fn xz_dec_run(decoder: *mut c_void, buffer: *mut XzBuffer) -> c_int;
    fn xz_dec_end(decoder: *mut c_void);
}

fn decompress_xz(input: &[u8]) -> Option<Vec<u8>> {
    static CRC_TABLES: Once = Once::new();
    CRC_TABLES.call_once(|| unsafe {
        xz_crc32_init();
        xz_crc64_init();
    });
    // XZ_SINGLE uses the output buffer as its dictionary and resets on each
    // call, so the existing bounded retry loop needs no streaming state.
    const XZ_SINGLE: c_int = 0;
    const XZ_STREAM_END: c_int = 1;
    const XZ_BUF_ERROR: c_int = 8;
    let decoder = unsafe { xz_dec_init(XZ_SINGLE, 0) };
    if decoder.is_null() {
        loge!("Zygisk: XZ decoder allocation failed");
        return None;
    }
    const MAX_SIZE: usize = 64 * 1024 * 1024;
    let mut size = input.len().saturating_mul(4).clamp(65536, MAX_SIZE);
    let decoded = loop {
        let mut output = vec![0u8; size];
        let mut buffer = XzBuffer {
            input: input.as_ptr(),
            in_pos: 0,
            in_size: input.len(),
            output: output.as_mut_ptr(),
            out_pos: 0,
            out_size: output.len(),
        };
        let result = unsafe { xz_dec_run(decoder, &mut buffer) };
        if result == XZ_STREAM_END && buffer.in_pos == input.len() {
            output.truncate(buffer.out_pos);
            break Some(output);
        }
        if result == XZ_BUF_ERROR && size < MAX_SIZE {
            size = (size * 2).min(MAX_SIZE);
            continue;
        }
        loge!("Zygisk: XZ decompress error {result}");
        break None;
    };
    unsafe { xz_dec_end(decoder) };
    decoded
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::offset_of;

    fn put<const N: usize>(data: &mut [u8], offset: usize, value: [u8; N]) {
        data[offset..offset + N].copy_from_slice(&value);
    }

    /// Seven sections, with intentionally unaligned section and symbol records.
    /// The exported overload precedes the exact name; local symbols only exist
    /// in .symtab. Undefined and zero-valued entries must never resolve.
    fn fixture(debug_data: &[u8]) -> Vec<u8> {
        let sections = size_of::<Ehdr>() + 1;
        let mut data = vec![0u8; sections + 7 * size_of::<Shdr>()];
        data[..7].copy_from_slice(&[
            0x7f,
            b'E',
            b'L',
            b'F',
            if cfg!(target_pointer_width = "64") {
                2
            } else {
                1
            },
            if cfg!(target_endian = "little") { 1 } else { 2 },
            1,
        ]);
        put(&mut data, offset_of!(Ehdr, e_version), 1u32.to_ne_bytes());
        put(
            &mut data,
            offset_of!(Ehdr, e_ehsize),
            (size_of::<Ehdr>() as u16).to_ne_bytes(),
        );
        put(
            &mut data,
            offset_of!(Ehdr, e_shoff),
            (sections as elf_types::Off).to_ne_bytes(),
        );
        put(
            &mut data,
            offset_of!(Ehdr, e_shentsize),
            (size_of::<Shdr>() as u16).to_ne_bytes(),
        );
        put(&mut data, offset_of!(Ehdr, e_shnum), 7u16.to_ne_bytes());
        put(&mut data, offset_of!(Ehdr, e_shstrndx), 5u16.to_ne_bytes());

        let strings = b"\0targetSuffix\0target\0local\0missing\0zero\0";
        for (index, kind, bytes, link, entry_size, name) in [
            (1, 3, strings.to_vec(), 0, 0, 0),
            (
                2,
                SHT_DYNSYM,
                symbol_table(&[(1, 0x110, 1), (14, 0x220, 1), (27, 0x330, 0), (35, 0, 1)]),
                1,
                size_of::<Sym>(),
                0,
            ),
            (3, 3, strings.to_vec(), 0, 0, 0),
            (
                4,
                SHT_SYMTAB,
                symbol_table(&[(21, 0x440, 1)]),
                3,
                size_of::<Sym>(),
                0,
            ),
            (5, 3, b"\0.gnu_debugdata\0".to_vec(), 0, 0, 0),
            (6, 1, debug_data.to_vec(), 0, 0, 1),
        ] {
            let start = data.len();
            data.extend_from_slice(&bytes);
            let section = sections + index * size_of::<Shdr>();
            put(
                &mut data,
                section + offset_of!(Shdr, sh_type),
                kind.to_ne_bytes(),
            );
            put(
                &mut data,
                section + offset_of!(Shdr, sh_name),
                (name as u32).to_ne_bytes(),
            );
            put(
                &mut data,
                section + offset_of!(Shdr, sh_offset),
                (start as elf_types::Off).to_ne_bytes(),
            );
            put(
                &mut data,
                section + offset_of!(Shdr, sh_size),
                (bytes.len() as elf_types::Xword).to_ne_bytes(),
            );
            put(
                &mut data,
                section + offset_of!(Shdr, sh_link),
                (link as u32).to_ne_bytes(),
            );
            put(
                &mut data,
                section + offset_of!(Shdr, sh_entsize),
                (entry_size as elf_types::Xword).to_ne_bytes(),
            );
        }
        data
    }

    fn symbol_table(entries: &[(u32, usize, u16)]) -> Vec<u8> {
        let mut data = vec![0; entries.len() * size_of::<Sym>()];
        for (index, &(name, value, section)) in entries.iter().enumerate() {
            let offset = index * size_of::<Sym>();
            put(
                &mut data,
                offset + offset_of!(Sym, st_name),
                name.to_ne_bytes(),
            );
            put(
                &mut data,
                offset + offset_of!(Sym, st_value),
                (value as elf_types::Addr).to_ne_bytes(),
            );
            put(
                &mut data,
                offset + offset_of!(Sym, st_shndx),
                section.to_ne_bytes(),
            );
        }
        data
    }

    #[test]
    fn distinguishes_exact_overloaded_local_and_undefined_symbols() {
        let data = fixture(&[]);
        let elf = ElfFile::from_slice(&data).unwrap();
        assert_eq!(elf.find_symbol("target", false).unwrap().st_value, 0x220);
        assert_eq!(elf.find_symbol("target", true).unwrap().st_value, 0x110);
        assert_eq!(elf.find_symbol("local", false).unwrap().st_value, 0x440);
        assert!(elf.find_symbol("missing", false).is_none());
        assert!(elf.find_symbol("zero", false).is_none());
        assert!(elf.find_symbol("targ", false).is_none());
    }

    #[test]
    fn rejects_invalid_headers_and_symbol_table_boundaries() {
        let original = fixture(&[]);
        for length in 0..size_of::<Ehdr>() {
            assert!(ElfFile::from_slice(&original[..length]).is_none());
        }
        let mut data = original.clone();
        data[5] ^= 3; // Incorrect endianness.
        assert!(ElfFile::from_slice(&data).is_none());
        let mut data = original.clone();
        put(
            &mut data,
            offset_of!(Ehdr, e_shoff),
            elf_types::Off::MAX.to_ne_bytes(),
        );
        assert!(ElfFile::from_slice(&data).is_none());
        let dynsym = size_of::<Ehdr>() + 1 + 2 * size_of::<Shdr>();
        for (offset, value) in [
            (offset_of!(Shdr, sh_offset), elf_types::Xword::MAX),
            (offset_of!(Shdr, sh_size), elf_types::Xword::MAX),
            (offset_of!(Shdr, sh_entsize), 0),
        ] {
            let mut data = original.clone();
            put(&mut data, dynsym + offset, value.to_ne_bytes());
            let elf = ElfFile::from_slice(&data).unwrap();
            assert!(elf.find_symbol("target", false).is_none());
            assert_eq!(elf.find_symbol("local", false).unwrap().st_value, 0x440);
        }
        let mut data = original;
        put(
            &mut data,
            dynsym + offset_of!(Shdr, sh_link),
            u32::MAX.to_ne_bytes(),
        );
        assert!(
            ElfFile::from_slice(&data)
                .unwrap()
                .find_symbol("target", false)
                .is_none()
        );
    }

    #[test]
    fn bounds_string_reads_to_their_own_section() {
        let mut data = fixture(&[]);
        let dynstr = size_of::<Ehdr>() + 1 + size_of::<Shdr>();
        let offset = ElfFile::from_slice(&data)
            .unwrap()
            .shdr(1)
            .unwrap()
            .sh_offset as usize;
        data[offset + 20] = b'X'; // Remove target's terminator.
        put(
            &mut data,
            dynstr + offset_of!(Shdr, sh_size),
            (21 as elf_types::Xword).to_ne_bytes(),
        );
        let elf = ElfFile::from_slice(&data).unwrap();
        assert!(elf.find_symbol("target", false).is_none());
        assert_eq!(
            elf.find_symbol("targetSuffix", false).unwrap().st_value,
            0x110
        );
    }

    #[test]
    fn relocates_symbols_and_searches_cached_mini_elf() {
        let mut data = fixture(&[]);
        // Only the mini ELF has the local symbol.
        let symtab = size_of::<Ehdr>() + 1 + 4 * size_of::<Shdr>();
        put(
            &mut data,
            symtab + offset_of!(Shdr, sh_type),
            0u32.to_ne_bytes(),
        );
        let resolver = ArtSymbolResolver {
            base: 0x10000,
            data,
            debug_data: OnceLock::from(Some(fixture(&[]))),
        };
        assert_eq!(resolver.resolve("target", false), 0x10220);
        assert_eq!(resolver.resolve("target", true), 0x10110);
        assert_eq!(resolver.resolve("local", false), 0x10440);
        assert_eq!(resolver.resolve("loc", false), 0);
        assert_eq!(resolver.resolve("loc", true), 0x10440);
        assert_eq!(resolver.resolve("missing", false), 0);
    }
}
