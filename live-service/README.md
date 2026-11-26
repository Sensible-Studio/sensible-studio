# Live cache (`studio-live`)

What stands between visitors and the publish instances.

```
visitor ──► Apache httpd ──► file in the document root?  ── yes ──► served by Apache
            (virtual hosts,                               ── no ───► studio-live ──► publish :4503
             rewrites, TLS)                                           (filter, fetch, write the file)

author ──► publish ──► its "Live cache" target ──► studio-live /dispatcher/invalidate.cache
                                                     (removes the files)
```

Apache is the web server and is configured the way Apache always is: virtual hosts, `mod_rewrite`
rules, TLS, compression. This service does the three things Apache has no module for:

- **Filter**: nothing reaches the publish unless a rule allows it (`[[filter]]` in `conf/live.toml`).
  The replication endpoints, the consoles and the repository dumps are closed whatever the rules say.
- **Cache**: an answer that is the same for everyone (a GET, 200, a name with an extension, no query
  that matters, no credentials, no login cookie, nothing the publish marked private) is written
  into the document root under the request's own path. From then on Apache serves it as a file.
- **Headers**: an answer with headers of its own worth keeping (a download's `Content-Disposition`,
  a type the extension does not say; `headers` in `live.toml`) is no plain file. It is kept beside
  the document root, in `<docroot>.held`, and this service serves it with those headers, still
  without asking the publish.
- **Stale pages**: what a publication takes out of the cache is kept for a day in
  `<docroot>.stale` (`stale_seconds`). If the publish does not answer, or answers with a 5xx, when
  the page is wanted again, the visitor gets the page as it was (`X-Studio-Live: stale`). A page
  that was unpublished is never kept.
- **Fetching again**: when a page is published, its own renderings that were in the cache are
  fetched again at once (`rewarm`), under the host name its visitors used, so the first visitor
  after a publication does not wait for the publish. A flush may also list pages to fetch again,
  as a dispatcher's re-fetching flush: their repository paths (`/content/site/en.html`, or the
  publish's own `/site/en.html` with `content_prefix`), one a line in the body of the invalidation
  request, at most 200; each is fetched for the site whose page it is.
- **Invalidation by dependencies** (`invalidate_by_dependencies = ["/content/*"]` in the shipped
  file): every page's answer from a publish says what it shows besides itself (`X-Studio-Depends`,
  `DependsFilter` in `studio-replication`: the assets and experience fragments its content and its
  template's structure name, the pages above it, the pages its navigation, lists, breadcrumb and
  teasers showed, the pages whose children it listed, where its searches looked). The service keeps
  that beside the cache (`<docroot>.deps`) and never passes the header on. A publication of
  something matching those globs takes out its own files, the pages that show it, and the pages
  that said nothing (so may show anything); the other pages stay. A template or anything under
  `/conf` still takes out what `invalidate` and `stat_level` say.
- **Kept as long as the publish says** (`enable_ttl`, off by default; a dispatcher's
  `/enableTTL`): `Cache-Control: s-maxage` / `max-age` (less `Age`) or `Expires`; the time is kept
  beside the cache (`<docroot>.ttl`) and the file taken out within a second of it.
- **Per site**: any `[cache]` setting may differ for a site, in its `[site.cache]` table.
- **Grace period** (`grace_seconds`, 10 in the shipped file, 0 by default): for that long after a
  publication, a page it took out is served as it was, at once (`X-Studio-Live: grace`), while it
  is fetched again in the background, once per page; the next visitor gets the new one. Unpublished
  pages are never served so; protected pages never.
- **Several publish instances**: `publish = [...]` lists them. Requests go to them in turn; one
  that does not answer is left out for a while and the request goes to the next. A visitor who
  signed in stays with the instance that knows the session (`sticky`, a `studio-route` cookie
  that names the instance by a fingerprint, not by its address); pages that are the same for
  everyone are cached once, whichever instance rendered them.
