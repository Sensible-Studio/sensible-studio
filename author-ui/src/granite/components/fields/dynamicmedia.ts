import type { DataSource, DataSourceForm } from '../../registry';
import type { GraniteNode } from '../../types';

/**
 * Datasources of Sensible Studio's Dynamic Media component (`/libs/dam/components/dynamicmedia`), read from the
 * DAM API: the image presets, and the smart crops of the image chosen in the dialog.
 */

const fieldValue = (form: DataSourceForm | undefined, name: string) => {
  const v = form?.get(name);
  return typeof v === 'string' ? v : '';
};

async function damJson(query: Record<string, string>): Promise<Record<string, unknown> | null> {
  try {
    const res = await fetch(`/bin/studio/dam.json?${new URLSearchParams(query)}`, { credentials: 'same-origin' });
    return res.ok ? ((await res.json()) as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** `dam/components/scene7/common/imagepresetdatasource`: Tools › Assets › Image Presets, by title. */
export const imagePresets: DataSource = async () => {
  const r = await damJson({ op: 'imagePresets' });
  const presets = (r?.presets as Array<{ name: string; title?: string; query?: string }> | undefined) ?? [];
  return presets.map((p) => ({ value: p.name, text: `${p.title || p.name} (${p.query ?? ''})` }));
};

export const GALLERY_PRESETS_DATASOURCE = 'studio/dam/datasources/gallerypresets';

/** `studio/dam/datasources/gallerypresets`: Media › Image Presets › Gallery Presets, by title. */
export const galleryPresets: DataSource = async () => {
  const r = await damJson({ op: 'galleryPresets', path: '/content/dam' });
  const presets = (r?.presets as Array<{ name: string; title?: string }> | undefined) ?? [];
  return presets.map((p) => ({ value: p.name, text: p.title || p.name }));
};

export const SMART_CROPS_DATASOURCE = 'studio/dam/datasources/smartcrops';

/** What the smart crops depend on: the image chosen in the dialog. */
export const fileReferenceKey = (_node: GraniteNode, form: DataSourceForm) => fieldValue(form, './fileReference');

/** `studio/dam/datasources/smartcrops`: the smart crops of the chosen image, from its folder's image profile. */
export const smartCrops: DataSource = async (_node, _env, form) => {
  const path = fieldValue(form, './fileReference');
  if (!path.startsWith('/content/dam/')) return [];
  const r = await damJson({ op: 'asset', path });
  const crops = (r?.smartCrops as Array<{ name: string; ratioWidth: number; ratioHeight: number; manual?: boolean }> | undefined) ?? [];
  return crops.map((c) => ({ value: c.name, text: `${c.name} (${c.ratioWidth}:${c.ratioHeight}${c.manual ? ', manual' : ''})` }));
};

// ------------------------------------------------------------------------------------------------ breakpoints

export const DM_EDITOR_CLIENTLIB = 'studio.dam.dynamicmedia.editor';
const DM_HOOK = 'studio-dm-hook';
const CORAL = 'granite/ui/components/coral/foundation';
const PRESET_DATASOURCE = 'dam/components/scene7/common/imagepresetdatasource';

export interface Breakpoint {
  name: string;
  min: number;
  max: number;
}

/** Without a policy, the component's own breakpoints (see dynamicmedia.js). */
export const DEFAULT_BREAKPOINTS: Breakpoint[] = [
  { name: 'Small', min: 0, max: 767 },
  { name: 'Medium', min: 768, max: 1023 },
  { name: 'Large', min: 1024, max: 1279 },
  { name: 'X-Large', min: 1280, max: 1919 },
  { name: 'XX-Large', min: 1920, max: 0 },
];

/** The property suffix of a breakpoint, as dynamicmedia.js derives it: "X-Large" → "x-large". */
export const breakpointSlug = (name: string) =>
  name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');

const num = (v: unknown) => {
  const n = typeof v === 'number' ? v : parseInt(String(v ?? ''), 10);
  return Number.isFinite(n) && n > 0 ? n : 0;
};

/** The breakpoints of a policy (`breakpoints/item0..n`), sorted; the defaults when it has none. */
export function policyBreakpoints(design: Record<string, unknown> | undefined): Breakpoint[] {
  const list = design?.breakpoints;
  const out: Breakpoint[] = [];
  if (list && typeof list === 'object') {
    for (const item of Object.values(list as Record<string, unknown>)) {
      if (!item || typeof item !== 'object') continue;
      const { name, min, max } = item as Record<string, unknown>;
      const n = String(name ?? '').trim();
      if (n) out.push({ name: n, min: num(min), max: num(max) });
    }
  }
  return (out.length ? out : DEFAULT_BREAKPOINTS).slice().sort((a, b) => a.min - b.min);
}

export const breakpointRange = (bp: Breakpoint) =>
  bp.max > 0 ? `${bp.min}–${bp.max} px` : `from ${bp.min} px`;

const node = (name: string, properties: GraniteNode['properties'], children: GraniteNode[] = []): GraniteNode => ({ name, properties, children });

/**
 * The dialog's `studio-dm-hook="breakpoints"` container becomes an accordion with a panel per breakpoint of the
 * policy, each choosing the smart crop (and optionally the preset) for that range of screens.
 */
export function breakpointsAccordion(design: Record<string, unknown> | undefined): GraniteNode {
  const panels = policyBreakpoints(design).map((bp) => {
    const s = breakpointSlug(bp.name);
    return node('bp-' + s, { 'jcr:title': `${bp.name} (${breakpointRange(bp)})`, 'sling:resourceType': `${CORAL}/container`, margin: true }, [
      node('items', {}, [
        node('crop', {
          'sling:resourceType': `${CORAL}/form/select`,
          name: `./crop_${s}`,
          fieldLabel: 'Focus crop',
          fieldDescription: bp.max > 0 ? `Screens up to ${bp.max} px wide. Empty: the next larger breakpoint's crop.` : 'The widest screens; also the image browsers without <picture> support get. Empty: the whole image.',
          emptyOption: true,
        }, [node('datasource', { 'sling:resourceType': SMART_CROPS_DATASOURCE })]),
        node('preset', {
          'sling:resourceType': `${CORAL}/form/select`,
          name: `./preset_${s}`,
          fieldLabel: 'Image preset',
          fieldDescription: "Empty: the preset of the component's configuration.",
          emptyOption: true,
        }, [node('datasource', { 'sling:resourceType': PRESET_DATASOURCE })]),
      ]),
    ]);
  });
  return node('breakpoints', { 'sling:resourceType': `${CORAL}/accordion`, variant: 'quiet' }, [node('items', {}, panels)]);
}

/** Behaviour of `studio.dam.dynamicmedia.editor`: the breakpoint panels come from the policy. */
export function dynamicMediaTransform(root: GraniteNode, env: { design?: Record<string, unknown> }): GraniteNode {
  const visit = (n: GraniteNode): GraniteNode => {
    const data = n.children.find((c) => c.name === 'granite:data');
    if (data && String(data.properties[DM_HOOK] ?? '') === 'breakpoints') return breakpointsAccordion(env.design);
    return { ...n, children: n.children.map(visit) };
  };
  return visit(root);
}
