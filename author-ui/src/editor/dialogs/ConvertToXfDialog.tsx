import { Button, ButtonGroup, Content, Divider, Form, Heading, Item, Picker, Text, TextField, useDialogContainer } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { StudioClient, XfTemplate } from '../api/StudioClient';
import { StudioDialog } from '../../shell/StudioDialog';

interface Props {
  client: StudioClient;
  /** The components to convert (siblings). */
  paths: string[];
  title: string;
  /** The page the components are on: its site decides the default fragment folder. */
  pagePath: string;
  onConvert(options: { parent: string; title: string; template: string }): Promise<void>;
}

/** `/content/experience-fragments/<site>/…` for the page's language root when that folder exists, else the XF root. */
export function defaultXfParent(pagePath: string, exists: (path: string) => boolean): string {
  const m = pagePath.match(/^\/content\/([^/]+)(\/[^/]+\/[^/]+)?/);
  if (m) {
    const candidates = [`/content/experience-fragments/${m[1]}${m[2] ?? ''}`, `/content/experience-fragments/${m[1]}`];
    for (const c of candidates) if (exists(c)) return c;
  }
  return '/content/experience-fragments';
}

/**
 * AEM's "Convert to block variant": the components move into a new fragment whose
 * master variation is made from an experience fragment template; the page keeps a reference.
 */
export function ConvertToXfDialog({ client, paths, title: initialTitle, pagePath, onConvert }: Props) {
  const dialog = useDialogContainer();
  const [title, setTitle] = useState(initialTitle);
  const [parent, setParent] = useState('/content/experience-fragments');
  const [templates, setTemplates] = useState<XfTemplate[] | null>(null);
  const [template, setTemplate] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    (async () => {
      const m = pagePath.match(/^\/content\/([^/]+)(\/[^/]+\/[^/]+)?/);
      const candidates = m ? [`/content/experience-fragments/${m[1]}${m[2] ?? ''}`, `/content/experience-fragments/${m[1]}`] : [];
      for (const c of candidates) {
        const ok = await client
          .getContent(c)
          .then(() => true)
          .catch(() => false);
        if (ok && live) {
          setParent(c);
          break;
        }
      }
      const t = await client.xfTemplates().catch(() => []);
      if (!live) return;
      setTemplates(t);
      if (t.length) setTemplate(t[0].path);
    })();
    return () => {
      live = false;
    };
  }, [client, pagePath]);

  const submit = async () => {
    if (!template || !title.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await onConvert({ parent: parent.trim().replace(/\/$/, ''), title: title.trim(), template });
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Convert to experience fragment variant</Heading>
      <Divider />
      <Content>
        <Form
          onSubmit={(e) => {
            e.preventDefault();
            void submit();
          }}
        >
          <Text>
            {paths.length === 1 ? 'The component moves' : `The ${paths.length} components move`} into a new experience fragment; the page shows the
            experience fragment in their place.
          </Text>
          <TextField label="Title" value={title} onChange={setTitle} isRequired autoFocus />
          <TextField label="Parent path" value={parent} onChange={setParent} description="Folder or experience fragment the new variant is created under" />
          <Picker
            label="Template"
            items={templates ?? []}
            selectedKey={template}
            onSelectionChange={(k) => setTemplate(k === null ? null : String(k))}
            isDisabled={!templates}
            placeholder={templates && templates.length === 0 ? 'No experience fragment template found' : 'Choose'}
          >
            {(t) => <Item key={t.path}>{t.title}</Item>}
          </Picker>
        </Form>
        {error && <Text UNSAFE_style={{ color: 'var(--spectrum-global-color-red-600)' }}>{error}</Text>}
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void submit()} isDisabled={!template || !title.trim()} isPending={busy}>
          Convert
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
