# studio-uberjar: înlocuitorul clean-room pentru AEM uber-jar

Implementare proprie, Apache-2.0, a API-urilor `com.day.cq.*` peste Apache Sling simplu. Bundle-urile unui proiect AEM (de exemplu `wknd.core`) se leagă de aceleași nume de pachete, clase și metode, fără recompilare.

## Module

| Modul | Ce conține |
|---|---|
| `studio-cq-api` | API-ul AEM folosit de Core Components 2.33 și WKND 4.0.4: toate cele 119 tipuri referite, în 52 de pachete `com.day.*` / `com.adobe.*`, exportate ca bundle OSGi. Conține și tipurile de noduri (`SLING-INF/nodetypes/studio-cq.cnd`) și utilitare cu implementare proprie (`Text`, `I18n`, `Layer`, `ContentPolicyStyle`, `WCMUtils`, `TemplatedContainer` etc.). |
| `studio-cq-impl` | Implementarea peste Sling: pagini, template-uri, policy-uri, componente, `ComponentContext` și decorare, layout grid, fuziunea cu structura template-ului, clientlibs (`/etc.clientlibs`), DAM, `TagManager`, `QueryBuilder`, Style System, `Externalizer`, `LanguageManager`, randarea `cq:Page`.. Pentru editor: în `wcmmode=edit` decorarea primește atributele `data-studio-*`, iar containerele primesc placeholder-ul „Drag components here”. `/bin/studio/editor` dă politicile containerelor și caută asset-uri, iar `/editor.html`, `/sites.html`, `/security/*.html` și `/studio/start.html` servesc aplicația de autor. `data-sly-resource` suportă opțiunile WCM `decorationTagName`, `cssClassName`, `decoration` și `wcmmode`. `ContainerItemsFilter` aplică `itemOrder` și `deletedItems` trimise de tab-ul Items al Carousel/Tabs/Accordion. |
| `studio-dam` | Legătura cu serviciul Rust `dam-service`: pornește și supraveghează procesul `studio-dam`, transformă fiecare original nou într-un Sling Job, scrie rendition-urile și metadatele înapoi pe asset, servletul de upload `/bin/studio/dam/upload`, API-ul JSON `/bin/studio/dam.json` și consola `/assets.html`. Vezi secțiunea DAM de mai jos. |
| `studio-security` | Consolele de securitate: `/bin/studio/security.json` pentru utilizatori, grupuri (creare, profil, dezactivare, parolă, membri, impersonare, ștergere) și permisiuni (ACE-uri pe principal, adăugare/editare cu `rep:glob`, permisiuni efective prin grupuri). Lucrează cu sesiunea utilizatorului logat. Interfața e în `author-ui` (`/security/*.html`). |
| `studio-tagging` | Consola de tag-uri: `/bin/studio/tags.json` peste arborele `cq:Tag` de sub `/content/cq:tags` (creare, editare cu titluri pe limbi, mutare, merge, ștergere, unde e folosit un tag). La mutare, merge și ștergere actualizează `cq:tags` din pagini și asset-uri. Servește și `/tags.html`. |
| `studio-workflow` | Motorul de workflow și consola de modele: modele de design (`cq:Page` sub `/conf/global/settings/workflow/models`, pași `cq/workflow/components/model/*`), sync în modelul runtime de sub `/var/workflow/models`, instanțe sub `/var/workflow/instances`, rulate în Sling Jobs. API-ul Granite (`com.adobe.granite.workflow.*`: `WorkflowSession`, `WorkflowProcess`, `ParticipantStepChooser`, `WorkItem`, `MetaDataMap`) e în `studio-cq-api`. `/bin/studio/workflow.json` și paginile `/workflow/*.html` (modele, instanțe, launchere, inbox); launcherele pornesc workflow-uri la schimbări de conținut. |
| `studio-siterail` | Datele rail-urilor din consola Sites: `/bin/studio/siterail.json` cu timeline-ul unei pagini (activități, versiuni, workflow-uri, comentarii în `/var/studio/comments`), rezumatul site-ului (limbi, pagini, configurație, template-uri) și filtrul de pagini (text, template, tag-uri, dată, live copy, blocate). Lucrează cu sesiunea utilizatorului. |
| `studio-compat-scanner` | CLI care citește manifestele bundle-urilor unui proiect și arată ce pachete Adobe lipsesc din Sensible Studio. |
| `studio-compat-content` | Pachete de conținut Sensible Studio: `studio-wcm-foundation` (componentele de bază la tipurile pe care le moștenesc componentele AEM: basicpage, responsivegrid, xfpage, servicelibs, template-urile HTL pentru clientlibs) și markerul `cq-platform-content` și `studio-workflow-content` (pașii modelelor de workflow, cu dialogurile lor). |
| `studio-compat-config` | Configurații OSGi: repoinit (namespace-uri, privilegiul `crx:replicate`, utilizatori de serviciu, mixin-uri), maparea utilizatorilor de serviciu, coada de joburi DAM, utilizatorul de serviciu și coada motorului de workflow. |

## Build

```bash
mvn clean install        # JDK 17+ pentru build (bnd 7); bytecode Java 11; Sling API 2.24+
```

## Stare (16.10.2026)

**`/content/wknd/us/en.html` se randează pe Sensible Studio**: 200 OK, cu header și footer din experience fragments,
layout grid, carousel, teaser, navigation, search, image list, content fragments și imagini adaptive,
cu CSS-ul și JS-ul WKND servite prin `/etc.clientlibs`.

Teste: 69 în `studio-cq-impl`, 66 în `studio-dam`, 2 în `studio-security` (pe Oak real), 3 în scanner; 97 în `author-ui` (`npm test`).

### Limitări cunoscute

- **Autorarea:** editorul de pagini (`author-ui`, servit la `/editor.html/<pagină>.html`) e complet pentru Edit, Layout și Preview. `EditContext` rămâne `null`. Editorul nu are nevoie de el: citește atributele `data-studio-*` scrise pe decorare în `wcmmode=edit`. Lipsesc undo/redo, editarea inline și modurile Timewarp și Developer.
- **Workflow și Dynamic Media** lipsesc: serviciile răspund „nimic” (fără modele de workflow, DM dezactivat). MSM există, vezi mai jos; trigger-ele de publicare (`publish`, `deactivate`) așteaptă replicarea.
- **Formularele** nu trimit încă date (`FormsHandlingServletHelper` răspunde 501).
- **Content Fragments:** există API-ul, dar nu și adaptarea unui asset la `ContentFragment`.
- **`QueryBuilder`** acoperă predicatele uzuale (path, type, property, nodename, fulltext, orderby, p.limit/p.offset). Grupurile imbricate și evaluatorii proprii sunt ignorați.
- **Clientlibs:** nu se face minificare, iar URL-urile `.min` servesc aceiași bytes.
- **`copy`/`move`** de pagini nu păstrează istoricul de versiuni. `move` rescrie referințele dacă primește `adjustRefs`; pe lângă ele le rescrie mereu și pe cele din interiorul paginii mutate. Consola Sites face asta prin `/bin/studio/sites.json?op=move`. `order()` cere JCR.
- **Lock-ul** e o proprietate (`studio:lockOwner`), nu un lock JCR.

