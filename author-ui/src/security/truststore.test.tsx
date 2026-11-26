import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { TrustStoreConsole } from './TrustStoreConsole';
import { commonName, validity, type TrustCheck, type TrustStoreClient, type TrustedCertificate } from './truststore';

afterEach(cleanup);

const DAY = 86_400_000;
const ca: TrustedCertificate = {
  alias: 'acme-root-ca',
  subject: 'CN=Acme Root CA,O=Acme',
  issuer: 'CN=Acme Root CA,O=Acme',
  names: [],
  notAfter: Date.now() + 400 * DAY,
  authority: true,
  selfSigned: true,
  sha256: 'AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89',
};
const server: TrustedCertificate = { alias: '', subject: 'CN=publish.internal', issuer: 'CN=Acme Root CA,O=Acme', names: ['publish.internal'], notAfter: Date.now() + 30 * DAY };

class Memory implements TrustStoreClient {
  certs: TrustedCertificate[];
  added: string[] = [];
  removed: string[] = [];
  constructor(certs: TrustedCertificate[], private readonly canEdit = true, private readonly answer: TrustCheck = { trusted: false, message: 'no authority this instance trusts signed it', chain: [server, { ...ca, pem: '-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----\n' }] }) {
    this.certs = certs;
  }
  async list() {
    return { certificates: this.certs, canEdit: this.canEdit };
  }
  async check() {
    return this.answer;
  }
  async add(alias: string, certificate: string | File) {
    this.added.push(`${alias}:${typeof certificate === 'string' ? certificate.split('\n')[0] : certificate.name}`);
    this.certs = [...this.certs, { ...ca, alias: alias || 'acme-root-ca' }];
    return { added: [alias || 'acme-root-ca'], certificates: this.certs };
  }
  async remove(alias: string) {
    this.removed.push(alias);
    this.certs = this.certs.filter((c) => c.alias !== alias);
    return { certificates: this.certs };
  }
}

const mount = (client: Memory) =>
  render(
    <Provider theme={defaultTheme}>
      <TrustStoreConsole user="admin" client={client} />
    </Provider>,
  );

describe('the Trust Store', () => {
  it('lists the certificates added, with what they are and until when', async () => {
    mount(new Memory([ca]));
    expect(await screen.findByText('acme-root-ca')).toBeTruthy();
    expect(screen.getByText('Acme Root CA')).toBeTruthy();
    expect(screen.getByText('Authority')).toBeTruthy();
    expect(screen.getByText(/self-signed/)).toBeTruthy();
    expect(screen.getByText(/^valid until/)).toBeTruthy();
  });

  it('says when there is none', async () => {
    mount(new Memory([]));
    expect(await screen.findByText('No certificates added')).toBeTruthy();
  });

  it('checks an address and offers to trust the authority that signed it', async () => {
    const client = new Memory([]);
    mount(client);
    await screen.findByText('No certificates added');
    fireEvent.change(screen.getByRole('textbox', { name: 'Check an address' }), { target: { value: 'publish.internal/bin/receive' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check' }));
    const chain = await screen.findByRole('list', { name: 'The certificates the server shows' });
    expect(screen.getByText('Not trusted')).toBeTruthy();
    expect(within(chain).getByText('publish.internal')).toBeTruthy();
    fireEvent.click(within(chain).getByRole('button', { name: 'Trust this authority…' }));
    const dialog = await screen.findByRole('dialog');
    expect((within(dialog).getByRole('textbox', { name: 'Alias' }) as HTMLInputElement).value).toBe('Acme Root CA');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(client.added).toEqual(['Acme Root CA:-----BEGIN CERTIFICATE-----']));
  });

  it('removes one after asking', async () => {
    const client = new Memory([ca]);
    mount(client);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove acme-root-ca' }));
    const dialog = await screen.findByRole('alertdialog');
    expect(client.removed).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
    await waitFor(() => expect(client.removed).toEqual(['acme-root-ca']));
  });

  it('changes nothing for who may not', async () => {
    mount(new Memory([ca], false));
    await screen.findByText('acme-root-ca');
    expect(screen.queryByRole('button', { name: /Add certificate/ })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove acme-root-ca' })).toBeNull();
  });

  it('puts names and dates in words', () => {
    expect(commonName('CN=Acme Root CA,O=Acme, Inc.,C=RO')).toBe('Acme Root CA');
    expect(commonName('O=Acme')).toBe('O=Acme');
    const now = Date.UTC(2026, 10, 24);
    expect(validity({ alias: 'x', notAfter: now + 10 * DAY }, now)).toBe('expires in 10 days');
    expect(validity({ alias: 'x', notAfter: now - DAY, expired: true }, now)).toMatch(/^expired /);
  });
});
