# studio-siterail

The data behind the left rails of the Sites console (`/sites.html`): one JSON endpoint,
`/bin/studio/siterail.json`, run with the request's resolver (the user's own permissions).

| Call | What it returns |
| --- | --- |
| `GET op=timeline&path=<page>` | The page's events, newest first: created, modified, published or unpublished, rolled out, versions, workflows (started, completed participant steps, completed or terminated), comments, the launches that hold a real copy of the page (created, promoted; with the launch, the page's copy and the live date — ancestors kept as shells in a launch do not count) and the annotations left on its components in the editor's Annotate mode (`<component>/cq:annotations/<name>`: `text`, `color`, the component below `jcr:content`; the time is `jcr:created`, else `jcr:lastModified`; one without either is left out). Also the lock owner. |
| `GET op=site&path=<path>` | The site the path is in (the first level below `/content`): page count, live copy and locked pages, the latest change, the `cq:conf`, the language roots and the templates of the configuration. |
| `GET op=templates&path=<path>` | The templates the pages below the path use (for the Filter rail). |
| `GET op=search&path=…&q=…&template=…&tag=…&modified=<days>&livecopy=livecopy\|notlivecopy&locked=true` | The pages below the path that match every filter, newest change first. At most 500 hits; the walk stops after 20 000 resources (`truncated: true`). |
| `POST op=comment&path=<page>&message=…` | Adds a comment to the page. |
| `POST op=deleteComment&id=<comment path>` | Deletes a comment: its author or `admin` only (403 otherwise). |
| `POST op=saveVersion&path=<page>&label=…&comment=…` | Creates a page version through `PageManager.createRevision`. |
| `POST op=restore&path=<page>&version=<id>` | Reverts one page to a version (timeline → Revert to this Version). `PageManager.restore` saves the current state as a version first. |
| `POST op=forgetDeleted&path=<page>` | Admin only: a deleted page's versions and record are removed for good. |
| `POST op=delete&path=…` (repeated) | Deletes pages or folders the restorable way: every page inside gets a version and a record in `/var/studio/deleted/<page path>` (version history, title, who, when). A page locked by someone else stops the whole delete (403). The Sites console deletes through this. |
| `GET op=versionsInside&path=<page>` | Restore Version: the pages inside the page that have versions, existing and deleted, with their versions (newest first). |
| `POST op=restoreVersions&restore=<page>\|<version>` (repeated) | Restores the chosen versions, parents before children. A deleted page is created again from the version (the same node, so it keeps its history); its parent must exist or be restored in the same call. One result per page. |
| `GET op=treeAt&path=<page>&date=<ISO>` | Restore Tree preview: the tree (existing and deleted pages) with the latest version each page had at the date, and for a deleted page without one the version it comes back with when pages below it do. |
| `POST op=restoreTree&path=<page>&date=<ISO>&preserve=true\|false` | Restores every page of the tree to its version at the date and brings back deleted ones. Without `preserve`, existing pages that had no version then (and nothing restorable below them) are deleted the restorable way. The tree's own page is never deleted. |
| `GET op=diffVersion&path=<page>&from=current\|<version>&to=current\|<version>` | Compare to Current: what differs between two states of the content — components added or removed (with their properties), properties added, removed or changed (before and after). Bookkeeping (modification dates, versions, replication, locks) does not count. |
| `GET op=searches` | The signed-in user's saved searches of the Filter rail (`name`, `criteria` as the console's JSON, `modified`), by name. |
| `POST op=saveSearch&name=…&criteria={…}` | Saves the filters under a name (a search of the same name is replaced); at most 50 per user, names up to 100 characters. Anonymous users cannot save. |
| `POST op=deleteSearch&name=…` | Deletes one of the user's saved searches. |
| `GET /bin/studio/versionpreview.html?path=<page>&version=<id>` | Preview Version (`VersionPreviewServlet`): the page rendered by Sling as usual (`wcmmode=disabled`), with a resolver in which `<page>/jcr:content` is the version's frozen content, read only. Components, templates, configuration and assets are today's. Nothing is written. A deleted page is previewed too, from its deletion record (read as the service user), by whoever can read where it was; the overlay then shows a page node for it. The page's own resources in the HTML (`<page>/_jcr_content/…`, its images) point back into the preview with `&res=<resource>`, so they are the version's and are found for a deleted page; responsive `{.width}` placeholders are kept. Files uploaded into components (`nt:file`, `nt:resource`) are read from the version too (`adaptTo(InputStream)`, `jcr:data` as a stream). |

Deleted pages are found in three ways:

- **The Sites console** deletes through `op=delete`: a version of every page inside first, then a record.
- **A Sling POST delete** (`:operation=delete`, with or without `:applyTo`) does the same: `RestorableDeleteFilter`
  versions and records the pages before the delete runs, and stops a delete of a page locked by someone else or that
  the user may not remove (403). The DAM and wildcard `:applyTo` are left alone.
