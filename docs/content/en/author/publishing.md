---
title: Publishing
description: Publish and unpublish pages, media and fragments in Sensible Studio: now or later, to the live site or a preview, with what a page needs along with it.
order: 150
---

You write on the **author** instance. Visitors read the **live** site. Publishing is what carries a page from one to the other. Until you publish, nothing you do is seen by visitors.

## Publish a page

Select one or more pages in the Pages console and choose **Publish**. Or, in the page editor, open the page menu and choose **Publish Page**.

The dialog asks three things.

### When

- **Now**: the page is sent at once.
- **Later**: at a date and time you choose. The page is published as it is *at that moment*, not as it is now, so you can keep working on it. Until then, the page menu says that publishing is scheduled, and the request can be cancelled from the Publishing console.

### Where

- **Publish**: the live site.
- **Preview**: a separate copy of the site for people to look at before something goes live. A page sent to the preview is not on the live site, and its publishing state is kept apart. This choice is offered when a preview has been set up.

### What comes with it

A page is rarely complete by itself. The dialog lists what the page uses that is not yet published, each with a checkbox:

- images and other media;
- experience fragments and content fragments;
- its template and component configurations;
- tags;
- pages it links to;
- the audiences its personalized components use.

Leave them ticked unless you know why not: a published page that shows an unpublished image shows a gap.

**Publish with the pages below** publishes a whole section. Parents are sent before their children.

## Unpublish

**Unpublish** takes the page off the live site. It stays on the author instance, untouched, and can be published again. Like publishing, it can be done **Later**.

Unpublishing a page also takes away the pages below it on the live site.

## What the dots mean

Each page in the Pages console shows its state:

| State | Meaning |
|---|---|
| **Published** | The live site has the page as it is now |
| **Modified** | The page was changed since it was published. Visitors still see the older one |
| **Not published** | The page has never been published, or was unpublished |
| **Pending** | A request is waiting to be delivered |

The inspector's **Properties** and **Timeline** tabs say when the page was last published, and by whom.

## Everything that waits to be published

**Home › Publishing** has an **Unpublished changes** list: every page below a path that was changed since it was last published, or never published. Tick what should go out and publish it in one step. Look here before a release, or at the end of a day.

## When it does not arrive

Publishing is a queue. If the live site cannot be reached, the request waits and is tried again every minute; nothing is lost. The Publishing console shows, for each target, its state, how many requests are waiting, and the reason the last one failed.

If a page you published does not show on the live site:

1. Look at the page's state. **Pending** means it is still in the queue.
2. Open **Home › Publishing**. A target that cannot be reached says why.
3. If it was delivered and the site still shows the old page, the cache has not been refreshed. See the next section.

Tell your administrator; these are things for them to fix. See [Publishing targets](../developer/publishing-setup.md).

## The cache in front of the live site

Most visitors do not reach the publish instance at all. A cache in front of it keeps a copy of each page and answers from that copy, which is what makes the site fast.

When you publish, the cache is told, and it drops what the publication changed:

- the page itself;
- the pages that show it: a page that lists it, a teaser that links to it, the navigation it is in;
- when an image, a fragment or a template is published, the pages that use it.

Other pages stay in the cache. The next visitor of a page that was dropped gets the new version.

The Publishing console shows the cache: what it holds, what it was last told, and a **Test a URL** tool that says what the cache does with an address and why. **Clear cache** empties it; use it only after a change to the site's code, never as a routine. See [The Live cache](../developer/live-cache.md).

## Who may publish

Publishing is a right of its own. An author may be allowed to change pages and not to publish them. In that case **Publish** is not offered, and the usual way forward is a [flow](flows.md): you ask for a review, and the reviewer publishes.

## Things that are published separately

| What | How it reaches the live site |
|---|---|
| **Media** | **Publish** in the Media console, or along with a page that uses it |
| **Content fragments** and their **models** | **Publish** in their consoles. An application sees a fragment only once it is published |
| **Experience fragments** | Like pages |
| **Templates** | **Publish** in the Templates console, with their component configurations |
| **Tags** | Along with the content that uses them |
| **Users and groups** who sign in on the site | **Publish** in the Users and Groups consoles |
| **Forms** | Like pages. What visitors submit travels back to the author instance by itself |
