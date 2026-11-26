import { ActionButton, DialogContainer, Flex, Item, Picker, ProgressCircle, TextArea, TextField } from '@adobe/react-spectrum';
import Remove from '@spectrum-icons/workflow/Remove';
import FolderOpen from '@spectrum-icons/workflow/FolderOpen';
import { useEffect, useMemo, useState, type Key } from 'react';
import { isValidName, languageName, nameFromTitle, nameOf, parentOf, TAG_ROOT, type TagDetail, type TagItem, type TagsClient } from './api';
import { TagPicker } from './TagPicker';
import { useTagReferences } from './views';
import { FormPage } from '../shell/FormPage';

function message(e: unknown) {
  return e instanceof Error ? e.message : String(e);
}

/** A path field with a button that opens the tag picker. */
function TagPathField({
  client,
  label,
  value,
  onChange,
  allowRoot,
  exclude,
  isRequired,
  browseFrom,
}: {
  client: TagsClient;
  label: string;
  value: string;
  onChange(v: string): void;
  allowRoot?: boolean;
  exclude?: string[];
  isRequired?: boolean;
  /** where the picker opens while the field is empty */
  browseFrom?: string;
}) {
  const [picking, setPicking] = useState(false);
  return (
    <>
      <Flex alignItems="end" gap="size-50">
        <TextField label={label} value={value} onChange={onChange} width="100%" isRequired={isRequired} necessityIndicator="icon" />
        <ActionButton aria-label={`Browse for ${label.toLowerCase()}`} onPress={() => setPicking(true)}>
          <FolderOpen />
        </ActionButton>
      </Flex>
      <DialogContainer onDismiss={() => setPicking(false)}>
        {picking && (
          <TagPicker
            client={client}
            title={label}
            start={value.startsWith(TAG_ROOT) ? value : (browseFrom ?? TAG_ROOT)}
            allowRoot={allowRoot}
            exclude={exclude}
            onPick={(p) => {
              setPicking(false);
              onChange(p);
            }}
          />
        )}
      </DialogContainer>
    </>
  );
}

// ------------------------------------------------------------------------------------------ create

interface CreateProps {
  client: TagsClient;
  /** where the tag goes; the tag root for a namespace */
  parent: string;
  namespace: boolean;
  onCancel(): void;
  onCreated(item: TagItem): void;
}

