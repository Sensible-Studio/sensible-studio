//! What may reach the publish. Deny by default; the rules of the configuration are read in order
//! and the last one that matches decides; a short built-in list is denied whatever they say.

use serde::{Deserialize, Serialize};

use crate::url::{glob, Parts};

#[derive(Clone, Copy, Debug, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    Allow,
    Deny,
}

/// What a field of a rule is matched with: a glob (`"/content/*"`, `*` crosses slashes) or a regular
/// expression (`{ regex = "/content/(a|b)/.*" }`), which must match the whole value, as a
/// dispatcher's single-quoted values do.
#[derive(Clone, Debug)]
pub enum Pattern {
    Glob(String),
    Regex(String, regex::Regex),
}

fn compile(text: &str) -> Result<regex::Regex, String> {
    regex::Regex::new(&format!("^(?:{text})$")).map_err(|e| format!("not a regular expression: {text}: {e}"))
}

impl Pattern {
    pub fn matches(&self, text: &str) -> bool {
        match self {
            Pattern::Glob(g) => glob(g, text),
            Pattern::Regex(_, r) => r.is_match(text),
        }
    }
}

#[derive(Deserialize)]
#[serde(untagged)]
enum RawPattern {
    Glob(String),
    Regex { regex: String },
}

impl<'de> Deserialize<'de> for Pattern {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        match RawPattern::deserialize(d)? {
            RawPattern::Glob(g) => Ok(Pattern::Glob(g)),
            RawPattern::Regex { regex } => compile(&regex).map(|r| Pattern::Regex(regex, r)).map_err(serde::de::Error::custom),
        }
    }
}

impl Serialize for Pattern {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        match self {
            Pattern::Glob(g) => s.serialize_str(g),
            Pattern::Regex(text, _) => {
                use serde::ser::SerializeMap;
                let mut m = s.serialize_map(Some(1))?;
                m.serialize_entry("regex", text)?;
                m.end()
            }
        }
    }
}

/// Words a field may be (extensions, selectors): a list, or a regular expression each one must
/// match whole (`{ regex = "feed|rss|[0-9-]+" }`).
#[derive(Clone, Debug, Default)]
pub enum Words {
    #[default]
    None,
    List(Vec<String>),
    Regex(String, regex::Regex),
}

impl Words {
    pub fn is_empty(&self) -> bool {
        match self {
            Words::None => true,
            Words::List(l) => l.is_empty(),
            Words::Regex(..) => false,
        }
    }

    /// Does this word match (`exact`: list items compared without case, else as globs)?
    fn has(&self, word: &str, exact: bool) -> bool {
        match self {
            Words::None => false,
            Words::List(l) => l.iter().any(|w| if exact { w.eq_ignore_ascii_case(word) } else { glob(w, word) }),
            Words::Regex(_, r) => r.is_match(word),
        }
    }
}

#[derive(Deserialize)]
#[serde(untagged)]
enum RawWords {
    List(Vec<String>),
    Regex { regex: String },
}

impl<'de> Deserialize<'de> for Words {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        match RawWords::deserialize(d)? {
            RawWords::List(l) => Ok(Words::List(l)),
            RawWords::Regex { regex } => compile(&regex).map(|r| Words::Regex(regex, r)).map_err(serde::de::Error::custom),
        }
    }
}

impl Serialize for Words {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        match self {
            Words::None => s.serialize_seq(Some(0))?.end(),
            Words::List(l) => l.serialize(s),
            Words::Regex(text, _) => {
                use serde::ser::SerializeMap;
                let mut m = s.serialize_map(Some(1))?;
                m.serialize_entry("regex", text)?;
                m.end()
            }
        }
    }
}

use serde::ser::SerializeSeq;

