# Configurații doar pentru instanța de publish

Se copiază în `install/` al instanței de publish (`bin/studio-publish` o face). `studio-publish` dă drept de citire
anonimă pe `/content`, `/conf`, `/etc`, `/apps`, `/libs` (paginile se randează fără login), mai puțin agenții de
replicare. Pe publish nu se pune `RepositoryInitializer~studio-start` (redirectul `/content` → ecranul de start al
autorului).

`io.sensible.studio.dam.impl.DamServiceManager`: publish-ul își pornește propriul `studio-dam` (serviciul de imagini)
pe portul 4511, ca să nu se calce cu cel al autorului (4510); fără el `/is/image/…` răspunde 503 pe publish.
Binarul se ia din `instances/author/dam/bin` (`bin/studio-publish setup`). `…AssetChangeListener` cu `enabled=false`:
fișierele sosesc procesate de pe autor și nu se mai procesează o dată pe publish.

`io.sensible.studio.auth.impl.LoginHandler` (bundle `studio-auth`): login-ul vizitatorilor valabil pe toate
instanțele de publish. După ce depozitul acceptă numele și parola, vizitatorul primește cookie-ul `studio-login`,
semnat cu cheia din `login.key` (în folderul instanței, scris la prima pornire, citibil doar de proprietar). Orice
instanță cu aceeași cheie îl acceptă, deci **`login.key` se copiază de pe prima instanță pe celelalte**
(`cp -p instances/publish/login.key instances/publish2/login.key`; se observă fără restart). Cine are fișierul se
poate autentifica drept orice vizitator: nu se pune în git și nici în backup-uri citite de alții. Utilizatorul
trebuie să existe pe instanța care primește cookie-ul. Fără această configurare (pe author) bundle-ul nu face nimic.
Cookie-ul poartă și o amprentă a hash-ului parolei (nu parola): după o schimbare de parolă, pe orice instanță
(sincronizarea userilor o duce pe celelalte), cookie-urile făcute înainte nu mai sunt primite. La login, amprenta se
citește cu userul de serviciu `studio-auth-service` (`RepositoryInitializer~studio-auth`: doar citire pe `/home`;
`ServiceUserMapperImpl.amended~studio-auth`).