/** Create Tag / Create Namespace: Title, Name (derived from the title until edited), Description, Tag Path. */
export function CreateTagForm({ client, parent: initialParent, namespace, onCancel, onCreated }: CreateProps) {
  const [title, setTitle] = useState('');
  const [name, setName] = useState('');
  const [nameEdited, setNameEdited] = useState(false);
  const [description, setDescription] = useState('');
  const [parent, setParent] = useState(namespace ? TAG_ROOT : initialParent);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const effectiveName = nameEdited ? name : nameFromTitle(title);
  const nameOk = effectiveName !== '' && isValidName(effectiveName);
  const parentOk = namespace || (parent.startsWith(`${TAG_ROOT}/`) || parent === TAG_ROOT);

  const submit = async () => {
    setBusy(true);
    try {
      onCreated(await client.create(parent, title.trim(), effectiveName, description));
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind={namespace ? 'Namespace' : 'Tag'}
      title={namespace ? 'Create Namespace' : 'Create Tag'}
      formLabel={namespace ? 'Create Namespace' : 'Create Tag'}
      dirty={!!(title || name || description || parent !== (namespace ? TAG_ROOT : initialParent))}
      busy={busy}
      canSave={title.trim() !== '' && nameOk && parentOk}
      error={error}
      onReset={() => {
        setTitle('');
        setName('');
        setNameEdited(false);
        setDescription('');
        setParent(namespace ? TAG_ROOT : initialParent);
        setError(null);
      }}
      onClose={onCancel}
      onSave={submit}
    >
      <TextField label="Title" value={title} onChange={setTitle} isRequired necessityIndicator="icon" width="100%" autoFocus />
      <TextField
        label="Name"
        value={effectiveName}
        onChange={(v) => {
          setNameEdited(true);
          setName(v);
        }}
        isRequired
        necessityIndicator="icon"
        width="100%"
        validationState={effectiveName && !nameOk ? 'invalid' : undefined}
        errorMessage="Letters, digits, '_', '-' and '.', not starting with '.' or '-'."
      />
      <TextArea label="Description" value={description} onChange={setDescription} width="100%" height="size-1600" />
      {!namespace && <TagPathField client={client} label="Tag Path" value={parent} onChange={setParent} allowRoot />}
    </FormPage>
  );
}

// ------------------------------------------------------------------------------------------ edit

interface EditProps {
  client: TagsClient;
  path: string;
  onCancel(): void;
  onSaved(tag: TagDetail): void;
}

/** Edit Tag: title and description, and titles per language (`jcr:title.<lang>`). */
export function EditTagForm({ client, path, onCancel, onSaved }: EditProps) {
  const [tag, setTag] = useState<TagDetail | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [localized, setLocalized] = useState<Array<[string, string]>>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    client.tag(path).then(
      (t) => {
        if (!live) return;
        setTag(t);
        setTitle(t.title);
        setDescription(t.description ?? '');
        setLocalized(Object.entries(t.localized));
      },
      (e: unknown) => live && setError(message(e)),
    );
    return () => {
      live = false;
    };
  }, [client, path]);

  const available = useMemo(() => (tag?.languages ?? []).filter((l) => !localized.some(([x]) => x === l)), [tag, localized]);

  const submit = async () => {
    if (!tag) return;
    setBusy(true);
    // languages removed here are sent empty, which removes them
    const values: Record<string, string> = {};
    for (const lang of Object.keys(tag.localized)) values[lang] = '';
    for (const [lang, t] of localized) values[lang] = t;
    try {
      onSaved(await client.update(path, title.trim(), description, values));
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind="Tag"
      title="Edit Tag"
      path={path}
      formLabel="Edit Tag"
      dirty={!!tag && (title !== tag.title || description !== (tag.description ?? '') || JSON.stringify(localized) !== JSON.stringify(Object.entries(tag.localized)))}
      busy={busy}
      canSave={!!tag && title.trim() !== ''}
      error={error}
      onReset={() => {
        if (!tag) return;
        setTitle(tag.title);
        setDescription(tag.description ?? '');
        setLocalized(Object.entries(tag.localized));
        setError(null);
      }}
      onClose={onCancel}
      onSave={submit}
      wide
    >
      {!tag ? (
        !error && <ProgressCircle isIndeterminate aria-label="Loading" />
      ) : (
        <div className="studio-formpage__columns">
          <section aria-labelledby="studio-tag-details">
            <h2 id="studio-tag-details">Tag Details</h2>
            <TextField label="Title" value={title} onChange={setTitle} isRequired necessityIndicator="icon" width="100%" autoFocus />
            <TextArea label="Description" value={description} onChange={setDescription} width="100%" height="size-1600" />
          </section>
          <section aria-labelledby="studio-tag-l10n">
            <h2 id="studio-tag-l10n">Localization</h2>
            <Picker
              label="Languages"
              placeholder="Add Language"
              selectedKey={null}
              items={available.map((l) => ({ id: l, name: languageName(l) }))}
              isDisabled={!available.length}
              width="100%"
              onSelectionChange={(k: Key | null) => k != null && setLocalized((ls) => [...ls, [String(k), '']])}
            >
              {(l) => <Item key={l.id}>{l.name}</Item>}
            </Picker>
            {localized.map(([lang, t]) => (
              <Flex key={lang} alignItems="end" gap="size-50">
                <TextField
                  label={languageName(lang)}
                  value={t}
                  width="100%"
                  onChange={(v) => setLocalized((ls) => ls.map(([l, x]) => (l === lang ? [l, v] : [l, x])))}
                />
                <ActionButton aria-label={`Remove ${languageName(lang)}`} onPress={() => setLocalized((ls) => ls.filter(([l]) => l !== lang))}>
                  <Remove />
                </ActionButton>
              </Flex>
            ))}
          </section>
        </div>
      )}
    </FormPage>
  );
}