## DAM

Partea de citire (`Asset`, `Rendition`, `AssetStore`) e în `studio-cq-impl`. Partea de scriere:

```text
upload / AssetManager / pachet de conținut
   └─► .../jcr:content/renditions/original      (dam:assetState = processing)
         └─► AssetChangeListener ─► Sling Job "studio/dam/process"
               └─► AssetProcessor ─► studio-dam (Rust): PUT /blobs, POST /process, GET /blobs/{id}
                     └─► renditions/cq5dam.*, jcr:content/metadata, dam:failedRenditions
                         dam:assetState = processed (sau failed), studio:blobId
```

- **`AssetManager`** (`resolver.adaptTo(AssetManager.class)`): `createAsset`, `getAssetByIdentifier`, `assetExists`.
  Creează structura `dam:Asset` și folderele lipsă (`sling:OrderedFolder`).
- **Upload**: `POST /bin/studio/dam/upload`, multipart cu `folder` (sub `/content/dam`) și `file`, plus antetul
  `X-Studio-Upload: 1` (protecție CSRF). Rulează cu drepturile utilizatorului care face cererea.
- **Metadate**: valorile tehnice (`dam:*`, `tiff:*`, `exif:*`, `exifEX:*`) se rescriu la fiecare procesare;
  cele descriptive din XMP (`dc:title`, `dc:description`, cuvinte cheie) se scriu doar dacă lipsesc, ca să nu se
  piardă editările autorilor.
- **Erori**: dacă `studio-dam` nu răspunde, jobul se reia (coada `Sensible Studio DAM processing`: 60 de încercări la 30 s).
  Erorile definitive (fișier prea mare, format invalid) și epuizarea reîncercărilor ajung în
  `dam:assetState = failed` și `dam:processingError`.
- **Re-upload rapid**: dacă originalul se schimbă cât timp un job lucrează, jobul acela renunță la scriere, iar
  jobul pentru noul original scrie rezultatul.
- **XMP**: maxim 300 de proprietăți, valori de maxim 4000 de caractere. Pachetele mai mari de 1 MB sunt ignorate de `studio-dam`.

### Livrarea imaginilor

Componentele Image și Teaser din Core Components cer imaginile în două feluri, iar Sensible Studio le servește pe amândouă din
`studio-dam`.

**URL-urile `.coreimg` (cele din AEM 6.5, folosite de WKND).** Paginile primesc un `srcset` cu lățimile din policy
(`allowedRenditionWidths`), de forma `<componentă>.coreimg.<calitate>.<lățime>.<ext>/<lastModified>/<nume>.<ext>`.
`CoreImageFilter` (filtru Sling în fața servletului de imagini din Core Components) le răspunde din `studio-dam` când
imaginea vine dintr-un asset din DAM:

- asset JPEG sau PNG, procesat, la care se ajunge prin `fileReference` (nu imaginea paginii, nu un fișier urcat în
  componentă);
- crop-ul, rotirea și flip-urile salvate de editorul de imagini al componentei se aplică în `studio-dam`, în ordinea
  servletului: crop pe original (coordonatele, desenate pe rendition-ul web, se scalează la original), rotire, flip
  orizontal, flip vertical; studio-dam primește pașii în parametrul semnat `t` (vezi `dam-service/README.md`);
- lățimea e în `allowedRenditionWidths`, calitatea e `jpegQuality` din policy (policy-ul se găsește ca în Core
  Components, prin `teaserDelegate` și `imageDelegate`);
- data din URL e cea curentă (maximul dintre componentă și asset).

Orice altceva (redirect pentru o dată veche, 404, GIF, SVG, rotiri care nu sunt multipli de 90°, crop/rotire pe o
fotografie cu orientare EXIF, conversii de format, `studio-dam` oprit) merge mai departe la servletul Java, neschimbat. Asset-urile venite din pachete de conținut (rendition-uri făcute în altă parte,
fără `studio:blobId`) sunt servite și ele: la prima cerere originalul se urcă în `studio-dam`, fără scrieri în repository.
Diferențe față de servletul Java: `studio-dam` redimensionează din original (servletul pornește de la rendition-ul cel mai
apropiat), recodează și imaginile cerute la o lățime mai mare decât originalul (servletul le trimite neschimbate), iar
înălțimea se rotunjește, nu se trunchiază (400×267 în loc de 400×266).

Măsurat pe 30 de URL-uri din paginile WKND (browser → Sensible Studio pe același Mac): 0,92 s cu servletul Java, 0,36 s din
cache-ul `studio-dam`; 5,4 MB față de 3,7 MB. Prima cerere (fără cache, cu urcarea originalului) durează cam cât servletul.

Configurația `io.sensible.studio.dam.impl.CoreImageFilter`: `enabled` (implicit `true`) și `webp` (implicit `false`).
Cu `webp`, URL-urile `.jpeg` primesc WebP când browserul îl acceptă, cu `Vary: Accept`; nu se activează în spatele unui
cache care ignoră `Vary` (Dispatcher-ul AEM îl ignoră).

**Web-optimized images (`enableAssetDelivery`).** E opțiunea din AEM as a Cloud Service („Enable Web Optimized
Images”), nu are legătură cu Dynamic Media. `AssetDeliveryImpl` implementează `com.adobe.cq.wcm.spi.AssetDelivery`, deci
când policy-ul are `enableAssetDelivery = true`, Core Components cer de la el URL-urile, iar imaginile vin din `studio-dam`.
WKND nu are opțiunea activată.

```text
/bin/studio/dam/deliver/<semnătură>/<studio:blobId>/<lățime>/<calitate>/<nume-seo>.<webp|jpeg|png>
```

- **Semnătura** e HMAC cu o cheie derivată din token. Acoperă blobul, formatul și calitatea, dar nu lățimea,
  pentru că Core Components pun lățimea în template-uri URL pe care le completează browserul. Servletul o rotunjește
  în sus la multiplu de 16 (între 16 și 4096), iar `studio-dam` o plafonează la dimensiunea originalului, deci numărul
  de variante per imagine e limitat.
- **Calitatea** e `0` la PNG (format fără pierderi) și între 1 și 100 la JPEG și WebP.
- **WebP** se livrează când Core Components cer `preferwebp` (implicit).
- **Fallback**: pentru crop, rotire, flip, GIF/SVG, asset-uri încă neprocesate sau `studio-dam` oprit, `AssetDelivery`
  întoarce `null`, iar Core Components folosesc servletul lor de imagini.
- **Cache**: răspunsurile au `Cache-Control: public, max-age=31536000, immutable`. URL-ul conține hash-ul conținutului,
  deci nu își schimbă niciodată sensul.
- **Token stabil**: în modul `embedded` tokenul se generează o singură dată în `crx-quickstart/dam/token`
  (permisiuni `0600`). URL-urile din paginile puse în cache rămân valide după repornire.

**Limitări cunoscute:**
- URL-urile semnate nu expiră. O imagine ștearsă sau înlocuită rămâne accesibilă prin URL-urile vechi până se rotește
  tokenul, iar rotirea invalidează toate URL-urile din paginile din cache. Cache-ul de livrare și originalele din
  `dam/store` nu au încă curățare automată.
