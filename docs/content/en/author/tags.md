---
title: Tags
description: Tags classify pages, media and content fragments in Sensible Studio. Create, organise, move and merge them in the Tags console.
order: 90
---

A tag is a word from a list your organisation agrees on: a topic, an activity, a season, a region. Tagging content with the same words everywhere is what lets a site show "more articles about surfing", lets an author find every winter picture, and lets a list on a page fill itself.

## How tags are organised

Tags are kept in a tree. At the top are **namespaces**, one per subject: *Activity*, *Season*, *Region*. Below each are its tags, and a tag can have tags below it: *Activity › Water sports › Surfing*.

Searching for a tag also finds what is tagged with the tags below it: a search for *Water sports* finds everything tagged *Surfing*.

## The Tags console

Open **Home › Tags**. Select a namespace or a tag to act on it.

| Action | What it does |
|---|---|
| **Create Tag** | A new tag below the selection, or a new namespace at the top. Give it a title and, if you like, a description |
| **Edit** | Changes the title and description. You can also give the title in other languages, so each language version of a site shows the tag in its own words |
| **Move** | Moves the tag elsewhere in the tree, or renames it. Content tagged with it stays tagged |
| **Merge** | Makes two tags one. Everything tagged with the first is from then on found under the second. Use it to clean up duplicates such as *Bike* and *Bicycle* |
| **Delete** | Removes the tag. Content that carried it no longer has it |

## Tag content

- **A page**: in its [properties](page-properties.md), under **Tags**.
- **Several pages**: select them, choose **Properties**, and set **Tags** for all.
- **A file in Media**: in its properties. Images can also get tags proposed for them: see [Auto tags](media-delivery.md).
- **A content fragment**: in its properties, and in any field of the type *Tags* its model has.

The tag field opens a picker where you browse the tree or type to search.

## Where tags show up

- **In searches**: the Filter rail of Pages and the filters of Media both narrow by tag.
- **On the site**: components that list pages or fragments can choose them by tag.
- **In personalization**: a visitor's interests can be gathered from the tags of the pages they read.

## Publish tags

Tags are content: a tag used on a published page must itself be on the live site, or the site cannot show its title. When you publish a page, the tags it uses that are not yet published are offered to be published with it.
