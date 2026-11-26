//! Previews of Office documents (Word, Excel, PowerPoint, OpenDocument, RTF): LibreOffice turns
//! the file into a PDF, whose first page then becomes the preview like any PDF.
//!
//! LibreOffice is not shipped: like ffmpeg it is a prerequisite of the machine, found through
//! `STUDIO_DAM_SOFFICE`, the `PATH` or the usual places. Without it these files keep no preview,
//! as before.
//!
//! Safety: every conversion runs headless with a fresh, throw-away profile (macros stay at the
//! default "never run"), in its own temporary folder, under a time limit after which the whole
//! process group is killed; the input and the PDF it gives are bounded in size.

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use crate::DamError;

/// Longest a conversion may take.
pub const TIMEOUT: Duration = Duration::from_secs(120);
/// Largest document converted.
pub const MAX_INPUT: usize = 100 * 1024 * 1024;
/// Largest PDF accepted back.
const MAX_PDF: u64 = 200 * 1024 * 1024;

/// An Office document this service can preview: its MIME type and the extension LibreOffice
/// expects.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Kind {
    pub mime: &'static str,
    pub ext: &'static str,
    pub label: &'static str,
}

const DOCX: Kind = Kind { mime: "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ext: "docx", label: "Word" };
const XLSX: Kind = Kind { mime: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ext: "xlsx", label: "Excel" };
const PPTX: Kind = Kind { mime: "application/vnd.openxmlformats-officedocument.presentationml.presentation", ext: "pptx", label: "PowerPoint" };
const DOC: Kind = Kind { mime: "application/msword", ext: "doc", label: "Word" };
const XLS: Kind = Kind { mime: "application/vnd.ms-excel", ext: "xls", label: "Excel" };
const PPT: Kind = Kind { mime: "application/vnd.ms-powerpoint", ext: "ppt", label: "PowerPoint" };
const ODT: Kind = Kind { mime: "application/vnd.oasis.opendocument.text", ext: "odt", label: "OpenDocument Text" };
const ODS: Kind = Kind { mime: "application/vnd.oasis.opendocument.spreadsheet", ext: "ods", label: "OpenDocument Spreadsheet" };
const ODP: Kind = Kind { mime: "application/vnd.oasis.opendocument.presentation", ext: "odp", label: "OpenDocument Presentation" };
const RTF: Kind = Kind { mime: "application/rtf", ext: "rtf", label: "Rich Text" };

/// What the bytes are, when they are an Office document (by their content; the hint only tells
/// the old binary formats apart when their streams do not).
pub fn kind(bytes: &[u8], hint: Option<&str>) -> Option<Kind> {
    if bytes.starts_with(b"{\\rtf") {
        return Some(RTF);
    }
    if bytes.starts_with(b"PK\x03\x04") {
        // OpenDocument: an uncompressed "mimetype" entry comes first
        if bytes.len() > 38 && &bytes[30..38] == b"mimetype" {
            let rest = &bytes[38..bytes.len().min(38 + 80)];
            for k in [ODT, ODS, ODP] {
                if rest.starts_with(k.mime.as_bytes()) {
                    return Some(k);
                }
            }
            return None;
        }
        // Office Open XML: the part names are in the central directory, at the end
        let tail = &bytes[bytes.len().saturating_sub(256 * 1024)..];
        for (name, k) in [(&b"word/document.xml"[..], DOCX), (b"xl/workbook.xml", XLSX), (b"ppt/presentation.xml", PPTX)] {
            if find(tail, name) || find(&bytes[..bytes.len().min(256 * 1024)], name) {
                return Some(k);
            }
        }
        return None;
    }
    if bytes.starts_with(&[0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]) {
        // a compound file: its stream names (UTF-16) tell which program wrote it
        let utf16 = |s: &str| s.encode_utf16().flat_map(|u| u.to_le_bytes()).collect::<Vec<u8>>();
        for (stream, k) in [("WordDocument", DOC), ("Workbook", XLS), ("PowerPoint Document", PPT), ("Book", XLS)] {
            if find(bytes, &utf16(stream)) {
                return Some(k);
            }
        }
        return match hint {
            Some("application/msword") => Some(DOC),
            Some("application/vnd.ms-excel") => Some(XLS),
            Some("application/vnd.ms-powerpoint") => Some(PPT),
            _ => None,
        };
    }
    None
}

fn find(hay: &[u8], needle: &[u8]) -> bool {
    !needle.is_empty() && hay.windows(needle.len()).any(|w| w == needle)
}

/// Where LibreOffice is: `STUDIO_DAM_SOFFICE`, else the `PATH`, else the usual places.
pub fn path() -> Option<&'static Path> {
    static FOUND: OnceLock<Option<PathBuf>> = OnceLock::new();
    FOUND
        .get_or_init(|| {
            if let Some(p) = std::env::var_os("STUDIO_DAM_SOFFICE") {
                let p = PathBuf::from(p);
                return if p.is_file() { Some(p) } else { None };
            }
            let mut candidates: Vec<PathBuf> = Vec::new();
            if let Some(path) = std::env::var_os("PATH") {
                for d in std::env::split_paths(&path) {
                    candidates.push(d.join("soffice"));
                    candidates.push(d.join("libreoffice"));
                }
            }
            for p in [
                "/Applications/LibreOffice.app/Contents/MacOS/soffice",
                "/opt/homebrew/bin/soffice",
                "/usr/local/bin/soffice",
                "/usr/bin/soffice",
                "/usr/bin/libreoffice",
                "/usr/lib/libreoffice/program/soffice",
                "/opt/libreoffice/program/soffice",
                "/snap/bin/libreoffice",
            ] {
                candidates.push(PathBuf::from(p));
            }
            candidates.into_iter().find(|p| p.is_file())
        })
        .as_deref()
}