- `studio:blobId` poate fi scris de oricine are drept de scriere pe asset. Cine cunoaște hash-ul SHA-256 al altui blob
  ar putea obține un URL semnat pentru el. Riscul e mic (hash-urile nu se pot ghici), dar proprietatea ar trebui
  protejată prin ACL.
- Upload-ul refuză HTML, XML și scripturi. SVG se acceptă: `ActiveContentFilter` servește binarele DAM de tip SVG,
  HTML sau XML cu `Content-Security-Policy: sandbox` și `nosniff`, deci un script dintr-un SVG deschis direct nu
  rulează în originea Sensible Studio. Tipul fișierului se stabilește din extensie, nu din ce declară clientul.

### Consola Assets

`/assets.html/content/dam/...` (navigare) și `/assetdetails.html/content/dam/...` (un asset, `#properties` pentru
metadate) sunt ecranele din `author-ui/src/assets`, modelate după comportamentul consolei din AEM 6.5:

- vizualizări card, listă și coloane; sortare; filtre după tip; selecție (Escape o golește), cu bara de acțiuni
  Properties, Download, Copy, Move (și redenumire), Reprocess, Delete;
- creare de foldere, upload prin buton sau drag & drop (3 fișiere în paralel), Copy → Paste între foldere;
- pagina asset-ului: rendition-urile, navigare „n of m”, erorile de procesare; pagina de proprietăți cu taburile
  Basic, Advanced, Camera Data și All Metadata;
- content fragment-ele apar ca „CONTENT FRAGMENT” și se deschid în `/editor.html/<cale>` (editorul de fragmente);
- editorul de imagini, la URL-ul din AEM `/mnt/overlay/dam/gui/content/assets/assetedit.html/<asset>` (butonul Edit
  sau tasta `e` pe pagina asset-ului, Edit în bara de selecție): crop liber sau la 2:3, 3:5, 16:9, pătrat, rotire
  stânga/dreapta, flip vertical/orizontal, image map (dreptunghiuri și cercuri cu link, text alternativ și target),
  undo/redo. Nimic nu se salvează până la Finish. Merge pentru JPEG, PNG și WebP;
- **References**: rail-ul References de pe pagina asset-ului și tabul References din Properties arată paginile care
  folosesc asset-ul; Delete avertizează cu lista
  lor; Move bifează paginile în care căile se rescriu (`DamReferences`, doar în `/content` și `/conf`);
- **căutare**: câmpul Search din bara de acțiuni caută sub folderul curent (`/assets.html/<folder>?fulltext=…`), cu
  rail-ul Filters: tip, tag-uri (cu sub-tag-uri), modificat (24 h, săptămână, lună, an), mărime, orientare. Cuvintele
  se potrivesc și ca început de cuvânt (`surf` → „surfing”), `-cuvânt` exclude, `"frază"`. Răspunsul spune ce a
  folosit: `"engine": "index"` sau `"walk"` (vezi „Indexul Lucene” mai jos);
- **tag-uri**: câmpul Tags din Properties (picker peste `/content/cq:tags`), salvat în `metadata/cq:tags`;
- **versiuni**: rail-ul Timeline de pe pagina asset-ului: Save as Version (etichetă, comentariu), Revert (starea
  curentă se salvează întâi ca versiune). Versiune automată înainte de editarea imaginii și la upload „Create version”;
- **upload cu conflict**: dacă numele există, serverul răspunde 409, iar panoul de upload întreabă: Keep both,
  Replace, Create version sau Skip (per fișier sau pentru toate);
- **duplicate**: înainte de upload, browserul calculează SHA-1-ul fișierului (până la 512 MB) și întreabă
  `op=duplicates`; dacă același conținut există oriunde în DAM (`dam:sha1`), panoul arată unde și întreabă Upload
  anyway sau Skip;
- **PDF și SVG** au thumbnail-uri și rendition web (prima pagină a PDF-ului), făcute de `studio-dam`; PDF-ul primește
  numărul de pagini și titlul/autorul din fișier;
- **video și audio** (MP4, MOV, WebM, M4A, MP3): player-ul browserului pe pagina asset-ului, pe original (Sling
  servește pe bucăți, cu `Range`, deci derularea nu descarcă tot fișierul); sub player durata, rezoluția, fps-ul și
  codecurile (`dam:duration`, `tiff:ImageWidth`/`ImageLength` după rotire, `xmpDM:videoFrameRate`,
  `xmpDM:videoCompressor`, `xmpDM:audioCompressor`, `xmpDM:audioSampleRate`, `xmpDM:audioChannelType`, citite de
  `studio-dam` din antetul containerului, fără decodare); pe card durata. Thumbnail-ul vine dintr-un cadru: la upload
  browserul ia cadrul de la secunda 1, iar „Use frame as thumbnail” folosește cadrul oprit în player. Cadrul se
  salvează ca `cq5dam.poster.jpg` (`op=poster`, doar JPEG/PNG, maxim 20 MB) și procesarea face din el thumbnail-urile
  și rendition-ul web; metadatele rămân ale video-ului. Formatele pe care browserul nu le redă (AVI, WMV, ProRes)
  rămân cu iconiță și Download; pentru ele va fi nevoie de ffmpeg, opțional. Durata asset-urilor importate din AEM
  (`xmpDM:duration` cu `xmpDM:value`/`xmpDM:scale`) se citește și ea;
- **Asset Reports** (meniul utilizatorului › Asset Reports,
  `/mnt/overlay/dam/gui/content/reports/reportspage.html/var/studio/dam/reports[/ID]`): tipurile din AEM cu id-urile
  lor — Upload, Modification, Expiration (`prism:expirationDate`), Publish (`cq:lastReplicated`, când va exista
  replicarea), Disk Usage (pe foldere, cu sub-folderele adunate și opțional rendition-urile), Files, Link Share.
  Wizard: tipul, apoi titlu, folder, perioadă, coloane (Title, Path, Type, Size, Added, Added By, Modified, ...) plus
  orice proprietate de metadate ca coloană. Raportul se face pe loc cu sesiunea utilizatorului (vede doar ce poate
  citi, maxim 100.000 de asset-uri), se păstrează cu CSV-ul în `/var/studio/dam/reports/<uuid>` (service user
  `studio-dam-reports`; `everyone` nu are citire acolo) și îl văd doar autorul și admin. API:
  `/bin/studio/dam/reports.json` (listă, `op=catalog`, `id=` pagini de 100–500 rânduri, POST `op=create|delete`) și
  `/bin/studio/dam/reports.csv?id=` (download, celule protejate de formule Excel). Namespace-ul `prism` e înregistrat
  în repoinit;
