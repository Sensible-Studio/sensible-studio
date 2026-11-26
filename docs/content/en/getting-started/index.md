---
title: Getting started
description: Install Sensible Studio, find your way around its interface, and publish a first page.
order: 10
---

This section takes you from nothing to a published page. It is written for someone who has not used Sensible Studio before; an hour is enough for all three steps.

:::cards
- [Install and run](install.md): What you need, how to build it, and how to start the author instance and a publish instance.
- [A tour of the interface](tour.md): Tabs, the sidebar, the inspector and the command palette.
- [Your first page](first-page.md): Create a page from a template, edit it in place, and publish it.
:::

## Words used in this documentation

| Word | What it means |
|---|---|
| **Author instance** | Where content is created and edited. People sign in to it. It listens on port 4502 on a developer's machine. |
| **Publish instance** | Where published content is served from. Visitors never sign in to edit there. Port 4503. |
| **Live** | The public site: the publish instances, usually behind the Live cache. |
| **Live cache** | The web server and cache in front of the publish instances. It serves most requests from files. |
| **Page** | A node of the content tree with a title, a template and components. Its address is its path, such as `/content/wknd/us/en`. |
| **Component** | A piece of a page: a title, a text, an image, a list. Each has a dialog where you edit it. |
| **Template** | What a new page starts from: its fixed parts, the components allowed in it, and their configuration. |
| **Media** | Images, video and documents, kept under `/content/dam`. |
| **Repository** | The store behind everything: a tree of nodes with properties, which you can browse and query. |

## Where to go next

Authors continue with the [author guide](../author/index.md). Developers continue with the [developer guide](../developer/index.md).
