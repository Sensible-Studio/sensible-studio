//! The HTTP side: requests Apache did not find in its document root, and the invalidation
//! endpoint a flush agent posts to.

use std::net::SocketAddr;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::body::Body;
use axum::extract::{ConnectInfo, Query, Request, State};
use axum::http::header::{self, HeaderMap, HeaderName, HeaderValue};
use axum::http::{Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{any, get, post};
use axum::Router;
use bytes::Bytes;
use futures_util::{stream, StreamExt};

use crate::cache::{Action, Cache, Entry};
use crate::config::Config;
use crate::filter::{self, Kind};
use crate::url::{cache_file, decode_path, decompose, encode_path, full_path, glob, query_names};

/// What this service answered with, for whoever reads the response headers while debugging.
const HEADER: &str = "x-studio-live";
/// What a page shows besides itself, as its publish says (`DependsFilter`).
const DEPENDS: &str = "x-studio-depends";
/// At most this many pages fetched again because a flush asked for them.
const MAX_REFETCH: usize = 200;

/// The paths an answer says it shows, `,` separated, `%2C` and `%25` escaped; `None` when it says
/// nothing (or something that is no list of paths), and so may show anything.
fn depends(headers: &reqwest::header::HeaderMap) -> Option<Vec<String>> {
    let value = headers.get(DEPENDS)?.to_str().ok()?;
    let mut paths = Vec::new();
    for item in value.split(',').map(str::trim).filter(|s| !s.is_empty()) {
        let path = item.replace("%2C", ",").replace("%2c", ",").replace("%25", "%");
        if !path.starts_with('/') || path.contains('\n') {
            return None;
        }
        paths.push(path);
    }
    Some(paths)
}

pub struct App {
    config: Config,
    /// One cache per site, each in its own directory, the document root of that site's virtual
    /// host; without sites one, in `docroot`.
    caches: Vec<Arc<Cache>>,
    /// Per site: where its protected pages are kept (`<name>.private`, outside the document root
    /// of its virtual host), if it lists any.
    protected: Vec<Option<Arc<Cache>>>,
    client: reqwest::Client,
    next: AtomicUsize,
    /// Every publish instance of the configuration, each once: the general ones, then the sites' own.
    instances: Vec<String>,
    /// Which of them answer when no site says otherwise, and which answer for each site.
    general: Vec<usize>,
    per_site: Vec<Vec<usize>>,
    /// Per publish instance: the time (ms since the epoch) until which it is skipped.
    down_until: Vec<AtomicU64>,
    /// Per publish instance: how long it took to answer lately (µs, a moving average), for pages
    /// and for everything else; 0 before its first answer.
    speed: Vec<[AtomicU64; 2]>,
    /// Per publish instance: the name it has in the route cookie. Not its address, which is
    /// nobody's business outside, and not its position, which changes when the list does.
    route_ids: Vec<String>,
    /// Per site (the first two segments of a path, `/content/wknd`): the `Host` of the last visitor
    /// whose page was kept. A page fetched again after a publication is asked for under that name,
    /// since a publish may write its links by it.
    hosts: Mutex<std::collections::HashMap<String, HeaderValue>>,
    /// The pages being fetched again in the background (grace period), each once.
    refreshing: Mutex<std::collections::HashSet<String>>,
    /// The publish's vanity URLs: path → the page it leads to, and when they were last read.
    vanity: std::sync::RwLock<std::collections::HashMap<String, String>>,
    vanity_read_at: AtomicU64,
    /// Per site: its redirect map, source path → (status, target).
    redirects: Vec<std::sync::RwLock<std::collections::HashMap<String, (u16, String)>>>,
    stats: Stats,
}

#[derive(Default)]
struct Stats {
    hits: AtomicU64,
    stale: AtomicU64,
    /// Served as it was while being fetched again (grace period).
    graced: AtomicU64,
    stored: AtomicU64,
    rewarmed: AtomicU64,
    passed: AtomicU64,
    /// Answered from a redirect map.
    redirected: AtomicU64,
    /// Protected pages served from the cache after the publish said yes, and refused there.
    checked: AtomicU64,
    refused: AtomicU64,
    denied: AtomicU64,
    errors: AtomicU64,
    invalidations: AtomicU64,
    last_invalidation: Mutex<Option<serde_json::Value>>,
    /// The after-invalidation command and URL: told, failed, the last time.
    hook_told: AtomicU64,
    hook_failed: AtomicU64,
    hook_last: Mutex<Option<serde_json::Value>>,
}

impl App {
    pub fn new(config: Config) -> Result<Arc<App>, String> {
        let docroots: Vec<(Option<usize>, std::path::PathBuf)> = if config.site.is_empty() {
            vec![(None, config.docroot.clone())]
        } else {
            (0..config.site.len()).map(|site| (Some(site), config.site_docroot(site))).collect()
        };
        let mut caches = Vec::new();
        for (site, docroot) in docroots {
            let settings = config.cache_for(site);
            let cache = Cache::new(docroot.clone(), config.stat_level_for(site), settings.invalidate.clone(), settings.stale_seconds > 0)
                .map_err(|e| format!("docroot {}: {e}", docroot.display()))?;
            caches.push(Arc::new(cache));
        }
        let mut protected = Vec::new();
        for site in 0..config.site.len() {
            protected.push(if config.site[site].protected.is_empty() {
                None
            } else {
                // never served as it was before a publication: that would skip the check's reason to be
                let docroot = config.site_private_docroot(site);
                let cache = Cache::new(docroot.clone(), config.stat_level_for(Some(site)), config.cache_for(Some(site)).invalidate.clone(), false)
                    .map_err(|e| format!("docroot {}: {e}", docroot.display()))?;
                // the service's alone, whatever group the cache directory hands down to the web server
                #[cfg(unix)]
                for dir in [cache.docroot(), cache.held()] {
                    use std::os::unix::fs::PermissionsExt;
                    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))
                        .map_err(|e| format!("docroot {}: {e}", dir.display()))?;
                }
                Some(Arc::new(cache))
            });
        }
        // the files Apache's RewriteMap reads must be there before Apache starts: empty until read
        let mut redirects = Vec::new();
        for site in 0..config.site.len() {
            if config.has_redirects(site) {
                let file = config.site_redirects_file(site);
                if !file.exists() {
                    std::fs::write(&file, "").map_err(|e| format!("redirect map {}: {e}", file.display()))?;
                }
            }
            redirects.push(std::sync::RwLock::new(std::collections::HashMap::new()));
        }
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(config.connect_timeout_seconds))
            .timeout(Duration::from_secs(config.timeout_seconds))
            .redirect(reqwest::redirect::Policy::none())
            .no_proxy()
            .build()
            .map_err(|e| e.to_string())?;
        let mut instances: Vec<String> = Vec::new();
        let mut index_of = |url: &String| match instances.iter().position(|known| known == url) {
            Some(index) => index,
            None => {
                instances.push(url.clone());
                instances.len() - 1
            }
        };
        let general: Vec<usize> = config.publish.iter().map(&mut index_of).collect();
        let per_site: Vec<Vec<usize>> = config
            .site
            .iter()
            .map(|site| if site.publish.is_empty() { general.clone() } else { site.publish.iter().map(&mut index_of).collect() })
            .collect();
        let down_until = instances.iter().map(|_| AtomicU64::new(0)).collect();
        let speed = instances.iter().map(|_| [AtomicU64::new(0), AtomicU64::new(0)]).collect();
        let route_ids = instances.iter().map(|url| route_id(url)).collect();
        Ok(Arc::new(App {
            config,
            caches,
            protected,
            client,
            next: AtomicUsize::new(0),
            instances,
            general,
            per_site,
            down_until,
            speed,
            route_ids,
            hosts: Mutex::new(std::collections::HashMap::new()),
            refreshing: Mutex::new(std::collections::HashSet::new()),
            vanity: std::sync::RwLock::new(std::collections::HashMap::new()),
            vanity_read_at: AtomicU64::new(0),
            redirects,
            stats: Stats::default(),
        }))
    }

    /// The cache of the first site, or the only one.
    pub fn cache(&self) -> &Arc<Cache> {
        &self.caches[0]
    }

    /// The cache a site's pages are kept in; with no sites declared, the only one.
    fn cache_of(&self, site: Option<usize>) -> &Arc<Cache> {
        site.and_then(|site| self.caches.get(site)).unwrap_or(&self.caches[0])
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Every cache: the sites' and their protected pages'.
    fn every_cache(&self) -> Vec<Arc<Cache>> {
        self.caches.iter().chain(self.protected.iter().flatten()).cloned().collect()
    }

    /// Every cache with the site whose settings apply to it.
    fn every_cache_of_site(&self) -> Vec<(Option<usize>, Arc<Cache>)> {
        let site = |index: usize| (!self.config.site.is_empty()).then_some(index);
        self.caches
            .iter()
            .enumerate()
            .map(|(index, cache)| (site(index), cache.clone()))
            .chain(self.protected.iter().enumerate().filter_map(|(index, cache)| cache.clone().map(|cache| (Some(index), cache))))
            .collect()
    }

    /// Where this page is kept, if it is one of the protected pages of the site asked for.
    fn protected_cache(&self, site: Option<usize>, path: &str) -> Option<Arc<Cache>> {
        let site = site?;
        self.protected.get(site)?.clone().filter(|_| self.config.protected(site, path))
    }

    /// Notes how long an instance took to answer a request of this kind (0 pages, 1 the rest).
    fn timed(&self, index: usize, kind: usize, took: Duration) {
        let sample = took.as_micros().min(u64::MAX as u128) as u64;
        let cell = &self.speed[index][kind];
        let old = cell.load(Ordering::Relaxed);
        // a moving average: a fifth of the newest
        let new = if old == 0 { sample.max(1) } else { (old * 4 + sample) / 5 };
        cell.store(new.max(1), Ordering::Relaxed);
    }

    /// The publish instances that answer for a path: those of the site the request's host is of,
    /// else of the site the path belongs to, else the general ones.
    fn instances_for(&self, site: Option<usize>, path: &str) -> &[usize] {
        match site.or_else(|| self.config.site_owning(path)) {
            Some(site) => &self.per_site[site],
            None => &self.general,
        }
    }
}

pub fn router(app: Arc<App>) -> Router {
    Router::new()
        .route("/dispatcher/invalidate.cache", any(invalidate))
        .route("/_live/status", get(status))
        .route("/_live/clear", post(clear))
        .route("/_live/explain", get(explain))
        .fallback(serve)
        .with_state(app)
}

