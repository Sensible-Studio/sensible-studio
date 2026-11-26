//! Video frames and previews through an `ffmpeg` installed on the machine (studio-dam itself
//! decodes no video). Without ffmpeg everything here answers "not ready" and a video is what it
//! was before: its metadata from the container, its thumbnail a frame a person picks.
//!
//! ffmpeg is run on files people uploaded, so it is kept on a short lead:
//! * only containers this service recognised itself (`video::sniff`), with the demuxer named on
//!   the command line, so a file is never read as a playlist or a script that points elsewhere;
//! * only the `file` and `pipe` protocols; no network;
//! * a time limit, after which the process is killed.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::OnceLock;
use std::time::{Duration, Instant};

use crate::error::DamError;

/// A frame is at most this wide (it becomes the source of the thumbnails and the web rendition).
const POSTER_WIDTH: u32 = 1920;
const POSTER_TIMEOUT: Duration = Duration::from_secs(60);
/// A preview is at most this tall, this long and this large.
const PREVIEW_HEIGHT: u32 = 720;
pub const PREVIEW_SECONDS: u32 = 30 * 60;
const PREVIEW_MAX_BYTES: &str = "400M";
pub const PREVIEW_TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// Where ffmpeg is: `STUDIO_DAM_FFMPEG`, else the `PATH`, else the usual places (a service
/// started by a desktop launcher often has a `PATH` without Homebrew's folder).
pub fn path() -> Option<&'static Path> {
    static FOUND: OnceLock<Option<PathBuf>> = OnceLock::new();
    FOUND
        .get_or_init(|| {
            if let Some(p) = std::env::var_os("STUDIO_DAM_FFMPEG") {
                let p = PathBuf::from(p);
                return if works(&p) { Some(p) } else { None };
            }
            let mut dirs: Vec<PathBuf> = std::env::var_os("PATH").map(|p| std::env::split_paths(&p).collect()).unwrap_or_default();
            for d in ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/opt/local/bin"] {
                dirs.push(PathBuf::from(d));
            }
            let name = if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" };
            dirs.into_iter().map(|d| d.join(name)).find(|p| p.is_file() && works(p))
        })
        .as_deref()
}

fn works(p: &Path) -> bool {
    Command::new(p)
        .arg("-version")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

/// The demuxer for a container this service recognised, or "unsupported".
fn demuxer(mime: Option<&str>) -> Result<&'static str, DamError> {
    match mime {
        Some("video/mp4" | "video/quicktime") => Ok("mov"),
        Some("video/webm" | "video/x-matroska") => Ok("matroska"),
        other => Err(DamError::Unsupported(format!("no frames can be read from {}", other.unwrap_or("this file")))),
    }
}

