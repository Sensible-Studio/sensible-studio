import { useCallback, useEffect, useMemo, useState } from 'react';
import { HttpPagePermissionsClient, type PagePermissionsClient, type PrincipalChoice } from '../pageprops/api';
import { PrincipalPicker } from '../pageprops/PrincipalPicker';

/**
 * Who sees a Media folder on the published site: its closed user group (the same mechanism, and
 * the same endpoint, as a page's). On the author everyone who works in Media keeps seeing the
 * folder; the restriction counts where visitors are, once the folder is published. Changes
 * apply at once, like in the page properties.
 */
export function FolderAccess({ path, client: given }: { path: string; client?: PagePermissionsClient }) {
  const client = useMemo(() => given ?? new HttpPagePermissionsClient(), [given]);
  const [chosen, setChosen] = useState<PrincipalChoice[] | null>(null);
  const [supported, setSupported] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  const take = useCallback((cug: { supported: boolean; principals: Array<{ principal: string; title?: string; group: boolean }> }) => {
    setSupported(cug.supported);
    setChosen(cug.principals.map((p) => ({ id: p.principal, title: p.title ?? p.principal, group: p.group })));
  }, []);

  useEffect(() => {
    let live = true;
    client.load(path).then(
      (d) => live && take(d.cug),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, path, take]);

  const save = async (next: PrincipalChoice[]) => {
    const before = chosen;
    setChosen(next);
    setError(null);
    setSaved(false);
    try {
      take((await client.setCug(path, next.map((c) => c.id))).cug);
      setSaved(true);
    } catch (e) {
      setChosen(before);
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <section className="studio-assets__folder-section" aria-labelledby="studio-fp-access">
      <h3 id="studio-fp-access">Access on the published site</h3>
      {!supported ? (
        <p className="studio-assets__muted">Folders cannot be closed to a group on this instance.</p>
      ) : chosen === null ? (
        !error && <p className="studio-assets__muted">Loading…</p>
      ) : (
        <>
          <p className="studio-assets__muted">
            {chosen.length === 0
              ? 'Everyone who can see the site sees this folder and its files. Add users or groups to close it to everybody else.'
              : 'Only these users and groups see this folder and its files, here and in subfolders. Publish the folder for it to take effect.'}
          </p>
          <PrincipalPicker client={client} label="Add user or group" exclude={chosen.map((c) => c.id)} onSelect={(p) => void save([...chosen, p])} />
          {chosen.length > 0 && (
            <ul className="studio-g-taglist studio-assets__folder-access" aria-label="Who sees this folder">
              {chosen.map((c) => (
                <li key={c.id} className="studio-g-taglist__tag">
                  <span>{c.title}</span>
                  <button type="button" aria-label={`Remove ${c.title}`} onClick={() => void save(chosen.filter((x) => x.id !== c.id))}>
                    ×
                  </button>
                </li>
              ))}
            </ul>
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
