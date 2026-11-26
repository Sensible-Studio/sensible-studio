---
title: Templates
description: Templates in Sensible Studio decide what a page starts with, which parts are fixed, and which components authors may add where.
order: 40
---

A template decides what a page is before anyone writes on it: which parts are the same on every page, where authors may add components, which components, and how each of them is set up. Change a template, and every page made from it follows.

Templates belong to a site's configuration. Open **Home › Templates**, choose the configuration at the left, and you see its templates as a list or as cards, with their status and when each was last changed and published.

## What a template is made of

| Part | What it holds |
|---|---|
| **Structure** | The fixed parts of the page, such as the header and footer, and the containers where authors add content |
| **Initial Content** | What a new page starts with: components already in place, ready to be filled in |
| **Component configuration** | For each container: which components may be added. For each component: its options, such as the heading sizes a title may use, and its **Styles** |
| **Page configuration** | The client libraries (the site's CSS and JavaScript) and the options of the page itself |

## Create a template

1. In the Templates console, choose the configuration, then **Create**.
2. Choose a template type. A template type is a starting point provided by the site's developers.
3. Give the template a title and a description, then create it.
4. Open it to edit its structure.
5. When it is ready, **Enable** it. Only enabled templates are offered when someone creates a page.
6. **Publish** it. A page on the live site needs its template there too; publishing a template publishes the component configurations it uses with it.

## Edit a template

**Edit** opens the template in the editor you know from pages, with three modes:

- **Structure**: add the parts every page will have. A component you add here is fixed: authors see it on their pages and cannot change it. To let authors change one, select it and choose **Unlock structure component**. A container you unlock is where authors add their own components.
- **Initial Content**: add what a new page should start with. This is copied into each new page once; changing it later does not change existing pages.
- **Layout**: set how the structure behaves at each screen size.

The page menu of the template editor also has **Initial Page Properties**, for the properties new pages start with, and **Page Configuration**.

> **Note:** A change to the structure shows on existing pages at once, on the author instance. It shows on the live site when you publish the template.

### Choose what authors may add

Select a container in **Structure** mode and choose **Configuration**. Under **Allowed Components**, tick the groups or the single components that may be added to this container. Ticking a whole group also allows the components added to that group later.

The same dialog sets a component's own options. Two containers can have different configurations, so a sidebar can allow other components than the main area. A configuration has a name and can be shared between templates; changing it changes every place that uses it.

### Styles

A component's configuration can define **Styles**: named looks, such as "Dark" or "Wide", each backed by a CSS class the site's developers provide. Authors then choose among them with the **Styles** button of the component's toolbar, without touching code.

## Actions on a template

| Action | What it does |
|---|---|
| **Edit** | Opens the template editor |
| **Properties** | Title, description and thumbnail. **Generate Preview** makes the thumbnail from the structure |
| **Enable**, **Disable** | Offers the template when a page is created, or stops offering it. Existing pages keep working |
| **Publish**, **Unpublish** | Puts the template and its configurations on the live site, or takes them off |
| **Copy**, then **Paste** | Makes a new template from this one |
| **Delete** | Refused while pages still use the template; the pages are listed |

## Which templates are offered where

When someone creates a page, they see the enabled templates that are allowed at that place. **Allowed Templates**, in the [properties](page-properties.md) of a page, decides this for the pages below it. It is usually set once, on a site's top page.

## If a component is not offered

When a component you expect is missing from the editor's **Components** tab:

1. Reload the editor. The list of components is read when the editor opens.
2. Open the template and check the container's **Allowed Components**.
3. Ask a developer to check that the component has a group of its own. A component without a group, or in a hidden group, is never offered. See [Components and templates](../developer/components.md).
