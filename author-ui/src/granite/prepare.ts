import type { ContentNode, GraniteNode, JcrValue } from './types';
import type { GraniteEnv, GraniteRegistry } from './registry';
import { child, graniteClasses, graniteData, resourceTypeOf, str, strList } from './node';
import { evaluate, isExpression, type ElContext } from './el';

/**
 * A field whose value shows or hides other parts of the dialog. This is the generic mechanism shipped with
 * AEM (`cq-dialog-dropdown-showhide`, `cq-dialog-checkbox-showhide`) and used by Core Components dialogs.
 */
export interface ShowHideController {
  kind: 'dropdown' | 'checkbox';
  /** Field name of the controller (`./type`). */
  name: string;
  /** Raw CSS selector from `granite:data`. Only class selectors are supported. */
  selector: string;
  /** Class sets parsed from the selector: `.a.b, .c` -> [['a','b'], ['c']] */
  targets: string[][];
}

/** `field` has value `is` (or, with `not`, any other value, including none). */
export interface Condition {
  field: string;
  is: unknown;
  not?: boolean;
}

/** One condition, or several that must all hold. */
export type When = Condition | { all: Condition[] };

/** Field `field` is disabled while any of `when` holds. */
export interface DisableRule {
  field: string;
  when: When[];
}

/** The node named `target` (node name or field name) is hidden while any of `when` holds. */
export interface HideRule {
  target: string;
  when: When[];
}

/**
 * While any of `when` holds (the field is then usually disabled, its value inherited), the field shows the
 * inherited value as its placeholder: the `dc:description` of the asset whose path is in field `assetDescription`.
 */
export interface HintRule {
  field: string;
  when: When[];
  assetDescription: string;
}

/** Whether any of `when` holds, reading values with `get`. */
export function holds(when: When[], get: (field: string) => unknown): boolean {
  const one = (c: Condition) => {
    const v = get(c.field);
    const same = typeof c.is === 'boolean' ? v === c.is || String(v) === String(c.is) : String(v ?? '') === String(c.is ?? '');
    return c.not ? !same : same;
  };
  return when.some((w) => ('all' in w ? w.all.every(one) : one(w)));
}

export interface PreparedDialog {
  root: GraniteNode;
  controllers: ShowHideController[];
  rules: DisableRule[];
  hideRules: HideRule[];
  hintRules: HintRule[];
  /** Applied in order to the content before the form starts (see `DialogBehavior.initialValues`). */
  initialValues: Array<(content: ContentNode) => ContentNode>;
  /** Things changed or dropped while preparing, shown to developers. */
  notes: string[];
}

const DROPDOWN_CLASS = 'cq-dialog-dropdown-showhide';
const CHECKBOX_CLASS = 'cq-dialog-checkbox-showhide';

/** Properties holding request-time paths: left for the server, never evaluated here. */
const NOT_EVALUATED = new Set(['path', 'componentPath', 'page', 'fragmentPath', 'modelPath']);

/** The names Granite exposes to dialog expressions. */
export function elContext(env: GraniteEnv): ElContext {
  return {
    cqDesign: env.design ?? {},
    param: {},
    requestPathInfo: { suffix: env.contentPath, resourcePath: env.contentPath },
  };
}

/**
 * Prepares a merged dialog tree for rendering, doing what Granite does on the server before rendering:
 * - evaluates `${...}` expressions in properties against the component policy (`cqDesign`),
 * - collects show/hide controllers and the field rules of known editor client libraries,
 * - resolves fields that exist twice under the same name where one variant depends on a datasource Sensible Studio cannot
 *   run yet (e.g. the Title component's `types` vs `defaulttypes`); the static variant is kept.
 */
