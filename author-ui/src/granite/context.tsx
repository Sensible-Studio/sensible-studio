import { createContext, useContext, useEffect, useId, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import type { GraniteNode } from './types';
import type { GraniteEnv, GraniteRegistry } from './registry';
import { holds, type DisableRule, type HideRule, type HintRule, type ShowHideController } from './prepare';
import { FormStore, type FieldRegistration } from './form/FormStore';
import { asString, graniteClasses, graniteData, str } from './node';

interface GraniteContextValue {
  registry: GraniteRegistry;
  env: GraniteEnv;
  controllers: ShowHideController[];
  /** Field rules from emulated editor client libraries. */
  rules: DisableRule[];
  hideRules?: HideRule[];
  hintRules?: HintRule[];
  /** The root form store; show/hide controllers always live there. */
  rootStore: FormStore;
}

const GraniteContext = createContext<GraniteContextValue | null>(null);
const StoreContext = createContext<FormStore | null>(null);
const VisibleContext = createContext<boolean>(true);
/** Set by containers that render fields as part of a multifield item. */
const ValidationContext = createContext<{ showErrors: boolean }>({ showErrors: false });

export function GraniteProvider(props: GraniteContextValue & { showErrors: boolean; children: ReactNode }) {
  const { children, showErrors, ...value } = props;
  return (
    <GraniteContext.Provider value={value}>
      <StoreContext.Provider value={value.rootStore}>
        <ValidationContext.Provider value={{ showErrors }}>{children}</ValidationContext.Provider>
      </StoreContext.Provider>
    </GraniteContext.Provider>
  );
}

export function useGranite(): GraniteContextValue {
  const ctx = useContext(GraniteContext);
  if (!ctx) throw new Error('Granite components must be rendered inside <GraniteForm>');
  return ctx;
}

export function useFormStore(): FormStore {
  const store = useContext(StoreContext);
  if (!store) throw new Error('No FormStore in context');
  return store;
}

/** Renders children against another store (one multifield item). */
export function StoreScope({ store, children }: { store: FormStore; children: ReactNode }) {
  return <StoreContext.Provider value={store}>{children}</StoreContext.Provider>;
}

export function VisibleScope({ visible, children }: { visible: boolean; children: ReactNode }) {
  const parent = useContext(VisibleContext);
  return <VisibleContext.Provider value={parent && visible}>{children}</VisibleContext.Provider>;
}

export function useIsVisible(): boolean {
  return useContext(VisibleContext);
}

export function useShowErrors(): boolean {
  return useContext(ValidationContext).showErrors;
}

/** Subscribes to one value of a store. */
export function useStoreValue<T>(store: FormStore, name: string): T | undefined {
  return useSyncExternalStore(
    store.subscribe,
    () => store.get<T>(name),
    () => store.get<T>(name),
  );
}

export interface FieldBinding<T> {
  id: string;
  value: T;
  setValue(value: T): void;
  /** Error message when validation failed and errors are shown. */
  error: string | null;
  /** Disabled by a behaviour rule: not validated, not posted (the stored value stays). */
  disabled: boolean;
}

function ruleDisables(rules: DisableRule[], name: string, store: FormStore): boolean {
  return rules.some((r) => r.field === name && holds(r.when, (f) => store.get(f)));
}

/**
 * Binds a field to the current store: initialises its value from content (or the dialog default) once,
 * registers how it is posted and validated, and subscribes to changes.
 */
export function useField<T>(
  name: string,
  initial: () => T,
  registration: Omit<FieldRegistration, 'name' | 'isVisible'>,
): FieldBinding<T> {
  const store = useFormStore();
  const { rules, rootStore } = useGranite();
  const id = useId();
  const visible = useIsVisible();
  const showErrors = useShowErrors();
  const governed = rules.some((r) => r.field === name);
  // Re-render when the fields governing this one change.
  useSyncExternalStore(governed ? rootStore.subscribe : noopSubscribe, rootStore.getVersion, rootStore.getVersion);
  const disabled = governed && ruleDisables(rules, name, rootStore);
  const disabledRef = useRef(disabled);
  disabledRef.current = disabled;

  if (!store.has(name)) store.init(name, initial());

  // Keep the latest registration callbacks without re-registering on every render.
  const regRef = useRef(registration);
  regRef.current = registration;
  const visibleRef = useRef(visible);
  visibleRef.current = visible;

  useEffect(() => {
    return store.register({
      name,
      label: regRef.current.label,
      entries: (v) => (disabledRef.current ? [] : regRef.current.entries(v)),
      values: regRef.current.values ? (v) => regRef.current.values!(v) : undefined,
      validate: regRef.current.validate ? (v) => (disabledRef.current ? null : regRef.current.validate!(v)) : undefined,
      isDirty: regRef.current.isDirty ? () => regRef.current.isDirty!() : undefined,
      isVisible: () => visibleRef.current,
    });
  }, [store, name]);

  const value = useStoreValue<T>(store, name) as T;
  const error = showErrors && visible && !disabled && registration.validate ? registration.validate(value) : null;
  return { id, value, setValue: (v: T) => store.set(name, v), error, disabled };
}

/**
 * Whether a node is visible according to the show/hide controllers of the dialog.
 * A node is a target when its `granite:class` matches a controller's selector.
 */
export function useShowHideVisible(node: GraniteNode): boolean {
  const { controllers, rootStore } = useGranite();
  const classes = graniteClasses(node);
  const relevant = classes.length
    ? controllers.filter((c) => c.targets.some((set) => set.every((cls) => classes.includes(cls))))
    : [];
  // Subscribe to the root store only when the node is a target.
  const version = useSyncExternalStore(
    relevant.length ? rootStore.subscribe : noopSubscribe,
    rootStore.getVersion,
    rootStore.getVersion,
  );
  void version;
  if (!relevant.length) return true;
  const targetValue = graniteData(node)['showhidetargetvalue'];
  return relevant.every((c) => {
    const value = rootStore.get(c.name);
    if (c.kind === 'checkbox') {
      const checked = value === true;
      if (targetValue === undefined) return checked;
      return String(checked) === targetValue;
    }
    const selected = Array.isArray(value) ? value.map(String) : [value === undefined ? '' : String(value)];
    if (targetValue === undefined) return true;
    return selected.includes(targetValue);
  });
}

/** Names of the nodes among `nodes` that a behaviour rule hides (for containers that skip them, like tabs). */
export function useRuleHiddenNames(nodes: GraniteNode[]): Set<string> {
  const { hideRules = [], rootStore } = useGranite();
  const relevant = hideRules.filter((r) => nodes.some((n) => r.target === n.name));
  useSyncExternalStore(relevant.length ? rootStore.subscribe : noopSubscribe, rootStore.getVersion, rootStore.getVersion);
  const hidden = new Set<string>();
  for (const r of relevant) if (holds(r.when, (f) => rootStore.get(f))) hidden.add(r.target);
  return hidden;
}

/** Whether a behaviour rule hides this node (matched by node name or field name). */
export function useRuleHidden(node: GraniteNode): boolean {
  const { hideRules = [], rootStore } = useGranite();
  const name = str(node, 'name');
  const relevant = hideRules.filter((r) => r.target === node.name || (name !== undefined && r.target === name));
  useSyncExternalStore(relevant.length ? rootStore.subscribe : noopSubscribe, rootStore.getVersion, rootStore.getVersion);
  return relevant.some((r) => holds(r.when, (f) => rootStore.get(f)));
}

/**
 * The inherited value a field shows as placeholder while a hint rule holds (e.g. the asset description that
 * becomes the alt text), or undefined.
 */
export function useFieldHint(name: string): string | undefined {
  const { hintRules = [], rootStore, env } = useGranite();
  const relevant = hintRules.filter((r) => r.field === name);
  useSyncExternalStore(relevant.length ? rootStore.subscribe : noopSubscribe, rootStore.getVersion, rootStore.getVersion);
  const rule = relevant.find((r) => holds(r.when, (f) => rootStore.get(f)));
  const asset = rule ? asString(rootStore.get(rule.assetDescription)) : '';
  const [hint, setHint] = useState<{ asset: string; text?: string }>({ asset: '' });
  useEffect(() => {
    if (!asset || !env.assetInfo) return;
    let live = true;
    env.assetInfo(asset).then(
      (info) => live && setHint({ asset, text: info?.description || undefined }),
      () => live && setHint({ asset }),
    );
    return () => {
      live = false;
    };
  }, [asset, env]);
  return asset && hint.asset === asset ? hint.text : undefined;
}

const noopSubscribe = () => () => {};
