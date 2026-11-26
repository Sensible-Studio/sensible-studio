import { ActionButton, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronLeft from '@spectrum-icons/workflow/ChevronLeft';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Copy from '@spectrum-icons/workflow/Copy';
import { useNavigate } from './ShellContext';
import { HOME } from './tabs';
import { VOCAB } from './vocabulary';
import { Fragment, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';

export interface Crumb {
  path: string;
  title: string;
}

const STEP = 240;

/**
 * Where a console is, as a path in its own box: the ancestors are links back up, the current folder
 * last, then a button that copies its path. A path too long for the box is cut from the left so the
 * end — where you are — stays in view; ‹ › scroll it. It always starts at Home, the
 * same first step on every console.
 */
export function PathCrumbs({ crumbs, onGo, label = 'Location' }: { crumbs: Crumb[]; onGo(path: string): void; label?: string }) {
  const track = useRef<HTMLDivElement>(null);
  const go = useNavigate();
  const [edges, setEdges] = useState({ left: false, right: false });
  const current = crumbs[crumbs.length - 1];

  const measure = useCallback(() => {
    const el = track.current;
    if (!el) return;
    setEdges({ left: el.scrollLeft > 1, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 1 });
  }, []);

  // a new path: show its end
  useLayoutEffect(() => {
    const el = track.current;
    if (!el) return;
    el.scrollLeft = el.scrollWidth;
    measure();
  }, [crumbs, measure]);

  useEffect(() => {
    const el = track.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [measure]);

  const scrollBy = (dx: number) => {
    const el = track.current;
    if (!el) return;
    el.scrollBy({ left: dx, behavior: 'smooth' });
    window.setTimeout(measure, 350);
  };

  const copy = async () => {
    if (!current) return;
    try {
      await navigator.clipboard.writeText(current.path);
      ToastQueue.positive(`Copied ${current.path}`, { timeout: 2500 });
    } catch {
      ToastQueue.negative('The path could not be copied', { timeout: 4000 });
    }
  };

  return (
    <nav className="studio-crumbs" aria-label={label}>
      <button type="button" className="studio-crumbs__link studio-crumbs__home" onClick={() => go(HOME)}>
        {VOCAB.home}
      </button>
      {edges.left && (
        <button type="button" className="studio-crumbs__scroll" aria-label="Scroll the path left" onClick={() => scrollBy(-STEP)}>
          <ChevronLeft size="XS" />
        </button>
      )}
      <div ref={track} className={`studio-crumbs__track${edges.left ? ' is-cut-left' : ''}${edges.right ? ' is-cut-right' : ''}`} onScroll={measure}>
        {crumbs.map((c, i) => {
          const last = i === crumbs.length - 1;
          return (
            <Fragment key={c.path}>
              <span className="studio-crumbs__sep" aria-hidden>
                ›
              </span>
              {last ? (
                <span className="studio-crumbs__current" aria-current="page" title={c.path}>
                  {c.title}
                </span>
              ) : (
                <button type="button" className="studio-crumbs__link" title={c.path} onClick={() => onGo(c.path)}>
                  {c.title}
                </button>
              )}
            </Fragment>
          );
        })}
      </div>
      {edges.right && (
        <button type="button" className="studio-crumbs__scroll" aria-label="Scroll the path right" onClick={() => scrollBy(STEP)}>
          <ChevronRight size="XS" />
        </button>
      )}
      {current && (
        <TooltipTrigger delay={600}>
          <ActionButton isQuiet aria-label="Copy path" UNSAFE_className="studio-crumbs__copy" onPress={() => void copy()}>
            <Copy size="S" />
          </ActionButton>
          <Tooltip>Copy path: {current.path}</Tooltip>
        </TooltipTrigger>
      )}
    </nav>
  );
}
