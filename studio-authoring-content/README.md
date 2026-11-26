# studio-authoring-content

Sensible Studio's own authoring resources, written from scratch (Apache License 2.0). AEM component dialogs include
these by path; on AEM they are product content, on Sensible Studio this package provides them.

| Path | Used by | What it is |
|---|---|---|
| `/libs/cq/gui/components/authoring/dialog/style/tab_edit/styletab` | every Core Components edit dialog | Styles tab: choose styles from the component policy, stored in `cq:styleIds` |
| `/libs/cq/gui/components/authoring/dialog/style/tab_design/styletab` | every Core Components design dialog | Styles tab of the policy: default classes/element and style groups |

Page properties (`/libs/wcm/foundation/components/basicpage/v1/basicpage`: `tabs/basic`, `tabs/advanced`, `tabs/pwa`
and its `cq:dialog`) live in `studio-uberjar/studio-compat-content/studio-wcm-foundation`, which owns that component
(rendering scripts included). Two packages must never filter the same path: the one installed last replaces the other.

The property names are those found in AEM content (see WKND `ui.content`); structure, labels and texts are Sensible Studio's.
Sensible Studio-specific widgets (`studio/granite/...`) are implemented by the author UI (`author-ui/src/granite`).

Build the package (zip root = this folder):

```bash
cd studio-authoring-content && zip -r ../crx-quickstart/install/studio-authoring-content-0.2.0.zip META-INF jcr_root -x '*.DS_Store'
```