- **Smart Tags** (meniul utilizatorului › Smart Tags,
  `/mnt/overlay/dam/gui/content/smarttags.html/conf/global/settings/dam/smarttags`): modelul SigLIP
  (`google/siglip-base-patch16-224`, Apache-2.0) rulează în `studio-dam`, local. Admin îl descarcă o dată din pagină
  (aprox. 815 MB, de pe Hugging Face, la o revizie fixată; `studio-dam` verifică dimensiunea și SHA-256-ul fiecărui fișier,
  în `crx-quickstart/dam/models`). Setări în `/conf/global/settings/dam/smarttags`: pornit, tag-urile din care alege
  (rădăcini, de ex. `wknd-shared:activity`, `wknd-shared:season`; se iau toate sub-tag-urile, cu titlul lor), cuvinte
  cheie simple (beach, mountain, ...), maximum pe asset, încrederea minimă. Cu ele pornite, fiecare imagine procesată
  primește `studio:smartTags` (+ `studio:smartTagConfidence`) și `studio:smartKeywords` în metadate, separat de `cq:tags`, ca în
  AEM; „Tag now” etichetează un folder existent din rendition-urile lui (job `studio/dam/smarttag`, fără re-render). În
  Properties, Smart Tags apar cu procentul: + le aplică în `cq:tags`, × le scoate definitiv (`studio:smartTagsRemoved`).
  Indexul Lucene (v2) le indexează ca text, deci căutarea „surfboard” găsește imaginile cu surfuri. Video-urile se
  etichetează după cadrul-poster. Fără model sau cu `studio-dam` oprit, procesarea merge mai departe fără etichete.
- **download**: un asset → originalul; mai multe sau foldere → dialog și ZIP cu structura folderelor, opțional cu
  rendition-urile (`/bin/studio/dam/download.zip`, maxim 5000 de fișiere);
- **proprietăți pentru mai multe asset-uri**: dialog cu valorile comune („Mixed values” unde diferă); se scriu doar
  câmpurile schimbate; la cele cu mai multe valori, Append adaugă la ce are fiecare asset;
- **Checkout / Check In**: `cq:drivelock` pe `jcr:content`; cât timp altcineva are asset-ul, metadate, editare,
  mutare, ștergere (și a folderelor care îl conțin), restaurare de versiune și înlocuire la upload răspund 423;
- **Relate**: din selecție (2+ asset-uri), legături în ambele sensuri în `jcr:content/related/others`; rail-ul
  Related de pe pagina asset-ului, cu Unrelate;
- **Extract Archive**: pe un ZIP, într-un folder nou cu numele arhivei sau lângă ea; numele existente: skip, keep
  both sau replace. Se refuză `..`, căile absolute și fișierele ascunse; maxim 2000 de fișiere și 2 GB despachetat;
  se creează doar tipurile acceptate la upload;
- **Metadata Export / Import**: meniul Metadata din bara de acțiuni. CSV în formatul AEM (`assetPath`, apoi
  `dc:title {{String}}`, `dc:subject {{String: multi}}`, valori multiple cu `|`; tipuri String, Long, Double, Boolean,
  Date). La import, celulele goale lasă valorile neschimbate, rândurile cu probleme se raportează cu linia și motivul;
  celulele care încep cu `= + - @` primesc un apostrof la export, ca Excel să nu le execute;
- **Processing Profiles**: `/mnt/overlay/dam/gui/content/processingprofilepage.html` (din meniul utilizatorului).
  Un profil are rendition-uri proprii (nume, lățime, înălțime, JPEG/PNG/WebP, calitate) și se alege în proprietățile
  folderului; subfolderele îl moștenesc. Se aplică la upload și la Reprocess (`extra` din `POST /process` al
  `studio-dam`). Profilele stau în `/conf/global/settings/dam/processing`; numele produse se țin în
  `jcr:content/dam:profileRenditions`, ca un profil scos să-și șteargă rendition-urile la următoarea procesare.
- **Collections**: `/mnt/overlay/dam/gui/content/collections.html` (din meniul utilizatorului). O colecție ține
  referințe la asset-uri și foldere, nu copii (`/content/dam/collections/<nume>`, `studio:members`, nod
  `nt:unstructured`, deci nu apare în listări). Add to Collection din selecție (sau colecție nouă), Remove from
  Collection și Download în pagina colecției. Privată (doar proprietarul) sau publică. **Smart collection**: din
  căutare, Save as Smart Collection salvează filtrele și folderul; pagina arată ce se potrivește acum;
- **Metadata Schemas**: `/mnt/overlay/dam/gui/content/metadataschemaeditor/schemalist.html`. O schemă e o listă de
  taburi cu câmpuri (text, text pe mai multe rânduri, mai multe valori, număr, dată, checkbox, dropdown cu opțiuni;
  obligatoriu sau nu), fiecare legat de o proprietate din metadate. Se alege în proprietățile folderului
  (`jcr:content/metadataSchema`, moștenită de subfoldere) și înlocuiește tabul Basic din Properties. Câmpurile
  obligatorii goale opresc salvarea. Schemele stau în `/conf/global/settings/dam/adminui-extension/metadataschema`.
- **Share Link**: din selecție, un link pe care oricine îl poate deschide fără cont, valabil 1 zi – 90 de zile:
  `/linkshare.html?sh=<token>` arată asset-urile (și conținutul folderelor) cu previzualizare, download per asset și
  ZIP cu tot. Tokenul (256 de biți) se arată o singură dată; în `/var/studio/dam/linkshare` se ține doar SHA-256-ul lui.
  Descărcările trec printr-un service user separat (`studio-dam-linkshare`, doar citire pe `/content/dam`), iar fiecare
  cale cerută se verifică față de cele partajate. Pagina are CSP strict, `Referrer-Policy: no-referrer`, fără cache;
  originalele se descarcă mereu ca atașament. Proprietarul își vede linkurile pentru un element și le poate revoca;
  cele expirate se șterg când se face un link nou.

`AssetsPageServlet` servește `/libs/studio/assets/app/index.html` dacă există, altfel aplicația editorului
(`/libs/studio/editor/app`, care conține aceleași ecrane după următorul `npm run package`). Pachetul separat se face cu
`npm run package:assets` în `author-ui` (`dist-package/studio-assets-app.zip`); se copiază peste
`crx-quickstart/install/studio-assets-app-1.0.0.zip`, mereu în același fișier.

API-ul folosit de consolă, `/bin/studio/dam.json`:

| Metodă | `op` | Parametri |
|---|---|---|
| GET | `list` | `path`, `sort` (`name`, `title`, `modified`, `size`, `type`), `order`, `offset`, `limit` |
| GET | `asset` | `path`: metadate, rendition-uri, erori |
| GET | `folders` | `path`: subfolderele (pentru selectorul de destinație) |
| POST | `createFolder` | `parent`, `title`, opțional `name` |
| POST | `delete`, `reprocess` | `path` (repetat) |
| POST | `move` | `path` (repetat), `dest`, opțional `name` (redenumire, un singur element) |
| POST | `copy` | `path` (repetat), `dest`; numele care există deja primesc sufix (`a1.jpg`) |
| POST | `metadata` | `path` și `dc:title`, `dc:description`, `dc:language`, `dc:rights`, `dc:subject`, `dc:creator`, `dc:contributor` (multi-valoare, `<prop>@Delete` le golește) |
| POST | `folderTitle` | `path`, `title` |
| POST | `edit` | `path`, `operations` (JSON: `crop` în fracții, `rotate` multipli de 90, `flip`), `imageMap` (JSON cu zonele, în fracții; `[]` îl șterge) |
| GET | `search` | `path` (folderul), `fulltext`, `type` (repetat: image, video, audio, pdf, document, archive, fragment, file), `tag` (repetat), `modified` (`7d`), `minSize`, `maxSize`, `orientation`, `sort`, `order`, `offset`, `limit` |
| GET | `versions` | `path`: versiunile, cele noi întâi |
| POST | `createVersion` | `path`, opțional `label`, `comment` |
| POST | `restoreVersion` | `path`, `version` |
| POST | `checkout`, `checkin` | `path` (repetat) |
| POST | `relate` | `path` (repetat, minim 2) |
| POST | `unrelate` | `path`, `other` (repetat) |
| POST | `extractArchive` | `path`, `into=folder` (opțional), `conflict` (`skip`, `rename`, `replace`) |
| POST | `importMetadata` | `csv` (multipart) |
| GET | `profiles` | opțional `path`: profilele și cel care se aplică folderului |
| POST | `saveProfile` | `name`, `title`, `renditions` (JSON) |
| POST | `deleteProfile` | `name` |
| POST | `folderProfile` | `path`, `profile` (gol îl scoate) |
| GET | `collections` | colecțiile vizibile utilizatorului |
| GET | `collection` | `path`: colecția și elementele ei (la smart: rezultatul căutării) |
| POST | `createCollection` | `title`, `description`, `public`; pentru smart `scope` și `query` (JSON cu filtrele căutării) |
| POST | `updateCollection`, `deleteCollection` | `collection`, plus `title`, `description`, `public` |
| POST | `addToCollection`, `removeFromCollection` | `collection`, `path` (repetat) |
| GET | `schemas` | opțional `path`: schemele și cea care se aplică acolo |
| POST | `saveSchema` | `name`, `title`, `definition` (JSON `{"tabs": [...]}`) |
| POST | `deleteSchema` | `name` |
| POST | `folderSchema` | `path`, `schema` (gol o scoate) |
| POST | `folderThumbnail` | `path`, `asset` (un asset din folder; gol revine la colajul automat) |
| GET | `duplicates` | `sha1`: asset-urile cu același conținut (maxim 10) |
| POST | `metadataFields` | `path`, `fields` (JSON: text, număr, boolean, listă, `{"date": "2027-01-31"}`, `null` șterge) |

Alte adrese: `POST /bin/studio/dam/upload` (`folder`, `file`, opțional `conflict` = `rename`, `replace`, `version`;
fără el, un nume existent → 409), `GET /bin/studio/dam/download.zip?path=…[&renditions=true][&separate=true]`,
`GET /bin/studio/dam/metadata.csv?path=…[&recursive=true][&property=…]`, Share Link: `POST /bin/studio/dam/linkshare.json`
(`op=create`, `path` repetat, `days`, `renditions`; `op=revoke`, `id`), `GET /bin/studio/dam/linkshare.json[?path=…]`
(linkurile proprii, fără token), `GET /linkshare.html?sh=…`, `GET /bin/studio/dam/linkshare.zip?sh=…`,
`GET /bin/studio/dam/linkshare.file?sh=…&path=…&r=original|thumbnail|web`. La `metadata`, `cq:tags` e tot multi-valoare
(ID-uri `ns:a/b`), iar `<prop>@Append=true` adaugă în loc să înlocuiască.

Toate cererile POST (inclusiv upload-ul) cer antetul `X-Studio-CSRF: 1`. Un formular de pe alt site nu poate trimite
antete proprii, deci nu poate declanșa ștergeri sau mutări în numele utilizatorului logat. Fiecare operație rulează cu
sesiunea utilizatorului, deci ACL-urile din Oak decid ce poate vedea și modifica. Pe `/assets.html`, anonimul e
trimis la login.

La `edit`, `studio-dam` aplică operațiile pe original (`POST /edit`, vezi `dam-service/README.md`), Sensible Studio scrie rezultatul
peste rendition-ul `original`, iar rendition-urile se regenerează ca după un upload. Nu se face o versiune înainte:
originalul vechi rămâne doar în store-ul `studio-dam`. Image map-ul se salvează în `jcr:content/imageMap`, în notația
Core Components (`[rect(x1,y1,x2,y2)"href"|"target"|"alt"|(fracții)]`). Link-urile `javascript:`, `data:` și `//…`
sunt refuzate, pentru că map-ul ajunge în pagini.

### Procesul `studio-dam`

Configurația `io.sensible.studio.dam.impl.DamServiceManager` (implicit `embedded`, fără niciun fișier):

| Mod | Ce face |
|---|---|
| `embedded` | Sensible Studio pornește `crx-quickstart/dam/bin/studio-dam` doar pe loopback (`127.0.0.1:4510`), cu tokenul din `crx-quickstart/dam/token`. Verifică la pornire că procesul care răspunde știe tokenul (challenge HMAC), deci un alt program care ocupă portul nu primește nicio cerere. Îl repornește dacă moare (backoff 1 s → 60 s) și îl oprește cu SIGTERM când se oprește Sensible Studio. Log: `crx-quickstart/logs/dam-service.log`. |
| `external` | Folosește un serviciu pornit separat (Docker, Kubernetes): `url` și `token` obligatorii. Pentru alt host, folosiți `https`; pe `http` tokenul circulă necriptat (Sensible Studio avertizează în log). |
| `disabled` | Fără procesare; joburile așteaptă. |

`./bin/studio start` compilează `dam-service` (dacă e instalat Rust) și copiază binarul în `crx-quickstart/dam/bin/`.
Fără Rust, Sensible Studio pornește normal, doar că asset-urile rămân în `processing`.

### Indexul Lucene

Căutarea și verificarea duplicatelor folosesc un index Oak Lucene propriu, `/oak:index/studioDamAsset` (`DamIndex`,
modulul `oak-lucene` din Apache Jackrabbit Oak, inclus în Sling Starter):

- **ce indexează**: pentru `dam:Asset` titlul, descrierea, cuvintele cheie, creatorul, drepturile și tag-urile (text),
  plus `dc:format`, `dam:sha1`, `dam:size`, `jcr:content/jcr:lastModified` (valori exacte, ultimele două sortabile);
  pentru foldere titlul. Prin agregare intră și textul originalului, extras de Apache Tika: căutarea găsește cuvinte
  din interiorul PDF-urilor (și Office, text);
- **actualizare**: asincronă (lane-ul `async` al Oak) plus `nrt`; o modificare apare în căutare în ~0,5 s;
- **instalare**: `DamIndexInstaller`, la pornirea bundle-ului, cu service user-ul `studio-dam-indexer` (are doar dreptul
  de a administra definiții de index sub `/oak:index`). Dacă definiția lipsește sau e mai veche decât
  `DamIndex.VERSION`, o înlocuiește, iar Oak reindexează. Când Oak termină (șterge `reindex`), scrie `ready = true` în
  `/var/studio/dam/index`, citibil de toți;
- **interogări** (`DamIndexQueries`): JCR-SQL2 cu sesiunea utilizatorului (Oak lasă deoparte ce nu are voie să vadă),
  valori prin bind variables, `OPTION(INDEX TAG studioDam)`. Tipul de fișier și orientarea se filtrează după aceea în Java;
