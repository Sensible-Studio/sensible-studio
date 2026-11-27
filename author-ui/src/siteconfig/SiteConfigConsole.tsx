import { ActionButton, Button, DialogTrigger, NumberField, ProgressCircle, Switch, Text, TextField, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Close from '@spectrum-icons/workflow/Close';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useState, useRef } from 'react';
import { useNavigate, useScreenTitle, useTab } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import '../sites/sites.scss';
import type { ReplicationClient } from '../replication/api';
import type { ConfigDetail, ConfigList, ConfigProperty, Scalar, SiteConfigClient, Value, Values } from './api';
import './siteconfig.scss';
import { StudioConfirmDialog } from '../shell/StudioDialog';

/**
 * Site configurations (Home › System › Developer › Site configurations, or Pages › a page › Site
 * configuration): the settings the code of a site reads through Sling's context-aware configuration —
 * per site, inherited from the sites above and from the global configuration, with the developers'
 * defaults. Left: where (a content path; the site it belongs to is named) and the configurations the
 * instance's bundles define; right: the form of one — a field left empty is not set here and shows what
 * applies instead (inherited or the default). What a site sets is content under /conf: the live site reads its
 * own copy, so a configuration is published (and unpublished) from here.
 */
export const SITECONFIG_URL = '/studio/siteconfig.html';

interface Props {
  client: SiteConfigClient;
  /** the content path the console is at */
  path: string;
  /** going to another path; by default a move in the shell */
  onPath?(path: string): void;
  /** publishing what a site sets; without it the console only saves */
  replication?: Publishing;
}

export type Publishing = Pick<ReplicationClient, 'status' | 'activate' | 'deactivate'>;

const show = (v: Value): string => (v === null || v === undefined ? '' : Array.isArray(v) ? v.join(', ') : String(v));

/** What a property is set to here: its own value, unless that value only comes from above or is the default. */
export function ownValue(p: ConfigProperty): Value {
  return p.inherited || p.isDefault ? null : p.value;
}

export function valuesOf(properties: ConfigProperty[]): Values {
  const out: Values = {};
  for (const p of properties) if (!p.nested) out[p.name] = ownValue(p);
  return out;
}

