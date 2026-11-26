//! Technical metadata of video and audio files, read from the container headers only (no
//! decoding): MP4 / QuickTime (ISO base media file format) and WebM / Matroska (EBML).
//!
//! Property names follow XMP Dynamic Media (`xmpDM:*`) where it has one, plus
//! `dam:duration` (seconds) and the display size as `tiff:ImageWidth` / `tiff:ImageLength`,
//! so the console's dimension and orientation filters work for videos too.
//!
//! Every read is bounds-checked: a damaged or hostile file yields fewer values, never a panic.

use std::collections::BTreeMap;

use serde_json::{json, Value};

/// Boxes / elements looked at, at most (a real file has a few hundred).
const MAX_NODES: usize = 20_000;
const MAX_DEPTH: usize = 8;

/// The container's MIME type, recognised from its first bytes.
pub fn sniff(b: &[u8]) -> Option<&'static str> {
    if b.len() >= 12 && &b[4..8] == b"ftyp" {
        let brand = &b[8..12];
        let compatible = &b[16.min(b.len())..b.len().min(64)];
        let has = |x: &[u8; 4]| brand == x || compatible.chunks_exact(4).any(|c| c == x);
        return Some(if brand == b"qt  " {
            "video/quicktime"
        } else if has(b"M4A ") || has(b"M4B ") {
            "audio/mp4"
        } else {
            "video/mp4"
        });
    }
    // Old QuickTime files start with the movie (or a wide/free atom) instead of ftyp.
    if b.len() >= 8 && (&b[4..8] == b"moov" || &b[4..8] == b"mdat" || &b[4..8] == b"wide") && be32(b, 0).is_some_and(|s| s >= 8) {
        return Some("video/quicktime");
    }
    if b.starts_with(&[0x1A, 0x45, 0xDF, 0xA3]) {
        let head = &b[..b.len().min(64)];
        return Some(if head.windows(4).any(|w| w == b"webm") { "video/webm" } else { "video/x-matroska" });
    }
    None
}

/// Metadata of a video / audio container, empty for anything else.
pub fn metadata(b: &[u8]) -> BTreeMap<String, Value> {
    let info = match sniff(b) {
        Some("video/webm" | "video/x-matroska") => matroska(b),
        Some(_) => bmff(b),
        None => return BTreeMap::new(),
    };
    info.into_map()
}

#[derive(Default, Debug, Clone, PartialEq)]
pub struct MediaInfo {
    pub duration: Option<f64>,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub fps: Option<f64>,
    pub video_codec: Option<String>,
    pub audio_codec: Option<String>,
    pub sample_rate: Option<u32>,
    pub channels: Option<u32>,
}

impl MediaInfo {
    fn into_map(self) -> BTreeMap<String, Value> {
        let mut m = BTreeMap::new();
        if let Some(d) = self.duration.filter(|d| d.is_finite() && *d > 0.0) {
            m.insert("dam:duration".into(), json!((d * 1000.0).round() / 1000.0));
        }
        if let (Some(w), Some(h)) = (self.width.filter(|w| *w > 0), self.height.filter(|h| *h > 0)) {
            m.insert("tiff:ImageWidth".into(), json!(w));
            m.insert("tiff:ImageLength".into(), json!(h));
        }
        if let Some(f) = self.fps.filter(|f| f.is_finite() && *f > 0.0 && *f < 1000.0) {
            m.insert("xmpDM:videoFrameRate".into(), json!((f * 100.0).round() / 100.0));
        }
        if let Some(c) = self.video_codec {
            m.insert("xmpDM:videoCompressor".into(), json!(c));
        }
        if let Some(c) = self.audio_codec {
            m.insert("xmpDM:audioCompressor".into(), json!(c));
        }
        if let Some(r) = self.sample_rate.filter(|r| *r > 0) {
            m.insert("xmpDM:audioSampleRate".into(), json!(r));
        }
        if let Some(c) = self.channels.filter(|c| *c > 0) {
            let t = match c {
                1 => "Mono",
                2 => "Stereo",
                6 => "5.1",
                8 => "7.1",
                16 => "16 Channel",
                _ => "Other",
            };
            m.insert("xmpDM:audioChannelType".into(), json!(t));
        }
        m
    }
}

fn be16(b: &[u8], i: usize) -> Option<u16> {
    Some(u16::from_be_bytes(b.get(i..i.checked_add(2)?)?.try_into().ok()?))
}

fn be32(b: &[u8], i: usize) -> Option<u32> {
    Some(u32::from_be_bytes(b.get(i..i.checked_add(4)?)?.try_into().ok()?))
}