/// One `[[filter]]` of the configuration. Every field that is set must match. The fields are the
/// ones of a dispatcher filter rule, so an existing rule set translates line by line. `url`,
/// `path`, `suffix` and `query` are globs or `{ regex = "…" }`; `extensions` and `selectors` lists
/// or `{ regex = "…" }`.
#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    #[serde(rename = "type")]
    pub kind: Kind,
    /// Request methods; empty: any.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub method: Vec<String>,
    /// The whole request path.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub url: Option<Pattern>,
    /// The resource path (the part before the first dot).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<Pattern>,
    /// The extension is one of these; empty: any, also none.
    #[serde(default, skip_serializing_if = "Words::is_empty")]
    pub extensions: Words,
    /// Globs on the selectors. A `deny` matches when any selector of the request matches one; an
    /// `allow` only when the request has selectors and every one of them matches.
    #[serde(default, skip_serializing_if = "Words::is_empty")]
    pub selectors: Words,
    /// The suffix (what follows the extension, `/x.html`; empty when there is none), as a
    /// dispatcher's `/suffix`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suffix: Option<Pattern>,
    /// The query string.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub query: Option<Pattern>,
}

pub struct Request<'a> {
    pub method: &'a str,
    /// The decoded request path.
    pub url: &'a str,
    pub query: &'a str,
    pub parts: &'a Parts<'a>,
}

impl Rule {
    fn matches(&self, r: &Request) -> bool {
        if !self.method.is_empty() && !self.method.iter().any(|m| m.eq_ignore_ascii_case(r.method)) {
            return false;
        }
        if self.url.as_ref().is_some_and(|url| !url.matches(r.url)) {
            return false;
        }
        if self.path.as_ref().is_some_and(|path| !path.matches(r.parts.path)) {
            return false;
        }
        if !self.extensions.is_empty() {
            match r.parts.extension {
                Some(ext) if self.extensions.has(ext, true) => {}
                _ => return false,
            }
        }
        if !self.selectors.is_empty() {
            let hit = |s: &&str| self.selectors.has(s, false);
            let ok = match self.kind {
                Kind::Deny => r.parts.selectors.iter().any(hit),
                Kind::Allow => !r.parts.selectors.is_empty() && r.parts.selectors.iter().all(hit),
            };
            if !ok {
                return false;
            }
        }
        if self.suffix.as_ref().is_some_and(|suffix| !suffix.matches(r.parts.suffix)) {
            return false;
        }
        !self.query.as_ref().is_some_and(|query| !query.matches(r.query))
    }
}

/// Never served, whatever the rules say: the replication endpoints, the system and repository
/// consoles, the agents with their credentials, this service's own endpoints.
const NEVER: &[&str] = &[
    "/bin/receive",
    "/bin/outbox",
    "/system/console",
    "/crx",
    "/etc/replication",
    "/var/studio/replication",
    "/dispatcher",
    "/_live",
];

/// Selectors that turn Sling's default JSON and XML renderers into a dump of a whole subtree.
const DUMP_SELECTORS: &[&str] = &["infinity", "tidy", "sysview", "docview", "query"];

fn never(r: &Request) -> bool {
    let under = |p: &str| {
        r.url == p || (r.url.starts_with(p) && matches!(r.url.as_bytes()[p.len()], b'/' | b'.'))
    };
    if NEVER.iter().any(|p| under(p)) {
        return true;
    }
    let dumps = matches!(r.parts.extension, Some(e) if e.eq_ignore_ascii_case("json") || e.eq_ignore_ascii_case("xml"));
    dumps
        && r.parts.selectors.iter().any(|s| {
            DUMP_SELECTORS.iter().any(|d| d.eq_ignore_ascii_case(s))
                || (!s.is_empty() && s.trim_start_matches('-').chars().all(|c| c.is_ascii_digit()))
        })
}

/// What decided about a request.
#[derive(Debug, PartialEq)]
pub enum By {
    /// The built-in list: closed whatever the rules say.
    BuiltIn,
    /// The rule at this position (from 0), the last one that matched.
    Rule(usize),
    /// No rule matched: refused, as everything is unless allowed.
    NoRule,
}

pub fn decide(rules: &[Rule], r: &Request) -> (bool, By) {
    decide_with(rules, &[], r)
}

/// As [`decide`], with a second list read after the first (a site's own rules after the general
/// ones). The position in [`By::Rule`] counts through both.
pub fn decide_with(rules: &[Rule], more: &[Rule], r: &Request) -> (bool, By) {
    if never(r) {
        return (false, By::BuiltIn);
    }
    let mut verdict = (false, By::NoRule);
    for (index, rule) in rules.iter().chain(more).enumerate() {
        if rule.matches(r) {
            verdict = (rule.kind == Kind::Allow, By::Rule(index));
        }
    }
    verdict
}

