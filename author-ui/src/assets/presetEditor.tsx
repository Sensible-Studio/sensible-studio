import {
  ActionButton,
  Item,
  NumberField,
  Picker,
  ProgressCircle,
  Slider,
  Switch,
  TextField,
} from "@adobe/react-spectrum";
import Copy from "@spectrum-icons/workflow/Copy";
import { useEffect, useMemo, useRef, useState } from "react";
import { DAM_ROOT, type AssetItem, type DamClient, type ImagePreset } from "./api";
import { formatBytes } from "./format";
import { FormPage } from "../shell/FormPage";

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The editor's fields, as the server stores them (`wid=…&fmt=…`). Empty and default values are left out. */
export interface PresetForm {
  wid: number | null;
  hei: number | null;
  fmt: string;
  qlt: number;
  fit: string;
  upscale: boolean;
  sharpen: boolean;
  usm: string;
  bgc: string;
  rotate: number;
  flip: string;
  blur: number;
  brightness: number;
  contrast: number;
  saturation: number;
  /** "", "grayscale" or "sepia" */
  tone: string;
  overlayColor: string;
  overlayOpacity: number;
  /** "", "b", "t", "l" or "r" (the end edge) */
  gradientTowards: string;
  gradientColor: string;
  gradientFrom: number;
  gradientTo: number;
  vignette: number;
  radius: number;
  borderWidth: number;
  borderColor: string;
}

export const EMPTY_FORM: PresetForm = {
  wid: null,
  hei: null,
  fmt: "jpeg",
  qlt: 80,
  fit: "fit",
  upscale: false,
  sharpen: false,
  usm: "",
  bgc: "",
  rotate: 0,
  flip: "",
  blur: 0,
  brightness: 0,
  contrast: 0,
  saturation: 0,
  tone: "",
  overlayColor: "000000",
  overlayOpacity: 0,
  gradientTowards: "",
  gradientColor: "000000",
  gradientFrom: 0,
  gradientTo: 60,
  vignette: 0,
  radius: 0,
  borderWidth: 0,
  borderColor: "ffffff",
};

