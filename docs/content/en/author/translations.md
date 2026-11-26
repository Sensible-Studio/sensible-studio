---
title: Translations
description: Translate pages, fragments and media in Sensible Studio: language versions, providers, the translation memory, review, translation projects and XLIFF.
order: 120
---

Sensible Studio translates content into **language versions**: the same page, fragment or file in another language, kept beside the original. A **provider** does the translating, usually a language model. People review and approve the result, and every approved sentence is remembered so it is never translated twice.

## How languages are laid out

A site keeps each language in a folder named with its code. The folder is the **language root**:

```
/content/weekender/language-masters/en     English
/content/weekender/language-masters/fr     French
/content/weekender/language-masters/ro     Romanian
```

A page has the same place in every language: `…/en/magazine/ski-touring` is `…/ro/magazine/ski-touring` in Romanian. Media and content fragments follow the same idea in their own folders.

## Translate pages

1. In the Pages console, select the page, then choose **Language versions**.
2. The dialog shows the page's language and every other language with its state: not created, to review, outdated because the original changed, or approved.
3. Choose the languages. To add a language the site does not have yet, type its code; its language root is created.
4. Choose the provider, and what to include:
   - the pages below;
   - what the pages use: experience fragments, content fragments and media. This is on by default.
5. Choose **Translate**. The progress shows each page as it is done.

### What is translated

Text is translated: titles, descriptions, body text, the alternative text of images, link and button labels, the titles of tabs and accordions, form labels and messages. Addresses, numbers and settings are left as they are. Formatting inside a text, such as bold words and links, is kept.

Links are adjusted. A link to another page of the original language leads, in the new version, to that page in the new language, if it exists or is being translated in the same run. The same goes for fragments and media.

### When the original changes

Translate the page again. Only the sentences that changed are sent to the provider; components added to the original are added to the version at the same place; what a person changed by hand in the version is kept. A version of the page is saved first, so you can go back.

A language version shows as **Outdated** from the moment its original changes.

## Translate content fragments and media

- **Content fragments**: select them in the Content Fragments console and choose **Translate**, or open the fragment and use its **Languages** panel. The fields marked as translatable in the model are translated.
- **Media**: a file's title, description and alternative text are translated. The file itself is shared between languages; it is not copied.

Both are translated by themselves when a page that uses them is translated with **what the pages use** ticked.

## The translation memory

Every sentence translated into a language is kept with its translation. The next time the same sentence appears, on any page, it is reused: the wording stays the same everywhere, and nothing is paid twice.

When a version is approved, its sentences are stored as approved, with the reviewer's corrections. A machine translation never replaces an approved one. So when you correct a title once in review, it is right from then on wherever it appears.

## Review

Open **Home › Translations › Review**. The list shows everything that was translated, with its languages and state:

| State | Meaning |
|---|---|
| **To review** | Translated; nobody has approved it yet |
| **With untranslated texts** | Some texts could not be translated; the original stands in for them |
| **Changes requested** | A reviewer sent it back, with a comment |
| **Outdated** | The original changed since |
| **Approved** | Checked; its sentences are in the memory |

Click an item to review it. Each text is shown with the original above and the translation below, ready to edit.

- **Save** keeps your corrections. A correction must keep the formatting of the original: its links and its bold words.
- **Translate again** asks the provider afresh for the texts you select.
- **Approve** saves, marks the version approved and stores its sentences in the memory.
- **Request review** hands it to a reviewer. See below.

To approve many at once, set the filters, select the rows, and choose **Approve**.

### A second pair of eyes

**Request review** starts a flow: a task lands in the [Inbox](flows.md) of the translation reviewers. The reviewer opens the version, corrects what needs it, then completes the task with **Approve** or **Request changes** and a comment. Use this when the person who checks is not the person who translated.

## Translation projects

A translation project is for larger work: many pages, several languages, a deadline. It gathers the content, the target languages and the provider, and has one **translation job** per language.

**Create one**:

- from **Home › Translations › Jobs**, with **Create project**; or
- from the Pages or Media console: select the content, then **Create › Translation Project**. You can add to an existing project instead.

**Run a job**:

- **Start** translates from your browser while you watch.
- **Run on the server** does it in the background. You can close the page; the job carries on and the console shows where it stands.

A job moves through **Draft**, **In progress**, **Ready for review**, **Approved** and **Complete**. From *Ready for review* you can request a review, approve everything the job made, or exchange it with an agency as XLIFF. **Update** runs a finished job again, sending only what changed in the originals since.

Each job counts what it did: texts translated, characters sent to the provider, and texts taken from the memory.

A project with a due date puts a reminder in its owner's Inbox, and is marked late once the date has passed.

### Work with a translation agency: XLIFF

XLIFF is the file format translation agencies and their tools use.

1. Translate the job first, so the agency improves a draft and does not start from nothing.
2. On the job, choose **Export XLIFF** and send the file to the agency.
3. When it comes back, choose **Import XLIFF**. Only texts that changed are touched; a report lists what was saved and what was left out, with the reason.
4. Review and approve as usual. The agency's wording goes into the memory.

## Providers

A provider is the service that translates. An administrator sets providers up under **Home › Translations › Providers**, per site: which service and model, its key, guidance on tone ("friendly, informal"), and the words never to translate, such as brand names. **Test connection** translates a sample sentence and shows the result, or why it failed.

Sensible Studio works with Anthropic (Claude), OpenAI, Google Gemini, Mistral, Azure OpenAI, OpenRouter and others, and with models running on your own machines through Ollama or LM Studio, where no text leaves your network.

The texts of your pages are sent to the provider you choose. Check that this fits your organisation's rules for the content in question.

See [Extending](../developer/extending.md) for writing a provider of your own.

## Translation and several sites

Translate the language master, then sync the country sites from it. A linked copy is never translated directly: it gets its content from its source. See [Several sites](sync.md).