- **Anything else** (CRX tools, WebDAV, the JCR API, packages): `DeletedPagesListener` notices removals below
  `/content` and records the pages that had versions. Oak notes on each version history where the node was versioned
  (`rep:VersionablePaths`, a property named after the workspace). The Lucene index `/oak:index/studioVersionablePaths`
  indexes that property on version histories, so the listener finds the histories below a path with a JCR-SQL2
  `LIKE` query; while the index is missing or not built yet (`EXPLAIN` does not name it) it walks the version storage
  instead (at most 200 000 nodes). Either runs when something is removed and once at startup, and the log line says
  which (`Recorded 1 deleted page(s) below … (index, 4 ms)`).

  The index ships as a content package, `oak-index/` (Oak wants an `oak:QueryIndexDefinition` saved with its `type`,
  which repoinit cannot do: it saves the node before setting properties). Pack and install it like the editor:
  `cd oak-index && zip -r ../target/studio-siterail-index-1.0.0.zip jcr_root META-INF` and copy the zip to
  `crx-quickstart/install`; Oak builds it at the next async indexing run.

  These records have `found` (when Sensible Studio noticed) instead of who and when; from versions, nothing newer than the last
  version can come back — the next point keeps the rest.
- **Kept as they were**: `DeletedPagesObserver` is an Oak observer. Oak hands it each new repository state after the
  commit, and it works in the background (queued), so it never slows down or blocks a save. It reads the pages
  deleted outside the console from the state before and keeps a copy, written by the service user, in
  `/var/studio/kept/<day>/<entry>` (`path`, `deleted`, `deletedBy` from the commit, and `node`: the deleted node as it
  was, without identifiers, version links, locks and access control). Pages that never had a version are kept too.
  Moves are not deletions (Oak marks the moved node with `:source-path`); pages the console or a Sling POST delete
  recorded are not kept twice; the DAM, tags and component deletions inside pages are left alone; a deletion of more
  than 20 000 nodes is not kept. Restore Version lists the copy as the version *As deleted* (Preview Version works on
  it); restoring brings the page back as it was, with a new version history, and the pages below it come back on
  their own. `op=forgetDeleted` removes the copy too. Code: `DeletedPages` (the comparison), `KeptPages` (the copy).

  - A page whose content was versioned gets its deletion record from the state before, too: its version history, who
    deleted it and when (the version storage walk only knows "found", and the async index may not have the history
    yet). Restoring its kept copy brings the content back from its last version first — the same node — and then puts
    the kept content on it, so the page keeps its version history. Any page that comes back from its kept copy gets
    a version right away ("Restored as it was when it was deleted"), as the user who restored it.
  - Restore Tree takes a kept copy as a version of the page's last change (`cq:lastModified` / `jcr:lastModified`):
    from then until the deletion it is exactly how the page was.
  - Configuration `DeletedPagesObserver` ("Sensible Studio Sites: pages deleted outside the console"): `keepDays` (90; kept
    copies older than that are removed every six hours, 0 keeps them), `maxNodes` (20 000: a larger deletion is kept
    page by page, each page on its own entry without the pages below it) and `maxTotalNodes` (200 000: the most kept
    of one deletion page by page; the pages left out are named in the log).
  - In a cluster (a document node store) only deletions committed on this instance are kept, so each is kept once; on
    a single instance everything is looked at (the observer queue reports merged commits as "external" too).

A moved page is not a deleted page: when the versioned content of a record or a history still exists elsewhere, it is
not listed, and restoring it is refused (a JCR restore would otherwise remove the moved page).

`POST op=forgetDeleted&path=<page>` (admin only) removes a deleted page's versions and record for good.

Restore, preview, compare, permissions and these deletes are tested on a real Oak repository (sling-mock-oak, with
Sensible Studio's `PageRevisions`).

Storage: comments live in `/var/studio/comments/<page path>/<name>` (`message`, `author`, `created`); saved searches
in `/var/studio/searches/<user>/<hash of the name>` (`name`, `criteria`, `created`, `modified`; the user folder is the
user ID with what a node name cannot hold escaped, `@` → `_0040`), and each user only reaches their own folder.
Workflows are read from `/var/workflow/instances` (studio-workflow), versions from the `PageManager`
of studio-cq-impl. The free-text filter matches the page name, title, description, page title and
navigation title (every word must appear); there is no index behind it yet.

Authors need no rights on `/var` or the version storage: comments, deletion records, saved searches, the versions of
deleted pages and pages brought back from deletion go through the bundle's service user `studio-siterail-service` (subservice
`siterail`, repoinit and mapping in `studio-compat-config`). What the user may do is checked with their own session first:
reading the page (comments, timeline), `remove` on what is deleted, `add_node` where a page comes back; reverting an
existing page and its versions run as the user. A version taken before a delete carries the user's name; the service
takes it only when the user may not version. Without the service user (no repoinit yet) everything runs as the
user, as before.

Tests: `SiteRailTest` (sling-mock, resource resolver mock), `VersionRestoreTest`, `VersionCompareTest`, `VersionPreviewTest`, `PermissionsTest` (real users and ACLs), `DeletedOutsideTest` and `SavedSearchesTest` (Oak, real users).
