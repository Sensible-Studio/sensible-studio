---
title: Flows and the Inbox
description: Flows in Sensible Studio move content through steps such as review and approval. The Inbox is where the steps waiting for you arrive.
order: 130
---

A flow is a sequence of steps that content goes through: someone reviews it, someone approves it, something happens to it. A flow makes sure the steps happen in order, that the right person is asked, and that there is a record of who decided what.

The tools are under **Home › System › Flows**.

![The Flows group of System](../images/flows-tools.webp)

| Tool | What it is for |
|---|---|
| **Flows** | The flow models: create and edit them, start them |
| **Instances** | The flows that are running, that failed and that ended |
| **Triggers** | Start a flow when content is created or changed |
| **Auto-Assign** | Start a flow on every page created from a template |
| **Inbox** | The steps waiting for you |

## Start a flow

Select pages in the Pages console, or files in Media, and choose **Create › Start Flow**. In the page editor, **Start Flow** is in the page menu.

![Create with a page selected](../images/flows-create-menu.webp)

![The flows to choose from](../images/flows-start-models.webp)

![Start Flow, filled in](../images/flows-start.webp)

| Field | Meaning |
|---|---|
| The path at the top | The content the flow is about |
| **Flow Model** | Which flow to run. Only models that were synced are offered |
| **Title** | A name for this run, so it is easy to recognise in an Inbox |
| **Comment** | A note for the first person |

Sensible Studio comes with three flows:

| Flow | What it does |
|---|---|
| **Request for Review** | A reviewer is asked to approve the content or to request changes, with a comment |
| **Translation Review** | Started by **Request review** on a translation: a translation reviewer approves it, which puts its texts into the translation memory, or asks for changes. See [Translations](translations.md) |
| **Translation Project Due** | Started by a translation project with a due date: a reminder for the project's owner, which ends by itself when the project is done |

Your administrators can build others.

While a page is in a flow, a band in the editor says so: which flow, which step it is at, and who has it. **View flow** opens the flow's history.

![A page in a flow, in the editor](../images/flows-editor.webp)

## The Inbox

The steps waiting for you arrive in your Inbox. The bell in the top bar shows how many there are; a click on it opens the Inbox, which is also at **Home › System › Flows › Inbox**.

![The Inbox with two steps](../images/flows-inbox.webp)

| Column | Meaning |
|---|---|
| **Step** | What is asked of you |
| **Flow** | The title of the run, and the comment it was started with |
| **Stage** | Where the flow stands, for flows that have stages: *Review (1 of 2)* |
| **Content** | What the flow is about. The link opens it |
| **Assignee** | The person or group the step is for. Any member of the group can do it |
| **Initiator** | Who started the flow |
| **Arrived**, **Due** | When the step reached the Inbox, and until when it should be done. A late step is marked |

Tick a step to act on it.

![A step selected](../images/flows-inbox-selected.webp)

| Action | What it does |
|---|---|
| **Complete** | Finishes the step and moves the flow on |
| **Delegate** | Hands the step to a colleague |
| **Open content** | Opens the page, fragment or file the step is about |
| **Flow History** | The run so far |

### Complete a step

![Complete step](../images/flows-complete.webp)

If the flow offers a choice, **Next Step** lists it: here *Approve* or *Request changes*. Add a **Comment** for the next person and choose **Complete**.

To complete many at once, tick them and choose **Complete**. The dialog offers the choices the selected steps have in common, and one comment for all of them.

### Delegate

![Delegate](../images/flows-delegate.webp)

The dialog says who the step is assigned to now. Type a name in **Delegate to** and pick the person or group; the step moves to their Inbox.

### Flow History

![The history of a flow](../images/flows-history.webp)

The top has the run's status, its model and version, its content, who started it and when, and the comment. The table lists each step: its status, who it was assigned to, who completed it, when it started and ended, and the comment left.

Tasks from [projects](projects.md), and reminders such as a translation project's due date, arrive in the same Inbox.

## Follow a flow

**On the content**: the inspector's **Timeline** tab lists each flow the page went through and each step someone completed, with their comment.

![The Timeline of a page that is in a flow](../images/flows-timeline.webp)

**All of them**: **Instances** lists the flows by state, in three tabs: **Running**, **Failures** and **Archive**.

![Instances: the running flows](../images/flows-instances.webp)

Each row has the run's title and comment, its status, the current step and who has it, the stage, the content, who started it and when, and the model with its version.

![A running flow selected](../images/flows-instances-selected.webp)