fn be64(b: &[u8], i: usize) -> Option<u64> {
    Some(u64::from_be_bytes(b.get(i..i.checked_add(8)?)?.try_into().ok()?))
}

// ------------------------------------------------------------------------------ MP4 / QuickTime

/// The boxes directly inside `b`, as (type, payload).
fn boxes(b: &[u8]) -> impl Iterator<Item = ([u8; 4], &[u8])> {
    let mut i = 0usize;
    std::iter::from_fn(move || {
        let size32 = be32(b, i)? as u64;
        let kind: [u8; 4] = b.get(i + 4..i + 8)?.try_into().ok()?;
        let (header, size) = match size32 {
            0 => (8u64, (b.len() - i) as u64),
            1 => (16u64, be64(b, i + 8)?),
            s => (8u64, s),
        };
        if size < header {
            return None;
        }
        let end = i.checked_add(usize::try_from(size).ok()?)?;
        // A box running past the end (a truncated download): read what is there.
        let body = b.get(i + header as usize..end.min(b.len()))?;
        i = if end > b.len() { b.len() } else { end };
        Some((kind, body))
    })
}

fn child<'a>(b: &'a [u8], kind: &[u8; 4]) -> Option<&'a [u8]> {
    boxes(b).take(MAX_NODES).find(|(k, _)| k == kind).map(|(_, v)| v)
}

/// (timescale, duration) of an `mvhd` or `mdhd` box.
fn time_header(b: &[u8]) -> Option<(u32, u64)> {
    match *b.first()? {
        1 => Some((be32(b, 20)?, be64(b, 24)?)),
        _ => Some((be32(b, 12)?, be32(b, 16)? as u64)),
    }
}

