import { describe, expect, it } from 'vitest';
import { signedOut, signInUrl } from './session';

const O = 'http://localhost:4502';

describe('noticing the end of a session', () => {
  it('sees the sign-in page behind a request', () => {
    expect(signedOut({ redirected: true, status: 200, url: `${O}/libs/granite/core/content/login.html?resource=%2Fbin%2Fstudio%2Fdam` }, O)).toBe(true);
    expect(signedOut({ redirected: false, status: 401, url: `${O}/bin/studio/dam` }, O)).toBe(true);
  });
  it('leaves ordinary answers alone', () => {
    expect(signedOut({ redirected: false, status: 200, url: `${O}/bin/studio/dam?op=list` }, O)).toBe(false);
    expect(signedOut({ redirected: true, status: 200, url: `${O}/content/wknd/us/en.html` }, O)).toBe(false);
    expect(signedOut({ redirected: true, status: 200, url: 'https://elsewhere.example/login.html?resource=x' }, O)).toBe(false);
    expect(signedOut({ redirected: false, status: 404, url: '' }, O)).toBe(false);
    // somebody reading the sign-in page on purpose
    expect(signedOut({ redirected: false, status: 200, url: `${O}/libs/granite/core/content/login.html` }, O)).toBe(false);
  });
  it('comes back to where the person was', () => {
    expect(signInUrl({ pathname: '/sites.html/content/wknd', search: '?a=1', hash: '#x' })).toBe(
      '/libs/granite/core/content/login.html?resource=%2Fsites.html%2Fcontent%2Fwknd%3Fa%3D1%23x',
    );
  });
});
