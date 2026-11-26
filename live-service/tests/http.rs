//! The service between a client and a stand-in publish instance, over real sockets.

use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use axum::body::Bytes;
use axum::extract::{Request, State};
use axum::http::{HeaderMap, Method, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Router;
use studio_live::config::Config;
use studio_live::server::{run, App};

/// A publish instance that says which version of the content it has and remembers what it was asked.
#[derive(Default)]
struct Publish {
    /// What it calls itself in answers that say who answered.
    name: Mutex<String>,
    /// Answers 503 to everything while set.
    down: AtomicBool,
    version: AtomicU64,
    asked: Mutex<Vec<String>>,
    /// What was posted to /cdn/purge, with the X-Token it came with.
    purged: Mutex<Vec<(String, serde_json::Value)>>,
    /// Milliseconds it takes for every answer.
    delay_ms: AtomicU64,
    /// Broken: 500 for everything, its health check included.
    sick: AtomicBool,
}

impl Publish {
    fn asked(&self, path: &str) -> usize {
        self.asked.lock().unwrap().iter().filter(|p| p.as_str() == path).count()
    }
}

async fn publish_answer(State(publish): State<Arc<Publish>>, request: Request) -> Response {
    let path = request.uri().path().to_owned();
    let method = request.method().clone();
    let host = request.headers().get("host").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
    let forwarded = request.headers().get("x-forwarded-for").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
    publish.asked.lock().unwrap().push(path.clone());
    let delay = publish.delay_ms.load(Ordering::SeqCst);
    if delay > 0 {
        tokio::time::sleep(std::time::Duration::from_millis(delay)).await;
    }
    if publish.sick.load(Ordering::SeqCst) {
        return (StatusCode::INTERNAL_SERVER_ERROR, "broken").into_response();
    }
    if path == "/health" {
        return "healthy".into_response();
    }
    if path == "/content/one/boom.html" {
        // a page whose own code fails, on every instance
        return (StatusCode::INTERNAL_SERVER_ERROR, "a component failed").into_response();
    }
    if publish.down.load(Ordering::SeqCst) {
        return (StatusCode::SERVICE_UNAVAILABLE, "starting up").into_response();
    }
    let version = publish.version.load(Ordering::SeqCst);
    let page = format!("{path} v{version}");
    let name = publish.name.lock().unwrap().clone();
    let cookies = request.headers().get("cookie").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
    if method == Method::POST && path == "/cdn/purge" {
        // a CDN's purge API, told after an invalidation
        let token = request.headers().get("x-token").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
        let body = axum::body::to_bytes(request.into_body(), 1 << 20).await.unwrap();
        publish.purged.lock().unwrap().push((token, serde_json::from_slice(&body).unwrap()));
        return "purged".into_response();
    }
    if method == Method::POST && path.ends_with("/j_security_check") {
        // a sign-in: a session that only this instance knows
        return ([("set-cookie", format!("sling.formauth.publish=session-on-{name}; Path=/; HttpOnly"))], format!("signed in on {name}")).into_response();
    }
    // a closed user group: who has the member's cookie may read /content/one/club
    let member = cookies.contains("studio-login=member");
    if path.ends_with("/headers.json") {
        // which of the visitor's headers it was told, by name, and X-Forwarded-For as it came
        let mut names: Vec<String> = request.headers().keys().map(|n| n.as_str().to_owned()).collect();
        names.sort();
        let body = serde_json::json!({ "names": names, "forwarded": forwarded });
        return ([("content-type", "application/json"), ("cache-control", "no-store")], body.to_string()).into_response();
    }
    if path == "/content/dam/one/shop-redirects.txt" {
        // the rules of one host only: one of the site's sources again, and one of its own
        return ([("content-type", "text/plain")], "/old.html /content/one/en/shop.html 302\n/cart /content/one/en/shop/cart.html\n").into_response();
    }
    if path == "/content/dam/one/redirectmap.txt" {
        // the authors' rules; after a publication one more, and then the map is gone
        let mut map = String::from("# campaigns\n/old.html /content/one/en.html\n/promo /content/one/en/deals.html 302\n/away https://example.org/x\nbad line here too many fields\n/bad-status /x 200\n");
        match version {
            0 => {}
            1 => map.push_str("/later.html /content/one/en/later.html 308\n"),
            _ => return (StatusCode::NOT_FOUND, "gone").into_response(),
        }
        return ([("content-type", "text/plain")], map).into_response();
    }
    if path == "/libs/granite/dispatcher/content/vanityUrls.json" {
        // a page of site one, one of site two, one not under /content; another one once published
        let mut list = vec![
            r#"{"path":"/summer","target":"/content/one/en/summer","redirect":false,"status":302}"#,
            r#"{"path":"/brand","target":"/content/two/en/x","redirect":false,"status":302}"#,
            r#"{"path":"/admin","target":"/apps/x","redirect":false,"status":302}"#,
        ];
        if version >= 1 {
            list.push(r#"{"path":"/winter","target":"/content/one/en/winter","redirect":false,"status":302}"#);
        }
        return ([("content-type", "application/json")], format!("{{\"vanityUrls\":[{}]}}", list.join(","))).into_response();
    }
    if path == "/bin/studio/permissioncheck" {
        let uri = request.uri().query().unwrap_or("").to_owned();
        publish.asked.lock().unwrap().push(format!("check {uri}"));
        return if member { StatusCode::OK } else { StatusCode::FORBIDDEN }.into_response();
    }
    if path.starts_with("/content/one/club") && !member {
        return (StatusCode::FORBIDDEN, "sign in").into_response();
    }
    // pages that say what they show
    let shows = match path.as_str() {
        "/content/deps/hero.html" => Some("/content/dam/deps/hero.jpg,/content/dam/deps/a%2Cb.jpg"),
        "/content/deps/header.html" => Some("/content/experience-fragments/deps/header/master"),
        "/content/deps/plain.html" => Some(""),
        _ => None,
    };
    if let Some(shows) = shows {
        return ([("content-type", "text/html; charset=utf-8"), ("x-studio-depends", shows)], page).into_response();
    }
    // pages that say how long they may be kept
    if path.ends_with("/ttl.html") {
        return ([("content-type", "text/html; charset=utf-8"), ("cache-control", "public, max-age=2")], page).into_response();
    }
    if path.ends_with("/over.html") {
        return ([("content-type", "text/html; charset=utf-8"), ("cache-control", "max-age=0")], page).into_response();
    }
    match path.as_str() {
        "/content/site/account.html" => ([("cache-control", "private")], format!("{name} sees [{cookies}]")).into_response(),
        "/content/site/logo.svg" => {
            ([("content-type", "image/svg+xml"), ("content-disposition", "attachment; filename=logo.svg"), ("x-frame-options", "SAMEORIGIN")], page)
                .into_response()
        }
        "/content/site/font.ttf" => ([("content-type", "application/octet-stream")], page).into_response(),
        "/content/site/private.html" => ([("cache-control", "private, max-age=60")], page).into_response(),
        "/content/site/cookie.html" => ([("set-cookie", "visitor=1")], page).into_response(),
        "/content/site/session.html" => {
            ([("content-type", "text/html;charset=utf-8"), ("set-cookie", "JSESSIONID=node0abc; Path=/; HttpOnly")], page).into_response()
        }
        "/content/site/both.html" => {
            let mut response = page.into_response();
            response.headers_mut().append("set-cookie", "JSESSIONID=node0abc; Path=/".parse().unwrap());
            response.headers_mut().append("set-cookie", "visitor=1".parse().unwrap());
            response
        }
        "/content/site/missing.html" => (StatusCode::NOT_FOUND, "no such page").into_response(),
        "/content/site/big.html" => "x".repeat(2 * 1024 * 1024).into_response(),
        "/content/site/echo.html" => format!("host={host} forwarded={forwarded}").into_response(),
        "/content/site/slow.html" => {
            tokio::time::sleep(std::time::Duration::from_millis(300)).await;
            ([("content-type", "text/html; charset=utf-8")], page).into_response()
        }
        "/bin/studio/forms/submit" if method == Method::POST => {
            let body = axum::body::to_bytes(request.into_body(), usize::MAX).await.unwrap();
            (StatusCode::CREATED, [("x-form", "saved")], body).into_response()
        }
        _ => ([("content-type", "text/html; charset=utf-8"), ("x-from", "publish")], page).into_response(),
    }
}

async fn start_publish() -> (Arc<Publish>, SocketAddr) {
    let publish = Arc::new(Publish::default());
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let router = Router::new().fallback(publish_answer).with_state(publish.clone());
    tokio::spawn(async move { axum::serve(listener, router).await.unwrap() });
    (publish, addr)
}

const CACHE: &str = r#"
[cache]
ignore_cookies = ["JSESSIONID"]
ignore_query = ["utm_*", "gclid"]
max_file_mb = 1
rewarm = false
"#;

const RULES: &str = r#"
[[filter]]
type = "allow"
method = ["GET", "HEAD"]
path = "/content/*"
extensions = ["html", "json", "jpg", "svg", "ttf"]

[[filter]]
type = "allow"
method = ["POST"]
url = "/bin/studio/forms/submit"

[[filter]]
type = "allow"
method = ["POST"]
url = "*/j_security_check"
"#;

struct Live {
    url: String,
    docroot: PathBuf,
    client: reqwest::Client,
    _dir: tempfile::TempDir,
}

async fn start_live(publish: &[String], extra: &str) -> Live {
    start_live_with(publish, extra, "").await
}

/// `extra`: top-level settings and tables; `cache`: more lines for the `[cache]` table.
async fn start_live_with(publish: &[String], extra: &str, cache: &str) -> Live {
    let dir = tempfile::tempdir().unwrap();
    let docroot = dir.path().join("docroot");
    let list = publish.iter().map(|p| format!("\"{p}\"")).collect::<Vec<_>>().join(", ");
    let text = format!("docroot = {docroot:?}\npublish = [{list}]\nconnect_timeout_seconds = 2\n{extra}\n{CACHE}{cache}\n{RULES}");
    let app = App::new(Config::parse(&text).unwrap()).unwrap();
    // with sites the top directory, under which each site has its own
    let docroot = if app.config().site.is_empty() { app.cache().docroot().to_owned() } else { app.config().docroot.canonicalize().unwrap() };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(async move { run(app, listener).await.unwrap() });
    Live { url, docroot, client: reqwest::Client::builder().no_proxy().build().unwrap(), _dir: dir }
}

impl Live {
    async fn get(&self, path: &str) -> (StatusCode, HeaderMap, Bytes) {
        self.send(self.client.get(format!("{}{path}", self.url))).await
    }

    async fn send(&self, request: reqwest::RequestBuilder) -> (StatusCode, HeaderMap, Bytes) {
        let response = request.send().await.unwrap();
        (response.status(), response.headers().clone(), response.bytes().await.unwrap())
    }

    async fn flush(&self, action: &str, handle: &str) -> StatusCode {
        let request = self
            .client
            .post(format!("{}/dispatcher/invalidate.cache", self.url))
            .header("CQ-Action", action)
            .header("CQ-Handle", handle);
        self.send(request).await.0
    }
}

fn verdict(headers: &HeaderMap) -> &str {
    headers.get("x-studio-live").and_then(|v| v.to_str().ok()).unwrap_or("")
}

#[tokio::test]
async fn a_page_is_fetched_once_and_again_after_a_publication() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "").await;
    let page = "/content/site/en.html";

    let (status, headers, body) = live.get(page).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(verdict(&headers), "stored");
    assert_eq!(headers.get("x-from").unwrap(), "publish");
    assert_eq!(body, "/content/site/en.html v0");
    assert_eq!(std::fs::read_to_string(live.docroot.join("content/site/en.html")).unwrap(), "/content/site/en.html v0");

    // the content changes on the publish; the cache does not know yet
    publish.version.store(1, Ordering::SeqCst);
    let (_, headers, body) = live.get(page).await;
    assert_eq!(verdict(&headers), "hit");
    assert_eq!(headers.get("content-type").unwrap(), "text/html; charset=utf-8");
    assert_eq!(body, "/content/site/en.html v0");
    assert_eq!(publish.asked(page), 1);

    // campaign parameters do not make it another page
    let (_, headers, _) = live.get("/content/site/en.html?utm_source=mail&gclid=1").await;
    assert_eq!(verdict(&headers), "hit");

    // a HEAD is answered from the cache too
    let (status, headers, body) = live.send(live.client.head(format!("{}{page}", live.url))).await;
    assert_eq!((status, verdict(&headers), body.len()), (StatusCode::OK, "hit", 0));
    assert_eq!(headers.get("content-length").unwrap(), "24");

    assert_eq!(live.flush("Activate", "/content/site/en").await, StatusCode::OK);
    assert!(!live.docroot.join("content/site/en.html").exists());
    let (_, headers, body) = live.get(page).await;
    assert_eq!(verdict(&headers), "stored");
    assert_eq!(body, "/content/site/en.html v1");
    assert_eq!(publish.asked(page), 2);
}

