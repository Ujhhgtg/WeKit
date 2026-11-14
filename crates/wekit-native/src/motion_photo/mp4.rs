//! MP4 structure and ISO MotionPhotoMetadataDescriptor parsing on a borrowed descriptor.
use super::{Reader, Result};
use serde::Serialize;
use std::collections::HashMap;

const MIME: &str = "application/motionphoto-image-meta";
const MAX_SAMPLES: usize = 4096;
const MAX_SAMPLE_BYTES: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, Serialize)]
pub struct BoxInfo {
    pub kind: String,
    pub start: u64,
    pub payload: u64,
    pub end: u64,
}

#[derive(Debug, Serialize)]
pub struct Metadata {
    pub frames: Vec<Frame>,
}

#[derive(Debug, Serialize)]
pub struct Analysis {
    pub boxes: Vec<BoxInfo>,
    pub metadata: Option<Metadata>,
}

#[derive(Debug, Serialize)]
pub struct Frame {
    /// Byte position relative to the beginning of this MP4, not its containing JPEG.
    pub offset: u64,
    pub timestamp_us: i64,
}

#[derive(Debug)]
struct Sample {
    // Other fragment tracks may temporarily have negative signed data offsets.
    offset: i64,
    size: usize,
}

pub fn boxes(reader: &Reader, start: u64, end: u64) -> Result<Vec<BoxInfo>> {
    if start > end || end > reader.len() || end > i64::MAX as u64 {
        return Err("Invalid MP4 box bounds".into());
    }
    let mut result = Vec::new();
    let mut offset = start;
    while offset < end {
        if result.len() >= 10000 || end - offset < 8 {
            return Err("Invalid MP4 box layout".into());
        }
        let size32 = reader.u32(offset)?;
        let kind = ascii(&reader.read(offset + 4, 4)?);
        let header_size = if size32 == 1 { 16 } else { 8 };
        let size = match size32 {
            0 => end - offset,
            1 => {
                if end - offset < 16 {
                    return Err("Truncated extended MP4 box".into());
                }
                reader.u64(offset + 8)?
            }
            _ => u64::from(size32),
        };
        if size < header_size || size > end - offset {
            return Err("MP4 box exceeds container bounds".into());
        }
        result.push(BoxInfo {
            kind,
            start: offset,
            payload: offset + header_size,
            end: offset + size,
        });
        offset += size;
    }
    Ok(result)
}

pub fn inspect(reader: &Reader, offset: u64, length: u64) -> Result<Option<Metadata>> {
    let top = bounded_boxes(reader, offset, length, "Invalid MP4 metadata bounds")?;
    let movie = movie_children(reader, &top, "MP4 metadata needs one movie box")?;
    inspect_top(reader, offset, &top, &movie)
}

/// Combined structural and scoring checks, sharing top-level and movie box parsing.
pub fn analyze(reader: &Reader, offset: u64, length: u64) -> Result<Option<Metadata>> {
    Ok(analyze_layout(reader, offset, length)?.metadata)
}

pub fn analyze_layout(reader: &Reader, offset: u64, length: u64) -> Result<Analysis> {
    let top = bounded_boxes(reader, offset, length, "Invalid MP4 bounds")?;
    let movie = validate_top(reader, &top)?;
    let metadata = inspect_top(reader, offset, &top, &movie)?;
    Ok(Analysis {
        boxes: top,
        metadata,
    })
}

fn bounded_boxes(reader: &Reader, offset: u64, length: u64, message: &str) -> Result<Vec<BoxInfo>> {
    if length < 8 || length > reader.len() || offset > reader.len() - length {
        return Err(message.into());
    }
    boxes(reader, offset, offset + length)
}

fn validate_top(reader: &Reader, top: &[BoxInfo]) -> Result<Vec<BoxInfo>> {
    let ftyp = top
        .first()
        .filter(|item| item.kind == "ftyp")
        .ok_or("Video must start with an MP4 ftyp box")?;
    let size = ftyp.end - ftyp.payload;
    if size < 8 || size % 4 != 0 || size > 4096 {
        return Err("Invalid MP4 file type".into());
    }
    let brands = reader.read(ftyp.payload, size as usize)?;
    let supported = [
        "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "hvc1", "av01",
    ];
    if !brands
        .as_chunks::<4>()
        .0
        .iter()
        .enumerate()
        .any(|(index, brand)| index != 1 && supported.contains(&ascii(brand).as_str()))
    {
        return Err("Unsupported MP4 file type".into());
    }
    if !top
        .iter()
        .any(|item| item.kind == "mdat" && item.end > item.payload)
    {
        return Err("MP4 has no media data".into());
    }
    let movie = movie_children(reader, top, "MP4 needs one movie box")?;
    for track in movie.iter().filter(|item| item.kind == "trak") {
        let children = boxes(reader, track.payload, track.end)?;
        let Some(mdia) = single(&children, |item| item.kind == "mdia") else {
            continue;
        };
        let media = boxes(reader, mdia.payload, mdia.end)?;
        let Some(handler) = single(&media, |item| item.kind == "hdlr") else {
            continue;
        };
        if handler.end - handler.payload < 12 {
            return Err("Truncated MP4 handler".into());
        }
        if reader.read(handler.payload + 8, 4)? != b"vide" {
            continue;
        }
        let Some(minf) = single(&media, |item| item.kind == "minf") else {
            continue;
        };
        let info = boxes(reader, minf.payload, minf.end)?;
        let Some(stbl) = single(&info, |item| item.kind == "stbl") else {
            continue;
        };
        let tables = boxes(reader, stbl.payload, stbl.end)?;
        let Some(stsd) = single(&tables, |item| item.kind == "stsd") else {
            continue;
        };
        if stsd.end - stsd.payload < 8 {
            return Err("Truncated MP4 sample description".into());
        }
        let entries = boxes(reader, stsd.payload + 8, stsd.end)?;
        if reader.u32(stsd.payload + 4)? as usize != entries.len() {
            return Err("Invalid MP4 sample description count".into());
        }
        if entries.iter().any(|item| {
            ["avc1", "avc3", "hvc1", "hev1", "av01"].contains(&item.kind.as_str())
                && item.end - item.payload >= 78
        }) {
            return Ok(movie);
        }
    }
    Err("MP4 has no supported video track".into())
}

