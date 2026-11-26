import { Button, Checkbox, Form, NumberField, TextField, ToastQueue } from '@adobe/react-spectrum';
import { useEffect, useRef, useState } from 'react';
import { ProductBar } from '../shell/ProductBar';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import './forms.scss';
import { FormPage, FormSection } from '../shell/FormPage';
import { ErrorState, LoadingState } from '../shell/FeedbackState';

interface Settings {
  host: string;
  port: number;
  starttls: boolean;
  user: string;
  from: string;
  hasPassword: boolean;
  configured: boolean;
}

const API = '/bin/studio/forms/mail.json';

async function read<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `HTTP ${res.status}`;
    try {
      const body = (await res.json()) as { error?: string };
      if (body.error) message = body.error;
    } catch {
      /* not JSON */
    }
    throw new Error(message);
  }
  return (await res.json()) as T;
}

/**
 * System › Mail: the mail server that sends a form's submissions by email (and, later, anything else the
 * author mails). Host, port, STARTTLS, user and password, the sender; a test message to check it works.
 */
export function MailSettings() {
  const tab = useTab();
  const [user, setUser] = useState<string | null>(null);
  const [settings, setSettings] = useState<Settings | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState<'save' | 'test' | null>(null);
  const [testTo, setTestTo] = useState('');
  const [dirty, setDirty] = useState(false);
  const loaded = useRef<Settings | null>(null);

  useScreenTitle('Mail', 'Mail | Sensible Studio');

  useEffect(() => {
    fetch('/system/sling/info.sessionInfo.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : null))
      .then((i: { userID?: string } | null) => setUser(i?.userID ?? null), () => setUser(null));
    fetch(API, { credentials: 'same-origin' })
      .then((r) => read<Settings>(r))
      .then((value) => { loaded.current = value; setSettings(value); }, (e: Error) => setError(e.message));
  }, []);

  const change = (patch: Partial<Settings>) => {
    setSettings((s) => (s ? { ...s, ...patch } : s));
    setDirty(true);
  };

  const save = async () => {
    if (!settings) return;
    setBusy('save');
    try {
      const body = new URLSearchParams({
        op: 'save',
        host: settings.host,
        port: String(settings.port),
        starttls: String(settings.starttls),
        user: settings.user,
        from: settings.from,
      });
      if (password) body.set('password', password);
      const saved = await read<Settings>(await fetch(API, { method: 'POST', credentials: 'same-origin', headers: { 'X-Studio-CSRF': '1' }, body }));
      setSettings(saved);
      loaded.current = saved;
      setPassword('');
      setDirty(false);
      ToastQueue.positive('Mail server saved', { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    } finally {
      setBusy(null);
    }
  };

  const close = () => {
    if (tab) tab.leave({ kind: 'start' });
    else window.location.href = '/studio/start.html#tools';
  };

  const test = async () => {
    setBusy('test');
    try {
      const body = new URLSearchParams({ op: 'test', to: testTo });
      await read(await fetch(API, { method: 'POST', credentials: 'same-origin', headers: { 'X-Studio-CSRF': '1' }, body }));
      ToastQueue.positive(`Test message sent to ${testTo}`, { timeout: 4000 });
    } catch (e) {
      ToastQueue.negative(`The test message was not sent: ${e instanceof Error ? e.message : String(e)}`, { timeout: 8000 });
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="studio-sites studio-mail">
      {!tab && <ProductBar user={user} />}
      <FormPage kind="Mail settings" title="Outgoing mail" dirty={dirty} busy={busy === 'save'} canSave={!!settings && !error} onReset={() => { if (loaded.current) setSettings({ ...loaded.current }); setPassword(''); setDirty(false); }} onSave={save} onClose={close}>
        {error ? <ErrorState title="Could not load Mail settings">{error}</ErrorState> : !settings ? <LoadingState label="Loading" /> : (
          <>
          <FormSection title="Server" description="Connection and authentication used for form submissions and other outgoing messages.">
            <p className="studio-mail__intro">
              The server Sensible Studio sends mail through — a form's submissions, for one. {settings.configured ? 'Mail is set up.' : 'Nothing is sent until a host is given.'}
            </p>
            <Form onSubmit={(e) => { e.preventDefault(); void save(); }}>
              <TextField label="Host" value={settings.host} onChange={(v) => change({ host: v })} description="smtp.example.com" isRequired />
              <NumberField label="Port" value={settings.port} onChange={(v) => change({ port: Number.isFinite(v) ? v : 587 })} minValue={1} maxValue={65535} formatOptions={{ useGrouping: false }} description="587 with STARTTLS, 465 for TLS from the start, 25 plain" />
              <Checkbox isSelected={settings.starttls} onChange={(v) => change({ starttls: v })}>
                Switch to TLS after connecting (STARTTLS)
              </Checkbox>
              <TextField label="User" value={settings.user} onChange={(v) => change({ user: v })} description="Empty when the server needs no sign-in" />
              <TextField label="Password" type="password" value={password} onChange={(v) => { setPassword(v); setDirty(true); }} description={settings.hasPassword ? 'A password is stored; leave empty to keep it.' : 'No password stored.'} autoComplete="new-password" />
              <TextField label="From" value={settings.from} onChange={(v) => change({ from: v })} description="The sender, e.g. WKND Forms <forms@wknd.site>" />
            </Form>
          </FormSection>
          <FormSection title="Try it" description="Send a test through the saved server configuration.">
            <Form onSubmit={(e) => { e.preventDefault(); void test(); }}>
              <TextField label="Send a test message to" type="email" value={testTo} onChange={setTestTo} description={dirty ? 'Save first; the test uses the stored settings.' : undefined} />
              <Button variant="secondary" type="submit" isDisabled={!testTo || !settings.configured || dirty || busy !== null} isPending={busy === 'test'}>Send test</Button>
            </Form>
          </FormSection>
          </>
        )}
      </FormPage>
    </div>
  );
}
