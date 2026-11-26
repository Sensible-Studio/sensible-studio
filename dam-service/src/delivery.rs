//! Signed, cached on-the-fly delivery:
//! `GET /deliver/<blobId>/<W>x<H>.<jpeg|png|webp>?q=<1-100>[&t=<transform>]&sig=<hmac>`.
//!
//! The optional transform is applied to the original before resizing, in order:
//! `c<x>,<y>,<w>,<h>` crops (pixels of the original as displayed, EXIF orientation applied),
//! `r90`/`r180`/`r270` turns clockwise, `h` and `v` mirror; steps are joined with `-`, e.g.
//! `c10,20,300,200-r90-h`. It is what Core Components' image editor stores on a component.
//!
//! Dynamic Media adds steps that act at or after the resize, whatever their position:
//! `fcrop` fills the box and crops the middle, `fstretch` ignores the aspect ratio (default:
//! fit inside the box), `u` allows upscaling; `s<amount>,<radius>,<threshold>` is an unsharp
//! mask, `b<sigma>` a blur, `l<-100..100>` brightness, `k<-100..100>` contrast (negative as `n`, e.g. `ln10`), and
//! `g<rrggbb>` the colour transparent pixels are flattened onto.
//!
//! Blob ids are content hashes, so anyone who learns one could otherwise pull
//! unpublished assets. Sensible Studio signs every URL it renders with the shared token
//! (HMAC-SHA256 over the canonical form), and we refuse unsigned requests.

use std::path::{Path, PathBuf};

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

use crate::blobstore::BlobId;
use crate::contract::OutFormat;
use crate::DamError;

type HmacSha256 = Hmac<Sha256>;

pub const DEFAULT_JPEG_QUALITY: u8 = 80;
pub const DEFAULT_WEBP_QUALITY: u8 = 75;

/// One step of a delivery transform.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    Crop { x: u32, y: u32, w: u32, h: u32 },
    /// Clockwise quarter turns: 90, 180 or 270.
    Rotate(u16),
    FlipH,
    FlipV,
    /// How the image meets the box (`fcrop`, `fstretch`); `ffit` is the default and not written.
    Fit(FitMode),
    /// The image may be made larger than the source.
    Upscale,
    /// Unsharp mask: amount in hundredths (0..=500), radius in tenths of a pixel (1..=2500),
    /// threshold in levels (0..=255).
    Sharpen { amount: u16, radius: u16, threshold: u8 },
    /// Gaussian blur, sigma in tenths of a pixel (1..=1000).
    Blur(u16),
    /// -100..=100
    Brightness(i8),
    /// -100..=100
    Contrast(i8),
    /// Background for transparency, `0xRRGGBB`.
    Background(u32),
    /// -100..=100: -100 is grey, 100 twice the colour.
    Saturation(i8),
    Grayscale,
    Sepia,
    /// A flat tint, `0xRRGGBB` at `opacity` per cent (1..=100).
    Overlay { rgb: u32, opacity: u8 },
    /// A linear fade of `rgb` from `from` per cent opacity at the start edge to `to` at the end
    /// edge; `towards` is the end edge: `b`ottom, `t`op, `l`eft or `r`ight. A dark gradient
    /// towards the bottom is the backdrop of a hero's text.
    Gradient { towards: u8, rgb: u32, from: u8, to: u8 },
    /// Darkened corners, strength 1..=100.
    Vignette(u8),
    /// Rounded corners, radius in pixels (1..=4096); transparent, or the background/white.
    Radius(u16),
    /// A border inside the image, width in pixels (1..=1000), `0xRRGGBB`.
    Border { width: u16, rgb: u32 },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FitMode {
    /// Inside the box, aspect ratio kept (the box is an upper bound).
    #[default]
    Fit,
    /// Fill the box, aspect ratio kept, the middle is kept.
    Crop,
    /// Exactly the box, aspect ratio ignored.
    Stretch,
}

/// At most this many steps (Core Components store at most four; Dynamic Media a few more).
pub const MAX_STEPS: usize = 20;

fn int<T: std::str::FromStr>(s: &str) -> Option<T> {
    // no sign, no leading zeros beyond "0": the canonical form must be unique
    if s.is_empty() || (s.len() > 1 && s.starts_with('0')) || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    s.parse().ok()
}

