//! The configuration file (`live.toml`). Virtual hosts, rewrites and TLS are Apache's and are not
//! here; this file says where the publish instances are, what may reach them and what is cached.

use std::net::SocketAddr;
use std::path::{Path, PathBuf};

use serde::Deserialize;

use crate::filter::Rule;

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Config {
    /// Where Apache reaches this service. Loopback unless Apache runs elsewhere.
    #[serde(default = "default_listen")]
    pub listen: SocketAddr,
    /// The cache directory, which is also Apache's `DocumentRoot`. Relative to the working directory.
    pub docroot: PathBuf,
    /// The publish instances (`http://host:4503`). Requests go to them in turn; one that does not
    /// answer is left out for a while.
    pub publish: Vec<String>,
    /// Set to `"/content"` when the publish writes URLs without it (`/wknd/us/en.html`): a path
    /// that does not start with a repository root is then read as one below this prefix, so that
    /// both spellings are one page to the filter, the cache and an invalidation.
    pub content_prefix: Option<String>,
    /// How much a publication invalidates besides the published resource itself. `0`: every
    /// auto-invalidated file of the cache (always correct). `2`: only those below the same
    /// `/content/<site>`, which is wrong for pages that show content of another tree (a block, a
    /// template under `/conf`).
    #[serde(default)]
    pub stat_level: usize,
    /// With several publish instances: a visitor who signed in (or to whom a publish gave any
    /// cookie) keeps being sent to the same instance, by a cookie of this service's own
    /// (`studio-route`), for as long as that instance answers. A sign-in on one publish instance
    /// is not known to the others. Pages that are the same for everyone are not affected.
    #[serde(default = "default_true")]
    pub sticky: bool,
    /// Seconds a publish instance is skipped after it failed to answer.
    #[serde(default = "default_retry")]
    pub publish_retry_seconds: u64,
    /// How a request chooses among the publish instances: `round_robin` (in turn), or `fastest`
    /// (a dispatcher's `/statistics`: the one that answered quickest lately, kept apart for pages
    /// and for everything else; one that failed has `unavailable_penalty_ms` added).
    #[serde(default)]
    pub balance: Balance,
    #[serde(default = "default_penalty")]
    pub unavailable_penalty_ms: u64,
    /// A path asked of a publish instance that answered 500 (a dispatcher's `/health_check`): when
    /// that is a 5xx too, or gets no answer, the instance is skipped for `publish_retry_seconds`
    /// and another one is asked; otherwise the 500 is the page's own, and passed on.
    pub health_check: Option<String>,
    /// Seconds to wait for a publish instance to accept a connection, and for its answer.
    #[serde(default = "default_connect_timeout")]
    pub connect_timeout_seconds: u64,
    #[serde(default = "default_timeout")]
    pub timeout_seconds: u64,
    /// The visitor's request headers the publish is told (a dispatcher's `/clientheaders`): names,
    /// in any case, `*` for any run of characters (`x-gwt-*`). Unset: all of them but those of the
    /// connection. `Host` always goes, and `X-Forwarded-For` is this service's.
    #[serde(default)]
    pub client_headers: Option<Vec<String>>,
    #[serde(default)]
    pub flush: Flush,
    #[serde(default)]
    pub cache: Cache,
    #[serde(default)]
    pub vanity: Vanity,
    #[serde(default)]
    pub redirects: Redirects,
    /// What is told of every invalidation, once it is done (a dispatcher's `/invalidateHandler`):
    /// to empty a CDN in front, say.
    #[serde(default)]
    pub after_invalidation: AfterInvalidation,
    /// A CDN in front that purges by tag (surrogate keys): every answer carries the tags of what it
    /// is and what it shows, and every invalidation names the tags to purge (to `after_invalidation`).
    pub cdn: Option<Cdn>,
    /// Deny by default: without rules nothing reaches the publish. The last rule that matches wins.
    #[serde(default)]
    pub filter: Vec<Rule>,
    /// The sites behind this cache, when there are several (`[[site]]`): which host names are
    /// whose, where its pages are, what a publication of one of them takes out of the cache.
    #[serde(default)]
    pub site: Vec<Site>,
    /// The site a host name no site lists is answered as (its `name`); unset: such a host name gets
    /// 404 for everything. The web server's default virtual host must then be that site's too.
    pub default_site: Option<String>,
    /// Each site's cache settings: `[cache]` with its `[site.cache]` in place.
    #[serde(skip)]
    pub site_cache: Vec<Cache>,
    #[serde(skip)]
    pub default_site_index: Option<usize>,
}

