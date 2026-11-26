---
title: Your first page
description: Create a page from a template in Sensible Studio, edit it in place, add an image, and publish it to the live site.
order: 30
---

Ten minutes, from an empty page to a published one. You need a running author instance with the WKND sample site, and a publish instance if you want to see the last step; both are in [Install and run](install.md).

## 1. Create the page

1. On **Home**, open **Pages**.
2. Go to *WKND Site › United States › English › Magazine*. The Content Tree on the left (`Alt+1`) gets you there quickly.
3. Click **Create**, then **Page**.
4. Choose the **Article Page Template**. Only the templates allowed at this place are offered.
5. Type a **Title**, for example *A weekend by the sea*. The **Name**, which becomes the page's address, is made from the title.
6. Click **Create**, then open the new page.

The page opens in the page editor in a tab of its own. It is not empty: the template gave it a header, a footer and a few components to start with.

## 2. Edit in place

Double-click the title and type. Double-click a text and write a paragraph; the small toolbar above it has bold, italic, lists and links. Click outside the text to finish.

Everything is saved as you go. `Ctrl+Z` (`⌘Z` on a Mac) takes back the last change.

## 3. Add a component

The side panel on the left has three tabs: **Components**, **Media** and the **Content Tree** of the page.

- Drag **Image** from **Components** to the place you want it. A line shows where it will land.
- Or skip that step: open **Media**, find a picture, and drag it onto the page. It becomes an Image component by itself.

Select the image and choose **Configure** on its toolbar. The dialog floats over the page; set the alternative text, then **Done**.

## 4. Look at it as a visitor

Switch the mode at the top from **Edit** to **Preview**. The editing frames go away and links work. **Layout** mode shows the page at phone and tablet widths and lets you resize components for each.

## 5. Publish

Open the page menu at the top of the editor and choose **Publish Page**.

The dialog lists what the page uses that is not yet published: here, the image you added. Leave it ticked and confirm.

A few seconds later the page is on the publish instance:

```
http://localhost:4503/content/wknd/us/en/magazine/a-weekend-by-the-sea.html
```

Back in the Pages console the page's row now says it is published, by whom and when.

## What you just used

| You did | Read more |
|---|---|
| Found your way through tabs, rails and the inspector | [A tour of the interface](tour.md) |
| Created a page from a template | [Pages](../author/pages.md), [Templates](../author/templates.md) |
| Edited in place, added components | [The page editor](../author/page-editor.md) |
| Used a picture from Media | [Media](../author/media.md) |
| Published with references | [Publishing](../author/publishing.md) |

From here, authors continue with the [author guide](../author/index.md) and developers with the [developer guide](../developer/index.md).
