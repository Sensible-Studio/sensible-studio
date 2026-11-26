import { Icon } from '@adobe/react-spectrum/Icon';
import Experience from '@spectrum-icons/workflow/Experience';
import WebPages from '@spectrum-icons/workflow/WebPages';
import type { ComponentProps } from 'react';

type Props = Omit<ComponentProps<typeof Icon>, 'children'>;

/** A page: two windows, one behind the other (icons/custom/WebPages.svg). */
export const PageIcon = WebPages;

/** A block: a section of a page (icons/custom/Experience.svg). */
export const BlockIcon = Experience;

/** A template: a window whose body is laid out in empty slots, the regions a page made from it will fill. */
export function TemplateIcon(props: Props) {
  return (
    <Icon {...props}>
      <svg viewBox="0 0 24 24" className="studio-icon" aria-hidden="true">
        <rect x="3" y="4" width="18" height="16" rx="2.5" />
        <path d="M3 9h18" />
        <rect x="6.5" y="12" width="4.5" height="5" rx="1" strokeWidth="1.5" strokeDasharray="1.6 1.6" />
        <path d="M14 12.500h3.5" strokeWidth="1.5" strokeDasharray="1.6 1.6" />
        <path d="M14 16.500h3.5" strokeWidth="1.5" strokeDasharray="1.6 1.6" />
      </svg>
    </Icon>
  );
}
