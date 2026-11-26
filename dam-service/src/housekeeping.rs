//! Keeping the disk in check: the delivery cache has a size and age limit, and blobs nobody
//! refers to any more are removed from the blob store.
//!
//! Why blobs pile up: renditions are written here, then Sensible Studio copies them into the repository, so
//! after processing they are only needed again if Sensible Studio asks for them in the next minutes; images
//! sent for Smart Tags and replaced originals are not needed at all. Only Sensible Studio knows which
//! originals are still in use (`studio:blobId` on each asset), so it sends that list and we remove
//! the rest, but only what is older than a grace period: a blob uploaded a second ago is not on
//! the list yet.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use serde::Serialize;

#[derive(Debug, Default, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct Sweep {
    /// files looked at
    pub files: u64,
    /// their bytes, before removing anything
    pub bytes: u64,
    pub removed: u64,
    pub freed: u64,
    /// unfinished temp files left by a crash
    pub temp_removed: u64,
}

/// Temp files (`.<name>.<pid>.<n>.tmp`) older than this are leftovers of a crash.
const TEMP_GRACE: Duration = Duration::from_secs(3600);

struct Entry {
    path: PathBuf,
    len: u64,
    modified: SystemTime,
}

/// Every file under `root`, up to `depth` levels of directories down.
fn files(root: &Path, depth: usize, out: &mut Vec<Entry>) {
    let Ok(dir) = std::fs::read_dir(root) else { return };
    for e in dir.flatten() {
        let Ok(meta) = e.metadata() else { continue };
        if meta.is_dir() {
            if depth > 0 {
                files(&e.path(), depth - 1, out);
            }
        } else if meta.is_file() {
            let modified = meta.modified().unwrap_or(SystemTime::UNIX_EPOCH);
            out.push(Entry { path: e.path(), len: meta.len(), modified });
        }
    }
}

fn is_temp(p: &Path) -> bool {
    p.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with('.') && n.ends_with(".tmp"))
}

fn age(e: &Entry, now: SystemTime) -> Duration {
    now.duration_since(e.modified).unwrap_or_default()
}

fn remove(e: &Entry, sweep: &mut Sweep) {
    if std::fs::remove_file(&e.path).is_ok() {
        sweep.removed += 1;
        sweep.freed += e.len;
    }
}

/// The delivery cache (`<cache>/<2 hex>/<key>.<ext>`): entries not used for `max_age` go, then
/// the least recently used ones until the cache is under 90% of `max_bytes`. A hit refreshes the
/// entry's modification time (see [`touch`]), so "least recently used" is by mtime.
pub fn sweep_cache(cache: &Path, max_bytes: u64, max_age: Duration) -> Sweep {
    let now = SystemTime::now();
    let mut all = Vec::new();
    files(cache, 1, &mut all);
    let mut sweep = Sweep { files: all.len() as u64, bytes: all.iter().map(|e| e.len).sum(), ..Sweep::default() };
    let mut kept = Vec::with_capacity(all.len());
    for e in all {
        if is_temp(&e.path) {
            if age(&e, now) > TEMP_GRACE && std::fs::remove_file(&e.path).is_ok() {
                sweep.temp_removed += 1;
            }
        } else if age(&e, now) > max_age {
            remove(&e, &mut sweep);
        } else {
            kept.push(e);
        }
    }
    let mut size: u64 = kept.iter().map(|e| e.len).sum();
    if size > max_bytes {
        let target = max_bytes / 10 * 9;
        kept.sort_by_key(|e| e.modified);
        for e in &kept {
            if size <= target {
                break;
            }
            remove(e, &mut sweep);
            size = size.saturating_sub(e.len);
        }
    }
    sweep
}

/// Marks a cache entry as used now, at most once a day per entry (a hit should not cost a
/// write each time).
pub fn touch(path: &Path) {
    let fresh = std::fs::metadata(path)
        .and_then(|m| m.modified())
        .map(|t| t.elapsed().unwrap_or_default() < Duration::from_secs(86_400))
        .unwrap_or(true);
    if !fresh {
        if let Ok(f) = std::fs::File::options().write(true).open(path) {
            let _ = f.set_modified(SystemTime::now());
        }
    }
}