fn movie_children(reader: &Reader, top: &[BoxInfo], message: &str) -> Result<Vec<BoxInfo>> {
    let moov = single(top, |item| item.kind == "moov").ok_or(message)?;
    boxes(reader, moov.payload, moov.end)
}

fn inspect_top(
    reader: &Reader,
    start: u64,
    top: &[BoxInfo],
    movie: &[BoxInfo],
) -> Result<Option<Metadata>> {
    let media_data: Vec<_> = top.iter().filter(|item| item.kind == "mdat").collect();
    let mut frames = Vec::new();
    let mut found_track = false;
    for track in movie.iter().filter(|item| item.kind == "trak") {
        let track_boxes = boxes(reader, track.payload, track.end)?;
        let Some(mdia) = single(&track_boxes, |item| item.kind == "mdia") else {
            continue;
        };
        let media = boxes(reader, mdia.payload, mdia.end)?;
        let Some(minf) = single(&media, |item| item.kind == "minf") else {
            continue;
        };
        let media_info = boxes(reader, minf.payload, minf.end)?;
        let Some(stbl) = single(&media_info, |item| item.kind == "stbl") else {
            continue;
        };
        let tables = boxes(reader, stbl.payload, stbl.end)?;
        let Some(stsd) = single(&tables, |item| item.kind == "stsd") else {
            continue;
        };
        let mut description = Cursor::new(reader, stsd.payload, stsd.end);
        description.full_box(&[0])?;
        let entry_count = description.u32()?;
        let entries = boxes(reader, description.position, stsd.end)?;
        if entry_count as usize != entries.len() {
            return Err("Invalid metadata sample description count".into());
        }
        let mut metadata_entries = Vec::new();
        for entry in &entries {
            if entry.kind == "mett" && mime(reader, entry)?.1 == MIME {
                metadata_entries.push(entry);
            }
        }
        if metadata_entries.is_empty() {
            continue;
        }
        if found_track || entries.len() != 1 {
            return Err("Motion Photo metadata needs one track and one sample description".into());
        }
        found_track = true;
        let handler =
            single(&media, |item| item.kind == "hdlr").ok_or("Metadata has no handler")?;
        let mut handler_reader = Cursor::new(reader, handler.payload, handler.end);
        handler_reader.skip(8)?;
        if handler_reader.text(4)? != "meta" {
            return Err("Motion Photo metadata handler must be meta".into());
        }
        verify_data_reference(reader, &media_info, mime(reader, metadata_entries[0])?.0)?;
        let mut samples = regular_samples(reader, &tables, start)?;
        if top.iter().any(|item| item.kind == "moof") {
            let tkhd = single(&track_boxes, |item| item.kind == "tkhd")
                .ok_or("Fragmented metadata has no track header")?;
            let mut header = Cursor::new(reader, tkhd.payload, tkhd.end);
            let (version, _) = header.full_box(&[0, 1])?;
            header.skip(if version == 1 { 16 } else { 8 })?;
            let track_id = header.u32()?;
            samples.extend(fragmented_samples(reader, top, movie, track_id, start)?);
        }
        if samples.is_empty() || samples.len() > MAX_SAMPLES {
            return Err("Invalid motion metadata sample count".into());
        }
        for sample in samples {
            if sample.size == 0 || sample.size > MAX_SAMPLE_BYTES || sample.offset < 0 {
                return Err("Motion metadata sample is outside its mdat".into());
            }
            let offset = sample.offset as u64;
            if !media_data.iter().any(|item| {
                offset >= item.payload
                    && item
                        .end
                        .checked_sub(sample.size as u64)
                        .is_some_and(|end| offset <= end)
            }) {
                return Err("Motion metadata sample is outside its mdat".into());
            }
            let data = reader.read(offset, sample.size)?;
            let (timestamp_us, timestamp_offset) = DescriptorReader::new(&data).parse()?;
            let relative = checked_add(sample.offset - start as i64, timestamp_offset as i64)?;
            frames.push(Frame {
                offset: relative as u64,
                timestamp_us,
            });
        }
    }
    Ok(found_track.then_some(Metadata { frames }))
}

fn mime(reader: &Reader, entry: &BoxInfo) -> Result<(u16, String)> {
    let mut cursor = Cursor::new(reader, entry.payload, entry.end);
    cursor.skip(6)?;
    let reference = cursor.u16()?;
    let encoding = cursor.c_string()?;
    let mime = cursor.c_string()?;
    if mime == MIME && !encoding.is_empty() {
        return Err("Encoded motion metadata is unsupported".into());
    }
    Ok((reference, mime))
}

fn verify_data_reference(reader: &Reader, media_info: &[BoxInfo], index: u16) -> Result<()> {
    let dinf =
        single(media_info, |item| item.kind == "dinf").ok_or("Metadata has no data reference")?;
    let children = boxes(reader, dinf.payload, dinf.end)?;
    let dref = single(&children, |item| item.kind == "dref")
        .ok_or("Metadata has no data reference table")?;
    let mut cursor = Cursor::new(reader, dref.payload, dref.end);
    cursor.full_box(&[0])?;
    let count = cursor.u32()?;
    let entries = boxes(reader, cursor.position, dref.end)?;
    if count as usize != entries.len() || index == 0 || index as usize > entries.len() {
        return Err("Invalid metadata data reference".into());
    }
    let entry = &entries[index as usize - 1];
    if entry.kind != "url "
        || Cursor::new(reader, entry.payload, entry.end)
            .full_box(&[0])?
            .1
            != 1
    {
        return Err("External motion metadata data is unsupported".into());
    }
    Ok(())
}

