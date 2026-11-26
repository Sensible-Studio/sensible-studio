//! The rendition contract observed in WKND content (see docs/dam-contract.md).

/// Output encoding of a rendition.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum OutFormat {
    Jpeg { quality: u8 },
    Png,
    WebP { quality: u8 },
}

impl OutFormat {
    pub fn extension(&self) -> &'static str {
        match self {
            OutFormat::Jpeg { .. } => "jpeg",
            OutFormat::Png => "png",
            OutFormat::WebP { .. } => "webp",
        }
    }

    pub fn mime(&self) -> &'static str {
        match self {
            OutFormat::Jpeg { .. } => "image/jpeg",
            OutFormat::Png => "image/png",
            OutFormat::WebP { .. } => "image/webp",
        }
    }

    pub fn supports_alpha(&self) -> bool {
        !matches!(self, OutFormat::Jpeg { .. })
    }
}

/// One rendition to produce, with its bounding box.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RenditionSpec {
    pub kind: &'static str,
    pub box_w: u32,
    pub box_h: u32,
    pub format: OutFormat,
    /// Name of a processing-profile rendition (`<name>.<ext>`); `None` for the AEM ones.
    pub custom: Option<String>,
    /// The watermark is drawn on it (processing-profile renditions only).
    pub watermark: bool,
}

impl RenditionSpec {
    /// `cq5dam.<kind>.<W>.<H>.<ext>`, or `<custom>.<ext>`
    pub fn name(&self) -> String {
        match &self.custom {
            Some(n) => format!("{}.{}", n, self.format.extension()),
            None => format!("cq5dam.{}.{}.{}.{}", self.kind, self.box_w, self.box_h, self.format.extension()),
        }
    }

    /// A rendition of a processing profile. The name must be a plain node name that cannot
    /// collide with the original or the standard renditions.
    pub fn custom(name: &str, box_w: u32, box_h: u32, format: OutFormat) -> Result<Self, String> {
        let ok_chars = name.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '-' | '_' | '.'));
        if name.is_empty() || name.len() > 64 || !ok_chars || name.starts_with('.') || name.ends_with('.') {
            return Err(format!("invalid rendition name {name:?}"));
        }
        if name == "original" || name.starts_with("cq5dam.") {
            return Err(format!("reserved rendition name {name:?}"));
        }
        if !(1..=MAX_CUSTOM_SIDE).contains(&box_w) || !(1..=MAX_CUSTOM_SIDE).contains(&box_h) {
            return Err(format!("rendition {name:?}: width and height must be 1..={MAX_CUSTOM_SIDE}"));
        }
        Ok(RenditionSpec { kind: "custom", box_w, box_h, format, custom: Some(name.to_string()), watermark: false })
    }
}

/// Largest box of a processing-profile rendition.
pub const MAX_CUSTOM_SIDE: u32 = 8192;
/// Processing-profile renditions per asset at most.
pub const MAX_CUSTOM_RENDITIONS: usize = 20;

/// JPEG quality used for `web` and `zoom` (q=80 in 125 of 130 observed renditions).
pub const WEB_QUALITY: u8 = 80;

/// The default processing profile:
/// - three PNG thumbnails for every image;
/// - `web.1280.1280` as JPEG, or PNG when the source has transparency;
/// - `zoom.2048.2048` as JPEG, only for sources without transparency.
pub fn default_profile(source_has_alpha: bool) -> Vec<RenditionSpec> {
    let thumb = |w, h| RenditionSpec { kind: "thumbnail", box_w: w, box_h: h, format: OutFormat::Png, custom: None, watermark: false };
    let mut specs = vec![thumb(48, 48), thumb(140, 100), thumb(319, 319)];
    if source_has_alpha {
        specs.push(RenditionSpec { kind: "web", box_w: 1280, box_h: 1280, format: OutFormat::Png, custom: None, watermark: false });
    } else {
        let jpeg = OutFormat::Jpeg { quality: WEB_QUALITY };
        specs.push(RenditionSpec { kind: "web", box_w: 1280, box_h: 1280, format: jpeg, custom: None, watermark: false });
        specs.push(RenditionSpec { kind: "zoom", box_w: 2048, box_h: 2048, format: jpeg, custom: None, watermark: false });
    }
    specs
}

/// Fit `src` inside the box keeping the aspect ratio, never upscaling.
/// The constrained side equals the box side exactly; the other is rounded.
pub fn fit_inside(src_w: u32, src_h: u32, box_w: u32, box_h: u32) -> (u32, u32) {
    if src_w == 0 || src_h == 0 {
        return (0, 0);
    }
    if src_w <= box_w && src_h <= box_h {
        return (src_w, src_h);
    }
    let (sw, sh, bw, bh) = (src_w as u64, src_h as u64, box_w as u64, box_h as u64);
    if sw * bh >= sh * bw {
        // width-limited: h = round(sh * bw / sw)
        let h = (2 * sh * bw + sw) / (2 * sw);
        (box_w, h.max(1) as u32)
    } else {
        let w = (2 * sw * bh + sh) / (2 * sh);
        (w.max(1) as u32, box_h)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_match_aem_convention() {
        let names: Vec<_> = default_profile(false).iter().map(|s| s.name()).collect();
        assert_eq!(
            names,
            [
                "cq5dam.thumbnail.48.48.png",
                "cq5dam.thumbnail.140.100.png",
                "cq5dam.thumbnail.319.319.png",
                "cq5dam.web.1280.1280.jpeg",
                "cq5dam.zoom.2048.2048.jpeg",
            ]
        );
        assert_eq!(default_profile(true)[3].name(), "cq5dam.web.1280.1280.png");
        assert_eq!(default_profile(true).len(), 4);
    }

    #[test]
    fn fit_inside_matches_observed_values() {
        // Values measured on WKND renditions.
        assert_eq!(fit_inside(1600, 886, 1280, 1280), (1280, 709));
        assert_eq!(fit_inside(2950, 3840, 2048, 2048), (1573, 2048));
        assert_eq!(fit_inside(2950, 3840, 140, 100), (77, 100));
        assert_eq!(fit_inside(1000, 357, 140, 100), (140, 50));
        assert_eq!(fit_inside(1280, 1280, 140, 100), (100, 100));
    }

    #[test]
    fn never_upscales() {
        assert_eq!(fit_inside(720, 1080, 2048, 2048), (720, 1080));
        assert_eq!(fit_inside(301, 128, 319, 319), (301, 128));
    }

    #[test]
    fn extreme_ratios_keep_one_pixel() {
        assert_eq!(fit_inside(10_000, 1, 48, 48), (48, 1));
        assert_eq!(fit_inside(1, 10_000, 48, 48), (1, 48));
    }
}
