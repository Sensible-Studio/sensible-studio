import { DialogContainer, ProgressCircle, TextArea, TextField, ToastContainer } from '@adobe/react-spectrum';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AssetPickerDialog } from '../cf/pickers';
import type { CfClient } from '../cf/api';
import { confOf, consoleUrl, type TemplatesClient, type TemplateSummary } from './api';
import { generatePreview } from './preview';
import { message } from './TemplatesConsole';
import '../pageprops/pageprops.scss';
import './templates.scss';
import { useNavigate, useTab } from '../shell/ShellContext';
import { TemplateIcon } from '../shell/pageIcons';
import { FormPage, FormSection } from '../shell/FormPage';
import { StudioConfirmDialog } from '../shell/StudioDialog';

interface Props {
  client: TemplatesClient;
  template: string;
  /** Select Image browses the DAM through it; without it the action is not offered */
  dam?: Pick<CfClient, 'listDam' | 'thumbnailUrl'>;
  /** Generate Preview (replaced in tests) */
  preview?(pageUrl: string): Promise<File>;
  go?(url: string): void;
}

/** The thumbnail change Save applies: a new image, or none. */
type ThumbnailChange = { kind: 'upload'; file: File; preview: string } | { kind: 'remove' } | null;

const MAX_BYTES = 10 * 1024 * 1024;

/**
 * A template's properties, laid out like AEM's: the thumbnail on the left (Generate Preview from the template's
 * structure, Upload Image, Select Image from the DAM) and the title and description on the right. Save applies the
 * changes.
 */
