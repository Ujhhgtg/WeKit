use flate2::read::GzDecoder;
use rasterlottie::{Animation, RenderConfig, Renderer, Rgba8, SupportProfile};
use sha2::{Digest, Sha256};
use std::ffi::{CStr, c_char, c_int, c_void};
use std::fs;
use std::io::Read;
use webm_iterable::{
    WebmIterator,
    matroska_spec::{Block, Master, MatroskaSpec, SimpleBlock},
};

const OUTPUT_SIZE: u32 = 512;
const TARGET_FPS: f32 = 20.0;
const MAX_FRAMES: usize = 90;
const WEBM_TARGET_FPS: f64 = 15.0;

#[repr(C)]
struct WeKitVpxDecoder(c_void);

#[repr(C)]
struct WeKitVpxFrame {
    width: u32,
    height: u32,
    planes: [*const u8; 3],
    strides: [i32; 3],
    high_bit_depth: i32,
    full_range: i32,
}

unsafe extern "C" {
    fn wekit_vpx_decoder_create() -> *mut WeKitVpxDecoder;
    fn wekit_vpx_decoder_destroy(decoder: *mut WeKitVpxDecoder);
    fn wekit_vpx_decoder_decode(
        decoder: *mut WeKitVpxDecoder,
        data: *const u8,
        data_size: usize,
    ) -> c_int;
    fn wekit_vpx_decoder_next_frame(
        decoder: *mut WeKitVpxDecoder,
        frame: *mut WeKitVpxFrame,
    ) -> c_int;
    fn wekit_vpx_decoder_error(decoder: *mut WeKitVpxDecoder) -> *const c_char;
}

pub fn webm_to_gif(input_path: &str, output_path: &str) -> Result<(), String> {
    let packets = read_webm_packets(input_path)?;
    if packets.len() < 2 {
        return Err("WebM contains fewer than two video frames".to_string());
    }

    let selected = selected_webm_frames(&packets);
    if selected.len() < 2 {
        return Err("WebM sampling produced fewer than two frames".to_string());
    }
    let duration_ms = webm_duration_ms(&packets);
    let delay_ms = (duration_ms as f64 / selected.len() as f64)
        .round()
        .max(1.0) as u32;
    let selected_set: std::collections::HashSet<usize> = selected.into_iter().collect();
    let mut color_decoder = VpxDecoder::new()?;
    let mut alpha_decoder = packets
        .iter()
        .any(|packet| packet.alpha.is_some())
        .then(VpxDecoder::new)
        .transpose()?;
    let mut encoder: Option<gif::Encoder<Vec<u8>>> = None;
    let mut encoded_frames = 0;
    let mut first_digest: Option<[u8; 32]> = None;
    let mut has_distinct_frame = false;

    for (index, packet) in packets.iter().enumerate() {
        let color = color_decoder.decode(&packet.color)?;
        let alpha = match (&mut alpha_decoder, &packet.alpha) {
            (Some(decoder), Some(data)) => decoder.decode(data)?,
            _ => None,
        };
        if !selected_set.contains(&index) {
            continue;
        }
        let color = color.ok_or_else(|| format!("VP9 produced no color frame at index {index}"))?;
        let mut rgba = color.to_rgba(alpha.as_ref())?;
        let digest: [u8; 32] = Sha256::digest(&rgba).into();
        if let Some(first) = &first_digest {
            has_distinct_frame |= first != &digest;
        } else {
            first_digest = Some(digest);
        }
        let width = u16::try_from(color.width).map_err(|_| "WebM width is too large")?;
        let height = u16::try_from(color.height).map_err(|_| "WebM height is too large")?;
        if encoder.is_none() {
            let mut created = gif::Encoder::new(Vec::new(), width, height, &[])
                .map_err(|error| format!("initialize WebM GIF: {error}"))?;
            created
                .set_repeat(gif::Repeat::Infinite)
                .map_err(|error| format!("configure WebM GIF: {error}"))?;
            encoder = Some(created);
        }
        write_rgba_frame(
            encoder.as_mut().expect("GIF encoder was initialized"),
            width,
            height,
            std::mem::take(&mut rgba),
            ((delay_ms as f32 / 10.0).round() as u16).max(1),
        )?;
        encoded_frames += 1;
    }

    if encoded_frames < 2 || !has_distinct_frame {
        return Err("WebM decoder did not produce an animation".to_string());
    }
    let output = encoder
        .ok_or_else(|| "WebM decoder produced no GIF frames".to_string())?
        .into_inner()
        .map_err(|error| format!("finish WebM GIF: {error}"))?;
    fs::write(output_path, output).map_err(|error| format!("write WebM GIF: {error}"))
}

