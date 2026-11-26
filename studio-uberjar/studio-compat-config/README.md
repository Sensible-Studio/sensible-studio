# Configurații OSGi de compatibilitate

`org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-compat.cfg.json` înregistrează namespace-urile
`crx`, `cq`, `dam`, `granite` și privilegiul `crx:replicate`, pe care le folosesc ACL-urile din pachetele AEM
(de exemplu `/conf/wknd/sling:configs/rep:policy`). Se pune în `crx-quickstart/install/`.

`org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~studio-wcm.cfg.json` mapează subserviciul
`clientlibs` al `io.sensible.studio.cq.impl` pe utilizatorul de serviciu `studio-wcm-service`, creat de repoinit.
Același repoinit creează și `clientlibs-service` și `sling-scripting`, pe care îi cer Core Components prin
propria lor configurație de mapare. Toți trei au doar drept de citire pe `/apps` și `/libs`.

Repoinit creează și grupul `studio-compat-groups-root`, doar ca să existe folderul `/home/groups` creat corect de
Oak (`rep:AuthorizableFolder`). Pachetele care aduc grupuri proprii (de exemplu `wkndmembers` din WKND) se
instalează sub el. Fără folder, FileVault creează nodul intermediar cu un tip greșit, iar importul eșuează.

## DAM

`org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-dam.cfg.json` înregistrează namespace-urile în care scrie
procesarea DAM (`studio`, `dc`, `xmp`, `xmpMM`, `xmpRights`, `tiff`, `exif`, `exifEX`, `psAux`, `photoshop`, `Iptc4xmpCore`,
`Iptc4xmpExt`, `prismusagerights`, `pdf`). În Oak, o proprietate dintr-un namespace neînregistrat face ca tot commit-ul
să eșueze, iar un test din `studio-dam` verifică că lista e completă. Același repoinit creează `/content/dam` și utilizatorul
de serviciu `studio-dam-processor`, cu `jcr:read` și `rep:write` doar pe `/content/dam`. Namespace-ul `studio` îl folosește
și lock-ul de pagină (`studio:lockOwner`), care până acum nu avea namespace înregistrat.

`org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~studio-dam.cfg.json` mapează subserviciul
`dam-processor` al bundle-ului `io.sensible.studio.dam` pe `studio-dam-processor`.

`org.apache.sling.event.jobs.QueueConfiguration~studio-dam.cfg.json` definește coada `Sensible Studio DAM processing` pentru topicul
`studio/dam/process`: 2 joburi în paralel, 60 de reîncercări la 30 de secunde, ca asset-urile urcate cât timp `studio-dam`
e oprit să fie procesate când pornește.

## Workflow

`org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-workflow.cfg.json` creează `/var/workflow/models`,
`/var/workflow/instances`, `/conf/global/settings/workflow/models` și utilizatorul de serviciu `studio-workflow-service`:
`jcr:all` pe `/var/workflow`, citire și scriere pe `/content` și `/conf`, citire pe `/libs`, `/apps` și `/home`.
Repoinit rulează la pornirea repository-ului, deci utilizatorul există abia după o repornire.

`org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~studio-workflow.cfg.json` mapează subserviciul
`workflow` al `io.sensible.studio.workflow` pe `studio-workflow-service`.

`org.apache.sling.jcr.base.internal.LoginAdminWhitelist.fragment~studio-workflow.cfg.json` permite bundle-ului
`io.sensible.studio.workflow` un resolver administrativ. E folosit doar cât timp utilizatorul de serviciu nu există încă.

`org.apache.sling.event.jobs.QueueConfiguration~studio-workflow.cfg.json` definește coada ordonată `Sensible Studio workflow engine`
pentru topicul `studio/workflow/event`, cu 3 reîncercări.

## Pagina de start și login

