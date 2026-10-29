import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';
import Modal from '../components/shared/Modal.jsx';

/**
 * The pane's foreign-modal `MutationObserver` with the REAL jsdom observer (performance review F22).
 * `WindowFirstMapPane.test.jsx` fakes the observer to fire deterministically; this file proves the
 * callback's FILTER: it still sees a mounted `Modal` and a `stacked` `aria-modal` toggle, and it
 * runs no document query for Leaflet tile churn.
 */
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="stub-map" />,
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../api/outcomeRatingApi.js', () => ({
  fetchMyRatings: vi.fn(() => new Promise(() => {})),
  putRating: vi.fn(),
}));

const DATES = ['2026-08-11', '2026-08-12'];
const DIALOG_QUERY = '[role="dialog"][aria-modal="true"]';
const flush = () => act(async () => { await Promise.resolve(); });

let sibling;

beforeEach(() => {
  vi.spyOn(document, 'hasFocus').mockReturnValue(true);
  sibling = document.createElement('div');
  document.body.appendChild(sibling);
});

afterEach(() => {
  sibling.remove();
  vi.restoreAllMocks();
});

const renderPane = () => render(
  <WindowFirstMapPane locations={[]} dates={DATES} selectedDate={DATES[0]} onSelectDate={vi.fn()} />,
);

describe('WindowFirstMapPane — the filtered foreign-modal observer', () => {
  it('still sees a Modal mounted outside the pane, and a stacked aria-modal toggle', async () => {
    const pane = renderPane();
    await flush();
    expect(screen.getByTestId('window-first-map-pane')).toBeInTheDocument();

    const dialog = (stacked) => (
      <Modal label="Settings" onClose={() => {}} stacked={stacked}><p>hello</p></Modal>
    );
    const host = render(dialog(false), { container: sibling });
    const spy = vi.spyOn(document, 'querySelectorAll');
    await flush();
    // The mount was seen: the pane re-asked the question.
    expect(spy.mock.calls.some(([q]) => q === DIALOG_QUERY)).toBe(true);

    spy.mockClear();
    host.rerender(dialog(true));
    await flush();
    // `stacked` dropped aria-modal: an attribute change, seen too.
    expect(spy.mock.calls.some(([q]) => q === DIALOG_QUERY)).toBe(true);
    pane.unmount();
  });

  it('sees a Modal mounted deep inside the SAME rendered tree, and sees it unmount', async () => {
    // Inline, as `Modal` renders in `#root`: a descendant of the shell next to the pane, not a
    // separate root and not portalled.
    function Shell({ open }) {
      return (
        <div data-testid="shell">
          <WindowFirstMapPane
            locations={[]}
            dates={DATES}
            selectedDate={DATES[0]}
            onSelectDate={vi.fn()}
          />
          <section><div>
            {open && <Modal label="Deep" onClose={() => {}}><p>deep</p></Modal>}
          </div></section>
        </div>
      );
    }
    const view = render(<Shell open={false} />);
    await flush();
    const spy = vi.spyOn(document, 'querySelectorAll');
    const asked = () => spy.mock.calls.filter(([q]) => q === DIALOG_QUERY).length;

    view.rerender(<Shell open />);
    await flush();
    expect(screen.getByRole('dialog', { name: 'Deep' })).toBeInTheDocument();
    expect(asked()).toBeGreaterThan(0);

    spy.mockClear();
    view.rerender(<Shell open={false} />);
    await flush();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(asked()).toBeGreaterThan(0);
    view.unmount();
  });

  it('runs no foreign-modal query for a tile inserted under a Leaflet tile container', async () => {
    const pane = renderPane();
    await flush();
    const leafletPane = document.createElement('div');
    leafletPane.className = 'leaflet-pane';
    const tiles = document.createElement('div');
    tiles.className = 'leaflet-tile-container';
    leafletPane.appendChild(tiles);
    document.body.appendChild(leafletPane);
    await flush();

    const spy = vi.spyOn(document, 'querySelectorAll');
    const tile = document.createElement('img');
    tiles.appendChild(tile);
    tiles.removeChild(tile);
    await flush();
    expect(spy.mock.calls.filter(([q]) => q === DIALOG_QUERY)).toHaveLength(0);

    // Positive control: the same observer does ask when a dialog is added.
    const dlg = document.createElement('div');
    dlg.setAttribute('role', 'dialog');
    dlg.setAttribute('aria-modal', 'true');
    document.body.appendChild(dlg);
    await flush();
    expect(spy.mock.calls.filter(([q]) => q === DIALOG_QUERY).length).toBeGreaterThan(0);
    dlg.remove();
    leafletPane.remove();
    pane.unmount();
  });
});