pub fn allowed(rules: &[Rule], r: &Request) -> bool {
    decide(rules, r).0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::url::decompose;

    fn rules(toml_text: &str) -> Vec<Rule> {
        #[derive(Deserialize)]
        struct File {
            filter: Vec<Rule>,
        }
        toml::from_str::<File>(toml_text).unwrap().filter
    }

    fn check(rules: &[Rule], method: &str, url: &str, query: &str) -> bool {
        let parts = decompose(url);
        allowed(rules, &Request { method, url, query, parts: &parts })
    }

    const SITE: &str = r#"
        [[filter]]
        type = "allow"
        method = ["GET", "HEAD"]
        path = "/content/*"
        extensions = ["html", "css", "js", "png", "jpg"]

        [[filter]]
        type = "allow"
        method = ["GET"]
        path = "/content/*"
        selectors = ["model"]
        extensions = ["json"]

        [[filter]]
        type = "allow"
        method = ["GET"]
        url = "/etc.clientlibs/*"

        [[filter]]
        type = "allow"
        method = ["POST"]
        url = "/bin/studio/forms/submit"

        [[filter]]
        type = "deny"
        selectors = ["feed", "rss", "blueprint"]

        [[filter]]
        type = "deny"
        query = "*wcmmode=*"
    "#;

    #[test]
    fn who_decided() {
        let r = rules(SITE);
        let by = |method: &str, url: &str, query: &str| {
            let parts = decompose(url);
            decide(&r, &Request { method, url, query, parts: &parts })
        };
        assert_eq!(by("GET", "/content/wknd/us/en.html", ""), (true, By::Rule(0)));
        assert_eq!(by("GET", "/content/wknd/us/en.model.json", ""), (true, By::Rule(1)));
        assert_eq!(by("GET", "/content/wknd/us/en.feed.html", ""), (false, By::Rule(4)));
        assert_eq!(by("GET", "/content/wknd/us/en.html", "wcmmode=disabled"), (false, By::Rule(5)));
        assert_eq!(by("GET", "/apps/wknd/x.jsp", ""), (false, By::NoRule));
        assert_eq!(by("GET", "/bin/receive", ""), (false, By::BuiltIn));

        // a site's rules are read after the general ones, and cannot open the built-in list either
        let site = rules("[[filter]]\ntype = \"allow\"\nurl = \"/apps/wknd/*\"\n[[filter]]\ntype = \"deny\"\nurl = \"/content/wknd/us/secret*\"\n[[filter]]\ntype = \"allow\"\nurl = \"/bin/receive\"\n");
        let with = |url: &str| {
            let parts = decompose(url);
            decide_with(&r, &site, &Request { method: "GET", url, query: "", parts: &parts })
        };
        assert_eq!(with("/apps/wknd/x.jsp"), (true, By::Rule(6)));
        assert_eq!(with("/content/wknd/us/secret.html"), (false, By::Rule(7)));
        assert_eq!(with("/content/wknd/us/en.html"), (true, By::Rule(0)));
        assert_eq!(with("/bin/receive"), (false, By::BuiltIn));
    }

    #[test]
    fn regexes_and_the_suffix() {
        let r = rules(
            r#"
            [[filter]]
            type = "allow"
            url = { regex = "/content/(one|two)/[a-z]+\\.html" }

            [[filter]]
            type = "allow"
            path = "/content/one/*"
            extensions = { regex = "json|xml" }
            selectors = { regex = "model|print-[a-z]+" }

            [[filter]]
            type = "allow"
            path = "/content/one/*"
            suffix = { regex = "/[a-z]+\\.pdf" }

            [[filter]]
            type = "deny"
            path = "/content/one/*"
            suffix = "/secret*"
            "#,
        );
        // a regex matches the whole value
        assert!(check(&r, "GET", "/content/one/en.html", ""));
        assert!(check(&r, "GET", "/content/two/de.html", ""));
        assert!(!check(&r, "GET", "/content/three/en.html", ""));
        assert!(!check(&r, "GET", "/content/one/en.html.bak", ""));
        assert!(!check(&r, "GET", "/content/one/EN.html", ""));
        // every selector of an allow must match it
        assert!(check(&r, "GET", "/content/one/en.model.json", ""));
        assert!(check(&r, "GET", "/content/one/en.model.print-a.xml", ""));
        assert!(!check(&r, "GET", "/content/one/en.model.print-.xml", ""));
        assert!(!check(&r, "GET", "/content/one/en.model.tidy.json", ""));
        assert!(!check(&r, "GET", "/content/one/en.model.txt", ""));
        // the suffix
        assert!(check(&r, "GET", "/content/one/en.json/print.pdf", ""));
        assert!(!check(&r, "GET", "/content/one/en.json/print.pdf.exe", ""));
        assert!(!check(&r, "GET", "/content/one/en.json/secret.pdf", ""), "the deny on the suffix comes last");
        // shown back as written (the console, explain)
        assert_eq!(serde_json::to_value(&r[0]).unwrap()["url"]["regex"], "/content/(one|two)/[a-z]+\\.html");
        assert_eq!(serde_json::to_value(&r[3]).unwrap()["suffix"], "/secret*");
        // a regex that is none is refused when the file is read
        #[derive(Deserialize)]
        struct File {
            #[allow(dead_code)]
            filter: Vec<Rule>,
        }
        assert!(toml::from_str::<File>("[[filter]]\ntype = \"allow\"\nurl = { regex = \"(\" }\n").is_err());
        assert!(toml::from_str::<File>("[[filter]]\ntype = \"allow\"\nurl = { regx = \"a\" }\n").is_err());
    }

    #[test]
    fn nothing_without_rules() {
        assert!(!check(&[], "GET", "/content/wknd/us/en.html", ""));
    }

    #[test]
    fn a_site() {
        let r = rules(SITE);
        assert!(check(&r, "GET", "/content/wknd/us/en.html", ""));
        assert!(check(&r, "HEAD", "/content/wknd/us/en.html", ""));
        assert!(check(&r, "GET", "/content/wknd/us/en.model.json", ""));
        assert!(check(&r, "GET", "/etc.clientlibs/wknd/clientlibs/site.min.css", ""));
        assert!(check(&r, "POST", "/bin/studio/forms/submit", ""));

        assert!(!check(&r, "POST", "/content/wknd/us/en.html", ""));
        assert!(!check(&r, "GET", "/content/wknd/us/en.json", ""));
        assert!(!check(&r, "GET", "/content/wknd/us/en", ""));
        assert!(!check(&r, "GET", "/content/wknd/us/en.feed.html", ""));
        assert!(!check(&r, "GET", "/content/wknd/us/en.html", "wcmmode=disabled"));
        assert!(!check(&r, "GET", "/bin/studio/security.json", ""));
        assert!(!check(&r, "GET", "/bin/studio/forms/submit", ""));
        assert!(!check(&r, "GET", "/apps/wknd/components/page/page.html", ""));
        // an allow with selectors wants every selector to be a listed one
        assert!(!check(&r, "GET", "/content/wknd/us/en.model.other.json", ""));
    }

    #[test]
    fn the_built_in_list_wins_over_the_rules() {
        let open = rules("[[filter]]\ntype = \"allow\"\nurl = \"*\"\n");
        assert!(check(&open, "GET", "/content/wknd/us/en.html", ""));
        for url in [
            "/bin/receive",
            "/bin/receive.html",
            "/bin/outbox",
            "/system/console",
            "/system/console/bundles",
            "/crx/de/index.jsp",
            "/etc/replication/agents.author/publish.html",
            "/etc/replication.infinity.json",
            "/var/studio/replication/outbox.json",
            "/dispatcher/invalidate.cache",
            "/_live/status",
            "/content/wknd.infinity.json",
            "/content/wknd.tidy.-1.json",
            "/content/wknd.4.json",
            "/content/wknd.model.INFINITY.json",
            "/content/wknd.docview.xml",
            "/home/users.1.json",
        ] {
            assert!(!check(&open, "GET", url, ""), "{url} must stay closed");
        }
        // a look-alike name is not the path
        assert!(check(&open, "GET", "/bin/received.html", ""));
        assert!(check(&open, "GET", "/content/wknd/us/en.2.html", ""));
    }
}
