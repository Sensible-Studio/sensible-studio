---
title: The Live cache
description: Apache httpd and the studio-live service in front of the publish instances: filter rules, what is cached, what a publication removes, several sites, CDN purging by tag, and importing a dispatcher configuration.
order: 70
---

The Live cache is what visitors actually talk to. It does the job AEM's dispatcher does: it keeps requests that have no business there away from the publish instances, and it serves pages as files.

```
visitor ──► Apache httpd ──► file in the document root?  ── yes ──► served by Apache
            (virtual hosts,                               ── no ───► studio-live ──► publish
             rewrites, TLS)                                           (filter, fetch, write the file)

publish ──► its "Live cache" target ──► studio-live  /dispatcher/invalidate.cache
                                         (removes the files)
```

Apache is configured the way Apache always is: virtual hosts, `mod_rewrite`, TLS, compression. **studio-live** is a small service (Rust, one binary) that does what Apache has no module for. Its configuration is one file, `live-service/conf/live.toml`.

## Try it

```
bin/studio-live build      # once; needs cargo
bin/studio-live start      # 127.0.0.1:4520
bin/studio-live status
```

It answers on its own port, which is enough on a developer's machine: `http://localhost:4520/content/weekender/us/en.html`. The response header `X-Studio-Live` says what happened:

| Value | Meaning |
|---|---|
| `stored` | Fetched from a publish instance and kept. |
| `hit` | Served from the cache. |
| `pass` | Answered by a publish instance, not kept. |
| `stale`, `grace` | The page as it was: the publish instance failed, or the new one is being fetched. |
| `denied` | No filter rule allows it. |
| `error` | No publish instance answered. |

`bin/studio-live explain "/content/weekender/us/en.html?page=2"` says what would happen to a request and which rule decides, without making it.

To try it behind Apache without touching the system's: `bin/studio-live apache start` runs one as you, on `http://localhost:8085`.

## The filter

Nothing reaches a publish instance unless a rule allows it. Rules are read in order and the last one that matches wins.

```
[[filter]]
type = "allow"
method = ["GET", "HEAD"]
path = "/content/*"
extensions = ["html", "css", "js", "jpg", "png", "svg", "webp"]

[[filter]]
type = "allow"
method = ["POST"]
url = "/bin/studio/forms/submit"
```

A rule matches on `method`, `url` (the whole address) or the parts Sling splits it into: `path`, `selectors`, `extensions`, `suffix`, and `query`.

The shipped file allows pages and their media, client libraries under `/etc.clientlibs`, `.model.json`, sitemaps, persisted GraphQL queries, media delivery, form submissions and personalization tracking. The publishing endpoints, the consoles and repository dumps are closed whatever the rules say.

## What is cached

An answer is kept when it is the same for everyone: a GET that answered 200, with an extension, no query that matters, no credentials, no sign-in cookie, and nothing the publish instance marked private. It is written into the document root under the request's own path, and from then on Apache serves it.