export function TemplateProperties({ client, template, dam, preview = generatePreview, go: goProp }: Props) {
  const tab = useTab();
  const navigateShell = useNavigate();
  const go = goProp ?? navigateShell;
  const [loaded, setLoaded] = useState<TemplateSummary | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [thumbnail, setThumbnail] = useState<ThumbnailChange>(null);
  const [saving, setSaving] = useState(false);
  const [working, setWorking] = useState<string | null>(null);
  const [showErrors, setShowErrors] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState<((discard: boolean) => void) | null>(null);
  const [picking, setPicking] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const back = consoleUrl(confOf(template));
  /** Cancel and Save & Close: back where the author came from, else the template's console. */
  const leave = () => (tab && !goProp ? tab.leave(back) : go(back));

  useEffect(() => {
    client.template(template).then(
      (t) => {
        setLoaded(t);
        setTitle(t.title);
        setDescription(t.description ?? '');
      },
      (e: unknown) => setFailure(message(e)),
    );
  }, [client, template]);

  useEffect(() => {
    if (tab) tab.setTitle(`Properties · ${loaded?.title ?? 'Template'}`);
    else document.title = `${loaded?.title ?? 'Template'} | Template Properties | Sensible Studio`;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loaded]);

  useEffect(() => () => (thumbnail?.kind === 'upload' ? URL.revokeObjectURL(thumbnail.preview) : undefined), [thumbnail]);

  const dirty = !!loaded && (title !== loaded.title || description !== (loaded.description ?? '') || thumbnail !== null);

  const reset = () => {
    if (!loaded) return;
    setTitle(loaded.title);
    setDescription(loaded.description ?? '');
    setThumbnail(null);
    setShowErrors(false);
    setFailure(null);
  };

  const save = async () => {
    if (!loaded) return;
    if (!title.trim()) {
      setShowErrors(true);
      return;
    }
    setSaving(true);
    setFailure(null);
    try {
      let next = await client.saveProperties(template, title.trim(), description.trim());
      if (thumbnail?.kind === 'upload') next = await client.uploadThumbnail(template, thumbnail.file);
      else if (thumbnail?.kind === 'remove') next = await client.removeThumbnail(template);
      setLoaded(next);
      setTitle(next.title);
      setDescription(next.description ?? '');
      setThumbnail(null);
    } catch (e) {
      setFailure(message(e));
    } finally {
      setSaving(false);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 's') {
        e.preventDefault();
        void save();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const use = (file: File | undefined) => {
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      setFailure('The thumbnail must be an image (PNG, JPEG, GIF, WebP or SVG).');
      return;
    }
    if (file.size > MAX_BYTES) {
      setFailure('The image is larger than 10 MB.');
      return;
    }
    setFailure(null);
    setThumbnail({ kind: 'upload', file, preview: URL.createObjectURL(file) });
  };

  const generate = async () => {
    setWorking('Generating the preview…');
    setFailure(null);
    try {
      use(await preview(`${template}/structure.html?wcmmode=disabled`));
    } catch (e) {
      setFailure(message(e));
    } finally {
      setWorking(null);
    }
  };

  const selectAsset = async (assetPath: string) => {
    setWorking('Loading the image…');
    setFailure(null);
    try {
      const res = await fetch(assetPath, { credentials: 'same-origin' });
      if (!res.ok) throw new Error(`Could not read ${assetPath} (HTTP ${res.status})`);
      const blob = await res.blob();
      use(new File([blob], assetPath.split('/').pop() || 'thumbnail', { type: blob.type || 'image/png' }));
    } catch (e) {
      setFailure(message(e));
    } finally {
      setWorking(null);
    }
  };

  const shown = useMemo(() => {
    if (thumbnail?.kind === 'upload') return thumbnail.preview;
    if (thumbnail?.kind === 'remove') return null;
    return loaded?.thumbnail ?? null;
  }, [loaded, thumbnail]);

  const cancel = () => (dirty ? setConfirmDiscard(() => (discard: boolean) => discard && leave()) : leave());

  // In the shell, Back / Forward / a link / closing the tab ask first while there are unsaved changes.
  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;
  useEffect(() => {
    if (!tab) return;
    tab.setGuard(() => (dirtyRef.current ? new Promise<boolean>((resolve) => setConfirmDiscard(() => resolve)) : Promise.resolve(true)));
    return () => tab.setGuard(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab?.id]);

  return (
    <div className="studio-pp studio-tpl-props">
      {!loaded ? (
        failure ? null : (
          <div className="studio-pp-loading">
            <ProgressCircle isIndeterminate aria-label="Loading the template" />
          </div>
        )
      ) : (
        <FormPage
          kind="Template properties"
          title={loaded.title}
          path={template}
          dirty={dirty}
          busy={saving || !!working}
          canSave
          requireDirty={false}
          error={failure}
          onReset={reset}
          onSave={save}
          onClose={cancel}
          aside={<section className="studio-tpl-thumb" aria-label="Thumbnail">
            <div className="studio-tpl-thumb__preview">
              {working ? (
                <ProgressCircle isIndeterminate aria-label={working} />
              ) : shown ? (
                <img src={shown} alt={`Thumbnail of ${loaded.title}`} />
              ) : (
                <TemplateIcon size="XXL" aria-label="No thumbnail" />
              )}
            </div>
            <div className="studio-tpl-thumb__actions">
              <button type="button" className="studio-tpl-thumb__action" onClick={() => void generate()} disabled={saving || !!working}>
                Generate Preview
              </button>
              <button type="button" className="studio-tpl-thumb__action" onClick={() => fileInput.current?.click()} disabled={saving || !!working}>
                Upload Image
              </button>
              {dam && (
                <button type="button" className="studio-tpl-thumb__action" onClick={() => setPicking(true)} disabled={saving || !!working}>
                  Select Image
                </button>
              )}
              {shown && (
                <button
                  type="button"
                  className="studio-tpl-thumb__action studio-tpl-thumb__action--quiet"
                  onClick={() => setThumbnail(loaded.thumbnail ? { kind: 'remove' } : null)}
                  disabled={saving || !!working}
                >
                  Clear
                </button>
              )}
              <input
                ref={fileInput}
                type="file"
                accept="image/*"
                hidden
                data-testid="thumbnail-file"
                onChange={(e) => {
                  use(e.target.files?.[0]);
                  e.target.value = '';
                }}
              />
            </div>
            {thumbnail && (
              <p className="studio-tpl-thumb__hint">{thumbnail.kind === 'upload' ? 'The new thumbnail is saved with the template.' : 'The thumbnail is removed when you save.'}</p>
            )}
          </section>}
        >
          <FormSection title="Template details">
            <div className="studio-tpl-props__fields" aria-label="Template details">
              <TextField
                label="Template Title"
                value={title}
                onChange={setTitle}
                isRequired
                autoFocus
                validationState={showErrors && !title.trim() ? 'invalid' : undefined}
                errorMessage="The template needs a title"
              />
              <TextArea label="Description" value={description} onChange={setDescription} height="size-1250" />
            </div>
          </FormSection>
        </FormPage>
      )}
      <DialogContainer onDismiss={() => (setConfirmDiscard(null), setPicking(false))}>
        {confirmDiscard && (
          <StudioConfirmDialog
            title="Discard changes?"
            variant="destructive"
            primaryActionLabel="Discard"
            cancelLabel="Keep editing"
            onCancel={() => confirmDiscard(false)}
            onPrimaryAction={() => {
              const answer = confirmDiscard;
              setConfirmDiscard(null);
              answer(true);
            }}
          >
            The changes made to the template properties have not been saved.
          </StudioConfirmDialog>
        )}
        {picking && dam && <AssetPickerDialog client={dam as CfClient} root="/content/dam" onSelect={(p) => void selectAsset(p)} />}
      </DialogContainer>
      <ToastContainer />
    </div>
  );
}
