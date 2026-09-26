//! Desktop executable used by arm64_relocation.py. Compiles the production emitter.
#![allow(dead_code)]
#[path = "../src/art/inline_hook/aarch64.rs"]
mod aarch64;

fn main() {
    let args: Vec<_> = std::env::args().collect();
    let source = u64::from_str_radix(&args[1], 16).unwrap();
    let destination = u64::from_str_radix(&args[2], 16).unwrap();
    let count: usize = args[3].parse().unwrap();
    let data: Vec<u8> = args[4]
        .as_bytes()
        .chunks_exact(2)
        .map(|b| u8::from_str_radix(std::str::from_utf8(b).unwrap(), 16).unwrap())
        .collect();
    let words: Vec<_> = data[..count * 4]
        .chunks_exact(4)
        .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
        .collect();
    let mut relays = Vec::new();
    let code = aarch64::relocate(
        &words,
        source,
        destination,
        |target| {
            let address = (target & !4095) + 0x100000 + relays.len() as u64 * 4096;
            let code = aarch64::relay_code(address, target)?;
            relays.push((address, code));
            Ok(address)
        },
        |address, size| {
            let start = address
                .checked_sub(source)
                .ok_or(aarch64::Error::InvalidAddress)? as usize;
            data.get(start..start + size)
                .map(|d| d.to_vec())
                .ok_or(aarch64::Error::InvalidAddress)
        },
    )
    .unwrap();
    let print = |address: u64, bytes: &[u8]| {
        print!("{address:x} ");
        for byte in bytes {
            print!("{byte:02x}");
        }
        println!();
    };
    print(destination, &code);
    for (address, code) in relays {
        print(address, &code);
    }
    if let Some(replacement) = args.get(5) {
        let replacement = u64::from_str_radix(replacement, 16).unwrap();
        let patch =
            aarch64::entry_patch(source, replacement, aarch64::landing_pad(words[0])).unwrap();
        print(source, &patch);
    }
}
