import { ActionButton, Button, Item, Picker, TextField, ToastQueue } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import { useEffect, useState } from 'react';
import type { Redirect, Segment, TargetingClient } from './api';

/**
 * The page's audience redirects: visitors in an audience are sent to another page as soon as the page
 * loads (ContextHub's segment redirect), the matching rule with the highest boost first.
 */
export function RedirectsSection({ client, pagePath, segments }: { client: TargetingClient; pagePath: string; segments: Segment[] }) {
  const [saved, setSaved] = useState<Redirect[]>([]);
  const [rules, setRules] = useState<Redirect[]>([]);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    client.redirects(pagePath).then(
      (r) => {
        setSaved(r);
        setRules(r);
      },
      () => undefined,
    );
  }, [client, pagePath]);
  const dirty = JSON.stringify(rules) !== JSON.stringify(saved);
  const update = (i: number, patch: Partial<Redirect>) => setRules(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  const save = async () => {
    setBusy(true);
    try {
      const r = await client.setRedirects(pagePath, rules.filter((x) => x.segment && x.target.trim()));
      setSaved(r);
      setRules(r);
      ToastQueue.positive(r.length ? 'Audience redirects saved' : 'Audience redirects removed', { timeout: 3000 });
    } catch (e) {
      ToastQueue.negative(e instanceof Error ? e.message : String(e), { timeout: 6000 });
    } finally {
      setBusy(false);
    }
  };
  return (
    <section className="studio-targeting__section" aria-label="Audience redirects">
      <div className="studio-targeting__head">
        <h3>Audience redirects</h3>
        <ActionButton isQuiet aria-label="Add redirect" isDisabled={segments.length === 0} onPress={() => setRules([...rules, { segment: segments[0]?.path ?? '', target: '' }])}>
          <Add />
        </ActionButton>
      </div>
      {rules.length === 0 && <p className="studio-targeting__hint">Visitors of this page stay on it. A rule sends an audience to another page instead.</p>}
      <ul className="studio-targeting__redirects">
        {rules.map((r, i) => (
          <li key={i} className="studio-targeting__redirect">
            <Picker aria-label="Audience" selectedKey={r.segment} onSelectionChange={(k) => update(i, { segment: String(k) })} width="100%">
              {segments.map((s) => (
                <Item key={s.path}>{s.title}</Item>
              ))}
            </Picker>
            <div className="studio-targeting__redirect-target">
              <TextField aria-label="Target page" value={r.target} onChange={(t) => update(i, { target: t })} placeholder="/content/site/en/page" width="100%" />
              <ActionButton isQuiet aria-label="Remove redirect" onPress={() => setRules(rules.filter((_, j) => j !== i))}>
                <Delete />
              </ActionButton>
            </div>
          </li>
        ))}
      </ul>
      {(dirty || rules.length > 0) && (
        <div className="studio-targeting__buttons">
          <Button variant="accent" isDisabled={!dirty || busy} onPress={() => void save()}>
            Save redirects
          </Button>
        </div>
      )}
    </section>
  );
}