fn base(ffmpeg: &Path) -> Command {
    let mut c = Command::new(ffmpeg);
    c.args(["-nostdin", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file,pipe"]);
    c.stdin(Stdio::null());
    c
}

/// Runs to the end or to the time limit; the output on success, ffmpeg's last words otherwise.
fn run(mut c: Command, limit: Duration, max_out: usize) -> Result<Vec<u8>, DamError> {
    let mut child = c.stdout(Stdio::piped()).stderr(Stdio::piped()).spawn()?;
    let mut stdout = child.stdout.take().expect("piped");
    let mut stderr = child.stderr.take().expect("piped");
    // both pipes are drained while waiting, or a talkative ffmpeg would block on a full pipe
    let out = std::thread::spawn(move || {
        let mut v = Vec::new();
        let _ = stdout.by_ref().take(max_out as u64 + 1).read_to_end(&mut v);
        v
    });
    let err = std::thread::spawn(move || {
        let mut v = Vec::new();
        let _ = stderr.by_ref().take(16 * 1024).read_to_end(&mut v);
        v
    });
    let started = Instant::now();
    let status = loop {
        match child.try_wait()? {
            Some(s) => break s,
            None if started.elapsed() > limit => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(DamError::Unsupported(format!("ffmpeg did not finish within {} s", limit.as_secs())));
            }
            None => std::thread::sleep(Duration::from_millis(40)),
        }
    };
    let out = out.join().unwrap_or_default();
    let err = String::from_utf8_lossy(&err.join().unwrap_or_default()).trim().to_string();
    if !status.success() {
        let last = err.lines().last().unwrap_or("ffmpeg failed").to_string();
        return Err(DamError::Unsupported(last));
    }
    if out.len() > max_out {
        return Err(DamError::TooLarge);
    }
    Ok(out)
}

/// A frame of the video as a JPEG, taken `at` seconds in (the first frame when the video is
/// shorter than that).
pub fn poster(input: &Path, mime: Option<&str>, at: f64) -> Result<Vec<u8>, DamError> {
    let ffmpeg = path().ok_or_else(|| DamError::NotReady("ffmpeg is not installed".into()))?;
    let demuxer = demuxer(mime)?;
    let scale = format!("scale='min({POSTER_WIDTH},iw)':-2");
    let frame = |at: f64| {
        let mut c = base(ffmpeg);
        c.args(["-ss", &format!("{:.3}", at.max(0.0)), "-f", demuxer, "-i"]).arg(input);
        c.args(["-map", "0:v:0", "-frames:v", "1", "-vf", &scale, "-c:v", "mjpeg", "-q:v", "3", "-f", "image2pipe", "pipe:1"]);
        run(c, POSTER_TIMEOUT, 32 * 1024 * 1024)
    };
    match frame(at) {
        Ok(jpeg) if !jpeg.is_empty() => Ok(jpeg),
        // past the end, or a container that cannot seek: the first frame it is
        _ if at > 0.0 => frame(0.0).and_then(|j| if j.is_empty() { Err(DamError::Unsupported("the video has no frame to show".into())) } else { Ok(j) }),
        Ok(_) => Err(DamError::Unsupported("the video has no frame to show".into())),
        Err(e) => Err(e),
    }
}

/// What a video is made into: the container and codecs, and the most lines it keeps.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Format {
    /// H.264 and AAC in MP4: plays everywhere
    Mp4,
    /// VP9 and Opus in WebM: smaller, plays in every current browser
    Webm,
}

impl Format {
    pub fn parse(s: &str) -> Option<Format> {
        match s {
            "mp4" => Some(Format::Mp4),
            "webm" => Some(Format::Webm),
            _ => None,
        }
    }

    pub fn mime(self) -> &'static str {
        match self {
            Format::Mp4 => "video/mp4",
            Format::Webm => "video/webm",
        }
    }

    pub fn extension(self) -> &'static str {
        match self {
            Format::Mp4 => "mp4",
            Format::Webm => "webm",
        }
    }
}

/// Lines an encoding may keep: from a small thumbnail clip to 4K.
pub const MIN_HEIGHT: u32 = 144;
pub const MAX_HEIGHT: u32 = 2160;

/// The video as an MP4 any browser plays (H.264 and AAC, 720 lines at most, the first
/// [`PREVIEW_SECONDS`]), written to `output`.
pub fn preview(input: &Path, mime: Option<&str>, output: &Path) -> Result<(), DamError> {
    encode(input, mime, output, Format::Mp4, PREVIEW_HEIGHT)
}

