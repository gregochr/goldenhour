/**
 * `hooks/useOutsideDismiss.js` — the map chrome's shared outside-click dismissal
 * (`docs/engineering/map-landing-plan.md` §3 L3, `docs/design/map-landing/README.md` §5).
 *
 * <p>⚠️ **Why this file exists.** The hook's own doc block says `isInsideMapFrame` is "exported so
 * a caller (and a test) can ask the question without mounting a listener", and for one commit there
 * was no such test — three review lenses said so independently. Five behaviours belong only to this
 * module and are invisible through its four consumers: a null `rootRef`, a non-`Element` target, a
 * press on a DESCENDANT of the frame rather than the frame node itself (the only case that
 * exercises the `closest()` climb at all — the MapView-level tests press the frame node, where
 * `closest` matches self), the `enabled` gate, and listener teardown. The doors series was corrected
 * the same way: lift the logic to a pure seam and test the seam.
 */
import React, { useRef, useState } from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { useOutsideDismiss, isInsideMapFrame, MAP_FRAME_SELECTOR } from '../hooks/useOutsideDismiss.js';

/**
 * A panel inside a map frame, with a sibling target outside it — the real shape: the frame wraps
 * both the map and the chrome, and the panel is chrome.
 */
function Harness({ open = true, enabled = true, onDismiss }) {
  const rootRef = useRef(null);
  useOutsideDismiss({ open, rootRef, onDismiss, enabled });
  return (
    <div>
      <div data-testid="map-container">
        <div data-testid="map-ground">
          <span data-testid="map-marker">a pin, deep inside the frame</span>
        </div>
        <div ref={rootRef} data-testid="panel">
          <button type="button" data-testid="panel-button">inside the panel</button>
        </div>
      </div>
      <div data-testid="masthead">outside the frame entirely</div>
    </div>
  );
}

describe('isInsideMapFrame', () => {
  it('is true for the frame node itself and for anything nested inside it', () => {
    render(<Harness onDismiss={vi.fn()} />);

    expect(isInsideMapFrame(screen.getByTestId('map-container'))).toBe(true);
    // ⚠️ The nested case is the one that exercises the `closest()` climb — on the frame node itself
    // `closest` matches self and would pass even for a naive identity check.
    expect(isInsideMapFrame(screen.getByTestId('map-marker'))).toBe(true);
  });

  it('is false outside the frame', () => {
    render(<Harness onDismiss={vi.fn()} />);

    expect(isInsideMapFrame(screen.getByTestId('masthead'))).toBe(false);
    expect(isInsideMapFrame(document.body)).toBe(false);
  });

  it('is false for anything that is not an Element, rather than throwing', () => {
    // A press can reach `document` itself, which has no `closest`. Without the guard this throws
    // inside a `mousedown` listener — nothing in the component tests dispatches such an event.
    expect(isInsideMapFrame(document)).toBe(false);
    expect(isInsideMapFrame(null)).toBe(false);
    expect(isInsideMapFrame(undefined)).toBe(false);
    expect(isInsideMapFrame(window)).toBe(false);
  });

  it('names the frame by the test-id MapView actually renders', () => {
    expect(MAP_FRAME_SELECTOR).toBe('[data-testid="map-container"]');
  });
});

describe('useOutsideDismiss', () => {
  it('dismisses on a press outside both the panel and the frame', () => {
    const onDismiss = vi.fn();
    render(<Harness onDismiss={onDismiss} />);

    fireEvent.mouseDown(screen.getByTestId('masthead'));

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('does NOT dismiss on a press inside the map frame — the rule this exists for', () => {
    const onDismiss = vi.fn();
    render(<Harness onDismiss={onDismiss} />);

    fireEvent.mouseDown(screen.getByTestId('map-ground'));
    fireEvent.mouseDown(screen.getByTestId('map-marker'));
    fireEvent.mouseDown(screen.getByTestId('map-container'));

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('does NOT dismiss on a press inside the panel itself', () => {
    const onDismiss = vi.fn();
    render(<Harness onDismiss={onDismiss} />);

    fireEvent.mouseDown(screen.getByTestId('panel-button'));

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('mounts no listener while closed', () => {
    const onDismiss = vi.fn();
    render(<Harness open={false} onDismiss={onDismiss} />);

    fireEvent.mouseDown(screen.getByTestId('masthead'));

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('mounts no listener when disabled — the phone gate', () => {
    // The two `BottomSheet` panels pass `enabled: !isMobile`, because their sheet is portalled
    // OUTSIDE `rootRef` and this listener would close it on the first tap inside it.
    const onDismiss = vi.fn();
    render(<Harness enabled={false} onDismiss={onDismiss} />);

    fireEvent.mouseDown(screen.getByTestId('masthead'));

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('removes its listener when the panel closes', () => {
    const onDismiss = vi.fn();
    const { rerender } = render(<Harness open onDismiss={onDismiss} />);
    fireEvent.mouseDown(screen.getByTestId('masthead'));
    expect(onDismiss).toHaveBeenCalledTimes(1);

    rerender(<Harness open={false} onDismiss={onDismiss} />);
    fireEvent.mouseDown(screen.getByTestId('masthead'));

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('removes its listener on unmount', () => {
    const onDismiss = vi.fn();
    const { unmount } = render(<Harness onDismiss={onDismiss} />);
    unmount();

    fireEvent.mouseDown(document.body);

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('dismisses nothing when the panel never attached its ref', () => {
    // `rootRef.current` null is "no panel to be outside of" — the same degrade the four listeners
    // this hook replaced already had.
    function NoRef({ onDismiss }) {
      const rootRef = useRef(null);
      useOutsideDismiss({ open: true, rootRef, onDismiss });
      return <div data-testid="masthead">no panel here</div>;
    }
    const onDismiss = vi.fn();
    render(<NoRef onDismiss={onDismiss} />);

    fireEvent.mouseDown(screen.getByTestId('masthead'));

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('calls the NEWEST callback, so a caller may pass a fresh arrow every render', () => {
    // ⚠️ This is what the ref-held callback buys. Without it the listener effect would either
    // re-subscribe on every render or close over the first `onDismiss` forever — and a stale
    // closure here would dismiss using a handler that no longer matches the panel on screen.
    const calls = [];
    function Rerendering() {
      const rootRef = useRef(null);
      const [n, setN] = useState(0);
      useOutsideDismiss({ open: true, rootRef, onDismiss: () => calls.push(n) });
      return (
        <div>
          <div data-testid="map-container"><div ref={rootRef} /></div>
          <button type="button" data-testid="bump" onClick={() => setN((v) => v + 1)}>bump</button>
          <div data-testid="masthead">outside</div>
        </div>
      );
    }
    render(<Rerendering />);

    act(() => { fireEvent.click(screen.getByTestId('bump')); });
    act(() => { fireEvent.click(screen.getByTestId('bump')); });
    fireEvent.mouseDown(screen.getByTestId('masthead'));

    expect(calls).toEqual([2]);
  });
});
