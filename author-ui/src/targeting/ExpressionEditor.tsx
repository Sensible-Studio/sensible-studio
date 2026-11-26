import { ActionButton, ComboBox, Item, Picker, TextField, Tooltip, TooltipTrigger } from '@adobe/react-spectrum';
import Add from '@spectrum-icons/workflow/Add';
import Delete from '@spectrum-icons/workflow/Delete';
import FolderAdd from '@spectrum-icons/workflow/FolderAdd';
import { OPERATORS, STORE_PROPERTIES, type Comparison, type Expression, type Logic } from './api';

interface Props {
  value: Logic;
  onChange(value: Logic): void;
  depth?: number;
  /** store/property names the configuration's ContextHub knows besides the usual ones */
  properties?: string[];
}

const PROPERTY_OPTIONS = STORE_PROPERTIES.flatMap((s) => s.properties.map((p) => ({ id: `${s.store}/${p}`, store: s.store })));
const NUMERIC = /\/(age|children|latitude|longitude|month|year|hour)$/;

export function emptyComparison(): Comparison {
  return { type: 'comparison', property: 'profile/age', operator: 'greater-than', value: '', dataType: 'number' };
}

/**
 * The conditions of an audience, like ContextHub's segment editor: a group matches all or any of its
 * items; an item is a comparison of a store property or a nested group.
 */
/** The usual store properties and the configuration's, each once. */
export function propertyOptions(extra: string[] = []): Array<{ id: string }> {
  const seen = new Set<string>();
  const out: Array<{ id: string }> = [];
  for (const id of [...PROPERTY_OPTIONS.map((o) => o.id), ...extra]) {
    if (seen.has(id)) continue;
    seen.add(id);
    out.push({ id });
  }
  return out;
}

export function ExpressionEditor({ value, onChange, depth = 0, properties }: Props) {
  const setItem = (i: number, item: Expression) => onChange({ ...value, items: value.items.map((x, j) => (j === i ? item : x)) });
  const removeItem = (i: number) => onChange({ ...value, items: value.items.filter((_, j) => j !== i) });
  return (
    <div className={`studio-expr studio-expr--${depth % 2 ? 'odd' : 'even'}`}>
      <div className="studio-expr__head">
        <span>Match</span>
        <Picker aria-label="Match" selectedKey={value.type} onSelectionChange={(k) => onChange({ ...value, type: k as 'and' | 'or' })} width="size-1250" isQuiet>
          <Item key="and">all of</Item>
          <Item key="or">any of</Item>
        </Picker>
        <span>these conditions</span>
        <span className="studio-expr__spacer" />
        <TooltipTrigger delay={400}>
          <ActionButton isQuiet aria-label="Add condition" onPress={() => onChange({ ...value, items: [...value.items, emptyComparison()] })}>
            <Add />
          </ActionButton>
          <Tooltip>Add condition</Tooltip>
        </TooltipTrigger>
        {depth < 3 && (
          <TooltipTrigger delay={400}>
            <ActionButton isQuiet aria-label="Add group" onPress={() => onChange({ ...value, items: [...value.items, { type: value.type === 'and' ? 'or' : 'and', items: [emptyComparison()] }] })}>
              <FolderAdd />
            </ActionButton>
            <Tooltip>Add a nested group</Tooltip>
          </TooltipTrigger>
        )}
      </div>
      {value.items.length === 0 && <p className="studio-expr__empty">No conditions yet: this audience matches nobody.</p>}
      {value.items.map((item, i) => (
        <div key={i} className="studio-expr__item">
          {item.type === 'comparison' ? (
            <ComparisonRow value={item} onChange={(c) => setItem(i, c)} onRemove={() => removeItem(i)} properties={properties} />
          ) : (
            <div className="studio-expr__group">
              <ExpressionEditor value={item} onChange={(g) => setItem(i, g)} depth={depth + 1} properties={properties} />
              <ActionButton isQuiet aria-label="Remove group" onPress={() => removeItem(i)}>
                <Delete />
              </ActionButton>
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

function ComparisonRow({ value, onChange, onRemove, properties }: { value: Comparison; onChange(c: Comparison): void; onRemove(): void; properties?: string[] }) {
  const op = OPERATORS.find((o) => o.id === value.operator);
  const setProperty = (p: string) => onChange({ ...value, property: p, dataType: NUMERIC.test(p) ? 'number' : value.dataType === 'number' && !NUMERIC.test(p) ? 'string' : value.dataType });
  return (
    <div className="studio-expr__row">
      <ComboBox aria-label="Store property" defaultItems={propertyOptions(properties)} inputValue={value.property} onInputChange={setProperty} allowsCustomValue width="size-2400" menuTrigger="focus">
        {(item) => <Item key={item.id}>{item.id}</Item>}
      </ComboBox>
      <Picker aria-label="Operator" selectedKey={value.operator} onSelectionChange={(k) => onChange({ ...value, operator: String(k) })} width="size-2000">
        {OPERATORS.map((o) => (
          <Item key={o.id}>{o.label}</Item>
        ))}
      </Picker>
      {!op?.unary && <TextField aria-label="Value" value={value.otherProperty ?? value.value ?? ''} onChange={(v) => onChange({ ...value, value: v, otherProperty: undefined })} width="size-1600" />}
      <Picker aria-label="Type" selectedKey={value.dataType ?? 'string'} onSelectionChange={(k) => onChange({ ...value, dataType: k as Comparison['dataType'] })} width="size-1250" isQuiet>
        <Item key="string">text</Item>
        <Item key="number">number</Item>
        <Item key="boolean">yes/no</Item>
      </Picker>
      <ActionButton isQuiet aria-label="Remove condition" onPress={onRemove}>
        <Delete />
      </ActionButton>
    </div>
  );
}
