import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { ComponentInfo } from '../api/StudioClient';
import { hasComponentIcon } from './ComponentIcon';
import { ComponentsPanel } from './ComponentsPanel';

afterEach(cleanup);

const COMPONENTS: ComponentInfo[] = [
  { resourceType: 'wknd/components/image', path: '/apps/wknd/components/image', title: 'Image', group: 'WKND.Content', icon: 'image', description: 'Smart adaptive image' },
  { resourceType: 'wknd/components/helloworld', title: 'Hello World Component', group: 'WKND.Content', abbreviation: 'He' },
  { resourceType: 'wcm/foundation/components/buildingblock', title: 'Building Block', group: '.hidden' },
];

describe('Components panel', () => {
  it('shows the cq:icon, else the abbreviation, and the description behind (i)', async () => {
    render(
      <Provider theme={defaultTheme} colorScheme="light">
        <ComponentsPanel components={COMPONENTS} />
      </Provider>,
    );
    const items = screen.getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(screen.queryByText('Building Block')).toBeNull();
    expect(items[1].querySelector('.studio-complist__icon.is-icon svg')).toBeTruthy();
    expect(items[0].querySelector('.studio-complist__icon')?.textContent).toBe('He');
    fireEvent.click(screen.getByRole('button', { name: 'Show description for Image' }));
    expect(await screen.findByText('Smart adaptive image')).toBeTruthy();
    expect(screen.getByText('wknd/components/image')).toBeTruthy();
  });

  it('knows the Core Components icons', () => {
    for (const icon of ['panel', 'textLeft', 'experience', 'card', 'tableHistogram']) expect(hasComponentIcon({ icon })).toBe(true);
    expect(hasComponentIcon({ icon: 'nope' })).toBe(false);
    expect(hasComponentIcon({})).toBe(false);
  });
});
