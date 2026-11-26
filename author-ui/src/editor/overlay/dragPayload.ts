import type { AssetInfo, ComponentInfo } from '../api/StudioClient';
import { ASSET_DRAG_TYPE } from '../../granite';

/**
 * What is being dragged from the side panel onto the page. HTML5 drag events do not expose their
 * data during `dragover`, so the side panel records the payload here when the drag starts.
 */
export type DragPayload = { kind: 'component'; component: ComponentInfo } | { kind: 'asset'; asset: AssetInfo };

let current: DragPayload | null = null;

export const MIME = 'application/x-studio-drag';

export function startDrag(e: React.DragEvent, payload: DragPayload, label: string) {
  current = payload;
  e.dataTransfer.effectAllowed = 'copy';
  e.dataTransfer.setData(MIME, payload.kind);
  e.dataTransfer.setData('text/plain', label);
  // Lets dialog fields (the image drop zone) accept the asset without knowing the editor.
  if (payload.kind === 'asset') e.dataTransfer.setData(ASSET_DRAG_TYPE, payload.asset.path);
}

export function endDrag() {
  current = null;
}

export function currentDrag(): DragPayload | null {
  return current;
}
