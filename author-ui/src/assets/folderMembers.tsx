import { useCallback, useEffect, useMemo, useState } from 'react';
import { HttpPagePermissionsClient, type PagePermissionsClient, type PrincipalChoice } from '../pageprops/api';
import { PrincipalPicker } from '../pageprops/PrincipalPicker';

export type FolderRole = 'owner' | 'editor' | 'viewer';

export interface FolderMember {
  id: string;
  title: string;
  group: boolean;
  role: FolderRole;
}

export interface FolderMembers {
  members: FolderMember[];
  private: boolean;
  /** whether the person looking may change them (owners and administrators) */
  canManage: boolean;
  /** false when the person looking may not even read them */
  readable: boolean;
}

/** The Permissions tab of a Media folder: `/bin/studio/dam.json?op=folderMembers`. */
export interface FolderMembersClient {
  load(path: string): Promise<FolderMembers>;
  save(path: string, members: Array<{ id: string; role: FolderRole }>, isPrivate: boolean): Promise<FolderMembers>;
}

const API = '/bin/studio/dam.json';

export class HttpFolderMembersClient implements FolderMembersClient {
  async load(path: string): Promise<FolderMembers> {
    const res = await fetch(`${API}?${new URLSearchParams({ op: 'folderMembers', path })}`, {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    return answer(res);
  }

  async save(path: string, members: Array<{ id: string; role: FolderRole }>, isPrivate: boolean): Promise<FolderMembers> {
    const body = new URLSearchParams({ op: 'folderMembers', path, private: String(isPrivate) });
    for (const m of members) body.append('member', `${m.id}:${m.role}`);
    const res = await fetch(API, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-Studio-CSRF': '1', Accept: 'application/json' },
      body,
    });
    return answer(res);
  }
}

async function answer(res: Response): Promise<FolderMembers> {
  const body = (await res.json().catch(() => ({}))) as FolderMembers & { error?: string };
  if (!res.ok) throw new Error(body.error || `HTTP ${res.status}`);
  return body;
}

export const ROLE_TITLES: Record<FolderRole, string> = { owner: 'Owner', editor: 'Editor', viewer: 'Viewer' };
const ROLE_HELP: Record<FolderRole, string> = {
  owner: 'everything, including who the members are',
  editor: 'add, edit, move and delete files and folders',
  viewer: 'see and download',
};

/**
 * Who works in a Media folder, as AEM's folder Permissions tab: users and groups as Owner, Editor
 * or Viewer of the folder and everything in it, and "Make folder private" (only the members and
 * administrators see it). Applies on the author, at once; who sees the folder on the published
 * site is the closed user group below it.
 */
export function FolderMembersSection({
  path,
  client: given,
  principals: givenPrincipals,
}: {
  path: string;
  client?: FolderMembersClient;
  principals?: PagePermissionsClient;
}) {
  const client = useMemo(() => given ?? new HttpFolderMembersClient(), [given]);
  const principals = useMemo(() => givenPrincipals ?? new HttpPagePermissionsClient(), [givenPrincipals]);
  const [state, setState] = useState<FolderMembers | null>(null);
  const [role, setRole] = useState<FolderRole>('editor');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    let live = true;
    client.load(path).then(
      (d) => live && setState(d),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, path]);

  const save = useCallback(
    async (members: FolderMember[], isPrivate: boolean) => {
      if (!state) return;
      const before = state;
      setState({ ...state, members, private: isPrivate });
      setError(null);
      setSaved(false);
      try {
        setState(await client.save(path, members.map((m) => ({ id: m.id, role: m.role })), isPrivate));
        setSaved(true);
      } catch (e) {
        setState(before);
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [client, path, state],
  );

  const add = (p: PrincipalChoice) => {
    if (!state) return;
    void save([...state.members, { id: p.id, title: p.title, group: p.group, role }], state.private);
  };

  return (
    <section className="studio-assets__folder-section" aria-labelledby="studio-fp-members">
      <h3 id="studio-fp-members">Members</h3>
      {state === null ? (
        !error && <p className="studio-assets__muted">Loading…</p>
      ) : !state.readable ? (
        <p className="studio-assets__muted">Only the folder's owners and administrators see who its members are.</p>
      ) : (
        <>
          <p className="studio-assets__muted">
            Users and groups who work in this folder and everything in it. Owner: {ROLE_HELP.owner}; Editor: {ROLE_HELP.editor};
            Viewer: {ROLE_HELP.viewer}.
          </p>
          <label className="studio-assets__folder-private">
            <input
              type="checkbox"
              checked={state.private}
              disabled={!state.canManage}
              onChange={(e) => void save(state.members, e.target.checked)}
            />{' '}
            Make folder private
          </label>
          <p className="studio-assets__muted">
            {state.private
              ? 'Only the members and administrators see this folder here.'
              : 'Everyone who works in Media sees this folder; the members get more than that.'}
          </p>
          {state.canManage && (
            <div className="studio-assets__folder-add">
              <label>
                Role{' '}
                <select aria-label="Role of the next member" value={role} onChange={(e) => setRole(e.target.value as FolderRole)}>
                  {(Object.keys(ROLE_TITLES) as FolderRole[]).map((r) => (
                    <option key={r} value={r}>
                      {ROLE_TITLES[r]}
                    </option>
                  ))}
                </select>
              </label>
              <PrincipalPicker
                client={principals}
                label="Add user or group"
                exclude={state.members.map((m) => m.id)}
                onSelect={add}
              />
            </div>
          )}
          {state.members.length > 0 ? (
            <ul className="studio-assets__folder-members" aria-label="Members of this folder">
              {state.members.map((m) => (
                <li key={m.id}>
                  <span className="studio-assets__folder-member">
                    {m.title}
                    {m.group ? <span className="studio-assets__muted"> (group)</span> : null}
                  </span>
                  {state.canManage ? (
                    <>
                      <select
                        aria-label={`Role of ${m.title}`}
                        value={m.role}
                        onChange={(e) =>
                          void save(
                            state.members.map((x) => (x.id === m.id ? { ...x, role: e.target.value as FolderRole } : x)),
                            state.private,
                          )
                        }
                      >
                        {(Object.keys(ROLE_TITLES) as FolderRole[]).map((r) => (
                          <option key={r} value={r}>
                            {ROLE_TITLES[r]}
                          </option>
                        ))}
                      </select>
                      <button
                        type="button"
                        aria-label={`Remove ${m.title}`}
                        onClick={() => void save(state.members.filter((x) => x.id !== m.id), state.private)}
                      >
                        ×
                      </button>
                    </>
                  ) : (
                    <span>{ROLE_TITLES[m.role]}</span>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <p className="studio-assets__muted">No members yet.</p>
          )}
          {!state.canManage && (
            <p className="studio-assets__muted">Only the folder's owners and administrators change its members.</p>
          )}
          {saved && !error && (
            <p className="studio-assets__muted" role="status">
              Saved.
            </p>
          )}
        </>
      )}
      {error && (
        <p className="studio-assets__error" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}