/// Removes from each blob store (Oak layout, `ab/cd/ef/<sha256>`) the blobs not in `keep` and
/// older than `grace`. With `dry_run` nothing is removed; `removed`/`freed` say what would be.
pub fn gc_blobs(stores: &[&Path], keep: &HashSet<String>, grace: Duration, dry_run: bool) -> Sweep {
    let now = SystemTime::now();
    let mut sweep = Sweep::default();
    let mut seen = HashSet::new();
    for root in stores {
        // --datastore and --output are usually the same directory
        let Ok(canonical) = std::fs::canonicalize(root) else { continue };
        if !seen.insert(canonical) {
            continue;
        }
        let mut all = Vec::new();
        files(root, 3, &mut all);
        for e in all {
            sweep.files += 1;
            sweep.bytes += e.len;
            if age(&e, now) <= grace {
                continue;
            }
            if is_temp(&e.path) {
                if age(&e, now) > TEMP_GRACE && !dry_run && std::fs::remove_file(&e.path).is_ok() {
                    sweep.temp_removed += 1;
                }
                continue;
            }
            let name = e.path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
            // only what is laid out as a blob: anything else in the directory is not ours to judge
            let blob = name.len() == 64 && name.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
            if !blob || keep.contains(name) {
                continue;
            }
            if dry_run {
                sweep.removed += 1;
                sweep.freed += e.len;
            } else {
                remove(&e, &mut sweep);
            }
        }
    }
    sweep
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::blobstore::BlobStore;

    fn age_file(p: &Path, by: Duration) {
        let f = std::fs::File::options().write(true).open(p).unwrap();
        f.set_modified(SystemTime::now() - by).unwrap();
    }

    const DAY: Duration = Duration::from_secs(86_400);

    #[test]
    fn cache_drops_old_entries_then_the_least_recently_used() {
        let dir = tempfile::tempdir().unwrap();
        let sub = dir.path().join("ab");
        std::fs::create_dir_all(&sub).unwrap();
        for (i, days) in [40u64, 3, 2, 1, 0].iter().enumerate() {
            let p = sub.join(format!("{i}.jpg"));
            std::fs::write(&p, vec![0u8; 100]).unwrap();
            age_file(&p, DAY * *days as u32);
        }
        let tmp = sub.join(".cache.1.1.tmp");
        std::fs::write(&tmp, b"x").unwrap();
        age_file(&tmp, DAY);

        // 400 bytes left after the 40-day one; limit 300 → down to 270: drop the 3- and 2-day ones
        let s = sweep_cache(dir.path(), 300, DAY * 30);
        assert_eq!((s.files, s.removed, s.freed, s.temp_removed), (6, 3, 300, 1));
        let left: Vec<_> = (0..5).filter(|i| sub.join(format!("{i}.jpg")).exists()).collect();
        assert_eq!(left, [3, 4]);
        assert_eq!(sweep_cache(dir.path(), 300, DAY * 30).removed, 0, "nothing more to do");
    }

    #[test]
    fn touch_refreshes_an_old_entry() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("x.jpg");
        std::fs::write(&p, b"x").unwrap();
        age_file(&p, DAY * 5);
        touch(&p);
        let m = std::fs::metadata(&p).unwrap().modified().unwrap();
        assert!(m.elapsed().unwrap() < Duration::from_secs(60));
    }

    #[test]
    fn gc_keeps_what_is_referenced_or_recent() {
        let dir = tempfile::tempdir().unwrap();
        let store = BlobStore::new(dir.path());
        let used = store.put(b"original in use").unwrap();
        let old = store.put(b"rendition copied into the repository").unwrap();
        let recent = store.put(b"uploaded a second ago").unwrap();
        for id in [&used, &old] {
            age_file(&store.path_of(id), DAY * 5);
        }
        let stray = dir.path().join("README");
        std::fs::write(&stray, b"not a blob").unwrap();
        age_file(&stray, DAY * 5);
        let keep: HashSet<String> = [used.to_string()].into();

        let dry = gc_blobs(&[dir.path(), dir.path()], &keep, DAY, true);
        assert_eq!((dry.files, dry.removed), (4, 1), "the same directory twice is swept once");
        assert!(store.exists(&old));

        let s = gc_blobs(&[dir.path()], &keep, DAY, false);
        assert_eq!(s.removed, 1);
        assert!(store.exists(&used) && store.exists(&recent) && !store.exists(&old) && stray.exists());
    }
}
