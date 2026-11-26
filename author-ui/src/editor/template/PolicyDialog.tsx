import { ActionButton, ProgressCircle, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Duplicate from '@spectrum-icons/workflow/Duplicate';
import { useEffect, useMemo, useState } from 'react';
import {
  GraniteForm,
  useGraniteForm,
  type ContentNode,
  type DialogResponse,
  type GraniteEnv,
  type GraniteNode,
  type GraniteRegistry,
  type PostEntry,
} from '../../granite';
import type { StudioClient } from '../api/StudioClient';
import type { PolicyInfo, PolicyTarget, TemplateClient } from './api';

interface Props {
  client: StudioClient;
  templates: TemplateClient;
  target: PolicyTarget;
  registry: GraniteRegistry;
  env: GraniteEnv;
  onClose(): void;
  /** after Done: the policy is assigned and saved */
  onSaved(title: string): void;
}

/** The policy being edited: one of the type's policies, or a new one (empty, or copied from another). */
type Selection = { kind: 'existing'; path: string } | { kind: 'new'; copyFrom?: string };

const keyOf = (s: Selection) => (s.kind === 'existing' ? s.path : `new:${s.copyFrom ?? ''}`);

/**
 * The policy dialog of the template editor, laid out like AEM's: on the left the policy (pick one of the
 * component's policies, add or duplicate one, its title and description, the templates that share it), on the
 * right its properties, from the component's design dialog. Done assigns the policy to the component in the
 * template and saves its properties with a Sling POST to the policy, as AEM does.
 */
export function PolicyDialog({ client, templates, target, registry, env: hostEnv, onClose, onSaved }: Props) {
  const [info, setInfo] = useState<PolicyInfo | null>(null);
  const [dialog, setDialog] = useState<(DialogResponse & { root: GraniteNode }) | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [content, setContent] = useState<ContentNode | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [form, setForm] = useState<{ serialize(): PostEntry[]; validate(): unknown[]; isDirty(): boolean } | null>(null);

  useEffect(() => {
    let live = true;
    Promise.all([templates.policy(target), client.getDialog(target.type, 'cq:design_dialog')]).then(
      ([i, d]) => {
        if (!live) return;
        setInfo(i);
        setDialog(d.root ? (d as DialogResponse & { root: GraniteNode }) : null);
        setSelection(i.policy ? { kind: 'existing', path: i.policy.path } : { kind: 'new' });
      },
      (e: unknown) => live && setError(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      live = false;
    };
  }, [client, templates, target]);

  // the selected policy's values (a new policy starts empty, or with the values of the one it copies)
  useEffect(() => {
    if (!selection || !info) return;
    let live = true;
    const source = selection.kind === 'existing' ? selection.path : selection.copyFrom;
    const summary = info.policies.find((p) => p.path === source);
    setContent(null);
    if (selection.kind === 'existing') {
      setTitle(summary?.title ?? '');
      setDescription(summary?.description ?? '');
    } else {
      setTitle(summary ? `${summary.title} (copy)` : '');
      setDescription(summary?.description ?? '');
    }
    (source ? client.getContent(source) : Promise.resolve({} as ContentNode)).then(
      (c) => live && setContent(c),
      () => live && setContent({}),
    );
    return () => {
      live = false;
    };
  }, [client, info, selection]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !saving && !document.querySelector('.studio-g-picker') && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, saving]);

  const env = useMemo<GraniteEnv>(
    () => ({ ...hostEnv, contentPath: selection?.kind === 'existing' ? selection.path : undefined, allComponents: () => templates.components() }),
    [hostEnv, selection, templates],
  );

  const current = info?.policy?.path;
  const changedPolicy = selection?.kind === 'new' || (selection?.kind === 'existing' && selection.path !== current);

  const save = async () => {
    if (!selection || !info) return;
    if (!title.trim()) {
      setFailure('The configuration needs a title.');
      return;
    }
    if (form && form.validate().length) {
      setFailure('Some properties need attention.');
      return;
    }
    setSaving(true);
    setFailure(null);
    try {
      let policyPath = selection.kind === 'existing' ? selection.path : '';
      if (selection.kind === 'new') {
        const made = await templates.assignPolicy(target, { create: true, title: title.trim(), description: description.trim(), copyFrom: selection.copyFrom });
        policyPath = made.path;
      } else if (changedPolicy || info.inherited) {
        await templates.assignPolicy(target, { policy: policyPath });
      }
      const entries: PostEntry[] = [
        { name: './jcr:title', value: title.trim() },
        { name: './jcr:description', value: description.trim() },
        ...(form?.serialize() ?? []),
      ];
      await client.post(policyPath, entries);
      onSaved(title.trim());
    } catch (e) {
      setFailure(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  const others = info?.usedBy ?? [];

  return (
    <div className="studio-policy" role="dialog" aria-modal="true" aria-labelledby="studio-policy-title">
      <header className="studio-policy__header">
        <h2 id="studio-policy-title" className="studio-policy__title">
          {target.title}
        </h2>
        <div className="studio-policy__actions">
          <button type="button" className="studio-pp-like-btn studio-pp-like-btn--secondary" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button type="button" className="studio-pp-like-btn studio-pp-like-btn--primary" onClick={() => void save()} disabled={saving || !info || !selection}>
            {saving ? 'Saving…' : 'Done'}
          </button>
        </div>
      </header>
      {failure && (
        <div className="studio-policy__failure" role="alert">
          {failure}
        </div>
      )}
      {error ? (
        <p className="studio-policy__error">{error}</p>
      ) : !info || dialog === undefined || !selection ? (
        <div className="studio-policy__loading">
          <ProgressCircle isIndeterminate aria-label="Loading the configuration" />
        </div>
      ) : (
        <div className="studio-policy__body">
          <section className="studio-policy__left" aria-labelledby="studio-policy-left">
            <h3 id="studio-policy-left" className="studio-policy__heading">
              Configuration
            </h3>
            <p className="studio-policy__hint">First choose a configuration to apply. Configurations can be shared across templates.</p>
            <label className="studio-policy__label" htmlFor="studio-policy-select">
              Select configuration
            </label>
            <div className="studio-policy__select">
              <select
                id="studio-policy-select"
                className="studio-g-input"
                value={selection.kind === 'existing' ? selection.path : ''}
                onChange={(e) => setSelection(e.target.value ? { kind: 'existing', path: e.target.value } : { kind: 'new' })}
              >
                {selection.kind === 'new' && <option value="">{selection.copyFrom ? 'New configuration (copy)' : 'New configuration'}</option>}
                {info.policies.map((p) => (
                  <option key={p.path} value={p.path}>
                    {p.title}
                  </option>
                ))}
              </select>
              <TooltipTrigger delay={400}>
                <ActionButton isQuiet aria-label="Add configuration" onPress={() => setSelection({ kind: 'new' })}>
                  <Add />
                </ActionButton>
                <Tooltip>Add configuration</Tooltip>
              </TooltipTrigger>
              <TooltipTrigger delay={400}>
                <ActionButton
                  isQuiet
                  aria-label="Duplicate configuration"
                  isDisabled={selection.kind !== 'existing'}
                  onPress={() => selection.kind === 'existing' && setSelection({ kind: 'new', copyFrom: selection.path })}
                >
                  <Duplicate />
                </ActionButton>
                <Tooltip>Duplicate configuration</Tooltip>
              </TooltipTrigger>
            </div>
            {info.inherited && selection.kind === 'existing' && selection.path === current && (
              <p className="studio-policy__hint">Inherited from the container. Done gives the component this configuration of its own.</p>
            )}
            <label className="studio-policy__label" htmlFor="studio-policy-title-input">
              Policy Title *
            </label>
            <input id="studio-policy-title-input" className="studio-g-input" value={title} onChange={(e) => setTitle(e.target.value)} />
            <label className="studio-policy__label" htmlFor="studio-policy-description">
              Configuration Description
            </label>
            <textarea
              id="studio-policy-description"
              className="studio-g-input studio-policy__description"
              placeholder="Add a description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
            {selection.kind === 'existing' && selection.path === current && others.length > 0 && (
              <>
                <p className="studio-policy__label">Other templates also using the selected configuration</p>
                <ul className="studio-policy__used">
                  {others.map((t) => (
                    <li key={t.path}>
                      {t.thumbnail ? <img src={t.thumbnail} alt="" /> : <span className="studio-policy__thumb" />}
                      <span>{t.title}</span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
          <section className="studio-policy__right" aria-labelledby="studio-policy-right">
            <h3 id="studio-policy-right" className="studio-policy__heading">
              Properties
            </h3>
            <p className="studio-policy__hint">Then adjust the settings of the selected configuration to configure the component.</p>
            {!dialog ? (
              <p className="studio-policy__hint">{info.componentTitle} has no configuration properties.</p>
            ) : !content ? (
              <ProgressCircle isIndeterminate size="S" aria-label="Loading the configuration properties" />
            ) : (
              <PolicyProperties key={keyOf(selection)} dialog={dialog} content={content} registry={registry} env={env} onForm={setForm} />
            )}
          </section>
        </div>
      )}
    </div>
  );
}

function PolicyProperties({
  dialog,
  content,
  registry,
  env,
  onForm,
}: {
  dialog: DialogResponse & { root: GraniteNode };
  content: ContentNode;
  registry: GraniteRegistry;
  env: GraniteEnv;
  onForm(form: { serialize(): PostEntry[]; validate(): unknown[]; isDirty(): boolean }): void;
}) {
  const form = useGraniteForm(dialog.root, content, registry, env);
  useEffect(() => onForm(form), [form, onForm]);
  return (
    <div className="studio-policy__form">
      <GraniteForm form={form} registry={registry} env={env} />
    </div>
  );
}