- **Signed-in visitors** always get the publish's own answer, and nothing of it is kept (unless a
  site says `allow_authorized`, a dispatcher's `/allowAuthorized`: then they get kept pages and
  theirs are kept, for sites whose pages are the same for everyone). With
  several publish instances the sign-in of `studio-auth` (cookie `studio-login`, the same
  `login.key` in every publish instance's home folder) is good on all of them; the cookie's name
  is in `login_cookies` and in `apache/conf.d/studio_live.conf`.
- **Protected pages** (a closed user group's, `protected = ["/content/site/members*"]` in a
  `[[site]]`): the one exception. A member's 200 is kept, but in `<docroot>/<name>.private`, where
  the web server never finds it; every later request for it, signed in or not, is answered from
  there only after the publish says at `cache.auth_checker` (`/bin/studio/permissioncheck?uri=…`,
  with the visitor's cookies; `studio-auth` on the publish) that this visitor may read it. Anyone
  else gets the publish's own answer (a sign-in page, a 404). It is what a dispatcher's
  permission-sensitive caching does, with the same condition: the page is the same for every
  member. A publication takes it out; it is not fetched again by the cache, which may not read it.
- **Redirect maps** (`redirect_map = "/content/dam/<site>/redirectmap.txt"` in a `[[site]]`, as
  AEM's managed rewrite maps): authors publish a text file of rules, `<source> <target>
  [301|302|303|307|308]` a line (301 when left out; the target a path or an http(s) URL). The service
  reads it from the publish at the start, every `redirects.refresh_seconds` (300) and after every
  publication, writes it beside the document root (`<name>.redirects.txt`) for the virtual host's
  `RewriteMap` (`rewrites/redirect_map.rules`, before the site's own rules; Apache reads the file
  again when it changes, no reload), and answers the same redirects itself for what reaches it.
  The query of the request is kept. Bad lines are left out and counted in the log. A host name may
  have a map of its own besides (`host_redirect_maps = { "shop.example" = "/content/dam/…" }`): its
  rules win over the site's for that host, and are written with the host name in front of the
  source (`shop.example/old.html 302|/…`), which `redirect_map.rules` looks up first (lowercased
  `SERVER_NAME`).
- **Request headers to the publish** (`client_headers`, a dispatcher's `/clientheaders`; a
  `[[site]]` may have its own list): only the listed headers of a visitor's request reach the
  publish (globs, any case); `Host` always, `X-Forwarded-For` extended by this service, the
  visitor's chain only when listed. Unset: all but the connection's. The shipped `live.toml` lists
  what Sling and the platform read (cookies, credentials, content negotiation, conditionals, CSRF,
  `X-Forwarded-*`).
- **After every invalidation** (`[after_invalidation]`, a dispatcher's `/invalidateHandler`): a
  command, a URL (POST of JSON), or both, told the action, the published path, the sites concerned
  with their host names and the request paths taken out (at most 10 000; `whole` says whether
  that is all): to empty a CDN in front. The command gets its arguments, then action and path,
  the paths on its input and `CQ_ACTION`, `CQ_HANDLE`, `STUDIO_LIVE_SITES`, `STUDIO_LIVE_HOSTS`,
  `STUDIO_LIVE_WHOLE`; a header value `env:NAME` is read from the environment. It runs after the
  flush is answered; failures are logged and counted in `/_live/status` (`afterInvalidation`).
- **Choosing a publish instance** (`balance = "fastest"`, a dispatcher's `/statistics` and
  `/unavailablePenalty`): the one that answered quickest lately (a moving average, pages and the
  rest apart; `unavailable_penalty_ms` added after a failure), instead of in turn; the visitor's own
  (sticky) still first. `/_live/status` gives each instance's `answersIn`. `health_check` (a
  dispatcher's `/health_check`): after a 500, that path is asked of the same instance; a 5xx or no
  answer there skips the instance and asks another one, otherwise the 500 is passed on.
- **The site's error pages** (AEM's `DispatcherPassError` with `ErrorDocument`; `sites/wknd.site`):
  `ProxyErrorOverride On 404 500` and `ErrorDocument 404|500 <the site's error page>`, so a 404 or
  500 from the publish, and the cache's own 404 for what it does not serve, is the site's page with
  the same status. Files Apache serves are never errors; a cached file a publication takes out
  while Apache is serving it (Apache's own 403) is asked of the service instead
  (`studio_live.conf`), so a visitor never sees that moment.
- **A CDN that purges by tag** (`[cdn] kind = "fastly" | "cloudflare" | "akamai" | "cloudfront"`;
  the industry's way, and AEM as a Cloud Service's): every answer gets the CDN's tag header
  (`Surrogate-Key`, `Cache-Tag`, `Edge-Cache-Tag`) with `all-<site>`, `site-<site>` for what the
  cache's own invalidation takes out with the site, its own page's tag and the tags of what it shows
  (`X-Studio-Depends`: `<page>/jcr:content` → the page's tag, `<page>/*` → the tag of its list of
  children, any other path → its tree's tag); one that says nothing, or more than the kind takes,
  gets `nodeps-<site>`. Tags are short hashes any CDN accepts. Every invalidation names the tags to
  purge (`STUDIO_LIVE_TAGS`, `tags`), so the CDN drops the same pages as the cache, under whatever
  short URL, host or query it keeps them (`src/tags.rs`, tested against the cache's own rule).
  Tagged answers are kept with their headers, so the service serves them; `untagged` paths
  (client libraries) stay Apache's files. Commands for `[after_invalidation]`:
  `deploy/cdn/purge-fastly.sh` (soft purge by default), `purge-cloudflare.sh` (100 tags a
  request), `purge-cloudfront.sh` (`aws cloudfront create-invalidation` with `#tag` items).
- **A default site** (`default_site = "<name>"` at the top of `live.toml`): a host name no site
  names is that site's, as a dispatcher's farm for `/virtualhosts "*"`, instead of getting 404.
  Apache's `000_default.vhost` answers those names before the service sees them, so give it the
  site's `DocumentRoot` and rules as well.
- **Vanity URLs** (`[vanity] url`, a dispatcher's `/vanity_urls`): the publish lists every
  `sling:vanityPath` an anonymous visitor may read with its page
  (`/libs/granite/dispatcher/content/vanityUrls.json`, `studio-cq-impl`); the service reads the
  list at the start, every `refresh_seconds` and after every publication, and lets a GET for one
  through without a filter rule, under the host names of the site whose page it leads to. The
  publish resolves it; it is not kept (no extension).
- **An existing dispatcher configuration** (`bin/studio-live import dispatcher.any [--var NAME=VALUE]...
  [--vars FILE]...`, or `studio-live --import-dispatcher`): read with its `$include`s
  (`enabled_farms/*.farm`) and printed as a `live.toml`: each farm a site (host names from
  `/virtualhosts`, publish instances from `/renders`, its pages guessed from the filter's
  `/content/<x>` rules, `/clientheaders`), its filter rules the site's (`/method`, `/url`, `/path`,
  `/extension`, `/selectors`, `/suffix`, `/query`, the old `/glob`, regexes as regexes), `/cache`
  `/rules`, `/invalidate`, `/ignoreUrlParams`, `/headers`, `/statfileslevel`, `/gracePeriod`,
  `/enableTTL`, `/allowAuthorized` (the first farm's as `[cache]`, every other farm's as its
  `[site.cache]`), `/vanity_urls` and the auth checker's pages (as `protected`). `${NAME}` (the
  dispatcher's environment variables) takes its value from `--var`, then the `Define` lines of
  `--vars` files, then the project's own `conf.d/variables/*.vars` beside `conf.dispatcher.d`, then
  the environment; one without a value stays and is named at the end. What has no equivalent is a
  comment that says why, a deny rule left out with a warning. Read the result through, then
  `--check` it.
- **Several sites** (`[[site]]`, what a dispatcher calls farms): each with its host names, its
  page trees, and optionally publish instances and filter rules of its own. Once a site is
  declared, what is not declared is not served: a host name that is no site's gets 404, and a
  site's names show, of all content, only its own trees and what it lists as shared. A
  publication inside a site (or of content it lists as shared) takes out that site's pages, not
  everybody's.
- **Invalidation**: a `POST /dispatcher/invalidate.cache` with `CQ-Action` and `CQ-Handle` (what a
  target of type "Dispatcher flush" in the Publishing console sends) removes the files of that
  resource and every `.html` in scope (`stat_level`).

## Run it

```
bin/studio-live build     # once, needs cargo
bin/studio-live start     # 127.0.0.1:4520, cache in instances/live/docroot
bin/studio-live status
bin/studio-live flush /content/wknd/us/en
bin/studio-live clear
bin/studio-live explain "/content/wknd/us/en.html?page=2"   # allowed? kept? which rule decides?
```

It answers on its own port as well, which is enough on a developer machine:
`http://localhost:4520/content/wknd/us/en.html`. The response header `X-Studio-Live` says what
happened: `stored`, `hit`, `stale`, `pass` (answered by the publish, not kept), `denied`, `error`.

## In the Publishing console

The console of the author (and of the publish) shows each cache under its agents: what it holds,
what it was last told, the publish instances it fetches from, and a **Clear cache** button for
administrators. The author does not talk to the cache: it asks the publish instances it delivers
to (`/bin/studio/replication.json?op=cache`, with the agent's credentials), and each publish asks
the caches behind its flush targets (`GET /_live/status`, `POST /_live/clear`, with the target's
credentials).

## Connect the publish

The flush comes from the **publish**, after the content has arrived there: its `/bin/receive`
passes every activation and deactivation on to the publish's own flush targets. A flush sent by
the author could reach the cache before the publish has the new page.

A publish instance has the target already: **Live cache** (`live_cache`, type Dispatcher flush,
`http://127.0.0.1:4520/dispatcher/invalidate.cache`), disabled. Enable it in the publish's
Publishing console (`http://localhost:4503/replication.html`, signed in as an administrator);
Test Connection answers `OK`. While the Live cache is down the publish keeps the request and
retries every minute.

On a server set a secret: `STUDIO_LIVE_FLUSH_SECRET` in the service's environment, and the same
value as the target's transport password (any user name). Without a secret only a direct
connection from the same machine may invalidate.

## Put Apache in front

`apache/conf.d` has the same layout as the `dispatcher/src/conf.d` of an AEM project:

| File | What it is |
|---|---|
| `studio_live.conf` | shared: the document root, "file or service" |
| `available_vhosts/000_default.vhost` | the host names no site has: 404 (first, over HTTP and HTTPS) |
| `available_vhosts/wknd.vhost` | one site: its domains, over HTTP or, on a server, HTTPS |
| `sites/wknd.site` | what the site does in its vhost: cache directory, headers, rewrite rules |
| `rewrites/wknd_rewrite.rules` | plain `mod_rewrite`: short URLs, the home page |
| `rewrites/redirect_map.rules` | the site's redirect map, through the `RewriteMap` its vhost declares |

Set `STUDIO_LIVE_DOCROOT` in `studio_live.conf` to the absolute path of the `docroot` of
`conf/live.toml`, enable `rewrite proxy proxy_http headers deflate`, and run `apachectl configtest`.
The user Apache runs as needs to read the document root; the user this service runs as writes it.

To try it without touching the system's Apache:

```
bin/studio-live apache start     # http://localhost:8085, run as you, with apache/test/httpd-test.conf
bin/studio-live apache stop | status | check
```

On macOS the system `httpd` may not read `~/Documents`, `~/Desktop` or `~/Downloads` ("Operation
not permitted") until it is given Full Disk Access (System Settings → Privacy & Security), or the
configuration and the document root live elsewhere (`LIVE_HOME`, and `docroot` in a copy of
`live.toml` named by `LIVE_CONFIG`).

A site's existing vhost and rewrite files work as they are, with two changes: the
`SetHandler dispatcher-handler` block goes, and `studio_live.conf` is included.

## What a real publish needed (WKND, 20.11.2026)

- `content_prefix = "/content"`: the publish writes image and block URLs without `/content`
  (`/wknd/us/en/_jcr_content/…jpeg`) and answers both spellings. With the prefix set both are one
  page: filtered, cached and invalidated at the full path. Apache does the same with one rewrite
  rule (`rewrites/wknd_rewrite.rules`).
- A session cookie on every page kept all of them out of the cache: an empty JSP in the head of
  every page (`cq/cloudserviceconfigs/components/servicelibs`) opened a servlet session. It now
  says `session="false"`. For a site whose own code does the same there is `ignore_cookies`
  (`["JSESSIONID"]`): an answer that sets only listed cookies is cached and the cookie dropped.
- `"v"` in `ignore_query`: client library URLs carry a version stamp (`site.css?v=…`). The file is
  kept once; clear the cache after deploying code.

## What it does not do yet

- After the grace period a page a publication took out waits for a visitor, who waits for the
  publish (or gets the old page if it fails).
- Of a cached page's headers only the listed ones are kept; `Last-Modified` is the file's.
- `;` in a path is refused (it is how filters get bypassed), except for the variables of a
  persisted GraphQL query (`/graphql/execute.json/<configuration>/<query>;name=value`), each set of
  which is kept once. A query that reads content no site lists as its own or shared is not taken
  out when that content is published.
- Sites share the cache settings (`[cache]`): no time-to-live or query rules per site.
- Between the publish instances, users, groups and what visitors leave under
  `/content/usergenerated` travel through the author within about 30 s (form submissions go to the
  author only); a sign-in is good on all of them (`studio-auth`). No weights, and no health checks
  other than failed requests.

## On a server

`deploy/studio-live.service` is a systemd unit to start from: the service runs as its own user,
may write nothing but the cache directories, and gets the flush secret from a file only root and
it can read. In short:

```
useradd --system --home /var/cache/studio-live --shell /usr/sbin/nologin studio-live
install -d -o studio-live -g studio-live -m 711 /var/cache/studio-live           # others may pass through, not look
install -d -o studio-live -g www-data -m 2750 /var/cache/studio-live/docroot    # Apache reads, the service writes
install -D -m 755 target/release/studio-live /usr/local/bin/studio-live
install -D -m 644 conf/live.toml /etc/studio-live/live.toml                      # see below: docroot, publish, listen
install -D -m 755 -t /usr/local/lib/studio-live deploy/cdn/purge-*.sh            # only with a CDN in front ([cdn])
install -m 640 -g studio-live /dev/null /etc/studio-live/env                     # STUDIO_LIVE_FLUSH_SECRET=<long random value>
install -m 644 deploy/studio-live.service /etc/systemd/system/
systemctl enable --now studio-live
```

In `/etc/studio-live/live.toml`: `docroot = "/var/cache/studio-live/docroot"`, `publish` the
publish instances' addresses, and `listen` an address they can reach (`"0.0.0.0:4520"`, or the
machine's own address): on a server the flush comes over the network, and the secret is what lets
it in. Apache's directory rules (`conf.d/studio_live.conf`) already assume these paths.

The unit is `Type=notify`: the service is ready once its port is bound and every site's
`<name>.redirects.txt` exists (empty until the map is read from the publish, which may be down).
Apache refuses to start without the files its `RewriteMap` names, so order it after the service:

```
# /etc/systemd/system/apache2.service.d/studio-live.conf (httpd.service on Red Hat)
[Unit]
After=studio-live.service
Wants=studio-live.service
```

**HTTPS.** `Define STUDIO_LIVE_TLS` before `conf.d/studio_live.conf` is included, enable `ssl`, and
put each site's certificate in `/etc/studio-live/tls` (`STUDIO_LIVE_TLS_DIR`): `<site>.crt` and
`<site>.key`, and `default.crt` / `default.key` for the virtual host of names no site has (keys
root's, mode 600). HTTP then only sends visitors to HTTPS; the HTTPS virtual host sends
`Strict-Transport-Security` and tells the cache and the publish `X-Forwarded-Proto: https`, so their
cookies (`studio-route`, `studio-login`) are `Secure`. Take `localhost` and `127.0.0.1` out of the
sites' `hosts` in `live.toml` and of the vhosts' `ServerAlias`.

**Logs.** Apache's go where the distribution puts them and rotate with its `logrotate` file, which
reloads Apache afterwards; the service logs to the journal (`journalctl -u studio-live`), which
rotates by itself (`SystemMaxUse` in `journald.conf`).

`deploy/rehearsal/` runs all of this on Ubuntu 24.04 with systemd in a container (Docker Desktop
is enough): `run.sh` builds and boots it, `check.sh` checks it (HTTPS by the site's names with
the rehearsal's own certificate authority, HTTP sent to HTTPS, unknown names and `localhost` 404,
`Secure` cookies, pages through Apache, file modes, who may read what, redirects, the flush with
and without the secret, rotating the logs, a restart, a crash, a reboot),
`publish-flush.sh` adds a flush agent with the secret on publish 4503 and activates a page from
author, then removes the agent. The server's Apache answers on 8443 (HTTPS) and 8090, the service
on 4522.

`deploy/rehearsal/farm/` puts two publish instances on machines of their own, Linux containers
with Java 21 and `studio-dam` built for Linux, on a network with the server's cache and nothing
else (no `127.0.0.1` between them): `farm.sh clone` copies publish 2 twice (content, users, code;
it is stopped for a minute), `farm.sh start` starts them and points the cache at them,
`farm.sh configure` gives the author an agent and a reverse agent for each and points their Live
cache targets at the cache with the secret, `farm.sh stop` undoes all of it. The farm's sign-in key
is a secret mounted read-only at `/run/secrets/login.key` (`key.file` points there), not a file in
their homes. `farm/check.sh`: pages from both machines, a publication reaching both and both
flushing, one sign-in valid on both and through the cache over HTTPS, a user made on one machine
arriving on the other and removed again, and a machine stopped under a signed-in visitor.

The same secret is the transport password of the **Live cache** target on every publish
instance (any user name; without it the target gets 403). With sites declared, each site's pages
are in `docroot/<name>`, the DocumentRoot of its virtual host, which Apache must read
(`docroot` above is setgid, so the service's new directories get the group); the directories beside
each (`<name>.held`, `<name>.stale`, `<name>.private` for protected pages) are the service's alone,
and `<name>.private` above all must never be a DocumentRoot: the service creates them mode 700.

**The sign-in key of the publish instances** (`studio-auth` signs the `studio-login` cookie with
it): the same on every publish instance, put there by the deploy, never sent between the instances.
On a developer's machine `bin/studio-publish` keeps it in `instances/farm/login.key` and gives each
instance a copy at `setup` and `start` (`bin/studio-publish key` for one that runs). On a server:

```
install -d -m 750 -o root -g studio /etc/studio
openssl rand -hex 32 | install -m 640 -o root -g studio /dev/stdin /etc/studio/login.key   # once per farm, from the secret store
# every publish instance: io.sensible.studio.auth.impl.LoginHandler.cfg.json → "key.file": "/etc/studio/login.key"
```

A new key (`openssl rand -hex 32` into the same file on every instance) is read within 5 s and signs
everybody out once. The key alone does not let anyone in: a cookie also carries a fingerprint of the
user's password hash, which only the repository has.

## Tests

`cargo test`: the URL handling, the filter, the cache on a real directory, and the service between
a client and a stand-in publish over real sockets (`tests/http.rs`).
