import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { securityRoute } from './SecurityApp';
import { SslConsole } from './SslConsole';
import { finished, portOf, type SslCheck, type SslClient, type SslRequest, type SslStatus } from './ssl';

afterEach(cleanup);

const DAY = 86_400_000;
const cert = { subject: 'CN=localhost,O=Sensible Studio', issuer: 'CN=localhost,O=Sensible Studio', names: ['localhost', '127.0.0.1'], notAfter: Date.now() + 365 * DAY, selfSigned: true, sha256: 'AA:BB:CC' };

const off: SslStatus = { httpPort: 4502, enabled: false, port: null, hostname: 'localhost', url: null, certificate: [], listening: false, suggestedPort: 8443, last: null, canEdit: true };

class Memory implements SslClient {
  state: SslStatus;
  requests: SslRequest[] = [];
  applied: SslRequest[] = [];
  /** How the next change ends. */
  outcome: 'done' | 'rolledBack' = 'done';
  polls = 0;
  constructor(s: SslStatus) {
    this.state = s;
  }
  async status() {
    this.polls++;
    // the change is over at the second look
    if (this.state.last?.state === 'applying' && this.polls > 1) {
      const ok = this.outcome === 'done';
      const turningOff = this.state.last.what === 'HTTPS off';
      this.state = {
        ...this.state,
        enabled: ok ? !turningOff : false,
        listening: ok && !turningOff,
        url: ok && !turningOff ? `https://${this.state.hostname}:${this.state.port}/` : null,
        last: { state: this.outcome, what: this.state.last.what, at: this.state.last.at + 1, message: ok ? 'done' : 'HTTPS does not answer on port 8443. The configuration was put back as it was' },
      };
    }
    return this.state;
  }
  async check(r: SslRequest): Promise<SslCheck> {
    this.requests.push(r);
    return { certificate: [cert], warnings: r.selfSigned ? ['The certificate is made here and signed by itself'] : [], hostname: r.hostname, port: r.port, url: `https://${r.hostname}:${r.port}/` };
  }
  async apply(r: SslRequest) {
    this.applied.push(r);
    this.polls = 0;
    this.state = { ...this.state, port: r.port, hostname: r.hostname, certificate: [cert], last: { state: 'applying', what: `HTTPS on port ${r.port}`, at: 1000, message: 'the web server restarts' } };
    return this.state;
  }
  async disable() {
    this.polls = 0;
    this.state = { ...this.state, last: { state: 'applying', what: 'HTTPS off', at: 2000, message: '' } };
    return this.state;
  }
  async enable() {
    return this.apply({ selfSigned: true, hostname: this.state.hostname, port: this.state.port ?? 8443 });
  }
}

const mount = (client: Memory) =>
  render(
    <Provider theme={defaultTheme}>
      <SslConsole user="admin" client={client} />
    </Provider>,
  );

describe('SSL Configuration', () => {
  it('is at /security/ssl.html and at AEM’s address', () => {
    expect(securityRoute('/security/ssl.html')).toBe('ssl');
    expect(securityRoute('/libs/granite/security/content/sslConfig.html')).toBe('ssl');
  });

  it('reads ports and when a change is over', () => {
    expect(portOf('8443')).toBe(8443);
    expect(portOf('0')).toBeNull();
    expect(portOf('70000')).toBeNull();
    expect(portOf('84a')).toBeNull();
    const started = { state: 'applying' as const, what: 'x', at: 10, message: '' };
    expect(finished({ ...off, last: started }, started)).toBeNull();
    expect(finished({ ...off, last: { ...started, state: 'done', at: 11 } }, started)?.state).toBe('done');
  });

  it('says HTTPS is off, and the wizard makes a self-signed certificate and turns it on', async () => {
    const client = new Memory(off);
    mount(client);
    expect(await screen.findByText('Off')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: /Configure HTTPS/ }));
    expect(screen.getByRole('dialog', { name: 'SSL Configuration' })).toBeTruthy();
    const next = () => screen.getByRole('button', { name: 'Next' });
    expect(next().hasAttribute('disabled')).toBe(true);
    fireEvent.click(screen.getByLabelText(/Make a self-signed certificate/));
    fireEvent.click(next());
    const port = screen.getByLabelText(/HTTPS Port/) as HTMLInputElement;
    expect(port.value).toBe('8443');
    fireEvent.change(port, { target: { value: '4502' } });
    expect(screen.getByText('That is the HTTP port')).toBeTruthy();
    fireEvent.change(port, { target: { value: '8443' } });
    fireEvent.change(screen.getByLabelText(/HTTPS Hostname/), { target: { value: 'localhost' } });
    fireEvent.click(next());
    expect(await screen.findByText(/signed by itself/)).toBeTruthy();
    expect(screen.getByText('https://localhost:8443/')).toBeTruthy();
    expect(client.requests[0]).toMatchObject({ selfSigned: true, port: 8443, hostname: 'localhost' });
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('HTTPS is on', {}, { timeout: 5000 })).toBeTruthy();
    expect(client.applied).toHaveLength(1);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(await screen.findByText('On')).toBeTruthy();
    expect(screen.getAllByText('https://localhost:8443/').length).toBeGreaterThan(0);
  });

  it('sends the private key and certificate given, and says when it was put back', async () => {
    const client = new Memory(off);
    client.outcome = 'rolledBack';
    mount(client);
    fireEvent.click(await screen.findByRole('button', { name: /Configure HTTPS/ }));
    fireEvent.change(screen.getByLabelText('Private key (PEM)'), { target: { value: '-----BEGIN PRIVATE KEY-----\nAAA\n-----END PRIVATE KEY-----' } });
    const cert = new File(['-----BEGIN CERTIFICATE-----'], 'server.crt');
    fireEvent.change(screen.getByLabelText('Certificate file'), { target: { files: [cert] } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(client.requests).toHaveLength(1));
    expect(client.requests[0].selfSigned).toBe(false);
    expect(client.requests[0].key).toContain('BEGIN PRIVATE KEY');
    expect(client.requests[0].certificate).toBe(cert);
    fireEvent.click(await screen.findByRole('button', { name: 'Apply' }));
    expect(await screen.findByText('Put back as it was', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getAllByText(/HTTPS does not answer on port 8443/).length).toBeGreaterThan(0);
  });

  it('turns HTTPS off after asking', async () => {
    const client = new Memory({ ...off, enabled: true, port: 8443, url: 'https://localhost:8443/', listening: true, certificate: [cert], serves: 'AA:BB:CC' });
    mount(client);
    expect(await screen.findByText('On')).toBeTruthy();
    expect(screen.getByRole('button', { name: /Change certificate/ })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Turn HTTPS off' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Turn off' }));
    expect(await screen.findByText('Changing…')).toBeTruthy();
    expect(await screen.findByText('Off', {}, { timeout: 5000 })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Turn HTTPS on' })).toBeTruthy();
  });
});