export function prepareDialog(root: GraniteNode, registry: GraniteRegistry, env: GraniteEnv = {}): PreparedDialog {
  const notes: string[] = [];
  let evaluated = evaluateTree(root, elContext(env));
  for (const category of strList(root, 'extraClientlibs')) {
    const transform = registry.behavior(category)?.transform;
    if (transform) evaluated = transform(evaluated, env);
  }
  const pruned = pruneUnresolvableDuplicates(evaluated, registry, notes);
  const controllers: ShowHideController[] = [];
  collectControllers(pruned, controllers);

  const rules: DisableRule[] = [];
  const hideRules: HideRule[] = [];
  const hintRules: HintRule[] = [];
  const initialValues: Array<(content: ContentNode) => ContentNode> = [];
  for (const category of strList(root, 'extraClientlibs')) {
    const behavior = registry.behavior(category);
    if (!behavior) {
      notes.push(`Editor client library "${category}" is not emulated; its dynamic behaviour is missing.`);
      continue;
    }
    rules.push(...(behavior.disable ?? []));
    hideRules.push(...(behavior.hide ?? []));
    hintRules.push(...(behavior.hint ?? []));
    if (behavior.initialValues) initialValues.push(behavior.initialValues);
  }
  return { root: pruned, controllers, rules, hideRules, hintRules, initialValues, notes };
}

export function parseClassSelector(selector: string): string[][] {
  return selector
    .split(',')
    .map((part) => part.trim())
    .filter((part) => /^(\.[A-Za-z0-9_-]+)+$/.test(part))
    .map((part) => part.split('.').filter(Boolean));
}

function evaluateTree(node: GraniteNode, ctx: ElContext): GraniteNode {
  let properties = node.properties;
  for (const [key, value] of Object.entries(node.properties)) {
    if (!isExpression(value) || NOT_EVALUATED.has(key) || key.startsWith('studio:')) continue;
    if (properties === node.properties) properties = { ...node.properties };
    const result = evaluate(value, ctx);
    if (result === undefined || result === null) delete properties[key];
    else properties[key] = toJcr(result);
  }
  return { ...node, properties, children: node.children.map((c) => evaluateTree(c, ctx)) };
}

function toJcr(v: unknown): JcrValue {
  if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return v;
  if (Array.isArray(v)) return v.map(toJcr);
  return String(v);
}

function collectControllers(node: GraniteNode, out: ShowHideController[]): void {
  const classes = graniteClasses(node);
  const name = str(node, 'name');
  if (name) {
    const data = graniteData(node);
    if (classes.includes(DROPDOWN_CLASS)) {
      const selector = data[`${DROPDOWN_CLASS}-target`];
      if (selector) out.push({ kind: 'dropdown', name, selector, targets: parseClassSelector(selector) });
    }
    if (classes.includes(CHECKBOX_CLASS)) {
      const selector = data[`${CHECKBOX_CLASS}-target`];
      if (selector) out.push({ kind: 'checkbox', name, selector, targets: parseClassSelector(selector) });
    }
  }
  for (const c of node.children) collectControllers(c, out);
}

function hasUnsupportedDataSource(node: GraniteNode, registry: GraniteRegistry): boolean {
  const ds = child(node, 'datasource');
  if (!ds) return false;
  return !registry.dataSource(resourceTypeOf(ds));
}

function isHiddenNode(node: GraniteNode): boolean {
  const v = node.properties['granite:hide'];
  return v === true || v === 'true';
}

function pruneUnresolvableDuplicates(root: GraniteNode, registry: GraniteRegistry, notes: string[]): GraniteNode {
  // Count visible fields per name across the whole dialog.
  const byName = new Map<string, GraniteNode[]>();
  const visit = (n: GraniteNode) => {
    if (isHiddenNode(n)) return;
    const name = str(n, 'name');
    // a container that repeats its field's name (a simple multifield's `field`) is not a variant of it
    if (name && resourceTypeOf(n) && !resourceTypeOf(n).endsWith('/container')) {
      const list = byName.get(name) ?? [];
      list.push(n);
      byName.set(name, list);
    }
    n.children.forEach(visit);
  };
  visit(root);

  const drop = new Set<GraniteNode>();
  for (const [name, fields] of byName) {
    if (fields.length < 2) continue;
    const resolvable = fields.filter((f) => !hasUnsupportedDataSource(f, registry));
    if (resolvable.length === 0 || resolvable.length === fields.length) continue;
    for (const f of fields) {
      if (!resolvable.includes(f)) {
        drop.add(f);
        notes.push(`"${name}": variant "${f.name}" uses a datasource Sensible Studio does not provide yet; using "${resolvable[0].name}".`);
      }
    }
  }
  if (drop.size === 0) return root;

  const copy = (n: GraniteNode): GraniteNode => ({
    ...n,
    children: n.children.filter((c) => !drop.has(c)).map(copy),
  });
  return copy(root);
}