fn regular_samples(reader: &Reader, tables: &[BoxInfo], start: u64) -> Result<Vec<Sample>> {
    let size_box = single(tables, |item| item.kind == "stsz" || item.kind == "stz2")
        .ok_or("Motion metadata has no sample size table")?;
    let sizes = sample_sizes(reader, size_box)?;
    if sizes.is_empty() {
        return Ok(Vec::new());
    }
    let offset_box = single(tables, |item| item.kind == "stco" || item.kind == "co64")
        .ok_or("Motion metadata has no chunk offsets")?;
    let mut offsets = Cursor::new(reader, offset_box.payload, offset_box.end);
    offsets.full_box(&[0])?;
    let chunk_count = offsets.count(MAX_SAMPLES)?;
    let mut chunks = Vec::with_capacity(chunk_count);
    for _ in 0..chunk_count {
        let offset = if offset_box.kind == "co64" {
            offsets.u64()?
        } else {
            offsets.u32()? as i64
        };
        chunks.push(checked_add(start as i64, offset)?);
    }
    offsets.end()?;
    let mapping_box =
        single(tables, |item| item.kind == "stsc").ok_or("Motion metadata has no chunk mapping")?;
    let mut mapping = Cursor::new(reader, mapping_box.payload, mapping_box.end);
    mapping.full_box(&[0])?;
    let entry_count = mapping.count(MAX_SAMPLES)?;
    let mut entries = Vec::with_capacity(entry_count);
    for _ in 0..entry_count {
        let first = mapping.u32()? as usize;
        let count = mapping.count(MAX_SAMPLES)?;
        if mapping.u32()? != 1 || count == 0 {
            return Err("Invalid metadata sample-to-chunk entry".into());
        }
        entries.push((first, count));
    }
    mapping.end()?;
    if entries.is_empty()
        || entries[0].0 != 1
        || entries.iter().any(|entry| entry.0 > chunks.len())
        || entries.windows(2).any(|pair| pair[0].0 >= pair[1].0)
    {
        return Err("Invalid metadata sample-to-chunk order".into());
    }
    let mut result = Vec::with_capacity(sizes.len());
    let mut mapping_index = 0;
    let mut sample_index = 0;
    for (chunk_index, chunk_offset) in chunks.into_iter().enumerate() {
        while mapping_index + 1 < entries.len() && entries[mapping_index + 1].0 <= chunk_index + 1 {
            mapping_index += 1;
        }
        let mut offset = chunk_offset;
        for _ in 0..entries[mapping_index].1 {
            let size = *sizes
                .get(sample_index)
                .ok_or("Metadata chunks have too many samples")?;
            sample_index += 1;
            result.push(Sample { offset, size });
            offset = checked_add(offset, size as i64)?;
        }
    }
    if sample_index != sizes.len() {
        return Err("Metadata chunks omit samples".into());
    }
    Ok(result)
}

fn sample_sizes(reader: &Reader, item: &BoxInfo) -> Result<Vec<usize>> {
    let mut cursor = Cursor::new(reader, item.payload, item.end);
    cursor.full_box(&[0])?;
    if item.kind == "stsz" {
        let fixed = cursor.u32()? as usize;
        let count = cursor.count(MAX_SAMPLES)?;
        if fixed > MAX_SAMPLE_BYTES {
            return Err("Metadata sample exceeds size limit".into());
        }
        let mut result = Vec::with_capacity(count);
        for _ in 0..count {
            result.push(if fixed == 0 {
                cursor.count(MAX_SAMPLE_BYTES)?
            } else {
                fixed
            });
        }
        cursor.end()?;
        return Ok(result);
    }
    cursor.skip(3)?;
    let width = cursor.u8()?;
    if ![4, 8, 16].contains(&width) {
        return Err("Invalid compact metadata sample size".into());
    }
    let count = cursor.count(MAX_SAMPLES)?;
    let mut result = Vec::with_capacity(count);
    let mut packed = 0;
    for index in 0..count {
        result.push(match width {
            4 if index % 2 == 0 => {
                packed = cursor.u8()?;
                usize::from(packed >> 4)
            }
            4 => usize::from(packed & 15),
            8 => usize::from(cursor.u8()?),
            _ => usize::from(cursor.u16()?),
        });
    }
    cursor.end()?;
    Ok(result)
}

fn fragmented_samples(
    reader: &Reader,
    top: &[BoxInfo],
    movie: &[BoxInfo],
    track_id: u32,
    start: u64,
) -> Result<Vec<Sample>> {
    let mut defaults = HashMap::new();
    if let Some(mvex) = single(movie, |item| item.kind == "mvex") {
        for entry in boxes(reader, mvex.payload, mvex.end)?
            .iter()
            .filter(|item| item.kind == "trex")
        {
            let mut cursor = Cursor::new(reader, entry.payload, entry.end);
            cursor.full_box(&[0])?;
            let id = cursor.u32()?;
            let description = cursor.u32()?;
            cursor.skip(4)?;
            let size = cursor.u32()?;
            cursor.skip(4)?;
            cursor.end()?;
            if defaults.insert(id, (description, size)).is_some() {
                return Err("Duplicate fragment track defaults".into());
            }
        }
    }
    let mut result = Vec::new();
    for moof in top.iter().filter(|item| item.kind == "moof") {
        let mut previous_traf_end = moof.start as i64;
        for traf in boxes(reader, moof.payload, moof.end)?
            .iter()
            .filter(|item| item.kind == "traf")
        {
            let entries = boxes(reader, traf.payload, traf.end)?;
            let tfhd = single(&entries, |item| item.kind == "tfhd")
                .ok_or("Fragment has no track header")?;
            let mut header = Cursor::new(reader, tfhd.payload, tfhd.end);
            let (_, flags) = header.full_box(&[0])?;
            if flags & !0x03003b != 0 {
                return Err("Unsupported fragment header flags".into());
            }
            let current_track = header.u32()?;
            let default = defaults.get(&current_track).copied();
            let base = if flags & 1 != 0 {
                checked_add(start as i64, header.u64()?)?
            } else if flags & 0x020000 != 0 {
                moof.start as i64
            } else {
                previous_traf_end
            };
            let description = if flags & 2 != 0 {
                header.u32()?
            } else {
                default.map_or(1, |value| value.0)
            };
            if current_track == track_id && description != 1 {
                return Err("Invalid fragmented metadata sample description".into());
            }
            if flags & 8 != 0 {
                header.skip(4)?;
            }
            let sample_size = if flags & 0x10 != 0 {
                header.u32()?
            } else {
                default.map_or(0, |value| value.1)
            };
            if flags & 0x20 != 0 {
                header.skip(4)?;
            }
            header.end()?;
            let mut next_data = base;
            for trun in entries.iter().filter(|item| item.kind == "trun") {
                let mut run = Cursor::new(reader, trun.payload, trun.end);
                let (_, run_flags) = run.full_box(&[0, 1])?;
                if run_flags & !0xf05 != 0 {
                    return Err("Unsupported fragment run flags".into());
                }
                let count = run.count(if current_track == track_id {
                    MAX_SAMPLES
                } else {
                    1_000_000
                })?;
                if current_track == track_id && result.len() + count > MAX_SAMPLES {
                    return Err("Too many fragmented metadata samples".into());
                }
                if run_flags & 1 != 0 {
                    next_data = checked_add(base, i64::from(run.i32()?))?;
                }
                if run_flags & 4 != 0 {
                    run.skip(4)?;
                }
                for _ in 0..count {
                    if run_flags & 0x100 != 0 {
                        run.skip(4)?;
                    }
                    let size = if run_flags & 0x200 != 0 {
                        run.u32()?
                    } else {
                        sample_size
                    };
                    if current_track == track_id && (size == 0 || size as usize > MAX_SAMPLE_BYTES)
                    {
                        return Err("Invalid fragmented metadata sample size".into());
                    }
                    if run_flags & 0x400 != 0 {
                        run.skip(4)?;
                    }
                    if run_flags & 0x800 != 0 {
                        run.skip(4)?;
                    }
                    if current_track == track_id {
                        result.push(Sample {
                            offset: next_data,
                            size: size as usize,
                        });
                    }
                    next_data = checked_add(next_data, i64::from(size))?;
                }
                run.end()?;
            }
            // ISO 14496-12: the inherited base is the previous traf's LAST run end,
            // including other tracks/backward explicit offsets, not its maximum end.
            previous_traf_end = next_data;
        }
    }
    Ok(result)
}

