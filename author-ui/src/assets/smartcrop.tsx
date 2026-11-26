import {
  ActionButton,
  Button,
  ButtonGroup,
  Content,
  DialogContainer,
  Divider,
  Heading,
  Item,
  NumberField,
  Picker,
  TextField,
  useDialogContainer,
} from "@adobe/react-spectrum";
import Add from "@spectrum-icons/workflow/Add";
import Copy from "@spectrum-icons/workflow/Copy";
import Delete from "@spectrum-icons/workflow/Delete";
import Edit from "@spectrum-icons/workflow/Edit";
import { useCallback, useEffect, useRef, useState } from "react";
import {
  type CropRatio,
  type DamClient,
  type ImageProfile,
  type SmartCrop,
} from "./api";
import { nodeName } from "./dialogs";
import type { AssetsRoute } from "./route";
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The crop's height as a fraction of the image, from its width: the ratio decides. */
export function cropHeight(
  c: Pick<
    SmartCrop,
    "imageWidth" | "imageHeight" | "ratioWidth" | "ratioHeight"
  >,
  width: number,
): number {
  return (
    ((width * c.imageWidth) / c.imageHeight) * (c.ratioHeight / c.ratioWidth)
  );
}

/** Keeps a crop inside the image: at most as wide as fits, then at most as tall. */
export function clampCrop(
  c: Pick<
    SmartCrop,
    "imageWidth" | "imageHeight" | "ratioWidth" | "ratioHeight"
  >,
  left: number,
  top: number,
  width: number,
) {
  let w = Math.min(Math.max(width, 0.02), 1);
  let h = cropHeight(c, w);
  if (h > 1) {
    h = 1;
    w = ((h * c.imageHeight) / c.imageWidth) * (c.ratioWidth / c.ratioHeight);
  }
  return {
    left: Math.min(Math.max(left, 0), 1 - w),
    top: Math.min(Math.max(top, 0), 1 - h),
    width: w,
    height: h,
  };
}

/** Crop names: letters, digits, '.', '_' and '-'. */
export function cropName(raw: string): string {
  return raw
    .trim()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[-.]+|-+$/g, "")
    .slice(0, 63);
}

export function profileProblems(name: string, ratios: CropRatio[]): string[] {
  const out: string[] = [];
  if (!name) out.push("The profile needs a title or name.");
  if (ratios.length === 0) out.push("Add at least one crop.");
  const seen = new Set<string>();
  ratios.forEach((r, i) => {
    const label = r.name || `Crop ${i + 1}`;
    if (!r.name) out.push(`${label}: a name is required.`);
    if (!(
      r.width >= 1 &&
      r.height >= 1 &&
      r.width <= 10000 &&
      r.height <= 10000
    ))
      out.push(`${label}: width and height are 1 to 10000.`);
    if (r.name && seen.has(r.name)) out.push(`${label}: listed twice.`);
    seen.add(r.name);
  });
  return out;
}

/**
 * Tools > Assets > Image Profiles: the aspect ratios (Smart Crop) a folder's images get crops
 * for. A profile is applied in the folder's properties.
 */
