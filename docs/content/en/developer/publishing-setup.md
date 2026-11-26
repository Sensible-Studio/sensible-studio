---
title: Publishing targets
description: Set up publish instances, a preview and cache flushing in Sensible Studio: targets, queues, what travels between instances, and how to add a publish instance.
order: 60
---

Authors press **Publish**; this page is what happens behind it and how to set it up. The author's side is in [Publishing](../author/publishing.md).

## Targets

A **publishing target** is where the author delivers to: an address, credentials, and a queue. Targets are managed in **Home › System › Publishing › Publishing**, and stored under `/etc/replication/agents.author/<id>`.

A new author has one target, `publish`, pointing at `http://localhost:4503/bin/receive`.

| Setting | What it is |
|---|---|
| **Address** | The publish instance's `/bin/receive`. |
| **User and password** | The transport user on the publish instance. |
| **Tier** | *Publish*, or *Preview*. **Publish** never delivers to a preview target; the Publish dialog's **Preview** destination does. |
| **Public URL** | The address visitors (or reviewers) use, for the links that open a page there. |
| **Type** | *Content*, or *Dispatcher flush*: no content, only a request that tells a cache what changed. |
| **Enabled** | A disabled target takes no new requests. |

Each target card shows its state, queue length and last delivery, with **Test Connection**, **Retry now** and **Clear queue**.

## What happens on Publish

1. The page is checked against the user's permissions.
2. A version is saved.
3. The publishing state is written on the page (`cq:lastReplicated`, `cq:lastReplicatedBy`, `cq:lastReplicationAction`).
4. One job is queued per enabled target.

Delivery packs the page into a zip: its content as typed JSON, plus the binaries. Access control, author-only properties and annotations are left out. Child pages are included only when the author chose **Publish with the pages below**.

The publish instance installs it: missing parents are created, the page's content is replaced, child pages are kept, and the order among siblings is restored. Unpublishing removes the page there.

**The queue is ordered and retries forever**, a minute between attempts. A publish instance that is down loses nothing: start it and the backlog drains.

Scheduled requests (**Later**) are kept under `/var/studio/replication/scheduled` and fire at their time, with the page as it is then.

## Add a publish instance

On a developer's machine:

```
PUBLISH_HOME=instances/publish2 PUBLISH_PORT=4504 bin/studio-publish start
```

Then add a target on the author for `http://localhost:4504/bin/receive`. On a server, see [On a server](server.md).

With more than one publish instance:

- **Content** reaches each through its own target and queue.
- **Sign-ins of visitors** are valid on all of them: they share one signing key. `bin/studio-publish` gives every instance a copy.
- **Users, groups and what visitors create** on one instance travel to the others through the author, within about half a minute. Form submissions go to the author only.
- **A new instance starts empty.** Clone an existing publish instance's repository while it is stopped, or publish the site tree to the new target.

## A preview

A preview is a publish instance whose target has **Tier = Preview**.

```
PUBLISH_HOME=instances/preview PUBLISH_PORT=4505 bin/studio-publish start
```

Once such a target exists, the Publish dialog offers **Destination: Publish / Preview**. A page sent to the preview is delivered at once, with what it needs there, and its state is kept apart (`cq:lastReplicated_preview`), so the Pages console keeps showing the live state.

## Tell the cache

The flush comes from the **publish instance**, after the content has arrived there. A flush from the author could reach the cache before the publish instance has the new page.

Every publish instance has the target already: **Live cache**, type *Dispatcher flush*, address `http://127.0.0.1:4520/dispatcher/invalidate.cache`, disabled. To use it:

1. Sign in to the publish instance as an administrator and open its Publishing console (`http://localhost:4503/replication.html`).
2. Enable **Live cache**. **Test Connection** should answer *OK*.
3. On a server, set the cache's flush secret as the target's password. See [The Live cache](live-cache.md).

While the cache is down the publish instance keeps the request and retries.

The author's Publishing console shows each cache under its targets (what it holds, what it was last told, which publish instances it fetches from) with a **Clear cache** button for administrators.

## HTTPS between instances

A target's address may be `https://`. If the publish instance's certificate is from an authority of your own, add it in **Home › Users › Trust Store**; **Check an address** there shows what a server presents and whether it is trusted.

## Not there

- Reverse replication in AEM's own format. What visitors create is collected by the mechanism described above.
- Serialization types other than the content pack and the dispatcher flush.
