import { ActionButton, Button, DialogContainer, Flex, Text, TextArea, TextField, ToastQueue } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import { useCallback, useEffect, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { ProductBar } from '../shell/ProductBar';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { commonName, httpTrustStore, validity, type TrustCheck, type TrustStoreClient, type TrustedCertificate } from './truststore';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

function Who({ c }: { c: TrustedCertificate }) {
  return (
    <>
      <strong>{commonName(c.subject)}</strong>
      {c.authority && <span className="studio-truststore__tag">Authority</span>}
      <br />
      <span className="studio-truststore__muted">
        {c.selfSigned ? 'self-signed' : `issued by ${commonName(c.issuer)}`}
        {c.names && c.names.length > 0 ? ` · for ${c.names.join(', ')}` : ''}
      </span>
    </>
  );
}

/** Add a certificate: pasted PEM text or a file (.pem, .crt, .cer, .der). */
function AddDialog({ client, initial, onClose, onAdded }: { client: TrustStoreClient; initial?: { alias: string; pem: string }; onClose(): void; onAdded(list: TrustedCertificate[]): void }) {
  const [alias, setAlias] = useState(initial?.alias ?? '');
  const [pem, setPem] = useState(initial?.pem ?? '');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const add = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await client.add(alias, file ?? pem);
      ToastQueue.positive(`Trusted: ${r.added.join(', ')}`, { timeout: 4000 });
      onAdded(r.certificates);
      onClose();
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };
  return (
    <StudioDialog title="Add a certificate" size="M" onCancel={onClose} busy={busy} primaryLabel="Add" onPrimary={() => void add()} primaryDisabled={!file && !pem.trim()}>
      <Flex direction="column" gap="size-150">
        <TextField label="Alias" value={alias} onChange={setAlias} description="A name for it; its common name when empty" width="100%" />
        <TextArea label="Certificate (PEM)" value={pem} onChange={setPem} isDisabled={!!file} width="100%" height="size-2400" UNSAFE_className="studio-truststore__pem" />
        <label className="studio-truststore__file">
          Or a file (.pem, .crt, .cer, .der){' '}
          <input type="file" accept=".pem,.crt,.cer,.der" aria-label="Certificate file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </label>
        <p className="studio-truststore__muted">
          Prefer the authority that signed the servers&apos; certificates (its root): everything it signs is trusted then, for the names each
          certificate is for. A server&apos;s own certificate works too, until it is renewed.
        </p>
        {error && <Text UNSAFE_className="studio-truststore__error">{error}</Text>}
      </Flex>
    </StudioDialog>
  );
}

/**
 * Tools › Security › Trust Store, as in AEM: the certificates this instance trusts for its own
 * HTTPS connections (publishing, the cache, fetching from the publish instances) besides the
 * authorities Java trusts, and a check of an address.
 */
