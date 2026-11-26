import { Button, DialogContainer, Flex, Radio, RadioGroup, StatusLight, Text, TextArea, TextField } from '@adobe/react-spectrum';
import LockClosed from '@spectrum-icons/workflow/LockClosed';
import { useCallback, useEffect, useRef, useState } from 'react';
import { ErrorState, LoadingState } from '../shell/FeedbackState';
import { WizardPage } from '../shell/FormPage';
import { ProductBar } from '../shell/ProductBar';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { finished, httpSsl, portOf, type SslChange, type SslCheck, type SslClient, type SslRequest, type SslStatus } from './ssl';
import { commonName, validity, type TrustedCertificate } from './truststore';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const STEPS = ['Private Key and Certificate', 'Connector', 'Review', 'Done'];

type Cert = Omit<TrustedCertificate, 'alias'>;

function Chain({ chain }: { chain: Cert[] }) {
  return (
    <ol className="studio-ssl__chain" aria-label="Certificates">
      {chain.map((c, i) => (
        <li key={c.sha256 ?? i}>
          <strong>{commonName(c.subject)}</strong>
          {i === 0 ? <span className="studio-truststore__tag">This server</span> : c.authority ? <span className="studio-truststore__tag">Authority</span> : null}
          <br />
          <span className="studio-truststore__muted">
            {c.selfSigned ? 'self-signed' : `issued by ${commonName(c.issuer)}`}
            {c.names && c.names.length > 0 ? ` · for ${c.names.join(', ')}` : ''} · <span className={c.expired ? 'studio-truststore__error' : undefined}>{validity(c as TrustedCertificate)}</span>
          </span>
          {c.sha256 && (
            <>
              <br />
              <code className="studio-truststore__fingerprint" title="SHA-256">
                {c.sha256}
              </code>
            </>
          )}
        </li>
      ))}
    </ol>
  );
}

/** A file, or its text pasted. */
function KeyInput({ label, accept, hint, file, text, onFile, onText }: { label: string; accept: string; hint: string; file: File | null; text: string; onFile(f: File | null): void; onText(t: string): void }) {
  return (
    <div className="studio-ssl__input">
      <label className="studio-truststore__file">
        <strong>{label}</strong> ({hint}){' '}
        <input type="file" accept={accept} aria-label={`${label} file`} onChange={(e) => onFile(e.target.files?.[0] ?? null)} />
      </label>
      {!file && <TextArea aria-label={`${label} (PEM)`} placeholder="…or paste it as PEM" value={text} onChange={onText} width="100%" height="size-1600" UNSAFE_className="studio-truststore__pem" />}
    </div>
  );
}

/**
 * The wizard, as AEM's SSL Configuration: the private key and its certificate (or one made here),
 * the connector (host name and port), a review of what was checked, and the change.
 */
