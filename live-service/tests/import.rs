//! An existing dispatcher configuration read into a live.toml.

use std::path::Path;

use studio_live::config::Config;
use studio_live::filter::{self, Request};
use studio_live::url::decompose;

#[test]
fn a_dispatcher_configuration_becomes_a_live_toml() {
    let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/dispatcher/dispatcher.any");
    let root = studio_live::dispatcher::read(&file).unwrap();
    let text = studio_live::dispatcher::to_toml(&root, &file).unwrap();
    let config = Config::parse(&text).unwrap_or_else(|e| panic!("{e}\n{text}"));

    // the farm is a site, with its host names, pages and publish instance
    assert_eq!(config.site.len(), 1);
    let site = &config.site[0];
    assert_eq!(site.name, "publishfarm");
    assert_eq!(site.hosts, vec!["www.mysite.example", "mysite.example", "shop.mysite.example"]);
    assert_eq!(site.roots, vec!["/content/mysite"]);
    assert_eq!(site.publish, vec!["http://127.0.0.1:4503"]);
    assert_eq!(site.protected, vec!["/content/mysite/members/*.html"], "the auth checker's pages");
    // the cache settings
    assert_eq!(config.stat_level, 2);
    assert_eq!(config.cache.invalidate, vec!["*.html", "/etc/segmentation.segment.js"]);
    assert_eq!(config.cache.ignore_query, vec!["utm_*", "gclid"]);
    assert_eq!(config.cache.grace_seconds, 2);
    assert!(config.cache.enable_ttl, "/enableTTL \"1\"");
    assert!(!config.cache.allow_authorized, "/allowAuthorized \"0\"");
    assert_eq!(config.vanity.url.as_deref(), Some("/libs/granite/dispatcher/content/vanityUrls.json"));
    // the filter: all 9 rules, real regexes and the suffix included
    assert_eq!(site.filter.len(), 9, "{text}");
    assert!(!text.contains("not carried over (/"), "{text}");
    assert!(text.contains(r#"suffix = { regex = "(.*)\\.html" }"#), "{text}");
    assert!(text.contains(r#"url = { regex = "^/content/(a|b)/.*$" }"#), "{text}");
    assert!(text.contains(r#"selectors = { regex = "(feed|rss|pages|languages|blueprint|infinity|tidy|sysview|docview|query|[0-9-]+|jcr:content)" }"#), "{text}");

    // and decides as the dispatcher did
    let decide = |method: &str, url: &str| {
        let parts = decompose(url);
        filter::decide_with(&config.filter, &site.filter, &Request { method, url, query: "", parts: &parts }).0
    };
    assert!(decide("GET", "/content/mysite/en.html"));
    assert!(decide("GET", "/etc.clientlibs/mysite/site.css"));
    assert!(decide("HEAD", "/content/mysite/en.model.json"));
    assert!(!decide("POST", "/content/mysite/en.model.json"));
    assert!(!decide("GET", "/content/mysite/en.feed.xml"));
    assert!(!decide("GET", "/content/mysite/en.pages.json"));
    assert!(!decide("GET", "/content/mysite/en.infinity.json"), "closed whatever the rules say");
    assert!(!decide("GET", "/bin/receive"));
    assert!(decide("GET", "/libs/granite/csrf/token.json"));
    // what only the regexes and the suffix said
    assert!(!decide("GET", "/content/mysite/en.2.html"), "a numeric selector, denied by the selector regex");
    assert!(decide("GET", "/content/a/notes.txt"), "the url regex");
    assert!(!decide("GET", "/content/c/notes.txt"));
    assert!(decide("GET", "/content/mysite/en.json/print.html"), "the suffix regex");
    assert!(!decide("GET", "/content/mysite/en.json/print.txt"));
}

#[test]
fn every_farm_keeps_its_own_cache_settings() {
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("dispatcher.any");
    std::fs::write(
        &file,
        r#"/farms {
  /one {
    /health_check { /url "/health_check.html" }
    /statistics { /categories { /html { /glob "*.html" } /others { /glob "*" } } }
    /unavailablePenalty "5"
    /virtualhosts { "one.example" }
    /renders { /0 { /hostname "p1" /port "4503" } }
    /filter { /0 { /type "allow" /url "/content/one/*" } }
    /cache { /docroot "/var/cache" /statfileslevel "1" /gracePeriod "5" /ignoreUrlParams { /0 { /glob "utm_*" /type "allow" } } }
  }
  /two {
    /clientheaders { "X-Custom" "Cookie" }
    /virtualhosts { "two.example" }
    /renders { /0 { /hostname "p2" /port "4503" } }
    /filter { /0 { /type "allow" /url "/content/two/*" } }
    /cache { /docroot "/var/cache" /statfileslevel "3" /enableTTL "1" /allowAuthorized "1" /ignoreUrlParams { /0 { /glob "*" /type "allow" } } }
  }
}"#,
    )
    .unwrap();
    let root = studio_live::dispatcher::read(&file).unwrap();
    let text = studio_live::dispatcher::to_toml(&root, &file).unwrap();
    let config = Config::parse(&text).unwrap_or_else(|e| panic!("{e}\n{text}"));
    // the first farm's are the general settings; the second's are its site's own
    let (one, two) = (config.cache_for(Some(0)), config.cache_for(Some(1)));
    assert_eq!((one.grace_seconds, one.enable_ttl, one.allow_authorized), (5, false, false));
    assert_eq!(one.ignore_query, vec!["utm_*"]);
    assert!(two.enable_ttl && two.allow_authorized);
    assert_eq!(two.ignore_query, vec!["*"]);
    assert_eq!(two.grace_seconds, 5, "what the second farm does not say is the general one");
    assert_eq!(config.site[1].filter.len(), 1, "its filter after its cache settings");
    assert_eq!(config.stat_level, 1);
    assert_eq!((config.stat_level_for(Some(0)), config.stat_level_for(Some(1))), (1, 3), "{text}");
    // its own request headers for the publish; a farm without a list tells it all of them
    assert_eq!(config.site[1].client_headers.as_deref(), Some(&["x-custom".to_owned(), "cookie".to_owned()][..]));
    assert!(config.site[0].client_headers.is_none());
    // the renders chosen by speed and checked after a 500
    assert_eq!(config.balance, studio_live::config::Balance::Fastest);
    assert_eq!((config.health_check.as_deref(), config.unavailable_penalty_ms), (Some("/health_check.html"), 500));
}

#[test]
fn includes_and_errors() {
    let dir = tempfile::tempdir().unwrap();
    std::fs::write(dir.path().join("a.any"), "/farms { $include \"b.any\" }").unwrap();
    std::fs::write(dir.path().join("b.any"), "$include \"a.any\"").unwrap();
    let e = studio_live::dispatcher::read(&dir.path().join("a.any")).unwrap_err();
    assert!(e.contains("includes itself"), "{e}");
    std::fs::write(dir.path().join("c.any"), "/farms { /f { /filter { /0001 { /type \"allow\" ").unwrap();
    assert!(studio_live::dispatcher::read(&dir.path().join("c.any")).unwrap_err().contains("not closed"));
    std::fs::write(dir.path().join("d.any"), "/name \"x\"").unwrap();
    let root = studio_live::dispatcher::read(&dir.path().join("d.any")).unwrap();
    assert!(studio_live::dispatcher::to_toml(&root, &dir.path().join("d.any")).unwrap_err().contains("no /farms"));
}

#[test]
fn variables_come_from_the_project_s_defines_and_the_command_line() {
    // laid out as an AEM project keeps it: conf.d/variables beside conf.dispatcher.d
    let dir = tempfile::tempdir().unwrap();
    let project = dir.path().join("dispatcher").join("src");
    std::fs::create_dir_all(project.join("conf.d/variables")).unwrap();
    std::fs::create_dir_all(project.join("conf.dispatcher.d/renders")).unwrap();
    std::fs::write(
        project.join("conf.d/variables/custom.vars"),
        "# the site's names\nDefine SITE_HOST \"www.site.example\"\nDefine AEM_PORT 4503\nDefine RENDERS default\nDefine OVERRIDDEN from-the-file\n",
    )
    .unwrap();
    std::fs::write(project.join("conf.dispatcher.d/renders/default.any"), r#"/0 { /hostname "${AEM_HOST}" /port "${AEM_PORT}" }"#).unwrap();
    let file = project.join("conf.dispatcher.d/dispatcher.any");
    std::fs::write(
        &file,
        r#"/farms {
  /site {
    /virtualhosts { "${SITE_HOST}" "${OVERRIDDEN}.example" }
    /renders { $include "renders/${RENDERS}.any" }
    /filter { /0 { /type "allow" /url "/content/site/*" } }
    /cache { /docroot "${DOCROOT_NOBODY_SET_THIS}" }
  }
}"#,
    )
    .unwrap();
    let given = vec![("AEM_HOST".to_owned(), "publish.internal".to_owned()), ("OVERRIDDEN".to_owned(), "given".to_owned())];
    let variables = studio_live::dispatcher::Variables::gather(&file, &given, &[]).unwrap();
    let root = studio_live::dispatcher::read_with(&file, &variables).unwrap();
    let text = studio_live::dispatcher::to_toml(&root, &file).unwrap();
    let config = Config::parse(&text).unwrap_or_else(|e| panic!("{e}\n{text}"));
    assert_eq!(config.site[0].hosts, vec!["www.site.example", "given.example"], "{text}");
    assert_eq!(config.publish, vec!["http://publish.internal:4503"], "{text}");
    // one without a value stays, and is named
    assert!(text.contains("${DOCROOT_NOBODY_SET_THIS} had no value"), "{text}");
}
