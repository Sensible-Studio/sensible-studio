//! Decode once, produce every rendition of a profile, extract metadata.

use std::collections::BTreeMap;
use std::io::Cursor;

use fast_image_resize::{ResizeOptions, Resizer};
use image::codecs::jpeg::JpegEncoder;
use image::codecs::png::{CompressionType, FilterType, PngEncoder};
use image::{ColorType, DynamicImage, ImageDecoder, ImageFormat, ImageReader, Limits};
use serde::Serialize;
use serde_json::Value;
use sha1::{Digest, Sha1};

use crate::contract::{default_profile, fit_inside, OutFormat, RenditionSpec};
use crate::metadata;
use crate::DamError;

/// A decoded source image plus what we learned while decoding it.
pub struct Decoded {
    /// Pixels with EXIF orientation applied, normalised to 8-bit RGB or RGBA.
    pub image: DynamicImage,
    pub format: ImageFormat,
    /// Stored dimensions (before orientation), as reported in `tiff:*` metadata.
    pub stored_width: u32,
    pub stored_height: u32,
    pub color: ColorType,
    pub exif: Option<Vec<u8>>,
    pub xmp: Option<Vec<u8>>,
}

impl Decoded {
    pub fn has_alpha(&self) -> bool {
        self.color.has_alpha()
    }
}

