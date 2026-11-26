//! Tags for a CDN in front that purges by tag (Fastly's surrogate keys, Cloudflare's and
//! CloudFront's cache tags, Akamai's edge cache tags): what an answer is tagged with, and what a
//! publication purges, so that the CDN takes out what this cache takes out.
//!
//! An answer carries `all-<site>` (everything of the site), `site-<site>` when the cache's own
//! invalidation would take it out with every page of the site (`cache.invalidate`), the tags of
//! its own page or asset, and the tags of what it says it shows (`X-Studio-Depends`):
//! `<page>/jcr:content` as the page's tag, `<page>/*` as the tag of the page's list of children,
//! anything else as the tag of that tree. An answer that says nothing (or more than the CDN takes)
//! carries `nodeps-<site>` instead, which every publication in the site purges.
//!
//! Paths become short hashes (`p-`, `c-`, `t-` and 16 hexadecimal digits): every CDN takes them
//! (Akamai allows no `:` and 128 characters a tag), and a long page needs a few kilobytes at most.

use crate::cache::Action;

/// FNV-1a, 64 bits.
fn fnv(text: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for byte in text.bytes() {
        hash ^= byte as u64;
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

fn hashed(kind: char, path: &str) -> String {
    format!("{kind}-{:016x}", fnv(&format!("{kind}:{path}")))
}

/// A page's (or an asset's) own tag.
pub fn page_tag(path: &str) -> String {
    hashed('p', path)
}

/// The tag of a page's list of children.
pub fn children_tag(path: &str) -> String {
    hashed('c', path)
}

/// The tag of a tree: what shows anything at or below `path`.
pub fn tree_tag(path: &str) -> String {
    hashed('t', path)
}

pub fn all_tag(site: &str) -> String {
    format!("all-{site}")
}

pub fn site_tag(site: &str) -> String {
    format!("site-{site}")
}

pub fn nodeps_tag(site: &str) -> String {
    format!("nodeps-{site}")
}

/// The page or asset a path is of: up to its content node (`jcr:content`, `_jcr_content` in URLs).
pub fn page_of(path: &str) -> &str {
    let cut = ["/jcr:content", "/_jcr_content"].iter().filter_map(|node| path.find(node)).min();
    let page = cut.map_or(path, |at| &path[..at]);
    page.strip_suffix('/').filter(|p| !p.is_empty()).unwrap_or(page)
}

/// The path without what follows the first dot of its last segment (`/a/en.html` → `/a/en`).
fn without_extension(path: &str) -> &str {
    let start = path.rfind('/').map_or(0, |at| at + 1);
    match path[start..].find('.') {
        Some(dot) if dot > 0 => &path[..start + dot],
        _ => path,
    }
}

/// The tags of an answer to a request for `path`. `auto`: the cache's own invalidation takes it
/// out with the site's pages. `shows`: what it says it shows, `None` when it says nothing.
pub fn answer_tags(site: &str, path: &str, shows: Option<&[String]>, auto: bool, max: usize) -> Vec<String> {
    let mut own = vec![all_tag(site)];
    if auto {
        own.push(site_tag(site));
    }
    let page = page_of(path);
    for p in [page, without_extension(page)] {
        let tag = page_tag(p);
        if !own.contains(&tag) {
            own.push(tag);
        }
    }
    let Some(shows) = shows else {
        own.push(nodeps_tag(site));
        return own;
    };
    let mut tags = own.clone();
    for shown in shows {
        let tag = if let Some(parent) = shown.strip_suffix("/*") {
            children_tag(parent)
        } else if shown.contains("/jcr:content") || shown.contains("/_jcr_content") {
            page_tag(page_of(shown))
        } else {
            tree_tag(shown)
        };
        if !tags.contains(&tag) {
            tags.push(tag);
        }
    }
    if tags.len() > max {
        // more than the CDN keeps: as one that says nothing
        own.push(nodeps_tag(site));
        return own;
    }
    tags
}

/// What a publication of `handle` purges in the site: what the cache takes out there. `by_dependencies`:
/// the site's cache goes by what pages show (`invalidate_by_dependencies`); otherwise every page
/// of the site goes, as `invalidate` says.
pub fn purge_tags(site: &str, action: Action, handle: &str, resource_only: bool, by_dependencies: bool) -> Vec<String> {
    let page = page_of(handle);
    if action == Action::Test {
        return Vec::new();
    }
    if action != Action::Activate {
        // unpublished: it and everything below it, whatever their tags; rare enough to be whole
        return vec![all_tag(site)];
    }
    let mut tags = vec![page_tag(page)];
    if resource_only {
        return tags;
    }
    if let Some((parent, name)) = page.rsplit_once('/') {
        if !name.is_empty() {
            tags.push(children_tag(if parent.is_empty() { "/" } else { parent }));
        }
    }
    // what shows anything at or above the published path, as a tree
    let mut at = page;
    loop {
        tags.push(tree_tag(at));
        match at.rsplit_once('/') {
            Some((parent, _)) if !parent.is_empty() => at = parent,
            _ => break,
        }
    }
    tags.push(if by_dependencies { nodeps_tag(site) } else { site_tag(site) });
    tags
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tags_of(shows: &[&str]) -> Vec<String> {
        let shows: Vec<String> = shows.iter().map(|s| s.to_string()).collect();
        answer_tags("wknd", "/content/wknd/us/en.html", Some(&shows), true, 800)
    }

    fn purged(handle: &str, tags: &[String]) -> bool {
        purge_tags("wknd", Action::Activate, handle, false, true).iter().any(|t| tags.contains(t) && !t.starts_with("all-"))
    }

    #[test]
    fn a_page_s_tags_and_what_purges_them() {
        // the home page: its own, what it shows
        let tags = tags_of(&[
            "/content/wknd/us/en/faqs/jcr:content",  // in the navigation
            "/content/wknd/us/en/magazine/*",         // lists the magazine's articles
            "/content/dam/wknd/en/surf.jpg",          // an image
            "/conf/wknd/settings/wcm/policies/x",     // a policy
        ]);
        // its own publication; a rendition of its own image block
        assert!(purged("/content/wknd/us/en", &tags));
        assert!(purged("/content/wknd/us/en/jcr:content", &tags));
        // what it shows
        assert!(purged("/content/wknd/us/en/faqs", &tags));
        assert!(purged("/content/wknd/us/en/magazine/new-article", &tags), "a new child of the listed page");
        assert!(purged("/content/dam/wknd/en/surf.jpg", &tags));
        assert!(purged("/content/dam/wknd/en/surf.jpg/jcr:content/renditions/original", &tags));
        assert!(purged("/conf/wknd/settings/wcm/policies/x", &tags));
        // and nothing else
        assert!(!purged("/content/wknd/us/en/adventures", &tags));
        assert!(!purged("/content/wknd/us/en/magazine/new-article/deeper", &tags), "not a child of the listed page");
        assert!(!purged("/content/wknd/us/en/faqs/sub", &tags));
        assert!(!purged("/content/dam/wknd/en/other.jpg", &tags));
        assert!(!purged("/content/wknd/ca/en", &tags));
    }

    #[test]
    fn as_the_cache_decides() {
        // where the cache's rule (shows) says a publication changes what a page shows, the tags
        // say so too; the one case they leave out is a page activated above a shown path, which
        // changes only that page's own content
        let shown = [
            "/content/wknd/us/en/faqs/jcr:content",
            "/content/wknd/us/en/magazine/*",
            "/content/dam/wknd/en/surf.jpg",
            "/content/experience-fragments/wknd/header/master",
        ];
        let handles = [
            "/content/wknd/us/en/faqs",
            "/content/wknd/us/en/faqs/child",
            "/content/wknd/us/en/magazine/a",
            "/content/wknd/us/en/magazine",
            "/content/dam/wknd/en/surf.jpg",
            "/content/dam/wknd/en/surf.jpg/jcr:content/renditions/x",
            "/content/experience-fragments/wknd/header/master",
            "/content/experience-fragments/wknd/header/master/jcr:content",
            "/content/wknd/us/en/adventures",
            "/content/dam/wknd/en",
        ];
        for s in shown {
            let tags = tags_of(&[s]);
            for h in handles {
                let cache = crate::cache::shows(s, h);
                let cdn = purged(h, &tags);
                let above = s.strip_prefix(h).is_some_and(|rest| rest.starts_with('/') && rest != "/jcr:content");
                if !above {
                    assert_eq!(cdn, cache, "{s} shown, {h} published");
                } else {
                    assert!(!cdn || cache, "{s} shown, {h} published");
                }
            }
        }
    }

    #[test]
    fn its_own_tags() {
        let tags = answer_tags("wknd", "/content/dam/wknd/a.jpg", None, false, 800);
        assert!(tags.contains(&page_tag("/content/dam/wknd/a.jpg")) && tags.contains(&nodeps_tag("wknd")) && !tags.contains(&site_tag("wknd")));
        let image = answer_tags("wknd", "/content/wknd/us/en/_jcr_content/root/image.coreimg.60.jpeg/1/a.jpeg", Some(&[]), false, 800);
        assert!(image.contains(&page_tag("/content/wknd/us/en")), "{image:?}");
        // every tag is one any CDN takes
        for t in tags.iter().chain(&image) {
            assert!(t.len() <= 128 && t.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'), "{t}");
        }
        // more than the CDN keeps: as one that says nothing
        let many: Vec<String> = (0..60).map(|n| format!("/content/dam/x{n}.jpg")).collect();
        let tags = answer_tags("wknd", "/content/wknd/us/en.html", Some(&many), true, 50);
        assert!(tags.len() <= 50 && tags.contains(&nodeps_tag("wknd")));
        // what an unpublication purges: all of the site
        assert_eq!(purge_tags("wknd", Action::Deactivate, "/content/wknd/us/en/x", false, true), vec![all_tag("wknd")]);
        // not by dependencies: every page of the site
        assert!(purge_tags("wknd", Action::Activate, "/conf/wknd/x", false, false).contains(&site_tag("wknd")));
    }
}
