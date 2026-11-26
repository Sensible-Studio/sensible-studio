import type { InheritanceModel } from '../granite';
import type { MsmStatus } from './types';

/**
 * Which page properties of a live copy page are inherited, for the page properties dialog.
 *
 * Properties that rollouts do not update (the `contentUpdate` exclusions, by default every `jcr:`, most `cq:` and
 * `sling:` properties) are the live copy's own: editable, no chain. Child nodes (`/cq:featuredimage`) are always
 * synchronised. A suspended page, or a page added in the live copy, has no inherited fields.
 *
 * @param toggle cancels (`cancel` true) or re-enables the inheritance of a property or child node name
 */
export function pageInheritance(
  status: MsmStatus | null | undefined,
  toggle: (name: string, cancel: boolean) => Promise<void>,
): InheritanceModel | null {
  const rel = status?.relationship;
  if (!status?.inLiveCopy || !rel || !rel.inherited || rel.cancelled) return null;
  const excluded = (status.pageExclusions ?? []).flatMap((r) => {
    try {
      return [new RegExp(`^(?:${r})$`)];
    } catch {
      return [];
    }
  });
  const state = (key: string): 'inherited' | 'cancelled' | undefined => {
    const node = key.startsWith('/');
    const name = node ? key.slice(1) : key;
    if (!node && excluded.some((re) => re.test(name))) return undefined;
    return rel.cancelledProperties.includes(name) ? 'cancelled' : 'inherited';
  };
  return {
    state,
    toggle: (key) => toggle(key.replace(/^\//, ''), state(key) === 'inherited'),
  };
}
