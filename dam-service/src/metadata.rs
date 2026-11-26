//! Asset metadata in the property names Sensible Studio writes to `jcr:content/metadata`.
//!
//! JSON integers map to `{Long}`, JSON floats to `{Decimal}`, arrays to
//! multi-value properties. Raw XMP is returned separately and parsed on the
//! Java side with an XMP library.

use std::collections::BTreeMap;

use image::ImageFormat;
use serde_json::{json, Value};
use sha1::{Digest, Sha1};

use crate::process::Decoded;

pub fn mime_of(format: ImageFormat) -> &'static str {
    match format {
        ImageFormat::Jpeg => "image/jpeg",
        ImageFormat::Png => "image/png",
        ImageFormat::WebP => "image/webp",
        ImageFormat::Gif => "image/gif",
        ImageFormat::Tiff => "image/tiff",
        _ => "application/octet-stream",
    }
}

fn file_format_name(format: ImageFormat) -> &'static str {
    match format {
        ImageFormat::Jpeg => "JPEG",
        ImageFormat::Png => "PNG",
        ImageFormat::WebP => "WEBP",
        _ => "UNKNOWN",
    }
}

/// Best-effort type detection for files we do not decode.
pub fn sniff_mime(bytes: &[u8], hint: Option<&str>) -> String {
    let head = &bytes[..bytes.len().min(1024)];
    if head.starts_with(b"%PDF") {
        return "application/pdf".into();
    }
    if String::from_utf8_lossy(head).contains("<svg") {
        return "image/svg+xml".into();
    }
    if let Ok(f) = image::guess_format(head) {
        return mime_of(f).into();
    }
    if let Some(m) = crate::video::sniff(head) {
        return m.into();
    }
    // The hint comes from the uploader: never let it claim an active type a browser would execute.
    hint.filter(|h| safe_hint(h)).unwrap_or("application/octet-stream").into()
}

fn safe_hint(h: &str) -> bool {
    let h = h.to_ascii_lowercase();
    let (top, sub) = match h.split_once('/') {
        Some(parts) => parts,
        None => return false,
    };
    h.len() < 128
        && matches!(top, "image" | "video" | "audio" | "application" | "font")
        && !sub.is_empty()
        && sub.bytes().all(|b| b.is_ascii_alphanumeric() || b"+.-".contains(&b))
        && !["svg", "html", "xml", "javascript", "ecmascript", "x-sh"].iter().any(|bad| sub.contains(bad))
}

pub fn extract(bytes: &[u8], decoded: Option<&Decoded>) -> BTreeMap<String, Value> {
    let mut m = BTreeMap::new();
    m.insert("dam:sha1".into(), json!(hex::encode(Sha1::digest(bytes))));
    m.insert("dam:size".into(), json!(bytes.len() as u64));

    let Some(d) = decoded else { return m };
    let mime = mime_of(d.format);
    m.insert("dam:MIMEtype".into(), json!(mime));
    m.insert("dc:format".into(), json!(mime));
    m.insert("dam:Fileformat".into(), json!(file_format_name(d.format)));
    m.insert("tiff:ImageWidth".into(), json!(d.stored_width));
    m.insert("tiff:ImageLength".into(), json!(d.stored_height));
    m.insert("dam:Bitsperpixel".into(), json!(d.color.bits_per_pixel()));
    m.insert("dam:Numberofimages".into(), json!(1));

    let exif = d.exif.as_deref().and_then(|raw| exif::Reader::new().read_raw(raw.to_vec()).ok());
    if let Some(exif) = &exif {
        add_exif(&mut m, exif);
    }

    let dpi = match d.format {
        ImageFormat::Jpeg => {
            let scan = scan_jpeg(bytes);
            m.insert("dam:Progressive".into(), json!(if scan.progressive { "yes" } else { "no" }));
            scan.dpi.or_else(|| exif.as_ref().and_then(exif_dpi))
        }
        ImageFormat::Png => png_dpi(bytes),
        _ => exif.as_ref().and_then(exif_dpi),
    };
    let (dx, dy) = dpi.unwrap_or((-1, -1));
    m.insert("dam:Physicalwidthindpi".into(), json!(dx));
    m.insert("dam:Physicalheightindpi".into(), json!(dy));
    if dx > 0 && dy > 0 {
        m.insert("dam:Physicalwidthininches".into(), json!(d.stored_width as f64 / dx as f64));
        m.insert("dam:Physicalheightininches".into(), json!(d.stored_height as f64 / dy as f64));
    }
    m
}

