---
title: The page editor
description: Edit a page in place in Sensible Studio: add and move components, edit text and images, lay out for each screen size, annotate and preview.
order: 30
---

The page editor shows the page as visitors will see it, with a thin layer on top that lets you select and change every component. What you change is saved at once; there is no Save button.

To open it, select a page in the Pages console and choose **Edit** (or press `e`).

## The screen

- **The top bar**: the side panel switch, the page menu, Undo and Redo, the device emulator, the page's title, the mode, Preview, and the annotation buttons.
- **The status bar**, under the top bar, appears when there is something to know about the page: it is in a flow, someone else has locked it, or it follows a source.
- **The side panel** has three tabs: **Components** to drag onto the page, **Media** to drag images and files from, and the **Content Tree** of the page.
- **The page** itself.

## Select and change a component

Move the pointer over the page: the component under it is outlined, and its name is shown. Click to select it. A toolbar opens with what you can do; only the actions the component supports are shown.

| Action | What it does |
|---|---|
| **Edit** | Edits the component in place: type in a title, format a text, crop an image |
| **Configure** | Opens the component's dialog, with all of its settings |
| **Styles** | Chooses among the looks the template allows for this component |
| **Select panel** | For tabs, accordions and carousels: shows another panel, or reorders the panels |
| **Copy**, **Cut**, **Paste**, **Delete** | Paste inserts before the selected component |
| **Insert component** | Adds a component here, from the ones allowed in this container |
| **Group** | Selects several components, so the next action applies to all. `Cmd`-click does the same |
| **Parent** | Selects the container around the component |
| **Layout** | Switches to Layout mode for this component |
| **Convert to experience fragment variant** | Moves the selection into a new [experience fragment](experience-fragments.md) and leaves a reference in its place |

Double-click a component to edit it in place. If it cannot be edited in place, its dialog opens.

### Text

Editing a text in place shows a small toolbar with the formats the site allows: bold, italic, lists, links, paragraph styles. Click **Save** in that toolbar, or simply click somewhere else on the page. In a title, `Enter` saves and `Esc` cancels.

### Images

Editing an image in place opens the image tools: **Crop**, freely or to one of the aspect ratios the site offers; **Rotate**; **Flip**; and the image map, where you draw rectangles, circles or polygons and give each a link. The original file is never changed: the crop is applied when the image is served.

### The dialog

**Configure** opens the component's dialog in a window that floats over the page. Drag it aside to see the page behind it, or to drag an image from the side panel into one of its fields. **Done** saves, **Cancel** discards.

## Add and move components

- **Add**: drag a component from the **Components** tab of the side panel to the place you want it. A line shows where it will land. Or select a component and choose **Insert component**.
- **Add an image**: drag it from the **Media** tab onto the page. It becomes the right component for its type: an image becomes an Image component.
- **Move**: drag a component to a new place on the page.

Every container ends with a slot that says **Drag components here**. A component that is still empty, such as an image without a file, is shown as a labelled box so you can select it and fill it in.

Some parts of a page cannot be selected: the ones its template keeps fixed, usually the header and the footer. They are the same on every page made from that template. To change them, use **Edit Template** in the page menu. See [Templates](templates.md).

### Tabs, accordions and carousels

These show one panel at a time. To work on another panel, select the container and choose **Select panel**: click a panel to show it, or drag one to change the order.

When you select a component that is inside a hidden panel, for instance from the Content Tree, the editor opens the panels around it for you.

## Undo and redo

`Cmd+Z` undoes the last change and `Shift+Cmd+Z` redoes it (`Ctrl+Z` and `Ctrl+Y` on Windows and Linux). The buttons in the top bar do the same. Changes to the page properties are not part of this history.

## Modes

The mode switch in the top bar changes what the editor is for.

| Mode | Use it to |
|---|---|
| **Edit** | Change content. The default |
| **Layout** | Set how wide each component is, and where it sits, for each screen size |
| **Personalize** | Show a different experience to each audience. See [Personalization](personalization.md) |
| **History** | See the page as it was at an earlier date |
| **Developer** | Inspect a component: its path, its type, its dialog and configuration, and any script errors on the page |
| **Preview** | Use the page as a visitor would. Links work, and nothing can be selected |

### Layout mode and the emulator

In **Layout** mode the page shows its columns. Drag the handles at the sides of a component to make it narrower or wider or to push it to the right. The toolbar adds **Float to new line**, **Hide component**, **Show hidden components** and **Revert breakpoint layout**.

A layout belongs to one screen size. The **emulator** button shows a ruler with devices: phones, tablets, desktop widths, and **Responsive** for any width you type. Choose a device to see the page at that width; what you then change in Layout mode applies to that size only.

### History mode

Pick a date and time, or click a saved version on the line: the page is shown as it was then. **Back to now** returns to today's page. **Create version** saves a version of the page as it is.

### Preview and the visitor simulator

In **Preview**, the **Visitor simulator** bar lets you look at the page as a particular visitor: a persona, a location, a device, a date and time. Personalized components show the experience that visitor would get.

## The page menu

The button at the left of the top bar opens the menu for the page as a whole.

| Item | What it does |
|---|---|
| **Open Properties** | The [page properties](page-properties.md) |
| **Lock Page**, **Unlock Page** | Keeps the page to yourself while you work |
| **Publish Page**, **Unpublish Page** | Publishes now or at a time you choose. See [Publishing](publishing.md) |
| **Edit Template** | Opens the page's template in the template editor |
| **View as Published** | The page without the editor, in the same tab or a new one |
| **Start Flow** | Starts a [flow](flows.md) for this page, such as a review |
| **Create Release** | Adds the page to a [release](releases.md). In a release, **Go live** puts it live |
| **Linked copy Status**, **Synchronize Page**, **Sync changes** | For pages that follow a source, or are one. See [Several sites](sync.md) |
| **Reload Page** | Loads the page again |
| **View in Admin** | Shows the page in the Pages console |

## Annotations

An annotation is a note on a component, for the people who work on the page. Visitors never see annotations, and they are never published.

- **Add one**: click **Add annotation** in the top bar. A note opens on the selected component. Type, choose a colour, click **Done**.
- **Read one**: each note shows its first words on the page. Hover to read all of it, with who wrote it and when.
- **Move one**: drag it by the dotted handle at its left. Dropped on another component, the note now belongs to that component.
- **Point at something**: hover the note, press the round arrow button and draw to where the arrow should point. A note can have several arrows. Click an arrow, then the cross on it, to remove it.
- **Change or delete one**: click the note.

The button beside **Add annotation** hides the notes and shows them again. Notes also appear in the page's **Timeline** in the Pages console, so a reviewer's comments are easy to find later.

## When a page cannot be edited

- **Locked by someone else**: the status bar says who. Ask them to unlock it.
- **In a flow**: the status bar shows the step and who it is assigned to. Depending on the flow, the page can still be edited.
- **A linked copy**: components that follow the source show a link icon. To change one here, choose **Cancel inheritance** in its toolbar; **Re-enable inheritance** gives it back to the source. See [Several sites](sync.md).
