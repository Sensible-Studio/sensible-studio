import { ActionButton, Button, Checkbox, ProgressCircle, Text, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Folder from '@spectrum-icons/workflow/Folder';
import Revert from '@spectrum-icons/workflow/Revert';
import { Fragment, useCallback, useEffect, useMemo, useState } from 'react';
import { security, type PermColumn, type PermLevel, type PermRow, type PermValue } from './api';

const LABELS: Record<PermColumn, string> = {
  read: 'Read',
  modify: 'Modify',
  create: 'Create',
  delete: 'Delete',
  readAcl: 'Read ACL',
  editAcl: 'Edit ACL',
  replicate: 'Publish',
};

const HINTS: Record<PermColumn, string> = {
  read: 'See the node and its properties',
  modify: 'Change properties, lock, create versions',
  create: 'Add nodes below',
  delete: 'Remove the node and nodes below',
  readAcl: 'See who may do what here',
  editAcl: 'Change who may do what here',
  replicate: 'Publish and unpublish',
};

/** A change waiting for Save: a box ticked or cleared, or a row put back to what it inherits. */
type Pending = Partial<Record<PermColumn, PermValue>>;

/**
 * Permissions as a tree, the way AEM's user admin shows them: the repository from `/`, and for every node
 * a box per column — ticked when the user or group can do it there, from wherever that comes (its own
 * entry, a parent, a group, everyone). A dot marks what is set on the node itself (green allowed, red
 * denied). Ticking or clearing boxes stages changes; Save writes them, each as an allow or deny entry on
 * that node only when what is inherited is not already right.
 */
export function PermissionTree({ principal, name }: { principal: string; name: string }) {
  const [levels, setLevels] = useState<Map<string, PermLevel>>(new Map());
  const [expanded, setExpanded] = useState<Set<string>>(new Set(['/', '/content']));
  const [pending, setPending] = useState<Map<string, Pending>>(new Map());
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const load = useCallback(
    async (paths: string[]) => {
      try {
        const loaded = await Promise.all(paths.map((p) => security.tree(principal, p).catch(() => null)));
        setLevels((prev) => {
          const next = new Map(prev);
          for (const l of loaded) if (l) next.set(l.node.path, l);
          return next;
        });
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [principal],
  );

  useEffect(() => {
    setLevels(new Map());
    setPending(new Map());
    void load(['/', '/content']);
  }, [load]);

  const root = levels.get('/');
  const columns = root?.columns ?? [];

  const toggleOpen = (path: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(path)) next.delete(path);
      else {
        next.add(path);
        if (!levels.has(path)) void load([path]);
      }
      return next;
    });
  };

  /** What a box shows: the staged change if any, else what the principal can do. */
  const shown = (row: PermRow, col: PermColumn): boolean => {
    const p = pending.get(row.path)?.[col];
    if (p === 'allow') return true;
    if (p === 'deny') return false;
    if (p === 'inherit') return row.set[col] ? !row.can[col] : !!row.can[col]; // best guess until saved
    return !!row.can[col];
  };

  const change = (row: PermRow, col: PermColumn, on: boolean) => {
    setPending((prev) => {
      const next = new Map(prev);
      const p: Pending = { ...(next.get(row.path) ?? {}) };
      if (on === !!row.can[col] && !p[col]) return prev;
      if (on === !!row.can[col]) delete p[col];
      else p[col] = on ? 'allow' : 'deny';
      if (Object.keys(p).length) next.set(row.path, p);
      else next.delete(row.path);
      return next;
    });
  };

  const inherit = (row: PermRow) => {
    setPending((prev) => {
      const next = new Map(prev);
      const p: Pending = {};
      for (const c of Object.keys(row.set) as PermColumn[]) p[c] = 'inherit';
      next.set(row.path, p);
      return next;
    });
  };

  const changes = useMemo(() => [...pending.values()].reduce((n, p) => n + Object.keys(p).length, 0), [pending]);

  const save = async () => {
    setSaving(true);
    let failed = 0;
    for (const [path, values] of pending) {
      try {
        await security.setPermissions(principal, path, values);
      } catch (e) {
        failed++;
        ToastQueue.negative(`${path}: ${e instanceof Error ? e.message : String(e)}`, { timeout: 8000 });
      }
    }
    setPending(new Map());
    // what is inherited below a changed node changes too: read again every level on screen
    await load([...levels.keys()]);
    setSaving(false);
    if (!failed) ToastQueue.positive(`Permissions of ${name} saved`, { timeout: 3000 });
  };

  const rows = (path: string, depth: number): JSX.Element[] => {
    const level = levels.get(path);
    if (!level) return [];
    const out: JSX.Element[] = [];
    for (const child of level.children) {
      out.push(renderRow(child, depth));
      if (expanded.has(child.path)) {
        if (!levels.has(child.path)) {
          out.push(
            <tr key={`${child.path}#loading`}>
              <td colSpan={columns.length + 2} style={{ paddingLeft: 32 + (depth + 1) * 20 }}>
                <ProgressCircle size="S" isIndeterminate aria-label="Loading" />
              </td>
            </tr>,
          );
        } else {
          out.push(...rows(child.path, depth + 1));
        }
      }
    }
    if (level.more) {
      out.push(
        <tr key={`${path}#more`}>
          <td colSpan={columns.length + 2} className="studio-permtree__more" style={{ paddingLeft: 32 + depth * 20 }}>
            Only the first 500 nodes are shown here.
          </td>
        </tr>,
      );
    }
    return out;
  };

  const renderRow = (row: PermRow, depth: number) => {
    const open = expanded.has(row.path);
    const staged = pending.get(row.path);
    const hasSet = Object.keys(row.set).length > 0;
    return (
      <tr key={row.path} className={staged ? 'is-pending' : undefined}>
        <td className="studio-permtree__path" style={{ paddingLeft: 8 + depth * 20 }} title={row.path}>
          {row.hasChildren ? (
            <button type="button" className="studio-permtree__twisty" aria-label={open ? `Collapse ${row.name}` : `Expand ${row.name}`} aria-expanded={open} onClick={() => toggleOpen(row.path)}>
              {open ? <ChevronDown size="XS" /> : <ChevronRight size="XS" />}
            </button>
          ) : (
            <span className="studio-permtree__twisty" />
          )}
          <Folder size="S" UNSAFE_className="studio-permtree__icon" />
          <span className="studio-permtree__name">{row.name}</span>
          {row.title && row.title !== row.name && <span className="studio-permtree__title">{row.title}</span>}
          {row.restricted && (
            <TooltipTrigger delay={300}>
              <ActionButton isQuiet UNSAFE_className="studio-permtree__glob" aria-label="Has entries with restrictions">
                *
              </ActionButton>
              <Tooltip>Entries with restrictions (globs) are set here too; see Entries.</Tooltip>
            </TooltipTrigger>
          )}
        </td>
        {columns.map((c) => {
          const set = staged?.[c] && staged[c] !== 'inherit' ? (staged[c] as 'allow' | 'deny') : staged?.[c] === 'inherit' ? undefined : row.set[c];
          return (
            <td key={c} className={`studio-permtree__cell${set ? ` is-set-${set}` : ''}${staged?.[c] ? ' is-changed' : ''}`}>
              <Checkbox
                aria-label={`${LABELS[c]} on ${row.path}`}
                isSelected={shown(row, c)}
                onChange={(on) => change(row, c, on)}
                isEmphasized
                isDisabled={saving}
                UNSAFE_className="studio-permtree__box"
              />
              {set && <span className="studio-permtree__dot" aria-hidden title={set === 'allow' ? 'Allowed here' : 'Denied here'} />}
            </td>
          );
        })}
        <td className="studio-permtree__rowactions">
          {hasSet && !staged && (
            <TooltipTrigger delay={300}>
              <ActionButton isQuiet aria-label={`Inherit on ${row.path}`} onPress={() => inherit(row)}>
                <Revert size="S" />
              </ActionButton>
              <Tooltip>Remove what is set here: inherit everything</Tooltip>
            </TooltipTrigger>
          )}
        </td>
      </tr>
    );
  };

  if (error && !root) {
    return (
      <div className="studio-sites__error" role="alert">
        {error}
      </div>
    );
  }
  if (!root) return <ProgressCircle isIndeterminate aria-label="Loading" />;

  return (
    <div className="studio-permtree">
      <div className="studio-permtree__bar">
        <div className="studio-permtree__legend">
          <span>
            <Checkbox isSelected isEmphasized isReadOnly aria-label="Can" UNSAFE_className="studio-permtree__box" /> can
          </span>
          <span className="is-set-allow">
            <span className="studio-permtree__dot" /> allowed here
          </span>
          <span className="is-set-deny">
            <span className="studio-permtree__dot" /> denied here
          </span>
          <span className="studio-permtree__inherited">no dot: inherited</span>
        </div>
        <div className="studio-permtree__save">
          {changes > 0 && (
            <Text UNSAFE_className="studio-permtree__count">
              {changes} change{changes === 1 ? '' : 's'}
            </Text>
          )}
          <Button variant="secondary" onPress={() => setPending(new Map())} isDisabled={!changes || saving}>
            Cancel
          </Button>
          <Button variant="accent" onPress={() => void save()} isDisabled={!changes} isPending={saving}>
            Save
          </Button>
        </div>
      </div>
      <div className="studio-listview studio-permtree__table">
        <table className="studio-table">
          <thead>
            <tr>
              <th>Path</th>
              {columns.map((c) => (
                <th key={c} className="studio-permtree__colhead" title={HINTS[c]}>
                  {LABELS[c]}
                </th>
              ))}
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            <Fragment>
              {renderRow(root.node, 0)}
              {expanded.has('/') && rows('/', 1)}
            </Fragment>
          </tbody>
        </table>
      </div>
    </div>
  );
}
