import Alert from '@spectrum-icons/workflow/Alert';
import Brush from '@spectrum-icons/workflow/Brush';
import type { ComponentInfo } from '../api/StudioClient';
import type { TemplateInfo } from './api';

/**
 * What an unlocked container shows in the structure, as in AEM: the components its policy allows, each with a
 * button for that component's policy inside the container. Authors add those components on pages (and the template
 * author in Initial Content).
 */
export function AllowedComponentsPanel({ components, onPolicy }: { components: ComponentInfo[]; onPolicy(c: ComponentInfo): void }) {
  return (
    <div className="studio-tpl-allowed">
      <span className="studio-tpl-allowed__title">Allowed Components</span>
      {components.length === 0 ? (
        <span className="studio-tpl-allowed__none">None yet: set them in the container's configuration.</span>
      ) : (
        <ul className="studio-tpl-allowed__list">
          {components.map((c) => (
            <li key={c.resourceType} className="studio-tpl-allowed__chip">
              <span title={c.resourceType}>{c.title}</span>
              <button
                type="button"
                className="studio-tpl-allowed__policy"
                aria-label={`Configuration of ${c.title} in this container`}
                title="Configuration"
                onPointerDown={(e) => e.stopPropagation()}
                onPointerUp={(e) => e.stopPropagation()}
                onClick={(e) => {
                  e.stopPropagation();
                  onPolicy(c);
                }}
              >
                <Brush size="XS" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const STATUS_TEXT: Record<string, string> = {
  enabled: 'This template is not a draft anymore and might already be referenced by one or more pages. Editing the structure will affect all the pages referencing it.',
  disabled: 'This template is disabled: no new pages can be created with it. Pages that already use it keep following its structure.',
};

/** The notice under the top bar, like AEM's, for templates that pages may already use. */
export function TemplateBanner({ template }: { template: TemplateInfo }) {
  const text = STATUS_TEXT[template.status];
  if (!text) return null;
  return (
    <div className="studio-tpl-banner" role="status">
      <Alert size="S" UNSAFE_className="studio-tpl-banner__icon" />
      <strong>Template editor</strong>
      <span>{text}</span>
    </div>
  );
}
