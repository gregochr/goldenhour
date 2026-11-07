import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import GuidePanel from '../components/guide/GuidePanel.jsx';

const baseProps = {
  page: 'map',
  hash: '',
  nonce: 1,
  focusKey: 1,
  onTrack: vi.fn(),
  onClose: vi.fn(),
};

describe('GuidePanel docked Escape handling', () => {
  it('ignores keys other than Escape', () => {
    const onClose = vi.fn();
    render(<GuidePanel {...baseProps} presentation="docked" onClose={onClose} />);

    fireEvent.keyDown(screen.getByTestId('guide-dock'), { key: 'a' });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('ignores Escape pressed while an IME composition is in progress', () => {
    const onClose = vi.fn();
    render(<GuidePanel {...baseProps} presentation="docked" onClose={onClose} />);

    fireEvent.keyDown(screen.getByTestId('guide-dock'), { key: 'Escape', isComposing: true });

    expect(onClose).not.toHaveBeenCalled();
  });

  it('closes on Escape', () => {
    const onClose = vi.fn();
    render(<GuidePanel {...baseProps} presentation="docked" onClose={onClose} />);

    fireEvent.keyDown(screen.getByTestId('guide-dock'), { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});

describe('GuidePanel header title', () => {
  it('falls back to the hub name when the frame reported a page it has no name for', () => {
    render(<GuidePanel {...baseProps} presentation="docked" tracked={{ page: 'not-a-page', hash: '' }} />);

    expect(screen.getByTestId('guide-title')).toHaveTextContent('Guide');
  });
});

describe('GuidePanel sheet presentation', () => {
  it('closes on Escape when the caller gave no fallback', () => {
    const onClose = vi.fn();
    render(<GuidePanel {...baseProps} presentation="sheet" onClose={onClose} />);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalled();
  });

  it('sends focus to the caller\'s fallback when the sheet closes with no opener to return to', async () => {
    const target = document.createElement('button');
    target.textContent = 'Fallback';
    document.body.appendChild(target);
    const { unmount } = render(
      <GuidePanel {...baseProps} presentation="sheet" returnFocus={() => target} />,
    );
    expect(screen.getByTestId('guide-title')).toHaveTextContent('Map');

    unmount();

    await waitFor(() => expect(target).toHaveFocus());
    target.remove();
  });
});
