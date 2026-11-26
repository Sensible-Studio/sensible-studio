# Pachete de compatibilitate

`cq-platform-content/`: pachet-marker gol. Pachetele de conținut Core Components declară o dependență
de `day/cq60/product:cq-platform-content:1.3.248`, iar FileVault refuză instalarea fără ea. Markerul
satisface doar declarația și nu conține nimic de la Adobe.

Construire (zip simplu, rădăcina arhivei = conținutul folderului):

```bash
cd cq-platform-content && zip -r ../studio-compat-cq-platform-content-1.3.248.zip META-INF jcr_root
```

`studio-msm-content/`: config-urile de rollout standard la `/libs/msm/wcm/rolloutconfigs` (plus `versioned`) și blueprint-ul pentru experience fragments la `/libs/msm/experience-fragments` (clean-room: nume, trigger și
acțiuni ca în AEM, texte proprii). Are nevoie de tipurile `cq:RolloutConfig` și `cq:LiveSyncAction` din `studio-cq-api`.

```bash
cd studio-msm-content && zip -r ../studio-msm-content-0.2.0.zip META-INF jcr_root
```

`studio-workflow-content/`: componentele pașilor de workflow la `/libs/cq/workflow/components/model` (process, participant,
dynamic_participant, or, and, goto, plus baza `step`), cu dialoguri scrise pentru Sensible Studio. Select-urile lor iau opțiunile
din datasource-urile `studio/workflow/datasources/*` implementate de editorul de modele din `author-ui`. Dialogurile pașilor au Workflow Stage, cei participant și process au Timeout și Timeout Handler, iar dialogul OR split
folosește comportamentul `studio.workflow.split`. Conține și modelul implicit „Request for Review” (`/libs/settings/workflow/models/request_for_review`).

```bash
cd studio-workflow-content && zip -r ../studio-workflow-content-0.1.4.zip META-INF jcr_root
```

`studio-dam-defaults/`: preseturile de imagine de pornire (`hero`, `banner`, `card`, `square`, `portrait`, `thumb`, `avatar`, `web`, `web-hq`,
`email`, `og`, `png-white`) și profilul de imagine `web` (Square 1:1, Banner 16:5, Story 9:16), la `/conf/global/settings/dam/dm`.
Filtrele sunt `mode="merge"`: ce există deja în repository nu se atinge, deci ce a schimbat un administrator rămâne; pe un repository
nou (sau pe publish) apar singure. Un preset șters de administrator revine doar la o versiune nouă a pachetului.

```bash
cd studio-dam-defaults && zip -r ../studio-dam-defaults-0.1.0.zip META-INF jcr_root
```
