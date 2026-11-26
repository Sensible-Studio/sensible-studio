//! The cache, in three directories side by side:
//!
//! - the **document root**: plain files named like the request path, which Apache serves without
//!   asking anyone. Only answers a file can stand for go here: the type its extension says, and no
//!   header of their own worth keeping;
//! - `<docroot>.held`: answers that carry headers of their own (a download's `Content-Disposition`,
//!   a type the extension does not say). Apache finds no file and asks the service, which answers
//!   from here with those headers, without asking the publish;
//! - `<docroot>.stale`: what an invalidation took out of the other two, kept for a while in case
//!   the publish cannot be asked when the page is wanted again.
//!
//! Invalidation moves or removes files; nothing is compared at request time.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::RwLock;
use std::time::{Duration, SystemTime};

use crate::url::{cache_file, glob, relative};

/// At most this many paths are told of one invalidation; past it, the list says it is not whole.
pub const MAX_TAKEN: usize = 10_000;

thread_local! {
    /// While set: the request paths of the files an invalidation on this thread takes out, and
    /// whether that is all of them.
    static TAKEN: std::cell::RefCell<Option<(Vec<String>, bool)>> = const { std::cell::RefCell::new(None) };
}

/// From now on, on this thread, note what invalidations take out (for `recorded`).
pub fn record_taken() {
    TAKEN.with(|t| *t.borrow_mut() = Some((Vec::new(), true)));
}

/// What was taken out since `record_taken`, sorted, each once, and whether that is all; stops
/// noting.
pub fn recorded() -> (Vec<String>, bool) {
    let (mut paths, whole) = TAKEN.with(|t| t.borrow_mut().take()).unwrap_or((Vec::new(), true));
    paths.sort();
    paths.dedup();
    (paths, whole)
}

fn note_taken(root: &Path, file: &Path) {
    TAKEN.with(|t| {
        if let Some((paths, whole)) = t.borrow_mut().as_mut() {
            if paths.len() >= MAX_TAKEN {
                *whole = false;
            } else if let Ok(rel) = file.strip_prefix(root) {
                let mut path = String::new();
                for part in rel.components() {
                    path.push('/');
                    path.push_str(&part.as_os_str().to_string_lossy());
                }
                paths.push(path);
            }
        }
    });
}

/// The folder, inside the folder of a held or stale file, where its headers are kept.
const HEADERS_DIR: &str = ".h";

pub struct Cache {
    docroot: PathBuf,
    held: PathBuf,
    /// `None`: invalidation removes, nothing is kept.
    stale: Option<PathBuf>,
    stat_level: usize,
    invalidate: Vec<String>,
    /// Counts invalidations. A response fetched before an invalidation must not be written after
    /// it: `store` compares the count it was given with the current one, under the lock that
    /// `invalidate` takes to raise it.
    generation: RwLock<u64>,
    /// When the last invalidation was (ms since the epoch; 0: none since the start).
    invalidated_at: AtomicU64,
    /// What each cached answer shows besides itself, as its publish said (`X-Studio-Depends`):
    /// relative file path → repository paths. Kept beside the cache (`<docroot>.deps`, one file per
    /// answer) and read back at the start. An answer without an entry shows, for all anyone knows,
    /// everything.
    deps: RwLock<std::collections::HashMap<String, Vec<String>>>,
    deps_dir: PathBuf,
    /// Until when an answer may be served, when its publish said (`Cache-Control: max-age`, a
    /// dispatcher's `/enableTTL`): relative file path → ms since the epoch. Kept beside the cache
    /// (`<docroot>.ttl`, one file per answer) and read back at the start; [`Cache::expire_due`]
    /// takes out what is over, which Apache would otherwise go on serving.
    expiry: RwLock<std::collections::HashMap<String, u64>>,
    expiry_dir: PathBuf,
    temp: AtomicU64,
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// Reads back until when the cached answers may be served.
fn read_expiry(base: &Path, dir: &Path, into: &mut std::collections::HashMap<String, u64>) {
    for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
        let path = entry.path();
        match fs::symlink_metadata(&path) {
            Ok(m) if m.is_dir() => read_expiry(base, &path, into),
            Ok(m) if m.is_file() => {
                if let (Ok(rel), Ok(text)) = (path.strip_prefix(base), fs::read_to_string(&path)) {
                    if let Ok(until) = text.trim().parse::<u64>() {
                        into.insert(rel.to_string_lossy().into_owned(), until);
                    }
                }
            }
            _ => {}
        }
    }
}

/// A cached answer. No headers: a plain file, typed by its extension.
#[derive(Debug, PartialEq)]
pub struct Entry {
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Action {
    Activate,
    Deactivate,
    Delete,
    Test,
}

impl Action {
    pub fn parse(name: &str) -> Option<Action> {
        match name.to_ascii_lowercase().as_str() {
            "activate" => Some(Action::Activate),
            "deactivate" => Some(Action::Deactivate),
            "delete" => Some(Action::Delete),
            "test" => Some(Action::Test),
            _ => None,
        }
    }
}

/// `<docroot>.held`, `<docroot>.stale`: beside the document root, never inside it.
/// Does what a page shows (`shown`) change when `handle` is published? The same path, or one below
/// it (a rendition of the asset), or the asset of a rendition that is published. Pages are said so
/// that only their own publication counts: `<page>/jcr:content` (its title, its link, shown in a
/// navigation or a teaser), and `<page>/*` for a list of its children, which a publication of a page
/// directly below it changes (one appears, goes, is renamed) and no other does.
pub(crate) fn shows(shown: &str, handle: &str) -> bool {
    if let Some(parent) = shown.strip_suffix("/*") {
        return handle.strip_prefix(parent).and_then(|rest| rest.strip_prefix('/')).is_some_and(|name| !name.is_empty() && !name.contains('/'));
    }
    shown == handle
        || shown.strip_prefix(handle).is_some_and(|rest| rest.starts_with('/'))
        || handle.strip_prefix(shown).is_some_and(|rest| rest.starts_with('/'))
}

/// Reads back what the cached answers show, from the files beside the cache.
fn read_deps(base: &Path, dir: &Path, into: &mut std::collections::HashMap<String, Vec<String>>) {
    for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
        let path = entry.path();
        match fs::symlink_metadata(&path) {
            Ok(m) if m.is_dir() => read_deps(base, &path, into),
            Ok(m) if m.is_file() => {
                if let (Ok(rel), Ok(text)) = (path.strip_prefix(base), fs::read_to_string(&path)) {
                    into.insert(rel.to_string_lossy().into_owned(), text.lines().filter(|l| !l.is_empty()).map(str::to_owned).collect());
                }
            }
            _ => {}
        }
    }
}

/// A directory beside the document root: the service's alone (mode 700), whatever group a setgid
/// cache directory hands down to the web server. Apache never reads these; the service answers
/// from them.
fn own_dir(dir: &Path) -> io::Result<()> {
    fs::create_dir_all(dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(dir, fs::Permissions::from_mode(0o700))?;
    }
    Ok(())
}

fn sibling(docroot: &Path, suffix: &str) -> PathBuf {
    let mut name = docroot.file_name().map(|n| n.to_os_string()).unwrap_or_default();
    name.push(suffix);
    docroot.with_file_name(name)
}