/// Runs until the listener fails. The expiry task runs beside it.
pub async fn run(app: Arc<App>, listener: tokio::net::TcpListener) -> std::io::Result<()> {
    if (0..app.config.site.len()).any(|site| app.config.has_redirects(site)) {
        let app = app.clone();
        tokio::spawn(async move {
            loop {
                read_redirects(&app).await;
                tokio::time::sleep(Duration::from_secs(app.config.redirects.refresh_seconds.max(5))).await;
            }
        });
    }
    if app.config.vanity.url.is_some() {
        let app = app.clone();
        tokio::spawn(async move {
            loop {
                read_vanity(&app).await;
                tokio::time::sleep(Duration::from_secs(app.config.vanity.refresh_seconds.max(5))).await;
            }
        });
    }
    // each cache by its site's settings
    let caches: Vec<(Arc<Cache>, Option<Duration>, Duration)> = app
        .every_cache_of_site()
        .into_iter()
        .map(|(site, cache)| {
            let settings = app.config.cache_for(site);
            (cache, settings.ttl_seconds.map(Duration::from_secs), Duration::from_secs(settings.stale_seconds))
        })
        .collect();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(Duration::from_secs(60)).await;
            let caches = caches.clone();
            if let Ok(removed) = tokio::task::spawn_blocking(move || caches.iter().map(|(cache, ttl, stale_ttl)| cache.expire(*ttl, *stale_ttl)).sum::<usize>()).await {
                if removed > 0 {
                    tracing::info!(removed, "expired");
                }
            }
        }
    });
    // answers kept as long as their publish said: taken out within a second of their time, since
    // until then Apache serves the file
    let timed: Vec<Arc<Cache>> =
        app.every_cache_of_site().into_iter().filter(|(site, _)| app.config.cache_for(*site).enable_ttl).map(|(_, cache)| cache).collect();
    if !timed.is_empty() {
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(1)).await;
                let timed = timed.clone();
                if let Ok(removed) = tokio::task::spawn_blocking(move || timed.iter().map(|cache| cache.expire_due(now_ms())).sum::<usize>()).await {
                    if removed > 0 {
                        tracing::info!(removed, "over the time the publish gave them");
                    }
                }
            }
        });
    }
    axum::serve(listener, router(app).into_make_service_with_connect_info::<SocketAddr>()).await
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn plain(status: StatusCode, text: &'static str, verdict: &'static str) -> Response {
    let mut response = (status, text).into_response();
    response.headers_mut().insert(HEADER, HeaderValue::from_static(verdict));
    response
}

// ---------------------------------------------------------------------------------------------
// invalidation

/// May this request invalidate? With a secret: whoever sends it (as the password of basic
/// authentication, which is what a flush agent's transport password becomes, or as a bearer
/// token). Without one: only a direct connection from this machine; one that Apache proxied
/// arrives from this machine too, so anything that came through a proxy is refused.
fn may_invalidate(app: &App, peer: SocketAddr, headers: &HeaderMap) -> bool {
    match &app.config.flush.secret {
        Some(secret) => {
            let Some(auth) = headers.get(header::AUTHORIZATION).and_then(|v| v.to_str().ok()) else { return false };
            let sent = if let Some(token) = auth.strip_prefix("Bearer ") {
                Some(token.trim().to_owned())
            } else {
                auth.strip_prefix("Basic ")
                    .and_then(|b64| base64_decode(b64.trim()))
                    .and_then(|bytes| String::from_utf8(bytes).ok())
                    .and_then(|pair| pair.split_once(':').map(|(_, password)| password.to_owned()))
            };
            sent.is_some_and(|s| constant_time_eq(s.as_bytes(), secret.as_bytes()))
        }
        None => {
            peer.ip().is_loopback()
                && !["x-forwarded-for", "x-forwarded-host", "forwarded", "via"].iter().any(|h| headers.contains_key(*h))
        }
    }
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn base64_decode(text: &str) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let (mut buffer, mut bits) = (0u32, 0u32);
    for c in text.bytes() {
        let value = match c {
            b'A'..=b'Z' => c - b'A',
            b'a'..=b'z' => c - b'a' + 26,
            b'0'..=b'9' => c - b'0' + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => break,
            _ => return None,
        };
        buffer = (buffer << 6) | value as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((buffer >> bits) as u8);
            buffer &= (1 << bits) - 1;
        }
    }
    Some(out)
}

async fn invalidate(
    State(app): State<Arc<App>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    request: Request,
) -> Response {
    let (parts, body) = request.into_parts();
    let headers = &parts.headers;
    if parts.method != Method::POST && parts.method != Method::GET {
        return plain(StatusCode::METHOD_NOT_ALLOWED, "POST the invalidation\n", "invalidate");
    }
    if !may_invalidate(&app, peer, headers) {
        tracing::warn!(%peer, "invalidation refused");
        return plain(StatusCode::FORBIDDEN, "Not allowed to invalidate\n", "invalidate");
    }
    // a dispatcher's re-fetching flush: the pages to fetch again once the cache is invalidated, one
    // path a line in the body (application/octet-stream), as the flush agent that asks for it sends
    let refetch: Vec<String> = match axum::body::to_bytes(body, 64 * 1024).await {
        Ok(bytes) => String::from_utf8_lossy(&bytes)
            .lines()
            .map(str::trim)
            .filter(|line| line.starts_with('/'))
            .filter_map(|line| decode_path(line.split(['?', '#']).next().unwrap_or(line)))
            .take(MAX_REFETCH)
            .collect(),
        Err(_) => return plain(StatusCode::PAYLOAD_TOO_LARGE, "The list of pages to fetch again is too long\n", "invalidate"),
    };
    let text = |name: &str| headers.get(name).and_then(|v| v.to_str().ok()).map(str::to_owned);
    let Some(action) = text("cq-action").as_deref().and_then(Action::parse) else {
        return plain(StatusCode::BAD_REQUEST, "CQ-Action must be Activate, Deactivate, Delete or Test\n", "invalidate");
    };
    if action == Action::Test {
        return plain(StatusCode::OK, "OK\n", "invalidate");
    }
    let Some(handle) = text("cq-handle").or_else(|| text("cq-path")) else {
        return plain(StatusCode::BAD_REQUEST, "CQ-Handle is missing\n", "invalidate");
    };
    let resource_only = text("cq-action-scope").is_some_and(|s| s.eq_ignore_ascii_case("ResourceOnly"));

    // with sites: the caches of those the publication concerns; all, when it is nobody's in particular
    let concerned = app.config.sites_showing(&handle);
    let sites: Vec<&str> = concerned.iter().map(|site| app.config.site[*site].name.as_str()).collect();
    let targets: Vec<(Option<usize>, Arc<Cache>)> = if app.config.site.is_empty() {
        vec![(None, app.caches[0].clone())]
    } else if concerned.is_empty() {
        app.caches.iter().cloned().enumerate().map(|(site, cache)| (Some(site), cache)).collect()
    } else {
        concerned.iter().map(|site| (Some(*site), app.caches[*site].clone())).collect()
    };
    // something pages show: only the pages that say they show it (and those that say nothing), as
    // each site's settings say
    let by_deps = |site: Option<usize>| !resource_only && app.config.cache_for(site).invalidate_by_dependencies.iter().any(|g| glob(g, &handle));
    let targets: Vec<(Option<usize>, Arc<Cache>, bool)> = targets.into_iter().map(|(site, cache)| (site, cache, by_deps(site))).collect();
    // a protected page is not fetched again: only a visitor who may read it can get it
    let private: Vec<(Arc<Cache>, bool)> = targets
        .iter()
        .filter_map(|(site, _, deps)| site.and_then(|site| app.protected.get(site).cloned().flatten()).map(|cache| (cache, *deps)))
        .collect();
    let by_dependencies = targets.iter().any(|(_, _, deps)| *deps);
    // for a CDN in front: the tags to purge, site by site as each cache goes
    let mut purge: Vec<String> = Vec::new();
    if app.config.cdn.is_some() {
        for (site, _, deps) in &targets {
            let name = site.map_or("cache", |s| app.config.site[s].name.as_str());
            for tag in crate::tags::purge_tags(name, action, &handle, resource_only, *deps) {
                if !purge.contains(&tag) {
                    purge.push(tag);
                }
            }
        }
    }
    let path = handle.clone();
    let dependents = Arc::new(AtomicU64::new(0));
    let counted = dependents.clone();
    let removed = tokio::task::spawn_blocking(move || {
        crate::cache::record_taken();
        let mut total: Option<Listing> = None;
        let invalidate = |cache: &Arc<Cache>, by_dependencies: bool| {
            if by_dependencies {
                cache.invalidate_dependents(action, &path).map(|(removed, own, shown)| {
                    counted.fetch_add(shown as u64, Ordering::Relaxed);
                    (removed, own)
                })
            } else {
                cache.invalidate_listing(action, &path, resource_only, None)
            }
        };
        for (site, cache, deps) in targets {
            if let Some((removed, own)) = invalidate(&cache, deps) {
                let entry = total.get_or_insert((0, Vec::new()));
                entry.0 += removed;
                entry.1.extend(own.into_iter().map(|own| (site, own)));
            }
        }
        for (cache, deps) in private {
            if let Some((removed, _)) = invalidate(&cache, deps) {
                total.get_or_insert((0, Vec::new())).0 += removed;
            }
        }
        let taken = crate::cache::recorded();
        total.map(|total| (total, taken))
    })
    .await;
    match removed {
        Ok(Some(((removed, own), (taken, whole)))) => {
            if app.config.after_invalidation.is_set() {
                tokio::spawn(after_invalidation(app.clone(), format!("{action:?}"), handle.clone(), concerned.clone(), removed, taken, whole, purge));
            }
            let showing = dependents.load(Ordering::Relaxed);
            tracing::info!(?action, %handle, removed, by_dependencies, showing, sites = %if sites.is_empty() { "all".to_owned() } else { sites.join(", ") }, "invalidated");
            if (0..app.config.site.len()).any(|site| app.config.has_redirects(site)) {
                // the map itself may be what was published
                let app = app.clone();
                tokio::spawn(async move { read_redirects(&app).await });
            }
            if app.config.vanity.url.is_some() {
                // a publication may have given a page a vanity URL, or taken one away
                let app = app.clone();
                tokio::spawn(async move { read_vanity(&app).await });
            }
            let own: Vec<SitePath> = own.into_iter().filter(|(site, _)| app.config.cache_for(*site).rewarm).collect();
            if !own.is_empty() {
                tokio::spawn(rewarm(app.clone(), own, 20));
            }
            // the pages the flush asked to have fetched again, each for the site whose page it is
            // (a short URL is the page at its full path)
            let asked: Vec<SitePath> = refetch
                .iter()
                .map(|path| match &app.config.content_prefix {
                    Some(prefix) => full_path(prefix, path).unwrap_or_else(|| path.clone()),
                    None => path.clone(),
                })
                .filter_map(|path| {
                    if app.config.site.is_empty() {
                        Some((None, path))
                    } else {
                        app.config.site_owning(&path).map(|site| (Some(site), path))
                    }
                })
                .collect();
            let refetching = asked.len();
            if !asked.is_empty() {
                tokio::spawn(rewarm(app.clone(), asked, MAX_REFETCH));
            }
            app.stats.invalidations.fetch_add(1, Ordering::Relaxed);
            *app.stats.last_invalidation.lock().unwrap() = Some(serde_json::json!({
                "action": format!("{action:?}"), "path": handle, "removed": removed, "at": now_ms(), "sites": sites,
                "byDependencies": by_dependencies, "showing": dependents.load(Ordering::Relaxed), "refetching": refetching,
            }));
            let text = if refetching > 0 { format!("OK, {removed} removed, {refetching} to fetch again\n") } else { format!("OK, {removed} removed\n") };
            (StatusCode::OK, [(HEADER, "invalidate")], text).into_response()
        }
        Ok(None) => plain(StatusCode::BAD_REQUEST, "CQ-Handle is not a content path\n", "invalidate"),
        Err(_) => plain(StatusCode::INTERNAL_SERVER_ERROR, "Invalidation failed\n", "invalidate"),
    }
}

