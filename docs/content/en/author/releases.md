---
title: Releases
description: Prepare a future version of pages apart from the live site in Sensible Studio, preview it, and put it live on a date.
order: 110
---

A release is a set of pages prepared for a date: a campaign, a seasonal change, a relaunch. You work on a copy of the pages, kept aside, while the pages visitors see stay as they are. When the day comes, the release **goes live**: its content takes the place of today's.

## Create a release

From the Pages console, select the page or pages, then choose **Create › Release**. Or, in the page editor, open the page menu and choose **Create Release**.

| Field | What it is for |
|---|---|
| **Release title** | A name people will recognise: "Spring campaign" |
| **Live date** | When the release is meant to go live |
| The pages below | Whether the release takes the whole section, or only the selected page |
| Following the source | Whether changes made to today's page keep arriving in the release, until someone changes that page in the release |
| **Production ready** | The release goes live and is published by itself at the live date |

With several pages selected, they all go into the same release.

## Work in a release

A page in a release opens in the normal page editor. The status bar tells you which release you are in, when it goes live, and offers **Open source page** to see today's version.

Everything works as usual: components, properties, annotations, flows for review. Nothing you do there shows on the live site.

To find a page's releases, select the page in the Pages console and open the inspector's **Timeline** or **References** tab. Each release is listed with its date, and a click opens the page as it is in that release.

## Preview the future

There are two ways to see the site as it will be:

- In a release page's status bar, choose **Preview on the site at the live date**. The page opens as a visitor will see it once the release is live.
- In the editor's **History** mode on today's page, the line shows the page's releases after today. Click one to see the page as of that date.

## Go live

Open **Home › Publishing › Releases**, select the release, and choose **Go live**. Or choose **Go live** in the page menu of a page in the release.

You decide:

- **what** goes live: the whole release, this page, or this page and the pages below;
- whether only the pages that were actually changed in the release go live, which is usually what you want;
- whether to **publish the pages** right after, so the live site has them at once;
- whether to delete the release afterwards.

Before a page is replaced, a version of it is saved, so you can go back. A page that exists only in the release is created in the right place.

### By itself, at the live date

Tick **Production ready** on a release that has a live date, when you create it or later with the clock switch in the Releases console. At that date the release goes live and its pages are published, without anyone having to be there. The console shows "Goes live by itself" with the date.

If something stops it, the console shows the reason and the release waits; fix the cause and switch **Production ready** on again.

> **Note:** Without **Production ready**, a live date is only information. Nothing happens by itself.

## The Releases console

**Home › Publishing › Releases** lists every release with its source pages, its live date, how many pages it holds, who created it, and whether it has gone live. From here you create, edit, put live and delete releases.

## Good to know

- A release is not published. You put it live, and the pages are published then.
- Two releases can hold the same page. The one that goes live last wins; look at the page's **References** before you start a second one.
- Deleting a release deletes its copies of the pages. Today's pages are not touched.
