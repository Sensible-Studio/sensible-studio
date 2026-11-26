---
title: Components and templates
description: Write components for Sensible Studio with HTL and Sling Models, give them dialogs, and control where authors may use them with editable templates and policies.
order: 40
---

Components and templates are built the way they are in AEM, with the same files in the same places. This page is the short version, with what is particular to Sensible Studio.

## A component

A component is a folder under `/apps/<project>/components` with a script and, usually, a dialog.

```
/apps/mysite/components/quote
├── .content.xml          jcr:primaryType="cq:Component", jcr:title, componentGroup
├── quote.html            the HTL script
├── _cq_dialog/           what the author fills in
└── _cq_editConfig.xml    optional: in-place editing, drop targets
```

```
<blockquote class="quote" data-sly-use.model="com.example.mysite.models.Quote">
    <p>${model.text}</p>
    <cite data-sly-test="${model.author}">${model.author}</cite>
</blockquote>
```

`componentGroup` is the group it appears under in the editor's **Components** tab, and what a template's policy allows by (`group:My Site`).

**Reuse before writing.** Set `sling:resourceSuperType` to a Core Component (`core/wcm/components/title/v3/title`) and you inherit its script, model and dialog; override only what differs. The Core Components are installed with the product.

## The dialog

The dialog is a tree of Granite UI field definitions under `cq:dialog`. Sensible Studio does not run Granite: it reads the definitions and draws the dialog itself. The common field types are supported: text field and area, rich text, path and tag pickers, select, checkbox, radio, number, date, colour, file upload, multifield (simple and composite), tabs, fieldsets, and show/hide by another field's value.

What follows from "read, not run":

- A field's `granite:data`, `validation` and datasource are honoured where they are declarative. The datasources of the Core Components are built in.
- Client-side JavaScript in a `cq.authoring.dialog` client library does not run. Use the declarative show/hide where you can, and check such dialogs after installing the project.

**Home › System › Developer › Components** lists every component with its group, properties, policies, the templates that use each policy, where it is used on live pages, and its README.

## In-place editing

`cq:editConfig` with `cq:inplaceEditing` makes a component editable on the page: `text` for rich text, `title` for a single line, `image` for crop and rotate. Authors double-click; without it, the dialog opens.

`cq:dropTargets` lets authors drag media onto the component.

## Templates

A template says what a new page starts with and what authors may do to it. Templates are edited in the interface (**Home › Templates**), not in code, and stored under `/conf/<site>/settings/wcm/templates`.

| Part | What it is |
|---|---|
| **Structure** | The components every page of the template has: header, footer, the layout containers. Fixed, unless unlocked. |
| **Initial content** | What a new page starts with and the author may change. |
| **Policies** | Per container: which components or groups are allowed. Per component: its design settings and styles. Stored under `/conf/<site>/settings/wcm/policies`. |
| **Template type** | What a new template starts from. Shipped in code under `/conf/<site>/settings/wcm/template-types`. |

A template must be **enabled** before authors can use it, and **published** before its pages render on the live site. Where a template may be used is set by `allowedPaths` on the template, or `cq:allowedTemplates` on a page.

The author's side of this is in [Templates](../author/templates.md).

## Styles

The Style System lets authors choose a look without a dialog field. In a component's policy, define style groups and styles; each style is a CSS class added to the component's wrapper. Your client library gives the class its meaning.

## Client libraries

CSS and JavaScript are client libraries: a `cq:ClientLibraryFolder` with `categories`, a `css.txt` and a `js.txt`.

```
/apps/mysite/clientlibs/site
├── .content.xml     categories="[mysite.site]", allowProxy="{Boolean}true"
├── css.txt
├── js.txt
├── css/…
└── js/…
```

A page loads them by category, through the page policy (the **Client libraries** field) or in the page component's `customheaderlibs.html`. With `allowProxy` they are served from `/etc.clientlibs/mysite/clientlibs/site.css`, the only place the Live cache lets visitors read code from.

The address carries a version stamp (`site.css?v=…`). The Live cache keeps the file once whatever the stamp, so **clear the cache after deploying code**: `bin/studio-live clear`.

## Deploy

Build the project's packages with Maven as usual, then put them in the install folder or upload them in **Developer › Packages**. See [Run an existing AEM project](aem-projects.md) for the two rules: a new version every build, and the publish instances need the code as well.

## When a page fails

A broken component makes the whole page answer with *Failed to include script body.html (500)*. `body.html` is only the first script in the chain; the cause is at the bottom of the *Caused by* list. For example

```
body.html → root → container → image-list.html:
com.adobe.aem.guides.wknd.core.models.ImageList cannot be resolved to a type
```

says the Image List component's model class is missing: its bundle is not active. **Web Console › Bundles** says why.
