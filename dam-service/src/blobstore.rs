//! Content-addressed blob store using the Oak FileDataStore directory layout:
//! `<root>/<h[0..2]>/<h[2..4]>/<h[4..6]>/<sha256-hex>`.
//!
//! Oak blob ids may carry a length suffix (`<hex>#<length>`); it is accepted
//! and ignored. Anything that is not 64 lowercase hex characters is rejected,
//! which also rules out path traversal.

use std::io::Write;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::DamError;

#[derive(Clone, Debug)]
pub struct BlobStore {
    root: PathBuf,
}

/// A validated SHA-256 blob id.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct BlobId(String);

impl BlobId {
    pub fn parse(raw: &str) -> Result<Self, DamError> {
        let hex_part = raw.split('#').next().unwrap_or_default();
        let ok = hex_part.len() == 64 && hex_part.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
        if ok {
            Ok(BlobId(hex_part.to_string()))
        } else {
            Err(DamError::BadRequest("blob id must be a sha-256 hex digest".into()))
        }
    }

    pub fn of(bytes: &[u8]) -> Self {
        BlobId(hex::encode(Sha256::digest(bytes)))
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Display for BlobId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// A blob read with [`BlobStore::read_mapped`].
pub enum Blob {
    Mapped(memmap2::Mmap),
    Empty,
}

impl std::ops::Deref for Blob {
    type Target = [u8];
    fn deref(&self) -> &[u8] {
        match self {
            Blob::Mapped(m) => m,
            Blob::Empty => &[],
        }
    }
}

impl BlobStore {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        BlobStore { root: root.into() }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    pub fn path_of(&self, id: &BlobId) -> PathBuf {
        let h = id.as_str();
        self.root.join(&h[0..2]).join(&h[2..4]).join(&h[4..6]).join(h)
    }

    pub fn exists(&self, id: &BlobId) -> bool {
        self.path_of(id).is_file()
    }

    pub fn read(&self, id: &BlobId, max_bytes: u64) -> Result<Vec<u8>, DamError> {
        let path = self.path_of(id);
        let meta = std::fs::metadata(&path).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => DamError::NotFound,
            _ => DamError::Io(e),
        })?;
        if meta.len() > max_bytes {
            return Err(DamError::TooLarge);
        }
        Ok(std::fs::read(path)?)
    }

    /// Like [`read`](Self::read), but maps the file instead of copying it into memory: a
    /// 400 MB video original costs page cache, not heap. Blob files are never modified in place
    /// (new content is written to a temp file and renamed), so the mapping cannot change under
    /// us; deleting the file (blob GC) leaves an existing mapping valid on every OS we run on.
    pub fn read_mapped(&self, id: &BlobId, max_bytes: u64) -> Result<Blob, DamError> {
        let path = self.path_of(id);
        let file = std::fs::File::open(&path).map_err(|e| match e.kind() {
            std::io::ErrorKind::NotFound => DamError::NotFound,
            _ => DamError::Io(e),
        })?;
        let len = file.metadata()?.len();
        if len > max_bytes {
            return Err(DamError::TooLarge);
        }
        if len == 0 {
            return Ok(Blob::Empty);
        }
        // SAFETY: see above; the file is immutable once stored.
        let map = unsafe { memmap2::Mmap::map(&file)? };
        Ok(Blob::Mapped(map))
    }

    /// Store bytes under their SHA-256. Writes to a temp file and renames, so a
    /// concurrent reader never sees a partial blob. Existing blobs are kept.
    pub fn put(&self, bytes: &[u8]) -> Result<BlobId, DamError> {
        let id = BlobId::of(bytes);
        let path = self.path_of(&id);
        if path.is_file() {
            // Fresh again: blob GC spares recent blobs, and this one is about to be referred to.
            if let Ok(f) = std::fs::File::options().write(true).open(&path) {
                let _ = f.set_modified(std::time::SystemTime::now());
            }
            return Ok(id);
        }
        let dir = path.parent().expect("blob path has a parent");
        std::fs::create_dir_all(dir)?;
        // Unique per call: two uploads of the same content must not share a temp file.
        let tmp = dir.join(crate::server::unique_tmp_name(id.as_str()));
        {
            let mut f = std::fs::File::create(&tmp)?;
            f.write_all(bytes)?;
            f.sync_all()?;
        }
        if let Err(e) = std::fs::rename(&tmp, &path) {
            let _ = std::fs::remove_file(&tmp);
            // Someone else stored the same content first: same bytes, so that is success.
            if !path.is_file() {
                return Err(e.into());
            }
        }
        Ok(id)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_traversal_and_bad_ids() {
        assert!(BlobId::parse("../../etc/passwd").is_err());
        assert!(BlobId::parse("ABCDEF").is_err());
        assert!(BlobId::parse(&"G".repeat(64)).is_err());
        assert!(BlobId::parse(&"A".repeat(64)).is_err(), "uppercase is not the Oak layout");
    }

    #[test]
    fn accepts_oak_length_suffix() {
        let h = "6132".to_string() + &"0".repeat(60);
        assert_eq!(BlobId::parse(&format!("{h}#12345")).unwrap().as_str(), h);
    }

    #[test]
    fn put_then_read_uses_oak_layout() {
        let dir = tempfile::tempdir().unwrap();
        let store = BlobStore::new(dir.path());
        let id = store.put(b"hello").unwrap();
        let h = id.as_str();
        assert!(dir.path().join(&h[0..2]).join(&h[2..4]).join(&h[4..6]).join(h).is_file());
        assert_eq!(store.read(&id, 100).unwrap(), b"hello");
        assert!(matches!(store.read(&id, 2), Err(DamError::TooLarge)));
        assert_eq!(store.put(b"hello").unwrap(), id, "idempotent");
        assert_eq!(&*store.read_mapped(&id, 100).unwrap(), b"hello");
        assert!(matches!(store.read_mapped(&id, 2), Err(DamError::TooLarge)));
        let empty = store.put(b"").unwrap();
        assert!(store.read_mapped(&empty, 2).unwrap().is_empty());
        assert!(matches!(store.read_mapped(&BlobId::of(b"nope"), 9), Err(DamError::NotFound)));
    }
}