#[tokio::test]
async fn what_the_filter_refuses_never_reaches_the_publish() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "").await;
    for (path, expected) in [
        ("/bin/studio/security.json", StatusCode::NOT_FOUND),
        ("/system/console/bundles", StatusCode::NOT_FOUND),
        ("/content/site.infinity.json", StatusCode::NOT_FOUND),
        ("/content/site/en", StatusCode::NOT_FOUND),
        ("/bin/receive", StatusCode::NOT_FOUND),
        ("/content/site/en.html;x=1", StatusCode::BAD_REQUEST),
        ("/_live/other", StatusCode::NOT_FOUND),
    ] {
        let (status, headers, _) = live.get(path).await;
        assert_eq!(status, expected, "{path}");
        assert_eq!(verdict(&headers), "denied", "{path}");
    }
    // a GET where only a POST is allowed
    assert_eq!(live.get("/bin/studio/forms/submit").await.0, StatusCode::NOT_FOUND);
    // paths a well-behaved client would have tidied up before sending, sent as they are
    for path in ["/content/%2e%2e/bin/receive.html", "/content/../bin/receive.html", "/content/site/%2e%2e/%2e%2e/a.html"] {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let mut socket = tokio::net::TcpStream::connect(live.url.trim_start_matches("http://")).await.unwrap();
        socket.write_all(format!("GET {path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").as_bytes()).await.unwrap();
        let mut answer = String::new();
        socket.read_to_string(&mut answer).await.unwrap();
        assert!(answer.starts_with("HTTP/1.1 400"), "{path}: {answer}");
    }
    assert!(publish.asked.lock().unwrap().is_empty());
}

#[tokio::test]
async fn personal_and_partial_answers_are_passed_on_but_not_kept() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "").await;
    let cached = |path: &str| live.docroot.join(&path[1..]).exists();

    // a query that matters
    for _ in 0..2 {
        let (status, headers, _) = live.get("/content/site/list.html?page=2").await;
        assert_eq!((status, verdict(&headers)), (StatusCode::OK, "pass"));
    }
    assert_eq!(publish.asked("/content/site/list.html"), 2);
    assert!(!cached("/content/site/list.html"));

    // what the publish marks as personal, a cookie, an error
    for path in ["/content/site/private.html", "/content/site/cookie.html"] {
        let (status, headers, _) = live.get(path).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::OK, "pass"), "{path}");
        assert!(!cached(path), "{path}");
    }
    // a session cookie the configuration calls meaningless: kept, and not handed on
    let (_, headers, _) = live.get("/content/site/session.html").await;
    assert_eq!(verdict(&headers), "stored");
    assert!(headers.get("set-cookie").is_none());
    assert!(cached("/content/site/session.html"));
    // together with a cookie that does mean something: the visitor gets both, the cache nothing
    let (_, headers, _) = live.get("/content/site/both.html").await;
    assert_eq!((verdict(&headers), headers.get_all("set-cookie").iter().count()), ("pass", 2));
    assert!(!cached("/content/site/both.html"));

    let (status, _, body) = live.get("/content/site/missing.html").await;
    assert_eq!((status, &body[..]), (StatusCode::NOT_FOUND, &b"no such page"[..]));
    assert!(!cached("/content/site/missing.html"));

    // a signed-in visitor gets the publish's answer even when the page is cached
    live.get("/content/site/en.html").await;
    assert!(cached("/content/site/en.html"));
    publish.version.store(5, Ordering::SeqCst);
    for (name, value) in [("authorization", "Basic YTpi"), ("cookie", "a=1; sling.formauth.publish=abc"), ("cookie", "studio-login=1.YW5h.1.2.abc; a=1")] {
        let (_, headers, body) = live.send(live.client.get(format!("{}/content/site/en.html", live.url)).header(name, value)).await;
        assert_eq!((verdict(&headers), &body[..]), ("pass", &b"/content/site/en.html v5"[..]), "{name}");
    }
    assert_eq!(std::fs::read_to_string(live.docroot.join("content/site/en.html")).unwrap(), "/content/site/en.html v0");

    // larger than the cache takes: all of it arrives, none of it stays
    let (status, headers, body) = live.get("/content/site/big.html").await;
    assert_eq!((status, verdict(&headers), body.len()), (StatusCode::OK, "pass", 2 * 1024 * 1024));
    assert!(!cached("/content/site/big.html"));
    assert_eq!(std::fs::read_dir(live.docroot.join("content/site")).unwrap().count(), 2, "no temporary files");
}

#[tokio::test]
async fn a_form_is_posted_through_with_the_visitors_host() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "").await;

    let post = live.client.post(format!("{}/bin/studio/forms/submit", live.url)).body("name=Ana&message=Salut");
    let (status, headers, body) = live.send(post).await;
    assert_eq!(status, StatusCode::CREATED);
    assert_eq!(headers.get("x-form").unwrap(), "saved");
    assert_eq!(body, "name=Ana&message=Salut");
    assert_eq!(publish.asked("/bin/studio/forms/submit"), 1);

    let get = live
        .client
        .get(format!("{}/content/site/echo.html", live.url))
        .header("host", "www.example.com")
        .header("x-forwarded-for", "203.0.113.7");
    let (_, _, body) = live.send(get).await;
    assert_eq!(body, "host=www.example.com forwarded=203.0.113.7, 127.0.0.1");
}

#[tokio::test]
async fn headers_of_its_own_come_back_with_a_cached_answer() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "").await;

    for (round, expected) in ["stored", "hit", "hit"].iter().enumerate() {
        let (status, headers, body) = live.get("/content/site/logo.svg").await;
        assert_eq!((status, verdict(&headers)), (StatusCode::OK, *expected), "round {round}");
        assert_eq!(headers.get("content-disposition").unwrap(), "attachment; filename=logo.svg", "round {round}");
        assert_eq!(headers.get("content-type").unwrap(), "image/svg+xml", "round {round}");
        assert_eq!(body, "/content/site/logo.svg v0");
    }
    // what is not kept is not invented: the hit has no X-Frame-Options of the publish
    assert!(live.get("/content/site/logo.svg").await.1.get("x-frame-options").is_none());
    assert_eq!(publish.asked("/content/site/logo.svg"), 1);
    // not a file Apache would serve without the header
    assert!(!live.docroot.join("content/site/logo.svg").exists());

    // a type the extension does not say comes back as the publish said it
    live.get("/content/site/font.ttf").await;
    let (_, headers, _) = live.get("/content/site/font.ttf").await;
    assert_eq!((verdict(&headers), headers.get("content-type").unwrap().to_str().unwrap()), ("hit", "application/octet-stream"));
    assert!(!live.docroot.join("content/site/font.ttf").exists());

    // a publication takes them out like any other file
    assert_eq!(live.flush("Activate", "/content/site/logo.svg").await, StatusCode::OK);
    publish.version.store(1, Ordering::SeqCst);
    let (_, headers, body) = live.get("/content/site/logo.svg").await;
    assert_eq!((verdict(&headers), &body[..]), ("stored", &b"/content/site/logo.svg v1"[..]));
}

