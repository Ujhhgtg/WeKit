use super::{Reader, Result, mp4};
use memchr::memchr;
use roxmltree::{Document, Node, ParsingOptions};
use serde::Serialize;

const CAMERA: &str = "http://ns.google.com/photos/1.0/camera/";
const CONTAINER: &str = "http://ns.google.com/photos/1.0/container/";
const ITEM: &str = "http://ns.google.com/photos/1.0/container/item/";
const RDF: &str = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
const XMP_HEADER: &[u8] = b"http://ns.adobe.com/xap/1.0/\0";
const EXTENDED_HEADER: &[u8] = b"http://ns.adobe.com/xmp/extension/\0";

#[derive(Debug, Serialize)]
pub struct Segment {
    pub start: u64,
    pub end: u64,
    pub is_xmp: bool,
    pub is_mpf: bool,
    pub is_extended_xmp: bool,
}

#[derive(Debug, Serialize)]
pub struct Jpeg {
    pub image_end: u64,
    pub segments: Vec<Segment>,
    pub xmp: Option<String>,
    pub has_extended_xmp: bool,
    pub has_mpf: bool,
    pub multi_image: bool,
    pub has_gain_map: bool,
}

#[derive(Debug, Serialize)]
pub struct PhotoInfo {
    pub video_offset: u64,
    pub video_length: u64,
    pub cover_timestamp_ms: i64,
    pub image_end: u64,
    pub cover_timestamp_us: i64,
}

pub fn parse(reader: &Reader, headers_only: bool, discard_unusable_metadata: bool) -> Result<Jpeg> {
    parse_impl(reader, headers_only, discard_unusable_metadata, true)
}

fn parse_impl(
    reader: &Reader,
    headers_only: bool,
    discard_unusable_metadata: bool,
    validate_xml: bool,
) -> Result<Jpeg> {
    let mut cursor = Cursor::new(reader, headers_only);
    if cursor.byte()? != 0xff || cursor.byte()? != 0xd8 {
        return Err("Only JPEG covers are supported".into());
    }
    let mut jpeg = Jpeg {
        image_end: 0,
        segments: Vec::new(),
        xmp: None,
        has_extended_xmp: false,
        has_mpf: false,
        multi_image: false,
        has_gain_map: false,
    };
    let mut in_scan = false;
    let mut scanned = false;
    let mut frame = false;
    let mut metadata_size = 0usize;
    let mut unusable_xmp = false;
    let mut marker_count = 0usize;
    loop {
        let (start, marker) = if in_scan {
            in_scan = false;
            cursor.entropy_marker()?
        } else {
            let start = cursor.position;
            if cursor.byte()? != 0xff {
                return Err("Invalid JPEG marker".into());
            }
            (start, cursor.marker_byte()?)
        };
        marker_count += 1;
        if marker_count > 10_000 {
            return Err("Too many JPEG segments".into());
        }
        if marker == 0xd9 {
            if !frame || !scanned {
                return Err("JPEG has no image frame".into());
            }
            jpeg.image_end = cursor.position;
            return Ok(jpeg);
        }
        if marker == 0 || marker == 0xd8 || (0xd0..=0xd7).contains(&marker) {
            return Err("Unexpected JPEG marker".into());
        }
        if marker == 1 {
            continue;
        }
        let length = ((cursor.byte()? as usize) << 8) | cursor.byte()? as usize;
        if length < 2 {
            return Err("Invalid JPEG segment length".into());
        }
        let payload_offset = cursor.position;
        let end = add(payload_offset, (length - 2) as u64)?;
        if end > reader.len() {
            return Err("Truncated JPEG segment".into());
        }
        let mut segment = Segment {
            start,
            end,
            is_xmp: false,
            is_mpf: false,
            is_extended_xmp: false,
        };
        if marker == 0xe1 || marker == 0xe2 {
            metadata_size += length;
            if metadata_size > 4 * 1024 * 1024 {
                return Err("JPEG metadata exceeds the supported limit".into());
            }
            let payload = reader.read(payload_offset, length - 2)?;
            if marker == 0xe1 && payload.starts_with(XMP_HEADER) {
                segment.is_xmp = true;
                if jpeg.xmp.is_some() {
                    if !discard_unusable_metadata {
                        return Err("Multiple standard XMP packets are unsupported".into());
                    }
                    unusable_xmp = true;
                }
                if !unusable_xmp {
                    let packet = decode_xmp(&payload[XMP_HEADER.len()..]).and_then(|packet| {
                        if validate_xml {
                            let document = xml(&packet)?;
                            jpeg.has_gain_map = unsupported_auxiliary_doc(&document);
                        }
                        Ok(packet)
                    });
                    match packet {
                        Ok(packet) => jpeg.xmp = Some(packet),
                        Err(_) if discard_unusable_metadata => unusable_xmp = true,
                        Err(error) => return Err(error),
                    }
                }
                if unusable_xmp {
                    jpeg.xmp = None;
                    jpeg.has_gain_map = false;
                }
            } else if marker == 0xe1 && payload.starts_with(EXTENDED_HEADER) {
                segment.is_extended_xmp = true;
                jpeg.has_extended_xmp = true;
            } else if marker == 0xe2 && payload.starts_with(b"MPF\0") {
                segment.is_mpf = true;
                jpeg.has_mpf = true;
                if !discard_unusable_metadata {
                    jpeg.multi_image |= mpf_image_count(&payload)? != 1;
                }
            }
        }
        if matches!(marker, 0xc0..=0xc2) {
            if length < 8 {
                return Err("Invalid JPEG frame".into());
            }
            let dimensions = reader.read(payload_offset, 5)?;
            if dimensions[1..3] == [0, 0] || dimensions[3..5] == [0, 0] {
                return Err("JPEG has zero dimensions".into());
            }
            frame = true;
        }
        jpeg.segments.push(segment);
        cursor.position = end;
        if marker == 0xda {
            if headers_only {
                if !frame {
                    return Err("JPEG has no image frame".into());
                }
                // Header-only classification deliberately leaves image_end=0 unknown;
                // it is not an extraction/export bound and does not validate entropy.
                return Ok(jpeg);
            }
            scanned = true;
            in_scan = true;
        }
    }
}

