import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { Provider, defaultTheme } from '@adobe/react-spectrum';
import { MemoryDam } from './testing/MemoryDam';
import { learnedNote, SmartTagTrainingSection } from './smartTagTraining';

describe('smart tag training', () => {
  afterEach(cleanup);

  it('says what the numbers of a learned tag mean', () => {
    expect(learnedNote({ examples: 12, recall: 0.9, others: 0 })).toBe('');
    expect(learnedNote({ examples: 6, recall: 0.8, others: 2 })).toBe(
      'learned from few pictures: 10 or more teach it better; it would also go to 2 pictures without it, which may look alike',
    );
  });

  it('lists the learned tags and forgets one', async () => {
    const dam = new MemoryDam();
    dam.learned = [{ tag: 'wknd:activity/surfing', title: 'Surfing', examples: 12, recall: 0.83, others: 0, threshold: 0.8 }];
    let learned = -1;
    render(
      <Provider theme={defaultTheme}>
        <SmartTagTrainingSection client={dam} admin onNotice={() => {}} onLearned={(n) => (learned = n)} />
      </Provider>,
    );
    expect(await screen.findByText('Surfing')).toBeTruthy();
    expect(screen.getByText('83%')).toBeTruthy();
    expect(learned).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: 'Forget Surfing' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Forget' }));
    await waitFor(() => expect(dam.calls).toContain('forgetSmartTag wknd:activity/surfing'));
    await waitFor(() => expect(screen.queryByText('Surfing')).toBeNull());
    expect(learned).toBe(0);
  });

  it('shows nothing to change to someone who is not admin', async () => {
    const dam = new MemoryDam();
    render(
      <Provider theme={defaultTheme}>
        <SmartTagTrainingSection client={dam} admin={false} onNotice={() => {}} />
      </Provider>,
    );
    await screen.findByText('Learn your tags');
    expect(screen.queryByRole('button', { name: 'Learn' })).toBeNull();
  });
});