#[derive(Debug)]
struct WebmPacket {
    timestamp_ms: i64,
    color: Vec<u8>,
    alpha: Option<Vec<u8>>,
}

fn read_webm_packets(input_path: &str) -> Result<Vec<WebmPacket>, String> {
    let mut input = fs::File::open(input_path).map_err(|error| format!("open WebM: {error}"))?;
    let full_groups = [MatroskaSpec::BlockGroup(Master::Start)];
    let tags = WebmIterator::new(&mut input, &full_groups);
    let mut packets = Vec::new();
    let mut timestamp_scale = 1_000_000_u64;
    let mut cluster_timestamp = 0_u64;
    for tag in tags {
        match tag.map_err(|error| format!("parse WebM: {error}"))? {
            MatroskaSpec::TimestampScale(value) => timestamp_scale = value.max(1),
            MatroskaSpec::Timestamp(value) => cluster_timestamp = value,
            MatroskaSpec::BlockGroup(Master::Full(children)) => {
                let Some(block_data) = children.iter().find_map(|child| match child {
                    MatroskaSpec::Block(data) => Some(data),
                    _ => None,
                }) else {
                    continue;
                };
                let block = Block::try_from(block_data.as_slice())
                    .map_err(|error| format!("parse WebM block: {error}"))?;
                let alpha = find_block_additional(&children);
                for frame in block
                    .read_frame_data()
                    .map_err(|error| format!("read WebM block frame: {error}"))?
                {
                    packets.push(WebmPacket {
                        timestamp_ms: block_timestamp_ms(
                            cluster_timestamp,
                            block.timestamp,
                            timestamp_scale,
                        ),
                        color: frame.data.to_vec(),
                        alpha: alpha.clone(),
                    });
                }
            }
            MatroskaSpec::SimpleBlock(data) => {
                let block = SimpleBlock::try_from(data.as_slice())
                    .map_err(|error| format!("parse WebM simple block: {error}"))?;
                for frame in block
                    .read_frame_data()
                    .map_err(|error| format!("read WebM simple block frame: {error}"))?
                {
                    packets.push(WebmPacket {
                        timestamp_ms: block_timestamp_ms(
                            cluster_timestamp,
                            block.timestamp,
                            timestamp_scale,
                        ),
                        color: frame.data.to_vec(),
                        alpha: None,
                    });
                }
            }
            _ => {}
        }
    }
    Ok(packets)
}

fn find_block_additional(children: &[MatroskaSpec]) -> Option<Vec<u8>> {
    children.iter().find_map(|child| {
        let MatroskaSpec::BlockAdditions(Master::Full(additions)) = child else {
            return None;
        };
        additions.iter().find_map(|addition| {
            let MatroskaSpec::BlockMore(Master::Full(more)) = addition else {
                return None;
            };
            let add_id = more.iter().find_map(|tag| match tag {
                MatroskaSpec::BlockAddID(value) => Some(*value),
                _ => None,
            });
            if add_id.unwrap_or(1) != 1 {
                return None;
            }
            more.iter().find_map(|tag| match tag {
                MatroskaSpec::BlockAdditional(data) => Some(data.clone()),
                _ => None,
            })
        })
    })
}

fn block_timestamp_ms(cluster: u64, relative: i16, scale_ns: u64) -> i64 {
    let ticks = (cluster as i64 + relative as i64).max(0);
    ticks.saturating_mul(scale_ns as i64) / 1_000_000
}

