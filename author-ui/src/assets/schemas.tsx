import {
  ActionButton,
  Button,
  Checkbox,
  DatePicker,
  DialogContainer,
  Item,
  NumberField,
  Picker,
  TextArea,
  TextField,
} from '@adobe/react-spectrum';
import { parseDate } from '@internationalized/date';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import Edit from '@spectrum-icons/workflow/Edit';
import { useCallback, useEffect, useState } from 'react';
import type { DamClient, FieldValue, MetadataSchema, SchemaField, SchemaFieldType, SchemaTab } from './api';
import { TagsField } from './AssetDetails';
import { nodeName } from './dialogs';
import type { AssetsRoute } from './route';
import { AssetTagsField } from './tagsField';
import { useScreenTitle } from '../shell/ShellContext';
import { ToolBar } from '../shell/ToolCrumbs';
import { routeUrl } from './route';
import { FormPage } from '../shell/FormPage';
import { StudioConfirmDialog } from '../shell/StudioDialog';
import { EmptyState, ErrorState, LoadingState } from '../shell/FeedbackState';

/** The fields of the default Basic tab, a starting point for a new schema. */
export const DEFAULT_TABS: SchemaTab[] = [
  {
    title: 'Basic',
    fields: [
      { label: 'Title', property: 'dc:title', type: 'text' },
      { label: 'Description', property: 'dc:description', type: 'textarea' },
      { label: 'Tags', property: 'cq:tags', type: 'multi' },
      { label: 'Keywords', property: 'dc:subject', type: 'multi' },
      { label: 'Creator', property: 'dc:creator', type: 'multi' },
      { label: 'Language', property: 'dc:language', type: 'text' },
      { label: 'Copyright', property: 'dc:rights', type: 'text' },
    ],
  },
];

const TYPES: Array<[SchemaFieldType, string]> = [
  ['text', 'Single Line Text'],
  ['textarea', 'Multi Line Text'],
  ['multi', 'Multi Value Text'],
  ['number', 'Number'],
  ['date', 'Date'],
  ['checkbox', 'Checkbox'],
  ['select', 'Dropdown'],
];

// ------------------------------------------------------------------------------------- the form

/** The value a field shows for what is stored. */
export function fieldValue(field: SchemaField, stored: unknown): FieldValue {
  const list = Array.isArray(stored) ? stored.map(String) : stored === undefined || stored === null || stored === '' ? [] : [String(stored)];
  switch (field.type) {
    case 'multi':
      return list;
    case 'number':
      return typeof stored === 'number' ? stored : stored !== undefined && stored !== null && stored !== '' && !Number.isNaN(Number(stored)) ? Number(stored) : null;
    case 'checkbox':
      return stored === true || stored === 'true';
    case 'date': {
      const s = typeof stored === 'string' ? stored : '';
      return s ? { date: s.slice(0, 10) } : null;
    }
    default:
      return list.join(', ');
  }
}

/** Fields whose value differs from what was loaded, ready for {@link DamClient.saveFields}. */
export function changedValues(tabs: SchemaTab[], before: Record<string, FieldValue>, after: Record<string, FieldValue>): Record<string, FieldValue> {
  const out: Record<string, FieldValue> = {};
  for (const f of tabs.flatMap((t) => t.fields)) {
    if (JSON.stringify(before[f.property]) !== JSON.stringify(after[f.property])) {
      const v = after[f.property];
      out[f.property] = v === '' || (Array.isArray(v) && v.length === 0) ? null : v;
    }
  }
  return out;
}

export function missingRequired(tabs: SchemaTab[], values: Record<string, FieldValue>): string[] {
  return tabs
    .flatMap((t) => t.fields)
    .filter((f) => f.required && f.type !== 'checkbox')
    .filter((f) => {
      const v = values[f.property];
      return v === null || v === undefined || v === '' || (Array.isArray(v) && v.length === 0);
    })
    .map((f) => f.label);
}

