import {
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  Divider,
  Heading,
  ProgressCircle,
  Radio,
  RadioGroup,
  Text,
  TextArea,
  TextField,
  ToastQueue,
} from '@adobe/react-spectrum';
import { useEffect, useMemo, useState } from 'react';
import { downloadUrl, MULTI_FIELDS, type AssetDetails, type AssetItem, type BasicMetadata, type DamClient, type DamItem, type ExtractResult, type MultiField } from './api';
import { TagsField } from './AssetDetails';
import { AssetTagsField } from './tagsField';
import { StudioDialog } from '../shell/StudioDialog';
import { startDownload, suggestBackground } from './downloads';
import { fetchLicenses, type License } from './assetsConfig';

// --------------------------------------------------------------------------------------------- download

/** Download of several items (or folders) as one ZIP, as AEM's Download dialog; a large one can be prepared in the background. */
export function DownloadDialog({ items, close }: { items: DamItem[]; close(): void }) {
  const [renditions, setRenditions] = useState(false);
  const [separate, setSeparate] = useState(false);
  const [background, setBackground] = useState(() => suggestBackground(items));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const folders = items.filter((i) => i.type === 'folder').length;
  const paths = items.map((i) => i.path);
  // DRM (Assets Configurations): each licensed file is downloaded only once its license is agreed to
  const [licenses, setLicenses] = useState<License[] | null>(null);
  const [termsUrl, setTermsUrl] = useState('');
  const [agreed, setAgreed] = useState<Set<string>>(new Set());
  useEffect(() => {
    let live = true;
    fetchLicenses(paths).then(
      (r) => {
        if (!live) return;
        setLicenses(r.enabled ? r.licenses : []);
        setTermsUrl(r.usageTermsUrl ?? '');
      },
      () => live && setLicenses([]),
    );
    return () => {
      live = false;
    };
  }, [paths.join('|')]); // eslint-disable-line react-hooks/exhaustive-deps
  const agree = [...agreed];
  const onlyLicensed = items.every((i) => i.type !== 'folder') && licenses !== null && licenses.length >= items.length;
  const nothingLeft = onlyLicensed && agreed.size === 0;
  const url = downloadUrl(paths, { renditions, separate, agree });
  const go = async () => {
    if (!background) {
      const a = document.createElement('a');
      a.href = url;
      a.setAttribute('download', '');
      document.body.appendChild(a);
      a.click();
      a.remove();
      close();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const d = await startDownload(paths, { renditions, separate, agree });
      ToastQueue.info(`${d.name} is being prepared: take it from Downloads in the tool bar when it is ready.`, { timeout: 6000 });
      close();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog size="S">
      <Heading>Download</Heading>
      <Divider />
      <Content>
        <p>
          {items.length === 1 ? <strong>{items[0].title}</strong> : `${items.length} items`}
          {folders ? `, ${folders === 1 && items.length === 1 ? 'with everything in it' : `including ${folders} folder${folders > 1 ? 's' : ''}`}` : ''} as a ZIP
          archive.
        </p>
        <div className="studio-assets__download-options">
          <Checkbox isSelected={separate || renditions} isDisabled={renditions} onChange={setSeparate}>
            Create separate folder for each file
          </Checkbox>
          <Checkbox isSelected={renditions} onChange={setRenditions}>
            Renditions
          </Checkbox>
          <Checkbox isSelected={background} onChange={setBackground}>
            Prepare in the background
          </Checkbox>
        </div>
        <Text UNSAFE_className="studio-assets__muted">
          The originals are always included.
          {background ? ' You can keep working: the archive waits under Downloads in the tool bar for 7 days.' : ''}
        </Text>
        {licenses && licenses.length > 0 && (
          <section className="studio-assets__licenses" aria-label="Licenses">
            <h3>
              {licenses.length === 1 ? 'One file has a license' : `${licenses.length} files have a license`}
            </h3>
            <p className="studio-assets__muted">
              Agree to a file&apos;s license to download it; the others are left out.
              {termsUrl && (
                <>
                  {' '}
                  <a href={termsUrl} target="_blank" rel="noreferrer">
                    Usage terms
                  </a>
                </>
              )}
            </p>
            {licenses.length > 1 && (
              <Checkbox
                isSelected={agreed.size === licenses.length}
                isIndeterminate={agreed.size > 0 && agreed.size < licenses.length}
                onChange={(on) => setAgreed(on ? new Set(licenses.map((l) => l.path)) : new Set())}
              >
                Agree to all
              </Checkbox>
            )}
            <ul>
              {licenses.map((l) => (
                <li key={l.path}>
                  <strong>{l.title}</strong>
                  {l.text && <p className="studio-assets__license-text">{l.text}</p>}
                  {l.url && (
                    <a href={l.url} target="_blank" rel="noreferrer">
                      Read the license
                    </a>
                  )}
                  <Checkbox
                    isSelected={agreed.has(l.path)}
                    onChange={(on) =>
                      setAgreed((a) => {
                        const n = new Set(a);
                        if (on) n.add(l.path);
                        else n.delete(l.path);
                        return n;
                      })
                    }
                  >
                    Agree
                  </Checkbox>
                </li>
              ))}
            </ul>
          </section>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void go()} isDisabled={busy || licenses === null || nothingLeft}>
          {background ? 'Prepare' : 'Download'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

// --------------------------------------------------------------------------------------------- archives

/** Extract Archive: the ZIP's files become assets, next to it or in a folder named after it. */
export function ExtractArchiveDialog({
  client,
  archive,
  onDone,
  close,
}: {
  client: DamClient;
  archive: AssetItem;
  onDone(result: ExtractResult): void;
  close(): void;
}) {
  const [into, setInto] = useState<'here' | 'folder'>('folder');
  const [conflict, setConflict] = useState<'skip' | 'replace' | 'rename'>('skip');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const base = archive.name.replace(/\.[^.]*$/, '');
  return (
    <StudioDialog size="S">
      <Heading>Extract Archive</Heading>
      <Divider />
      <Content>
        <RadioGroup label="Extract to" value={into} onChange={(v) => setInto(v as 'here' | 'folder')} isDisabled={busy}>
          <Radio value="folder">A new folder “{base}”</Radio>
          <Radio value="here">This folder</Radio>
        </RadioGroup>
        <RadioGroup label="When a file name exists" value={conflict} onChange={(v) => setConflict(v as typeof conflict)} isDisabled={busy}>
          <Radio value="skip">Skip it</Radio>
          <Radio value="rename">Keep both</Radio>
          <Radio value="replace">Replace</Radio>
        </RadioGroup>
        <Text UNSAFE_className="studio-assets__muted">Only the file types that can be uploaded are extracted.</Text>
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          isDisabled={busy}
          onPress={async () => {
            setBusy(true);
            setError(null);
            try {
              onDone(await client.extractArchive(archive.path, { into, conflict }));
              close();
            } catch (e) {
              setError(e instanceof Error ? e.message : String(e));
            } finally {
              setBusy(false);
            }
          }}
        >
          {busy ? 'Extracting…' : 'Extract'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

// ---------------------------------------------------------------------------------- bulk properties

const SINGLE = ['dc:title', 'dc:description', 'dc:language', 'dc:rights'] as const;
type SingleField = (typeof SINGLE)[number];

const listOf = (v: unknown): string[] => (Array.isArray(v) ? v.map(String) : v === undefined || v === null || v === '' ? [] : [String(v)]);
const textOf = (v: unknown): string => (v === undefined || v === null ? '' : Array.isArray(v) ? v.join(', ') : String(v));

/** What the assets have in common: a value where all agree, `null` where they differ. */
export function common(assets: Array<Pick<AssetDetails, 'metadata'>>) {
  const single = {} as Record<SingleField, string | null>;
  for (const k of SINGLE) {
    const vals = new Set(assets.map((a) => textOf(a.metadata[k])));
    single[k] = vals.size === 1 ? [...vals][0] : null;
  }
  const multi = {} as Record<MultiField, string[] | null>;
  for (const k of MULTI_FIELDS) {
    const vals = new Set(assets.map((a) => JSON.stringify(listOf(a.metadata[k]))));
    multi[k] = vals.size === 1 ? listOf(assets[0]?.metadata[k]) : null;
  }
  return { single, multi };
}

/**
 * Properties of several assets at once. Only fields that were changed are written; multi-valued
 * fields are appended to each asset's own values unless "Append" is unticked.
 */
export function BulkPropertiesDialog({ client, paths, onDone, close }: { client: DamClient; paths: string[]; onDone(saved: number): void; close(): void }) {
  const [assets, setAssets] = useState<AssetDetails[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [single, setSingle] = useState<Partial<Record<SingleField, string>>>({});
  const [multi, setMulti] = useState<Partial<Record<MultiField, string[]>>>({});
  const [replace, setReplace] = useState<ReadonlySet<MultiField>>(new Set());

  useEffect(() => {
    let live = true;
    Promise.all(paths.map((p) => client.asset(p))).then(
      (a) => live && setAssets(a),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, paths]);

  const shared = useMemo(() => (assets ? common(assets) : null), [assets]);
  const touched = Object.keys(single).length + Object.keys(multi).length;

  const save = async () => {
    setBusy(true);
    setError(null);
    const fields: BasicMetadata = { ...single, ...multi };
    const append = MULTI_FIELDS.filter((k) => k in multi && !replace.has(k));
    let saved = 0;
    try {
      for (const p of paths) {
        await client.saveMetadata(p, fields, append);
        saved++;
      }
      onDone(saved);
      close();
    } catch (e) {
      setError(`${saved} of ${paths.length} saved. ${e instanceof Error ? e.message : String(e)}`);
      if (saved > 0) onDone(saved);
    } finally {
      setBusy(false);
    }
  };

  const text = (k: SingleField, label: string, area = false) => {
    const mixed = shared?.single[k] === null;
    const value = single[k] ?? shared?.single[k] ?? '';
    const props = {
      label,
      value,
      width: '100%',
      placeholder: mixed ? 'Mixed values' : undefined,
      description: mixed && !(k in single) ? 'The files differ; a value set here replaces theirs.' : undefined,
      onChange: (v: string) => setSingle((s) => ({ ...s, [k]: v })),
      isDisabled: busy,
    };
    return area ? <TextArea {...props} /> : <TextField {...props} />;
  };

  const many = (k: MultiField, label: string) => {
    const mixed = shared?.multi[k] === null;
    const appending = !replace.has(k);
    // Appending shows only what is added; replacing starts from the common values.
    const values = multi[k] ?? (appending ? [] : (shared?.multi[k] ?? []));
    const onChange = (v: string[]) => setMulti((m) => ({ ...m, [k]: v }));
    const description = appending ? `Added to each file's ${label.toLowerCase()}.` : mixed ? 'Replaces the values of every file.' : undefined;
    return (
      <div className="studio-assets__bulk-multi" key={k}>
        {k === 'cq:tags' ? <AssetTagsField label={label} values={values} onChange={onChange} description={description} /> : <TagsField label={label} values={values} onChange={onChange} />}
        {k !== 'cq:tags' && description && <Text UNSAFE_className="studio-assets__muted">{description}</Text>}
        <Checkbox
          isSelected={appending}
          isDisabled={busy}
          onChange={(on) => {
            setReplace((r) => {
              const next = new Set(r);
              if (on) next.delete(k);
              else next.add(k);
              return next;
            });
            setMulti((m) => {
              const next = { ...m };
              delete next[k];
              return next;
            });
          }}
        >
          Append
        </Checkbox>
      </div>
    );
  };

  return (
    <StudioDialog size="L">
      <Heading>Properties of {paths.length} files</Heading>
      <Divider />
      <Content>
        {!assets && !error && <ProgressCircle isIndeterminate aria-label="Loading" />}
        {assets && (
          <div className="studio-assets__props-form studio-assets__bulk">
            {text('dc:title', 'Title')}
            {text('dc:description', 'Description', true)}
            {many('cq:tags', 'Tags')}
            {many('dc:subject', 'Keywords')}
            {many('dc:creator', 'Creator')}
            {many('dc:contributor', 'Contributor')}
            {text('dc:language', 'Language')}
            {text('dc:rights', 'Copyright')}
          </div>
        )}
        {error && <p className="studio-assets__dialog-error">{error}</p>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={close} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={save} isDisabled={busy || !assets || touched === 0}>
          {busy ? 'Saving…' : 'Save'}
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
