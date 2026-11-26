---
title: Reference
description: Sensible Studio reference: commands, ports, environment variables, endpoints, and where everything is stored in the repository.
order: 120
---

## Commands

### bin/studio

| Command | What it does |
|---|---|
| `setup [--demo] [--wknd]` | Builds everything and fills `instances/author/install`. First, and after pulling new code. `--demo` adds Weekender, the demo site; `--wknd` adds Adobe's WKND sample site. |
| `start [-p <port>]` | Starts the author instance in the foreground. Home `instances/author`; `AUTHOR_HOME` overrides. |
| `stop` | Stops it. |
| `build` | Compiles and packages the Java modules. |
| `dist <version>` | Makes a release archive in `dist/`: everything built, for this machine's system. |
| `test` | Runs the Java tests. |

### bin/studio-publish

| Command | What it does |
|---|---|
| `setup` | Seeds the publish instance's install folder from the author's, with the publish-only configuration. |
| `start`, `stop`, `restart`, `status` | The instance on port 4503, home `instances/publish`. |
| `key` | Gives a running instance the farm's sign-in key. |

`PUBLISH_HOME` and `PUBLISH_PORT` choose another instance: a second publish, or a preview.

### bin/studio-live

| Command | What it does |
|---|---|
| `build` | Compiles the service (needs cargo). |
| `start`, `stop`, `restart`, `status` | The service on `127.0.0.1:4520`. |
| `check` | Reads the configuration and says what it means. |
| `flush [path]` | Invalidates as a publication of that path would. |
| `clear` | Forgets every cached file. |
| `explain URL [METHOD] [HOST]` | What would happen to that request, and which rule decides. |
| `import FILE [--var N=V] [--vars FILE]` | Prints a dispatcher configuration as a `live.toml`. |
| `apache start\|stop\|status\|check` | A trial Apache in front of it, on `http://localhost:8085`. |

## Ports

| Port | What |
|---|---|
| 4502 | Author |
| 4503 | Publish |
| 4504, 4505 | By convention: a second publish, the preview |
| 4510 | Media service of the author |
| publish port + 8 | Media service of a publish instance (4511, 4512, 4513) |
| 4520 | Live cache |
| 8085 | Trial Apache |

## Environment variables

| Variable | What it sets |
|---|---|
| `JAVA_HOME` | The Java to run with (21 or newer). |
| `AUTHOR_HOME` | The author's home folder. |
| `PUBLISH_HOME`, `PUBLISH_PORT` | A publish instance's home and port. |
| `STUDIO_LOGIN_KEY` | Where the farm's sign-in key is. |
| `STUDIO_DAM_FFMPEG`, `STUDIO_DAM_SOFFICE` | ffmpeg and LibreOffice, if not on the path. |
| `LIVE_CONFIG`, `LIVE_HOME` | The Live cache's configuration file and home. |
| `STUDIO_LIVE_FLUSH_SECRET` | The secret a flush from another machine must carry. |
| `HTTPD` | Which `httpd` the trial Apache uses. |

## Where things are stored

| What | Where |
|---|---|
| Pages | `/content/<site>` |
| Media and content fragments | `/content/dam` |
| Experience fragments | `/content/experience-fragments` |
| Releases | `/content/launches/<date>/<name>` |
| Tags | `/content/cq:tags` |
| Templates, policies, audiences, content fragment models, media settings | `/conf/<site>/settings/…`, falling back to `/conf/global` |
| A project's code | `/apps/<project>` |
| The product's code and interface | `/libs`, `/libs/studio` |
| Publishing targets | `/etc/replication/agents.author/<id>` |
| Flow instances | `/var/workflow` |
| OAuth clients and tokens (hashes) | `/var/studio/oauth` |
| Scheduled publications | `/var/studio/replication/scheduled` |
| Everything else the product keeps for itself: personalization counters, deleted pages kept for restoring, saved searches, comments, translation memory, projects, media reports, share links | `/var/studio/…` |
| Users and groups | `/home` |

Service users write under `/var` on a person's behalf, each within its own area, and each operation is checked against the person's own session first. Authors need no rights there.

## On disk

| What | Where |
|---|---|
| An instance's repository | `<home>/repository` |
| What it installs from | `<home>/install` |
| Its logs | `<home>/logs` |
| The media service and its files | `<home>/dam` |
| The Live cache's files | `instances/live/docroot`, and beside it `.held`, `.stale`, `.deps`, `.ttl`, `.private` |
| The Live cache's configuration | `live-service/conf/live.toml` |
| Apache's side | `live-service/apache/conf.d` |

## Endpoints

Content: any node at its path, with `.json`, `.<depth>.json`, `.infinity.json`; a page with `.model.json`. Writes are Sling POSTs to the node's path. See [APIs](apis.md).

| Area | Endpoint |
|---|---|
| GraphQL | `/content/graphql/global/endpoint.json`, `/content/cq:graphql/<configuration>/endpoint.json` |
| Persisted queries | `/graphql/execute.json/<configuration>/<query>` |
| OAuth | `/oauth/authorize`, `/oauth/token`, `/oauth/revoke` |
| Publishing | `/bin/studio/replication.json`; receiver `/bin/receive` on a publish instance |
| Page editor | `/bin/studio/editor.json`, `/bin/studio/dialog` |
| Templates | `/bin/studio/templates` |
| Pages console | `/bin/studio/sites.json`, `/bin/studio/siterail.json` |
| Media | `/bin/studio/dam.json`; delivery `/bin/studio/dam/deliver` |
| Content fragments | `/bin/studio/cf.json` |
| Sync | `/bin/studio/msm.json` |
| Releases | `/bin/studio/launches.json` |
| Flows | `/bin/studio/workflow.json` |
| Translations | `/bin/studio/translation` |
| Personalization | `/bin/studio/targeting.json`; tracking `/bin/studio/targeting/track` |
| Forms | `/bin/studio/forms/submit` |
| Permission check for protected pages | `/bin/studio/permissioncheck` |
| Sitemap and robots | `<page>.sitemap.xml`, `<page>.sitemap-index.xml`, `<page>.robots.txt` |
| Live cache | `POST /dispatcher/invalidate.cache`; `GET /_live/status`; `POST /_live/clear` |

The `/bin/studio/` endpoints are the authoring interface's own and may change between versions.

### /bin/studio/replication.json

| | |
|---|---|
| GET | `op=agents`, `queue`, `status`, `references`, `cache` |
| POST | `op=activate`, `deactivate` (with `path`, and optionally `deep`, `agent`, `at`); `unschedule`, `test`, `agent`, `removeAgent`, `clear`, `retry` |

### Headers

| Header | Meaning |
|---|---|
| `X-Studio-CSRF: 1` | Required on every request that changes something. |
| `X-Studio-Live` | On answers from the Live cache: `stored`, `hit`, `pass`, `stale`, `grace`, `denied`, `error`. |
| `X-Studio-Depends` | From a publish instance to the cache: what the page shows. Never passed on to visitors. |
| `CQ-Action`, `CQ-Handle` | On a flush request: what happened, and to which path. |

## Useful addresses on an instance

| Address | What |
|---|---|
| `/` | Home |
| `/system/console/bundles` | OSGi bundles and their state |
| `/system/console/configMgr` | OSGi configuration |
| `/system/console/osgi-installer` | What the installer picked up from `install/` |
| `/system/console/jcrresolver` | Test how an address resolves |
| `/system/console/requests` | The latest requests, step by step |
| `/system/health.json` | Health checks, for a monitor |