`org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-start.cfg.json` face ca rădăcina instanței (`/`) să redirecționeze
spre `/studio/start.html`, cum face AEM spre `/aem/start.html`. Pune `sling:resourceType = sling:redirect` și
`sling:target = /studio/start.html` pe `/` și pe `/content` (pentru un vizitator anonim, `/` se rezolvă la `/content`), în
locul paginii `/starter.html` din Sling Starter. Repoinit rulează la fiecare pornire, deci valoarea rămâne și dacă
pachetul de conținut al Sling Starter o rescrie.

`org.apache.sling.auth.form.FormAuthenticationHandler.cfg.json` trimite autentificarea prin formular la pagina de login
a Sensible Studio, la URL-ul din AEM: `/libs/granite/core/content/login.html?resource=<pagina următoare>` (`LoginPageServlet` din
`studio-cq-impl`). Formularul trimite la `.../login.html/j_security_check`; cu JavaScript verifică întâi parola
(`j_validate`) și arată eroarea pe loc. `LoginRedirectFilter` trimite tot acolo vizitele formularului Sling
(`/system/sling/form/login`), unde încă trimit unele ecrane Sensible Studio. După login se merge doar la căi locale (nu la alte
site-uri).

## Rail-urile din Sites

`org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-siterail.cfg.json` creează `/var/studio/comments`,
`/var/studio/deleted`, `/var/studio/searches` (căutările salvate din Filter, per utilizator), `/var/studio/kept` (copiile
paginilor șterse în afara consolei, cum erau) și utilizatorul de serviciu `studio-siterail-service`: citire și scriere pe
`/var/studio/comments`, `/var/studio/deleted` și `/var/studio/searches`, citire, scriere și `jcr:nodeTypeManagement` pe
`/var/studio/kept` (copia păstrează tipurile nodurilor), citire pe `/var/workflow`, citire și `jcr:versionManagement` pe
`/jcr:system/jcr:versionStorage` (găsește paginile șterse în afara consolei după versiunile lor), iar pe `/content` citire, scriere, `jcr:versionManagement` și
`jcr:nodeTypeManagement` (versiunea luată înainte de ștergere și pagina adusă înapoi din versiune). Așa autorii nu au
nevoie de drepturi pe `/var` sau pe versiunile paginilor șterse: `studio-siterail` verifică întâi cu sesiunea lor că pot
citi pagina, o pot șterge sau pot crea pagina la loc, și abia apoi scrie ca serviciu. Pe instanța de dev, Sling a aplicat
repoinit-ul imediat ce a apărut configurația, fără repornire. Fără utilizatorul de serviciu totul merge ca utilizatorul
logat, ca înainte.

`org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~studio-siterail.cfg.json` mapează subserviciul
`siterail` al `io.sensible.studio.siterail` pe `studio-siterail-service`.

## Traducere

`org.apache.sling.jcr.repoinit.RepositoryInitializer~studio-translation.cfg.json` creează `/var/studio/translation/pages` și
utilizatorul de serviciu `studio-translation-service`, cu `jcr:read` și `rep:write` doar pe `/var/studio/translation`. Acolo
stau înregistrările copiilor de limbă ale paginilor (sursa, hash-urile textelor, starea, cine a tradus și aprobat), în
afara paginilor. Paginile se scriu tot cu sesiunea utilizatorului; înregistrarea se scrie cu utilizatorul de serviciu
doar după ce utilizatorul a putut scrie copia (Approve cere drept de modificare pe copie). Fără utilizatorul de serviciu
totul merge ca utilizatorul logat.

`org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~studio-translation.cfg.json` mapează subserviciul
`translation` al `io.sensible.studio.translation` pe `studio-translation-service`.

## Parametrii cererilor în UTF-8

`org.apache.sling.engine.parameters.cfg.json` pune `sling.default.parameter.encoding` pe `UTF-8`. Fără ea, Sling citește
parametrii din URL și din formularele trimise fără `_charset_` ca ISO-8859-1, iar antetul `Content-Type: …;charset=UTF-8`
nu schimbă asta: „ăș× Pagină” trimis din interfață se salva „ÄÈÃ PaginÄ”. Browserele codifică mereu în UTF-8, deci
setarea acoperă toate cererile (inclusiv textul din căutări), nu doar cele care trimit `_charset_`.
