# Sensible Studio

An open-source content platform that runs projects built for Adobe Experience Manager.

Authors create and publish pages, media and structured content. Developers build sites from components, templates and client libraries. Underneath it is Apache Sling and Jackrabbit Oak, with a compatibility layer for the AEM APIs, an authoring interface of its own, and two small Rust services: one for media, one in front of the live site.

- **Runs existing AEM projects.** The OSGi bundles, the HTL components and dialogs, and the content packages of a project install as they are. Adobe's WKND sample site is the reference.
- **A fast authoring interface.** Pages, the page editor, templates, media, content and experience fragments, tags, flows, translation, releases, linked copies, personalization, forms.
- **Media built in.** Renditions, any size by address, focus crops, video, auto tags and search by what a picture shows.
- **A live site that stays up.** The Live cache does a dispatcher's job: filter, cache, precise invalidation, several sites, CDN purging by tag. It reads an existing dispatcher configuration.
- **APIs.** JSON for every node, the page model, GraphQL for content fragments, OAuth 2.0.

## Quick start

**From a release** (you need Java 21, nothing else): download the archive for your system from [Releases](https://github.com/Sensible-Studio/sensible-studio/releases), unpack it, and

```
bin/studio setup --wknd     # optional: the WKND sample site
bin/studio start            # the author instance: http://localhost:4502
```

**From source** you also need Maven and Node.js 20; Rust is optional (media processing and the Live cache). The commands are the same: `setup` builds everything first.

Sign in as `admin` / `admin`, and change that password before anyone else can reach the instance.

```
bin/studio-publish start    # a publish instance: http://localhost:4503
bin/studio-live start       # the Live cache: http://localhost:4520
```

## Documentation

The documentation is in [`docs/content/en`](docs/content/en), as Markdown:

- [Getting started](docs/content/en/getting-started/index.md): install, a tour of the interface, your first page.
- [Author guide](docs/content/en/author/index.md): everything an author, editor or administrator does.
- [Developer guide](docs/content/en/developer/index.md): architecture, running an AEM project, components, APIs, the Live cache, putting it on a server.

It is also a site built with Sensible Studio itself: `python3 docs/build.py` writes it into a running instance, at `/content/studio-docs/en.html`.

## What is in the repository

| Folder | What it holds |
|---|---|
| `bin/` | `studio`, `studio-publish`, `studio-live`: build, set up, start and stop |
| `studio-core/`, `studio-adapter/`, `studio-uberjar/`, `studio-cfm/`, `studio-translation/`, `studio-forms/` | The Java modules: the AEM API on Sling and Oak, and the features |
| `studio-quickstart/` | The launcher that starts an instance |
| `author-ui/` | The authoring interface (React, TypeScript) |
| `dam-service/` | The media service (Rust) |
| `live-service/` | The Live cache (Rust), its Apache configuration, and the files for a server |
| `docs/` | The documentation and the script that builds it into a site |

## Before you put it on a server

A new instance is set up for a developer's machine: default passwords, sample users, no secrets. Read [On a server](docs/content/en/developer/server.md) first.

## License

Apache License 2.0. See [LICENSE](LICENSE).

Sensible Studio is an independent project. It is not affiliated with, endorsed by or sponsored by Adobe. Adobe, Adobe Experience Manager and AEM are trademarks of Adobe Inc.
