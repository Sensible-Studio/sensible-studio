import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  NumberField,
  Picker,
  Switch,
  Text,
  TextArea,
  TextField,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronUp from '@spectrum-icons/workflow/ChevronUp';
import Delete from '@spectrum-icons/workflow/Delete';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { StudioClient } from '../editor/api/StudioClient';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { ProductBar } from '../shell/ProductBar';
import { useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import type { ContextHubSettings, HubModule, HubStore, TargetingClient } from './api';

export const CONTEXTHUB_URL = '/libs/granite/contexthub/content/admin/contexthub.html';

const BUILT_IN = ['profile', 'geolocation', 'surferinfo'];
const NAME = /^[a-z][A-Za-z0-9_-]{0,39}$/;
const PROPERTY = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$/;
const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

const STORE_HELP: Record<string, string> = {
  profile: "Who the visitor is: what a form or a sign-in tells (name, age, gender, interests, loyalty…)",
  geolocation: 'Where the visitor is: country, city, latitude, longitude',
  surferinfo: 'Their browser and device, and the time: device, browser, os, month, year, hour, weekday',
  generic: 'Values the site sets itself (StudioContextHub.set), starting with the ones below',
  other: "One of AEM's stores: kept as it is; Sensible Studio does not run it",
  json: "The answer of a JSON address, read in the visitor's browser and kept a while",
};

/** `key = value` lines → values (numbers and true/false as such). */
export function linesToValues(text: string): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const line of text.split('\n')) {
    const i = line.indexOf('=');
    if (i <= 0) continue;
    const key = line.slice(0, i).trim();
    const raw = line.slice(i + 1).trim();
    if (!key) continue;
    out[key] = /^-?\d+(\.\d+)?$/.test(raw) ? Number(raw) : raw === 'true' ? true : raw === 'false' ? false : raw;
  }
  return out;
}

export function valuesToLines(values: Record<string, unknown>): string {
  return Object.entries(values)
    .map(([k, v]) => `${k} = ${Array.isArray(v) ? v.join(', ') : String(v)}`)
    .join('\n');
}

interface StoreDraft extends HubStore {
  key: number;
  lines: string;
  isNew?: boolean;
}

interface ModuleDraft extends HubModule {
  key: number;
  optionsText: string;
}

let keys = 0;
const storeDrafts = (stores: HubStore[]): StoreDraft[] => stores.map((s) => ({ ...s, key: ++keys, lines: valuesToLines(s.initial ?? {}) }));
const moduleDrafts = (modules: HubModule[]): ModuleDraft[] => modules.map((m) => ({ ...m, key: ++keys, optionsText: (m.options ?? []).join(', ') }));

/** What the server takes. */
export function toSettings(stores: StoreDraft[], modules: ModuleDraft[]): { stores: HubStore[]; modules: HubModule[] } {
  return {
    stores: stores.map((s) => {
      if (s.type === 'other') return { name: s.name, type: 'other', enabled: s.enabled, initial: {} };
      const out: HubStore = { name: s.name.trim(), type: s.type, enabled: s.enabled, initial: linesToValues(s.lines) };
      if (s.title?.trim()) out.title = s.title.trim();
      if (s.type === 'json') {
        out.url = (s.url ?? '').trim();
        out.refresh = s.refresh ?? 60;
        if (s.select?.trim()) out.select = s.select.trim();
      }
      return out;
    }),
    modules: modules.map((m) => {
      const out: HubModule = { title: m.title.trim(), store: m.store, property: m.property.trim(), type: m.type };
      if (m.type === 'select')
        out.options = m.optionsText
          .split(',')
          .map((o) => o.trim())
          .filter(Boolean);
      return out;
    }),
  };
}

