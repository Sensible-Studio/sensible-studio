import { Button, ButtonGroup, Checkbox, Content, Divider, Heading, ProgressCircle, Text, ToastQueue, useDialogContainer } from '@adobe/react-spectrum';
import { useEffect, useState } from 'react';
import type { ReplicationClient } from '../replication/api';
import type { TemplatePolicy, TemplatesClient, TemplateSummary } from './api';
import { StudioDialog } from '../shell/StudioDialog';

interface Props {
  client: TemplatesClient;
  replication: ReplicationClient;
  templates: TemplateSummary[];
  onDone(): void;
}

type Row = TemplatePolicy & { status: 'never' | 'modified' | 'deactivated' };

const STATUS: Record<Row['status'], string> = { never: 'Not published', modified: 'Modified since published', deactivated: 'Unpublished' };

/**
 * Publish for templates, as AEM's: the template goes with its structure, initial content and policy mappings (its
 * parts are pages below it), and the content policies it maps that publish does not have yet are listed, ticked, to
 * go with it. Publish replicates the ticked policies, then the templates.
 */
export function TemplatePublishDialog({ client, replication, templates, onDone }: Props) {
  const dialog = useDialogContainer();
  const [rows, setRows] = useState<Row[] | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    (async () => {
      const all: TemplatePolicy[] = [];
      for (const t of templates) {
        for (const p of await client.policies(t.path).catch(() => [])) if (!all.some((x) => x.path === p.path)) all.push(p);
      }
      const status = all.length ? await replication.status(all.map((p) => p.path)).catch(() => []) : [];
      const out: Row[] = [];
      for (const p of all) {
        const s = status.find((x) => x.path === p.path);
        if (s?.published) continue;
        out.push({ ...p, status: !s?.lastReplicationAction ? 'never' : s.lastReplicationAction === 'Deactivate' ? 'deactivated' : 'modified' });
      }
      if (!live) return;
      setRows(out);
      setSelected(new Set(out.map((r) => r.path)));
    })();
    return () => {
      live = false;
    };
  }, [client, replication, templates]);

  const publish = async () => {
    setBusy(true);
    setError(null);
    try {
      const policies = [...selected];
      if (policies.length) await replication.activate(policies);
      await replication.activate(
        templates.map((t) => t.path),
        { deep: true },
      );
      ToastQueue.positive(
        `${templates.length === 1 ? `“${templates[0].title}”` : `${templates.length} templates`} published${policies.length ? ` with ${policies.length} polic${policies.length === 1 ? 'y' : 'ies'}` : ''}`,
        { timeout: 5000 },
      );
      onDone();
      dialog.dismiss();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <StudioDialog size="M">
      <Heading>Publish {templates.length === 1 ? 'Template' : `${templates.length} Templates`}</Heading>
      <Divider />
      <Content>
        <div className="studio-tpl-publish">
          <p className="studio-tpl-publish__what">
            {templates.length === 1 ? <strong>{templates[0].title}</strong> : `${templates.length} templates`}, with the structure, initial content and policy
            mappings.
          </p>
          {rows === null ? (
            <ProgressCircle size="S" isIndeterminate aria-label="Checking the configurations" />
          ) : rows.length === 0 ? (
            <Text>The configurations the template uses are already published.</Text>
          ) : (
            <>
              <p className="studio-tpl-publish__label">Configurations that are not on publish yet:</p>
              <ul className="studio-tpl-publish__list">
                {rows.map((r) => (
                  <li key={r.path}>
                    <Checkbox
                      isSelected={selected.has(r.path)}
                      onChange={(on) =>
                        setSelected((s) => {
                          const n = new Set(s);
                          if (on) n.add(r.path);
                          else n.delete(r.path);
                          return n;
                        })
                      }
                    >
                      {r.title}
                    </Checkbox>
                    <span className="studio-tpl-publish__meta">
                      {r.component} · {STATUS[r.status]}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
          {error && (
            <p className="studio-tpl-publish__error" role="alert">
              {error}
            </p>
          )}
        </div>
      </Content>
      <ButtonGroup>
        <Button variant="secondary" onPress={dialog.dismiss} isDisabled={busy}>
          Cancel
        </Button>
        <Button variant="accent" onPress={() => void publish()} isDisabled={busy || rows === null} isPending={busy}>
          Publish
        </Button>
      </ButtonGroup>
    </StudioDialog>
  );
}
