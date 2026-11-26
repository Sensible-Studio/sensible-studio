import { ProgressCircle } from '@adobe/react-spectrum';
import Globe from '@spectrum-icons/workflow/Globe';
import { useEffect, useState } from 'react';
import { Section } from './context';

/** What can translate a page, as `/bin/studio/translation.json?op=providers&path=` lists it (configurations first). */
export interface TranslationProvider {
  /** a cloud configuration's path, or an instance connector's name */
  name: string;
  label?: string;
  cloudConfig?: boolean;
  /** the `/conf` folder of a cloud configuration */
  conf?: string;
  connector?: string;
  available: boolean;
  problem?: string;
  code?: string;
  /** used when nothing else is chosen */
  default: boolean;
}

export interface CloudServicesClient {
  /** the cloud configuration folder of the page (its own or an ancestor's `cq:conf`), or null */
  conf(page: string): Promise<string | null>;
  translation(page: string): Promise<TranslationProvider[]>;
}

async function json<T>(url: string): Promise<T | null> {
  const res = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return (await res.json()) as T;
}

export class HttpCloudServicesClient implements CloudServicesClient {
  async conf(page: string) {
    for (let p = page; p.split('/').length > 2; p = p.slice(0, p.lastIndexOf('/'))) {
      for (const url of [`${encodeURI(p)}/jcr:content.json`, `${encodeURI(p)}.json`]) {
        const node = await json<Record<string, unknown>>(url).catch(() => null);
        const conf = node?.['cq:conf'];
        if (typeof conf === 'string' && conf) return conf;
      }
    }
    return null;
  }

  async translation(page: string) {
    return (await json<TranslationProvider[]>(`/bin/studio/translation.json?op=providers&path=${encodeURIComponent(page)}`)) ?? [];
  }
}

const CONSOLE = '/cloudservices/translation.html';

/** `/conf/wknd/settings/cloudconfigs/translation/claude` → `claude` */
const lastSegment = (p: string) => p.slice(p.lastIndexOf('/') + 1);

/**
 * The Cloud Services tab: the cloud configurations that serve the page. Like AEM's newer configurations, Sensible Studio finds
 * them through the page's Cloud Configuration (`cq:conf`, on the Advanced tab), then `/conf/global`; the tab shows
 * which ones apply and whether they are ready, and links to their console.
 */
export function CloudServicesTab({ client, page }: { client: CloudServicesClient; page: string }) {
  const [conf, setConf] = useState<string | null | undefined>(undefined);
  const [providers, setProviders] = useState<TranslationProvider[] | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([client.conf(page), client.translation(page)]).then(
      ([c, p]) => {
        if (!live) return;
        setConf(c);
        setProviders(p);
      },
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, page]);

  if (error) {
    return (
      <div className="studio-pp-column">
        <Section title="Cloud Service Configurations">
          <p className="studio-pp-muted">The cloud configurations could not be read: {error}</p>
        </Section>
      </div>
    );
  }
  if (providers === undefined || conf === undefined) {
    return (
      <div className="studio-pp-column studio-pp-pending">
        <ProgressCircle isIndeterminate size="S" aria-label="Loading cloud configurations" />
      </div>
    );
  }

  const configs = providers.filter((p) => p.cloudConfig);
  const connectors = providers.filter((p) => !p.cloudConfig);
  const used = providers.find((p) => p.default);
  const manage = `${CONSOLE}${conf ?? '/conf/global'}`;

  return (
    <div className="studio-pp-column">
      <Section title="Cloud Service Configurations">
        <p className="studio-pp-hint studio-pp-hint--lead">
          {conf ? (
            <>
              From the page's Cloud Configuration, <code>{conf}</code> (Advanced tab), then <code>/conf/global</code>.
            </>
          ) : (
            <>
              The page has no Cloud Configuration (Advanced tab), so only <code>/conf/global</code> applies.
            </>
          )}
        </p>

        <article className="studio-pp-service" aria-labelledby="studio-pp-service-translation">
          <header className="studio-pp-service__head">
            <Globe size="S" />
            <h4 id="studio-pp-service-translation">Translation Integration</h4>
            <a className="studio-pp-link studio-pp-service__manage" href={manage}>
              Manage
            </a>
          </header>

          {configs.length === 0 && (
            <p className="studio-pp-service__empty">
              No translation configuration in {conf ? <code>{conf}</code> : null}
              {conf ? ' or ' : ''}
              <code>/conf/global</code>. Content is translated with the instance's connector.{' '}
              <a className="studio-pp-link" href={manage}>
                Create a configuration
              </a>
            </p>
          )}

          <ul className="studio-pp-service__list">
            {[...configs, ...connectors].map((p) => (
              <li key={p.name} className={`studio-pp-service__row${p === used ? ' is-used' : ''}`}>
                <div className="studio-pp-service__what">
                  <span className="studio-pp-service__name">
                    {p.cloudConfig ? (p.label && p.label !== p.connector ? p.label : lastSegment(p.name)) : (p.label ?? p.name)}
                  </span>
                  <span className="studio-pp-service__where">
                    {p.cloudConfig ? (
                      <a className="studio-pp-link" href={`${CONSOLE}${p.conf ?? ''}`}>
                        {p.name}
                      </a>
                    ) : (
                      'Instance connector (OSGi)'
                    )}
                  </span>
                  {!p.available && p.problem && <span className="studio-pp-service__problem">{p.problem}</span>}
                </div>
                <div className="studio-pp-service__badges">
                  {p === used && <span className="studio-pp-badge studio-pp-badge--used">Used for this page</span>}
                  <span className={`studio-pp-status ${p.available ? 'is-ready' : 'is-missing'}`}>{p.available ? 'Ready' : 'Not set up'}</span>
                </div>
              </li>
            ))}
          </ul>
        </article>
      </Section>
    </div>
  );
}
