# dam-service (`studio-dam`)

Serviciul de procesare a asset-urilor pentru Sensible Studio, scris în Rust. Generează rendition-urile `cq5dam.*`,
extrage metadatele și livrează imagini redimensionate on-the-fly. Partea Java din Sensible Studio rămâne singura care
scrie în JCR; serviciul doar citește și scrie binare și întoarce JSON.

Contractul (nume, dimensiuni, formate, metadate) e în [`docs/dam-contract.md`](docs/dam-contract.md),
observat clean-room din conținutul WKND (licență MIT). Nu s-a folosit cod Adobe.

## Build și teste

Necesită Rust stabil (`rustup`, 1.80+) și un compilator C (pentru libwebp; pe macOS: `xcode-select --install`).

```bash
cd dam-service
cargo test               # 31 de teste: unitare, contractul WKND, API-ul HTTP, concurență
cargo build --release    # target/release/studio-dam, ~8 MB, fără dependențe de sistem
```

## Rulare

```bash
export STUDIO_DAM_TOKEN="$(openssl rand -hex 32)"     # secretul comun cu Sensible Studio; nu se dă ca argument
./target/release/studio-dam \
    --datastore ../crx-quickstart/repository/datastore \
    --cache ../crx-quickstart/dam/cache
```

Implicit ascultă pe `127.0.0.1:4510`. `--output` (unde se scriu rendition-urile) e implicit egal cu `--datastore`,
deci Oak găsește binarele deja acolo când partea Java le importă.

## API

| Rută | Autentificare | Ce face |
|---|---|---|
| `GET /health[?challenge=<hex>]` | — | starea serviciului; cu `challenge`, întoarce și `proof = HMAC(token, "health:" + challenge)`, ca Sensible Studio să recunoască propriul proces fără să trimită tokenul |
| `PUT /blobs` | `Authorization: Bearer <token>` | salvează un binar, întoarce `{"blobId"}` (SHA-256) |
| `GET /blobs/{id}` | bearer | descarcă un binar (rendition sau original) |
| `POST /process` | bearer | `{"blobId", "mimeType"?, "extra"?}` → rendition-uri + metadate; `extra` = rendition-urile unui processing profile |
| `GET /deliver/{id}/{W}x{H}.{jpeg\|png\|webp}?q=&t=&sig=` | URL semnat | redimensionare on-the-fly, cu cache pe disc; `t` opțional: crop/rotire/flip înainte (vezi mai jos) |
| `POST /edit` | bearer | `{"blobId", "operations"}` → un original nou, în același format (vezi mai jos) |

Exemplu de răspuns `POST /process` (prescurtat):

```json
{
  "blobId": "6132…",
  "mimeType": "image/jpeg",
  "metadata": { "dam:sha1": "660c…", "dam:size": 496684, "tiff:ImageWidth": 1600, "exif:FNumber": "8/1", "…": "…" },
  "xmpPacket": "<x:xmpmeta …>",
  "renditions": [
    { "name": "cq5dam.web.1280.1280.jpeg", "blobId": "1d5c…", "mimeType": "image/jpeg",
      "size": 209122, "sha1": "…", "width": 1280, "height": 709 }
  ],
  "failed": [],
  "elapsedMs": 145
}
```

