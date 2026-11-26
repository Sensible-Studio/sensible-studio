import { Checkbox, Radio, RadioGroup, useDialogContainer } from '@adobe/react-spectrum';
import { useState } from 'react';
import { curvePath, headOf } from '../editor/annotations/arrows';
import { StudioDialog } from '../shell/StudioDialog';
import { NOTE_COLORS, noteNumbers, timeLabel } from './annotations';
import type { AssetComment, CommentRegion } from './api';

export type NotesPlacement = 'beside' | 'below' | 'next-page';

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);

/** The SVG mark of a note on a picture of W × H pixels. */
export function markSvg(r: CommentRegion, color: string, W: number, H: number): string {
  const sw = Math.max(2, Math.round(Math.max(W, H) / 400));
  const stroke = `stroke="${esc(color)}" stroke-width="${sw}" fill="none" stroke-linecap="round" stroke-linejoin="round"`;
  if (r.shape === 'arrow' || r.shape === 'free') {
    const pts = (r.points ?? []).map(([x, y]) => ({ x: x * W, y: y * H }));
    if (pts.length < 2) return '';
    if (r.shape === 'free') return `<path d="${pts.map((p, i) => `${i ? 'L' : 'M'}${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')}" ${stroke}/>`;
    const head = headOf(pts, sw * 7);
    return `<path d="${curvePath(pts, sw * 5)}" ${stroke}/><polygon points="${head.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' ')}" fill="${esc(color)}"/>`;
  }
  const x = r.x * W;
  const y = r.y * H;
  const w = r.w * W;
  const h = r.h * H;
  return r.shape === 'ellipse'
    ? `<ellipse cx="${(x + w / 2).toFixed(1)}" cy="${(y + h / 2).toFixed(1)}" rx="${(w / 2).toFixed(1)}" ry="${(h / 2).toFixed(1)}" ${stroke}/>`
    : `<rect x="${x.toFixed(1)}" y="${y.toFixed(1)}" width="${w.toFixed(1)}" height="${h.toFixed(1)}" ${stroke}/>`;
}

/** Where a note's number goes: the start of an arrow or a line, the top left of a box. */
function badgeAt(r: CommentRegion): { x: number; y: number } {
  return r.points?.length ? { x: r.points[0][0], y: r.points[0][1] } : { x: r.x, y: r.y };
}

/**
 * The page to print (AEM's Print of annotations): the picture with the chosen notes drawn and numbered, then the
 * notes and comments as a list, beside the picture, below it or on the next page.
 */
export function printHtml({
  title,
  path,
  src,
  width,
  height,
  comments,
  placement,
  now = new Date(),
}: {
  title: string;
  path: string;
  src: string;
  width: number;
  height: number;
  comments: AssetComment[];
  placement: NotesPlacement;
  now?: Date;
}): string {
  const numbers = noteNumbers(comments);
  const notes = comments.filter((c) => c.region);
  const marks = notes.map((c) => markSvg(c.region!, c.color ?? NOTE_COLORS[0], width, height)).join('');
  const badges = notes
    .map((c) => {
      const a = badgeAt(c.region!);
      return `<span class="n" style="left:${(a.x * 100).toFixed(2)}%;top:${(a.y * 100).toFixed(2)}%;background:${esc(c.color ?? NOTE_COLORS[0])}">${numbers.get(c.id)}</span>`;
    })
    .join('');
  const items = comments
    .map(
      (c) =>
        `<li>${c.region ? `<span class="b" style="background:${esc(c.color ?? NOTE_COLORS[0])}">${numbers.get(c.id)}</span>` : '<span class="b c">•</span>'}<div><p>${esc(c.text)}</p><small>${esc(
          [c.author ?? '', new Date(c.created).toLocaleString(), c.region?.time !== undefined ? `at ${timeLabel(c.region.time)}` : ''].filter(Boolean).join(' · '),
        )}</small></div></li>`,
    )
    .join('');
  return `<!doctype html><html><head><meta charset="utf-8"><title>${esc(title)} · Annotations</title><style>
@page{margin:14mm}
body{font:12px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#222;margin:0}
header{margin-bottom:10px}h1{font-size:16px;margin:0}header small{color:#666}
.layout{display:flex;gap:16px;align-items:flex-start}.layout.below,.layout.next-page{display:block}
.pic{position:relative;display:inline-block;max-width:100%}.layout.beside .pic{max-width:64%}
.pic img{display:block;max-width:100%;max-height:170mm}.pic svg{position:absolute;inset:0;width:100%;height:100%}
.n{position:absolute;transform:translate(-50%,-50%);min-width:16px;height:16px;padding:0 3px;border-radius:8px;color:#fff;font:700 10px/16px sans-serif;text-align:center;-webkit-print-color-adjust:exact;print-color-adjust:exact}
ol{list-style:none;margin:0;padding:0;flex:1}.layout.below ol{margin-top:14px}.layout.next-page ol{break-before:page}
li{display:flex;gap:8px;padding:6px 0;border-top:1px solid #ddd;break-inside:avoid}li p{margin:0;white-space:pre-wrap}li small{color:#666}
.b{flex:none;min-width:16px;height:16px;border-radius:8px;color:#fff;font:700 10px/16px sans-serif;text-align:center;-webkit-print-color-adjust:exact;print-color-adjust:exact}.b.c{background:#999}
</style></head><body><header><h1>${esc(title)}</h1><small>${esc(path)} · printed ${esc(now.toLocaleString())}</small></header>
<div class="layout ${placement}"><div class="pic"><img src="${esc(src)}" alt=""><svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none">${marks}</svg>${badges}</div><ol>${items}</ol></div></body></html>`;
}

/** Loads a picture to know its size. */
function sizeOf(src: string): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve({ width: img.naturalWidth || 1000, height: img.naturalHeight || 1000 });
    img.onerror = () => reject(new Error('The picture did not load'));
    img.src = src;
  });
}

