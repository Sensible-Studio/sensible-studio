---
title: Developer guide
description: How Sensible Studio is built, how to run an existing AEM project on it, its APIs, and how to put it on a server.
order: 30
---

This guide is for the people who build sites and run the platform: component developers, people moving a project from AEM, and operators.

Sensible Studio is Apache Sling and Jackrabbit Oak, with a compatibility layer that runs projects built for Adobe Experience Manager, an authoring interface of its own, and two small Rust services: one for media, one in front of the live site. If you know Sling, HTL and OSGi, you know most of it already.

Start with [Install and run](../getting-started/install.md) if you have no instance yet.

## Understand it

:::cards
- [Architecture](architecture.md): The instances, the modules, and how a request becomes a page.
- [Run an existing AEM project](aem-projects.md): What installs as it is, what is reimplemented, and how to check a project before moving it.
- [Components and templates](components.md): HTL components, dialogs, editable templates, policies and client libraries.
:::

## Build with it

:::cards
- [APIs](apis.md): Content over HTTP, GraphQL for content fragments, and OAuth for applications.
- [Extending](extending.md): Flow steps, translation providers, site configurations, and code of your own.
:::

## Run it

:::cards
- [Publishing targets](publishing-setup.md): Publish instances, the preview, queues, and what travels between the instances.
- [The Live cache](live-cache.md): Apache httpd and a small service in front of the live site: filter, cache, invalidation, CDN.
- [On a server](server.md): Install the instances and the cache; HTTPS, users, secrets, backups and logs.
- [Reference](reference.md): Commands, ports, endpoints, and where everything is stored.
:::