/// Empties the cache: for whoever may invalidate.
async fn clear(State(app): State<Arc<App>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, headers: HeaderMap) -> Response {
    if !may_invalidate(&app, peer, &headers) {
        tracing::warn!(%peer, "clearing refused");
        return plain(StatusCode::FORBIDDEN, "Not allowed to clear the cache\n", "clear");
    }
    let caches = app.every_cache();
    match tokio::task::spawn_blocking(move || caches.iter().map(|cache| cache.clear()).sum::<usize>()).await {
        Ok(removed) => {
            tracing::info!(removed, "cleared");
            if app.config.after_invalidation.is_set() {
                // everything went: the list is not worth making, and says it is not whole
                let purge: Vec<String> = if app.config.cdn.is_none() {
                    Vec::new()
                } else if app.config.site.is_empty() {
                    vec![crate::tags::all_tag("cache")]
                } else {
                    app.config.site.iter().map(|s| crate::tags::all_tag(&s.name)).collect()
                };
                tokio::spawn(after_invalidation(app.clone(), "Clear".into(), "/".into(), Vec::new(), removed, Vec::new(), false, purge));
            }
            app.stats.invalidations.fetch_add(1, Ordering::Relaxed);
            *app.stats.last_invalidation.lock().unwrap() = Some(serde_json::json!({
                "action": "Clear", "path": "/", "removed": removed, "at": now_ms(),
            }));
            (StatusCode::OK, [(HEADER, "clear")], format!("OK, {removed} removed\n")).into_response()
        }
        Err(_) => plain(StatusCode::INTERNAL_SERVER_ERROR, "Clearing failed\n", "clear"),
    }
}

async fn status(State(app): State<Arc<App>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, headers: HeaderMap) -> Response {
    if !may_invalidate(&app, peer, &headers) {
        return plain(StatusCode::FORBIDDEN, "Not allowed\n", "status");
    }
    let caches = app.caches.clone();
    let counts: Vec<(usize, usize, usize)> = tokio::task::spawn_blocking(move || caches.iter().map(|cache| cache.count()).collect()).await.unwrap_or_default();
    let private = app.protected.clone();
    let private_counts: Vec<Option<(usize, usize, usize)>> =
        tokio::task::spawn_blocking(move || private.iter().map(|cache| cache.as_ref().map(|cache| cache.count())).collect()).await.unwrap_or_default();
    let (plain_files, held, stale) = counts.iter().fold((0, 0, 0), |(a, b, c), (x, y, z)| (a + x, b + y, c + z));
    let now = now_ms();
    let publish: Vec<_> = app
        .instances
        .iter()
        .zip(&app.down_until)
        .zip(&app.speed)
        .map(|((url, until), speed)| serde_json::json!({
            "url": url, "skipped": until.load(Ordering::Relaxed) > now,
            // ms, lately: pages, the rest
            "answersIn": { "pages": speed[0].load(Ordering::Relaxed) / 1000, "other": speed[1].load(Ordering::Relaxed) / 1000 },
        }))
        .collect();
    let s = &app.stats;
    let body = serde_json::json!({
        "docroot": app.config.docroot,
        "sites": app.config.site.iter().enumerate().map(|(index, site)| serde_json::json!({
            "name": site.name, "hosts": site.hosts, "roots": site.roots, "docroot": app.caches[index].docroot(),
            "files": counts.get(index).map(|(plain, held, stale)| serde_json::json!({ "plain": plain, "held": held, "stale": stale })),
            "tracked": app.caches[index].tracked(),
            "timed": app.caches[index].timed(),
            "cache": site.cache.is_some().then(|| {
                let c = app.config.cache_for(Some(index));
                serde_json::json!({ "enableTtl": c.enable_ttl, "allowAuthorized": c.allow_authorized, "graceSeconds": c.grace_seconds, "staleSeconds": c.stale_seconds })
            }),
            "redirects": app.config.has_redirects(index).then(|| serde_json::json!({
                "map": site.redirect_map, "hosts": site.host_redirect_maps,
                "rules": app.redirects[index].read().unwrap().len(), "file": app.config.site_redirects_file(index),
            })),
            "protected": app.protected[index].as_ref().map(|cache| serde_json::json!({
                "globs": site.protected, "docroot": cache.docroot(),
                "files": private_counts.get(index).copied().flatten().map(|(plain, held, _)| plain + held),
            })),
        })).collect::<Vec<_>>(),
        "files": { "plain": plain_files, "held": held, "stale": stale },
        "staleSeconds": app.config.cache.stale_seconds,
        "hits": s.hits.load(Ordering::Relaxed),
        "stale": s.stale.load(Ordering::Relaxed),
        "graced": s.graced.load(Ordering::Relaxed),
        "graceSeconds": app.config.cache.grace_seconds,
        "stored": s.stored.load(Ordering::Relaxed),
        "rewarmed": s.rewarmed.load(Ordering::Relaxed),
        "passed": s.passed.load(Ordering::Relaxed),
        "redirected": s.redirected.load(Ordering::Relaxed),
        "checked": s.checked.load(Ordering::Relaxed),
        "refused": s.refused.load(Ordering::Relaxed),
        "denied": s.denied.load(Ordering::Relaxed),
        "errors": s.errors.load(Ordering::Relaxed),
        "vanityUrls": app.config.vanity.url.as_ref().map(|_| serde_json::json!({
            "count": app.vanity.read().unwrap().len(), "readAt": app.vanity_read_at.load(Ordering::Relaxed),
        })),
        "invalidations": s.invalidations.load(Ordering::Relaxed),
        "lastInvalidation": *s.last_invalidation.lock().unwrap(),
        "afterInvalidation": app.config.after_invalidation.is_set().then(|| serde_json::json!({
            "command": app.config.after_invalidation.command.first(), "url": app.config.after_invalidation.url,
            "told": s.hook_told.load(Ordering::Relaxed), "failed": s.hook_failed.load(Ordering::Relaxed),
            "last": *s.hook_last.lock().unwrap(),
        })),
        "publish": publish,
    });
    ([(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response()
}

// ---------------------------------------------------------------------------------------------
// pages

/// The cookie that keeps a signed-in visitor on one publish instance.
const ROUTE_COOKIE: &str = "studio-route";

/// A publish instance's name in the route cookie: a fingerprint of its address (FNV-1a).
fn route_id(publish_url: &str) -> String {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in publish_url.bytes() {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("{hash:016x}")
}

/// The value of a cookie of the request, if it has one by that name.
fn cookie<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get_all(header::COOKIE).iter().filter_map(|v| v.to_str().ok()).flat_map(|cookies| cookies.split(';')).find_map(|c| {
        let (n, value) = c.split_once('=')?;
        n.trim().eq_ignore_ascii_case(name).then_some(value.trim())
    })
}

/// Headers that belong to one connection, not to the message.
fn hop_by_hop(name: &HeaderName) -> bool {
    matches!(
        name.as_str(),
        "connection" | "keep-alive" | "proxy-authenticate" | "proxy-authorization" | "proxy-connection" | "te" | "trailer"
            | "transfer-encoding" | "upgrade"
    )
}

fn content_type(path: &str) -> &'static str {
    let extension = path.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    match extension.as_str() {
        "html" | "htm" => "text/html; charset=utf-8",
        "css" => "text/css; charset=utf-8",
        "js" | "mjs" => "text/javascript; charset=utf-8",
        "json" | "webmanifest" => "application/json",
        "xml" => "application/xml",
        "txt" => "text/plain; charset=utf-8",
        "svg" => "image/svg+xml",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "avif" => "image/avif",
        "ico" => "image/x-icon",
        "pdf" => "application/pdf",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "ttf" => "font/ttf",
        "mp4" | "m4v" => "video/mp4",
        "mov" => "video/quicktime",
        _ => "application/octet-stream",
    }
}

/// `type/subtype` in lower case, and whether the charset, if one is given, is UTF-8.
fn media_type(content_type: &str) -> (String, bool) {
    let mut parts = content_type.split(';');
    let media = parts.next().unwrap_or("").trim().to_ascii_lowercase();
    let utf8 = parts.filter_map(|p| p.trim().to_ascii_lowercase().strip_prefix("charset=").map(str::to_owned)).all(|c| {
        let c = c.trim_matches('"');
        c == "utf-8" || c == "utf8"
    });
    (media, utf8)
}

/// Would a web server, going by the extension, say the same type as the publish did? Then the
/// answer can be a plain file.
fn type_of_a_file(path: &str, publish_says: &str) -> bool {
    let (said, utf8) = media_type(publish_says);
    let (by_extension, _) = media_type(content_type(path));
    let same = |a: &str, b: &str| a == b || [a, b].iter().all(|t| matches!(*t, "text/javascript" | "application/javascript" | "application/x-javascript"))
        || [a, b].iter().all(|t| matches!(*t, "application/xml" | "text/xml"));
    by_extension != "application/octet-stream" && utf8 && same(&said, &by_extension)
}

/// The headers to keep with a cached answer: those the configuration lists, and with them, or
/// when the extension would say another type, the `Content-Type`. Empty: a plain file will do.
fn own_headers(path: &str, upstream: &reqwest::header::HeaderMap, keep: &[String]) -> Vec<(String, String)> {
    let mut kept = Vec::new();
    for name in keep {
        for value in upstream.get_all(name.as_str()).iter().filter_map(|v| v.to_str().ok()) {
            kept.push((name.clone(), value.to_owned()));
        }
    }
    let said = upstream.get("content-type").and_then(|v| v.to_str().ok());
    match said {
        Some(said) if !kept.is_empty() || !type_of_a_file(path, said) => kept.insert(0, ("Content-Type".to_owned(), said.to_owned())),
        _ => {}
    }
    kept
}

/// The CDN's tag header for an answer to a GET or HEAD of `path` (`None` without a `[cdn]`, or
/// for a path it leaves untagged).
fn cdn_tags(app: &App, site: Option<usize>, path: &str, shows: Option<&[String]>) -> Option<(HeaderName, HeaderValue)> {
    let cdn = app.config.cdn.as_ref()?;
    if cdn.untagged.iter().any(|g| glob(g, path)) {
        return None;
    }
    let (header, separator, max) = cdn.header();
    let name = site.map_or("cache", |s| app.config.site[s].name.as_str());
    let auto = app.config.cache_for(site).invalidate.iter().any(|g| glob(g, path));
    let tags = crate::tags::answer_tags(name, path, shows, auto, max);
    Some((HeaderName::from_bytes(header.as_bytes()).ok()?, HeaderValue::from_str(&tags.join(separator)).ok()?))
}

/// A cached answer as a response.
fn from_cache(entry: Entry, path: &str, head: bool, verdict: &'static str) -> Response {
    let mut response = Response::builder().header(header::CONTENT_LENGTH, entry.body.len()).header(HEADER, verdict);
    let mut typed = false;
    for (name, value) in &entry.headers {
        if let (Ok(name), Ok(value)) = (HeaderName::from_bytes(name.as_bytes()), HeaderValue::from_str(value)) {
            typed |= name == header::CONTENT_TYPE;
            response = response.header(name, value);
        }
    }
    if !typed {
        response = response.header(header::CONTENT_TYPE, content_type(path));
    }
    response.body(if head { Body::empty() } else { Body::from(entry.body) }).unwrap()
}

/// Why the answer to this request is neither taken from the cache nor put into it; `None`: it is
/// the same for everyone, so it may be.
fn not_cacheable(app: &App, site: Option<usize>, method: &Method, path: &str, query: &str, headers: &HeaderMap) -> Option<&'static str> {
    not_cacheable_as(app, site, method, path, query, headers, true)
}

/// [`not_cacheable`]; `for_everyone` false: for a protected page, which is checked for every
/// visitor it is served to, so a signed-in visitor's request may be answered from the cache. A site
/// with `allow_authorized` treats signed-in visitors as everyone else (a dispatcher's
/// `/allowAuthorized`).
fn not_cacheable_as(app: &App, site: Option<usize>, method: &Method, path: &str, query: &str, headers: &HeaderMap, for_everyone: bool) -> Option<&'static str> {
    let cache = app.config.cache_for(site);
    let for_everyone = for_everyone && !cache.allow_authorized;
    if method != Method::GET && method != Method::HEAD {
        return Some("only a GET or a HEAD is answered from the cache");
    }
    if cache_file(path).is_none() {
        return Some("the path has no extension in its last segment, or a segment no file can have");
    }
    if for_everyone && headers.contains_key(header::AUTHORIZATION) {
        return Some("the request carries credentials");
    }
    if !query_names(query).all(|name| cache.ignore_query.iter().any(|pattern| glob(pattern, name))) {
        return Some("the query has a parameter that is not listed in ignore_query");
    }
    if for_everyone && signed_in(app, site, headers) {
        return Some("the visitor is signed in (a login cookie)");
    }
    let mut verdict = Kind::Deny;
    for rule in &cache.rules {
        if glob(&rule.glob, path) {
            verdict = rule.kind;
        }
    }
    (verdict != Kind::Allow).then_some("a cache rule (cache.rules) says not to keep this path")
}

