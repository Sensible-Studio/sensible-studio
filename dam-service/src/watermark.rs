//! A watermark drawn on renditions of a processing profile: one picture, at a position, an
//! opacity and a size (a share of the rendition's width).

use image::{imageops, DynamicImage, RgbaImage};

use crate::error::DamError;

/// Where the mark goes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Position {
    Center,
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
    /// Repeated over the whole picture.
    Tile,
}

impl Position {
    pub fn parse(s: &str) -> Result<Self, DamError> {
        Ok(match s {
            "center" => Position::Center,
            "top-left" => Position::TopLeft,
            "top-right" => Position::TopRight,
            "bottom-left" => Position::BottomLeft,
            "bottom-right" => Position::BottomRight,
            "tile" => Position::Tile,
            other => return Err(DamError::BadRequest(format!("unknown watermark position {other:?}"))),
        })
    }
}

/// The watermark picture (with its alpha), and how to draw it.
#[derive(Clone, Debug)]
pub struct Watermark {
    pub mark: RgbaImage,
    pub position: Position,
    /// 0.01..=1
    pub opacity: f32,
    /// Share of the rendition's width, 0.05..=1.
    pub size: f32,
}

/// A mark larger than this on its longest side is shrunk once when read.
const MAX_MARK_SIDE: u32 = 4096;

impl Watermark {
    pub fn new(mark: DynamicImage, position: Position, opacity_percent: u32, size_percent: u32) -> Self {
        let mark = if mark.width().max(mark.height()) > MAX_MARK_SIDE {
            mark.resize(MAX_MARK_SIDE, MAX_MARK_SIDE, imageops::FilterType::Triangle)
        } else {
            mark
        };
        Watermark {
            mark: mark.to_rgba8(),
            position,
            opacity: opacity_percent.clamp(1, 100) as f32 / 100.0,
            size: size_percent.clamp(5, 100) as f32 / 100.0,
        }
    }

    /// The picture with the mark drawn on it. A picture without transparency stays without.
    pub fn apply(&self, img: &DynamicImage) -> DynamicImage {
        let (w, h) = (img.width(), img.height());
        if w == 0 || h == 0 || self.mark.width() == 0 || self.mark.height() == 0 {
            return img.clone();
        }
        // the mark's width is a share of the picture's; never taller than the picture
        let mut mw = ((w as f32 * self.size).round() as u32).max(1);
        let mut mh = ((mw as u64 * self.mark.height() as u64) / self.mark.width() as u64).max(1) as u32;
        if mh > h {
            mh = h;
            mw = ((mh as u64 * self.mark.width() as u64) / self.mark.height() as u64).max(1) as u32;
        }
        let mut mark = imageops::resize(&self.mark, mw, mh, imageops::FilterType::Triangle);
        if self.opacity < 1.0 {
            for p in mark.pixels_mut() {
                p.0[3] = (p.0[3] as f32 * self.opacity).round() as u8;
            }
        }
        let alpha = img.color().has_alpha();
        let mut base = img.to_rgba8();
        let margin = (w.min(h) as f32 * 0.03).round() as i64;
        let (mw, mh) = (mw as i64, mh as i64);
        let (w, h) = (w as i64, h as i64);
        match self.position {
            Position::Tile => {
                // a gap of half a mark between copies, rows shifted by half a step
                let (sx, sy) = (mw + mw / 2, mh + mh);
                let mut row = 0;
                let mut y = -(mh / 2);
                while y < h {
                    let mut x = if row % 2 == 0 { 0 } else { -(sx / 2) };
                    while x < w {
                        imageops::overlay(&mut base, &mark, x, y);
                        x += sx.max(1);
                    }
                    y += sy.max(1);
                    row += 1;
                }
            }
            p => {
                let (x, y) = match p {
                    Position::Center => ((w - mw) / 2, (h - mh) / 2),
                    Position::TopLeft => (margin, margin),
                    Position::TopRight => (w - mw - margin, margin),
                    Position::BottomLeft => (margin, h - mh - margin),
                    _ => (w - mw - margin, h - mh - margin),
                };
                imageops::overlay(&mut base, &mark, x.max(0), y.max(0));
            }
        }
        if alpha {
            DynamicImage::ImageRgba8(base)
        } else {
            DynamicImage::ImageRgb8(DynamicImage::ImageRgba8(base).to_rgb8())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{Rgb, RgbImage, Rgba};

    fn white(w: u32, h: u32) -> DynamicImage {
        DynamicImage::ImageRgb8(RgbImage::from_pixel(w, h, Rgb([255, 255, 255])))
    }

    fn red_mark() -> DynamicImage {
        DynamicImage::ImageRgba8(RgbaImage::from_pixel(10, 5, Rgba([255, 0, 0, 255])))
    }

    #[test]
    fn a_centred_mark_takes_its_share_of_the_width() {
        let out = Watermark::new(red_mark(), Position::Center, 100, 50).apply(&white(200, 100)).to_rgb8();
        // 100 × 50 mark from (50, 25)
        assert_eq!(out.get_pixel(100, 50).0, [255, 0, 0]);
        assert_eq!(out.get_pixel(55, 30).0, [255, 0, 0]);
        assert_eq!(out.get_pixel(45, 50).0, [255, 255, 255]);
        assert_eq!(out.get_pixel(100, 20).0, [255, 255, 255]);
    }

    #[test]
    fn opacity_blends_the_mark() {
        let out = Watermark::new(red_mark(), Position::Center, 50, 50).apply(&white(200, 100)).to_rgb8();
        let p = out.get_pixel(100, 50).0;
        assert_eq!(p[0], 255);
        assert!((120..=135).contains(&p[1]), "{p:?}");
    }

    #[test]
    fn a_corner_keeps_a_margin() {
        let out = Watermark::new(red_mark(), Position::BottomRight, 100, 20).apply(&white(200, 100)).to_rgb8();
        // mark 40 × 20, margin 3 px: from (157, 77) to (196, 96)
        assert_eq!(out.get_pixel(190, 90).0, [255, 0, 0]);
        assert_eq!(out.get_pixel(198, 98).0, [255, 255, 255]);
        assert_eq!(out.get_pixel(10, 10).0, [255, 255, 255]);
    }

    #[test]
    fn tiles_cover_the_picture() {
        let out = Watermark::new(red_mark(), Position::Tile, 100, 10).apply(&white(400, 400)).to_rgb8();
        let red = out.pixels().filter(|p| p.0 == [255, 0, 0]).count();
        assert!(red > 400 * 400 / 10, "{red}");
        assert!(red < 400 * 400 / 2, "{red}");
    }

    #[test]
    fn a_tall_mark_is_never_taller_than_the_picture() {
        let tall = DynamicImage::ImageRgba8(RgbaImage::from_pixel(5, 100, Rgba([0, 0, 255, 255])));
        let out = Watermark::new(tall, Position::Center, 100, 100).apply(&white(100, 50));
        assert_eq!((out.width(), out.height()), (100, 50));
        assert!(!out.color().has_alpha());
    }
}
