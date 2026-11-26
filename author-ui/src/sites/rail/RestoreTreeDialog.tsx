import {
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  ContextualHelp,
  DatePicker,
  Divider,
  Heading,
  ProgressCircle,
  Text,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import { getLocalTimeZone, now, type ZonedDateTime } from '@internationalized/date';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Folder from '@spectrum-icons/workflow/Folder';
import WebPage from '@spectrum-icons/workflow/WebPage';
import { useEffect, useMemo, useState } from 'react';
import type { SiteRailClient, TreeAtNode } from './api';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  rail: SiteRailClient;
  page: { path: string; title: string };
  onRestored?(): void;
  /** for tests: the date to start from (default: now) */
  initialDate?: ZonedDateTime;
}

type Fate = 'restore' | 'recreate' | 'container' | 'keep' | 'remove' | 'folder';

/** What Restore Tree does to each node (the same rules as the server). */
export function fates(root: TreeAtNode, preserve: boolean): Map<string, Fate> {
  const out = new Map<string, Fate>();
  const walk = (n: TreeAtNode, isRoot: boolean): boolean => {
    let below = false;
    for (const c of n.children) below = walk(c, false) || below;
    const here = !!n.version;
    let fate: Fate;
    if (!n.page) fate = 'folder';
    else if (here) fate = n.exists ? 'restore' : 'recreate';
    else if (!n.exists && below && n.fallback) fate = 'container';
    else if (!preserve && !isRoot && n.exists && !below) fate = 'remove';
    else fate = 'keep';
    out.set(n.path, fate);
    return here || below;
  };
  walk(root, true);
  return out;
}

const DESCRIBE: Record<Fate, string> = {
  restore: 'Restored to this version',
  recreate: 'Deleted since: comes back',
  container: 'Deleted since: comes back with the pages below it',
  keep: 'Not versioned: kept as it is',
  remove: 'Not versioned: deleted (it can be restored later)',
  folder: 'Folder',
};

/**
 * Restore Tree: every page of the tree back to the latest version it had at a date, deleted pages included. Without
 * "Preserve Non Versioned Pages", the pages that had no version then are deleted.
 */
export function RestoreTreeDialog({ rail, page, onRestored, initialDate }: Props) {
  const container = useDialogContainer();
  const [date, setDate] = useState<ZonedDateTime>(() => initialDate ?? now(getLocalTimeZone()));
  const [preserve, setPreserve] = useState(true);
  const [tree, setTree] = useState<TreeAtNode | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState<Set<string>>(new Set([page.path]));
  const iso = date.toDate().toISOString();

  useEffect(() => {
    let live = true;
    setTree(null);
    setError(null);
    const timer = window.setTimeout(() => {
      rail.treeAt(page.path, iso).then(
        (t) => live && setTree(t),
        (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
      );
    }, 200);
    return () => {
      live = false;
      window.clearTimeout(timer);
    };
  }, [rail, page.path, iso]);

  const fate = useMemo(() => (tree ? fates(tree, preserve) : new Map<string, Fate>()), [tree, preserve]);
  const changes = [...fate.values()].filter((f) => f === 'restore' || f === 'recreate' || f === 'container' || f === 'remove').length;

  const restore = async () => {
    setBusy(true);
    try {
      const r = await rail.restoreTree(page.path, iso, preserve);
      const parts = [`${r.restored} restored`, r.recreated ? `${r.recreated} brought back` : '', r.removed ? `${r.removed} deleted` : ''].filter(Boolean);
      if (r.failed.length) {
        ToastQueue.negative(`${parts.join(', ')}; ${r.failed.length} failed: ${r.failed.map((f) => `${f.path} (${f.error})`).join('; ')}`, { timeout: 10000 });
      } else {
        ToastQueue.positive(`Tree restored: ${parts.join(', ')}`, { timeout: 5000 });
      }
      onRestored?.();
      container.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  const toggle = (p: string) =>
    setOpen((s) => {
      const next = new Set(s);
      if (next.has(p)) next.delete(p);
      else next.add(p);
      return next;
    });

  const row = (n: TreeAtNode, depth: number) => {
    const f = fate.get(n.path) ?? 'keep';
    const expanded = open.has(n.path);
    const v = n.version ?? (f === 'container' ? n.fallback : undefined);
    return (
      <li key={n.path} role="treeitem" aria-expanded={n.children.length ? expanded : undefined}>
        <div className={`studio-restore__node studio-restore__node--${f}`} style={{ paddingLeft: depth * 20 }}>
          {n.children.length ? (
            <button type="button" className="studio-tree__twisty" aria-label={`${expanded ? 'Collapse' : 'Expand'} ${n.title}`} onClick={() => toggle(n.path)}>
              {expanded ? <ChevronDown size="XS" /> : <ChevronRight size="XS" />}
            </button>
          ) : (
            <span className="studio-tree__twisty" aria-hidden />
          )}
          <span className="studio-tree__icon">{n.page ? <WebPage size="S" /> : <Folder size="S" />}</span>
          <span className="studio-restore__name">
            {v?.title ?? n.title} ({n.name}, {v ? `version ${v.label ? `${v.name} · ${v.label}` : v.name}` : n.page ? 'not versioned' : 'folder'})
          </span>
          <span className={`studio-restore__dot studio-restore__dot--${f}`} aria-hidden />
          <span className="studio-restore__fate">{DESCRIBE[f]}</span>
        </div>
        {expanded && n.children.length > 0 && <ul role="group">{n.children.map((c) => row(c, depth + 1))}</ul>}
      </li>
    );
  };

  return (
    <StudioDialog>
      <Heading>Restore Tree at {page.title}</Heading>
      <Divider />
      <Content>
        <div className="studio-restore__form">
          <DatePicker
            label="Latest Versions at Date"
            value={date}
            onChange={(d) => d && setDate(d as ZonedDateTime)}
            granularity="minute"
            hideTimeZone
            maxValue={now(getLocalTimeZone())}
          />
          <div className="studio-restore__preserve">
            <Checkbox isSelected={preserve} onChange={setPreserve}>
              Preserve Non Versioned Pages
            </Checkbox>
            <ContextualHelp variant="info">
              <Heading>Pages without a version</Heading>
              <Content>
                <Text>
                  Checked, the pages that had no version at that date stay as they are. Unchecked, they are deleted — with a version first, so Restore Version
                  can bring them back.
                </Text>
              </Content>
            </ContextualHelp>
          </div>
        </div>
        <h3 className="studio-restore__heading">Revised structure</h3>
        {error && (
          <p className="studio-rail__error" role="alert">
            {error}
          </p>
        )}
        {!tree ? (
          !error && <ProgressCircle isIndeterminate aria-label="Loading the tree" />
        ) : (
          <>
            {changes === 0 && <p className="studio-restore__empty">No versions to restore.</p>}
            <ul role="tree" aria-label="Revised structure" className="studio-restore__tree">
              {row(tree, 0)}
            </ul>
          </>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={container.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void restore()} isDisabled={busy || !tree || changes === 0}>
          Restore
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