/// One site (a brand, a country site): what a dispatcher calls a farm.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Site {
    /// For the log and the console.
    pub name: String,
    /// Its host names, without a port; `*` stands for any run of characters (`*.example.com`).
    pub hosts: Vec<String>,
    /// Where its pages are (`/content/wknd`). Of all content, its host names show only these
    /// trees and what it lists as shared; a host name that is no site's shows nothing.
    pub roots: Vec<String>,
    /// Content outside its roots that its pages show (its blocks, its templates, its media):
    /// served under its host names too, and a publication there takes this site's pages out of
    /// the cache, as one inside its roots does. Several sites may list the same path.
    #[serde(default)]
    pub shared: Vec<String>,
    /// Its own publish instances; empty: the ones listed at the top.
    #[serde(default)]
    pub publish: Vec<String>,
    /// Filter rules for requests under its host names, read after the general ones.
    #[serde(default)]
    pub filter: Vec<Rule>,
    /// Where its pages are kept: the document root of its virtual host. Every site has one of its
    /// own, or a file kept for one site would be served by the web server under any host name.
    /// Default: `<docroot>/<name>`.
    #[serde(default)]
    pub docroot: Option<PathBuf>,
    /// Globs of its pages that only some visitors may read (a closed user group): they are kept
    /// apart from its other pages, where the web server never finds them, and every visitor who
    /// asks for one is let in only after the publish says, at `cache.auth_checker`, that this
    /// visitor may read it. What a member sees must be the same for every member: a page that
    /// greets the visitor by name is not one to list here.
    #[serde(default)]
    pub protected: Vec<String>,
    /// Its redirect map on the publish, a text file published like any asset
    /// (`/content/dam/<site>/redirectmap.txt`), as AEM's managed rewrite maps: one rule a line,
    /// `<source path> <target path or URL> [301|302|307|308]`, `#` for a comment. The cache reads it
    /// at the start, every `redirects.refresh_seconds` and after every publication, answers the
    /// redirects itself, and writes it beside the document root (`<name>.redirects.txt`) for
    /// Apache's `RewriteMap` (`conf.d/rewrites/redirect_map.rules`).
    #[serde(default)]
    pub redirect_map: Option<String>,
    /// Redirect maps of single host names of the site (`"shop.example" = "/content/dam/…/shop.txt"`),
    /// for a site with several domains: a rule of the host's own map comes before one of
    /// `redirect_map`, for requests under that host name only.
    #[serde(default)]
    pub host_redirect_maps: std::collections::BTreeMap<String, String>,
    /// Its own cache settings, each in place of the one of `[cache]`.
    #[serde(default)]
    pub cache: Option<CacheOverride>,
    /// The request headers its publish instances are told, in place of the general `client_headers`.
    #[serde(default)]
    pub client_headers: Option<Vec<String>>,
}

/// A list of header names: in lower case, each a token (letters, digits, `-`, `_`, `.`) or `*`.
fn client_headers(what: &str, list: &mut Option<Vec<String>>) -> Result<(), String> {
    for name in list.iter_mut().flatten() {
        *name = name.trim().to_ascii_lowercase();
        if name.is_empty() || !name.bytes().all(|b| b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'*')) {
            return Err(format!("{what}: {name:?} is not a header name"));
        }
    }
    Ok(())
}

/// Is `path` the node `base`, something below it, or a rendering of it (`base.html`)?
pub fn under(path: &str, base: &str) -> bool {
    path.strip_prefix(base).is_some_and(|rest| rest.is_empty() || rest.starts_with('/') || rest.starts_with('.'))
}

