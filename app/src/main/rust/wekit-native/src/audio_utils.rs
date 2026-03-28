use anyhow::{Result, anyhow, bail};
use mp3lame_encoder::{Bitrate, Builder, FlushNoGap, MonoPcm, Quality};
use silk_v3_rs::*;
use std::ffi::c_void;
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom, Write};
use std::path::Path;
use symphonia::core::audio::SampleBuffer;
use symphonia::core::codecs::DecoderOptions;
use symphonia::core::formats::FormatOptions;
use symphonia::core::io::MediaSourceStream;
use symphonia::core::meta::MetadataOptions;
use symphonia::core::probe::Hint;
use symphonia::core::units::Time;

use crate::{loge, logi};

pub fn mp3_to_pcm_mono(path: &str) -> Result<(Vec<i16>, u32)> {
    let file = Box::new(File::open(path)?);
    let mss = MediaSourceStream::new(file, Default::default());
    let mut hint = Hint::new();
    hint.with_extension("mp3");

    let probed = symphonia::default::get_probe().format(
        &hint,
        mss,
        &FormatOptions::default(),
        &MetadataOptions::default(),
    )?;

    let mut format = probed.format;
    let track = format.default_track().unwrap();
    let sample_rate = track.codec_params.sample_rate.unwrap();
    let mut decoder =
        symphonia::default::get_codecs().make(&track.codec_params, &DecoderOptions::default())?;

    let mut samples: Vec<i16> = Vec::new();

    loop {
        let packet = match format.next_packet() {
            Ok(p) => p,
            Err(_) => break,
        };
        let decoded = decoder.decode(&packet)?;
        let spec = *decoded.spec();
        let mut buf = SampleBuffer::<i16>::new(decoded.capacity() as u64, spec);
        buf.copy_interleaved_ref(decoded);

        let channels = spec.channels.count();
        // Downmix to mono if needed
        let raw = buf.samples();
        if channels == 1 {
            samples.extend_from_slice(raw);
        } else {
            for chunk in raw.chunks(channels) {
                let mono = chunk.iter().map(|&s| s as i32).sum::<i32>() / channels as i32;
                samples.push(mono.clamp(i16::MIN as i32, i16::MAX as i32) as i16);
            }
        }
    }

    Ok((samples, sample_rate))
}

pub fn resample_to(samples: &[i16], from_rate: u32, to_rate: u32) -> Vec<i16> {
    if from_rate == to_rate {
        return samples.to_vec();
    }
    // Simple linear resampler (good enough for voice; use rubato for quality)
    let ratio = from_rate as f64 / to_rate as f64;
    let out_len = (samples.len() as f64 / ratio) as usize;
    (0..out_len)
        .map(|i| {
            let src = i as f64 * ratio;
            let idx = src as usize;
            let frac = src - idx as f64;
            let a = samples.get(idx).copied().unwrap_or(0) as f64;
            let b = samples.get(idx + 1).copied().unwrap_or(0) as f64;
            (a + frac * (b - a)) as i16
        })
        .collect()
}

