import { ActionButton, Button, ButtonGroup, Checkbox, Content, DialogContainer, Divider, Heading, ProgressCircle, Text, ToastQueue, useDialogContainer } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Checkmark from '@spectrum-icons/workflow/Checkmark';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import UserGroup from '@spectrum-icons/workflow/UserGroup';
import User from '@spectrum-icons/workflow/User';
import UsersLock from '@spectrum-icons/workflow/UsersLock';
import ViewList from '@spectrum-icons/workflow/ViewList';
import { useCallback, useEffect, useState } from 'react';
import { PERMISSION_FLAGS, PERMISSION_LABELS, type PagePermissions, type PagePermissionsClient, type PermissionFlag, type PermissionRow, type PrincipalChoice } from './api';
import { PrincipalPicker } from './PrincipalPicker';
import { StudioDialog, StudioConfirmDialog } from '../shell/StudioDialog';

type Flags = Record<PermissionFlag, boolean>;
const NONE: Flags = { read: false, modify: false, delete: false, replicate: false, create: false };

type Open =
  | { kind: 'add' }
  | { kind: 'edit'; row: PermissionRow }
  | { kind: 'delete'; row: PermissionRow }
  | { kind: 'cug' }
  | { kind: 'effective' }
  | null;

/**
 * The Permissions tab, as in AEM: the page's closed user group and the permissions set on the page for users and
 * groups, with Add Permissions, Edit Closed User Group and Effective Permissions. Changes apply at once.
 */
export function PermissionsTab({ client, page }: { client: PagePermissionsClient; page: string }) {
  const [data, setData] = useState<PagePermissions | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Open>(null);

  const load = useCallback(() => {
    client.load(page).then(
      (d) => {
        setData(d);
        setError(null);
      },
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
  }, [client, page]);
  useEffect(load, [load]);

  const apply = async (what: string, run: () => Promise<PagePermissions>) => {
    try {
      setData(await run());
      ToastQueue.positive(what, { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 8000 });
      throw e;
    }
  };

  // Who may not read the page's permissions (an author) is told what they may do themselves, in words, and is
  // not offered Add Permissions or the lists that would only answer "Access denied".
  if (data?.restricted) {
    const own = data.own;
    const may = own ? PERMISSION_FLAGS.filter((f) => own[f]) : [];
    const mayNot = own ? PERMISSION_FLAGS.filter((f) => !own[f]) : [];
    return (
      <div className="studio-pp-permissions">
        <p className="studio-pp-own">
          {may.length > 0 ? `On this page you may: ${may.map((f) => PERMISSION_LABELS[f].toLowerCase()).join(', ')}.` : 'You may not do anything on this page.'}
          {mayNot.length > 0 && may.length > 0 ? ` You may not: ${mayNot.map((f) => PERMISSION_LABELS[f].toLowerCase()).join(', ')}.` : ''}
        </p>
        <p className="studio-pp-own studio-pp-own--note">Who may do what here is set by an administrator.</p>
      </div>
    );
  }

  return (
    <div className="studio-pp-permissions">
      <div className="studio-pp-actionbar">
        <ActionButton isQuiet onPress={() => setOpen({ kind: 'add' })} isDisabled={!data}>
          <Add />
          <Text>Add Permissions</Text>
        </ActionButton>
        {data?.cug.supported && (
          <ActionButton isQuiet onPress={() => setOpen({ kind: 'cug' })}>
            <UsersLock />
            <Text>Edit Closed User Group</Text>
          </ActionButton>
        )}
        <ActionButton isQuiet onPress={() => setOpen({ kind: 'effective' })}>
          <ViewList />
          <Text>Effective Permissions</Text>
        </ActionButton>
      </div>
      {error && <p className="studio-pp-error">{error}</p>}
      {!data && !error && <ProgressCircle isIndeterminate size="S" aria-label="Loading permissions" />}
      {data && (
        <>
          {data.cug.supported && (
            <PermissionTable
              caption="Closed user group"
              first="Closed user group"
              columns={['read']}
              rows={data.cug.principals.map((r) => ({ ...r, read: true }))}
            />
          )}
          <PermissionTable
            caption="Permissions"
            first="User or group"
            columns={[...PERMISSION_FLAGS]}
            rows={data.acl}
            onEdit={(row) => setOpen({ kind: 'edit', row })}
            onDelete={(row) => setOpen({ kind: 'delete', row })}
          />
        </>
      )}

      <DialogContainer onDismiss={() => setOpen(null)}>
        {open?.kind === 'add' && data && (
          <PermissionDialog
            title="Add Permissions"
            client={client}
            exclude={data.acl.map((r) => r.principal)}
            onSave={(principal, flags) => apply(`Permissions of ${principal} added`, () => client.save(page, principal, flags))}
          />
        )}
        {open?.kind === 'edit' && (
          <PermissionDialog
            title={`Edit Permissions of ${open.row.title ?? open.row.principal}`}
            client={client}
            row={open.row}
            onSave={(principal, flags) => apply(`Permissions of ${principal} saved`, () => client.save(page, principal, flags))}
          />
        )}
        {open?.kind === 'delete' && (
          <StudioConfirmDialog
            title="Delete Permissions"
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() => void apply(`Permissions of ${open.row.principal} deleted`, () => client.remove(page, open.row.principal)).catch(() => {})}
          >
            Remove every permission of {open.row.title ?? open.row.principal} on this page?
            {open.row.other ? ' Its other access control entries on the page are removed too.' : ''}
          </StudioConfirmDialog>
        )}
        {open?.kind === 'cug' && data && (
          <CugDialog
            client={client}
            current={data.cug.principals.map((p) => ({ id: p.principal, title: p.title ?? p.principal, group: p.group }))}
            onSave={(ids) => apply('Closed user group saved', () => client.setCug(page, ids))}
          />
        )}
        {open?.kind === 'effective' && <EffectiveDialog client={client} page={page} />}
      </DialogContainer>
    </div>
  );
}

