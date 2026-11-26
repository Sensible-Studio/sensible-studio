import { ActionButton, Button, ButtonGroup, Checkbox, Content, Divider, Heading, Item, Picker, ProgressCircle, Tooltip, TooltipTrigger, useDialogContainer } from '@adobe/react-spectrum';
import Preview from '@spectrum-icons/workflow/Preview';
import Delete from '@spectrum-icons/workflow/Delete';
import { useEffect, useMemo, useState } from 'react';
import { ago, versionLabel, versionPreviewUrl, type RestoreResult, type SiteRailClient, type VersionedPage } from './api';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  rail: SiteRailClient;
  page: { path: string; title: string };
  onRestored?(): void;
}

const STATUS: Record<RestoreResult['status'], string> = { restored: 'Restored', recreated: 'Restored (was deleted)', failed: 'Failed' };

/**
 * Restore Version: the versioned pages inside a page — existing ones and deleted ones — with the version to restore
 * each to. Restoring a deleted page creates it again.
 */
export function RestoreVersionDialog({ rail, page, onRestored }: Props) {
  const container = useDialogContainer();
  const [pages, setPages] = useState<VersionedPage[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [chosen, setChosen] = useState<Record<string, string>>({});
  const [results, setResults] = useState<Record<string, RestoreResult>>({});
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    rail.versionsInside(page.path).then(
      (p) => {
        setPages(p);
        setChosen(Object.fromEntries(p.map((x) => [x.path, x.versions[0]?.id ?? ''])));
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [rail, page.path]);

  const all = useMemo(() => (pages ?? []).map((p) => p.path), [pages]);

  const toggle = (path: string, on: boolean) =>
    setSelected((s) => {
      const next = new Set(s);
      if (on) next.add(path);
      else next.delete(path);
      return next;
    });

  const restore = async () => {
    setBusy(true);
    try {
      const out = await rail.restoreVersions(all.filter((p) => selected.has(p)).map((p): [string, string] => [p, chosen[p]]));
      setResults(Object.fromEntries(out.map((r) => [r.path, r])));
      setDone(true);
      onRestored?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  };

  return (
    <StudioDialog>
      <Heading>Restore Versions inside {page.title}</Heading>
      <Divider />
      <Content>
        {error && (
          <p className="studio-rail__error" role="alert">
            {error}
          </p>
        )}
        {!pages ? (
          !error && <ProgressCircle isIndeterminate aria-label="Loading versions" />
        ) : pages.length === 0 ? (
          <p className="studio-restore__empty">No page inside {page.title} has a version.</p>
        ) : (
          <>
            <p className="studio-restore__count">{selected.size} selected page(s)</p>
            <table className="studio-restore__table studio-table">
              <thead>
                <tr>
                  <th>
                    <Checkbox
                      aria-label="Select all"
                      isSelected={selected.size === all.length}
                      isIndeterminate={selected.size > 0 && selected.size < all.length}
                      onChange={(v) => setSelected(v ? new Set(all) : new Set())}
                      isDisabled={done}
                    />
                  </th>
                  <th>Title</th>
                  <th>Restore to Version</th>
                  <th>Version Comment</th>
                  <th>Restore Status</th>
                </tr>
              </thead>
              <tbody>
                {pages.map((p) => {
                  const v = p.versions.find((x) => x.id === chosen[p.path]);
                  const r = results[p.path];
                  return (
                    <tr key={p.path} className={selected.has(p.path) ? 'is-selected' : undefined}>
                      <td>
                        <Checkbox aria-label={`Restore ${p.title}`} isSelected={selected.has(p.path)} onChange={(on) => toggle(p.path, on)} isDisabled={done} />
                      </td>
                      <td>
                        <div className="studio-restore__title">
                          {p.title}
                          {p.deleted && (
                            <span className="studio-restore__deleted">
                              <Delete size="XS" /> Deleted
                            </span>
                          )}
                        </div>
                        <div className="studio-rail__muted">
                          {p.path.slice(page.path.length + 1)}
                          {p.deleted && p.deletedAt ? ` · deleted ${ago(p.deletedAt)}${p.deletedBy ? ` by ${p.deletedBy}` : ''}` : ''}
                          {p.deleted && !p.deletedAt && p.foundAt ? ` · deleted outside Pages, noticed ${ago(p.foundAt)}` : ''}
                        </div>
                      </td>
                      <td>
                        <Picker
                          aria-label={`Version of ${p.title}`}
                          items={p.versions}
                          selectedKey={chosen[p.path]}
                          onSelectionChange={(k) => setChosen((c) => ({ ...c, [p.path]: String(k) }))}
                          isDisabled={done}
                          width="size-3000"
                        >
                          {(x) => <Item key={x.id} textValue={versionLabel(x)}>{`${versionLabel(x)} — ${new Date(x.created).toLocaleString()}`}</Item>}
                        </Picker>
                        <TooltipTrigger>
                          <ActionButton
                            isQuiet
                            aria-label={`Preview ${p.title} in this version`}
                            onPress={() => window.open(versionPreviewUrl(p.path, chosen[p.path]), '_blank', 'noopener')}
                            isDisabled={!chosen[p.path]}
                          >
                            <Preview />
                          </ActionButton>
                          <Tooltip>Preview Version</Tooltip>
                        </TooltipTrigger>
                      </td>
                      <td className="studio-restore__comment">{v?.comment ?? ''}</td>
                      <td className={r ? `studio-restore__status studio-restore__status--${r.status}` : 'studio-restore__status'} title={r?.error}>
                        {r ? (r.status === 'failed' ? `Failed: ${r.error ?? ''}` : STATUS[r.status]) : ''}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </>
        )}
      </Content>
      <ButtonGroup>
        {done ? (
          <Button variant="accent" onPress={container.dismiss}>
            Done
          </Button>
        ) : (
          <>
            <Button variant="secondary" onPress={container.dismiss} isDisabled={busy}>
              Cancel
            </Button>
            <Button variant="accent" onPress={() => void restore()} isDisabled={busy || selected.size === 0}>
              Restore
            </Button>
          </>
        )}
      </ButtonGroup>
    </StudioDialog>
  );
}
