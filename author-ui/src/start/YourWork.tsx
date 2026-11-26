import { ActionButton, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import ChevronDown from '@spectrum-icons/workflow/ChevronDown';
import ChevronRight from '@spectrum-icons/workflow/ChevronRight';
import Close from '@spectrum-icons/workflow/Close';
import PinOff from '@spectrum-icons/workflow/PinOff';
import PinOn from '@spectrum-icons/workflow/PinOn';
import RailLeft from '@spectrum-icons/workflow/RailLeft';
import { useEffect, useState, type LiHTMLAttributes, type ReactNode } from 'react';
import { relativeTime } from '../editor/shell/TopBar';
import { Link } from '../shell/ShellContext';
import { forgetVisit, iconOfUrl, reorderPins, togglePin, unpin, useRecent, usePinned } from '../shell/recent';
import { dueText, projectUrl, type MyTask } from '../projects/api';
import { workflowUrl, type InboxEntry } from '../workflow/api';
import { INBOX_CHANGED } from '../workflow/InboxBell';
import { NavIcon } from './icons';

/**
 * Home's left rail: what you were doing (Recent, per browser) and what you keep at hand (Pinned, per
 * browser; ⌘K › Pin this screen, or the pin on a recent row; drag to reorder), then your Inbox: the workflow steps waiting for you and the project tasks assigned to you. Each section folds; the rail itself can
 * be hidden (a strip with one button brings it back). Folds and the rail are remembered per browser.
 */
const RAIL_KEY = 'studio.start.rail';
const FOLDS_KEY = 'studio.start.rail.folded';
const ROWS = 8;

type Section = 'recent' | 'pinned' | 'inbox';

function readRailHidden(): boolean {
  try {
    return window.localStorage.getItem(RAIL_KEY) === 'hidden';
  } catch {
    return false;
  }
}

export function readFolded(): Section[] {
  try {
    const v = JSON.parse(window.localStorage.getItem(FOLDS_KEY) ?? '[]') as unknown;
    return Array.isArray(v) ? (v.filter((x) => x === 'recent' || x === 'pinned' || x === 'inbox') as Section[]) : [];
  } catch {
    return [];
  }
}

export function storeFolded(folded: Section[]) {
  store(FOLDS_KEY, JSON.stringify(folded));
}

function store(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* per-viewer convenience only */
  }
}

export function WorkRail() {
  const [hidden, setHidden] = useState(readRailHidden);
  const [folded, setFolded] = useState<Section[]>(readFolded);

  const hide = (h: boolean) => {
    setHidden(h);
    store(RAIL_KEY, h ? 'hidden' : 'shown');
  };
  const fold = (s: Section) => {
    const next = folded.includes(s) ? folded.filter((x) => x !== s) : [...folded, s];
    setFolded(next);
    store(FOLDS_KEY, JSON.stringify(next));
  };

  if (hidden) {
    return (
      <div className="studio-work studio-work--hidden">
        <TooltipTrigger delay={600} placement="end">
          <ActionButton isQuiet aria-label="Show Recent and Pinned" onPress={() => hide(false)}>
            <RailLeft size="S" />
          </ActionButton>
          <Tooltip>Recent and Pinned</Tooltip>
        </TooltipTrigger>
      </div>
    );
  }

  return (
    <aside className="studio-work" aria-label="Recent and pinned screens">
      <header className="studio-work__railhead">
        <TooltipTrigger delay={600} placement="end">
          <ActionButton isQuiet aria-label="Hide Recent and Pinned" onPress={() => hide(true)}>
            <RailLeft size="S" />
          </ActionButton>
          <Tooltip>Hide this rail</Tooltip>
        </TooltipTrigger>
      </header>
      <Card title="Recent" open={!folded.includes('recent')} onToggle={() => fold('recent')}>
        <RecentList />
      </Card>
      <Card title="Pinned" open={!folded.includes('pinned')} onToggle={() => fold('pinned')}>
        <PinnedList />
      </Card>
      <InboxCard open={!folded.includes('inbox')} onToggle={() => fold('inbox')} />
    </aside>
  );
}