function PermissionTable({
  caption,
  first,
  columns,
  rows,
  onEdit,
  onDelete,
}: {
  caption: string;
  first: string;
  columns: PermissionFlag[];
  rows: PermissionRow[];
  onEdit?(row: PermissionRow): void;
  onDelete?(row: PermissionRow): void;
}) {
  const actions = !!(onEdit || onDelete);
  return (
    <table className="studio-pp-table studio-table">
      <caption className="studio-g-visually-hidden">{caption}</caption>
      <thead>
        <tr>
          <th scope="col">{first}</th>
          {columns.map((c) => (
            <th scope="col" key={c}>
              {PERMISSION_LABELS[c]}
            </th>
          ))}
          {actions && (
            <th scope="col" className="studio-pp-table__actions">
              <span className="studio-g-visually-hidden">Actions</span>
            </th>
          )}
        </tr>
      </thead>
      <tbody>
        {rows.length === 0 ? (
          <tr className="studio-pp-table__empty">
            <td colSpan={columns.length + 1 + (actions ? 1 : 0)}>There is no item.</td>
          </tr>
        ) : (
          rows.map((r) => (
            <tr key={r.principal}>
              <th scope="row">
                <span className="studio-pp-principal">
                  {r.group ? <UserGroup size="S" /> : <User size="S" />}
                  <span>
                    {r.title ?? r.principal}
                    {r.title && r.title !== r.principal && <span className="studio-pp-principal__id">{r.principal}</span>}
                    {r.other && <span className="studio-pp-principal__id">has other entries on this page</span>}
                  </span>
                </span>
              </th>
              {columns.map((c) => (
                <td key={c}>{r[c] ? <Checkmark size="S" aria-label="Yes" /> : <span className="studio-g-visually-hidden">No</span>}</td>
              ))}
              {actions && (
                <td className="studio-pp-table__actions">
                  {onEdit && (
                    <ActionButton isQuiet aria-label={`Edit permissions of ${r.principal}`} onPress={() => onEdit(r)}>
                      <Edit size="S" />
                    </ActionButton>
                  )}
                  {onDelete && (
                    <ActionButton isQuiet aria-label={`Delete permissions of ${r.principal}`} onPress={() => onDelete(r)}>
                      <Delete size="S" />
                    </ActionButton>
                  )}
                </td>
              )}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}

function PermissionDialog({
  title,
  client,
  row,
  exclude = [],
  onSave,
}: {
  title: string;
  client: PagePermissionsClient;
  row?: PermissionRow;
  exclude?: string[];
  onSave(principal: string, flags: Flags): Promise<void>;
}) {
  const [principal, setPrincipal] = useState<PrincipalChoice | null>(row ? { id: row.principal, title: row.title ?? row.principal, group: row.group } : null);
  const [flags, setFlags] = useState<Flags>(row ? { read: row.read, modify: row.modify, delete: row.delete, replicate: row.replicate, create: row.create } : { ...NONE, read: true });
  const [busy, setBusy] = useState(false);
  return (
    <StudioDialog size="M">
      <Heading>{title}</Heading>
      <Divider />
      <Content>
        {!row && <PrincipalPicker client={client} exclude={exclude} onSelect={setPrincipal} autoFocus />}
        {principal && (
          <p className="studio-pp-dialog__who">
            {principal.group ? <UserGroup size="S" /> : <User size="S" />} {principal.title}
            {principal.title !== principal.id && <span className="studio-pp-principal__id">{principal.id}</span>}
          </p>
        )}
        <div className="studio-pp-dialog__flags">
          {PERMISSION_FLAGS.map((f) => (
            <Checkbox key={f} isSelected={flags[f]} onChange={(v) => setFlags((x) => ({ ...x, [f]: v }))}>
              {PERMISSION_LABELS[f]}
            </Checkbox>
          ))}
        </div>
      </Content>
      <ButtonGroup>
        <CloseButton label="Cancel" />
        <SaveButton
          label={row ? 'Save' : 'Add'}
          isDisabled={!principal || busy}
          onPress={async (close) => {
            if (!principal) return;
            setBusy(true);
            try {
              await onSave(principal.id, flags);
              close();
            } catch {
              setBusy(false);
            }
          }}
        />
      </ButtonGroup>
    </StudioDialog>
  );
}

function CugDialog({ client, current, onSave }: { client: PagePermissionsClient; current: PrincipalChoice[]; onSave(ids: string[]): Promise<void> }) {
  const [chosen, setChosen] = useState<PrincipalChoice[]>(current);
  const [busy, setBusy] = useState(false);
  return (
    <StudioDialog size="M">
      <Heading>Edit Closed User Group</Heading>
      <Divider />
      <Content>
        <Text>Only these users and groups can browse the page and the pages below it.</Text>
        <PrincipalPicker client={client} exclude={chosen.map((c) => c.id)} onSelect={(p) => setChosen((c) => [...c, p])} autoFocus />
        <ul className="studio-g-taglist studio-pp-cuglist">
          {chosen.map((c) => (
            <li key={c.id} className="studio-g-taglist__tag">
              <span>{c.title}</span>
              <button type="button" aria-label={`Remove ${c.title}`} onClick={() => setChosen((x) => x.filter((y) => y.id !== c.id))}>
                ×
              </button>
            </li>
          ))}
        </ul>
        {chosen.length === 0 && <Text UNSAFE_className="studio-pp-muted">No closed user group: everyone with read access browses the page.</Text>}
      </Content>
      <ButtonGroup>
        <CloseButton label="Cancel" />
        <SaveButton
          label="Save"
          isDisabled={busy}
          onPress={async (close) => {
            setBusy(true);
            try {
              await onSave(chosen.map((c) => c.id));
              close();
            } catch {
              setBusy(false);
            }
          }}
        />
      </ButtonGroup>
    </StudioDialog>
  );
}

function EffectiveDialog({ client, page }: { client: PagePermissionsClient; page: string }) {
  const [rows, setRows] = useState<PermissionRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    client.effective(page).then(setRows, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, page]);
  return (
    <StudioDialog size="L">
      <Heading>Effective Permissions</Heading>
      <Divider />
      <Content>
        <Text UNSAFE_className="studio-pp-muted">
          What the users and groups named by the access control lists of this page and its parents may do here, their groups
          included.
        </Text>
        {error && <p className="studio-pp-error">{error}</p>}
        {!rows && !error && <ProgressCircle isIndeterminate size="S" aria-label="Loading" />}
        {rows && <PermissionTable caption="Effective permissions" first="User or group" columns={[...PERMISSION_FLAGS]} rows={rows} />}
      </Content>
      <ButtonGroup>
        <CloseButton label="Close" />
      </ButtonGroup>
    </StudioDialog>
  );
}

function CloseButton({ label }: { label: string }) {
  const dialog = useDialogContainer();
  return (
    <Button variant="secondary" onPress={dialog.dismiss}>
      {label}
    </Button>
  );
}

function SaveButton({ label, isDisabled, onPress }: { label: string; isDisabled?: boolean; onPress(close: () => void): void }) {
  const dialog = useDialogContainer();
  return (
    <Button variant="accent" isDisabled={isDisabled} onPress={() => onPress(dialog.dismiss)}>
      {label}
    </Button>
  );
}
