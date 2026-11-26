import { useEffect, useState } from 'react';

/**
 * A counter that changes whenever the content frame may have moved its elements: scrolling, resizing,
 * DOM mutations, loaded images, CSS transitions. Overlay boxes are recomputed on each change.
 */
export function useFrameTick(doc: Document | null): number {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const win = doc?.defaultView;
    if (!doc || !win) return;
    let frame = 0;
    const bump = () => {
      if (frame) return;
      frame = win.requestAnimationFrame(() => {
        frame = 0;
        setTick((t) => t + 1);
      });
    };
    win.addEventListener('scroll', bump, { passive: true });
    win.addEventListener('resize', bump);
    doc.addEventListener('load', bump, true); // images
    doc.addEventListener('transitionend', bump, true);
    doc.addEventListener('animationend', bump, true);
    const mo = new MutationObserver(bump);
    mo.observe(doc.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['class', 'style'] });
    const ro = new ResizeObserver(bump);
    ro.observe(doc.body);
    // carousels and other script-driven motion that fires none of the above
    const interval = win.setInterval(bump, 800);
    return () => {
      if (frame) win.cancelAnimationFrame(frame);
      win.removeEventListener('scroll', bump);
      win.removeEventListener('resize', bump);
      doc.removeEventListener('load', bump, true);
      doc.removeEventListener('transitionend', bump, true);
      doc.removeEventListener('animationend', bump, true);
      mo.disconnect();
      ro.disconnect();
      win.clearInterval(interval);
    };
  }, [doc]);
  return tick;
}
