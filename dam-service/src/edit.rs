//! Destructive image edits for the Assets image editor: crop, quarter-turn rotations and flips,
//! applied in order to the original. The result is a new original in the same format; Sensible Studio then
//! regenerates the renditions from it.

use serde::Deserialize;

use crate::contract::OutFormat;
use crate::process::{self, decode};
use crate::DamError;

/// More than this many steps is not something an editor session produces.
pub const MAX_OPERATIONS: usize = 64;

/// Quality for re-encoded JPEG originals: high, because the original is the source of every
/// rendition and may be edited again.
pub const JPEG_QUALITY: u8 = 92;

#[derive(Debug, Clone, Deserialize, PartialEq)]
#[serde(tag = "op", rename_all = "lowercase")]
pub enum Operation {
    /// Clockwise quarter turns: 90, 180, 270 (or -90 for one turn to the left).
    Rotate { degrees: i32 },
    /// `horizontal` mirrors left and right, `vertical` top and bottom.
    Flip { axis: Axis },
    /// A rectangle in fractions (0..1) of the image as it is at this step.
    Crop { x: f64, y: f64, width: f64, height: f64 },
}

#[derive(Debug, Clone, Copy, Deserialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Axis {
    Horizontal,
    Vertical,
}

pub struct Edited {
    pub bytes: Vec<u8>,
    pub mime: &'static str,
    pub width: u32,
    pub height: u32,
}

/// Pixel rectangle `(x, y, w, h)` for a fractional crop of a `w`×`h` image; at least 1×1.
pub fn crop_rect(img_w: u32, img_h: u32, x: f64, y: f64, w: f64, h: f64) -> Result<(u32, u32, u32, u32), DamError> {
    let finite = [x, y, w, h].iter().all(|v| v.is_finite());
    if !finite || x < 0.0 || y < 0.0 || w <= 0.0 || h <= 0.0 || x + w > 1.000_001 || y + h > 1.000_001 {
        return Err(DamError::BadRequest("crop must lie inside the image (fractions 0..1)".into()));
    }
    let px = |f: f64, size: u32| ((f * size as f64).round() as i64).clamp(0, size as i64) as u32;
    let (x0, y0) = (px(x, img_w).min(img_w - 1), px(y, img_h).min(img_h - 1));
    let (x1, y1) = (px(x + w, img_w).max(x0 + 1), px(y + h, img_h).max(y0 + 1));
    Ok((x0, y0, x1 - x0, y1 - y0))
}