/// `25` or `n25` (negative; `-` separates steps).
fn signed(s: &str) -> Option<i8> {
    let (neg, digits) = match s.strip_prefix('n') {
        Some(rest) => (true, rest),
        None => (false, s),
    };
    let v: i8 = int::<u8>(digits).filter(|v| *v <= 100).map(|v| v as i8)?;
    if neg && v == 0 {
        return None;
    }
    Some(if neg { -v } else { v })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeliveryRequest {
    pub blob: BlobId,
    pub box_w: u32,
    pub box_h: u32,
    pub format: OutFormat,
    pub steps: Vec<Step>,
}

/// Parses `c10,20,300,200-r90-h-v`.
pub fn parse_transform(t: &str) -> Result<Vec<Step>, DamError> {
    let bad = || DamError::BadRequest(format!("bad transform: {t}"));
    if t.is_empty() {
        return Ok(Vec::new());
    }
    let mut steps = Vec::new();
    for part in t.split('-') {
        let step = match part {
            "h" => Step::FlipH,
            "v" => Step::FlipV,
            "r90" => Step::Rotate(90),
            "r180" => Step::Rotate(180),
            "r270" => Step::Rotate(270),
            "fcrop" => Step::Fit(FitMode::Crop),
            "fstretch" => Step::Fit(FitMode::Stretch),
            "u" => Step::Upscale,
            _ if part.starts_with('c') => {
                let n: Vec<u32> = part[1..].split(',').map(|v| v.parse::<u32>()).collect::<Result<_, _>>().map_err(|_| bad())?;
                if n.len() != 4 || n[2] == 0 || n[3] == 0 {
                    return Err(bad());
                }
                Step::Crop { x: n[0], y: n[1], w: n[2], h: n[3] }
            }
            _ if part.starts_with('s') => {
                let n: Vec<&str> = part[1..].split(',').collect();
                if n.len() != 3 {
                    return Err(bad());
                }
                let amount: u16 = int(n[0]).filter(|v| *v <= 500).ok_or_else(bad)?;
                let radius: u16 = int(n[1]).filter(|v| (1..=2500).contains(v)).ok_or_else(bad)?;
                let threshold: u8 = int(n[2]).ok_or_else(bad)?;
                Step::Sharpen { amount, radius, threshold }
            }
            _ if part.starts_with('b') => Step::Blur(int(&part[1..]).filter(|v| (1..=1000).contains(v)).ok_or_else(bad)?),
            _ if part.starts_with('l') => Step::Brightness(signed(&part[1..]).ok_or_else(bad)?),
            _ if part.starts_with('k') => Step::Contrast(signed(&part[1..]).ok_or_else(bad)?),
            "m" => Step::Grayscale,
            "p" => Step::Sepia,
            _ if part.starts_with('g') => Step::Background(rgb(&part[1..]).ok_or_else(bad)?),
            _ if part.starts_with('a') => Step::Saturation(signed(&part[1..]).ok_or_else(bad)?),
            _ if part.starts_with('o') => {
                let n: Vec<&str> = part[1..].split(',').collect();
                if n.len() != 2 {
                    return Err(bad());
                }
                Step::Overlay { rgb: rgb(n[0]).ok_or_else(bad)?, opacity: int(n[1]).filter(|v| (1..=100).contains(v)).ok_or_else(bad)? }
            }
            _ if part.starts_with('d') => {
                let n: Vec<&str> = part[1..].split(',').collect();
                if n.len() != 4 || n[0].len() != 1 || !"btlr".contains(n[0]) {
                    return Err(bad());
                }
                let pct = |v: &str| int::<u8>(v).filter(|v| *v <= 100);
                Step::Gradient {
                    towards: n[0].as_bytes()[0],
                    rgb: rgb(n[1]).ok_or_else(bad)?,
                    from: pct(n[2]).ok_or_else(bad)?,
                    to: pct(n[3]).ok_or_else(bad)?,
                }
            }
            _ if part.starts_with('w') => Step::Vignette(int(&part[1..]).filter(|v| (1..=100).contains(v)).ok_or_else(bad)?),
            _ if part.starts_with('e') => Step::Radius(int(&part[1..]).filter(|v| (1..=4096).contains(v)).ok_or_else(bad)?),
            _ if part.starts_with('x') => {
                let n: Vec<&str> = part[1..].split(',').collect();
                if n.len() != 2 {
                    return Err(bad());
                }
                Step::Border { width: int(n[0]).filter(|v| (1..=1000).contains(v)).ok_or_else(bad)?, rgb: rgb(n[1]).ok_or_else(bad)? }
            }
            _ => return Err(bad()),
        };
        steps.push(step);
    }
    if steps.len() > MAX_STEPS {
        return Err(bad());
    }
    Ok(steps)
}

/// `rrggbb`, lower-case hex.
fn rgb(hex: &str) -> Option<u32> {
    if hex.len() != 6 || !hex.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
        return None;
    }
    u32::from_str_radix(hex, 16).ok()
}

fn signed_str(v: i8) -> String {
    if v < 0 {
        format!("n{}", -(v as i16))
    } else {
        v.to_string()
    }
}