| Setting in `[cache]` | What it does |
|---|---|
| `rules` | Which paths may be kept at all. |
| `ignore_query` | Query parameters that do not change the answer (`utm_*`, `gclid`, the client libraries' `v`). |
| `ignore_cookies` | Cookies an answer may set and still be kept; the cookie is dropped. |
| `headers` | Response headers worth keeping with the file. |
| `enable_ttl` | Keep an answer only as long as its `Cache-Control` or `Expires` says. |
| `login_cookies` | The cookies that mean "signed in". |
| `allow_authorized` | Serve kept pages to signed-in visitors too, for a site whose pages are the same for everyone. |

**Signed-in visitors** always get the publish instance's own answer, and nothing of it is kept.

**Protected pages** (`protected = ["/content/site/members*"]` on a site) are the exception. A member's page is kept where the web server cannot find it, and served only after the publish instance confirms that this visitor may read it.

## What a publication removes

When something is published, the publish instance tells the cache, and the cache removes:

- the files of the published page itself;
- **the pages that show it**. Every page's answer says what it showed besides itself: the media and fragments it used, the pages its navigation, lists and teasers listed, where its searches looked. The cache keeps that record and removes exactly the pages that depend on what changed.
- pages that said nothing about their dependencies, since they may show anything.

Publishing a template, or anything under `/conf`, removes more: what `invalidate` and `stat_level` say, as on a dispatcher.

Three things soften the moment after a publication:

- **Fetch again** (`rewarm`): the published page's own renderings are fetched at once, so the first visitor does not wait.
- **Grace** (`grace_seconds`): for a few seconds a removed page is served as it was, while the new one is fetched in the background.
- **Stale** (`stale_seconds`): if the publish instance is down or answers with an error, visitors get the page as it was, for up to a day. A page that was unpublished is never served this way.

By hand: `bin/studio-live flush /content/weekender/us/en` invalidates as a publication of that path would; `bin/studio-live clear` forgets everything. Clear after deploying code.

## Several sites

A `[[site]]` is what a dispatcher calls a farm: its host names, its page trees, and optionally its own publish instances, filter rules, cache settings and error pages.

Once one site is declared, what is not declared is not served. A host name that belongs to no site gets 404, unless `default_site` names the site that takes them. A site's host names show only its own trees and what it lists as shared, and a publication inside a site removes that site's pages only.

```
[[site]]
name = "weekender"
hosts = ["www.weekender.example", "weekender.example"]
roots = ["/content/weekender"]
shared = ["/content/experience-fragments/weekender", "/conf/weekender", "/content/dam/weekender"]
```

Each site's files are in `docroot/<name>`, the `DocumentRoot` of its virtual host.

Per site you can also have:

- **A redirect map** (`redirect_map`): a text file authors keep in Media and publish, one rule a line, `<source> <target> [301|302|…]`. Apache and the service both answer from it; no reload.
- **Short addresses**: with `content_prefix = "/content"`, `/weekender/us/en.html` and `/content/weekender/us/en.html` are one page, cached once.
- **Vanity URLs**: read from the publish instance and let through without a filter rule.

## Several publish instances

`publish = ["http://10.0.0.11:4503", "http://10.0.0.12:4503"]`. Requests go to them in turn, or to the one that answered quickest lately with `balance = "fastest"`. One that does not answer is left out for a while. A signed-in visitor stays with the instance that knows the session.

## Apache

`live-service/apache/conf.d` has the layout of the `dispatcher/src/conf.d` of an AEM project.

| File | What it is |
|---|---|
| `studio_live.conf` | Shared: the document root; "a file, or else the service". |
| `available_vhosts/000_default.vhost` | The host names no site has: 404. |
| `available_vhosts/<site>.vhost` | One site: its domains, over HTTP or HTTPS. |
| `sites/<site>.site` | What the site does in its vhost: cache directory, headers, error pages. |
| `rewrites/<site>_rewrite.rules` | Plain `mod_rewrite`: short addresses, the home page. |
| `rewrites/redirect_map.rules` | The site's redirect map. |

Set `STUDIO_LIVE_DOCROOT` in `studio_live.conf`, enable `rewrite proxy proxy_http headers deflate`, and run `apachectl configtest`.

A site's existing vhost and rewrite files work with two changes: the `SetHandler dispatcher-handler` block goes, and `studio_live.conf` is included.

## A CDN in front

A CDN keeps pages under its own keys, so removing a file here does not remove it there. The reliable way, and the one AEM as a Cloud Service uses, is **purging by tag**.

```
[cdn]
kind = "fastly"        # or "cloudflare", "akamai", "cloudfront"
```

Every answer then carries the CDN's tag header (`Surrogate-Key`, `Cache-Tag` or `Edge-Cache-Tag`) with a tag for its site, one for the page itself, and one for each thing it shows. Tags are short hashes. Every invalidation names the tags to purge, so the CDN drops the same pages as the cache, under whatever address, host or query it keeps them.

The purge itself is the hook that runs after every invalidation:

```
[after_invalidation]
command = ["/usr/local/lib/studio-live/purge-fastly.sh"]
```

Scripts for Fastly, Cloudflare and CloudFront are in `live-service/deploy/cdn`. The hook may also be a URL that receives the invalidation as JSON. The CDN's credentials are read from the service's environment, never from the file.

## Bring a dispatcher configuration

```
bin/studio-live import path/to/dispatcher.any > live.toml
bin/studio-live check
```

`import` reads a `dispatcher.any` with everything it includes and prints it as a `live.toml`: each farm a site, with its virtual hosts, renders, filter rules, cache rules, invalidation, ignored parameters, headers, stat-file level, grace period, TTL, vanity URLs and protected pages. What has no equivalent becomes a comment that says why.

`${NAME}` variables take their values from `--var NAME=VALUE`, from the `Define` lines of `--vars` files, from the project's own `conf.d/variables/*.vars`, then from the environment. Read the result through before using it.

## What it does not do yet

- Weighted balancing between publish instances.
- `;` in a path is refused, except for the variables of a persisted GraphQL query.
- Of a kept page's headers, only the listed ones are kept; `Last-Modified` is the file's.

The complete description, with every setting, is `live-service/README.md` in the repository. Putting it on a server is in [On a server](server.md).
