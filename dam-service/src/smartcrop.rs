//! Smart Crop: for each aspect ratio asked for, the window of that ratio that keeps the most of
//! what matters in the picture.
//!
//! "What matters" is an attention map. When the Smart Tags model is installed, it is mostly
//! where that model looks: the attention of its pooling head over the image patches, which
//! settles on people, faces and products. Mixed in (and used alone without the model) is a
//! cheap map computed on a small copy: local contrast (how much a pixel differs from its
//! blurred surroundings), colour distinctiveness (how far a pixel's colour is from the
//! picture's mean colour) and a mild preference for the middle. The window is the largest one of the
//! ratio that fits the image; it slides over every position and the one with the most
//! attention under it wins. Crops are returned in pixels of the image as displayed (EXIF
//! orientation applied) and as fractions of it.

use image::{DynamicImage, GenericImageView};
use serde::{Deserialize, Serialize};

use crate::DamError;

/// The attention map is computed at this longest side.
const MAP_SIDE: u32 = 160;

#[derive(Debug, Clone, Deserialize)]
pub struct Ratio {
    pub name: String,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct Crop {
    pub name: String,
    /// pixels of the displayed image
    pub x: u32,
    pub y: u32,
    pub width: u32,
    pub height: u32,
    /// the same as fractions of the image: left, top, width, height
    pub normalized: [f64; 4],
}

/// Attention per pixel of a `w`×`h` map (row-major), values 0..=1.
pub struct Attention {
    pub w: u32,
    pub h: u32,
    pub values: Vec<f32>,
}

/// A square map from a vision model (side × side, row-major, 0..=1), e.g. `SmartTagger::attention_map`.
pub type ModelMap = (usize, Vec<f32>);

/// The attention map of an image: the cheap one, blended with the model's when there is one.
pub fn attention(img: &DynamicImage, model: Option<&ModelMap>) -> Attention {
    let mut att = cheap_attention(img);
    if let Some((side, map)) = model {
        if *side > 0 && map.len() == side * side {
            // bilinear lookup of the model map at each cell of ours
            let (w, h) = (att.w as usize, att.h as usize);
            for y in 0..h {
                for x in 0..w {
                    let fx = ((x as f32 + 0.5) / w as f32 * *side as f32 - 0.5).clamp(0.0, (*side - 1) as f32);
                    let fy = ((y as f32 + 0.5) / h as f32 * *side as f32 - 0.5).clamp(0.0, (*side - 1) as f32);
                    let (x0, y0) = (fx.floor() as usize, fy.floor() as usize);
                    let (x1, y1) = ((x0 + 1).min(side - 1), (y0 + 1).min(side - 1));
                    let (tx, ty) = (fx - x0 as f32, fy - y0 as f32);
                    let m = map[y0 * side + x0] * (1.0 - tx) * (1.0 - ty)
                        + map[y0 * side + x1] * tx * (1.0 - ty)
                        + map[y1 * side + x0] * (1.0 - tx) * ty
                        + map[y1 * side + x1] * tx * ty;
                    let i = y * w + x;
                    att.values[i] = 0.3 * att.values[i] + 0.7 * m;
                }
            }
        }
    }
    att
}

/// The cheap attention map of an image.
pub fn cheap_attention(img: &DynamicImage) -> Attention {
    let (iw, ih) = img.dimensions();
    let scale = MAP_SIDE as f32 / iw.max(ih).max(1) as f32;
    let (w, h) = if scale < 1.0 {
        (((iw as f32 * scale).round() as u32).max(1), ((ih as f32 * scale).round() as u32).max(1))
    } else {
        (iw, ih)
    };
    let small = img.resize_exact(w, h, image::imageops::FilterType::Triangle).to_rgb8();
    let blurred = image::imageops::blur(&small, (w.max(h) as f32 / 12.0).max(1.0));
    let n = (w * h) as f32;
    let mut mean = [0f32; 3];
    for p in small.pixels() {
        for c in 0..3 {
            mean[c] += p.0[c] as f32;
        }
    }
    for m in &mut mean {
        *m /= n;
    }
    let (cx, cy) = ((w as f32 - 1.0) / 2.0, (h as f32 - 1.0) / 2.0);
    let radius = (cx * cx + cy * cy).sqrt().max(1.0);
    let mut values = Vec::with_capacity((w * h) as usize);
    let (mut max_contrast, mut max_color) = (1e-3f32, 1e-3f32);
    let mut raw = Vec::with_capacity((w * h) as usize);
    for y in 0..h {
        for x in 0..w {
            let p = small.get_pixel(x, y).0;
            let b = blurred.get_pixel(x, y).0;
            let contrast = (0..3).map(|c| (p[c] as f32 - b[c] as f32).abs()).sum::<f32>() / 3.0;
            let color = (0..3).map(|c| (p[c] as f32 - mean[c]).powi(2)).sum::<f32>().sqrt();
            max_contrast = max_contrast.max(contrast);
            max_color = max_color.max(color);
            raw.push((contrast, color));
        }
    }
    for (i, (contrast, color)) in raw.iter().enumerate() {
        let (x, y) = ((i as u32 % w) as f32, (i as u32 / w) as f32);
        let d = ((x - cx).powi(2) + (y - cy).powi(2)).sqrt() / radius;
        let center = 1.0 - 0.5 * d; // 1 in the middle, 0.5 in the corners
        // the small constant breaks ties towards the middle (a uniform picture crops its centre)
        values.push((0.6 * contrast / max_contrast + 0.4 * color / max_color + 0.02) * center);
    }
    Attention { w, h, values }
}

/// The crops of `img` for `ratios`, guided by a model's map when there is one.
pub fn crops(img: &DynamicImage, ratios: &[Ratio], model: Option<&ModelMap>) -> Result<Vec<Crop>, DamError> {
    let (iw, ih) = img.dimensions();
    if iw == 0 || ih == 0 {
        return Err(DamError::BadRequest("empty image".into()));
    }
    for r in ratios {
        if r.width == 0 || r.height == 0 || r.name.is_empty() || r.name.len() > 64 {
            return Err(DamError::BadRequest(format!("bad ratio {}: {}x{}", r.name, r.width, r.height)));
        }
    }
    let att = attention(img, model);
    // summed-area table, so any window's attention is four lookups
    let (aw, ah) = (att.w as usize, att.h as usize);
    let mut sat = vec![0f64; (aw + 1) * (ah + 1)];
    for y in 0..ah {
        let mut row = 0f64;
        for x in 0..aw {
            row += att.values[y * aw + x] as f64;
            sat[(y + 1) * (aw + 1) + x + 1] = sat[y * (aw + 1) + x + 1] + row;
        }
    }
    let sum = |x0: usize, y0: usize, x1: usize, y1: usize| -> f64 {
        sat[y1 * (aw + 1) + x1] - sat[y0 * (aw + 1) + x1] - sat[y1 * (aw + 1) + x0] + sat[y0 * (aw + 1) + x0]
    };
    let mut out = Vec::with_capacity(ratios.len());
    for r in ratios {
        // the largest window of this ratio inside the image
        let (cw, ch) = if (iw as u64) * (r.height as u64) <= (ih as u64) * (r.width as u64) {
            (iw, ((iw as u64 * r.height as u64 + r.width as u64 / 2) / r.width as u64).max(1) as u32)
        } else {
            (((ih as u64 * r.width as u64 + r.height as u64 / 2) / r.height as u64).max(1) as u32, ih)
        };
        let (cw, ch) = (cw.min(iw), ch.min(ih));
        // slide it over the attention map (in map pixels)
        let sx = att.w as f64 / iw as f64;
        let sy = att.h as f64 / ih as f64;
        let mw = ((cw as f64 * sx).round() as usize).clamp(1, aw);
        let mh = ((ch as f64 * sy).round() as usize).clamp(1, ah);
        let (mut best, mut bx, mut by) = (-1f64, 0usize, 0usize);
        for y in 0..=(ah - mh) {
            for x in 0..=(aw - mw) {
                let s = sum(x, y, x + mw, y + mh);
                if s > best {
                    best = s;
                    bx = x;
                    by = y;
                }
            }
        }
        // back to image pixels; a tie (uniform picture) lands in the middle
        let x = if aw > mw { ((bx as f64 / sx).round() as u32).min(iw - cw) } else { (iw - cw) / 2 };
        let y = if ah > mh { ((by as f64 / sy).round() as u32).min(ih - ch) } else { (ih - ch) / 2 };
        out.push(Crop {
            name: r.name.clone(),
            x,
            y,
            width: cw,
            height: ch,
            normalized: [x as f64 / iw as f64, y as f64 / ih as f64, cw as f64 / iw as f64, ch as f64 / ih as f64],
        });
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{Rgb, RgbImage};

    fn ratio(name: &str, w: u32, h: u32) -> Ratio {
        Ratio { name: name.into(), width: w, height: h }
    }

    #[test]
    fn finds_the_subject_off_centre() {
        // grey picture with a bright red square near the right edge: the square is the subject
        let img = DynamicImage::ImageRgb8(RgbImage::from_fn(800, 400, |x, y| {
            if (600..700).contains(&x) && (150..250).contains(&y) {
                Rgb([255, 0, 0])
            } else {
                Rgb([120, 120, 120])
            }
        }));
        let c = crops(&img, &[ratio("square", 1, 1), ratio("wide", 16, 9)], None).unwrap();
        assert_eq!((c[0].width, c[0].height), (400, 400), "the largest square");
        assert!(c[0].x >= 300 && c[0].x <= 400, "contains the subject: x={}", c[0].x);
        assert!((c[0].normalized[2] - 0.5).abs() < 1e-9);
        assert_eq!((c[1].width, c[1].height), (711, 400));
        assert!(c[1].x + c[1].width >= 700, "wide crop keeps the subject too");
    }

    #[test]
    fn uniform_pictures_crop_the_middle() {
        let img = DynamicImage::ImageRgb8(RgbImage::from_pixel(300, 600, Rgb([50, 50, 50])));
        let c = crops(&img, &[ratio("square", 1, 1)], None).unwrap();
        assert_eq!((c[0].x, c[0].y, c[0].width, c[0].height), (0, 150, 300, 300));
        assert!(crops(&img, &[ratio("", 1, 1)], None).is_err());
        assert!(crops(&img, &[ratio("x", 0, 1)], None).is_err());
        // a model map that points at the bottom pulls the crop down
        let mut map = vec![0f32; 4 * 4];
        map[3 * 4 + 1] = 1.0;
        map[3 * 4 + 2] = 1.0;
        let c = crops(&img, &[ratio("square", 1, 1)], Some(&(4, map))).unwrap();
        assert_eq!((c[0].x, c[0].y), (0, 300));
    }

    #[test]
    fn tiny_images_work() {
        let img = DynamicImage::ImageRgb8(RgbImage::from_pixel(3, 2, Rgb([50, 50, 50])));
        let c = crops(&img, &[ratio("square", 1, 1), ratio("tall", 1, 4)], None).unwrap();
        assert_eq!((c[0].width, c[0].height), (2, 2));
        assert_eq!((c[1].width, c[1].height), (1, 2));
    }
}
