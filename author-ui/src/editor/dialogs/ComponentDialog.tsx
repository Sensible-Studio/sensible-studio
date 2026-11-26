import { ActionButton, Button, ButtonGroup, DialogContainer, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import FullScreen from '@spectrum-icons/workflow/FullScreen';
import FullScreenExit from '@spectrum-icons/workflow/FullScreenExit';
import Help from '@spectrum-icons/workflow/Help';
import Info from '@spectrum-icons/workflow/Info';
import { useEffect, useMemo, useRef, useState } from 'react';
import {
  designFromPolicy,
  GraniteForm,
  str,
  useGraniteForm,
  withoutInherited,
  type InheritanceModel,
  type ContentNode,
  type DialogResponse,
  type GraniteEnv,
  type GraniteNode,
  type GraniteRegistry,
  type PostEntry,
} from '../../granite';
import { StudioConfirmDialog } from '../../shell/StudioDialog';

export interface OpenDialog {
  title: string;
  path: string;
  dialog: DialogResponse & { root: GraniteNode };
  content: ContentNode;
  /** Page properties open full screen with Cancel / Save & Close, like AEM's properties page. */
  variant: 'component' | 'page';
}

interface Props extends OpenDialog {
  registry: GraniteRegistry;
  env: GraniteEnv;
  /** Resolves when saved; rejects with a message to show. */
  onSubmit(entries: PostEntry[]): Promise<void>;
  onClose(): void;
  /** MSM: inherited fields of a live copy page (read-only behind a chain, not posted); `undefined` while loading. */
  inheritance?: InheritanceModel | null;
  /** MSM: the inheritance of the page is still loading, saving waits for it */
  inheritancePending?: boolean;
}

/**
 * The editor's window around a Granite form. Component properties open in a floating window over the page,
 * as AEM's component dialog does: it is moved by its title band, grows to the page's area with Full screen,
 * and leaves the page and the side panel usable (a file can be dragged in). Page properties are their own
 * screen (`propertiesUrl`), not this window.
 */

/** Where the author last left the window, so the next dialog opens there (this editor, until reload). */
let lastPlace: { x: number; y: number } | null = null;

/** A window's place kept on screen: its title band stays reachable whatever the viewport became. */
export function clampPlace(p: { x: number; y: number }, size: { width: number; height: number }, view: { width: number; height: number }): { x: number; y: number } {
  const keep = 48;
  return {
    x: Math.round(Math.min(Math.max(p.x, keep - size.width), view.width - keep)),
    y: Math.round(Math.min(Math.max(p.y, 0), view.height - keep)),
  };
}
export function ComponentDialog({ title, dialog, content, registry, env: hostEnv, onSubmit, onClose, variant, inheritance, inheritancePending }: Props) {
  // The instance's policy is what Granite exposes to the dialog as `cqDesign`.
  const env = useMemo(() => ({ ...hostEnv, design: designFromPolicy(dialog.policy) }), [hostEnv, dialog.policy]);
  const form = useGraniteForm(dialog.root, content, registry, env);
  const [showErrors, setShowErrors] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);
  const windowRef = useRef<HTMLDivElement>(null);
  const [full, setFull] = useState(false);
  /** null: centred, where it opens the first time */
  const [place, setPlace] = useState<{ x: number; y: number } | null>(lastPlace);
  const drag = useRef<{ dx: number; dy: number } | null>(null);

  const onGrab = (e: React.PointerEvent<HTMLElement>) => {
    // the band moves the window; its buttons do what they say
    if (variant === 'page' || full || e.button !== 0 || (e.target as HTMLElement).closest('button, a, input, [role="button"]')) return;
    const box = windowRef.current?.getBoundingClientRect();
    if (!box) return;
    drag.current = { dx: e.clientX - box.left, dy: e.clientY - box.top };
    e.currentTarget.setPointerCapture?.(e.pointerId);
    e.preventDefault();
  };
  /** The editor's own area: the window lives inside it, under the shell's tabs. */
  const area = () => windowRef.current?.parentElement?.getBoundingClientRect() ?? null;
  const onDrag = (e: React.PointerEvent<HTMLElement>) => {
    const box = windowRef.current?.getBoundingClientRect();
    const within = area();
    if (!drag.current || !box || !within) return;
    setPlace(clampPlace({ x: e.clientX - drag.current.dx - within.left, y: e.clientY - drag.current.dy - within.top }, box, within));
  };
  const onDrop = () => {
    if (!drag.current) return;
    drag.current = null;
    lastPlace = place;
  };
  /**
   * Full screen is the page's area, not the editor's: the bar and the side panel stay, so a file can still be
   * dragged in. The area is measured (the stage is in another grid) and followed while it changes size.
   */
  const [stage, setStage] = useState<{ left: number; top: number; width: number; height: number } | null>(null);
  useEffect(() => {
    if (!full || variant === 'page') return setStage(null);
    const host = windowRef.current?.parentElement;
    const el = host?.closest('.studio-app')?.querySelector<HTMLElement>('.studio-stage');
    if (!host || !el) return setStage(null);
    const measure = () => {
      const a = host.getBoundingClientRect();
      const b = el.getBoundingClientRect();
      setStage({ left: Math.round(b.left - a.left), top: Math.round(b.top - a.top), width: Math.round(b.width), height: Math.round(b.height) });
    };
    measure();
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    ro?.observe(el);
    window.addEventListener('resize', measure);
    return () => {
      ro?.disconnect();
      window.removeEventListener('resize', measure);
    };
  }, [full, variant]);

  // a smaller browser window must not strand the dialog outside it
  useEffect(() => {
    const onResize = () => {
      const box = windowRef.current?.getBoundingClientRect();
      const within = windowRef.current?.parentElement?.getBoundingClientRect();
      if (box && within) setPlace((p) => (p ? clampPlace(p, box, within) : p));
    };
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const helpPath = str(dialog.root, 'helpPath');
  // page properties are titled with the page, like AEM; component dialogs with their own title
  const dialogTitle = variant === 'page' ? title : (str(dialog.root, 'jcr:title') ?? title);
  const notes = useMemo(() => [...dialog.warnings, ...form.prepared.notes], [dialog.warnings, form.prepared.notes]);

  const requestClose = () => {
    if (form.isDirty()) {
      setConfirmDiscard(true);
      return;
    }
    onClose();
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !document.querySelector('.studio-g-picker') && !confirmDiscard) requestClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  useEffect(() => {
    windowRef.current?.focus();
  }, []);

  const submit = async () => {
    if (inheritancePending) return;
    const errors = form.validate();
    if (errors.length) {
      setShowErrors(true);
      setFailure(`${errors.length} field${errors.length > 1 ? 's need' : ' needs'} attention: ${errors.map((e) => e.label ?? e.name).join(', ')}`);
      requestAnimationFrame(() => revealFirstError(bodyRef.current));
      return;
    }
    setSaving(true);
    setFailure(null);
    try {
      await onSubmit(withoutInherited(form.serialize(), inheritance));
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  const isPage = variant === 'page';

  return (
    <div
      className={`studio-dialog${isPage ? ' is-fullscreen studio-dialog--page' : ` studio-dialog--floating${full ? ' is-fullscreen' : ''}${place && !full ? ' is-placed' : ''}`}`}
      role="presentation"
    >
      {isPage && <div className="studio-dialog__backdrop" />}
      <div
        className="studio-dialog__window"
        role="dialog"
        aria-modal={isPage ? 'true' : 'false'}
        aria-labelledby="studio-dialog-title"
        tabIndex={-1}
        ref={windowRef}
        // lower on the screen it is shorter, so its end never leaves the editor
        style={isPage ? undefined : full ? (stage ?? undefined) : place ? { left: place.x, top: place.y, maxHeight: `max(240px, calc(100% - ${place.y}px - 8px))` } : undefined}
      >
        <header className="studio-dialog__header" onPointerDown={onGrab} onPointerMove={onDrag} onPointerUp={onDrop} onPointerCancel={onDrop} onDoubleClick={(e) => !isPage && !(e.target as HTMLElement).closest('button') && setFull((v) => !v)}>
          <div>
            {!isPage && <span className="studio-dialog__eyebrow">Properties</span>}
            <h2 id="studio-dialog-title" className="studio-dialog__title">{dialogTitle}</h2>
          </div>
          <div className="studio-dialog__actions">
            {(notes.length > 0 || dialog.policyPath) && (
              <IconButton label={showDetails ? 'Hide details' : `Details${notes.length ? ` (${notes.length} notes)` : ''}`} onPress={() => setShowDetails((v) => !v)}>
                <Info UNSAFE_className={notes.length ? 'studio-icon--notice' : undefined} />
              </IconButton>
            )}
            {helpPath && (
              <IconButton label="Help" onPress={() => window.open(helpPath, '_blank', 'noopener')}>
                <Help />
              </IconButton>
            )}
            {!isPage && (
              <IconButton label={full ? 'Exit full screen' : 'Full screen'} onPress={() => setFull((v) => !v)}>
                {full ? <FullScreenExit /> : <FullScreen />}
              </IconButton>
            )}
            {isPage ? (
              <ButtonGroup>
                <Button variant="secondary" onPress={requestClose} isDisabled={saving}>
                  Cancel
                </Button>
                <Button variant="accent" onPress={() => void submit()} isPending={saving} isDisabled={inheritancePending}>
                  Save &amp; Close
                </Button>
              </ButtonGroup>
            ) : (
              <ButtonGroup>
                <Button variant="secondary" onPress={requestClose} isDisabled={saving}>Cancel</Button>
                <Button variant="accent" onPress={() => void submit()} isPending={saving} isDisabled={inheritancePending}>Save</Button>
              </ButtonGroup>
            )}
          </div>
        </header>

        {showDetails && (
          <div className="studio-dialog__details">
            <div>
              <strong>Resource type</strong> <code>{dialog.resourceType}</code>
            </div>
            <div>
              <strong>Resolved from</strong> {dialog.chain.join(' → ')}
            </div>
            <div>
              <strong>Configuration</strong> {dialog.policyPath ?? 'none'}
            </div>
            {notes.length > 0 && (
              <ul>
                {notes.map((n) => (
                  <li key={n}>{n}</li>
                ))}
              </ul>
            )}
          </div>
        )}

        {failure && (
          <div className="studio-dialog__error" role="alert">
            {failure}
          </div>
        )}

        <div className="studio-dialog__body" ref={bodyRef}>
          <GraniteForm form={form} registry={registry} env={env} showErrors={showErrors} inheritance={inheritance} />
        </div>
      </div>

      <DialogContainer onDismiss={() => setConfirmDiscard(false)}>
        {confirmDiscard && (
          <StudioConfirmDialog
            title="Discard changes?"
            variant="destructive"
            primaryActionLabel="Discard"
            cancelLabel="Keep editing"
            onPrimaryAction={onClose}
          >
            The changes made in this dialog have not been saved.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

function IconButton({
  label,
  onPress,
  children,
  isDisabled,
  accent,
}: {
  label: string;
  onPress(): void;
  children: React.ReactNode;
  isDisabled?: boolean;
  accent?: boolean;
}) {
  return (
    <TooltipTrigger delay={400}>
      <ActionButton isQuiet aria-label={label} onPress={onPress} isDisabled={isDisabled} UNSAFE_className={accent ? 'studio-dialog__done' : undefined}>
        {children}
      </ActionButton>
      <Tooltip>{label}</Tooltip>
    </TooltipTrigger>
  );
}

function revealFirstError(root: HTMLElement | null) {
  if (!root) return;
  const invalid = root.querySelector<HTMLElement>('.studio-g-field--invalid');
  if (!invalid) return;
  const panel = invalid.closest<HTMLElement>('[role="tabpanel"]');
  if (panel?.hidden) {
    const tab = root.querySelector<HTMLButtonElement>(`[aria-controls="${panel.id}"]`);
    tab?.click();
  }
  requestAnimationFrame(() => {
    const control = invalid.querySelector<HTMLElement>('input, select, textarea, [contenteditable="true"]');
    control?.focus();
    invalid.scrollIntoView({ block: 'center', behavior: 'smooth' });
  });
}