fn headers_file(file: &Path) -> Option<PathBuf> {
    Some(file.parent()?.join(HEADERS_DIR).join(file.file_name()?))
}

fn is_file(path: &Path) -> bool {
    fs::symlink_metadata(path).map(|m| m.is_file()).unwrap_or(false)
}

fn is_dir(path: &Path) -> bool {
    fs::symlink_metadata(path).map(|m| m.is_dir()).unwrap_or(false)
}

fn read_entry(file: &Path) -> Option<Entry> {
    if !is_file(file) {
        return None;
    }
    let body = fs::read(file).ok()?;
    let mut headers = Vec::new();
    if let Some(text) = headers_file(file).and_then(|h| fs::read_to_string(h).ok()) {
        for line in text.lines() {
            if let Some((name, value)) = line.split_once(": ") {
                headers.push((name.to_owned(), value.to_owned()));
            }
        }
    }
    Some(Entry { body, headers })
}

/// Removes a file and its headers. Counts the file.
fn remove_entry(file: &Path) -> usize {
    if let Some(h) = headers_file(file) {
        let _ = fs::remove_file(h);
    }
    fs::remove_file(file).map(|_| 1).unwrap_or(0)
}

impl Cache {
    pub fn new(docroot: PathBuf, stat_level: usize, invalidate: Vec<String>, keep_stale: bool) -> io::Result<Cache> {
        fs::create_dir_all(&docroot)?;
        let docroot = docroot.canonicalize()?;
        let held = sibling(&docroot, ".held");
        own_dir(&held)?;
        let stale = sibling(&docroot, ".stale");
        if keep_stale {
            own_dir(&stale)?;
        }
        let deps_dir = sibling(&docroot, ".deps");
        own_dir(&deps_dir)?;
        let mut deps = std::collections::HashMap::new();
        read_deps(&deps_dir, &deps_dir, &mut deps);
        let expiry_dir = sibling(&docroot, ".ttl");
        own_dir(&expiry_dir)?;
        let mut expiry = std::collections::HashMap::new();
        read_expiry(&expiry_dir, &expiry_dir, &mut expiry);
        Ok(Cache {
            expiry: RwLock::new(expiry),
            expiry_dir,
            docroot,
            held,
            stale: keep_stale.then_some(stale),
            stat_level,
            invalidate,
            generation: RwLock::new(0),
            invalidated_at: AtomicU64::new(0),
            deps: RwLock::new(deps),
            deps_dir,
            temp: AtomicU64::new(0),
        })
    }

    /// How many cached answers say what they show.
    pub fn tracked(&self) -> usize {
        self.deps.read().unwrap().len()
    }

    /// Until when the answer just stored for a path may be served (ms since the epoch), as its
    /// publish said; `None`: until a publication takes it out.
    pub fn set_expiry(&self, decoded_path: &str, until: Option<u64>) {
        let Some(rel) = cache_file(decoded_path) else { return };
        let key = rel.to_string_lossy().into_owned();
        let file = self.expiry_dir.join(&rel);
        match until {
            Some(until) => {
                let written = file.parent().is_some_and(|dir| fs::create_dir_all(dir).is_ok()) && fs::write(&file, until.to_string()).is_ok();
                let mut expiry = self.expiry.write().unwrap();
                if written {
                    expiry.insert(key, until);
                } else {
                    expiry.remove(&key);
                }
            }
            None => {
                if self.expiry.write().unwrap().remove(&key).is_some() {
                    let _ = fs::remove_file(&file);
                }
            }
        }
    }

    /// How many cached answers have a time of their own.
    pub fn timed(&self) -> usize {
        self.expiry.read().unwrap().len()
    }

    /// Is the answer to this file over its time?
    fn over(&self, key: &str, now: u64) -> bool {
        self.expiry.read().unwrap().get(key).is_some_and(|until| *until <= now)
    }

    /// Takes out the answers whose time is over (kept as stale, like a publication's); returns how
    /// many. Run often: until then Apache serves the file.
    pub fn expire_due(&self, now: u64) -> usize {
        let due: Vec<String> = self.expiry.read().unwrap().iter().filter(|(_, until)| **until <= now).map(|(key, _)| key.clone()).collect();
        let mut removed = 0;
        for key in due {
            for root in [&self.docroot, &self.held] {
                let path = root.join(&key);
                if is_file(&path) {
                    removed += self.take(root, &path, true);
                }
            }
            self.expiry.write().unwrap().remove(&key);
            let _ = fs::remove_file(self.expiry_dir.join(&key));
        }
        removed
    }

    /// As [`Cache::store`], with what the answer shows besides itself (`None`: not said).
    pub fn store_with(
        &self,
        decoded_path: &str,
        fetched_at_generation: u64,
        body: &[u8],
        headers: &[(String, String)],
        shows: Option<&[String]>,
    ) -> io::Result<bool> {
        let stored = self.store(decoded_path, fetched_at_generation, body, headers)?;
        if stored {
            if let Some(rel) = cache_file(decoded_path) {
                let key = rel.to_string_lossy().into_owned();
                let file = self.deps_dir.join(&rel);
                match shows {
                    Some(paths) => {
                        let written = file.parent().is_some_and(|dir| fs::create_dir_all(dir).is_ok())
                            && fs::write(&file, paths.join("\n")).is_ok();
                        let mut deps = self.deps.write().unwrap();
                        if written {
                            deps.insert(key, paths.to_vec());
                        } else {
                            deps.remove(&key);
                        }
                    }
                    None => {
                        let _ = fs::remove_file(&file);
                        self.deps.write().unwrap().remove(&key);
                    }
                }
            }
        }
        Ok(stored)
    }

    /// A publication of something pages show (an asset, an experience fragment): takes out its own
    /// files, the answers that say they show it, and the auto-invalidated files in scope that say
    /// nothing of what they show; not those that show other things. Otherwise as
    /// [`Cache::invalidate_listing`]. Also returns how many answers went because they show it.
    pub fn invalidate_dependents(&self, action: Action, handle: &str) -> Option<(usize, Vec<String>, usize)> {
        if action == Action::Test {
            return Some((0, Vec::new(), 0));
        }
        let rel = relative(handle)?;
        *self.generation.write().unwrap() += 1;
        let now = SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
        self.invalidated_at.store(now, Ordering::Relaxed);
        let unpublished = action != Action::Activate;
        let mut removed = 0;
        let mut own = Vec::new();
        let showing: Vec<String> = self
            .deps
            .read()
            .unwrap()
            .iter()
            .filter(|(_, paths)| paths.iter().any(|p| shows(p, handle)))
            .map(|(file, _)| file.clone())
            .collect();
        let mut dependents = 0;
        for root in [&self.docroot, &self.held] {
            if !unpublished {
                own.extend(self.own_renderings(root, &rel));
            }
            removed += self.own_files(root, &rel, unpublished, !unpublished);
            for file in &showing {
                let path = root.join(file);
                if is_file(&path) {
                    let gone = self.take(root, &path, true);
                    removed += gone;
                    dependents += gone;
                }
            }
            removed += self.sweep_untracked(root, root, "");
        }
        if unpublished {
            if let Some(stale) = &self.stale {
                self.own_files(stale, &rel, true, false);
            }
        }
        own.sort();
        own.dedup();
        Some((removed, own, dependents))
    }

