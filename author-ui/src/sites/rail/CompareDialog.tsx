import { ActionButton, Button, ButtonGroup, Content, Divider, Heading, Item, Picker, ProgressCircle, Switch, Tabs, TabList, TabPanels, useDialogContainer } from '@adobe/react-spectrum';
import { useEffect, useMemo, useRef, useState } from 'react';
import { currentPreviewUrl, diffWords, plainText, versionPreviewUrl, type DiffChange, type PageDiff, type SiteRailClient } from './api';
import { firstChange, highlight, shownOnPage, unhighlight, type Highlighted } from './highlight';
import { StudioDialog } from '../../shell/StudioDialog';

export interface VersionChoice {
  id: string;
  /** "1.2 · Before release" */
  label: string;
  time: string;
}

interface Props {
  rail: SiteRailClient;
  page: { path: string; title: string };
  versions: VersionChoice[];
  /** the version to start from */
  version: string;
}

/** How long a loaded page gets to finish its own scripts before it is compared. */
const SETTLE_MS = 1200;

const KIND: Record<DiffChange['kind'], string> = {
  added: 'Added',
  removed: 'Removed',
  changed: 'Changed',
  propertyAdded: 'Added',
  propertyRemoved: 'Removed',
};

/** "root/container/text" → "text (root › container)" */
function nodeLabel(c: DiffChange): string {
  if (!c.node) return 'Page properties';
  const parts = c.node.split('/');
  const name = parts[parts.length - 1];
  const type = c.resourceType ? c.resourceType.slice(c.resourceType.lastIndexOf('/') + 1) : '';
  return type && type !== name ? `${name} (${type})` : name;
}

function TextDiff({ before, after }: { before?: string; after?: string }) {
  const a = plainText(before);
  const b = plainText(after);
  return (
    <span className="studio-compare__text">
      {diffWords(a, b).map((p, i) =>
        p.type === 'same' ? (
          <span key={i}>{p.text}</span>
        ) : p.type === 'added' ? (
          <ins key={i}>{p.text}</ins>
        ) : (
          <del key={i}>{p.text}</del>
        ),
      )}
    </span>
  );
}

/**
 * Compare to Current: a version and the page as it is now, side by side as rendered pages, and the list of what changed
 * component by component (texts with the changed words marked).
 */