/// Applies `ops` to an original and re-encodes it in its own format.
pub fn edit(bytes: &[u8], ops: &[Operation], max_pixels: u64) -> Result<Edited, DamError> {
    if ops.is_empty() {
        return Err(DamError::BadRequest("no operations".into()));
    }
    if ops.len() > MAX_OPERATIONS {
        return Err(DamError::BadRequest(format!("at most {MAX_OPERATIONS} operations")));
    }
    let decoded = decode(bytes, max_pixels)?;
    let (format, mime) = match decoded.format {
        image::ImageFormat::Jpeg => (OutFormat::Jpeg { quality: JPEG_QUALITY }, "image/jpeg"),
        image::ImageFormat::Png => (OutFormat::Png, "image/png"),
        image::ImageFormat::WebP => (OutFormat::WebP { quality: 100 }, "image/webp"),
        _ => return Err(DamError::Unsupported("only JPEG, PNG and WebP can be edited".into())),
    };
    let mut img = decoded.image;
    for op in ops {
        img = match *op {
            Operation::Rotate { degrees } => match degrees.rem_euclid(360) {
                0 => img,
                90 => img.rotate90(),
                180 => img.rotate180(),
                270 => img.rotate270(),
                _ => return Err(DamError::BadRequest("rotation must be a multiple of 90 degrees".into())),
            },
            Operation::Flip { axis: Axis::Horizontal } => img.fliph(),
            Operation::Flip { axis: Axis::Vertical } => img.flipv(),
            Operation::Crop { x, y, width, height } => {
                let (cx, cy, cw, ch) = crop_rect(img.width(), img.height(), x, y, width, height)?;
                img.crop_imm(cx, cy, cw, ch)
            }
        };
    }
    let bytes = match format {
        // Lossless, so repeated edits do not degrade the original.
        OutFormat::WebP { .. } => webp::Encoder::from_image(&img)
            .map_err(|e| DamError::Internal(format!("webp: {e}")))?
            .encode_lossless()
            .to_vec(),
        other => process::encode(&img, other)?,
    };
    Ok(Edited { bytes, mime, width: img.width(), height: img.height() })
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{DynamicImage, ImageFormat, Rgb, RgbImage, Rgba, RgbaImage};
    use std::io::Cursor;

    /// 4×2 image: left half red, right half blue.
    fn sample_png() -> Vec<u8> {
        let img = RgbImage::from_fn(4, 2, |x, _| if x < 2 { Rgb([255, 0, 0]) } else { Rgb([0, 0, 255]) });
        let mut out = Vec::new();
        DynamicImage::ImageRgb8(img).write_to(&mut Cursor::new(&mut out), ImageFormat::Png).unwrap();
        out
    }

    fn load(bytes: &[u8]) -> RgbImage {
        image::load_from_memory(bytes).unwrap().to_rgb8()
    }

    #[test]
    fn rotates_clockwise() {
        let out = edit(&sample_png(), &[Operation::Rotate { degrees: 90 }], 1 << 20).unwrap();
        assert_eq!((out.width, out.height, out.mime), (2, 4, "image/png"));
        // Clockwise: the left (red) half ends up on top.
        let img = load(&out.bytes);
        assert_eq!(img.get_pixel(0, 0).0, [255, 0, 0]);
        assert_eq!(img.get_pixel(0, 3).0, [0, 0, 255]);
    }

    #[test]
    fn left_turn_is_minus_ninety() {
        let a = edit(&sample_png(), &[Operation::Rotate { degrees: -90 }], 1 << 20).unwrap();
        let b = edit(&sample_png(), &[Operation::Rotate { degrees: 270 }], 1 << 20).unwrap();
        assert_eq!(load(&a.bytes), load(&b.bytes));
    }

    #[test]
    fn flips_and_crops_in_order() {
        let ops = [
            Operation::Flip { axis: Axis::Horizontal },
            Operation::Crop { x: 0.0, y: 0.0, width: 0.5, height: 1.0 },
        ];
        let out = edit(&sample_png(), &ops, 1 << 20).unwrap();
        assert_eq!((out.width, out.height), (2, 2));
        // After the mirror the blue half is on the left, and that is what the crop keeps.
        assert!(load(&out.bytes).pixels().all(|p| p.0 == [0, 0, 255]));
    }

    #[test]
    fn keeps_alpha_in_png() {
        let img = RgbaImage::from_pixel(3, 3, Rgba([0, 0, 0, 0]));
        let mut png = Vec::new();
        DynamicImage::ImageRgba8(img).write_to(&mut Cursor::new(&mut png), ImageFormat::Png).unwrap();
        let out = edit(&png, &[Operation::Flip { axis: Axis::Vertical }], 1 << 20).unwrap();
        let back = image::load_from_memory(&out.bytes).unwrap();
        assert!(back.color().has_alpha());
    }

    #[test]
    fn jpeg_stays_jpeg() {
        let img = RgbImage::from_pixel(16, 8, Rgb([120, 30, 200]));
        let mut jpg = Vec::new();
        DynamicImage::ImageRgb8(img).write_to(&mut Cursor::new(&mut jpg), ImageFormat::Jpeg).unwrap();
        let out = edit(&jpg, &[Operation::Rotate { degrees: 180 }], 1 << 20).unwrap();
        assert_eq!(out.mime, "image/jpeg");
        assert_eq!((out.width, out.height), (16, 8));
    }

    #[test]
    fn rejects_bad_requests() {
        let png = sample_png();
        assert!(matches!(edit(&png, &[], 1 << 20), Err(DamError::BadRequest(_))));
        assert!(matches!(edit(&png, &[Operation::Rotate { degrees: 45 }], 1 << 20), Err(DamError::BadRequest(_))));
        let outside = Operation::Crop { x: 0.6, y: 0.0, width: 0.6, height: 1.0 };
        assert!(matches!(edit(&png, &[outside], 1 << 20), Err(DamError::BadRequest(_))));
        let nan = Operation::Crop { x: f64::NAN, y: 0.0, width: 0.5, height: 0.5 };
        assert!(matches!(edit(&png, &[nan], 1 << 20), Err(DamError::BadRequest(_))));
        let many = vec![Operation::Rotate { degrees: 90 }; MAX_OPERATIONS + 1];
        assert!(matches!(edit(&png, &many, 1 << 20), Err(DamError::BadRequest(_))));
        assert!(matches!(edit(b"not an image", &[Operation::Rotate { degrees: 90 }], 1 << 20), Err(DamError::Unsupported(_))));
    }

    #[test]
    fn tiny_crops_keep_one_pixel() {
        assert_eq!(crop_rect(100, 100, 0.999, 0.999, 0.001, 0.001).unwrap(), (99, 99, 1, 1));
        assert_eq!(crop_rect(4, 2, 0.25, 0.0, 0.5, 1.0).unwrap(), (1, 0, 2, 2));
    }

    #[test]
    fn parses_the_wire_format() {
        let ops: Vec<Operation> = serde_json::from_str(
            r#"[{"op":"rotate","degrees":-90},{"op":"flip","axis":"vertical"},{"op":"crop","x":0.1,"y":0.2,"width":0.5,"height":0.5}]"#,
        )
        .unwrap();
        assert_eq!(ops[0], Operation::Rotate { degrees: -90 });
        assert_eq!(ops[1], Operation::Flip { axis: Axis::Vertical });
        assert!(matches!(ops[2], Operation::Crop { .. }));
    }
}