/// Does the request carry one of the login cookies?
fn signed_in(app: &App, site: Option<usize>, headers: &HeaderMap) -> bool {
    app.config.cache_for(site).login_cookies.iter().any(|name| cookie(headers, name).is_some())
}

fn cacheable_request(app: &App, site: Option<usize>, method: &Method, path: &str, query: &str, headers: &HeaderMap) -> bool {
    not_cacheable(app, site, method, path, query, headers).is_none()
}

/// How long the publish says an answer may be kept, for a site with `enable_ttl`.
#[derive(Debug, PartialEq)]
enum Ttl {
    /// It says nothing: kept until a publication takes it out.
    Unsaid,
    /// Until this time (ms since the epoch).
    Until(u64),
    /// Its time is over already: not kept.
    Over,
}

/// `Cache-Control: s-maxage` (for caches like this one), else `max-age`, less the `Age` it already
/// has; else `Expires`, measured against the publish's `Date` (its clock, not ours).
fn ttl_of(headers: &reqwest::header::HeaderMap, now: u64) -> Ttl {
    let directives: Vec<String> = headers
        .get_all("cache-control")
        .iter()
        .filter_map(|v| v.to_str().ok())
        .flat_map(|v| v.split(','))
        .map(|d| d.trim().to_ascii_lowercase())
        .collect();
    let seconds = |name: &str| directives.iter().find_map(|d| d.strip_prefix(name)?.strip_prefix('=')?.trim_matches('"').parse::<u64>().ok());
    let age = headers.get("age").and_then(|v| v.to_str().ok()).and_then(|v| v.trim().parse::<u64>().ok()).unwrap_or(0);
    if let Some(max) = seconds("s-maxage").or_else(|| seconds("max-age")) {
        let left = max.saturating_sub(age);
        return if left == 0 { Ttl::Over } else { Ttl::Until(now + left * 1000) };
    }
    let date = |name: &str| headers.get(name).and_then(|v| v.to_str().ok()).and_then(http_date);
    match headers.get("expires") {
        None => Ttl::Unsaid,
        Some(value) => match value.to_str().ok().and_then(http_date) {
            // an Expires that is no date (0, -1) means already over
            None => Ttl::Over,
            Some(expires) => {
                let base = date("date").unwrap_or(now / 1000);
                if expires <= base {
                    Ttl::Over
                } else {
                    Ttl::Until(now + (expires - base) * 1000)
                }
            }
        },
    }
}

