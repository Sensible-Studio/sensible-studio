//! Previews of files that are not raster images: the first page of a PDF (hayro) and SVG
//! drawings (resvg), both pure Rust. The result is an ordinary image the rendition pipeline
//! resizes like a photo.
//!
//! Safety: neither library runs scripts. SVG files cannot reach the file system or the network
//! (`<image href>` is limited to `data:` URLs). Rendering is bounded by [`MAX_SIDE`] and a panic
//! inside a library (a malformed file) becomes an unsupported-format error, not a crash.

use std::collections::BTreeMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, OnceLock};

use image::{DynamicImage, RgbImage, RgbaImage};
use serde_json::{json, Value};

use crate::DamError;

/// Longest side of the rendered preview: the zoom size, enough for every standard rendition.
pub const MAX_SIDE: u32 = 2048;

pub struct Preview {
    pub image: DynamicImage,
    pub mime: &'static str,
    /// What the file says about itself (page count, title, author, ...).
    pub metadata: BTreeMap<String, Value>,
}

/// `Some` for PDF and SVG files and, when LibreOffice is installed, Office documents (rendered
/// or failed); `None` for everything else.
pub fn preview(bytes: &[u8], mime_hint: Option<&str>) -> Option<Result<Preview, DamError>> {
    if bytes.starts_with(b"%PDF-") {
        return Some(guard(|| pdf(bytes)));
    }
    if is_svg(bytes, mime_hint) {
        return Some(guard(|| svg(bytes)));
    }
    if let Some(kind) = crate::office::kind(bytes, mime_hint) {
        if crate::office::path().is_some() {
            return Some(office(bytes, kind));
        }
    }
    None
}

/// An Office document: its first page through a PDF; what the document is stays its own type.
fn office(bytes: &[u8], kind: crate::office::Kind) -> Result<Preview, DamError> {
    let converted = crate::office::to_pdf(bytes, kind)?;
    let mut p = guard(|| pdf(&converted))?;
    p.mime = kind.mime;
    for k in ["dam:MIMEtype", "dc:format"] {
        p.metadata.insert(k.into(), json!(kind.mime));
    }
    p.metadata.insert("dam:Fileformat".into(), json!(kind.label));
    // the PDF's own page size and producer describe the conversion, not the document
    for k in ["pdf:PageWidth", "pdf:PageHeight", "pdf:Producer", "xmp:CreatorTool"] {
        p.metadata.remove(k);
    }
    Ok(p)
}

fn guard(f: impl FnOnce() -> Result<Preview, DamError>) -> Result<Preview, DamError> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| Err(DamError::Unsupported("the file could not be rendered".into())))
}

fn is_svg(bytes: &[u8], hint: Option<&str>) -> bool {
    let head = String::from_utf8_lossy(&bytes[..bytes.len().min(2048)]).to_ascii_lowercase();
    let looks = head.contains("<svg") && (head.trim_start().starts_with('<') || head.starts_with('\u{feff}'));
    looks || (hint == Some("image/svg+xml") && head.contains("<svg"))
}

// ------------------------------------------------------------------------------------------- PDF

