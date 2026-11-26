import type { ComponentType } from 'react';
import type { ContentNode, GraniteNode } from './types';
import type { DisableRule, HideRule, HintRule } from './prepare';
import { normalizeResourceType } from './node';

export interface GraniteComponentProps {
  node: GraniteNode;
}

export type GraniteComponent = ComponentType<GraniteComponentProps>;

export interface SelectOption {
  value: string;
  text: string;
  disabled?: boolean;
  /** Chosen when the content has no value yet (a datasource's default, e.g. the configuration's heading element). */
  selected?: boolean;
}

/** Services the host application provides to fields (pickers, datasources, ...). All optional. */
export interface GraniteEnv {
  /** Path of the resource the dialog edits, e.g. `/content/wknd/us/en/jcr:content/root/title`. */
  contentPath?: string;
  /** Lists children of a repository path, for path/page/asset pickers. */
  listChildren?(path: string): Promise<RepositoryItem[]>;
  /** URL to show a DAM asset (thumbnail or original). */
  assetUrl?(path: string): string;
  /** URL of a small rendition of a DAM asset, for previews (falls back to `assetUrl`). */
  assetThumbnail?(path: string): string;
  /** Title and description (`dc:title`, `dc:description`) of a DAM asset. */
  assetInfo?(path: string): Promise<AssetDetails | null>;
  /** The featured image of a page (`cq:featuredimage` in its page properties), or null when it has none. */
  pageImage?(pagePath: string): Promise<{ fileReference: string; alt?: string } | null>;
  /** Title and group of a component, by resource type (labels of the children editor). */
  componentInfo?(resourceType: string): ComponentChoice | undefined;
  /** Components that may be added to the container at `containerPath` (its policy), for the children editor. */
  allowedComponents?(containerPath: string): ComponentChoice[] | Promise<ComponentChoice[]>;
  /** Every component authors can be allowed, with its group (a container policy's Allowed Components). */
  allComponents?(): Promise<ComponentChoice[]>;
  /** The component's content policy, exposed to dialog expressions as `cqDesign`. */
  design?: Record<string, unknown>;
  /** Display path of a tag ID, `Namespace : Parent / Tag` (the tag field shows it on its chips). */
  tagTitle?(tagId: string): Promise<string>;
  /** Browses the tag tree; calls `onPick` with a tag ID (the tag field's folder button). */
  TagPicker?: ComponentType<{ onPick(tagId: string): void; onCancel(): void }>;
  /**
   * The value of a page property inherited from the closest ancestor page that sets it (`brandSlug`, `cq:conf`), and
   * the page it comes from; null when no ancestor sets it.
   */
  inheritedValue?(pagePath: string, property: string): Promise<{ value: string; from: string } | null>;
  /**
   * Keeps a file to upload with the form (page thumbnail): posted as `name` to the edited resource when the form is
   * saved; `null` drops it.
   */
  stageFile?(name: string, file: File | null): void;
  /** URL showing a repository file or DAM asset (thumbnails), with a cache breaker. */
  fileUrl?(path: string): string;
  /** The client library categories (`type`: js or css), for Core's page policy. */
  clientLibraryCategories?(type?: string): Promise<string[]>;
  /** Content Fragments, for the Content Fragment (List) datasources: models, their elements, a fragment's variations. */
  contentFragments?: ContentFragmentServices;
}

export interface ContentFragmentServices {
  models(): Promise<Array<{ path: string; title: string; enabled?: boolean }>>;
  /** The model's elements, in order; `kind` as Sensible Studio's model editor names it (text, longtext, number, enumeration, ...). */
  elements(modelPath: string): Promise<Array<{ name: string; title: string; kind: string }>>;
  /** The fragment's model and variations (without master). */
  fragment(path: string): Promise<{ model: string | null; variations: Array<{ name: string; title: string }> }>;
}

/**
 * Field rules that stand in for the editor client libraries AEM loads through `extraClientlibs`
 * (jQuery/Coral scripts that Sensible Studio does not run). A disabled field is neither validated nor posted,
 * exactly like a disabled Coral field.
 */
export interface DialogBehavior {
  disable?: DisableRule[];
  /** Parts of the dialog the client library shows or hides (it keeps their values). */
  hide?: HideRule[];
  /** Inherited values shown as placeholders. */
  hint?: HintRule[];
  /**
   * Values the client library derives when the dialog opens on content that does not store them, e.g. the
   * Image v3 editor unchecks "Inherit featured image from page" for an image that has its own asset.
   */
  initialValues?(content: ContentNode): ContentNode;
  /**
   * Rewrites the dialog before it renders, for what the client library does to the form's structure: removes the
   * variant of a field it removes, wires a select it fills to the field it writes, ... (`env.design` is the policy).
   */
  transform?(root: GraniteNode, env: GraniteEnv): GraniteNode;
}

