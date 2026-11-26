---
title: Forms
description: Build forms from components in Sensible Studio, with rules and steps, and collect, read and export what visitors submit.
order: 170
---

A form in Sensible Studio is a page made of form components. You build it in the page editor, publish it like a page, and read what visitors send in a table beside it.

Because a form is a page, everything pages have works for forms: templates, versions, publishing, translation, releases.

## Create a form

1. Open **Home › Forms** and go to the folder of your site.
2. Choose **Create › Form**, and give it a title.
3. Open it. A new form starts with a name, an email address, a message and a **Send** button.

## Build it

In the editor, the **Components** tab offers the form components:

| Component | For |
|---|---|
| **Text field**, **Text area** | A short answer; a longer one |
| **Email**, **Number**, **Date** | Checked as the visitor types |
| **Dropdown**, **Radio group** | One choice from several |
| **Checkbox**, **Checkbox group** | Yes or no; several choices |
| **File upload** | Documents or images, with the kinds and sizes you allow |
| **Hidden** | A value sent along that the visitor does not see, such as the campaign |
| **Text** | An explanation between fields |
| **Panel** | A group of fields. In a form with steps, each panel is a step |
| **Submit** | The button that sends the form |

Open a field's dialog to set it up:

- its **name**: the heading of its column in the submissions;
- its **label**, a **placeholder** and a line of help;
- whether it is **required**;
- what is accepted: a shortest and longest length, a lowest and highest number, an earliest and latest date, a pattern.

A field that is filled in wrongly is pointed out to the visitor before the form is sent, and checked again on the server.

### Rules

A rule makes a field react to an answer. In a field's dialog, on the **Rules** tab:

> **When** *country* **is** *Other* **then show** this field.

A rule can show or hide the field, enable or disable it, or make it required. Hidden fields are not required and are not sent.

### Steps

Switch on **multi-step** in the form's own dialog, and each panel becomes a step, with **Next** and **Back** and a progress bar. Each step is checked before the visitor moves on.

### Try it

Switch the editor to **Preview** and fill in the form. Rules, steps and checks work as on the live site. A form sent from the preview is checked but not stored, and the confirmation says so.

## After a visitor sends the form

On the **Actions** tab of the form's dialog, choose what happens next:

- **Show a message**, or **go to a page**, to thank the visitor.
- **Send an email** to an address, with the answers. An administrator sets up the mail server under **Home › Forms › Mail**.
- **Start a flow** with the submission, so someone handles it from their [Inbox](flows.md).
- **Call a web address** with the answers, to pass them to another system.
- **Create a content fragment** from each submission, with the form's fields mapped to the fields of a model.

## Read what was sent

Select the form in the Forms console and choose **Submissions** (or press `s`). The console also shows, on each form, how many submissions are new.

The table has one column per field and one row per submission, newest first. You can:

- filter by any answer;
- open a submission to see everything in it, including the files that were uploaded and what happened afterwards (the email, the flow, the call);
- **export** the table as CSV or JSON;
- delete submissions.

Submissions are sent on the live site and arrive on the author instance within a moment. They are never kept on the live site.

## Publish

Publish the form like a page. Use the form on a site page by linking to it, or let your developers embed it in the site's templates.

## Personal data

A form collects what you ask for, and what you collect is your responsibility. Ask only for what you need, say on the form what it is used for, and delete submissions when you no longer need them. Exports are files outside Sensible Studio: handle them with the same care.

## Protection against misuse

Each form has a hidden field that people never fill in and automated senders do; submissions that fill it are dropped. Submissions from one address are limited to one every two seconds, and a submission may be at most 256 KB, files aside.
