import { Link } from './ShellContext';

export interface Segment<K extends string> {
  key: K;
  label: string;
  /** When set, the segment is a link to that place (a console of its own); otherwise a button. */
  to?: string;
}

/**
 * A segmented switch between a few views of a console — Users · Groups · Permissions, Running · Failures ·
 * Archive — for the console bar, next to the breadcrumb. One look for every console: segments that are
 * links (each view is its own console) and segments that are buttons (one console, several views) are
 * told apart only by their markup.
 */
export function Segmented<K extends string>({ label, items, value, onChange }: { label: string; items: Array<Segment<K>>; value: K; onChange?(key: K): void }) {
  const links = items.some((i) => i.to);
  return (
    <nav className="studio-segmented" aria-label={label} role={links ? undefined : 'radiogroup'}>
      {items.map((item) => {
        const current = item.key === value;
        const className = current ? 'is-current' : undefined;
        return item.to ? (
          <Link key={item.key} to={item.to} className={className} aria-current={current ? 'page' : undefined}>
            {item.label}
          </Link>
        ) : (
          <button key={item.key} type="button" role="radio" className={className} aria-checked={current} onClick={() => onChange?.(item.key)}>
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}
