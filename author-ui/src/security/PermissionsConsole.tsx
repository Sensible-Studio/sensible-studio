import {
  ActionButton,
  Button,
  ButtonGroup,
  Checkbox,
  Content,
  DialogContainer,
  Divider,
  Form,
  Heading,
  ProgressCircle,
  Radio,
  RadioGroup,
  SearchField,
  Text,
  TextField,
  ToastContainer,
  ToastQueue,
  useDialogContainer,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Globe from '@spectrum-icons/workflow/Globe';
import Search from '@spectrum-icons/workflow/Search';
import User from '@spectrum-icons/workflow/User';
import UserGroup from '@spectrum-icons/workflow/UserGroup';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { ProductBar } from '../shell/ProductBar';
import { PermissionTree } from './PermissionTree';
import { security, securityUrl, type Ace, type AuthorizableSummary, type Effective, type PrivilegeInfo } from './api';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { Segmented } from '../shell/Segmented';
import { ToolBar } from '../shell/ToolCrumbs';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

interface Props {
  user: string | null;
  initialPrincipal: string | null;
}

const EVERYONE: AuthorizableSummary = { id: 'everyone', principal: 'everyone', name: 'Everyone', type: 'group', path: '', description: 'All users, including anonymous' };

/** The privileges most ACEs use, offered first in the editor. */
const COMMON = ['jcr:read', 'rep:write', 'jcr:modifyProperties', 'jcr:addChildNodes', 'jcr:removeNode', 'jcr:removeChildNodes', 'jcr:readAccessControl', 'jcr:modifyAccessControl', 'crx:replicate', 'jcr:versionManagement', 'jcr:lockManagement', 'jcr:all'];

/**
 * Permissions: principals on the left; the selected principal's access control entries on the right,
 * with Add/Edit/Delete and a permission check on a path.
 */
export function PermissionsConsole({ user, initialPrincipal }: Props) {
  const tab = useTab();
  const [principals, setPrincipals] = useState<AuthorizableSummary[] | null>(null);
  const [filter, setFilter] = useState('');
  const [principal, setPrincipal] = useState<string | null>(initialPrincipal);
  const [aces, setAces] = useState<Ace[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [privileges, setPrivileges] = useState<PrivilegeInfo[]>([]);
  const [editing, setEditing] = useState<Ace | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Ace | null>(null);
  const [checking, setChecking] = useState(false);
  // the tree (what can be done where, as boxes) or the entries themselves; remembered per viewer
  const [mode, setModeState] = useState<'tree' | 'entries'>(() => {
    try {
      return window.localStorage.getItem('studio.permissions.view') === 'entries' ? 'entries' : 'tree';
    } catch {
      return 'tree';
    }
  });
  const setMode = (m: 'tree' | 'entries') => {
    setModeState(m);
    try {
      window.localStorage.setItem('studio.permissions.view', m);
    } catch {
      /* private window */
    }
  };

  useScreenTitle('Permissions', 'Permissions | Sensible Studio Security');
  useEffect(() => {
    Promise.all([security.groups(), security.users('', true)]).then(
      ([g, u]) => setPrincipals([EVERYONE, ...g, ...u]),
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
    security.privileges().then(setPrivileges, () => setPrivileges([]));
  }, []);

  const loadAces = useCallback(() => {
    if (!principal) return;
    setAces(null);
    security.aces(principal).then(setAces, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [principal]);

  useEffect(loadAces, [loadAces]);

  const choose = (p: string) => {
    if (tab) tab.setPlace({ kind: 'security', route: 'permissions', id: null, principal: p }, { replace: true });
    else window.history.replaceState(null, '', securityUrl.permissions(p));
    setPrincipal(p);
    setError(null);
  };

  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return (principals ?? []).filter((p) => !q || `${p.name} ${p.id}`.toLowerCase().includes(q));
  }, [principals, filter]);

  const current = principals?.find((p) => p.principal === principal) ?? null;

  const remove = async (ace: Ace) => {
    setDeleting(null);
    if (!principal) return;
    try {
      const r = await security.removeAce(principal, ace.path, ace.index);
      setAces(r.aces);
      ToastQueue.positive('Entry deleted', { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
      loadAces();
    }
  };

  return (
    <div className="studio-sites studio-security">
      {!tab && <ProductBar user={user} />}
      <ToolBar url={securityUrl.permissions()} extra={current ? [{ path: `principal:${principal}`, title: current.name }] : []} />
      <div className="studio-permissions">
        <aside className="studio-permissions__principals" aria-label="Users and groups">
          <SearchField aria-label="Filter users and groups" placeholder="Filter users and groups" value={filter} onChange={setFilter} width="100%" />
          {!principals ? (
            <ProgressCircle isIndeterminate size="S" aria-label="Loading" />
          ) : (
            <ul>
              {shown.map((p) => (
                <li key={`${p.type}:${p.id}`}>
                  <button type="button" className={p.principal === principal ? 'is-current' : undefined} onClick={() => choose(p.principal)}>
                    <span className="studio-security__avatar">{p.id === 'everyone' ? <Globe size="S" /> : p.type === 'group' ? <UserGroup size="S" /> : <User size="S" />}</span>
                    <span className="studio-memberships__text">
                      <span className="studio-memberships__name">{p.name}</span>
                      <span className="studio-memberships__id">{p.principal}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </aside>

        <main className="studio-permissions__main">
          {!principal ? (
            <p className="studio-sites__empty">Choose a user or group to see and edit its permissions.</p>
          ) : (
            <>
              <div className="studio-permissions__head">
                <div>
                  <h1>{current?.name ?? principal}</h1>
                  <Text UNSAFE_className="studio-permissions__sub">
                    {current?.type === 'group' ? 'Group' : 'User'} · {principal}
                  </Text>
                </div>
                <div className="studio-permissions__actions">
                  <Segmented<'tree' | 'entries'>
                    label="View"
                    items={[
                      { key: 'tree', label: 'Tree' },
                      { key: 'entries', label: 'Entries' },
                    ]}
                    value={mode}
                    onChange={setMode}
                  />
                  {mode === 'entries' && (
                  <>
                  <ActionButton onPress={() => setChecking(true)}>
                    <Search />
                    <Text>Check Permissions</Text>
                  </ActionButton>
                  <Button variant="accent" onPress={() => setEditing('new')}>
                    <Add />
                    <Text>Add permission</Text>
                  </Button>
                  </>
                  )}
                </div>
              </div>
              {error && <ErrorState title="Could not load permissions">{error}</ErrorState>}
              {mode === 'tree' ? (
                <PermissionTree key={principal} principal={principal} name={current?.name ?? principal} />
              ) : !aces ? (
                !error && <LoadingState />
              ) : aces.length === 0 ? (
                <EmptyState title="No access control entries">
                  No access control entries for {current?.name ?? principal}. It may still get permissions through its groups; use Check
                  Permissions.
                </EmptyState>
              ) : (
                <div className="studio-listview">
                  <table className="studio-table">
                    <thead>
                      <tr>
                        <th>Path</th>
                        <th>Privileges</th>
                        <th>Permission</th>
                        <th>Restrictions</th>
                        <th aria-label="Actions" />
                      </tr>
                    </thead>
                    <tbody>
                      {aces.map((a) => (
                        <tr key={`${a.path}#${a.index}`}>
                          <td className="studio-permissions__path">{a.path ?? '(repository)'}</td>
                          <td>
                            <span className="studio-privileges">
                              {a.privileges.map((p) => (
                                <span key={p} className="studio-privilege">
                                  {p}
                                </span>
                              ))}
                            </span>
                          </td>
                          <td>
                            <span className={`studio-allow ${a.allow ? 'is-allow' : 'is-deny'}`}>{a.allow ? 'Allow' : 'Deny'}</span>
                          </td>
                          <td className="studio-listview__muted">
                            {Object.entries(a.restrictions)
                              .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join(',') : v}`)
                              .join('; ')}
                          </td>
                          <td className="studio-permissions__rowactions">
                            <ActionButton isQuiet aria-label="Edit entry" onPress={() => setEditing(a)}>
                              <Edit />
                            </ActionButton>
                            <ActionButton isQuiet aria-label="Delete entry" onPress={() => setDeleting(a)}>
                              <Delete />
                            </ActionButton>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </main>
      </div>

      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && principal && (
          <AceDialog
            principal={principal}
            ace={editing === 'new' ? null : editing}
            privileges={privileges}
            onSaved={(list) => {
              setAces(list);
              ToastQueue.positive('Permissions saved', { timeout: 3000 });
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog title="Delete entry" variant="destructive" primaryActionLabel="Delete" cancelLabel="Cancel" onPrimaryAction={() => void remove(deleting)}>
            {deleting.allow ? 'Allow' : 'Deny'} <strong>{deleting.privileges.join(', ')}</strong> on <code>{deleting.path ?? 'the repository'}</code> for{' '}
            {principal} will be removed.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setChecking(false)}>{checking && principal && <CheckDialog principal={principal} />}</DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function AceDialog({
  principal,
  ace,
  privileges,
  onSaved,
}: {
  principal: string;
  ace: Ace | null;
  privileges: PrivilegeInfo[];
  onSaved(aces: Ace[]): void;
}) {
  const container = useDialogContainer();
  const [path, setPath] = useState(ace?.path ?? '/content');
  const [allow, setAllow] = useState(ace ? ace.allow : true);
  const [chosen, setChosen] = useState<Set<string>>(new Set(ace?.privileges ?? ['jcr:read']));
  const [glob, setGlob] = useState(typeof ace?.restrictions['rep:glob'] === 'string' ? (ace.restrictions['rep:glob'] as string) : '');
  const [showAll, setShowAll] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const names = privileges.map((p) => p.name);
  const list = showAll ? names : [...COMMON.filter((c) => names.includes(c)), ...[...chosen].filter((c) => !COMMON.includes(c))];

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await security.saveAce(principal, path.trim() || null, allow, [...chosen], glob.trim(), ace?.index);
      onSaved(r.aces);
      container.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>{ace ? 'Edit permission' : 'Add permission'}</Heading>
      <Divider />
      <Content>
        <Form>
          <Text>
            Principal: <strong>{principal}</strong>
          </Text>
          <TextField label="Path" value={path} onChange={setPath} isRequired isReadOnly={!!ace} description={ace ? undefined : 'An existing node, e.g. /content/my-site'} />
          <RadioGroup label="Permission type" orientation="horizontal" value={allow ? 'allow' : 'deny'} onChange={(v) => setAllow(v === 'allow')}>
            <Radio value="allow">Allow</Radio>
            <Radio value="deny">Deny</Radio>
          </RadioGroup>
          <div className="studio-acedialog__privileges" role="group" aria-label="Privileges">
            <Text UNSAFE_className="studio-move__label">Privileges</Text>
            <div className="studio-acedialog__grid">
              {list.map((n) => (
                <Checkbox
                  key={n}
                  isSelected={chosen.has(n)}
                  onChange={(v) => {
                    const next = new Set(chosen);
                    if (v) next.add(n);
                    else next.delete(n);
                    setChosen(next);
                  }}
                >
                  {n}
                </Checkbox>
              ))}
            </div>
            <ActionButton isQuiet onPress={() => setShowAll((v) => !v)}>
              {showAll ? 'Show common privileges' : `Show all ${names.length} privileges`}
            </ActionButton>
          </div>
          <TextField label="Restriction rep:glob" value={glob} onChange={setGlob} description="Optional, e.g. /jcr:content* or empty for the whole subtree" />
          {error ? <Text UNSAFE_className="studio-acedialog__error">{error}</Text> : <></>}
        </Form>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={container.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void save()} isDisabled={chosen.size === 0 || busy} isPending={busy}>
          Save
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}

function CheckDialog({ principal }: { principal: string }) {
  const container = useDialogContainer();
  const [path, setPath] = useState('/content');
  const [result, setResult] = useState<Effective | null>(null);
  const [error, setError] = useState<string | null>(null);

  const check = () => {
    setError(null);
    security.effective(principal, path.trim()).then(setResult, (e: unknown) => {
      setResult(null);
      setError(e instanceof Error ? e.message : String(e));
    });
  };

  const labels: Array<[keyof Effective['summary'], string]> = [
    ['read', 'Read'],
    ['modify', 'Modify'],
    ['create', 'Create'],
    ['delete', 'Delete'],
    ['readAcl', 'Read ACL'],
    ['editAcl', 'Edit ACL'],
  ];

  return (
    <StudioDialog size="M">
      <Heading>Check Permissions</Heading>
      <Divider />
      <Content>
        <Form onSubmit={(e) => (e.preventDefault(), check())}>
          <Text>
            What <strong>{principal}</strong> may do (directly, through its groups and as everyone):
          </Text>
          <TextField label="Path" value={path} onChange={setPath} autoFocus />
        </Form>
        {error && <p className="studio-acedialog__error">{error}</p>}
        {result && (
          <div className="studio-check">
            <ul className="studio-check__summary">
              {labels.map(([k, label]) => (
                <li key={k} className={result.summary[k] ? 'is-allow' : 'is-deny'}>
                  {result.summary[k] ? '✓' : '✕'} {label}
                </li>
              ))}
            </ul>
            <Text UNSAFE_className="studio-move__label">Privileges at {result.path}</Text>
            <span className="studio-privileges">
              {result.privileges.length ? result.privileges.map((p) => <span key={p} className="studio-privilege">{p}</span>) : 'none'}
            </span>
            <Text UNSAFE_className="studio-move__label">Evaluated for {result.principals.join(', ')}</Text>
          </div>
        )}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={container.dismiss}>
          Close
        </Button>
        <Button variant="accent" onPress={check}>
          Check
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
