import { triggerWords } from './MsmClient';
import { Checkbox, CheckboxGroup, Form, Item, Picker, ProgressCircle, TextField } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { nameFromTitle } from '../sites/names';
import type { MsmClient } from './MsmClient';
import type { BlueprintInfo, BlueprintStructure, RolloutConfigInfo, RolloutResult } from './types';
import { WizardPage } from '../shell/FormPage';
import './msm.scss';

interface Props {
  msm: MsmClient;
  /** where the site is created */
  parent: string;
  parentTitle: string;
  /** the signed-in user, the default site owner */
  user?: string | null;
  onClose(): void;
  onCreated(path: string, result: RolloutResult): void;
  /**
   * A site that starts from nothing: one home page from a template, with a configuration of its own. Without it
   * a new installation had no way to a first site from here: every choice wanted a site that exists already.
   */
  blank?: BlankSite;
  /** Called instead of {@link onCreated} when the site was made from nothing. */
  onBlankCreated?(path: string): void;
}

export interface BlankSite {
  templates(): Promise<Array<{ path: string; title: string }>>;
  /** Makes the home page and the site's configuration; answers the page's path. */
  create(title: string, name: string, template: string): Promise<string>;
}

const NEW = '__new__';
const BLANK = '__blank__';

/**
 * Create Site, like AEM's: pick a blueprint configuration (or define one: a title and the root of the blueprint site,
 * created only together with the site, so a wizard left half way leaves nothing behind), then the languages and
 * chapters to start with, title, name and owner. With "Linked copy" the site root and each language become shallow live
 * copies, each chapter a deep one; without it, plain copies with no tie to the blueprint. "Linked copy" starts unchecked,
 * as in AEM 6.5's wizard.
 */
