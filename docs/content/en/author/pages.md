---
title: Pages
description: Browse, create, move, copy, delete and restore pages in the Pages console of Sensible Studio.
order: 10
---

The Pages console is where a site's pages live. You browse the tree, create and organise pages, and start everything else from here: editing, publishing, translating, a flow, a release.

Open it from **Home › Pages**. Experience Fragments and Forms use the same console, rooted at their own folder, so what you learn here works there too.

## Find your way around

The switch at the right of the bar changes how the pages are shown:

- **Column View**: one column per level of the tree. Click a page to see the pages below it. This is the quickest way to move through a deep site.
- **Card View**: a card with a thumbnail for each page.
- **List View**: a table with the title, name, who modified the page and when, and its publishing state. Click a column heading to sort.

A dot beside a page's title says where it stands: published, modified since it was published, or not published. Small icons mark a page that is locked, one that follows a source, and one that is in a flow.

The breadcrumb in the bar shows where you are. Click any part of it to go up.

Two panels help you find things:

- **The left rail**: the **Content Tree** (`Alt+1`), or the **Filter** (`Alt+2`), which searches the pages below a path by text, template, tags, date, linked copies and locked pages. You can save a search and run it again later. While a filter is set, the console shows the results as a list, and every action works on them.
- **The inspector** at the right: properties, timeline, references and site of what you selected. See [A tour of the interface](../getting-started/tour.md).

To find a page by name from anywhere, press `Cmd+K` and type.

## Create a page

1. Go to the page that the new page will sit below.
2. Click **Create**, then **Page**.
3. Choose a template. Only the templates allowed at this place are offered.
4. Fill in the **Title**. The **Name** is the part of the address; it is made from the title, and you can change it. You can also set the page title, navigation title, subtitle and description now, or later in the [page properties](page-properties.md).
5. Click **Create**, then open the new page to edit it.

**Create** offers more than a page:

| Create | What you get |
|---|---|
| **Page** | A page from a template allowed here |
| **Site** | A whole site made from a site source. See [Several sites](sync.md) |
| **Linked copy** | Pages that follow a source. See [Several sites](sync.md) |
| **Paste** | The pages you copied, below the page you are at |
| **CSV Report** | A spreadsheet of the pages below this one, with the properties you choose |

With a page selected, **Create** also offers a **Version**, a **Release**, a **Translation Project** and **Start Flow** for that page.

## Act on pages

Select one or more pages with the checkbox on each item. The bar changes to the actions for the selection; right-click shows the same actions as a menu.

| Action | Key | What it does |
|---|---|---|
| **Edit** | `e` | Opens the page in the [page editor](page-editor.md). With several pages selected, each opens in a tab of its own |
| **Properties** | `p` | Opens the [page properties](page-properties.md). With several pages selected, you set what they can share: tags, on and off time, hide in navigation |
| **Lock**, **Unlock** | | Keeps others from changing the page while you work on it |
| **Publish**, **Unpublish** | | Puts the page on the live site, or takes it off. See [Publishing](publishing.md) |
| **Publish with the pages below** | | Publishes a whole section |
| **Language versions** | | The page in other languages. See [Translations](translations.md) |
| **Site configuration** | | The settings the site's code reads at this place |
| **Copy** | `Cmd+C` | Remembers the selection for **Create › Paste** |
| **Move** | `m` | Moves or renames the page |
| **Delete** | `Backspace` | Deletes the selection |

### Move or rename a page

**Move** asks for a **Destination** and a **Name**. Sensible Studio lists the pages that refer to the page you are moving. Leave **Adjust references** ticked, and their links are rewritten to the new address.

A move changes the page's address. If the page was published, publish it again at its new place, together with the pages that link to it.

### Delete a page

**Delete** shows the pages that refer to what you are deleting, because their links will break. Deleting a page also deletes the pages below it.

Nothing is lost for good: every deleted page can be brought back. See the next section.

## Versions

A version is the page as it was at a moment you chose. Sensible Studio also makes one by itself before anything risky: before a revert, before a delete.

- **Save a version**: select the page, then **Create › Version**. Give it a label and a comment, so you can find it later. With several pages selected, each gets a version.
- **See the versions**: the inspector's **Properties** tab lists them, and so does **Timeline**.
- **Look at a version**: click it in the Timeline, then **Preview Version**. The page opens as it was, in a new tab. Nothing is changed.
- **See what changed**: **Compare to Current** shows the version and today's page side by side with the changes marked, and a second tab that lists them component by component.
- **Go back to a version**: **Revert to this Version**. The page as it is now is saved as a version first, so a revert can be undone.

## Restore deleted pages

Select the page that contained what was deleted, then **Restore › Restore Version**. The screen lists every page below it that has versions, the deleted ones included. For each, choose the version to go back to, tick the pages you want, and click **Restore**. A deleted page comes back as the same page, with its history.

Pages deleted in some other way than this console, such as by a script or a package, are listed too. Sensible Studio keeps a copy of every deleted page for 90 days.

**Restore › Restore Tree** puts a whole section back as it was at a date and time. It shows what will happen to each page before you confirm.

> **Warning:** Restore Tree changes every page below the one you start from. Pages created after the date are deleted unless you choose to preserve them.

## The timeline of a page

The inspector's **Timeline** tab lists everything that happened to the selected page, newest first: it was created, modified, published; versions; flows and each step someone completed; comments; the notes left in the editor's Annotate mode; the releases that hold a copy of it.

Write in the **Comment** field at the bottom to leave a note for your colleagues. Comments are kept beside the page, not in it: they are never published.

## Who refers to a page

The inspector's **References** tab shows the pages that link to or include the selection, its linked copies with their state, and the releases that contain it. Look here before you move, unpublish or delete something.

## Several pages at once

Most actions work on a selection of many pages: publish, unpublish, move, delete, start a flow, create a translation project, a version or a release. **Select All** in the bar selects everything in the current folder or in the filter's results.
