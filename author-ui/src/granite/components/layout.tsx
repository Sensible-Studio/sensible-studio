import { useId, useState, useSyncExternalStore } from 'react';
import type { GraniteComponentProps } from '../registry';
import { useGranite, useRuleHiddenNames } from '../context';
import { GraniteItems, GraniteNodeView, isRendered, Unsupported } from '../render';
import { bool, child, items, num, str, titleOf } from '../node';

/** `cq/gui/components/authoring/dialog`: the dialog root; its UI lives under `content`. */
export function AuthoringDialog({ node }: GraniteComponentProps) {
  const content = child(node, 'content');
  if (!content) return <GraniteItems node={node} />;
  // Some dialogs leave `content` without a resource type; Granite treats it as a container.
  return content.properties['sling:resourceType'] ? <GraniteNodeView node={content} /> : <GraniteItems node={content} />;
}

/** `granite/ui/components/coral/foundation/container` */
export function Container({ node }: GraniteComponentProps) {
  return (
    <div className={`studio-g-container${bool(node, 'margin') ? ' studio-g-container--margin' : ''}`}>
      <GraniteItems node={node} />
    </div>
  );
}

/** `granite/ui/components/coral/foundation/fixedcolumns` */
export function FixedColumns({ node }: GraniteComponentProps) {
  const { registry, env } = useGranite();
  const columns = items(node).filter((c) => isRendered(c, registry, env));
  return (
    <div
      className={`studio-g-columns${bool(node, 'margin') ? ' studio-g-container--margin' : ''}`}
      style={{ gridTemplateColumns: `repeat(${Math.max(columns.length, 1)}, minmax(0, 1fr))` }}
    >
      {columns.map((c) => (
        <div className="studio-g-columns__column" key={c.name}>
          <GraniteNodeView node={c} />
        </div>
      ))}
    </div>
  );
}

/**
 * `granite/ui/components/coral/foundation/tabs`
 * All panels stay mounted (only hidden) so fields on inactive tabs are still submitted, like in Coral.
 */
export function Tabs({ node }: GraniteComponentProps) {
  const { registry, env } = useGranite();
  const rendered = items(node).filter((c) => isRendered(c, registry, env));
  // a panel a behaviour rule hides loses its tab as well (its fields keep their values)
  const hidden = useRuleHiddenNames(rendered);
  const panels = hidden.size ? rendered.filter((p) => !hidden.has(p.name)) : rendered;
  const [active, setActive] = useState(0);
  const uid = useId();
  const current = Math.min(active, Math.max(panels.length - 1, 0));
  if (panels.length === 0) return null;
  return (
    <div className="studio-g-tabs">
      <div className="studio-g-tabs__list" role="tablist">
        {panels.map((p, i) => (
          <button
            key={p.name}
            type="button"
            role="tab"
            id={`${uid}-tab-${p.name}`}
            aria-selected={i === current}
            aria-controls={`${uid}-panel-${p.name}`}
            className={`studio-g-tabs__tab${i === current ? ' is-active' : ''}`}
            onClick={() => setActive(i)}
          >
            {panelTitle(p)}
          </button>
        ))}
      </div>
      {panels.map((p, i) => (
        <div
          key={p.name}
          role="tabpanel"
          id={`${uid}-panel-${p.name}`}
          aria-labelledby={`${uid}-tab-${p.name}`}
          hidden={i !== current}
          className="studio-g-tabs__panel"
        >
          <GraniteNodeView node={p} />
        </div>
      ))}
    </div>
  );
}

