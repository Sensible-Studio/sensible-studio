import { Text, ToggleButton } from '@adobe/react-spectrum';
import Apps from '@spectrum-icons/workflow/Apps';
import Images from '@spectrum-icons/workflow/Images';
import Layers from '@spectrum-icons/workflow/Layers';
import type { ReactElement, ReactNode } from 'react';
import './workspace.scss';
import { usePanelWidth } from './usePanelWidth';

export type PanelId = 'assets' | 'components' | 'tree';

const TABS: Array<{ id: PanelId; label: string; icon: ReactElement }> = [
  { id: 'assets', label: 'Media', icon: <Images /> },
  { id: 'components', label: 'Components', icon: <Apps /> },
  { id: 'tree', label: 'Content Tree', icon: <Layers /> },
];

interface Props {
  active: PanelId;
  onActive(id: PanelId): void;
  children: ReactNode;
}

/**
 * Docked content workspace. Labeled tabs make the three authoring tasks explicit without an icon rail.
 */
export function SidePanel({ active, onActive, children }: Props) {
  const tab = TABS.find((t) => t.id === active)!;
  const size = usePanelWidth();
  return (
    <aside className={`studio-sidepanel${size.dragging ? ' is-resizing' : ''}`} aria-label="Content workspace">
      <section className="studio-sidepanel__panel" aria-label={tab.label} style={{ width: size.width }}>
      <nav className="studio-sidepanel__tabs" aria-label="Content tools">
        {TABS.map((t) => (
            <ToggleButton key={t.id} isQuiet isSelected={t.id === active} onChange={() => onActive(t.id)} aria-label={t.label}>
              {t.icon}
              <Text>{t.id === 'tree' ? 'Layers' : t.label}</Text>
            </ToggleButton>
        ))}
      </nav>
        <h2 className="studio-sidepanel__title">{tab.id === 'tree' ? 'Page structure' : tab.label}</h2>
        <div className="studio-sidepanel__body">{children}</div>
      </section>
      <div className="studio-sidepanel__resizer" role="separator" aria-orientation="vertical" aria-label="Resize side panel" title="Drag to resize; double-click to reset" onPointerDown={size.onPointerDown} onDoubleClick={size.reset} />
    </aside>
  );
}
