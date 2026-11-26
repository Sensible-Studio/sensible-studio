---
title: Content Fragments
description: Structured content in Sensible Studio: models, content fragments and their variants, and how pages and applications use them.
order: 70
---

A content fragment is content without a page: a product, an article, a person, an opening hour. You write it once, in a form, and it is used wherever it is needed: on several pages, in an app, on a screen in a shop.

The form comes from a **model**. A model says which fields a kind of content has. Every fragment made from the model has those fields.

## Models

Open **Home › Content Fragment Models**. Models belong to a site's configuration; choose it, then **Create model**.

In the model editor you add fields from the **Data types** list and set each one up.

| Data type | For |
|---|---|
| **Single line text**, **Multi line text** | A name, a summary, a body. A multi line text can be rich text, Markdown or plain |
| **Number**, **Boolean**, **Date and time** | Prices and quantities, yes or no, dates |
| **Enumeration** | One choice from a list you define |
| **Tags** | [Tags](tags.md) from the site's vocabulary |
| **Content reference** | A page or a file from Media, such as a picture |
| **Fragment reference** | Another content fragment: the author of an article, the related products |
| **JSON** | Data in a form an application reads |

For each field you set a **Label**, the **Property name** applications will use, a **Description** and **Placeholder** to help authors, whether it is required, whether it holds several values, a **Default value**, and rules such as a **Maximum length** or a **Validation pattern** with the **Error message** to show.

> **Warning:** Changing a model does not rewrite the fragments already made from it. Removing or renaming a field leaves the old values in existing fragments, unused. The editor tells you when a model is in use.

A model can be enabled or disabled. Only enabled models are offered when someone creates a fragment. A folder's **Allowed models** can narrow the choice further.

## Create a content fragment

1. Open **Home › Content Fragments** and go to the folder for it. Content fragments live in Media's folders.
2. Choose **Create content fragment**.
3. Choose the model, give the fragment a title, and create it.
4. Fill in the form and **Save**.

The editor checks the fields as you save: a required field left empty or a value that breaks a rule is pointed out, and nothing is saved until it is fixed.

## The content fragment editor

- **In the middle**: the form the model defines. Rich text fields have a toolbar and can be switched between **Rich text**, **Markdown** and **Plain text**.
- **At the left**: the **Variants** of the fragment.
- **At the right**, a panel with:
  - **Properties**: title, description and tags;
  - **References**: **Used in**, the pages and fragments that show this one, and **Uses**, what this one points at;
  - **Versions**: create a version with a label and a comment, **Compare with current**, **Restore**;
  - **Languages**: the fragment in other languages.

### Variants

A variant is another wording of the same fragment for another use: a short version for a teaser, a version for the newsletter. **Create variant** copies the main one; you then change what should differ. A page or an application asks for a variant by name and gets the main one when that variant does not exist.

## Use a fragment on a page

Drag the **Content Fragment** component onto a page and choose the fragment. Choose whether to show one field or several, and which variant. The **Content Fragment List** component shows every fragment of a model, optionally filtered by tags and sorted by a field.

When the fragment changes and is published, every page that shows it shows the new content.

## Use fragments in an application

Every model is available over GraphQL: an application asks for exactly the fields it needs and gets JSON back. Fragment references are followed, so an article arrives with its author in one request.

A developer writes the query once and saves it under a name as a **persisted query**; applications then call it by that name. Try queries in **Home › Developer › GraphQL Query Editor**. See [APIs](../developer/apis.md).

## Publish, translate, move

In the Content Fragments console, a selection offers **Open**, **Properties**, **Publish**, **Unpublish**, **Translate**, **Copy**, **Move**, **Download** and **Delete**.

- **Publish** puts the fragment on the live site. Publish its model too, the first time: the live site needs the model to answer queries.
- **Translate** makes the fragment in another language, beside the original or in the language's own folder. See [Translations](translations.md).
- Before you **Delete** or **Move** a fragment, look at **Used in**: pages that show it will show nothing in its place.
