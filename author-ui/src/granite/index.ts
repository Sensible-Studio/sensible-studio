/**
 * @sensible-studio/granite-ui — renders AEM Granite UI dialogs (the merged `cq:dialog` resource tree) with React and
 * posts them the way AEM does, so existing component dialogs work unchanged on Sensible Studio.
 *
 * Public API. Everything else under `granite/` is internal.
 */
export type { ContentNode, DialogResponse, GraniteNode, JcrValue, PostEntry } from './types';
export {
  GraniteRegistry,
  type ComponentChoice,
  type DataSource,
  type GraniteComponent,
  type GraniteComponentProps,
  type GraniteEnv,
  type RenderCondition,
  type RepositoryItem,
  type SelectOption,
} from './registry';
export { createDefaultRegistry } from './builtins';
export { GraniteForm, useGraniteForm, type GraniteFormModel } from './GraniteForm';
export { FormStore, type FieldRegistration, type ValidationError } from './form/FormStore';
export { checkboxEntries, multiEntries, scalarEntries, toUrlEncoded } from './form/slingPost';
export { expandDialogDataSources, type DialogSources } from './expand';
export { prepareDialog, type PreparedDialog, type ShowHideController } from './prepare';
export { GraniteNodeView, GraniteItems, Unsupported } from './render';
export { useField, useFormStore, useGranite } from './context';
export { FieldShell, FieldInfo, commonFieldProps } from './components/FieldShell';
export { ASSET_DRAG_TYPE } from './components/fields/path';
export { InheritanceLock, InheritanceProvider, keyOf, lockKey, withoutInherited, type InheritanceModel } from './inheritance';
export { bool, child, designFromPolicy, items, normalizeResourceType, num, resourceTypeOf, str, titleOf } from './node';
export { styleGroups, type StyleGroup } from './components/fields/stylesystem';
import './styles/granite.scss';