fn pdf(bytes: &[u8]) -> Result<Preview, DamError> {
    use hayro::hayro_interpret::InterpreterSettings;
    use hayro::hayro_syntax::Pdf;
    use hayro::vello_cpu::color::palette::css::WHITE;
    use hayro::{PixmapSettings, RenderCache, RenderSettings};

    let doc = Pdf::new(bytes.to_vec()).map_err(|_| DamError::Unsupported("not a readable PDF (damaged or encrypted)".into()))?;
    let pages = doc.pages();
    let first = pages.first().ok_or_else(|| DamError::Unsupported("the PDF has no pages".into()))?;
    let (w, h) = first.render_dimensions();
    if !(w > 0.0 && h > 0.0) {
        return Err(DamError::Unsupported("the first page has no size".into()));
    }
    // Pages are measured in points (1/72 in); never more than 4x (288 dpi), never above MAX_SIDE.
    let scale = (MAX_SIDE as f32 / w.max(h)).min(4.0);
    let cache = RenderCache::new();
    let settings = InterpreterSettings { render_annotations: false, ..Default::default() };
    let pixmap = hayro::render(
        first,
        &cache,
        &settings,
        &RenderSettings::default(),
        &PixmapSettings { x_scale: scale, y_scale: scale, bg_color: WHITE },
    );
    let (pw, ph) = (pixmap.width() as u32, pixmap.height() as u32);
    // White background: every pixel is opaque, so premultiplied RGBA is plain RGBA.
    let rgba = RgbaImage::from_raw(pw, ph, pixmap.data_as_u8_slice().to_vec())
        .ok_or_else(|| DamError::Internal("pdf pixmap size".into()))?;
    let image = DynamicImage::ImageRgb8(DynamicImage::ImageRgba8(rgba).to_rgb8());

    let mut m = BTreeMap::new();
    m.insert("dam:MIMEtype".into(), json!("application/pdf"));
    m.insert("dc:format".into(), json!("application/pdf"));
    m.insert("dam:Fileformat".into(), json!("PDF"));
    m.insert("dam:Numberofpages".into(), json!(pages.len()));
    // Page size in points, as AEM reports the PDF page (not the preview's pixels).
    m.insert("pdf:PageWidth".into(), json!(w.round() as i64));
    m.insert("pdf:PageHeight".into(), json!(h.round() as i64));
    let md = doc.metadata();
    let text = |v: &Option<Vec<u8>>| v.as_deref().map(pdf_text).filter(|s| !s.is_empty());
    if let Some(t) = text(&md.title) {
        m.insert("dc:title".into(), json!(t));
    }
    if let Some(a) = text(&md.author) {
        m.insert("dc:creator".into(), json!([a]));
    }
    if let Some(s) = text(&md.subject) {
        m.insert("dc:description".into(), json!(s));
    }
    if let Some(k) = text(&md.keywords) {
        let words: Vec<String> = k.split([',', ';']).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if !words.is_empty() {
            m.insert("pdf:Keywords".into(), json!(k));
            m.insert("dc:subject".into(), json!(words));
        }
    }
    if let Some(p) = text(&md.producer) {
        m.insert("pdf:Producer".into(), json!(p));
    }
    if let Some(c) = text(&md.creator) {
        m.insert("xmp:CreatorTool".into(), json!(c));
    }
    Ok(Preview { image, mime: "application/pdf", metadata: m })
}

/// PDF text strings: UTF-16BE with a BOM, UTF-8 with a BOM, or (close enough) Latin-1.
fn pdf_text(raw: &[u8]) -> String {
    let s = if raw.starts_with(&[0xFE, 0xFF]) {
        let units: Vec<u16> = raw[2..].chunks_exact(2).map(|c| u16::from_be_bytes([c[0], c[1]])).collect();
        String::from_utf16_lossy(&units)
    } else if raw.starts_with(&[0xEF, 0xBB, 0xBF]) {
        String::from_utf8_lossy(&raw[3..]).into_owned()
    } else {
        raw.iter().map(|&b| b as char).collect()
    };
    let s: String = s.chars().filter(|c| !c.is_control()).collect();
    s.trim().chars().take(1000).collect()
}

// ------------------------------------------------------------------------------------------- SVG

/// System fonts, loaded once: text in SVG drawings needs them.
fn fonts() -> Arc<resvg::usvg::fontdb::Database> {
    static DB: OnceLock<Arc<resvg::usvg::fontdb::Database>> = OnceLock::new();
    DB.get_or_init(|| {
        let mut db = resvg::usvg::fontdb::Database::new();
        db.load_system_fonts();
        Arc::new(db)
    })
    .clone()
}

fn svg(bytes: &[u8]) -> Result<Preview, DamError> {
    use resvg::tiny_skia::{Pixmap, Transform};
    use resvg::usvg::{ImageHrefResolver, Options, Tree};

    let opts = Options {
        // No file system, no network: only images embedded as data: URLs.
        resources_dir: None,
        image_href_resolver: ImageHrefResolver {
            resolve_data: ImageHrefResolver::default_data_resolver(),
            resolve_string: Box::new(|_, _| None),
        },
        fontdb: fonts(),
        ..Options::default()
    };
    let tree = Tree::from_data(bytes, &opts).map_err(|e| DamError::Unsupported(format!("not a readable SVG: {e}")))?;
    let size = tree.size();
    let (w, h) = (size.width(), size.height());
    if !(w > 0.0 && h > 0.0) {
        return Err(DamError::Unsupported("the SVG has no size".into()));
    }
    let scale = MAX_SIDE as f32 / w.max(h);
    let (pw, ph) = (((w * scale).round() as u32).max(1), ((h * scale).round() as u32).max(1));
    let mut pixmap = Pixmap::new(pw, ph).ok_or_else(|| DamError::Internal("svg pixmap".into()))?;
    resvg::render(&tree, Transform::from_scale(scale, scale), &mut pixmap.as_mut());
    // tiny-skia stores premultiplied alpha; the pipeline wants straight RGBA.
    let mut data = Vec::with_capacity((pw * ph * 4) as usize);
    for p in pixmap.pixels() {
        let c = p.demultiply();
        data.extend_from_slice(&[c.red(), c.green(), c.blue(), c.alpha()]);
    }
    let rgba = RgbaImage::from_raw(pw, ph, data).ok_or_else(|| DamError::Internal("svg pixmap size".into()))?;
    let opaque = rgba.pixels().all(|p| p.0[3] == 255);
    let image = if opaque { DynamicImage::ImageRgb8(RgbImage::from_fn(pw, ph, |x, y| {
        let p = rgba.get_pixel(x, y).0;
        image::Rgb([p[0], p[1], p[2]])
    })) } else { DynamicImage::ImageRgba8(rgba) };

    let mut m = BTreeMap::new();
    m.insert("dam:MIMEtype".into(), json!("image/svg+xml"));
    m.insert("dc:format".into(), json!("image/svg+xml"));
    m.insert("dam:Fileformat".into(), json!("SVG"));
    // The drawing's own size (user units ≈ CSS pixels), as width and height of the asset.
    m.insert("tiff:ImageWidth".into(), json!(w.round() as i64));
    m.insert("tiff:ImageLength".into(), json!(h.round() as i64));
    Ok(Preview { image, mime: "image/svg+xml", metadata: m })
}

