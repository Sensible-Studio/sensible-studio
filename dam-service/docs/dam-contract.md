# Contractul DAM (observat, clean-room)

Sursa: pachetele de conținut WKND (`aem-guides-wknd-shared.ui.content-3.1.0`, `aem-guides-wknd.ui.content-4.0.4`),
licență MIT, conținute în `aem-guides-wknd.all-4.0.4.zip`. Am citit doar **date** (noduri `.content.xml` și binare),
nu cod Adobe. Scriptul de analiză e în `tools/observe_wknd.py`; rezultatele sunt fixate în `tests/fixtures/wknd/expected.json`.

Eșantion: 162 de noduri `dam:Asset` (124 JPEG, 2 PDF, 2 SVG, PNG-uri, Content Fragments).

## Structura în JCR

```text
<asset>.jpg                         jcr:primaryType = dam:Asset, mixin mix:referenceable
└── jcr:content                     dam:AssetContent
    ├── dam:assetState = "processed"
    ├── dam:processingRenditions = [listă de nume]      (procesare asincronă)
    ├── dam:failedRenditions/<nume> {message, reason}   ex. reason = RenditionFormatUnsupported
    ├── metadata                    nt:unstructured, mixin cq:Taggable
    ├── related                     nt:unstructured
    └── renditions
        ├── original                nt:file → jcr:content (oak:Resource, jcr:mimeType)
        ├── cq5dam.thumbnail.48.48.png
        ├── cq5dam.thumbnail.140.100.png
        ├── cq5dam.thumbnail.319.319.png
        ├── cq5dam.web.1280.1280.jpeg      (sau .png dacă sursa are alfa)
        └── cq5dam.zoom.2048.2048.jpeg     (doar pentru surse fără alfa)
```

Fiecare rendition e `nt:file` cu `jcr:content` (`oak:Resource`, mixin `dam:Metadata`) și un nod
`jcr:content/metadata` cu: `dam:sha1`, `dam:size`, `dc:format`, `tiff:ImageWidth`, `tiff:ImageLength`.

Rendition-urile `cqdam.machine.metadata.*.json` (tag-uri, culori, features generate de ML) și `cqdam.metadata.xml`
apar în conținut, dar nu sunt în scopul MVP-ului.

## Reguli de dimensionare (din 448 de rendition-uri măsurate)

Nume: `cq5dam.<tip>.<W>.<H>.<ext>`. W×H e o **cutie**, nu dimensiunea finală.

- Imaginea se încadrează în cutie păstrând proporțiile (**fit inside**). Nu se adaugă margini.
- **Fără mărire**: dacă originalul încape deja, rendition-ul are dimensiunile originalului.
- Latura lungă = exact latura cutiei. Latura scurtă = `round(...)`.
  Pentru `web` și `zoom` potrivirea e exactă. La `thumbnail`, ~18% diferă cu ±1 px
  (thumbnail-urile par generate din imagini intermediare). Testele acceptă ±1 px la latura scurtă.

| Rendition | Format | Calitate | Mod de culoare |
|---|---|---|---|
| `thumbnail.48.48`, `140.100`, `319.319` | PNG | — | RGB pentru surse fără alfa, RGBA pentru surse cu alfa |
| `web.1280.1280` | JPEG baseline, q≈80 / PNG pentru surse cu alfa | 80 | RGB / RGBA |
| `zoom.2048.2048` | JPEG baseline, q≈80 | 80 | RGB; lipsește la surse cu alfa |

Calitatea e estimată din tabelele de cuantizare (125 din 130 de rendition-uri `web` au q=80; restul de 5 au 89–91).

## Metadate pe asset (`jcr:content/metadata`)

Generate de procesare (în afară de ce vine din XMP/EXIF/IPTC):

| Proprietate | Exemplu | Observații |
|---|---|---|
| `dam:sha1` | `660c63…f09e` | SHA-1 al originalului |
| `dam:size` | `{Long}496684` | octeți |
| `dam:MIMEtype`, `dc:format` | `image/jpeg` | |
| `dam:Fileformat` | `JPEG` | |
| `tiff:ImageWidth`, `tiff:ImageLength` | `{Long}1600`, `{Long}886` | |
| `dam:Bitsperpixel` | `{Long}24` / `{Long}32` cu alfa | |
| `dam:Progressive` | `"yes"` / `"no"` | JPEG progresiv (SOF2) |
| `dam:Physicalwidthindpi`, `dam:Physicalheightindpi` | `{Long}72`, `{Long}-1` dacă lipsește | |
| `dam:Physicalwidthininches`, `…heightininches` | `{Decimal}22.22…` | lățime / dpi |
| `dam:extracted` | `{Date}…` | momentul extragerii |
| `tiff:Orientation` | `{Long}1` | când există EXIF |

Restul cheilor (`exif:*`, `exifEX:*`, `psAux:*`, `photoshop:*`, `xmp:*`, `xmpMM:*`, `dc:title`, `dc:subject` etc.)
sunt câmpuri XMP standard copiate din fișier. Tablourile au o proprietate soră `<nume>_xmpArrayType` (`rdf:Seq` / `rdf:Bag`).