#[tokio::test]
async fn while_the_publish_is_away_a_page_is_served_as_it_was() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "").await;
    for path in ["/content/site/en.html", "/content/site/other.html", "/content/site/logo.svg", "/content/site/gone.html"] {
        assert_eq!(verdict(&live.get(path).await.1), "stored", "{path}");
    }

    // a publication, and then the publish stops answering before anyone asks for the pages again
    assert_eq!(live.flush("Activate", "/content/site/en").await, StatusCode::OK);
    assert_eq!(live.flush("Activate", "/content/site/logo.svg").await, StatusCode::OK);
    assert_eq!(live.flush("Deactivate", "/content/site/gone").await, StatusCode::OK);
    assert!(!live.docroot.join("content/site/en.html").exists());
    publish.version.store(1, Ordering::SeqCst);
    publish.down.store(true, Ordering::SeqCst);

    for path in ["/content/site/en.html", "/content/site/other.html"] {
        let (status, headers, body) = live.get(path).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::OK, "stale"), "{path}");
        assert_eq!(body, format!("{path} v0"));
        assert!(!live.docroot.join(&path[1..]).exists(), "{path}: a stale page is not put back as if it were current");
    }
    let (_, headers, _) = live.get("/content/site/logo.svg").await;
    assert_eq!((verdict(&headers), headers.get("content-disposition").unwrap().to_str().unwrap()), ("stale", "attachment; filename=logo.svg"));
    // what was unpublished does not come back, and what was never cached is the publish's error
    assert_eq!(live.get("/content/site/gone.html").await.0, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(live.get("/content/site/never.html").await.0, StatusCode::SERVICE_UNAVAILABLE);
    // a signed-in visitor gets no cached page, stale or not
    let signed_in = live.client.get(format!("{}/content/site/en.html", live.url)).header("cookie", "sling.formauth.publish=abc");
    assert_eq!(live.send(signed_in).await.0, StatusCode::SERVICE_UNAVAILABLE);

    // the publish is back: the new page, and from then on the cache
    publish.down.store(false, Ordering::SeqCst);
    let (_, headers, body) = live.get("/content/site/en.html").await;
    assert_eq!((verdict(&headers), &body[..]), ("stored", &b"/content/site/en.html v1"[..]));
    publish.down.store(true, Ordering::SeqCst);
    let (_, headers, body) = live.get("/content/site/en.html").await;
    assert_eq!((verdict(&headers), &body[..]), ("hit", &b"/content/site/en.html v1"[..]));

    let (_, _, body) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(status["stale"], 3);

    // with stale copies turned off the old page is simply gone
    let (publish, addr) = start_publish().await;
    let strict = start_live_with(&[format!("http://{addr}")], "", "stale_seconds = 0").await;
    strict.get("/content/site/en.html").await;
    strict.flush("Activate", "/content/site/en").await;
    publish.down.store(true, Ordering::SeqCst);
    assert_eq!(strict.get("/content/site/en.html").await.0, StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn a_published_page_is_fetched_again_before_anyone_asks() {
    let (publish, addr) = start_publish().await;
    let text = format!(
        "docroot = {:?}\npublish = [\"http://{addr}\"]\n[cache]\nrewarm = true\n{RULES}",
        tempfile::tempdir().unwrap().keep().join("docroot")
    );
    let app = App::new(Config::parse(&text).unwrap()).unwrap();
    let docroot = app.cache().docroot().to_owned();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let live = Live { url: format!("http://{}", listener.local_addr().unwrap()), docroot, client: reqwest::Client::builder().no_proxy().build().unwrap(), _dir: tempfile::tempdir().unwrap() };
    tokio::spawn(async move { run(app, listener).await.unwrap() });

    // cached under a visitor's host name; another site's visitors do not change that
    live.get("/content/site/other.html").await;
    let first = live.client.get(format!("{}/content/site/echo.html", live.url)).header("host", "www.example.com");
    assert_eq!(verdict(&live.send(first).await.1), "stored");
    live.get("/content/elsewhere/page.html").await;
    assert_eq!(publish.asked("/content/site/echo.html"), 1);

    assert_eq!(live.flush("Activate", "/content/site/echo").await, StatusCode::OK);
    // nobody asks; the page comes back by itself, asked for under the same host name
    for _ in 0..100 {
        if publish.asked("/content/site/echo.html") == 2 {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(publish.asked("/content/site/echo.html"), 2);
    let mut answer = live.get("/content/site/echo.html").await;
    for _ in 0..50 {
        if verdict(&answer.1) == "hit" {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
        answer = live.get("/content/site/echo.html").await;
    }
    assert_eq!(verdict(&answer.1), "hit");
    assert!(answer.2.starts_with(b"host=www.example.com "), "fetched again under the visitor's host name");
    // the other pages wait for a visitor
    assert!(!live.docroot.join("content/site/other.html").exists());
    assert_eq!(publish.asked("/content/site/other.html"), 1);
    let (_, _, body) = live.get("/_live/status").await;
    assert_eq!(serde_json::from_slice::<serde_json::Value>(&body).unwrap()["rewarmed"], 1);

    // two publish instances tell the cache one after the other: the second flush comes while the page
    // is being fetched again; that answer is not kept, and the page is fetched once more
    live.get("/content/site/slow.html").await;
    assert!(live.docroot.join("content/site/slow.html").exists());
    assert_eq!(live.flush("Activate", "/content/site/slow").await, StatusCode::OK);
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    publish.version.store(7, Ordering::SeqCst);
    assert_eq!(live.flush("Activate", "/content/site/slow").await, StatusCode::OK);
    for _ in 0..100 {
        if live.docroot.join("content/site/slow.html").exists() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(std::fs::read_to_string(live.docroot.join("content/site/slow.html")).unwrap(), "/content/site/slow.html v7");
    assert_eq!(publish.asked("/content/site/slow.html"), 3, "the visitor, the fetch the second flush outdated, the one kept");
    let (_, _, body) = live.get("/_live/status").await;
    assert_eq!(serde_json::from_slice::<serde_json::Value>(&body).unwrap()["rewarmed"], 2);

    // an unpublished page is not fetched again
    assert_eq!(live.flush("Deactivate", "/content/site/echo").await, StatusCode::OK);
    tokio::time::sleep(std::time::Duration::from_millis(150)).await;
    assert_eq!(publish.asked("/content/site/echo.html"), 2);
}

#[tokio::test]
async fn why_a_request_ends_as_it_does() {
    let (_publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "content_prefix = \"/content\"").await;
    let explain = |url: &'static str| {
        let live = &live;
        async move {
            let (status, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", url)])).await;
            assert_eq!(status, StatusCode::OK, "{url}");
            serde_json::from_slice::<serde_json::Value>(&body).unwrap()
        }
    };

    let e = explain("/content/site/en.html").await;
    assert_eq!((e["allowed"].as_bool(), e["cacheable"].as_bool(), e["inCache"].as_str()), (Some(true), Some(true), None));
    assert_eq!(e["decidedBy"], "filter rule 1 of 3");
    assert_eq!(e["rule"]["path"], "/content/*");
    assert_eq!(e["file"], "content/site/en.html");
    assert!(e["summary"].as_str().unwrap().contains("Not in the cache"));

    live.get("/content/site/en.html").await;
    live.get("/content/site/logo.svg").await;
    assert_eq!(explain("/content/site/en.html").await["inCache"], "plain");
    assert_eq!(explain("/content/site/logo.svg").await["inCache"], "held");
    // a short URL is explained as the page it is
    let e = explain("/site/en.html?utm_source=mail").await;
    assert_eq!((e["path"].as_str(), e["shortUrl"].as_bool(), e["inCache"].as_str()), (Some("/content/site/en.html"), Some(true), Some("plain")));
    live.flush("Activate", "/content/site/en").await;
    assert_eq!(explain("/content/site/en.html").await["inCache"], "stale");

    let e = explain("/content/site/en.html?page=2").await;
    assert_eq!((e["allowed"].as_bool(), e["cacheable"].as_bool()), (Some(true), Some(false)));
    assert!(e["notCacheableBecause"].as_str().unwrap().contains("ignore_query"));

    let e = explain("/content/site.infinity.json").await;
    assert_eq!((e["allowed"].as_bool(), e["cacheable"].as_bool()), (Some(false), Some(false)));
    assert!(e["decidedBy"].as_str().unwrap().contains("built-in"));
    assert_eq!(e["sling"]["selectors"][0], "infinity");
    assert!(explain("/apps/site/page.jsp").await["decidedBy"].as_str().unwrap().contains("no rule"));
    assert_eq!(explain("/content/../bin/receive").await["answer"], 400);

    // a POST is another question
    let (_, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", "/bin/studio/forms/submit"), ("method", "post")])).await;
    let e = serde_json::from_slice::<serde_json::Value>(&body).unwrap();
    assert_eq!((e["allowed"].as_bool(), e["cacheable"].as_bool(), e["decidedBy"].as_str()), (Some(true), Some(false), Some("filter rule 2 of 3")));

    // not for visitors
    let proxied = live.client.get(format!("{}/_live/explain?url=/x", live.url)).header("X-Forwarded-For", "203.0.113.7");
    assert_eq!(live.send(proxied).await.0, StatusCode::FORBIDDEN);
}

#[tokio::test]
async fn a_signed_in_visitor_stays_with_one_publish_instance() {
    let (a, addr_a) = start_publish().await;
    let (b, addr_b) = start_publish().await;
    *a.name.lock().unwrap() = "A".into();
    *b.name.lock().unwrap() = "B".into();
    let live = start_live(&[format!("http://{addr_a}"), format!("http://{addr_b}")], "").await;
    let route = |headers: &HeaderMap| {
        headers.get_all("set-cookie").iter().filter_map(|v| v.to_str().ok()).find(|c| c.starts_with("studio-route=")).map(|c| c.split(';').next().unwrap().to_owned())
    };
    let text = |body: Bytes| String::from_utf8(body.to_vec()).unwrap();

    // a visitor nobody knows is sent wherever it is the turn, and gets no cookie of ours
    let mut seen = std::collections::BTreeSet::new();
    for _ in 0..6 {
        let (_, headers, body) = live.get("/content/site/account.html").await;
        assert!(route(&headers).is_none());
        seen.insert(text(body).chars().next().unwrap());
    }
    assert_eq!(seen.len(), 2, "both instances take their turn");

    // signing in: the instance that gave the session is the one to go back to
    let (_, headers, body) = live.send(live.client.post(format!("{}/content/site/en.html/j_security_check", live.url)).body("j_username=x")).await;
    let home = text(body).replace("signed in on ", "");
    let pin = route(&headers).expect("a route cookie with the sign-in");
    assert!(pin.len() > "studio-route=".len() && !pin.contains("127.0.0.1"), "{pin}");
    let session = format!("sling.formauth.publish=session-on-{home}");
    for _ in 0..8 {
        let request = live.client.get(format!("{}/content/site/account.html", live.url)).header("cookie", format!("{session}; {pin}"));
        let (_, headers, body) = live.send(request).await;
        // the same instance every time, which sees the session but not our cookie; nothing to set again
        assert_eq!(text(body), format!("{home} sees [{session}]"));
        assert!(route(&headers).is_none());
    }

    // signed in but without our cookie (it was lost): wherever the request lands is where the visitor stays from now on
    let (_, headers, body) = live.send(live.client.get(format!("{}/content/site/account.html", live.url)).header("cookie", &session)).await;
    let landed = text(body).chars().next().unwrap().to_string();
    assert_eq!(route(&headers).map(|p| p == pin), Some(landed == home));
    // a cookie that names no instance is no route
    let (_, headers, _) = live.send(live.client.get(format!("{}/content/site/account.html", live.url)).header("cookie", format!("{session}; studio-route=nonsense"))).await;
    assert!(route(&headers).is_some());

    // the visitor's instance stops answering: the other one takes over, and the cookie follows
    let (home_publish, other) = if home == "A" { (&a, "B") } else { (&b, "A") };
    home_publish.down.store(true, Ordering::SeqCst);
    let (status, headers, body) = live.send(live.client.get(format!("{}/content/site/account.html", live.url)).header("cookie", format!("{session}; {pin}"))).await;
    assert_eq!(status, StatusCode::OK);
    assert!(text(body).starts_with(other));
    let moved = route(&headers).expect("a new route");
    assert_ne!(moved, pin);

    // pages that are the same for everyone do not care: cached once, served to all
    home_publish.down.store(false, Ordering::SeqCst);
    live.get("/content/site/en.html").await;
    let (_, headers, _) = live.send(live.client.get(format!("{}/content/site/en.html", live.url)).header("cookie", &pin)).await;
    assert_eq!((verdict(&headers), route(&headers)), ("hit", None));

    // with one instance, or sticky turned off, there is nothing to stick to
    let single = start_live(&[format!("http://{addr_a}")], "").await;
    let (_, headers, _) = single.send(single.client.post(format!("{}/content/site/en.html/j_security_check", single.url))).await;
    assert!(route(&headers).is_none());
    let loose = start_live(&[format!("http://{addr_a}"), format!("http://{addr_b}")], "sticky = false").await;
    let (_, headers, _) = loose.send(loose.client.post(format!("{}/content/site/en.html/j_security_check", loose.url))).await;
    assert!(route(&headers).is_none());
}

#[tokio::test]
async fn several_sites_behind_one_cache() {
    let (general, addr) = start_publish().await;
    let (own, own_addr) = start_publish().await;
    let sites = format!(
        r#"
[[site]]
name = "one"
hosts = ["one.test", "www.one.test"]
roots = ["/content/one"]
shared = ["/content/experience-fragments/one"]

[[site]]
name = "two"
hosts = ["*.two.test"]
roots = ["/content/two"]
publish = ["http://{own_addr}"]
"#
    );
    let live = start_live(&[format!("http://{addr}")], &sites).await;
    let get = |host: &'static str, path: &'static str| {
        let live = &live;
        async move { live.send(live.client.get(format!("{}{path}", live.url)).header("host", host)).await }
    };
    // every site keeps its pages in a directory of its own, the document root of its virtual host
    let cached_in = |site: &str, path: &str| live.docroot.join(site).join(&path[1..]).exists();
    let cached = |path: &str| ["one", "two"].iter().any(|site| cached_in(site, path));

    // each site under its own names, from its own publish instances
    assert_eq!(verdict(&get("one.test", "/content/one/en.html").await.1), "stored");
    assert_eq!(verdict(&get("WWW.One.Test:8080", "/content/one/en.html").await.1), "hit");
    assert_eq!(verdict(&get("shop.two.test", "/content/two/en.html").await.1), "stored");
    assert_eq!((general.asked("/content/one/en.html"), own.asked("/content/one/en.html")), (1, 0));
    assert_eq!((general.asked("/content/two/en.html"), own.asked("/content/two/en.html")), (0, 1));
    assert!(cached_in("one", "/content/one/en.html") && !cached_in("two", "/content/one/en.html"));
    assert!(cached_in("two", "/content/two/en.html") && !cached_in("one", "/content/two/en.html"));
    assert!(!live.docroot.join("content").exists(), "nothing in the shared directory, which no virtual host serves");

    // not under another site's name, cached or not
    for (host, path) in [("one.test", "/content/two/en.html"), ("one.test", "/content/two/other.html"), ("shop.two.test", "/content/one/en.html")] {
        let (status, headers, _) = get(host, path).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::NOT_FOUND, "denied"), "{path} under {host}");
    }
    assert_eq!(own.asked("/content/two/other.html") + general.asked("/content/two/other.html"), 0);
    // what a site does not declare it does not show: another's shared content, nobody's content
    for (host, path) in [("shop.two.test", "/content/experience-fragments/one/header.html"), ("one.test", "/content/nobody/en.html")] {
        let (status, headers, _) = get(host, path).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::NOT_FOUND, "denied"), "{path} under {host}");
    }
    assert_eq!(get("one.test", "/content/experience-fragments/one/header.html").await.0, StatusCode::OK);
    assert!(cached_in("one", "/content/experience-fragments/one/header.html"));
    // and a host name that is no site's shows nothing at all, cached or not
    for path in ["/content/two/en.html", "/content/one/en.html", "/content/nobody/en.html"] {
        let (status, headers, _) = live.get(path).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::NOT_FOUND, "denied"), "{path} under no site's name");
    }
    assert_eq!(general.asked("/content/nobody/en.html") + own.asked("/content/nobody/en.html"), 0);
    // a page of site two comes from site two's publish
    get("shop.two.test", "/content/two/more.html").await;
    assert_eq!((general.asked("/content/two/more.html"), own.asked("/content/two/more.html")), (0, 1));

    // a publication in site one takes out site one's pages only
    get("one.test", "/content/one/en/about.html").await;
    assert_eq!(live.flush("Activate", "/content/one/en/new-page").await, StatusCode::OK);
    assert!(!cached("/content/one/en.html") && !cached("/content/one/en/about.html"));
    // its cache is emptied of pages, its copy of the shared header too; site two keeps its own
    assert!(!cached_in("one", "/content/experience-fragments/one/header.html"));
    assert!(cached_in("two", "/content/two/en.html"));
    let (_, _, body) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(status["lastInvalidation"]["sites"], serde_json::json!(["one"]));
    assert_eq!(status["sites"][1]["name"], "two");
    assert!(status["sites"][1]["docroot"].as_str().unwrap().ends_with("two"), "{status}");
    assert_eq!(status["sites"][1]["files"]["plain"], 2, "{status}");
    assert_eq!(status["sites"][0]["files"]["plain"], 0, "{status}");
    assert_eq!(status["publish"].as_array().unwrap().len(), 2);

    // so does a publication of something site one shows from elsewhere
    get("one.test", "/content/one/en.html").await;
    get("one.test", "/content/experience-fragments/one/header.html").await;
    assert_eq!(live.flush("Activate", "/content/experience-fragments/one/header").await, StatusCode::OK);
    assert!(!cached("/content/one/en.html") && !cached("/content/experience-fragments/one/header.html"));
    assert!(cached("/content/two/en.html"));

    // what is nobody's in particular takes out everybody's pages
    get("one.test", "/content/one/en.html").await;
    assert_eq!(live.flush("Activate", "/conf/global/settings").await, StatusCode::OK);
    assert!(!cached("/content/one/en.html") && !cached("/content/two/en.html"));

    // and explain says whose a request is
    let explain = |host: &'static str, url: &'static str| {
        let live = &live;
        async move {
            let (_, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", url), ("host", host)])).await;
            serde_json::from_slice::<serde_json::Value>(&body).unwrap()
        }
    };
    let e = explain("one.test", "/content/one/en.html").await;
    assert_eq!((e["site"].as_str(), e["allowed"].as_bool()), (Some("one"), Some(true)));
    let e = explain("one.test", "/content/two/en.html").await;
    assert_eq!(e["allowed"], false);
    assert!(e["summary"].as_str().unwrap().contains("a page of the site two"), "{e}");
    let e = explain("one.test", "/content/nobody/en.html").await;
    assert!(e["summary"].as_str().unwrap().contains("nor in what it lists as shared"), "{e}");
    let e = explain("elsewhere.test", "/content/one/en.html").await;
    assert!(e["summary"].as_str().unwrap().contains("no declared site's host name"), "{e}");
}

#[tokio::test]
async fn a_short_url_is_the_same_page() {
    let (publish, addr) = start_publish().await;
    let live = start_live(&[format!("http://{addr}")], "content_prefix = \"/content\"").await;

    let (status, headers, body) = live.get("/site/en.html").await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "stored"));
    assert_eq!(body, "/content/site/en.html v0");
    assert!(live.docroot.join("content/site/en.html").is_file());
    assert!(!live.docroot.join("site").exists());
    let (_, headers, _) = live.get("/content/site/en.html").await;
    assert_eq!(verdict(&headers), "hit");
    assert_eq!(publish.asked("/content/site/en.html"), 1);

    // a publication of the page removes it, whichever way it was asked for
    assert_eq!(live.flush("Activate", "/content/site/en").await, StatusCode::OK);
    let (_, headers, _) = live.get("/site/en.html").await;
    assert_eq!(verdict(&headers), "stored");

    // the prefix opens nothing: what is closed stays closed
    for path in ["/bin/receive", "/system/console/bundles", "/site/en.infinity.json", "/site/en"] {
        assert_eq!(live.get(path).await.0, StatusCode::NOT_FOUND, "{path}");
    }
}

