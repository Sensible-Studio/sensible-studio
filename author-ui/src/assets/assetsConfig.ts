/**
 * Tools › Assets › Assets Configurations (AEM's): the Media settings that are not per folder. Read by the
 * console (uploads, downloads, search, Share Link), changed by administrators. `DamAssetsConfig` on the server.
 */
import { useEffect, useState } from 'react';
import type { WatermarkSetting } from './api';

export type ShareMode = 'editable' | 'readonly' | 'hidden';
export type ValidityUnit = 'hours' | 'days' | 'months' | 'years';

export interface LinkShareSettings {
  includeOriginals: boolean;
  originalsMode: ShareMode;
  includeRenditions: boolean;
  renditionsMode: ShareMode;
  validityAmount: number;
  validityUnit: ValidityUnit;
  /** allow: only these groups share links; deny: everyone but them (none listed: everyone) */
  groupsMode: 'allow' | 'deny';
  groups: string[];
}

export interface AssetsConfigurations {
  uploadRestrictions: { allowedMimeTypes: string[] };
  drm: { enabled: boolean; usageTermsUrl: string };
  omnisearch: { guessTotal: 1000 | 500 | 200; currentFolder: boolean };
  duplicates: { enabled: boolean; field: string };
  linkShare: LinkShareSettings;
  watermark?: WatermarkSetting;
  admin?: boolean;
  /** this user may make share links (Link Share's groups) */
  mayShare?: boolean;
}

export type AssetsConfigName = 'uploadrestrictions' | 'drm' | 'omnisearch' | 'duplicates' | 'linkshare';

export interface License {
  path: string;
  title: string;
  /** the license's text (its HTML as plain text) */
  text?: string | null;
  /** a page with the license */
  url?: string | null;
}

export const DEFAULT_ASSETS_CONFIG: AssetsConfigurations = {
  uploadRestrictions: { allowedMimeTypes: [] },
  drm: { enabled: false, usageTermsUrl: '' },
  omnisearch: { guessTotal: 1000, currentFolder: true },
  duplicates: { enabled: true, field: 'dam:sha1' },
  linkShare: {
    includeOriginals: true,
    originalsMode: 'editable',
    includeRenditions: false,
    renditionsMode: 'editable',
    validityAmount: 7,
    validityUnit: 'days',
    groupsMode: 'allow',
    groups: [],
  },
  mayShare: true,
};

const API = '/bin/studio/dam.json';
const CSRF = { 'X-Studio-CSRF': '1' };

async function answer<T>(res: Response, what: string): Promise<T> {
  const body = (await res.json().catch(() => ({}))) as T & { error?: string };
  if (!res.ok) throw new Error(body.error ?? `${what}: HTTP ${res.status}`);
  return body;
}

let cached: Promise<AssetsConfigurations> | null = null;

/** The settings, read once per page load (saving reads them again). */
export function loadAssetsConfig(force = false): Promise<AssetsConfigurations> {
  if (!cached || force) {
    cached = fetch(`${API}?op=assetsConfigurations`, { credentials: 'same-origin', cache: 'no-store' })
      .then((r) => answer<AssetsConfigurations>(r, 'Assets Configurations'))
      .catch((e: unknown) => {
        cached = null;
        throw e;
      });
  }
  return cached;
}

/** For tests: forget what was read. */
export function resetAssetsConfig(value?: AssetsConfigurations): void {
  cached = value ? Promise.resolve(value) : null;
}

export async function saveAssetsConfig(name: AssetsConfigName, config: object): Promise<AssetsConfigurations> {
  const body = new URLSearchParams({ op: 'saveAssetsConfiguration', name, config: JSON.stringify(config) });
  const saved = await answer<AssetsConfigurations>(
    await fetch(API, { method: 'POST', credentials: 'same-origin', headers: CSRF, body }),
    'Save',
  );
  cached = Promise.resolve(saved);
  return saved;
}

/** DRM: the licensed files among (and below) the items, when DRM is on. */
export async function fetchLicenses(paths: string[]): Promise<{ enabled: boolean; usageTermsUrl: string; licenses: License[] }> {
  const q = new URLSearchParams({ op: 'licenses' });
  for (const p of paths) q.append('item', p);
  return answer(await fetch(`${API}?${q}`, { credentials: 'same-origin', cache: 'no-store' }), 'Licenses');
}

/** Whether a file of this MIME type may be uploaded (none listed: all; image/* a family). */
export function mimeAllowed(allowed: string[], mime: string): boolean {
  if (allowed.length === 0) return true;
  const m = mime.toLowerCase().split(';')[0].trim();
  return allowed.some((a) => a === m || (a.endsWith('/*') && m.startsWith(a.slice(0, -1))));
}

/** "7 days", "1 year", "12 hours". */
export function validityLabel(amount: number, unit: ValidityUnit): string {
  const one = unit.slice(0, -1);
  return `${amount} ${amount === 1 ? one : unit}`;
}

/** The settings in a component: the defaults until they are read (and when they cannot be). */
export function useAssetsConfig(): AssetsConfigurations {
  const [c, setC] = useState<AssetsConfigurations>(DEFAULT_ASSETS_CONFIG);
  useEffect(() => {
    let live = true;
    loadAssetsConfig().then((v) => live && setC(v), () => undefined);
    return () => {
      live = false;
    };
  }, []);
  return c;
}

/** DRM is on and the file has a license: it is downloaded through the Download dialog, after agreeing. */
export function needsLicense(c: AssetsConfigurations, item: { licensed?: boolean }): boolean {
  return c.drm.enabled && item.licensed === true;
}

/** The preview of a licensed file with the system watermark on it (`DrmPreviewServlet`). */
export function drmPreviewUrl(path: string, version?: string | number | null): string {
  const q = new URLSearchParams({ path });
  if (version) q.set('_ck', String(version));
  return `/bin/studio/dam/drmpreview.img?${q}`;
}
