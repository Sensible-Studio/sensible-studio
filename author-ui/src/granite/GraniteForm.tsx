import { useMemo, useRef } from 'react';
import type { ContentNode, GraniteNode, PostEntry } from './types';
import type { GraniteEnv, GraniteRegistry } from './registry';
import { GraniteProvider } from './context';
import { FormStore, type ValidationError } from './form/FormStore';
import { prepareDialog, type PreparedDialog } from './prepare';
import { GraniteNodeView } from './render';
import { InheritanceProvider, type InheritanceModel } from './inheritance';

export interface GraniteFormModel {
  store: FormStore;
  prepared: PreparedDialog;
  /** Validates mounted, visible fields. */
  validate(): ValidationError[];
  /** Sling POST entries for every mounted field. */
  serialize(): PostEntry[];
  isDirty(): boolean;
}

/**
 * Creates the state of a dialog form. Recreate it (new `content` or `root`) to reset the form.
 */
export function useGraniteForm(
  root: GraniteNode,
  content: ContentNode | undefined,
  registry: GraniteRegistry,
  env: GraniteEnv = {},
): GraniteFormModel {
  // `env` only feeds expression evaluation at creation time; it must not reset the form when it changes.
  const envRef = useRef(env);
  envRef.current = env;
  return useMemo(() => {
    const prepared = prepareDialog(root, registry, envRef.current);
    const initial = prepared.initialValues.reduce((c, derive) => derive(c), content ?? {});
    const store = new FormStore(initial);
    return {
      store,
      prepared,
      validate: () => store.validate(),
      serialize: () => store.serialize(),
      isDirty: () => store.isDirty(),
    };
  }, [root, content, registry]);
}

interface GraniteFormProps {
  form: GraniteFormModel;
  registry: GraniteRegistry;
  env?: GraniteEnv;
  /** Show validation messages next to fields (set after a failed submit). */
  showErrors?: boolean;
  /** MSM: which fields are inherited from a blueprint (live copies only). */
  inheritance?: InheritanceModel | null;
}

/** Renders a Granite dialog tree as a form bound to `form.store`. */
export function GraniteForm({ form, registry, env = {}, showErrors = false, inheritance }: GraniteFormProps) {
  return (
    <GraniteProvider
      registry={registry}
      env={env}
      controllers={form.prepared.controllers}
      rules={form.prepared.rules}
      hideRules={form.prepared.hideRules}
      hintRules={form.prepared.hintRules}
      rootStore={form.store}
      showErrors={showErrors}
    >
      <InheritanceProvider model={inheritance}>
        <div className="studio-g-form">
          <GraniteNodeView node={form.prepared.root} />
        </div>
      </InheritanceProvider>
    </GraniteProvider>
  );
}
