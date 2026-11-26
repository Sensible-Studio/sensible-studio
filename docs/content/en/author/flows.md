---
title: Flows and the Inbox
description: Flows in Sensible Studio move content through steps such as review and approval. The Inbox is where the steps waiting for you arrive.
order: 130
---

A flow is a sequence of steps that content goes through: someone reviews it, someone approves it, something happens to it. A flow makes sure the steps happen in order, that the right person is asked, and that there is a record of who decided what.

## Start a flow

Select pages in the Pages console, or files in Media, and choose **Create › Start Flow**. In the page editor, **Start Flow** is in the page menu.

Choose the flow, give this run a title so it is easy to recognise in an Inbox, add a comment for the first person, and start it.

Sensible Studio comes with **Request for Review**: a reviewer is asked to approve the content or to request changes, with a comment. Your administrators can build others.

While a page is in a flow, the editor's status bar says so: which step it is at, and who has it.

## The Inbox

The steps waiting for you arrive in your Inbox. The bell in the top bar shows how many there are, and the sidebar lists them. **Home › Flows › Inbox** shows them as a table, with the content each is about, its stage, and its due date. A step that is late is marked.

Open a step to see the content and the comments so far. Then:

- **Complete** it. If the flow offers a choice, such as *Approve* or *Request changes*, choose one. Add a comment for the next person.
- **Delegate** it to a colleague, when it is not yours to decide.

To complete many at once, select them and choose **Complete**. The dialog offers the choices the selected steps have in common, and one comment for all of them.

Tasks from [projects](projects.md), and reminders such as a translation project's due date, arrive in the same Inbox.

## Follow a flow

- **On the content**: the inspector's **Timeline** tab lists each flow the page went through and each step someone completed, with their comment.
- **All of them**: **Home › Flows › Instances** lists the flows that are running, the ones that failed and the ones that ended, each with its stage, such as "Approval (2 of 3)". An administrator can suspend, resume, or end a flow from there.

## Build a flow

Open **Home › Flows › Flows**, then create a flow or open one. A flow is built in an editor like the page editor: drag steps in from the side, put them in order, and open each to set it up.

| Step | What it does |
|---|---|
| **Participant** | Waits for a person or a group. One member of the group completing it is enough |
| **Dynamic participant** | Chooses who is asked while the flow runs: the person who started it, for example |
| **Process** | Does something by itself, such as setting a property on the content. Developers add processes of their own: publish the page, notify another system |
| **OR split** | Continues along one of several branches, by a rule or by the choice of the person before |
| **AND split** | Continues along several branches at once, and waits for all of them |
| **Goto** | Jumps to another step, to repeat a review until it is approved |

Each step can have:

- a **stage**: the name shown to people for where the flow stands, such as *Review* or *Approval*;
- a **timeout**: how long the step may wait, and what happens then. The flow can move on by itself along its default branch.

When you change a flow, **sync** it to put the change into use. Flows already running finish as they started.

## Start flows by themselves

### Triggers

A trigger starts a flow when content changes: when a page is created below a path, when a file is uploaded to a folder, when a property is set. Set them up under **Home › Flows › Triggers**: where to watch, what to watch for, and the flow to start.

A trigger does not start a second run of a flow for content that is already in one.

### Auto-Assign

**Home › Flows › Auto-Assign** starts a flow on every page created from a given template below a given path. Use it when every new article must be reviewed, for example.

## Good to know

- A step can be completed by the person it is assigned to, by any member of the group it is assigned to, or by an administrator.
- A step that fails stops its flow and is listed under **Instances** with the reason. An administrator can retry it.
- Flows that ended are kept for 30 days, then removed. Their trace on the content's **Timeline** stays.