pub fn has_motion_metadata(reader: &Reader) -> Result<bool> {
    if !is_jpeg(reader)? {
        return Ok(false);
    }
    // Adobe XMP Part 3 section 1.1.3 places XMP before SOF, with tolerance only
    // through the first SOS. JPEG late-APP1 remains supported by full inspection.
    let jpeg = parse_impl(reader, true, false, false)?;
    let Some(packet) = jpeg.xmp.as_deref() else {
        return Ok(false);
    };
    let doc = xml(packet)?;
    let modern = property(&doc, CAMERA, "MotionPhoto")?;
    let legacy = property(&doc, CAMERA, "MicroVideo")?;
    Ok(modern
        .as_deref()
        .map_or(legacy.as_deref() == Some("1"), |flag| flag == "1"))
}

pub fn inspect(reader: &Reader) -> Result<Option<PhotoInfo>> {
    if !is_jpeg(reader)? {
        return Ok(None);
    }
    let jpeg = parse_impl(reader, false, false, false)?;
    let Some(packet) = jpeg.xmp.as_deref() else {
        return Ok(None);
    };
    let doc = xml(packet)?;
    let modern = property(&doc, CAMERA, "MotionPhoto")?;
    let legacy = property(&doc, CAMERA, "MicroVideo")?;
    if modern.as_deref().is_some_and(|flag| flag != "1")
        || (modern.is_none() && legacy.as_deref() != Some("1"))
    {
        return Ok(None);
    }
    if jpeg.has_extended_xmp || jpeg.multi_image {
        return Err("Extended XMP and multi-image JPEG are unsupported".into());
    }
    if unsupported_auxiliary_doc(&doc) {
        return Err("Gain map and auxiliary JPEG items are unsupported".into());
    }
    let (offset, length, timestamp) = if modern.as_deref() == Some("1") {
        if property(&doc, CAMERA, "MotionPhotoVersion")?.as_deref() != Some("1") {
            return Err("Unsupported Motion Photo version".into());
        }
        let directories: Vec<_> = doc
            .descendants()
            .filter(|node| element(*node, CONTAINER, "Directory"))
            .collect();
        if directories.len() != 1 {
            return Err("Motion Photo needs exactly one directory".into());
        }
        let sequences: Vec<_> = directories[0]
            .descendants()
            .filter(|node| element(*node, RDF, "Seq"))
            .collect();
        if sequences.len() != 1 {
            return Err("Motion Photo directory must be an ordered array".into());
        }
        let entries: Vec<_> = sequences[0].children().filter(Node::is_element).collect();
        if entries.len() != 2 || entries.iter().any(|node| !element(*node, RDF, "li")) {
            return Err("Only a primary JPEG and one motion video are supported".into());
        }
        let mut items = Vec::with_capacity(2);
        for entry in entries {
            let children: Vec<_> = entry.children().filter(Node::is_element).collect();
            if children.len() != 1 || !element(children[0], CONTAINER, "Item") {
                return Err("Invalid Motion Photo directory item".into());
            }
            items.push(children[0]);
        }
        let primary = items[0];
        let video = items[1];
        if field(primary, "Mime")?.as_deref() != Some("image/jpeg")
            || field(primary, "Semantic")?.as_deref() != Some("Primary")
            || field(primary, "Length")?
                .as_deref()
                .map(|value| nonnegative(value, "primary length"))
                .transpose()?
                .unwrap_or(0)
                != 0
            || field(video, "Mime")?.as_deref() != Some("video/mp4")
            || field(video, "Semantic")?.as_deref() != Some("MotionPhoto")
        {
            return Err("Unsupported Motion Photo directory contents".into());
        }
        // FIXME: OPlus emits non-standard secondary Padding=0. Zero adds no bytes;
        //        reject nonzero padding. Restore strict standard behavior by rejecting any
        //        secondary Padding, including zero, instead of this compatibility branch.
        if let Some(value) = field(video, "Padding")? {
            if nonnegative(&value, "video padding")? != 0 {
                return Err("Secondary video padding is unsupported".into());
            }
        }
        let length = positive(
            &field(video, "Length")?.ok_or("Missing video length")?,
            "video length",
        )?;
        let padding = field(primary, "Padding")?
            .as_deref()
            .map(|value| nonnegative(value, "image padding"))
            .transpose()?
            .unwrap_or(0);
        let offset = add(jpeg.image_end, padding)?;
        if add(offset, length)? != reader.len() {
            return Err("Motion Photo directory does not match file bounds".into());
        }
        (
            offset,
            length,
            timestamp(property(&doc, CAMERA, "MotionPhotoPresentationTimestampUs")?.as_deref())?,
        )
    } else {
        if property(&doc, CAMERA, "MicroVideoVersion")?.as_deref() != Some("1") {
            return Err("Unsupported MicroVideo version".into());
        }
        let length = positive(
            &property(&doc, CAMERA, "MicroVideoOffset")?.ok_or("Missing MicroVideo offset")?,
            "MicroVideo offset",
        )?;
        if length > reader.len() - jpeg.image_end {
            return Err("MicroVideo overlaps the JPEG image".into());
        }
        (
            reader.len() - length,
            length,
            timestamp(property(&doc, CAMERA, "MicroVideoPresentationTimestampUs")?.as_deref())?,
        )
    };
    if let Some(metadata) = mp4::analyze(reader, offset, length)? {
        if metadata
            .frames
            .iter()
            .any(|frame| frame.timestamp_us != timestamp)
        {
            return Err("XMP and video metadata timestamps disagree".into());
        }
    }
    Ok(Some(PhotoInfo {
        video_offset: offset,
        video_length: length,
        cover_timestamp_ms: if timestamp == -1 {
            -1
        } else {
            timestamp / 1000
        },
        image_end: jpeg.image_end,
        cover_timestamp_us: timestamp,
    }))
}

