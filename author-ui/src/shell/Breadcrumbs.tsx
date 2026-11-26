import { Fragment } from 'react';
import { titleOf, type Place } from './places';
import { Link } from './ShellContext';

interface Crumb {
  label: string;
  place?: Place;
}

/** Where the active tab is, as a path: each segment before the last is a place you can go back up to. */
export function crumbsOf(place: Place): Crumb[] {
  const sitesChain = (path: string, lastIsPage: boolean): Crumb[] => {
    const segments = path.replace(/^\/content\/?/, '').split('/').filter(Boolean);
    const out: Crumb[] = [{ label: 'Pages', place: { kind: 'sites', path: '/content' } }];
    let acc = '/content';
    segments.forEach((seg, i) => {
      acc += `/${seg}`;
      const last = i === segments.length - 1;
      out.push(last && lastIsPage ? { label: seg } : { label: seg, place: { kind: 'sites', path: acc } });
    });
    return out;
  };
  switch (place.kind) {
    case 'sites':
      return sitesChain(place.path, false).map((c, i, all) => (i === all.length - 1 ? { label: c.label } : c));
    case 'editor':
      if (place.path.startsWith('/content/')) return sitesChain(place.path, true);
      return place.path.split('/').filter(Boolean).slice(-3).map((s) => ({ label: s }));
    case 'page-properties':
      return [...sitesChain(place.item, false), { label: 'Properties' }];
    case 'templates': {
      const r = place.route;
      const console: Crumb = { label: 'Templates', place: { kind: 'templates', route: { screen: 'console', conf: null } } };
      const confCrumb = (conf: string): Crumb => ({ label: conf.split('/').pop()!, place: { kind: 'templates', route: { screen: 'console', conf } } });
      if (r.screen === 'console') return r.conf ? [console, { label: r.conf.split('/').pop()! }] : [{ label: 'Templates' }];
      if (r.screen === 'wizard') return [console, confCrumb(r.conf), { label: 'Create Template' }];
      const conf = r.template.replace(/\/settings\/wcm\/templates\/.*$/, '');
      return [console, confCrumb(conf), { label: r.template.split('/').pop()! }, { label: 'Properties' }];
    }
    default:
      return [{ label: titleOf(place) }];
  }
}

export function Breadcrumbs({ place }: { place: Place }) {
  const crumbs = crumbsOf(place);
  return (
    <nav className="studio-crumbs" aria-label="Location">
      {crumbs.map((c, i) => (
        <Fragment key={i}>
          {i > 0 && (
            <span className="studio-crumbs__sep" aria-hidden>
              ›
            </span>
          )}
          {c.place ? (
            <Link to={c.place} className="studio-crumbs__link">
              {c.label}
            </Link>
          ) : (
            <span className="studio-crumbs__current" aria-current="page">
              {c.label}
            </span>
          )}
        </Fragment>
      ))}
    </nav>
  );
}