#[derive(Clone, Copy)]
struct Descriptor {
    payload: usize,
    end: usize,
}

struct DescriptorReader<'a> {
    bytes: &'a [u8],
}

impl<'a> DescriptorReader<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes }
    }

    fn parse(&self) -> Result<(i64, usize)> {
        let root = self.descriptor(0, self.bytes.len(), 0xc0)?;
        let score = self.descriptor(root.payload, root.end, 0xc1)?;
        self.require_bytes(score.payload, 4, score.end)?;
        let primary = self.descriptor(score.payload + 4, score.end, 0xc3)?;
        let timestamp = self.frame(primary)?;
        let timestamp_offset = primary.payload + 4;
        if timestamp < -1 {
            return Err("Invalid primary metadata timestamp".into());
        }
        let high_res = self.descriptor(primary.end, score.end, 0xc2)?;
        self.require_bytes(high_res.payload, 4, high_res.end)?;
        let count = u32::from_be_bytes(
            self.bytes[high_res.payload..high_res.payload + 4]
                .try_into()
                .unwrap(),
        ) as usize;
        if count > (high_res.end - high_res.payload - 4) / 14 {
            return Err("Invalid high-resolution score count".into());
        }
        let mut offset = high_res.payload + 4;
        let mut previous = i64::MIN;
        for _ in 0..count {
            let scored_frame = self.descriptor(offset, high_res.end, 0xc3)?;
            let current = self.frame(scored_frame)?;
            if current < previous {
                return Err("Metadata scored frames are not ordered".into());
            }
            previous = current;
            offset = scored_frame.end;
        }
        if offset != high_res.end || high_res.end != score.end {
            return Err("Unexpected score descriptor data".into());
        }
        let flags = self.descriptor(score.end, root.end, 0xc4)?;
        let low = self.descriptor(flags.payload, flags.end, 0xc5)?;
        let high = self.descriptor(low.end, flags.end, 0xc5)?;
        if low.end - low.payload != 1
            || high.end - high.payload != 1
            || high.end != flags.end
            || flags.end != root.end
            || root.end != self.bytes.len()
        {
            return Err("Invalid metadata flags descriptor".into());
        }
        Ok((timestamp, timestamp_offset))
    }

    fn frame(&self, descriptor: Descriptor) -> Result<i64> {
        if descriptor.end - descriptor.payload != 12 {
            return Err("Invalid frame score descriptor".into());
        }
        let value = &self.bytes[descriptor.payload..descriptor.end];
        let score = f32::from_bits(u32::from_be_bytes(value[..4].try_into().unwrap()));
        if !score.is_finite() || !(0.0..=1.0).contains(&score) {
            return Err("Invalid metadata frame score".into());
        }
        Ok(i64::from_be_bytes(value[4..].try_into().unwrap()))
    }

    fn descriptor(&self, start: usize, end: usize, tag: u8) -> Result<Descriptor> {
        self.require_bytes(start, 2, end)?;
        if self.bytes[start] != tag {
            return Err("Unexpected motion metadata descriptor tag".into());
        }
        let mut offset = start + 1;
        let mut length = 0;
        for _ in 0..4 {
            self.require_bytes(offset, 1, end)?;
            let next = self.bytes[offset];
            offset += 1;
            length = (length << 7) | usize::from(next & 0x7f);
            if next & 0x80 == 0 {
                self.require_bytes(offset, length, end)?;
                return Ok(Descriptor {
                    payload: offset,
                    end: offset + length,
                });
            }
        }
        Err("Invalid expandable descriptor length".into())
    }

    fn require_bytes(&self, offset: usize, size: usize, end: usize) -> Result<()> {
        if size > end || offset > end - size {
            return Err("Truncated motion metadata descriptor".into());
        }
        Ok(())
    }
}

struct Cursor<'a> {
    reader: &'a Reader,
    position: u64,
    limit: u64,
}