export function CreateSiteWizard({ msm, parent, parentTitle, user, onClose, onCreated, blank, onBlankCreated }: Props) {
  const [step, setStep] = useState<0 | 1>(0);
  const [blueprints, setBlueprints] = useState<BlueprintInfo[] | null>(null);
  const [chosen, setChosen] = useState<string>('');
  const [structure, setStructure] = useState<BlueprintStructure | null>(null);
  const [configs, setConfigs] = useState<RolloutConfigInfo[]>([]);
  const [languages, setLanguages] = useState<string[]>([]);
  const [chapters, setChapters] = useState<string[]>([]);
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [owner, setOwner] = useState(user ?? '');
  const [liveCopy, setLiveCopy] = useState(false);
  const [rolloutConfigs, setRolloutConfigs] = useState<string[]>([]);
  const [bpTitle, setBpTitle] = useState('');
  const [bpSite, setBpSite] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [templates, setTemplates] = useState<Array<{ path: string; title: string }>>([]);
  const [template, setTemplate] = useState('');

  useEffect(() => {
    msm.blueprints().then(setBlueprints, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
    msm.configs().then(setConfigs, () => setConfigs([]));
  }, [msm]);

  useEffect(() => {
    if (user && !owner) setOwner(user);
  }, [user, owner]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !saving && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, saving]);

  const effectiveName = name.trim() || nameFromTitle(title);
  const nameOk = !name.trim() || /^[A-Za-z0-9_.~-]+$/.test(name.trim());
  const isBlank = chosen === BLANK;
  const canCreate = (isBlank ? !!template : !!structure && languages.length > 0) && title.trim().length > 0 && !!effectiveName && nameOk && !saving;
  const canNext = chosen === NEW ? bpTitle.trim().length > 0 && bpSite.startsWith('/content/') : !!chosen;

  const next = async () => {
    setError(null);
    setSaving(true);
    try {
      if (isBlank && blank) {
        const found = await blank.templates();
        if (found.length === 0) throw new Error('No page template can be used here: install a site, or make a template in Templates.');
        setTemplates(found);
        setTemplate((t) => (found.some((x) => x.path === t) ? t : found[0].path));
        setStep(1);
        return;
      }
      const s = chosen === NEW ? { ...(await msm.siteStructure(bpSite.trim())), title: bpTitle.trim() } : await msm.blueprint(chosen);
      setStructure(s);
      setLanguages(s.languages.map((l) => l.path));
      setChapters(s.chapters.map((c) => c.name));
      setStep(1);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  const create = async () => {
    if (!canCreate) return;
    if (isBlank && blank) {
      setSaving(true);
      setError(null);
      try {
        const made = await blank.create(title.trim(), effectiveName, template);
        (onBlankCreated ?? (() => onClose()))(made);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        setSaving(false);
      }
      return;
    }
    if (!structure) return;
    setSaving(true);
    setError(null);
    try {
      const res = await msm.createSite({
        ...(chosen === NEW ? { newBlueprint: { title: bpTitle.trim(), sitePath: bpSite.trim() } } : { blueprint: chosen }),
        parent,
        title: title.trim(),
        name: effectiveName,
        owner: owner.trim() || undefined,
        languages,
        chapters,
        liveCopy,
        rolloutConfigs,
      });
      onCreated(res.path, res.result);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  return (
    <WizardPage
      title="Create Site"
      path={parent}
      steps={['Source', 'Properties']}
      step={step}
      busy={saving}
      error={error}
      onBack={step === 1 ? () => setStep(0) : undefined}
      onClose={onClose}
      primaryLabel={step === 0 ? 'Next' : 'Create'}
      onPrimary={step === 0 ? () => void next() : () => void create()}
      primaryDisabled={step === 0 ? !canNext : !canCreate}
    >
        <div className="studio-wizard__form">
          {step === 0 ? (
            blueprints === null ? (
              error ? null : <ProgressCircle isIndeterminate aria-label="Loading sources" />
            ) : (
              <div className="studio-msm-blueprints" role="radiogroup" aria-label="Source">
                {[
                  ...(blank ? [{ path: BLANK, title: 'A new site', description: 'One home page to start from, with a configuration of its own', sitePath: '' }] : []),
                  ...blueprints,
                  { path: NEW, title: 'New source…', description: 'Use any site root as a source', sitePath: '' },
                ].map((b) => (
                  <button
                    key={b.path}
                    type="button"
                    role="radio"
                    aria-checked={chosen === b.path}
                    className={`studio-msm-blueprint${chosen === b.path ? ' is-selected' : ''}`}
                    onClick={() => setChosen(b.path)}
                  >
                    <strong>{b.title}</strong>
                    {b.sitePath && <code>{b.sitePath}</code>}
                    {b.description && <span>{b.description}</span>}
                  </button>
                ))}
                {chosen === NEW && (
                  <Form maxWidth="size-6000" onSubmit={(e) => e.preventDefault()} UNSAFE_className="studio-msm-blueprints__new">
                    <TextField label="Source title" value={bpTitle} onChange={setBpTitle} isRequired autoFocus />
                    <TextField
                      label="Site root"
                      value={bpSite}
                      onChange={setBpSite}
                      isRequired
                      placeholder="/content/my-site/language-masters"
                      description="Its child pages are the languages. The source is saved below /apps/msm when the site is created."
                    />
                  </Form>
                )}
              </div>
            )
          ) : isBlank ? (
            <Form maxWidth="size-6000" onSubmit={(e) => (e.preventDefault(), void create())}>
              <p className="studio-wizard__context">
                A new site below <strong>{parentTitle}</strong>: its home page, which you fill in the editor and add pages below.
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
              <Picker label="Template of the home page" selectedKey={template} onSelectionChange={(k) => setTemplate(String(k))} width="100%">
                {templates.map((t) => (
                  <Item key={t.path}>{t.title}</Item>
                ))}
              </Picker>
            </Form>
          ) : (
            structure && (
              <Form maxWidth="size-6000" onSubmit={(e) => (e.preventDefault(), void create())}>
                <p className="studio-wizard__context">
                  A site from <strong>{structure.title}</strong> ({structure.sitePath}) below <strong>{parentTitle}</strong>
                </p>
                <CheckboxGroup label="Initial languages" value={languages} onChange={setLanguages} isRequired orientation="horizontal">
                  {structure.languages.map((l) => (
                    <Checkbox key={l.path} value={l.path}>
                      {`${l.title} (${l.name})`}
                    </Checkbox>
                  ))}
                </CheckboxGroup>
                <CheckboxGroup
                  label="Initial chapters"
                  value={chapters}
                  onChange={setChapters}
                  orientation="horizontal"
                  description={structure.chapters.length ? 'Created in every chosen language that has them' : 'The languages have no child pages'}
                >
                  {structure.chapters.map((c) => (
                    <Checkbox key={c.name} value={c.name}>
                      {`${c.title} (${c.name})`}
                    </Checkbox>
                  ))}
                </CheckboxGroup>
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
                <TextField label="Site owner" value={owner} onChange={setOwner} />
                <Checkbox isSelected={liveCopy} onChange={setLiveCopy}>
                  Linked copy (the site stays in sync with its source; otherwise plain copies)
                </Checkbox>
                <CheckboxGroup
                  label="Sync rules"
                  value={rolloutConfigs}
                  onChange={setRolloutConfigs}
                  isDisabled={!liveCopy}
                  description={!liveCopy ? 'Only for linked copies' : rolloutConfigs.length ? undefined : 'None chosen: the standard sync'}
                >
                  {configs.map((c) => (
                    <Checkbox key={c.path} value={c.path}>
                      {c.title} <span className="studio-msm__muted">({triggerWords(c.trigger)})</span>
                    </Checkbox>
                  ))}
                </CheckboxGroup>
              </Form>
            )
          )}
        </div>
    </WizardPage>
  );
}
