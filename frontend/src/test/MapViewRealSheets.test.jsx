/**
 * The phone warning sheet with the REAL `BottomSheet` (glance-to-map-plan.md G1). Every other Map test mocks the sheet
 * as a passthrough, which cannot see what the real one does: it is not `aria-modal` here, it draws its scrim, and it
 * must not answer Escape a second time beside the pane's own rung.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeAll, beforeEach, afterEach,
} from 'vitest';
import { act, screen, fireEvent } from '@testing-library/react';
import {
  mapViewState, renderGlanceMap, resetGlanceMap, restoreGlanceClock,
} from './mapViewGlanceHarness.jsx';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ location }) => <b data-testid="probe-callout-name">{location?.name}</b>,
}));

let MapView;
let onOpenList;
beforeAll(async () => {
  mapViewState.realSheets = true;
  ({ default: MapView } = await import('../components/MapView.jsx'));
});
const renderMap = () => renderGlanceMap(MapView, onOpenList);

beforeEach(() => {
  resetGlanceMap({ isMobile: true });
  onOpenList = vi.fn(() => true);
});
afterEach(restoreGlanceClock);

describe('the phone warning sheet, real BottomSheet', () => {
  it('is a labelled sheet that is not aria-modal, so it is not a foreign modal to the Map\'s rules', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    const root = screen.getByTestId('bottom-sheet-root');
    expect(root).toContainElement(screen.getByTestId('map-warning-panel'));
    const dialog = root.querySelector('[role="dialog"]');
    expect(dialog).not.toBeNull();
    expect(dialog).not.toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveAccessibleName('Met Office weather warning');
  });

  it('closes on one Escape and leaves the callout: exactly one layer per press', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    fireEvent.click(screen.getByTestId('wf-map-peek-list'));
    await act(async () => { onOpenList.mock.calls[0][0].onPick({ locationId: 3, locationName: 'Wastwater' }); });
    expect(await screen.findByTestId('probe-callout-name')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('map-warning-toggle'));
    expect(screen.getByTestId('map-warning-panel')).toBeInTheDocument();
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-warning-panel'), { key: 'Escape', bubbles: true }); });
    expect(screen.queryByTestId('map-warning-panel')).toBeNull();
    expect(screen.getByTestId('probe-callout-name')).toBeInTheDocument();
    await act(async () => { fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' }); });
    expect(screen.queryByTestId('probe-callout-name')).toBeNull();
  });

  it('opens the phone List door from the real peek sheet', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.getByTestId('wf-map-peek-list')).toHaveTextContent('See all 3 spots');
  });
});