function SslWizard({ client, status, onClose, onChanged }: { client: SslClient; status: SslStatus; onClose(): void; onChanged(s: SslStatus): void }) {
  const [step, setStep] = useState(0);
  const [selfSigned, setSelfSigned] = useState(false);
  const [keyFile, setKeyFile] = useState<File | null>(null);
  const [keyText, setKeyText] = useState('');
  const [certFile, setCertFile] = useState<File | null>(null);
  const [certText, setCertText] = useState('');
  const [hostname, setHostname] = useState(status.enabled ? status.hostname : typeof window !== 'undefined' ? window.location.hostname || 'localhost' : 'localhost');
  const [port, setPort] = useState(String(status.port ?? status.suggestedPort));
  const [checked, setChecked] = useState<SslCheck | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [started, setStarted] = useState<SslChange | null>(null);
  const [result, setResult] = useState<SslChange | null>(null);
  const [now, setNow] = useState<SslStatus | null>(null);

  const keyOk = selfSigned || ((!!keyFile || !!keyText.trim()) && (!!certFile || !!certText.trim()));
  const portNumber = portOf(port);
  const request = (): SslRequest => ({
    selfSigned,
    key: keyFile ?? keyText,
    certificate: certFile ?? certText,
    hostname: hostname.trim() || 'localhost',
    port: portNumber ?? 0,
  });

  // while the web server restarts, its answers fail for a moment: asked again until the change is over
  useEffect(() => {
    if (step !== 3 || result) return undefined;
    let stop = false;
    const tick = async () => {
      while (!stop) {
        await new Promise((r) => setTimeout(r, 1000));
        if (stop) return;
        try {
          const s = await client.status();
          const end = finished(s, started);
          if (end) {
            setNow(s);
            setResult(end);
            onChanged(s);
            return;
          }
        } catch {
          // restarting
        }
      }
    };
    void tick();
    return () => {
      stop = true;
    };
  }, [step, result, client, started, onChanged]);

  const next = async () => {
    setError(null);
    if (step === 0) {
      setStep(1);
    } else if (step === 1) {
      setBusy(true);
      try {
        setChecked(await client.check(request()));
        setStep(2);
      } catch (e) {
        setError(message(e));
      } finally {
        setBusy(false);
      }
    } else if (step === 2) {
      setBusy(true);
      try {
        const s = await client.apply(request());
        setStarted(s.last);
        setStep(3);
      } catch (e) {
        setError(message(e));
      } finally {
        setBusy(false);
      }
    } else {
      onClose();
    }
  };

  const applying = step === 3 && !result;
  return (
    <WizardPage
      title="SSL Configuration"
      steps={STEPS}
      step={step}
      busy={busy || applying}
      error={error}
      onBack={step > 0 && step < 3 ? () => (setError(null), setStep(step - 1)) : undefined}
      onClose={onClose}
      primaryLabel={step === 2 ? 'Apply' : step === 3 ? 'Done' : 'Next'}
      onPrimary={() => void next()}
      primaryDisabled={step === 0 ? !keyOk : step === 1 ? !portNumber || portNumber === status.httpPort || !hostname.trim() : false}
      className="studio-ssl__wizard"
    >
      <div className="studio-wizard__form studio-ssl__form">
        {step === 0 && (
          <>
            <p className="studio-wizard__context">
              The private key the server proves who it is with, and its certificate. Without a password, as a file the server can read on its own:
              DER (as AEM asks for) or PEM.
            </p>
            <RadioGroup label="Certificate" value={selfSigned ? 'self' : 'own'} onChange={(v) => setSelfSigned(v === 'self')}>
              <Radio value="own">A private key and its certificate (from an authority, or your own)</Radio>
              <Radio value="self">Make a self-signed certificate here, for trying HTTPS out</Radio>
            </RadioGroup>
            {selfSigned ? (
              <p className="studio-truststore__muted">
                An RSA 2048 key and a certificate for a year, for the host name of the next step, localhost and 127.0.0.1. Browsers warn about it until
                it is trusted; use one from an authority for real visitors.
              </p>
            ) : (
              <>
                <KeyInput label="Private key" accept=".der,.key,.pem" hint=".der, .key or .pem" file={keyFile} text={keyText} onFile={setKeyFile} onText={setKeyText} />
                <KeyInput
                  label="Certificate"
                  accept=".crt,.cer,.pem,.der"
                  hint=".crt, .cer, .pem or .der; with the authority's intermediates in the same file"
                  file={certFile}
                  text={certText}
                  onFile={setCertFile}
                  onText={setCertText}
                />
              </>
            )}
          </>
        )}
        {step === 1 && (
          <Flex direction="column" gap="size-150" maxWidth="size-6000">
            <p className="studio-wizard__context">Where this instance answers HTTPS, besides HTTP on port {status.httpPort}, which stays as it is.</p>
            <TextField label="HTTPS Hostname" value={hostname} onChange={setHostname} isRequired description="The name visitors use; the certificate should be for it" width="100%" />
            <TextField
              label="HTTPS Port"
              value={port}
              onChange={setPort}
              isRequired
              inputMode="numeric"
              validationState={port && (!portNumber || portNumber === status.httpPort) ? 'invalid' : undefined}
              errorMessage={portNumber === status.httpPort ? 'That is the HTTP port' : 'A number from 1 to 65535'}
              description={`${status.suggestedPort} is free`}
              width="size-2400"
            />
          </Flex>
        )}
        {step === 2 && checked && (
          <>
            <p className="studio-wizard__context">
              HTTPS at <strong>{checked.url}</strong>. The web server restarts with it (a few seconds without answers); if HTTPS does not answer
              with this certificate, or HTTP stops answering, the configuration is put back as it was.
            </p>
            <Chain chain={checked.certificate} />
            {checked.warnings.length > 0 && (
              <ul className="studio-ssl__warnings" aria-label="Warnings">
                {checked.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            )}
          </>
        )}
        {step === 3 && (
          <div className="studio-ssl__done" aria-live="polite">
            {!result ? (
              <LoadingState label="The web server restarts with HTTPS" />
            ) : result.state === 'done' ? (
              <>
                <StatusLight variant="positive">HTTPS is on</StatusLight>
                <p>
                  This instance answers HTTPS at{' '}
                  <a href={now?.url ?? checked?.url} target="_blank" rel="noreferrer">
                    {now?.url ?? checked?.url}
                  </a>
                  {now?.url && now.certificate[0]?.selfSigned ? ' (the browser asks whether to trust the certificate first)' : ''}.
                </p>
              </>
            ) : (
              <>
                <StatusLight variant="negative">{result.state === 'rolledBack' ? 'Put back as it was' : 'Not changed'}</StatusLight>
                <p>{result.message}</p>
              </>
            )}
          </div>
        )}
      </div>
    </WizardPage>
  );
}

/**
 * Tools › Security › SSL Configuration, as in AEM: HTTPS for this instance from its own web server,
 * without Apache in front: what is configured, what it answers with, the wizard, on and off.
 */
export function SslConsole({ user, client = httpSsl }: { user: string | null; client?: SslClient }) {
  const tab = useTab();
  useScreenTitle('SSL Configuration', 'SSL Configuration | Sensible Studio Security');
  const [status, setStatus] = useState<SslStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [wizard, setWizard] = useState(false);
  const [turningOff, setTurningOff] = useState(false);
  const [switching, setSwitching] = useState<SslChange | null>(null);
  const alive = useRef(true);
  useEffect(
    () => () => {
      alive.current = false;
    },
    [],
  );

  const load = useCallback(() => {
    setError(null);
    client.status().then(setStatus, (e: unknown) => setError(message(e)));
  }, [client]);
  useEffect(load, [load]);

  // on or off: waits for the web server like the wizard does
  useEffect(() => {
    if (!switching) return undefined;
    const t = setInterval(() => {
      client.status().then(
        (s) => {
          if (!alive.current) return;
          if (finished(s, switching)) {
            setStatus(s);
            setSwitching(null);
          }
        },
        () => undefined,
      );
    }, 1000);
    return () => clearInterval(t);
  }, [switching, client]);

  const toggle = async (on: boolean) => {
    try {
      const s = on ? await client.enable() : await client.disable();
      setStatus(s);
      setSwitching(s.last);
    } catch (e) {
      setError(message(e));
    }
  };

  const onChanged = useCallback((s: SslStatus) => setStatus(s), []);
  const last = status?.last;
  const canEdit = status?.canEdit !== false;

  return (
    <div className="studio-sites studio-security studio-truststore studio-ssl">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url="/security/ssl.html"
        primary={
          status && canEdit ? (
            <Button variant="accent" onPress={() => setWizard(true)} isDisabled={!!switching}>
              <LockClosed />
              <Text>{status.certificate.length ? 'Change certificate' : 'Configure HTTPS'}</Text>
            </Button>
          ) : undefined
        }
      />
      <div className="studio-truststore__body">
        <p className="studio-truststore__intro">
          HTTPS from this instance&apos;s own web server, without Apache or another proxy in front: a private key and its certificate, and the port
          it answers on. HTTP stays on as well. For the other instances, open their own SSL Configuration.
        </p>
        {error && <ErrorState title="SSL">{error}</ErrorState>}
        {!status ? (
          !error && <LoadingState label="Loading the configuration" />
        ) : (
          <section className="studio-ssl__status" aria-label="HTTPS">
            <dl>
              <dt>HTTPS</dt>
              <dd>
                {switching ? (
                  <StatusLight variant="info">Changing…</StatusLight>
                ) : status.enabled ? (
                  <StatusLight variant={status.listening ? 'positive' : 'negative'}>{status.listening ? 'On' : 'On, but not answering'}</StatusLight>
                ) : (
                  <StatusLight variant="neutral">Off</StatusLight>
                )}
              </dd>
              <dt>Address</dt>
              <dd>
                {status.url ? (
                  <a href={status.url} target="_blank" rel="noreferrer">
                    {status.url}
                  </a>
                ) : (
                  <span className="studio-truststore__muted">{status.port ? `port ${status.port}, off` : 'none'}</span>
                )}
              </dd>
              <dt>HTTP</dt>
              <dd>port {status.httpPort}</dd>
              {status.enabled && status.listening && status.serves && status.certificate[0]?.sha256 && status.serves !== status.certificate[0].sha256 && (
                <>
                  <dt>Shows</dt>
                  <dd className="studio-truststore__error">another certificate than the one configured</dd>
                </>
              )}
            </dl>
            {status.keystoreError && <p className="studio-truststore__error">{status.keystoreError}</p>}
            {status.certificate.length > 0 && <Chain chain={status.certificate} />}
            {last && last.state !== 'done' && last.state !== 'applying' && (
              <p className="studio-truststore__error" role="status">
                <strong>{last.what}:</strong> {last.message}
              </p>
            )}
            {canEdit && status.certificate.length > 0 && (
              <Flex gap="size-100">
                {status.enabled ? (
                  <Button variant="negative" onPress={() => setTurningOff(true)} isDisabled={!!switching}>
                    Turn HTTPS off
                  </Button>
                ) : (
                  <Button variant="secondary" onPress={() => void toggle(true)} isDisabled={!!switching}>
                    Turn HTTPS on
                  </Button>
                )}
              </Flex>
            )}
          </section>
        )}
      </div>
      {wizard && status && (
        <SslWizard
          client={client}
          status={status}
          onClose={() => {
            setWizard(false);
            load();
          }}
          onChanged={onChanged}
        />
      )}
      <DialogContainer onDismiss={() => setTurningOff(false)}>
        {turningOff && (
          <StudioConfirmDialog title="Turn HTTPS off?" variant="destructive" primaryActionLabel="Turn off" cancelLabel="Cancel" onPrimaryAction={() => void toggle(false)}>
            The web server restarts without HTTPS: who opens it at {status?.url} gets no answer. The certificate stays, to turn it on again.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