fn format_transform(steps: &[Step]) -> String {
    steps
        .iter()
        .map(|s| match *s {
            Step::Crop { x, y, w, h } => format!("c{x},{y},{w},{h}"),
            Step::Rotate(d) => format!("r{d}"),
            Step::FlipH => "h".into(),
            Step::FlipV => "v".into(),
            Step::Fit(FitMode::Crop) => "fcrop".into(),
            Step::Fit(FitMode::Stretch) => "fstretch".into(),
            Step::Fit(FitMode::Fit) => "ffit".into(),
            Step::Upscale => "u".into(),
            Step::Sharpen { amount, radius, threshold } => format!("s{amount},{radius},{threshold}"),
            Step::Blur(s) => format!("b{s}"),
            Step::Brightness(v) => format!("l{}", signed_str(v)),
            Step::Contrast(v) => format!("k{}", signed_str(v)),
            Step::Background(c) => format!("g{c:06x}"),
            Step::Saturation(v) => format!("a{}", signed_str(v)),
            Step::Grayscale => "m".into(),
            Step::Sepia => "p".into(),
            Step::Overlay { rgb, opacity } => format!("o{rgb:06x},{opacity}"),
            Step::Gradient { towards, rgb, from, to } => format!("d{},{rgb:06x},{from},{to}", towards as char),
            Step::Vignette(v) => format!("w{v}"),
            Step::Radius(r) => format!("e{r}"),
            Step::Border { width, rgb } => format!("x{width},{rgb:06x}"),
        })
        .collect::<Vec<_>>()
        .join("-")
}

/// Applies the steps to decoded pixels. A crop is clipped to the image; one left empty is an error.
pub fn apply(mut img: image::DynamicImage, steps: &[Step]) -> Result<image::DynamicImage, DamError> {
    for step in steps {
        img = match *step {
            Step::Crop { x, y, w, h } => {
                let (iw, ih) = (img.width(), img.height());
                let x1 = x.min(iw);
                let y1 = y.min(ih);
                let x2 = x.saturating_add(w).min(iw);
                let y2 = y.saturating_add(h).min(ih);
                if x2 <= x1 || y2 <= y1 {
                    return Err(DamError::BadRequest("crop lies outside the image".into()));
                }
                img.crop_imm(x1, y1, x2 - x1, y2 - y1)
            }
            Step::Rotate(90) => img.rotate90(),
            Step::Rotate(180) => img.rotate180(),
            Step::Rotate(270) => img.rotate270(),
            Step::Rotate(_) => img,
            Step::FlipH => img.fliph(),
            Step::FlipV => img.flipv(),
            // the rest act at or after the resize: see `finish`
            _ => img,
        };
    }
    Ok(img)
}

impl DeliveryRequest {
    pub fn fit_mode(&self) -> FitMode {
        self.steps.iter().rev().find_map(|s| if let Step::Fit(m) = s { Some(*m) } else { None }).unwrap_or_default()
    }

    pub fn upscale(&self) -> bool {
        self.steps.contains(&Step::Upscale)
    }

    /// The output size for a `w`×`h` image (after the geometry steps).
    pub fn output_size(&self, w: u32, h: u32) -> (u32, u32) {
        if w == 0 || h == 0 {
            return (0, 0);
        }
        let (bw, bh) = (self.box_w, self.box_h);
        let up = self.upscale();
        match self.fit_mode() {
            FitMode::Fit => {
                if up && w <= bw && h <= bh {
                    // scale up until one side meets the box
                    let (sw, sh, bw64, bh64) = (w as u64, h as u64, bw as u64, bh as u64);
                    if sw * bh64 >= sh * bw64 {
                        (bw, ((2 * sh * bw64 + sw) / (2 * sw)).max(1) as u32)
                    } else {
                        (((2 * sw * bh64 + sh) / (2 * sh)).max(1) as u32, bh)
                    }
                } else {
                    crate::contract::fit_inside(w, h, bw, bh)
                }
            }
            FitMode::Crop => (bw.min(if up { bw } else { w }), bh.min(if up { bh } else { h })),
            FitMode::Stretch => (if up { bw } else { bw.min(w) }, if up { bh } else { bh.min(h) }),
        }
    }
}