În `metadata`, numerele întregi devin `{Long}`, cele zecimale `{Decimal}`, iar tablourile proprietăți multi-valoare.
XMP-ul se întoarce brut în `xmpPacket`, pentru că partea Java îl parsează oricum cu o bibliotecă XMP.
**PDF** (prima pagină, cu [hayro](https://github.com/LaurenzV/hayro)) și **SVG** (cu
[resvg](https://github.com/linebender/resvg)), ambele în Rust pur, Apache-2.0/MIT: se randează o imagine de
previzualizare (maxim 2048 px pe latura lungă, PDF-ul maxim 288 dpi), din care ies thumbnail-urile și `web.1280.1280`
(fără `zoom`, ca în AEM pentru documente) plus rendition-urile din profil. PDF-ul aduce și `dam:Numberofpages`,
`pdf:PageWidth`/`pdf:PageHeight` (puncte), `dc:title`, `dc:creator`, `dc:description`, `dc:subject` și
`pdf:Producer` din dicționarul Info; SVG-ul `tiff:ImageWidth`/`tiff:ImageLength` (unitățile desenului) și
transparența. Pentru video, Office etc., sau un PDF criptat sau stricat, răspunsul are metadate de bază și toate
rendition-urile în `failed` cu `reason: "RenditionFormatUnsupported"`, ca în AEM.

**Video și audio** (`src/video.rs`): fără decodare, doar antetul containerului. MP4/QuickTime/M4A (cutii ISO BMFF:
`mvhd`, `tkhd` cu matricea de rotație, `mdhd`, `hdlr`, `stsd`, `stts`; pentru fișierele fragmentate, cum le
înregistrează browserul, se adună `moof`/`trun`) și WebM/Matroska (EBML: `Info`, `Tracks`, până la primul `Cluster`).
Rezultă `dam:duration` (secunde), `tiff:ImageWidth`/`tiff:ImageLength` (dimensiunea afișată: un video filmat vertical
pe telefon e portret), `xmpDM:videoFrameRate`, `xmpDM:videoCompressor` (de ex. „H.264 (avc1)”),
`xmpDM:audioCompressor`, `xmpDM:audioSampleRate`, `xmpDM:audioChannelType`. Tipul se recunoaște din primii octeți
(`video/mp4`, `video/quicktime`, `audio/mp4`, `video/webm`). Thumbnail-urile unui video vin din cadrul ales în consolă
(`cq5dam.poster.jpg`, procesat ca o imagine obișnuită). Orice citire e verificată la limite: un fișier stricat dă mai
puține valori, niciodată o eroare.

### Smart Tags (`src/smarttags.rs`)

[SigLIP](https://huggingface.co/google/siglip-base-patch16-224) (Google, Apache-2.0) cu
[candle](https://github.com/huggingface/candle) (Hugging Face, Apache-2.0/MIT), pe CPU, în Rust pur. Pune imagini și
texte scurte în același spațiu: un tag se potrivește când imaginea e aproape de „This is a photo of <titlu>.”. Se
alege din etichetele trimise de Sensible Studio, cu softmax între ele (încrederea afișată) și sigmoid pe fiecare (cât de bine se
potrivește singură). Modelul nu e în binar: `GET /smart-tags/status` spune ce fișiere lipsesc,
`PUT /smart-tags/model/{file}` primește un fișier (doar cele trei cunoscute, cu dimensiunea și SHA-256-ul fixate în cod,
scris întâi într-un fișier temporar), `POST /smart-tags/predict` {blobId, labels[{id,text}], max, minConfidence,
minScore} întoarce etichetele (409 până e instalat modelul). Modelul se încarcă la prima cerere (aprox. 0,6 s), apoi
aprox. 0,6 s pe imagine pe un CPU x86 obișnuit; embedding-urile textelor rămân în memorie.

### Rendition-uri din processing profiles

`extra` din `POST /process` adaugă rendition-uri pe lângă cele standard, maxim 20:
`[{"name": "banner", "width": 1200, "height": 400, "format": "webp", "quality": 75}]` → `banner.webp`, încadrat în
1200 x 400, fără mărire peste original. `format`: `jpeg` (implicit), `png`, `webp`. Numele sunt litere mici, cifre,
`.`, `_` și `-`; `original` și `cq5dam.*` sunt rezervate; laturile între 1 și 8192. Un nume invalid → 400.

### Editarea originalului

`POST /edit` aplică, în ordine, operațiile editorului de imagini din consola Assets și salvează rezultatul ca un
original nou (originalul vechi rămâne în store):

```json
{ "blobId": "6132…", "operations": [
    { "op": "crop", "x": 0.1, "y": 0.2, "width": 0.5, "height": 0.5 },
    { "op": "rotate", "degrees": 90 },
    { "op": "flip", "axis": "horizontal" } ] }
```

- `crop` primește fracții (0..1) din imaginea de la pasul respectiv; `rotate` primește multipli de 90 (în sensul
  acelor de ceasornic, `-90` e o rotire la stânga); `flip` primește `horizontal` sau `vertical`. Maxim 64 de operații.
- Orientarea EXIF se aplică înainte de operații, deci coordonatele sunt cele ale imaginii afișate.
- JPEG rămâne JPEG (calitate 92), PNG rămâne PNG (cu transparența), WebP se salvează lossless. Alte formate primesc 422.
- Metadatele EXIF/XMP din fișier nu se păstrează în originalul nou; cele din `jcr:content/metadata` rămân.

Răspuns: `{"blobId", "mimeType", "width", "height", "size"}`. Sensible Studio scrie binarul peste rendition-ul `original`,
iar procesarea obișnuită regenerează rendition-urile.

### URL-uri de livrare semnate

Id-urile de blob sunt hash-uri de conținut; fără semnătură, cine află un hash ar putea descărca și asset-uri
nepublicate. Sensible Studio semnează fiecare URL:

```text
canonical = "<blobId>/<W>x<H>.<jpeg|png|webp>?q=<calitate>"     (png: q=0; implicit jpeg 80, webp 75)
sig       = hex(HMAC-SHA256(STUDIO_DAM_TOKEN, canonical))
URL       = /deliver/<blobId>/<W>x<H>.<ext>?q=<calitate>&sig=<sig>
```

Răspunsurile au `Cache-Control: public, max-age=31536000, immutable` și antetul `x-studio-cache: hit|miss`.
Cutia cerută se plafonează la latura mai lungă a originalului înainte de a deveni cheie de cache, deci lățimile mai
mari decât originalul împart o singură intrare. Mai multe cereri simultane pentru același URL necacheuit generează
imaginea o singură dată. Publicul nu ajunge direct la acest endpoint: Sensible Studio îl expune prin
`/bin/studio/dam/deliver/...`, cu propria semnătură.

**Transformări la livrare (`t`).** Pași separați prin `-`, aplicați originalului (cu orientarea EXIF aplicată) înainte
de redimensionare: `c<x>,<y>,<w>,<h>` crop în pixeli (tăiat la marginile imaginii), `r90`/`r180`/`r270` rotire în
sensul acelor de ceasornic, `h` și `v` oglindire. Exemplu: `t=c250,125,800,450-r90-h`. Maxim 8 pași. Fără `t`, forma
canonică (și deci semnăturile și cache-ul existente) rămâne aceeași; cu `t`, forma canonică devine
`<blob>/<W>x<H>.<ext>?q=<q>&t=<pași>`. Sensible Studio o folosește pentru crop-ul, rotirea și flip-urile setate pe componentele
Image din Core Components.

## Siguranță

- Id-urile de blob trebuie să fie exact 64 de caractere hex; altceva primește 400 (fără path traversal).
- Dimensiunile imaginii se verifică din header înainte de decodare (`--max-source-pixels`, implicit 150 MP).
- Token-ul vine doar din mediu (`STUDIO_DAM_TOKEN`, minim 16 caractere) și se compară în timp constant.
- Lucrul CPU rulează pe un pool limitat la `--workers` joburi simultane. Când așteaptă deja `workers × 8` cereri,
  următoarele primesc imediat `503` cu `Retry-After`, în loc să stea la coadă.
- Fișierele temporare (blob store, cache) au nume unice per cerere, iar redenumirea e atomică, deci două cereri
  simultane nu pot amesteca fișierele.
- Pachetele XMP mai mari de 1 MB nu se trimit mai departe. Tipul MIME declarat de client se acceptă doar pentru
  tipuri pasive: niciodată HTML, SVG, XML sau scripturi.
- SVG: niciun script nu rulează; `<image href>` acceptă doar URL-uri `data:` (nici fișiere locale, nici rețea);
  fonturile sunt cele ale sistemului. Un panic în hayro sau resvg (fișier construit răuvoitor) devine
  „unsupported”, nu oprește serviciul.
- `SIGTERM` oprește serviciul curat, pentru managerul de proces din Sensible Studio.

## Prima măsurătoare

Build release, 2 nuclee, toate cele 4–5 rendition-uri plus metadatele, într-un singur request:

| Fișier | Dimensiune | Timp |
|---|---|---|
| `adobestock-151584995.jpg` | 1000×357 | 48 ms |
| `freeride.jpg` | 720×1080 | 73 ms |
| `adobestock-231698835.jpg` | 1600×886 | 145 ms |
| `kumar-selvaraj.jpg` | 1280×1280 | 174 ms |
| `ayo-ogunseinde-237739.jpg` | 2950×3840 | 368 ms |
| `wknd-logo-dk.png` | 301×128 | 8 ms |

Memorie rezidentă după procesare: ~37 MB.

## Integrarea cu Sensible Studio

Modulul `studio-uberjar/studio-dam` pornește și supraveghează acest proces (modul `embedded`) sau se conectează la unul
extern, și scrie rezultatul lui `POST /process` în repository. `./bin/studio start` compilează serviciul (prima dată descarcă și crate-urile hayro și resvg) și copiază
binarul în `crx-quickstart/dam/bin/studio-dam`. Detalii în `studio-uberjar/README.md`, secțiunea DAM.

## Ce urmează

- Crop, rotire și flip la livrare (pentru componenta Image), ca să nu mai fie nevoie de fallback la servletul Core Components.
- Curățarea cache-ului de livrare și a originalelor nefolosite.
- Decodare JPEG la rezoluție redusă (libjpeg-turbo sau libvips) pentru thumbnail-uri.
- AVIF, Office; opțional ffmpeg (instalat separat) pentru thumbnail-uri din orice format video și conversii web.

## Regenerarea fixture-urilor

```bash
python3 -I tools/observe_wknd.py <jcr_root-uri dezarhivate> observed.json
```

Scriptul citește doar nodurile `.content.xml` și binarele din pachetele de conținut WKND.
