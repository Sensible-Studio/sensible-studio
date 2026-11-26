/**
 * Wire format of `GET /bin/studio/dialog` (see studio-adapter DialogJson).
 * A dialog is the merged Granite resource tree, exactly as AEM would render it.
 */

export type JcrValue = string | number | boolean | null | JcrValue[];

export interface GraniteNode {
  name: string;
  properties: Record<string, JcrValue>;
  children: GraniteNode[];
}

export interface DialogResponse {
  resourceType: string;
  dialogName: 'cq:dialog' | 'cq:design_dialog' | string;
  /** Component and its super types, most specific first. */
  chain: string[];
  /** Things the server could not resolve (missing include targets, EL paths, ...). */
  warnings: string[];
  /** Content policy of the edited instance, when the request named one (`path`). */
  policyPath?: string | null;
  policy?: GraniteNode | null;
  root: GraniteNode | null;
}

/**
 * Content as returned by the Sling default GET servlet (`.infinity.json`):
 * properties and child nodes share one object; children are nested objects.
 */
export interface ContentNode {
  [key: string]: JcrValue | ContentNode | undefined;
}

/** One `name=value` pair of a Sling POST request. Order matters for repeated names. */
export interface PostEntry {
  name: string;
  value: string;
}