/// An HTTP date (`Sun, 06 Nov 1994 08:49:37 GMT`) as seconds since the epoch.
fn http_date(text: &str) -> Option<u64> {
    let fields: Vec<&str> = text.trim().split_whitespace().collect();
    let [_, day, month, year, time, zone] = fields.as_slice() else { return None };
    if *zone != "GMT" {
        return None;
    }
    let month = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"].iter().position(|m| m == month)? as i64 + 1;
    let (day, year): (i64, i64) = (day.parse().ok()?, year.parse().ok()?);
    let hms: Vec<i64> = time.split(':').map(|n| n.parse().ok()).collect::<Option<_>>()?;
    let [h, m, s] = hms.as_slice() else { return None };
    if !(1..=31).contains(&day) || year < 1970 || *h > 23 || *m > 59 || *s > 60 {
        return None;
    }
    // days from the civil date (Howard Hinnant's algorithm)
    let y = if month <= 2 { year - 1 } else { year };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (month + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    u64::try_from(days * 86_400 + h * 3600 + m * 60 + s).ok()
}

/// May this answer of the publish be kept? Only a complete, public, unencoded 200.
fn cacheable_response(status: reqwest::StatusCode, headers: &reqwest::header::HeaderMap, ignore_cookies: &[String]) -> bool {
    if status != reqwest::StatusCode::OK {
        return false;
    }
    if headers.contains_key("content-encoding") || headers.contains_key("content-range") {
        return false;
    }
    // a cookie makes the answer one visitor's, unless it is one the configuration says means nothing
    let personal = headers.get_all("set-cookie").iter().any(|value| {
        let name = value.to_str().ok().and_then(|v| v.split('=').next()).map(str::trim).unwrap_or("");
        !ignore_cookies.iter().any(|ignored| ignored.eq_ignore_ascii_case(name))
    });
    if personal {
        return false;
    }
    if headers.get("dispatcher").and_then(|v| v.to_str().ok()).is_some_and(|v| v.eq_ignore_ascii_case("no-cache")) {
        return false;
    }
    !headers.get_all("cache-control").iter().filter_map(|v| v.to_str().ok()).any(|v| {
        let v = v.to_ascii_lowercase();
        v.contains("private") || v.contains("no-store") || v.contains("no-cache")
    })
}

async fn serve(State(app): State<Arc<App>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, request: Request) -> Response {
    let method = request.method().clone();
    let mut raw_path = request.uri().path().to_owned();
    let query = request.uri().query().unwrap_or("").to_owned();

    let Some(mut path) = decode_path(&raw_path) else {
        app.stats.denied.fetch_add(1, Ordering::Relaxed);
        return plain(StatusCode::BAD_REQUEST, "Bad request\n", "denied");
    };
    // whose host name this is
    let host_site = app.config.site_for_host(request.headers().get(header::HOST).and_then(|v| v.to_str().ok()));
    // a rule of the site's redirect map: answered here, as Apache's RewriteMap does in front
    let asked_host = request.headers().get(header::HOST).and_then(|v| v.to_str().ok()).map(str::to_owned);
    if let Some((status, location)) = redirect_for(&app, host_site, asked_host.as_deref(), &method, &path, &query) {
        app.stats.redirected.fetch_add(1, Ordering::Relaxed);
        let mut response = Response::new(Body::empty());
        *response.status_mut() = status;
        if let Ok(value) = HeaderValue::from_str(&location) {
            response.headers_mut().insert(header::LOCATION, value);
        }
        response.headers_mut().insert(HEADER, HeaderValue::from_static("redirect"));
        return response;
    }
    // a vanity URL of the publish: let through as it is, for the site whose page it leads to
    let vanity = (method == Method::GET || method == Method::HEAD).then(|| app.vanity.read().unwrap().get(&path).cloned()).flatten();
    // a short URL of the publish is the page at its full path
    if let (Some(prefix), None) = (&app.config.content_prefix, &vanity) {
        if let Some(full) = full_path(prefix, &path) {
            path = full;
            raw_path = format!("{prefix}{raw_path}");
        }
    }
    // whose host name this is, and whose page
    let site = app.config.site_for_host(request.headers().get(header::HOST).and_then(|v| v.to_str().ok()));
    let outside_why = match &vanity {
        Some(target) => outside(&app.config, site, target),
        None => outside(&app.config, site, &path),
    };
    if let Some(why) = outside_why {
        app.stats.denied.fetch_add(1, Ordering::Relaxed);
        tracing::debug!(%method, %path, ?why, "not this site's");
        return plain(StatusCode::NOT_FOUND, "Not found\n", "denied");
    }
    let parts = decompose(&path);
    let asked = filter::Request { method: method.as_str(), url: &path, query: &query, parts: &parts };
    let site_rules: &[filter::Rule] = site.map_or(&[], |site| &app.config.site[site].filter);
    if vanity.is_none() && !filter::decide_with(&app.config.filter, site_rules, &asked).0 {
        app.stats.denied.fetch_add(1, Ordering::Relaxed);
        tracing::debug!(%method, %path, "denied by the filter");
        return plain(StatusCode::NOT_FOUND, "Not found\n", "denied");
    }

    // a protected page is kept apart and served only to whom the publish says may read it
    let protected = app.protected_cache(site, &path);
    let settings = app.config.cache_for(site);
    let cacheable = not_cacheable_as(&app, site, &method, &path, &query, request.headers(), protected.is_none()).is_none();
    let host = request.headers().get(header::HOST).cloned();
    // the publish instance this visitor was sent to before, if the visitor has one and it is still in the list
    let instances = app.instances_for(site, &path);
    let sticky = app.config.sticky && instances.len() > 1;
    let pinned = cookie(request.headers(), ROUTE_COOKIE)
        .and_then(|id| app.route_ids.iter().position(|r| r == id))
        .filter(|index| sticky && instances.contains(index));
    let knows_visitor = signed_in(&app, site, request.headers());
    let secure = request.headers().get("x-forwarded-proto").and_then(|v| v.to_str().ok()).is_some_and(|p| p.eq_ignore_ascii_case("https"));
    if cacheable {
        let (cache, cached_path) = (protected.clone().unwrap_or_else(|| app.cache_of(site).clone()), path.clone());
        if let Ok(Some(entry)) = tokio::task::spawn_blocking(move || cache.read(&cached_path)).await {
            if protected.is_none() {
                app.stats.hits.fetch_add(1, Ordering::Relaxed);
                return from_cache(entry, &path, method == Method::HEAD, "hit");
            }
            if may_read(&app, site, instances, request.headers(), &path, host.as_ref()).await {
                app.stats.hits.fetch_add(1, Ordering::Relaxed);
                app.stats.checked.fetch_add(1, Ordering::Relaxed);
                let mut response = from_cache(entry, &path, method == Method::HEAD, "hit-checked");
                response.headers_mut().insert(header::CACHE_CONTROL, HeaderValue::from_static("private"));
                return response;
            }
            // not for this visitor: the publish answers, as it would without the cache (a sign-in page, a 404)
            app.stats.refused.fetch_add(1, Ordering::Relaxed);
        }
        // just taken out by a publication: as it was, at once, while it is fetched again
        if protected.is_none() && settings.grace_seconds > 0 {
            let cache = app.cache_of(site).clone();
            let since = now_ms().saturating_sub(cache.invalidated_at());
            if cache.invalidated_at() > 0 && since < settings.grace_seconds * 1000 {
                let cached_path = path.clone();
                if let Ok(Some(entry)) = tokio::task::spawn_blocking(move || cache.read_stale(&cached_path)).await {
                    app.stats.graced.fetch_add(1, Ordering::Relaxed);
                    refresh(app.clone(), site, path.clone(), host.clone());
                    return from_cache(entry, &path, method == Method::HEAD, "grace");
                }
            }
        }
    }

    // what the publish is asked: the visitor's request, minus what is ours to decide
    let mut upstream_headers = reqwest::header::HeaderMap::new();
    for (name, value) in request.headers() {
        if hop_by_hop(name) || name == header::ACCEPT_ENCODING || name == header::CONTENT_LENGTH {
            continue;
        }
        if !app.config.forwards_header(site, name.as_str()) {
            continue; // not one the publish is to be told
        }
        if name == header::COOKIE {
            // the route cookie is this service's, not the publish's
            let rest: Vec<&str> = value
                .to_str()
                .unwrap_or("")
                .split(';')
                .map(str::trim)
                .filter(|c| !c.is_empty() && !c.split('=').next().is_some_and(|n| n.trim().eq_ignore_ascii_case(ROUTE_COOKIE)))
                .collect();
            if let Ok(value) = HeaderValue::from_str(&rest.join("; ")) {
                if !rest.is_empty() {
                    upstream_headers.append(name.clone(), value);
                }
            }
            continue;
        }
        if cacheable && matches!(name.as_str(), "if-none-match" | "if-modified-since" | "range" | "if-range") {
            continue; // the whole page is wanted, to be kept
        }
        upstream_headers.append(name.clone(), value.clone());
    }
    // the chain the visitor's request came along, if the publish is told it, with the last hop
    let chain = request.headers().get("x-forwarded-for").filter(|_| app.config.forwards_header(site, "x-forwarded-for"));
    let forwarded = match chain.and_then(|v| v.to_str().ok()) {
        Some(chain) => format!("{chain}, {}", peer.ip()),
        None => peer.ip().to_string(),
    };
    if let Ok(value) = HeaderValue::from_str(&forwarded) {
        upstream_headers.insert("x-forwarded-for", value);
    }

    let keeper = protected.clone().unwrap_or_else(|| app.cache_of(site).clone());
    let generation = keeper.generation();
    let target = if query.is_empty() { raw_path.clone() } else { format!("{raw_path}?{query}") };
    let retry = method == Method::GET || method == Method::HEAD;
    let mut body = Some(request.into_body());
    let count = instances.len();
    let start = app.next.fetch_add(1, Ordering::Relaxed);
    let now = now_ms();
    // the instances in turn, those that failed lately last
    let mut order: Vec<usize> = (0..count).map(|i| instances[(start + i) % count]).collect();
    let kind = usize::from(!path.ends_with(".html"));
    let fastest = app.config.balance == crate::config::Balance::Fastest;
    // those that failed lately last; among the others, the visitor's own first; then, when asked
    // for, the quickest lately (in turn among equals, and one not timed yet first: it is tried)
    order.sort_by_key(|i| {
        let speed = if fastest { app.speed[*i][kind].load(Ordering::Relaxed) } else { 0 };
        (app.down_until[*i].load(Ordering::Relaxed) > now, Some(*i) != pinned, speed)
    });

    let mut answer = None;
    let mut answered_by = None;
    for index in order {
        let mut call = app
            .client
            .request(method.clone(), format!("{}{target}", app.instances[index]))
            .headers(upstream_headers.clone());
        if !retry {
            if let Some(body) = body.take() {
                call = call.body(reqwest::Body::wrap_stream(body.into_data_stream()));
            }
        }
        let asked_at = std::time::Instant::now();
        let sent = call.send().await;
        // a 500: the page's own, unless the instance's health check fails too
        let sick = match &sent {
            Ok(response) if retry && response.status() == reqwest::StatusCode::INTERNAL_SERVER_ERROR => match &app.config.health_check {
                Some(check) => !healthy(&app, index, check).await,
                None => false,
            },
            _ => false,
        };
        match sent {
            Ok(response) if retry && (sick || matches!(response.status().as_u16(), 502..=504)) => {
                if sick {
                    tracing::warn!(publish = %app.instances[index], "the publish answered 500 and its health check failed: skipped for now");
                }
                mark_down(&app, index);
                answer = Some(response);
                answered_by = None;
            }
            Ok(response) => {
                app.timed(index, kind, asked_at.elapsed());
                app.down_until[index].store(0, Ordering::Relaxed);
                answer = Some(response);
                answered_by = Some(index);
                break;
            }
            Err(error) => {
                tracing::warn!(publish = %app.instances[index], %error, "publish did not answer");
                mark_down(&app, index);
            }
        }
        if !retry {
            break; // a request with a body is sent once
        }
    }
    // the publish cannot be asked, or fails: the page as it was before the last publication, if kept
    if cacheable && protected.is_none() && answer.as_ref().is_none_or(|a| a.status().is_server_error()) {
        let (cache, cached_path) = (app.cache_of(site).clone(), path.clone());
        if let Ok(Some(entry)) = tokio::task::spawn_blocking(move || cache.read_stale(&cached_path)).await {
            app.stats.stale.fetch_add(1, Ordering::Relaxed);
            tracing::warn!(%path, "the publish does not answer: served as it was before the last publication");
            return from_cache(entry, &path, method == Method::HEAD, "stale");
        }
    }
    let Some(upstream) = answer else {
        app.stats.errors.fetch_add(1, Ordering::Relaxed);
        return plain(StatusCode::BAD_GATEWAY, "The site is not available at the moment\n", "error");
    };

    let status = StatusCode::from_u16(upstream.status().as_u16()).unwrap_or(StatusCode::BAD_GATEWAY);
    let mut headers = HeaderMap::new();
    for (name, value) in upstream.headers() {
        // what a page shows is for this service, not for the visitor
        if !hop_by_hop(name) && name != header::CONTENT_LENGTH && name.as_str() != DEPENDS {
            headers.append(name.clone(), value.clone());
        }
    }
    // for a CDN in front: what this answer is and shows, as the tags it purges by
    let tagged = if method == Method::GET || method == Method::HEAD { cdn_tags(&app, site, &path, depends(upstream.headers()).as_deref()) } else { None };
    if let Some((name, value)) = &tagged {
        headers.insert(name.clone(), value.clone());
    }
    let limit = settings.max_file_mb.saturating_mul(1024 * 1024);
    // with enable_ttl: as long as the publish says, and not at all when its time is over already
    let ttl = if settings.enable_ttl { ttl_of(upstream.headers(), now_ms()) } else { Ttl::Unsaid };
    let keep = cacheable
        && method == Method::GET
        && ttl != Ttl::Over
        && cacheable_response(upstream.status(), upstream.headers(), &settings.ignore_cookies)
        && upstream.content_length().is_none_or(|length| length <= limit);

    let (body, verdict) = if keep {
        // the next visitor gets the file without it; so does this one
        headers.remove(header::SET_COOKIE);
        let mut own = own_headers(&path, upstream.headers(), &settings.headers);
        if let Some((name, value)) = &tagged {
            // kept with the answer, which the service then serves itself: Apache's files carry no headers
            if !own.iter().any(|(n, _)| n.eq_ignore_ascii_case("content-type")) {
                if let Some(said) = upstream.headers().get("content-type").and_then(|v| v.to_str().ok()) {
                    own.insert(0, ("Content-Type".to_owned(), said.to_owned()));
                }
            }
            own.push((name.as_str().to_owned(), value.to_str().unwrap_or("").to_owned()));
        }
        let shows = depends(upstream.headers());
        let until = match ttl {
            Ttl::Until(until) => Some(until),
            _ => None,
        };
        match buffer(upstream, limit).await {
            Buffered::Whole(bytes) => {
                let (cache, cached_path, page) = (keeper.clone(), path.clone(), bytes.clone());
                let stored = tokio::task::spawn_blocking(move || {
                    let stored = cache.store_with(&cached_path, generation, &page, &own, shows.as_deref());
                    if matches!(stored, Ok(true)) {
                        cache.set_expiry(&cached_path, until);
                    }
                    stored
                })
                .await;
                if protected.is_some() {
                    headers.insert(header::CACHE_CONTROL, HeaderValue::from_static("private"));
                }
                let verdict = match stored {
                    Ok(Ok(true)) if protected.is_some() => {
                        app.stats.stored.fetch_add(1, Ordering::Relaxed);
                        "stored-protected"
                    }
                    Ok(Ok(true)) => {
                        app.stats.stored.fetch_add(1, Ordering::Relaxed);
                        if let Some(host) = &host {
                            let mut hosts = app.hosts.lock().unwrap();
                            let key = host_key(site, &path);
                            if hosts.len() < 1000 || hosts.contains_key(&key) {
                                hosts.insert(key, host.clone());
                            }
                        }
                        "stored"
                    }
                    Ok(Err(error)) => {
                        tracing::warn!(%path, %error, "could not write to the cache");
                        "pass"
                    }
                    _ => "pass",
                };
                (Body::from(bytes), verdict)
            }
            Buffered::TooLarge(stream) => (stream, "pass"),
            Buffered::Failed => {
                app.stats.errors.fetch_add(1, Ordering::Relaxed);
                return plain(StatusCode::BAD_GATEWAY, "The site is not available at the moment\n", "error");
            }
        }
    } else {
        if let Some(length) = upstream.content_length() {
            headers.insert(header::CONTENT_LENGTH, HeaderValue::from(length));
        }
        // an answer for this visitor alone: the visitor stays with the publish instance that gave it
        let personal = knows_visitor || pinned.is_some() || upstream.headers().contains_key("set-cookie");
        if let (true, true, Some(index)) = (sticky, personal, answered_by) {
            if pinned != Some(index) {
                let route = format!("{ROUTE_COOKIE}={}; Path=/; HttpOnly; SameSite=Lax{}", app.route_ids[index], if secure { "; Secure" } else { "" });
                if let Ok(value) = HeaderValue::from_str(&route) {
                    headers.append(header::SET_COOKIE, value);
                }
            }
        }
        (Body::from_stream(upstream.bytes_stream()), "pass")
    };
    if verdict == "pass" {
        app.stats.passed.fetch_add(1, Ordering::Relaxed);
    }
    headers.insert(HEADER, HeaderValue::from_static(verdict));
    let mut response = Response::new(body);
    *response.status_mut() = status;
    *response.headers_mut() = headers;
    response
}

/// The redirect a site's maps hold for this request: the rule of the host's own map, else of the
/// site's; its status and where to, with the query of the request when the target has none (as
/// Apache's `R` flag does).
fn redirect_for(app: &App, site: Option<usize>, host: Option<&str>, method: &Method, path: &str, query: &str) -> Option<(StatusCode, String)> {
    let site = site?;
    if (method != Method::GET && method != Method::HEAD) || !app.config.has_redirects(site) {
        return None;
    }
    let rules = app.redirects[site].read().unwrap();
    let own = host.map(|host| format!("{}{path}", crate::config::host_name(host))).and_then(|key| rules.get(&key).cloned());
    let (status, target) = own.or_else(|| rules.get(path).cloned())?;
    let location = if query.is_empty() || target.contains('?') { target } else { format!("{target}?{query}") };
    Some((StatusCode::from_u16(status).ok()?, location))
}

/// One redirect map: `<source> <target> [status]` a line. Bad lines are left out and counted.
pub fn parse_redirect_map(text: &str) -> (std::collections::HashMap<String, (u16, String)>, usize) {
    let mut rules = std::collections::HashMap::new();
    let mut bad = 0;
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let fields: Vec<&str> = line.split_whitespace().collect();
        let status = match fields.get(2) {
            None => Some(301),
            Some(code) => code.parse::<u16>().ok().filter(|c| matches!(c, 301 | 302 | 303 | 307 | 308)),
        };
        let source_ok = fields.first().is_some_and(|s| decode_path(s).as_deref() == Some(*s) && !s.contains(';'));
        let target_ok = fields.get(1).is_some_and(|t| {
            (t.starts_with('/') && !t.starts_with("//")) || t.starts_with("https://") || t.starts_with("http://")
        }) && fields.get(1).is_some_and(|t| t.bytes().all(|b| b > 0x20 && b != b'|' && b < 0x7f));
        match (fields.len() <= 3, source_ok, target_ok, status) {
            (true, true, true, Some(status)) if rules.len() < 50_000 => {
                rules.entry(fields[0].to_owned()).or_insert((status, fields[1].to_owned()));
            }
            _ => bad += 1,
        }
    }
    (rules, bad)
}