fn bmff(b: &[u8]) -> MediaInfo {
    let mut info = MediaInfo::default();
    let Some(moov) = boxes(b).take(MAX_NODES).find(|(k, _)| k == b"moov").map(|(_, v)| v) else {
        return info;
    };
    if let Some((scale, dur)) = child(moov, b"mvhd").and_then(time_header) {
        if scale > 0 && dur > 0 && dur != u32::MAX as u64 && dur != u64::MAX {
            info.duration = Some(dur as f64 / scale as f64);
        }
        // Fragmented files (recorded in a browser, streamed) may carry it in mvex/mehd instead.
        if info.duration.is_none() && scale > 0 {
            let mehd = child(moov, b"mvex").and_then(|m| child(m, b"mehd"));
            let frag = mehd.and_then(|h| if *h.first()? == 1 { be64(h, 4) } else { be32(h, 4).map(u64::from) });
            if let Some(d) = frag.filter(|d| *d > 0) {
                info.duration = Some(d as f64 / scale as f64);
            }
        }
    }
    for (kind, trak) in boxes(moov).take(MAX_NODES) {
        if &kind != b"trak" {
            continue;
        }
        let Some(mdia) = child(trak, b"mdia") else { continue };
        let handler = child(mdia, b"hdlr").and_then(|h| h.get(8..12));
        let stbl = child(mdia, b"minf").and_then(|m| child(m, b"stbl"));
        let entry = stbl.and_then(|s| child(s, b"stsd")).and_then(|s| boxes(s.get(8..)?).next());
        match handler {
            Some(b"vide") if info.video_codec.is_none() => {
                if let Some((fourcc, _)) = entry {
                    info.video_codec = Some(codec_name(&fourcc));
                }
                if let Some(tkhd) = child(trak, b"tkhd") {
                    let off = if tkhd.first() == Some(&1) { 4 + 32 } else { 4 + 20 };
                    // 8 reserved, layer, group, volume, reserved, then the 3x3 matrix and the size.
                    let matrix = off + 8 + 8;
                    let (a, bm) = (be32(tkhd, matrix).map(|v| v as i32), be32(tkhd, matrix + 4).map(|v| v as i32));
                    let w = be32(tkhd, matrix + 36).map(|v| v >> 16);
                    let h = be32(tkhd, matrix + 40).map(|v| v >> 16);
                    // A phone video filmed upright is stored landscape with a 90° matrix.
                    let turned = a == Some(0) && bm.is_some_and(|v| v.unsigned_abs() == 0x10000);
                    let (w, h) = if turned { (h, w) } else { (w, h) };
                    info.width = w.filter(|v| *v > 0);
                    info.height = h.filter(|v| *v > 0);
                }
                if info.width.is_none() {
                    // No track size (some encoders): the coded size of the sample entry.
                    if let Some((_, e)) = entry {
                        info.width = be16(e, 24).map(u32::from).filter(|v| *v > 0);
                        info.height = be16(e, 26).map(u32::from).filter(|v| *v > 0);
                    }
                }
                let media_time = child(mdia, b"mdhd").and_then(time_header);
                let samples: Option<u64> = stbl.and_then(|s| child(s, b"stts")).and_then(|t| {
                    let n = be32(t, 4)? as usize;
                    let mut total = 0u64;
                    for k in 0..n.min(1 << 20) {
                        total = total.checked_add(be32(t, 8 + k * 8)? as u64)?;
                    }
                    Some(total)
                });
                if let (Some((scale, dur)), Some(n)) = (media_time, samples) {
                    if scale > 0 && dur > 0 && n > 0 {
                        info.fps = Some(n as f64 * scale as f64 / dur as f64);
                    }
                }
                if info.duration.is_none() {
                    info.duration = media_time.filter(|(s, d)| *s > 0 && *d > 0).map(|(s, d)| d as f64 / s as f64);
                }
                // Fragmented (browser recordings): no sample table, maybe no total either; add up the
                // fragments of this track.
                let fragmented = info.duration.is_none() || samples.unwrap_or(0) == 0;
                if let (true, Some((scale, _))) = (fragmented, media_time) {
                    let id = child(trak, b"tkhd").and_then(|t| be32(t, if t.first() == Some(&1) { 20 } else { 12 }));
                    let default = child(moov, b"mvex").and_then(|m| {
                        boxes(m).take(MAX_NODES).find(|(k, v)| k == b"trex" && be32(v, 4) == id).and_then(|(_, v)| be32(v, 12))
                    });
                    if let (Some(id), true) = (id, scale > 0) {
                        if let Some((ticks, n)) = fragments(b, id, default.unwrap_or(0)).filter(|(t, _)| *t > 0) {
                            info.duration = info.duration.or(Some(ticks as f64 / scale as f64));
                            info.fps = Some(n as f64 * scale as f64 / ticks as f64);
                        }
                    }
                }
            }
            Some(b"soun") if info.audio_codec.is_none() => {
                if let Some((fourcc, e)) = entry {
                    info.audio_codec = Some(codec_name(&fourcc));
                    // Sound sample entry after the 8 bytes of every entry: version (QuickTime; 0 in ISO
                    // files), revision, vendor, then channels and rate (16.16) in versions 0 and 1;
                    // version 2 moves both to a float64 and a u32 further on.
                    match be16(e, 8) {
                        Some(0 | 1) => {
                            info.channels = be16(e, 16).map(u32::from);
                            info.sample_rate = be32(e, 24).map(|r| r >> 16);
                        }
                        Some(2) => {
                            info.sample_rate = be64(e, 32)
                                .map(f64::from_bits)
                                .filter(|r| r.is_finite() && *r > 0.0 && *r < 1e7)
                                .map(|r| r.round() as u32);
                            info.channels = be32(e, 40);
                        }
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }
    info
}

/// (media ticks, samples) of one track over every `moof` of a fragmented file.
fn fragments(b: &[u8], track: u32, trex_default: u32) -> Option<(u64, u64)> {
    let (mut ticks, mut samples) = (0u64, 0u64);
    for (kind, moof) in boxes(b).take(MAX_NODES * 10) {
        if &kind != b"moof" {
            continue;
        }
        for (k, traf) in boxes(moof).take(MAX_NODES) {
            if &k != b"traf" {
                continue;
            }
            let tfhd = child(traf, b"tfhd")?;
            if be32(tfhd, 4)? != track {
                continue;
            }
            let flags = be32(tfhd, 0)? & 0x00FF_FFFF;
            let mut off = 8;
            if flags & 0x01 != 0 {
                off += 8; // base data offset
            }
            if flags & 0x02 != 0 {
                off += 4; // sample description index
            }
            let default = if flags & 0x08 != 0 { be32(tfhd, off)? } else { trex_default };
            for (k2, trun) in boxes(traf).take(MAX_NODES) {
                if &k2 != b"trun" {
                    continue;
                }
                let tf = be32(trun, 0)? & 0x00FF_FFFF;
                let n = be32(trun, 4)? as u64;
                samples = samples.checked_add(n)?;
                if tf & 0x100 == 0 {
                    ticks = ticks.checked_add(n.checked_mul(default as u64)?)?;
                    continue;
                }
                let mut p = 8 + if tf & 0x01 != 0 { 4 } else { 0 } + if tf & 0x04 != 0 { 4 } else { 0 };
                let stride = 4 * [0x100, 0x200, 0x400, 0x800].iter().filter(|f| tf & **f != 0).count();
                for _ in 0..n.min(1 << 22) {
                    ticks = ticks.checked_add(be32(trun, p)? as u64)?;
                    p += stride;
                }
            }
        }
    }
    Some((ticks, samples))
}

fn codec_name(fourcc: &[u8; 4]) -> String {
    let code = String::from_utf8_lossy(fourcc).trim().to_string();
    let name = match fourcc {
        b"avc1" | b"avc3" => "H.264",
        b"hvc1" | b"hev1" => "H.265 (HEVC)",
        b"av01" => "AV1",
        b"vp08" => "VP8",
        b"vp09" => "VP9",
        b"mp4v" => "MPEG-4 Visual",
        b"apch" | b"apcn" | b"apcs" | b"apco" | b"ap4h" | b"ap4x" => "Apple ProRes",
        b"jpeg" | b"mjpa" | b"mjpb" => "Motion JPEG",
        b"mp4a" => "AAC",
        b"ac-3" => "Dolby Digital (AC-3)",
        b"ec-3" => "Dolby Digital Plus (E-AC-3)",
        b"Opus" => "Opus",
        b"fLaC" => "FLAC",
        b"alac" => "Apple Lossless",
        b"lpcm" | b"sowt" | b"twos" | b"in24" | b"in32" | b"fl32" => "PCM",
        b".mp3" => "MP3",
        _ => "",
    };
    if name.is_empty() {
        code.chars().filter(|c| c.is_ascii_graphic() || *c == ' ').collect()
    } else {
        format!("{name} ({code})")
    }
}

// ------------------------------------------------------------------------------ WebM / Matroska

const SEGMENT: u32 = 0x1853_8067;
const INFO: u32 = 0x1549_A966;
const TIMECODE_SCALE: u32 = 0x2A_D7B1;
const DURATION: u32 = 0x4489;
const TRACKS: u32 = 0x1654_AE6B;
const TRACK_ENTRY: u32 = 0xAE;
const TRACK_TYPE: u32 = 0x83;
const CODEC_ID: u32 = 0x86;
const DEFAULT_DURATION: u32 = 0x23_E383;
const VIDEO: u32 = 0xE0;
const PIXEL_WIDTH: u32 = 0xB0;
const PIXEL_HEIGHT: u32 = 0xBA;
const AUDIO: u32 = 0xE1;
const SAMPLING_FREQUENCY: u32 = 0xB5;
const CHANNELS: u32 = 0x9F;
const CLUSTER: u32 = 0x1F43_B675;

/// One EBML variable-length integer: (value, length). IDs keep their marker bit.
fn vint(b: &[u8], i: usize, keep_marker: bool) -> Option<(u64, usize)> {
    let first = *b.get(i)?;
    let len = first.leading_zeros() as usize + 1;
    if len > 8 {
        return None;
    }
    let mut v = if keep_marker { first as u64 } else { (first as u64) & (0xFF >> len) };
    for k in 1..len {
        v = (v << 8) | *b.get(i + k)? as u64;
    }
    Some((v, len))
}

/// The elements directly inside `b` as (id, payload); unknown sizes run to the end.
fn elements(b: &[u8]) -> impl Iterator<Item = (u32, &[u8])> {
    let mut i = 0usize;
    std::iter::from_fn(move || {
        let (id, il) = vint(b, i, true)?;
        let (size, sl) = vint(b, i + il, false)?;
        let start = i + il + sl;
        let unknown = size == (1u64 << (7 * sl)) - 1;
        let end = if unknown { b.len() } else { start.checked_add(usize::try_from(size).ok()?)?.min(b.len()) };
        let body = b.get(start..end)?;
        i = end;
        Some((u32::try_from(id).ok()?, body))
    })
}

fn uint(b: &[u8]) -> Option<u64> {
    if b.is_empty() || b.len() > 8 {
        return None;
    }
    Some(b.iter().fold(0u64, |v, x| (v << 8) | *x as u64))
}

fn float(b: &[u8]) -> Option<f64> {
    match b.len() {
        4 => Some(f32::from_be_bytes(b.try_into().ok()?) as f64),
        8 => Some(f64::from_be_bytes(b.try_into().ok()?)),
        _ => None,
    }
}

fn matroska(b: &[u8]) -> MediaInfo {
    let mut info = MediaInfo::default();
    let Some(segment) = elements(b).take(16).find(|(id, _)| *id == SEGMENT).map(|(_, v)| v) else {
        return info;
    };
    let mut scale = 1_000_000u64; // nanoseconds per timecode unit
    let mut duration = None;
    let mut seen = 0usize;
    for (id, body) in elements(segment) {
        seen += 1;
        if seen > MAX_NODES || id == CLUSTER {
            break; // the headers come before the media; recorded files never end their segment
        }
        match id {
            INFO => {
                for (k, v) in elements(body).take(MAX_NODES) {
                    match k {
                        TIMECODE_SCALE => scale = uint(v).filter(|s| *s > 0).unwrap_or(scale),
                        DURATION => duration = float(v),
                        _ => {}
                    }
                }
            }
            TRACKS => {
                for (k, entry) in elements(body).take(MAX_NODES) {
                    if k == TRACK_ENTRY {
                        track(entry, &mut info);
                    }
                }
            }
            _ => {}
        }
    }
    info.duration = duration.map(|d| d * scale as f64 / 1e9).filter(|d| d.is_finite() && *d > 0.0);
    info
}

fn track(entry: &[u8], info: &mut MediaInfo) {
    let mut kind = 0;
    let mut codec = None;
    let mut frame_ns = None;
    let (mut w, mut h, mut rate, mut ch) = (None, None, None, None);
    for (k, v) in elements(entry).take(MAX_NODES) {
        match k {
            TRACK_TYPE => kind = uint(v).unwrap_or(0),
            CODEC_ID => codec = Some(String::from_utf8_lossy(v).trim_end_matches('\0').to_string()),
            DEFAULT_DURATION => frame_ns = uint(v),
            VIDEO => {
                for (k2, v2) in elements(v).take(MAX_DEPTH * 8) {
                    match k2 {
                        PIXEL_WIDTH => w = uint(v2),
                        PIXEL_HEIGHT => h = uint(v2),
                        _ => {}
                    }
                }
            }
            AUDIO => {
                for (k2, v2) in elements(v).take(MAX_DEPTH * 8) {
                    match k2 {
                        SAMPLING_FREQUENCY => rate = float(v2),
                        CHANNELS => ch = uint(v2),
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }
    let name = |id: &str| -> String {
        let n = match id {
            "V_VP8" => "VP8",
            "V_VP9" => "VP9",
            "V_AV1" => "AV1",
            "V_MPEG4/ISO/AVC" => "H.264",
            "V_MPEGH/ISO/HEVC" => "H.265 (HEVC)",
            "A_OPUS" => "Opus",
            "A_VORBIS" => "Vorbis",
            "A_AAC" => "AAC",
            "A_FLAC" => "FLAC",
            "A_MPEG/L3" => "MP3",
            "A_AC3" => "Dolby Digital (AC-3)",
            "A_EAC3" => "Dolby Digital Plus (E-AC-3)",
            _ => "",
        };
        let id: String = id.chars().filter(|c| c.is_ascii_graphic()).take(32).collect();
        if n.is_empty() { id } else { format!("{n} ({id})") }
    };
    match kind {
        1 if info.video_codec.is_none() => {
            info.video_codec = codec.as_deref().map(name);
            info.width = w.and_then(|v| u32::try_from(v).ok());
            info.height = h.and_then(|v| u32::try_from(v).ok());
            info.fps = frame_ns.filter(|n| *n > 0).map(|n| 1e9 / n as f64);
        }
        2 if info.audio_codec.is_none() => {
            info.audio_codec = codec.as_deref().map(name);
            info.sample_rate = rate.filter(|r| r.is_finite() && *r > 0.0 && *r < 1e7).map(|r| r.round() as u32);
            info.channels = ch.and_then(|v| u32::try_from(v).ok());
        }
        _ => {}
    }
}

#[cfg(test)]
pub mod tests_support {
    //! Small but well-formed containers, written box by box.

    pub fn bx(kind: &[u8; 4], body: &[u8]) -> Vec<u8> {
        let mut v = ((body.len() + 8) as u32).to_be_bytes().to_vec();
        v.extend_from_slice(kind);
        v.extend_from_slice(body);
        v
    }

    fn full(kind: &[u8; 4], version: u8, body: &[u8]) -> Vec<u8> {
        let mut b = vec![version, 0, 0, 0];
        b.extend_from_slice(body);
        bx(kind, &b)
    }

    fn time(version: u8, scale: u32, dur: u64) -> Vec<u8> {
        let mut b = Vec::new();
        if version == 1 {
            b.extend_from_slice(&[0; 16]);
            b.extend_from_slice(&scale.to_be_bytes());
            b.extend_from_slice(&dur.to_be_bytes());
        } else {
            b.extend_from_slice(&[0; 8]);
            b.extend_from_slice(&scale.to_be_bytes());
            b.extend_from_slice(&(dur as u32).to_be_bytes());
        }
        b.extend_from_slice(&[0; 80]);
        b
    }

    fn tkhd(w: u32, h: u32, turned: bool) -> Vec<u8> {
        let mut b = vec![0u8; 20]; // version 0: times, track id, reserved, duration
        b.extend_from_slice(&[0; 16]); // reserved, layer, group, volume, reserved
        let one = 0x10000u32;
        let m: [u32; 9] = if turned {
            [0, one, 0, (-(one as i32)) as u32, 0, 0, 0, 0, 0x4000_0000]
        } else {
            [one, 0, 0, 0, one, 0, 0, 0, 0x4000_0000]
        };
        for x in m {
            b.extend_from_slice(&x.to_be_bytes());
        }
        b.extend_from_slice(&(w << 16).to_be_bytes());
        b.extend_from_slice(&(h << 16).to_be_bytes());
        full(b"tkhd", 0, &b)
    }

    fn hdlr(kind: &[u8; 4]) -> Vec<u8> {
        let mut b = vec![0u8; 4];
        b.extend_from_slice(kind);
        b.extend_from_slice(&[0; 13]);
        full(b"hdlr", 0, &b)
    }

    fn stsd(entry: Vec<u8>) -> Vec<u8> {
        let mut b = 1u32.to_be_bytes().to_vec();
        b.extend(entry);
        full(b"stsd", 0, &b)
    }

    fn stts(samples: u32, delta: u32) -> Vec<u8> {
        let mut b = 1u32.to_be_bytes().to_vec();
        b.extend_from_slice(&samples.to_be_bytes());
        b.extend_from_slice(&delta.to_be_bytes());
        full(b"stts", 0, &b)
    }

    fn trak(parts: Vec<Vec<u8>>) -> Vec<u8> {
        bx(b"trak", &parts.concat())
    }

    /// An MP4: 10.24 s, 1920x1080 H.264 at 25 fps (optionally filmed upright), AAC stereo 48 kHz.
    pub fn mp4(turned: bool, brand: &[u8; 4]) -> Vec<u8> {
        let mut avc1 = vec![0u8; 24];
        avc1.extend_from_slice(&1920u16.to_be_bytes());
        avc1.extend_from_slice(&1080u16.to_be_bytes());
        avc1.extend_from_slice(&[0; 50]);
        let video = trak(vec![
            tkhd(1920, 1080, turned),
            bx(
                b"mdia",
                &[
                    full(b"mdhd", 0, &time(0, 12800, 131_072)),
                    hdlr(b"vide"),
                    bx(b"minf", &bx(b"stbl", &[stsd(bx(b"avc1", &avc1)), stts(256, 512)].concat())),
                ]
                .concat(),
            ),
        ]);
        let mut mp4a = vec![0u8; 8];
        mp4a.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0]); // version 0, revision, vendor
        mp4a.extend_from_slice(&2u16.to_be_bytes()); // channels
        mp4a.extend_from_slice(&16u16.to_be_bytes());
        mp4a.extend_from_slice(&[0; 4]);
        mp4a.extend_from_slice(&(48_000u32 << 16).to_be_bytes());
        let audio = trak(vec![
            tkhd(0, 0, false),
            bx(
                b"mdia",
                &[
                    full(b"mdhd", 0, &time(0, 48_000, 491_520)),
                    hdlr(b"soun"),
                    bx(b"minf", &bx(b"stbl", &stsd(bx(b"mp4a", &mp4a)))),
                ]
                .concat(),
            ),
        ]);
        let moov = bx(b"moov", &[full(b"mvhd", 1, &time(1, 1000, 10_240)), video, audio].concat());
        let mut ftyp = brand.to_vec();
        ftyp.extend_from_slice(&[0, 0, 2, 0]);
        ftyp.extend_from_slice(b"isomiso2avc1mp41");
        // The movie after the media, as cameras write it.
        [bx(b"ftyp", &ftyp), bx(b"mdat", &[0u8; 64]), moov].concat()
    }

    /// A fragmented MP4 as a browser records it: no total duration, two fragments of 25 frames
    /// of 40 ms (the second with per-sample durations).
    pub fn fragmented() -> Vec<u8> {
        let mut tk = vec![0u8; 20];
        tk[8..12].copy_from_slice(&1u32.to_be_bytes());
        tk.extend_from_slice(&[0; 16]);
        for x in [0x10000u32, 0, 0, 0, 0x10000, 0, 0, 0, 0x4000_0000] {
            tk.extend_from_slice(&x.to_be_bytes());
        }
        tk.extend_from_slice(&(640u32 << 16).to_be_bytes());
        tk.extend_from_slice(&(480u32 << 16).to_be_bytes());
        let mut avc1 = vec![0u8; 24];
        avc1.extend_from_slice(&640u16.to_be_bytes());
        avc1.extend_from_slice(&480u16.to_be_bytes());
        let video = trak(vec![
            full(b"tkhd", 0, &tk),
            bx(
                b"mdia",
                &[
                    full(b"mdhd", 0, &time(0, 1000, 0)),
                    hdlr(b"vide"),
                    bx(b"minf", &bx(b"stbl", &[stsd(bx(b"avc1", &avc1)), full(b"stts", 0, &[0; 4])].concat())),
                ]
                .concat(),
            ),
        ]);
        let mut trex = 1u32.to_be_bytes().to_vec();
        trex.extend_from_slice(&1u32.to_be_bytes());
        trex.extend_from_slice(&40u32.to_be_bytes());
        trex.extend_from_slice(&[0; 8]);
        let moov = bx(b"moov", &[full(b"mvhd", 0, &time(0, 1000, 0)), video, bx(b"mvex", &full(b"trex", 0, &trex))].concat());
        let tfhd = full(b"tfhd", 0, &1u32.to_be_bytes());
        let mut run1 = 25u32.to_be_bytes().to_vec();
        run1.extend_from_slice(&0u32.to_be_bytes()); // data offset (flag 0x01)
        let trun1 = {
            let mut b = vec![0, 0, 0, 0x01];
            b.extend(run1);
            bx(b"trun", &b)
        };
        let trun2 = {
            let mut b = vec![0, 0, 0x03, 0x00]; // per-sample duration and size
            b.extend_from_slice(&25u32.to_be_bytes());
            for _ in 0..25 {
                b.extend_from_slice(&40u32.to_be_bytes());
                b.extend_from_slice(&100u32.to_be_bytes());
            }
            bx(b"trun", &b)
        };
        let moof = |run: Vec<u8>| bx(b"moof", &bx(b"traf", &[tfhd.clone(), run].concat()));
        let mut ftyp = b"iso5".to_vec();
        ftyp.extend_from_slice(&[0, 0, 2, 0]);
        [bx(b"ftyp", &ftyp), moov, moof(trun1), bx(b"mdat", &[0; 16]), moof(trun2), bx(b"mdat", &[0; 16])].concat()
    }

    fn el(id: u32, body: &[u8]) -> Vec<u8> {
        let id_bytes: Vec<u8> = id.to_be_bytes().into_iter().skip_while(|b| *b == 0).collect();
        let mut v = id_bytes;
        // 8-byte size: 0x01 followed by seven bytes.
        v.push(0x01);
        v.extend_from_slice(&(body.len() as u64).to_be_bytes()[1..]);
        v.extend_from_slice(body);
        v
    }

    /// A WebM like a browser records it: unknown segment size, VP9 640x360 30 fps, Opus mono.
    pub fn webm(with_duration: bool) -> Vec<u8> {
        let header = el(0x1A45_DFA3, &el(0x4282, b"webm"));
        let mut info = el(super::TIMECODE_SCALE, &1_000_000u32.to_be_bytes());
        if with_duration {
            info.extend(el(super::DURATION, &5500.0f64.to_be_bytes()));
        }
        let video = [
            el(super::TRACK_TYPE, &[1]),
            el(super::CODEC_ID, b"V_VP9"),
            el(super::DEFAULT_DURATION, &33_333_333u32.to_be_bytes()),
            el(super::VIDEO, &[el(super::PIXEL_WIDTH, &640u16.to_be_bytes()), el(super::PIXEL_HEIGHT, &360u16.to_be_bytes())].concat()),
        ]
        .concat();
        let audio = [
            el(super::TRACK_TYPE, &[2]),
            el(super::CODEC_ID, b"A_OPUS"),
            el(super::AUDIO, &[el(super::SAMPLING_FREQUENCY, &48_000.0f32.to_be_bytes()), el(super::CHANNELS, &[1])].concat()),
        ]
        .concat();
        let tracks = el(super::TRACKS, &[el(super::TRACK_ENTRY, &video), el(super::TRACK_ENTRY, &audio)].concat());
        let cluster = el(super::CLUSTER, &[0u8; 32]);
        let mut segment = vec![0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF];
        segment.extend([el(super::INFO, &info), tracks, cluster].concat());
        [header, segment].concat()
    }
}

#[cfg(test)]
mod tests {
    use super::tests_support::*;
    use super::*;

    #[test]
    fn mp4_headers() {
        let b = mp4(false, b"isom");
        assert_eq!(sniff(&b), Some("video/mp4"));
        let i = bmff(&b);
        assert_eq!(i.duration, Some(10.24));
        assert_eq!((i.width, i.height), (Some(1920), Some(1080)));
        assert_eq!(i.fps, Some(25.0));
        assert_eq!(i.video_codec.as_deref(), Some("H.264 (avc1)"));
        assert_eq!(i.audio_codec.as_deref(), Some("AAC (mp4a)"));
        assert_eq!((i.sample_rate, i.channels), (Some(48_000), Some(2)));
        let m = metadata(&b);
        assert_eq!(m["dam:duration"], json!(10.24));
        assert_eq!(m["xmpDM:audioChannelType"], json!("Stereo"));
        assert_eq!(m["tiff:ImageLength"], json!(1080));
    }

    #[test]
    fn fragmented_files_add_up_their_fragments() {
        let f = fragmented();
        let i = bmff(&f);
        assert_eq!(i.duration, Some(2.0));
        assert_eq!(i.fps, Some(25.0));
        assert_eq!((i.width, i.height), (Some(640), Some(480)));
        // Chrome writes the total in mvhd: the frame rate still comes from the fragments.
        let at = f.windows(4).position(|w| w == b"mvhd").unwrap() + 4;
        let mut with_total = f.clone();
        with_total[at + 16..at + 20].copy_from_slice(&1990u32.to_be_bytes());
        let j = bmff(&with_total);
        assert_eq!(j.duration, Some(1.99));
        assert_eq!(j.fps, Some(25.0));
    }

    #[test]
    fn upright_phone_video_is_portrait() {
        let i = bmff(&mp4(true, b"qt  "));
        assert_eq!((i.width, i.height), (Some(1080), Some(1920)));
        assert_eq!(sniff(&mp4(true, b"qt  ")), Some("video/quicktime"));
        assert_eq!(sniff(&mp4(false, b"M4A ")), Some("audio/mp4"));
    }

    #[test]
    fn webm_headers() {
        let b = webm(true);
        assert_eq!(sniff(&b), Some("video/webm"));
        let i = matroska(&b);
        assert_eq!(i.duration, Some(5.5));
        assert_eq!((i.width, i.height), (Some(640), Some(360)));
        assert_eq!(i.fps.map(|f| f.round()), Some(30.0));
        assert_eq!(i.video_codec.as_deref(), Some("VP9 (V_VP9)"));
        assert_eq!(i.audio_codec.as_deref(), Some("Opus (A_OPUS)"));
        assert_eq!((i.sample_rate, i.channels), (Some(48_000), Some(1)));
        // Browser recordings have no duration: the rest is still read.
        let r = matroska(&webm(false));
        assert_eq!(r.duration, None);
        assert_eq!(r.width, Some(640));
        assert!(!metadata(&webm(false)).contains_key("dam:duration"));
    }

    #[test]
    fn damaged_files_give_what_they_can() {
        let b = mp4(false, b"isom");
        for cut in [0, 7, 12, 40, 100, b.len() / 2, b.len() - 1] {
            let _ = metadata(&b[..cut]); // no panic
        }
        let w = webm(true);
        for cut in [0, 3, 10, 30, w.len() / 2, w.len() - 1] {
            let _ = metadata(&w[..cut]);
        }
        // Sizes that lie: a box claiming to be 4 GB, an EBML size past the end.
        let mut lying = bx(b"ftyp", b"isom\0\0\0\0");
        lying.extend_from_slice(&[0xFF, 0xFF, 0xFF, 0xF0]);
        lying.extend_from_slice(b"moov");
        lying.extend_from_slice(&[0; 32]);
        assert_eq!(metadata(&lying), BTreeMap::new());
        assert!(metadata(b"not a video at all").is_empty());
        let mut deep = Vec::new();
        for _ in 0..1_000 {
            deep = bx(b"moov", &deep);
        }
        let _ = metadata(&[bx(b"ftyp", b"isom\0\0\0\0"), deep].concat());
    }
}
