import {
  ActionButton,
  Checkbox,
  CheckboxGroup,
  DatePicker,
  Flex,
  Image,
  InlineAlert,
  Item,
  NumberField,
  Picker,
  Switch,
  TagGroup,
  Text,
  TextArea,
  TextField,
  Heading,
  Content,
  Tooltip,
  TooltipTrigger,
} from '@adobe/react-spectrum';
import { parseAbsoluteToLocal, parseDate, type CalendarDate, type ZonedDateTime } from '@internationalized/date';
import Add from '@spectrum-icons/workflow/Add';
import Close from '@spectrum-icons/workflow/Close';
import FolderOpen from '@spectrum-icons/workflow/FolderOpen';
import { useState } from 'react';
import type { CfClient, CfField } from './api';
import { isMulti, type FormValue } from './fields';
import { RichTextField } from './RichTextField';

export interface FieldEditorProps {
  field: CfField;
  value: FormValue;
  onChange(value: FormValue): void;
  error: string | null;
  contentType: string;
  onContentTypeChange(type: string): void;
  client: CfClient;
  /** Opens the asset or fragment picker; resolves with the chosen path. */
  browse(kind: 'asset' | 'fragment', field: CfField, current?: string): Promise<string | null>;
  isDisabled?: boolean;
}

const NONE = '__none__';

/** One element of the fragment, with the Spectrum input that fits its model type. */
export function FieldEditor(props: FieldEditorProps) {
  const { field, value, onChange, error } = props;
  const common = {
    label: field.label,
    description: error ? undefined : field.description,
    isRequired: field.required,
    validationState: error ? ('invalid' as const) : undefined,
    errorMessage: error ?? undefined,
    isDisabled: props.isDisabled,
    width: '100%' as const,
  };

  switch (field.kind) {
    case 'text':
      if (isMulti(field)) return <MultiText {...props} />;
      return (
        <TextField
          {...common}
          value={String(value ?? '')}
          onChange={onChange}
          placeholder={field.emptyText}
          maxLength={field.maxLength}
        />
      );

    case 'longtext':
      return (
        <RichTextField
          label={field.label}
          description={field.description}
          value={String(value ?? '')}
          onChange={onChange}
          contentType={props.contentType}
          onContentTypeChange={props.onContentTypeChange}
          isRequired={field.required}
          errorMessage={error}
          isDisabled={props.isDisabled}
        />
      );

    case 'number':
      if (isMulti(field)) return <MultiText {...props} numeric />;
      return (
        <NumberField
          {...common}
          width="size-3000"
          value={typeof value === 'number' ? value : NaN}
          onChange={(n) => onChange(Number.isFinite(n) ? n : null)}
          step={field.step && field.step !== 'any' ? Number(field.step) : undefined}
          minValue={field.validation?.min}
          maxValue={field.validation?.max}
          formatOptions={field.valueType === 'long' ? { maximumFractionDigits: 0 } : { maximumFractionDigits: 6 }}
        />
      );

    case 'boolean':
      return (
        <div className="studio-cf-boolean">
          <Switch isSelected={value === true} onChange={onChange} isDisabled={props.isDisabled}>
            {field.label}
          </Switch>
          {field.description && <div className="studio-cf-help">{field.description}</div>}
        </div>
      );

    case 'date':
      return <DateField {...props} />;

    case 'enumeration': {
      const options = field.options ?? [];
      if (isMulti(field)) {
        return (
          <CheckboxGroup {...common} value={Array.isArray(value) ? value : []} onChange={onChange} orientation="horizontal">
            {options.map((o) => (
              <Checkbox key={o.value} value={o.value}>
                {o.label}
              </Checkbox>
            ))}
          </CheckboxGroup>
        );
      }
      const items = [{ value: NONE, label: 'None' }, ...options];
      return (
        <Picker
          {...common}
          width="size-4600"
          selectedKey={value ? String(value) : NONE}
          onSelectionChange={(k) => onChange(k === NONE ? '' : String(k))}
          items={items}
        >
          {(o) => <Item key={o.value}>{o.label}</Item>}
        </Picker>
      );
    }

    case 'tags':
      return <TagsField {...props} />;

    case 'content-reference':
    case 'fragment-reference':
      if (isMulti(field)) return <MultiText {...props} reference />;
      return <ReferenceField {...props} />;

    case 'json':
      return (
        <TextArea
          {...common}
          value={String(value ?? '')}
          onChange={onChange}
          height="size-2400"
          UNSAFE_className="studio-cf-mono"
        />
      );

    default:
      return (
        <InlineAlert variant="notice">
          <Heading>{field.label}</Heading>
          <Content>
            The model type “{field.metaType}” is not supported by the Sensible Studio editor yet. The stored value is left unchanged.
          </Content>
        </InlineAlert>
      );
  }
}

// ------------------------------------------------------------------------------------------- variants

