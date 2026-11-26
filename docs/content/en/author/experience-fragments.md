---
title: Experience Fragments
description: Experience fragments in Sensible Studio are parts of pages, such as a header or a promotion, written once and shown in many places.
order: 80
---

An experience fragment is a part of a page, made of components, that you write once and show in many places: the header and footer of a site, a promotion that appears on twenty pages, a sign-up box.

Change the fragment and publish it, and every page that shows it changes.

## Experience fragment or content fragment?

| | Experience fragment | Content fragment |
|---|---|---|
| **What it is** | Components laid out the way they will look | Fields of content, with no look of their own |
| **You edit it** | In the page editor | In a form |
| **Use it for** | A header, a footer, a banner, a promotion | A product, an article, a person |
| **Shown by** | The Experience Fragment component | Components and applications that decide how it looks |

## Create one

1. Open **Home › Experience Fragments** and go to the folder for it. Fragments are usually kept by site and by language, in the same shape as the site's pages.
2. Choose **Create**, pick a template, and give the fragment a title.
3. Open it. It opens in the [page editor](page-editor.md): add and arrange components as on any page.

The console is the Pages console you already know. Everything works the same: properties, versions, the timeline, references, publishing.

## Variants

A fragment can have several variants: the same promotion as a wide banner for pages and as a narrow box for a sidebar. Each variant is edited on its own. A page shows the variant you choose.

A new fragment starts with one variant, called **master**. Create more below the fragment, the way you create a page below a page.

## Show a fragment on a page

Drag the **Experience Fragment** component onto the page and choose the variant to show.

### In a template

The header and footer of a site are usually experience fragments placed in the [template](templates.md), so every page gets them without anyone adding them. To change the header of the whole site, edit the header fragment and publish it.

### Sites in several languages

When fragments are kept by language, in the same shape as the site, a page shows the fragment of its own language by itself: the English page shows the English header, the French page the French one, though both use the same template.

## Make a fragment from part of a page

You built something on a page and now want it elsewhere too. In the page editor, select the components, then choose **Convert to experience fragment variant** in the toolbar. They are moved into a new fragment, or added as a variant to an existing one, and the page shows the fragment in their place.

## Publish

A fragment is published like a page. When you publish a page that shows a fragment not yet on the live site, the fragment is offered to be published with it.

When a fragment is published, the Live cache refreshes exactly the pages that show it. See [Publishing](publishing.md).

## Before you change or delete one

Select the fragment and open the inspector's **References** tab: it lists every page that shows it. A change reaches all of them. A deleted fragment leaves an empty place on each.
