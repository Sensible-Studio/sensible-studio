---
title: Run an existing AEM project
description: How a project built for Adobe Experience Manager runs on Sensible Studio: what installs as it is, what is reimplemented, what has no equivalent, and how to check a project first.
order: 30
---

A project built for AEM is three things: the OSGi bundles of `core`, the components, dialogs and templates of `ui.apps`, and the content of `ui.content`. Sensible Studio takes all three as they are. The project is not rebuilt, and its source is not changed.

The reference project is WKND, Adobe's own sample site. It is installed by `bin/studio setup --wknd` from the package Adobe publishes.

## Check a project before moving it

The compatibility scanner reads the manifests of a project's bundles and lists the proprietary packages they import, checked against what Sensible Studio exports.

```
java -jar studio-uberjar/studio-compat-scanner/target/studio-compat-scanner-*.jar \
     --studio studio-uberjar/studio-cq-api/target/studio-cq-api-*.jar \
     my-project.all-1.0.0.zip
```

The inputs may be bundle jars, content packages (the `all` package, with the bundles embedded in it) or folders. Only manifests are read; nothing is decompiled. The answer is a list of packages: provided, or missing.

A missing package does not always matter. A bundle that imports it optionally still starts; one that needs it for a class nobody calls on your pages only fails where that class is used.

## Install the project

Put the project's `all` package, or its separate packages and bundles, in `instances/author/install` and start the instance. The installer orders packages by their dependencies.

On a running instance, upload it in **Home › System › Developer › Packages** instead.

Two things to know:

- **Every build needs a new version.** The installer installs the highest version of a package and never installs again a version it has already seen. Snapshot builds with the same version are skipped.
- **The publish instance needs the code too.** `bin/studio-publish setup` copies it from the author's install folder.

Then look at **Home › System › Web Console › Bundles**: the project's bundles should be *Active*. One that stays *Installed* names the package it could not find.

## What works as it is

| In the project | On Sensible Studio |
|---|---|
| HTL scripts, Sling Models, servlets, filters, OSGi services and configurations | Run unchanged: it is the same Sling and OSGi. |
| Core Components (`core.wcm.components`) | Adobe's own bundles and components, installed from Maven Central. |
| `cq:dialog`, `cq:design_dialog` | Read and drawn by the authoring interface, with the Sling Resource Merger rules: overlays, `sling:hideProperties`, `sling:orderBefore`. |
| `cq:editConfig`, in-place editing, drop targets | Honoured by the page editor. |
| Editable templates, structure, initial content, policies, the Style System | Edited in **Templates**; stored in the same place and form. |
| The responsive grid (`cq:responsive`) | Layout mode. |
| Client libraries (`cq:ClientLibraryFolder`, categories, embed, dependencies) | Served from `/etc.clientlibs`. |
| Context-aware configuration | **Developer › Site configurations**. |
| Content packages, filters, `rep:policy` | Installed by FileVault. |
| Content fragments, models, GraphQL persisted queries | See [APIs](apis.md). |
| Experience fragments, tags, language copies, live copies, launches, workflows | Each has its console. The names differ; see below. |
| Dispatcher configuration | Imported: see [The Live cache](live-cache.md). |

## What has another name

The repository uses AEM's names, so that projects and content packages keep working. The interface uses its own.

| AEM | Sensible Studio |
|---|---|
| Sites | Pages |
| Assets | Media |
| Live copy, blueprint, rollout | Linked copy, source, sync |
| Launch, promote | Release, go live |
| Workflow, launcher | Flow, trigger |
| Activate, deactivate; replication agent | Publish, unpublish; publishing target |
| Language copy | Language version |
| Dynamic Media, smart crop, smart tags | Image delivery, focus crop, auto tags |
| Targeting | Personalize; audiences and experiences |
| Dispatcher | The Live cache |
| CRXDE Lite | Repository Browser |

## What has no equivalent

Anything that is a service of Adobe's or of a third party tied to AEM: Adobe Target, Analytics, Launch, IMS, Adobe Stock, Dynamic Media (Scene7), Brand Portal, Connected Assets, InDesign Server, Journey Optimizer. A project that calls them needs another integration.

Also not there:

- **The classic and Coral/Granite authoring interfaces.** Dialogs are read, not run: a dialog that depends on its own client-side JavaScript (a `cq.authoring.dialog` client library that changes fields) needs checking. The dialog behaviour of the Core Components is built in.
- **Custom consoles** a project added to AEM's navigation with Granite UI pages.
- **Reverse replication** formats other than Sensible Studio's own, and serialization types other than its content pack and the dispatcher flush.

## Moving content

Content moves as content packages. Build a package of `/content/<site>`, `/content/dam/<site>` and `/conf/<site>` on the old system, and install it here. Users and groups travel in a package too, with their `rep:policy` entries.

Renditions are made again here: after installing a package of media, the media service processes the files in the background. **Home › System › Developer › Jobs** shows the progress.

Versions and audit history do not travel in a content package.