fn is_jpeg(reader: &Reader) -> Result<bool> {
    Ok(reader.len() >= 2 && reader.u16(0)? == 0xffd8)
}

fn decode_xmp(bytes: &[u8]) -> Result<String> {
    // FIXME: An observed producer pads a complete UTF-8 XMP packet with trailing
    //        NUL bytes. Trim only that suffix; interior NUL/UTF-16/32 remain rejected.
    //        Strict XMP would reject all NUL bytes instead of this compatibility exception.
    let end = bytes
        .iter()
        .rposition(|byte| *byte != 0)
        .map_or(0, |offset| offset + 1);
    let packet = &bytes[..end];
    if memchr(0, packet).is_some() {
        return Err("XMP must use UTF-8 encoding".into());
    }
    let text = std::str::from_utf8(packet).map_err(|_| "XMP must use UTF-8 encoding")?;
    check_declarations(text)?;
    Ok(text.into())
}

fn check_declarations(text: &str) -> Result<()> {
    if text
        .as_bytes()
        .windows(9)
        .any(|part| part.eq_ignore_ascii_case(b"<!DOCTYPE"))
        || text
            .as_bytes()
            .windows(8)
            .any(|part| part.eq_ignore_ascii_case(b"<!ENTITY"))
    {
        return Err("XMP external/entity declarations are forbidden".into());
    }
    Ok(())
}

