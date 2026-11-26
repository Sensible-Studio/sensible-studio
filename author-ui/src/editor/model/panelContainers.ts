/**
 * Panel containers (Core's Tabs, Accordion and Carousel), as Core's editor hooks register them for AEM's
 * `PanelContainer`: how the editor finds a container's panels in the page, which one is showing, and how it shows
 * another (AEM posts `cmp.panelcontainer` "navigate" messages to the component's script; Sensible Studio drives the
 * component's own controls, so the page shows exactly what a visitor would see).
 */

export type PanelContainerKind = 'tabs' | 'accordion' | 'carousel';

interface PanelContainerType {
  kind: PanelContainerKind;
  /** The component's element, `[data-panelcontainer=...]` (Core's `wrapperSelector`). */
  wrapper: string;
  /** The panels (Core's `itemSelector`). */
  item: string;
  /** The panel showing (Core's `itemActiveSelector`, relative to the panel). */
  isActive(panel: HTMLElement): boolean;
  /** The control that shows a panel. */
  control: string;
}

const TYPES: PanelContainerType[] = [
  {
    kind: 'tabs',
    wrapper: '[data-panelcontainer="tabs"]',
    item: "[data-cmp-hook-tabs='tabpanel']",
    isActive: (p) => p.classList.contains('cmp-tabs__tabpanel--active'),
    control: "[data-cmp-hook-tabs='tab']",
  },
  {
    kind: 'accordion',
    wrapper: '[data-panelcontainer="accordion"]',
    item: "[data-cmp-hook-accordion='panel']",
    isActive: (p) => !!p.closest("[data-cmp-hook-accordion='item']")?.hasAttribute('data-cmp-expanded'),
    control: "[data-cmp-hook-accordion='button']",
  },
  {
    kind: 'carousel',
    wrapper: '[data-panelcontainer="carousel"]',
    item: "[data-cmp-hook-carousel='item']",
    isActive: (p) => p.classList.contains('cmp-carousel__item--active'),
    control: "[data-cmp-hook-carousel='indicator']",
  },
];

export interface PanelContainer {
  kind: PanelContainerKind;
  /** The `[data-panelcontainer]` element. */
  el: HTMLElement;
}

/** The panel container a component renders (its decoration element, or the element right inside it), if any. */
export function panelContainerOf(componentEl: HTMLElement): PanelContainer | null {
  for (const t of TYPES) {
    if (componentEl.matches(t.wrapper)) return { kind: t.kind, el: componentEl };
    for (const c of Array.from(componentEl.children)) if (c.matches(t.wrapper)) return { kind: t.kind, el: c as HTMLElement };
  }
  return null;
}

const typeOf = (c: PanelContainer) => TYPES.find((t) => t.kind === c.kind)!;

/** Elements of this container only (not of panel containers nested in its panels). */
function own(c: PanelContainer, selector: string): HTMLElement[] {
  return Array.from(c.el.querySelectorAll<HTMLElement>(selector)).filter((x) => x.closest('[data-panelcontainer]') === c.el);
}

export function panels(c: PanelContainer): HTMLElement[] {
  return own(c, typeOf(c).item);
}

/** Index of the panel showing (the first one, for an accordion with several expanded), 0 when none is. */
export function activeIndex(c: PanelContainer): number {
  const t = typeOf(c);
  const i = panels(c).findIndex((p) => t.isActive(p));
  return i < 0 ? 0 : i;
}

/** Shows the panel at `index`, as a visitor would: the tab, the accordion item's button, the carousel indicator. */
export function navigate(c: PanelContainer, index: number): boolean {
  const t = typeOf(c);
  const list = panels(c);
  const panel = list[index];
  if (!panel) return false;
  if (t.isActive(panel)) return true;
  if (c.kind === 'accordion') {
    const item = panel.closest<HTMLElement>("[data-cmp-hook-accordion='item']");
    const button = item?.querySelector<HTMLElement>(t.control);
    if (!button) return false;
    button.click();
    return true;
  }
  const control = own(c, t.control)[index];
  if (!control) return false;
  control.click();
  return true;
}

/**
 * Shows an element that sits inside panel containers, as Core's content-tree hook does when a component in a hidden
 * panel is selected: every panel container around it is navigated to the panel holding it, outermost first.
 * Returns whether any panel changed.
 */
export function revealInPanels(el: HTMLElement): boolean {
  const containers: PanelContainer[] = [];
  for (let p = el.parentElement; p; p = p.parentElement) {
    if (p.matches('[data-panelcontainer]')) {
      const kind = TYPES.find((t) => p!.matches(t.wrapper))?.kind;
      if (kind) containers.unshift({ kind, el: p });
    }
  }
  let changed = false;
  for (const c of containers) {
    const i = panels(c).findIndex((p) => p.contains(el));
    if (i < 0 || i === activeIndex(c)) continue;
    if (navigate(c, i)) changed = true;
  }
  return changed;
}