#[cfg(test)]
pub mod tests_support {
    /// One page, 200 x 100 points, a red rectangle on the left half.
    pub fn tiny_pdf() -> Vec<u8> {
        let content = b"1 0 0 rg 0 0 100 100 re f";
        let objs = [
            "<< /Type /Catalog /Pages 2 0 R >>".to_string(),
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_string(),
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R >>".to_string(),
            format!("<< /Length {} >>\nstream\n{}\nendstream", content.len(), String::from_utf8_lossy(content)),
            "<< /Title (Spring Brochure) /Author (WKND) >>".to_string(),
        ];
        let mut out = b"%PDF-1.4\n".to_vec();
        let mut offsets = vec![];
        for (i, o) in objs.iter().enumerate() {
            offsets.push(out.len());
            out.extend_from_slice(format!("{} 0 obj\n{}\nendobj\n", i + 1, o).as_bytes());
        }
        let xref = out.len();
        out.extend_from_slice(format!("xref\n0 {}\n0000000000 65535 f \n", objs.len() + 1).as_bytes());
        for o in offsets {
            out.extend_from_slice(format!("{o:010} 00000 n \n").as_bytes());
        }
        out.extend_from_slice(format!("trailer\n<< /Size {} /Root 1 0 R /Info 5 0 R >>\nstartxref\n{xref}\n%%EOF\n", objs.len() + 1).as_bytes());
        out
    }
}

#[cfg(test)]
mod tests {
    use super::tests_support::tiny_pdf;
    use super::*;

    #[test]
    fn renders_the_first_pdf_page() {
        let p = preview(&tiny_pdf(), None).unwrap().unwrap();
        // 200 x 100 points at the 4x cap (288 dpi)
        assert_eq!((p.image.width(), p.image.height()), (800, 400));
        let rgb = p.image.to_rgb8();
        assert_eq!(rgb.get_pixel(100, 200).0, [255, 0, 0], "the red rectangle");
        assert_eq!(rgb.get_pixel(700, 200).0, [255, 255, 255], "white paper");
        assert_eq!(p.metadata["dam:Numberofpages"], json!(1));
        assert_eq!(p.metadata["dc:title"], json!("Spring Brochure"));
        assert_eq!(p.metadata["dc:creator"], json!(["WKND"]));
    }

    #[test]
    fn renders_svg_and_keeps_transparency() {
        let svg = br##"<svg xmlns="http://www.w3.org/2000/svg" width="40" height="20"><rect width="20" height="20" fill="#00f"/></svg>"##;
        let p = preview(svg, None).unwrap().unwrap();
        assert_eq!((p.image.width(), p.image.height()), (2048, 1024));
        let rgba = p.image.to_rgba8();
        assert_eq!(rgba.get_pixel(10, 10).0, [0, 0, 255, 255]);
        assert_eq!(rgba.get_pixel(2000, 10).0[3], 0, "transparent where nothing is drawn");
        assert_eq!(p.metadata["tiff:ImageWidth"], json!(40));
    }

    #[test]
    fn svg_cannot_read_local_files() {
        let svg = br#"<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="10" height="10"><image width="10" height="10" xlink:href="/etc/hosts"/></svg>"#;
        let p = preview(svg, None).unwrap().unwrap();
        assert!(p.image.to_rgba8().pixels().all(|px| px.0[3] == 0), "the linked file is not drawn");
    }

    #[test]
    fn broken_files_are_unsupported_not_fatal() {
        assert!(matches!(preview(b"%PDF-1.7 garbage", None), Some(Err(DamError::Unsupported(_)))));
        assert!(matches!(preview(b"<svg><rect", Some("image/svg+xml")), Some(Err(DamError::Unsupported(_)))));
        assert!(preview(b"\x89PNG", None).is_none());
    }
}