pub fn pcm_bytes_to_silk(pcm: &[i16], mut out: impl Write) -> Result<()> {
    const SAMPLE_RATE: i32 = 24000;
    const FRAME_SIZE: usize = (SAMPLE_RATE as usize * SILK_FRAME_MS as usize) / 1000; // 480 samples/frame

    // WeChat-specific: prepend 0x02 before the SILK header
    out.write_all(&[0x02])?;
    out.write_all(SILK_MAGIC)?;

    // Allocate and initialize encoder
    let mut enc_size: i32 = 0;
    unsafe { SKP_Silk_SDK_Get_Encoder_Size(&mut enc_size) };
    if enc_size <= 0 {
        bail!("Invalid encoder size: {}", enc_size);
    }

    let mut enc_state = vec![0u8; enc_size as usize];
    let enc_state_ptr = enc_state.as_mut_ptr() as *mut c_void;

    let mut enc_ctrl = unsafe { std::mem::zeroed::<SKP_SILK_SDK_EncControlStruct>() };
    let ret = unsafe { SKP_Silk_SDK_InitEncoder(enc_state_ptr, &mut enc_ctrl) };
    if ret != 0 {
        bail!("Failed to init SILK encoder: {}", ret);
    }

    // Apply encoder params
    enc_ctrl.API_sampleRate = SAMPLE_RATE;
    enc_ctrl.maxInternalSampleRate = SAMPLE_RATE;
    enc_ctrl.packetSize = FRAME_SIZE as i32;
    enc_ctrl.bitRate = 25000;
    enc_ctrl.packetLossPercentage = 0;
    enc_ctrl.complexity = 2;
    enc_ctrl.useInBandFEC = 0;
    enc_ctrl.useDTX = 0;

    // Encode frames
    let mut frame_buf = vec![0i16; FRAME_SIZE];
    let mut out_buf = vec![0u8; 1024]; // output buffer for each frame

    let mut offset = 0;
    while offset < pcm.len() {
        let end = (offset + FRAME_SIZE).min(pcm.len());
        let frame_len = end - offset;

        // Copy samples and pad last frame with zeros
        frame_buf[..frame_len].copy_from_slice(&pcm[offset..end]);
        if frame_len < FRAME_SIZE {
            frame_buf[frame_len..].fill(0);
        }

        let mut n_bytes_out = out_buf.len() as i16;
        let ret = unsafe {
            SKP_Silk_SDK_Encode(
                enc_state_ptr,
                &enc_ctrl,
                frame_buf.as_ptr(),
                FRAME_SIZE as i32,
                out_buf.as_mut_ptr(),
                &mut n_bytes_out,
            )
        };

        if ret != 0 {
            bail!("SILK encode error: {}", ret);
        }

        out.write_all(&(n_bytes_out as i16).to_le_bytes())?;
        out.write_all(&out_buf[..n_bytes_out as usize])?;
        offset += FRAME_SIZE;
    }

    Ok(())
}

pub fn mp3_to_silk(mp3_path: &str, silk_path: &str) -> Result<()> {
    let (pcm, src_rate) = mp3_to_pcm_mono(mp3_path)?;
    logi!("mp3_to_pcm_mono done");
    let pcm = resample_to(&pcm, src_rate, 24000);
    logi!("resample_to done");
    let out_file = File::create(silk_path)?;
    pcm_bytes_to_silk(&pcm, out_file)?;
    logi!("encode_to_silk done");
    Ok(())
}

const SILK_MAGIC: &[u8] = b"#!SILK_V3";
const SILK_FRAME_MS: i32 = 20;

pub fn silk_to_pcm(silk_path: &str, pcm_path: &str, mut sample_rate: i32) -> Result<()> {
    const MAX_BYTES_PER_FRAME: usize = 1024;
    const MAX_INPUT_FRAMES: usize = 5;
    const MAX_API_FS_KHZ: usize = 48;

    // 1. Open Files
    let silk_file = File::open(silk_path)?;
    let mut silk_reader = BufReader::new(silk_file);
    let mut pcm_file = File::create(pcm_path)?;

    // 2. Validate Header
    seek_silk_header(&mut silk_reader)?;

    // 3. Setup Decoder Control
    if sample_rate <= 0 {
        sample_rate = 24000;
    }

    let mut decode_control = SKP_SILK_SDK_DecControlStruct {
        API_sampleRate: sample_rate,
        frameSize: 0,
        framesPerPacket: 0,
        moreInternalDecoderFrames: 0,
        inBandFECOffset: 0,
    };

    // 4. Initialize Decoder State
    let mut decode_size_bytes: i32 = 0;
    let res = unsafe { SKP_Silk_SDK_Get_Decoder_Size(&mut decode_size_bytes) };
    if res != 0 {
        bail!("SKP_Silk_SDK_Get_Decoder_Size failed: {}", res);
    }

    // Allocate memory for the decoder state
    let mut decode_state_vec = vec![0u8; decode_size_bytes as usize];
    let decode_state_ptr = decode_state_vec.as_mut_ptr() as *mut std::ffi::c_void;

    let res = unsafe { SKP_Silk_SDK_InitDecoder(decode_state_ptr) };
    if res != 0 {
        bail!("SKP_Silk_SDK_InitDecoder failed: {}", res);
    }

    // 5. Decoding Buffers
    let mut valid_data_buffer = [0u8; MAX_BYTES_PER_FRAME * MAX_INPUT_FRAMES];
    let mut out_buffer =
        [0i16; ((SILK_FRAME_MS as usize * MAX_API_FS_KHZ) << 1) * MAX_INPUT_FRAMES];

    // 6. Main Decoding Loop
    loop {
        let mut n_bytes_len_buf = [0u8; 2];
        if silk_reader.read_exact(&mut n_bytes_len_buf).is_err() {
            break; // End of file
        }

        // Convert 2 bytes to i16 (Length of the encoded frame)
        let valid_data_len = i16::from_le_bytes(n_bytes_len_buf);
        if valid_data_len <= 0 {
            break;
        }

        // Read the actual encoded data
        silk_reader.read_exact(&mut valid_data_buffer[..valid_data_len as usize])?;

        let mut out_offset = 0;
        let mut frames = 0;

        loop {
            let mut out_data_len: i16 = 0;

            let res = unsafe {
                SKP_Silk_SDK_Decode(
                    decode_state_ptr,
                    &mut decode_control,
                    0,                                    // lostFlag
                    valid_data_buffer.as_ptr().offset(0), // Simplified: buffer is reset per loop
                    valid_data_len as i32,
                    out_buffer.as_mut_ptr().add(out_offset),
                    &mut out_data_len,
                )
            };

            if res != 0 {
                loge!("SKP_Silk_SDK_Decode error: {}", res);
                break;
            }

            frames += 1;
            out_offset += out_data_len as usize;

            if frames > MAX_INPUT_FRAMES || decode_control.moreInternalDecoderFrames == 0 {
                break;
            }
        }

        // Write decoded i16 PCM data to file
        // We need to convert i16 slice to u8 slice for writing
        let pcm_bytes: &[u8] = unsafe {
            std::slice::from_raw_parts(
                out_buffer.as_ptr() as *const u8,
                out_offset * std::mem::size_of::<i16>(),
            )
        };
        pcm_file.write_all(pcm_bytes)?;
    }

    logi!("Silk decode finished successfully");
    Ok(())
}