/// The video in `format`, at most `height` lines (never enlarged), the first [`PREVIEW_SECONDS`],
/// written to `output`.
pub fn encode(input: &Path, mime: Option<&str>, output: &Path, format: Format, height: u32) -> Result<(), DamError> {
    let ffmpeg = path().ok_or_else(|| DamError::NotReady("ffmpeg is not installed".into()))?;
    let demuxer = demuxer(mime)?;
    let height = height.clamp(MIN_HEIGHT, MAX_HEIGHT);
    let mut c = base(ffmpeg);
    c.args(["-f", demuxer, "-i"]).arg(input);
    c.args(["-map", "0:v:0", "-map", "0:a:0?", "-t", &PREVIEW_SECONDS.to_string()]);
    // even sizes (both codecs need them), never larger than the source
    c.args(["-vf", &format!("scale=-2:'min({height},ih)':force_divisible_by=2")]);
    match format {
        Format::Mp4 => {
            c.args(["-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p"]);
            c.args(["-c:a", "aac", "-b:a", "128k", "-ac", "2", "-movflags", "+faststart"]);
        }
        Format::Webm => {
            c.args(["-c:v", "libvpx-vp9", "-crf", "34", "-b:v", "0", "-deadline", "good", "-cpu-used", "4", "-row-mt", "1", "-pix_fmt", "yuv420p"]);
            c.args(["-c:a", "libopus", "-b:a", "96k", "-ac", "2"]);
        }
    }
    c.args(["-fs", PREVIEW_MAX_BYTES, "-f", format.extension(), "-y"]).arg(output);
    run(c, PREVIEW_TIMEOUT, 1024).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A two-second test video made by ffmpeg itself; None where ffmpeg is not installed.
    fn sample(dir: &Path, name: &str, args: &[&str]) -> Option<PathBuf> {
        let ffmpeg = path()?;
        let out = dir.join(name);
        let ok = Command::new(ffmpeg)
            .args(["-nostdin", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=2:size=320x180:rate=10"])
            .args(args)
            .arg(&out)
            .status()
            .map(|s| s.success())
            .unwrap_or(false);
        ok.then_some(out)
    }

    #[test]
    fn a_frame_and_a_preview_come_out_of_a_video() {
        let dir = tempfile::tempdir().unwrap();
        let Some(mkv) = sample(dir.path(), "a.mkv", &["-c:v", "mpeg4"]) else {
            eprintln!("ffmpeg is not installed: skipped");
            return;
        };
        let bytes = std::fs::read(&mkv).unwrap();
        let mime = crate::video::sniff(&bytes);
        assert_eq!(mime, Some("video/x-matroska"));
        let jpeg = poster(&mkv, mime, 1.0).unwrap();
        let img = image::load_from_memory(&jpeg).unwrap();
        assert_eq!((img.width(), img.height()), (320, 180));
        // asked for a moment after the end: the first frame, not an error
        assert!(!poster(&mkv, mime, 60.0).unwrap().is_empty());

        let out = dir.path().join("preview.mp4");
        preview(&mkv, mime, &out).unwrap();
        let made = std::fs::read(&out).unwrap();
        assert_eq!(crate::video::sniff(&made), Some("video/mp4"));
        let md = crate::video::metadata(&made);
        assert_eq!(md["xmpDM:videoCompressor"], serde_json::json!("H.264 (avc1)"));
    }

    #[test]
    fn a_video_is_encoded_as_webm_at_a_height() {
        if path().is_none() {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let Some(mp4) = sample(dir.path(), "a.mp4", &["-c:v", "libx264", "-pix_fmt", "yuv420p"]) else {
            return;
        };
        let out = dir.path().join("out.webm");
        encode(&mp4, Some("video/mp4"), &out, Format::Webm, 144).unwrap();
        let bytes = std::fs::read(&out).unwrap();
        assert_eq!(&bytes[..4], &[0x1a, 0x45, 0xdf, 0xa3], "an EBML (WebM) file");
        let md = crate::video::metadata(&bytes);
        assert_eq!(md.get("tiff:ImageLength").and_then(|v| v.as_u64()), Some(144), "{md:?}");
        assert_eq!(Format::parse("mov"), None);
    }

    #[test]
    fn only_recognised_containers_are_handed_to_ffmpeg() {
        let dir = tempfile::tempdir().unwrap();
        let list = dir.path().join("list.m3u8");
        std::fs::write(&list, "#EXTM3U\n#EXTINF:1,\nfile:///etc/hostname\n").unwrap();
        // whatever it says it is, a file this service did not recognise is refused before ffmpeg sees it
        for mime in [None, Some("application/vnd.apple.mpegurl"), Some("video/x-msvideo")] {
            match poster(&list, mime, 0.0) {
                Err(DamError::Unsupported(_)) | Err(DamError::NotReady(_)) => {}
                other => panic!("{mime:?}: {other:?}"),
            }
        }
        // and named as a container it is not, ffmpeg reads it as that container and finds nothing
        if path().is_some() {
            assert!(poster(&list, Some("video/mp4"), 0.0).is_err());
        }
    }
}
