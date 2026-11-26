import {
  ActionButton,
  Button,
  DialogContainer,
  Item,
  Picker,
  Section,
  Text,
  TextField,
  ToastContainer,
  ToastQueue,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import Refresh from '@spectrum-icons/workflow/Refresh';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';
import { ProductBar } from '../shell/ProductBar';
import { Link, useScreenTitle, useTab } from '../shell/ShellContext';
import { StudioConfirmDialog, StudioDialog } from '../shell/StudioDialog';
import { ToolBar } from '../shell/ToolCrumbs';
import { VOCAB } from '../shell/vocabulary';
import { formatTime, workflowUrl, type AutoAssignRule, type ModelSummary, type TemplateChoice, type WorkflowClient } from './api';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));
const last = (p: string) => p.slice(p.lastIndexOf('/') + 1);

/** What the rule form is checked for before it is sent. */
export function autoAssignProblems(rule: { template: string; workflow: string; rootPath: string }, others: AutoAssignRule[]): string[] {
  const out: string[] = [];
  if (!rule.template) out.push('Choose a template.');
  if (!rule.workflow) out.push(`Choose the ${VOCAB.flow.toLowerCase()} to start.`);
  const root = rule.rootPath.trim().replace(/\/+$/, '');
  if (!/^\/content\/[^/]+(\/[^/]+)*$/.test(root) || root.includes('..')) out.push('The path is a page or folder under /content, such as /content/wknd.');
  else if (others.some((o) => o.template === rule.template && o.workflow === rule.workflow && o.rootPath === root)) out.push('That rule exists already.');
  return out;
}

/**
 * AEM's Tools › Workflow › Auto-Assign (`/workflow/autoassign.html`): a page created from a template, below a path,
 * starts a flow on itself. A rule is the template, the flow and the path.
 */