pub fn pcm_to_mp3(
    pcm_file_path: &str,
    mp3_file_path: &str,
    sample_rate: u32,
    bitrate: i32,
) -> bool {
    use std::fs::File;
    use std::io::{Read, Write};

    // Read PCM
    let mut pcm_file = match File::open(pcm_file_path) {
        Ok(f) => f,
        Err(_) => return false,
    };

    let mut pcm_bytes = Vec::new();
    if pcm_file.read_to_end(&mut pcm_bytes).is_err() {
        return false;
    }

    if pcm_bytes.len() % 2 != 0 {
        return false;
    }

    // bytes -> i16
    let samples: Vec<i16> = pcm_bytes
        .chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]))
        .collect();

    // Map bitrate
    let bitrate = match bitrate {
        64 => Bitrate::Kbps64,
        96 => Bitrate::Kbps96,
        128 => Bitrate::Kbps128,
        160 => Bitrate::Kbps160,
        192 => Bitrate::Kbps192,
        256 => Bitrate::Kbps256,
        320 => Bitrate::Kbps320,
        _ => Bitrate::Kbps192,
    };

    // Build encoder
    let mut builder = match Builder::new() {
        Some(b) => b,
        None => return false,
    };

    if builder.set_num_channels(1).is_err() {
        return false;
    }

    if builder.set_sample_rate(sample_rate).is_err() {
        return false;
    }

    if builder.set_brate(bitrate).is_err() {
        return false;
    }

    if builder.set_quality(Quality::Best).is_err() {
        return false;
    }

    let mut encoder = match builder.build() {
        Ok(e) => e,
        Err(_) => return false,
    };

    let input = MonoPcm(&samples);

    let mut mp3_buffer = Vec::new();
    mp3_buffer.reserve(mp3lame_encoder::max_required_buffer_size(samples.len()));

    // Encode
    let encoded = match encoder.encode(input, mp3_buffer.spare_capacity_mut()) {
        Ok(size) => size,
        Err(_) => return false,
    };

    unsafe {
        mp3_buffer.set_len(encoded);
    }

    // Flush
    let flushed = match encoder.flush::<FlushNoGap>(mp3_buffer.spare_capacity_mut()) {
        Ok(size) => size,
        Err(_) => return false,
    };

    unsafe {
        mp3_buffer.set_len(mp3_buffer.len() + flushed);
    }

    // Write file
    let mut out_file = match File::create(mp3_file_path) {
        Ok(f) => f,
        Err(_) => return false,
    };

    if out_file.write_all(&mp3_buffer).is_err() {
        return false;
    }

    true
}

