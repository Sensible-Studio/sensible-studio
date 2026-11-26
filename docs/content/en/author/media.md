---
title: Media
description: Upload, organise, describe, find and share images, video and documents in the Media console of Sensible Studio.
order: 50
---

Media is where the files of your sites live: images, video, audio, documents. Everything a page shows comes from here, and everything here is processed, searchable and ready to deliver.

Open it from **Home › Media**.

## Browse

Folders and files are shown as **cards**, as a **list** or in **columns**. Sort by name, title, date, size or type. The console remembers the view and the order you chose.

Select files with the checkbox on each. The bar then shows what you can do with the selection; right-click shows the same as a menu. `Esc` clears the selection.

You see only the actions you are allowed to perform in that folder. A folder you may only read says so, instead of inviting you to drop files into it.

## Upload

Drop files anywhere on a folder, or choose **Create › Files**. A panel shows the progress.

When a file of the same name is already there, you decide for each file:

| Choice | Result |
|---|---|
| **Keep both** | The new file gets a numbered name |
| **Replace** | The new file takes the place of the old one |
| **Create version** | The old file becomes a version of the new one, so you can go back |
| **Skip** | Nothing is uploaded |

The console also tells you when the same file, under any name, is already somewhere in Media, and where.

After the upload, each file is processed in the background: thumbnails and web sizes are made, its metadata is read, and the text inside documents is taken out for the search. See [Images and delivery](media-delivery.md).

## Organise

| Action | What it does |
|---|---|
| **Create › Folder** | A new folder. Its name in the address is made from its title |
| **Move** | Moves or renames. The pages that use the files are listed, and their references are rewritten so they keep working |
| **Copy**, then **Paste** | A copy in another folder |
| **Delete** | Warns when pages still use the files, and lists those pages |
| **Download** | One file downloads as it is. Several files or folders download as a ZIP, with their renditions if you want |
| **Extract Archive** | Unpacks a ZIP into a new folder or beside the archive |
| **Relate** | Links files to each other, such as a photo and the release form signed for it |

### Folder settings

**Properties** on a folder sets what applies to every file in it and in the folders below:

- the folder's title and the thumbnail on its card;
- the **processing profile**: extra renditions to make;
- the **image profile**: the focus crops to make;
- whether **Auto tags** are proposed for its images;
- the **metadata schema**: which fields the Properties form of its files shows;
- a **metadata profile**: values new files start with, such as a copyright line.

## A file's page

Double-click a file to open it. You see a preview (a player for video and audio), the actions, and panels at the side:

- **Renditions**: every size of the file, each with a download. For images, also the focus crops and the delivery addresses, ready to copy.
- **Timeline**: its versions.
- **Related**: the files linked to it.
- **References**: the pages that use it.

`p` opens the properties, `e` the image editor, and the arrow keys move to the previous and next file.

## Describe: properties and metadata

**Properties** (`p`) holds what is known about a file: title, description, keywords, creator, copyright, language, tags. Much of it is read from the file itself when it is uploaded: the size of an image, camera data, dates, the page count and author of a PDF.

Select several files and choose **Properties** to change them together. Fields whose values differ say **Mixed values**; only what you change is written.

An administrator can design the form: which tabs and fields it has, which are required, which are dropdowns. That is a **metadata schema**, set per folder. See **Home › Media › Metadata Schemas**.

### Edit metadata in a spreadsheet

**Export metadata** on a folder gives you a CSV file with one row per file and one column per property. Edit it in a spreadsheet, then **Import metadata**. The result lists what was written and which rows failed.

### Versions

The **Timeline** of a file lists its versions. **Save as Version** takes a label and a comment. **Revert** goes back to a version; the current state is saved as a version first. A version is also made before the image editor changes a file, and when you choose **Create version** at upload.

## Work together

- **Checkout** keeps a file to yourself while you work on it: nobody else can change, move, replace or delete it until you **Check In**. An administrator can check in for someone who is away.
- **Start Flow** sends files through a [flow](flows.md), such as an approval.
- **Translation Project** sends their metadata, or the text of documents, to be [translated](translations.md).

## Find

The search field at the top of a folder searches that folder and everything below it: names, titles, descriptions, keywords, tags, every metadata value, the tags proposed by the image model, and the text inside PDFs and office documents.

**Filters** narrows the results by type, tag, date, size, orientation and colour. An administrator can change which filters are offered (**Home › Media › Search Forms**).

A search has an address, so you can bookmark it, or save it as a **smart collection**.

## Collections

A collection gathers files from anywhere in Media without moving or copying them: the pictures of a campaign, a shortlist for a page.

- **Add to Collection** from any selection.
- A collection is private to you, or public.
- A **smart collection** is a saved search. It stays up to date as files change.

Find them under **Home › Media › Collections**.

## Share with people outside

**Share Link** makes an address that anyone can open, without an account, to view and download the files you selected. You choose how long it stays valid, from 1 to 90 days. **Home › Media › Shared Links** lists the links you made, what each gives access to and until when, and lets you **Unshare** one at any time.

## Publish

Files are published like pages: select them and choose **Publish**. When you publish a page, the files it uses that are not yet on the live site are offered to be published with it. See [Publishing](publishing.md).

## Reports

**Home › Media › Reports** makes a table over a folder and a period, which you can download as CSV: what was uploaded, modified or published, what is about to expire, how much space each folder takes, which links are shared.
