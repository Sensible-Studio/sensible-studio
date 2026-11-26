---
title: Architecture
description: The instances of Sensible Studio, the modules in the repository, and how a visitor's request becomes a page.
order: 10
---

## The picture

```
            authors                                   visitors
               │                                         │
               ▼                                         ▼
      ┌─────────────────┐   publish    ┌──────────────┐  ┌──────────────────────┐
      │ Author instance │ ───────────► │   Publish    │◄─│ Live cache           │
      │      :4502      │              │  instance(s) │  │ Apache httpd +       │
      │                 │              │    :4503     │─►│ studio-live :4520    │
      └────────┬────────┘              └──────┬───────┘  └──────────────────────┘
               │                              │   flush
        media service                   media service
           :4510                           :4511
```

- The **author instance** is where people sign in and work. It holds every version of the content, published or not.
- A **publish instance** holds only what was published, and renders it for visitors. There can be several, and a separate one for preview.
- The **Live cache** stands in front of the publish instances. It decides which requests may reach them and keeps their answers as files, which Apache serves.
- The **media service** runs beside each instance and does the heavy work on binaries: renditions, resizing, video, text extraction, auto tags.

The author and publish instances are the same software, started in a different run mode with a different set of packages.

## What an instance is made of

An instance is an OSGi container (Apache Felix) running Apache Sling on a Jackrabbit Oak repository. Sensible Studio adds bundles and content to it:

| Layer | What it is |
|---|---|
| **Repository** | Jackrabbit Oak with the segment store (TarMK): one tree of nodes for content, code, configuration and users. |
| **Sling** | Resolves an address to a node and a node to a script; Sling Models; HTL. |
| **The AEM API** | The `com.day.cq.*`, `com.adobe.cq.*` and Granite packages a project's bundles import, implemented on Sling and Oak. See [Run an existing AEM project](aem-projects.md). |
| **The features** | Publishing, releases, sync, flows, translation, content fragments, forms, personalization, media: each a bundle with its servlets under `/bin/studio/…`. |
| **The authoring interface** | A single-page application (React, TypeScript) served from `/libs/studio`. It reads the same dialog, template and policy definitions that AEM's interface reads. |

## How a request becomes a page

A visitor asks for `https://www.example.com/us/en/magazine.html`.

1. **Apache** maps the short address to `/content/weekender/us/en/magazine.html` and looks for that file in the site's document root. If it is there, Apache serves it and nothing else is involved.
2. Otherwise the request goes to **studio-live**. It checks the filter rules, then asks a publish instance.
3. **Sling** on the publish instance resolves the path to the page's node, reads its `sling:resourceType`, and finds the component's script under `/apps`.
4. The **page component** renders the template's structure and the page's own components, each with its HTL script and Sling Model.
5. The answer goes back through studio-live, which writes it into the document root if it is the same for everyone. The next visitor is served by Apache.

When the page is published again, the publish instance tells the cache, which removes that file and the files of the pages that show it. See [The Live cache](live-cache.md).

## How publishing works

Publishing packs the page's content and binaries, sends them over HTTP to each publish instance's `/bin/receive`, and installs them there. Each target has a queue that retries until the instance answers. See [Publishing targets](publishing-setup.md).

## The repository layout

| Folder | What it holds |
|---|---|
| `bin/` | `studio`, `studio-publish`, `studio-live`: build, set up, start and stop. |
| `studio-core/` | The repository, Sling and HTL base. |
| `studio-adapter/` | Reads AEM's authoring definitions: dialogs, edit configurations, responsive grid. |
| `studio-uberjar/` | The AEM API (`studio-cq-api`, `studio-cq-impl`) and the feature bundles: `studio-auth`, `studio-dam`, `studio-forms`, `studio-health`, `studio-launches`, `studio-linkchecker`, `studio-personalization`, `studio-replication`, `studio-security`, `studio-siterail`, `studio-tagging`, `studio-workflow`. Also `studio-compat-config` (OSGi configuration), `studio-compat-content` (content packages) and `studio-compat-scanner` (a tool). |
| `studio-cfm/` | Content fragments, their models, GraphQL. |
| `studio-translation/` | Translation jobs, providers, translation memory. |
| `studio-authoring-content/` | Content the authoring interface needs in the repository. |
| `studio-quickstart/` | The launcher jar that starts an instance. |
| `author-ui/` | The authoring interface. |
| `dam-service/` | The media service (Rust). |
| `live-service/` | The Live cache (Rust), its Apache configuration, and the files for a server. |
| `docs/` | This documentation: Markdown in `docs/content`, and the script that builds it into a site. |
| `instances/` | The running instances: repositories, logs, install folders. Not in git. |

## Naming

The product is **Sensible Studio** and the one technical token is `studio`: Java packages `io.sensible.studio.*`, modules `studio-*`, the JCR namespace `studio:`, servlets under `/bin/studio/`, repository paths `/var/studio` and `/libs/studio`, headers `X-Studio-*`, environment variables `STUDIO_*`.
