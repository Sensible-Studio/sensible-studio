import { describe, expect, it } from 'vitest';
import { activeIndex, navigate, panelContainerOf, panels, revealInPanels } from './panelContainers';

/** Core's Tabs markup, with a stand-in for its script: a click on a tab shows its panel. */
function tabs(): HTMLElement {
  const host = document.createElement('div');
  host.innerHTML = `
    <div class="tabs panelcontainer" data-studio-path="/c/tabs">
      <div data-panelcontainer="tabs" class="cmp-tabs">
        <ol role="tablist">
          <li data-cmp-hook-tabs="tab" class="cmp-tabs__tab cmp-tabs__tab--active">A</li>
          <li data-cmp-hook-tabs="tab" class="cmp-tabs__tab">B</li>
        </ol>
        <div data-cmp-hook-tabs="tabpanel" class="cmp-tabs__tabpanel cmp-tabs__tabpanel--active">
          <div data-panelcontainer="tabs" class="cmp-tabs">
            <div data-cmp-hook-tabs="tabpanel" class="cmp-tabs__tabpanel cmp-tabs__tabpanel--active">nested</div>
          </div>
        </div>
        <div data-cmp-hook-tabs="tabpanel" class="cmp-tabs__tabpanel">b</div>
      </div>
    </div>`;
  const root = host.firstElementChild as HTMLElement;
  const outer = root.firstElementChild as HTMLElement;
  const own = (sel: string) => [...outer.querySelectorAll<HTMLElement>(sel)].filter((x) => x.closest('[data-panelcontainer]') === outer);
  own('[data-cmp-hook-tabs="tab"]').forEach((tab, i) =>
    tab.addEventListener('click', () => own('[data-cmp-hook-tabs="tabpanel"]').forEach((p, j) => p.classList.toggle('cmp-tabs__tabpanel--active', i === j))),
  );
  return root;
}

describe('panel containers', () => {
  it('finds the container of a component and its own panels', () => {
    const el = tabs();
    const pc = panelContainerOf(el)!;
    expect(pc.kind).toBe('tabs');
    expect(panels(pc)).toHaveLength(2);
    expect(activeIndex(pc)).toBe(0);
    expect(panelContainerOf(document.createElement('div'))).toBeNull();
  });

  it('shows a panel with the component’s own control', () => {
    const pc = panelContainerOf(tabs())!;
    expect(navigate(pc, 1)).toBe(true);
    expect(activeIndex(pc)).toBe(1);
    expect(navigate(pc, 5)).toBe(false);
  });

  it('expands an accordion item only when it is collapsed', () => {
    const host = document.createElement('div');
    host.innerHTML = `
      <div data-panelcontainer="accordion">
        <div data-cmp-hook-accordion="item" data-cmp-expanded><button data-cmp-hook-accordion="button">1</button><div data-cmp-hook-accordion="panel"></div></div>
        <div data-cmp-hook-accordion="item"><button data-cmp-hook-accordion="button">2</button><div data-cmp-hook-accordion="panel"></div></div>
      </div>`;
    const pc = panelContainerOf(host)!;
    let clicks = 0;
    host.querySelectorAll('button').forEach((b) =>
      b.addEventListener('click', () => {
        clicks++;
        b.parentElement!.setAttribute('data-cmp-expanded', '');
      }),
    );
    navigate(pc, 0);
    expect(clicks).toBe(0);
    navigate(pc, 1);
    expect(clicks).toBe(1);
  });

  it('reveals an element inside nested panel containers, outermost first', () => {
    const outer = tabs();
    const nested = panelContainerOf(outer)!;
    const inner = panels(nested)[0].querySelector<HTMLElement>('[data-panelcontainer]')!;
    // move the inner container into the second (hidden) outer panel; the element to reveal sits in its panel
    panels(nested)[1].appendChild(inner);
    const target = document.createElement('p');
    inner.querySelector("[data-cmp-hook-tabs='tabpanel']")!.appendChild(target);
    expect(activeIndex(nested)).toBe(0);
    expect(revealInPanels(target)).toBe(true);
    expect(activeIndex(nested)).toBe(1);
    expect(revealInPanels(target)).toBe(false);
  });
});