pub fn get_audio_duration_ms(path: &str) -> Result<i64> {
    let file_path = Path::new(path);

    let extension = file_path
        .extension()
        .and_then(|e| e.to_str())
        .map(|e| e.to_lowercase());

    match extension.as_deref() {
        Some("mp3") => get_mp3_duration_ms(path),
        Some("amr") => get_silk_duration_ms(path),
        Some(ext) => bail!("Unsupported file extension: .{}", ext),
        None => bail!("File has no extension: {}", path),
    }
}

fn get_mp3_duration_ms(path: &str) -> Result<i64> {
    let file = File::open(path)?;
    let mss = MediaSourceStream::new(Box::new(file), Default::default());

    let mut hint = Hint::new();
    hint.with_extension("mp3");

    let probed = symphonia::default::get_probe().format(
        &hint,
        mss,
        &FormatOptions::default(),
        &MetadataOptions::default(),
    )?;

    let format = probed.format;

    // Try to get duration from the default track
    let track = format
        .default_track()
        .ok_or_else(|| anyhow!("No default track found in: {}", path))?;

    let codec_params = &track.codec_params;

    let n_frames = codec_params
        .n_frames
        .ok_or_else(|| anyhow!("Could not determine frame count for: {}", path))?;

    let sample_rate = codec_params
        .sample_rate
        .ok_or_else(|| anyhow!("Could not determine sample rate for: {}", path))?;

    let time_base = codec_params
        .time_base
        .ok_or_else(|| anyhow!("Could not determine time base for: {}", path))?;

    let duration: Time = time_base.calc_time(n_frames);
    let ms = (duration.seconds * 1000) as i64 + (duration.frac * 1000.0) as i64;

    let _ = sample_rate; // available if needed for manual calculation
    Ok(ms)
}

fn seek_silk_header(reader: &mut (impl Read + Seek)) -> Result<()> {
    let mut header = [0u8; 9];
    reader.read_exact(&mut header)?;
    if &header[0..9] != SILK_MAGIC {
        // WeChat sometimes prepends a byte (0x02) or uses a slightly different offset
        // This is a basic check; you might need to seek if it's WeChat format
        if header[0] == 0x02 {
            reader.seek(SeekFrom::Start(10))?; // Skip WeChat's extra byte + header
        } else {
            bail!("Invalid Silk Header");
        }
    }
    Ok(())
}

fn get_silk_duration_ms(path: &str) -> Result<i64> {
    let file = File::open(path)?;
    let mut reader = BufReader::new(file);

    seek_silk_header(&mut reader)?;

    let mut total_ms: i64 = 0;

    loop {
        // Each packet is prefixed by a 2-byte little-endian payload length.
        // A length of 0xFFFF (-1 as i16) signals end-of-stream.
        let mut len_buf = [0u8; 2];
        match reader.read_exact(&mut len_buf) {
            Ok(_) => {}
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e.into()),
        }

        let packet_len = i16::from_le_bytes(len_buf);
        if packet_len < 0 {
            // End-of-stream marker
            break;
        }
        let packet_len = packet_len as usize;
        if packet_len == 0 {
            continue;
        }

        let mut payload = vec![0u8; packet_len];
        reader.read_exact(&mut payload)?;

        // Parse the TOC without decoding — zero-cost for duration purposes.
        let mut toc = SKP_Silk_TOC_struct {
            framesInPacket: 0,
            fs_kHz: 0,
            inbandLBRR: 0,
            corrupt: 0,
            vadFlags: [0; 5],
            sigtypeFlags: [0; 5],
        };

        unsafe {
            SKP_Silk_SDK_get_TOC(
                payload.as_ptr(),
                packet_len as std::os::raw::c_int,
                &mut toc as *mut SKP_Silk_TOC_struct,
            );
        }

        if toc.corrupt != 0 {
            // Skip corrupt packets rather than hard-erroring; a single bad
            // packet shouldn't invalidate the entire duration estimate.
            continue;
        }

        // framesInPacket is capped at SILK_MAX_FRAMES_PER_PACKET (5) by the SDK.
        total_ms += toc.framesInPacket as i64 * SILK_FRAME_MS as i64;
    }

    Ok(total_ms)
}
