---
title: Page properties
description: What a page's properties are for in Sensible Studio: titles, tags, on and off time, short addresses, redirects, search engine settings and permissions.
order: 20
---

A page's properties are everything about the page that is not its content: what it is called, when it is visible, how its address behaves, who may see it.

Open them from the Pages console (**Properties**, or `p`) or from the page menu in the editor (**Open Properties**). The form is a place of its own: **Save** and **Cancel** both take you back to where you were.

> **Note:** A change to the properties is a change to the page. It reaches the live site when you publish the page.

## Basic

| Field | What it is for |
|---|---|
| **Title** | The page's name everywhere in Sensible Studio, and on the site unless a field below says otherwise |
| **Tags** | The [tags](tags.md) that classify the page. Lists and searches on the site can use them |
| **Hide in Navigation** | Keeps the page out of menus. The page still exists and can be linked to |
| **Page Title** | The title shown in the browser's tab and in search results, when it should differ from the title |
| **Navigation Title** | A shorter name for menus |
| **Subtitle**, **Description** | Shown by components that list pages, and used as the description for search engines |
| **On Time**, **Off Time** | When the page starts and stops being visible on the live site. Outside these times a visitor gets "not found" |
| **Vanity URL** | A short address for the page, such as `/summer`. See below |

### On and off times

Publish the page as usual; the on and off times decide when visitors can see it. A page with an on time in the future is published but not yet visible. To publish at a certain moment instead, use **Publish › Later**, described in [Publishing](publishing.md).

### Vanity URLs

A vanity URL is an extra, short address that leads to the page. Type it without the site's name: `/summer`. With **Redirect Vanity URL** ticked, the visitor's browser is sent to the page's real address; without it, the page is shown under the short address.

The live site learns about a new vanity URL when the page is published.

## Advanced

| Field | What it is for |
|---|---|
| **Language**, **Language Root** | The language of this page and the pages below it. Translations start from language roots. See [Translations](translations.md) |
| **Redirect** | Sends visitors of this page to another page. Tick **Permanent Redirect** when the move is for good |
| **Alias** | Another name for the last part of the page's address |
| **Cloud Configuration** | The site configuration this page and the pages below it use: their templates, component configurations and audiences |
| **Allowed Templates** | Which templates can be used for pages below this one |

### Search engines

| Field | What it is for |
|---|---|
| **Canonical Url** | The address search engines should treat as the page's own, when the same content is reachable at several |
| **Robots Tags** | What search engines may do with the page: `index` or `noindex`, `follow` or `nofollow`, and more |
| **Generate Sitemap** | Makes this page the root of a sitemap listing it and the pages below it |
| **robots.txt** | On a site's top page: what the site's `/robots.txt` says. Left empty, everything is allowed and every sitemap of the site is named |

Tick **Generate Sitemap** on each language root of a site. The sitemap then lists every published page below that root, leaves out pages marked `noindex` and pages that only redirect, and names the same page in the other languages. The site's `/sitemap.xml` is the index of all of them.

## Permissions

The **Permissions** tab shows who may do what with this page, and lets an administrator change it.

- **Add Permissions**: give a user or a group the right to read, modify, delete, publish or create below this page.
- **Edit Closed User Group**: limit the page and the pages below it to the users and groups you name. On the live site, everyone else gets "not found" until they sign in as a member.
- **Effective Permissions**: what a user or group can do here once every rule is taken into account, and where each rule comes from.

## Linked copy and Source

These tabs appear when the page follows a source, or is the source of other pages. They show whether the page is in sync, when changes were last synced and by whom, and let you **Synchronize**, **Suspend** or **Detach**. See [Several sites](sync.md).

## Cloud Services

The **Cloud Services** tab holds the configurations of outside services used below this page. Today that is the translation provider and its settings (**Translation Integration**). See [Translations](translations.md).

## Several pages at once

Select several pages in the Pages console and choose **Properties**. The form then offers what pages can share: **Tags**, **Hide in Navigation**, **On Time** and **Off Time**. For each, choose **Leave as it is** or **Set to…**. What you set is saved on every selected page; everything else stays as each page has it.