#[tokio::test]
async fn a_publish_that_is_down_is_skipped() {
    let (publish, addr) = start_publish().await;
    // a port nobody listens on
    let dead = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap().local_addr().unwrap();
    let live = start_live(&[format!("http://{dead}"), format!("http://{addr}")], "").await;

    for n in 0..4 {
        let (status, _, _) = live.get(&format!("/content/site/p{n}.html")).await;
        assert_eq!(status, StatusCode::OK, "request {n}");
    }
    assert_eq!(publish.asked.lock().unwrap().len(), 4);
    let (_, _, body) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(status["publish"][0]["skipped"], true);
    assert_eq!(status["publish"][1]["skipped"], false);
    assert_eq!(status["stored"], 4);

    // none at all: an error, not a hang
    let alone = start_live(&[format!("http://{dead}")], "").await;
    let (status, headers, _) = alone.get("/content/site/en.html").await;
    assert_eq!((status, verdict(&headers)), (StatusCode::BAD_GATEWAY, "error"));
}

#[tokio::test]
async fn only_who_may_invalidates() {
    let (_publish, addr) = start_publish().await;

    // without a secret: this machine, directly
    let open = start_live(&[format!("http://{addr}")], "").await;
    assert_eq!(open.flush("Test", "/content/site").await, StatusCode::OK);
    assert_eq!(open.flush("Activate", "/content/site/en").await, StatusCode::OK);
    assert_eq!(open.flush("Explode", "/content/site/en").await, StatusCode::BAD_REQUEST);
    assert_eq!(open.flush("Activate", "/content/../etc").await, StatusCode::BAD_REQUEST);
    let proxied = open
        .client
        .post(format!("{}/dispatcher/invalidate.cache", open.url))
        .header("CQ-Action", "Activate")
        .header("CQ-Handle", "/content/site/en")
        .header("X-Forwarded-For", "203.0.113.7");
    assert_eq!(open.send(proxied).await.0, StatusCode::FORBIDDEN);

    // emptying it, and what the status says before and after
    open.get("/content/site/en.html").await;
    open.get("/content/site/logo.svg").await;
    let files = |body: Bytes| serde_json::from_slice::<serde_json::Value>(&body).unwrap()["files"].clone();
    assert_eq!(files(open.get("/_live/status").await.2), serde_json::json!({"plain": 1, "held": 1, "stale": 0}));
    let (status, _, body) = open.send(open.client.post(format!("{}/_live/clear", open.url))).await;
    assert_eq!((status, &body[..]), (StatusCode::OK, &b"OK, 2 removed\n"[..]));
    assert_eq!(files(open.get("/_live/status").await.2), serde_json::json!({"plain": 0, "held": 0, "stale": 0}));
    assert_eq!(open.get("/_live/clear").await.0, StatusCode::METHOD_NOT_ALLOWED);
    let proxied = open.client.post(format!("{}/_live/clear", open.url)).header("X-Forwarded-For", "203.0.113.7");
    assert_eq!(open.send(proxied).await.0, StatusCode::FORBIDDEN);

    // with a secret: whoever has it, as a flush agent sends it
    let closed = start_live(&[format!("http://{addr}")], "[flush]\nsecret = \"s3cret\"").await;
    closed.get("/content/site/en.html").await;
    assert_eq!(closed.flush("Activate", "/content/site/en").await, StatusCode::FORBIDDEN);
    assert_eq!(closed.get("/_live/status").await.0, StatusCode::FORBIDDEN);
    assert!(closed.docroot.join("content/site/en.html").exists());
    // the same goes for emptying the cache
    let clear = closed.client.post(format!("{}/_live/clear", closed.url));
    assert_eq!(closed.send(clear).await.0, StatusCode::FORBIDDEN);
    assert!(closed.docroot.join("content/site/en.html").exists());
    let flush = |password: &'static str| {
        closed
            .client
            .post(format!("{}/dispatcher/invalidate.cache", closed.url))
            .header("CQ-Action", "Activate")
            .header("CQ-Handle", "/content/site/en")
            .basic_auth("flush", Some(password))
    };
    assert_eq!(closed.send(flush("wrong")).await.0, StatusCode::FORBIDDEN);
    assert_eq!(closed.send(flush("s3cret")).await.0, StatusCode::OK);
    assert!(!closed.docroot.join("content/site/en.html").exists());
}

