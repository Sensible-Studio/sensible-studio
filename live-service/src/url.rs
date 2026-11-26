//! Request paths: decoding, the Sling decomposition the filter works on, globs, and the mapping
//! of a path to a file below the document root.

use std::path::PathBuf;

/// A request path the way Sling reads it, without asking the repository: everything up to the
/// first dot is the resource path, the dotted part up to the next slash holds the selectors and
/// the extension, what follows is the suffix. `/content/a.model.tidy.json/x` → path `/content/a`,
/// selectors `model`, `tidy`, extension `json`, suffix `/x`.
#[derive(Debug, PartialEq)]
pub struct Parts<'a> {
    pub path: &'a str,
    pub selectors: Vec<&'a str>,
    pub extension: Option<&'a str>,
    pub suffix: &'a str,
}

pub fn decompose(url_path: &str) -> Parts<'_> {
    let Some(dot) = url_path.find('.') else {
        return Parts { path: url_path, selectors: Vec::new(), extension: None, suffix: "" };
    };
    let rest = &url_path[dot + 1..];
    let (dotted, suffix) = match rest.find('/') {
        Some(slash) => (&rest[..slash], &rest[slash..]),
        None => (rest, ""),
    };
    let mut tokens: Vec<&str> = dotted.split('.').collect();
    let extension = tokens.pop().filter(|e| !e.is_empty());
    Parts { path: &url_path[..dot], selectors: tokens, extension, suffix }
}

/// Where persisted GraphQL queries are run: `/graphql/execute.json/<configuration>/<name>`, with
/// their variables as path parameters (`;slug=bali-surf-camp;limit=5`, or `%3B` and `%3D`).
pub const PERSISTED: &str = "/graphql/execute.json/";

/// The query and its variables, if this is a persisted query: `(/graphql/execute.json/conf/name,
/// ";a=1;b=2")`. The part before the variables names a configuration and a query, without `;`.
pub fn persisted(decoded_path: &str) -> Option<(&str, &str)> {
    let rest = decoded_path.strip_prefix(PERSISTED)?;
    let at = rest.find(';').unwrap_or(rest.len());
    let query = &rest[..at];
    let mut segments = query.split('/');
    let (conf, name) = (segments.next()?, segments.next()?);
    if conf.is_empty() || name.is_empty() || segments.next().is_some() {
        return None;
    }
    Some((&decoded_path[..PERSISTED.len() + at], &rest[at..]))
}

/// Decodes the percent-escapes of a request path once (as the publish will) and refuses what has
/// no business in a path: bytes that are not UTF-8, control characters, backslashes, `.` and `..`
/// segments, and `;` path parameters (the servlet container drops them, so `/bin/receive;x.css`
/// would pass a filter as a stylesheet and reach `/bin/receive`). The one exception: the variables
/// of a persisted GraphQL query, after `/graphql/execute.json/<configuration>/<name>`, which is
/// where the container would find that servlet anyway.
pub fn decode_path(raw: &str) -> Option<String> {
    let bytes = raw.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hi = bytes.get(i + 1).and_then(|b| (*b as char).to_digit(16))?;
            let lo = bytes.get(i + 2).and_then(|b| (*b as char).to_digit(16))?;
            out.push((hi * 16 + lo) as u8);
            i += 3;
        } else {
            out.push(bytes[i]);
            i += 1;
        }
    }
    let path = String::from_utf8(out).ok()?;
    if !path.starts_with('/') || path.chars().any(|c| c.is_control() || c == '\\') {
        return None;
    }
    if path.contains(';') && persisted(&path).is_none_or(|(_, variables)| !variables.starts_with(';')) {
        return None;
    }
    if path.split('/').any(|segment| segment == "." || segment == "..") {
        return None;
    }
    Some(path)
}