/// One produced rendition.
#[derive(Clone, Debug)]
pub struct RenditionOut {
    pub name: String,
    pub mime: &'static str,
    pub width: u32,
    pub height: u32,
    pub bytes: Vec<u8>,
    pub sha1: String,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct FailedRendition {
    pub name: String,
    pub reason: &'static str,
    pub message: String,
}

pub struct ProcessOutput {
    pub mime: String,
    pub metadata: BTreeMap<String, Value>,
    pub xmp_packet: Option<String>,
    pub renditions: Vec<RenditionOut>,
    pub failed: Vec<FailedRendition>,
}

/// Larger XMP packets are dropped (real ones are a few KB; 1 MB leaves room for previews).
pub const MAX_XMP_BYTES: usize = 1 << 20;

/// Formats we decode. Everything else gets metadata only and failed renditions.
fn supported(format: ImageFormat) -> bool {
    matches!(format, ImageFormat::Jpeg | ImageFormat::Png | ImageFormat::WebP)
}

pub fn decode(bytes: &[u8], max_pixels: u64) -> Result<Decoded, DamError> {
    let probe = ImageReader::new(Cursor::new(bytes)).with_guessed_format()?;
    let format = probe
        .format()
        .filter(|f| supported(*f))
        .ok_or_else(|| DamError::Unsupported("not a JPEG, PNG or WebP image".into()))?;
    // Read the header first so a tiny file declaring a huge canvas is refused
    // before anything is allocated.
    let (w, h) = probe.into_dimensions()?;
    if w as u64 * h as u64 > max_pixels {
        return Err(DamError::TooLarge);
    }

    let mut reader = ImageReader::with_format(Cursor::new(bytes), format);
    let mut limits = Limits::default();
    limits.max_image_width = Some(w);
    limits.max_image_height = Some(h);
    // RGBA16 is the widest layout we accept, plus decoder working memory.
    limits.max_alloc = Some(max_pixels.saturating_mul(8).saturating_add(64 << 20));
    reader.limits(limits);

    let mut decoder = reader.into_decoder()?;
    let color = decoder.color_type();
    let exif = decoder.exif_metadata().ok().flatten();
    let xmp = decoder.xmp_metadata().ok().flatten();
    let orientation = decoder.orientation().unwrap_or(image::metadata::Orientation::NoTransforms);
    let mut image = DynamicImage::from_decoder(decoder)?;
    image.apply_orientation(orientation);

    // Work in 8-bit RGB/RGBA: it is what every output format stores.
    let image = if color.has_alpha() {
        match image {
            DynamicImage::ImageRgba8(_) => image,
            other => DynamicImage::ImageRgba8(other.to_rgba8()),
        }
    } else {
        match image {
            DynamicImage::ImageRgb8(_) => image,
            other => DynamicImage::ImageRgb8(other.to_rgb8()),
        }
    };

    Ok(Decoded { image, format, stored_width: w, stored_height: h, color, exif, xmp })
}

/// Lanczos3 resize into a new image of the same pixel layout.
pub fn resize(src: &DynamicImage, width: u32, height: u32) -> Result<DynamicImage, DamError> {
    if src.width() == width && src.height() == height {
        return Ok(src.clone());
    }
    let mut dst = DynamicImage::new(width, height, src.color());
    Resizer::new()
        .resize(src, &mut dst, &ResizeOptions::new())
        .map_err(|e| DamError::Internal(format!("resize: {e}")))?;
    Ok(dst)
}

pub fn encode(img: &DynamicImage, format: OutFormat) -> Result<Vec<u8>, DamError> {
    let mut out = Vec::new();
    match format {
        OutFormat::Jpeg { quality } => {
            let enc = JpegEncoder::new_with_quality(&mut out, quality);
            match img {
                DynamicImage::ImageRgb8(_) => img.write_with_encoder(enc)?,
                // JPEG has no alpha: flatten onto white rather than black.
                other if other.color().has_alpha() => {
                    DynamicImage::ImageRgb8(flatten_on_white(other)).write_with_encoder(enc)?
                }
                other => DynamicImage::ImageRgb8(other.to_rgb8()).write_with_encoder(enc)?,
            }
        }
        OutFormat::Png => {
            let enc = PngEncoder::new_with_quality(&mut out, CompressionType::Default, FilterType::Adaptive);
            img.write_with_encoder(enc)?;
        }
        OutFormat::WebP { quality } => {
            let enc = webp::Encoder::from_image(img)
                .map_err(|e| DamError::Internal(format!("webp: {e}")))?;
            out.extend_from_slice(&enc.encode(quality as f32));
        }
    }
    Ok(out)
}

fn flatten_on_white(img: &DynamicImage) -> image::RgbImage {
    let rgba = img.to_rgba8();
    image::RgbImage::from_fn(rgba.width(), rgba.height(), |x, y| {
        let p = rgba.get_pixel(x, y).0;
        let a = p[3] as u32;
        let mix = |c: u8| ((c as u32 * a + 255 * (255 - a) + 127) / 255) as u8;
        image::Rgb([mix(p[0]), mix(p[1]), mix(p[2])])
    })
}

/// Produce the renditions of `specs` from one decoded image.
///
/// Renditions are generated from the largest to the smallest; each one is
/// resized from the previous output when that is at least as large, which is
/// much cheaper than going back to the original every time.
pub fn render(decoded: &Decoded, specs: &[RenditionSpec]) -> Result<Vec<RenditionOut>, DamError> {
    render_marked(decoded, specs, None)
}

/// [`render`], drawing `mark` on the renditions that ask for it. A smaller rendition is still
/// resized from the larger one without the mark.
pub fn render_marked(
    decoded: &Decoded,
    specs: &[RenditionSpec],
    mark: Option<&crate::watermark::Watermark>,
) -> Result<Vec<RenditionOut>, DamError> {
    let (ow, oh) = (decoded.image.width(), decoded.image.height());
    let mut order: Vec<usize> = (0..specs.len()).collect();
    order.sort_by_key(|&i| std::cmp::Reverse(specs[i].box_w as u64 * specs[i].box_h as u64));

    let mut results: Vec<Option<RenditionOut>> = vec![None; specs.len()];
    let mut previous: Option<DynamicImage> = None;
    for i in order {
        let spec = &specs[i];
        let (tw, th) = fit_inside(ow, oh, spec.box_w, spec.box_h);
        let source = match &previous {
            Some(p) if p.width() >= tw && p.height() >= th => p,
            _ => &decoded.image,
        };
        let pixels = resize(source, tw, th)?;
        let bytes = match mark {
            Some(m) if spec.watermark => encode(&m.apply(&pixels), spec.format)?,
            _ => encode(&pixels, spec.format)?,
        };
        results[i] = Some(RenditionOut {
            name: spec.name(),
            mime: spec.format.mime(),
            width: tw,
            height: th,
            sha1: hex::encode(Sha1::digest(&bytes)),
            bytes,
        });
        previous = Some(pixels);
    }
    Ok(results.into_iter().map(|r| r.expect("every spec rendered")).collect())
}

/// Full processing of an original with the default profile.
pub fn process(bytes: &[u8], mime_hint: Option<&str>, max_pixels: u64) -> Result<ProcessOutput, DamError> {
    process_with(bytes, mime_hint, max_pixels, &[])
}

/// [`process`] plus the renditions of a processing profile (`extra`). A JPEG extra of a source
/// with transparency is still made (flattened on white): the profile asked for that format.
pub fn process_with(
    bytes: &[u8],
    mime_hint: Option<&str>,
    max_pixels: u64,
    extra: &[RenditionSpec],
) -> Result<ProcessOutput, DamError> {
    process_marked(bytes, mime_hint, max_pixels, extra, None)
}

/// [`process_with`], drawing `mark` on the profile's renditions that ask for it.
pub fn process_marked(
    bytes: &[u8],
    mime_hint: Option<&str>,
    max_pixels: u64,
    extra: &[RenditionSpec],
    mark: Option<&crate::watermark::Watermark>,
) -> Result<ProcessOutput, DamError> {
    // PDF and SVG: render a preview image first, then the same renditions as for a photo.
    match crate::document::preview(bytes, mime_hint) {
        Some(Ok(p)) => return from_preview(bytes, p, extra, mark),
        Some(Err(DamError::Unsupported(_))) => return Ok(unsupported(bytes, mime_hint, extra)),
        Some(Err(e)) => return Err(e),
        None => {}
    }
    match decode(bytes, max_pixels) {
        Ok(decoded) => {
            let mut specs = default_profile(decoded.has_alpha());
            specs.extend(extra.iter().cloned());
            let renditions = render_marked(&decoded, &specs, mark)?;
            let metadata = metadata::extract(bytes, Some(&decoded));
            let mime = metadata::mime_of(decoded.format).to_string();
            // Bound what an uploaded file can push into the repository through XMP.
            let xmp_packet = decoded
                .xmp
                .as_deref()
                .filter(|x| x.len() <= MAX_XMP_BYTES)
                .map(|x| String::from_utf8_lossy(x).into_owned());
            Ok(ProcessOutput { mime, metadata, xmp_packet, renditions, failed: vec![] })
        }
        Err(DamError::Unsupported(_)) => Ok(unsupported(bytes, mime_hint, extra)),
        Err(e) => Err(e),
    }
}

/// Mirror AEM: the asset exists, metadata is basic, renditions are recorded as failed.
fn unsupported(bytes: &[u8], mime_hint: Option<&str>, extra: &[RenditionSpec]) -> ProcessOutput {
    // Mirror AEM: the asset exists, metadata is basic, renditions are recorded as failed.
    let mime = metadata::sniff_mime(bytes, mime_hint);
    let mut metadata = metadata::extract(bytes, None);
    metadata.insert("dam:MIMEtype".into(), Value::from(mime.clone()));
    metadata.insert("dc:format".into(), Value::from(mime.clone()));
    // Videos and audio: duration, size, codecs from the container (no frames are decoded).
    metadata.extend(crate::video::metadata(bytes));
    let failed = default_profile(false)
        .iter()
        .chain(extra.iter())
        .map(|s| FailedRendition {
            name: s.name(),
            reason: "RenditionFormatUnsupported",
            message: format!("Rendition generation is not supported for {mime}"),
        })
        .collect();
    ProcessOutput { mime, metadata, xmp_packet: None, renditions: vec![], failed }
}

/// Renditions of a PDF page or SVG drawing: the standard thumbnails and web rendition (no zoom:
/// AEM makes none for documents either), plus the profile's.
fn from_preview(
    bytes: &[u8],
    p: crate::document::Preview,
    extra: &[RenditionSpec],
    mark: Option<&crate::watermark::Watermark>,
) -> Result<ProcessOutput, DamError> {
    let alpha = p.image.color().has_alpha();
    let decoded = Decoded {
        stored_width: p.image.width(),
        stored_height: p.image.height(),
        color: p.image.color(),
        image: p.image,
        format: ImageFormat::Png,
        exif: None,
        xmp: None,
    };
    let mut specs: Vec<RenditionSpec> = default_profile(alpha).into_iter().filter(|s| s.kind != "zoom").collect();
    specs.extend(extra.iter().cloned());
    let renditions = render_marked(&decoded, &specs, mark)?;
    let mut metadata = metadata::extract(bytes, None);
    metadata.extend(p.metadata);
    Ok(ProcessOutput { mime: p.mime.to_string(), metadata, xmp_packet: None, renditions, failed: vec![] })
}

/// Resize an original for on-the-fly delivery, after its transform (crop, turns, mirrors, fit
/// mode, sharpening, ...).
pub fn derive_transformed(bytes: &[u8], req: &crate::delivery::DeliveryRequest, max_pixels: u64) -> Result<Vec<u8>, DamError> {
    let decoded = decode(bytes, max_pixels)?;
    let image = if req.steps.is_empty() { decoded.image } else { crate::delivery::apply(decoded.image, &req.steps)? };
    let pixels = crate::delivery::finish(image, req)?;
    encode(&pixels, req.format)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png_bytes(img: DynamicImage) -> Vec<u8> {
        let mut out = Vec::new();
        img.write_to(&mut Cursor::new(&mut out), ImageFormat::Png).unwrap();
        out
    }

    #[test]
    fn refuses_huge_canvas_before_decoding() {
        let big = png_bytes(DynamicImage::new_rgb8(2000, 2000));
        assert!(matches!(decode(&big, 1_000_000), Err(DamError::TooLarge)));
    }

    #[test]
    fn unsupported_formats_get_failed_renditions() {
        let out = process(b"%PDF-1.7 fake", None, 1_000_000).unwrap();
        assert_eq!(out.mime, "application/pdf");
        assert!(out.renditions.is_empty());
        assert_eq!(out.failed.len(), 5);
        assert!(out.failed.iter().all(|f| f.reason == "RenditionFormatUnsupported"));
    }

    #[test]
    fn jpeg_output_flattens_alpha_on_white() {
        let transparent = DynamicImage::ImageRgba8(image::RgbaImage::new(4, 4));
        let jpeg = encode(&transparent, OutFormat::Jpeg { quality: 90 }).unwrap();
        let back = image::load_from_memory(&jpeg).unwrap().to_rgb8();
        assert!(back.get_pixel(1, 1).0.iter().all(|&c| c > 245));
    }

    #[test]
    fn webp_round_trips() {
        let img = DynamicImage::ImageRgb8(image::RgbImage::from_pixel(64, 32, image::Rgb([10, 200, 30])));
        let bytes = encode(&img, OutFormat::WebP { quality: 75 }).unwrap();
        let back = image::load_from_memory_with_format(&bytes, ImageFormat::WebP).unwrap();
        assert_eq!((back.width(), back.height()), (64, 32));
    }

    #[test]
    fn videos_get_their_technical_metadata() {
        let b = crate::video::tests_support::mp4(true, b"qt  ");
        let out = process(&b, Some("application/octet-stream"), 10_000_000).unwrap();
        assert_eq!(out.mime, "video/quicktime");
        assert!(out.renditions.is_empty());
        assert!(!out.failed.is_empty());
        assert_eq!(out.metadata["dam:duration"], serde_json::json!(10.24));
        assert_eq!(out.metadata["tiff:ImageWidth"], serde_json::json!(1080));
        assert_eq!(out.metadata["xmpDM:videoCompressor"], serde_json::json!("H.264 (avc1)"));
    }

    #[test]
    fn pdfs_get_thumbnails_and_a_web_rendition() {
        let out = process(&crate::document::tests_support::tiny_pdf(), None, 10_000_000).unwrap();
        assert_eq!(out.mime, "application/pdf");
        let names: Vec<_> = out.renditions.iter().map(|r| r.name.as_str()).collect();
        assert_eq!(names, ["cq5dam.thumbnail.48.48.png", "cq5dam.thumbnail.140.100.png", "cq5dam.thumbnail.319.319.png", "cq5dam.web.1280.1280.jpeg"]);
        assert!(out.failed.is_empty());
        assert_eq!(out.metadata["dam:size"], serde_json::json!(crate::document::tests_support::tiny_pdf().len()));
        assert_eq!(out.metadata["dc:title"], serde_json::json!("Spring Brochure"));
    }

    #[test]
    fn profile_renditions_come_after_the_standard_ones() {
        let png = png_bytes(DynamicImage::ImageRgb8(image::RgbImage::from_pixel(1000, 500, image::Rgb([1, 2, 3]))));
        let extra = [
            RenditionSpec::custom("banner", 600, 600, OutFormat::WebP { quality: 70 }).unwrap(),
            RenditionSpec::custom("square-small", 100, 100, OutFormat::Png).unwrap(),
        ];
        let out = process_with(&png, None, 10_000_000, &extra).unwrap();
        let named: Vec<_> = out.renditions.iter().map(|r| (r.name.as_str(), r.width, r.height)).collect();
        assert!(named.contains(&("banner.webp", 600, 300)), "{named:?}");
        assert!(named.contains(&("square-small.png", 100, 50)), "{named:?}");
        assert_eq!(out.renditions.len(), 7);
    }

    #[test]
    fn only_the_renditions_that_ask_get_the_watermark() {
        let png = png_bytes(DynamicImage::ImageRgb8(image::RgbImage::from_pixel(400, 200, image::Rgb([255, 255, 255]))));
        let mut marked = RenditionSpec::custom("marked", 200, 200, OutFormat::Png).unwrap();
        marked.watermark = true;
        let plain = RenditionSpec::custom("plain", 100, 100, OutFormat::Png).unwrap();
        let red = DynamicImage::ImageRgba8(image::RgbaImage::from_pixel(10, 10, image::Rgba([255, 0, 0, 255])));
        let mark = crate::watermark::Watermark::new(red, crate::watermark::Position::Center, 100, 50);
        let out = process_marked(&png, None, 10_000_000, &[marked, plain], Some(&mark)).unwrap();
        let centre = |name: &str| {
            let r = out.renditions.iter().find(|r| r.name == name).unwrap();
            let img = image::load_from_memory(&r.bytes).unwrap().to_rgb8();
            img.get_pixel(img.width() / 2, img.height() / 2).0
        };
        assert_eq!(centre("marked.png"), [255, 0, 0]);
        // resized from the larger rendition, but without its mark
        assert_eq!(centre("plain.png"), [255, 255, 255]);
        assert_eq!(centre("cq5dam.web.1280.1280.jpeg")[1] > 200, true);
    }

    #[test]
    fn profile_rendition_names_are_checked() {
        for bad in ["", "original", "cq5dam.web.1.1", "../x", "A", ".x", "x."] {
            assert!(RenditionSpec::custom(bad, 10, 10, OutFormat::Png).is_err(), "{bad}");
        }
        assert!(RenditionSpec::custom("ok", 0, 10, OutFormat::Png).is_err());
        assert!(RenditionSpec::custom("ok", 10, 9000, OutFormat::Png).is_err());
        assert_eq!(RenditionSpec::custom("hero.2x", 10, 10, OutFormat::Png).unwrap().name(), "hero.2x.png");
    }
}