export interface ComponentChoice {
  resourceType: string;
  /** the component's path (`/apps/site/components/title`), how policies name it */
  path?: string;
  title: string;
  group?: string;
}

export interface AssetDetails {
  path: string;
  title?: string;
  description?: string;
  mimeType?: string;
}

export interface RepositoryItem {
  name: string;
  path: string;
  title?: string;
  /** jcr:primaryType, e.g. cq:Page, dam:Asset, sling:Folder */
  type?: string;
  hasChildren?: boolean;
}

/** Evaluates a `granite:rendercondition`. Return undefined when the condition cannot be decided. */
export type RenderCondition = (node: GraniteNode, env: GraniteEnv) => boolean | undefined;

/** The dialog's values, for datasources that depend on other fields (see `registerDataSource`'s `live`). */
export interface DataSourceForm {
  get(name: string): unknown;
}

/**
 * Produces options for a field's `datasource` child, like a Granite datasource servlet. A synchronous result is
 * used while the field initialises (so a default it marks `selected` becomes the field's value, as on the server).
 */
export type DataSource = (node: GraniteNode, env: GraniteEnv, form?: DataSourceForm) => Promise<SelectOption[]> | SelectOption[];

export interface DataSourceOptions {
  /**
   * Recomputed (synchronously) whenever the dialog's values change: options that an editor client library keeps in
   * step with another field, e.g. the Tabs "Active Item" listing the items of the Items tab.
   */
  live?: boolean;
  /**
   * For a live datasource: what its options depend on (e.g. the chosen fragment's path); they are recomputed, and
   * may then be loaded asynchronously, only when it changes. Without it they follow every change of the dialog.
   */
  key?(node: GraniteNode, form: DataSourceForm): string;
}

interface DataSourceEntry {
  impl: DataSource;
  live: boolean;
  key?(node: GraniteNode, form: DataSourceForm): string;
}

/**
 * Maps `sling:resourceType` to implementations, the way Sling maps resource types to scripts.
 * Projects register their own widgets (e.g. `myproject/dialog/widgets/colorpicker`) on the same registry.
 */
export class GraniteRegistry {
  private readonly components = new Map<string, GraniteComponent>();
  private readonly renderConditions = new Map<string, RenderCondition>();
  private readonly dataSources = new Map<string, DataSourceEntry>();
  private readonly silent = new Set<string>();
  private readonly behaviors = new Map<string, DialogBehavior>();

  registerComponent(resourceType: string, component: GraniteComponent): this {
    this.components.set(normalizeResourceType(resourceType), component);
    return this;
  }

  /** Resource types that are configuration, not UI (e.g. RTE plugin config): rendered as nothing. */
  registerSilent(resourceType: string): this {
    this.silent.add(normalizeResourceType(resourceType));
    return this;
  }

  registerRenderCondition(resourceType: string, condition: RenderCondition): this {
    this.renderConditions.set(normalizeResourceType(resourceType), condition);
    return this;
  }

  registerDataSource(resourceType: string, dataSource: DataSource, options: DataSourceOptions = {}): this {
    this.dataSources.set(normalizeResourceType(resourceType), { impl: dataSource, live: !!options.live || !!options.key, key: options.key });
    return this;
  }

  /** Behaviour for a client library category listed in a dialog's `extraClientlibs`. */
  registerBehavior(clientlibCategory: string, behavior: DialogBehavior): this {
    this.behaviors.set(clientlibCategory, behavior);
    return this;
  }

  behavior(clientlibCategory: string): DialogBehavior | undefined {
    return this.behaviors.get(clientlibCategory);
  }

  component(resourceType: string): GraniteComponent | undefined {
    return this.components.get(normalizeResourceType(resourceType));
  }

  isSilent(resourceType: string): boolean {
    const rt = normalizeResourceType(resourceType);
    for (const prefix of this.silent) {
      if (rt === prefix || rt.startsWith(`${prefix}/`)) return true;
    }
    return false;
  }

  renderCondition(resourceType: string): RenderCondition | undefined {
    return this.renderConditions.get(normalizeResourceType(resourceType));
  }

  dataSource(resourceType: string): DataSource | undefined {
    return this.dataSources.get(normalizeResourceType(resourceType))?.impl;
  }

  /** Whether the datasource is recomputed as the dialog's values change. */
  isLiveDataSource(resourceType: string): boolean {
    return !!this.dataSources.get(normalizeResourceType(resourceType))?.live;
  }

  /** What a live datasource's options depend on (see `DataSourceOptions.key`). */
  dataSourceKey(resourceType: string, node: GraniteNode, form: DataSourceForm): string | undefined {
    return this.dataSources.get(normalizeResourceType(resourceType))?.key?.(node, form);
  }

  supportedResourceTypes(): string[] {
    return [...this.components.keys()].sort();
  }
}