/** `granite/ui/components/coral/foundation/accordion` — panels stay mounted, like tabs. */
export function Accordion({ node }: GraniteComponentProps) {
  const { registry, env } = useGranite();
  const panels = items(node).filter((c) => isRendered(c, registry, env));
  const [open, setOpen] = useState<Set<string>>(() => new Set(panels.slice(0, 1).map((p) => p.name)));
  const toggle = (name: string) =>
    setOpen((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  return (
    <div className="studio-g-accordion">
      {panels.map((p) => {
        const isOpen = open.has(p.name);
        return (
          <section key={p.name} className="studio-g-accordion__item">
            <button
              type="button"
              className="studio-g-accordion__header"
              aria-expanded={isOpen}
              onClick={() => toggle(p.name)}
            >
              <span className="studio-g-accordion__chevron" aria-hidden="true">
                {isOpen ? '▾' : '▸'}
              </span>
              {panelTitle(p)}
            </button>
            <div className="studio-g-accordion__panel" hidden={!isOpen}>
              <GraniteNodeView node={p} />
            </div>
          </section>
        );
      })}
    </div>
  );
}

/** `granite/ui/components/coral/foundation/well` */
export function Well({ node }: GraniteComponentProps) {
  return (
    <div className="studio-g-well">
      <GraniteItems node={node} />
    </div>
  );
}

/** `granite/ui/components/coral/foundation/form/fieldset` */
export function Fieldset({ node }: GraniteComponentProps) {
  const title = str(node, 'jcr:title');
  return (
    <fieldset className="studio-g-fieldset">
      {title && <legend className="studio-g-fieldset__legend">{title}</legend>}
      <GraniteItems node={node} />
    </fieldset>
  );
}

/** `granite/ui/components/coral/foundation/heading` */
export function Heading({ node }: GraniteComponentProps) {
  const level = Math.min(Math.max(num(node, 'level') ?? 3, 1), 6);
  const Tag = `h${level}` as 'h3';
  return <Tag className={`studio-g-heading studio-g-heading--${level}`}>{str(node, 'text')}</Tag>;
}

/** `granite/ui/components/coral/foundation/text` */
export function Text({ node }: GraniteComponentProps) {
  const text = str(node, 'text');
  return text ? <p className="studio-g-text">{text}</p> : null;
}

/** `granite/ui/components/coral/foundation/alert` */
export function Alert({ node }: GraniteComponentProps) {
  const variant = str(node, 'variant', 'info');
  const title = str(node, 'jcr:title');
  return (
    <div className={`studio-g-alert studio-g-alert--${variant}`} role="status">
      {title && <strong className="studio-g-alert__title">{title}</strong>}
      <span>{str(node, 'text')}</span>
    </div>
  );
}

/**
 * `granite/ui/components/coral/foundation/button` — dialog buttons trigger component specific client
 * libraries in AEM. Rendered, but inert, so authors see the dialog as designed.
 */
export function Button({ node }: GraniteComponentProps) {
  return (
    <button type="button" className="studio-g-button" disabled title="Action not available in Sensible Studio yet">
      {str(node, 'text') ?? node.name}
    </button>
  );
}

/** An include the server could not resolve (flagged with `studio:unresolvedInclude`). */
export function Include({ node }: GraniteComponentProps) {
  const path = str(node, 'studio:unresolvedInclude') ?? str(node, 'path') ?? '';
  return <Unsupported node={node} reason={explainUnresolvedInclude(path)} />;
}

/** Human explanation for the include targets we know Sensible Studio does not provide yet. */
export function explainUnresolvedInclude(path: string): string {
  if (path.includes('${')) return `Included per request in AEM (${path}); Sensible Studio does not evaluate expression paths yet.`;
  if (path.includes('/dialog/style/')) return 'AEM Style System tab: not provided by Sensible Studio yet.';
  if (path.includes('wcm/foundation/components/basicpage')) {
    return `Standard AEM page properties (${path.split('/').pop()}): not provided by Sensible Studio yet.`;
  }
  return `Included resource not found: ${path}`;
}

/** Tab/panel title; unresolved AEM style tabs get the name AEM shows for them. */
function panelTitle(node: GraniteComponentProps['node']): string {
  const title = titleOf(node);
  if (title) return title;
  const unresolved = str(node, 'studio:unresolvedInclude') ?? '';
  if (unresolved.includes('/dialog/style/')) return 'Styles';
  return node.name;
}

/**
 * `studio/granite/components/showwhen` (Sensible Studio): its items while field `studio:field` has value `studio:value`, nothing
 * otherwise (their fields are then not posted): what an editor script toggles, e.g. the options of the chosen
 * embeddable (see expand.ts).
 */
export function ShowWhen({ node }: GraniteComponentProps) {
  const { rootStore } = useGranite();
  useSyncExternalStore(rootStore.subscribe, rootStore.getVersion, rootStore.getVersion);
  const field = str(node, 'studio:field') ?? '';
  const value = str(node, 'studio:value') ?? '';
  if (String(rootStore.get(field) ?? '') !== value) return null;
  return <GraniteItems node={node} />;
}
