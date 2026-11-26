import { ActionButton, Button, Item, Picker, Text, TextField, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Close from '@spectrum-icons/workflow/Close';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useRef, useState } from 'react';
import { emulatedDevice, emulateDevice, HUB_DEVICES, readStores, tagNames, writeStores, type ContextHubSettings, type Expression, type HubModeItem, type HubModule, type Persona, type Segment, type Stores, type TargetingClient } from './api';

interface Props {
  client: TargetingClient;
  pagePath: string;
  /** The page's window (same origin): told to re-read the stores after a change. */
  frame: () => Window | null;
  onClose(): void;
  /** The Device Emulator module chose a device: the page is shown at its size (null: this browser's again). */
  onDevice?(size: { width: number; height: number } | null): void;
}

type Mode = NonNullable<ContextHubSettings['modes']>[number];
/** What the page's ContextHub script offers the bar (same origin). */
type Hub = { reload(): void; stores(): Stores; evaluate?(e: Expression | null): boolean; _template?(template: string, mapping?: Record<string, string> | null): string[] };

type Field = { store: string; property: string; label: string; options?: string[]; number?: boolean };

/** The bar's fields when the configuration says nothing (and while it is read). */
const FIELDS: Field[] = [
  { store: 'profile', property: 'givenName', label: 'First name' },
  { store: 'profile', property: 'age', label: 'Age' },
  { store: 'profile', property: 'gender', label: 'Gender', options: ['', 'female', 'male', 'other'] },
  { store: 'profile', property: 'interests', label: 'Interests' },
  { store: 'profile', property: 'loyalty', label: 'Loyalty', options: ['', 'none', 'member', 'gold', 'platinum'] },
  { store: 'geolocation', property: 'country', label: 'Country' },
  { store: 'geolocation', property: 'city', label: 'City' },
  { store: 'geolocation', property: 'latitude', label: 'Latitude' },
  { store: 'geolocation', property: 'longitude', label: 'Longitude' },
  { store: 'surferinfo', property: 'device', label: 'Device', options: ['', 'desktop', 'tablet', 'mobile'] },
  { store: 'surferinfo', property: 'month', label: 'Month' },
];

/** A field of the configuration (AEM's UI module) as the bar shows it. */
function fieldOf(m: HubModule): Field {
  return { store: m.store, property: m.property, label: m.title, number: m.type === 'number', ...(m.type === 'select' ? { options: ['', ...(m.options ?? [])] } : {}) };
}

/**
 * AEM's ContextHub bar in Preview: pick a persona or set store properties by hand and see which
 * experiences the page resolves. The stores live in the browser's localStorage, which the page's
 * ContextHub script reads (the editor and the page share the origin).
 */
