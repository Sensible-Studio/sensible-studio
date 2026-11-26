import Numbers from '@spectrum-icons/workflow/123';
import Calendar from '@spectrum-icons/workflow/Calendar';
import FileJson from '@spectrum-icons/workflow/FileJson';
import Image from '@spectrum-icons/workflow/Image';
import Labels from '@spectrum-icons/workflow/Labels';
import SplitView from '@spectrum-icons/workflow/SplitView';
import Switch from '@spectrum-icons/workflow/Switch';
import TextParagraph from '@spectrum-icons/workflow/TextParagraph';
import TextSize from '@spectrum-icons/workflow/TextSize';
import ViewList from '@spectrum-icons/workflow/ViewList';
import type { FieldKind } from './api';
import { RecordIcon } from '../shell/recordIcons';

const ICONS: Partial<Record<FieldKind, typeof TextSize>> = {
  text: TextSize,
  longtext: TextParagraph,
  number: Numbers,
  boolean: Switch,
  date: Calendar,
  enumeration: ViewList,
  tags: Labels,
  'content-reference': Image,
  'fragment-reference': RecordIcon,
  json: FileJson,
  tab: SplitView,
};

/** The icon of a model data type. Accepts Spectrum's slot props when used inside a ListView item. */
export function DataTypeIcon({ kind, ...rest }: { kind: FieldKind; slot?: string }) {
  const Icon = ICONS[kind] ?? TextSize;
  return <Icon size="S" aria-hidden marginEnd="size-100" {...rest} />;
}