struct JpegScan {
    progressive: bool,
    dpi: Option<(i64, i64)>,
}

/// Walk JPEG markers up to the first scan: SOF type and JFIF density.
fn scan_jpeg(b: &[u8]) -> JpegScan {
    let mut out = JpegScan { progressive: false, dpi: None };
    let be16 = |i: usize| -> usize { ((b[i] as usize) << 8) | b[i + 1] as usize };
    let mut i = 2;
    while i + 4 <= b.len() {
        if b[i] != 0xFF {
            break;
        }
        let marker = b[i + 1];
        if marker == 0xFF {
            i += 1; // fill byte
            continue;
        }
        if marker == 0x01 || (0xD0..=0xD8).contains(&marker) {
            i += 2;
            continue;
        }
        let len = be16(i + 2);
        if len < 2 || i + 2 + len > b.len() {
            break;
        }
        match marker {
            0xE0 if len >= 16 && &b[i + 4..i + 9] == b"JFIF\0" => {
                let units = b[i + 11];
                let (x, y) = (be16(i + 12) as f64, be16(i + 14) as f64);
                out.dpi = match units {
                    1 => Some((x.round() as i64, y.round() as i64)),
                    2 => Some(((x * 2.54).round() as i64, (y * 2.54).round() as i64)),
                    _ => None,
                };
            }
            0xC0..=0xCF if !matches!(marker, 0xC4 | 0xC8 | 0xCC) => {
                out.progressive = matches!(marker, 0xC2 | 0xC6 | 0xCA | 0xCE);
                return out;
            }
            0xDA => return out,
            _ => {}
        }
        i += 2 + len;
    }
    out
}

/// `pHYs` chunk, when the unit is metres.
fn png_dpi(b: &[u8]) -> Option<(i64, i64)> {
    let mut i = 8;
    while i + 12 <= b.len() {
        let len = u32::from_be_bytes(b[i..i + 4].try_into().ok()?) as usize;
        let kind = &b[i + 4..i + 8];
        if kind == b"pHYs" && len == 9 && i + 17 <= b.len() {
            let x = u32::from_be_bytes(b[i + 8..i + 12].try_into().ok()?) as f64;
            let y = u32::from_be_bytes(b[i + 12..i + 16].try_into().ok()?) as f64;
            return (b[i + 16] == 1).then(|| ((x * 0.0254).round() as i64, (y * 0.0254).round() as i64));
        }
        if kind == b"IDAT" {
            return None;
        }
        i = i.checked_add(12 + len)?;
    }
    None
}

fn exif_dpi(exif: &exif::Exif) -> Option<(i64, i64)> {
    let rational = |tag| match &exif.get_field(tag, exif::In::PRIMARY)?.value {
        exif::Value::Rational(v) if !v.is_empty() && v[0].denom != 0 => Some(v[0].to_f64()),
        _ => None,
    };
    let unit = exif
        .get_field(exif::Tag::ResolutionUnit, exif::In::PRIMARY)
        .and_then(|f| f.value.get_uint(0))
        .unwrap_or(2);
    let factor = match unit {
        2 => 1.0,
        3 => 2.54,
        _ => return None,
    };
    let x = rational(exif::Tag::XResolution)?;
    let y = rational(exif::Tag::YResolution).unwrap_or(x);
    Some(((x * factor).round() as i64, (y * factor).round() as i64))
}

