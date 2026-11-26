import { useEffect, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { AuthorizableEditor } from './AuthorizableEditor';
import { AuthorizablesConsole } from './AuthorizablesConsole';
import { PermissionsConsole } from './PermissionsConsole';
import { TrustStoreConsole } from './TrustStoreConsole';
import { OAuthConsole } from './OAuthConsole';
import { SslConsole } from './SslConsole';

export type SecurityRoute = 'users' | 'groups' | 'permissions' | 'user' | 'group' | 'truststore' | 'oauth' | 'ssl';

/** `/security/<route>.html`, like AEM's security consoles; and AEM's own address of the SSL wizard. */
export function securityRoute(pathname: string): SecurityRoute | null {
  if (pathname === '/libs/granite/security/content/sslConfig.html') return 'ssl';
  const m = pathname.match(/^\/security\/(users|groups|permissions|user|group|truststore|oauth|ssl)\.html$/);
  return m ? (m[1] as SecurityRoute) : null;
}

export function SecurityApp({ client, route, id, principal }: { client: StudioClient; route: SecurityRoute; id?: string | null; principal?: string | null }) {
  const [user, setUser] = useState<string | null>(null);
  useEffect(() => {
    client.currentUser().then(setUser, () => setUser(null));
  }, [client]);
  const params = new URLSearchParams(window.location.search);
  switch (route) {
    case 'users':
    case 'groups':
      return <AuthorizablesConsole kind={route} user={user} />;
    case 'permissions':
      return <PermissionsConsole user={user} initialPrincipal={principal === undefined ? params.get('principal') : principal} />;
    case 'truststore':
      return <TrustStoreConsole user={user} />;
    case 'oauth':
      return <OAuthConsole user={user} />;
    case 'ssl':
      return <SslConsole user={user} />;
    default:
      return <AuthorizableEditor kind={route} id={id === undefined ? params.get('id') : id} user={user} />;
  }
}