/// A `Host` header as a name to compare: no port, lower case.
pub fn host_name(host: &str) -> String {
    let host = host.trim();
    let name = match host.strip_prefix('[') {
        Some(v6) => v6.split(']').next().unwrap_or(v6),
        None => host.rsplit_once(':').filter(|(_, port)| port.chars().all(|c| c.is_ascii_digit())).map_or(host, |(name, _)| name),
    };
    name.to_ascii_lowercase()
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Flush {
    /// The password a flush agent must send (basic authentication, any user name) or a bearer
    /// token. `STUDIO_LIVE_FLUSH_SECRET` in the environment overrides it. Without a secret only a
    /// direct connection from this machine may invalidate.
    pub secret: Option<String>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Cache {
    /// Globs of paths that may (`allow`) or may not (`deny`) be cached; the last match wins.
    #[serde(default = "default_cache_rules")]
    pub rules: Vec<CacheRule>,
    /// Globs of the files a publication removes besides those of the published resource: pages
    /// show each other's titles and navigation, so by default every `.html`.
    #[serde(default = "default_invalidate")]
    pub invalidate: Vec<String>,
    /// Globs of query parameter names that do not change the page (campaign tracking). A request
    /// with any other parameter is neither served from the cache nor cached.
    #[serde(default)]
    pub ignore_query: Vec<String>,
    /// A request carrying one of these cookies is a signed-in visitor: never cached.
    #[serde(default = "default_login_cookies")]
    pub login_cookies: Vec<String>,
    /// Cookies the publish sets on pages that are the same for everyone (a servlet session nobody
    /// uses). An answer that sets only these is cached, and the cookie is not passed on. An answer
    /// that sets any other cookie is never cached.
    #[serde(default)]
    pub ignore_cookies: Vec<String>,
    /// Headers of the publish's answer that are kept with a cached page and sent again with it,
    /// besides a `Content-Type` its extension does not already say. An answer that has any of
    /// them is not a plain file: Apache leaves it to this service, which still does not ask the
    /// publish. Not for headers the virtual host sets itself (`Cache-Control`, `X-Frame-Options`).
    #[serde(default = "default_headers")]
    pub headers: Vec<String>,
    /// How long, in seconds, a page that a publication took out of the cache is kept for the case
    /// that the publish cannot be asked when it is wanted again. `0`: not kept.
    #[serde(default = "default_stale_seconds")]
    pub stale_seconds: u64,
    /// For this many seconds after a publication, a page it took out of the cache is still served
    /// as it was, at once, while it is fetched again in the background (one fetch per page, the
    /// next visitor gets the new one). What a dispatcher calls its grace period. `0`: the first
    /// visitor waits for the publish. Pages that were unpublished are never served so.
    #[serde(default)]
    pub grace_seconds: u64,
    /// Globs of published paths whose publication takes out only the pages that show them
    /// (`/content/dam/*`, `/content/experience-fragments/*`), as each page's publish said
    /// (`X-Studio-Depends`), plus the pages that said nothing; not every page. Empty: every
    /// publication takes out what `invalidate` and `stat_level` say.
    #[serde(default)]
    pub invalidate_by_dependencies: Vec<String>,
    /// After a page was published, fetch its own renderings that were cached (`en.html`,
    /// `en.model.json`) again at once, so that the first visitor does not wait for the publish.
    #[serde(default = "default_true")]
    pub rewarm: bool,
    /// Responses larger than this are passed through without being cached.
    #[serde(default = "default_max_file_mb")]
    pub max_file_mb: u64,
    /// Files older than this are removed (checked once a minute). Unset: only publications
    /// remove files.
    pub ttl_seconds: Option<u64>,
    /// Keep an answer only as long as the publish says, as a dispatcher's `/enableTTL`:
    /// `Cache-Control: s-maxage`, else `max-age` (less its `Age`), else `Expires`. An answer that
    /// says none of them stays until a publication takes it out; one whose time is already over is
    /// not kept. The service takes an answer out within a second of its time; until then Apache
    /// serves the file.
    #[serde(default)]
    pub enable_ttl: bool,
    /// Signed-in visitors (credentials, a login cookie) get pages from the cache, and their answers
    /// are kept, as a dispatcher's `/allowAuthorized`. Only for sites whose pages are the same for
    /// every visitor, signed in or not: a page that greets the visitor by name would be served to
    /// everyone. Protected pages are not touched by it (they are always checked for each visitor).
    #[serde(default)]
    pub allow_authorized: bool,
    /// Where on the publish a protected page (`site.protected`) is checked for a visitor: asked
    /// with `?uri=<the path>` and the visitor's cookies and credentials, it answers 200 when the
    /// visitor may read it and anything else when not.
    #[serde(default = "default_auth_checker")]
    pub auth_checker: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CacheRule {
    #[serde(rename = "type")]
    pub kind: crate::filter::Kind,
    pub glob: String,
}

/// The vanity URLs of the publish (`sling:vanityPath`), which no filter rule lists: what a
/// dispatcher's `/vanity_urls` is for.
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Vanity {
    /// Where the publish lists them (`/libs/granite/dispatcher/content/vanityUrls.json`); unset: no
    /// vanity URL is let through.
    pub url: Option<String>,
    /// How often the list is read again; it is also read after every invalidation.
    #[serde(default = "default_vanity_refresh")]
    pub refresh_seconds: u64,
}

/// How the sites' redirect maps are read.
/// After every invalidation: a command run, a URL told, or both. Each gets the action, the
/// published path, the sites concerned with their host names, and the request paths of what was
/// taken out of the cache.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AfterInvalidation {
    /// The program and its first arguments; the action and the path follow, the paths taken out
    /// come one a line on its standard input, the rest in `CQ_ACTION`, `CQ_HANDLE`,
    /// `STUDIO_LIVE_SITES`, `STUDIO_LIVE_HOSTS` (comma separated), `STUDIO_LIVE_WHOLE` (`false`
    /// when more were taken out than listed).
    #[serde(default)]
    pub command: Vec<String>,
    /// Asked with a POST of JSON: `action`, `handle`, `sites` (`name`, `hosts`), `removed`,
    /// `paths`, `whole`.
    pub url: Option<String>,
    /// Headers for the URL; a value `env:NAME` is the environment variable NAME (a token).
    #[serde(default)]
    pub headers: std::collections::BTreeMap<String, String>,
    /// How long either may take.
    #[serde(default = "default_hook_timeout")]
    pub timeout_seconds: u64,
}

impl AfterInvalidation {
    pub fn is_set(&self) -> bool {
        !self.command.is_empty() || self.url.is_some()
    }
}

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Balance {
    #[default]
    RoundRobin,
    Fastest,
}

fn default_penalty() -> u64 {
    1000
}

/// The CDN in front, for tagging answers (surrogate keys, cache tags).
#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Cdn {
    /// `fastly` (also Adobe's CDN of AEM as a Cloud Service), `cloudflare`, `akamai` or
    /// `cloudfront`: the header its tags go in, how they are separated, how many it keeps.
    pub kind: CdnKind,
    /// In place of the kind's header.
    pub header: Option<String>,
    /// In place of the kind's limit: an answer with more tags is tagged as saying nothing of
    /// what it shows (purged on every publication of its site).
    pub max_tags: Option<usize>,
    /// Globs of paths not tagged (their URLs change when they do): Apache keeps serving them from
    /// its files.
    #[serde(default = "default_untagged")]
    pub untagged: Vec<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum CdnKind {
    Fastly,
    Cloudflare,
    Akamai,
    Cloudfront,
}

impl Cdn {
    /// The response header, the separator, how many tags an answer may carry.
    pub fn header(&self) -> (&str, &'static str, usize) {
        let (header, separator, max) = match self.kind {
            // 16 KB of keys (Fastly; AEM as a Cloud Service's CDN is Fastly)
            CdnKind::Fastly => ("Surrogate-Key", " ", 800),
            // 16 KB in all (Cloudflare)
            CdnKind::Cloudflare => ("Cache-Tag", ",", 800),
            // 128 tags an object (Akamai)
            CdnKind::Akamai => ("Edge-Cache-Tag", ",", 128),
            // 50 tags an object (CloudFront's cache tags, header named in its CacheTagConfig)
            CdnKind::Cloudfront => ("Cache-Tag", ",", 50),
        };
        (self.header.as_deref().unwrap_or(header), separator, self.max_tags.unwrap_or(max))
    }
}

fn default_untagged() -> Vec<String> {
    vec!["/etc.clientlibs/*".into(), "/libs/*".into()]
}

fn default_hook_timeout() -> u64 {
    30
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Redirects {
    /// How often they are read again; they are also read after every invalidation.
    #[serde(default = "default_vanity_refresh")]
    pub refresh_seconds: u64,
}

impl Default for Redirects {
    fn default() -> Self {
        Redirects { refresh_seconds: default_vanity_refresh() }
    }
}

impl Default for Vanity {
    fn default() -> Self {
        Vanity { url: None, refresh_seconds: default_vanity_refresh() }
    }
}

fn default_vanity_refresh() -> u64 {
    300
}

impl Default for Cache {
    fn default() -> Self {
        Cache {
            rules: default_cache_rules(),
            invalidate: default_invalidate(),
            ignore_query: Vec::new(),
            login_cookies: default_login_cookies(),
            ignore_cookies: Vec::new(),
            headers: default_headers(),
            stale_seconds: default_stale_seconds(),
            grace_seconds: 0,
            invalidate_by_dependencies: Vec::new(),
            rewarm: true,
            max_file_mb: default_max_file_mb(),
            ttl_seconds: None,
            enable_ttl: false,
            allow_authorized: false,
            auth_checker: default_auth_checker(),
        }
    }
}

/// A site's own cache settings (`[site.cache]`), each replacing the one of `[cache]`; what is not
/// set is the general one. What a dispatcher's farms each have in their `/cache`.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CacheOverride {
    pub rules: Option<Vec<CacheRule>>,
    pub invalidate: Option<Vec<String>>,
    pub ignore_query: Option<Vec<String>>,
    pub login_cookies: Option<Vec<String>>,
    pub ignore_cookies: Option<Vec<String>>,
    pub headers: Option<Vec<String>>,
    pub stale_seconds: Option<u64>,
    pub grace_seconds: Option<u64>,
    pub invalidate_by_dependencies: Option<Vec<String>>,
    pub rewarm: Option<bool>,
    pub max_file_mb: Option<u64>,
    pub ttl_seconds: Option<u64>,
    pub enable_ttl: Option<bool>,
    pub allow_authorized: Option<bool>,
    pub auth_checker: Option<String>,
    /// In place of the general `stat_level` (a farm's own `/statfileslevel`).
    pub stat_level: Option<usize>,
}

impl CacheOverride {
    /// The general settings with these in their place.
    pub fn over(&self, general: &Cache) -> Cache {
        let mut c = general.clone();
        macro_rules! take {
            ($($field:ident),*) => { $( if let Some(v) = &self.$field { c.$field = v.clone(); } )* };
        }
        take!(rules, invalidate, ignore_query, login_cookies, ignore_cookies, headers, stale_seconds, grace_seconds,
            invalidate_by_dependencies, rewarm, max_file_mb, enable_ttl, allow_authorized, auth_checker);
        if self.ttl_seconds.is_some() {
            c.ttl_seconds = self.ttl_seconds;
        }
        c
    }
}

fn default_listen() -> SocketAddr {
    "127.0.0.1:4520".parse().unwrap()
}
fn default_retry() -> u64 {
    10
}
fn default_connect_timeout() -> u64 {
    5
}
fn default_timeout() -> u64 {
    60
}
fn default_cache_rules() -> Vec<CacheRule> {
    vec![CacheRule { kind: crate::filter::Kind::Allow, glob: "*".into() }]
}
fn default_invalidate() -> Vec<String> {
    vec!["*.html".into()]
}
fn default_login_cookies() -> Vec<String> {
    vec!["studio-login".into(), "sling.formauth.publish".into()]
}
fn default_headers() -> Vec<String> {
    ["Content-Disposition", "Content-Language", "Content-Security-Policy", "Link", "Access-Control-Allow-Origin",
        "Access-Control-Allow-Credentials", "Access-Control-Expose-Headers"]
        .iter()
        .map(|h| h.to_string())
        .collect()
}
fn default_auth_checker() -> String {
    "/bin/studio/permissioncheck".into()
}
fn default_true() -> bool {
    true
}
fn default_stale_seconds() -> u64 {
    86_400
}
fn default_max_file_mb() -> u64 {
    64
}

impl Config {
    pub fn parse(text: &str) -> Result<Config, String> {
        let mut config: Config = toml::from_str(text).map_err(|e| e.to_string())?;
        if let Ok(secret) = std::env::var("STUDIO_LIVE_FLUSH_SECRET") {
            if !secret.is_empty() {
                config.flush.secret = Some(secret);
            }
        }
        config.validate()?;
        Ok(config)
    }

    pub fn load(path: &Path) -> Result<Config, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
        Config::parse(&text).map_err(|e| format!("{}: {e}", path.display()))
    }

    /// Where a site's pages are kept: its own `docroot`, else `<docroot>/<name>`.
    pub fn site_docroot(&self, site: usize) -> PathBuf {
        self.site[site].docroot.clone().unwrap_or_else(|| self.docroot.join(&self.site[site].name))
    }

    /// Where a site's protected pages are kept: beside its document root (`<name>.private`), so
    /// that the web server never serves one without the check.
    pub fn site_private_docroot(&self, site: usize) -> PathBuf {
        let docroot = self.site_docroot(site);
        let mut name = docroot.file_name().map(|n| n.to_os_string()).unwrap_or_default();
        name.push(".private");
        docroot.with_file_name(name)
    }

    /// Where a site's redirect map is written for Apache: beside its document root.
    pub fn site_redirects_file(&self, site: usize) -> PathBuf {
        let docroot = self.site_docroot(site);
        let mut name = docroot.file_name().map(|n| n.to_os_string()).unwrap_or_default();
        name.push(".redirects.txt");
        docroot.with_file_name(name)
    }

    /// The cache settings for a site's requests and pages; with no site, the general ones.
    /// How much of the site's cache a publication takes out: its own `[site.cache] stat_level`,
    /// else the general one.
    pub fn stat_level_for(&self, site: Option<usize>) -> usize {
        site.and_then(|site| self.site[site].cache.as_ref()).and_then(|own| own.stat_level).unwrap_or(self.stat_level)
    }

    pub fn cache_for(&self, site: Option<usize>) -> &Cache {
        site.and_then(|site| self.site_cache.get(site)).unwrap_or(&self.cache)
    }

    /// Is this path one of the site's protected pages?
    pub fn protected(&self, site: usize, path: &str) -> bool {
        self.site[site].protected.iter().any(|pattern| crate::url::glob(pattern, path))
    }

    /// The site a request under this host name is for: the one that lists it, else the default site.
    pub fn site_for_host(&self, host: Option<&str>) -> Option<usize> {
        host.and_then(|host| {
            let name = host_name(host);
            self.site.iter().position(|site| site.hosts.iter().any(|pattern| crate::url::glob(&pattern.to_ascii_lowercase(), &name)))
        })
        .or(self.default_site_index)
    }

    /// Does the site have a redirect map, of all its host names or of one?
    /// Is the publish told this request header (a lower-case name) of a visitor of the site?
    pub fn forwards_header(&self, site: Option<usize>, name: &str) -> bool {
        if name == "host" {
            return true;
        }
        let list = site.and_then(|s| self.site[s].client_headers.as_ref()).or(self.client_headers.as_ref());
        list.is_none_or(|names| names.iter().any(|pattern| crate::url::glob(pattern, name)))
    }

    pub fn has_redirects(&self, site: usize) -> bool {
        self.site[site].redirect_map.is_some() || !self.site[site].host_redirect_maps.is_empty()
    }

    /// The site whose pages include this path, if any does.
    pub fn site_owning(&self, path: &str) -> Option<usize> {
        self.site.iter().position(|site| site.roots.iter().any(|root| under(path, root)))
    }

    /// The sites whose pages a publication of this path concerns: the one it belongs to, and
    /// those that say they show it.
    pub fn sites_showing(&self, handle: &str) -> Vec<usize> {
        (0..self.site.len())
            .filter(|i| self.site[*i].roots.iter().chain(&self.site[*i].shared).any(|base| under(handle, base)))
            .collect()
    }

    fn validate(&mut self) -> Result<(), String> {
        fn instances(what: &str, urls: &mut [String]) -> Result<(), String> {
            for url in urls {
                if !(url.starts_with("http://") || url.starts_with("https://")) {
                    return Err(format!("{what}: {url} is not an http(s) URL"));
                }
                while url.ends_with('/') {
                    url.pop();
                }
            }
            Ok(())
        }
        if self.publish.is_empty() {
            return Err("publish: at least one publish instance is needed".into());
        }
        instances("publish", &mut self.publish)?;
        let mut names = std::collections::HashSet::new();
        for site in &mut self.site {
            let what = format!("site {}", site.name);
            if site.name.trim().is_empty() || !names.insert(site.name.clone()) {
                return Err(format!("{what}: every site needs a name of its own"));
            }
            if !site.name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') {
                return Err(format!("{what}: a site's name is letters, digits, - and _ (it names its cache directory)"));
            }
            if site.hosts.is_empty() || site.hosts.iter().any(|h| h.trim().is_empty() || h.contains('/') || h.contains(':')) {
                return Err(format!("{what}: hosts are host names without a scheme or a port (www.example.com)"));
            }
            if site.roots.is_empty() {
                return Err(format!("{what}: roots says where its pages are (/content/{})", site.name));
            }
            for path in site.roots.iter().chain(&site.shared) {
                if !path.starts_with('/') || path.ends_with('/') || crate::url::relative(path).is_none() {
                    return Err(format!("{what}: {path} is not a path like /content/{}", site.name));
                }
            }
            if site.redirect_map.as_deref().is_some_and(|map| !map.starts_with('/') || map.contains('?')) {
                return Err(format!("{what}: redirect_map is a path on the publish, like /content/dam/{}/redirectmap.txt", site.name));
            }
            for (host, map) in &site.host_redirect_maps {
                if !map.starts_with('/') || map.contains('?') {
                    return Err(format!("{what}: host_redirect_maps: {map} is not a path on the publish"));
                }
                if host.is_empty() || host.contains(['/', ':', '*', '?', ' ']) || *host != host.to_ascii_lowercase() {
                    return Err(format!("{what}: host_redirect_maps: {host} is not a host name in lower case, without a port or a *"));
                }
            }
            if site.protected.iter().any(|glob| !glob.starts_with('/')) {
                return Err(format!("{what}: protected lists globs of paths, like /content/{}/members/*", site.name));
            }
            instances(&format!("{what}: publish"), &mut site.publish)?;
        }
        if self.docroot.as_os_str().is_empty() {
            return Err("docroot: must not be empty".into());
        }
        client_headers("client_headers", &mut self.client_headers)?;
        for site in &mut self.site {
            client_headers(&format!("site {}: client_headers", site.name), &mut site.client_headers)?;
        }
        let mut docroots = std::collections::HashSet::new();
        for index in 0..self.site.len() {
            let docroot = self.site_docroot(index);
            if docroot == self.docroot || !docroots.insert(docroot.clone()) {
                return Err(format!("site {}: docroot {} is another's; every site keeps its pages apart", self.site[index].name, docroot.display()));
            }
        }
        if self.vanity.url.as_deref().is_some_and(|url| !url.starts_with('/')) {
            return Err("vanity.url: a path on the publish, like /libs/granite/dispatcher/content/vanityUrls.json".into());
        }
        self.default_site_index = match &self.default_site {
            None => None,
            Some(name) => Some(self.site.iter().position(|site| &site.name == name).ok_or(format!("default_site: no site is called {name}"))?),
        };
        self.site_cache = self.site.iter().map(|site| site.cache.as_ref().map_or_else(|| self.cache.clone(), |own| own.over(&self.cache))).collect();
        for (what, cache) in std::iter::once(("cache".to_owned(), &self.cache))
            .chain(self.site.iter().zip(&self.site_cache).map(|(site, cache)| (format!("site {}: cache", site.name), cache)))
        {
            if !cache.auth_checker.starts_with('/') || cache.auth_checker.contains('?') {
                return Err(format!("{what}.auth_checker: a path on the publish, like /bin/studio/permissioncheck"));
            }
        }
        if let Some(prefix) = &self.content_prefix {
            if !prefix.starts_with('/') || prefix.ends_with('/') || crate::url::relative(prefix).is_none() {
                return Err(format!("content_prefix: {prefix} is not a path like /content"));
            }
        }
        if self.health_check.as_deref().is_some_and(|path| !path.starts_with('/') || path.contains(char::is_whitespace)) {
            return Err("health_check: a path on the publish, like /system/health".into());
        }
        if let Some(cdn) = &self.cdn {
            if cdn.header.as_deref().is_some_and(|h| reqwest::header::HeaderName::from_bytes(h.as_bytes()).is_err()) {
                return Err("cdn.header: not a header name".into());
            }
            if cdn.max_tags.is_some_and(|m| m < 4) {
                return Err("cdn.max_tags: at least 4 (an answer's own tags)".into());
            }
            if cdn.untagged.iter().any(|g| !g.starts_with('/')) {
                return Err("cdn.untagged: globs of paths, like /etc.clientlibs/*".into());
            }
        }
        let hook = &self.after_invalidation;
        if hook.command.first().is_some_and(|program| program.trim().is_empty()) {
            return Err("after_invalidation.command: the first is the program to run".into());
        }
        if let Some(url) = &hook.url {
            if !(url.starts_with("https://") || url.starts_with("http://")) || reqwest::Url::parse(url).is_err() {
                return Err(format!("after_invalidation.url: {url} is not an http(s) URL"));
            }
        }
        for (name, value) in &hook.headers {
            if reqwest::header::HeaderName::from_bytes(name.as_bytes()).is_err() {
                return Err(format!("after_invalidation.headers: {name} is not a header name"));
            }
            if value.strip_prefix("env:").is_some_and(|v| v.is_empty()) {
                return Err(format!("after_invalidation.headers: {name} = env: names no variable"));
            }
        }
        if hook.is_set() && hook.timeout_seconds == 0 {
            return Err("after_invalidation.timeout_seconds: more than 0".into());
        }
        if matches!(self.flush.secret.as_deref(), Some("")) {
            self.flush.secret = None;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn minimal_file_gets_the_defaults() {
        let c = Config::parse("docroot = \"cache\"\npublish = [\"http://localhost:4503/\"]\n").unwrap();
        assert_eq!(c.listen, default_listen());
        assert_eq!(c.publish, vec!["http://localhost:4503"]);
        assert_eq!(c.stat_level, 0);
        assert!(c.sticky);
        assert_eq!(c.cache.invalidate, vec!["*.html"]);
        assert_eq!(c.cache.stale_seconds, 86_400);
        assert!(c.cache.headers.iter().any(|h| h == "Content-Disposition"));
        assert!(c.filter.is_empty());
    }

    const SITES: &str = r#"
docroot = "cache"
publish = ["http://p1:4503"]

[[site]]
name = "wknd"
hosts = ["www.wknd.example", "wknd.example"]
roots = ["/content/wknd"]
shared = ["/content/experience-fragments/wknd", "/conf/wknd"]

[[site]]
name = "shop"
hosts = ["*.shop.example"]
roots = ["/content/shop", "/content/shop-campaigns"]
publish = ["http://p2:4503/", "http://p3:4503"]

[[site.filter]]
type = "allow"
method = ["POST"]
url = "/bin/shop/order"
"#;

    #[test]
    fn sites() {
        let c = Config::parse(SITES).unwrap();
        assert_eq!(c.site.len(), 2);
        assert_eq!(c.site[1].publish, vec!["http://p2:4503", "http://p3:4503"]);
        assert_eq!(c.site[1].filter.len(), 1);

        assert_eq!(c.site_for_host(Some("www.wknd.example")), Some(0));
        assert_eq!(c.site_for_host(Some("WKND.example:8080")), Some(0));
        assert_eq!(c.site_for_host(Some("de.shop.example")), Some(1));
        assert_eq!(c.site_for_host(Some("shop.example")), None);
        assert_eq!(c.site_for_host(Some("localhost:4520")), None);
        assert_eq!(c.site_for_host(None), None);

        assert_eq!(c.site_owning("/content/wknd/us/en.html"), Some(0));
        assert_eq!(c.site_owning("/content/wknd.html"), Some(0));
        assert_eq!(c.site_owning("/content/shop-campaigns/x.html"), Some(1));
        assert_eq!(c.site_owning("/content/wknd-other/x.html"), None);
        assert_eq!(c.site_owning("/content/experience-fragments/wknd/header.html"), None);

        assert_eq!(c.sites_showing("/content/wknd/us/en"), vec![0]);
        assert_eq!(c.sites_showing("/content/experience-fragments/wknd/header"), vec![0]);
        assert_eq!(c.sites_showing("/content/shop/de"), vec![1]);
        assert_eq!(c.sites_showing("/content/dam/shared/logo.png"), Vec::<usize>::new());

        assert_eq!(host_name("Example.COM:443"), "example.com");
        assert_eq!(host_name("[::1]:4520"), "::1");
        assert_eq!(host_name("example.com"), "example.com");
    }

    #[test]
    fn every_site_keeps_its_pages_in_a_directory_of_its_own() {
        let parse = |sites: &str| Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\n{sites}"));
        let two = "[[site]]\nname = \"a\"\nhosts = [\"a.example\"]\nroots = [\"/content/a\"]\n[[site]]\nname = \"b\"\nhosts = [\"b.example\"]\nroots = [\"/content/b\"]\n";
        let c = parse(two).unwrap();
        assert_eq!(c.site_docroot(0), PathBuf::from("cache/a"));
        assert_eq!(c.site_docroot(1), PathBuf::from("cache/b"));
        let own = parse(&two.replace("roots = [\"/content/b\"]\n", "roots = [\"/content/b\"]\ndocroot = \"/srv/b\"\n")).unwrap();
        assert_eq!(own.site_docroot(1), PathBuf::from("/srv/b"));
        // two sites in one directory, or a site in the top one: the web server would mix them up
        assert!(parse(&two.replace("roots = [\"/content/b\"]\n", "roots = [\"/content/b\"]\ndocroot = \"cache/a\"\n")).is_err());
        assert!(parse(&two.replace("roots = [\"/content/b\"]\n", "roots = [\"/content/b\"]\ndocroot = \"cache\"\n")).is_err());
        // the name names the directory
        assert!(parse(&two.replace("name = \"b\"", "name = \"../b\"")).is_err());
        assert!(parse(&two.replace("name = \"b\"", "name = \"b.held\"")).is_err());
    }

    #[test]
    fn a_site_must_say_who_and_where_it_is() {
        let with = |site: &str| Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\n[[site]]\n{site}"));
        assert!(with("name = \"a\"\nhosts = [\"a.example\"]\nroots = [\"/content/a\"]").is_ok());
        assert!(with("name = \"a\"\nhosts = []\nroots = [\"/content/a\"]").is_err());
        assert!(with("name = \"a\"\nhosts = [\"http://a.example\"]\nroots = [\"/content/a\"]").is_err());
        assert!(with("name = \"a\"\nhosts = [\"a.example:8080\"]\nroots = [\"/content/a\"]").is_err());
        assert!(with("name = \"a\"\nhosts = [\"a.example\"]\nroots = []").is_err());
        assert!(with("name = \"a\"\nhosts = [\"a.example\"]\nroots = [\"content/a/\"]").is_err());
        assert!(with("name = \"a\"\nhosts = [\"a.example\"]\nroots = [\"/content/a\"]\npublish = [\"p:4503\"]").is_err());
        assert!(with("name = \"a\"\nhosts = [\"a.example\"]\nroots = [\"/content/a\"]\n[[site]]\nname = \"a\"\nhosts = [\"b.example\"]\nroots = [\"/content/b\"]").is_err());
    }

    #[test]
    fn a_default_site_and_redirect_maps_per_host() {
        let with = |top: &str, site: &str| {
            Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\n{top}\n[[site]]\nname = \"a\"\nhosts = [\"a.example\", \"shop.a.example\"]\nroots = [\"/content/a\"]\n{site}\n[[site]]\nname = \"b\"\nhosts = [\"b.example\"]\nroots = [\"/content/b\"]"))
        };
        // without a default site a host no site names is nobody's; with one, it is that site's
        let none = with("", "").unwrap();
        assert_eq!((none.site_for_host(Some("other.example")), none.site_for_host(None)), (None, None));
        let config = with("default_site = \"b\"", "").unwrap();
        assert_eq!((config.site_for_host(Some("other.example")), config.site_for_host(None)), (Some(1), Some(1)));
        assert_eq!(config.site_for_host(Some("A.Example:80")), Some(0), "a named host stays its own site's");
        assert!(with("default_site = \"c\"", "").unwrap_err().contains("no site is called c"));
        // a host's own map: a path on the publish, for a host name in lower case
        let maps = with("", "host_redirect_maps = { \"shop.a.example\" = \"/content/dam/a/shop.txt\" }").unwrap();
        assert!(maps.has_redirects(0) && !maps.has_redirects(1));
        assert!(with("", "host_redirect_maps = { \"shop.a.example\" = \"content/dam/a/shop.txt\" }").is_err());
        assert!(with("", "host_redirect_maps = { \"Shop.A.Example\" = \"/content/dam/a/shop.txt\" }").is_err());
        assert!(with("", "host_redirect_maps = { \"shop.a.example:8080\" = \"/content/dam/a/shop.txt\" }").is_err());
        assert!(with("", "host_redirect_maps = { \"*.a.example\" = \"/content/dam/a/shop.txt\" }").is_err());
    }

    #[test]
    fn refuses_what_it_does_not_know() {
        assert!(Config::parse("docroot = \"cache\"\npublish = []\n").is_err());
        assert!(Config::parse("docroot = \"cache\"\npublish = [\"localhost:4503\"]\n").is_err());
        assert!(Config::parse("docroot = \"cache\"\npublish = [\"http://p\"]\ndocrot = 1\n").is_err());
        assert!(Config::parse("docroot = \"cache\"\npublish = [\"http://p\"]\ncontent_prefix = \"content/\"\n").is_err());
        assert!(Config::parse("docroot = \"cache\"\npublish = [\"http://p\"]\n[[filter]]\ntype = \"maybe\"\n").is_err());
    }

    #[test]
    fn a_site_has_cache_settings_of_its_own() {
        let text = r#"
docroot = "cache"
publish = ["http://p"]
[cache]
grace_seconds = 10
ignore_query = ["utm_*"]
[[site]]
name = "a"
hosts = ["a.test"]
roots = ["/content/a"]
[site.cache]
enable_ttl = true
grace_seconds = 0
ttl_seconds = 600
allow_authorized = true
stat_level = 2
[[site]]
name = "b"
hosts = ["b.test"]
roots = ["/content/b"]
"#;
        let c = Config::parse(text).unwrap();
        let (a, b) = (c.cache_for(Some(0)), c.cache_for(Some(1)));
        assert!(a.enable_ttl && a.allow_authorized);
        assert_eq!((a.grace_seconds, a.ttl_seconds), (0, Some(600)));
        // what it does not set is the general one
        assert_eq!(a.ignore_query, vec!["utm_*"]);
        assert!(!b.enable_ttl && !b.allow_authorized);
        assert_eq!((b.grace_seconds, b.ttl_seconds), (10, None));
        assert_eq!(c.cache_for(None).grace_seconds, 10);
        assert_eq!((c.stat_level_for(Some(0)), c.stat_level_for(Some(1)), c.stat_level_for(None)), (2, 0, 0));
        // nothing unknown, and a site's checker is a path too
        assert!(Config::parse(&text.replace("enable_ttl = true", "enable_tll = true")).is_err());
        assert!(Config::parse(&text.replace("enable_ttl = true", "auth_checker = \"x\"")).unwrap_err().contains("site a: cache.auth_checker"));
    }

    #[test]
    fn protected_pages_are_kept_beside_the_sites_document_root() {
        let site = "docroot = \"cache\"\npublish = [\"http://p:4503\"]\n[[site]]\nname = \"a\"\nhosts = [\"a.test\"]\nroots = [\"/content/a\"]\n";
        let c = Config::parse(&format!("{site}protected = [\"/content/a/club*\"]\n")).unwrap();
        assert_eq!(c.site_private_docroot(0), PathBuf::from("cache/a.private"));
        assert!(c.protected(0, "/content/a/club.html") && c.protected(0, "/content/a/club/news.html"));
        assert!(!c.protected(0, "/content/a/en.html"));
        assert_eq!(c.cache.auth_checker, "/bin/studio/permissioncheck");
        assert!(Config::parse(&format!("{site}protected = [\"club*\"]\n")).unwrap_err().contains("protected"));
        let own = format!("{site}docroot = \"/srv/a\"\n");
        assert_eq!(Config::parse(&own).unwrap().site_private_docroot(0), PathBuf::from("/srv/a.private"));
        let checker = "docroot = \"cache\"\npublish = [\"http://p:4503\"]\n[cache]\nauth_checker = \"bin/check\"\n";
        assert!(Config::parse(checker).unwrap_err().contains("auth_checker"));
    }
}