    /// The auto-invalidated files below `dir` that do not say what they show.
    fn sweep_untracked(&self, root: &Path, dir: &Path, url: &str) -> usize {
        let mut removed = 0;
        for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            if name.starts_with('.') {
                continue;
            }
            let path = entry.path();
            let entry_url = format!("{url}/{name}");
            match fs::symlink_metadata(&path) {
                Ok(m) if m.is_dir() => removed += self.sweep_untracked(root, &path, &entry_url),
                Ok(m) if m.is_file() && self.invalidate.iter().any(|pattern| glob(pattern, &entry_url)) => {
                    let key = path.strip_prefix(root).map(|r| r.to_string_lossy().into_owned()).unwrap_or_default();
                    if !self.deps.read().unwrap().contains_key(&key) {
                        removed += self.take(root, &path, true);
                    }
                }
                _ => {}
            }
        }
        removed
    }

    pub fn docroot(&self) -> &Path {
        &self.docroot
    }

    pub fn held(&self) -> &Path {
        &self.held
    }

    /// To be read before a page is fetched and handed to [`Cache::store`] with the page.
    /// When the last invalidation took files out (ms since the epoch; 0: none since the start).
    pub fn invalidated_at(&self) -> u64 {
        self.invalidated_at.load(std::sync::atomic::Ordering::Relaxed)
    }

    pub fn generation(&self) -> u64 {
        *self.generation.read().unwrap()
    }

    /// The cached answer to a path, if there is one.
    pub fn read(&self, decoded_path: &str) -> Option<Entry> {
        let rel = cache_file(decoded_path)?;
        if self.over(&rel.to_string_lossy(), now_ms()) {
            return None; // its time is over: fetched again, and the sweep takes the file out
        }
        if is_file(&self.docroot.join(&rel)) {
            // typed by its extension, whatever may lie beside it
            return Some(Entry { body: fs::read(self.docroot.join(&rel)).ok()?, headers: Vec::new() });
        }
        read_entry(&self.held.join(&rel))
    }

    /// What an invalidation took out of the cache for this path, if it is still kept.
    pub fn read_stale(&self, decoded_path: &str) -> Option<Entry> {
        read_entry(&self.stale.as_ref()?.join(cache_file(decoded_path)?))
    }

    /// Writes an answer into the cache: into the document root when it has no headers of its own,
    /// beside it when it has. `Ok(false)`: not written, because content was published since the
    /// fetch began, or because the name is taken by a directory (a page that is also the parent
    /// of a cached suffix) or lies below a file.
    pub fn store(
        &self,
        decoded_path: &str,
        fetched_at_generation: u64,
        body: &[u8],
        headers: &[(String, String)],
    ) -> io::Result<bool> {
        let Some(rel) = cache_file(decoded_path) else { return Ok(false) };
        let (root, other) = if headers.is_empty() { (&self.docroot, &self.held) } else { (&self.held, &self.docroot) };
        let file = root.join(&rel);
        let dir = file.parent().expect("a cache file has a parent");
        if fs::create_dir_all(dir).is_err() || is_dir(&file) {
            return Ok(false);
        }
        // written beside its place and moved there: nobody sees half a page
        let temp = dir.join(format!(".{}.{}.tmp", std::process::id(), self.temp.fetch_add(1, Ordering::Relaxed)));
        if let Err(e) = fs::File::create(&temp).and_then(|mut f| f.write_all(body)) {
            let _ = fs::remove_file(&temp);
            return Err(e);
        }
        let headers_path = headers_file(&file).expect("a cache file has a name");
        let mut headers_temp = None;
        if !headers.is_empty() {
            let text: String = headers
                .iter()
                .map(|(name, value)| format!("{name}: {}\n", value.replace(['\r', '\n'], " ")))
                .collect();
            let path = dir.join(HEADERS_DIR).join(format!(".{}.{}.tmp", std::process::id(), self.temp.fetch_add(1, Ordering::Relaxed)));
            let written = fs::create_dir_all(dir.join(HEADERS_DIR)).and_then(|_| fs::write(&path, text));
            if let Err(e) = written {
                let _ = fs::remove_file(&temp);
                let _ = fs::remove_file(&path);
                return Err(e);
            }
            headers_temp = Some(path);
        }
        let discard = |e: Option<io::Error>| {
            let _ = fs::remove_file(&temp);
            if let Some(h) = &headers_temp {
                let _ = fs::remove_file(h);
            }
            e.map_or(Ok(false), Err)
        };
        {
            let generation = self.generation.read().unwrap();
            if *generation != fetched_at_generation {
                return discard(None);
            }
            match &headers_temp {
                Some(h) => {
                    if let Err(e) = fs::rename(h, &headers_path) {
                        return discard(Some(e));
                    }
                }
                None => {
                    let _ = fs::remove_file(&headers_path);
                }
            }
            if let Err(e) = fs::rename(&temp, &file) {
                let _ = fs::remove_file(&headers_path);
                return discard(Some(e));
            }
        }
        // the answer has one place: not also the other directory, and no longer what was kept of the old one
        remove_entry(&other.join(&rel));
        if let Some(stale) = &self.stale {
            remove_entry(&stale.join(&rel));
        }
        Ok(true)
    }

    /// A publication of `handle` (a repository path): takes the files of that resource out of the
    /// cache, and, unless `resource_only`, every auto-invalidated file within the scope the stat
    /// level gives. What is taken out is kept as stale, except the resource's own files when it was
    /// unpublished: those are gone for good. Returns how many files left the cache, or `None`
    /// when the handle is not a path the cache could hold.
    pub fn invalidate(&self, action: Action, handle: &str, resource_only: bool) -> Option<usize> {
        self.invalidate_listing(action, handle, resource_only, None).map(|(removed, _)| removed)
    }

    /// As [`Cache::invalidate`], and also says which renderings of the resource itself (request
    /// paths: `/…/en.html`, `/…/en.model.json`) were in the cache when it was activated: those are
    /// worth fetching again at once.
    ///
    /// `scopes`: instead of the scope the stat level gives, the page trees (`/content/wknd`) whose
    /// auto-invalidated files go, and no others: the sites the publication concerns.
    pub fn invalidate_listing(
        &self,
        action: Action,
        handle: &str,
        resource_only: bool,
        scopes: Option<&[String]>,
    ) -> Option<(usize, Vec<String>)> {
        if action == Action::Test {
            return Some((0, Vec::new()));
        }
        let rel = relative(handle)?;
        *self.generation.write().unwrap() += 1;
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
        self.invalidated_at.store(now, std::sync::atomic::Ordering::Relaxed);

        let unpublished = action != Action::Activate;
        let mut removed = 0;
        let mut own = Vec::new();
        for root in [&self.docroot, &self.held] {
            if !unpublished {
                own.extend(self.own_renderings(root, &rel));
            }
            removed += self.own_files(root, &rel, unpublished, !unpublished);
            if let (false, Some(scopes)) = (resource_only, scopes) {
                for scope in scopes {
                    let Some(scope_rel) = relative(scope) else { continue };
                    // the tree, and the renderings of its top page, which lie beside it
                    removed += self.sweep(root, &root.join(&scope_rel), scope, true);
                    let top = root.join(&scope_rel);
                    if let (Some(dir), Some(name)) = (top.parent(), top.file_name().and_then(|n| n.to_str())) {
                        let dotted = format!("{name}.");
                        for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
                            let file_name = entry.file_name();
                            let Some(entry_name) = file_name.to_str() else { continue };
                            let url = format!("{}/{entry_name}", scope.rsplit_once('/').map_or("", |(parent, _)| parent));
                            if entry_name.starts_with(&dotted) && is_file(&entry.path()) && self.invalidate.iter().any(|pattern| glob(pattern, &url)) {
                                removed += self.take(root, &entry.path(), true);
                            }
                        }
                    }
                }
            } else if !resource_only {
                // files directly in the folders above the scope, then everything below the scope
                let mut scope = root.clone();
                let mut url = String::new();
                for segment in rel.iter().take(self.stat_level) {
                    removed += self.sweep(root, &scope, &url, false);
                    scope.push(segment);
                    url.push('/');
                    url.push_str(&segment.to_string_lossy());
                }
                removed += self.sweep(root, &scope, &url, true);
            }
        }
        if unpublished {
            // nor may an older copy of it come back while the publish is away
            if let Some(stale) = &self.stale {
                self.own_files(stale, &rel, true, false);
            }
        }
        own.sort();
        own.dedup();
        Some((removed, own))
    }

    /// The request paths of the files in `root` that are renderings of the resource itself (not
    /// of what is below it).
    fn own_renderings(&self, root: &Path, rel: &Path) -> Vec<String> {
        let target = root.join(rel);
        let (Some(dir), Some(name), Some(parent)) = (target.parent(), target.file_name().and_then(|n| n.to_str()), rel.parent()) else {
            return Vec::new();
        };
        let dotted = format!("{name}.");
        let mut paths = Vec::new();
        for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
            let file_name = entry.file_name();
            let Some(entry_name) = file_name.to_str() else { continue };
            if (entry_name == name || entry_name.starts_with(&dotted)) && is_file(&entry.path()) {
                let mut url = String::new();
                for segment in parent.iter() {
                    url.push('/');
                    url.push_str(&segment.to_string_lossy());
                }
                url.push('/');
                url.push_str(entry_name);
                paths.push(url);
            }
        }
        paths
    }

    /// Where the answer to a path is right now: `plain` (a file the web server serves), `held`
    /// (kept with its headers), `stale` (taken out by a publication, kept for when the publish
    /// does not answer), or nowhere.
    pub fn whereabouts(&self, decoded_path: &str) -> Option<&'static str> {
        let rel = cache_file(decoded_path)?;
        if is_file(&self.docroot.join(&rel)) {
            Some("plain")
        } else if is_file(&self.held.join(&rel)) {
            Some("held")
        } else if self.stale.as_ref().is_some_and(|stale| is_file(&stale.join(&rel))) {
            Some("stale")
        } else {
            None
        }
    }

    /// The resource's own renderings below `root`: en.html, en.model.json, en.html/<suffix>, the
    /// asset itself, and its content node (the folder of a page holds its child pages too, which
    /// stay unless the page itself is gone).
    fn own_files(&self, root: &Path, rel: &Path, whole_subtree: bool, keep: bool) -> usize {
        let target = root.join(rel);
        let (Some(dir), Some(name)) = (target.parent(), target.file_name().and_then(|n| n.to_str())) else { return 0 };
        let dotted = format!("{name}.");
        let mut removed = 0;
        for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
            let file_name = entry.file_name();
            let Some(entry_name) = file_name.to_str() else { continue };
            if entry_name != name && !entry_name.starts_with(&dotted) {
                continue;
            }
            let path = entry.path();
            if is_dir(&path) {
                if entry_name == name && !whole_subtree {
                    removed += self.take_tree(root, &path.join("_jcr_content"), keep);
                    removed += self.take_tree(root, &path.join("jcr:content"), keep);
                } else {
                    removed += self.take_tree(root, &path, keep);
                }
            } else {
                removed += self.take(root, &path, keep);
            }
        }
        removed
    }

    fn sweep(&self, root: &Path, dir: &Path, url: &str, deep: bool) -> usize {
        let mut removed = 0;
        for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
            let Some(name) = entry.file_name().to_str().map(str::to_owned) else { continue };
            if name.starts_with('.') {
                continue; // headers and temporary files go with their file, not on their own
            }
            let path = entry.path();
            let entry_url = format!("{url}/{name}");
            match fs::symlink_metadata(&path) {
                Ok(m) if m.is_dir() => {
                    if deep {
                        removed += self.sweep(root, &path, &entry_url, true);
                    }
                }
                Ok(m) if m.is_file() && self.invalidate.iter().any(|pattern| glob(pattern, &entry_url)) => {
                    removed += self.take(root, &path, true);
                }
                _ => {}
            }
        }
        removed
    }

    /// Takes one file (and its headers) out of `root`: into the stale directory when it is to be
    /// kept and stale copies are kept at all, otherwise removed. Counts the file.
    fn take(&self, root: &Path, file: &Path, keep: bool) -> usize {
        let kept = match (&self.stale, file.strip_prefix(root)) {
            (Some(stale), Ok(rel)) if keep && stale != root && is_file(file) => {
                let to = stale.join(rel);
                let moved = to.parent().is_some_and(|dir| fs::create_dir_all(dir).is_ok())
                    && !is_dir(&to)
                    && fs::rename(file, &to).is_ok();
                if moved {
                    // its headers with it, or none: not those of an older copy
                    if let (Some(from), Some(dest)) = (headers_file(file), headers_file(&to)) {
                        let _ = fs::remove_file(&dest);
                        if is_file(&from) && dest.parent().is_some_and(|dir| fs::create_dir_all(dir).is_ok()) {
                            let _ = fs::rename(&from, &dest);
                        }
                    }
                }
                moved
            }
            _ => false,
        };
        let taken = if kept { 1 } else { remove_entry(file) };
        if taken > 0 {
            note_taken(root, file);
        }
        taken
    }

    /// Takes a directory and what is in it out of `root`, without following links.
    fn take_tree(&self, root: &Path, path: &Path, keep: bool) -> usize {
        let Ok(meta) = fs::symlink_metadata(path) else { return 0 };
        if !meta.is_dir() {
            return self.take(root, path, keep);
        }
        let mut removed = 0;
        for entry in fs::read_dir(path).into_iter().flatten().flatten() {
            if entry.file_name() != HEADERS_DIR {
                removed += self.take_tree(root, &entry.path(), keep);
            }
        }
        let headers = path.join(HEADERS_DIR);
        if is_dir(&headers) {
            let _ = fs::remove_dir_all(&headers);
        }
        let _ = fs::remove_dir(path);
        removed
    }

    /// Empties the cache, stale copies included (after new code was deployed, say). Counts the
    /// cached files that went.
    pub fn clear(&self) -> usize {
        *self.generation.write().unwrap() += 1;
        self.deps.write().unwrap().clear();
        let _ = fs::remove_dir_all(&self.deps_dir);
        let _ = own_dir(&self.deps_dir);
        self.expiry.write().unwrap().clear();
        let _ = fs::remove_dir_all(&self.expiry_dir);
        let _ = own_dir(&self.expiry_dir);
        let mut removed = 0;
        for root in [Some(&self.docroot), Some(&self.held), self.stale.as_ref()].into_iter().flatten() {
            let mut gone = 0;
            for entry in fs::read_dir(root).into_iter().flatten().flatten() {
                if entry.file_name() == HEADERS_DIR {
                    let _ = fs::remove_dir_all(entry.path());
                } else {
                    gone += self.take_tree(root, &entry.path(), false);
                }
            }
            if Some(root) != self.stale.as_ref() {
                removed += gone;
            }
        }
        removed
    }

    /// How many answers are cached: plain files, held ones, stale ones.
    pub fn count(&self) -> (usize, usize, usize) {
        fn files(dir: &Path) -> usize {
            let mut n = 0;
            for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
                if entry.file_name().to_str().is_some_and(|name| name.starts_with('.')) {
                    continue;
                }
                match fs::symlink_metadata(entry.path()) {
                    Ok(m) if m.is_dir() => n += files(&entry.path()),
                    Ok(m) if m.is_file() => n += 1,
                    _ => {}
                }
            }
            n
        }
        (files(&self.docroot), files(&self.held), self.stale.as_deref().map_or(0, files))
    }

    /// Removes cached files older than `ttl`, stale ones older than `stale_ttl`, and temporary
    /// files a crash left behind.
    pub fn expire(&self, ttl: Option<Duration>, stale_ttl: Duration) -> usize {
        fn walk(dir: &Path, ttl: Option<Duration>, now: SystemTime) -> usize {
            let mut removed = 0;
            for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
                let path = entry.path();
                let Ok(meta) = fs::symlink_metadata(&path) else { continue };
                let name = entry.file_name();
                let age = meta.modified().ok().and_then(|m| now.duration_since(m).ok()).unwrap_or_default();
                let temp = name.to_str().is_some_and(|n| n.starts_with('.') && n.ends_with(".tmp"));
                if meta.is_dir() {
                    // in a headers folder only what a crash left; the headers go with their file
                    removed += walk(&path, if name == HEADERS_DIR { None } else { ttl }, now);
                } else if meta.is_file() && temp {
                    if age > Duration::from_secs(3600) {
                        let _ = fs::remove_file(&path);
                    }
                } else if meta.is_file() && dir.file_name().is_none_or(|d| d != HEADERS_DIR) && ttl.is_some_and(|t| age > t) {
                    removed += remove_entry(&path);
                }
            }
            removed
        }
        let now = SystemTime::now();
        let mut removed = walk(&self.docroot, ttl, now) + walk(&self.held, ttl, now);
        if let Some(stale) = &self.stale {
            removed += walk(stale, Some(stale_ttl), now);
        }
        removed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cache_with(level: usize, keep_stale: bool) -> (tempfile::TempDir, Cache) {
        let dir = tempfile::tempdir().unwrap();
        let cache = Cache::new(dir.path().join("docroot"), level, vec!["*.html".into()], keep_stale).unwrap();
        (dir, cache)
    }

    fn cache(level: usize) -> (tempfile::TempDir, Cache) {
        cache_with(level, false)
    }

    fn put(cache: &Cache, path: &str) {
        assert!(cache.store(path, cache.generation(), path.as_bytes(), &[]).unwrap(), "{path}");
    }

    fn has(cache: &Cache, path: &str) -> bool {
        cache.read(path).is_some()
    }

    fn download() -> Vec<(String, String)> {
        vec![("Content-Type".to_owned(), "image/svg+xml".to_owned()), ("Content-Disposition".to_owned(), "attachment; filename=a.svg".to_owned())]
    }

    #[test]
    fn stores_and_reads() {
        let (_dir, cache) = cache(0);
        assert!(!has(&cache, "/content/wknd/us/en.html"));
        put(&cache, "/content/wknd/us/en.html");
        assert_eq!(cache.read("/content/wknd/us/en.html").unwrap().body, b"/content/wknd/us/en.html");
        assert!(cache.read("/content/wknd/us/en.html").unwrap().headers.is_empty());
        assert!(cache.docroot().join("content/wknd/us/en.html").is_file());
        // nothing but the page is left in its folder
        assert_eq!(fs::read_dir(cache.docroot().join("content/wknd/us")).unwrap().count(), 1);
        // not a cacheable name
        assert!(!cache.store("/content/wknd/us/en", cache.generation(), b"x", &[]).unwrap());
    }

    #[test]
    fn an_answer_with_headers_of_its_own_is_kept_beside_the_document_root() {
        let (_dir, cache) = cache(0);
        assert!(cache.store("/content/dam/a.svg", cache.generation(), b"<svg/>", &download()).unwrap());
        assert!(!cache.docroot().join("content/dam/a.svg").exists(), "Apache must not serve it as a plain file");
        assert!(!cache.held().starts_with(cache.docroot()));
        assert_eq!(cache.read("/content/dam/a.svg").unwrap(), Entry { body: b"<svg/>".to_vec(), headers: download() });
        // only the file and its headers folder
        assert_eq!(fs::read_dir(cache.held().join("content/dam")).unwrap().count(), 2);

        // the same path answered plainly later: one place only
        put(&cache, "/content/dam/a.svg");
        assert!(cache.docroot().join("content/dam/a.svg").is_file());
        assert!(!cache.held().join("content/dam/a.svg").exists());
        assert!(cache.read("/content/dam/a.svg").unwrap().headers.is_empty());
        // and back
        assert!(cache.store("/content/dam/a.svg", cache.generation(), b"<svg/>", &download()).unwrap());
        assert!(!cache.docroot().join("content/dam/a.svg").exists());
        assert_eq!(cache.read("/content/dam/a.svg").unwrap().headers, download());

        // a line break in a value cannot add a header
        let sneaky = vec![("Content-Disposition".to_owned(), "inline\r\nSet-Cookie: a=b".to_owned())];
        assert!(cache.store("/content/dam/b.svg", cache.generation(), b"x", &sneaky).unwrap());
        assert_eq!(cache.read("/content/dam/b.svg").unwrap().headers.len(), 1);
    }

    #[test]
    fn a_page_and_its_suffix_do_not_fight() {
        let (_dir, cache) = cache(0);
        put(&cache, "/content/a.html");
        // below a file: not cached, not an error
        assert!(!cache.store("/content/a.html/suffix.html", cache.generation(), b"x", &[]).unwrap());
        put(&cache, "/content/b.html/suffix.html");
        // the name is a directory now
        assert!(!cache.store("/content/b.html", cache.generation(), b"x", &[]).unwrap());
        assert!(!has(&cache, "/content/b.html"));
    }

    #[test]
    fn a_page_fetched_before_a_publication_is_not_kept() {
        let (_dir, cache) = cache(0);
        let before = cache.generation();
        cache.invalidate(Action::Activate, "/content/wknd/us/en", false).unwrap();
        assert!(!cache.store("/content/wknd/us/en.html", before, b"old", &[]).unwrap());
        assert!(!cache.store("/content/wknd/us/en.svg", before, b"old", &download()).unwrap());
        assert!(!has(&cache, "/content/wknd/us/en.html"));
        assert!(!has(&cache, "/content/wknd/us/en.svg"));
        assert_eq!(fs::read_dir(cache.docroot().join("content/wknd/us")).unwrap().count(), 0, "no temporary file left");
        assert_eq!(fs::read_dir(cache.held().join("content/wknd/us/.h")).unwrap().count(), 0, "no temporary file left");
    }

    #[test]
    fn activation_removes_the_page_and_every_html() {
        let (_dir, cache) = cache(0);
        for p in [
            "/content/wknd/us/en.html",
            "/content/wknd/us/en.model.json",
            "/content/wknd/us/en/_jcr_content/root/image.coreimg.jpeg/1/a.jpeg",
            "/content/wknd/us/en/about.html",
            "/content/wknd/us/en/about.model.json",
            "/content/wknd/us/english.html",
            "/content/other/home.html",
            "/content/dam/wknd/a.jpg",
            "/etc.clientlibs/wknd/site.css",
        ] {
            put(&cache, p);
        }
        // held answers are invalidated like plain ones
        assert!(cache.store("/content/wknd/us/en/_jcr_content/logo.coreimg.svg/1/logo.svg", cache.generation(), b"s", &download()).unwrap());
        assert!(cache.store("/content/other/terms.html", cache.generation(), b"t", &download()).unwrap());
        assert!(cache.store("/content/dam/wknd/b.svg", cache.generation(), b"b", &download()).unwrap());

        let removed = cache.invalidate(Action::Activate, "/content/wknd/us/en", false).unwrap();
        // its own renderings, whatever the extension
        assert!(!has(&cache, "/content/wknd/us/en.html"));
        assert!(!has(&cache, "/content/wknd/us/en.model.json"));
        assert!(!has(&cache, "/content/wknd/us/en/_jcr_content/root/image.coreimg.jpeg/1/a.jpeg"));
        assert!(!has(&cache, "/content/wknd/us/en/_jcr_content/logo.coreimg.svg/1/logo.svg"));
        // every page: they may show its title
        assert!(!has(&cache, "/content/wknd/us/en/about.html"));
        assert!(!has(&cache, "/content/wknd/us/english.html"));
        assert!(!has(&cache, "/content/other/home.html"));
        assert!(!has(&cache, "/content/other/terms.html"));
        // what is not auto-invalidated and not the page's stays
        assert!(has(&cache, "/content/wknd/us/en/about.model.json"));
        assert!(has(&cache, "/content/dam/wknd/a.jpg"));
        assert_eq!(cache.read("/content/dam/wknd/b.svg").unwrap().headers, download());
        assert!(has(&cache, "/etc.clientlibs/wknd/site.css"));
        assert_eq!(removed, 8);
        // no headers left without their file
        assert!(!cache.held().join("content/other/.h/terms.html").exists());
    }

    #[test]
    fn the_stat_level_limits_the_scope() {
        let (_dir, cache) = cache(2);
        for p in [
            "/index.html",
            "/content/root.html",
            "/content/wknd/us/en.html",
            "/content/wknd/us/en/about.html",
            "/content/wknd/fr.html",
            "/content/other/home.html",
            "/content/other/deep/page.html",
        ] {
            put(&cache, p);
        }
        cache.invalidate(Action::Activate, "/content/wknd/us/en", false).unwrap();
        assert!(!has(&cache, "/index.html"));
        assert!(!has(&cache, "/content/root.html"));
        assert!(!has(&cache, "/content/wknd/us/en.html"));
        assert!(!has(&cache, "/content/wknd/us/en/about.html"));
        assert!(!has(&cache, "/content/wknd/fr.html"));
        assert!(has(&cache, "/content/other/home.html"));
        assert!(has(&cache, "/content/other/deep/page.html"));
    }

    #[test]
    fn resource_only_leaves_the_other_pages() {
        let (_dir, cache) = cache(0);
        put(&cache, "/content/wknd/us/en.html");
        put(&cache, "/content/wknd/us/en/about.html");
        cache.invalidate(Action::Activate, "/content/wknd/us/en", true).unwrap();
        assert!(!has(&cache, "/content/wknd/us/en.html"));
        assert!(has(&cache, "/content/wknd/us/en/about.html"));
    }

    #[test]
    fn deactivation_removes_the_subtree() {
        let (_dir, cache) = cache(0);
        put(&cache, "/content/wknd/us/en.html");
        put(&cache, "/content/wknd/us/en/about.model.json");
        put(&cache, "/content/wknd/us/english.model.json");
        cache.invalidate(Action::Deactivate, "/content/wknd/us/en", false).unwrap();
        assert!(!has(&cache, "/content/wknd/us/en.html"));
        assert!(!has(&cache, "/content/wknd/us/en/about.model.json"));
        assert!(!cache.docroot().join("content/wknd/us/en").exists());
        assert!(has(&cache, "/content/wknd/us/english.model.json"));
    }

    #[test]
    fn an_asset_is_removed_with_its_renditions() {
        let (_dir, cache) = cache(0);
        put(&cache, "/content/dam/wknd/a.jpg");
        put(&cache, "/content/dam/wknd/a.jpg.transform/small/img.jpeg");
        put(&cache, "/content/dam/wknd/ab.jpg");
        cache.invalidate(Action::Activate, "/content/dam/wknd/a.jpg", false).unwrap();
        assert!(!has(&cache, "/content/dam/wknd/a.jpg"));
        assert!(!has(&cache, "/content/dam/wknd/a.jpg.transform/small/img.jpeg"));
        assert!(has(&cache, "/content/dam/wknd/ab.jpg"));
    }

    #[test]
    fn what_a_publication_takes_out_is_kept_as_stale() {
        let (_dir, cache) = cache_with(0, true);
        put(&cache, "/content/site/en.html");
        put(&cache, "/content/site/en/about.html");
        put(&cache, "/content/site/en/about.model.json");
        assert!(cache.store("/content/site/en/terms.html", cache.generation(), b"terms", &download()).unwrap());
        assert!(cache.read_stale("/content/site/en.html").is_none());

        assert_eq!(cache.invalidate(Action::Activate, "/content/site/en", false), Some(3));
        // out of the cache: Apache finds no file, the service no answer
        assert!(!has(&cache, "/content/site/en.html"));
        assert!(!cache.docroot().join("content/site/en.html").exists());
        assert!(!has(&cache, "/content/site/en/terms.html"));
        // but kept, with the headers it had
        assert_eq!(cache.read_stale("/content/site/en.html").unwrap().body, b"/content/site/en.html");
        assert_eq!(cache.read_stale("/content/site/en/about.html").unwrap().body, b"/content/site/en/about.html");
        assert_eq!(cache.read_stale("/content/site/en/terms.html").unwrap(), Entry { body: b"terms".to_vec(), headers: download() });
        // what stayed in the cache is not stale
        assert!(cache.read_stale("/content/site/en/about.model.json").is_none());

        // the new page arrives: the old one is not kept any longer
        put(&cache, "/content/site/en.html");
        assert!(cache.read_stale("/content/site/en.html").is_none());
        assert!(cache.read_stale("/content/site/en/about.html").is_some());

        // a second publication replaces what was kept, headers included
        put(&cache, "/content/site/en/terms.html");
        cache.invalidate(Action::Activate, "/content/site/en/about", false).unwrap();
        assert!(cache.read_stale("/content/site/en/terms.html").unwrap().headers.is_empty());
    }

    #[test]
    fn an_unpublished_page_is_not_kept() {
        let (_dir, cache) = cache_with(0, true);
        put(&cache, "/content/site/en/secret.html");
        put(&cache, "/content/site/en/secret/child.html");
        put(&cache, "/content/site/en/other.html");
        // already stale from an earlier publication
        cache.invalidate(Action::Activate, "/content/site/en/other", false).unwrap();
        assert!(cache.read_stale("/content/site/en/secret.html").is_some());
        put(&cache, "/content/site/en/secret.model.json");

        for action in [Action::Deactivate, Action::Delete] {
            cache.invalidate(action, "/content/site/en/secret", false).unwrap();
            for path in ["/content/site/en/secret.html", "/content/site/en/secret/child.html", "/content/site/en/secret.model.json"] {
                assert!(!has(&cache, path), "{path}");
                assert!(cache.read_stale(path).is_none(), "{path} must not come back while the publish is away");
            }
        }
        // the other pages, taken out because they may link to it, are kept as usual
        assert!(cache.read_stale("/content/site/en/other.html").is_some());
    }

    #[test]
    fn handles_that_are_not_paths_are_refused() {
        let (_dir, cache) = cache(0);
        put(&cache, "/content/a.html");
        assert_eq!(cache.invalidate(Action::Activate, "../outside", false), None);
        assert_eq!(cache.invalidate(Action::Activate, "/content/../../outside", false), None);
        assert_eq!(cache.invalidate(Action::Activate, "", false), None);
        assert_eq!(cache.invalidate(Action::Activate, "/", false), None);
        assert_eq!(cache.invalidate(Action::Test, "/content/a", false), Some(0));
        assert!(has(&cache, "/content/a.html"));
    }

    #[test]
    fn links_are_not_followed() {
        let (dir, cache) = cache_with(0, true);
        let outside = dir.path().join("outside");
        fs::create_dir_all(&outside).unwrap();
        fs::write(outside.join("keep.html"), "keep").unwrap();
        put(&cache, "/content/a.html");
        #[cfg(unix)]
        std::os::unix::fs::symlink(&outside, cache.docroot().join("content/link")).unwrap();
        cache.invalidate(Action::Delete, "/content/link", false).unwrap();
        cache.invalidate(Action::Activate, "/content/a", false).unwrap();
        assert!(outside.join("keep.html").is_file());
    }

    #[test]
    fn an_activation_says_which_of_its_own_pages_were_cached() {
        let (_dir, cache) = cache_with(0, true);
        put(&cache, "/content/site/en.html");
        put(&cache, "/content/site/en.model.json");
        put(&cache, "/content/site/en/about.html");
        put(&cache, "/content/site/en/_jcr_content/image.coreimg.jpeg/1/a.jpeg");
        put(&cache, "/content/site/english.html");
        assert!(cache.store("/content/site/en.svg", cache.generation(), b"s", &download()).unwrap());
        assert_eq!(cache.whereabouts("/content/site/en.html"), Some("plain"));
        assert_eq!(cache.whereabouts("/content/site/en.svg"), Some("held"));
        assert_eq!(cache.whereabouts("/content/site/nothing.html"), None);

        let (removed, own) = cache.invalidate_listing(Action::Activate, "/content/site/en", false, None).unwrap();
        assert_eq!(removed, 6);
        // its own renderings, not its children, its images or its look-alikes
        assert_eq!(own, vec!["/content/site/en.html", "/content/site/en.model.json", "/content/site/en.svg"]);
        assert_eq!(cache.whereabouts("/content/site/en.html"), Some("stale"));
        assert_eq!(cache.whereabouts("/content/site/en.svg"), Some("stale"));

        // nothing to fetch again for what was unpublished, or was not cached
        put(&cache, "/content/site/en.html");
        assert_eq!(cache.invalidate_listing(Action::Deactivate, "/content/site/en", false, None).unwrap().1, Vec::<String>::new());
        assert_eq!(cache.invalidate_listing(Action::Activate, "/content/site/never", false, None).unwrap().1, Vec::<String>::new());
        assert_eq!(cache.whereabouts("/content/site/en.html"), None);
    }

    #[test]
    fn a_publication_within_a_site_leaves_the_other_sites_alone() {
        let (_dir, cache) = cache_with(0, true);
        let all = [
            "/content/a.html",
            "/content/a/en.html",
            "/content/a/en/deep/page.html",
            "/content/a/en/data.model.json",
            "/content/b.html",
            "/content/b/en.html",
            "/content/ab/en.html",
            "/content/experience-fragments/a/header.html",
            "/index.html",
        ];
        let fill = |cache: &Cache| all.iter().for_each(|p| put(cache, p));
        let left = |cache: &Cache| all.iter().copied().filter(|p| has(cache, p)).collect::<Vec<_>>();
        let scope_a = vec!["/content/a".to_owned()];

        // a page of site a: its own files, and the pages of a, top page included
        fill(&cache);
        let (removed, own) = cache.invalidate_listing(Action::Activate, "/content/a/en/deep/page", false, Some(&scope_a)).unwrap();
        assert_eq!(own, vec!["/content/a/en/deep/page.html"]);
        assert_eq!(removed, 3);
        assert_eq!(left(&cache), vec!["/content/a/en/data.model.json", "/content/b.html", "/content/b/en.html", "/content/ab/en.html", "/content/experience-fragments/a/header.html", "/index.html"]);

        // something a shows from elsewhere: the thing itself, and the pages of a
        fill(&cache);
        cache.invalidate_listing(Action::Activate, "/content/experience-fragments/a/header", false, Some(&scope_a)).unwrap();
        assert_eq!(left(&cache), vec!["/content/a/en/data.model.json", "/content/b.html", "/content/b/en.html", "/content/ab/en.html", "/index.html"]);

        // two sites concerned
        fill(&cache);
        cache.invalidate_listing(Action::Activate, "/content/dam/shared", false, Some(&["/content/a".to_owned(), "/content/b".to_owned()])).unwrap();
        assert_eq!(left(&cache), vec!["/content/a/en/data.model.json", "/content/ab/en.html", "/content/experience-fragments/a/header.html", "/index.html"]);

        // no site named: everything, as without sites
        fill(&cache);
        cache.invalidate_listing(Action::Activate, "/conf/global/settings", false, None).unwrap();
        assert_eq!(left(&cache), vec!["/content/a/en/data.model.json"]);

        // only the resource: no site's pages
        fill(&cache);
        cache.invalidate_listing(Action::Activate, "/content/a/en", true, Some(&scope_a)).unwrap();
        assert_eq!(left(&cache).len(), all.len() - 1);
    }

    #[test]
    fn counting_and_clearing() {
        let (_dir, cache) = cache_with(0, true);
        assert_eq!(cache.count(), (0, 0, 0));
        put(&cache, "/content/a.html");
        put(&cache, "/content/deep/b.html");
        put(&cache, "/content/c.jpg");
        assert!(cache.store("/content/d.svg", cache.generation(), b"d", &download()).unwrap());
        cache.invalidate(Action::Activate, "/content/a", true).unwrap();
        assert_eq!(cache.count(), (2, 1, 1));

        let before = cache.generation();
        assert_eq!(cache.clear(), 3);
        assert_eq!(cache.count(), (0, 0, 0));
        assert!(cache.read_stale("/content/a.html").is_none());
        assert_eq!(fs::read_dir(cache.docroot()).unwrap().count(), 0);
        assert_eq!(fs::read_dir(cache.held()).unwrap().count(), 0);
        // a page fetched before the clearing is not written after it
        assert!(!cache.store("/content/a.html", before, b"old", &[]).unwrap());
        put(&cache, "/content/a.html");
        assert_eq!(cache.count(), (1, 0, 0));
    }

    #[test]
    fn expiry() {
        let (_dir, cache) = cache_with(0, true);
        let day = Duration::from_secs(86_400);
        put(&cache, "/content/a.html");
        assert!(cache.store("/content/b.svg", cache.generation(), b"b", &download()).unwrap());
        put(&cache, "/content/c.html");
        cache.invalidate(Action::Activate, "/content/c", true).unwrap();
        assert_eq!(cache.expire(None, day), 0);
        assert_eq!(cache.expire(Some(Duration::from_secs(3600)), day), 0);
        std::thread::sleep(Duration::from_millis(20));
        // the stale copy goes by its own clock
        assert_eq!(cache.expire(None, Duration::from_millis(1)), 1);
        assert!(cache.read_stale("/content/c.html").is_none());
        assert!(has(&cache, "/content/a.html"));
        assert_eq!(cache.expire(Some(Duration::from_millis(1)), day), 2);
        assert!(!has(&cache, "/content/a.html"));
        assert!(!has(&cache, "/content/b.svg"));
        assert!(!cache.held().join("content/.h/b.svg").exists(), "the headers go with their file");
    }

    #[test]
    fn an_answer_kept_as_long_as_its_publish_said() {
        let dir = tempfile::tempdir().unwrap();
        let cache = Cache::new(dir.path().join("docroot"), 0, vec!["*.html".into()], true).unwrap();
        put(&cache, "/content/a.html");
        put(&cache, "/content/b.html");
        let now = now_ms();
        cache.set_expiry("/content/a.html", Some(now + 60_000));
        cache.set_expiry("/content/b.html", Some(now - 1));
        assert_eq!(cache.timed(), 2);
        // over its time: not served by the service, and the sweep takes it out (kept as stale)
        assert!(has(&cache, "/content/a.html"));
        assert!(!has(&cache, "/content/b.html"));
        assert_eq!(cache.expire_due(now), 1);
        assert!(!cache.docroot().join("content/b.html").exists());
        assert!(cache.read_stale("/content/b.html").is_some());
        assert_eq!(cache.timed(), 1);
        // read back at the start
        let again = Cache::new(dir.path().join("docroot"), 0, vec!["*.html".into()], true).unwrap();
        assert_eq!(again.timed(), 1);
        assert_eq!(again.expire_due(now + 61_000), 1);
        assert!(!again.docroot().join("content/a.html").exists());
        // stored again without a time: none
        put(&again, "/content/a.html");
        again.set_expiry("/content/a.html", Some(now + 1000));
        again.set_expiry("/content/a.html", None);
        assert_eq!(again.timed(), 0);
        assert_eq!(again.expire_due(now + 10_000_000), 0);
        assert!(has(&again, "/content/a.html"));
        // clear forgets the times
        again.set_expiry("/content/a.html", Some(now));
        again.clear();
        assert_eq!(again.timed(), 0);
    }

    #[test]
    fn what_a_page_shows_of_other_pages() {
        // a page shown (a teaser, a navigation entry): its own publication, or one above it that takes it along
        assert!(shows("/content/wknd/us/en/faqs/jcr:content", "/content/wknd/us/en/faqs"));
        assert!(shows("/content/wknd/us/en/faqs/jcr:content", "/content/wknd/us/en"), "unpublishing a tree takes its pages along");
        assert!(!shows("/content/wknd/us/en/jcr:content", "/content/wknd/us/en/faqs"), "a page below is not the page");
        // a page's children listed: one directly below it, not deeper, not the page itself
        assert!(shows("/content/wknd/us/en/*", "/content/wknd/us/en/faqs"));
        assert!(!shows("/content/wknd/us/en/*", "/content/wknd/us/en/faqs/old"));
        assert!(!shows("/content/wknd/us/en/*", "/content/wknd/us/en"));
        assert!(!shows("/content/wknd/us/en/*", "/content/wknd/us/english"));
        // media as before
        assert!(shows("/content/dam/wknd/a.jpg", "/content/dam/wknd/a.jpg"));
        assert!(shows("/content/dam/wknd", "/content/dam/wknd/a.jpg"));
    }

    #[test]
    fn a_page_publication_takes_out_the_pages_that_show_it() {
        let (_dir, cache) = cache(0);
        let store = |path: &str, shown: Option<&[&str]>| {
            let shown: Option<Vec<String>> = shown.map(|s| s.iter().map(|p| (*p).to_owned()).collect());
            assert!(cache.store_with(path, cache.generation(), b"x", &[], shown.as_deref()).unwrap(), "{path}");
        };
        // the home page lists its children (navigation); an article shows FAQs in a teaser; a page below
        // FAQs shows only the pages above it; a search page says nothing
        store("/content/wknd/us/en.html", Some(&["/content/wknd/us/en/*"]));
        store("/content/wknd/us/en/magazine/article.html", Some(&["/content/wknd/us/en/faqs/jcr:content", "/content/wknd/us/en/jcr:content"]));
        store("/content/wknd/us/en/faqs/old.html", Some(&["/content/wknd/us/en/faqs/jcr:content", "/content/wknd/us/en/jcr:content"]));
        store("/content/wknd/us/en/adventures/deep.html", Some(&["/content/wknd/us/en/adventures/jcr:content", "/content/wknd/us/en/jcr:content"]));
        store("/content/wknd/us/en/search.html", None);
        store("/content/wknd/us/en/faqs.html", Some(&["/content/wknd/us/en/jcr:content"]));

        let (removed, _, dependents) = cache.invalidate_dependents(Action::Activate, "/content/wknd/us/en/faqs").unwrap();
        assert!(!has(&cache, "/content/wknd/us/en/faqs.html"), "its own page");
        assert!(!has(&cache, "/content/wknd/us/en.html"), "lists it");
        assert!(!has(&cache, "/content/wknd/us/en/magazine/article.html"), "shows it");
        assert!(!has(&cache, "/content/wknd/us/en/faqs/old.html"), "a page below it, which shows it above");
        assert!(!has(&cache, "/content/wknd/us/en/search.html"), "says nothing");
        assert!(has(&cache, "/content/wknd/us/en/adventures/deep.html"), "shows other pages only");
        assert_eq!((removed, dependents), (5, 3));

        // a page deep down: only what shows it; the home page's list is of its children, not of everything
        store("/content/wknd/us/en.html", Some(&["/content/wknd/us/en/*"]));
        let (_, _, dependents) = cache.invalidate_dependents(Action::Activate, "/content/wknd/us/en/adventures/deep").unwrap();
        assert!(has(&cache, "/content/wknd/us/en.html"));
        assert_eq!(dependents, 0);
    }
}
