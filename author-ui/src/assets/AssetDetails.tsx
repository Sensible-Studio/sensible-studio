import { ColorTagsField } from "./colors";
import { ImageSetStage } from "./imageSet";
import {
  ActionButton,
  Button,
  Content,
  DialogContainer,
  Heading,
  IllustratedMessage,
  InlineAlert,
  Item,
  ProgressCircle,
  TabList,
  TabPanels,
  Tabs,
  TagGroup,
  Text,
  TextArea,
  TextField,
  ToggleButton,
} from "@adobe/react-spectrum";
import ChevronLeft from "@spectrum-icons/workflow/ChevronLeft";
import { ScreenFrame } from "../shell/ScreenFrame";
import ChevronRight from "@spectrum-icons/workflow/ChevronRight";
import Images from "@spectrum-icons/workflow/Images";
import Delete from "@spectrum-icons/workflow/Delete";
import Download from "@spectrum-icons/workflow/Download";
import Edit from "@spectrum-icons/workflow/Edit";
import History from "@spectrum-icons/workflow/History";
import LinkIcon from "@spectrum-icons/workflow/Link";
import LockClosed from "@spectrum-icons/workflow/LockClosed";
import LockOpen from "@spectrum-icons/workflow/LockOpen";
import Unlink from "@spectrum-icons/workflow/Unlink";
import WebPages from "@spectrum-icons/workflow/WebPages";
import Info from "@spectrum-icons/workflow/Info";
import RailLeft from "@spectrum-icons/workflow/RailLeft";
import Refresh from "@spectrum-icons/workflow/Refresh";
import MagicWand from "@spectrum-icons/workflow/MagicWand";
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
  useRef,
} from "react";
import {
  may,
  type AssetDetails as Details,
  type AssetItem,
  type BasicMetadata,
  type DamClient,
  type FieldValue,
  type Rendition,
} from "./api";
import {
  changedValues,
  fieldValue,
  missingRequired,
  SchemaFields,
} from "./schemas";
import { DeleteDialog } from "./dialogs";
import { ReferenceList, referrerUrl, useReferrers } from "./references";
import { isEditable } from "./ImageEditor";
import { dimensions, formatBytes, kindOf, parentOf, typeLabel } from "./format";
import type { AssetsRoute } from "./route";
import { AssetTagsField } from "./tagsField";
import { Timeline } from "./timeline";
import { AnnotatedPicture, CommentsSection, useComments } from "./annotations";
import { PrintNotesDialog } from "./printNotes";
import Print from "@spectrum-icons/workflow/Print";
import Annotate from "@spectrum-icons/workflow/Annotate";
import { KindIcon, Thumb, daysToExpiry } from "./views";
import { MediaStage } from "./video";
import { DynamicRenditions } from "./dynamicmedia";
import { SmartCropDialog, SmartCropList } from "./smartcrop";
import type { SmartCrop } from "./api";
import { SmartTagsField } from "./smarttags";
import { SimilarRail } from "./similar";
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { FormPage } from '../shell/FormPage';
import { InspectorShell } from '../shell/InspectorShell';
import { drmPreviewUrl, needsLicense, useAssetsConfig } from './assetsConfig';
import { DownloadDialog } from './bulk';

interface Props {
  client: DamClient;
  path: string;
  properties: boolean;
  /** Opens with the Similar rail (Find Similar on a selection). */
  similar?: boolean;
  /** Opens drawing notes on it (Annotate on a selection). */
  annotate?: boolean;
  /** Opens with the Timeline (a comment someone was told about). */
  timeline?: boolean;
  navigate(route: AssetsRoute): void;
}

/** `/assetdetails.html/<asset>`: the asset large, its renditions, and (with #properties) its metadata. */
/** What the camera's own property names mean; a name that is not listed is shown in words made from it. */
const CAMERA_LABELS: Record<string, string> = {
  'tiff:Make': 'Camera maker',
  'tiff:Model': 'Camera model',
  'tiff:Orientation': 'Orientation',
  'tiff:Software': 'Software',
  'exif:DateTimeOriginal': 'Taken',
  'exif:ExposureTime': 'Exposure time',
  'exif:FNumber': 'Aperture',
  'exif:ISOSpeedRatings': 'ISO',
  'exif:FocalLength': 'Focal length',
  'exif:FocalLengthIn35mmFilm': 'Focal length (35 mm)',
  'exif:Flash': 'Flash',
  'exif:WhiteBalance': 'White balance',
  'exif:ExposureProgram': 'Exposure program',
  'exif:ExposureBiasValue': 'Exposure compensation',
  'exif:MeteringMode': 'Metering',
  'exif:ColorSpace': 'Colour space',
  'exif:PixelXDimension': 'Width in pixels',
  'exif:PixelYDimension': 'Height in pixels',
  'exif:GPSLatitude': 'Latitude',
  'exif:GPSLongitude': 'Longitude',
  'exif:GPSAltitude': 'Altitude',
  'exifEX:LensModel': 'Lens',
  'exifEX:LensMake': 'Lens maker',
  'psAux:Lens': 'Lens',
};