export function ContextHubBar({ client, pagePath, frame, onClose, onDevice }: Props) {
  const [personas, setPersonas] = useState<Persona[]>([]);
  const [stores, setStores] = useState<Stores>(() => readStores());
  const [resolved, setResolved] = useState<string[]>([]);
  const [fields, setFields] = useState<Field[]>(FIELDS);
  /** AEM's UI modes of the configuration (Persona, Device…), beside the fields */
  const [modes, setModes] = useState<Mode[]>([]);
  const [modeName, setModeName] = useState('');
  const [segments, setSegments] = useState<Segment[] | null>(null);

  useEffect(() => {
    client.personas(pagePath).then(setPersonas, () => setPersonas([]));
    // the fields of the bar: the site's ContextHub configuration (Tools › ContextHub), of its enabled stores
    client.contexthub(pagePath).then(
      (c) => {
        const on = new Set(c.stores.filter((s) => s.enabled).map((s) => s.name));
        setFields(c.modules.filter((m) => on.has(m.store)).map(fieldOf));
        const withItems = (c.modes ?? []).filter((m) => m.items?.length);
        setModes(withItems);
        // Resolved Segments names the audiences the visitor is in: asked for only when a mode shows them
        if (withItems.some((m) => m.items!.some((i) => i.type === 'granite.resolvedsegments'))) client.segments(pagePath).then((r) => setSegments(r.segments), () => setSegments([]));
      },
      () => setFields(FIELDS),
    );
  }, [client, pagePath]);

  const hub = useCallback(() => (frame() as (Window & { StudioContextHub?: Hub }) | null)?.StudioContextHub ?? null, [frame]);

  /**
   * A page without personalized components does not load ContextHub's script, and AEM's modules need it
   * (the audiences a visitor is in, a module's template): the bar loads it into the page then. It only
   * resolves the page's targets, of which there are none.
   */
  const [, setHubReady] = useState('');
  const frameRef = useRef(frame);
  frameRef.current = frame;
  useEffect(() => {
    if (!modes.length) return;
    const ensure = () => {
      const w = frameRef.current() as (Window & { StudioContextHub?: Hub }) | null;
      let doc: Document | null = null;
      try {
        doc = w?.document ?? null;
      } catch {
        doc = null;
      }
      if (!w || !doc?.head || w.StudioContextHub) return setHubReady(w?.StudioContextHub ? `${doc?.URL}` : '');
      if (doc.querySelector('script[data-studio-contexthub-bar]')) return;
      const script = doc.createElement('script');
      script.src = '/libs/studio/contexthub/contexthub.js';
      script.setAttribute('data-studio-contexthub-bar', '');
      script.onload = () => setHubReady(`${doc?.URL}`);
      doc.head.appendChild(script);
    };
    ensure();
    let el: Element | null = null;
    try {
      el = frameRef.current()?.frameElement ?? null;
    } catch {
      el = null;
    }
    el?.addEventListener('load', ensure);
    return () => el?.removeEventListener('load', ensure);
  }, [modes, pagePath]);

  const apply = (next: Stores) => {
    setStores(next);
    writeStores(next);
    const h = hub();
    if (h) h.reload();
    else frame()?.dispatchEvent(new Event('studio-contexthub-changed'));
    readResolved();
  };

  const readResolved = useCallback(() => {
    const doc = frame()?.document;
    if (!doc) return;
    window.setTimeout(() => {
      setResolved(Array.from(doc.querySelectorAll<HTMLElement>('.studio-target')).map((t) => t.getAttribute('data-studio-resolved') ?? 'default'));
    }, 50);
  }, [frame]);

  useEffect(() => {
    readResolved();
    const doc = frame()?.document;
    const h = () => readResolved();
    doc?.addEventListener('studio-contexthub-resolved', h);
    return () => doc?.removeEventListener('studio-contexthub-resolved', h);
  }, [frame, readResolved, pagePath]);

  const setValue = (store: string, property: string, raw: string) => {
    const current = stores[store];
    const values: Record<string, unknown> = { ...(current && typeof current === 'object' ? current : {}) };
    if (raw === '') delete values[property];
    else if (property === 'interests') values[property] = raw.split(',').map((s) => s.trim()).filter(Boolean);
    else if (/^-?\d+(\.\d+)?$/.test(raw) && (fields.find((f) => f.store === store && f.property === property)?.number || ['age', 'latitude', 'longitude', 'month', 'children'].includes(property)))
      values[property] = Number(raw);
    else values[property] = raw;
    const next: Stores = { ...stores, [store]: values };
    apply(next);
  };

  const valueOf = (store: string, property: string): string => {
    const s = stores[store];
    const v = s && typeof s === 'object' ? s[property] : undefined;
    return v === undefined || v === null ? '' : Array.isArray(v) ? v.join(', ') : String(v);
  };

  const persona = personas.find((p) => p.id === stores._persona);
  const mode = modes.find((m) => m.name === modeName);
  const device = emulatedDevice(stores);

  const emulate = (id: string, orientation: 'portrait' | 'landscape') => {
    const next = emulateDevice(stores, id, orientation);
    apply(next);
    const d = emulatedDevice(next);
    onDevice?.(d.width && d.height ? { width: d.width, height: d.height } : null);
  };

  /** One module of an AEM mode, by its type; the ones this bar does not know are named, greyed. */
  const moduleOf = (it: HubModeItem) => {
    const chip = (title: string, text: string, off = false) => (
      <span key={it.name} className={`studio-contexthub__module${off ? ' is-off' : ''}`} title={off ? `${it.type}: not shown in this bar` : undefined}>
        <strong>{title}</strong>
        {text && ` ${text}`}
      </span>
    );
    switch (it.type) {
      case 'granite.profile':
        return chip(it.title || 'Persona', persona?.title ?? 'none: choose one above');
      case 'contexthub.location':
        return (
          <span key={it.name} className="studio-contexthub__pair">
            <TextField label="Country" value={valueOf('geolocation', 'country')} onChange={(v) => setValue('geolocation', 'country', v)} width="size-1250" isQuiet />
            <TextField label="City" value={valueOf('geolocation', 'city')} onChange={(v) => setValue('geolocation', 'city', v)} width="size-1250" isQuiet />
          </span>
        );
      case 'granite.resolvedsegments': {
        const h = hub();
        const names = segments === null || !h?.evaluate ? null : segments.filter((s) => h.evaluate!(s.expression)).map((s) => s.title || s.name);
        return chip(it.title || 'Resolved Segments', names === null ? '…' : names.length ? names.join(', ') : 'none');
      }
      case 'contexthub.device':
        return (
          <Picker key={it.name} label={it.title || 'Device'} selectedKey={device.id} onSelectionChange={(k) => emulate(String(k), device.orientation)} width="size-1700" isQuiet>
            {HUB_DEVICES.map((d) => (
              <Item key={d.id}>{d.title}</Item>
            ))}
          </Picker>
        );
      case 'contexthub.screen-orientation':
        return (
          <Picker
            key={it.name}
            label={it.title || 'Orientation'}
            selectedKey={device.orientation}
            onSelectionChange={(k) => emulate(device.id, k === 'landscape' ? 'landscape' : 'portrait')}
            isDisabled={device.id === 'native' || device.id === 'desktop'}
            width="size-1250"
            isQuiet
          >
            <Item key="portrait">portrait</Item>
            <Item key="landscape">landscape</Item>
          </Picker>
        );
      case 'contexthub.browserinfo': {
        // what the page itself reads of the browser (the stored values hold only what was changed)
        const info = ((hub()?.stores().surferinfo ?? stores.surferinfo ?? {}) as Record<string, unknown>) ?? {};
        return chip(it.title || 'Browser', [info.browser, info.os].filter(Boolean).join(' · ') || '…');
      }
      case 'contexthub.datetime':
        return chip(it.title || 'Date & Time', new Date().toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }));
      case 'contexthub.tagcloud':
        return chip(it.title || 'Tags', tagNames(hub()?.stores().tagcloud ?? stores.tagcloud).join(', ') || 'none yet');
      case 'contexthub.base': {
        const lines = it.template ? (hub()?._template?.(it.template, it.storeMapping) ?? []) : [];
        return lines.length ? chip(lines[0], lines.slice(1).join(' · ')) : chip(it.title, '');
      }
      default:
        return chip(it.title, '', true);
    }
  };
  const targets = resolved.length;
  const personalised = resolved.filter((r) => r && r !== 'default').length;

  return (
    <div className="studio-contexthub" role="region" aria-label="Visitor simulator">
      <div className="studio-contexthub__row">
        <Picker
          aria-label="Persona"
          placeholder="Persona…"
          selectedKey={stores._persona ?? ''}
          onSelectionChange={(k) => {
            const p = personas.find((x) => x.id === k);
            if (!p) return;
            const next: Stores = { _persona: p.id };
            for (const [store, values] of Object.entries(p.stores)) next[store] = { ...values };
            apply(next);
          }}
          width="size-2400"
        >
          {personas.map((p) => (
            <Item key={p.id} textValue={p.title}>
              <Text>{p.title}</Text>
              {p.description && <Text slot="description">{p.description}</Text>}
            </Item>
          ))}
        </Picker>
        <span className="studio-contexthub__status">
          {targets === 0 ? 'No personalized components on this page' : `${personalised} of ${targets} personalized component${targets === 1 ? '' : 's'} personalised${persona ? ` for ${persona.title}` : ''}`}
        </span>
        <span className="studio-contexthub__spacer" />
        <Button
          variant="secondary"
          isQuiet
          onPress={() => {
            apply({ _persona: null });
            onDevice?.(null);
          }}
        >
          <Refresh />
          <Text>Reset</Text>
        </Button>
        <TooltipTrigger delay={400}>
          <ActionButton isQuiet aria-label="Close Visitor simulator" onPress={onClose}>
            <Close />
          </ActionButton>
          <Tooltip>Close</Tooltip>
        </TooltipTrigger>
      </div>
      {modes.length > 0 && (
        <div className="studio-contexthub__modes" role="tablist" aria-label="ContextHub modes">
          {[{ name: '', title: 'Fields' }, ...modes].map((m) => (
            <button key={m.name} type="button" role="tab" aria-selected={modeName === m.name} onClick={() => setModeName(m.name)}>
              {m.title}
            </button>
          ))}
        </div>
      )}
      {mode && <div className="studio-contexthub__row studio-contexthub__stores">{mode.items!.filter((i) => i.enabled !== false).map(moduleOf)}</div>}
      <div className="studio-contexthub__row studio-contexthub__stores" hidden={!!mode}>
        {!mode && fields.map((f) =>
          f.options ? (
            <Picker key={`${f.store}/${f.property}`} label={f.label} selectedKey={valueOf(f.store, f.property)} onSelectionChange={(k) => setValue(f.store, f.property, String(k))} width="size-1250" isQuiet>
              {f.options.map((o) => (
                <Item key={o}>{o || '—'}</Item>
              ))}
            </Picker>
          ) : (
            <TextField key={`${f.store}/${f.property}`} label={f.label} value={valueOf(f.store, f.property)} onChange={(v) => setValue(f.store, f.property, v)} width={f.property === 'interests' ? 'size-2400' : 'size-1250'} isQuiet />
          ),
        )}
      </div>
    </div>
  );
}