impl<'a> Cursor<'a> {
    fn new(reader: &'a Reader, position: u64, limit: u64) -> Self {
        Self {
            reader,
            position,
            limit,
        }
    }
    fn available(&self, size: usize) -> Result<()> {
        if size as u64 > self.limit || self.position > self.limit - size as u64 {
            return Err("Truncated MP4 metadata box".into());
        }
        Ok(())
    }
    fn take(&mut self, size: usize) -> Result<Vec<u8>> {
        self.available(size)?;
        let result = self.reader.read(self.position, size)?;
        self.position += size as u64;
        Ok(result)
    }
    fn u8(&mut self) -> Result<u8> {
        self.available(1)?;
        let value = self.reader.byte(self.position)?;
        self.position += 1;
        Ok(value)
    }
    fn u16(&mut self) -> Result<u16> {
        self.available(2)?;
        let value = self.reader.u16(self.position)?;
        self.position += 2;
        Ok(value)
    }
    fn i32(&mut self) -> Result<i32> {
        Ok(self.u32()? as i32)
    }
    fn u32(&mut self) -> Result<u32> {
        self.available(4)?;
        let value = self.reader.u32(self.position)?;
        self.position += 4;
        Ok(value)
    }
    fn u64(&mut self) -> Result<i64> {
        self.available(8)?;
        let value = self.reader.u64(self.position)?;
        self.position += 8;
        if value > i64::MAX as u64 {
            return Err("Unsigned MP4 offset exceeds supported range".into());
        }
        Ok(value as i64)
    }
    fn text(&mut self, size: usize) -> Result<String> {
        Ok(ascii(&self.take(size)?))
    }
    fn skip(&mut self, size: usize) -> Result<()> {
        self.take(size)?;
        Ok(())
    }
    fn count(&mut self, maximum: usize) -> Result<usize> {
        let count = self.u32()? as usize;
        if count > maximum {
            return Err("MP4 metadata table exceeds supported limit".into());
        }
        Ok(count)
    }
    fn full_box(&mut self, versions: &[u8]) -> Result<(u8, u32)> {
        let value = self.u32()?;
        let version = (value >> 24) as u8;
        if !versions.contains(&version) {
            return Err("Unsupported MP4 metadata box version".into());
        }
        Ok((version, value & 0xffffff))
    }
    fn c_string(&mut self) -> Result<String> {
        let mut value = String::new();
        for _ in 0..4096 {
            let byte = self.u8()?;
            if byte == 0 {
                return Ok(value);
            }
            value.push(char::from(byte));
        }
        Err("MP4 metadata string exceeds supported limit".into())
    }
    fn end(&self) -> Result<()> {
        if self.position != self.limit {
            return Err("Unexpected MP4 metadata box data".into());
        }
        Ok(())
    }
}

fn single(items: &[BoxInfo], matches: impl Fn(&BoxInfo) -> bool) -> Option<&BoxInfo> {
    let mut found = items.iter().filter(|item| matches(item));
    let first = found.next()?;
    if found.next().is_some() {
        None
    } else {
        Some(first)
    }
}
fn checked_add(first: i64, second: i64) -> Result<i64> {
    first
        .checked_add(second)
        .ok_or_else(|| "MP4 metadata offset overflow".into())
}
fn ascii(bytes: &[u8]) -> String {
    bytes
        .iter()
        .map(|byte| {
            if *byte <= 127 {
                char::from(*byte)
            } else {
                '\u{fffd}'
            }
        })
        .collect()
}

