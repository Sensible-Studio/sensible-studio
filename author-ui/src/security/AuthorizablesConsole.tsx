import {
  ActionButton,
  Button,
  Checkbox,
  DialogContainer,
  SearchField,
  Switch,
  Text,
  ToastContainer,
  ToastQueue,
} from '@adobe/react-spectrum';
import { SelectionActions } from '../shell/SelectionBar';
import Delete from '@spectrum-icons/workflow/Delete';
import Gears from '@spectrum-icons/workflow/Gears';
import Globe from '@spectrum-icons/workflow/Globe';
import GlobeRemove from '@spectrum-icons/workflow/GlobeRemove';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import LockOpen from '@spectrum-icons/workflow/LockOpen';
import Properties from '@spectrum-icons/workflow/Properties';
import SelectBox from '@spectrum-icons/workflow/SelectBox';
import Shield from '@spectrum-icons/workflow/Shield';
import User from '@spectrum-icons/workflow/User';
import UserGroup from '@spectrum-icons/workflow/UserGroup';
import { useCallback, useEffect, useState } from 'react';
import { ProductBar } from '../shell/ProductBar';
import { security, securityUrl, type AuthorizableSummary } from './api';
import { Link, useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { HttpReplicationClient } from '../replication/api';

const replication = new HttpReplicationClient();
/** Who administers an instance stays on it: the publish instances refuse these. */
const STAY = ['admin', 'anonymous', 'administrators', 'everyone'];

interface Props {
  kind: 'users' | 'groups';
  user: string | null;
}

/** User Management / Group Management: a filterable table with an action bar for the selection. */
export function AuthorizablesConsole({ kind, user }: Props) {
  const go = useNavigate();
  const tab = useTab();
  const isUsers = kind === 'users';
  const [items, setItems] = useState<AuthorizableSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [system, setSystem] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [deleting, setDeleting] = useState<AuthorizableSummary[] | null>(null);
  const [unpublishing, setUnpublishing] = useState<AuthorizableSummary[] | null>(null);

  useScreenTitle(isUsers ? 'Users' : 'Groups', `${isUsers ? 'Users' : 'Groups'} | Sensible Studio Security`);

  useEffect(() => {
    const t = setTimeout(() => setQuery(q), 250);
    return () => clearTimeout(t);
  }, [q]);

  const load = useCallback(() => {
    (isUsers ? security.users(query, system) : security.groups(query)).then(
      (list) => {
        setItems(list);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [isUsers, query, system]);

  useEffect(load, [load]);

  const selection = (items ?? []).filter((i) => selected.includes(i.id));
  const single = selection.length === 1 ? selection[0] : null;
  const editUrl = (i: AuthorizableSummary) => (i.type === 'user' ? securityUrl.user(i.id) : securityUrl.group(i.id));

  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const setDisabled = async (disabled: boolean) => {
    try {
      for (const i of selection) await security.update(i.id, {}, { disabled });
      ToastQueue.positive(`${selection.length === 1 ? selection[0].name : `${selection.length} users`} ${disabled ? 'disabled' : 'enabled'}`, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
    load();
  };

  const remove = async (list: AuthorizableSummary[]) => {
    setDeleting(null);
    try {
      for (const i of list) await security.remove(i.id);
      ToastQueue.positive(`${list.length === 1 ? list[0].name : `${list.length} items`} deleted`, { timeout: 3000 });
      setSelected([]);
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    }
    load();
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (tab && !tab.active) return; // another tab is in front
      const t = e.target as HTMLElement | null;
      if (deleting || t?.closest('input:not([type="checkbox"]), textarea, [role="dialog"]')) return;
      if (e.key === 'Escape') setSelected([]);
      if (e.key === 'p' && single) go(editUrl(single));
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  /** AEM's Activate / Deactivate of a user or group: to every publish instance, through the usual queue. */
  const publish = async (list: AuthorizableSummary[], yes: boolean) => {
    setUnpublishing(null);
    const what = list.length === 1 ? list[0].name : `${list.length} ${isUsers ? 'users' : 'groups'}`;
    try {
      const paths = list.map((i) => i.path);
      if (yes) await replication.activate(paths);
      else await replication.deactivate(paths);
      ToastQueue.positive(`${what} ${yes ? 'published to' : 'removed from'} the publish instances`, { timeout: 4000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
    }
  };
  const publishable = selection.length > 0 && selection.every((i) => !i.system && !i.admin && !STAY.includes(i.id) && !!i.path);

  const deletable = selection.length > 0 && selection.every((i) => !i.protected && !i.system && !i.admin);

  return (
    <div className="studio-sites studio-security">
      {!tab && <ProductBar user={user} />}
      {selection.length > 0 ? (
        <SelectionActions count={selection.length} onClear={() => setSelected([])}>
            {single && (
              <ActionButton isQuiet onPress={() => go(editUrl(single))}>
                <Properties />
                <Text>
                  Properties <span className="studio-actionbar__hint">(p)</span>
                </Text>
              </ActionButton>
            )}
            {single && (
              <ActionButton isQuiet onPress={() => go(securityUrl.permissions(single.principal))}>
                <Shield />
                <Text>Permissions</Text>
              </ActionButton>
            )}
            {isUsers && selection.every((i) => !i.protected) && (
              <>
                <ActionButton isQuiet onPress={() => void setDisabled(true)} isDisabled={selection.every((i) => i.disabled)}>
                  <LockClosed />
                  <Text>Disable</Text>
                </ActionButton>
                <ActionButton isQuiet onPress={() => void setDisabled(false)} isDisabled={selection.every((i) => !i.disabled)}>
                  <LockOpen />
                  <Text>Enable</Text>
                </ActionButton>
              </>
            )}
            {publishable && (
              <>
                <ActionButton isQuiet onPress={() => void publish(selection, true)}>
                  <Globe />
                  <Text>Publish</Text>
                </ActionButton>
                <ActionButton isQuiet onPress={() => setUnpublishing(selection)}>
                  <GlobeRemove />
                  <Text>Unpublish</Text>
                </ActionButton>
              </>
            )}
            {deletable && (
              <ActionButton isQuiet onPress={() => setDeleting(selection)}>
                <Delete />
                <Text>Delete</Text>
              </ActionButton>
            )}
          </SelectionActions>
      ) : (
        <ToolBar
          url={isUsers ? securityUrl.users() : securityUrl.groups()}
          closable={false}
          end={<>
            <SearchField aria-label="Filter" placeholder={isUsers ? 'Filter users' : 'Filter groups'} value={q} onChange={setQ} width="size-3000" isQuiet />
            {isUsers && (
              <Switch isSelected={system} onChange={setSystem}>
                System users
              </Switch>
            )}
            <ActionButton isQuiet onPress={() => setSelected((items ?? []).map((i) => i.id))} isDisabled={!items?.length}>
              <SelectBox />
              <Text>Select All</Text>
            </ActionButton>
          </>}
          primary={<Button variant="accent" onPress={() => go(isUsers ? securityUrl.user() : securityUrl.group())}>Create {isUsers ? 'user' : 'group'}</Button>}
        />
      )}

      <main className="studio-sites__content">
        {error ? (
          <ErrorState title={`Could not load the ${kind}`}>{error}</ErrorState>
        ) : !items ? (
          <LoadingState />
        ) : items.length === 0 ? (
          <EmptyState title={`No ${kind} match`}>Change the filter or create a new {isUsers ? 'user' : 'group'}.</EmptyState>
        ) : (
          <div className="studio-listview">
            <table className="studio-table">
              <thead>
                <tr>
                  <th aria-label="Select" />
                  <th>Name</th>
                  <th>{isUsers ? 'Login Name' : 'ID'}</th>
                  {isUsers ? (
                    <>
                      <th>Status</th>
                      <th>Job Title</th>
                      <th>Email</th>
                    </>
                  ) : (
                    <th>Description</th>
                  )}
                </tr>
              </thead>
              <tbody>
                {items.map((i) => {
                  const isSel = selected.includes(i.id);
                  return (
                    <tr key={i.id} className={isSel ? 'is-selected' : undefined}>
                      <td className="studio-listview__check">
                        <Checkbox aria-label={`Select ${i.name}`} isSelected={isSel} onChange={() => toggle(i.id)} isEmphasized />
                      </td>
                      <td>
                        <Link className="studio-listview__title" to={editUrl(i)}>
                          <span className="studio-security__avatar">{i.type === 'group' ? <UserGroup size="S" /> : i.system ? <Gears size="S" /> : <User size="S" />}</span>
                          {i.name}
                        </Link>
                      </td>
                      <td className="studio-listview__muted">{i.id}</td>
                      {isUsers ? (
                        <>
                          <td className={i.disabled ? 'studio-security__disabled' : 'studio-listview__muted'}>{i.disabled ? 'Disabled' : 'Enabled'}</td>
                          <td className="studio-listview__muted">{i.jobTitle || 'N/A'}</td>
                          <td className="studio-listview__muted">{i.email ?? ''}</td>
                        </>
                      ) : (
                        <td className="studio-listview__muted">{i.description ?? ''}</td>
                      )}
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </main>

      <DialogContainer
        onDismiss={() => {
          setDeleting(null);
          setUnpublishing(null);
        }}
      >
        {unpublishing ? (
          <StudioConfirmDialog
            title="Unpublish"
            variant="destructive"
            primaryActionLabel="Unpublish"
            cancelLabel="Cancel"
            onPrimaryAction={() => void publish(unpublishing, false)}
          >
            <strong>{unpublishing.map((d) => d.name).join(', ')}</strong> {unpublishing.length === 1 ? 'is' : 'are'} removed from every publish
            instance: {isUsers ? 'they can no longer sign in there, and what they saved in their profile there is gone' : 'its members lose what it gave them there'}.
            {isUsers ? ' Here on the author nothing changes.' : ''}
          </StudioConfirmDialog>
        ) : deleting ? (
          <StudioConfirmDialog title="Delete" variant="destructive" primaryActionLabel="Delete" cancelLabel="Cancel" onPrimaryAction={() => void remove(deleting)}>
            You are going to delete <strong>{deleting.map((d) => d.name).join(', ')}</strong>. Their access control entries stay in the
            repository but no longer apply to anyone.
          </StudioConfirmDialog>
        ) : null}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}