export function SiteConfigConsole({ client, path, onPath: go, replication }: Props) {
  const navigate = useNavigate();
  const tab = useTab();
  const onPath = go ?? ((p: string) => navigate({ kind: 'siteconfig', path: p }));
  useScreenTitle('Site configurations', 'Site configurations | Sensible Studio');
  const [typed, setTyped] = useState(path);
  const [list, setList] = useState<ConfigList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<ConfigDetail | null>(null);

  useEffect(() => setTyped(path), [path]);

  const load = useCallback(() => {
    client.list(path).then(
      (l) => {
        setList(l);
        setError(null);
        setSelected((s) => (s && l.configs.some((c) => c.name === s) ? s : (l.configs.find((c) => c.set)?.name ?? l.configs[0]?.name ?? null)));
      },
      (e: unknown) => {
        setList(null);
        setError(e instanceof Error ? e.message : String(e));
      },
    );
  }, [client, path]);
  useEffect(load, [load]);

  useEffect(() => {
    setDetail(null);
    if (!selected || !list) return;
    let live = true;
    client.get(path, selected).then(
      (d) => live && setDetail(d),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, path, selected, list]);

  const saved = (d: ConfigDetail, what: string) => {
    setDetail(d);
    load();
    ToastQueue.positive(what, { timeout: 3000 });
  };

  return (
    <div className="studio-sites studio-siteconfig">
      <ToolBar
        url={SITECONFIG_URL}
        onClose={() => tab ? tab.leave({ kind: 'start' }) : navigate('/studio/start.html#tools')}
        closeLabel="Close Site configurations"
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Refresh</Tooltip>
          </TooltipTrigger>
        }
      />
      <main className="studio-siteconfig__main">
        <aside className="studio-siteconfig__side" aria-label="Where and which">
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (typed.trim()) onPath(typed.trim().replace(/\/+$/, '') || '/content');
            }}
          >
            <TextField label="For the content at" description="A page or a site root; Enter to go" width="100%" value={typed} onChange={setTyped} />
          </form>
          {list && (
            <p className="studio-siteconfig__context">
              {list.contextPath ? (
                <>
                  Belongs to the site at <code>{list.contextPath}</code>
                  {list.contextPaths.length > 1 && <> (inside {list.contextPaths.slice(1).map((c) => c).join(', ')})</>}. What you set applies to it and everything below.
                </>
              ) : (
                <>No site configuration applies here: none of its ancestors names one (<code>sling:configRef</code>). The values shown are the global ones and the defaults; saving is not possible.</>
              )}
            </p>
          )}
          {error && <p className="studio-siteconfig__error">{error}</p>}
          {list && list.configs.length === 0 && <p className="studio-siteconfig__muted">No bundle of this instance defines a context-aware configuration.</p>}
          {list && list.configs.length > 0 && (
            <ul className="studio-siteconfig__list" aria-label="Configurations">
              {list.configs.map((c) => (
                <li key={c.name}>
                  <button type="button" className={c.name === selected ? 'is-current' : undefined} aria-current={c.name === selected} onClick={() => setSelected(c.name)} title={c.name}>
                    <span className="studio-siteconfig__name">{c.label}</span>
                    <span className={`studio-siteconfig__state${c.set ? ' is-set' : ''}`}>{c.set ? (c.collection ? `${c.items} here` : 'set here') : c.inherited ? 'inherited' : c.collection && c.items ? `${c.items} inherited` : 'defaults'}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </aside>
        <section className="studio-siteconfig__form" aria-label="Configuration">
          {!detail && selected && !error && <ProgressCircle size="S" isIndeterminate aria-label="Loading" />}
          {detail && !detail.collection && <Singleton key={`${path}|${detail.name}`} client={client} detail={detail} canSave={!!list?.contextPath} onSaved={saved} replication={replication} />}
          {detail && detail.collection && <Collection key={`${path}|${detail.name}`} client={client} detail={detail} canSave={!!list?.contextPath} onSaved={saved} replication={replication} />}
        </section>
      </main>
    </div>
  );
}

function Head({ detail }: { detail: ConfigDetail }) {
  return (
    <header className="studio-siteconfig__head">
      <h2>{detail.label}</h2>
      <code>{detail.name}</code>
      {detail.description && <p>{detail.description}</p>}
      <p className="studio-siteconfig__muted">
        {detail.resourcePath ? (
          <>
            {detail.inherited ? 'Inherited from' : 'Stored at'} <code>{detail.resourcePath}</code>
            {detail.overridden ? ' — some values are overridden by the instance (an override provider)' : ''}
          </>
        ) : (
          'Nothing is stored for this site: the defaults apply.'
        )}
      </p>
    </header>
  );
}

/**
 * Whether what this site sets is on the live site, with Publish and Unpublish. The live site reads its own
 * copy of /conf: a configuration saved here changes nothing there until it is published.
 */
function Live({ replication, detail, dirty }: { replication?: Publishing; detail: ConfigDetail; dirty?: boolean }) {
  const own = detail.resourcePath && !detail.inherited ? detail.resourcePath : null;
  const [state, setState] = useState<{ published: boolean; activated: boolean; pending: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    setState(null);
    if (!replication || !own) return;
    let live = true;
    replication.status([own]).then(
      (s) => live && setState({ published: !!s[0]?.published, activated: !!s[0]?.activated, pending: !!s[0]?.pending }),
      () => live && setState(null),
    );
    return () => {
      live = false;
    };
  }, [replication, own, detail]);
  // While it travels, ask again: "On its way" stayed on the screen long after it had arrived. Not for ever:
  // a target that is down keeps it on its way for as long as it is down.
  const pending = !!state?.pending;
  const asked = useRef(0);
  useEffect(() => {
    if (!pending) asked.current = 0;
    if (!replication || !own || !pending || asked.current >= 15) return;
    let live = true;
    const timer = setTimeout(() => {
      asked.current += 1;
      replication.status([own]).then(
        (s) => live && setState({ published: !!s[0]?.published, activated: !!s[0]?.activated, pending: !!s[0]?.pending }),
        () => undefined,
      );
    }, 2000);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [replication, own, pending, state]);
  if (!replication || !own) return null;
  const run = (work: Promise<Array<{ published: boolean; activated?: boolean; pending: boolean }>>, what: string) => {
    setBusy(true);
    work.then(
      (s) => {
        setBusy(false);
        setState({ published: !!s[0]?.published, activated: !!s[0]?.activated, pending: !!s[0]?.pending });
        ToastQueue.positive(what, { timeout: 3000 });
      },
      (e: unknown) => {
        setBusy(false);
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
      },
    );
  };
  const words = !state
    ? 'Checking the live site…'
    : state.pending
      ? state.activated
        ? 'On its way to the live site.'
        : 'Being taken off the live site.'
      : state.published
        ? 'On the live site as it is here.'
        : state.activated
          ? 'Changed since it was published: the live site still has the earlier values.'
          : 'Not published from this author. Publish for the live site to read these values.';
  return (
    <div className="studio-siteconfig__live" role="group" aria-label="Live site">
      <span className={`studio-siteconfig__livestate${state?.published ? ' is-live' : ''}`}>{words}</span>
      <Button variant="secondary" isDisabled={busy || !!dirty || !state || (state.published && !state.pending)} onPress={() => run(replication.activate([own]), `${detail.label} published`)}>
        Publish
      </Button>
      <Button variant="secondary" isDisabled={busy || !state || !state.activated} onPress={() => run(replication.deactivate([own]), `${detail.label} unpublished`)}>
        Unpublish
      </Button>
    </div>
  );
}

function Singleton({ client, detail, canSave, onSaved, replication }: { client: SiteConfigClient; detail: ConfigDetail; canSave: boolean; onSaved(d: ConfigDetail, what: string): void; replication?: Publishing }) {
  const [values, setValues] = useState<Values>(() => valuesOf(detail.properties));
  const [busy, setBusy] = useState(false);
  const dirty = useMemo(() => JSON.stringify(values) !== JSON.stringify(valuesOf(detail.properties)), [values, detail]);
  const run = (work: Promise<ConfigDetail>, what: string) => {
    setBusy(true);
    work.then(
      (d) => {
        setBusy(false);
        setValues(valuesOf(d.properties));
        onSaved(d, what);
      },
      (e: unknown) => {
        setBusy(false);
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
      },
    );
  };
  return (
    <>
      <Head detail={detail} />
      <Fields properties={detail.properties} values={values} onChange={setValues} />
      <div className="studio-siteconfig__buttons">
        <Button variant="accent" isDisabled={!canSave || busy || !dirty} onPress={() => run(client.save(detail.path, detail.name, values), `${detail.label} saved`)}>
          Save
        </Button>
        <DialogTrigger>
          <Button variant="secondary" isDisabled={!canSave || busy || !detail.resourcePath || !!detail.inherited}>
            Remove what this site sets
          </Button>
          <StudioConfirmDialog title={`Remove ${detail.label} from this site?`} variant="destructive" primaryActionLabel="Remove" cancelLabel="Cancel" onPrimaryAction={() => run(unpublished(replication, detail.resourcePath).then(() => client.remove(detail.path, detail.name)), `${detail.label}: this site's values removed`)}>
            The values set for {detail.contextPath} are removed, on the live site too if they were published; what the sites above set, or the defaults, apply again.
          </StudioConfirmDialog>
        </DialogTrigger>
      </div>
      <Live replication={replication} detail={detail} dirty={dirty} />
    </>
  );
}

/** Takes a published configuration off the live site before it is removed here; nothing to do when it was never published. */
async function unpublished(replication: Publishing | undefined, resourcePath: string | null | undefined): Promise<void> {
  if (!replication || !resourcePath) return;
  const s = await replication.status([resourcePath]);
  if (s[0]?.activated) await replication.deactivate([resourcePath]);
}

function Collection({ client, detail, canSave, onSaved, replication }: { client: SiteConfigClient; detail: ConfigDetail; canSave: boolean; onSaved(d: ConfigDetail, what: string): void; replication?: Publishing }) {
  const initial = () => (detail.items ?? []).map((i) => ({ name: i.name, values: valuesOf(i.properties), properties: i.properties }));
  const [items, setItems] = useState(initial);
  const [busy, setBusy] = useState(false);
  const save = () => {
    setBusy(true);
    client.saveItems(detail.path, detail.name, items.map((i, k) => ({ name: i.name.trim() || `item${k}`, values: i.values }))).then(
      (d) => {
        setBusy(false);
        setItems((d.items ?? []).map((i) => ({ name: i.name, values: valuesOf(i.properties), properties: i.properties })));
        onSaved(d, `${detail.label} saved`);
      },
      (e: unknown) => {
        setBusy(false);
        ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
      },
    );
  };
  return (
    <>
      <Head detail={detail} />
      {items.length === 0 && <p className="studio-siteconfig__muted">No items.</p>}
      {items.map((item, k) => (
        <fieldset key={k} className="studio-siteconfig__item">
          <div className="studio-siteconfig__itemhead">
            <TextField label="Item name" value={item.name} onChange={(name) => setItems((all) => all.map((x, i) => (i === k ? { ...x, name } : x)))} />
            <TooltipTrigger delay={400}>
              <ActionButton isQuiet aria-label={`Remove item ${item.name}`} onPress={() => setItems((all) => all.filter((_, i) => i !== k))}>
                <Close />
              </ActionButton>
              <Tooltip>Remove this item</Tooltip>
            </TooltipTrigger>
          </div>
          <Fields properties={item.properties} values={item.values} onChange={(values) => setItems((all) => all.map((x, i) => (i === k ? { ...x, values } : x)))} />
        </fieldset>
      ))}
      <div className="studio-siteconfig__buttons">
        <Button variant="secondary" onPress={() => setItems((all) => [...all, { name: `item${all.length}`, values: {}, properties: detail.properties }])}>
          <Add />
          <Text>Add an item</Text>
        </Button>
        <Button variant="accent" isDisabled={!canSave || busy} onPress={save}>
          Save
        </Button>
      </div>
      <Live replication={replication} detail={detail} />
    </>
  );
}

/** One field per property; empty means "not set here", and the field says what applies instead. */
function Fields({ properties, values, onChange }: { properties: ConfigProperty[]; values: Values; onChange(values: Values): void }) {
  const set = (name: string, v: Value) => onChange({ ...values, [name]: v });
  return (
    <div className="studio-siteconfig__fields">
      {properties.map((p) => {
        const v = values[p.name] ?? null;
        const applies = p.effectiveValue !== null && show(p.effectiveValue) !== '' ? `${p.inherited ? 'Inherited' : 'Default'}: ${show(v === null ? p.effectiveValue : p.inherited ? p.effectiveValue : p.default)}` : 'Not set';
        const hint = [p.description, v === null ? applies : p.default !== null && show(p.default) !== '' ? `Default: ${show(p.default)}` : null].filter(Boolean).join(' · ');
        if (p.nested) {
          return (
            <p key={p.name} className="studio-siteconfig__muted">
              {p.label}: a nested configuration, edited in the repository ({p.sourcePath ?? 'not stored'}).
            </p>
          );
        }
        if (p.multiple) {
          const list = Array.isArray(v) ? v : v === null ? [] : [v];
          return (
            <div key={p.name} className="studio-siteconfig__multi" role="group" aria-label={p.label}>
              <span className="studio-siteconfig__label">{p.label}</span>
              {list.map((one, i) => (
                <div key={i} className="studio-siteconfig__row">
                  <TextField aria-label={`${p.label} ${i + 1}`} width="size-4600" value={String(one)} onChange={(s) => set(p.name, list.map((x, j) => (j === i ? coerce(s, p.type) : x)) as Scalar[])} />
                  <ActionButton isQuiet aria-label={`Remove ${p.label} ${i + 1}`} onPress={() => set(p.name, list.length === 1 ? null : (list.filter((_, j) => j !== i) as Scalar[]))}>
                    <Close size="S" />
                  </ActionButton>
                </div>
              ))}
              <ActionButton isQuiet onPress={() => set(p.name, [...list, p.type === 'boolean' ? false : ''] as Scalar[])}>
                <Add size="S" />
                <Text>Add</Text>
              </ActionButton>
              <span className="studio-siteconfig__hint">{hint}</span>
            </div>
          );
        }
        if (p.type === 'boolean') {
          const on = v === null ? p.effectiveValue === true : v === true;
          return (
            <div key={p.name} className="studio-siteconfig__bool">
              <Switch isSelected={on} onChange={(b) => set(p.name, b)}>
                {p.label}
              </Switch>
              {v !== null && (
                <ActionButton isQuiet aria-label={`Do not set ${p.label} here`} onPress={() => set(p.name, null)}>
                  <Close size="S" />
                  <Text>Not set here</Text>
                </ActionButton>
              )}
              <span className="studio-siteconfig__hint">{hint}</span>
            </div>
          );
        }
        if (p.type === 'integer' || p.type === 'number') {
          return (
            <NumberField
              key={p.name}
              label={p.label}
              description={hint}
              width="size-3000"
              hideStepper
              formatOptions={p.type === 'integer' ? { maximumFractionDigits: 0, useGrouping: false } : { useGrouping: false }}
              value={typeof v === 'number' ? v : Number.NaN}
              onChange={(n) => set(p.name, Number.isNaN(n) ? null : n)}
            />
          );
        }
        return <TextField key={p.name} label={p.label} description={hint} width="size-6000" value={v === null ? '' : String(v)} onChange={(s) => set(p.name, s === '' ? null : s)} />;
      })}
    </div>
  );
}

function coerce(s: string, type: ConfigProperty['type']): Scalar {
  if (type === 'boolean') return s === 'true';
  if ((type === 'integer' || type === 'number') && s.trim() !== '' && !Number.isNaN(Number(s))) return Number(s);
  return s;
}