const hex = (v: string) => v.trim().replace(/^#/, "").toLowerCase();
const isHex = (v: string) => /^[0-9a-f]{6}$/.test(hex(v));

/** A copy of `preset`'s parameters with only the ones the editor shows, as the server stores them. */
export function presetParams(p: Partial<PresetForm>): Record<string, string> {
  const f = { ...EMPTY_FORM, ...p };
  const out: Record<string, string> = {};
  if (f.wid) out.wid = String(f.wid);
  if (f.hei) out.hei = String(f.hei);
  out.fmt = f.fmt;
  if (f.fmt !== "png" && f.fmt !== "png-alpha") out.qlt = String(f.qlt);
  if (f.fit !== "fit" || f.upscale) out.fit = f.upscale ? `${f.fit},1` : f.fit;
  if (f.rotate) out.rotate = String(f.rotate);
  if (f.flip) out.flip = f.flip;
  if (f.sharpen) {
    if (f.usm.trim()) out.op_usm = f.usm.trim();
    else out.op_sharpen = "1";
  }
  if (f.blur > 0) out.op_blur = String(f.blur);
  if (f.brightness) out.op_brightness = String(f.brightness);
  if (f.contrast) out.op_contrast = String(f.contrast);
  if (f.saturation) out.op_saturation = String(f.saturation);
  if (f.tone === "grayscale") out.op_grayscale = "1";
  if (f.tone === "sepia") out.op_sepia = "1";
  if (f.overlayOpacity > 0 && isHex(f.overlayColor)) out.op_overlay = `${hex(f.overlayColor)},${f.overlayOpacity}`;
  if (f.gradientTowards && (f.gradientFrom > 0 || f.gradientTo > 0) && isHex(f.gradientColor))
    out.op_gradient = `${f.gradientTowards},${hex(f.gradientColor)},${f.gradientFrom},${f.gradientTo}`;
  if (f.vignette > 0) out.op_vignette = String(f.vignette);
  if (f.radius > 0) out.op_radius = String(f.radius);
  if (f.borderWidth > 0 && isHex(f.borderColor)) out.op_border = `${f.borderWidth},${hex(f.borderColor)}`;
  if (f.bgc.trim()) out.bgc = f.bgc.trim().replace(/^#/, "");
  return out;
}

/** The editor's fields from a stored preset. */
export function formOf(preset: ImagePreset | null): PresetForm {
  const p = preset?.params ?? {};
  const overlay = (p.op_overlay ?? "").split(",").filter(Boolean);
  const gradient = (p.op_gradient ?? "").split(",").filter(Boolean);
  const border = (p.op_border ?? "").split(",").filter(Boolean);
  return {
    wid: p.wid ? Number(p.wid) : null,
    hei: p.hei ? Number(p.hei) : null,
    fmt: p.fmt ?? "jpeg",
    qlt: p.qlt ? Number(p.qlt) : 80,
    fit: (p.fit ?? "fit").split(",")[0],
    upscale: (p.fit ?? "").split(",")[1] === "1",
    sharpen: Boolean(p.op_sharpen || p.op_usm),
    usm: p.op_usm ?? "",
    bgc: p.bgc ?? "",
    rotate: p.rotate ? Number(p.rotate) : 0,
    flip: p.flip ?? "",
    blur: p.op_blur ? Number(p.op_blur) : 0,
    brightness: p.op_brightness ? Number(p.op_brightness) : 0,
    contrast: p.op_contrast ? Number(p.op_contrast) : 0,
    saturation: p.op_saturation ? Number(p.op_saturation) : 0,
    tone: p.op_grayscale === "1" ? "grayscale" : p.op_sepia === "1" ? "sepia" : "",
    overlayColor: overlay[0] ?? EMPTY_FORM.overlayColor,
    overlayOpacity: overlay[1] ? Number(overlay[1]) : 0,
    gradientTowards: gradient.length === 4 ? gradient[0] : "",
    gradientColor: gradient[1] ?? EMPTY_FORM.gradientColor,
    gradientFrom: gradient[2] ? Number(gradient[2]) : 0,
    gradientTo: gradient[3] ? Number(gradient[3]) : EMPTY_FORM.gradientTo,
    vignette: p.op_vignette ? Number(p.op_vignette) : 0,
    radius: p.op_radius ? Number(p.op_radius) : 0,
    borderWidth: border[0] ? Number(border[0]) : 0,
    borderColor: border[1] ?? EMPTY_FORM.borderColor,
  };
}

/** What is wrong with the form, in the user's words; empty when it can be saved. */
export function presetProblems(
  f: PresetForm,
  name: string,
  taken: string[],
  isNew: boolean,
): string[] {
  const problems: string[] = [];
  if (!name) problems.push("The preset needs a title or name.");
  if (isNew && taken.includes(name))
    problems.push(`A preset named ${name} exists already.`);
  if ((f.fit === "crop" || f.fit === "stretch") && !(f.wid && f.hei))
    problems.push(`fit=${f.fit} needs both a width and a height.`);
  if (
    f.sharpen &&
    f.usm.trim() &&
    !/^\d+(\.\d+)?,\d+(\.\d+)?,\d+(\.\d+)?$/.test(f.usm.trim())
  )
    problems.push("Unsharp mask is amount,radius,threshold.");
  if (f.bgc.trim() && !/^#?[0-9a-fA-F]{6}$/.test(f.bgc.trim()))
    problems.push("Background is a colour like #ffffff.");
  if (f.overlayOpacity > 0 && !isHex(f.overlayColor)) problems.push("Overlay colour is RRGGBB.");
  if (f.gradientTowards && !isHex(f.gradientColor)) problems.push("Gradient colour is RRGGBB.");
  if (f.borderWidth > 0 && !isHex(f.borderColor)) problems.push("Border colour is RRGGBB.");
  return problems;
}

/** Preset names: letters, digits, '.', '_' and '-'. */
export function presetName(raw: string): string {
  return raw
    .trim()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[-.]+|-+$/g, "")
    .slice(0, 63);
}

/** The Dynamic Media URL of an asset: `/is/image/<path below /content/dam, without extension>`. */
export function dmBase(assetPath: string): string {
  let rel = assetPath.startsWith(DAM_ROOT) ? assetPath.slice(DAM_ROOT.length) : assetPath;
  const slash = rel.lastIndexOf("/");
  const dot = rel.lastIndexOf(".");
  if (dot > slash + 1) rel = rel.slice(0, dot);
  return `/is/image${rel}`;
}

const SAMPLE_KEY = "studio.dm.sampleImage";

/**
 * The image preset editor, a full page like AEM's: the parameters on the left, on the right a
 * sample image delivered with them, refreshed as they change (the Dynamic Media URL is the
 * preview; nothing is computed in the browser).
 */
export function PresetEditor({
  client,
  preset,
  taken,
  onSaved,
  close,
}: {
  client: DamClient;
  preset: ImagePreset | null;
  taken: string[];
  onSaved(preset: ImagePreset): void;
  close(): void;
}) {
  const [title, setTitle] = useState(preset?.title ?? "");
  const [description, setDescription] = useState(preset?.description ?? "");
  const [form, setForm] = useState<PresetForm>(() => formOf(preset));
  const [advanced, setAdvanced] = useState(
    () => !!(form.rotate || form.flip || form.blur || form.brightness || form.contrast || form.saturation || form.tone || form.overlayOpacity || form.gradientTowards || form.vignette || form.radius || form.borderWidth),
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [baseline, setBaseline] = useState(() => ({ title: preset?.title ?? "", description: preset?.description ?? "", form: formOf(preset) }));
  const set = <K extends keyof PresetForm>(k: K, v: PresetForm[K]) =>
    setForm((f) => ({ ...f, [k]: v }));

  const name = preset?.name ?? presetName(title);
  const params = useMemo(() => presetParams(form), [form]);
  const query = Object.entries(params)
    .map(([k, v]) => `${k}=${v}`)
    .join("&");
  const problems = presetProblems(form, name, taken, !preset);

  // ---- the sample image: recent images of the DAM, the last one used remembered
  const [samples, setSamples] = useState<AssetItem[]>([]);
  const [sample, setSample] = useState<string | null>(() => {
    try {
      return localStorage.getItem(SAMPLE_KEY);
    } catch {
      return null;
    }
  });
  useEffect(() => {
    let live = true;
    client
      .search(DAM_ROOT, { types: ["image"] }, { sort: "modified", order: "desc", limit: 12 })
      .then((r) => {
        if (!live) return;
        const images = r.items.filter((i): i is AssetItem => i.type === "asset");
        setSamples(images);
        setSample((s) => (s && images.some((i) => i.path === s) ? s : (images[0]?.path ?? s)));
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [client]);
  const chooseSample = (path: string) => {
    setSample(path);
    try {
      localStorage.setItem(SAMPLE_KEY, path);
    } catch {
      /* per-viewer convenience only */
    }
  };

  const previewUrl = sample && problems.every((p) => !p.startsWith("fit=") && !p.startsWith("Unsharp") && !p.endsWith("RRGGBB.") && !p.startsWith("Background"))
    ? `${dmBase(sample)}${query ? `?${query}` : ""}`
    : null;
  const preview = usePreview(previewUrl);
  const dirty = title !== baseline.title || description !== baseline.description || JSON.stringify(form) !== JSON.stringify(baseline.form);
  const reset = () => {
    setTitle(baseline.title);
    setDescription(baseline.description);
    setForm(baseline.form);
    setError(null);
  };
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = await client.saveImagePreset(name, title.trim() || name, description.trim(), params);
      const next = { title: saved.title, description: saved.description ?? "", form: formOf(saved) };
      setBaseline(next);
      setTitle(next.title);
      setDescription(next.description);
      setForm(next.form);
      onSaved(saved);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind="Image preset"
      title={preset ? `Edit ${preset.title}` : "Create Image Preset"}
      path={preset?.name}
      dirty={dirty}
      busy={busy}
      canSave={problems.length === 0}
      onReset={reset}
      onSave={save}
      onClose={close}
      error={error}
      full
      secondaryActions={<code className="studio-assets__preset-query">/is/image/&lt;path&gt;?{query || "…"}</code>}
    >
    <div className="studio-assets__preset-editor">
      <div className="studio-assets__preset-body">
        <div className="studio-assets__preset-form studio-assets__dm-form">
          <TextField
            label="Title"
            value={title}
            onChange={setTitle}
            width="100%"
            autoFocus
            description={`Used in URLs as $${name || "…"}$`}
          />
          <TextField label="Description" value={description} onChange={setDescription} width="100%" />
          <h2>Size</h2>
          <div className="studio-assets__dm-row">
            <NumberField
              label="Width"
              value={form.wid ?? NaN}
              minValue={1}
              maxValue={4096}
              onChange={(v) => set("wid", Number.isFinite(v) ? v : null)}
              width="size-1600"
            />
            <NumberField
              label="Height"
              value={form.hei ?? NaN}
              minValue={1}
              maxValue={4096}
              onChange={(v) => set("hei", Number.isFinite(v) ? v : null)}
              width="size-1600"
            />
          </div>
          <Picker label="Fit" selectedKey={form.fit} onSelectionChange={(k) => set("fit", String(k))} width="100%">
            <Item key="fit">Fit inside (keep ratio)</Item>
            <Item key="crop">Fill and crop the middle</Item>
            <Item key="stretch">Stretch to the size</Item>
          </Picker>
          <Switch isSelected={form.upscale} onChange={(v) => set("upscale", v)}>
            Allow upscaling
          </Switch>
          <h2>Format</h2>
          <div className="studio-assets__dm-row">
            <Picker label="Format" selectedKey={form.fmt} onSelectionChange={(k) => set("fmt", String(k))} width="size-2400">
              <Item key="jpeg">JPEG</Item>
              <Item key="webp">WebP</Item>
              <Item key="webp-alpha">WebP with transparency</Item>
              <Item key="png">PNG</Item>
              <Item key="png-alpha">PNG with transparency</Item>
            </Picker>
            <NumberField
              label="Quality"
              value={form.qlt}
              minValue={1}
              maxValue={100}
              isDisabled={form.fmt.startsWith("png")}
              onChange={(v) => set("qlt", v)}
              width="size-1200"
            />
          </div>
          <TextField
            label="Background"
            value={form.bgc}
            onChange={(v) => set("bgc", v)}
            width="100%"
            description="For transparent areas when the output has none, e.g. ffffff"
          />
          <h2>Sharpening</h2>
          <Switch isSelected={form.sharpen} onChange={(v) => set("sharpen", v)}>
            Sharpen after resizing
          </Switch>
          {form.sharpen && (
            <TextField
              label="Unsharp mask"
              value={form.usm}
              onChange={(v) => set("usm", v)}
              width="100%"
              description="amount,radius,threshold; empty: 1,1,0"
            />
          )}
          <button type="button" className="studio-assets__preset-advanced" onClick={() => setAdvanced((a) => !a)} aria-expanded={advanced}>
            {advanced ? "▾" : "▸"} Advanced
          </button>
          {advanced && (
            <>
              <div className="studio-assets__dm-row">
                <Picker label="Rotate" selectedKey={String(form.rotate)} onSelectionChange={(k) => set("rotate", Number(k))} width="size-1600">
                  <Item key="0">None</Item>
                  <Item key="90">90°</Item>
                  <Item key="180">180°</Item>
                  <Item key="270">270°</Item>
                </Picker>
                <Picker label="Flip" selectedKey={form.flip || "none"} onSelectionChange={(k) => set("flip", k === "none" ? "" : String(k))} width="size-1600">
                  <Item key="none">None</Item>
                  <Item key="h">Horizontal</Item>
                  <Item key="v">Vertical</Item>
                  <Item key="hv">Both</Item>
                </Picker>
              </div>
              <Slider label="Blur" value={form.blur} minValue={0} maxValue={20} step={0.5} onChange={(v) => set("blur", v)} width="100%" />
              <Slider label="Brightness" value={form.brightness} minValue={-100} maxValue={100} onChange={(v) => set("brightness", v)} width="100%" />
              <Slider label="Contrast" value={form.contrast} minValue={-100} maxValue={100} onChange={(v) => set("contrast", v)} width="100%" />
              <Slider label="Saturation" value={form.saturation} minValue={-100} maxValue={100} onChange={(v) => set("saturation", v)} width="100%" />
              <Picker label="Tone" selectedKey={form.tone || "none"} onSelectionChange={(k) => set("tone", k === "none" ? "" : String(k))} width="100%">
                <Item key="none">Colour</Item>
                <Item key="grayscale">Black and white</Item>
                <Item key="sepia">Sepia</Item>
              </Picker>
              <h2>Overlays</h2>
              <div className="studio-assets__dm-row">
                <ColorBox label="Overlay colour" value={form.overlayColor} onChange={(v) => set("overlayColor", v)} />
                <Slider label="Overlay opacity" value={form.overlayOpacity} minValue={0} maxValue={100} onChange={(v) => set("overlayOpacity", v)} width="size-2400" />
              </div>
              <div className="studio-assets__dm-row">
                <Picker label="Gradient" selectedKey={form.gradientTowards || "none"} onSelectionChange={(k) => set("gradientTowards", k === "none" ? "" : String(k))} width="size-2400">
                  <Item key="none">None</Item>
                  <Item key="b">Fades in towards the bottom</Item>
                  <Item key="t">Fades in towards the top</Item>
                  <Item key="r">Fades in towards the right</Item>
                  <Item key="l">Fades in towards the left</Item>
                </Picker>
                {form.gradientTowards && <ColorBox label="Gradient colour" value={form.gradientColor} onChange={(v) => set("gradientColor", v)} />}
              </div>
              {form.gradientTowards && (
                <div className="studio-assets__dm-row">
                  <Slider label="Opacity at the start" value={form.gradientFrom} minValue={0} maxValue={100} onChange={(v) => set("gradientFrom", v)} width="size-2000" />
                  <Slider label="Opacity at the end" value={form.gradientTo} minValue={0} maxValue={100} onChange={(v) => set("gradientTo", v)} width="size-2000" />
                </div>
              )}
              <Slider label="Vignette" value={form.vignette} minValue={0} maxValue={100} onChange={(v) => set("vignette", v)} width="100%" />
              <h2>Shape</h2>
              <NumberField label="Rounded corners (px)" value={form.radius} minValue={0} maxValue={4096} onChange={(v) => set("radius", Number.isFinite(v) ? v : 0)} width="size-2400" description="Transparent with PNG/WebP with transparency; otherwise on the background colour or white." />
              <div className="studio-assets__dm-row">
                <NumberField label="Border (px)" value={form.borderWidth} minValue={0} maxValue={1000} onChange={(v) => set("borderWidth", Number.isFinite(v) ? v : 0)} width="size-1600" />
                {form.borderWidth > 0 && <ColorBox label="Border colour" value={form.borderColor} onChange={(v) => set("borderColor", v)} />}
              </div>
            </>
          )}
          {problems.length > 0 && (
            <ul className="studio-assets__dialog-error">
              {problems.map((x) => (
                <li key={x}>{x}</li>
              ))}
            </ul>
          )}
        </div>
        <div className="studio-assets__preset-preview">
          <div className="studio-assets__preset-stage">
            {preview.src ? (
              <img src={preview.src} alt="Preview of the preset on the sample image" className="studio-assets__preset-img" />
            ) : preview.loading ? (
              <ProgressCircle isIndeterminate aria-label="Rendering" />
            ) : (
              <p className="studio-assets__muted">{preview.error ?? "Pick a sample image below."}</p>
            )}
            {preview.loading && preview.src && <div className="studio-assets__preset-busy" aria-hidden="true" />}
          </div>
          <div className="studio-assets__preset-info">
            {preview.width ? (
              <span>
                {preview.width} × {preview.height} · {preview.type?.replace("image/", "").toUpperCase()} · {formatBytes(preview.bytes)}
                {preview.ms !== null && ` · ${preview.ms} ms`}
              </span>
            ) : (
              <span />
            )}
            {previewUrl && (
              <span className="studio-assets__preset-url">
                <code>{previewUrl}</code>
                <ActionButton isQuiet aria-label="Copy preview URL" onPress={() => void navigator.clipboard?.writeText(location.origin + previewUrl)}>
                  <Copy />
                </ActionButton>
              </span>
            )}
          </div>
          {samples.length > 0 && (
            <div className="studio-assets__preset-samples" role="radiogroup" aria-label="Sample image">
              {samples.map((s) => (
                <button
                  key={s.path}
                  type="button"
                  role="radio"
                  aria-checked={s.path === sample}
                  aria-label={s.title || s.name}
                  title={s.title || s.name}
                  className={`studio-assets__preset-sample${s.path === sample ? " is-selected" : ""}`}
                  onClick={() => chooseSample(s.path)}
                >
                  {s.thumbnail ? <img src={s.thumbnail} alt="" /> : <span>{s.name}</span>}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
    </FormPage>
  );
}

interface PreviewState {
  src: string | null;
  loading: boolean;
  error: string | null;
  width: number;
  height: number;
  bytes: number;
  type: string | null;
  ms: number | null;
}

/** Fetches `url` (debounced, the previous request dropped) and reports what came back. */
function usePreview(url: string | null): PreviewState {
  const [state, setState] = useState<PreviewState>({ src: null, loading: false, error: null, width: 0, height: 0, bytes: 0, type: null, ms: null });
  const objectUrl = useRef<string | null>(null);
  useEffect(() => {
    if (!url) {
      setState((s) => ({ ...s, src: null, loading: false, width: 0, height: 0 }));
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      setState((s) => ({ ...s, loading: true, error: null }));
      const started = performance.now();
      try {
        const res = await fetch(url, { signal: controller.signal, credentials: "same-origin" });
        if (!res.ok) throw new Error(`${res.status} ${res.statusText}`.trim());
        const blob = await res.blob();
        const ms = Math.round(performance.now() - started);
        const size = await imageSize(blob);
        if (controller.signal.aborted) return;
        if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
        objectUrl.current = URL.createObjectURL(blob);
        setState({ src: objectUrl.current, loading: false, error: null, width: size.width, height: size.height, bytes: blob.size, type: blob.type, ms });
      } catch (e) {
        if (controller.signal.aborted) return;
        setState((s) => ({ ...s, loading: false, error: `The image could not be rendered: ${message(e)}` }));
      }
    }, 350);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [url]);
  useEffect(
    () => () => {
      if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    },
    [],
  );
  return state;
}

async function imageSize(blob: Blob): Promise<{ width: number; height: number }> {
  if (typeof createImageBitmap === "function") {
    try {
      const bmp = await createImageBitmap(blob);
      const out = { width: bmp.width, height: bmp.height };
      bmp.close();
      return out;
    } catch {
      /* fall through */
    }
  }
  return { width: 0, height: 0 };
}

/** A colour as RRGGBB: a native colour picker next to the hex text, so the hex can be typed or picked. */
function ColorBox({ label, value, onChange }: { label: string; value: string; onChange(v: string): void }) {
  const valid = isHex(value);
  return (
    <div className="studio-assets__colorbox">
      <TextField label={label} value={value} onChange={onChange} width="size-1600" validationState={valid ? undefined : "invalid"} />
      <input
        type="color"
        aria-label={`${label} picker`}
        value={valid ? `#${hex(value)}` : "#000000"}
        onChange={(e) => onChange(e.target.value.slice(1))}
      />
    </div>
  );
}
