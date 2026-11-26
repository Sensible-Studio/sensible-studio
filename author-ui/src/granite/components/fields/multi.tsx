import { useId, useState } from 'react';
import { useOptions } from './choice';
import type { GraniteComponentProps } from '../../registry';
import type { ContentNode, GraniteNode } from '../../types';
import { StoreScope, useField, useFormStore } from '../../context';
import { FormStore } from '../../form/FormStore';
import { multiEntries, scalarEntries } from '../../form/slingPost';
import { asString, asStringList, bool, child, contentChildren, isContentNode, namePath, str } from '../../node';
import { GraniteNodeView, Unsupported } from '../../render';
import { commonFieldProps, describedBy, FieldShell, requiredError } from '../FieldShell';
import { ArrowGlyph, TrashGlyph } from '../glyphs';

interface Item {
  key: string;
  store: FormStore;
}

let itemSeq = 0;
const newItem = (content: ContentNode): Item => ({ key: `item-${++itemSeq}`, store: new FormStore(content) });

/** Content for one item of a simple multifield: the value placed where the field's name points. */
function contentFor(name: string, value: string): ContentNode {
  const path = namePath(name);
  const root: ContentNode = {};
  let current = root;
  path.forEach((segment, i) => {
    if (i === path.length - 1) current[segment] = value;
    else {
      const next: ContentNode = {};
      current[segment] = next;
      current = next;
    }
  });
  return root;
}

/**
 * `granite/ui/components/coral/foundation/form/multifield`
 *
 * - simple: the `field` child is one field; all items post under the same name as a String[] property,
 * - composite (`composite=true`): the multifield's `name` is a node, each item a child node `item0..n`
 *   holding the fields of the `field` container (their names are relative to the item).
 */
export function MultiField({ node }: GraniteComponentProps) {
  const template = child(node, 'field');
  if (!template) return <Unsupported node={node} reason="Multifield without a field definition." />;
  return bool(node, 'composite') ? (
    <CompositeMultiField node={node} template={template} />
  ) : (
    <SimpleMultiField node={node} template={simpleField(template)} />
  );
}

/**
 * The field of a simple multifield. Some dialogs wrap it in a container that repeats the name (Core's page
 * policy: `field` is a container named `./clientlibs` around an autocomplete named `./clientlibs`); Granite posts the
 * inner field, so that is the one each item renders.
 */
function simpleField(template: GraniteNode): GraniteNode {
  if (!(str(template, 'sling:resourceType') ?? '').endsWith('/container')) return template;
  const find = (n: GraniteNode): GraniteNode | null => {
    for (const c of n.children ?? []) {
      if (str(c, 'name') && !(str(c, 'sling:resourceType') ?? '').endsWith('/container')) return c;
      const deeper = find(c);
      if (deeper) return deeper;
    }
    return null;
  };
  return find(template) ?? template;
}

function SimpleMultiField({ node, template }: { node: GraniteNode; template: GraniteNode }) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const name = str(template, 'name') ?? common.name;
  // Key the multifield's state under its own name so it never collides with the template field's.
  const stateKey = `${name}#multifield`;
  const binding = useField<Item[]>(
    stateKey,
    () => asStringList(store.contentValue(name)).map((v) => newItem(contentFor(name, v))),
    {
      label: common.label,
      entries: (v) => multiEntries(name, collect(v as Item[], name), str(template, 'typeHint') ?? 'String[]'),
      validate: (v) => requiredError(common.required, collect(v as Item[], name).length === 0),
      isDirty: () => (store.get<Item[]>(stateKey) ?? []).some((i) => i.store.isDirty()),
    },
  );
  return (
    <ItemsList
      node={node}
      template={template}
      binding={binding}
      label={common.label ?? str(template, 'fieldLabel')}
      description={common.description}
      required={common.required}
      create={() => newItem(contentFor(name, ''))}
    />
  );
}

function collect(items: Item[] | undefined, name: string): string[] {
  return (items ?? []).flatMap((i) => i.store.plainValues(name)).filter((v) => v !== '');
}

function CompositeMultiField({ node, template }: { node: GraniteNode; template: GraniteNode }) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  // Granite takes the item node name from the `field` container; older dialogs put it on the multifield.
  const name = str(template, 'name') ?? common.name;
  const binding = useField<Item[]>(
    name,
    () => {
      const stored = name ? store.contentValue(name) : undefined;
      return isContentNode(stored) ? contentChildren(stored).map(([, content]) => newItem(content)) : [];
    },
    {
      label: common.label,
      entries: (v) => {
        const items = (v as Item[]) ?? [];
        const base = name.replace(/\/$/, '');
        return [
          { name: `${base}@Delete`, value: '' },
          ...items.flatMap((item, i) => item.store.serialize(`${base}/item${i}`)),
        ];
      },
      validate: (v) => requiredError(common.required, ((v as Item[]) ?? []).length === 0),
      isDirty: () => (store.get<Item[]>(name) ?? []).some((i) => i.store.isDirty()),
    },
  );
  if (!name) return <Unsupported node={node} reason="Composite multifield without a name." />;
  return (
    <ItemsList
      node={node}
      template={template}
      binding={binding}
      label={common.label}
      description={common.description}
      required={common.required}
      create={() => newItem({})}
    />
  );
}

