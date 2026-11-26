import { ActionButton, ToastQueue, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Link from '@spectrum-icons/workflow/Link';
import Unlink from '@spectrum-icons/workflow/Unlink';
import { createContext, useContext, useState, type ReactNode } from 'react';
import type { GraniteNode } from './types';
import { child, str } from './node';

/**
 * MSM inheritance of dialog fields (AEM's `cq.wcm.msm.properties` client library): in a live copy, a field whose
 * property is synchronised from the blueprint is read-only and shows a closed chain; breaking the chain cancels the
 * inheritance of that property so it can be edited, closing it again re-enables it.
 *
 * The host decides which fields are inherited ({@link InheritanceModel}); the form only asks by key.
 */
export interface InheritanceModel {
  /** `inherited`, `cancelled`, or `undefined` for fields that are not synchronised (editable, no chain). */
  state(key: string): 'inherited' | 'cancelled' | undefined;
  /** Cancels (`inherited` → `cancelled`) or re-enables the inheritance of `key`. */
  toggle(key: string): Promise<void>;
}

const InheritanceContext = createContext<InheritanceModel | null>(null);
/** Set inside a field that already shows a chain, so nested fields do not show their own. */
const LockedScope = createContext(false);

export function InheritanceProvider({ model, children }: { model?: InheritanceModel | null; children: ReactNode }) {
  return <InheritanceContext.Provider value={model ?? null}>{children}</InheritanceContext.Provider>;
}

/**
 * What a field locks: its `granite:data/cq-msm-lockable`, else its `name`. A property is returned as its name
 * (`./jcr:title` → `jcr:title`), a child node with a leading slash (`/cq:featuredimage`, or `./image/file` →
 * `/image`): rollouts copy child nodes whole, so their fields lock together.
 */
export function lockKey(node: GraniteNode): string | undefined {
  const data = child(node, 'granite:data');
  const lockable = data ? str(data, 'cq-msm-lockable') : undefined;
  return keyOf(lockable ?? str(node, 'name'));
}

/** The lock key of a field or POST entry name (`./navTitle@TypeHint` → `navTitle`, `./image/file` → `/image`). */
export function keyOf(raw: string | undefined): string | undefined {
  if (!raw) return undefined;
  const k = raw.replace(/^\.\//, '');
  const isNode = k.startsWith('/') || k.replace(/^\/+/, '').includes('/');
  const first = k.replace(/^\/+/, '').split('/')[0].replace(/@.*$/, '');
  if (!first || first.startsWith(':')) return undefined;
  return isNode ? `/${first}` : first;
}

/**
 * Leaves out the POST entries of inherited fields: they are read-only, and posting their (possibly stale) values
 * would overwrite what the next rollout brings.
 */
export function withoutInherited<T extends { name: string }>(entries: T[], model: InheritanceModel | null | undefined): T[] {
  if (!model) return entries;
  return entries.filter((e) => {
    const key = keyOf(e.name);
    return !key || model.state(key) !== 'inherited';
  });
}

/** Wraps a field: chain button, and read-only while inherited. */
export function InheritanceLock({ node, children }: { node: GraniteNode; children: ReactNode }) {
  const model = useContext(InheritanceContext);
  const inside = useContext(LockedScope);
  const [busy, setBusy] = useState(false);
  const key = model && !inside ? lockKey(node) : undefined;
  const state = key ? model!.state(key) : undefined;
  if (!state) return <>{children}</>;
  const inherited = state === 'inherited';
  const shown = key!.replace(/^\//, '');
  const label = inherited ? `Cancel inheritance of ${shown}` : `Re-enable inheritance of ${shown}`;
  return (
    <LockedScope.Provider value={true}>
      <div className={`studio-g-msm${inherited ? ' is-inherited' : ' is-cancelled'}`} data-msm-key={shown}>
        <TooltipTrigger delay={300}>
          <ActionButton
            isQuiet
            aria-label={label}
            aria-pressed={inherited}
            isDisabled={busy}
            UNSAFE_className="studio-g-msm__chain"
            onPress={async () => {
              setBusy(true);
              try {
                await model!.toggle(key!);
              } catch (e) {
                ToastQueue.negative(`${label}: ${e instanceof Error ? e.message : String(e)}`, { timeout: 8000 });
              } finally {
                setBusy(false);
              }
            }}
          >
            {inherited ? <Link size="S" /> : <Unlink size="S" />}
          </ActionButton>
          <Tooltip>{inherited ? 'Inherited from the source. Break the chain to edit.' : 'Edited in the linked copy. Close the chain to inherit again.'}</Tooltip>
        </TooltipTrigger>
        {/* a disabled fieldset makes every control inside read-only */}
        <fieldset className="studio-g-msm__field" disabled={inherited}>
          {children}
        </fieldset>
      </div>
    </LockedScope.Provider>
  );
}
