---
title: Sensible Studio documentation
description: Sensible Studio is an open source content management system that runs existing AEM projects, with a fast authoring interface, a media library, publishing and a cache server of its own.
layout: home
---

:::hero
eyebrow: Open source content management
title: Sensible Studio
text: A content management system for teams that build with pages, components and templates. It runs projects written for Adobe Experience Manager as they are, on an open source stack, with an authoring interface made for people who use it all day.
primary: Get started | getting-started/index.md
secondary: Author guide | author/index.md
:::

## What it is

Sensible Studio is a complete content platform: authors create and publish pages, media and structured content; developers build sites from components, templates and client libraries. It is built on Apache Sling, Apache Jackrabbit Oak and Apache Felix, and released under the Apache License 2.0.

It reads the same project format as Adobe Experience Manager (AEM): the OSGi bundles, the HTL components and dialogs, and the content packages of an existing project install and run without being rewritten. The WKND reference site and the Core Components are the test it has to pass on every change.

What is different is everything a person touches. The authoring interface is one window with tabs, a sidebar and an inspector, built to be quick with a keyboard. Media is processed by a small native service. Publishing, the cache in front of the live site and the tools around them were written for this product, and each is documented here.

## What is in it

:::cards
- [Pages](author/pages.md): Create, organise, move and version pages. A tree, a history and the references of every page beside the list.
- [The page editor](author/page-editor.md): Edit in place, drag components, lay out for each screen size, annotate, preview as a visitor.
- [Media](author/media.md): Upload, organise and find images, video and documents. Renditions, metadata, focus crops and image delivery by URL.
- [Content Fragments](author/content-fragments.md): Structured content with models of your own, delivered to pages and to applications over GraphQL.
- [Experience Fragments](author/experience-fragments.md): Parts of pages, such as a header or a promotion, written once and shown in many places.
- [Several sites](author/sync.md): One source site and its linked copies, per country or brand, kept in sync with rules you choose.
- [Translations](author/translations.md): Language versions of pages, fragments and media, translated by a provider, reviewed and kept up to date.
- [Releases](author/releases.md): Prepare a future version of pages apart from the live site and put it live on a date.
- [Flows and Inbox](author/flows.md): Reviews and approvals as steps that land in a person's Inbox.
- [Publishing](author/publishing.md): Publish now or later, to the live site or to a preview, with what a page needs published along with it.
- [Personalization](author/personalization.md): Show a different experience to each audience, and measure which one reaches the goal.
- [Forms](author/forms.md): Build forms from components, collect and export what visitors submit.
:::

## For developers

:::cards
- [Architecture](developer/architecture.md): The instances, the modules and how a request becomes a page.
- [Run an existing AEM project](developer/aem-projects.md): What installs as it is, what is reimplemented, and how to check a project before moving it.
- [The Live cache](developer/live-cache.md): Apache httpd and a small service in front of the live site: what is cached, what a publication takes out, and how a CDN is told.
- [APIs](developer/apis.md): The HTTP endpoints, GraphQL for content fragments, and OAuth for applications.
- [On a server](developer/server.md): Install the author, the publish instances and the cache; HTTPS, users, secrets and logs.
- [Reference](developer/reference.md): Where everything is stored, every configuration, every command.
:::

## Where to start

:::cards
- [Install and run](getting-started/install.md): From a clone of the repository to a running author instance.
- [A tour of the interface](getting-started/tour.md): Tabs, the sidebar, the inspector and the command palette, in five minutes.
- [Your first page](getting-started/first-page.md): Create a page, edit it, and publish it to the live site.
:::