fn webm_duration_ms(packets: &[WebmPacket]) -> u64 {
    let first = packets
        .first()
        .map(|packet| packet.timestamp_ms)
        .unwrap_or(0);
    let last = packets
        .last()
        .map(|packet| packet.timestamp_ms)
        .unwrap_or(first);
    let mut deltas: Vec<i64> = packets
        .windows(2)
        .map(|pair| pair[1].timestamp_ms - pair[0].timestamp_ms)
        .filter(|delta| *delta > 0)
        .collect();
    deltas.sort_unstable();
    let tail = deltas.get(deltas.len() / 2).copied().unwrap_or(67);
    (last - first + tail).max(1) as u64
}

fn selected_webm_frames(packets: &[WebmPacket]) -> Vec<usize> {
    let duration_ms = webm_duration_ms(packets);
    let target_count = ((duration_ms as f64 * WEBM_TARGET_FPS / 1_000.0).ceil() as usize)
        .clamp(2, MAX_FRAMES)
        .min(packets.len());
    (0..target_count)
        .map(|index| index * packets.len() / target_count)
        .collect()
}

struct VpxDecoder(*mut WeKitVpxDecoder);

impl VpxDecoder {
    fn new() -> Result<Self, String> {
        let decoder = unsafe { wekit_vpx_decoder_create() };
        if decoder.is_null() {
            Err("initialize VP9 decoder failed".to_string())
        } else {
            Ok(Self(decoder))
        }
    }

    fn decode(&mut self, data: &[u8]) -> Result<Option<YuvFrame>, String> {
        let status = unsafe { wekit_vpx_decoder_decode(self.0, data.as_ptr(), data.len()) };
        if status != 0 {
            return Err(self.error("decode VP9 frame"));
        }
        let mut decoded = None;
        loop {
            let mut frame = WeKitVpxFrame {
                width: 0,
                height: 0,
                planes: [std::ptr::null(); 3],
                strides: [0; 3],
                high_bit_depth: 0,
                full_range: 0,
            };
            match unsafe { wekit_vpx_decoder_next_frame(self.0, &mut frame) } {
                0 => break,
                1 => decoded = Some(YuvFrame::copy_from(&frame)?),
                _ => return Err(self.error("read VP9 frame")),
            }
        }
        Ok(decoded)
    }

    fn error(&self, operation: &str) -> String {
        let pointer = unsafe { wekit_vpx_decoder_error(self.0) };
        let message = if pointer.is_null() {
            "unknown libvpx error".into()
        } else {
            unsafe { CStr::from_ptr(pointer) }.to_string_lossy()
        };
        format!("{operation}: {message}")
    }
}

impl Drop for VpxDecoder {
    fn drop(&mut self) {
        unsafe { wekit_vpx_decoder_destroy(self.0) };
    }
}

struct YuvFrame {
    width: usize,
    height: usize,
    y: Vec<u8>,
    u: Vec<u8>,
    v: Vec<u8>,
    full_range: bool,
}

impl YuvFrame {
    fn copy_from(frame: &WeKitVpxFrame) -> Result<Self, String> {
        if frame.high_bit_depth != 0 {
            return Err("high-bit-depth VP9 stickers are not supported".to_string());
        }
        let width = frame.width as usize;
        let height = frame.height as usize;
        if width == 0 || height == 0 || frame.planes.iter().any(|plane| plane.is_null()) {
            return Err("VP9 decoder returned an invalid frame".to_string());
        }
        let chroma_width = width.div_ceil(2);
        let chroma_height = height.div_ceil(2);
        Ok(Self {
            width,
            height,
            y: copy_plane(frame.planes[0], frame.strides[0], width, height)?,
            u: copy_plane(
                frame.planes[1],
                frame.strides[1],
                chroma_width,
                chroma_height,
            )?,
            v: copy_plane(
                frame.planes[2],
                frame.strides[2],
                chroma_width,
                chroma_height,
            )?,
            full_range: frame.full_range != 0,
        })
    }

