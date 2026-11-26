---
title: Users and permissions
description: Create users and groups in Sensible Studio, decide who may read, change and publish what, and see who did what.
order: 180
---

This page is for the people who administer Sensible Studio. It explains users, groups and permissions, and where to see what happened.

Everything here is under **Home › Users**.

## Users

A user is a person who signs in, or a member of the public who signs in on the live site.

- **Create** a user with an ID (what they sign in with) and a password. Add their name and email in the profile.
- **Disable** a user who has left. They can no longer sign in; what they did stays attributed to them.
- **Change a password** from the user's page.

## Groups

A group is a set of users, and of other groups. Give permissions to groups, never to single users: when someone changes role, you move them between groups and everything follows.

A workable start:

| Group | For |
|---|---|
| Authors of a site | May read and change that site's pages and media |
| Publishers of a site | Authors who may also publish |
| Reviewers | Receive the review steps of [flows](flows.md) |
| Administrators | May do everything. Keep this group small |

## Permissions

A permission says what a user or a group may do at a place in the content tree, and below it.

| Permission | Allows |
|---|---|
| **Read** | See the page or file |
| **Modify** | Change it |
| **Create** | Add pages or files below it |
| **Delete** | Remove it |
| **Publish** | Publish and unpublish it |

**Home › Users › Permissions** shows the rules of a user or group, and lets you add and remove them. You can also set them where the content is: the **Permissions** tab of a page's [properties](page-properties.md).

A rule either allows or denies. Rules set further down the tree override rules set above, and a deny for a user overrides an allow for one of their groups. When the outcome is not obvious, **Effective Permissions** on a page tells you what someone can do there and which rule decides.

The interface follows the permissions: an action a person may not perform is not offered to them.

## Pages for members only

To limit a section of the live site to certain visitors, open the properties of its top page, go to **Permissions**, and choose **Edit Closed User Group**. Name the groups that may see it. Everyone else gets "not found" on the live site, until they sign in as a member.

The members must exist on the live site: see the next section.

## Users of the live site

Users are kept per instance. A person who signs in on the live site must exist there.

- **Created on the author instance**: select the users or groups and choose **Publish**. **Unpublish** removes them from the live site.
- **Registered on the live site**: a visitor who signs up, or changes their profile, on one publish instance is copied to the others by themselves, within about half a minute.

Keep each group in one place. A group you manage on the author instance and publish replaces its members on the live site every time it is published. For visitors who sign up on the site, use a group that exists only there, and make it a member of the group that grants access.

## Applications: OAuth

An application that reads or writes content does so as a user, with a token instead of a password. **Home › Users › OAuth** lists the applications you registered, what each may do (**Read** or **Write**), and the tokens in use, each of which you can **Revoke**. See [APIs](../developer/apis.md).

## Who did what

**Home › Users › Activity** lists what happened in Sensible Studio: who created, changed, published and removed what, and when. 

For one page, the **Timeline** tab of the inspector in the Pages console shows the same, limited to that page.