#[tokio::test]
async fn a_protected_page_is_kept_and_served_only_to_whom_the_publish_lets_in() {
    let (publish, addr) = start_publish().await;
    let sites = r#"
[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]
protected = ["/content/one/club*"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let get = |cookie: Option<&'static str>, path: &'static str| {
        let live = &live;
        async move {
            let mut request = live.client.get(format!("{}{path}", live.url)).header("host", "one.test");
            if let Some(cookie) = cookie {
                request = request.header("cookie", cookie);
            }
            live.send(request).await
        }
    };
    let page = "/content/one/club/news.html";
    let checks = || publish.asked.lock().unwrap().iter().filter(|p| p.starts_with("check ")).count();

    // a visitor who is not a member gets what the publish says (here a 403; a real one may send to the sign-in), nothing is kept
    let (status, headers, _) = get(None, page).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::FORBIDDEN, "pass"));
    // a member's 200 is kept, apart from the site's pages, where the web server never finds it
    let (status, headers, body) = get(Some("studio-login=member"), page).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "stored-protected"));
    assert_eq!(headers.get("cache-control").unwrap(), "private");
    assert!(body.starts_with(b"/content/one/club/news.html v0"));
    assert!(!live.docroot.join("one").join("content/one/club/news.html").exists());
    assert!(live.docroot.join("one.private").join("content/one/club/news.html").exists());
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = std::fs::metadata(live.docroot.join("one.private")).unwrap().permissions().mode();
        assert_eq!(mode & 0o777, 0o700, "the protected pages are the service's alone");
    }
    assert_eq!(publish.asked(page), 2);

    // the next member is served from the cache once the publish said yes
    let (status, headers, body) = get(Some("other=1; studio-login=member"), page).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "hit-checked"));
    assert_eq!(headers.get("cache-control").unwrap(), "private");
    assert!(body.starts_with(b"/content/one/club/news.html v0"));
    assert_eq!((publish.asked(page), checks()), (2, 1));
    assert!(publish.asked.lock().unwrap().contains(&"check uri=/content/one/club/news.html".to_owned()));
    // and anyone else is not: the check says no, the publish answers as it would without the cache
    for cookie in [None, Some("studio-login=stranger")] {
        let (status, headers, _) = get(cookie, page).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::FORBIDDEN, "pass"), "{cookie:?}");
    }
    assert_eq!((publish.asked(page), checks()), (4, 3));

    // a publication takes it out, and it is not fetched again by the cache, which may not read it
    assert_eq!(live.flush("Activate", "/content/one/club/news").await, StatusCode::OK);
    assert!(!live.docroot.join("one.private").join("content/one/club/news.html").exists());
    publish.version.store(1, Ordering::SeqCst);
    let (_, headers, body) = get(Some("studio-login=member"), page).await;
    assert_eq!(verdict(&headers), "stored-protected");
    assert!(body.starts_with(b"/content/one/club/news.html v1"));

    // the site's other pages are as before: for everyone, signed-in visitors passed through
    assert_eq!(verdict(&get(None, "/content/one/en.html").await.1), "stored");
    assert_eq!(verdict(&get(Some("studio-login=member"), "/content/one/en.html").await.1), "pass");

    // the status counts them, explain says what happens
    let (_, _, body) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(status["sites"][0]["protected"]["files"], 1, "{status}");
    assert_eq!((status["checked"].as_u64(), status["refused"].as_u64()), (Some(1), Some(2)), "{status}");
    let (_, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", page), ("host", "one.test")])).await;
    let e: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(e["protected"], true, "{e}");
    assert!(e["summary"].as_str().unwrap().contains("A protected page"), "{e}");
    // clearing empties it too
    let cleared = live.send(live.client.post(format!("{}/_live/clear", live.url))).await.0;
    assert_eq!(cleared, StatusCode::OK);
    assert!(!live.docroot.join("one.private").join("content/one/club/news.html").exists());
}

#[tokio::test]
async fn in_the_grace_period_a_page_is_served_as_it_was_while_it_is_fetched_again() {
    let (publish, addr) = start_publish().await;
    let live = start_live_with(&[format!("http://{addr}")], "", "grace_seconds = 2\n").await;
    let page = "/content/site/en.html";
    assert_eq!(verdict(&live.get(page).await.1), "stored");
    // a publication elsewhere takes it out with every other page
    publish.version.store(1, Ordering::SeqCst);
    assert_eq!(live.flush("Activate", "/content/site/other").await, StatusCode::OK);
    let (status, headers, body) = live.get(page).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "grace"));
    assert_eq!(&body[..], b"/content/site/en.html v0");
    // the visitors meanwhile get it at once too, and it is fetched once
    let (_, headers, _) = live.get(page).await;
    assert!(matches!(verdict(&headers), "grace" | "hit"), "{headers:?}");
    for _ in 0..50 {
        if publish.asked(page) == 2 && live.docroot.join(&page[1..]).exists() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    let (_, headers, body) = live.get(page).await;
    assert_eq!((verdict(&headers), &body[..]), ("hit", &b"/content/site/en.html v1"[..]));
    assert_eq!(publish.asked(page), 2, "fetched again once");

    // an unpublished page is never served so
    assert_eq!(live.flush("Deactivate", "/content/site/en").await, StatusCode::OK);
    let (_, headers, _) = live.get(page).await;
    assert_eq!(verdict(&headers), "stored", "asked from the publish, not served as it was");

    // after the grace period, the visitor waits for the publish as before
    live.get("/content/site/third.html").await;
    assert_eq!(live.flush("Activate", "/content/site/other").await, StatusCode::OK);
    tokio::time::sleep(std::time::Duration::from_millis(2100)).await;
    assert_eq!(verdict(&live.get("/content/site/third.html").await.1), "stored");
    let (_, _, body) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert!(status["graced"].as_u64().unwrap() >= 1, "{status}");
    assert_eq!(status["graceSeconds"], 2);
}

#[tokio::test]
async fn a_persisted_query_is_kept_once_per_set_of_variables() {
    let (publish, addr) = start_publish().await;
    let rules = r#"
[[filter]]
type = "allow"
method = ["GET", "HEAD"]
url = "/graphql/execute.json/*"
"#;
    let live = start_live_with(&[format!("http://{addr}")], rules, "invalidate = [\"*.html\", \"/graphql/execute.json/*\"]\n").await;
    let bali = "/graphql/execute.json/wknd-shared/adventure-by-slug;slug=bali";
    let (status, headers, body) = live.get(bali).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "stored"));
    assert!(body.starts_with(b"/graphql/execute.json/wknd-shared/adventure-by-slug;slug=bali v0"), "{body:?}");
    // the same query with its variables escaped is the same file; other variables another one
    assert_eq!(verdict(&live.get("/graphql/execute.json/wknd-shared/adventure-by-slug%3Bslug%3Dbali").await.1), "hit");
    assert_eq!(verdict(&live.get("/graphql/execute.json/wknd-shared/adventure-by-slug;slug=riverside").await.1), "stored");
    assert_eq!(verdict(&live.get("/graphql/execute.json/wknd-shared/adventures-all").await.1), "stored");
    let kept = live.docroot.join("graphql/execute.json/wknd-shared/adventure-by-slug");
    assert!(kept.join(";slug=bali.json").exists() || live.docroot.with_extension("held").join("graphql/execute.json/wknd-shared/adventure-by-slug/;slug=bali.json").exists());
    assert_eq!(publish.asked.lock().unwrap().iter().filter(|p| p.contains("adventure-by-slug")).count(), 2);
    // a ; anywhere else is still refused, before the publish hears of it
    for bad in ["/content/site/en.html;x", "/graphql/execute.json;x/a/b", "/graphql/execute.json/a;x/b"] {
        assert_eq!(live.get(bad).await.0, StatusCode::BAD_REQUEST, "{bad}");
    }
    // a publication (a content fragment changed) takes the answers out
    publish.version.store(1, Ordering::SeqCst);
    assert_eq!(live.flush("Activate", "/content/dam/wknd-shared/en/adventures/bali").await, StatusCode::OK);
    let (_, headers, body) = live.get(bali).await;
    assert_eq!(verdict(&headers), "stored");
    assert!(body.ends_with(b"v1"), "{body:?}");
}