/** Prints the page from a hidden frame: the browser's Print, where "Save as PDF" makes the PDF. */
export async function printPage(html: string): Promise<void> {
  const frame = document.createElement('iframe');
  frame.setAttribute('aria-hidden', 'true');
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;visibility:hidden';
  document.body.appendChild(frame);
  const doc = frame.contentDocument!;
  doc.open();
  doc.write(html);
  doc.close();
  const img = doc.querySelector('img');
  if (img && !img.complete) await new Promise((r) => ((img.onload = r), (img.onerror = r)));
  frame.contentWindow!.focus();
  frame.contentWindow!.print();
  // the print dialog blocks until it closes in most browsers; leave a little time in the others
  window.setTimeout(() => frame.remove(), 60_000);
}

/** AEM's Print of annotations: which notes and comments, and where the list goes; then the browser's Print. */
export function PrintNotesDialog({
  title,
  path,
  src,
  comments,
  print = printPage,
}: {
  title: string;
  path: string;
  src: string;
  comments: AssetComment[];
  print?(html: string): Promise<void>;
}) {
  const dialog = useDialogContainer();
  const numbers = noteNumbers(comments);
  const [chosen, setChosen] = useState<Set<string>>(() => new Set(comments.filter((c) => c.region).map((c) => c.id)));
  const [placement, setPlacement] = useState<NotesPlacement>('beside');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toggle = (id: string, on: boolean) =>
    setChosen((s) => {
      const n = new Set(s);
      if (on) n.add(id);
      else n.delete(id);
      return n;
    });

  const go = async () => {
    setBusy(true);
    setError(null);
    try {
      const { width, height } = await sizeOf(src);
      const picked = comments.filter((c) => chosen.has(c.id));
      await print(printHtml({ title, path, src: new URL(src, window.location.href).href, width, height, comments: picked, placement }));
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog
      title="Print"
      subject={title}
      size="M"
      onCancel={dialog.dismiss}
      primaryLabel="Print"
      onPrimary={() => void go()}
      primaryDisabled={chosen.size === 0}
      busy={busy}
    >
      <p className="studio-assets__muted">The picture with the notes you choose, numbered, and their text. The browser's Print can save it as a PDF.</p>
      <div className="studio-assets__print-choices" role="group" aria-label="What to print">
        {[...comments].map((c) => (
          <Checkbox key={c.id} isSelected={chosen.has(c.id)} onChange={(on) => toggle(c.id, on)}>
            {c.region ? `${numbers.get(c.id)}. ` : 'Comment: '}
            {c.text.length > 80 ? `${c.text.slice(0, 80)}…` : c.text}
          </Checkbox>
        ))}
      </div>
      <RadioGroup label="The notes" value={placement} onChange={(v) => setPlacement(v as NotesPlacement)} orientation="horizontal">
        <Radio value="beside">Beside the picture</Radio>
        <Radio value="below">Below it</Radio>
        <Radio value="next-page">On the next page</Radio>
      </RadioGroup>
      {error && (
        <p className="studio-assets__dialog-error" role="alert">
          {error}
        </p>
      )}
    </StudioDialog>
  );
}