- **rezervă**: cât timp indexul nu e gata (sau o interogare eșuează), `DamSearch` parcurge arborele, cu aceleași
  rezultate, doar mai încet și fără textul din fișiere.

Pe WKND: căutările răspund în 10–90 ms, verificarea duplicatelor în ~6 ms. `DamIndexOakTest` pornește un Oak în
memorie cu Lucene, instalează definiția și verifică planurile de execuție (indexul e folosit) și rezultatele.

### Teste

`mvn install` rulează testele unitare. Cu `STUDIO_DAM_BINARY=/cale/spre/studio-dam`, `EndToEndTest` pornește binarul real
prin managerul de proces, procesează `freeride.jpg` din WKND și verifică dimensiunile față de ce a produs AEM, apoi
omoară procesul și verifică repornirea. Tot acolo se testează livrarea prin servlet, stabilitatea URL-urilor după
repornire și refuzul unui alt program care ocupă portul.

## MSM (Multi Site Manager)

Pachetul `io.sensible.studio.wcm.impl.msm` din `studio-cq-impl`, cu API-ul `com.day.cq.wcm.msm.api` din `studio-cq-api`.
Structura din JCR e cea din AEM, observată pe conținutul WKND (17 live copies) și pe AEM 6.5 (:5502):
`jcr:content/cq:LiveSyncConfig` (`cq:master`, `cq:isDeep`, `cq:rolloutConfigs`, `cq:excludedPaths`), mixin-ul
`cq:LiveRelationship` și `cq:lastRolledout` pe nodurile moștenite, `cq:LiveSyncCancelled` (+ `cq:isCancelledForChildren`)
și `cq:PropertyLiveSyncCancelled` (+ `cq:propertyInheritanceCancelled`).

- **Relații** (`LiveRelationshipManagerImpl`, `Relationships`): calculate doar din conținut. O cale aparține live copy-ului
  celui mai apropiat `cq:LiveSyncConfig` de deasupra ei; sursa e aceeași cale relativă sub `cq:master`. Mixin-ul singur
  nu face o pagină live copy (WKND are pagini copiate care îl păstrează). `LiveCopyIndex` ține lista master → live copies,
  invalidată la schimbări. Core Components (Navigation, Search, Experience Fragment) primesc relații reale.
- **Rollout** (`RolloutManagerImpl`): acțiunile config-urilor rulează pe fiecare pereche sursă/țintă din `jcr:content`,
  apoi, la rollout deep, pe paginile copil. Nodurile locale (fără mixin) nu se schimbă și nu se șterg, cele anulate se
  sar, proprietățile anulate își păstrează valoarea. O pagină locală cu numele unei pagini noi din blueprint devine
  `<nume>_msm_moved`, ca în AEM. Reset-ul anulează întâi anulările.
- **Acțiuni** (`ActionFactories`, `SyncActions`): `contentUpdate`, `contentCopy`, `contentDelete`, `orderChildren`,
  `referencesUpdate` (rescrie căile spre blueprint în căi spre live copy, cu `References`). Sunt servicii
  `LiveActionFactory`, deci un proiect își poate aduce acțiunile lui. Excluderile implicite și PID-urile de configurare
  sunt cele din AEM (`com.day.cq.wcm.msm.impl.actions.ContentUpdateActionFactory` etc.,
  `com.day.cq.wcm.msm.impl.RolloutManagerImpl`), așa că o configurație OSGi existentă se aplică neschimbată.
- **Config-uri de rollout:** `studio-compat-content/studio-msm-content` pune la `/libs/msm/wcm/rolloutconfigs` config-urile
  standard (`default`, `pushonmodify`, `activate`, `deactivate`), cu texte proprii. `cq:trigger` are valorile din AEM:
  `rollout`, `modification`, `publish`, `deactivate`.
- **Push on modify** (`PushOnModify`): o schimbare într-o pagină blueprint, după 2 s de liniște, devine un job
  `studio/msm/rollout` (coada `Sensible Studio MSM rollouts`), rulat cu utilizatorul de serviciu `studio-msm-service`.
- **HTTP** (`MsmServlet`, `/bin/studio/msm.json`): `GET op=status|overview|configs|liveCopies`, `POST op=rollout|sync|cancel|
  reenable|suspend|resume|cancelProperty|reenableProperty|detach|create`. `EditMeta` scrie `data-studio-msm` în editor,
  iar `/bin/studio/sites.json` dă `liveCopy`/`liveCopyRoot` pentru consola Sites.

- **Referințe spre XF și CF** (`referencesUpdateXF`, `referencesUpdateXFEmbedded`, `referencesUpdateCF`):
  - o legătură spre un experience fragment sau content fragment din blueprint trece la fragmentul live copy-ului,
    dacă acesta există;
  - partea de site/limbă se găsește lăsând deoparte segmentele finale comune ale celor două căi:
    `/content/wknd/language-masters/en` → `/content/wknd/us/en` mapează
    `/content/experience-fragments/wknd/language-masters/…` pe `…/wknd/us/…`;
  - `personalizationContentRollout` și `updateField` sunt acceptate fără efect, ca să ruleze neschimbate și
    config-urile copiate din AEM.
- **Ordinea acțiunilor** e fixă, oricum ar fi ordonate nodurile config-ului:
  1. `targetVersion`;
  2. copiere, actualizare, ștergere;
  3. acțiunile proiectului;
  4. rescrierea legăturilor.
- **Versiuni de pagină.**
  - `PageManager.createRevision`, `getRevisions` și `restore` (`PageRevisions`) lucrează pe versiuni JCR ale
    `jcr:content`, cu `cq:versionComment` și `cq:versionCreator`. O etichetă deja folosită e refuzată înainte să se
    salveze ceva.
  - Restaurarea salvează întâi starea curentă ca versiune și e refuzată pe o pagină blocată de alt utilizator. Ce nu
    e conținut rămâne cum e acum: `cq:LiveSyncConfig` (o versiune veche nu poate lega pagina din nou de un blueprint și
    nici nu o poate detașa), lock-ul, starea de replicare și ultimul rollout.
  - Config-ul `versioned` („Standard rollout with versions”, acțiunea `targetVersion`) păstrează paginile dinainte de
    rollout, deci un rollout se poate anula. Versiunile se iau toate la început, înainte de orice scriere, pentru
    paginile moștenite pe care rollout-ul le poate schimba (nu cele locale, suspendate sau din live copy-uri imbricate).
    Așa rollout-ul rămâne o singură salvare și, dacă eșuează, nu rămâne nimic pe jumătate. Rezultatul le listează în
    `versioned`.
  - Endpoint: `GET /bin/studio/msm.json?op=revisions&path=`, `POST op=restore&revision=`.