function DateField({ field, value, onChange, error, isDisabled }: FieldEditorProps) {
  const dateOnly = field.dateType === 'date';
  let parsed: CalendarDate | ZonedDateTime | null = null;
  if (typeof value === 'string' && value) {
    try {
      parsed = dateOnly ? parseDate(value.slice(0, 10)) : parseAbsoluteToLocal(new Date(value).toISOString());
    } catch {
      parsed = null;
    }
  }
  return (
    <DatePicker
      label={field.label}
      description={error ? undefined : field.description}
      isRequired={field.required}
      validationState={error ? 'invalid' : undefined}
      errorMessage={error ?? undefined}
      isDisabled={isDisabled}
      granularity={dateOnly ? 'day' : 'minute'}
      value={parsed}
      onChange={(d) => {
        if (!d) onChange('');
        else if ('toAbsoluteString' in d) onChange((d as ZonedDateTime).toAbsoluteString());
        else onChange(d.toString());
      }}
    />
  );
}

function TagsField({ field, value, onChange, isDisabled }: FieldEditorProps) {
  const tags = Array.isArray(value) ? value : [];
  const [draft, setDraft] = useState('');
  const add = () => {
    const t = draft.trim();
    if (t && !tags.includes(t)) onChange([...tags, t]);
    setDraft('');
  };
  return (
    <div>
      <TagGroup
        label={field.label}
        description={field.description}
        items={tags.map((t) => ({ id: t }))}
        onRemove={isDisabled ? undefined : (keys) => onChange(tags.filter((t) => !keys.has(t)))}
        renderEmptyState={() => <Text>No tags</Text>}
      >
        {(item) => <Item key={item.id}>{item.id}</Item>}
      </TagGroup>
      <TextField
        aria-label={`Add ${field.label}`}
        placeholder="namespace:tag/path, then Enter"
        value={draft}
        onChange={setDraft}
        onKeyDown={(e) => e.key === 'Enter' && add()}
        onBlur={add}
        isDisabled={isDisabled}
        marginTop="size-100"
        width="size-4600"
      />
    </div>
  );
}

function ReferenceField({ field, value, onChange, error, client, browse, isDisabled }: FieldEditorProps) {
  const path = String(value ?? '');
  const isAsset = field.kind === 'content-reference';
  const isImage = isAsset && /\.(png|jpe?g|gif|webp|svg|avif|tiff?)$/i.test(path);
  return (
    <div className="studio-cf-ref">
      {isImage && (
        <div className="studio-cf-ref__preview">
          <Image src={client.thumbnailUrl(path)} alt="" objectFit="cover" />
        </div>
      )}
      <Flex gap="size-100" alignItems="end" flex={1} minWidth={0}>
        <TextField
          label={field.label}
          description={error ? undefined : field.description}
          isRequired={field.required}
          validationState={error ? 'invalid' : undefined}
          errorMessage={error ?? undefined}
          value={path}
          onChange={onChange}
          placeholder={field.rootPath ?? '/content/dam'}
          isDisabled={isDisabled}
          width="100%"
          UNSAFE_className="studio-cf-mono"
        />
        <TooltipTrigger>
          <ActionButton
            aria-label={isAsset ? 'Browse files' : 'Browse content fragments'}
            isDisabled={isDisabled}
            onPress={async () => {
              const picked = await browse(isAsset ? 'asset' : 'fragment', field, path || undefined);
              if (picked) onChange(picked);
            }}
          >
            <FolderOpen />
          </ActionButton>
          <Tooltip>{isAsset ? 'Browse files' : 'Browse content fragments'}</Tooltip>
        </TooltipTrigger>
      </Flex>
    </div>
  );
}

/** Multi-value text, number or reference elements: one input per value, add and remove. */
function MultiText(props: FieldEditorProps & { numeric?: boolean; reference?: boolean }) {
  const { field, value, onChange, error, isDisabled, browse } = props;
  const items = Array.isArray(value) ? value : [];
  const set = (i: number, v: string) => onChange(items.map((x, j) => (j === i ? v : x)));
  return (
    <fieldset className="studio-cf-multi">
      <legend className="studio-cf-label">
        {field.label}
        {field.required && <span className="studio-cf-required"> *</span>}
      </legend>
      {items.length === 0 && <div className="studio-cf-help">No values</div>}
      {items.map((v, i) => (
        <Flex key={i} gap="size-100" alignItems="center" marginBottom="size-100">
          <TextField
            aria-label={`${field.label} ${i + 1}`}
            value={v}
            onChange={(nv) => set(i, nv)}
            inputMode={props.numeric ? 'decimal' : undefined}
            isDisabled={isDisabled}
            width="100%"
          />
          {props.reference && (
            <ActionButton
              aria-label="Browse"
              isDisabled={isDisabled}
              onPress={async () => {
                const picked = await browse(field.kind === 'content-reference' ? 'asset' : 'fragment', field, v || undefined);
                if (picked) set(i, picked);
              }}
            >
              <FolderOpen />
            </ActionButton>
          )}
          <ActionButton aria-label={`Remove value ${i + 1}`} isQuiet isDisabled={isDisabled} onPress={() => onChange(items.filter((_, j) => j !== i))}>
            <Close />
          </ActionButton>
        </Flex>
      ))}
      <ActionButton isDisabled={isDisabled} onPress={() => onChange([...items, ''])}>
        <Add />
        <Text>Add</Text>
      </ActionButton>
      {error ? <div className="studio-cf-error">{error}</div> : field.description ? <div className="studio-cf-help">{field.description}</div> : null}
    </fieldset>
  );
}