interface ItemsListProps {
  node: GraniteNode;
  template: GraniteNode;
  binding: { id: string; value: Item[]; setValue(v: Item[]): void; error: string | null; disabled: boolean };
  label?: string;
  description?: string;
  required: boolean;
  create(): Item;
}

function ItemsList({ node, template, binding, label, description, required, create }: ItemsListProps) {
  const { id, value, setValue, error } = binding;
  const items = value ?? [];
  const disabled = bool(node, 'disabled') || binding.disabled;
  const move = (from: number, to: number) => {
    if (to < 0 || to >= items.length) return;
    const next = [...items];
    const [m] = next.splice(from, 1);
    next.splice(to, 0, m);
    setValue(next);
  };
  return (
    <FieldShell id={id} label={label} required={required} description={description} error={error}>
      <div className="studio-g-multifield" id={id} aria-describedby={describedBy(id, description, error)}>
        {items.length === 0 && <div className="studio-g-multifield__empty">No items</div>}
        <ol className="studio-g-multifield__items">
          {items.map((item, i) => (
            <li key={item.key} className="studio-g-multifield__item">
              <div className="studio-g-multifield__body">
                <StoreScope store={item.store}>
                  <GraniteNodeView node={template} />
                </StoreScope>
              </div>
              <div className="studio-g-multifield__actions">
                <button type="button" aria-label="Move up" title="Move up" disabled={disabled || i === 0} onClick={() => move(i, i - 1)}>
                  <ArrowGlyph up />
                </button>
                <button
                  type="button"
                  aria-label="Move down"
                  title="Move down"
                  disabled={disabled || i === items.length - 1}
                  onClick={() => move(i, i + 1)}
                >
                  <ArrowGlyph />
                </button>
                <button
                  type="button"
                  aria-label="Remove item"
                  title="Remove"
                  disabled={disabled}
                  onClick={() => setValue(items.filter((x) => x !== item))}
                >
                  <TrashGlyph />
                </button>
              </div>
            </li>
          ))}
        </ol>
        <button type="button" className="studio-g-btn studio-g-multifield__add" disabled={disabled} onClick={() => setValue([...items, create()])}>
          Add
        </button>
      </div>
    </FieldShell>
  );
}

/**
 * `granite/ui/components/coral/foundation/form/autocomplete` and `cq/gui/components/coral/common/form/tagfield`:
 * free-entry token list, with the options of a `datasource` (e.g. Core's client library categories) as suggestions;
 * values are stored verbatim (tag IDs like `wknd-shared:activity/surfing` for tag fields).
 */
export function TokenField({ node }: GraniteComponentProps) {
  const store = useFormStore();
  const common = commonFieldProps(node);
  const isTagField = (str(node, 'sling:resourceType') ?? '').includes('tagfield');
  const multiple = bool(node, 'multiple', isTagField);
  const [draft, setDraft] = useState('');
  const suggestions = useOptions(node).options;
  const listId = useId();
  const { id, value, setValue, error, disabled: ruleDisabled } = useField<string[]>(
    common.name,
    () => asStringList(common.name ? store.contentValue(common.name) : undefined),
    {
      label: common.label,
      entries: (v) => {
        const list = (v as string[]) ?? [];
        return multiple ? multiEntries(common.name, list) : scalarEntries(common.name, asString(list[0] ?? ''));
      },
      values: (v) => (v as string[]) ?? [],
      validate: (v) => requiredError(common.required, ((v as string[]) ?? []).length === 0),
    },
  );
  const tokens = value ?? [];
  const add = (text = draft) => {
    const t = text.trim();
    if (!t) return;
    setValue(multiple ? (tokens.includes(t) ? tokens : [...tokens, t]) : [t]);
    setDraft('');
  };
  return (
    <FieldShell id={id} label={common.label} required={common.required} description={common.description} error={error}>
      <div className="studio-g-tokens">
        {tokens.map((t) => (
          <span key={t} className="studio-g-tokens__token">
            {t}
            <button type="button" aria-label={`Remove ${t}`} onClick={() => setValue(tokens.filter((x) => x !== t))}>
              ×
            </button>
          </span>
        ))}
        <input
          id={id}
          className="studio-g-tokens__input"
          value={draft}
          placeholder={common.emptyText ?? (isTagField ? 'namespace:tag/path' : 'Type and press Enter')}
          disabled={common.disabled || common.readOnly || ruleDisabled}
          aria-describedby={describedBy(id, common.description, error)}
          list={suggestions.length ? listId : undefined}
          onChange={(e) => {
            // a suggestion picked from the list is added at once, like Coral's autocomplete
            const picked = (e.nativeEvent as InputEvent).inputType === 'insertReplacementText' || !(e.nativeEvent as InputEvent).inputType;
            if (picked && suggestions.some((o) => o.value === e.target.value)) add(e.target.value);
            else setDraft(e.target.value);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ',') {
              e.preventDefault();
              add();
            } else if (e.key === 'Backspace' && !draft && tokens.length) {
              setValue(tokens.slice(0, -1));
            }
          }}
          onBlur={() => add()}
        />
        {suggestions.length > 0 && (
          <datalist id={listId}>
            {suggestions
              .filter((o) => !tokens.includes(o.value))
              .map((o) => (
                <option key={o.value} value={o.value}>
                  {o.text !== o.value ? o.text : undefined}
                </option>
              ))}
          </datalist>
        )}
      </div>
    </FieldShell>
  );
}
