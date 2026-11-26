import { GraniteRegistry, type SelectOption } from './registry';
import type { GraniteNode } from './types';
import {
  Accordion,
  Alert,
  AuthoringDialog,
  Button,
  Container,
  Fieldset,
  FixedColumns,
  Heading,
  Include,
  Tabs,
  Text,
  ShowWhen,
  Well,
} from './components/layout';
import { ColorField, DatePicker, Hidden, NumberField, TextArea, TextField } from './components/fields/text';
import { Checkbox, RadioGroup, Select, Switch } from './components/fields/choice';
import { FileUpload, PathField } from './components/fields/path';
import { PageImageThumbnail } from './components/fields/pageimage';
import { ChildrenEditor } from './components/fields/children';
import { MultiField, TokenField } from './components/fields/multi';
import { RichText } from './components/fields/richtext';
import { hasStyles, StyleId, StyleSelector } from './components/fields/stylesystem';
import { TagField } from './components/fields/tags';
import { InheritedField } from './components/fields/inherited';
import { PageThumbnail } from './components/fields/pagethumbnail';
import { ConfPathField } from './components/fields/confpath';
import { AllowedComponentsField, AssetMappingsField } from './components/fields/policyfields';
import { languageOptions } from './components/fields/languages';
import {
  allowedColorSwatches,
  allowedHeadingElements,
  allowedTitleTypes,
  clientLibraryCategories,
  fragmentElements,
  fragmentListOrderBy,
  fragmentModels,
  fragmentPathKey,
  fragmentVariations,
  hook,
  modelElements,
  modelPathKey,
  PANEL_ITEMS_DATASOURCE,
  panelItemsDataSource,
  rewrite,
} from './components/fields/coreDataSources';
import { DM_EDITOR_CLIENTLIB, dynamicMediaTransform, fileReferenceKey, GALLERY_PRESETS_DATASOURCE, galleryPresets, imagePresets, SMART_CROPS_DATASOURCE, smartCrops } from './components/fields/dynamicmedia';
import { ExpandedItems } from './components/fields/panelcontainer';
import { CfElementNames } from './components/fields/cfelements';
import { SHOW_WHEN } from './expand';

const CORAL = 'granite/ui/components/coral/foundation';

/**
 * A registry with every widget Sensible Studio implements. Covers all `coral/foundation` types used by Core Components
 * 2.33 and the AEM authoring widgets that appear in their dialogs.
 */