fn xml(text: &str) -> Result<Document<'_>> {
    check_declarations(text)?;
    Document::parse_with_options(
        text,
        ParsingOptions {
            allow_dtd: false,
            nodes_limit: 65_536,
            entity_resolver: None,
        },
    )
    .map_err(|error| format!("Malformed XMP: {error}"))
}

fn unsupported_auxiliary_doc(doc: &Document<'_>) -> bool {
    let mut items = 0;
    for node in doc.descendants().filter(Node::is_element) {
        let namespace = node.tag_name().namespace().unwrap_or_default();
        if namespace.contains("hdr-gain-map")
            || namespace.contains("hdrgm")
            || node.attributes().any(|attribute| {
                let namespace = attribute.namespace().unwrap_or_default();
                namespace.contains("hdr-gain-map") || namespace.contains("hdrgm")
            })
        {
            return true;
        }
        if element(node, CONTAINER, "Item") {
            items += 1;
            if node
                .attribute((ITEM, "Semantic"))
                .is_some_and(|value| value.trim() == "GainMap")
                || node.children().any(|child| {
                    element(child, ITEM, "Semantic") && text_content(child).trim() == "GainMap"
                })
            {
                return true;
            }
        }
    }
    items > 2
}

fn property(doc: &Document<'_>, namespace: &str, name: &str) -> Result<Option<String>> {
    let mut values = Vec::new();
    for node in doc.descendants().filter(Node::is_element) {
        if let Some(value) = node.attribute((namespace, name)) {
            values.push(value.trim().to_owned());
        }
        if element(node, namespace, name) {
            values.push(text_content(node).trim().to_owned());
        }
    }
    if values.len() > 1 {
        return Err(format!("Duplicate XMP property: {name}"));
    }
    Ok(values.pop())
}

fn field(node: Node<'_, '_>, name: &str) -> Result<Option<String>> {
    let mut values = Vec::new();
    if let Some(value) = node.attribute((ITEM, name)) {
        values.push(value.trim().to_owned());
    }
    for child in node.children().filter(|child| element(*child, ITEM, name)) {
        values.push(text_content(child).trim().to_owned());
    }
    if values.len() > 1 {
        return Err(format!("Duplicate directory field: {name}"));
    }
    Ok(values.pop())
}

fn element(node: Node<'_, '_>, namespace: &str, name: &str) -> bool {
    node.is_element()
        && node.tag_name().namespace() == Some(namespace)
        && node.tag_name().name() == name
}

fn text_content(node: Node<'_, '_>) -> String {
    node.descendants()
        .filter(Node::is_text)
        .filter_map(|node| node.text())
        .collect()
}

fn timestamp(value: Option<&str>) -> Result<i64> {
    let value = value
        .map(|value| {
            value
                .parse::<i64>()
                .map_err(|_| "Invalid cover timestamp".to_owned())
        })
        .transpose()?
        .unwrap_or(-1);
    if value < -1 {
        return Err("Invalid cover timestamp".into());
    }
    Ok(value)
}

fn nonnegative(value: &str, label: &str) -> Result<u64> {
    value
        .parse::<i64>()
        .ok()
        .filter(|value| *value >= 0)
        .map(|value| value as u64)
        .ok_or_else(|| format!("Invalid {label}"))
}

fn positive(value: &str, label: &str) -> Result<u64> {
    nonnegative(value, label).and_then(|value| {
        if value == 0 {
            Err(format!("Invalid {label}"))
        } else {
            Ok(value)
        }
    })
}

fn add(first: u64, second: u64) -> Result<u64> {
    first
        .checked_add(second)
        .ok_or_else(|| "File offset overflow".into())
}

fn mpf_image_count(payload: &[u8]) -> Result<u32> {
    if payload.len() < 12 {
        return Err("Truncated MPF header".into());
    }
    let little = match &payload[4..6] {
        b"MM" => false,
        b"II" => true,
        _ => return Err("Invalid MPF byte order".into()),
    };
    let u16_at = |offset: usize| -> Result<u16> {
        let bytes: [u8; 2] = payload
            .get(offset..offset + 2)
            .ok_or("Truncated MPF IFD")?
            .try_into()
            .unwrap();
        Ok(if little {
            u16::from_le_bytes(bytes)
        } else {
            u16::from_be_bytes(bytes)
        })
    };
    let u32_at = |offset: usize| -> Result<u32> {
        let bytes: [u8; 4] = payload
            .get(offset..offset + 4)
            .ok_or("Truncated MPF IFD")?
            .try_into()
            .unwrap();
        Ok(if little {
            u32::from_le_bytes(bytes)
        } else {
            u32::from_be_bytes(bytes)
        })
    };
    if u16_at(6)? != 42 {
        return Err("Invalid MPF TIFF header".into());
    }
    let ifd = u32_at(8)? as u64 + 4;
    if ifd > payload.len() as u64 - 2 {
        return Err("MPF IFD is out of bounds".into());
    }
    let count = u16_at(ifd as usize)? as usize;
    if ifd + 2 + count as u64 * 12 + 4 > payload.len() as u64 {
        return Err("Truncated MPF IFD".into());
    }
    for index in 0..count {
        let offset = ifd as usize + 2 + index * 12;
        if u16_at(offset)? == 0xb001 {
            if u16_at(offset + 2)? != 4 || u32_at(offset + 4)? != 1 {
                return Err("Invalid MPF image count".into());
            }
            return u32_at(offset + 8);
        }
    }
    Err("MPF has no image count".into())
}