- **Blueprint configs și Create Site** (`BlueprintSites`).
  - Un blueprint e o pagină cu `sling:resourceType=wcm/msm/components/blueprint` și `sitePath`. Se caută în
    `/apps/msm` (WKND aduce `wknd_blueprint`), `/libs/msm` și `/etc/blueprints`.
  - `studio-msm-content` adaugă blueprint-ul pentru experience fragments.
  - Create Site produce exact structura observată pe AEM 6.5: rădăcina și fiecare limbă sunt live copy-uri shallow,
    fiecare capitol e un live copy deep, iar `cq:siteOwner` se pune pe rădăcină. Fără „Live Copy” (debifat implicit,
    ca în AEM), se fac copii simple, fără nicio urmă MSM, chiar dacă site-ul blueprint conține el însuși live copy-uri.
  - Un blueprint nou din wizard se creează odată cu site-ul (`newBlueprintSite`, `newBlueprintTitle`), deci un wizard
    abandonat nu lasă nimic în urmă.
  - Endpoint-uri: `op=blueprints`, `op=blueprint&path=` (sau `&sitePath=` pentru un site fără blueprint),
    `POST op=createSite`, `op=createBlueprint` (scrie în `/apps/msm`).

Siguranță:
- **Fără bucle.** Nu se face rollout spre un live copy aflat în blueprint-ul lui sau în jurul lui. Live copy-urile
  live copy-ului curent, găsite în blueprint, se sar. Nu se poate crea un live copy în propriul blueprint sau în lanțul
  de blueprint-uri al sursei.
- **Căi validate.** Căile primite trebuie să fie sub `/content`, fără `.`/`..`, iar numele nu pot conține `/`.
- **`cq:master` protejat.** Un POST Sling care creează, schimbă, copiază sau mută un `cq:LiveSyncConfig` eșuează dacă
  autorul nu poate citi blueprint-ul (`MsmConfigGuard`). Se caută prin pagini, `jcr:content` și foldere, deci o mutare
  sau copiere a unui arbore mare de site îl parcurge tot, prețul pentru a nu lăsa nimic să treacă. Un restore de versiune
  prin Sling (`:operation=restore`) e deja aplicat în repository când rulează verificarea, așa că acolo config-ul cu un
  blueprint necitibil se șterge (pagina e detașată) în loc să fie refuzat. Scrierile pe alte căi (Package Manager, JCR
  direct) rămân în grija celor care au acces la ele, de regulă administratorii.
- **Push on modify cu drepturile autorului.** Rulează cu `studio-msm-service`, dar doar spre live copy-urile pe care
  autorul schimbării le-ar putea modifica, și doar dacă autorul poate citi pagina blueprint. Schimbările fără autor nu
  se trimit.
- **Conținutul local e păstrat.**
  - Re-enable, Resume și Synchronize refuză conținutul local.
  - Un container moștenit, șters din blueprint, rămâne (ca local) dacă autorii au adăugat componente în el.
  - `referencesUpdate` nu atinge componentele locale.
  - Copiile, fie prin Sling POST `:operation=copy`, fie prin `PageManager.copy`, pierd markerii MSM (`MsmCopies`), deci
    sunt locale. Excepția e copia unei rădăcini de live copy, care rămâne live copy.

Instalare: `studio-cq-api`, `studio-cq-impl`, configurațiile `*~studio-msm.cfg.json` din `studio-compat-config` și pachetul
`studio-msm-content` în `crx-quickstart/install/`. Pachetul cere tipul `cq:RolloutConfig` din `studio-cq-api`: instalat odată
cu un `studio-cq-api` mai vechi, eșuează fără reîncercare; atunci se reinstalează cu o versiune nouă.

Teste: `LiveRelationshipsTest`, `RolloutTest`, `MsmServletTest`, `TriggerTest`, `SafetyTest`, `BlueprintSitesTest`, `MsmConfigGuardTest`, `PageRevisionsTest` (Oak real), pe fixture-ul
`src/test/resources/msm/wknd-msm-content.json` generat de `tools/observe_msm.py` din conținutul WKND (doar date).

Lipsesc: acțiunile `targetActivate`/`targetDeactivate` (Sensible Studio nu are replicare), traducerea și rularea ca job a
rollout-urilor manuale foarte mari.

## Scannerul de compatibilitate

```bash
java -jar studio-compat-scanner/target/studio-compat-scanner-0.1.0-SNAPSHOT.jar \
     --studio studio-cq-api/target/studio-cq-api-0.1.0-SNAPSHOT.jar \
     path/to/aem-guides-wknd/all/target/aem-guides-wknd.all-*.zip
```

Acceptă jar-uri, pachete de conținut (bundle-urile încorporate sunt găsite automat) sau directoare. Pentru fiecare pachet `com.day.*` / `com.adobe.*` importat afișează intervalul de versiuni cerut și statusul:

- `OK`: Sensible Studio îl exportă
- `MISSING`: trebuie implementat
- `in-project`: îl exportă chiar proiectul
- `deploy-oss-bundle`: e un bundle Adobe open source (de exemplu Core Components), care se instalează ca atare

Ieșirea are codul 1 dacă lipsește ceva obligatoriu, deci merge folosit și în CI.

## Workflow

Modelele se editează în `author-ui` (`/workflow/models.html`, editorul la `/editor.html/conf/global/settings/workflow/models/<m>.html`).
Formatul e cel din AEM, ca modelele exportate de acolo să meargă:

- **Modelul de design** e o pagină `cq:Page` (`sling:resourceType` `cq/workflow/components/pages/model`) cu pașii în
  `jcr:content/flow`. Configurarea fiecărui pas stă în `metaData` (`PROCESS`, `PROCESS_ARGS`, `PROCESS_AUTO_ADVANCE`,
  `PARTICIPANT`, `DYNAMIC_PARTICIPANT`, `TARGET_NODE_PATH`). OR/AND split au `branches` și ramurile `1`…`5`
  (`cq/flow/components/parsys`); pentru OR, `metaData/default<i>` și `metaData/rule<i>`.
- **Sync** scrie modelul runtime: noduri START, PROCESS, PARTICIPANT, DYNAMIC_PARTICIPANT, OR_SPLIT/OR_JOIN,
  AND_SPLIT/AND_JOIN, END și tranzițiile (cu `isDefault` și `rule`). Versiunea crește la fiecare sync (1.0, 1.1, ...).
  Modelul de design e marcat `studio:modified` până la următorul sync.
- **Instanțele** stau la `/var/workflow/instances/server0/<zi>/<model>_<n>`, cu `data` (payload), `metaData` și
  `workItems`. Stări: RUNNING, COMPLETED, ABORTED, SUSPENDED; work item-urile sunt ACTIVE, COMPLETED, FAILED sau
  TERMINATED.
- **Motorul** rulează în joburi Sling (topicul `studio/workflow/event`, coada ordonată `Sensible Studio workflow engine`), cu un lock
  pe instanță. Un pas Process apelează serviciul `WorkflowProcess` găsit după `process.label` sau numele clasei.
  Dacă nu există sau aruncă excepție, work item-ul devine FAILED și poate fi reluat (`retry`).
  - Cu `PROCESS_AUTO_ADVANCE=false`, procesul își termină singur pasul.
  - Un pas Participant creează un work item ACTIVE pentru user sau grup. Îl termină doar acel user, un membru al
    grupului sau un administrator, alegând ruta (ramura OR care urmează).
  - Dynamic Participant întreabă `ParticipantStepChooser`-ul ales.
  - Goto sare la `TARGET_NODE_PATH` cât timp regula ține.
  - **Regulile:** gol sau `true` țin, `false` nu. Scripturile ECMA **nu se execută** (Sensible Studio nu are motor de scripting
    pentru reguli) și contează ca false, deci la OR se ia ramura implicită. Singura excepție recunoscută e
    `function check(){return true;}`.
