import type { ReactNode } from 'react';
import type { GraniteNode } from './types';
import { useGranite, useRuleHidden, useShowHideVisible, VisibleScope } from './context';
import { child, graniteClasses, items, resourceTypeOf, str } from './node';
import { InheritanceLock } from './inheritance';

/**
 * Renders one Granite node with the component registered for its `sling:resourceType`.
 *
 * Mirrors what Granite does on the server before a component script runs:
 * `granite:hide` and `granite:rendercondition` remove the node, show/hide controllers toggle it.
 */
export function GraniteNodeView({ node }: { node: GraniteNode }) {
  const { registry, env } = useGranite();
  const shown = useShowHideVisible(node);
  const ruleHidden = useRuleHidden(node);
  const visible = shown && !ruleHidden;

  if (isHidden(node)) return null;

  const condition = child(node, 'granite:rendercondition');
  if (condition && evaluateCondition(condition, registry, env) === false) return null;

  const rt = resourceTypeOf(node);
  if (!rt) return null; // structural node without a renderer, Granite ignores it too
  if (registry.isSilent(rt)) return null;

  const Component = registry.component(rt);
  const rendered = Component ? <Component node={node} /> : <Unsupported node={node} />;
  // MSM: in a live copy, inherited fields are read-only behind a chain (hidden fields have nothing to show)
  const content = rt.endsWith('/form/hidden') ? rendered : <InheritanceLock node={node}>{rendered}</InheritanceLock>;

  return (
    <VisibleScope visible={visible}>
      <div className={['studio-g-node', ...graniteClasses(node)].join(' ')} hidden={!visible} data-node={node.name} data-rt={rt}>
        {content}
      </div>
    </VisibleScope>
  );
}

/** Renders the `items` children of a container-like node. */
export function GraniteItems({ node }: { node: GraniteNode }) {
  return (
    <>
      {items(node).map((c) => (
        <GraniteNodeView key={c.name} node={c} />
      ))}
    </>
  );
}

export function isHidden(node: GraniteNode): boolean {
  const v = node.properties['granite:hide'];
  return v === true || v === 'true';
}

/** Whether a node would be rendered at all (used by tabs/accordions to skip empty panels). */
export function isRendered(node: GraniteNode, registry: ReturnType<typeof useGranite>['registry'], env: ReturnType<typeof useGranite>['env']): boolean {
  if (isHidden(node)) return false;
  const condition = child(node, 'granite:rendercondition');
  return !(condition && evaluateCondition(condition, registry, env) === false);
}

function evaluateCondition(
  condition: GraniteNode,
  registry: ReturnType<typeof useGranite>['registry'],
  env: ReturnType<typeof useGranite>['env'],
): boolean | undefined {
  const rt = resourceTypeOf(condition);
  const impl = registry.renderCondition(rt);
  if (!impl) return undefined; // unknown condition: render, like a permissive default
  return impl(condition, env);
}

/** Shown for resource types Sensible Studio has no implementation for. Never silently degrades to a text field. */
export function Unsupported({ node, reason }: { node: GraniteNode; reason?: ReactNode }) {
  const rt = resourceTypeOf(node);
  const name = str(node, 'name');
  const label = str(node, 'fieldLabel') ?? str(node, 'jcr:title') ?? str(node, 'text');
  return (
    <div className="studio-g-unsupported" role="note">
      <div className="studio-g-unsupported__title">{label ?? node.name}</div>
      <div className="studio-g-unsupported__meta">
        <code>{rt}</code>
        {name && (
          <>
            {' '}
            · <code>{name}</code>
          </>
        )}
      </div>
      <div className="studio-g-unsupported__reason">
        {reason ?? 'Not implemented in Sensible Studio yet. The stored value is left unchanged.'}
      </div>
    </div>
  );
}