/// The document as a PDF.
pub fn to_pdf(bytes: &[u8], kind: Kind) -> Result<Vec<u8>, DamError> {
    let soffice = path().ok_or_else(|| DamError::NotReady("LibreOffice is not installed".into()))?;
    if bytes.len() > MAX_INPUT {
        return Err(DamError::Unsupported(format!("{} documents over {} MB get no preview", kind.label, MAX_INPUT / 1024 / 1024)));
    }
    let dir = Scratch::new()?;
    let input = dir.0.join(format!("document.{}", kind.ext));
    std::fs::write(&input, bytes)?;
    let profile = dir.0.join("profile");
    let out = dir.0.join("out");
    std::fs::create_dir_all(&out)?;
    let mut c = Command::new(soffice);
    c.arg(format!("-env:UserInstallation={}", file_url(&profile)))
        .args(["--headless", "--invisible", "--nodefault", "--nolockcheck", "--nologo", "--norestore", "--nofirststartwizard"])
        .args(["--convert-to", "pdf", "--outdir"])
        .arg(&out)
        .arg(&input)
        .current_dir(&dir.0)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        c.process_group(0);
    }
    let mut child = c.spawn()?;
    let started = Instant::now();
    let status = loop {
        match child.try_wait()? {
            Some(s) => break s,
            None if started.elapsed() > TIMEOUT => {
                kill_group(&mut child);
                return Err(DamError::Unsupported(format!("LibreOffice did not finish within {} s", TIMEOUT.as_secs())));
            }
            None => std::thread::sleep(Duration::from_millis(50)),
        }
    };
    let pdf = out.join("document.pdf");
    if !status.success() || !pdf.is_file() {
        return Err(DamError::Unsupported(format!("LibreOffice could not read this {} document", kind.label)));
    }
    if std::fs::metadata(&pdf)?.len() > MAX_PDF {
        return Err(DamError::TooLarge);
    }
    Ok(std::fs::read(&pdf)?)
}

fn kill_group(child: &mut std::process::Child) {
    #[cfg(unix)]
    {
        // the helper processes LibreOffice starts are in the same group
        let _ = Command::new("kill").arg("-9").arg(format!("-{}", child.id())).status();
    }
    let _ = child.kill();
    let _ = child.wait();
}

fn file_url(p: &Path) -> String {
    let s = p.to_string_lossy().replace('\\', "/");
    let s = s.replace(' ', "%20");
    if s.starts_with('/') {
        format!("file://{s}")
    } else {
        format!("file:///{s}")
    }
}

/// A temporary folder removed when dropped.
struct Scratch(PathBuf);

impl Scratch {
    fn new() -> Result<Self, DamError> {
        use std::sync::atomic::{AtomicU64, Ordering};
        static N: AtomicU64 = AtomicU64::new(0);
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let p = std::env::temp_dir().join(format!("studio-office-{}-{}-{}", std::process::id(), nanos, N.fetch_add(1, Ordering::Relaxed)));
        std::fs::create_dir_all(&p)?;
        Ok(Scratch(p))
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Enough of a ZIP for the sniffing: a local header naming the first entry, then the names
    /// of the others as the central directory has them.
    fn zip(first: &str, first_data: &[u8], others: &[&str]) -> Vec<u8> {
        let mut v = b"PK\x03\x04".to_vec();
        v.extend([0u8; 22]);
        v.extend((first.len() as u16).to_le_bytes());
        v.extend([0u8; 2]);
        v.extend(first.as_bytes());
        v.extend(first_data);
        for o in others {
            v.extend(b"PK\x01\x02");
            v.extend([0u8; 42]);
            v.extend(o.as_bytes());
        }
        v
    }

    #[test]
    fn office_documents_are_told_by_their_content() {
        let docx = zip("[Content_Types].xml", b"<Types/>", &["word/document.xml"]);
        assert_eq!(kind(&docx, None), Some(DOCX));
        let xlsx = zip("[Content_Types].xml", b"<Types/>", &["xl/workbook.xml"]);
        assert_eq!(kind(&xlsx, Some("application/zip")), Some(XLSX));
        let pptx = zip("ppt/presentation.xml", b"<p/>", &[]);
        assert_eq!(kind(&pptx, None), Some(PPTX));
        let odt = zip("mimetype", b"application/vnd.oasis.opendocument.text", &["content.xml"]);
        assert_eq!(kind(&odt, None), Some(ODT));
        let plain = zip("readme.txt", b"hello", &[]);
        assert_eq!(kind(&plain, None), None, "an ordinary ZIP is not a document");
        assert_eq!(kind(b"{\\rtf1\\ansi hello}", None), Some(RTF));
        let mut ole = vec![0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1];
        ole.extend(vec![0; 64]);
        assert_eq!(kind(&ole, Some("application/vnd.ms-powerpoint")), Some(PPT));
        ole.extend("Workbook".encode_utf16().flat_map(|u| u.to_le_bytes()));
        assert_eq!(kind(&ole, Some("application/msword")), Some(XLS), "the streams win over the hint");
        assert_eq!(kind(b"%PDF-1.7", None), None);
        assert_eq!(kind(b"\x89PNG\r\n", None), None);
    }

    #[test]
    fn a_document_becomes_a_pdf_when_libreoffice_is_there() {
        if path().is_none() {
            eprintln!("LibreOffice is not installed: conversion not tested");
            return;
        }
        let pdf = to_pdf(b"{\\rtf1\\ansi Hello from a test}", RTF).unwrap();
        assert!(pdf.starts_with(b"%PDF-"));
    }
}