#[cfg(test)]
pub(crate) fn scored_video_fixture(timestamp: i64) -> Vec<u8> {
    tests::scored_video_fixture(timestamp)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::motion_photo::fixture_reader;
    use std::io::{Seek, SeekFrom};

    #[derive(Clone, Copy)]
    enum Sizes {
        Variable,
        Fixed,
        Compact8,
        Compact16,
    }

    #[derive(Clone, Copy, Debug)]
    enum Fragment {
        DefaultMoof,
        Explicit,
        Signed,
        Inherited,
        ChainedRuns,
        LastRunEnd,
        Extended,
        NegativeOtherRun,
    }

    #[test]
    fn classic_tables_and_embedded_base_locate_the_exact_primary_timestamp_bytes() {
        for (wide, sizes, separate) in [
            (false, Sizes::Variable, false),
            (true, Sizes::Variable, true),
            (false, Sizes::Compact8, false),
            (true, Sizes::Compact16, true),
            (true, Sizes::Fixed, false),
        ] {
            let samples = if matches!(sizes, Sizes::Fixed) {
                vec![descriptor(1234567, &[], 1), descriptor(1234567, &[], 1)]
            } else {
                vec![
                    descriptor(1234567, &[1000, 2000], 4),
                    descriptor(-1, &[], 1),
                ]
            };
            let bytes = classic(&samples, sizes, wide, separate);
            let mut embedded = vec![0x42; 19];
            embedded.extend_from_slice(&bytes);
            let (mut file, reader) = fixture_reader(&embedded);
            file.seek(SeekFrom::Start(7)).unwrap();
            let analysis = analyze_layout(&reader, 19, bytes.len() as u64).unwrap();
            let info = analysis.metadata.unwrap();
            assert_eq!(2, info.frames.len());
            for (index, frame) in info.frames.iter().enumerate() {
                let expected = if matches!(sizes, Sizes::Fixed) || index == 0 {
                    1234567
                } else {
                    -1
                };
                assert_eq!(expected, frame.timestamp_us);
                assert_eq!(expected, reader.u64(19 + frame.offset).unwrap() as i64);
            }
            assert_eq!(
                7,
                file.stream_position().unwrap(),
                "Borrowed FD position must remain intact"
            );
            assert_eq!("ftyp", analysis.boxes[0].kind);
        }
    }

    #[test]
    fn fragments_follow_defaults_signed_offsets_chained_runs_and_the_last_traf_run_end() {
        for mode in [
            Fragment::DefaultMoof,
            Fragment::Explicit,
            Fragment::Signed,
            Fragment::Inherited,
            Fragment::ChainedRuns,
            Fragment::LastRunEnd,
            Fragment::Extended,
            Fragment::NegativeOtherRun,
        ] {
            let payload = descriptor(250000, &[], 1);
            let bytes = fragmented(mode, &payload, false, false);
            let (_file, reader) = fixture_reader(&bytes);
            let metadata = analyze(&reader, 0, reader.len()).unwrap().unwrap();
            assert_eq!(
                if matches!(mode, Fragment::ChainedRuns) {
                    2
                } else {
                    1
                },
                metadata.frames.len(),
                "{mode:?}"
            );
            for frame in metadata.frames {
                assert_eq!(250000, frame.timestamp_us, "{mode:?}");
                assert_eq!(250000, reader.u64(frame.offset).unwrap(), "{mode:?}");
            }
        }
    }

    #[test]
    fn per_sample_trun_size_overrides_zero_tfhd_but_zero_does_not_fall_back_to_trex() {
        let payload = descriptor(0, &[], 1);
        let good = fragmented(Fragment::DefaultMoof, &payload, true, true);
        assert_eq!(
            0,
            inspect_bytes(&good).unwrap().unwrap().frames[0].timestamp_us
        );
        let bad = fragmented(Fragment::DefaultMoof, &payload, true, false);
        assert!(
            inspect_bytes(&bad)
                .unwrap_err()
                .contains("Invalid fragmented metadata sample size")
        );
    }

    #[test]
    fn invalid_external_references_chunk_mapping_offsets_and_table_counts_are_rejected() {
        let valid = scored_video_fixture(0);
        let mut external = valid.clone();
        let url = occurrence(&external, b"url ");
        external[url + 4..url + 8].copy_from_slice(&0u32.to_be_bytes());
        assert!(
            inspect_bytes(&external)
                .unwrap_err()
                .contains("External motion metadata")
        );

        let mut outside = valid.clone();
        let stco = occurrence(&outside, b"stco");
        outside[stco + 12..stco + 16].copy_from_slice(&0u32.to_be_bytes());
        assert!(
            inspect_bytes(&outside)
                .unwrap_err()
                .contains("outside its mdat")
        );

        let mut mapping = valid.clone();
        let stsc = occurrence(&mapping, b"stsc");
        mapping[stsc + 20..stsc + 24].copy_from_slice(&2u32.to_be_bytes());
        assert!(
            inspect_bytes(&mapping)
                .unwrap_err()
                .contains("sample-to-chunk")
        );

        let mut too_many = valid.clone();
        let stsz = occurrence(&too_many, b"stsz");
        too_many[stsz + 12..stsz + 16].copy_from_slice(&((MAX_SAMPLES + 1) as u32).to_be_bytes());
        assert!(
            inspect_bytes(&too_many)
                .unwrap_err()
                .contains("supported limit")
        );

        let mut high_offset = classic(&[descriptor(0, &[], 1)], Sizes::Variable, true, false);
        let co64 = occurrence(&high_offset, b"co64");
        high_offset[co64 + 12..co64 + 20].copy_from_slice(&u64::MAX.to_be_bytes());
        assert!(
            inspect_bytes(&high_offset)
                .unwrap_err()
                .contains("Unsigned MP4 offset")
        );
    }

    #[test]
    fn compact_four_bit_sizes_handle_odd_counts_and_require_exact_table_lengths() {
        let bytes = atom(
            "stz2",
            &[full(0), vec![0, 0, 0, 4], word(3), vec![0x12, 0x30]].concat(),
        );
        let (_file, reader) = fixture_reader(&bytes);
        let item = boxes(&reader, 0, reader.len()).unwrap().remove(0);
        assert_eq!(vec![1, 2, 3], sample_sizes(&reader, &item).unwrap());
        let malformed = atom(
            "stz2",
            &[full(0), vec![0, 0, 0, 4], word(3), vec![0x12]].concat(),
        );
        let (_file, reader) = fixture_reader(&malformed);
        let item = boxes(&reader, 0, reader.len()).unwrap().remove(0);
        assert!(sample_sizes(&reader, &item).is_err());
    }

    #[test]
    fn scoring_descriptors_validate_expandable_lengths_scores_order_and_exact_envelopes() {
        for width in 1..=4 {
            let data = descriptor(-1, &[-2, -1, 1000], width);
            let (time, offset) = DescriptorReader::new(&data).parse().unwrap();
            assert_eq!(-1, time);
            assert_eq!(
                -1,
                i64::from_be_bytes(data[offset..offset + 8].try_into().unwrap())
            );
        }
        assert!(
            DescriptorReader::new(&descriptor(-2, &[], 1))
                .parse()
                .is_err()
        );
        assert!(
            DescriptorReader::new(&descriptor(0, &[1000, 0], 1))
                .parse()
                .is_err()
        );
        for score in [f32::NAN, f32::INFINITY, -0.1, 1.1] {
            let mut bad = descriptor(0, &[], 1);
            bad[10..14].copy_from_slice(&score.to_bits().to_be_bytes());
            assert!(DescriptorReader::new(&bad).parse().is_err());
        }
        let mut bad_tag = descriptor(0, &[], 1);
        bad_tag[0] = 0xc1;
        assert!(DescriptorReader::new(&bad_tag).parse().is_err());
        let mut bad_length = descriptor(0, &[], 1);
        bad_length[1] = 127;
        assert!(DescriptorReader::new(&bad_length).parse().is_err());
        let mut extra = descriptor(0, &[], 1);
        extra.push(0);
        assert!(DescriptorReader::new(&extra).parse().is_err());
        assert!(
            DescriptorReader::new(&[0xc0, 0x80, 0x80, 0x80, 0x80, 0])
                .parse()
                .is_err()
        );
    }

    #[test]
    fn box_layout_rejects_truncation_and_accepts_extended_and_terminal_zero_size_boxes() {
        let mut bytes = extended_atom("free", &[1, 2, 3]);
        bytes.extend_from_slice(&[word(0), b"mdat".to_vec(), vec![4, 5]].concat());
        let (_file, reader) = fixture_reader(&bytes);
        let layout = boxes(&reader, 0, reader.len()).unwrap();
        assert_eq!(16, layout[0].payload);
        assert_eq!(reader.len(), layout[1].end);
        let mut truncated = bytes.clone();
        truncated[8..16].copy_from_slice(&u64::MAX.to_be_bytes());
        let (_file, reader) = fixture_reader(&truncated);
        assert!(boxes(&reader, 0, reader.len()).is_err());
        let (_file, reader) = fixture_reader(&[0; 7]);
        assert!(boxes(&reader, 0, reader.len()).is_err());
    }

    #[test]
    fn supported_video_structure_and_no_metadata_are_independent_of_scoring_tracks() {
        for codec in ["avc1", "avc3", "hvc1", "hev1", "av01"] {
            let bytes = [
                ftyp(),
                atom("mdat", &[1]),
                atom("moov", &video_track(codec)),
            ]
            .concat();
            let (_file, reader) = fixture_reader(&bytes);
            assert!(analyze(&reader, 0, reader.len()).unwrap().is_none());
        }
        let mut wrong_brand = [
            ftyp(),
            atom("mdat", &[1]),
            atom("moov", &video_track("avc1")),
        ]
        .concat();
        wrong_brand[8..12].copy_from_slice(b"qt  ");
        let (_file, reader) = fixture_reader(&wrong_brand);
        assert!(
            analyze_layout(&reader, 0, reader.len())
                .unwrap_err()
                .contains("Unsupported MP4 file type")
        );
        let duplicate = [
            ftyp(),
            atom("mdat", &[1]),
            atom("moov", &video_track("avc1")),
            atom("moov", &[]),
        ]
        .concat();
        let (_file, reader) = fixture_reader(&duplicate);
        assert!(analyze_layout(&reader, 0, reader.len()).is_err());
        let real = include_bytes!("../../../../app/src/test/resources/motion_photo/video.mp4");
        let (_file, reader) = fixture_reader(real);
        assert!(analyze(&reader, 0, reader.len()).unwrap().is_none());
    }

    #[test]
    fn metadata_requires_one_track_supported_handler_and_known_fragment_flags() {
        let mut wrong_handler = scored_video_fixture(0);
        let hdlr = occurrence(&wrong_handler, b"hdlr");
        wrong_handler[hdlr + 12..hdlr + 16].copy_from_slice(b"vide");
        assert!(
            inspect_bytes(&wrong_handler)
                .unwrap_err()
                .contains("handler must be meta")
        );
        let mut fragment = fragmented(Fragment::DefaultMoof, &descriptor(0, &[], 1), false, false);
        let tfhd = occurrence(&fragment, b"tfhd");
        fragment[tfhd + 4..tfhd + 8].copy_from_slice(&0x020004u32.to_be_bytes());
        assert!(
            inspect_bytes(&fragment)
                .unwrap_err()
                .contains("header flags")
        );
        let mut run = fragmented(Fragment::DefaultMoof, &descriptor(0, &[], 1), false, false);
        let trun = occurrence(&run, b"trun");
        run[trun + 4..trun + 8].copy_from_slice(&3u32.to_be_bytes());
        assert!(inspect_bytes(&run).unwrap_err().contains("run flags"));
    }

    pub(super) fn scored_video_fixture(timestamp: i64) -> Vec<u8> {
        classic(
            &[descriptor(timestamp, &[], 1)],
            Sizes::Variable,
            false,
            false,
        )
    }

    fn inspect_bytes(bytes: &[u8]) -> Result<Option<Metadata>> {
        let (_file, reader) = fixture_reader(bytes);
        inspect(&reader, 0, reader.len())
    }
    fn word(value: u32) -> Vec<u8> {
        value.to_be_bytes().to_vec()
    }
    fn long(value: u64) -> Vec<u8> {
        value.to_be_bytes().to_vec()
    }
    fn full(flags: u32) -> Vec<u8> {
        word(flags)
    }
    fn atom(kind: &str, data: &[u8]) -> Vec<u8> {
        [
            word((data.len() + 8) as u32),
            kind.as_bytes().to_vec(),
            data.to_vec(),
        ]
        .concat()
    }
    fn extended_atom(kind: &str, data: &[u8]) -> Vec<u8> {
        [
            word(1),
            kind.as_bytes().to_vec(),
            long((data.len() + 16) as u64),
            data.to_vec(),
        ]
        .concat()
    }
    fn ftyp() -> Vec<u8> {
        atom("ftyp", &[b"isom".to_vec(), word(0)].concat())
    }
    fn handler(kind: &str) -> Vec<u8> {
        atom(
            "hdlr",
            &[full(0), word(0), kind.as_bytes().to_vec(), vec![0; 12]].concat(),
        )
    }
    fn video_track(codec: &str) -> Vec<u8> {
        let stsd = atom("stsd", &[full(0), word(1), atom(codec, &[0; 78])].concat());
        atom(
            "trak",
            &atom(
                "mdia",
                &[handler("vide"), atom("minf", &atom("stbl", &stsd))].concat(),
            ),
        )
    }
    fn metadata_track(table: &[u8]) -> Vec<u8> {
        let tkhd = atom("tkhd", &[full(0), vec![0; 8], word(3)].concat());
        let dref = atom("dref", &[full(0), word(1), atom("url ", &full(1))].concat());
        let minf = atom("minf", &[atom("dinf", &dref), atom("stbl", table)].concat());
        atom(
            "trak",
            &[tkhd, atom("mdia", &[handler("meta"), minf].concat())].concat(),
        )
    }
    fn tables(
        samples: &[Vec<u8>],
        offsets: &[u64],
        sizes: Sizes,
        wide: bool,
        separate: bool,
    ) -> Vec<u8> {
        let mett = atom(
            "mett",
            &[
                vec![0; 6],
                1u16.to_be_bytes().to_vec(),
                vec![0],
                MIME.as_bytes().to_vec(),
                vec![0],
            ]
            .concat(),
        );
        let stsd = atom("stsd", &[full(0), word(1), mett].concat());
        let size_table = match sizes {
            Sizes::Variable | Sizes::Fixed => {
                let fixed = matches!(sizes, Sizes::Fixed);
                atom(
                    "stsz",
                    &[
                        full(0),
                        word(if fixed {
                            samples.first().map_or(0, |item| item.len() as u32)
                        } else {
                            0
                        }),
                        word(samples.len() as u32),
                        if fixed {
                            vec![]
                        } else {
                            samples
                                .iter()
                                .flat_map(|item| word(item.len() as u32))
                                .collect()
                        },
                    ]
                    .concat(),
                )
            }
            Sizes::Compact8 | Sizes::Compact16 => {
                let width = if matches!(sizes, Sizes::Compact8) {
                    8
                } else {
                    16
                };
                let values = samples
                    .iter()
                    .flat_map(|item| {
                        if width == 8 {
                            vec![item.len() as u8]
                        } else {
                            (item.len() as u16).to_be_bytes().to_vec()
                        }
                    })
                    .collect::<Vec<_>>();
                atom(
                    "stz2",
                    &[
                        full(0),
                        vec![0, 0, 0, width],
                        word(samples.len() as u32),
                        values,
                    ]
                    .concat(),
                )
            }
        };
        let offset_values = offsets
            .iter()
            .flat_map(|offset| {
                if wide {
                    long(*offset)
                } else {
                    word(*offset as u32)
                }
            })
            .collect::<Vec<_>>();
        let chunks = atom(
            if wide { "co64" } else { "stco" },
            &[full(0), word(offsets.len() as u32), offset_values].concat(),
        );
        let mapping = atom(
            "stsc",
            &[
                full(0),
                word(u32::from(!samples.is_empty())),
                if samples.is_empty() {
                    vec![]
                } else {
                    [
                        word(1),
                        word(if separate { 1 } else { samples.len() as u32 }),
                        word(1),
                    ]
                    .concat()
                },
            ]
            .concat(),
        );
        [stsd, size_table, chunks, mapping].concat()
    }
    fn classic(samples: &[Vec<u8>], sizes: Sizes, wide: bool, separate: bool) -> Vec<u8> {
        let prefix = ftyp();
        let mut offset = prefix.len() as u64 + 8;
        let all_offsets = samples
            .iter()
            .map(|sample| {
                let current = offset;
                offset += sample.len() as u64;
                current
            })
            .collect::<Vec<_>>();
        let offsets = if separate {
            all_offsets
        } else {
            all_offsets.into_iter().take(1).collect()
        };
        let table = tables(samples, &offsets, sizes, wide, separate);
        [
            prefix,
            atom("mdat", &samples.concat()),
            atom(
                "moov",
                &[video_track("avc1"), metadata_track(&table)].concat(),
            ),
        ]
        .concat()
    }
    fn trex(id: u32, size: u32) -> Vec<u8> {
        atom(
            "trex",
            &[full(0), word(id), word(1), word(44100), word(size), word(0)].concat(),
        )
    }
    fn fragmented(
        mode: Fragment,
        payload: &[u8],
        explicit_zero: bool,
        per_sample_size: bool,
    ) -> Vec<u8> {
        let metadata = metadata_track(&tables(&[], &[], Sizes::Variable, false, false));
        let prefix = [
            ftyp(),
            atom(
                "moov",
                &[
                    video_track("avc1"),
                    metadata,
                    atom(
                        "mvex",
                        &[trex(3, payload.len() as u32), trex(1, 2)].concat(),
                    ),
                ]
                .concat(),
            ),
        ]
        .concat();
        let pad = vec![0x33, 0x44];
        let data = match mode {
            Fragment::Inherited => [pad.clone(), payload.to_vec()].concat(),
            Fragment::LastRunEnd => [pad.clone(), payload.to_vec(), pad.clone()].concat(),
            Fragment::ChainedRuns => [payload.to_vec(), payload.to_vec()].concat(),
            _ => payload.to_vec(),
        };
        let build = |size: usize| {
            let data_offset = (size + 8) as u32;
            let data_start = prefix.len() as u64 + u64::from(data_offset);
            let base_flags = match mode {
                Fragment::Explicit | Fragment::Signed => 1 | 0x20000,
                Fragment::Inherited | Fragment::LastRunEnd | Fragment::NegativeOtherRun => 0,
                _ => 0x20000,
            };
            let explicit_base = match mode {
                Fragment::Explicit => long(data_start),
                Fragment::Signed => long(data_start + payload.len() as u64),
                _ => vec![],
            };
            let tfhd = atom(
                "tfhd",
                &[
                    full(base_flags | if explicit_zero { 0x10 } else { 0 }),
                    word(3),
                    explicit_base,
                    if explicit_zero { word(0) } else { vec![] },
                ]
                .concat(),
            );
            let has_offset = !matches!(
                mode,
                Fragment::Explicit
                    | Fragment::Inherited
                    | Fragment::LastRunEnd
                    | Fragment::NegativeOtherRun
            );
            let offset = if matches!(mode, Fragment::Signed) {
                (-(payload.len() as i32)) as u32
            } else {
                data_offset
            };
            let run = atom(
                "trun",
                &[
                    full(u32::from(has_offset) | if per_sample_size { 0x200 } else { 0 }),
                    word(1),
                    if has_offset { word(offset) } else { vec![] },
                    if per_sample_size {
                        word(payload.len() as u32)
                    } else {
                        vec![]
                    },
                ]
                .concat(),
            );
            let preceding = if matches!(mode, Fragment::Inherited | Fragment::LastRunEnd) {
                let first = atom(
                    "trun",
                    &[
                        full(1),
                        word(1),
                        word(if matches!(mode, Fragment::LastRunEnd) {
                            data_offset + 2 + payload.len() as u32
                        } else {
                            data_offset
                        }),
                    ]
                    .concat(),
                );
                let last = if matches!(mode, Fragment::LastRunEnd) {
                    atom("trun", &[full(1), word(1), word(data_offset)].concat())
                } else {
                    vec![]
                };
                atom(
                    "traf",
                    &[atom("tfhd", &[full(0), word(1)].concat()), first, last].concat(),
                )
            } else if matches!(mode, Fragment::NegativeOtherRun) {
                // The non-metadata track's signed run starts at -2 but ends at mdat.
                // Metadata inspection uses that last end; downstream video checks stay separate.
                atom(
                    "traf",
                    &[
                        atom(
                            "tfhd",
                            &[
                                full(1 | 0x10),
                                word(1),
                                long(0),
                                word((data_start + 2) as u32),
                            ]
                            .concat(),
                        ),
                        atom("trun", &[full(1), word(1), word((-2i32) as u32)].concat()),
                    ]
                    .concat(),
                )
            } else {
                vec![]
            };
            let chained = if matches!(mode, Fragment::ChainedRuns) {
                atom("trun", &[full(0), word(1)].concat())
            } else {
                vec![]
            };
            let body = [
                atom("mfhd", &[full(0), word(1)].concat()),
                preceding,
                atom("traf", &[tfhd, run, chained].concat()),
            ]
            .concat();
            if matches!(mode, Fragment::Extended) {
                extended_atom("moof", &body)
            } else {
                atom("moof", &body)
            }
        };
        let moof = build(build(0).len());
        [prefix, moof, atom("mdat", &data)].concat()
    }
    fn descriptor(timestamp: i64, high_res: &[i64], width: usize) -> Vec<u8> {
        fn d(tag: u8, bytes: &[u8], width: usize) -> Vec<u8> {
            let sizes = (0..width)
                .map(|index| {
                    (((bytes.len() >> ((width - index - 1) * 7)) & 0x7f) as u8)
                        | if index + 1 < width { 0x80 } else { 0 }
                })
                .collect::<Vec<_>>();
            [vec![tag], sizes, bytes.to_vec()].concat()
        }
        let frame = |time: i64| {
            d(
                0xc3,
                &[
                    0.5f32.to_bits().to_be_bytes().to_vec(),
                    time.to_be_bytes().to_vec(),
                ]
                .concat(),
                width,
            )
        };
        let high = d(
            0xc2,
            &[
                word(high_res.len() as u32),
                high_res.iter().flat_map(|time| frame(*time)).collect(),
            ]
            .concat(),
            width,
        );
        let scores = d(0xc1, &[word(1), frame(timestamp), high].concat(), width);
        let flags = d(
            0xc4,
            &[d(0xc5, &[0x80], width), d(0xc5, &[0], width)].concat(),
            width,
        );
        d(0xc0, &[scores, flags].concat(), width)
    }
    fn occurrence(bytes: &[u8], needle: &[u8]) -> usize {
        bytes
            .windows(needle.len())
            .rposition(|item| item == needle)
            .unwrap()
    }
}