/// Resizes per the fit mode, then applies the steps that follow the resize.
pub fn finish(img: image::DynamicImage, req: &DeliveryRequest) -> Result<image::DynamicImage, DamError> {
    let (ow, oh) = req.output_size(img.width(), img.height());
    if ow == 0 || oh == 0 {
        return Err(DamError::BadRequest("empty image".into()));
    }
    let mut out = match req.fit_mode() {
        FitMode::Fit | FitMode::Stretch => crate::process::resize(&img, ow, oh)?,
        FitMode::Crop => {
            // scale so the box is covered (never below 1 unless upscaling), then keep the middle
            let (w, h) = (img.width() as f64, img.height() as f64);
            let mut scale = (ow as f64 / w).max(oh as f64 / h);
            if !req.upscale() {
                scale = scale.min(1.0);
            }
            let sw = ((w * scale).round() as u32).max(ow);
            let sh = ((h * scale).round() as u32).max(oh);
            let scaled = crate::process::resize(&img, sw, sh)?;
            scaled.crop_imm((sw - ow) / 2, (sh - oh) / 2, ow, oh)
        }
    };
    for step in &req.steps {
        out = match *step {
            Step::Sharpen { amount, radius, threshold } => unsharp(&out, amount as f32 / 100.0, radius as f32 / 10.0, threshold),
            Step::Blur(sigma) => out.blur(sigma as f32 / 10.0),
            Step::Brightness(v) => out.brighten((v as i32 * 255) / 100),
            Step::Contrast(v) => out.adjust_contrast(v as f32),
            Step::Background(c) => flatten(&out, c),
            Step::Saturation(v) => saturate(&out, v),
            Step::Grayscale => grayscale(&out),
            Step::Sepia => sepia(&out),
            Step::Overlay { rgb, opacity } => tint(&out, rgb, |_, _| opacity as f32 / 100.0),
            Step::Gradient { towards, rgb, from, to } => {
                let (w, h) = (out.width().max(1) as f32, out.height().max(1) as f32);
                let (f, t) = (from as f32 / 100.0, to as f32 / 100.0);
                tint(&out, rgb, move |x, y| {
                    let p = match towards {
                        b'b' => y as f32 / (h - 1.0).max(1.0),
                        b't' => 1.0 - y as f32 / (h - 1.0).max(1.0),
                        b'r' => x as f32 / (w - 1.0).max(1.0),
                        _ => 1.0 - x as f32 / (w - 1.0).max(1.0),
                    };
                    f + (t - f) * p
                })
            }
            Step::Vignette(v) => {
                let (w, h) = (out.width().max(1) as f32, out.height().max(1) as f32);
                let strength = v as f32 / 100.0;
                let (cx, cy) = ((w - 1.0) / 2.0, (h - 1.0) / 2.0);
                let radius = (cx * cx + cy * cy).sqrt().max(1.0);
                tint(&out, 0, move |x, y| {
                    let d = ((x as f32 - cx).powi(2) + (y as f32 - cy).powi(2)).sqrt() / radius;
                    // nothing in the middle third, then a smooth ramp to `strength` in the corners
                    let t = ((d - 0.35) / 0.65).clamp(0.0, 1.0);
                    strength * t * t
                })
            }
            Step::Radius(r) => rounded(&out, r as f32),
            Step::Border { width, rgb } => border(&out, width as u32, rgb),
            _ => out,
        };
    }
    Ok(out)
}

/// Mixes `rgb` over every pixel with the opacity `alpha(x, y)` gives (0..=1); the image's own alpha is kept.
fn tint(img: &image::DynamicImage, rgb: u32, alpha: impl Fn(u32, u32) -> f32) -> image::DynamicImage {
    let c = [(rgb >> 16) as u8 as f32, (rgb >> 8) as u8 as f32, rgb as u8 as f32];
    let mut out = img.to_rgba8();
    for (x, y, p) in out.enumerate_pixels_mut() {
        let a = alpha(x, y).clamp(0.0, 1.0);
        for i in 0..3 {
            p.0[i] = (p.0[i] as f32 * (1.0 - a) + c[i] * a).round() as u8;
        }
    }
    keep_alpha(img, out)
}

fn keep_alpha(like: &image::DynamicImage, out: image::RgbaImage) -> image::DynamicImage {
    if like.color().has_alpha() {
        image::DynamicImage::ImageRgba8(out)
    } else {
        image::DynamicImage::ImageRgb8(image::DynamicImage::ImageRgba8(out).to_rgb8())
    }
}

fn saturate(img: &image::DynamicImage, v: i8) -> image::DynamicImage {
    let k = 1.0 + v as f32 / 100.0; // 0 = grey, 1 = unchanged, 2 = doubled
    let mut out = img.to_rgba8();
    for p in out.pixels_mut() {
        let l = 0.299 * p.0[0] as f32 + 0.587 * p.0[1] as f32 + 0.114 * p.0[2] as f32;
        for i in 0..3 {
            p.0[i] = (l + (p.0[i] as f32 - l) * k).round().clamp(0.0, 255.0) as u8;
        }
    }
    keep_alpha(img, out)
}