/// Reads every site's redirect map from the publish (as an anonymous visitor: the map is an asset
/// everyone may read), keeps it, and writes it for Apache as `<source> <status>|<target>` lines. A
/// map that is gone (404) is empty; one that cannot be read is kept as it was.
async fn read_redirects(app: &App) {
    'sites: for site in 0..app.config.site.len() {
        if !app.config.has_redirects(site) {
            continue;
        }
        // the site's map, then each host's own, whose rules are kept as <host><source>
        let maps = app.config.site[site]
            .redirect_map
            .iter()
            .map(|map| (String::new(), map.clone()))
            .chain(app.config.site[site].host_redirect_maps.iter().map(|(host, map)| (host.clone(), map.clone())));
        let mut rules = std::collections::HashMap::new();
        let mut bad = 0;
        for (host, map) in maps {
            // one that cannot be read: the site's rules stay as they were
            let Some(text) = read_map(app, site, &map).await else { continue 'sites };
            let (read, wrong) = parse_redirect_map(&text);
            bad += wrong;
            for (source, rule) in read {
                rules.entry(format!("{host}{source}")).or_insert(rule);
            }
        }
        // unchanged, and still there for the web server (someone may have emptied the directory)
        if *app.redirects[site].read().unwrap() == rules && app.config.site_redirects_file(site).exists() {
            continue;
        }
        let mut lines: Vec<String> = rules.iter().map(|(source, (status, target))| format!("{source} {status}|{target}\n")).collect();
        lines.sort();
        let file = app.config.site_redirects_file(site);
        let temp = file.with_extension("txt.tmp");
        if let Err(error) = std::fs::write(&temp, lines.concat()).and_then(|_| std::fs::rename(&temp, &file)) {
            tracing::warn!(file = %file.display(), %error, "the redirect map could not be written for the web server");
        }
        tracing::info!(site = %app.config.site[site].name, rules = rules.len(), bad, "redirect map read");
        *app.redirects[site].write().unwrap() = rules;
    }
}

/// One redirect map from the publish, as an anonymous visitor; empty when it is gone (404), None
/// when no instance could say.
async fn read_map(app: &App, site: usize, map: &str) -> Option<String> {
    let now = now_ms();
    let mut order: Vec<usize> = app.instances_for(Some(site), map).to_vec();
    order.sort_by_key(|i| app.down_until[*i].load(Ordering::Relaxed) > now);
    for index in order {
        match app.client.get(format!("{}{}", app.instances[index], encode_path(map))).send().await {
            Ok(answer) if answer.status() == reqwest::StatusCode::OK => {
                if let Ok(bytes) = answer.bytes().await {
                    return Some(String::from_utf8_lossy(&bytes).into_owned());
                }
            }
            Ok(answer) if answer.status() == reqwest::StatusCode::NOT_FOUND => return Some(String::new()),
            Ok(answer) => tracing::warn!(publish = %app.instances[index], status = %answer.status(), %map, "the redirect map could not be read"),
            Err(error) => tracing::warn!(publish = %app.instances[index], %error, %map, "the redirect map could not be read"),
        }
    }
    None
}

/// Reads the publish's vanity URLs (`vanity.url`, as an anonymous visitor: only those of pages
/// everyone may read) from the first instance that answers. Kept as they were when none does.
async fn read_vanity(app: &App) {
    let Some(url) = &app.config.vanity.url else { return };
    let now = now_ms();
    let mut order: Vec<usize> = (0..app.instances.len()).collect();
    order.sort_by_key(|i| app.down_until[*i].load(Ordering::Relaxed) > now);
    for index in order {
        let answer = match app.client.get(format!("{}{url}", app.instances[index])).send().await {
            Ok(answer) if answer.status() == reqwest::StatusCode::OK => answer,
            Ok(answer) => {
                tracing::warn!(publish = %app.instances[index], status = %answer.status(), "the vanity URLs could not be read");
                continue;
            }
            Err(error) => {
                tracing::warn!(publish = %app.instances[index], %error, "the vanity URLs could not be read");
                continue;
            }
        };
        let Ok(bytes) = answer.bytes().await else { continue };
        let Ok(body) = serde_json::from_slice::<serde_json::Value>(&bytes) else { continue };
        let mut map = std::collections::HashMap::new();
        for v in body["vanityUrls"].as_array().into_iter().flatten() {
            let (Some(path), Some(target)) = (v["path"].as_str(), v["target"].as_str()) else { continue };
            // only what a visitor could have asked for, leading to content
            if decode_path(path).as_deref() == Some(path) && !path.contains(';') && crate::config::under(target, "/content") {
                map.insert(path.to_owned(), target.to_owned());
            }
        }
        let count = map.len();
        let changed = *app.vanity.read().unwrap() != map;
        *app.vanity.write().unwrap() = map;
        app.vanity_read_at.store(now_ms(), Ordering::Relaxed);
        if changed {
            tracing::info!(count, publish = %app.instances[index], "vanity URLs read");
        }
        return;
    }
}

/// Asks the publish whether this visitor may read a protected page: `cache.auth_checker` with
/// `?uri=<path>` and the visitor's cookies and credentials. Only a 200 is a yes; no answer is a no.
async fn may_read(app: &App, site: Option<usize>, instances: &[usize], headers: &HeaderMap, path: &str, host: Option<&HeaderValue>) -> bool {
    let now = now_ms();
    let Some(index) =
        instances.iter().copied().find(|i| app.down_until[*i].load(Ordering::Relaxed) <= now).or_else(|| instances.first().copied())
    else {
        return false;
    };
    let url = format!("{}{}?uri={}", app.instances[index], app.config.cache_for(site).auth_checker, query_value(path));
    let mut call = app.client.head(url);
    for name in [header::COOKIE, header::AUTHORIZATION] {
        for value in headers.get_all(&name) {
            call = call.header(name.clone(), value.clone());
        }
    }
    if let Some(host) = host {
        call = call.header(header::HOST, host.clone());
    }
    match call.send().await {
        Ok(answer) => answer.status() == reqwest::StatusCode::OK,
        Err(error) => {
            tracing::warn!(publish = %app.instances[index], %error, "the permission check did not answer");
            mark_down(app, index);
            false
        }
    }
}