/** What keeps the settings from being saved, in words. */
export function problemsOf(stores: StoreDraft[], modules: ModuleDraft[]): string[] {
  const out: string[] = [];
  const names = new Set<string>();
  for (const s of stores) {
    const n = s.name.trim();
    if (!NAME.test(n)) out.push(`A store's name is a word starting with a small letter (letters, digits, - and _): “${n}”.`);
    else if (names.has(n)) out.push(`Two stores named ${n}.`);
    names.add(n);
    if (s.type === 'json' && !/^(https?:\/\/\S+|\/\S*)$/.test((s.url ?? '').trim())) out.push(`${n}: the address is https://… or a path on this site.`);
  }
  for (const m of modules) {
    const label = m.title.trim() || 'A field';
    if (!m.title.trim()) out.push('Each field of the bar needs a label.');
    if (!names.has(m.store)) out.push(`${label}: choose a store.`);
    if (!PROPERTY.test(m.property.trim())) out.push(`${label}: a property is a name such as age or address.city.`);
    if (m.type === 'select' && !m.optionsText.split(',').some((o) => o.trim())) out.push(`${label}: a choice needs its options, separated by commas.`);
  }
  return out;
}

/**
 * AEM's ContextHub configuration (Tools › Sites › ContextHub, `/libs/granite/contexthub/content/admin/contexthub.html`):
 * for a configuration, the stores the visitor's context is kept in — the three built in, turned on or off and with the
 * values they start with, and stores of its own (values, or the answer of a JSON address) — and the fields of the
 * ContextHub bar (AEM's UI modules). Audiences can use every store property.
 */
/** What the bar in the page does with each of AEM's module types. */
export const MODULE_NOTES: Record<string, string> = {
  'granite.profile': 'the persona to look as',
  'contexthub.location': 'country and city',
  'granite.resolvedsegments': 'the audiences that match now',
  'contexthub.device': 'the device the audiences see and the size of the page',
  'contexthub.browserinfo': 'the browser and system',
  'contexthub.datetime': 'the date and time now',
  'contexthub.tagcloud': 'the tags of the pages seen',
  'contexthub.screen-orientation': 'portrait or landscape of the emulated device',
  'contexthub.base': 'the text of its template',
};

