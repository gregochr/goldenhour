import React from 'react';
import {
  describe, it, expect, vi, afterEach,
} from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import MapScreen from '../components/MapScreen.jsx';
import usePanelEscape from '../hooks/usePanelEscape.js';

/**
 * Review round 2, item 4: an Operations panel (`usePanelEscape`, a document listener) open BEHIND the map view must not
 * also close when Escape closes the view (`MapScreen`'s document fallback, with focus on <body>).
 */
afterEach(() => {
  cleanup();
  document.body.innerHTML = '';
});

function Panel({ onEscape }) {
  const panelRef = React.useRef(null);
  usePanelEscape({ active: true, panelRef, onEscape });
  return <div ref={panelRef} data-testid="panel">panel</div>;
}

function pressEscapeOnBody() {
  const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
  document.body.dispatchEvent(event);
  return event;
}

describe('an Operations panel behind the map view', () => {
  it('is left open: one Escape closes only the view', () => {
    const onEscape = vi.fn();
    const onClose = vi.fn();
    render(
      <>
        <Panel onEscape={onEscape} />
        <MapScreen open onClose={onClose} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={React.createRef()} />
      </>,
    );

    pressEscapeOnBody();

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('is left open whichever listener registered first (the panel mounted after the view)', () => {
    const onEscape = vi.fn();
    const onClose = vi.fn();
    const { rerender } = render(
      <MapScreen open onClose={onClose} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={React.createRef()} />,
    );
    rerender(
      <>
        <MapScreen open onClose={onClose} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={React.createRef()} />
        <Panel onEscape={onEscape} />
      </>,
    );

    pressEscapeOnBody();

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onEscape).not.toHaveBeenCalled();
  });

  it('still closes on Escape when the view is mounted but hidden (closed)', () => {
    const onEscape = vi.fn();
    render(
      <>
        <Panel onEscape={onEscape} />
        <MapScreen
          open={false}
          onClose={vi.fn()}
          onScrimPress={vi.fn()}
          onRoute={vi.fn()}
          titleRef={React.createRef()}
        />
      </>,
    );
    expect(screen.getByTestId('map-screen')).toHaveAttribute('hidden');

    pressEscapeOnBody();

    expect(onEscape).toHaveBeenCalledTimes(1);
  });

  it('still closes on Escape with no view at all', () => {
    const onEscape = vi.fn();
    render(<Panel onEscape={onEscape} />);
    pressEscapeOnBody();
    expect(onEscape).toHaveBeenCalledTimes(1);
  });
});