    fn to_rgba(&self, alpha: Option<&YuvFrame>) -> Result<Vec<u8>, String> {
        if let Some(alpha) = alpha {
            if alpha.width != self.width || alpha.height != self.height {
                return Err("VP9 color and alpha frames have different dimensions".to_string());
            }
        }
        let mut pixels = vec![0_u8; self.width * self.height * 4];
        for row in 0..self.height {
            for column in 0..self.width {
                let y = self.y[row * self.width + column] as i32;
                let u = self.u[(row / 2) * self.width.div_ceil(2) + column / 2] as i32;
                let v = self.v[(row / 2) * self.width.div_ceil(2) + column / 2] as i32;
                let (red, green, blue) = yuv_to_rgb(y, u, v, self.full_range);
                let alpha = alpha
                    .map(|frame| alpha_luma(frame.y[row * self.width + column], frame.full_range))
                    .unwrap_or(255);
                let offset = (row * self.width + column) * 4;
                pixels[offset..offset + 4].copy_from_slice(&[red, green, blue, alpha]);
            }
        }
        Ok(pixels)
    }
}

fn copy_plane(
    source: *const u8,
    stride: i32,
    width: usize,
    height: usize,
) -> Result<Vec<u8>, String> {
    let stride = usize::try_from(stride).map_err(|_| "VP9 frame has a negative stride")?;
    if stride < width {
        return Err("VP9 frame stride is smaller than its width".to_string());
    }
    let mut output = Vec::with_capacity(width * height);
    for row in 0..height {
        let bytes = unsafe { std::slice::from_raw_parts(source.add(row * stride), width) };
        output.extend_from_slice(bytes);
    }
    Ok(output)
}

fn yuv_to_rgb(y: i32, u: i32, v: i32, full_range: bool) -> (u8, u8, u8) {
    let (red, green, blue) = if full_range {
        let d = u - 128;
        let e = v - 128;
        (
            y + ((359 * e) >> 8),
            y - ((88 * d + 183 * e) >> 8),
            y + ((454 * d) >> 8),
        )
    } else {
        let c = (y - 16).max(0);
        let d = u - 128;
        let e = v - 128;
        (
            (298 * c + 409 * e + 128) >> 8,
            (298 * c - 100 * d - 208 * e + 128) >> 8,
            (298 * c + 516 * d + 128) >> 8,
        )
    };
    (
        red.clamp(0, 255) as u8,
        green.clamp(0, 255) as u8,
        blue.clamp(0, 255) as u8,
    )
}

fn alpha_luma(value: u8, full_range: bool) -> u8 {
    if full_range {
        value
    } else {
        (((value as i32 - 16).max(0) * 255 + 109) / 219).clamp(0, 255) as u8
    }
}

pub fn tgs_to_gif(input_path: &str, output_path: &str) -> Result<(), String> {
    let input = fs::read(input_path).map_err(|error| format!("read TGS: {error}"))?;
    let json = decompress_tgs(&input)?;
    let animation =
        Animation::from_json_str(&json).map_err(|error| format!("parse Lottie: {error}"))?;

    let source_fps = animation.frame_rate.max(1.0);
    let start = animation.in_point.floor();
    let end = animation.out_point.ceil().max(start + 1.0);
    let frame_step = (source_fps / TARGET_FPS.min(source_fps)).round().max(1.0);
    let actual_fps = source_fps / frame_step;
    let mut frames = Vec::new();
    let mut frame = start;
    while frame < end {
        frames.push(frame);
        frame += frame_step;
    }
    if frames.len() > MAX_FRAMES {
        let stride = (frames.len() as f32 / MAX_FRAMES as f32).ceil() as usize;
        frames = frames
            .into_iter()
            .step_by(stride.max(1))
            .take(MAX_FRAMES)
            .collect();
    }
    if frames.is_empty() {
        return Err("TGS contains no renderable frames".to_string());
    }

    let width = animation.width.max(1) as f32;
    let height = animation.height.max(1) as f32;
    let scale = (OUTPUT_SIZE as f32 / width)
        .min(OUTPUT_SIZE as f32 / height)
        .max(0.01);
    let config = RenderConfig::new(Rgba8::TRANSPARENT, scale);
    let profile = SupportProfile {
        allow_effects: true,
        allow_expressions: true,
        allow_unknown_shape_items: true,
        ..SupportProfile::target_corpus()
    };
    let prepared = Renderer::new(profile)
        .prepare(&animation)
        .map_err(|error| format!("prepare Lottie: {error}"))?;
    let first = prepared
        .render_frame(frames[0], config)
        .map_err(|error| format!("render TGS frame: {error}"))?;
    let width = u16::try_from(first.width).map_err(|_| "TGS width is too large".to_string())?;
    let height = u16::try_from(first.height).map_err(|_| "TGS height is too large".to_string())?;
    let delay = ((100.0 / actual_fps).round() as u16).max(1);
    let mut output = Vec::new();
    {
        let mut encoder = gif::Encoder::new(&mut output, width, height, &[])
            .map_err(|error| format!("initialize GIF: {error}"))?;
        encoder
            .set_repeat(gif::Repeat::Infinite)
            .map_err(|error| format!("configure GIF: {error}"))?;
        write_rgba_frame(&mut encoder, width, height, first.pixels, delay)?;
        for frame_number in &frames[1..] {
            let rendered = prepared
                .render_frame(*frame_number, config)
                .map_err(|error| format!("render TGS frame: {error}"))?;
            write_rgba_frame(&mut encoder, width, height, rendered.pixels, delay)?;
        }
    }
    fs::write(output_path, output).map_err(|error| format!("write GIF: {error}"))
}