// ------------------------------------------------------------------------------------------ move & merge

function Information({ item }: { item: TagItem }) {
  return (
    <section aria-labelledby="studio-tag-info">
      <h2 id="studio-tag-info">Information</h2>
      <dl className="studio-formpage__info">
        <dt>Last modified on</dt>
        <dd>{item.lastModified ?? item.created ? new Date((item.lastModified ?? item.created)!).toLocaleString() : '—'}</dd>
        <dt>Last modified by</dt>
        <dd>{item.lastModifiedBy ?? '—'}</dd>
      </dl>
    </section>
  );
}

function UsageNote({ client, item, text }: { client: TagsClient; item: TagItem; text(n: number): string }) {
  const { refs } = useTagReferences(client, [item.path]);
  if (!refs) return null;
  return <p className="studio-formpage__note">{text(refs.length)}</p>;
}

interface ItemProps {
  client: TagsClient;
  item: TagItem;
  onCancel(): void;
  onDone(result: { path: string; adjusted: number }): void;
}

/** Move Tag: a new name and/or a new parent; content using it is updated. */
export function MoveTagForm({ client, item, onCancel, onDone }: ItemProps) {
  const [name, setName] = useState(item.name);
  const [dest, setDest] = useState(parentOf(item.path));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const unchanged = name === item.name && dest === parentOf(item.path);
  const destOk = dest === TAG_ROOT || dest.startsWith(`${TAG_ROOT}/`);

  const submit = async () => {
    setBusy(true);
    try {
      onDone(await client.move(item.path, dest, name));
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind="Tag"
      title="Move Tag"
      path={item.path}
      formLabel="Move Tag"
      dirty={!unchanged}
      busy={busy}
      canSave={!unchanged && isValidName(name) && destOk}
      error={error}
      onReset={() => {
        setName(item.name);
        setDest(parentOf(item.path));
        setError(null);
      }}
      onClose={onCancel}
      onSave={submit}
    >
      <h2>Move {item.title}</h2>
      <TextField label="Rename to" value={name} onChange={setName} width="100%" validationState={name && !isValidName(name) ? 'invalid' : undefined} />
      <TagPathField client={client} label="Move to" value={dest} onChange={setDest} allowRoot exclude={[item.path]} />
      <UsageNote
        client={client}
        item={item}
        text={(n) => (n ? `${n} page${n === 1 ? '' : 's'} or file${n === 1 ? '' : 's'} using it (or its sub-tags) will be updated.` : 'No page or file uses it.')}
      />
      <Information item={item} />
    </FormPage>
  );
}

/** Merge Tag: content tagged with this tag gets the target instead, then this tag is deleted. */
export function MergeTagForm({ client, item, onCancel, onDone }: ItemProps) {
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const targetOk = target.startsWith(`${TAG_ROOT}/`) && target !== item.path && !target.startsWith(`${item.path}/`) && !item.path.startsWith(`${target}/`);

  const submit = async () => {
    setBusy(true);
    try {
      onDone(await client.merge(item.path, target));
    } catch (e) {
      setError(message(e));
      setBusy(false);
    }
  };

  return (
    <FormPage
      kind="Tag"
      title="Merge Tag"
      path={item.path}
      formLabel="Merge Tag"
      dirty={!!target}
      busy={busy}
      canSave={targetOk}
      error={error}
      onReset={() => {
        setTarget('');
        setError(null);
      }}
      onClose={onCancel}
      onSave={submit}
    >
      <h2>Merge {item.title}</h2>
      <TextField label="Path" value={item.path} isDisabled width="100%" />
      <TagPathField client={client} label="Merge into" value={target} onChange={setTarget} exclude={[item.path]} isRequired browseFrom={parentOf(item.path)} />
      <UsageNote
        client={client}
        item={item}
        text={(n) =>
          `${n ? `${n} page${n === 1 ? '' : 's'} or file${n === 1 ? '' : 's'} tagged with ${item.title} will get ${target ? nameOf(target) : 'the target'} instead. ` : ''}${item.title} is deleted; its sub-tags move below the target.`
        }
      />
      <Information item={item} />
    </FormPage>
  );
}