export function CompareDialog({ rail, page, versions, version }: Props) {
  const container = useDialogContainer();
  const [from, setFrom] = useState(version);
  const [view, setView] = useState('side');
  const [diff, setDiff] = useState<PageDiff | null>(null);
  const [error, setError] = useState<string | null>(null);
  const chosen = versions.find((v) => v.id === from);
  const [marks, setMarks] = useState(true);
  const [marked, setMarked] = useState<Highlighted | null>(null);
  // content changes that render nothing on either page (found while the pages are shown side by side)
  const [unseen, setUnseen] = useState<Set<DiffChange>>(new Set());
  const before = useRef<HTMLIFrameElement>(null);
  const after = useRef<HTMLIFrameElement>(null);
  // bumped by each frame's load (a new version, a tab shown again, a link followed inside a frame)
  const [loads, setLoads] = useState<[number, number]>([0, 0]);

  useEffect(() => {
    const a = before.current?.contentDocument;
    const b = after.current?.contentDocument;
    const ready = (d: Document) => d.readyState === 'complete' && d.URL !== 'about:blank';
    if (!a || !b || !ready(a) || !ready(b)) return;
    unhighlight(a);
    unhighlight(b);
    if (!marks) setMarked(null);
    // pages fill some texts in after loading (the signed-in user, say): let them settle first
    const t = window.setTimeout(() => {
      unhighlight(a);
      unhighlight(b);
      // read from the pages as they are, before anything is marked
      if (diff) setUnseen(new Set(diff.changes.filter((c) => !shownOnPage(c, a, b))));
      if (marks) {
        setMarked(highlight(a, b));
        for (const d of [a, b]) firstChange(d)?.scrollIntoView?.({ block: 'center' });
      }
    }, SETTLE_MS);
    return () => window.clearTimeout(t);
  }, [loads, marks, diff]);

  useEffect(() => {
    let live = true;
    setDiff(null);
    setUnseen(new Set());
    setError(null);
    rail.diff(page.path, from, 'current').then(
      (d) => live && setDiff(d),
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [rail, page.path, from]);

  // property changes of one component together
  const groups = useMemo(() => {
    const out = new Map<string, DiffChange[]>();
    for (const c of diff?.changes ?? []) {
      const list = out.get(c.node) ?? [];
      list.push(c);
      out.set(c.node, list);
    }
    return [...out.entries()];
  }, [diff]);

  return (
    <StudioDialog>
      <Heading>Compare to Current: {page.title}</Heading>
      <Divider />
      <Content>
        <div className="studio-compare__bar">
          <Picker
            label="Version"
            labelPosition="side"
            items={versions}
            selectedKey={from}
            onSelectionChange={(k) => k !== null && setFrom(String(k))}
            width="size-4600"
          >
            {(v) => <Item key={v.id} textValue={v.label}>{`${v.label} — ${new Date(v.time).toLocaleString()}`}</Item>}
          </Picker>
          <span className="studio-rail__muted">{diff ? `${diff.changes.length} change${diff.changes.length === 1 ? '' : 's'}` : ''}</span>
          {view === 'side' && (
            <>
              <Switch isSelected={marks} onChange={setMarks}>
                Highlight changes
              </Switch>
              {marks && marked && (
                <span className="studio-compare__legend" aria-live="polite">
                  {(['changed', 'added', 'removed'] as const).map((k) => (
                    <span key={k} className="studio-compare__legend-item">
                      <span className={`studio-compare__swatch studio-compare__swatch--${k}`} />
                      {marked[k]} {k}
                    </span>
                  ))}
                  {marked.revealed > 0 && (
                    <span className="studio-compare__legend-item" title="Closed accordion items and other tabs with changes inside are shown open">
                      <span className="studio-compare__swatch studio-compare__swatch--revealed" />
                      {marked.revealed} hidden part{marked.revealed === 1 ? '' : 's'} opened
                    </span>
                  )}
                </span>
              )}
              {unseen.size > 0 && (
                <ActionButton isQuiet onPress={() => setView('changes')} UNSAFE_className="studio-compare__unseenlink">
                  {unseen.size} change{unseen.size === 1 ? '' : 's'} not shown on the page
                </ActionButton>
              )}
            </>
          )}
        </div>
        <Tabs aria-label="Compare" selectedKey={view} onSelectionChange={(k) => setView(String(k))}>
          <TabList>
            <Item key="side">Side by Side</Item>
            <Item key="changes">Changes</Item>
          </TabList>
          <TabPanels>
            <Item key="side">
              <div className="studio-compare__side">
                <figure>
                  <figcaption>{chosen ? `Version ${chosen.label}` : 'Version'}</figcaption>
                  <iframe
                    ref={before}
                    key={from}
                    title={`${page.title}, version ${chosen?.label ?? ''}`}
                    src={versionPreviewUrl(page.path, from)}
                    onLoad={() => setLoads(([x, y]) => [x + 1, y])}
                  />
                </figure>
                <figure>
                  <figcaption>Current</figcaption>
                  <iframe
                    ref={after}
                    title={`${page.title}, current`}
                    src={currentPreviewUrl(page.path)}
                    onLoad={() => setLoads(([x, y]) => [x, y + 1])}
                  />
                </figure>
              </div>
            </Item>
            <Item key="changes">
              {error ? (
                <p className="studio-rail__error" role="alert">
                  {error}
                </p>
              ) : !diff ? (
                <ProgressCircle isIndeterminate aria-label="Comparing" />
              ) : groups.length === 0 ? (
                <p className="studio-restore__empty">The content is the same as in this version.</p>
              ) : (
                <ul className="studio-compare__changes" aria-label="Changes">
                  {groups.map(([node, list]) => {
                    const whole = list.find((c) => c.kind === 'added' || c.kind === 'removed');
                    return (
                      <li key={node || '.'} className={whole ? `is-${whole.kind}` : 'is-changed'}>
                        <div className="studio-compare__node">
                          <strong>{nodeLabel(list[0])}</strong>
                          <span className={`studio-compare__kind studio-compare__kind--${whole ? whole.kind : 'changed'}`}>{whole ? KIND[whole.kind] : 'Changed'}</span>
                          {list.every((c) => unseen.has(c)) && (
                            <span className="studio-compare__unseen" title="This change renders nothing on the page">
                              Not on the page
                            </span>
                          )}
                          {node && <span className="studio-rail__muted">{node}</span>}
                        </div>
                        {whole ? (
                          <dl className="studio-compare__props">
                            {Object.entries(whole.properties ?? {})
                              .filter(([k]) => !k.startsWith('sling:') && !k.startsWith('jcr:primaryType'))
                              .slice(0, 6)
                              .map(([k, v]) => (
                                <div key={k}>
                                  <dt>{k}</dt>
                                  <dd className={whole.kind === 'added' ? 'is-added' : 'is-removed'}>{plainText(v)}</dd>
                                </div>
                              ))}
                          </dl>
                        ) : (
                          <dl className="studio-compare__props">
                            {list.map((c) => (
                              <div key={c.property}>
                                <dt>
                                  {c.property}
                                  {c.kind !== 'changed' && <span className="studio-rail__muted"> ({KIND[c.kind].toLowerCase()})</span>}
                                </dt>
                                <dd>
                                  <TextDiff before={c.before} after={c.after} />
                                </dd>
                              </div>
                            ))}
                          </dl>
                        )}
                      </li>
                    );
                  })}
                </ul>
              )}
            </Item>
          </TabPanels>
        </Tabs>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={() => window.open(versionPreviewUrl(page.path, from), '_blank', 'noopener')}>
          Open Version
        </Button>
        <Button variant="accent" onPress={container.dismiss}>
          Done
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