export function ImageProfilesPage({
  client,
  navigate: _navigate,
}: {
  client: DamClient;
  navigate(route: AssetsRoute): void;
}) {
  const [profiles, setProfiles] = useState<ImageProfile[] | null>(null);
  const [admin, setAdmin] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<ImageProfile | "new" | null>(null);
  const [deleting, setDeleting] = useState<ImageProfile | null>(null);

  const load = useCallback(async () => {
    try {
      const r = await client.imageProfiles();
      setProfiles(r.profiles);
      setAdmin(r.admin);
      setError(null);
    } catch (e) {
      setError(message(e));
    }
  }, [client]);

  useScreenTitle('Image Profiles', "Image Profiles | Sensible Studio Media");
  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="studio-assets__profiles">
      <ToolBar
        url={routeUrl({ kind: "imageprofiles" })}
        primary={
          admin && (
            <Button variant="accent" onPress={() => setEditing("new")}>
              <Add />
              Create
            </Button>
          )
        }
      />
      <p className="studio-assets__dm-intro">
        Focus crop: each ratio here becomes a crop of every image in the folders
        that use the profile, placed around the picture's subject and served as{" "}
        <code>/is/image/&lt;path&gt;:&lt;crop&gt;</code>. Authors can move a
        crop on the file.
      </p>
      {error ? (
        <ErrorState title="Image profiles could not be loaded">{error}</ErrorState>
      ) : profiles === null ? (
        <LoadingState label="Loading image profiles" />
      ) : profiles?.length === 0 ? (
        <EmptyState title="No image profiles">Create one with the crops you need (Square 1:1, Banner 16:9, ...), then choose it in the properties of a folder.</EmptyState>
      ) : (
        <table className="studio-assets__profiles-table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Name</th>
              <th>Crops</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {profiles?.map((p) => (
              <tr key={p.path}>
                <td>{p.title}</td>
                <td>
                  <code>{p.name}</code>
                </td>
                <td>
                  {p.ratios
                    .map((r) => `${r.name} (${r.width}:${r.height})`)
                    .join(", ")}
                </td>
                <td>
                  {admin && (
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
      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <ProfileDialog
            client={client}
            profile={editing === "new" ? null : editing}
            taken={(profiles ?? []).map((p) => p.name)}
            onSaved={() => void load()}
            close={() => setEditing(null)}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title="Delete image profile"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await client.deleteImageProfile(deleting.name);
              } catch (e) {
                setError(message(e));
              }
              void load();
            }}
            onCancel={() => setDeleting(null)}
          >
            Folders that use <strong>{deleting.title}</strong> keep their crops
            until their images are processed again.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

const EMPTY: CropRatio = { name: "", width: 1, height: 1 };

function ProfileDialog({
  client,
  profile,
  taken,
  onSaved,
  close,
}: {
  client: DamClient;
  profile: ImageProfile | null;
  taken: string[];
  onSaved(): void;
  close(): void;
}) {
  const [title, setTitle] = useState(profile?.title ?? "");
  const [ratios, setRatios] = useState<CropRatio[]>(
    profile?.ratios.map((r) => ({ ...r })) ?? [{ ...EMPTY }],
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const name = profile?.name ?? nodeName(title);
  const problems = profileProblems(name, ratios);
  const clash = !profile && taken.includes(name);
  const set = (i: number, patch: Partial<CropRatio>) =>
    setRatios((rs) => rs.map((r, k) => (k === i ? { ...r, ...patch } : r)));

  return (
    <StudioDialog size="L">
      <Heading>
        {profile ? `Edit ${profile.title}` : "Create Image Profile"}
      </Heading>
      <Divider />
      <Content>
        <TextField
          label="Title"
          value={title}
          onChange={setTitle}
          width="100%"
          autoFocus
          description={`Name: ${name || "—"}`}
        />
        <h3 className="studio-assets__profiles-sub">Focus crops</h3>
        <div className="studio-assets__profiles-rows">
          {ratios.map((r, i) => (
            <fieldset
              key={i}
              className="studio-assets__profiles-row"
              aria-label={`Crop ${i + 1}`}
            >
              <TextField
                label="Name"
                value={r.name}
                onChange={(v) => set(i, { name: cropName(v) })}
                width="size-2400"
              />
              <NumberField
                label="Width"
                value={r.width}
                minValue={1}
                maxValue={10000}
                onChange={(v) => set(i, { width: v })}
                width="size-1250"
              />
              <NumberField
                label="Height"
                value={r.height}
                minValue={1}
                maxValue={10000}
                onChange={(v) => set(i, { height: v })}
                width="size-1250"
              />
              <span className="studio-assets__muted">
                {r.width && r.height
                  ? `${(r.width / r.height).toFixed(2)} : 1`
                  : ""}
              </span>
              <ActionButton
                isQuiet
                aria-label={`Remove crop ${i + 1}`}
                onPress={() => setRatios((rs) => rs.filter((_, k) => k !== i))}
              >
                <Delete />
              </ActionButton>
            </fieldset>
          ))}
        </div>
        <ActionButton
          onPress={() => setRatios((rs) => [...rs, { ...EMPTY }])}
          isDisabled={ratios.length >= 20}
        >
          <Add />
          Add Crop
        </ActionButton>
        {(problems.length > 0 || clash) && (
          <ul className="studio-assets__dialog-error">
            {clash && <li>A profile named {name} exists already.</li>}
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={busy || problems.length > 0 || clash}
          onPress={async () => {
            setBusy(true);
            setError(null);
            try {
              await client.saveImageProfile(name, title.trim() || name, ratios);
              onSaved();
              close();
            } catch (e) {
              setError(message(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/**
 * The Smart Crop editor of one asset: its crops on the web rendition, each a box to drag
 * (move) or resize from its corner, always in the crop's proportions. Saving marks the crop as
 * manual; "Reset" goes back to the computed ones.
 */
export function SmartCropDialog({
  client,
  path,
  src,
  crops: initial,
  onChanged,
}: {
  client: DamClient;
  path: string;
  /** the web rendition to draw on */
  src: string;
  crops: SmartCrop[];
  onChanged(crops: SmartCrop[]): void;
}) {
  const dialog = useDialogContainer();
  const [crops, setCrops] = useState(initial.map((c) => ({ ...c })));
  const [current, setCurrent] = useState(initial[0]?.name ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  const stage = useRef<HTMLDivElement>(null);
  const drag = useRef<{
    kind: "move" | "resize";
    x: number;
    y: number;
    left: number;
    top: number;
    width: number;
  } | null>(null);
  const crop = crops.find((c) => c.name === current) ?? null;
  const dirty = crops.filter((c, i) => {
    const o = initial[i];
    return o && (c.left !== o.left || c.top !== o.top || c.width !== o.width);
  });

  const update = (left: number, top: number, width: number) => {
    if (!crop) return;
    const next = clampCrop(crop, left, top, width);
    setCrops((cs) =>
      cs.map((c) =>
        c.name === crop.name ? { ...c, ...next, manual: true } : c,
      ),
    );
  };

  const onPointerDown = (e: React.PointerEvent, kind: "move" | "resize") => {
    if (!crop || !size) return;
    e.preventDefault();
    const el = e.currentTarget as HTMLElement;
    if (typeof el.setPointerCapture === "function")
      el.setPointerCapture(e.pointerId);
    drag.current = {
      kind,
      x: e.clientX,
      y: e.clientY,
      left: crop.left,
      top: crop.top,
      width: crop.width,
    };
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const d = drag.current;
    if (!d || !size || !crop) return;
    const dx = (e.clientX - d.x) / size.w;
    const dy = (e.clientY - d.y) / size.h;
    if (d.kind === "move") update(d.left + dx, d.top + dy, d.width);
    else update(d.left, d.top, d.width + dx);
  };
  const onPointerUp = () => {
    drag.current = null;
  };

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = [...crops];
      for (const c of dirty) {
        const s = await client.setSmartCrop(
          path,
          c.name,
          c.left,
          c.top,
          c.width,
        );
        saved[saved.findIndex((x) => x.name === c.name)] = s;
      }
      onChanged(saved);
      dialog.dismiss();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };

  const reset = async () => {
    setBusy(true);
    setError(null);
    try {
      const fresh = await client.resetSmartCrops(path);
      setCrops(fresh.map((c) => ({ ...c })));
      onChanged(fresh);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="L" UNSAFE_className="studio-smartcrop">
      <Heading>Focus crop</Heading>
      <Divider />
      <Content>
        <div className="studio-smartcrop__bar">
          <Picker
            aria-label="Crop"
            selectedKey={current}
            onSelectionChange={(k) => setCurrent(String(k))}
            items={crops}
          >
            {(c) => (
              <Item key={c.name}>
                {`${c.name} (${c.ratioWidth}:${c.ratioHeight})${c.manual ? " · manual" : ""}`}
              </Item>
            )}
          </Picker>
          <span className="studio-assets__muted">
            Drag the box to move it; drag its corner to resize. The proportions
            are kept.
          </span>
          <span className="studio-assets__spacer" />
          <Button
            variant="secondary"
            onPress={() => void reset()}
            isDisabled={busy || !crops.some((c) => c.manual)}
          >
            Reset to automatic
          </Button>
        </div>
        <div
          className="studio-smartcrop__stage"
          ref={stage}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
        >
          <img
            src={src}
            alt=""
            draggable={false}
            onLoad={(e) =>
              setSize({
                w: e.currentTarget.clientWidth,
                h: e.currentTarget.clientHeight,
              })
            }
          />
          {crop && size && (
            <div
              className="studio-smartcrop__box"
              role="img"
              aria-label={`${crop.name} crop`}
              style={{
                left: crop.left * size.w,
                top: crop.top * size.h,
                width: crop.width * size.w,
                height: crop.height * size.h,
              }}
              onPointerDown={(e) => onPointerDown(e, "move")}
            >
              <span
                className="studio-smartcrop__handle"
                onPointerDown={(e) => {
                  e.stopPropagation();
                  onPointerDown(e, "resize");
                }}
              />
            </div>
          )}
        </div>
        {crop && (
          <p className="studio-assets__muted">
            {Math.round(crop.left * crop.imageWidth)},{" "}
            {Math.round(crop.top * crop.imageHeight)} ·{" "}
            {Math.round(crop.width * crop.imageWidth)} x{" "}
            {Math.round(crop.height * crop.imageHeight)} px
            {crop.url && (
              <>
                {" "}
                · <code>{crop.url}</code>
              </>
            )}
          </p>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          onPress={() => void save()}
          isDisabled={busy || dirty.length === 0}
        >
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

/** The smart crops of an image in its Renditions rail: look at one, copy its URL, open the editor. */
export function SmartCropList({
  crops,
  onShow,
  onEdit,
  onNotice,
}: {
  crops: SmartCrop[];
  onShow(name: string, url: string): void;
  onEdit(): void;
  onNotice(text: string): void;
}) {
  if (crops.length === 0) return null;
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
      <h2>
        Focus crop
        <ActionButton isQuiet aria-label="Edit focus crops" onPress={onEdit}>
          <Edit size="S" />
        </ActionButton>
      </h2>
      <ul className="studio-assets__renditions">
        {crops.map((c) => (
          <li key={c.name}>
            <button
              type="button"
              onClick={() => c.url && onShow(c.name, c.url)}
            >
              <span className="studio-assets__rendition-name">
                {c.name}
                {c.manual ? " · manual" : ""}
              </span>
              <span className="studio-assets__rendition-meta">
                {c.ratioWidth}:{c.ratioHeight} ·{" "}
                {Math.round(c.width * c.imageWidth)} x{" "}
                {Math.round(c.height * c.imageHeight)}
              </span>
            </button>
            <ActionButton
              isQuiet
              aria-label={`Copy URL of ${c.name}`}
              onPress={() => c.url && void copy(c.url)}
            >
              <Copy size="S" />
            </ActionButton>
          </li>
        ))}
      </ul>
    </div>
  );
}
