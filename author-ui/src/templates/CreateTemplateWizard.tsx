import { DialogContainer, Form, ProgressCircle, TextArea, TextField } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import { consoleUrl, templateEditorUrl, type TemplatesClient, type TemplateSummary, type TemplateType } from './api';
import { message } from './TemplatesConsole';
import './templates.scss';
import { useNavigate, useTab } from '../shell/ShellContext';
import { TemplateIcon } from '../shell/pageIcons';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { WizardPage } from '../shell/FormPage';

interface Props {
  client: TemplatesClient;
  conf: string;
  go?(url: string): void;
}

const SOURCE: Record<string, string> = { '/apps': 'Application', '/libs': 'Product', '/conf/global': 'Global' };

/**
 * Create Template, as in AEM: pick a template type of the configuration (its own, Global's, the application's or the
 * product's), then the template's title and description. The new template is a draft; Open goes to the template
 * editor.
 */
export function CreateTemplateWizard({ client, conf, go: goProp }: Props) {
  const tab = useTab();
  const navigateShell = useNavigate();
  const go = goProp ?? navigateShell;
  /** Cancel: back where the author came from (the console, normally). */
  const leave = () => (tab && !goProp ? tab.leave(consoleUrl(conf)) : go(consoleUrl(conf)));
  const [step, setStep] = useState<0 | 1>(0);
  const [types, setTypes] = useState<TemplateType[] | null>(null);
  const [type, setType] = useState<TemplateType | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [created, setCreated] = useState<TemplateSummary | null>(null);

  useEffect(() => {
    if (tab) tab.setTitle('Create Template');
    else document.title = 'Create Template | Sensible Studio';
    client.types(conf).then(setTypes, (e: unknown) => setError(message(e)));
  }, [client, conf]);

  const cancel = () => leave();

  useEffect(() => {
    if (tab && !tab.active) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && !saving && !created && cancel();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [conf, created, go, saving, tab?.active]);

  const create = async () => {
    if (!type || !title.trim() || saving) return;
    setSaving(true);
    setError(null);
    try {
      setCreated(await client.create(conf, type.path, title.trim(), description.trim() || undefined));
    } catch (e) {
      setError(message(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <WizardPage
      title="Create Template"
      path={conf}
      steps={['Pick a Template Type', 'Template Details']}
      step={step}
      busy={saving}
      error={error}
      onBack={step === 1 ? () => setStep(0) : undefined}
      onClose={cancel}
      primaryLabel={step === 0 ? 'Next' : 'Create'}
      onPrimary={step === 0 ? () => setStep(1) : () => void create()}
      primaryDisabled={step === 0 ? !type : !title.trim()}
      className="studio-tpl-wizard"
    >
        {step === 0 ? (
          types === null ? (
            error ? null : <ProgressCircle isIndeterminate aria-label="Loading template types" />
          ) : types.length === 0 ? (
            <p className="studio-wizard__empty">
              There is no template type for <strong>{conf}</strong>. Template types are created by developers in{' '}
              <code>{conf}/settings/wcm/template-types</code>, <code>/conf/global</code>, <code>/apps/settings</code> or{' '}
              <code>/libs/settings</code>.
            </p>
          ) : (
            <ul className="studio-templates" aria-label="Template types">
              {types.map((t) => (
                <li key={t.path}>
                  <button
                    type="button"
                    className={`studio-template${type?.path === t.path ? ' is-selected' : ''}`}
                    onClick={() => setType(t)}
                    onDoubleClick={() => {
                      setType(t);
                      setStep(1);
                    }}
                    aria-pressed={type?.path === t.path}
                    title={t.path}
                  >
                    <span className="studio-template__thumb">{t.thumbnail ? <img src={t.thumbnail} alt="" /> : <TemplateIcon size="XL" />}</span>
                    <span className="studio-template__title">{t.title}</span>
                    {t.description && <span className="studio-template__desc studio-tpl-wizard__desc">{t.description}</span>}
                    {SOURCE[t.source] && <span className="studio-tpl-wizard__source">{SOURCE[t.source]}</span>}
                  </button>
                </li>
              ))}
            </ul>
          )
        ) : (
          <div className="studio-wizard__form studio-tpl-wizard__form">
            <Form maxWidth="size-6000" aria-label={`${type?.title ?? 'Template'} in ${conf}`} onSubmit={(e) => (e.preventDefault(), void create())}>
              <TextField label="Template Title" value={title} onChange={setTitle} isRequired autoFocus width="100%" />
              <TextArea label="Description" value={description} onChange={setDescription} width="100%" />
            </Form>
          </div>
        )}

      <DialogContainer onDismiss={cancel}>
        {created && (
          <StudioConfirmDialog
            title="Success"
            variant="information"
            primaryActionLabel="Open"
            cancelLabel="Done"
            onPrimaryAction={() => (tab && !goProp ? navigateShell(templateEditorUrl(created.path), { replace: true }) : go(templateEditorUrl(created.path)))}
            onCancel={cancel}
          >
            Your template “{created.title}” has been created as a draft. Open it to build its structure, then enable it so authors can use it.
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </WizardPage>
  );
}
