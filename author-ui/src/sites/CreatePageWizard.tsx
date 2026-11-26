import { Checkbox, Form, ProgressCircle, TextArea, TextField } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { PostEntry } from '../granite';
import type { StudioClient, SiteItem, TemplateInfo } from '../editor/api/StudioClient';
import { nameFromTitle } from './names';
import { TemplateIcon } from '../shell/pageIcons';
import { WizardPage } from '../shell/FormPage';

interface Props {
  client: StudioClient;
  parent: string;
  parentTitle: string;
  onClose(): void;
  onCreated(page: SiteItem): void;
}

/** Create Page: pick one of the templates allowed here, then the page's basic properties. */
export function CreatePageWizard({ client, parent, parentTitle, onClose, onCreated }: Props) {
  const [step, setStep] = useState<0 | 1>(0);
  const [templates, setTemplates] = useState<TemplateInfo[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [template, setTemplate] = useState<TemplateInfo | null>(null);
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [pageTitle, setPageTitle] = useState('');
  const [navTitle, setNavTitle] = useState('');
  const [subtitle, setSubtitle] = useState('');
  const [description, setDescription] = useState('');
  const [hideInNav, setHideInNav] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    client.pageTemplates(parent).then(setTemplates, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [client, parent]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !saving && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, saving]);

  const effectiveName = name.trim() || nameFromTitle(title);
  const nameOk = !name.trim() || /^[A-Za-z0-9_.~-]+$/.test(name.trim());
  const canCreate = !!template && title.trim().length > 0 && nameOk && !saving;

  const create = async () => {
    if (!template || !canCreate) return;
    setSaving(true);
    setError(null);
    try {
      const page = await client.createPage(parent, template.path, title.trim(), effectiveName || undefined);
      const props: PostEntry[] = [];
      const add = (n: string, v: string) => v.trim() && props.push({ name: n, value: v.trim() });
      add('./pageTitle', pageTitle);
      add('./navTitle', navTitle);
      add('./subtitle', subtitle);
      add('./jcr:description', description);
      if (hideInNav) props.push({ name: './hideInNav', value: 'true' });
      if (props.length) await client.post(`${page.path}/jcr:content`, props);
      onCreated(page);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  };

  return (
    <WizardPage
      title="Create Page"
      path={parent}
      steps={['Template', 'Properties']}
      step={step}
      busy={saving}
      error={error}
      onBack={step === 1 ? () => setStep(0) : undefined}
      onClose={onClose}
      primaryLabel={step === 0 ? 'Next' : 'Create'}
      onPrimary={step === 0 ? () => setStep(1) : () => void create()}
      primaryDisabled={step === 0 ? !template : !canCreate}
    >
        {step === 0 ? (
          templates === null ? (
            error ? null : <ProgressCircle isIndeterminate aria-label="Loading templates" />
          ) : templates.length === 0 ? (
            <p className="studio-wizard__empty">
              No template is allowed below <strong>{parentTitle}</strong>. Templates come from the site's configuration (
              <code>cq:conf</code>), must be enabled, and may be limited by <code>cq:allowedTemplates</code>.
            </p>
          ) : (
            <ul className="studio-templates" aria-label="Templates">
              {templates.map((t) => (
                <li key={t.path}>
                  <button
                    type="button"
                    className={`studio-template${template?.path === t.path ? ' is-selected' : ''}`}
                    onClick={() => setTemplate(t)}
                    onDoubleClick={() => {
                      setTemplate(t);
                      setStep(1);
                    }}
                    aria-pressed={template?.path === t.path}
                  >
                    <span className="studio-template__thumb">
                      {t.thumbnail ? <img src={client.assetUrl(t.thumbnail)} alt="" /> : <TemplateIcon size="XL" />}
                    </span>
                    <span className="studio-template__title">{t.title}</span>
                    {t.description && <span className="studio-template__desc">{t.description}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )
        ) : (
          <div className="studio-wizard__form">
            <p className="studio-wizard__context">
              {template?.title} below <strong>{parentTitle}</strong>
            </p>
            <Form maxWidth="size-6000" onSubmit={(e) => (e.preventDefault(), void create())}>
              <TextField label="Title" value={title} onChange={setTitle} isRequired autoFocus />
              <TextField
                label="Name"
                value={name}
                onChange={setName}
                placeholder={nameFromTitle(title) || 'from the title'}
                validationState={nameOk ? undefined : 'invalid'}
                errorMessage="Letters, digits and - _ . ~ only"
                description={effectiveName ? `URL: ${parent}/${effectiveName}.html` : undefined}
              />
              <TextField label="Page Title" value={pageTitle} onChange={setPageTitle} />
              <TextField label="Navigation Title" value={navTitle} onChange={setNavTitle} />
              <TextField label="Subtitle" value={subtitle} onChange={setSubtitle} />
              <TextArea label="Description" value={description} onChange={setDescription} />
              <Checkbox isSelected={hideInNav} onChange={setHideInNav}>
                Hide in Navigation
              </Checkbox>
            </Form>
          </div>
        )}
    </WizardPage>
  );
}