| Action | What it does |
|---|---|
| **Open History** | The run so far, as in the Inbox |
| **Suspend** | Pauses the flow: its step leaves the Inboxes until someone chooses **Resume** |
| **Terminate** | Ends the flow where it stands. The content is not changed |
| **Retry Step** (under Failures) | Runs the failed step again, after the cause was fixed |

## Build a flow

**Flows** lists the flow models.

![Flows: the three models that come with Sensible Studio](../images/flows-models.webp)

| Column | Meaning |
|---|---|
| **Title**, **Description** | What the flow is and does |
| **Details** | Its version |
| **Synced** | A tick when the version in use is the one you see |
| **Type** | *Default* for a model that comes with Sensible Studio |

Tick a model for **Edit** and **Start Flow**; **Create flow model** makes a new one, from a title.

![A model selected](../images/flows-models-selected.webp)

![Add Flow Model](../images/flows-model-create.webp)

### The flow editor

A model opens as a diagram, from **Flow Start** to **Flow End**.

![The model Request for Review](../images/flows-model-editor.webp)

A default model is shown read-only. **Edit** makes a copy of your own that replaces it; from then on the model opens for editing, with the steps to drag in on the left.

![The same model open for editing](../images/flows-model-edit.webp)

| Part | Meaning |
|---|---|
| The steps on the left | Drag one onto a place marked **Drag steps here**, or click the place to choose one |
| A step | Click it for its toolbar: **Configure** (the wrench) and **Delete**. A double-click opens its properties |
| **Model Properties** | The model's title and description, and its **stages**: the names shown to people for where a flow stands |
| **Start Flow** | Starts this model on content you choose |
| **Sync** | Puts your changes into use. Flows already running finish as they started |

| Step | What it does |
|---|---|
| **Participant** | Waits for a person or a group. One member of the group completing it is enough |
| **Dynamic participant** | Chooses who is asked while the flow runs: the person who started it, for example |
| **Process** | Does something by itself, such as setting a property on the content. Developers add processes of their own: publish the page, notify another system |
| **OR split** | Continues along one of several branches, by a rule or by the choice of the person before. In the diagram it is the *Decision* |
| **AND split** | Continues along several branches at once, and waits for all of them |
| **Goto** | Jumps to another step, to repeat a review until it is approved |

![A step selected in the editor](../images/flows-step-selected.webp)

![The properties of a participant step](../images/flows-step.webp)

A step's properties have a tab **Common**, the same for every step, and a tab for its kind: **User/Group** for a participant.

| Field (Common) | Meaning |
|---|---|
| **Title**, **Description** | What the Inbox shows for the step |
| **Workflow Stage** | The stage the step belongs to, from the stages in Model Properties |
| **Timeout** | How long the step may wait: off, immediate, or 1, 6, 12 or 24 hours |
| **Timeout Handler** | What happens then. **Auto Advancer** completes the overdue step along its default branch |

## Start flows by themselves

### Triggers

A trigger starts a flow when content changes. **Triggers** lists them; **Create trigger** adds one.

![Triggers](../images/flows-triggers.webp)

![Create Flow Trigger](../images/flows-trigger-create.webp)

| Field | Meaning |
|---|---|
| **Event Type** | What to watch for: content created, modified or removed |
| **Node Type** | The kind of content whose changes count. `cq:PageContent` is the content of a page |
| **Run Mode(s)** | Where the trigger works: on the author, on the live site, or both |
| **Path** | Where to watch, as a pattern the whole path must match: `/content/my-site(/.*)/jcr:content` |
| **Conditions** | Properties the content must have: `name==value`, `name!=value`, or `name!=` for "has a value". All must hold |
| **Flow Model** | The flow to start |
| **Description** | A note for administrators |
| **Activate** | Off, the trigger is kept but does nothing |
| **Exclude List** | Property names whose change alone does not start the flow, one per line |

A trigger does not start a second run of a flow for content that is already in one.

### Auto-Assign

**Auto-Assign** starts a flow on every page created from a given template below a given path. Use it when every new article must be reviewed, for example.

![Auto-Assign](../images/flows-autoassign.webp)

**Add** makes a rule from three things: the **Template**, the **Flow**, and the **Path** below which new pages count.

![Add rule](../images/flows-autoassign-add.webp)

## Good to know

- A step can be completed by the person it is assigned to, by any member of the group it is assigned to, or by an administrator.
- A step that fails stops its flow and is listed under **Failures** with the reason. An administrator can retry it.
- Flows that ended are kept for 30 days, then removed. Their trace on the content's **Timeline** stays.