export function AutoAssignConsole({ client, user }: { client: WorkflowClient; user: string | null }) {
  const tab = useTab();
  const [rules, setRules] = useState<AutoAssignRule[] | null>(null);
  const [templates, setTemplates] = useState<TemplateChoice[]>([]);
  const [models, setModels] = useState<ModelSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<AutoAssignRule | 'new' | null>(null);
  const [removing, setRemoving] = useState<AutoAssignRule | null>(null);

  useScreenTitle('Auto-Assign', `Auto-Assign | ${VOCAB.product}`);
  const load = useCallback(() => {
    setError(null);
    client.autoAssign().then(
      (r) => {
        setRules(r.rules);
        setTemplates(r.templates);
      },
      (e: unknown) => setError(message(e)),
    );
  }, [client]);
  useEffect(load, [load]);
  useEffect(() => {
    client.models().then(setModels, () => setModels([]));
  }, [client]);

  const templateTitle = (p: string) => templates.find((t) => t.path === p)?.title ?? last(p);
  const modelTitle = (id: string) => models.find((m) => m.id === id)?.title ?? last(id);

  return (
    <div className="studio-sites studio-wf studio-autoassign">
      {!tab && <ProductBar user={user} />}
      <ToolBar
        url={workflowUrl.autoassign()}
        end={
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Refresh" onPress={load}>
              <Refresh />
            </ActionButton>
            <Tooltip>Read the rules again</Tooltip>
          </TooltipTrigger>
        }
        primary={
          <Button variant="accent" onPress={() => setEditing('new')}>
            <Add />
            <Text>Add</Text>
          </Button>
        }
      />
      <main className="studio-sites__content studio-autoassign__body">
        {error ? (
          <ErrorState title="Could not read the Auto-Assign rules">{error}</ErrorState>
        ) : !rules ? (
          <LoadingState />
        ) : rules.length === 0 ? (
          <EmptyState title="No rule">
            {`A rule starts a ${VOCAB.flow.toLowerCase()} on every page created from a template below a path: a review for every new article, for instance.`}
          </EmptyState>
        ) : (
          <>
            <p className="studio-autoassign__intro">
              {`A page created from the template, below the path, starts the ${VOCAB.flow.toLowerCase()} on itself, as its author. Pages that exist already are not touched.`}
            </p>
            <table className="studio-autoassign__table">
              <thead>
                <tr>
                  <th>Template</th>
                  <th>Path</th>
                  <th>{VOCAB.flow}</th>
                  <th>Modified</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {rules.map((r) => (
                  <tr key={r.path}>
                    <td>
                      <strong>{templateTitle(r.template)}</strong>
                      <div className="studio-autoassign__muted">{r.template}</div>
                    </td>
                    <td>
                      <Link to={`/sites.html${r.rootPath}`} className="studio-autoassign__link">
                        <code>{r.rootPath}</code>
                      </Link>
                    </td>
                    <td>{modelTitle(r.workflow)}</td>
                    <td className="studio-autoassign__muted">{[formatTime(r.modified), r.modifiedBy].filter(Boolean).join(' · ')}</td>
                    <td className="studio-autoassign__actions">
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label={`Edit the rule of ${templateTitle(r.template)}`} onPress={() => setEditing(r)}>
                          <Edit />
                        </ActionButton>
                        <Tooltip>Edit</Tooltip>
                      </TooltipTrigger>
                      <TooltipTrigger delay={400}>
                        <ActionButton isQuiet aria-label={`Remove the rule of ${templateTitle(r.template)}`} onPress={() => setRemoving(r)}>
                          <Delete />
                        </ActionButton>
                        <Tooltip>Remove</Tooltip>
                      </TooltipTrigger>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </main>

      <DialogContainer onDismiss={() => setEditing(null)}>
        {editing && (
          <RuleDialog
            rule={editing === 'new' ? null : editing}
            others={(rules ?? []).filter((r) => editing === 'new' || r.path !== editing.path)}
            templates={templates}
            models={models}
            onCancel={() => setEditing(null)}
            onSave={async (input) => {
              await client.saveAutoAssign(input, editing === 'new' ? undefined : editing.path);
              setEditing(null);
              ToastQueue.positive(editing === 'new' ? 'Rule added' : 'Rule saved', { timeout: 3000 });
              load();
            }}
          />
        )}
      </DialogContainer>
      <DialogContainer onDismiss={() => setRemoving(null)}>
        {removing && (
          <StudioConfirmDialog
            title="Remove rule"
            variant="destructive"
            primaryActionLabel="Remove"
            cancelLabel="Cancel"
            onPrimaryAction={() =>
              client.deleteAutoAssign(removing.path).then(
                () => {
                  ToastQueue.positive('Rule removed', { timeout: 3000 });
                  load();
                },
                (e: unknown) => ToastQueue.negative(message(e), { timeout: 8000 }),
              )
            }
          >
            {`New pages from ${templateTitle(removing.template)} below ${removing.rootPath} no longer start ${modelTitle(removing.workflow)}. ${VOCAB.flows} started already go on.`}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
      <ToastContainer placement="bottom" />
    </div>
  );
}

function RuleDialog({
  rule,
  others,
  templates,
  models,
  onCancel,
  onSave,
}: {
  rule: AutoAssignRule | null;
  others: AutoAssignRule[];
  templates: TemplateChoice[];
  models: ModelSummary[];
  onCancel(): void;
  onSave(input: { template: string; workflow: string; rootPath: string }): Promise<void>;
}) {
  const [template, setTemplate] = useState(rule?.template ?? '');
  const [workflow, setWorkflow] = useState(rule?.workflow ?? '');
  const [rootPath, setRootPath] = useState(rule?.rootPath ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [tried, setTried] = useState(false);
  const problems = autoAssignProblems({ template, workflow, rootPath }, others);

  // templates by configuration, as the template console groups them
  const groups = useMemo(() => {
    const byConf = new Map<string, TemplateChoice[]>();
    for (const t of templates) byConf.set(t.conf, [...(byConf.get(t.conf) ?? []), t]);
    if (template && !templates.some((t) => t.path === template)) byConf.set('Other', [{ path: template, title: last(template), conf: 'Other' }]);
    return [...byConf.entries()].map(([conf, items]) => ({ conf, items }));
  }, [templates, template]);
  // flows that can be started: synchronised ones (and the rule's own)
  const flows = useMemo(() => {
    const synced = models.filter((m) => m.version);
    return workflow && !synced.some((m) => m.id === workflow) ? [...synced, { id: workflow, title: last(workflow) } as ModelSummary] : synced;
  }, [models, workflow]);

  const save = async () => {
    setTried(true);
    if (problems.length > 0) return;
    setBusy(true);
    setError(null);
    try {
      await onSave({ template, workflow, rootPath: rootPath.trim().replace(/\/+$/, '') });
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <StudioDialog
      title={rule ? 'Edit rule' : 'Add rule'}
      subject="Auto-Assign"
      primaryLabel={rule ? 'Save' : 'Add'}
      onCancel={onCancel}
      onPrimary={() => void save()}
      busy={busy}
    >
      <div className="studio-autoassign__form">
        <Picker
          label="Template"
          isRequired
          selectedKey={template || null}
          onSelectionChange={(k) => k !== null && setTemplate(String(k))}
          items={groups}
          width="100%"
          validationState={tried && !template ? 'invalid' : undefined}
        >
          {(g) => (
            <Section key={g.conf} title={g.conf.replace(/^\/conf\//, '')} items={g.items}>
              {(t) => (
                <Item key={t.path} textValue={t.title}>
                  {t.title}
                </Item>
              )}
            </Section>
          )}
        </Picker>
        <Picker
          label={VOCAB.flow}
          isRequired
          selectedKey={workflow || null}
          onSelectionChange={(k) => k !== null && setWorkflow(String(k))}
          items={flows}
          width="100%"
          validationState={tried && !workflow ? 'invalid' : undefined}
          description={`Only ${VOCAB.flows.toLowerCase()} that were synchronised can be started.`}
        >
          {(m) => <Item key={m.id}>{m.title}</Item>}
        </Picker>
        <TextField
          label="Path"
          isRequired
          value={rootPath}
          onChange={setRootPath}
          width="100%"
          description="Pages created anywhere below it, such as /content/wknd"
        />
        {tried && problems.length > 0 && (
          <ul className="studio-autoassign__problems">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}
        {error && (
          <p className="studio-autoassign__problems" role="alert">
            {error}
          </p>
        )}
      </div>
    </StudioDialog>
  );
}