export function Card({ title, count, open, onToggle, children }: { title: string; count?: number; open: boolean; onToggle(): void; children: ReactNode }) {
  return (
    <section className={`studio-work__card${open ? ' is-open' : ''}`} aria-label={title}>
      <h2 className="studio-work__head">
        <button type="button" className="studio-work__fold" aria-expanded={open} onClick={onToggle}>
          {open ? <ChevronDown size="XS" /> : <ChevronRight size="XS" />}
          <span>{title}</span>
          {!!count && <span className="studio-work__count">{count > 99 ? '99+' : count}</span>}
        </button>
      </h2>
      {open && children}
    </section>
  );
}

function Row({ url, icon, title, hint, hintTitle, children, drag }: { url: string; icon: ReactNode; title: string; hint?: string; hintTitle?: string; children?: ReactNode; drag?: LiHTMLAttributes<HTMLLIElement> & { className?: string } }) {
  return (
    <li {...drag} className={`studio-work__row${drag?.className ? ` ${drag.className}` : ''}`}>
      <Link to={url} className="studio-work__link" title={url}>
        <span className="studio-work__icon" aria-hidden>
          {icon}
        </span>
        <span className="studio-work__texts">
          <span className="studio-work__title">{title}</span>
          {hint && (
            <span className="studio-work__hint" title={hintTitle}>
              {hint}
            </span>
          )}
        </span>
      </Link>
      {children && <span className="studio-work__actions">{children}</span>}
    </li>
  );
}

function RecentList() {
  const recent = useRecent();
  const pinned = usePinned();
  const shown = recent.slice(0, ROWS);
  if (shown.length === 0) return <p className="studio-work__empty">The screens you open will be here.</p>;
  return (
    <ul className="studio-work__list">
      {shown.map((v) => {
        const pin = pinned.some((p) => p.url === v.url);
        return (
          <Row key={v.url} url={v.url} icon={<NavIcon name={iconOfUrl(v.url)} size="S" />} title={v.title} hint={relativeTime(new Date(v.at))} hintTitle={new Date(v.at).toLocaleString()}>
            <TooltipTrigger delay={600}>
              <ActionButton isQuiet aria-label={pin ? `Unpin ${v.title}` : `Pin ${v.title}`} onPress={() => togglePin(v.url, v.title)} UNSAFE_className={pin ? 'is-on' : undefined}>
                {pin ? <PinOn size="S" /> : <PinOff size="S" />}
              </ActionButton>
              <Tooltip>{pin ? 'Unpin' : 'Pin: keep it at hand'}</Tooltip>
            </TooltipTrigger>
            <TooltipTrigger delay={600}>
              <ActionButton isQuiet aria-label={`Forget ${v.title}`} onPress={() => forgetVisit(v.url)}>
                <Close size="S" />
              </ActionButton>
              <Tooltip>Remove from Recent</Tooltip>
            </TooltipTrigger>
          </Row>
        );
      })}
    </ul>
  );
}

/** The pinned screens; drag a row onto another to reorder them. */
export function PinnedList() {
  const pinned = usePinned();
  const [dragging, setDragging] = useState<string | null>(null);
  const [over, setOver] = useState<string | null>(null);
  if (pinned.length === 0) return <p className="studio-work__empty">Pin a screen to keep it here: the pin on a recent row, or ⌘K › Pin this screen.</p>;
  const drop = (target: string) => {
    if (dragging && dragging !== target) reorderPins(movedBefore(pinned.map((p) => p.url), dragging, target));
    setDragging(null);
    setOver(null);
  };
  return (
    <ul className="studio-work__list">
      {pinned.map((p) => (
        <Row
          key={p.url}
          url={p.url}
          icon={<NavIcon name={iconOfUrl(p.url)} size="S" />}
          title={p.title}
          hint={p.url}
          drag={{
            draggable: pinned.length > 1,
            className: `${dragging === p.url ? 'is-dragging' : ''}${over === p.url && dragging && dragging !== p.url ? ' is-over' : ''}`.trim(),
            onDragStart: (e) => {
              setDragging(p.url);
              e.dataTransfer.effectAllowed = 'move';
              e.dataTransfer.setData('text/plain', p.url);
            },
            onDragOver: (e) => {
              if (!dragging) return;
              e.preventDefault();
              setOver(p.url);
            },
            onDrop: (e) => {
              e.preventDefault();
              drop(p.url);
            },
            onDragEnd: () => {
              setDragging(null);
              setOver(null);
            },
          }}
        >
          <TooltipTrigger delay={600}>
            <ActionButton isQuiet aria-label={`Unpin ${p.title}`} onPress={() => unpin(p.url)}>
              <PinOn size="S" />
            </ActionButton>
            <Tooltip>Unpin</Tooltip>
          </TooltipTrigger>
        </Row>
      ))}
    </ul>
  );
}

