import { Text } from '@adobe/react-spectrum';
import type { ContentNode } from '../../granite';
import type { ComponentInfo } from '../api/StudioClient';
import { containerOf, type Editable, type EditableTree } from '../model/editables';
import { ContentTreePanel } from './ContentTreePanel';

interface Props {
  tree: EditableTree;
  content: ContentNode | null;
  contentRoot: string;
  selected: Editable | null;
  onSelect(path: string): void;
  infoOf(type: string): ComponentInfo | undefined;
  /** The allowed components of a container (its policy). */
  allowedIn(container: Editable): string[];
  /** Console errors the page reported while rendering in the frame. */
  errors: string[];
}

/**
 * AEM's Developer mode side panel: the page's components, and for the selected one everything a
 * developer asks first: resource path and type, the component definition and its super types, the
 * dialogs it has, the policy's allowed components for containers, and the page's script errors.
 */
export function DeveloperPanel({ tree, content, contentRoot, selected, onSelect, infoOf, allowedIn, errors }: Props) {
  const info = selected ? infoOf(selected.type) : undefined;
  const container = selected ? containerOf(selected) : null;
  return (
    <div className="studio-developer">
      <section className="studio-developer__section">
        <h3>Components</h3>
        <ContentTreePanel tree={tree} content={content} contentRoot={contentRoot} selected={selected?.path ?? null} onSelect={onSelect} />
      </section>
      <section className="studio-developer__section" aria-label="Component details">
        <h3>Details</h3>
        {!selected ? (
          <Text UNSAFE_className="studio-developer__muted">Select a component in the page or the tree.</Text>
        ) : (
          <dl className="studio-developer__props">
            <dt>Title</dt>
            <dd>{selected.title}</dd>
            <dt>Path</dt>
            <dd>
              <code>{selected.path}</code>
            </dd>
            <dt>Resource type</dt>
            <dd>
              <code>{selected.type}</code>
            </dd>
            {info?.path && (
              <>
                <dt>Component</dt>
                <dd>
                  <a href={`${info.path}.infinity.json`} target="_blank" rel="noreferrer">
                    <code>{info.path}</code>
                  </a>
                  {info.group && <span className="studio-developer__muted"> · {info.group}</span>}
                </dd>
              </>
            )}
            {info?.superTypes && info.superTypes.length > 0 && (
              <>
                <dt>Super types</dt>
                <dd>
                  <ol className="studio-developer__chain">
                    {info.superTypes.map((s) => (
                      <li key={s.path}>
                        <code>{s.resourceType}</code>
                      </li>
                    ))}
                  </ol>
                </dd>
              </>
            )}
            <dt>Dialogs</dt>
            <dd>
              {[selected.dialog && 'cq:dialog', selected.designDialog && 'cq:design_dialog'].filter(Boolean).join(', ') || 'none'}
              {info?.inplace?.editorType && ` · in-place: ${info.inplace.editorType}`}
            </dd>
            {selected.container && (
              <>
                <dt>Allowed components</dt>
                <dd>
                  {allowedIn(selected).length ? (
                    <ul className="studio-developer__chain">
                      {allowedIn(selected).map((t) => (
                        <li key={t}>
                          <code>{t}</code>
                        </li>
                      ))}
                    </ul>
                  ) : (
                    'none (no configuration)'
                  )}
                </dd>
              </>
            )}
            {container && (
              <>
                <dt>Container</dt>
                <dd>
                  <code>{container.path}</code>
                </dd>
              </>
            )}
            {selected.msm && (
              <>
                <dt>MSM</dt>
                <dd>{selected.msm}</dd>
              </>
            )}
            <dt>Content</dt>
            <dd>
              <a href={`${selected.path}.infinity.json`} target="_blank" rel="noreferrer">
                JSON
              </a>
              {' · '}
              <a href={`${selected.path}.html?wcmmode=disabled`} target="_blank" rel="noreferrer">
                HTML
              </a>
            </dd>
          </dl>
        )}
      </section>
      <section className="studio-developer__section" aria-label="Errors">
        <h3>Errors {errors.length > 0 && <span className="studio-developer__count">{errors.length}</span>}</h3>
        {errors.length === 0 ? (
          <Text UNSAFE_className="studio-developer__muted">The page reported no script errors.</Text>
        ) : (
          <ul className="studio-developer__errors">
            {errors.map((e, i) => (
              <li key={i}>
                <code>{e}</code>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
