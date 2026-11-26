import './sharedLinks.scss';
import { ActionButton, DialogContainer, Text, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Delete from '@spectrum-icons/workflow/Delete';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { useScreenTitle } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import type { DamClient, ShareLink } from './api';
import { routeUrl, type AssetsRoute } from './route';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const name = (p: string) => p.slice(p.lastIndexOf('/') + 1);

/** "in 3 days", "in 5 hours", "today": when a link stops working. */
export function expiresIn(iso: string, now = Date.now()): string {
  const ms = new Date(iso).getTime() - now;
  if (Number.isNaN(ms)) return iso;
  if (ms <= 0) return 'expired';
  const hours = Math.round(ms / 3_600_000);
  if (hours < 1) return 'within the hour';
  if (hours < 24) return `in ${hours} hour${hours === 1 ? '' : 's'}`;
  const days = Math.round(hours / 24);
  return `in ${days} day${days === 1 ? '' : 's'}`;
}

/** Newest first: the link made last on top. */
export const byCreated = (a: ShareLink, b: ShareLink) => (b.created ?? '').localeCompare(a.created ?? '');

/**
 * AEM's Navigation › Assets › Shared Links: the links I shared, what each one lets download and until when, and
 * Unshare. A link's address is shown only when it is made (only a fingerprint of it is kept), so here it can be
 * stopped, not copied again.
 */
export function SharedLinksPage({ client, navigate }: { client: DamClient; navigate(route: AssetsRoute): void }) {
  const [links, setLinks] = useState<ShareLink[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [revoking, setRevoking] = useState<ShareLink | null>(null);

  useScreenTitle('Shared Links', 'Shared Links | Sensible Studio Media');
  const load = useCallback(() => {
    setError(null);
    client.shareLinks().then((l) => setLinks([...l].sort(byCreated)), (e: unknown) => setError(message(e)));
  }, [client]);
  useEffect(load, [load]);

  const open = (path: string, e: React.MouseEvent) => {
    e.preventDefault();
    navigate({ kind: 'details', path, properties: false });
  };

  return (
    <div className="studio-assets__profiles studio-sharedlinks">
      <ToolBar
        url={routeUrl({ kind: 'sharedlinks' })}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Read the links again</Tooltip>
          </TooltipTrigger>
        }
      />
      {error ? (
        <ErrorState title="Shared links could not be loaded">{error}</ErrorState>
      ) : links === null ? (
        <LoadingState label="Loading shared links" />
      ) : links.length === 0 ? (
        <EmptyState title="No shared links">
          Select files or folders in Media and choose Share Link: anyone with the link can download them, without an account, until it expires.
        </EmptyState>
      ) : (
        <>
          <p className="studio-sharedlinks__intro">
            The links you shared that still work. A link&apos;s address is shown only when it is made; here you can stop it before it
            expires.
          </p>
          <table className="studio-assets__profiles-table studio-table">
            <thead>
              <tr>
                <th>Shared</th>
                <th>Shared on</th>
                <th>Expires</th>
                <th>Renditions</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {links.map((l) => (
                <tr key={l.id}>
                  <td>
                    <ul className="studio-sharedlinks__paths">
                      {l.paths.slice(0, 5).map((p) => (
                        <li key={p}>
                          <a href={routeUrl({ kind: 'details', path: p, properties: false })} onClick={(e) => open(p, e)} title={p}>
                            {name(p)}
                          </a>
                          <span className="studio-assets__muted">{` ${p.slice(0, p.lastIndexOf('/'))}`}</span>
                        </li>
                      ))}
                      {l.paths.length > 5 && <li className="studio-assets__muted">{`and ${l.paths.length - 5} more`}</li>}
                    </ul>
                  </td>
                  <td>{l.created ? new Date(l.created).toLocaleString() : ''}</td>
                  <td title={new Date(l.expires).toLocaleString()}>{expiresIn(l.expires)}</td>
                  <td>{l.renditions ? 'Original and renditions' : 'Original only'}</td>
                  <td>
                    <ActionButton isQuiet onPress={() => setRevoking(l)} aria-label={`Unshare ${l.paths.map(name).join(', ')}`}>
                      <Delete />
                      <Text>Unshare</Text>
                    </ActionButton>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      <DialogContainer onDismiss={() => setRevoking(null)}>
        {revoking && (
          <StudioConfirmDialog
            title="Unshare"
            variant="destructive"
            primaryActionLabel="Unshare"
            cancelLabel="Cancel"
            onPrimaryAction={() => client.revokeShareLink(revoking.id).then(load, (e: unknown) => setError(message(e)))}
          >
            {`The link to ${revoking.paths.length === 1 ? name(revoking.paths[0]) : `${revoking.paths.length} items`} stops working now, for everyone who has it. The files stay as they are.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
