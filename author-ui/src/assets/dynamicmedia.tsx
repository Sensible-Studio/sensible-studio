import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  NumberField,
  Picker,
  Tooltip,
  TooltipTrigger,
} from "@adobe/react-spectrum";
import Add from "@spectrum-icons/workflow/Add";
import Copy from "@spectrum-icons/workflow/Copy";
import Delete from "@spectrum-icons/workflow/Delete";
import Edit from "@spectrum-icons/workflow/Edit";
import Preview from "@spectrum-icons/workflow/Preview";
import { useCallback, useEffect, useState } from "react";
import {
  type DamClient,
  type ImagePreset,
  type ImagePresets,
} from "./api";
import type { AssetsRoute } from "./route";
import { PresetEditor, presetName, presetParams } from "./presetEditor";
import { GalleryPresetsSection } from "./galleryPresets";
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

export { presetName, presetParams };

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The public URL of an asset with a preset and extra parameters, as the console shows it. */
export function presetUrl(
  base: string,
  preset: string | null,
  extra: Record<string, string> = {},
): string {
  const parts: string[] = [];
  if (preset) parts.push(`$${preset}$`);
  for (const [k, v] of Object.entries(extra))
    if (v) parts.push(`${k}=${encodeURIComponent(v)}`);
  return parts.length ? `${base}?${parts.join("&")}` : base;
}

/**
 * Tools > Assets > Image Presets (Dynamic Media): named sets of URL parameters, so a site asks
 * for `?$banner$` instead of spelling the size and format out everywhere.
 */