fn grayscale(img: &image::DynamicImage) -> image::DynamicImage {
    saturate(img, -100)
}

fn sepia(img: &image::DynamicImage) -> image::DynamicImage {
    let mut out = img.to_rgba8();
    for p in out.pixels_mut() {
        let [r, g, b] = [p.0[0] as f32, p.0[1] as f32, p.0[2] as f32];
        p.0[0] = (0.393 * r + 0.769 * g + 0.189 * b).min(255.0) as u8;
        p.0[1] = (0.349 * r + 0.686 * g + 0.168 * b).min(255.0) as u8;
        p.0[2] = (0.272 * r + 0.534 * g + 0.131 * b).min(255.0) as u8;
    }
    keep_alpha(img, out)
}

/// Transparent outside a rounded rectangle; the output always has alpha (JPEG flattens it on white,
/// a background step on its colour).
fn rounded(img: &image::DynamicImage, radius: f32) -> image::DynamicImage {
    let (w, h) = (img.width(), img.height());
    let r = radius.min(w as f32 / 2.0).min(h as f32 / 2.0);
    let mut out = img.to_rgba8();
    for (x, y, p) in out.enumerate_pixels_mut() {
        let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
        // distance from the nearest corner's circle centre, only inside the corner squares
        let dx = if px < r { r - px } else if px > w as f32 - r { px - (w as f32 - r) } else { 0.0 };
        let dy = if py < r { r - py } else if py > h as f32 - r { py - (h as f32 - r) } else { 0.0 };
        if dx > 0.0 && dy > 0.0 {
            let d = (dx * dx + dy * dy).sqrt();
            // one pixel of anti-aliasing at the edge
            let cover = (r + 0.5 - d).clamp(0.0, 1.0);
            p.0[3] = (p.0[3] as f32 * cover).round() as u8;
        }
    }
    image::DynamicImage::ImageRgba8(out)
}