/** One tab of a schema form. */
export function SchemaFields({ tab, values, onChange }: { tab: SchemaTab; values: Record<string, FieldValue>; onChange(property: string, v: FieldValue): void }) {
  return (
    <div className="studio-assets__props-form">
      {tab.fields.map((f) => {
        const v = values[f.property];
        const label = f.label;
        const common = { label, description: f.description, isRequired: f.required, width: '100%' as const };
        switch (f.type) {
          case 'textarea':
            return <TextArea key={f.property} {...common} value={(v as string) ?? ''} onChange={(x) => onChange(f.property, x)} />;
          case 'multi':
            return f.property === 'cq:tags' ? (
              <AssetTagsField key={f.property} label={label} values={(v as string[]) ?? []} onChange={(x) => onChange(f.property, x)} description={f.description} />
            ) : (
              <TagsField key={f.property} label={label} values={(v as string[]) ?? []} onChange={(x) => onChange(f.property, x)} />
            );
          case 'number':
            return (
              <NumberField
                key={f.property}
                {...common}
                width="size-2400"
                value={typeof v === 'number' ? v : Number.NaN}
                onChange={(x) => onChange(f.property, Number.isNaN(x) ? null : x)}
              />
            );
          case 'checkbox':
            return (
              <Checkbox key={f.property} isSelected={v === true} onChange={(x) => onChange(f.property, x)}>
                {label}
              </Checkbox>
            );
          case 'date': {
            let date = null;
            try {
              date = v && typeof v === 'object' && 'date' in v ? parseDate(v.date) : null;
            } catch {
              date = null;
            }
            return (
              <DatePicker
                key={f.property}
                label={label}
                description={f.description}
                isRequired={f.required}
                value={date}
                onChange={(d) => onChange(f.property, d ? { date: d.toString() } : null)}
              />
            );
          }
          case 'select':
            return (
              <Picker
                key={f.property}
                label={label}
                description={f.description}
                isRequired={f.required}
                selectedKey={(v as string) || null}
                onSelectionChange={(k) => onChange(f.property, String(k))}
                items={f.options ?? []}
                width="size-3600"
              >
                {(o) => <Item key={o.value}>{o.text}</Item>}
              </Picker>
            );
          default:
            return <TextField key={f.property} {...common} value={(v as string) ?? ''} onChange={(x) => onChange(f.property, x)} />;
        }
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------------- the console

/**
 * The three forms of Tools > Assets that are tabs of fields: metadata schemas (a file's Properties), folder
 * metadata schemas (a folder's Properties) and metadata profiles (default values for the files uploaded to a
 * folder). One console and one editor serve the three.
 */
export interface FormKindConfig {
  route: AssetsRoute;
  /** The console's title: "Metadata Schemas". */
  title: string;
  /** One of them, in a sentence: "metadata schema". */
  noun: string;
  list(client: DamClient): Promise<MetadataSchema[]>;
  save(client: DamClient, name: string, title: string, tabs: SchemaTab[], values?: Record<string, FieldValue>): Promise<MetadataSchema>;
  remove(client: DamClient, name: string): Promise<void>;
  /** What the empty console says. */
  empty: string;
  /** What deleting one does, for the confirmation. */
  deleting: string;
  starter: SchemaTab[];
  /** Fields carry default values (metadata profiles). */
  defaults?: boolean;
}

export const METADATA_SCHEMAS: FormKindConfig = {
  route: { kind: 'schemas' },
  title: 'Metadata Schemas',
  noun: 'metadata schema',
  list: async (c) => (await c.schemas()).schemas,
  save: (c, name, title, tabs) => c.saveSchema(name, title, tabs),
  remove: (c, name) => c.deleteSchema(name),
  empty: 'Without one, files get the standard Properties form. Create a schema, then choose it in the properties of a folder.',
  deleting: 'Folders that use it get the standard Properties form again. The metadata of the files stays.',
  starter: DEFAULT_TABS,
};

export const FOLDER_SCHEMAS: FormKindConfig = {
  route: { kind: 'folderschemas' },
  title: 'Folder Metadata Schemas',
  noun: 'folder metadata schema',
  list: async (c) => (await c.forms('folderSchema')).forms,
  save: (c, name, title, tabs) => c.saveForm('folderSchema', name, title, tabs),
  remove: (c, name) => c.deleteForm('folderSchema', name),
  empty: 'A folder metadata schema adds fields of your own to the properties of a folder: a campaign, an owner, a date. Create one, then choose it in the properties of a folder.',
  deleting: 'Folders that use it lose those fields in their properties. The values stay on the folders.',
  starter: [
    {
      title: 'Folder',
      fields: [
        { label: 'Description', property: 'dc:description', type: 'textarea' },
        { label: 'Owner', property: 'dc:contributor', type: 'text' },
      ],
    },
  ],
};

export const METADATA_PROFILES: FormKindConfig = {
  route: { kind: 'metadataprofiles' },
  title: 'Metadata Profiles',
  noun: 'metadata profile',
  list: async (c) => (await c.forms('profile')).forms,
  save: (c, name, title, tabs, values) => c.saveForm('profile', name, title, tabs, values ?? {}),
  remove: (c, name) => c.deleteForm('profile', name),
  empty: 'A metadata profile gives the files uploaded to a folder default values: a copyright, a creator, keywords. Create one, then choose it in the properties of a folder.',
  deleting: 'Folders that use it no longer give new files these values. The files keep what they got.',
  starter: DEFAULT_TABS,
  defaults: true,
};

const isEmptyValue = (v: FieldValue | undefined) => v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);

/** A profile's values that are set and belong to one of its fields. */
export function profileValues(tabs: SchemaTab[], values: Record<string, FieldValue>): Record<string, FieldValue> {
  const out: Record<string, FieldValue> = {};
  for (const f of tabs.flatMap((t) => t.fields)) {
    const v = values[f.property];
    if (!isEmptyValue(v) && !(f.type === 'checkbox' && v === false)) out[f.property] = v as FieldValue;
  }
  return out;
}

/** "Copyright: © WKND · Keywords: wknd, travel", for the console's list. */
export function valuesSummary(form: MetadataSchema): string {
  const fields = form.tabs.flatMap((t) => t.fields);
  const show = (v: FieldValue) => (Array.isArray(v) ? v.join(', ') : v && typeof v === 'object' ? v.date : String(v));
  return Object.entries(form.values ?? {})
    .map(([p, v]) => `${fields.find((f) => f.property === p)?.label ?? p}: ${show(v)}`)
    .join(' · ');
}

/** Tools > Assets > Metadata Schemas, Folder Metadata Schemas, Metadata Profiles. */
export function SchemasPage({ client, navigate: _navigate, kind = METADATA_SCHEMAS }: { client: DamClient; navigate(route: AssetsRoute): void; kind?: FormKindConfig }) {
  const [schemas, setSchemas] = useState<MetadataSchema[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<MetadataSchema | 'new' | null>(null);
  const [deleting, setDeleting] = useState<MetadataSchema | null>(null);

  const load = useCallback(async () => {
    try {
      setSchemas(await kind.list(client));
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [client, kind]);
  useScreenTitle(kind.title, `${kind.title} | Sensible Studio Media`);
  useEffect(() => {
    void load();
  }, [load]);

  if (editing) {
    return (
      <div className="studio-assets__profiles">
        <SchemaDialog
          client={client}
          kind={kind}
          schema={editing === 'new' ? null : editing}
          taken={(schemas ?? []).map((s) => s.name)}
          onSaved={(saved) => {
            setEditing(saved);
            void load();
          }}
          close={() => setEditing(null)}
        />
      </div>
    );
  }

  return (
    <div className="studio-assets__profiles">
      <ToolBar
        url={routeUrl(kind.route)}
        primary={
          <Button variant="accent" onPress={() => setEditing('new')}>
            <Add />
            Create
          </Button>
        }
      />
      {error ? (
        <ErrorState title={`${kind.title} could not be loaded`}>{error}</ErrorState>
      ) : schemas === null ? (
        <LoadingState label={`Loading ${kind.title.toLowerCase()}`} />
      ) : schemas?.length === 0 ? (
        <EmptyState title={`No ${kind.title.toLowerCase()}`}>{kind.empty}</EmptyState>
      ) : (
        <table className="studio-assets__profiles-table studio-table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Name</th>
              <th>{kind.defaults ? 'Default values' : 'Tabs'}</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {schemas?.map((s) => (
              <tr key={s.path}>
                <td>{s.title}</td>
                <td>
                  <code>{s.name}</code>
                </td>
                <td>{kind.defaults ? valuesSummary(s) || 'None yet' : s.tabs.map((t) => `${t.title} (${t.fields.length})`).join(', ')}</td>
                <td>
                  <ActionButton isQuiet aria-label={`Edit ${s.title}`} onPress={() => setEditing(s)}>
                    <Edit />
                  </ActionButton>
                  <ActionButton isQuiet aria-label={`Delete ${s.title}`} onPress={() => setDeleting(s)}>
                    <Delete />
                  </ActionButton>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <DialogContainer onDismiss={() => setDeleting(null)}>
        {deleting && (
          <StudioConfirmDialog
            title={`Delete ${kind.noun}`}
            variant="destructive"
            primaryActionLabel="Delete"
            cancelLabel="Cancel"
            onPrimaryAction={async () => {
              try {
                await kind.remove(client, deleting.name);
              } catch (e) {
                setError(e instanceof Error ? e.message : String(e));
              }
              void load();
            }}
            onCancel={() => setDeleting(null)}
          >
            You are going to delete <strong>{deleting.title}</strong>. {kind.deleting}
          </StudioConfirmDialog>
        )}
      </DialogContainer>
    </div>
  );
}

export function parseOptions(text: string): Array<{ value: string; text: string }> {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const i = s.indexOf('=');
      return i > 0 ? { value: s.slice(0, i).trim(), text: s.slice(i + 1).trim() || s.slice(0, i).trim() } : { value: s, text: s };
    });
}

const optionsText = (o?: Array<{ value: string; text: string }>) => (o ?? []).map((x) => (x.text && x.text !== x.value ? `${x.value}=${x.text}` : x.value)).join(', ');

function SchemaDialog({
  client,
  kind,
  schema,
  taken,
  onSaved,
  close,
}: {
  client: DamClient;
  kind: FormKindConfig;
  schema: MetadataSchema | null;
  taken: string[];
  onSaved(schema: MetadataSchema): void;
  close(): void;
}) {
  const [title, setTitle] = useState(schema?.title ?? '');
  const [tabs, setTabs] = useState<SchemaTab[]>(() => structuredClone(schema?.tabs ?? kind.starter));
  const [values, setValues] = useState<Record<string, FieldValue>>(() => structuredClone(schema?.values ?? {}));
  const [options, setOptions] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const name = schema?.name ?? nodeName(title);
  const setField = (t: number, i: number, patch: Partial<SchemaField>) =>
    setTabs((ts) => ts.map((tab, k) => (k === t ? { ...tab, fields: tab.fields.map((f, j) => (j === i ? { ...f, ...patch } : f)) } : tab)));
  const problems: string[] = [];
  if (!name) problems.push(`The ${kind.noun} needs a title.`);
  if (!schema && taken.includes(name)) problems.push(`A ${kind.noun} named ${name} exists already.`);
  const seen = new Set<string>();
  tabs.forEach((tab) => {
    if (!tab.title.trim()) problems.push('Every tab needs a title.');
    tab.fields.forEach((f) => {
      if (!/^[A-Za-z][A-Za-z0-9_-]*:[A-Za-z0-9_.-]+$/.test(f.property) || /^(jcr|rep|sling|oak|mix|nt):/.test(f.property))
        problems.push(`${f.label || 'A field'}: “${f.property}” is not a metadata property (use names such as dc:title).`);
      else if (seen.has(f.property)) problems.push(`${f.property} is used by two fields.`);
      seen.add(f.property);
      if (f.type === 'select' && (f.options ?? []).length === 0) problems.push(`${f.label}: a dropdown needs options.`);
    });
  });
  const dirty =
    title !== (schema?.title ?? '') ||
    JSON.stringify(tabs) !== JSON.stringify(schema?.tabs ?? kind.starter) ||
    (!!kind.defaults && JSON.stringify(profileValues(tabs, values)) !== JSON.stringify(profileValues(tabs, schema?.values ?? {})));
  const reset = () => {
    setTitle(schema?.title ?? '');
    setTabs(structuredClone(schema?.tabs ?? kind.starter));
    setValues(structuredClone(schema?.values ?? {}));
    setOptions({});
    setError(null);
  };
  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = await kind.save(client, name, title.trim() || name, tabs, kind.defaults ? profileValues(tabs, values) : undefined);
      onSaved(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind={kind.noun.charAt(0).toUpperCase() + kind.noun.slice(1)}
      title={schema ? `Edit ${schema.title}` : `Create ${kind.noun.replace(/\b\w/g, (c) => c.toUpperCase())}`}
      path={schema?.path}
      dirty={dirty}
      busy={busy}
      canSave={problems.length === 0}
      onReset={reset}
      onSave={save}
      onClose={close}
      error={error}
      wide
    >
        <TextField label="Title" value={title} onChange={setTitle} width="size-4600" autoFocus description={`Name: ${name || '—'}`} />
        {tabs.map((tab, t) => (
          <section key={t} className="studio-assets__schema-tab" aria-label={`Tab ${t + 1}`}>
            <div className="studio-assets__schema-tabhead">
              <TextField label="Tab" value={tab.title} onChange={(v) => setTabs((ts) => ts.map((x, k) => (k === t ? { ...x, title: v } : x)))} />
              <ActionButton isQuiet aria-label={`Remove tab ${tab.title}`} isDisabled={tabs.length === 1} onPress={() => setTabs((ts) => ts.filter((_, k) => k !== t))}>
                <Delete />
              </ActionButton>
            </div>
            {tab.fields.map((f, i) => {
              const key = `${t}:${i}`;
              return (
                <fieldset key={i} className="studio-assets__profiles-row" aria-label={`Field ${f.label || i + 1}`}>
                  <TextField label="Label" value={f.label} onChange={(v) => setField(t, i, { label: v })} width="size-2400" />
                  <TextField label="Property" value={f.property} onChange={(v) => setField(t, i, { property: v.trim() })} width="size-2400" />
                  <Picker label="Type" selectedKey={f.type} onSelectionChange={(k) => setField(t, i, { type: k as SchemaFieldType })} width="size-2400">
                    {TYPES.map(([k, l]) => (
                      <Item key={k}>{l}</Item>
                    ))}
                  </Picker>
                  {f.type === 'select' && (
                    <TextField
                      label="Options"
                      value={options[key] ?? optionsText(f.options)}
                      onChange={(v) => {
                        setOptions((o) => ({ ...o, [key]: v }));
                        setField(t, i, { options: parseOptions(v) });
                      }}
                      description="value=Text, value=Text"
                      width="size-3600"
                    />
                  )}
                  {!kind.defaults && (
                    <Checkbox isSelected={!!f.required} onChange={(v) => setField(t, i, { required: v })}>
                      Required
                    </Checkbox>
                  )}
                  <ActionButton
                    isQuiet
                    aria-label={`Remove field ${f.label || i + 1}`}
                    onPress={() => setTabs((ts) => ts.map((x, k) => (k === t ? { ...x, fields: x.fields.filter((_, j) => j !== i) } : x)))}
                  >
                    <Delete />
                  </ActionButton>
                </fieldset>
              );
            })}
            <ActionButton
              onPress={() => setTabs((ts) => ts.map((x, k) => (k === t ? { ...x, fields: [...x.fields, { label: '', property: '', type: 'text' }] } : x)))}
            >
              <Add />
              Add Field
            </ActionButton>
          </section>
        ))}
        <ActionButton onPress={() => setTabs((ts) => [...ts, { title: `Tab ${ts.length + 1}`, fields: [] }])}>
          <Add />
          Add Tab
        </ActionButton>
        {kind.defaults && (
          <section className="studio-assets__schema-tab studio-assets__profile-values" aria-label="Default values">
            <h3>Default values</h3>
            <p className="studio-assets__muted">
              What a file uploaded to a folder with this profile gets, for the properties it does not bring itself. A title or keywords
              in the file, or an author's edit, stay. Empty fields give nothing.
            </p>
            {tabs
              .map((tab) => ({ ...tab, fields: tab.fields.filter((f) => f.property && (f.type !== 'select' || (f.options ?? []).length > 0)) }))
              .filter((tab) => tab.fields.length > 0)
              .map((tab, t) => (
                <div key={t}>
                  {tabs.length > 1 && <h4>{tab.title}</h4>}
                  <SchemaFields tab={{ ...tab, fields: tab.fields.map((f) => ({ ...f, required: false })) }} values={values} onChange={(p, v) => setValues((x) => ({ ...x, [p]: v }))} />
                </div>
              ))}
          </section>
        )}
        {problems.length > 0 && (
          <ul className="studio-assets__dialog-error">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}
    </FormPage>
  );
}
