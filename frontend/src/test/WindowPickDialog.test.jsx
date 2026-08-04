import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowPickDialog from '../components/WindowPickDialog.jsx';

function pick(overrides = {}) {
  return {
    kind: 'best',
    regionName: 'Northumberland & Tyneside',
    headline: 'Breaking clear with the antisolar canvas alight',
    detail: 'Low cloud clears from the west and mid-level cloud catches fire on cue.',
    locationName: 'Bamburgh Beach',
    locationId: 1,
    ...overrides,
  };
}

const renderDialog = (overrides = {}, props = {}) => {
  const handlers = {
    onClose: vi.fn(), onShowRegion: vi.fn(), onShowLocation: vi.fn(), ...props,
  };
  render(
    <WindowPickDialog pick={pick(overrides)} when="Tomorrow sunset" time="21:11" {...handlers} />,
  );
  return handlers;
};

describe('WindowPickDialog', () => {
  it('is a dialog, not a tooltip, because it carries destinations', () => {
    // PopoverHost was the obvious home and is the wrong one by its own rule: it is role="tooltip",
    // and content a reader must REACH — a destination, an action — belongs in a dialog. §6 makes
    // the same point from the other side: no control whose only visible effect is an aria-hidden
    // panel. A hover peek is also unreachable by keyboard and by touch.
    renderDialog();
    const dialog = screen.getByRole('dialog', { name: 'Best bet — Northumberland & Tyneside' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('names which pick it is, its region, and the window it falls on', () => {
    renderDialog();
    expect(screen.getByTestId('window-pick-dialog-kind')).toHaveTextContent('Best bet');
    expect(screen.getByTestId('window-pick-dialog')).toHaveTextContent('Northumberland & Tyneside');
    expect(screen.getByTestId('window-pick-dialog')).toHaveTextContent('Tomorrow sunset · 21:11');
  });

  it('calls the runner-up by its own name', () => {
    renderDialog({ kind: 'also' });
    expect(screen.getByTestId('window-pick-dialog-kind')).toHaveTextContent('Also good');
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Also good — Northumberland & Tyneside');
  });

  it('shows the headline and the detail', () => {
    renderDialog();
    expect(screen.getByTestId('window-pick-dialog-headline'))
      .toHaveTextContent('Breaking clear with the antisolar canvas alight');
    expect(screen.getByTestId('window-pick-dialog-detail'))
      .toHaveTextContent('Low cloud clears from the west');
  });

  it('omits the detail rather than replacing it when the payload has none', () => {
    // A headline-present, detail-absent pick is a shipped state — the two are independently
    // nullable — and a "no detail available" line would be noise where silence reads fine.
    renderDialog({ detail: null });
    expect(screen.queryByTestId('window-pick-dialog-detail')).toBeNull();
    expect(screen.getByTestId('window-pick-dialog-headline')).toBeInTheDocument();
  });

  describe('the two destinations', () => {
    it('opens the map on the region', () => {
      const { onShowRegion } = renderDialog();
      fireEvent.click(screen.getByRole('button', { name: /show northumberland & tyneside on map/i }));
      expect(onShowRegion).toHaveBeenCalledTimes(1);
    });

    it('opens the map on the pick\'s own location', () => {
      const { onShowLocation } = renderDialog();
      fireEvent.click(screen.getByRole('button', { name: /open bamburgh beach on map/i }));
      expect(onShowLocation).toHaveBeenCalledTimes(1);
    });

    it('drops the location action, rather than disabling it, when nothing is rated', () => {
      // locationName is null exactly when the region has nothing rated, and a greyed control for a
      // place that does not exist explains nothing.
      renderDialog({ locationName: null, locationId: null });
      expect(screen.queryByTestId('window-pick-dialog-location-action')).toBeNull();
      // The region route survives, so the dialog is never a dead end.
      expect(screen.getByTestId('window-pick-dialog-region')).toBeInTheDocument();
    });

    it('shows the location name as prose even before it is an action', () => {
      renderDialog();
      expect(screen.getByTestId('window-pick-dialog-location')).toHaveTextContent('Bamburgh Beach');
    });
  });

  it('states no drive time or distance', () => {
    // The design's location line reads "Bothal · 45 min · 23 mi". The last two are per-user reach
    // data, which must never ride the ETag-revalidated briefing payload; the lens arrives at P8
    // through its own endpoint. The name alone is honest now.
    renderDialog();
    expect(screen.getByTestId('window-pick-dialog').textContent).not.toMatch(/\bmin\b|\bmi\b/);
  });

  it('states no rating, because the card header already states a different one', () => {
    // Pick.averageRating is the region average, canopy-inclusive; the header's star is bestRating,
    // a max over non-canopy slots. Two different statistics under one glyph, one row apart.
    renderDialog();
    expect(screen.getByTestId('window-pick-dialog').textContent).not.toMatch(/★|\/5/);
  });

  describe('getting out of it', () => {
    it('closes on Escape', () => {
      // Modal's own key handler sits on an empty, untabbable backdrop div, so it can never be a
      // keydown target — a keyboard user had no way out at all except taking a navigation they did
      // not ask for. P4b shipped exactly this dismissal one phase earlier.
      const { onClose } = renderDialog();
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('stops listening for Escape once it has gone', () => {
      const { onClose } = renderDialog();
      screen.getByTestId('window-pick-dialog-close').click();
      onClose.mockClear();

      fireEvent.keyDown(document, { key: 'Escape' });
      // Still mounted in this test (the parent owns the open state), so it fires again — what must
      // NOT happen is a listener surviving unmount. Asserted by unmounting below.
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('removes its listener on unmount', () => {
      const onClose = vi.fn();
      const { unmount } = render(
        <WindowPickDialog pick={pick()} when="Tomorrow sunset" time="21:11" onClose={onClose} />,
      );
      unmount();

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).not.toHaveBeenCalled();
    });

    it('closes from a real close control, as every other dialog in the app offers', () => {
      const { onClose } = renderDialog();
      fireEvent.click(screen.getByRole('button', { name: 'Close' }));
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('closes from the backdrop', () => {
      const { onClose } = renderDialog();
      fireEvent.click(screen.getByTestId('window-pick-dialog-backdrop'));
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  it('offers both destinations as real buttons, reachable without a pointer', () => {
    // The whole reason this is a dialog rather than the hover peek: a keyboard or touch user has
    // to be able to reach both destinations.
    renderDialog();
    const dialog = screen.getByRole('dialog');
    const actions = within(dialog).getAllByRole('button')
      .filter((b) => b.getAttribute('aria-label') !== 'Close');
    expect(actions).toHaveLength(2);
    actions.forEach((b) => expect(b).toHaveAttribute('type', 'button'));
  });
});