struct Cursor<'r> {
    reader: &'r Reader,
    position: u64,
    start: u64,
    buffer_size: u64,
    buffer: Vec<u8>,
}

impl<'r> Cursor<'r> {
    fn new(reader: &'r Reader, headers_only: bool) -> Self {
        Self {
            reader,
            position: 0,
            start: 0,
            buffer_size: if headers_only { 4096 } else { 65_536 },
            buffer: Vec::new(),
        }
    }
    fn ensure(&mut self) -> Result<()> {
        if self.position < self.start || self.position >= self.start + self.buffer.len() as u64 {
            if self.position >= self.reader.len() {
                return Err("Truncated JPEG image".into());
            }
            self.start = self.position;
            self.buffer = self.reader.read(
                self.position,
                (self.reader.len() - self.position).min(self.buffer_size) as usize,
            )?;
        }
        Ok(())
    }
    fn byte(&mut self) -> Result<u8> {
        self.ensure()?;
        let value = self.buffer[(self.position - self.start) as usize];
        self.position += 1;
        Ok(value)
    }
    fn marker_byte(&mut self) -> Result<u8> {
        loop {
            let value = self.byte()?;
            if value != 0xff {
                return Ok(value);
            }
        }
    }
    fn entropy_marker(&mut self) -> Result<(u64, u8)> {
        loop {
            self.ensure()?;
            let local = (self.position - self.start) as usize;
            let Some(found) = memchr(0xff, &self.buffer[local..]) else {
                self.position = self.start + self.buffer.len() as u64;
                continue;
            };
            self.position += found as u64;
            let start = self.position;
            self.position += 1;
            let marker = self.marker_byte()?;
            if marker == 0 || (0xd0..=0xd7).contains(&marker) {
                continue;
            }
            return Ok((start, marker));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::livephoto::fixture_reader;
    use std::os::fd::AsRawFd;

    const JPEG: &[u8] = include_bytes!("../../../../../test/resources/livephoto/cover.jpg");
    const VIDEO: &[u8] = include_bytes!("../../../../../test/resources/livephoto/video.mp4");

    fn modern(
        length: usize,
        timestamp: Option<&str>,
        padding: usize,
        video_padding: Option<usize>,
    ) -> String {
        format!(
            r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><r:RDF xmlns:r="{RDF}"><r:Description r:about="" xmlns:c="{CAMERA}" xmlns:d="{CONTAINER}" xmlns:i="{ITEM}" c:MotionPhoto="1" c:MotionPhotoVersion="1" {}><d:Directory><r:Seq><r:li r:parseType="Resource"><d:Item i:Mime="image/jpeg" i:Semantic="Primary" i:Length="0" i:Padding="{padding}"/></r:li><r:li r:parseType="Resource"><d:Item i:Mime="video/mp4" i:Semantic="MotionPhoto" i:Length="{length}" {}/></r:li></r:Seq></d:Directory></r:Description></r:RDF></x:xmpmeta>"#,
            timestamp
                .map(|time| format!("c:MotionPhotoPresentationTimestampUs=\"{time}\""))
                .unwrap_or_default(),
            video_padding
                .map(|value| format!("i:Padding=\"{value}\""))
                .unwrap_or_default()
        )
    }
    fn legacy(length: usize) -> String {
        format!(
            r#"<x:xmpmeta xmlns:x="adobe:ns:meta/"><r:RDF xmlns:r="{RDF}"><r:Description xmlns:c="{CAMERA}" c:MicroVideo="1" c:MicroVideoVersion="1" c:MicroVideoOffset="{length}" c:MicroVideoPresentationTimestampUs="875000"/></r:RDF></x:xmpmeta>"#
        )
    }
    fn segment(image: &[u8], marker: u8, payload: &[u8]) -> Vec<u8> {
        let mut output = image[..2].to_vec();
        output.extend([0xff, marker]);
        output.extend(((payload.len() + 2) as u16).to_be_bytes());
        output.extend(payload);
        output.extend(&image[2..]);
        output
    }
    fn with_xmp(image: &[u8], packet: &[u8]) -> Vec<u8> {
        let mut payload = XMP_HEADER.to_vec();
        payload.extend(packet);
        segment(image, 0xe1, &payload)
    }
    fn photo(packet: &str, padding: usize, video: &[u8]) -> Vec<u8> {
        let mut output = with_xmp(JPEG, packet.as_bytes());
        output.extend(vec![0; padding]);
        output.extend(video);
        output
    }
    fn header_end(image: &[u8]) -> usize {
        let start = image
            .windows(2)
            .position(|bytes| bytes == [0xff, 0xda])
            .unwrap();
        start + 2 + u16::from_be_bytes(image[start + 2..start + 4].try_into().unwrap()) as usize
    }

    fn small_reader(bytes: &[u8]) -> (std::fs::File, Reader) {
        let (file, _) = fixture_reader(bytes);
        let reader = Reader::with_header_cache(file.as_raw_fd(), bytes.len() as u64);
        (file, reader)
    }

    #[test]
    fn real_modern_photo_namespace_aliases_padding_and_exact_microseconds() {
        let bytes = photo(&modern(VIDEO.len(), Some("1234567"), 37, None), 37, VIDEO);
        let (_file, reader) = fixture_reader(&bytes);
        assert!(has_motion_metadata(&reader).unwrap());
        let info = inspect(&reader).unwrap().unwrap();
        assert_eq!(info.cover_timestamp_us, 1_234_567);
        assert_eq!(info.cover_timestamp_ms, 1234);
        assert_eq!(info.image_end + 37, info.video_offset);
        assert_eq!(
            reader
                .read(info.video_offset, info.video_length as usize)
                .unwrap(),
            VIDEO
        );
    }

    #[test]
    fn header_candidate_does_not_validate_missing_entropy_but_full_inspection_does() {
        let empty = &JPEG[..header_end(JPEG)];
        let (_file, reader) = fixture_reader(empty);
        assert!(!has_motion_metadata(&reader).unwrap());
        assert!(inspect(&reader).is_err());
        let candidate = with_xmp(JPEG, modern(VIDEO.len(), Some("0"), 0, None).as_bytes());
        let (_file, reader) = fixture_reader(&candidate[..header_end(&candidate)]);
        assert!(has_motion_metadata(&reader).unwrap());
        assert_eq!(parse(&reader, true, false).unwrap().image_end, 0);
        assert!(inspect(&reader).is_err());
    }

    #[test]
    fn small_header_cache_reads_complete_long_xmp_after_skipped_segments() {
        // Put the camera properties beyond the small read buffer, after a large
        // non-metadata segment. Neither the segment skip nor XMP may stop at 4 KiB.
        let packet = format!(
            "{}{}",
            " ".repeat(16_384),
            modern(VIDEO.len(), Some("1234567"), 0, None)
        );
        let bytes = segment(&photo(&packet, 0, VIDEO), 0xe0, &vec![0x11; 10_000]);
        let (_file, reader) = small_reader(&bytes);
        assert!(has_motion_metadata(&reader).unwrap());
        assert_eq!(
            inspect(&reader).unwrap().unwrap().cover_timestamp_us,
            1_234_567
        );

        // XML errors past the initial buffer must remain classification errors.
        let malformed = packet.replace("</r:RDF>", "</wrong>");
        let bytes = segment(&photo(&malformed, 0, VIDEO), 0xe0, &vec![0x11; 10_000]);
        let (_file, reader) = small_reader(&bytes);
        assert!(has_motion_metadata(&reader).is_err());
    }

    #[test]
    fn small_header_cache_preserves_split_markers_lengths_and_truncation_errors() {
        let packet = modern(VIDEO.len(), Some("0"), 0, None);
        // The next APP1 starts at 4093 or 4095: respectively its two-byte length
        // or FF/marker pair straddles the small Cursor buffer boundary.
        for prefix_length in [4087, 4089] {
            let bytes = segment(&photo(&packet, 0, VIDEO), 0xe0, &vec![0x11; prefix_length]);
            let (_file, reader) = small_reader(&bytes);
            assert!(has_motion_metadata(&reader).unwrap());
            assert_eq!(parse(&reader, true, false).unwrap().image_end, 0);

            let xmp_end = 2 + 4 + prefix_length + 4 + XMP_HEADER.len() + packet.len();
            for end in [4096, xmp_end - 1, header_end(&bytes) - 1] {
                let (_file, reader) = small_reader(&bytes[..end]);
                assert!(has_motion_metadata(&reader).is_err());
            }
        }
    }

    #[test]
    fn entropy_marker_search_crosses_buffers_and_ignores_stuffing_restart_and_fill() {
        // Marker-level fixture, not a pixel-decoding claim: exercise the buffered
        // scanner with escape/restart sequences crossing 64-KiB read boundaries.
        let mut bytes = JPEG[..header_end(JPEG)].to_vec();
        bytes.resize(196_607, 0x11);
        bytes[65_535] = 0xff;
        bytes[65_536] = 0;
        bytes[131_071] = 0xff;
        bytes[131_072] = 0xd3;
        bytes.extend([0xff, 0xff, 0xff, 0xd9]);
        let end = bytes.len() as u64;
        bytes.extend(b"unclassified tail");
        let (_file, reader) = fixture_reader(&bytes);
        assert_eq!(parse(&reader, false, false).unwrap().image_end, end);
    }

    #[test]
    fn legacy_is_independent_and_explicit_modern_zero_overrides_it() {
        let old = legacy(VIDEO.len());
        let (_file, reader) = fixture_reader(&photo(&old, 0, VIDEO));
        assert_eq!(inspect(&reader).unwrap().unwrap().cover_timestamp_ms, 875);
        let disabled = old.replace(
            "c:MicroVideo=\"1\"",
            "c:MotionPhoto=\"0\" c:MicroVideo=\"1\"",
        );
        let (_file, reader) = fixture_reader(&photo(&disabled, 0, VIDEO));
        assert!(!has_motion_metadata(&reader).unwrap());
        assert!(inspect(&reader).unwrap().is_none());
    }

    #[test]
    fn xmp_tail_nul_compatibility_keeps_interior_nul_utf16_and_entity_rejection() {
        let packet = modern(VIDEO.len(), Some("0"), 0, None);
        let wrapped = format!("<?xpacket begin=\"\u{feff}\"?>{packet}<?xpacket end=\"w\"?>\0\0");
        let bytes = photo(&wrapped, 0, VIDEO);
        let (_file, reader) = fixture_reader(&bytes);
        assert!(inspect(&reader).unwrap().is_some());
        let bad = [
            packet.replace("c:MotionPhoto=", "\0c:MotionPhoto="),
            format!("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>{packet}"),
        ];
        for text in bad {
            let bytes = with_xmp(JPEG, text.as_bytes());
            let (_file, reader) = fixture_reader(&bytes);
            assert!(has_motion_metadata(&reader).is_err());
        }
        let utf16: Vec<u8> = packet.encode_utf16().flat_map(u16::to_le_bytes).collect();
        let bytes = with_xmp(JPEG, &utf16);
        let (_file, reader) = fixture_reader(&bytes);
        assert!(parse(&reader, false, false).is_err());
        let discarded = parse(&reader, false, true).unwrap();
        assert!(discarded.xmp.is_none());
        assert!(discarded.segments.iter().any(|segment| segment.is_xmp));
    }

    #[test]
    fn secure_namespace_matching_rejects_duplicate_properties_and_fake_camera_namespace() {
        let packet = modern(VIDEO.len(), Some("0"), 0, None);
        let fake = packet.replace(CAMERA, "urn:not-camera");
        let (_file, reader) = fixture_reader(&photo(&fake, 0, VIDEO));
        assert!(!has_motion_metadata(&reader).unwrap());
        assert!(inspect(&reader).unwrap().is_none());
        let duplicate = packet.replace(
            "</r:Description>",
            "<c:MotionPhoto>1</c:MotionPhoto></r:Description>",
        );
        let (_file, reader) = fixture_reader(&photo(&duplicate, 0, VIDEO));
        assert!(has_motion_metadata(&reader).is_err());
    }

    #[test]
    fn wrong_lengths_stripped_video_and_truncated_segments_stay_failures() {
        let cases = [
            photo(&modern(VIDEO.len() + 1, Some("0"), 0, None), 0, VIDEO),
            photo(&modern(VIDEO.len(), Some("0"), 0, None), 0, &[]),
            photo(&legacy(usize::MAX), 0, VIDEO),
        ];
        for bytes in cases {
            let (_file, reader) = fixture_reader(&bytes);
            assert!(inspect(&reader).is_err());
        }
        let (_file, reader) = fixture_reader(&[0xff, 0xd8, 0xff, 0xe1, 0xff, 0xff, 1]);
        assert!(parse(&reader, false, true).is_err());
    }

    #[test]
    fn secondary_padding_zero_is_explicitly_compatible_but_nonzero_rejected() {
        for (padding, accepted) in [(0, true), (1, false)] {
            let bytes = photo(&modern(VIDEO.len(), Some("0"), 0, Some(padding)), 0, VIDEO);
            let (_file, reader) = fixture_reader(&bytes);
            assert_eq!(inspect(&reader).is_ok(), accepted);
        }
    }

    #[test]
    fn unsupported_hdr_and_extended_xmp_remain_visible() {
        let packet = modern(VIDEO.len(), Some("0"), 0, None);
        let hdr = packet.replace(
            "c:MotionPhoto=",
            "xmlns:h=\"http://ns.adobe.com/hdr-gain-map/1.0/\" h:Version=\"1.0\" c:MotionPhoto=",
        );
        let (_file, reader) = fixture_reader(&photo(&hdr, 0, VIDEO));
        assert!(parse(&reader, true, false).unwrap().has_gain_map);
        assert!(inspect(&reader).is_err());
        let mut extended = segment(&with_xmp(JPEG, packet.as_bytes()), 0xe1, EXTENDED_HEADER);
        extended.extend(VIDEO);
        let (_file, reader) = fixture_reader(&extended);
        assert!(parse(&reader, true, false).unwrap().has_extended_xmp);
        assert!(inspect(&reader).is_err());
    }

    #[test]
    fn mpf_index_preserves_endianness_count_bounds_and_multi_image_state() {
        for little in [false, true] {
            let mut mpf = b"MPF\0".to_vec();
            mpf.extend(if little { b"II" } else { b"MM" });
            let short = |value: u16| {
                if little {
                    value.to_le_bytes()
                } else {
                    value.to_be_bytes()
                }
            };
            let int = |value: u32| {
                if little {
                    value.to_le_bytes()
                } else {
                    value.to_be_bytes()
                }
            };
            mpf.extend(short(42));
            mpf.extend(int(8));
            mpf.extend(short(1));
            mpf.extend(short(0xb001));
            mpf.extend(short(4));
            mpf.extend(int(1));
            mpf.extend(int(2));
            mpf.extend(int(0));
            let bytes = segment(
                &photo(&modern(VIDEO.len(), Some("0"), 0, None), 0, VIDEO),
                0xe2,
                &mpf,
            );
            let (_file, reader) = fixture_reader(&bytes);
            let parsed = parse(&reader, false, false).unwrap();
            assert!(parsed.has_mpf && parsed.multi_image);
            assert!(inspect(&reader).is_err());
            assert!(!parse(&reader, false, true).unwrap().multi_image);
        }
    }

    #[test]
    fn missing_timestamp_stays_minus_one_and_invalid_negative_is_not_clamped() {
        for value in [None, Some("-1"), Some("0")] {
            let (_file, reader) =
                fixture_reader(&photo(&modern(VIDEO.len(), value, 0, None), 0, VIDEO));
            assert_eq!(
                inspect(&reader).unwrap().unwrap().cover_timestamp_us,
                if value == Some("0") { 0 } else { -1 }
            );
        }
        let (_file, reader) =
            fixture_reader(&photo(&modern(VIDEO.len(), Some("-2"), 0, None), 0, VIDEO));
        assert!(inspect(&reader).is_err());
    }

    #[test]
    fn timed_scoring_track_requires_exact_microsecond_match_including_unspecified_time() {
        let video = mp4::scored_video_fixture(1_234_567);
        let (_file, reader) = fixture_reader(&photo(
            &modern(video.len(), Some("1234567"), 0, None),
            0,
            &video,
        ));
        assert_eq!(
            inspect(&reader).unwrap().unwrap().cover_timestamp_us,
            1_234_567
        );
        for timestamp in [Some("1234000"), None] {
            let (_file, reader) =
                fixture_reader(&photo(&modern(video.len(), timestamp, 0, None), 0, &video));
            assert!(inspect(&reader).is_err());
        }
        let video = mp4::scored_video_fixture(-1);
        let (_file, reader) =
            fixture_reader(&photo(&modern(video.len(), None, 0, None), 0, &video));
        assert_eq!(inspect(&reader).unwrap().unwrap().cover_timestamp_us, -1);
    }
}