#[tokio::test]
async fn a_vanity_url_of_the_publish_is_let_through_for_its_own_site() {
    let (publish, addr) = start_publish().await;
    let sites = r#"
[vanity]
url = "/libs/granite/dispatcher/content/vanityUrls.json"

[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let get = |host: &'static str, path: &'static str| {
        let live = &live;
        async move { live.send(live.client.get(format!("{}{path}", live.url)).header("host", host)).await }
    };
    // read at the start
    for _ in 0..50 {
        if publish.asked("/libs/granite/dispatcher/content/vanityUrls.json") > 0 {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    let (status, headers, body) = get("one.test", "/summer").await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "pass"), "{body:?}");
    assert_eq!(&body[..], b"/summer v0", "the publish resolves it itself");
    // not under another site's name, not what leads outside /content, not what is no vanity URL
    for (host, path) in [("two.test", "/summer"), ("one.test", "/brand"), ("one.test", "/admin"), ("one.test", "/winter"), ("one.test", "/summer/x")] {
        let (status, headers, _) = get(host, path).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::NOT_FOUND, "denied"), "{path} under {host}");
    }
    assert_eq!(get("two.test", "/brand").await.0, StatusCode::OK);
    let post = live.send(live.client.post(format!("{}/summer", live.url)).header("host", "one.test")).await;
    assert_eq!(post.0, StatusCode::NOT_FOUND, "only GET and HEAD");
    // a publication may bring a new one: read again after it
    publish.version.store(1, Ordering::SeqCst);
    assert_eq!(live.flush("Activate", "/content/one/en/winter").await, StatusCode::OK);
    let mut status = StatusCode::NOT_FOUND;
    for _ in 0..50 {
        status = get("one.test", "/winter").await.0;
        if status == StatusCode::OK {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(status, StatusCode::OK);
    let (_, _, body) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(status["vanityUrls"]["count"], 3, "{status}");
    // and the URL tester says what it is
    for (host, allowed) in [("one.test", true), ("two.test", false)] {
        let (_, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", "/summer"), ("host", host)])).await;
        let e: serde_json::Value = serde_json::from_slice(&body).unwrap();
        assert_eq!((e["vanity"].as_str(), e["allowed"].as_bool()), (Some("/content/one/en/summer"), Some(allowed)), "{e}");
    }
}

#[tokio::test]
async fn a_site_s_redirect_map_is_answered_here_and_written_for_apache() {
    let (publish, addr) = start_publish().await;
    let sites = r#"
[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]
shared = ["/content/dam/one"]
redirect_map = "/content/dam/one/redirectmap.txt"

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let client = reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).build().unwrap();
    let get = |host: &'static str, path: &'static str| {
        let (client, url) = (client.clone(), live.url.clone());
        async move {
            let r = client.get(format!("{url}{path}")).header("host", host).send().await.unwrap();
            let location = r.headers().get("location").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
            (r.status(), location, verdict(r.headers()).to_owned())
        }
    };
    let file = live.docroot.join("one.redirects.txt");
    for _ in 0..50 {
        if std::fs::read_to_string(&file).unwrap_or_default().contains("/old.html") {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(get("one.test", "/old.html").await, (StatusCode::MOVED_PERMANENTLY, "/content/one/en.html".into(), "redirect".into()));
    assert_eq!(get("one.test", "/promo?utm_source=x").await, (StatusCode::FOUND, "/content/one/en/deals.html?utm_source=x".into(), "redirect".into()));
    assert_eq!(get("one.test", "/away").await.1, "https://example.org/x");
    // bad lines are left out; another site's names and other methods are not redirected
    assert_eq!(get("one.test", "/bad-status").await.0, StatusCode::NOT_FOUND);
    assert_eq!(get("two.test", "/old.html").await.0, StatusCode::NOT_FOUND);
    let post = client.post(format!("{}/old.html", live.url)).header("host", "one.test").send().await.unwrap();
    assert_ne!(post.status(), StatusCode::MOVED_PERMANENTLY);
    assert_eq!(publish.asked("/old.html"), 0, "the publish never hears of a redirect");
    // the file Apache's RewriteMap reads
    let text = std::fs::read_to_string(&file).unwrap();
    assert!(text.contains("/old.html 301|/content/one/en.html\n") && text.contains("/promo 302|/content/one/en/deals.html\n"), "{text}");
    assert!(!live.docroot.join("two.redirects.txt").exists());
    // the URL tester says so
    let (_, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", "/promo?a=1"), ("host", "one.test")])).await;
    let e: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!((e["answer"].as_u64(), e["location"].as_str()), (Some(302), Some("/content/one/en/deals.html?a=1")), "{e}");
    // a publication brings a new rule; a map that is gone empties it
    publish.version.store(1, Ordering::SeqCst);
    assert_eq!(live.flush("Activate", "/content/dam/one/redirectmap.txt").await, StatusCode::OK);
    let mut seen = StatusCode::NOT_FOUND;
    for _ in 0..50 {
        seen = get("one.test", "/later.html").await.0;
        if seen == StatusCode::PERMANENT_REDIRECT {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(seen, StatusCode::PERMANENT_REDIRECT);
    publish.version.store(2, Ordering::SeqCst);
    assert_eq!(live.flush("Deactivate", "/content/dam/one/redirectmap.txt").await, StatusCode::OK);
    for _ in 0..50 {
        if std::fs::read_to_string(&file).unwrap().is_empty() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(std::fs::read_to_string(&file).unwrap(), "");
    assert_ne!(get("one.test", "/old.html").await.0, StatusCode::MOVED_PERMANENTLY);
}

#[tokio::test]
async fn a_host_may_have_redirects_of_its_own_and_unknown_hosts_go_to_the_default_site() {
    let (publish, addr) = start_publish().await;
    let sites = r#"
default_site = "one"

[[site]]
name = "one"
hosts = ["one.test", "shop.one.test"]
roots = ["/content/one"]
shared = ["/content/dam/one"]
redirect_map = "/content/dam/one/redirectmap.txt"
host_redirect_maps = { "shop.one.test" = "/content/dam/one/shop-redirects.txt" }

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let client = reqwest::Client::builder().no_proxy().redirect(reqwest::redirect::Policy::none()).build().unwrap();
    let get = |host: &'static str, path: &'static str| {
        let (client, url) = (client.clone(), live.url.clone());
        async move {
            let r = client.get(format!("{url}{path}")).header("host", host).send().await.unwrap();
            let location = r.headers().get("location").and_then(|v| v.to_str().ok()).unwrap_or("").to_owned();
            (r.status(), location)
        }
    };
    let file = live.docroot.join("one.redirects.txt");
    for _ in 0..50 {
        if std::fs::read_to_string(&file).unwrap_or_default().contains("/cart") {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    // the host's own rule wins over the site's, whatever the case of the name and with a port
    assert_eq!(get("shop.one.test", "/old.html").await, (StatusCode::FOUND, "/content/one/en/shop.html".into()));
    assert_eq!(get("Shop.One.Test:8080", "/old.html").await, (StatusCode::FOUND, "/content/one/en/shop.html".into()));
    assert_eq!(get("shop.one.test", "/cart").await, (StatusCode::MOVED_PERMANENTLY, "/content/one/en/shop/cart.html".into()));
    // the site's rules still hold on that host; the host's only there
    assert_eq!(get("shop.one.test", "/promo").await, (StatusCode::FOUND, "/content/one/en/deals.html".into()));
    assert_eq!(get("one.test", "/old.html").await, (StatusCode::MOVED_PERMANENTLY, "/content/one/en.html".into()));
    assert_eq!(get("one.test", "/cart").await.0, StatusCode::NOT_FOUND);
    // a host no site names is the default site's
    assert_eq!(get("unknown.test", "/old.html").await, (StatusCode::MOVED_PERMANENTLY, "/content/one/en.html".into()));
    let (status, headers, _) = live.send(live.client.get(format!("{}/content/one/en.html", live.url)).header("host", "unknown.test")).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::OK, "stored"));
    assert!(live.docroot.join("one/content/one/en.html").exists(), "kept in the default site's cache");
    // ...and another site's pages are not for it
    let (status, headers, _) = live.send(live.client.get(format!("{}/content/two/en.html", live.url)).header("host", "unknown.test")).await;
    assert_eq!((status, verdict(&headers)), (StatusCode::NOT_FOUND, "denied"));
    assert_eq!(publish.asked("/content/two/en.html"), 0);
    // what Apache reads: the host's rules with the host in front
    let text = std::fs::read_to_string(&file).unwrap();
    assert!(text.contains("shop.one.test/old.html 302|/content/one/en/shop.html\n") && text.contains("/old.html 301|/content/one/en.html\n"), "{text}");
    // the URL tester, and the status page
    let (_, _, body) = live.send(live.client.get(format!("{}/_live/explain", live.url)).query(&[("url", "/old.html"), ("host", "shop.one.test")])).await;
    let e: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert_eq!((e["answer"].as_u64(), e["location"].as_str()), (Some(302), Some("/content/one/en/shop.html")), "{e}");
    let (_, _, body) = live.get("/_live/status").await;
    let s: serde_json::Value = serde_json::from_slice(&body).unwrap();
    assert!(s.to_string().contains("shop-redirects.txt"), "{s}");
    // the file emptied away by hand (the directory cleared): written again at the next reading
    std::fs::remove_file(&file).unwrap();
    assert_eq!(live.flush("Activate", "/content/one/en/x").await, StatusCode::OK);
    for _ in 0..100 {
        if std::fs::read_to_string(&file).unwrap_or_default().contains("/cart") {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert!(std::fs::read_to_string(&file).unwrap_or_default().contains("shop.one.test/old.html 302|"), "written again");
}

#[tokio::test]
async fn the_publish_is_told_only_the_headers_listed() {
    let (_publish, addr) = start_publish().await;
    let sites = r#"
client_headers = ["User-Agent", "accept-*", "cookie", "x-forwarded-for"]

[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
client_headers = ["x-custom"]
"#;
    let live = &start_live(&[format!("http://{addr}")], sites).await;
    let told = |host: &'static str, path: &'static str| {
        let request = live
            .client
            .get(format!("{}{path}", live.url))
            .header("host", host)
            .header("user-agent", "test")
            .header("accept-language", "ro")
            .header("x-custom", "1")
            .header("x-evil", "drop table")
            .header("cookie", "a=1")
            .header("x-forwarded-for", "203.0.113.7");
        async move {
            let (status, _, body) = live.send(request).await;
            assert_eq!(status, StatusCode::OK, "{}", String::from_utf8_lossy(&body));
            let told: serde_json::Value = serde_json::from_slice(&body).unwrap();
            let names: Vec<String> = told["names"].as_array().unwrap().iter().map(|n| n.as_str().unwrap().to_owned()).collect();
            (names, told["forwarded"].as_str().unwrap().to_owned())
        }
    };
    // the general list, in any case and with globs; Host always
    let (names, forwarded) = told("one.test", "/content/one/headers.json").await;
    for name in ["host", "user-agent", "accept-language", "cookie", "x-forwarded-for"] {
        assert!(names.contains(&name.to_owned()), "{name} in {names:?}");
    }
    assert!(!names.contains(&"x-evil".to_owned()) && !names.contains(&"x-custom".to_owned()), "{names:?}");
    assert_eq!(forwarded, "203.0.113.7, 127.0.0.1");
    // a site's own list in its place: not the visitor's chain, only this hop
    let (names, forwarded) = told("two.test", "/content/two/headers.json").await;
    assert!(names.contains(&"x-custom".to_owned()) && names.contains(&"host".to_owned()), "{names:?}");
    assert!(!names.contains(&"cookie".to_owned()) && !names.contains(&"user-agent".to_owned()), "{names:?}");
    assert_eq!(forwarded, "127.0.0.1");
}

#[test]
fn a_list_of_client_headers_is_header_names() {
    let with = |list: &str| Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\nclient_headers = {list}\n"));
    assert!(with(r#"["Cookie", "x-gwt-*"]"#).is_ok());
    assert!(with(r#"["a b"]"#).is_err());
    assert!(with(r#"[""]"#).is_err());
    let config = with(r#"["Cookie"]"#).unwrap();
    assert!(config.forwards_header(None, "cookie") && config.forwards_header(None, "host") && !config.forwards_header(None, "referer"));
    assert!(Config::parse("docroot = \"cache\"\npublish = [\"http://p\"]\n").unwrap().forwards_header(None, "x-anything"));
}

#[tokio::test]
async fn a_site_may_take_out_less_of_its_cache_on_a_publication() {
    let (_publish, addr) = start_publish().await;
    let sites = r#"
[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]
[site.cache]
stat_level = 3

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = &start_live(&[format!("http://{addr}")], sites).await;
    let get = |host: &'static str, path: &'static str| async move { live.send(live.client.get(format!("{}{path}", live.url)).header("host", host)).await };
    let cached = |site: &str, path: &str| live.docroot.join(site).join(&path[1..]).exists();
    for (host, site) in [("one.test", "one"), ("two.test", "two")] {
        for lang in ["en", "fr"] {
            let page: &'static str = Box::leak(format!("/content/{site}/{lang}/page.html").into_boxed_str());
            assert_eq!(get(host, page).await.0, StatusCode::OK);
            assert!(cached(site, page));
        }
    }
    // site one: only below /content/one/en (and the pages directly above it)
    assert_eq!(live.flush("Activate", "/content/one/en/new").await, StatusCode::OK);
    assert!(!cached("one", "/content/one/en/page.html") && cached("one", "/content/one/fr/page.html"));
    // site two, the general stat_level 0: every page of it
    assert_eq!(live.flush("Activate", "/content/two/en/new").await, StatusCode::OK);
    assert!(!cached("two", "/content/two/en/page.html") && !cached("two", "/content/two/fr/page.html"));
}

#[cfg(unix)]
#[tokio::test]
async fn after_an_invalidation_a_command_and_a_url_are_told_what_was_taken_out() {
    let (publish, addr) = start_publish().await;
    let dir = tempfile::tempdir().unwrap();
    // the command writes down what it was given
    let script = dir.path().join("purge.sh");
    let told = dir.path().join("told.txt");
    std::fs::write(&script, format!("#!/bin/sh\n{{ echo \"args $*\"; echo \"env $CQ_ACTION $CQ_HANDLE $STUDIO_LIVE_SITES $STUDIO_LIVE_HOSTS $STUDIO_LIVE_WHOLE\"; cat; }} > {:?}\n", told)).unwrap();
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
    let sites = format!(
        r#"
[after_invalidation]
command = [{script:?}, "--zone", "z1"]
url = "http://{addr}/cdn/purge"
headers = {{ "X-Token" = "env:PATH" }}

[[site]]
name = "one"
hosts = ["one.test", "*.one.test"]
roots = ["/content/one"]

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#
    );
    let live = &start_live(&[format!("http://{addr}")], &sites).await;
    let get = |host: &'static str, path: &'static str| async move { live.send(live.client.get(format!("{}{path}", live.url)).header("host", host)).await };
    assert_eq!(get("one.test", "/content/one/en.html").await.0, StatusCode::OK);
    assert_eq!(get("one.test", "/content/one/en/a.html").await.0, StatusCode::OK);
    assert_eq!(get("two.test", "/content/two/en.html").await.0, StatusCode::OK);
    assert_eq!(live.flush("Activate", "/content/one/en/a").await, StatusCode::OK);
    let mut text = String::new();
    for _ in 0..100 {
        text = std::fs::read_to_string(&told).unwrap_or_default();
        if text.lines().count() >= 4 && !publish.purged.lock().unwrap().is_empty() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    // the command: its own arguments, then the action and the path; the paths on its input
    let lines: Vec<&str> = text.lines().collect();
    assert_eq!(lines[0], "args --zone z1 Activate /content/one/en/a", "{text}");
    assert_eq!(lines[1], "env Activate /content/one/en/a one one.test true", "{text}");
    assert_eq!(&lines[2..], ["/content/one/en.html", "/content/one/en/a.html"], "only site one's, and each once: {text}");
    // the URL: the same as JSON, with the header from the environment
    let (token, body) = publish.purged.lock().unwrap()[0].clone();
    assert_eq!(token, std::env::var("PATH").unwrap());
    assert_eq!(body["action"], "Activate");
    assert_eq!(body["paths"], serde_json::json!(["/content/one/en.html", "/content/one/en/a.html"]));
    assert_eq!(body["sites"], serde_json::json!([{"name": "one", "hosts": ["one.test"]}]));
    assert_eq!((body["removed"].as_u64(), body["whole"].as_bool()), (Some(2), Some(true)));
    // the status says so
    let mut status = serde_json::Value::Null;
    for _ in 0..50 {
        let (_, _, raw) = live.get("/_live/status").await;
        status = serde_json::from_slice(&raw).unwrap();
        if status["afterInvalidation"]["told"] == 1 {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!((status["afterInvalidation"]["told"].as_u64(), status["afterInvalidation"]["failed"].as_u64()), (Some(1), Some(0)), "{status}");
    // emptying the whole cache is said too: everything, without a list
    let (status_code, _, _) = live.send(live.client.post(format!("{}/_live/clear", live.url))).await;
    assert_eq!(status_code, StatusCode::OK);
    for _ in 0..100 {
        text = std::fs::read_to_string(&told).unwrap_or_default();
        if text.starts_with("args --zone z1 Clear /") && text.lines().count() >= 2 {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(text.lines().take(2).collect::<Vec<_>>(), ["args --zone z1 Clear /", "env Clear / one,two one.test,two.test false"], "{text}");
    // a command that fails is counted and said
    std::fs::write(&script, "#!/bin/sh\necho 'the CDN said no' >&2\nexit 3\n").unwrap();
    assert_eq!(live.flush("Activate", "/content/two/en").await, StatusCode::OK);
    for _ in 0..100 {
        let (_, _, raw) = live.get("/_live/status").await;
        status = serde_json::from_slice(&raw).unwrap();
        if status["afterInvalidation"]["failed"].as_u64() >= Some(1) {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    let last = &status["afterInvalidation"]["last"];
    assert_eq!(last["ok"], false, "{status}");
    assert!(last["error"].as_str().unwrap().contains("the CDN said no"), "{status}");
}

#[test]
fn the_after_invalidation_settings_are_checked() {
    let with = |hook: &str| Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\n[after_invalidation]\n{hook}\n"));
    assert!(with("command = [\"/usr/local/bin/purge\"]").is_ok());
    assert!(with("url = \"https://api.cdn.example/purge\"\nheaders = { Authorization = \"env:CDN_TOKEN\" }").is_ok());
    assert!(with("command = [\"\"]").is_err());
    assert!(with("url = \"ftp://x\"").is_err());
    assert!(with("url = \"https://x\"\nheaders = { \"a b\" = \"1\" }").is_err());
    assert!(with("url = \"https://x\"\nheaders = { A = \"env:\" }").is_err());
    assert!(with("url = \"https://x\"\ntimeout_seconds = 0").is_err());
}

#[tokio::test]
async fn the_quickest_publish_instance_gets_the_requests() {
    let (slow, slow_addr) = start_publish().await;
    let (quick, quick_addr) = start_publish().await;
    slow.delay_ms.store(150, Ordering::SeqCst);
    let live = &start_live(&[format!("http://{slow_addr}"), format!("http://{quick_addr}")], "balance = \"fastest\"\n").await;
    // answers nobody keeps (no-store): every one is asked of a publish instance
    for _ in 0..20 {
        assert_eq!(live.get("/content/one/headers.json").await.0, StatusCode::OK);
    }
    let (s, q) = (slow.asked("/content/one/headers.json"), quick.asked("/content/one/headers.json"));
    assert!(s <= 2 && q >= 18, "slow {s}, quick {q}");
    // pages are timed apart: the slow one is tried for them too, once
    for _ in 0..6 {
        live.get("/content/one/x.html?a=1").await;
    }
    assert!(slow.asked("/content/one/x.html") <= 2, "{}", slow.asked("/content/one/x.html"));
    // the status says how quick each is
    let (_, _, raw) = live.get("/_live/status").await;
    let status: serde_json::Value = serde_json::from_slice(&raw).unwrap();
    let slow_ms = status["publish"][0]["answersIn"]["other"].as_u64().unwrap();
    assert!(slow_ms >= 100, "{status}");
}

#[tokio::test]
async fn a_500_with_a_failing_health_check_sends_the_request_elsewhere() {
    let (first, first_addr) = start_publish().await;
    let (second, second_addr) = start_publish().await;
    let live = &start_live(&[format!("http://{first_addr}"), format!("http://{second_addr}")], "health_check = \"/health\"\n").await;
    // a page whose own code fails: its 500 is passed on, the other instance not bothered
    let (status, _, _) = live.get("/content/one/boom.html").await;
    assert_eq!(status, StatusCode::INTERNAL_SERVER_ERROR);
    assert_eq!(first.asked("/content/one/boom.html") + second.asked("/content/one/boom.html"), 1);
    assert_eq!(first.asked("/health") + second.asked("/health"), 1);
    // a broken instance: its health check fails too, so the other one answers, and it is skipped
    first.sick.store(true, Ordering::SeqCst);
    for n in 0..4 {
        let (status, _, body) = live.get(&format!("/content/one/p{n}.html")).await;
        assert_eq!(status, StatusCode::OK, "{}", String::from_utf8_lossy(&body));
    }
    let asked_first: usize = (0..4).map(|n| first.asked(&format!("/content/one/p{n}.html"))).sum();
    assert!(asked_first <= 1, "skipped after the first: {asked_first}");
    let (_, _, raw) = live.get("/_live/status").await;
    let s: serde_json::Value = serde_json::from_slice(&raw).unwrap();
    assert_eq!(s["publish"][0]["skipped"], true, "{s}");
}

#[test]
fn the_balance_settings_are_checked() {
    let with = |extra: &str| Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\n{extra}\n"));
    assert_eq!(with("").unwrap().balance, studio_live::config::Balance::RoundRobin);
    assert_eq!(with("balance = \"fastest\"").unwrap().balance, studio_live::config::Balance::Fastest);
    assert!(with("balance = \"random\"").is_err());
    assert!(with("health_check = \"health\"").is_err());
    assert!(with("health_check = \"/system/health\"").is_ok());
}

#[tokio::test]
async fn answers_are_tagged_for_a_cdn_and_a_publication_names_the_tags_to_purge() {
    let (publish, addr) = start_publish().await;
    let extra = format!("[cdn]\nkind = \"fastly\"\n\n[after_invalidation]\nurl = \"http://{addr}/cdn/purge\"\n");
    let cache = "invalidate_by_dependencies = [\"/content/dam/*\", \"/content/experience-fragments/*\"]\n";
    let live = &start_live_with(&[format!("http://{addr}")], &extra, cache).await;
    let tags_of = |headers: &HeaderMap| -> Vec<String> {
        headers.get("surrogate-key").and_then(|v| v.to_str().ok()).unwrap_or("").split(' ').filter(|t| !t.is_empty()).map(str::to_owned).collect()
    };
    let mut tagged = std::collections::HashMap::new();
    for page in ["/content/deps/hero.html", "/content/deps/header.html", "/content/deps/plain.html", "/content/deps/unknown.html"] {
        let (status, headers, _) = live.get(page).await;
        assert_eq!((status, verdict(&headers)), (StatusCode::OK, "stored"), "{page}");
        let tags = tags_of(&headers);
        assert!(tags.contains(&"all-cache".to_owned()) && tags.contains(&"site-cache".to_owned()), "{page}: {tags:?}");
        // kept with its tags, so the service serves it, not the web server from a bare file
        assert!(!live.docroot.join(&page[1..]).exists(), "{page} is not a plain file");
        let (_, again, _) = live.get(page).await;
        assert_eq!((verdict(&again), tags_of(&again)), ("hit", tags.clone()), "{page} served again with its tags");
        tagged.insert(page, tags);
    }
    assert!(tagged["/content/deps/unknown.html"].contains(&"nodeps-cache".to_owned()));
    assert!(!tagged["/content/deps/hero.html"].contains(&"nodeps-cache".to_owned()));

    // the hero image is published: the tags purged are those of the pages the cache takes out
    assert_eq!(live.flush("Activate", "/content/dam/deps/hero.jpg").await, StatusCode::OK);
    let mut purged: Vec<String> = Vec::new();
    for _ in 0..100 {
        if let Some((_, body)) = publish.purged.lock().unwrap().first() {
            purged = body["tags"].as_array().unwrap().iter().map(|t| t.as_str().unwrap().to_owned()).collect();
        }
        if !purged.is_empty() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    let hits = |page: &str| tagged[page].iter().any(|t| purged.contains(t));
    assert!(hits("/content/deps/hero.html") && hits("/content/deps/unknown.html"), "{purged:?}");
    assert!(!hits("/content/deps/header.html") && !hits("/content/deps/plain.html"), "{purged:?}");
    // ...and that is what the cache took out
    let held = live.docroot.with_extension("held");
    assert!(!held.join("content/deps/hero.html").exists() && held.join("content/deps/header.html").exists());
}

#[test]
fn the_cdn_settings_are_checked() {
    let with = |cdn: &str| Config::parse(&format!("docroot = \"cache\"\npublish = [\"http://p\"]\n[cdn]\n{cdn}\n"));
    let akamai = with("kind = \"akamai\"").unwrap();
    assert_eq!(akamai.cdn.as_ref().unwrap().header(), ("Edge-Cache-Tag", ",", 128));
    assert_eq!(with("kind = \"fastly\"").unwrap().cdn.unwrap().header(), ("Surrogate-Key", " ", 800));
    assert_eq!(with("kind = \"cloudfront\"\nheader = \"X-Tags\"\nmax_tags = 40").unwrap().cdn.unwrap().header(), ("X-Tags", ",", 40));
    assert!(with("kind = \"varnish\"").is_err());
    assert!(with("kind = \"fastly\"\nmax_tags = 2").is_err());
    assert!(with("kind = \"fastly\"\nuntagged = [\"etc\"]").is_err());
}

#[test]
fn a_redirect_map_line_by_line() {
    let (rules, bad) = studio_live::server::parse_redirect_map(
        "# c\n\n/a /b\n/c https://x.test/y 307\n/a /other\nrelative /x\n/d //evil.test\n/e /f 200\n/g /h 301 extra\n/i javascript:x\n/j /k|l\n",
    );
    assert_eq!(rules.len(), 2, "{rules:?}");
    assert_eq!(rules["/a"], (301, "/b".to_owned()), "the first rule for a source wins");
    assert_eq!(rules["/c"], (307, "https://x.test/y".to_owned()));
    assert_eq!(bad, 6, "a second rule for /a is not a bad line, only not used");
}

#[tokio::test]
async fn a_published_asset_takes_out_only_the_pages_that_show_it() {
    let (publish, addr) = start_publish().await;
    let cache = "invalidate_by_dependencies = [\"/content/dam/*\", \"/content/experience-fragments/*\"]\n";
    let live = start_live_with(&[format!("http://{addr}")], "", cache).await;
    let pages = ["/content/deps/hero.html", "/content/deps/header.html", "/content/deps/plain.html", "/content/deps/unknown.html"];
    for page in pages {
        let (_, headers, _) = live.get(page).await;
        assert_eq!(verdict(&headers), "stored", "{page}");
        assert!(headers.get("x-studio-depends").is_none(), "what a page shows is not the visitor's business");
    }
    let cached = |page: &str| live.docroot.join(&page[1..]).exists();
    assert!(live.docroot.with_extension("deps").join("content/deps/hero.html").exists(), "kept beside the cache");

    // the hero image: the page that shows it and the one that says nothing; not the others
    assert_eq!(live.flush("Activate", "/content/dam/deps/hero.jpg").await, StatusCode::OK);
    assert!(!cached(pages[0]) && cached(pages[1]) && cached(pages[2]) && !cached(pages[3]));
    let s = live_status(&live).await;
    assert_eq!((s["lastInvalidation"]["byDependencies"].as_bool(), s["lastInvalidation"]["showing"].as_u64()), (Some(true), Some(1)), "{s}");
    // a rendition of an asset a page shows, and an escaped comma
    live.get(pages[0]).await;
    assert_eq!(live.flush("Activate", "/content/dam/deps/a,b.jpg/jcr:content/renditions/original").await, StatusCode::OK);
    assert!(!cached(pages[0]) && cached(pages[1]));
    // the header fragment: the page that shows it
    assert_eq!(live.flush("Activate", "/content/experience-fragments/deps/header/master").await, StatusCode::OK);
    assert!(!cached(pages[1]) && cached(pages[2]));
    // a page: everything, as before
    for page in pages {
        live.get(page).await;
    }
    assert_eq!(live.flush("Activate", "/content/deps/other").await, StatusCode::OK);
    assert!(pages.iter().all(|p| !cached(p)));
    assert_eq!(live_status(&live).await["lastInvalidation"]["byDependencies"], false);
    assert_eq!(publish.asked(pages[2]), 1, "the page that shows nothing published was kept all along");
}

async fn live_status(live: &Live) -> serde_json::Value {
    let (_, _, body) = live.get("/_live/status").await;
    serde_json::from_slice(&body).unwrap()
}

#[tokio::test]
async fn a_site_keeps_an_answer_as_long_as_its_publish_says() {
    let (publish, addr) = start_publish().await;
    // two sites, the same pages; only the first keeps them by the publish's Cache-Control
    let sites = r#"
[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]
[site.cache]
enable_ttl = true
grace_seconds = 0

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let get = |host: &'static str, path: &'static str| {
        let live = &live;
        async move { live.send(live.client.get(format!("{}{path}", live.url)).header("host", host)).await }
    };
    let file = |site: &str, path: &str| live.docroot.join(site).join(&path[1..]);

    assert_eq!(verdict(&get("one.test", "/content/one/ttl.html").await.1), "stored");
    assert_eq!(verdict(&get("two.test", "/content/two/ttl.html").await.1), "stored");
    assert_eq!(verdict(&get("one.test", "/content/one/ttl.html").await.1), "hit");
    assert!(file("one", "/content/one/ttl.html").exists());
    // max-age=0: over already, not kept where the time counts; kept where it does not
    assert_eq!(verdict(&get("one.test", "/content/one/over.html").await.1), "pass");
    assert!(!file("one", "/content/one/over.html").exists());
    assert_eq!(verdict(&get("two.test", "/content/two/over.html").await.1), "stored");
    let status = live_status(&live).await;
    assert_eq!(status["sites"][0]["timed"], 1);
    assert_eq!(status["sites"][0]["cache"]["enableTtl"], true);
    assert_eq!(status["sites"][1]["timed"], 0);
    assert!(status["sites"][1]["cache"].is_null(), "the general settings");

    // two seconds later the file is gone (Apache would have served it until then), without a publication
    publish.version.store(1, Ordering::SeqCst);
    tokio::time::sleep(std::time::Duration::from_millis(3200)).await;
    assert!(!file("one", "/content/one/ttl.html").exists());
    assert!(file("two", "/content/two/ttl.html").exists(), "the other site keeps it until a publication");
    let (_, headers, body) = get("one.test", "/content/one/ttl.html").await;
    assert_eq!((verdict(&headers), body.as_ref()), ("stored", b"/content/one/ttl.html v1".as_ref()));
    let (_, headers, body) = get("two.test", "/content/two/ttl.html").await;
    assert_eq!((verdict(&headers), body.as_ref()), ("hit", b"/content/two/ttl.html v0".as_ref()));
    assert_eq!(publish.asked("/content/one/ttl.html"), 2);
}

#[tokio::test]
async fn each_site_with_its_own_cache_settings() {
    let (_publish, addr) = start_publish().await;
    // site one keeps every query parameter as the same page and keeps nothing under /content/one/live
    let sites = r#"
[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one"]
[site.cache]
ignore_query = ["*"]
rules = [{ type = "allow", glob = "*" }, { type = "deny", glob = "/content/one/live/*" }]

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let get = |host: &'static str, path: &'static str| {
        let live = &live;
        async move { verdict(&live.send(live.client.get(format!("{}{path}", live.url)).header("host", host)).await.1).to_owned() }
    };
    assert_eq!(get("one.test", "/content/one/en.html?page=2").await, "stored");
    assert_eq!(get("one.test", "/content/one/en.html?page=3").await, "hit");
    // the general ignore_query (utm_*, gclid) for the other site
    assert_eq!(get("two.test", "/content/two/en.html?page=2").await, "pass");
    assert_eq!(get("two.test", "/content/two/en.html?utm_source=x").await, "stored");
    // its own rules
    assert_eq!(get("one.test", "/content/one/live/score.html").await, "pass");
    assert_eq!(get("one.test", "/content/one/live/score.html").await, "pass");
    assert_eq!(get("two.test", "/content/two/live/score.html").await, "stored");
}

#[tokio::test]
async fn a_site_that_allows_it_serves_signed_in_visitors_from_the_cache() {
    let (publish, addr) = start_publish().await;
    // site one: pages the same for everyone, signed in or not (a dispatcher's /allowAuthorized "1")
    let sites = r#"
[[site]]
name = "one"
hosts = ["one.test"]
roots = ["/content/one", "/content/site"]
[site.cache]
allow_authorized = true

[[site]]
name = "two"
hosts = ["two.test"]
roots = ["/content/two"]
"#;
    let live = start_live(&[format!("http://{addr}")], sites).await;
    let get = |host: &'static str, path: &'static str, cookie: Option<&'static str>, basic: bool| {
        let live = &live;
        async move {
            let mut request = live.client.get(format!("{}{path}", live.url)).header("host", host);
            if let Some(cookie) = cookie {
                request = request.header("cookie", cookie);
            }
            if basic {
                request = request.basic_auth("ana", Some("secret"));
            }
            verdict(&live.send(request).await.1).to_owned()
        }
    };
    // a signed-in visitor's page is kept, and served to the next one, signed in or not
    assert_eq!(get("one.test", "/content/one/en.html", Some("studio-login=member"), false).await, "stored");
    assert_eq!(get("one.test", "/content/one/en.html", None, true).await, "hit");
    assert_eq!(get("one.test", "/content/one/en.html", None, false).await, "hit");
    assert_eq!(publish.asked("/content/one/en.html"), 1);
    // what the publish says is one visitor's is still not kept
    assert_eq!(get("one.test", "/content/site/account.html", Some("studio-login=member"), false).await, "pass");
    assert_eq!(get("one.test", "/content/site/account.html", Some("studio-login=member"), false).await, "pass");
    // the other site never serves a signed-in visitor from the cache
    assert_eq!(get("two.test", "/content/two/en.html", None, false).await, "stored");
    assert_eq!(get("two.test", "/content/two/en.html", Some("studio-login=member"), false).await, "pass");
    assert_eq!(get("two.test", "/content/two/en.html", None, true).await, "pass");
}

#[tokio::test]
async fn a_flush_may_list_the_pages_to_fetch_again() {
    let (publish, addr) = start_publish().await;
    // rewarm is off here: only what the flush lists is fetched again
    let live = start_live(&[format!("http://{addr}")], "").await;
    for page in ["/content/site/en.html", "/content/site/about.html"] {
        assert_eq!(verdict(&live.get(page).await.1), "stored");
    }
    publish.version.store(1, Ordering::SeqCst);
    // a dispatcher's re-fetching flush: the paths in the body, one a line
    let request = live
        .client
        .post(format!("{}/dispatcher/invalidate.cache", live.url))
        .header("CQ-Action", "Activate")
        .header("CQ-Handle", "/content/site/en")
        .header("Content-Type", "application/octet-stream")
        .body("/content/site/en.html\n/content/site/news.html?x=1\nnot a path\n/content/site/../secret.html\n");
    let (status, _, body) = live.send(request).await;
    assert_eq!(status, StatusCode::OK);
    assert!(String::from_utf8_lossy(&body).contains("2 to fetch again"), "{body:?}");
    // fetched again without a visitor: the published page and one that was not cached before
    for _ in 0..50 {
        if publish.asked("/content/site/en.html") == 2 && live.docroot.join("content/site/news.html").exists() {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(20)).await;
    }
    assert_eq!(std::fs::read_to_string(live.docroot.join("content/site/en.html")).unwrap(), "/content/site/en.html v1");
    assert_eq!(std::fs::read_to_string(live.docroot.join("content/site/news.html")).unwrap(), "/content/site/news.html v1");
    // what the flush took out and did not list waits for a visitor
    assert!(!live.docroot.join("content/site/about.html").exists());
    assert_eq!(publish.asked("/content/site/about.html"), 1);
    assert_eq!(live_status(&live).await["lastInvalidation"]["refetching"], 2);
    // a flush without permission lists nothing either
    let outsider = live
        .client
        .post(format!("{}/dispatcher/invalidate.cache", live.url))
        .header("CQ-Action", "Activate")
        .header("CQ-Handle", "/content/site/en")
        .header("X-Forwarded-For", "203.0.113.9")
        .body("/content/site/x.html");
    assert_eq!(live.send(outsider).await.0, StatusCode::FORBIDDEN);
}
