# Dialog fixtures

`dialogs/*.json` are the merged `cq:dialog` / `cq:design_dialog` trees of every WKND component, exactly as
`GET /bin/studio/dialog` returns them. They are produced by studio-adapter from the packages installed on the local
quickstart (WKND 4.0.4 `ui.apps` and `ui.content` + Core Components 2.33.0 content) plus `studio-authoring-content`, so they reflect real AEM dialogs:

```bash
mvn -pl studio-adapter test -Dtest=WkndDialogResolutionTest -Dstudio.dialog.dump=$(pwd)/author-ui/fixtures/dialogs   # policies go to fixtures/policies
```

`policies/*.json` are the content policies of those components as placed in the main container of WKND's article
page template (same test, written next to `dialogs/`).

They power `npm run dev:offline` and the dialog tests (`src/granite/dialogs.test.tsx`).

Content comes from Adobe's open source projects: Core Components (Apache License 2.0) and the WKND
reference site (see its repository for its license). File names: `<resource type with / as _>[.design].json`.