export function ContextHubConsole({ client, studio, initialConf }: { client: TargetingClient; studio?: StudioClient; initialConf?: string }) {
  const tab = useTab();
  const [user, setUser] = useState<string | null>(null);
  const [confs, setConfs] = useState<string[]>([]);
  const [conf, setConf] = useState(initialConf ?? '/conf/global');
  const [settings, setSettings] = useState<ContextHubSettings | null>(null);
  const [stores, setStores] = useState<StoreDraft[]>([]);
  const [modules, setModules] = useState<ModuleDraft[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [resetting, setResetting] = useState(false);

  useScreenTitle('ContextHub', 'ContextHub | Sensible Studio');
  useEffect(() => {
    if (!tab && studio) studio.currentUser().then(setUser, () => setUser(null));
    fetch('/conf.1.json', { credentials: 'same-origin' })
      .then((r) => (r.ok ? r.json() : {}))
      .then((j: Record<string, unknown>) => {
        const names = Object.entries(j)
          .filter(([k, v]) => !k.includes(':') && v && typeof v === 'object')
          .map(([k]) => `/conf/${k}`);
        setConfs(names.includes('/conf/global') ? names : ['/conf/global', ...names]);
      })
      .catch(() => setConfs(['/conf/global']));
  }, [tab, studio]);

  const take = useCallback((s: ContextHubSettings) => {
    setSettings(s);
    setStores(storeDrafts(s.stores));
    setModules(moduleDrafts(s.modules));
    setSaveError(null);
  }, []);
  const load = useCallback(() => {
    setSettings(null);
    client.contexthub(conf).then(take, (e: unknown) => setError(message(e)));
  }, [client, conf, take]);
  useEffect(load, [load]);

  const tabConf = tab?.place.kind === 'contexthub' ? tab.place.conf : undefined;
  useEffect(() => {
    if (tabConf && tabConf !== conf) setConf(tabConf);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tabConf]);
  useEffect(() => {
    if (tab) {
      if (tabConf !== conf) tab.setPlace({ kind: 'contexthub', conf }, { replace: true });
    } else window.history.replaceState(null, '', `${CONTEXTHUB_URL}${conf}`);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conf]);

  const value = useMemo(() => toSettings(stores, modules), [stores, modules]);
  const problems = problemsOf(stores, modules);
  const dirty = !!settings && JSON.stringify(value) !== JSON.stringify(toSettings(storeDrafts(settings.stores), moduleDrafts(settings.modules)));
  const canEdit = settings?.canEdit !== false;

  const setStore = (key: number, patch: Partial<StoreDraft>) => setStores((ss) => ss.map((s) => (s.key === key ? { ...s, ...patch } : s)));
  const setModule = (key: number, patch: Partial<ModuleDraft>) => setModules((ms) => ms.map((m) => (m.key === key ? { ...m, ...patch } : m)));
  const moveModule = (i: number, by: number) =>
    setModules((ms) => {
      const next = [...ms];
      const [m] = next.splice(i, 1);
      next.splice(i + by, 0, m);
      return next;
    });

  const save = async () => {
    setBusy(true);
    setSaveError(null);
    try {
      take(await client.saveContexthub(conf, value));
      ToastQueue.positive(`ContextHub of ${conf.replace('/conf/', '')} saved. Pages read it at once.`, { timeout: 4000 });
    } catch (e) {
      setSaveError(message(e));
    } finally {
      setBusy(false);
    }
  };

  const storeNames = stores
    .filter((s) => s.type !== 'other')
    .map((s) => s.name.trim())
    .filter(Boolean);
  const inherited = settings && !settings.custom;

  return (
    <div className="studio-sites studio-contexthub-config">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url={CONTEXTHUB_URL}
        title="ContextHub"
        closable={false}
        end={
          <>
            <Picker aria-label="Configuration" selectedKey={conf} onSelectionChange={(k) => setConf(String(k))} isQuiet width="size-2400">
              {confs.map((c) => (
                <Item key={c}>{c.replace('/conf/', '')}</Item>
              ))}
            </Picker>
            <Button variant="secondary" style="outline" isDisabled={!dirty || busy} onPress={() => settings && take(settings)}>
              Cancel
            </Button>
            <Button variant="secondary" style="outline" isDisabled={!canEdit || busy || !settings?.custom} onPress={() => setResetting(true)}>
              Reset
            </Button>
          </>
        }
        primary={
          <Button variant="accent" isDisabled={!canEdit || !dirty || problems.length > 0 || busy} isPending={busy} onPress={() => void save()}>
            Save
          </Button>
        }
      />
      <main className="studio-sites__content studio-chconf">
        {error ? (
          <ErrorState title="Could not read ContextHub">{error}</ErrorState>
        ) : !settings ? (
          <LoadingState />
        ) : (
          <>
            <p className="studio-chconf__intro">
              What the pages of <strong>{conf.replace('/conf/', '')}</strong> know about the visitor, which audiences decide on, and what the ContextHub
              bar shows in Preview.{' '}
              {settings.custom
                ? `Its own settings${settings.modifiedBy ? `, changed by ${settings.modifiedBy}` : ''}${settings.modified ? ` on ${new Date(settings.modified).toLocaleString()}` : ''}.`
                : settings.from
                  ? `It uses ${settings.from.replace('/settings/cloudsettings/default/contexthub', '')}'s until it is saved here.`
                  : 'The default; saved here it becomes its own.'}
              {!canEdit && ' You may not change it.'}
            </p>
            {inherited && settings.from && (
              <p className="studio-chconf__note">Saving here gives {conf.replace('/conf/', '')} settings of its own; the others keep theirs.</p>
            )}

            <section aria-label="Stores">
              <h2>Stores</h2>
              <ul className="studio-chconf__stores">
                {stores.map((s) => {
                  const builtIn = BUILT_IN.includes(s.type);
                  const other = s.type === 'other';
                  return (
                    <li key={s.key} className="studio-chconf__store" aria-label={`Store ${s.name || 'new'}`}>
                      <header>
                        <strong>{s.title || s.name || 'New store'}</strong>
                        <code>{other && s.storeType ? `${s.name} · ${s.storeType}` : s.name}</code>
                        <span className="studio-chconf__help">{STORE_HELP[s.type]}</span>
                        <Switch isSelected={s.enabled} onChange={(on) => setStore(s.key, { enabled: on })} isDisabled={!canEdit}>
                          On
                        </Switch>
                        {!builtIn && !other && (
                          <TooltipTrigger delay={400}>
                            <ActionButton isQuiet aria-label={`Remove store ${s.name}`} isDisabled={!canEdit} onPress={() => setStores((ss) => ss.filter((x) => x.key !== s.key))}>
                              <Delete />
                            </ActionButton>
                            <Tooltip>Remove</Tooltip>
                          </TooltipTrigger>
                        )}
                      </header>
                      {!other && (
                      <div className="studio-chconf__fields">
                        {!builtIn && (
                          <>
                            <TextField label="Name" value={s.name} onChange={(v) => setStore(s.key, { name: v })} isReadOnly={!s.isNew} isRequired width="size-2000" description="Audiences say store/property, such as campaign/source" />
                            <TextField label="Title" value={s.title ?? ''} onChange={(v) => setStore(s.key, { title: v })} width="size-2400" />
                            <Picker label="Kind" selectedKey={s.type} onSelectionChange={(k) => setStore(s.key, { type: k as HubStore['type'] })} width="size-2400" isDisabled={!canEdit}>
                              <Item key="generic">Values</Item>
                              <Item key="json">From a JSON address</Item>
                            </Picker>
                          </>
                        )}
                        {s.type === 'json' && (
                          <>
                            <TextField label="Address" value={s.url ?? ''} onChange={(v) => setStore(s.key, { url: v })} isRequired width="size-4600" description="Read in the visitor's browser: an address on this site, or one that allows it (CORS)" />
                            <TextField label="Keep" value={s.select ?? ''} onChange={(v) => setStore(s.key, { select: v })} width="size-2000" description="The part of the answer, such as current; empty: all of it" />
                            <NumberField label="Read again after (minutes)" value={s.refresh ?? 60} minValue={1} maxValue={10080} onChange={(v) => setStore(s.key, { refresh: Number.isNaN(v) ? 60 : v })} width="size-2400" />
                          </>
                        )}
                        <TextArea
                          label={s.type === 'json' ? 'Until it answers' : 'Starts with'}
                          value={s.lines}
                          onChange={(v) => setStore(s.key, { lines: v })}
                          width="size-4600"
                          height="size-1000"
                          isReadOnly={!canEdit}
                          description="One per line: property = value (the visitor's own values win)"
                        />
                      </div>
                      )}
                    </li>
                  );
                })}
              </ul>
              {canEdit && (
                <ActionButton
                  onPress={() => {
                    let i = 1;
                    while (storeNames.includes(`store${i}`)) i++;
                    setStores((ss) => [...ss, { key: ++keys, name: `store${i}`, title: '', type: 'generic', enabled: true, initial: {}, lines: '', isNew: true }]);
                  }}
                >
                  <Add />
                  <Text>Add store</Text>
                </ActionButton>
              )}
            </section>

            <section aria-label="ContextHub bar">
              <h2>ContextHub bar</h2>
              <p className="studio-chconf__help">The fields Preview shows to try the page as another visitor, in this order (AEM's UI modules).</p>
              {modules.length === 0 ? (
                <EmptyState title="No fields">The bar shows only the persona.</EmptyState>
              ) : (
                <table className="studio-table studio-chconf__modules" aria-label="Fields of the bar">
                  <thead>
                    <tr>
                      <th>Label</th>
                      <th>Store</th>
                      <th>Property</th>
                      <th>Kind</th>
                      <th>Options</th>
                      <th aria-label="Order and remove" />
                    </tr>
                  </thead>
                  <tbody>
                    {modules.map((m, i) => (
                      <tr key={m.key} aria-label={`Field ${m.title || i + 1}`}>
                        <td>
                          <TextField aria-label="Label" value={m.title} onChange={(v) => setModule(m.key, { title: v })} isQuiet width="100%" isReadOnly={!canEdit} />
                        </td>
                        <td>
                          <Picker aria-label="Store" selectedKey={m.store} onSelectionChange={(k) => setModule(m.key, { store: String(k) })} isQuiet isDisabled={!canEdit} width="size-1700">
                            {storeNames.map((n) => (
                              <Item key={n}>{n}</Item>
                            ))}
                          </Picker>
                        </td>
                        <td>
                          <TextField aria-label="Property" value={m.property} onChange={(v) => setModule(m.key, { property: v })} isQuiet width="100%" isReadOnly={!canEdit} />
                        </td>
                        <td>
                          <Picker aria-label="Kind" selectedKey={m.type} onSelectionChange={(k) => setModule(m.key, { type: k as HubModule['type'] })} isQuiet isDisabled={!canEdit} width="size-1250">
                            <Item key="text">Text</Item>
                            <Item key="number">Number</Item>
                            <Item key="select">Choice</Item>
                          </Picker>
                        </td>
                        <td>
                          {m.type === 'select' && (
                            <TextField aria-label="Options" value={m.optionsText} onChange={(v) => setModule(m.key, { optionsText: v })} isQuiet width="100%" placeholder="a, b, c" isReadOnly={!canEdit} />
                          )}
                        </td>
                        <td className="studio-chconf__row-tools">
                          <ActionButton isQuiet aria-label={`Move ${m.title} up`} isDisabled={!canEdit || i === 0} onPress={() => moveModule(i, -1)}>
                            <ChevronUp />
                          </ActionButton>
                          <ActionButton isQuiet aria-label={`Move ${m.title} down`} isDisabled={!canEdit || i === modules.length - 1} onPress={() => moveModule(i, 1)}>
                            <ChevronDown />
                          </ActionButton>
                          <ActionButton isQuiet aria-label={`Remove ${m.title}`} isDisabled={!canEdit} onPress={() => setModules((ms) => ms.filter((x) => x.key !== m.key))}>
                            <Delete />
                          </ActionButton>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {canEdit && (
                <ActionButton
                  onPress={() => setModules((ms) => [...ms, { key: ++keys, title: '', store: storeNames[0] ?? 'profile', property: '', type: 'text', optionsText: '' }])}
                >
                  <Add />
                  <Text>Add field</Text>
                </ActionButton>
              )}
            </section>
            {(settings.modes ?? []).length > 0 && (
              <section aria-label="AEM UI modes">
                <h2>AEM's ContextHub UI</h2>
                <p className="studio-chconf__help">
                  The configuration also has AEM's own UI modes, kept as they are. The ContextHub bar in the page shows them next to the fields
                  above, one tab each:
                </p>
                <ul className="studio-chconf__modes">
                  {(settings.modes ?? []).map((m) => (
                    <li key={m.name}>
                      <strong>{m.title}</strong>{' '}
                      {(m.items ?? m.modules.map((t) => ({ name: t, title: t, type: t }))).map((it) => `${it.title}${MODULE_NOTES[it.type] ? ` (${MODULE_NOTES[it.type]})` : ''}`).join(' · ') || 'no modules'}
                    </li>
                  ))}
                </ul>
              </section>
            )}
            {(problems.length > 0 || saveError) && (
              <ul className="studio-chconf__problems" role="alert">
                {saveError && <li>{saveError}</li>}
                {problems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            )}
          </>
        )}
      </main>
      <DialogContainer onDismiss={() => setResetting(false)}>
        {resetting && (
          <StudioConfirmDialog
            title="Reset ContextHub"
            variant="destructive"
            primaryActionLabel="Reset"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.resetContexthub(conf).then(
                (s) => {
                  take(s);
                  ToastQueue.positive(`${conf.replace('/conf/', '')} uses ${s.from ? "/conf/global's" : 'the default'} ContextHub again.`, { timeout: 4000 });
                },
                (e: unknown) => setSaveError(message(e)),
              )
            }
          >
            {settings?.madeHere
              ? `${conf.replace('/conf/', '')}'s ContextHub configuration, made here, is removed; its pages use /conf/global's, or the default.`
              : `What was changed here is undone: the stores and the bar's fields added here go, the values stores start with go, and a store turned off here is on again. The rest of ${conf.replace('/conf/', '')}'s configuration (from AEM) stays.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}
