import { useCallback, useEffect, useRef, useState } from 'react';

const KEY = 'studio.sidepanel.width';
export const MIN_PANEL_WIDTH = 280;
const DEFAULT_WIDTH = 320;

/** The widest the panel may be: a little over half the window, the page keeps the rest. */
function maxWidth(): number {
  return Math.max(MIN_PANEL_WIDTH, Math.floor(window.innerWidth * 0.6) - 48);
}

function clamp(w: number): number {
  return Math.min(maxWidth(), Math.max(MIN_PANEL_WIDTH, Math.round(w)));
}

/**
 * The side panel's width, like AEM's resizable side panel: dragged at its right edge, remembered in
 * this browser. `onPointerDown` goes on the handle; the width goes on the panel.
 */
export function usePanelWidth(): { width: number; dragging: boolean; onPointerDown(e: React.PointerEvent<HTMLElement>): void; reset(): void } {
  const [width, setWidth] = useState(() => {
    try {
      const saved = Number(window.localStorage.getItem(KEY));
      return saved ? clamp(saved) : DEFAULT_WIDTH;
    } catch {
      return DEFAULT_WIDTH;
    }
  });
  const [dragging, setDragging] = useState(false);
  const start = useRef<{ x: number; width: number } | null>(null);

  useEffect(() => {
    const onResize = () => setWidth((w) => clamp(w));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const onPointerDown = useCallback(
    (e: React.PointerEvent<HTMLElement>) => {
      if (e.button !== 0) return;
      e.preventDefault();
      start.current = { x: e.clientX, width };
      setDragging(true);
      const move = (ev: PointerEvent) => {
        if (!start.current) return;
        setWidth(clamp(start.current.width + ev.clientX - start.current.x));
      };
      const up = () => {
        start.current = null;
        setDragging(false);
        window.removeEventListener('pointermove', move);
        window.removeEventListener('pointerup', up);
        setWidth((w) => {
          try {
            window.localStorage.setItem(KEY, String(w));
          } catch {
            // private window: the width lasts for this page
          }
          return w;
        });
      };
      window.addEventListener('pointermove', move);
      window.addEventListener('pointerup', up);
    },
    [width],
  );

  const reset = useCallback(() => {
    setWidth(DEFAULT_WIDTH);
    try {
      window.localStorage.removeItem(KEY);
    } catch {
      // ignore
    }
  }, []);

  return { width, dragging, onPointerDown, reset };
}
