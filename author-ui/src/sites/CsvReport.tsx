import { Checkbox, Flex, NumberField, Radio, RadioGroup, TextField } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { SiteItem, StudioClient } from '../editor/api/StudioClient';
import { FormPage, FormSection } from '../shell/FormPage';

/** A column the report can have, as AEM's Create CSV Export lists them (Title and Path are always there). */
export interface CsvColumn {
  id: string;
  label: string;
  group: 'Metadata' | 'Translation' | 'Sync';
}

export const CSV_COLUMNS: CsvColumn[] = [
  { id: 'name', label: 'Name', group: 'Metadata' },
  { id: 'modified', label: 'Modified', group: 'Metadata' },
  { id: 'published', label: 'Published', group: 'Metadata' },
  { id: 'template', label: 'Template', group: 'Metadata' },
  { id: 'language', label: 'Language', group: 'Metadata' },
  { id: 'locked', label: 'Locked', group: 'Metadata' },
  { id: 'workflow', label: 'Flow', group: 'Metadata' },
  { id: 'translated', label: 'Translated', group: 'Translation' },
  { id: 'livecopy', label: 'Linked copy', group: 'Sync' },
];

/** What the report reads beyond the page listing; absent, those columns stay empty. */
export interface CsvSources {
  /** Running flows by payload path: the flow's title. */
  runningFlows?(): Promise<Record<string, string>>;
  /** Translated copies by path: their review state (review, approved, outdated, failed). */
  translations?(under: string): Promise<Record<string, string>>;
}

interface Row {
  item: SiteItem;
  depth: number;
}

/** The pages below `parent`, parents before their children, down to `levels` (1: direct children only). */
export async function collectPages(client: StudioClient, parent: string, levels: number): Promise<Row[]> {
  const out: Row[] = [];
  const walk = async (path: string, depth: number) => {
    if (depth > levels) return;
    const listing = await client.siteChildren(path);
    for (const child of listing.children) {
      if (child.type !== 'page') continue;
      out.push({ item: child, depth });
      if (child.hasChildren) await walk(child.path, depth + 1);
    }
  };
  await walk(parent, 1);
  return out;
}

const quote = (v: string) => (/[",\n\r;]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
const date = (iso?: string) => (iso ? new Date(iso).toISOString().replace('T', ' ').slice(0, 19) : '');

/** The CSV text: a header line, then one line per page. */
export function toCsv(rows: Row[], columns: string[], flows: Record<string, string>, translations: Record<string, string>): string {
  const head = ['Title', 'Path', ...CSV_COLUMNS.filter((c) => columns.includes(c.id)).map((c) => c.label)];
  const value = (r: Row, id: string): string => {
    const i = r.item;
    switch (id) {
      case 'name':
        return i.name;
      case 'modified':
        return [date(i.lastModified), i.lastModifiedBy].filter(Boolean).join(' · ');
      case 'published':
        return i.publication ? `${i.publication}${i.lastPublished ? ` ${date(i.lastPublished)}` : ''}` : 'never';
      case 'template':
        return i.templateTitle ?? i.template ?? '';
      case 'language':
        return i.language ?? '';
      case 'locked':
        return i.locked ? `yes${i.lockOwner ? ` (${i.lockOwner})` : ''}` : 'no';
      case 'workflow':
        return flows[i.path] ?? '';
      case 'translated':
        return translations[i.path] ?? '';
      case 'livecopy':
        return i.liveCopy ? (i.liveCopyRoot ? `${i.liveCopy}, root` : i.liveCopy) : '';
      default:
        return '';
    }
  };
  const lines = [head, ...rows.map((r) => [r.item.title, r.item.path, ...CSV_COLUMNS.filter((c) => columns.includes(c.id)).map((c) => value(r, c.id))])];
  // a BOM, so Excel reads the UTF-8 titles as they are
  return '﻿' + lines.map((l) => l.map(quote).join(',')).join('\r\n') + '\r\n';
}

/**
 * AEM's Create CSV Export (Create ▾ CSV Report): the pages below a parent, one line each, with the chosen
 * properties; direct children only or more levels. The file is made here and downloaded.
 */
export function CsvReport({ client, parent, sources = {}, onClose }: { client: StudioClient; parent: string; sources?: CsvSources; onClose(): void }) {
  const [path, setPath] = useState(parent);
  const [columns, setColumns] = useState<string[]>(['name', 'modified', 'published', 'template']);
  const [deep, setDeep] = useState(false);
  const [levels, setLevels] = useState(2);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !busy && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [busy, onClose]);

  const toggle = (id: string, on: boolean) => setColumns((c) => (on ? [...c, id] : c.filter((x) => x !== id)));

  const create = async () => {
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const target = path.trim().replace(/\/+$/, '');
      const rows = await collectPages(client, target, deep ? Math.max(1, levels) : 1);
      const flows = columns.includes('workflow') && sources.runningFlows ? await sources.runningFlows() : {};
      const translations = columns.includes('translated') && sources.translations ? await sources.translations(target) : {};
      const csv = toCsv(rows, columns, flows, translations);
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = `${target.split('/').pop() || 'pages'}-report.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setDone(`${rows.length} page${rows.length === 1 ? '' : 's'} in ${link.download}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const groups = ['Metadata', 'Translation', 'Sync'] as const;
  return (
    <div className="studio-wizard studio-csvreport" role="dialog" aria-modal="true" aria-label="Create CSV Report">
      <FormPage
        kind="Pages report"
        title="Create CSV Report"
        dirty={false}
        busy={busy}
        requireDirty={false}
        canSave={!!path.trim() && !busy}
        primaryLabel="Create"
        onReset={onClose}
        onSave={create}
        onClose={onClose}
        error={error}
        wide
      >
        <div className="studio-csvreport__body">
          <FormSection title="Properties to export" description="Title and path are always in the report.">
            {groups.map((g) => (
              <fieldset key={g} className="studio-csvreport__group">
                <legend>{g}</legend>
                {CSV_COLUMNS.filter((c) => c.group === g).map((c) => (
                  <Checkbox key={c.id} isSelected={columns.includes(c.id)} onChange={(on) => toggle(c.id, on)}>
                    {c.label}
                  </Checkbox>
                ))}
              </fieldset>
            ))}
          </FormSection>
          <FormSection title="Depth">
            <Flex direction="column" gap="size-150">
              <TextField label="Parent path" value={path} onChange={setPath} width="100%" />
              <RadioGroup label="Pages" value={deep ? 'deep' : 'children'} onChange={(v) => setDeep(v === 'deep')}>
                <Radio value="children">Direct children only</Radio>
                <Radio value="deep">Additional levels of children</Radio>
              </RadioGroup>
              <NumberField label="Levels" minValue={1} maxValue={20} value={levels} onChange={(v) => setLevels(Number.isFinite(v) ? v : 1)} isDisabled={!deep} width="size-1600" />
              {done && (
                <p className="studio-csvreport__done" role="status">
                  Downloaded: {done}
                </p>
              )}
            </Flex>
          </FormSection>
        </div>
      </FormPage>
    </div>
  );
}
