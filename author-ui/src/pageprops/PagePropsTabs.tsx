import { useContext, useId, useState } from 'react';
import type { GraniteComponentProps } from '../granite/registry';
import { useGranite, useRuleHiddenNames } from '../granite/context';
import { GraniteNodeView, isRendered } from '../granite/render';
import { items, titleOf } from '../granite/node';
import { Tabs } from '../granite/components/layout';
import { ExtraTabsContext, NestedTabsContext } from './context';

interface TabEntry {
  name: string;
  title: string;
  actions?: React.ReactNode;
  render(): React.ReactNode;
}

/**
 * The page properties' tabs, laid out like AEM's properties page: centered tab names over a full-width rule, each
 * panel with its action bar on the left and its fields in a centered column. Besides the tabs of the page component's
 * dialog it shows Sensible Studio's own (Permissions, Blueprint, Live Copy). Panels stay mounted so every field is posted.
 */
export function PagePropsTabs(props: GraniteComponentProps) {
  const nested = useContext(NestedTabsContext);
  if (nested) return <Tabs {...props} />;
  return <TopTabs {...props} />;
}

function TopTabs({ node }: GraniteComponentProps) {
  const { registry, env } = useGranite();
  const extras = useContext(ExtraTabsContext);
  const rendered = items(node).filter((c) => isRendered(c, registry, env));
  const hidden = useRuleHiddenNames(rendered);
  const panels = hidden.size ? rendered.filter((p) => !hidden.has(p.name)) : rendered;
  const extra = (x: (typeof extras)[number]): TabEntry => ({ name: x.name, title: x.title, actions: x.actions, render: () => x.content });
  const entries: TabEntry[] = [];
  for (const p of panels) {
    entries.push({ name: p.name, title: titleOf(p) ?? p.name, render: () => <GraniteNodeView node={p} /> });
    entries.push(...extras.filter((x) => x.after === p.name).map(extra));
  }
  entries.push(...extras.filter((x) => !x.after || !panels.some((p) => p.name === x.after)).map(extra));
  const [active, setActive] = useState(entries[0]?.name ?? '');
  const uid = useId();
  const current = entries.some((e) => e.name === active) ? active : (entries[0]?.name ?? '');
  if (entries.length === 0) return null;
  return (
    <NestedTabsContext.Provider value={true}>
      <div className="studio-pp-tabs">
        <div className="studio-pp-tabs__list" role="tablist" aria-label="Page properties">
          {entries.map((e) => (
            <button
              key={e.name}
              type="button"
              role="tab"
              id={`${uid}-tab-${e.name}`}
              aria-selected={e.name === current}
              aria-controls={`${uid}-panel-${e.name}`}
              className={`studio-pp-tabs__tab${e.name === current ? ' is-active' : ''}`}
              onClick={() => setActive(e.name)}
            >
              {e.title}
            </button>
          ))}
        </div>
        {entries.map((e) => (
          <div
            key={e.name}
            role="tabpanel"
            id={`${uid}-panel-${e.name}`}
            aria-labelledby={`${uid}-tab-${e.name}`}
            hidden={e.name !== current}
            className="studio-pp-tabs__panel"
            data-tab={e.name}
          >
            {e.actions && <div className="studio-pp-actionbar">{e.actions}</div>}
            {e.render()}
          </div>
        ))}
      </div>
    </NestedTabsContext.Provider>
  );
}