export function createDefaultRegistry(): GraniteRegistry {
  const r = new GraniteRegistry();

  // dialog + layout
  r.registerComponent('cq/gui/components/authoring/dialog', AuthoringDialog);
  r.registerComponent(`${CORAL}/container`, Container);
  r.registerComponent(`${CORAL}/fixedcolumns`, FixedColumns);
  r.registerComponent(`${CORAL}/tabs`, Tabs);
  r.registerComponent(`${CORAL}/accordion`, Accordion);
  r.registerComponent(`${CORAL}/well`, Well);
  r.registerComponent(`${CORAL}/form/fieldset`, Fieldset);
  r.registerComponent(`${CORAL}/heading`, Heading);
  r.registerComponent(`${CORAL}/text`, Text);
  r.registerComponent(`${CORAL}/alert`, Alert);
  r.registerComponent(`${CORAL}/button`, Button);
  r.registerComponent(`${CORAL}/include`, Include);

  // fields
  r.registerComponent(`${CORAL}/form/textfield`, TextField);
  r.registerComponent(`${CORAL}/form/textarea`, TextArea);
  r.registerComponent(`${CORAL}/form/numberfield`, NumberField);
  r.registerComponent(`${CORAL}/form/hidden`, Hidden);
  r.registerComponent(`${CORAL}/form/colorfield`, ColorField);
  r.registerComponent(`${CORAL}/form/datepicker`, DatePicker);
  r.registerComponent(`${CORAL}/form/checkbox`, Checkbox);
  r.registerComponent(`${CORAL}/form/switch`, Switch);
  r.registerComponent(`${CORAL}/form/select`, Select);
  r.registerComponent(`${CORAL}/form/radiogroup`, RadioGroup);
  r.registerComponent(`${CORAL}/form/pathfield`, PathField);
  r.registerComponent(`${CORAL}/form/pathbrowser`, PathField);
  r.registerComponent(`${CORAL}/form/multifield`, MultiField);
  r.registerComponent(`${CORAL}/form/autocomplete`, TokenField);

  // AEM authoring widgets
  r.registerComponent('cq/gui/components/coral/common/form/pagefield', PathField);
  r.registerComponent('cq/gui/components/coral/common/form/tagfield', TagField);
  r.registerComponent('cq/gui/components/common/wcm/pagethumbnail', PageThumbnail);
  r.registerComponent('core/wcm/components/commons/editor/dialog/inherited/v1/inherited', InheritedField);
  r.registerComponent('studio/granite/components/confpath', ConfPathField);
  // policies (template editor): a layout container's Allowed Components and Default Components
  r.registerComponent('studio/granite/components/allowedcomponents', AllowedComponentsField);
  r.registerComponent('studio/granite/components/assetmappings', AssetMappingsField);
  r.registerDataSource('studio/granite/datasources/languages', languageOptions);
  r.registerComponent('cq/gui/components/authoring/dialog/richtext', RichText);
  r.registerComponent('cq/gui/components/authoring/dialog/fileupload', FileUpload);
  r.registerComponent('core/wcm/components/commons/editor/dialog/pageimagethumbnail/v1/pageimagethumbnail', PageImageThumbnail);
  r.registerComponent('core/wcm/components/commons/editor/dialog/childreneditor/v1/childreneditor', ChildrenEditor);
  r.registerComponent('cq/experience-fragments/editor/components/xffield', PathField);
  r.registerComponent('dam/cfm/components/cfpicker', PathField);

  // Sensible Studio widgets used by Sensible Studio's own authoring content (studio-authoring-content)
  r.registerComponent('studio/granite/components/stylesystem/styleselector', StyleSelector);
  r.registerComponent('studio/granite/components/stylesystem/styleid', StyleId);
  r.registerRenderCondition('studio/granite/renderconditions/hasstyles', hasStyles);

  // configuration nodes read by their parent widget, never rendered on their own
  r.registerSilent('cq/gui/components/authoring/dialog/inplaceediting');
  r.registerSilent(`${CORAL}/form/autocomplete/list`);
  r.registerSilent('granite/ui/components/foundation/form/autocomplete/list');

  // render conditions
  // `expression` was already evaluated by prepareDialog (see el.ts); what is left is a boolean or unknown.
  const simple = (node: GraniteNode) => {
    const v = node.properties['expression'];
    if (v === undefined) return true; // evaluated to nothing (e.g. empty policy): Granite treats as true
    if (typeof v === 'boolean') return v;
    const s = String(v).trim();
    if (s === 'true') return true;
    if (s === 'false') return false;
    return undefined;
  };
  r.registerRenderCondition(`${CORAL}/renderconditions/simple`, simple);
  r.registerRenderCondition('granite/ui/components/foundation/renderconditions/simple', simple);
  // Adobe cloud features that do not exist on Sensible Studio.
  r.registerRenderCondition('core/wcm/components/rendercondition/isAssetDeliveryEnabled', () => false);
  r.registerRenderCondition('core/wcm/components/rendercondition/isNGDMEnabled', () => false);
  r.registerRenderCondition('core/wcm/components/renderconditions/imagedelegate', () => false);
  // AEM product feature toggles (e.g. Visual Content Fragments): off on Sensible Studio
  r.registerRenderCondition('granite/ui/components/renderconditions/featuretoggle', () => false);

  // Editor client libraries of Core Components, re-expressed as field rules (see DialogBehavior).
  // Image v3 (also used by Teaser v2): either the page's featured image or an asset of its own, and the alt
  // text from the matching source (page image alt / asset description) or typed in.
  const fromPage = { field: './imageFromPageImage', is: true };
  const notFromPage = { field: './imageFromPageImage', is: true, not: true };
  r.registerBehavior('core.wcm.components.image.v3.editor', {
    // Content from before the option existed: an image with its own asset does not inherit the page's.
    initialValues: (content) =>
      content.imageFromPageImage === undefined && typeof content.fileReference === 'string' && content.fileReference
        ? { ...content, imageFromPageImage: 'false' }
        : content,
    disable: [
      {
        field: './alt',
        when: [
          { field: './isDecorative', is: true },
          { all: [fromPage, { field: './altValueFromPageImage', is: true }] },
          { all: [notFromPage, { field: './altValueFromDAM', is: true }] },
        ],
      },
      { field: './altValueFromDAM', when: [{ field: './isDecorative', is: true }] },
      { field: './altValueFromPageImage', when: [{ field: './isDecorative', is: true }] },
      { field: './jcr:title', when: [{ field: './titleValueFromDAM', is: true }] },
      { field: './fileReference', when: [fromPage] },
    ],
    hide: [
      { target: 'file', when: [fromPage] },
      { target: 'pageImageThumbnail', when: [notFromPage] },
      { target: './altValueFromPageImage', when: [notFromPage] },
      { target: './altValueFromDAM', when: [fromPage] },
    ],
    hint: [
      {
        field: './alt',
        when: [{ all: [notFromPage, { field: './altValueFromDAM', is: true }, { field: './isDecorative', is: true, not: true }] }],
        assetDescription: './fileReference',
      },
    ],
  });
  r.registerBehavior('core.wcm.components.image.v2.editor', {
    disable: [
      { field: './alt', when: [{ field: './altValueFromDAM', is: true }, { field: './isDecorative', is: true }] },
      { field: './jcr:title', when: [{ field: './titleValueFromDAM', is: true }] },
    ],
    hint: [
      {
        field: './alt',
        when: [{ all: [{ field: './altValueFromDAM', is: true }, { field: './isDecorative', is: true, not: true }] }],
        assetDescription: './fileReference',
      },
    ],
  });
  r.registerBehavior('core.wcm.components.teaser.v2.editor', {
    disable: [
      { field: './jcr:title', when: [{ field: './titleFromPage', is: true }] },
      { field: './jcr:description', when: [{ field: './descriptionFromPage', is: true }] },
    ],
  });
  r.registerBehavior('core.wcm.components.teaser.v1.editor', {
    disable: [
      { field: './jcr:title', when: [{ field: './titleFromPage', is: true }] },
      { field: './jcr:description', when: [{ field: './descriptionFromPage', is: true }] },
    ],
  });
  // Embed: the Type radio shows the fields of the chosen kind only (URL, an embeddable, or HTML).
  const embedType = (kind: string) => ({ field: './type', is: kind, not: true });
  r.registerBehavior('core.wcm.components.embed.v1.editor', {
    hide: [
      { target: 'url', when: [embedType('url')] },
      { target: 'embeddable', when: [embedType('embeddable')] },
      { target: 'html', when: [embedType('html')] },
    ],
  });
  // Title v2 editor: of the two `./type` selects it keeps the policy's (datasource) one when the policy allows
  // several types, the static one when it allows none, and neither when it allows exactly one.
  r.registerBehavior('core.wcm.components.title.v2.editor', {
    transform: (root, env) => {
      const allowed = allowedTitleTypes(root, env, NO_FORM) as SelectOption[];
      return rewrite(root, (n) => {
        const classes = String(n.properties['granite:class'] ?? '').split(/\s+/);
        if (classes.includes('core-title-sizes')) return allowed.length > 1 ? n : null;
        if (classes.includes('core-title-sizes-default')) return allowed.length === 0 ? n : null;
        return n;
      });
    },
  });

  // Core Components datasources (Java servlets on AEM), from the policy
  r.registerDataSource('core/wcm/components/commons/datasources/allowedheadingelements/v1', allowedHeadingElements);
  r.registerDataSource('core/wcm/components/title/v1/datasource/allowedtypes', allowedTitleTypes);
  r.registerDataSource('core/wcm/components/commons/datasources/allowedcolorswatches/v1', allowedColorSwatches);
  r.registerDataSource('dam/components/scene7/common/imagepresetdatasource', imagePresets);
  r.registerDataSource(GALLERY_PRESETS_DATASOURCE, galleryPresets);
  r.registerDataSource(SMART_CROPS_DATASOURCE, smartCrops, { key: fileReferenceKey });
  // the Dynamic Media component's dialog: a smart crop per breakpoint of the policy
  r.registerBehavior(DM_EDITOR_CLIENTLIB, { transform: dynamicMediaTransform });
  r.registerDataSource('core/wcm/components/commons/datasources/clientlibrarycategories/v1', clientLibraryCategories);
  // Content Fragment (List): options from the chosen fragment / model, reloaded when it changes (as the editor
  // scripts reload the dialog)
  r.registerDataSource('core/wcm/components/contentfragment/v1/datasource/elements', fragmentElements, { key: fragmentPathKey });
  r.registerDataSource('core/wcm/components/contentfragment/v1/datasource/variations', fragmentVariations, { key: fragmentPathKey });
  r.registerComponent('studio/granite/components/contentfragment/elementnames', CfElementNames);
  r.registerBehavior('core.wcm.components.contentfragment.v1.dialog', {
    // the Display Mode switches the element choice (one widget, see CfElementNames) and the paragraph controls,
    // which only apply to a single text element
    transform: (root) =>
      rewrite(root, (n) =>
        graniteDataValue(n, 'element-names-container')
          ? { ...n, properties: { ...n.properties, 'sling:resourceType': 'studio/granite/components/contentfragment/elementnames' } }
          : n,
      ),
    hide: [
      { target: 'paragraphControls', when: [{ field: './displayMode', is: 'singleText', not: true }] },
      { target: 'vcfTemplateContainer', when: [{ field: './displayMode', is: 'vcf', not: true }] },
    ],
  });
  r.registerDataSource('core/wcm/components/contentfragmentlist/v2/datasource/models', fragmentModels);
  r.registerDataSource('core/wcm/components/contentfragmentlist/v2/datasource/elements', modelElements, { key: modelPathKey });
  r.registerDataSource('core/wcm/components/contentfragmentlist/v2/datasource/orderby', fragmentListOrderBy, { key: modelPathKey });

  // Panel containers (Tabs, Carousel, Accordion): the children editor is native; the editor scripts fill the
  // Properties tab's selects from the Items tab
  r.registerBehavior('core.wcm.components.commons.editor.dialog.childreneditor.v1', {});
  r.registerDataSource(PANEL_ITEMS_DATASOURCE, panelItemsDataSource, { live: true });
  r.registerComponent('studio/granite/components/panelcontainer/expandeditems', ExpandedItems);
  r.registerComponent(SHOW_WHEN, ShowWhen);
  for (const kind of ['tabs', 'carousel']) {
    const attr = `cmp-${kind}-v1-dialog-edit-hook`;
    r.registerBehavior(`core.wcm.components.${kind}.v1.editor`, {
      // "Active Item" writes the hidden `./activeItem` itself: one select posting `./activeItem`
      transform: (root) =>
        rewrite(root, (n) => {
          const h = hook(n, attr);
          if (h === 'activeItem') return null;
          if (h !== 'activeSelect') return n;
          const ds = { name: 'datasource', properties: { 'sling:resourceType': PANEL_ITEMS_DATASOURCE, 'studio:keepItems': true }, children: [] };
          return { ...n, properties: { ...n.properties, name: './activeItem' }, children: [...n.children.filter((c) => c.name !== 'datasource'), ds] };
        }),
    });
  }
  const accordionHook = 'cmp-accordion-v1-dialog-edit-hook';
  r.registerBehavior('core.wcm.components.accordion.v1.editor', {
    transform: (root, env) => {
      if (!findNode(root, (n) => !!hook(n, accordionHook))) return root; // the design dialog
      const single = findNode(root, (n) => hook(n, accordionHook) === 'expandedSelectSingle');
      const headings = (allowedHeadingElements(root, env, NO_FORM) as SelectOption[]).length;
      return rewrite(root, (n) => {
        const h = hook(n, accordionHook);
        // the hidden multifield only carried the stored values to the script
        if (String(n.properties['sling:resourceType'] ?? '').endsWith('/form/multifield') && findNode(n, (x) => hook(x, accordionHook) === 'expandedItems')) return null;
        if (h === 'expandedSelectSingle') return null;
        if (h === 'expandedSelect')
          return {
            ...n,
            properties: {
              ...n.properties,
              'sling:resourceType': 'studio/granite/components/panelcontainer/expandeditems',
              'studio:singleLabel': single?.properties.fieldLabel ?? 'Expanded Item',
              'studio:singleDescription': single?.properties.fieldDescription ?? null,
            },
          };
        // the heading element is only offered when the policy allows a choice
        if (h === 'headingElement' && headings < 2) return null;
        return n;
      });
    },
  });

  return r;
}

const graniteDataValue = (n: GraniteNode, key: string) => hook(n, key) !== undefined;

const NO_FORM = { get: () => undefined };

function findNode(node: GraniteNode, test: (n: GraniteNode) => boolean): GraniteNode | undefined {
  if (test(node)) return node;
  for (const c of node.children) {
    const found = findNode(c, test);
    if (found) return found;
  }
  return undefined;
}

