import { triggerWords } from './MsmClient';
import { Checkbox, CheckboxGroup, ComboBox, Form, Item, ProgressCircle, TextField } from '@adobe/react-spectrum';
import { useEffect, useMemo, useState } from 'react';
import type { PageListItem, StudioClient } from '../editor/api/StudioClient';
import { nameFromTitle } from '../sites/names';
import type { MsmClient } from './MsmClient';
import type { RolloutConfigInfo, RolloutResult } from './types';
import { WizardPage } from '../shell/FormPage';
import './msm.scss';

interface Props {
  client: StudioClient;
  msm: MsmClient;
  /** where the live copy is created */
  parent: string;
  parentTitle: string;
  onClose(): void;
  onCreated(path: string, result: RolloutResult): void;
}

const OWN_TREES = ['/content/forms', '/content/experience-fragments'];
const OTHER_TREES = [...OWN_TREES, '/content/dam'];

/**
 * Create Live Copy: the blueprint page (source), then title, name, whether child pages are copied and the rollout
 * configurations. Without a configuration the live copy inherits one (the standard rollout).
 */
export function CreateLiveCopyWizard({ client, msm, parent, parentTitle, onClose, onCreated }: Props) {
  const [step, setStep] = useState<0 | 1>(0);
  const [pages, setPages] = useState<PageListItem[] | null>(null);
  const [configs, setConfigs] = useState<RolloutConfigInfo[]>([]);
  const [source, setSource] = useState('');
  const [filter, setFilter] = useState('');
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [deep, setDeep] = useState(true);
  const [chosen, setChosen] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    // the sources of the console the copy is made in: forms for a form, experience fragments for a fragment, and
    // for a page the pages, without those two trees and without media
    const own = OWN_TREES.find((tree) => parent === tree || parent.startsWith(tree + '/'));
    client.listPages(own ?? '/content', 6).then(
      (all) => setPages(own ? all : all.filter((p) => !OTHER_TREES.some((tree) => p.path === tree || p.path.startsWith(tree + '/')))),
      (e: unknown) => setError(e instanceof Error ? e.message : String(e)),
    );
    msm.configs().then(setConfigs, () => setConfigs([]));
  }, [client, msm, parent]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !saving && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, saving]);

  const sourcePage = pages?.find((p) => p.path === source);
  const options = useMemo(() => {
    const f = filter.toLowerCase();
    return (pages ?? []).filter((p) => !f || p.path.toLowerCase().includes(f) || p.title.toLowerCase().includes(f)).slice(0, 200);
  }, [pages, filter]);

  const effectiveName = name.trim() || nameFromTitle(title);
  const nameOk = !name.trim() || /^[A-Za-z0-9_.~-]+$/.test(name.trim());
  const inside = !!source && (parent === source || parent.startsWith(`${source}/`));
  const canCreate = !!source && !inside && title.trim().length > 0 && !!effectiveName && nameOk && !saving;

  const create = async () => {
    if (!canCreate) return;
    setSaving(true);
    setError(null);
    try {
      const res = await msm.createLiveCopy({ source, parent, name: effectiveName, title: title.trim(), deep, rolloutConfigs: chosen });
      onCreated(res.path, res.result);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  return (
    <WizardPage
      title="Create Linked copy"
      path={parent}
      steps={['Source', 'Linked copy']}
      step={step}
      busy={saving}
      error={error}
      onBack={step === 1 ? () => setStep(0) : undefined}
      onClose={onClose}
      primaryLabel={step === 0 ? 'Next' : 'Create'}
      onPrimary={step === 0 ? () => {
        if (!title && sourcePage) setTitle(sourcePage.title);
        setStep(1);
      } : () => void create()}
      primaryDisabled={step === 0 ? !source || inside : !canCreate}
    >
        <div className="studio-wizard__form">
          {step === 0 ? (
            pages === null ? (
              error ? null : <ProgressCircle isIndeterminate aria-label="Loading pages" />
            ) : (
              <Form maxWidth="size-6000" onSubmit={(e) => e.preventDefault()}>
                <p className="studio-wizard__context">
                  The source page whose content the linked copy below <strong>{parentTitle}</strong> follows.
                </p>
                <ComboBox
                  label="Source page"
                  items={options}
                  inputValue={filter}
                  onInputChange={setFilter}
                  selectedKey={source || null}
                  onSelectionChange={(k) => {
                    const p = pages.find((x) => x.path === k);
                    setSource(p ? p.path : '');
                    if (p) setFilter(p.path);
                  }}
                  allowsCustomValue={false}
                  menuTrigger="focus"
                  isRequired
                  autoFocus
                  validationState={inside ? 'invalid' : undefined}
                  errorMessage="A linked copy cannot be created inside its own source"
                  width="100%"
                >
                  {(p) => (
                    <Item key={p.path} textValue={p.path}>
                      {`${p.title} — ${p.path}`}
                    </Item>
                  )}
                </ComboBox>
              </Form>
            )
          ) : (
            <Form maxWidth="size-6000" onSubmit={(e) => (e.preventDefault(), void create())}>
              <p className="studio-wizard__context">
                Linked copy of <strong>{source}</strong> below <strong>{parentTitle}</strong>
              </p>
              <TextField label="Title" value={title} onChange={setTitle} isRequired autoFocus />
              <TextField
                label="Name"
                value={name}
                onChange={setName}
                placeholder={nameFromTitle(title) || 'from the title'}
                validationState={nameOk ? undefined : 'invalid'}
                errorMessage="Letters, digits and - _ . ~ only"
                description={effectiveName ? `Path: ${parent}/${effectiveName}` : undefined}
              />
              <Checkbox isSelected={!deep} onChange={(v) => setDeep(!v)}>
                Exclude sub pages (only this page is synchronized)
              </Checkbox>
              <CheckboxGroup
                label="Sync rules"
                value={chosen}
                onChange={setChosen}
                description={chosen.length ? undefined : 'None chosen: inherited from the parent linked copy, else the standard sync'}
              >
                {configs.map((c) => (
                  <Checkbox key={c.path} value={c.path}>
                    {c.title} <span className="studio-msm__muted">({triggerWords(c.trigger)})</span>
                  </Checkbox>
                ))}
              </CheckboxGroup>
            </Form>
          )}
        </div>
    </WizardPage>
  );
}
