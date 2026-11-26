# studio-cfm-content

Content package with the Content Fragment components that AEM content refers to by resource type:

- `dam/cfm/components/grid`: the paragraph system Core Components' Content Fragment component (display mode
  "single text") places between the text's paragraphs (`par0`, `par1`, ...). A layout grid
  (`sling:resourceSuperType` = `wcm/foundation/components/responsivegrid`).

Filter: `/libs/dam/cfm/components`. Build (the archive root is this folder's content):

```bash
cd studio-cfm/content && zip -r ../target/studio-cfm-content-0.1.0.zip META-INF jcr_root
```
