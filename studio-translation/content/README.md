# studio-translation-content

The default workflow model **Translation Review** (`/libs/settings/workflow/models/translation_review`, runtime
`/var/workflow/models/translation_review` once synced):

1. **Review the translation** (participant, group `translation-reviewers`; stage Review)
2. **Decision** (OR split): **Approve** → `TranslationReviewProcesses$Approve` (the copy is approved on behalf of the
   reviewer and its texts go to the translation memory), or **Request changes** → `TranslationReviewProcesses$RequestChanges`
   (state "changes requested" with the reviewer's comment). Stage Decision.

The payload is the translated copy (page, experience fragment or content fragment). Translation Review starts it
("Request review"). The group is created by the repoinit in `studio-compat-config` (`~studio-translation`).

```bash
cd content && zip -r ../studio-translation-content-0.1.0.zip META-INF jcr_root
```
