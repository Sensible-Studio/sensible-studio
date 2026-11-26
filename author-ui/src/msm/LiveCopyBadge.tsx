import LinkIcon from '@spectrum-icons/workflow/Link';
import Unlink from '@spectrum-icons/workflow/Unlink';

/** Small marker next to a page title in the Sites console: the page is (the root of) a live copy. */
export function LiveCopyBadge({ state, root }: { state?: 'inherited' | 'cancelled'; root?: boolean }) {
  if (!state) return null;
  const label = state === 'cancelled' ? 'Linked copy, suspended' : root ? 'Linked copy root' : 'Linked copy';
  return (
    <span className={`studio-msm-badge${state === 'cancelled' ? ' is-suspended' : ''}${root ? ' is-root' : ''}`} title={label} aria-label={label}>
      {state === 'cancelled' ? <Unlink size="XS" /> : <LinkIcon size="XS" />}
    </span>
  );
}