export function ImagePresetsPage({
  client,
  navigate: _navigate,
}: {
  client: DamClient;
  navigate(route: AssetsRoute): void;
}) {
  const [data, setData] = useState<ImagePresets | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ImagePreset | "new" | null>(null);
  const [deleting, setDeleting] = useState<ImagePreset | null>(null);

  const load = useCallback(async () => {
    try {
      setData(await client.imagePresets());
      setError(null);
    } catch (e) {
      setError(message(e));
    }
  }, [client]);

  useScreenTitle('Image Presets', "Image Presets | Sensible Studio Media");
  useEffect(() => {
    void load();
  }, [load]);

  const presets = data?.presets ?? null;
  if (editing)
    return (
      <PresetEditor
        client={client}
        preset={editing === "new" ? null : editing}
        taken={(presets ?? []).map((p) => p.name)}
        onSaved={(saved) => {
          setEditing(saved);
          void load();
        }}
        close={() => setEditing(null)}
      />
    );
  return (
    <div className="studio-assets__profiles">
      <ToolBar
        url={routeUrl({ kind: "imagepresets" })}
        primary={
          data?.admin && (
            <Button variant="accent" onPress={() => setEditing("new")}>
              <Add />
              Create
            </Button>
          )
        }
      />
      <p className="studio-assets__dm-intro">
        Every image in Media is served at <code>/is/image/&lt;path&gt;</code>{" "}
        with parameters such as <code>wid</code>, <code>hei</code>,{" "}
        <code>fmt</code>, <code>qlt</code>, <code>fit</code>, <code>crop</code>,{" "}
        <code>rotate</code> and <code>op_usm</code>. A preset names a set of
        them: <code>?$banner$</code>. Parameters given in the URL override the
        preset's.
      </p>
      {error ? (
        <ErrorState title="Image presets could not be loaded">{error}</ErrorState>
      ) : presets === null ? (
        <LoadingState label="Loading image presets" />
      ) : presets?.length === 0 ? (
        <EmptyState title="No image presets">Create one, then use it from the Renditions rail of any image: it lists each preset with its URL.</EmptyState>
      ) : (
        <table className="studio-assets__profiles-table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Name</th>
              <th>Parameters</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {presets?.map((p) => (
              <tr key={p.name}>
                <td>
                  {p.title}
                  {p.description && (
                    <div className="studio-assets__muted">{p.description}</div>
                  )}
                </td>
                <td>
                  <code>${p.name}$</code>
                </td>
                <td>
                  <code>{p.query}</code>
                </td>
                <td>
                  {data?.admin && (
                    <>
                      <ActionButton
                        isQuiet
                        aria-label={`Edit ${p.title}`}
                        onPress={() => setEditing(p)}
                      >
                        <Edit />
                      </ActionButton>
                      <ActionButton
                        isQuiet
                        aria-label={`Delete ${p.title}`}
                        onPress={() => setDeleting(p)}
                      >
                        <Delete />
                      </ActionButton>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <GalleryPresetsSection client={client} />
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete image preset"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteImagePreset(deleting.name);
              } catch (e) {
                setError(message(e));
              }
              void load();
            }}
            onCancel={() => setDeleting(null)}
          >
            URLs that use <strong>${deleting.name}$</strong> stop working.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

/**
 * The "Dynamic" part of an image's Renditions rail: the public URL and one entry per preset,
 * to look at and to copy.
 */
export function DynamicRenditions({
  client,
  path,
  onShow,
  onNotice,
}: {
  client: DamClient;
  path: string;
  onShow(name: string, url: string): void;
  onNotice(text: string): void;
}) {
  const [data, setData] = useState<ImagePresets | null>(null);
  const [wid, setWid] = useState(800);
  const [fmt, setFmt] = useState("webp");
  useEffect(() => {
    let live = true;
    client.imagePresets(path).then(
      (d) => live && setData(d),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [client, path]);
  if (!data?.url) return null;
  const base = data.url;
  const custom = presetUrl(base, null, { wid: String(wid), fmt });
  const copy = async (url: string) => {
    const full = `${window.location.origin}${url}`;
    try {
      await navigator.clipboard.writeText(full);
      onNotice("URL copied");
    } catch {
      onNotice(full);
    }
  };
  return (
    <div className="studio-assets__dm-rail">
      <h2>Image delivery</h2>
      <ul className="studio-assets__renditions">
        {data.presets.map((p) => (
          <li key={p.name}>
            <button
              type="button"
              onClick={() => p.url && onShow(`$${p.name}$`, p.url)}
            >
              <span className="studio-assets__rendition-name">{p.title}</span>
              <span className="studio-assets__rendition-meta">{p.query}</span>
            </button>
            <TooltipTrigger delay={400}>
              <ActionButton
                isQuiet
                aria-label={`Copy URL of ${p.title}`}
                onPress={() => p.url && void copy(p.url)}
              >
                <Copy size="S" />
              </ActionButton>
              <Tooltip>Copy URL</Tooltip>
            </TooltipTrigger>
          </li>
        ))}
        <li className="studio-assets__dm-custom">
          <div className="studio-assets__dm-custom-fields">
            <span className="studio-assets__rendition-name">Custom size</span>
            <span className="studio-assets__rendition-meta">
              <NumberField
                aria-label="Width"
                value={wid}
                minValue={16}
                maxValue={4096}
                step={16}
                onChange={setWid}
                width="size-1250"
                isQuiet
              />
              <Picker
                aria-label="Format"
                selectedKey={fmt}
                onSelectionChange={(k) => setFmt(String(k))}
                isQuiet
                width="size-1000"
              >
                <Item key="webp">WebP</Item>
                <Item key="jpeg">JPEG</Item>
                <Item key="png">PNG</Item>
              </Picker>
            </span>
          </div>
          <ActionButton
            isQuiet
            aria-label="Show custom size"
            onPress={() => onShow("custom", custom)}
          >
            <Preview size="S" />
          </ActionButton>
          <TooltipTrigger delay={400}>
            <ActionButton
              isQuiet
              aria-label="Copy custom URL"
              onPress={() => void copy(custom)}
            >
              <Copy size="S" />
            </ActionButton>
            <Tooltip>Copy URL</Tooltip>
          </TooltipTrigger>
        </li>
      </ul>
      <p className="studio-assets__dm-base">
        <code>{base}</code>
      </p>
    </div>
  );
}
