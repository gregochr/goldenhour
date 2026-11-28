/**
 * The phone map sheets with the REAL `BottomSheet` (glance-to-map-plan.md G1). Every other Map test mocks the sheet
 * as a passthrough, which cannot see what the real one does. (A warning no longer has a sheet: it opens in place under
 * its row, map-warning-row-plan.md M2, and is tested in `MapViewGlancePanels.test.jsx`.)
 */
import {
  describe, it, expect, beforeAll,
} from 'vitest';
import { screen, fireEvent } from '@testing-library/react';
import { mapViewState } from './mapViewMocks.jsx';
import { installGlanceHooks } from './mapViewGlanceHarness.jsx';

// Before the harness first imports `MapView`, so it gets the real `BottomSheet`.
beforeAll(() => { mapViewState.realSheets = true; });
const { renderMap } = installGlanceHooks({ isMobile: true });

describe('the phone map sheets, real BottomSheet', () => {
  it('opens the phone List door from the real peek sheet', async () => {
    await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-win'));
    expect(screen.getByTestId('wf-map-peek-list')).toHaveTextContent('See all 3 spots');
  });

  it.each([
    ['Regions', 'wf-map-peek-lay-regions', 'wf-jump-menu'],
    ['Filters', 'wf-map-peek-lay-filters', 'wf-filters-panel'],
  ])('closes the %s sheet when the pane hides, and it does not return with the pane (review H1)', async (_name, row, sheet) => {
    const view = await renderMap();
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    fireEvent.click(screen.getByTestId(row));
    expect(screen.getByTestId('bottom-sheet-root')).toContainElement(screen.getByTestId(sheet));
    await view.again({ panelShown: false });
    expect(screen.queryByTestId(sheet)).toBeNull();
    expect(screen.queryByTestId('bottom-sheet-root')).toBeNull();
    await view.again({ panelShown: true });
    expect(screen.queryByTestId(sheet)).toBeNull();
  });
});