/** `urls` with `moved` taken out and put where `target` is (before it when coming from below, after it from above). */
export function movedBefore(urls: string[], moved: string, target: string): string[] {
  const from = urls.indexOf(moved);
  const to = urls.indexOf(target);
  if (from < 0 || to < 0 || from === to) return urls;
  const next = urls.filter((u) => u !== moved);
  next.splice(to, 0, moved);
  return next;
}

/** The workflow steps waiting for you: the first few, and the way to the inbox. */
function InboxCard({ open, onToggle }: { open: boolean; onToggle(): void }) {
  const [entries, setEntries] = useState<InboxEntry[] | null>(null);
  const [tasks, setTasks] = useState<MyTask[]>([]);
  useEffect(() => {
    let live = true;
    const load = () =>
      fetch('/bin/studio/workflow.json?op=inbox', { credentials: 'same-origin' })
        .then((r) => (r.ok ? (r.json() as Promise<InboxEntry[]>) : []))
        .then((list) => live && setEntries(Array.isArray(list) ? list : []))
        .catch(() => live && setEntries([]));
    void load();
    // the project tasks assigned to you wait for you too
    fetch('/bin/studio/projects.json?op=mytasks', { credentials: 'same-origin' })
      .then((r) => (r.ok ? (r.json() as Promise<{ tasks?: MyTask[] }>) : { tasks: [] }))
      .then((d) => live && setTasks(Array.isArray(d.tasks) ? d.tasks : []))
      .catch(() => live && setTasks([]));
    const later = () => window.setTimeout(load, 1500);
    window.addEventListener(INBOX_CHANGED, later);
    return () => {
      live = false;
      window.removeEventListener(INBOX_CHANGED, later);
    };
  }, []);
  const shown = (entries ?? []).slice(0, 5);
  return (
    <Card title="Inbox" count={(entries?.length ?? 0) + tasks.length} open={open} onToggle={onToggle}>
      {entries !== null && entries.length === 0 && tasks.length === 0 && <p className="studio-work__empty">Nothing waits for you.</p>}
      {tasks.length > 0 && (
        <ul className="studio-work__list">
          {tasks.slice(0, 5).map((t) => (
            <Row key={`${t.project}/${t.id}`} url={projectUrl(t.project.slice(t.project.lastIndexOf('/') + 1))} icon={<NavIcon name="projects" size="S" />} title={t.title} hint={[t.projectTitle, dueText(t.due)].filter(Boolean).join(' · ')} hintTitle={t.project} />
          ))}
        </ul>
      )}
      {shown.length > 0 && (
        <ul className="studio-work__list">
          {shown.map((e) => (
            <Row
              key={e.id}
              url={workflowUrl.inbox()}
              icon={<NavIcon name="workflow" size="S" />}
              title={e.title ?? e.name}
              hint={`${e.workflow.title ?? e.workflow.modelTitle ?? e.workflow.name}${e.startTime ? ` · ${relativeTime(new Date(e.startTime))}` : ''}`}
              hintTitle={e.workflow.payload}
            />
          ))}
        </ul>
      )}
      {entries !== null && entries.length > shown.length && (
        <p className="studio-work__empty">
          <Link to={workflowUrl.inbox()}>{entries.length - shown.length} more in the inbox</Link>
        </p>
      )}
    </Card>
  );
}