fn decompress_tgs(input: &[u8]) -> Result<String, String> {
    if input.starts_with(&[0x1f, 0x8b]) {
        let mut decoder = GzDecoder::new(input);
        let mut output = String::new();
        decoder
            .read_to_string(&mut output)
            .map_err(|error| format!("decompress TGS: {error}"))?;
        Ok(output)
    } else {
        std::str::from_utf8(input)
            .map(str::to_owned)
            .map_err(|_| "TGS is not gzip or UTF-8 Lottie JSON".to_string())
    }
}

fn write_rgba_frame<W: std::io::Write>(
    encoder: &mut gif::Encoder<W>,
    width: u16,
    height: u16,
    mut pixels: Vec<u8>,
    delay: u16,
) -> Result<(), String> {
    let mut frame = gif::Frame::from_rgba_speed(width, height, &mut pixels, 10);
    frame.delay = delay;
    frame.dispose = gif::DisposalMethod::Background;
    encoder
        .write_frame(&frame)
        .map_err(|error| format!("encode GIF frame: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn gif_frame_count(data: &[u8]) -> usize {
        let mut options = gif::DecodeOptions::new();
        options.set_color_output(gif::ColorOutput::RGBA);
        let mut decoder = options.read_info(data).expect("decode GIF");
        let mut count = 0;
        while decoder.read_next_frame().expect("read GIF frame").is_some() {
            count += 1;
        }
        count
    }

    fn gif_has_transparent_pixel(data: &[u8]) -> bool {
        let mut options = gif::DecodeOptions::new();
        options.set_color_output(gif::ColorOutput::RGBA);
        let mut decoder = options.read_info(data).expect("decode GIF");
        while let Some(frame) = decoder.read_next_frame().expect("read GIF frame") {
            if frame.buffer.chunks_exact(4).any(|pixel| pixel[3] < 255) {
                return true;
            }
        }
        false
    }

    #[test]
    fn converts_tgs_sample_from_environment() {
        let Ok(input) = std::env::var("WEKIT_TGS_TEST_INPUT") else {
            return;
        };
        let output = std::env::temp_dir().join("wekit-telegram-tgs-test.gif");
        tgs_to_gif(&input, output.to_str().expect("UTF-8 output path")).expect("convert TGS");
        let data = fs::read(&output).expect("read GIF output");
        assert!(data.starts_with(b"GIF8"));
        assert!(data.len() > 100);
        assert!(gif_frame_count(&data) > 1);
        let _ = fs::remove_file(output);
    }

    #[test]
    fn converts_webm_sample_from_environment() {
        let Ok(input) = std::env::var("WEKIT_WEBM_TEST_INPUT") else {
            return;
        };
        let output = std::env::temp_dir().join("wekit-telegram-webm-test.gif");
        webm_to_gif(&input, output.to_str().expect("UTF-8 output path")).expect("convert WebM");
        let data = fs::read(&output).expect("read GIF output");
        assert!(data.starts_with(b"GIF8"));
        assert!(data.len() > 100);
        assert!(gif_frame_count(&data) > 1);
        assert!(gif_has_transparent_pixel(&data));
        let _ = fs::remove_file(output);
    }
}