/// EXIF fields copied under their XMP names. Rationals keep the `n/d` form.
fn add_exif(m: &mut BTreeMap<String, Value>, exif: &exif::Exif) {
    use exif::Tag;
    const MAP: &[(Tag, &str)] = &[
        (Tag::Make, "tiff:Make"),
        (Tag::Model, "tiff:Model"),
        (Tag::Orientation, "tiff:Orientation"),
        (Tag::XResolution, "tiff:XResolution"),
        (Tag::YResolution, "tiff:YResolution"),
        (Tag::ResolutionUnit, "tiff:ResolutionUnit"),
        (Tag::Software, "tiff:Software"),
        (Tag::Artist, "tiff:Artist"),
        (Tag::Copyright, "tiff:Copyright"),
        (Tag::DateTimeOriginal, "exif:DateTimeOriginal"),
        (Tag::ExposureTime, "exif:ExposureTime"),
        (Tag::FNumber, "exif:FNumber"),
        (Tag::ExposureProgram, "exif:ExposureProgram"),
        (Tag::PhotographicSensitivity, "exif:ISOSpeedRatings"),
        (Tag::ExifVersion, "exif:ExifVersion"),
        (Tag::ShutterSpeedValue, "exif:ShutterSpeedValue"),
        (Tag::ApertureValue, "exif:ApertureValue"),
        (Tag::ExposureBiasValue, "exif:ExposureBiasValue"),
        (Tag::MaxApertureValue, "exif:MaxApertureValue"),
        (Tag::MeteringMode, "exif:MeteringMode"),
        (Tag::Flash, "exif:Flash"),
        (Tag::FocalLength, "exif:FocalLength"),
        (Tag::ColorSpace, "exif:ColorSpace"),
        (Tag::PixelXDimension, "exif:PixelXDimension"),
        (Tag::PixelYDimension, "exif:PixelYDimension"),
        (Tag::ExposureMode, "exif:ExposureMode"),
        (Tag::WhiteBalance, "exif:WhiteBalance"),
        (Tag::SceneCaptureType, "exif:SceneCaptureType"),
        (Tag::CustomRendered, "exif:CustomRendered"),
        (Tag::BodySerialNumber, "exifEX:BodySerialNumber"),
        (Tag::LensModel, "exifEX:LensModel"),
        (Tag::LensSerialNumber, "exifEX:LensSerialNumber"),
    ];
    for (tag, name) in MAP {
        let Some(field) = exif.get_field(*tag, exif::In::PRIMARY) else { continue };
        let value = match &field.value {
            exif::Value::Ascii(parts) => {
                let s = parts
                    .iter()
                    .map(|p| String::from_utf8_lossy(p).trim_end_matches('\0').trim().to_string())
                    .collect::<Vec<_>>()
                    .join(" ");
                if *tag == Tag::DateTimeOriginal {
                    json!(exif_datetime_to_iso(&s))
                } else {
                    json!(s)
                }
            }
            exif::Value::Rational(v) if v.len() == 1 => json!(format!("{}/{}", v[0].num, v[0].denom)),
            exif::Value::SRational(v) if v.len() == 1 => json!(format!("{}/{}", v[0].num, v[0].denom)),
            exif::Value::Undefined(bytes, _) if *tag == Tag::ExifVersion => {
                json!(String::from_utf8_lossy(bytes).to_string())
            }
            exif::Value::Short(v) if *tag == Tag::PhotographicSensitivity => json!(v),
            exif::Value::Short(v) if v.len() == 1 => json!(v[0]),
            exif::Value::Long(v) if v.len() == 1 => json!(v[0]),
            _ => continue,
        };
        if value.as_str().is_some_and(str::is_empty) {
            continue;
        }
        m.insert((*name).to_string(), value);
    }
}

/// `2016:09:07 04:13:42` → `2016-09-07T04:13:42`
fn exif_datetime_to_iso(s: &str) -> String {
    let b = s.as_bytes();
    // Check ASCII first: slicing a non-ASCII string at byte 19 could split a character.
    if b.len() >= 19 && b[..19].is_ascii() && b[4] == b':' && b[7] == b':' && b[10] == b' ' {
        format!("{}-{}-{}T{}", &s[0..4], &s[5..7], &s[8..10], &s[11..19])
    } else {
        s.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exif_dates_become_iso() {
        assert_eq!(exif_datetime_to_iso("2016:09:07 04:13:42"), "2016-09-07T04:13:42");
        assert_eq!(exif_datetime_to_iso("garbage"), "garbage");
        // Multi-byte character across byte 19 must not panic.
        assert_eq!(exif_datetime_to_iso("2016:09:07 04:13:4é"), "2016:09:07 04:13:4é");
    }

    #[test]
    fn sniffs_documents() {
        assert_eq!(sniff_mime(b"%PDF-1.4", None), "application/pdf");
        assert_eq!(sniff_mime(b"<?xml version='1.0'?><svg xmlns=", None), "image/svg+xml");
        assert_eq!(sniff_mime(b"????", Some("video/mp4")), "video/mp4");
        assert_eq!(sniff_mime(b"????", Some("bogus")), "application/octet-stream");
        assert_eq!(sniff_mime(b"????", Some("text/html")), "application/octet-stream");
        assert_eq!(sniff_mime(b"????", Some("application/xhtml+xml")), "application/octet-stream");
    }

    #[test]
    fn jpeg_scan_survives_truncated_input() {
        let s = scan_jpeg(&[0xFF, 0xD8, 0xFF, 0xE0, 0x00]);
        assert!(!s.progressive && s.dpi.is_none());
    }
}