/// A decoded path as it is written in a request: what is not plainly a path character is escaped.
pub fn encode_path(decoded: &str) -> String {
    let mut out = String::with_capacity(decoded.len());
    for byte in decoded.bytes() {
        if byte.is_ascii_alphanumeric() || b"-._~!$&'()*+,=:@/".contains(&byte) {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

/// `*` is any run of characters (slashes too, as in a dispatcher glob), `?` is one character.
pub fn glob(pattern: &str, text: &str) -> bool {
    let p: Vec<char> = pattern.chars().collect();
    let t: Vec<char> = text.chars().collect();
    let (mut pi, mut ti) = (0, 0);
    let mut star: Option<(usize, usize)> = None;
    while ti < t.len() {
        if pi < p.len() && (p[pi] == '?' || p[pi] == t[ti]) {
            pi += 1;
            ti += 1;
        } else if pi < p.len() && p[pi] == '*' {
            star = Some((pi, ti));
            pi += 1;
        } else if let Some((sp, st)) = star {
            pi = sp + 1;
            ti = st + 1;
            star = Some((sp, st + 1));
        } else {
            return false;
        }
    }
    while pi < p.len() && p[pi] == '*' {
        pi += 1;
    }
    pi == p.len()
}

/// The path of a content node or cached file relative to the document root, or `None` when it
/// cannot be one: an empty or dot-led segment (our temporary files are dot files), a segment
/// longer than a file name may be, a path longer than a path may be.
pub fn relative(decoded_path: &str) -> Option<PathBuf> {
    if !decoded_path.starts_with('/') || decoded_path.len() > 2048 {
        return None;
    }
    let mut rel = PathBuf::new();
    for segment in decoded_path[1..].split('/') {
        if segment.is_empty() || segment.starts_with('.') || segment.len() > 200 || segment.contains('\0') {
            return None;
        }
        rel.push(segment);
    }
    Some(rel)
}

/// Where a response to this path is cached, or `None` when it is not a cacheable name: the last
/// segment needs an extension, so that Apache knows the type and a page never has to be both a
/// file and a directory more often than Sling's suffixes already force.
///
/// A persisted GraphQL query is kept as a file inside a folder named like the query, one file per
/// set of variables (`adventure-by-slug/;slug=bali-surf-camp.json`, `adventures-all/_.json`), so
/// that a variable holding a `/` stays in its file's name; Apache never finds it under the URL and
/// leaves it to this service, which answers from it.
pub fn cache_file(decoded_path: &str) -> Option<PathBuf> {
    if let Some((query, variables)) = persisted(decoded_path) {
        let mut rel = relative(query)?;
        let name = if variables.is_empty() { "_".to_owned() } else { variables.replace('%', "%25").replace('/', "%2F") };
        if name.len() > 195 {
            return None;
        }
        rel.push(format!("{name}.json"));
        return Some(rel);
    }
    let last = decoded_path.rsplit('/').next()?;
    match last.rfind('.') {
        Some(dot) if dot > 0 && dot + 1 < last.len() => relative(decoded_path),
        _ => None,
    }
}

/// First segments that are the repository's own, or this service's: a path that starts with one
/// is already a full path.
const ROOTS: &[&str] = &[
    "content", "etc", "etc.clientlibs", "libs", "apps", "bin", "conf", "home", "var", "system", "tmp", "crx",
    "graphql", "adobe", "dispatcher", "_live", "oak:index", "jcr:system",
];

/// A publish whose URLs leave `/content` out (`/wknd/us/en.html` for `/content/wknd/us/en.html`)
/// answers both spellings. So that a page is filtered, cached and invalidated as one thing, the
/// short spelling is turned back into the full one. Paths of one segment (`/favicon.ico`) are
/// left alone.
pub fn full_path(prefix: &str, path: &str) -> Option<String> {
    let mut segments = path.strip_prefix('/')?.split('/');
    let first = segments.next()?;
    segments.next()?;
    if first.is_empty() || ROOTS.contains(&first) || ROOTS.contains(&first.split('.').next().unwrap_or(first)) {
        return None;
    }
    Some(format!("{prefix}{path}"))
}

/// The names of the query parameters, for the "may this query be ignored" test.
pub fn query_names(query: &str) -> impl Iterator<Item = &str> {
    query.split('&').filter(|p| !p.is_empty()).map(|p| p.split('=').next().unwrap_or(p))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decomposes_like_sling() {
        let p = decompose("/content/wknd/us/en.model.tidy.json/some/suffix.html");
        assert_eq!(p.path, "/content/wknd/us/en");
        assert_eq!(p.selectors, vec!["model", "tidy"]);
        assert_eq!(p.extension, Some("json"));
        assert_eq!(p.suffix, "/some/suffix.html");

        let p = decompose("/content/wknd/us/en.html");
        assert_eq!((p.path, p.extension, p.suffix), ("/content/wknd/us/en", Some("html"), ""));
        assert!(p.selectors.is_empty());

        let p = decompose("/content/wknd/us/en");
        assert_eq!((p.path, p.extension), ("/content/wknd/us/en", None));

        // the first dot decides, as in a dispatcher: client library URLs are matched by `url`
        let p = decompose("/etc.clientlibs/wknd/clientlibs/site.min.css");
        assert_eq!((p.path, p.extension, p.suffix), ("/etc", Some("clientlibs"), "/wknd/clientlibs/site.min.css"));
    }

    #[test]
    fn decoding_refuses_tricks() {
        assert_eq!(decode_path("/content/a%20b.html").as_deref(), Some("/content/a b.html"));
        assert_eq!(decode_path("/system%2Fconsole").as_deref(), Some("/system/console"));
        assert_eq!(decode_path("/content/%2e%2e/system/console"), None);
        assert_eq!(decode_path("/content/../system/console"), None);
        assert_eq!(decode_path("/content/./a.html"), None);
        assert_eq!(decode_path("/bin/receive;x.css"), None);
        assert_eq!(decode_path("/bin/receive%3Bx.css"), None);
        assert_eq!(decode_path("/content/a%00.html"), None);
        assert_eq!(decode_path("/content/a\\b.html"), None);
        assert_eq!(decode_path("/content/%zz.html"), None);
        assert_eq!(decode_path("/content/%ff.html"), None);
        assert_eq!(decode_path("content/a.html"), None);
        // decoded once, like the publish: a double escape stays an escape
        assert_eq!(decode_path("/content/%252e%252e/a.html").as_deref(), Some("/content/%2e%2e/a.html"));
    }

    #[test]
    fn encoding_is_undone_by_decoding() {
        for path in ["/content/wknd/us/en.html", "/content/a b/ö.html", "/content/x/_jcr_content/jcr:content.json", "/content/100%/a?.html"] {
            let encoded = encode_path(path);
            assert!(encoded.is_ascii() && !encoded.contains(' ') && !encoded.contains('?'), "{encoded}");
            assert_eq!(decode_path(&encoded).as_deref(), Some(path));
        }
        assert_eq!(encode_path("/content/a b.html"), "/content/a%20b.html");
    }

    #[test]
    fn globs() {
        assert!(glob("*", ""));
        assert!(glob("/content/*", "/content/wknd/us/en.html"));
        assert!(glob("*.html", "/content/wknd/us/en.html"));
        assert!(glob("/content/*/manifest", "/content/wknd/us/manifest"));
        assert!(glob("utm_*", "utm_source"));
        assert!(glob("a?c", "abc"));
        assert!(!glob("a?c", "ac"));
        assert!(!glob("/content/*", "/conf/wknd"));
        assert!(!glob("*.html", "/content/wknd/us/en.json"));
        assert!(glob("*/contexthub.pagedata.json", "/content/a/_jcr_content/contexthub.pagedata.json"));
    }

    #[test]
    fn cache_files() {
        assert_eq!(cache_file("/content/wknd/us/en.html"), Some(PathBuf::from("content/wknd/us/en.html")));
        assert_eq!(cache_file("/content/wknd/us/en"), None);
        assert_eq!(cache_file("/content/wknd/us/"), None);
        assert_eq!(cache_file("/content/wknd/us/en."), None);
        assert_eq!(cache_file("/content/.hidden/en.html"), None);
        assert_eq!(cache_file("/content//en.html"), None);
        assert_eq!(cache_file("/content/wknd/.en.html.tmp"), None);
        assert_eq!(cache_file(&format!("/content/{}.html", "a".repeat(300))), None);
    }

    #[test]
    fn short_urls_get_their_prefix_back() {
        assert_eq!(full_path("/content", "/wknd/us/en.html").as_deref(), Some("/content/wknd/us/en.html"));
        assert_eq!(full_path("/content", "/experience-fragments/wknd/a.svg").as_deref(), Some("/content/experience-fragments/wknd/a.svg"));
        for full in ["/content/wknd/us/en.html", "/etc.clientlibs/wknd/site.css", "/etc/map.json", "/bin/receive", "/bin.x/receive",
            "/libs/granite/x.json", "/system/console", "/_live/status", "/favicon.ico", "/", "//wknd/a.html"] {
            assert_eq!(full_path("/content", full), None, "{full}");
        }
    }

    #[test]
    fn query_parameter_names() {
        assert_eq!(query_names("utm_source=x&gclid=1&flag").collect::<Vec<_>>(), vec!["utm_source", "gclid", "flag"]);
        assert_eq!(query_names("").count(), 0);
    }

    #[test]
    fn persisted_queries_keep_their_variables() {
        // the variables, written plainly or escaped, are one query
        let plain = decode_path("/graphql/execute.json/wknd-shared/adventure-by-slug;slug=bali-surf-camp").unwrap();
        let escaped = decode_path("/graphql/execute.json/wknd-shared/adventure-by-slug%3Bslug%3Dbali-surf-camp").unwrap();
        assert_eq!(plain, escaped);
        assert_eq!(persisted(&plain), Some(("/graphql/execute.json/wknd-shared/adventure-by-slug", ";slug=bali-surf-camp")));
        assert_eq!(cache_file(&plain), Some(PathBuf::from("graphql/execute.json/wknd-shared/adventure-by-slug/;slug=bali-surf-camp.json")));
        assert_eq!(cache_file("/graphql/execute.json/wknd-shared/adventures-all"), Some(PathBuf::from("graphql/execute.json/wknd-shared/adventures-all/_.json")));
        // a variable with a slash stays in its file's name
        let path = decode_path("/graphql/execute.json/wknd-shared/article-by-path;path=%2Fcontent%2Fdam%2Fa").unwrap();
        assert_eq!(cache_file(&path), Some(PathBuf::from("graphql/execute.json/wknd-shared/article-by-path/;path=%2Fcontent%2Fdam%2Fa.json")));
        // nowhere else: a ; is still how a filter is fooled
        for bad in [
            "/bin/receive;x.css",
            "/content/a.html;x",
            "/graphql/execute.json;x/a/b",
            "/graphql/execute.json/conf;x/name",
            "/graphql/execute.json/conf/name/more;x=1",
            "/graphql/execute.json/conf;/name",
            "/graphql/execute.json/conf/name;a=/../../bin/receive",
        ] {
            assert_eq!(decode_path(bad), None, "{bad}");
        }
        assert_eq!(persisted("/graphql/execute.json/only-a-conf"), None);
        assert_eq!(cache_file(&format!("/graphql/execute.json/c/q;v={}", "x".repeat(300))), None, "too long a name");
    }
}
