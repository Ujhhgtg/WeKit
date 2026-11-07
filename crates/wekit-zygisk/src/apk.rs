//! APK validation and bounded multidex decompression, independent of Android/JNI.

use anyhow::{Context, Result, ensure};
use std::io::{Read, Seek};
use zip::{CompressionMethod, ZipArchive};

pub const MAX_APK_BYTES: u64 = 256 * 1024 * 1024;
const MAX_DEX_BYTES: u64 = 64 * 1024 * 1024;
const MAX_TOTAL_DEX_BYTES: u64 = 256 * 1024 * 1024;

pub fn read_dex(reader: impl Read + Seek) -> Result<Vec<Vec<u8>>> {
    read_dex_with_limits(reader, MAX_DEX_BYTES, MAX_TOTAL_DEX_BYTES)
}

fn read_dex_with_limits(
    reader: impl Read + Seek,
    max_dex: u64,
    max_total: u64,
) -> Result<Vec<Vec<u8>>> {
    let mut archive = ZipArchive::new(reader).context("invalid APK ZIP")?;
    let mut headers = Vec::with_capacity(archive.len());
    let mut dex = Vec::new();
    let mut total = 0_u64;
    for index in 0..archive.len() {
        let entry = archive.by_index_raw(index)?;
        // zip 2.x indexes by name and silently replaces duplicate central-directory
        // entries. Require its retained metadata to cover the directory contiguously,
        // including the first header, to detect those discarded entries.
        let header_size = 46
            + entry.name_raw().len() as u64
            + entry.extra_data().map_or(0, |data| data.len()) as u64
            + entry.comment().len() as u64;
        headers.push((entry.central_header_start(), header_size));
        let name = entry.name();
        if name.contains('/') || !name.starts_with("classes") || !name.ends_with(".dex") {
            continue;
        }
        let order = if name == "classes.dex" {
            1
        } else {
            let number = &name[7..name.len() - 4];
            let order: usize = number.parse().context("invalid DEX entry name")?;
            ensure!(
                order >= 2 && number == order.to_string(),
                "noncanonical DEX entry: {name}"
            );
            order
        };
        ensure!(
            entry.is_file() && !entry.encrypted(),
            "invalid DEX entry: {name}"
        );
        ensure!(
            matches!(
                entry.compression(),
                CompressionMethod::Stored | CompressionMethod::Deflated
            ),
            "unsupported DEX compression: {name}"
        );
        ensure!(
            entry.size() > 0 && entry.size() <= max_dex,
            "DEX size exceeds limit: {name}"
        );
        total = total
            .checked_add(entry.size())
            .context("DEX size overflow")?;
        ensure!(total <= max_total, "total DEX size exceeds limit");
        dex.push((order, index, entry.size()));
    }
    headers.sort_unstable();
    let mut expected_header = archive.central_directory_start();
    for (start, size) in headers {
        ensure!(
            start == expected_header,
            "duplicate or inconsistent APK directory entry"
        );
        expected_header = start
            .checked_add(size)
            .context("APK header size overflow")?;
    }
    dex.sort_unstable_by_key(|entry| entry.0);
    ensure!(!dex.is_empty(), "APK is missing classes.dex");
    let mut buffers = Vec::with_capacity(dex.len());
    for (expected, (order, index, size)) in dex.into_iter().enumerate() {
        ensure!(
            order == expected + 1,
            "APK has a missing or duplicate DEX index"
        );
        let mut entry = archive.by_index(index)?;
        let mut bytes = vec![0u8; size as usize];
        entry
            .read_exact(&mut bytes)
            .context("cannot decompress DEX")?;
        // Probe EOF/CRC without allowing read_to_end to grow beyond the budget.
        ensure!(
            entry.read(&mut [0u8; 1])? == 0,
            "DEX length disagrees with ZIP metadata"
        );
        buffers.push(bytes);
    }
    Ok(buffers)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};
    use zip::{ZipWriter, write::SimpleFileOptions};

    #[test]
    #[ignore = "requires WEKIT_TEST_APK pointing to a built APK"]
    fn reads_built_apk() {
        let path = std::env::var("WEKIT_TEST_APK").expect("set WEKIT_TEST_APK");
        let buffers = read_dex(std::fs::File::open(&path).unwrap()).unwrap();
        assert!(buffers.iter().all(|bytes| bytes.starts_with(b"dex\n")));
        println!(
            "{path}: {} DEX files, {} bytes",
            buffers.len(),
            buffers.iter().map(Vec::len).sum::<usize>()
        );
    }

    fn apk(entries: &[(&str, &[u8])], compression: CompressionMethod) -> Vec<u8> {
        let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
        for (name, data) in entries {
            zip.start_file(
                *name,
                SimpleFileOptions::default().compression_method(compression),
            )
            .unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap().into_inner()
    }

    #[test]
    fn loads_stored_and_deflated_dex_in_numeric_order() {
        for compression in [CompressionMethod::Stored, CompressionMethod::Deflated] {
            let bytes = apk(
                &[
                    ("classes2.dex", b"two"),
                    ("assets/classes.dex", b"ignored"),
                    ("classes.dex", b"one"),
                ],
                compression,
            );
            assert_eq!(
                read_dex(Cursor::new(bytes)).unwrap(),
                [b"one".to_vec(), b"two".to_vec()]
            );
        }
    }

    #[test]
    fn rejects_missing_and_noncanonical_dex() {
        for names in [
            vec!["classes2.dex"],
            vec!["classes.dex", "classes3.dex"],
            vec!["classes.dex", "classes02.dex"],
            vec!["classes.dex", "classes1.dex"],
        ] {
            let entries: Vec<_> = names
                .iter()
                .map(|name| (*name, b"dex".as_slice()))
                .collect();
            assert!(read_dex(Cursor::new(apk(&entries, CompressionMethod::Stored))).is_err());
        }
    }

    #[test]
    fn rejects_duplicate_entries_hidden_by_zip_index() {
        let mut bytes = apk(
            &[("classes.dex", b"one"), ("classee.dex", b"two")],
            CompressionMethod::Stored,
        );
        // Forge equal-length duplicate names in both local and central headers.
        for i in 0..bytes.len() - 10 {
            if &bytes[i..i + 11] == b"classee.dex" {
                bytes[i..i + 11].copy_from_slice(b"classes.dex");
            }
        }
        assert!(
            read_dex(Cursor::new(bytes))
                .unwrap_err()
                .to_string()
                .contains("duplicate")
        );
    }

    #[test]
    fn rejects_crc_corruption_and_truncation() {
        let bytes = apk(
            &[("classes.dex", b"unique-dex-content")],
            CompressionMethod::Stored,
        );
        let mut corrupt = bytes.clone();
        let pos = corrupt
            .windows(18)
            .position(|w| w == b"unique-dex-content")
            .unwrap();
        corrupt[pos] ^= 1;
        assert!(read_dex(Cursor::new(corrupt)).is_err());
        assert!(read_dex(Cursor::new(&bytes[..bytes.len() / 2])).is_err());
    }

    #[test]
    fn enforces_individual_total_and_nonempty_limits() {
        let bytes = apk(
            &[("classes.dex", b"1234"), ("classes2.dex", b"5678")],
            CompressionMethod::Deflated,
        );
        assert!(read_dex_with_limits(Cursor::new(&bytes), 3, 100).is_err());
        assert!(read_dex_with_limits(Cursor::new(&bytes), 10, 7).is_err());
        assert!(
            read_dex(Cursor::new(apk(
                &[("classes.dex", b"")],
                CompressionMethod::Stored
            )))
            .is_err()
        );
    }
}