function cameraLabel(property: string): string {
  const known = CAMERA_LABELS[property];
  if (known) return known;
  const name = property.slice(property.indexOf(':') + 1).replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return name.charAt(0).toUpperCase() + name.slice(1);
}

export function AssetDetails({ client, path, properties, similar, annotate, timeline, navigate }: Props) {
  const [asset, setAsset] = useState<Details | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [siblings, setSiblings] = useState<string[]>([]);

  const load = useCallback(async () => {
    try {
      setAsset(await client.asset(path));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client, path]);

  useEffect(() => {
    setAsset(null);
    void load();
  }, [load]);

  // Assets of the same folder, for "3 of 12 files" and previous / next.
  const folder = parentOf(path);
  useEffect(() => {
    let live = true;
    client.list(folder).then(
      (l) =>
        live &&
        setSiblings(
          l.items.filter((i) => i.type === "asset").map((i) => i.path),
        ),
      () => live && setSiblings([]),
    );
    return () => {
      live = false;
    };
  }, [client, folder]);

  // Keep polling while the asset is being processed.
  useEffect(() => {
    if (asset?.state !== "processing") return undefined;
    const t = window.setInterval(() => void load(), 2500);
    return () => window.clearInterval(t);
  }, [asset?.state, load]);

  useScreenTitle(asset ? (properties ? `Properties · ${asset.title}` : asset.title) : null, asset ? `${asset.title} | Sensible Studio Media | ${properties ? "Properties" : "Asset Details"}` : null);

  // Close: back where the asset was opened from; in a tab of its own, the tab closes.
  const detailsTab = useTab();
  const close = () => {
    if (!detailsTab) navigate({ kind: "browse", path: folder });
    else if (detailsTab.canBack) detailsTab.leave({ kind: "assets", route: { kind: "browse", path: folder } });
    else detailsTab.close();
  };

  if (error) {
    return (
      <div className="studio-assets__details">
        <IllustratedMessage>
          <Heading>Cannot open {path}</Heading>
          <Content>{error}</Content>
        </IllustratedMessage>
        <div className="studio-assets__center">
          <Button variant="secondary" onPress={close}>
            Back to the folder
          </Button>
        </div>
      </div>
    );
  }
  if (!asset) {
    return (
      <div className="studio-assets__loading">
        <ProgressCircle isIndeterminate aria-label="Loading" />
      </div>
    );
  }
  return properties ? (
    <PropertiesPage
      client={client}
      asset={asset}
      // Done or Cancel go back where the form was opened from (the details, or the folder); a form
      // reached by its URL falls back to the details. Pushing the details here would make × from the
      // details return to the form, and so on — a loop.
      onClose={() =>
        detailsTab && detailsTab.canBack
          ? detailsTab.leave({ kind: "assets", route: { kind: "details", path, properties: false } })
          : navigate({ kind: "details", path, properties: false })
      }
      onSaved={load}
    />
  ) : (
    <DetailsPage
      client={client}
      asset={asset}
      siblings={siblings}
      navigate={navigate}
      onClose={close}
      onChanged={load}
      similar={similar}
      annotate={annotate}
      timeline={timeline}
    />
  );
}

// ------------------------------------------------------------------------------------------- details

function DetailsPage({
  client,
  asset,
  siblings,
  navigate,
  onClose,
  onChanged,
  similar,
  annotate,
  timeline,
}: {
  client: DamClient;
  asset: Details;
  siblings: string[];
  navigate(route: AssetsRoute): void;
  onClose(): void;
  onChanged(): void;
  similar?: boolean;
  annotate?: boolean;
  timeline?: boolean;
}) {
  const tab = useTab();
  const tabActive = useRef(true);
  tabActive.current = !tab || tab.active;
  const [rail, setRail] = useState<
    "renditions" | "timeline" | "related" | "references" | "similar" | null
  >(similar ? "similar" : annotate || timeline ? "timeline" : null);
  const comments = useComments(client, asset.path);
  const [annotating, setAnnotating] = useState(!!annotate);
  const [printing, setPrinting] = useState(false);
  const assetsConfig = useAssetsConfig();
  const [licensedDownload, setLicensedDownload] = useState(false);
  // the marked preview could not be made (a format Java does not read): the usual one
  const [unmarked, setUnmarked] = useState(false);
  const [highlight, setHighlight] = useState<string | null>(null);
  const [shown, setShown] = useState<Rendition | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [cropping, setCropping] = useState(false);
  const [smartCrops, setSmartCrops] = useState<SmartCrop[]>(
    asset.smartCrops ?? [],
  );
  useEffect(() => setSmartCrops(asset.smartCrops ?? []), [asset.smartCrops]);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => setShown(null), [asset.path]);

  const index = siblings.indexOf(asset.path);
  const go = (delta: number) => {
    const target = siblings[index + delta];
    if (target) navigate({ kind: "details", path: target, properties: false });
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!tabActive.current) return; // another tab is in front
      if (
        (e.target as HTMLElement | null)?.closest(
          'input, textarea, [role="dialog"]',
        )
      )
        return;
      if (e.key === "ArrowLeft") go(-1);
      if (e.key === "ArrowRight") go(1);
      if (e.key === "p")
        navigate({ kind: "details", path: asset.path, properties: true });
      if (e.key === "e" && isEditable(asset) && may(asset, "canEdit"))
        navigate({ kind: "edit", path: asset.path });
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const kind = kindOf(asset);
  const image = kind === "image";
  const media = kind === "video" || kind === "audio";
  // Videos and audio play: the rendition of the same kind picked in the rail, else the preview
  // made for browsers when the original is in a format they do not play, else the original.
  const playing = media
    ? shown
      ? /^(video|audio)\//.test(shown.mimeType ?? "")
        ? shown.url
        : null
      : (asset.preview ?? asset.original)
    : null;
  const preview = shown
    ? shown.mimeType?.startsWith("image/")
      ? shown.url
      : null
    : image
      ? (asset.web ?? asset.original)
      : media
        ? null
        : asset.web;

  // DRM: a licensed picture is shown large with the system watermark on it (when one is set)
  const marked =
    preview && !shown && image && !unmarked && needsLicense(assetsConfig, asset) && assetsConfig.watermark?.ready
      ? drmPreviewUrl(asset.path, asset.modified)
      : null;
  const stage = marked ?? preview;

  return (
    <div className="studio-assets__details">
      <ScreenFrame
        navigation={
          <>
            <ToggleButton isQuiet isSelected={rail === "renditions"} onChange={(on) => setRail(on ? "renditions" : null)} aria-label="Renditions"><RailLeft /></ToggleButton>
            <ToggleButton isQuiet isSelected={rail === "timeline"} onChange={(on) => setRail(on ? "timeline" : null)} aria-label="Timeline"><History /></ToggleButton>
            {((preview && !asset.imageSet && !playing) || (playing && kindOf(asset) === "video")) && (
              <ToggleButton
                isQuiet
                isSelected={annotating}
                isDisabled={!comments.canComment}
                onChange={(on) => {
                  setAnnotating(on);
                  if (on) setRail("timeline");
                }}
                aria-label="Annotate"
              >
                <Annotate />
              </ToggleButton>
            )}
          </>
        }
        identity={<><h1>{asset.title}</h1>{asset.checkedOutBy && <span className="studio-assets__lock"><LockClosed size="XS" /> Checked out by {asset.checkedOutBy}</span>}</>}
        actions={
          <>
        {asset.original && (needsLicense(assetsConfig, asset) ? (
          // DRM: through the Download dialog, which shows the license to agree to
          <ActionButton isQuiet onPress={() => setLicensedDownload(true)}>
            <Download size="S" />
            <Text>Download</Text>
          </ActionButton>
        ) : (
          <a
            className="studio-assets__linkbtn"
            href={asset.original}
            download={asset.name}
          >
            <Download size="S" /> Download
          </a>
        ))}
        <ActionButton
          isQuiet
          onPress={() =>
            navigate({ kind: "details", path: asset.path, properties: true })
          }
        >
          <Info />
          <Text>
            Properties <em>(p)</em>
          </Text>
        </ActionButton>
        {may(asset, "canEdit") && (
          <ActionButton
            isQuiet
            onPress={async () => {
              try {
                await client.reprocess([asset.path]);
                setNotice("Queued for processing");
                onChanged();
              } catch (e) {
                setNotice(e instanceof Error ? e.message : String(e));
              }
            }}
          >
            <Refresh />
            <Text>Reprocess</Text>
          </ActionButton>
        )}
        {asset.smartTags &&
          may(asset, "canEdit") &&
          (kindOf(asset) === "image" ||
            kindOf(asset) === "video" ||
            kindOf(asset) === "pdf") && (
            <ActionButton
              isQuiet
              onPress={async () => {
                try {
                  await client.smartTag([asset.path]);
                  setNotice(
                    "Queued for Auto tags: see Properties in a few seconds",
                  );
                } catch (e) {
                  setNotice(e instanceof Error ? e.message : String(e));
                }
              }}
            >
              <MagicWand />
              <Text>Auto tags</Text>
            </ActionButton>
          )}
        {may(asset, "canEdit") && (
          <ActionButton
            isQuiet
            onPress={async () => {
              try {
                if (asset.checkedOutBy) await client.checkin([asset.path]);
                else await client.checkout([asset.path]);
                setNotice(asset.checkedOutBy ? "Checked in" : "Checked out");
                onChanged();
              } catch (e) {
                setNotice(e instanceof Error ? e.message : String(e));
              }
            }}
          >
            {asset.checkedOutBy ? <LockOpen /> : <LockClosed />}
            <Text>{asset.checkedOutBy ? "Check In" : "Checkout"}</Text>
          </ActionButton>
        )}
        {preview && !playing && !asset.imageSet && (comments.comments ?? []).some((c) => c.region) && (
          <ActionButton isQuiet onPress={() => setPrinting(true)}>
            <Print />
            <Text>Print</Text>
          </ActionButton>
        )}
        {may(asset, "canDelete") && (
          <ActionButton isQuiet onPress={() => setDeleting(true)}>
            <Delete />
            <Text>Delete</Text>
          </ActionButton>
        )}
          </>
        }
        tools={
          <>
          <ToggleButton
            isQuiet
            isSelected={rail === "related"}
            onChange={(on) => setRail(on ? "related" : null)}
            aria-label="Related"
          >
            <LinkIcon />
          </ToggleButton>
          {["image", "video", "pdf"].includes(kindOf(asset)) && (
            <ToggleButton
              isQuiet
              isSelected={rail === "similar"}
              onChange={(on) => setRail(on ? "similar" : null)}
              aria-label="Similar"
            >
              <Images />
            </ToggleButton>
          )}
          <ToggleButton
            isQuiet
            isSelected={rail === "references"}
            onChange={(on) => setRail(on ? "references" : null)}
            aria-label="References"
          >
            <WebPages />
          </ToggleButton>
          {index >= 0 && (
            <>
              <ActionButton
                isQuiet
                aria-label="Previous file"
                isDisabled={index === 0}
                onPress={() => go(-1)}
              >
                <ChevronLeft />
              </ActionButton>
              <span className="studio-assets__count">
                <strong>{index + 1}</strong> of {siblings.length} assets
              </span>
              <ActionButton
                isQuiet
                aria-label="Next file"
                isDisabled={index === siblings.length - 1}
                onPress={() => go(1)}
              >
                <ChevronRight />
              </ActionButton>
            </>
          )}
          </>
        }
        primary={isEditable(asset) && may(asset, "canEdit") ? (
          <Button variant="accent" onPress={() => navigate({ kind: "edit", path: asset.path })}>
            <Edit /><Text>Edit</Text>
          </Button>
        ) : undefined}
        onClose={onClose}
        closeLabel="Close file"
      />
      <div className="studio-assets__body">
        {rail === "timeline" && (
          <InspectorShell className="studio-assets__rail" label="Timeline" kind="Timeline" subject={asset.title} path={asset.path} onClose={() => setRail(null)}>
            <CommentsSection state={comments} highlight={highlight} onHighlight={setHighlight} />
            <Timeline
              client={client}
              path={asset.path}
              onRestored={() => {
                setNotice("Version restored");
                onChanged();
              }}
            />
          </InspectorShell>
        )}
        {rail === "similar" && (
          <InspectorShell className="studio-assets__rail" label="Similar" kind="Similar" subject={asset.title} path={asset.path} onClose={() => setRail(null)}>
            <SimilarRail client={client} path={asset.path} canEdit={may(asset, "canEdit")} navigate={navigate} />
          </InspectorShell>
        )}
        {rail === "references" && (
          <InspectorShell className="studio-assets__rail" label="References" kind="References" subject={asset.title} path={asset.path} onClose={() => setRail(null)}>
            <AssetReferences client={client} path={asset.path} />
          </InspectorShell>
        )}
        {rail === "related" && (
          <InspectorShell className="studio-assets__rail" label="Related" kind="Related" subject={asset.title} path={asset.path} onClose={() => setRail(null)}>
            {(asset.related ?? []).length === 0 ? (
              <p className="studio-assets__muted">
                No related files. Select files in a folder and use Relate.
              </p>
            ) : (
              <ul className="studio-assets__related">
                {(asset.related ?? []).map((r) => (
                  <li key={r.path}>
                    <button
                      type="button"
                      onClick={() =>
                        navigate({
                          kind: "details",
                          path: r.path,
                          properties: false,
                        })
                      }
                    >
                      {r.thumbnail ? (
                        <img src={r.thumbnail} alt="" />
                      ) : (
                        <span
                          className="studio-assets__related-noimg"
                          aria-hidden
                        />
                      )}
                      <span>{r.title}</span>
                    </button>
                    <ActionButton
                      isQuiet
                      aria-label={`Unrelate ${r.title}`}
                      onPress={async () => {
                        try {
                          await client.unrelate(asset.path, [r.path]);
                          onChanged();
                        } catch (e) {
                          setNotice(e instanceof Error ? e.message : String(e));
                        }
                      }}
                    >
                      <Unlink size="S" />
                    </ActionButton>
                  </li>
                ))}
              </ul>
            )}
          </InspectorShell>
        )}
        {rail === "renditions" && (
          <InspectorShell className="studio-assets__rail" label="Renditions" kind="Renditions" subject={asset.title} path={asset.path} onClose={() => setRail(null)}>
            <ul className="studio-assets__renditions">
              {asset.renditions.map((r) => (
                <li key={r.name}>
                  <button
                    type="button"
                    className={shown?.name === r.name ? "is-active" : ""}
                    onClick={() => setShown(r)}
                  >
                    <span className="studio-assets__rendition-name">{r.name}</span>
                    <span className="studio-assets__rendition-meta">
                      {[
                        r.width && r.height ? `${r.width} x ${r.height}` : "",
                        formatBytes(r.size),
                        r.mimeType,
                      ]
                        .filter(Boolean)
                        .join(" · ")}
                    </span>
                  </button>
                  {!needsLicense(assetsConfig, asset) && (
                    <a
                      href={r.url}
                      download={r.name === "original" ? asset.name : r.name}
                      aria-label={`Download ${r.name}`}
                    >
                      <Download size="S" />
                    </a>
                  )}
                </li>
              ))}
            </ul>
            {image && (
              <SmartCropList
                crops={smartCrops}
                onShow={(name, url) =>
                  setShown({
                    name: `Focus crop ${name}`,
                    url,
                    mimeType: "image/*",
                  })
                }
                onEdit={() => setCropping(true)}
                onNotice={setNotice}
              />
            )}
            {image && (
              <DynamicRenditions
                client={client}
                path={asset.path}
                onShow={(name, url) =>
                  setShown({ name, url, mimeType: "image/*" })
                }
                onNotice={setNotice}
              />
            )}
            {asset.failed.length > 0 && (
              <>
                <h2>Not generated</h2>
                <ul className="studio-assets__renditions">
                  {asset.failed.map((f) => (
                    <li key={f.name}>
                      <span className="studio-assets__rendition-name">
                        {f.name}
                      </span>
                      <span className="studio-assets__rendition-meta">
                        {f.reason}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </InspectorShell>
        )}
        <main className="studio-assets__stage">
          {asset.state === "processing" && (
            <InlineAlert
              variant="info"
              UNSAFE_className="studio-assets__stage-alert"
            >
              <Heading>Processing</Heading>
              <Content>
                Renditions and metadata are being generated. This page updates
                by itself.
              </Content>
            </InlineAlert>
          )}
          {asset.state === "failed" && (
            <InlineAlert
              variant="negative"
              UNSAFE_className="studio-assets__stage-alert"
            >
              <Heading>Processing failed</Heading>
              <Content>
                {asset.processingError ?? "Unknown error"}. Use Reprocess to try
                again.
              </Content>
            </InlineAlert>
          )}
          {(asset.expired || (daysToExpiry(asset) ?? 99) <= 7) && (
            <ExpiryAlert client={client} asset={asset} />
          )}
          {asset.videoTools === false && (
            <InlineAlert
              variant="notice"
              UNSAFE_className="studio-assets__stage-alert"
            >
              <Heading>Videos are not fully processed on this server</Heading>
              <Content>
                ffmpeg is not installed where Sensible Studio runs, so videos
                get no thumbnail, no poster and no copy that every browser
                plays. Ask your administrator to install ffmpeg (on a Mac:{" "}
                <code>brew install ffmpeg</code>), restart the instance, then
                use Reprocess.
              </Content>
            </InlineAlert>
          )}
          {asset.imageSet ? (
            <ImageSetStage
              key={asset.path}
              client={client}
              set={asset}
              canEdit={may(asset, "canEdit")}
              onOpen={(p) => navigate({ kind: "details", path: p, properties: false })}
              onChanged={onChanged}
              onError={setNotice}
            />
          ) : playing ? (
            <MediaStage
              client={client}
              asset={asset}
              src={playing}
              notes={
                kindOf(asset) === "video" && (annotating || (comments.comments ?? []).some((c) => c.region))
                  ? { state: comments, annotating, highlight, onHighlight: setHighlight }
                  : undefined
              }
              onPosterSet={() => {
                setNotice("Thumbnail set from this frame");
                onChanged();
              }}
              onError={setNotice}
            />
          ) : preview ? (
            annotating || (comments.comments ?? []).some((c) => c.region) ? (
              <AnnotatedPicture
                src={stage ?? preview}
                alt={asset.title}
                state={comments}
                annotating={annotating}
                highlight={highlight}
                onHighlight={setHighlight}
              />
            ) : (
              <img
                className="studio-assets__stage-img"
                src={stage ?? preview}
                alt={asset.title}
                onError={marked ? () => setUnmarked(true) : undefined}
              />
            )
          ) : (
            <div className="studio-assets__stage-file">
              <KindIcon item={asset} size="XL" />
              <p>{typeLabel(asset)}</p>
              {asset.original && !needsLicense(assetsConfig, asset) && (
                <a
                  className="studio-assets__linkbtn"
                  href={shown?.url ?? asset.original}
                  download
                >
                  <Download size="S" /> Download{" "}
                  {shown ? shown.name : asset.name}
                </a>
              )}
            </div>
          )}
        </main>
      </div>
      {notice && (
        <div className="studio-assets__toast" role="status">
          <span>{notice}</span>
          <ActionButton
            isQuiet
            aria-label="Dismiss"
            onPress={() => setNotice(null)}
          >
            ×
          </ActionButton>
        </div>
      )}
      <DialogContainer onDismiss={() => setLicensedDownload(false)}>
        {licensedDownload && <DownloadDialog items={[asset as unknown as Parameters<typeof DownloadDialog>[0]['items'][number]]} close={() => setLicensedDownload(false)} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setPrinting(false)}>
        {printing && preview && <PrintNotesDialog title={asset.title} path={asset.path} src={preview} comments={comments.comments ?? []} />}
      </DialogContainer>
      <DialogContainer onDismiss={() => setCropping(false)}>
        {cropping && (
          <SmartCropDialog
            client={client}
            path={asset.path}
            src={asset.web ?? asset.original ?? ""}
            crops={smartCrops}
            onChanged={(c) => {
              setSmartCrops(c);
              setNotice("Focus crops saved");
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDeleting(false)}>
        {deleting && (
          <DeleteDialog
            client={client}
            items={[asset]}
            onDone={onClose}
            close={() => setDeleting(false)}
          />
        )}
      </DialogContainer>
    </div>
  );
}

/**
 * An expired file (or one expiring within a week), and the pages that still show it: AEM marks
 * expired assets; here the page says where they are used so they can be replaced.
 */
function ExpiryAlert({ client, asset }: { client: DamClient; asset: Details }) {
  const { referrers } = useReferrers(client, [asset.path]);
  const day = asset.expires ? new Date(asset.expires).toLocaleDateString() : "";
  const pages = (referrers ?? []).filter((r) => r.isPage);
  return (
    <InlineAlert
      variant={asset.expired ? "negative" : "notice"}
      UNSAFE_className="studio-assets__stage-alert"
    >
      <Heading>{asset.expired ? `Expired on ${day}` : `Expires on ${day}`}</Heading>
      <Content>
        {referrers === null ? (
          "Looking for the pages that use it…"
        ) : pages.length === 0 ? (
          asset.expired ? "No page uses it." : "No page uses it yet."
        ) : (
          <>
            {asset.expired ? "Still used on" : "Used on"} {pages.length} page
            {pages.length === 1 ? "" : "s"}:{" "}
            {pages.slice(0, 5).map((r, i) => (
              <span key={r.key}>
                {i > 0 && ", "}
                <a href={referrerUrl(r)}>{r.title || r.key}</a>
              </span>
            ))}
            {pages.length > 5 && ` and ${pages.length - 5} more (References)`}.
          </>
        )}
      </Content>
    </InlineAlert>
  );
}

// ---------------------------------------------------------------------------------------- properties

const str = (v: unknown): string =>
  v === undefined || v === null
    ? ""
    : Array.isArray(v)
      ? v.join(", ")
      : String(v);
const list = (v: unknown): string[] =>
  Array.isArray(v)
    ? v.map(String)
    : v === undefined || v === null || v === ""
      ? []
      : [String(v)];

function initialBasic(md: Record<string, unknown>): Required<BasicMetadata> {
  return {
    "dc:title": str(md["dc:title"]),
    "dc:description": str(md["dc:description"]),
    "dc:language": str(md["dc:language"]),
    "dc:rights": str(md["dc:rights"]),
    "dc:subject": list(md["dc:subject"]),
    "dc:creator": list(md["dc:creator"]),
    "dc:contributor": list(md["dc:contributor"]),
    "cq:tags": list(md["cq:tags"]),
    // stored as the end of the day, in the server's time: the day is what people set
    "prism:expirationDate": str(md["prism:expirationDate"]).slice(0, 10),
  };
}

/** Only the fields that differ from what was loaded. */
export function changedFields(
  before: Required<BasicMetadata>,
  after: Required<BasicMetadata>,
): BasicMetadata {
  const out: BasicMetadata = {};
  for (const key of Object.keys(after) as Array<keyof BasicMetadata>) {
    const a = before[key];
    const b = after[key];
    if (JSON.stringify(a) !== JSON.stringify(b))
      (out as Record<string, unknown>)[key] = b;
  }
  return out;
}

function PropertiesPage({
  client,
  asset,
  onClose,
  onSaved,
}: {
  client: DamClient;
  asset: Details;
  onClose(): void;
  onSaved(): void;
}) {
  const initial = useMemo(() => initialBasic(asset.metadata), [asset]);
  const [form, setForm] = useState(initial);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const set = <K extends keyof BasicMetadata>(
    k: K,
    v: Required<BasicMetadata>[K],
  ) => setForm((f) => ({ ...f, [k]: v }));
  const diff = changedFields(initial, form);
  const dirty = Object.keys(diff).length > 0;
  // A folder's metadata schema replaces the Basic tab with its own tabs.
  const schema = asset.schema ?? null;
  const schemaInitial = useMemo(() => {
    const v: Record<string, FieldValue> = {};
    for (const f of schema?.tabs.flatMap((t) => t.fields) ?? [])
      v[f.property] = fieldValue(f, asset.metadata[f.property]);
    return v;
  }, [schema, asset]);
  const [values, setValues] = useState(schemaInitial);

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      if (schema) {
        const missing = missingRequired(schema.tabs, values);
        if (missing.length) throw new Error(`Required: ${missing.join(", ")}`);
        const changed = changedValues(schema.tabs, schemaInitial, values);
        if (Object.keys(changed).length)
          await client.saveFields(asset.path, changed);
      } else if (dirty) await client.saveMetadata(asset.path, diff);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const md = asset.metadata;
  const camera = Object.entries(md).filter(([k]) =>
    /^(exif|exifEX|psAux):|^tiff:(Make|Model|Orientation|Software)$/.test(k),
  );
  const tabs = [
    ...(schema
      ? schema.tabs.map((t, i) => ({ id: `schema-${i}`, title: t.title }))
      : [{ id: "basic", title: "Basic" }]),
    { id: "advanced", title: "Advanced" },
    { id: "camera", title: "Camera Data" },
    { id: "all", title: "All Metadata" },
    { id: "references", title: "References" },
  ];
  const panel = (id: string): ReactNode => {
    if (id.startsWith("schema-") && schema) {
      return (
        <>
          <SchemaFields
            tab={schema.tabs[Number(id.slice(7))]}
            values={values}
            onChange={(p, v) => setValues((x) => ({ ...x, [p]: v }))}
          />
          {id === "schema-0" && (
            <SmartTagsField
              client={client}
              path={asset.path}
              initial={asset.smartTags}
              onApplied={() => onSaved()}
            />
          )}
          {id === "schema-0" && <ColorTagsField colors={asset.colors} />}
        </>
      );
    }
    switch (id) {
      case "basic":
        return (
          <div className="studio-assets__props-form">
            <TextField
              label="Title"
              value={form["dc:title"]}
              onChange={(v) => set("dc:title", v)}
              width="100%"
            />
            <TextArea
              label="Description"
              value={form["dc:description"]}
              onChange={(v) => set("dc:description", v)}
              width="100%"
            />
            <AssetTagsField
              values={form["cq:tags"]}
              onChange={(v) => set("cq:tags", v)}
            />
            <SmartTagsField
              client={client}
              path={asset.path}
              initial={asset.smartTags}
              onApplied={(tag) =>
                set(
                  "cq:tags",
                  form["cq:tags"].includes(tag)
                    ? form["cq:tags"]
                    : [...form["cq:tags"], tag],
                )
              }
            />
            <ColorTagsField colors={asset.colors} />
            <TagsField
              label="Keywords"
              values={form["dc:subject"]}
              onChange={(v) => set("dc:subject", v)}
            />
            <TagsField
              label="Creator"
              values={form["dc:creator"]}
              onChange={(v) => set("dc:creator", v)}
            />
            <TagsField
              label="Contributor"
              values={form["dc:contributor"]}
              onChange={(v) => set("dc:contributor", v)}
            />
            <TextField
              label="Language"
              value={form["dc:language"]}
              onChange={(v) => set("dc:language", v)}
              width="size-2400"
              description="e.g. en, de-CH"
            />
            <TextField
              label="Copyright"
              value={form["dc:rights"]}
              onChange={(v) => set("dc:rights", v)}
              width="100%"
            />
            <label className="studio-assets__expires-field">
              <span>Expires</span>
              <input
                type="date"
                value={form["prism:expirationDate"]}
                onChange={(e) => set("prism:expirationDate", e.target.value)}
              />
              <small>
                After this day the file is marked Expired, and its page
                says where it is still used.
              </small>
            </label>
          </div>
        );
      case "advanced":
        return (
          <Facts
            rows={[
              ["Name", asset.name],
              ["Path", asset.path],
              ["Type", asset.mimeType],
              ["Size", formatBytes(asset.size)],
              ["Dimensions", dimensions(asset)],
              [
                "Resolution",
                md["dam:Physicalwidthindpi"] &&
                Number(md["dam:Physicalwidthindpi"]) > 0
                  ? `${str(md["dam:Physicalwidthindpi"])} dpi`
                  : "",
              ],
              ["Bits per pixel", str(md["dam:Bitsperpixel"])],
              ["Progressive", str(md["dam:Progressive"])],
              ["SHA-1", str(md["dam:sha1"])],
              [
                "Created",
                asset.created ? new Date(asset.created).toLocaleString() : "",
              ],
              [
                "Modified",
                asset.modified ? new Date(asset.modified).toLocaleString() : "",
              ],
              [
                "Metadata extracted",
                md["dam:extracted"]
                  ? new Date(String(md["dam:extracted"])).toLocaleString()
                  : "",
              ],
              ["Processing", asset.state ?? ""],
              ["Metadata schema", schema?.title ?? "Default"],
            ]}
          />
        );
      case "camera":
        return camera.length === 0 ? (
          <p className="studio-assets__muted">No camera data in this file.</p>
        ) : (
          <Facts rows={camera.map(([k, v]) => [cameraLabel(k), str(v)])} />
        );
      case "all":
        return (
          <Facts rows={Object.entries(md).map(([k, v]) => [k, str(v)])} mono />
        );
      default:
        return <AssetReferences client={client} path={asset.path} />;
    }
  };
  const reset = () => {
    setForm(initial);
    setValues(schemaInitial);
    setError(null);
  };
  return (
    <FormPage
      kind="Media properties"
      title={asset.title}
      path={asset.path}
      dirty={dirty || JSON.stringify(values) !== JSON.stringify(schemaInitial)}
      busy={saving}
      canSave={may(asset, "canEdit")}
      onReset={reset}
      onSave={save}
      onClose={onClose}
      wide
      error={error}
      aside={
        <div className="studio-assets__props-card">
          <Thumb
            item={asset as AssetItem}
            src={asset.thumbnail}
            className="studio-assets__props-thumb"
          />
          <div className="studio-assets__card-title">{asset.title}</div>
          <div className="studio-assets__card-type">
            {asset.name.toUpperCase()}
          </div>
        </div>
      }
    >
        <section className="studio-assets__props-tabs">
          {/* keyed by the tab set: when the schema arrives, the first tab is selected afresh rather than none */}
          <Tabs
            key={tabs.map((t) => t.id).join("|")}
            aria-label="Metadata"
            items={tabs}
          >
            <TabList>
              {(t: { id: string; title: string }) => (
                <Item key={t.id}>{t.title}</Item>
              )}
            </TabList>
            <TabPanels>
              {(t: { id: string; title: string }) => (
                <Item key={t.id}>{panel(t.id)}</Item>
              )}
            </TabPanels>
          </Tabs>
        </section>
    </FormPage>
  );
}

/** Pages that use the asset (the References tab of AEM's properties page). */
function AssetReferences({
  client,
  path,
}: {
  client: DamClient;
  path: string;
}) {
  const { referrers, error } = useReferrers(client, [path]);
  return (
    <div className="studio-assets__props-refs">
      <ReferenceList referrers={referrers} error={error} max={500} />
    </div>
  );
}

function Facts({
  rows,
  mono,
}: {
  rows: Array<[string, ReactNode]>;
  mono?: boolean;
}) {
  return (
    <dl className={`studio-assets__facts${mono ? " is-mono" : ""}`}>
      {rows
        .filter(([, v]) => v !== "" && v !== null && v !== undefined)
        .map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
    </dl>
  );
}

/** Multi-value field: type and press Enter (or comma) to add, remove with the tag's ×. */
export function TagsField({
  label,
  values,
  onChange,
}: {
  label: string;
  values: string[];
  onChange(v: string[]): void;
}) {
  const [draft, setDraft] = useState("");
  const commit = () => {
    const parts = draft
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean)
      .filter((s) => !values.includes(s));
    if (parts.length) onChange([...values, ...parts]);
    setDraft("");
  };
  return (
    <div className="studio-assets__tags">
      <TextField
        label={label}
        value={draft}
        onChange={setDraft}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === ",") {
            e.preventDefault();
            commit();
          } else {
            e.continuePropagation();
          }
        }}
        onBlur={commit}
        width="100%"
        description="Press Enter to add"
      />
      {values.length > 0 && (
        <TagGroup
          aria-label={label}
          items={values.map((v) => ({ id: v }))}
          onRemove={(keys) => onChange(values.filter((v) => !keys.has(v)))}
        >
          {(t) => <Item key={t.id}>{t.id}</Item>}
        </TagGroup>
      )}
    </div>
  );
}