/// A value for a query string: every byte but letters, digits and `/-._~` as `%XX`.
fn query_value(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || b"/-._~".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

/// Why a request is not answered under the host name it came with, once sites are declared: what
/// no site declares is nobody's to see.
#[derive(Debug, PartialEq)]
enum Outside {
    /// The host name is no declared site's.
    UnknownHost,
    /// Content that is neither in the site's roots nor in what it lists as shared.
    NotItsContent,
}

fn outside(config: &Config, host_site: Option<usize>, path: &str) -> Option<Outside> {
    if config.site.is_empty() {
        return None;
    }
    let Some(asked) = host_site else { return Some(Outside::UnknownHost) };
    let content = config.content_prefix.as_deref().unwrap_or("/content");
    let site = &config.site[asked];
    let its = site.roots.iter().chain(&site.shared).any(|base| crate::config::under(path, base));
    (crate::config::under(path, content) && !its).then_some(Outside::NotItsContent)
}

/// A path in the cache of a site (None: the one cache, with no sites).
type SitePath = (Option<usize>, String);
/// What an invalidation took out: how many files, and the published resource's own renderings.
type Listing = (usize, Vec<SitePath>);

/// Tells the after-invalidation command and URL what an invalidation took out.
#[allow(clippy::too_many_arguments)]
async fn after_invalidation(
    app: Arc<App>,
    action_name: String,
    handle: String,
    concerned: Vec<usize>,
    removed: usize,
    paths: Vec<String>,
    whole: bool,
    tags: Vec<String>,
) {
    let hook = &app.config.after_invalidation;
    // the sites concerned (all of them when it is nobody's in particular) and their host names
    let sites: Vec<usize> = if concerned.is_empty() { (0..app.config.site.len()).collect() } else { concerned };
    let hosts: Vec<&str> =
        sites.iter().flat_map(|s| app.config.site[*s].hosts.iter().map(String::as_str)).filter(|h| !h.contains('*')).collect();
    let names: Vec<&str> = sites.iter().map(|s| app.config.site[*s].name.as_str()).collect();
    let timeout = Duration::from_secs(hook.timeout_seconds);
    let mut failures: Vec<String> = Vec::new();
    if let Some((program, args)) = hook.command.split_first() {
        let run = async {
            let mut child = tokio::process::Command::new(program)
                .args(args)
                .arg(&action_name)
                .arg(&handle)
                .env("CQ_ACTION", &action_name)
                .env("CQ_HANDLE", &handle)
                .env("STUDIO_LIVE_SITES", names.join(","))
                .env("STUDIO_LIVE_HOSTS", hosts.join(","))
                .env("STUDIO_LIVE_WHOLE", whole.to_string())
                .env("STUDIO_LIVE_TAGS", tags.join(" "))
                .stdin(std::process::Stdio::piped())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::piped())
                .kill_on_drop(true)
                .spawn()
                .map_err(|e| format!("{program}: {e}"))?;
            if let Some(mut stdin) = child.stdin.take() {
                use tokio::io::AsyncWriteExt;
                let list: String = paths.iter().map(|p| format!("{p}\n")).collect();
                // a program that does not read them may close its input: not a failure
                let _ = stdin.write_all(list.as_bytes()).await;
            }
            let output = child.wait_with_output().await.map_err(|e| format!("{program}: {e}"))?;
            if output.status.success() {
                Ok(())
            } else {
                let said = String::from_utf8_lossy(&output.stderr);
                let said = said.trim();
                let tail: String = said.chars().rev().take(300).collect::<Vec<_>>().into_iter().rev().collect();
                Err(format!("{program}: {}{}", output.status, if tail.is_empty() { String::new() } else { format!(": {tail}") }))
            }
        };
        match tokio::time::timeout(timeout, run).await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => failures.push(e),
            Err(_) => failures.push(format!("{program}: no end within {} s", hook.timeout_seconds)),
        }
    }
    if let Some(url) = &hook.url {
        let body = serde_json::json!({
            "action": action_name, "handle": handle, "removed": removed, "whole": whole, "paths": paths, "tags": tags,
            "sites": sites.iter().map(|s| serde_json::json!({
                "name": app.config.site[*s].name,
                "hosts": app.config.site[*s].hosts.iter().filter(|h| !h.contains('*')).collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
        });
        let mut request =
            app.client.post(url).timeout(timeout).header(reqwest::header::CONTENT_TYPE, "application/json").body(body.to_string());
        let mut ready = true;
        for (name, value) in &hook.headers {
            let value = match value.strip_prefix("env:") {
                Some(variable) => match std::env::var(variable) {
                    Ok(v) => v,
                    Err(_) => {
                        failures.push(format!("{url}: the environment has no {variable} for {name}"));
                        ready = false;
                        break;
                    }
                },
                None => value.clone(),
            };
            request = request.header(name.as_str(), value);
        }
        if ready {
            match request.send().await {
                Ok(answer) if answer.status().is_success() => {}
                Ok(answer) => failures.push(format!("{url}: {}", answer.status())),
                Err(e) => failures.push(format!("{url}: {e}")),
            }
        }
    }
    let at = now_ms();
    if failures.is_empty() {
        app.stats.hook_told.fetch_add(1, Ordering::Relaxed);
        tracing::info!(action = %action_name, %handle, paths = paths.len(), whole, "told after the invalidation");
    } else {
        app.stats.hook_failed.fetch_add(1, Ordering::Relaxed);
        tracing::warn!(action = %action_name, %handle, failures = %failures.join("; "), "after the invalidation: not told");
    }
    *app.stats.hook_last.lock().unwrap() = Some(serde_json::json!({
        "action": action_name, "path": handle, "paths": paths.len(), "whole": whole, "at": at,
        "ok": failures.is_empty(), "error": (!failures.is_empty()).then(|| failures.join("; ")),
    }));
}

/// Under which key the host name of a kept page's visitor is remembered: the site whose cache it
/// went into, and the first two segments of its path.
fn host_key(site: Option<usize>, path: &str) -> String {
    format!("{}:{}", site.map_or(String::new(), |site| site.to_string()), site_of(path))
}

/// The site a path belongs to, as far as a path says: its first two segments (`/content/wknd`).
fn site_of(path: &str) -> &str {
    let mut slashes = path.match_indices('/').map(|(i, _)| i);
    match slashes.nth(2) {
        Some(end) => &path[..end],
        None => path,
    }
}

/// After a publication: fetches the published resource's own renderings that were in the cache
/// again, as a visitor who is not signed in would, and keeps them. Quietly gives up on whatever
/// does not work: the next visitor's request does the same with an answer for the visitor.
///
/// With several publish instances every one of them tells the cache once it has the content, so a
/// second flush may come while a page is being fetched again; what was fetched is then not kept (it
/// may be from an instance that did not have the new content yet), and the page is fetched once more.
async fn rewarm(app: Arc<App>, paths: Vec<SitePath>, max: usize) {
    for (site, path) in paths.into_iter().take(max) {
        // fetched for the site whose cache it was in (with sites declared every cache is a site's)
        let owner = site;
        let limit = app.config.cache_for(owner).max_file_mb.saturating_mul(1024 * 1024);
        // under the name its visitors used; nobody came yet: the site's own first name
        let host = app.hosts.lock().unwrap().get(&host_key(owner, &path)).cloned().or_else(|| {
            let name = app.config.site[owner?].hosts.iter().find(|host| !host.contains('*') && !host.contains('?'))?;
            HeaderValue::from_str(name).ok()
        });
        let parts = decompose(&path);
        let asked = filter::Request { method: "GET", url: &path, query: "", parts: &parts };
        let site_rules: &[filter::Rule] = owner.map_or(&[], |site| &app.config.site[site].filter);
        if !filter::decide_with(&app.config.filter, site_rules, &asked).0 || !cacheable_request(&app, owner, &Method::GET, &path, "", &HeaderMap::new()) {
            continue;
        }
        for _ in 0..3 {
            let generation = app.cache_of(owner).generation();
            match fetch_again(&app, owner, &path, host.as_ref(), generation, limit).await {
                Some(true) => {
                    app.stats.rewarmed.fetch_add(1, Ordering::Relaxed);
                    tracing::info!(%path, "fetched again after its publication");
                    break;
                }
                // not kept because a flush came meanwhile: once more, with what the instances have now
                Some(false) if app.cache_of(owner).generation() != generation => continue,
                _ => break,
            }
        }
    }
}

/// Fetches a page again in the background, once at a time per page, for the grace period: up to
/// three times when a flush comes during the fetch, as [`rewarm`] does.
fn refresh(app: Arc<App>, site: Option<usize>, path: String, host: Option<HeaderValue>) {
    let key = host_key(site, &path);
    if !app.refreshing.lock().unwrap().insert(key.clone()) {
        return; // already on its way
    }
    tokio::spawn(async move {
        let limit = app.config.cache_for(site).max_file_mb.saturating_mul(1024 * 1024);
        for _ in 0..3 {
            let generation = app.cache_of(site).generation();
            match fetch_again(&app, site, &path, host.as_ref(), generation, limit).await {
                Some(false) if app.cache_of(site).generation() != generation => continue,
                Some(true) => {
                    tracing::info!(%path, "fetched again while served as it was");
                    break;
                }
                _ => break,
            }
        }
        app.refreshing.lock().unwrap().remove(&key);
    });
}

/// One attempt of [`rewarm`]: Some(true) kept, Some(false) fetched but not kept, None nothing to keep.
async fn fetch_again(app: &App, owner: Option<usize>, path: &str, host: Option<&HeaderValue>, generation: u64, limit: u64) -> Option<bool> {
    let now = now_ms();
    let index = app.instances_for(owner, path).iter().copied().find(|i| app.down_until[*i].load(Ordering::Relaxed) <= now)?;
    let mut call = app.client.get(format!("{}{}", app.instances[index], encode_path(path)));
    if let Some(host) = host {
        call = call.header(header::HOST, host.clone());
    }
    let upstream = call.send().await.ok()?;
    let settings = app.config.cache_for(owner);
    let ttl = if settings.enable_ttl { ttl_of(upstream.headers(), now_ms()) } else { Ttl::Unsaid };
    if ttl == Ttl::Over
        || !cacheable_response(upstream.status(), upstream.headers(), &settings.ignore_cookies)
        || upstream.content_length().is_some_and(|length| length > limit)
    {
        return None;
    }
    let until = match ttl {
        Ttl::Until(until) => Some(until),
        _ => None,
    };
    let own = own_headers(path, upstream.headers(), &settings.headers);
    let shows = depends(upstream.headers());
    let Buffered::Whole(bytes) = buffer(upstream, limit).await else { return None };
    let (cache, cached_path) = (app.cache_of(owner).clone(), path.to_owned());
    match tokio::task::spawn_blocking(move || {
        let stored = cache.store_with(&cached_path, generation, &bytes, &own, shows.as_deref());
        if matches!(stored, Ok(true)) {
            cache.set_expiry(&cached_path, until);
        }
        stored
    })
    .await
    {
        Ok(Ok(kept)) => Some(kept),
        _ => None,
    }
}

/// `GET /_live/explain?url=/content/site/en.html%3Fa%3D1[&method=POST]`: what would happen to this
/// request from a visitor who is not signed in, and why. For whoever may invalidate.
async fn explain(
    State(app): State<Arc<App>>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Query(params): Query<std::collections::HashMap<String, String>>,
    headers: HeaderMap,
) -> Response {
    if !may_invalidate(&app, peer, &headers) {
        return plain(StatusCode::FORBIDDEN, "Not allowed\n", "explain");
    }
    let url = params.get("url").map(String::as_str).unwrap_or("");
    let method = params.get("method").map(|m| m.to_ascii_uppercase()).unwrap_or_else(|| "GET".to_owned());
    let (raw_path, query) = url.split_once('?').unwrap_or((url, ""));
    let json = |status: StatusCode, body: serde_json::Value| (status, [(header::CONTENT_TYPE, "application/json")], body.to_string()).into_response();
    let Ok(method) = Method::from_bytes(method.as_bytes()) else {
        return json(StatusCode::BAD_REQUEST, serde_json::json!({ "error": "method is not a request method" }));
    };
    let Some(mut path) = decode_path(raw_path) else {
        return json(
            StatusCode::OK,
            serde_json::json!({
                "url": url, "method": method.as_str(), "answer": 400,
                "summary": "Refused with 400: not a path a visitor can ask for (it must start with /, and may not contain .., ;, a backslash or control characters).",
            }),
        );
    };
    let host = params.get("host").map(String::as_str).filter(|h| !h.is_empty());
    let site = app.config.site_for_host(host);
    if let Some((status, location)) = redirect_for(&app, site, host, &method, &path, query) {
        return json(
            StatusCode::OK,
            serde_json::json!({
                "url": url, "method": method.as_str(), "path": path, "host": host, "allowed": true, "cacheable": false,
                "answer": status.as_u16(), "location": location,
                "summary": format!("Redirected with {} to {location} by the site's redirect map (the web server's RewriteMap, and this service for what reaches it).", status.as_u16()),
            }),
        );
    }
    let vanity = (method == Method::GET || method == Method::HEAD).then(|| app.vanity.read().unwrap().get(&path).cloned()).flatten();
    if let Some(target) = vanity {
        let (answer, summary) = match outside(&app.config, site, &target) {
            None => (None, format!("A vanity URL of the publish, for the page {target}: let through, the publish resolves it (not kept: no extension).")),
            Some(_) => (Some(404), format!("Refused with 404: a vanity URL of the page {target}, which is not of the site this host name is for.")),
        };
        return json(
            StatusCode::OK,
            serde_json::json!({
                "url": url, "method": method.as_str(), "path": path, "host": host, "vanity": target, "allowed": answer.is_none(),
                "cacheable": false, "answer": answer, "summary": summary,
            }),
        );
    }
    let mut short = false;
    if let Some(prefix) = &app.config.content_prefix {
        if let Some(full) = full_path(prefix, &path) {
            path = full;
            short = true;
        }
    }
    let site_name = site.map(|site| app.config.site[site].name.as_str());
    if let Some(why) = outside(&app.config, site, &path) {
        let summary = match why {
            Outside::UnknownHost => match host {
                Some(host) => format!("Refused with 404: {host} is no declared site's host name."),
                None => "Refused with 404: sites are declared, and without a host name the request is no site's (add host=).".to_owned(),
            },
            Outside::NotItsContent => match app.config.site_owning(&path) {
                Some(owner) => format!(
                    "Refused with 404: a page of the site {}, asked under a host name of the site {}.",
                    app.config.site[owner].name,
                    site_name.unwrap_or("")
                ),
                None => format!("Refused with 404: neither in the roots of the site {} nor in what it lists as shared.", site_name.unwrap_or("")),
            },
        };
        return json(
            StatusCode::OK,
            serde_json::json!({
                "url": url, "method": method.as_str(), "path": path, "host": host, "site": site_name, "allowed": false, "cacheable": false, "answer": 404,
                "summary": summary,
            }),
        );
    }
    let parts = decompose(&path);
    let asked = filter::Request { method: method.as_str(), url: &path, query, parts: &parts };
    let site_rules: &[filter::Rule] = site.map_or(&[], |site| &app.config.site[site].filter);
    let (allowed, by) = filter::decide_with(&app.config.filter, site_rules, &asked);
    let total = app.config.filter.len() + site_rules.len();
    let (by_text, rule) = match &by {
        filter::By::BuiltIn => ("the built-in list, closed whatever the rules say".to_owned(), None),
        filter::By::NoRule => ("no rule allows it".to_owned(), None),
        filter::By::Rule(index) if *index >= app.config.filter.len() => (
            format!("filter rule {} of {total} (the site's own)", index + 1),
            site_rules.get(*index - app.config.filter.len()),
        ),
        filter::By::Rule(index) => (format!("filter rule {} of {total}", index + 1), app.config.filter.get(*index)),
    };
    let protected = app.protected_cache(site, &path);
    let why_not = not_cacheable(&app, site, &method, &path, query, &HeaderMap::new());
    let settings = app.config.cache_for(site);
    let (cache, cached_path) = (protected.clone().unwrap_or_else(|| app.cache_of(site).clone()), path.clone());
    let now = tokio::task::spawn_blocking(move || cache.whereabouts(&cached_path)).await.ok().flatten();
    let summary = if !allowed {
        match by {
            filter::By::NoRule => "Refused with 404: no filter rule allows it.".to_owned(),
            _ => format!("Refused with 404 by {by_text}."),
        }
    } else if let Some(why) = why_not {
        format!("Allowed by {by_text}. Always answered by the publish, never kept: {why}.")
    } else if protected.is_some() {
        match now {
            Some(_) => format!("Allowed by {by_text}. A protected page, in the cache apart from the others: served from there to a visitor the publish says may read it ({}); to anyone else the publish answers.", settings.auth_checker),
            None => format!("Allowed by {by_text}. A protected page, not in the cache: the publish answers, and a 200 the same for everyone is kept apart and served only after the check ({}).", settings.auth_checker),
        }
    } else {
        match now {
            Some("plain") => format!("Allowed by {by_text}. In the cache as a plain file: the web server answers it."),
            Some("held") => format!("Allowed by {by_text}. In the cache with headers of its own: this service answers it without asking the publish."),
            Some(_) if settings.grace_seconds > 0 => format!("Allowed by {by_text}. Taken out of the cache by a publication: for {} s after one it is served as it was while it is fetched again; after that the publish is asked, and the old answer is served if it fails.", settings.grace_seconds),
            Some(_) => format!("Allowed by {by_text}. Taken out of the cache by a publication: the publish is asked, and the old answer is served if it fails."),
            None => format!("Allowed by {by_text}. Not in the cache: the publish is asked and its answer kept, if it is a 200 for everyone."),
        }
    };
    json(
        StatusCode::OK,
        serde_json::json!({
            "url": url,
            "method": method.as_str(),
            "path": path,
            "host": host,
            "site": site_name.or_else(|| app.config.site_owning(&path).map(|site| app.config.site[site].name.as_str())),
            "shortUrl": short,
            "sling": { "path": parts.path, "selectors": parts.selectors, "extension": parts.extension, "suffix": parts.suffix },
            "allowed": allowed,
            "decidedBy": by_text,
            "rule": rule,
            "cacheable": allowed && why_not.is_none(),
            "notCacheableBecause": if allowed { why_not } else { None },
            "inCache": now,
            "protected": protected.is_some(),
            "file": if allowed && why_not.is_none() { cache_file(&path) } else { None },
            "summary": summary,
        }),
    )
}

fn mark_down(app: &App, index: usize) {
    app.down_until[index].store(now_ms() + app.config.publish_retry_seconds * 1000, Ordering::Relaxed);
    // and slower in the eyes of `fastest`, until it answers quickly again
    for cell in &app.speed[index] {
        let old = cell.load(Ordering::Relaxed);
        cell.store(old.saturating_add(app.config.unavailable_penalty_ms * 1000).max(1), Ordering::Relaxed);
    }
}

/// Does the instance answer its health check with something other than a 5xx?
async fn healthy(app: &App, index: usize, check: &str) -> bool {
    match app.client.get(format!("{}{}", app.instances[index], encode_path(check))).send().await {
        Ok(answer) => !answer.status().is_server_error(),
        Err(_) => false,
    }
}

enum Buffered {
    Whole(Bytes),
    /// More than the cache takes: what was read so far followed by the rest, to be passed on.
    TooLarge(Body),
    Failed,
}

async fn buffer(mut upstream: reqwest::Response, limit: u64) -> Buffered {
    let mut page = Vec::new();
    loop {
        match upstream.chunk().await {
            Ok(Some(chunk)) => {
                page.extend_from_slice(&chunk);
                if page.len() as u64 > limit {
                    let head = stream::once(async move { Ok::<_, reqwest::Error>(Bytes::from(page)) });
                    return Buffered::TooLarge(Body::from_stream(head.chain(upstream.bytes_stream())));
                }
            }
            Ok(None) => return Buffered::Whole(Bytes::from(page)),
            Err(_) => return Buffered::Failed,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn what_an_answer_says_it_shows() {
        let with = |value: &str| {
            let mut h = reqwest::header::HeaderMap::new();
            h.insert(DEPENDS, value.parse().unwrap());
            depends(&h)
        };
        assert_eq!(with("/content/dam/a%2Cb.jpg, /content/wknd/us/en/*").unwrap(), vec!["/content/dam/a,b.jpg", "/content/wknd/us/en/*"]);
        assert_eq!(with("").unwrap(), Vec::<String>::new());
        // a page that cannot tell says why, which is no path: it says nothing
        assert!(with("?a search").is_none());
        assert!(depends(&reqwest::header::HeaderMap::new()).is_none());
    }

    #[test]
    fn how_long_the_publish_says_an_answer_may_be_kept() {
        let with = |pairs: &[(&'static str, &'static str)]| {
            let mut h = reqwest::header::HeaderMap::new();
            for (name, value) in pairs {
                h.append(*name, value.parse().unwrap());
            }
            h
        };
        let now = 1_000_000;
        assert_eq!(ttl_of(&with(&[]), now), Ttl::Unsaid);
        assert_eq!(ttl_of(&with(&[("cache-control", "public, max-age=60")]), now), Ttl::Until(now + 60_000));
        // for caches like this one s-maxage comes first; what it waited elsewhere (Age) counts
        assert_eq!(ttl_of(&with(&[("cache-control", "max-age=60, s-maxage=300"), ("age", "100")]), now), Ttl::Until(now + 200_000));
        assert_eq!(ttl_of(&with(&[("cache-control", "max-age=0")]), now), Ttl::Over);
        assert_eq!(ttl_of(&with(&[("cache-control", "max-age=10"), ("age", "10")]), now), Ttl::Over);
        // Expires against the publish's own Date
        let headers = with(&[("date", "Sun, 06 Nov 1994 08:49:37 GMT"), ("expires", "Sun, 06 Nov 1994 08:50:37 GMT")]);
        assert_eq!(ttl_of(&headers, now), Ttl::Until(now + 60_000));
        assert_eq!(ttl_of(&with(&[("date", "Sun, 06 Nov 1994 08:49:37 GMT"), ("expires", "Sun, 06 Nov 1994 08:49:37 GMT")]), now), Ttl::Over);
        assert_eq!(ttl_of(&with(&[("expires", "0")]), now), Ttl::Over);
        // max-age wins over Expires
        assert_eq!(ttl_of(&with(&[("cache-control", "max-age=5"), ("expires", "0")]), now), Ttl::Until(now + 5000));
    }

    #[test]
    fn http_dates() {
        assert_eq!(http_date("Sun, 06 Nov 1994 08:49:37 GMT"), Some(784_111_777));
        assert_eq!(http_date("Thu, 01 Jan 1970 00:00:00 GMT"), Some(0));
        assert_eq!(http_date("Tue, 29 Feb 2028 23:59:59 GMT"), Some(1_835_481_599));
        assert_eq!(http_date("Sun, 06 Nov 1994 08:49:37 PST"), None);
        assert_eq!(http_date("Sunday, 06-Nov-94 08:49:37 GMT"), None);
        assert_eq!(http_date("yesterday"), None);
    }

    #[test]
    fn base64() {
        assert_eq!(base64_decode("Zmx1c2g6czNjcmV0").unwrap(), b"flush:s3cret");
        assert_eq!(base64_decode("YQ==").unwrap(), b"a");
        assert_eq!(base64_decode("YWI=").unwrap(), b"ab");
        assert!(base64_decode("a b").is_none());
    }

    #[test]
    fn which_answers_are_plain_files() {
        let headers = |pairs: &[(&'static str, &'static str)]| {
            let mut map = reqwest::header::HeaderMap::new();
            for (name, value) in pairs {
                map.append(*name, value.parse().unwrap());
            }
            map
        };
        let keep = vec!["Content-Disposition".to_owned(), "Content-Language".to_owned()];
        let own = |path: &str, pairs: &[(&'static str, &'static str)]| own_headers(path, &headers(pairs), &keep);

        // the type the extension says, nothing else worth keeping: a file
        assert!(own("/content/a.html", &[("content-type", "text/html;charset=utf-8"), ("x-frame-options", "SAMEORIGIN")]).is_empty());
        assert!(own("/content/a.jpeg", &[("content-type", "image/jpeg"), ("last-modified", "Fri, 12 Aug 2022 17:03:09 GMT")]).is_empty());
        assert!(own("/etc.clientlibs/a.js", &[("content-type", "application/javascript;charset=utf-8")]).is_empty());
        assert!(own("/content/a.sitemap.xml", &[("content-type", "text/xml; charset=UTF-8")]).is_empty());
        assert!(own("/content/a.html", &[]).is_empty());

        // a header of its own: kept, with the type
        assert_eq!(
            own("/content/logo.svg", &[("content-type", "image/svg+xml"), ("content-disposition", "attachment; filename=logo.svg")]),
            vec![("Content-Type".to_owned(), "image/svg+xml".to_owned()), ("Content-Disposition".to_owned(), "attachment; filename=logo.svg".to_owned())]
        );
        // a type the extension does not say
        assert_eq!(own("/etc.clientlibs/font.ttf", &[("content-type", "application/octet-stream")]).len(), 1);
        assert_eq!(own("/content/a.html", &[("content-type", "text/html; charset=iso-8859-2")]).len(), 1);
        assert_eq!(own("/content/a.html", &[("content-type", "application/json")]).len(), 1);
        assert_eq!(own("/content/a.unknown", &[("content-type", "application/octet-stream")]).len(), 1);
    }

    #[test]
    fn cookies_and_route_names() {
        let mut headers = HeaderMap::new();
        headers.append(header::COOKIE, "a=1; studio-route=abc".parse().unwrap());
        headers.append(header::COOKIE, "Sling.Formauth.Publish=tok=en".parse().unwrap());
        assert_eq!(cookie(&headers, "studio-route"), Some("abc"));
        assert_eq!(cookie(&headers, "sling.formauth.publish"), Some("tok=en"));
        assert_eq!(cookie(&headers, "route"), None);
        // a name that says nothing about the address, and the same every time
        let id = route_id("http://publish1.internal:4503");
        assert_eq!(id.len(), 16);
        assert!(!id.contains("publish1"));
        assert_eq!(id, route_id("http://publish1.internal:4503"));
        assert_ne!(id, route_id("http://publish2.internal:4503"));
    }

    #[test]
    fn sites() {
        assert_eq!(site_of("/content/wknd/us/en.html"), "/content/wknd");
        assert_eq!(site_of("/content/wknd"), "/content/wknd");
        assert_eq!(site_of("/content/dam/wknd/a.jpg"), "/content/dam");
        assert_eq!(site_of("/index.html"), "/index.html");
    }

    #[test]
    fn content_types() {
        assert_eq!(content_type("/content/a.html"), "text/html; charset=utf-8");
        assert_eq!(content_type("/content/a.model.json"), "application/json");
        assert_eq!(content_type("/content/a.JPG"), "image/jpeg");
        assert_eq!(content_type("/content/a.unknown"), "application/octet-stream");
    }
}

#[cfg(test)]
mod site_tests {
    use super::*;

    #[test]
    fn what_no_site_declares_is_not_shown() {
        let none = Config::parse("docroot = \"cache\"\npublish = [\"http://p\"]\n").unwrap();
        assert_eq!(outside(&none, None, "/content/a/en.html"), None);

        let c = Config::parse(
            "docroot = \"cache\"\npublish = [\"http://p\"]\n[[site]]\nname = \"a\"\nhosts = [\"a.example\"]\nroots = [\"/content/a\"]\nshared = [\"/content/dam/a\"]\n[[site]]\nname = \"b\"\nhosts = [\"b.example\"]\nroots = [\"/content/b\"]\n",
        )
        .unwrap();
        assert_eq!(outside(&c, Some(0), "/content/a/en.html"), None);
        assert_eq!(outside(&c, Some(0), "/content/a.html"), None);
        assert_eq!(outside(&c, Some(0), "/content/dam/a/logo.png"), None);
        // what the filter decides: not content
        assert_eq!(outside(&c, Some(0), "/etc.clientlibs/a/site.css"), None);
        assert_eq!(outside(&c, Some(0), "/content/b/en.html"), Some(Outside::NotItsContent));
        assert_eq!(outside(&c, Some(1), "/content/dam/a/logo.png"), Some(Outside::NotItsContent));
        assert_eq!(outside(&c, Some(0), "/content/ab/en.html"), Some(Outside::NotItsContent));
        assert_eq!(outside(&c, Some(0), "/content/starter.html"), Some(Outside::NotItsContent));
        assert_eq!(outside(&c, Some(0), "/content.html"), Some(Outside::NotItsContent));
        assert_eq!(outside(&c, None, "/content/a/en.html"), Some(Outside::UnknownHost));
        assert_eq!(outside(&c, None, "/etc.clientlibs/a/site.css"), Some(Outside::UnknownHost));
    }
}