export function TrustStoreConsole({ user, client = httpTrustStore }: { user: string | null; client?: TrustStoreClient }) {
  const tab = useTab();
  useScreenTitle('Trust Store', 'Trust Store | Sensible Studio Security');
  const [list, setList] = useState<TrustedCertificate[] | null>(null);
  const [canEdit, setCanEdit] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState<{ alias: string; pem: string } | null | undefined>(undefined);
  const [removing, setRemoving] = useState<TrustedCertificate | null>(null);
  const [url, setUrl] = useState('');
  const [checking, setChecking] = useState(false);
  const [check, setCheck] = useState<TrustCheck | null>(null);

  const load = useCallback(() => {
    setError(null);
    client.list().then(
      (r) => {
        setList(r.certificates);
        setCanEdit(r.canEdit);
      },
      (e: unknown) => setError(message(e)),
    );
  }, [client]);
  useEffect(load, [load]);

  const runCheck = async () => {
    const u = url.trim();
    if (!u) return;
    setChecking(true);
    try {
      setCheck(await client.check(u.includes('://') ? u : `https://${u}`));
    } catch (e) {
      setCheck({ trusted: false, message: message(e), chain: [] });
    } finally {
      setChecking(false);
    }
  };

  const remove = async (c: TrustedCertificate) => {
    try {
      const r = await client.remove(c.alias);
      setList(r.certificates);
      // what was trusted with it may not be any more
      setCheck(null);
      ToastQueue.positive(`${c.alias} is no longer trusted`, { timeout: 4000 });
    } catch (e) {
      ToastQueue.negative(message(e), { timeout: 6000 });
    }
  };

  return (
    <div className="studio-sites studio-security studio-truststore">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/security/truststore.html"
        primary={
          canEdit ? (
            <Button variant="accent" onPress={() => setAdding(null)}>
              <Add />
              <Text>Add certificate</Text>
            </Button>
          ) : undefined
        }
      />
      <div className="studio-truststore__body">
        <p className="studio-truststore__intro">
          Certificates this instance trusts for its own HTTPS connections, besides the authorities Java trusts: publishing to publish instances,
          the cache in front of them, fetching what visitors made there. For servers whose certificates come from your organisation&apos;s own
          authority. Host names are always checked.
        </p>

        <section className="studio-truststore__check" aria-label="Check an address">
          <Flex gap="size-100" alignItems="end" wrap>
            <TextField
              label="Check an address"
              value={url}
              onChange={setUrl}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void runCheck();
                else e.continuePropagation();
              }}
              description="e.g. https://publish.example.internal/bin/receive"
              width="size-5000"
            />
            <Button variant="secondary" onPress={() => void runCheck()} isPending={checking} isDisabled={!url.trim()}>
              Check
            </Button>
          </Flex>
          {check && (
            <div className="studio-truststore__result">
              <p className={check.trusted ? 'studio-truststore__ok' : 'studio-truststore__error'}>
                <strong>{check.trusted ? 'Trusted' : 'Not trusted'}</strong> · {check.message}
              </p>
              {check.chain.length > 0 && (
                <ol className="studio-truststore__chain" aria-label="The certificates the server shows">
                  {check.chain.map((c, i) => (
                    <li key={c.sha256 ?? i}>
                      <Who c={c} />
                      <span className="studio-truststore__muted"> · {validity(c)}</span>
                      {!check.trusted && canEdit && c.pem && (c.authority || i === check.chain.length - 1) && (
                        <ActionButton isQuiet onPress={() => setAdding({ alias: commonName(c.subject), pem: c.pem! })} UNSAFE_className="studio-truststore__inline">
                          {c.authority ? 'Trust this authority…' : 'Trust this certificate…'}
                        </ActionButton>
                      )}
                    </li>
                  ))}
                </ol>
              )}
            </div>
          )}
        </section>

        {error ? (
          <ErrorState title="The trust store could not be read">{error}</ErrorState>
        ) : list === null ? (
          <LoadingState label="Loading certificates" />
        ) : list.length === 0 ? (
          <EmptyState title="No certificates added">Only the authorities Java trusts are trusted. Add one when a server&apos;s certificate comes from your own authority.</EmptyState>
        ) : (
          <table className="studio-table studio-truststore__table">
            <thead>
              <tr>
                <th>Alias</th>
                <th>Certificate</th>
                <th>Valid</th>
                <th>SHA-256</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {list.map((c) => (
                <tr key={c.alias}>
                  <td>
                    <code>{c.alias}</code>
                  </td>
                  <td>{c.error ? <span className="studio-truststore__error">{c.error}</span> : <Who c={c} />}</td>
                  <td className={c.expired ? 'studio-truststore__error' : undefined}>{validity(c)}</td>
                  <td>
                    <code className="studio-truststore__fingerprint" title={c.sha256}>
                      {c.sha256 ? `${c.sha256.slice(0, 23)}…` : ''}
                    </code>
                  </td>
                  <td>
                    {canEdit && (
                      <ActionButton isQuiet onPress={() => setRemoving(c)} aria-label={`Remove ${c.alias}`}>
                        <Delete />
                        <Text>Remove</Text>
                      </ActionButton>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      <DialogContainer onDismiss={() => setAdding(undefined)}>
        {adding !== undefined && (
          <AddDialog
            client={client}
            initial={adding ?? undefined}
            onClose={() => setAdding(undefined)}
            onAdded={(l) => {
              setList(l);
              setCheck(null);
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title={`Remove ${removing.alias}?`}
            variant="destructive"
            primaryActionLabel="Remove"
            cancelLabel="Cancel"
            onPrimaryAction={() => void remove(removing)}
          >
            Connections to servers whose certificates only it vouches for fail from now on, publishing to them included, until it is added again.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
