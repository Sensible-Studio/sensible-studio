# studio-forms-content

Sensible Studio Forms (`claude/forms.md`): the *Form* template (`/conf/global/settings/wcm/templates/form`,
allowed under `/content/forms`), the page component (`studio/forms/components/formpage`: Core Components head and
footer around the template's structure), the containers (`form`: a `<form>` around a layout grid; `panel`: a
fieldset, and a step when the form says so) and the fields (`textfield`, `textarea`, `email`, `number`, `date`,
`fileupload`, `dropdown`, `checkbox`, `radiogroup`, `text`, `submit`) with their dialogs (Field / Validation / Rules tabs), the `studio.forms`
client library (`forms.js`: validation as the visitor types, rules, steps, the submission, the after-submit message
or redirect; `forms.css`: a neutral look plus a 12-column grid), the `/content/forms` root (`cq:conf=/conf/global`,
the Form template allowed) and the samples `/content/forms/wknd/contact` (a rule: *travellers* shows up, required,
when the topic is *A trip*) and `/content/forms/wknd/booking` (two panels as steps, a date, a file upload).

`gen.py` writes the whole `jcr_root` and `META-INF` beside itself (the fields' markup and dialogs share one shape)
and copies `forms.js` / `forms.css` from beside itself into the client library; edit those three, not the XML. Build:

```bash
python3 gen.py && zip -r ../studio-forms-content-<version>.zip META-INF jcr_root
```

(the version is in `gen.py`, `properties.xml`; the Sling installer takes the highest one in `instances/author/install`).

## Rules

Each field, panel and text has a `rules` child (the dialog's Rules tab: a composite multifield) with items
`action` (show / hide / require / disable), `field` (the name of another field), `op` (is / isnot / contains /
empty / notempty / gt / lt) and `value`. `rules.html` renders them as `<span hidden data-studio-rules>` with one
`<span data-studio-rule data-action… >` per rule (HTL does not evaluate inside `<script>`, and the editor wraps an
included resource in a decoration div, so `forms.js` looks for the nearest field box above, not the direct parent).
`forms.js` re-applies them on every input / change: *show* needs every show rule to hold, *hide* any, *require*
every, *disable* any. A hidden field is `hidden` on its grid column, `data-studio-off`, and left out of the data; in
the editor (`data-studio-editing`) it is only dimmed so it stays editable. A panel's rules cover its fields.
`FormModel` on the server evaluates the same rules from the values sent, so a hidden or disabled field is neither
validated nor stored, and *require* rules count.

## Steps

The form dialog's *One step per panel* (`steps`) makes each top-level panel a step: `forms.js` adds a progress line
and Back / Next under the current panel, checks the panel before moving on, and moves the submit row into that
line for the last step. Off in the editor, so every panel can be edited.

## Files and after-submit actions

A `fileupload` field makes `forms.js` send the submission as `multipart/form-data` (the values as fields, the files
as parts) instead of JSON; the servlet keeps each file under `<submission>/files/<field>/<name>` (`nt:file`) and the
field's value is the file names. `accept` (".pdf,image/*") and `maxSize` (MB) are checked on both sides. The Submissions
view opens a file through `?op=file&path=…` (signed-in only).

The form dialog's *Actions* tab: *Email the submission to* (needs the `io.sensible.studio.forms.impl.Mail` OSGi
configuration: host, port, starttls, user, password, from — without it the action is skipped and says so), *Start a
flow* (a model under `/var/workflow/models`, run on the submission; the flows engine's service user may read and write
`/var/studio/forms` for that — the forms repoinit grants it), *Send to a web address* (the submission POSTed as JSON).
The flow starts right away; email and webhook run on a background thread. Each action's outcome is kept with the
submission (`actions/<name>`: done / failed / skipped, detail) and shown in the Submissions view.