- **Procese incluse:** No Operation, Log Message (`PROCESS_ARGS` în log), Set Payload Properties (`nume=valoare`,
  separate prin virgulă sau rând nou, scrise pe `jcr:content` al payload-ului sau pe payload).
- **Utilizatorul de serviciu** `studio-workflow-service` (subserviciul `workflow`) e creat de repoinit la pornire. Până
  la prima repornire după instalare, motorul folosește un resolver administrativ: bundle-ul e în whitelist-ul
  `studio-workflow` doar pentru asta.
- **Launcherele** sunt la `/conf/global/settings/workflow/launcher/config/<nume>` (cele implicite la
  `/libs/settings/workflow/launcher/config`), în formatul din AEM: `eventType` (1 creat, 16 modificat, 2 șters), `glob`
  (expresie regulată pentru tot path-ul), `nodetype`, `conditions` (`nume==valoare`, `nume!=valoare`, `nume!=` = are
  valoare; numele poate fi un path relativ), `excludeList` (proprietăți a căror schimbare singură nu pornește nimic și
  `event-user-data:changedByWorkflowProcess`), `workflow`, `enabled`, `runModes`.
  - `LauncherListener` ascultă `/content`, `/conf` și `/etc`.
  - Un launcher nu pornește modelul pe un payload care are deja o instanță a lui în rulare sau suspendată, sau una
    terminată în ultimele 10 secunde. Astfel, scrierile unui workflow în propriul payload nu îl repornesc. Sling nu dă
    `event-user-data`, deci excluderea după utilizator merge doar după ce există utilizatorul de serviciu.
  - Run mode-ul nu se verifică: Sensible Studio e o instanță de autor, așa că pornesc launcherele cu `author` sau fără run mode.
- **Modele implicite:** `studio-workflow-content` aduce „Request for Review”. Un administrator aprobă sau cere modificări,
  iar decizia ajunge în `studioReviewStatus` pe payload. Modelele implicite fără model runtime se sincronizează singure
  la prima listare. **Edit** pe un model implicit îl copiază în `/conf/global` (`op=copyModel`).
- **Întreținere** (`WorkflowMaintenance`, rulează în fiecare minut prin Sling Scheduler):
  - Pașii participant cu `timeoutMillis` (secunde, ca în dialogurile AEM) și `timeoutHandler` = Auto Advancer
    (`com.adobe.granite.workflow.console.timeout.autoadvance.AutoAdvancer`) au `dueTime`. Când expiră, se termină
    pe ruta implicită, ca utilizatorul `workflow-timeout`.
  - Instanțele modelelor `transient` se șterg la un minut după ce s-au terminat. Nu imediat, fiindcă launcherele se
    uită la instanțele terminate recent.
  - Instanțele terminate sau încheiate mai vechi de `purge.days` (implicit 30; 0 = niciodată) se șterg, împreună cu
    folderul zilei rămas gol. Se configurează în OSGi, PID `io.sensible.studio.workflow.impl.WorkflowMaintenance`.
- **Etape** (`cq:workflowStages` pe `jcr:content` al modelului; fiecare pas are `metaData/workflowStage`):
  - Sync copiază lista în `metaData/workflowStages` al modelului runtime.
  - Work item-urile primesc `workflowStage`.
  - `op=instance` / `op=instances` / `op=inbox` întorc `stages` și `stage`: etapa pasului curent sau, la final, a
    ultimului pas.
- **Timeout pe pașii process:** un pas process cu `PROCESS_AUTO_ADVANCE=false`, care așteaptă să-l termine procesul,
  primește `dueTime` la fel ca un pas participant.
- **Setările de curățenie** se schimbă și din UI:
  - `op=maintenance` (GET) dă `purgeDays`, ultima rulare, câte instanțe încheiate sunt și cea mai veche.
  - `op=setPurge&days=` scrie `purge.days` în configurația OSGi a `WorkflowMaintenance`, prin ConfigurationAdmin.
  - `op=purgeNow&days=` curăță imediat.
  - Ambele cer administrator.
- **Inbox-ul și instanțele:**
  - `delegate` mută un pas activ la alt user sau grup. Pot face asta asignatul, membrii grupului lui și
    administratorii.
  - `deleteInstance` șterge instanțele terminate sau încheiate; doar administratorii.
  - Un pas nu se poate termina cât workflow-ul e suspendat.
  - Userul `admin` contează ca membru al grupului `administrators`, chiar dacă în Sling nu e.

## Reguli clean-room

1. Semnăturile (nume de pachete, clase, metode) se iau din documentația publică și din codul open source care le folosește.
2. Implementarea se scrie din comportamentul observabil și din formatul conținutului. Nu se decompilează și nu se citește cod Adobe.
3. Textul Javadoc Adobe nu se copiază. Comentariile din cod sunt scrise de noi.
4. Ca să aflăm versiunile, citim manifestele bundle-urilor *proiectului* (WKND), nu ale produsului AEM.

## Rulare pe Sling Starter

Mediu testat: Sling Starter 12 (pornit de `studio-quickstart`) pe **JDK 21**. Totul se instalează prin
`crx-quickstart/install/` (file installer-ul Sling), în ordinea:

1. **Dependențe open source** care lipsesc sau sunt prea vechi în Sling Starter 12: `commons-lang3 3.17.0`,
   `jackson-annotations 2.17.3`, `sling.models.api 1.5.4` + `sling.models.impl 1.6.4`, `commons-imaging 1.0-alpha3`,
   servicemix `commons-httpclient 3.1_7` și `json 20090211_2`, `oak-authorization-cug 1.42.0`.
2. **Bundle-urile Sensible Studio**: `studio-cq-api`, `studio-cq-impl`, `studio-security`, `studio-tagging`, `studio-workflow`, `studio-siterail`.
3. **Configurații** (`studio-compat-config/*.cfg.json`) și CUG pe `/content`
   (`CugConfiguration.cfg.json`, plus PID-ul ei în `SecurityProviderRegistration.requiredServicePids`). Schimbarea
   providerului de securitate cere o **repornire**.
4. **Pachete Sensible Studio**: `studio-wcm-foundation`, markerul `cq-platform-content`, `studio-workflow-content` și `studio-editor` (editorul de pagini, construit cu `npm run package` din `author-ui`).
5. **Core Components**: `core.wcm.components.all-2.33.0.zip`, plus subpachetele `content` și `config` instalate direct.
6. **WKND**: `aem-guides-wknd.all-4.0.4.zip`. Subpachetele care eșuaseră înainte de corecturi trebuie reinstalate
   cu altă versiune: installer-ul nu reîncearcă un pachet eșuat.

Recomandare: trecerea `studio-quickstart` la Sling Starter 14 pe JDK 21. Ar elimina majoritatea dependențelor de la pasul 1.