fn border(img: &image::DynamicImage, width: u32, rgb: u32) -> image::DynamicImage {
    let c = [(rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8];
    let (w, h) = (img.width(), img.height());
    let mut out = img.to_rgba8();
    for (x, y, p) in out.enumerate_pixels_mut() {
        // the pixel's alpha is kept, so a border follows rounded corners
        if x < width || y < width || x + width >= w || y + width >= h {
            p.0[..3].copy_from_slice(&c);
        }
    }
    keep_alpha(img, out)
}

/// `orig + amount * (orig - blurred)` where the difference exceeds the threshold.
fn unsharp(img: &image::DynamicImage, amount: f32, sigma: f32, threshold: u8) -> image::DynamicImage {
    let blurred = img.blur(sigma).to_rgba8();
    let mut out = img.to_rgba8();
    for (p, b) in out.pixels_mut().zip(blurred.pixels()) {
        for i in 0..3 {
            let diff = p.0[i] as f32 - b.0[i] as f32;
            if diff.abs() > threshold as f32 {
                p.0[i] = (p.0[i] as f32 + amount * diff).round().clamp(0.0, 255.0) as u8;
            }
        }
    }
    if img.color().has_alpha() {
        image::DynamicImage::ImageRgba8(out)
    } else {
        image::DynamicImage::ImageRgb8(image::DynamicImage::ImageRgba8(out).to_rgb8())
    }
}

fn flatten(img: &image::DynamicImage, rgb: u32) -> image::DynamicImage {
    if !img.color().has_alpha() {
        return img.clone();
    }
    let bg = [(rgb >> 16) as u8, (rgb >> 8) as u8, rgb as u8];
    let rgba = img.to_rgba8();
    image::DynamicImage::ImageRgb8(image::RgbImage::from_fn(rgba.width(), rgba.height(), |x, y| {
        let p = rgba.get_pixel(x, y).0;
        let a = p[3] as u32;
        let mix = |c: u8, b: u8| ((c as u32 * a + b as u32 * (255 - a) + 127) / 255) as u8;
        image::Rgb([mix(p[0], bg[0]), mix(p[1], bg[1]), mix(p[2], bg[2])])
    }))
}

impl DeliveryRequest {
    /// Parse `<W>x<H>.<ext>` plus an optional quality.
    pub fn parse(blob: &str, spec: &str, quality: Option<u8>, max_dim: u32) -> Result<Self, DamError> {
        let blob = BlobId::parse(blob)?;
        let bad = || DamError::BadRequest("expected <width>x<height>.<jpeg|png|webp>".into());
        let (dims, ext) = spec.rsplit_once('.').ok_or_else(bad)?;
        let (w, h) = dims.split_once('x').ok_or_else(bad)?;
        let box_w: u32 = w.parse().map_err(|_| bad())?;
        let box_h: u32 = h.parse().map_err(|_| bad())?;
        if box_w == 0 || box_h == 0 || box_w > max_dim || box_h > max_dim {
            return Err(DamError::BadRequest(format!("dimensions must be between 1 and {max_dim}")));
        }
        if quality.is_some_and(|q| q == 0 || q > 100) {
            return Err(DamError::BadRequest("q must be between 1 and 100".into()));
        }
        let format = match ext {
            "jpg" | "jpeg" => OutFormat::Jpeg { quality: quality.unwrap_or(DEFAULT_JPEG_QUALITY) },
            "png" => OutFormat::Png,
            "webp" => OutFormat::WebP { quality: quality.unwrap_or(DEFAULT_WEBP_QUALITY) },
            _ => return Err(bad()),
        };
        Ok(DeliveryRequest { blob, box_w, box_h, format, steps: Vec::new() })
    }

    /// Adds the `t` parameter.
    pub fn with_transform(mut self, t: Option<&str>) -> Result<Self, DamError> {
        self.steps = parse_transform(t.unwrap_or(""))?;
        Ok(self)
    }

    /// The string that is signed and that keys the cache. Equivalent requests
    /// (`jpg` vs `jpeg`, default vs explicit quality) share one canonical form.
    pub fn canonical(&self) -> String {
        let q = match self.format {
            OutFormat::Jpeg { quality } | OutFormat::WebP { quality } => quality,
            OutFormat::Png => 0,
        };
        let base = format!("{}/{}x{}.{}?q={}", self.blob, self.box_w, self.box_h, self.format.extension(), q);
        if self.steps.is_empty() {
            base
        } else {
            format!("{base}&t={}", format_transform(&self.steps))
        }
    }

    /// Longer side of the image the transform produces from a `src_w`×`src_h` original (crops
    /// clipped, turns do not change it); the box is clamped to it.
    pub fn longest_side_after(&self, src_w: u32, src_h: u32) -> u32 {
        let (mut w, mut h) = (src_w, src_h);
        for step in &self.steps {
            if let Step::Crop { x, y, w: cw, h: ch } = *step {
                w = x.saturating_add(cw).min(w).saturating_sub(x.min(w));
                h = y.saturating_add(ch).min(h).saturating_sub(y.min(h));
            }
        }
        w.max(h)
    }

    /// The same request with the box limited to `max_side` (the source's longer side): asking
    /// for more than the source has gives the same image, so it should share one cache entry.
    pub fn clamped_to(&self, max_side: u32) -> Self {
        let mut r = self.clone();
        r.box_w = r.box_w.min(max_side.max(1));
        r.box_h = r.box_h.min(max_side.max(1));
        r
    }

    pub fn cache_path(&self, cache_dir: &Path) -> PathBuf {
        let key = hex::encode(Sha256::digest(self.canonical().as_bytes()));
        cache_dir.join(&key[0..2]).join(format!("{key}.{}", self.format.extension()))
    }
}

pub fn sign(token: &str, canonical: &str) -> String {
    let mut mac = HmacSha256::new_from_slice(token.as_bytes()).expect("hmac accepts any key length");
    mac.update(canonical.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// Constant-time signature check.
pub fn verify(token: &str, canonical: &str, signature_hex: &str) -> bool {
    let Ok(sig) = hex::decode(signature_hex) else { return false };
    let mut mac = HmacSha256::new_from_slice(token.as_bytes()).expect("hmac accepts any key length");
    mac.update(canonical.as_bytes());
    mac.verify_slice(&sig).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    const BLOB: &str = "613269c22d868b76da4f3926548b0cd8068cd5f307f4b9bbb75244e4c4b519b1";

    #[test]
    fn parses_and_canonicalises() {
        let a = DeliveryRequest::parse(BLOB, "640x480.jpg", None, 4096).unwrap();
        let b = DeliveryRequest::parse(BLOB, "640x480.jpeg", Some(80), 4096).unwrap();
        assert_eq!(a.canonical(), b.canonical());
        assert_eq!(a.canonical(), format!("{BLOB}/640x480.jpeg?q=80"));
    }

    #[test]
    fn rejects_bad_specs() {
        for spec in ["640x480", "640.jpeg", "0x10.png", "5000x10.png", "10x10.gif", "axb.png"] {
            assert!(DeliveryRequest::parse(BLOB, spec, None, 4096).is_err(), "{spec}");
        }
        assert!(DeliveryRequest::parse(BLOB, "10x10.webp", Some(0), 4096).is_err());
        assert!(DeliveryRequest::parse("../x", "10x10.png", None, 4096).is_err());
    }

    #[test]
    fn transforms_parse_and_join_the_canonical_form() {
        let plain = DeliveryRequest::parse(BLOB, "640x4096.jpeg", Some(60), 4096).unwrap();
        let t = plain.clone().with_transform(Some("c10,20,300,200-r90-h-v")).unwrap();
        assert_eq!(t.steps, vec![Step::Crop { x: 10, y: 20, w: 300, h: 200 }, Step::Rotate(90), Step::FlipH, Step::FlipV]);
        assert_eq!(t.canonical(), format!("{BLOB}/640x4096.jpeg?q=60&t=c10,20,300,200-r90-h-v"));
        // No transform: the canonical form (and so old signatures and cache keys) is unchanged.
        assert_eq!(plain.clone().with_transform(Some("")).unwrap().canonical(), plain.canonical());
        let dm = plain.clone().with_transform(Some("fcrop-u-s120,15,2-b5-ln10-k25-g0a0b0c")).unwrap();
        assert_eq!(dm.fit_mode(), FitMode::Crop);
        assert!(dm.upscale());
        assert_eq!(dm.canonical(), format!("{BLOB}/640x4096.jpeg?q=60&t=fcrop-u-s120,15,2-b5-ln10-k25-g0a0b0c"));
        for bad in ["s1,2", "s600,1,0", "s1,0,0", "b0", "l101", "ln0", "l01", "kn101", "l-5", "gfff", "gGGGGGG", "ffit2"] {
            assert!(plain.clone().with_transform(Some(bad)).is_err(), "{bad}");
        }
        for bad in ["r45", "c1,2,3", "c1,2,0,4", "x", "c-1,2,3,4", "h-h-h-h-h-h-h-h-h-h-h-h-h-h-h-h-h-h-h-h-h"] {
            assert!(plain.clone().with_transform(Some(bad)).is_err(), "{bad}");
        }
        assert_eq!(t.longest_side_after(1000, 800), 300);
        let edge = plain.with_transform(Some("c900,0,300,100")).unwrap();
        assert_eq!(edge.longest_side_after(1000, 800), 100, "clipped to 100x100");
    }

    #[test]
    fn applies_steps_in_order() {
        use image::{DynamicImage, Rgb, RgbImage};
        // 4x2: left half red, right half blue.
        let img = DynamicImage::ImageRgb8(RgbImage::from_fn(4, 2, |x, _| if x < 2 { Rgb([255, 0, 0]) } else { Rgb([0, 0, 255]) }));
        let out = apply(img.clone(), &parse_transform("c2,0,2,2-r90").unwrap()).unwrap();
        assert_eq!((out.width(), out.height()), (2, 2));
        assert!(out.to_rgb8().pixels().all(|p| p.0 == [0, 0, 255]));
        let turned = apply(img.clone(), &parse_transform("r90-h").unwrap()).unwrap().to_rgb8();
        // Clockwise puts red on top; the mirror keeps it there.
        assert_eq!((turned.width(), turned.height()), (2, 4));
        assert_eq!(turned.get_pixel(0, 0).0, [255, 0, 0]);
        assert_eq!(turned.get_pixel(1, 3).0, [0, 0, 255]);
        assert!(apply(img, &parse_transform("c10,10,5,5").unwrap()).is_err());
    }

    #[test]
    fn fit_modes_size_the_output() {
        let req = |t: &str, spec: &str| DeliveryRequest::parse(BLOB, spec, None, 4096).unwrap().with_transform(Some(t)).unwrap();
        assert_eq!(req("", "500x500.png").output_size(1000, 400), (500, 200));
        assert_eq!(req("", "2000x2000.png").output_size(1000, 400), (1000, 400), "no upscale");
        assert_eq!(req("u", "2000x2000.png").output_size(1000, 400), (2000, 800));
        assert_eq!(req("fcrop", "300x300.png").output_size(1000, 400), (300, 300));
        assert_eq!(req("fcrop", "2000x300.png").output_size(1000, 400), (1000, 300), "no upscale: as much as there is");
        assert_eq!(req("fcrop-u", "2000x300.png").output_size(1000, 400), (2000, 300));
        assert_eq!(req("fstretch", "300x300.png").output_size(1000, 400), (300, 300));
        assert_eq!(req("fstretch", "3000x300.png").output_size(1000, 400), (1000, 300));

        use image::{DynamicImage, Rgb, Rgba, RgbImage, RgbaImage};
        // 4x2: left half red, right half blue: a 2x2 crop keeps the middle (one red, one blue column)
        let img = DynamicImage::ImageRgb8(RgbImage::from_fn(4, 2, |x, _| if x < 2 { Rgb([255, 0, 0]) } else { Rgb([0, 0, 255]) }));
        let out = finish(img.clone(), &req("fcrop", "2x2.png")).unwrap().to_rgb8();
        assert_eq!((out.width(), out.height()), (2, 2));
        assert_eq!(out.get_pixel(0, 0).0, [255, 0, 0]);
        assert_eq!(out.get_pixel(1, 0).0, [0, 0, 255]);
        let stretched = finish(img.clone(), &req("fstretch", "2x2.png")).unwrap();
        assert_eq!((stretched.width(), stretched.height()), (2, 2));
        // transparency flattened onto the background colour
        let clear = DynamicImage::ImageRgba8(RgbaImage::from_pixel(2, 2, Rgba([0, 0, 0, 0])));
        let flat = finish(clear, &req("g0a0b0c", "2x2.png")).unwrap().to_rgb8();
        assert_eq!(flat.get_pixel(0, 0).0, [0x0a, 0x0b, 0x0c]);
        // brightness, sharpening and blur keep the size and do not fail on tiny images
        let bright = finish(img.clone(), &req("l50-s150,10,0-b3", "4x2.png")).unwrap();
        assert_eq!((bright.width(), bright.height()), (4, 2));
    }

    #[test]
    fn looks_parse_and_apply() {
        let t = "a n40-m-p-o000000,30-db,000000,0,70-w60-e24-x4,ff0000-gffffff".replace(' ', "");
        let steps = parse_transform(&t).unwrap();
        assert_eq!(steps.len(), 9);
        assert_eq!(format_transform(&steps), t);
        assert_eq!(steps[4], Step::Gradient { towards: b'b', rgb: 0, from: 0, to: 70 });
        for bad in ["dq,000000,0,70", "db,00000,0,70", "db,000000,0,101", "o000000,0", "w0", "e0", "x0,ff0000", "a101"] {
            assert!(parse_transform(bad).is_err(), "{bad}");
        }
        // a white 100x50 picture: a black gradient towards the bottom leaves the top white and darkens the bottom
        let white = image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(100, 50, image::Rgb([255, 255, 255])));
        let req = DeliveryRequest::parse("0".repeat(64).as_str(), "100x50.png", None, 4096)
            .unwrap()
            .with_transform(Some("db,000000,0,100"))
            .unwrap();
        let out = finish(white.clone(), &req).unwrap().to_rgb8();
        assert_eq!(out.get_pixel(50, 0).0, [255, 255, 255]);
        assert_eq!(out.get_pixel(50, 49).0, [0, 0, 0]);
        assert!(out.get_pixel(50, 25).0[0] > 100 && out.get_pixel(50, 25).0[0] < 160);
        // rounded corners make the corner transparent and keep the middle; a border paints the edge
        let req = DeliveryRequest::parse("0".repeat(64).as_str(), "100x50.png", None, 4096)
            .unwrap()
            .with_transform(Some("e20-x2,ff0000"))
            .unwrap();
        let out = finish(white.clone(), &req).unwrap().to_rgba8();
        assert_eq!(out.get_pixel(0, 0).0[3], 0);
        assert_eq!(out.get_pixel(50, 25).0, [255, 255, 255, 255]);
        assert_eq!(out.get_pixel(50, 0).0, [255, 0, 0, 255]);
        // grey after grayscale; sepia is warm
        let red = image::DynamicImage::ImageRgb8(image::RgbImage::from_pixel(10, 10, image::Rgb([200, 50, 50])));
        let g = grayscale(&red).to_rgb8().get_pixel(5, 5).0;
        assert_eq!(g[0], g[1]);
        assert_eq!(g[1], g[2]);
        let sp = sepia(&red).to_rgb8().get_pixel(5, 5).0;
        assert!(sp[0] > sp[1] && sp[1] > sp[2]);
        // a vignette leaves the centre alone and darkens the corners
        let req = DeliveryRequest::parse("0".repeat(64).as_str(), "100x50.png", None, 4096)
            .unwrap()
            .with_transform(Some("w100"))
            .unwrap();
        let out = finish(white, &req).unwrap().to_rgb8();
        assert_eq!(out.get_pixel(50, 25).0, [255, 255, 255]);
        assert!(out.get_pixel(0, 0).0[0] < 60);
    }

    #[test]
    fn signatures_bind_every_parameter() {
        let token = "a-very-secret-token";
        let req = DeliveryRequest::parse(BLOB, "640x480.webp", None, 4096).unwrap();
        let sig = sign(token, &req.canonical());
        assert!(verify(token, &req.canonical(), &sig));
        let other = DeliveryRequest::parse(BLOB, "641x480.webp", None, 4096).unwrap();
        assert!(!verify(token, &other.canonical(), &sig));
        assert!(!verify("another-token-xyz", &req.canonical(), &sig));
        assert!(!verify(token, &req.canonical(), "not-hex"));
    }
}
