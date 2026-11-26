---
title: Several sites
description: Keep a family of sites in step in Sensible Studio: a source, its linked copies, syncing changes, and letting a copy go its own way.
order: 100
---

Many organisations run the same site several times: one per country, per brand, per shop. Most of the content is the same; some of it differs. Sensible Studio keeps such a family in step with **sources** and **linked copies**.

- The **source** is where content is written first.
- A **linked copy** follows the source. A change in the source is brought to the copy when you **sync changes**.
- Where a copy needs to differ, you **cancel the inheritance** of that component or page. From then on it is the copy's own, and syncing leaves it alone.

A typical site has a language master as its source, such as `/language-masters/en`, and one linked copy per country that uses that language: `/us/en`, `/ca/en`.

## Create a linked copy

1. In the Pages console, go to where the copy should be.
2. Choose **Create › Linked copy**.
3. Choose the **Source page**, and whether the pages below it come along.
4. Give the copy a title and a name.
5. Choose the **sync rules**: what a sync does. The standard rule copies new and changed content and removes what was removed in the source.

### Create a whole site

**Create › Site** makes a complete site from a **site source**: a recipe that says which languages and which sections a new site of this kind gets. You choose the languages and sections you want and the site's owner; the site is created with all its pages as linked copies.

An administrator defines site sources under **Home › Publishing › Site sources**.

## Sync changes

There are two directions, and both do the same work.

| From | Action | Use it when |
|---|---|---|
| The source | **Sync changes** | You changed the source and want to push it to some or all of its copies |
| A linked copy | **Synchronize** | You look after one copy and want to pull in what is new |

Both are on the bar of the Pages console when the page is selected, and in the page menu of the editor. **Sync changes** lists the linked copies so you choose which ones receive the change, and whether the pages below are included.

Before a page is changed by a sync, a version of it is saved. If a sync brought something you did not want, go back to that version. See [Pages](pages.md).

A source can also sync by itself whenever it is changed. That is a sync rule an administrator sets on the copy.

> **Note:** Syncing changes the copy on the author instance. Visitors see the change after the copy is published.

## Let a copy differ

Open the linked copy in the page editor. Components that follow the source are marked. Select one and choose **Cancel inheritance**: it is now the copy's own, and you can change it. Choose **Re-enable inheritance** to give it back to the source; its content is replaced by the source's at the next sync.

The same works for a whole page, in the page's properties, and for single properties such as the title.

Components you add to a copy yourself are the copy's own from the start. Syncing never touches them.

## See how things stand

- **In the list**: a linked copy is marked with an icon.
- **The inspector's Properties tab** says whether the selected page is a linked copy or a source, and whether it is in sync.
- **Linked copy Overview**, on a source, shows every copy of the source and of the pages below it, with the state of each: in sync, changed in the source since, changed in the copy, not yet created.
- **The page properties** have a **Linked copy** tab on a copy and a **Source** tab on a source, with the source's address, the sync rules, and when changes were last synced and by whom.

## Suspend, reset, detach

| Action | What it does |
|---|---|
| **Suspend** | The copy stops receiving changes for now. **Resume** switches it back on |
| **Reset** | Throws away everything that was changed in the copy and makes it match the source again |
| **Detach** | Cuts the link for good. The page becomes an ordinary page |

> **Warning:** Reset and Detach cannot be undone, except by going back to a version of the page.

## Links between pages

When a page is synced, its links are adjusted: a link in the source that leads to another page of the source leads, in the copy, to the copy's own page. The same holds for experience fragments and content fragments that exist in both.

## Several sites and translation

A source and its copies share a language. To get a site in another language, [translate](translations.md) the language master, then create or sync the country sites from the translated master.
