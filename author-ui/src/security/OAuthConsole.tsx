import { ActionButton, Button, Checkbox, CheckboxGroup, DialogContainer, Flex, Text, TextArea, TextField, ToastQueue } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Key from '@spectrum-icons/workflow/Key';
import { useCallback, useEffect, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { ProductBar } from '../shell/ProductBar';
import { Segmented } from '../shell/Segmented';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { httpOAuth, OAUTH_SCOPES, relative, type OAuthAdminClient, type OAuthClient, type OAuthClientInput, type OAuthScope, type OAuthToken } from './oauth';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

function ClientDialog({ initial, onClose, onSave }: { initial?: OAuthClient; onClose(): void; onSave(input: OAuthClientInput): Promise<void> }) {
  const [name, setName] = useState(initial?.name ?? '');
  const [uris, setUris] = useState((initial?.redirectUris ?? []).join('\n'));
  const [scopes, setScopes] = useState<OAuthScope[]>(initial?.scopes ?? ['read']);
  const [runAs, setRunAs] = useState(initial?.runAs ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      await onSave({ name, redirectUris: uris, scopes, runAs });
      onClose();
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog title={initial ? `Edit ${initial.name}` : 'New OAuth client'} size="M" onCancel={onClose} busy={busy} primaryLabel={initial ? 'Save' : 'Create'} onPrimary={() => void save()} primaryDisabled={!name.trim()}>
      <Flex direction="column" gap="size-150">
        <TextField label="Name" value={name} onChange={setName} isRequired width="100%" description="What users see when it asks them" />
        <TextArea
          label="Redirect addresses"
          value={uris}
          onChange={setUris}
          width="100%"
          description="One a line, exactly as the application sends them: https, or http on localhost. Users allow it there."
        />
        <CheckboxGroup label="What it may ask for" value={scopes} onChange={(v) => setScopes(v as OAuthScope[])}>
          {OAUTH_SCOPES.map((s) => (
            <Checkbox key={s.key} value={s.key}>
              {`${s.label}: ${s.description}`}
            </Checkbox>
          ))}
        </CheckboxGroup>
        <TextField
          label="Acts as user (client credentials)"
          value={runAs}
          onChange={setRunAs}
          width="100%"
          description="For a service without users: its tokens act as this user, never admin. Make a user with only the rights it needs."
        />
        {error && <Text UNSAFE_className="studio-oauth__error">{error}</Text>}
      </Flex>
    </StudioDialog>
  );
}

function SecretDialog({ client, secret, onClose }: { client: OAuthClient; secret: string; onClose(): void }) {
  const copy = (text: string) => void navigator.clipboard?.writeText(text).then(() => ToastQueue.positive('Copied', { timeout: 2000 }));
  return (
    <StudioDialog title={`${client.name}: its secret`} size="M" onCancel={onClose} cancelLabel="Done">
      <p>Give these to the application. The secret is shown this once: if it is lost, make a new one.</p>
      <dl className="studio-oauth__secret">
        <dt>Client ID</dt>
        <dd>
          <code>{client.id}</code> <ActionButton isQuiet onPress={() => copy(client.id)}>Copy</ActionButton>
        </dd>
        <dt>Client secret</dt>
        <dd>
          <code aria-label="Client secret">{secret}</code> <ActionButton isQuiet onPress={() => copy(secret)}>Copy</ActionButton>
        </dd>
      </dl>
    </StudioDialog>
  );
}

function Endpoints() {
  const base = typeof window === 'undefined' ? '' : window.location.origin;
  return (
    <details className="studio-oauth__how">
      <summary>How an application uses it</summary>
      <ul>
        <li>
          Users allow it: <code>{base}/oauth/authorize?response_type=code&amp;client_id=…&amp;redirect_uri=…&amp;scope=read&amp;state=…&amp;code_challenge=…&amp;code_challenge_method=S256</code>
        </li>
        <li>
          Tokens: <code>POST {base}/oauth/token</code> with <code>client_id</code>, <code>client_secret</code> and <code>grant_type</code> (<code>authorization_code</code> with{' '}
          <code>code</code>, <code>redirect_uri</code>, <code>code_verifier</code>; <code>refresh_token</code>; <code>client_credentials</code>)
        </li>
        <li>
          Requests: <code>Authorization: Bearer …</code>, for an hour; <code>POST {base}/oauth/revoke</code> ends one
        </li>
      </ul>
    </details>
  );
}

/**
 * Users › OAuth, AEM's OAuth clients and tokens: applications that act as a user without the
 * user's password. Administrators register clients and see every token; anybody else sees and
 * revokes the tokens that act as them.
 */
export function OAuthConsole({ user, client = httpOAuth }: { user: string | null; client?: OAuthAdminClient }) {
  const tab = useTab();
  useScreenTitle('OAuth', 'OAuth | Sensible Studio Security');
  const [view, setView] = useState<'clients' | 'tokens'>('clients');
  const [admin, setAdmin] = useState<boolean | null>(null);
  const [clients, setClients] = useState<OAuthClient[] | null>(null);
  const [tokens, setTokens] = useState<OAuthToken[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<OAuthClient | null | undefined>(undefined);
  const [secret, setSecret] = useState<{ client: OAuthClient; secret: string } | null>(null);
  const [removing, setRemoving] = useState<OAuthClient | null>(null);
  const [renewing, setRenewing] = useState<OAuthClient | null>(null);
  const [revoking, setRevoking] = useState<OAuthToken | null>(null);

  const load = useCallback(() => {
    setError(null);
    client.tokens().then(
      (r) => {
        setTokens(r.tokens);
        setAdmin(r.admin);
        if (!r.admin) setView('tokens');
      },
      (e: unknown) => setError(message(e)),
    );
    client.clients().then(
      (r) => setClients(r.clients),
      (e: unknown) => setError(message(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  const act = async (work: () => Promise<void>) => {
    try {
      await work();
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 6000 });
    }
  };

  const nameOf = (id: string) => clients?.find((c) => c.id === id)?.name ?? id;

  return (
    <div className="studio-sites studio-security studio-oauth">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/security/oauth.html"
        primary={
          admin && view === 'clients' ? (
            <Button variant="accent" onPress={() => setEditing(null)}>
              <Add />
              <Text>New client</Text>
            </Button>
          ) : undefined
        }
      />
      <div className="studio-oauth__body">
        <p className="studio-oauth__intro">
          Applications that act as a user without knowing the user&apos;s password: a site, an app, a script. A token never does more than its user
          may; tokens last an hour, and the user or an administrator can end them here.
        </p>
        {admin && (
          <Segmented<'clients' | 'tokens'>
            label="Show"
            value={view}
            onChange={setView}
            items={[
              { key: 'clients', label: `Clients${clients ? ` (${clients.length})` : ''}` },
              { key: 'tokens', label: `Tokens${tokens ? ` (${tokens.length})` : ''}` },
            ]}
          />
        )}
        {error ? (
          <ErrorState title="OAuth could not be read">{error}</ErrorState>
        ) : view === 'clients' ? (
          clients === null ? (
            <LoadingState label="Loading clients" />
          ) : clients.length === 0 ? (
            <EmptyState title="No OAuth clients">Register an application to let it ask users for tokens, or to act as a user of its own.</EmptyState>
          ) : (
            <>
              <Endpoints />
              <table className="studio-table studio-oauth__table">
                <thead>
                  <tr>
                    <th>Client</th>
                    <th>Redirect addresses</th>
                    <th>May ask for</th>
                    <th>Acts as</th>
                    <th aria-label="Actions" />
                  </tr>
                </thead>
                <tbody>
                  {clients.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <strong>{c.name}</strong>
                        <br />
                        <code className="studio-oauth__muted">{c.id}</code>
                      </td>
                      <td>
                        {c.redirectUris.length ? (
                          <ul className="studio-oauth__list">
                            {c.redirectUris.map((u) => (
                              <li key={u}>
                                <code>{u}</code>
                              </li>
                            ))}
                          </ul>
                        ) : (
                          <span className="studio-oauth__muted">none</span>
                        )}
                      </td>
                      <td>{c.scopes.join(', ')}</td>
                      <td>{c.runAs || <span className="studio-oauth__muted">the user who allows it</span>}</td>
                      <td className="studio-oauth__actions">
                        <ActionButton isQuiet aria-label={`Edit ${c.name}`} onPress={() => setEditing(c)}>
                          <Edit />
                        </ActionButton>
                        <ActionButton isQuiet aria-label={`New secret for ${c.name}`} onPress={() => setRenewing(c)}>
                          <Key />
                        </ActionButton>
                        <ActionButton isQuiet aria-label={`Delete ${c.name}`} onPress={() => setRemoving(c)}>
                          <Delete />
                        </ActionButton>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </>
          )
        ) : tokens === null ? (
          <LoadingState label="Loading tokens" />
        ) : tokens.length === 0 ? (
          <EmptyState title="No tokens">{admin ? 'No application holds a token now.' : 'No application acts as you now.'}</EmptyState>
        ) : (
          <table className="studio-table studio-oauth__table">
            <thead>
              <tr>
                <th>Application</th>
                <th>Acts as</th>
                <th>May</th>
                <th>Given</th>
                <th>Ends</th>
                <th>Last used</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {tokens.map((t) => (
                <tr key={t.id}>
                  <td>
                    {t.clientName || nameOf(t.client)}
                    {t.kind === 'refresh' && <span className="studio-oauth__tag">Refresh</span>}
                  </td>
                  <td>{t.user}</td>
                  <td>{t.scopes.join(', ')}</td>
                  <td title={new Date(t.created).toLocaleString()}>{relative(t.created)}</td>
                  <td title={new Date(t.expires).toLocaleString()}>{relative(t.expires)}</td>
                  <td>{t.lastUsed ? relative(t.lastUsed) : <span className="studio-oauth__muted">not yet</span>}</td>
                  <td>
                    <ActionButton isQuiet aria-label={`Revoke the token of ${t.clientName} for ${t.user}`} onPress={() => setRevoking(t)}>
                      <Delete />
                      <Text>Revoke</Text>
                    </ActionButton>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <DialogContainer onDismiss={() => setEditing(undefined)}>
        {editing !== undefined && (
          <ClientDialog
            initial={editing ?? undefined}
            onClose={() => setEditing(undefined)}
            onSave={async (input) => {
              if (editing) {
                const r = await client.update(editing.id, input);
                setClients(r.clients);
                ToastQueue.positive(`${input.name} saved`, { timeout: 3000 });
              } else {
                const r = await client.create(input);
                setClients(r.clients);
                if (r.client.secret) setSecret({ client: r.client, secret: r.client.secret });
              }
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setSecret(null)}>{secret && <SecretDialog client={secret.client} secret={secret.secret} onClose={() => setSecret(null)} />}</DialogContainer>
      <DialogContainer onDismiss={() => setRenewing(null)}>
        {renewing && (
          <StudioConfirmDialog
            title={`A new secret for ${renewing.name}?`}
            variant="destructive"
            primaryActionLabel="Make a new secret"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void act(async () => {
                const r = await client.newSecret(renewing.id);
                setSecret({ client: renewing, secret: r.secret });
              })
            }
          >
            The old secret stops working now: the application gets no new tokens until it has the new one. Tokens it holds stay good until they end.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title={`Delete ${removing.name}?`}
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void act(async () => {
                const r = await client.remove(removing.id);
                setClients(r.clients);
                const t = await client.tokens();
                setTokens(t.tokens);
              })
            }
          >
            Every token it was given stops working now, and it can get no new ones.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRevoking(null)}>
        {revoking && (
          <StudioConfirmDialog
            title="Revoke this token?"
            variant="destructive"
            primaryActionLabel="Revoke"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              void act(async () => {
                const r = await client.revoke(revoking.id);
                setTokens(r.tokens);
              })
            }
          >
            {`${revoking.clientName} no longer acts as ${revoking.user} with it, nor with the tokens it got from it. It has to be allowed again.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
