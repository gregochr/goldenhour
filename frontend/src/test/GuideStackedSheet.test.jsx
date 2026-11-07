import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import LocationFourDaySheet from '../components/LocationFourDaySheet.jsx';
import { GuideProvider } from '../context/GuideContext.jsx';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';
import { GUIDE_ORIGIN } from '../utils/guide.js';
import { installViewport, resetViewport } from './askViewport.js';

/**
 * G3 review 1: the four-day sheet over the window popup is already two layers deep, but the popup is stacked
 * (inert, no aria-modal), so a count of aria-modal dialogs alone saw one. What breaks if this fails: the guide
 * opens as a third layer, or closes the reader's sheet to make room.
 */
const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };
const WINDOWS = [{
  key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false,
  label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false,
}];
const ROWS = [{
  locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 3,
}];

beforeEach(() => installViewport(1280));
afterEach(() => { resetViewport(); vi.restoreAllMocks(); });

describe('the guide over a sheet that is itself over the window popup', () => {
  it('opens the full page in a new tab and mounts no third layer, leaving both open', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null);
    const onClose = vi.fn();
    render(
      <GuideProvider activeTab="plan">
        {/* The window popup under the sheet: stacked, so inert with no aria-modal. */}
        <div role="dialog" aria-label="Window popup" inert data-testid="popup" />
        <LocationFourDaySheet
          spot={SPOT}
          windows={WINDOWS}
          scoreIndex={buildScoreIndex(ROWS)}
          slotIndex={buildSlotIndex([])}
          regionGlossIndex={buildRegionGlossIndex([])}
          scoresKnown
          todayStr="2026-08-14"
          scoresLocked
          onClose={onClose}
          onShowOnMap={vi.fn()}
        />
      </GuideProvider>,
    );
    fireEvent.click(screen.getByTestId('wf-scores-upsell-trigger'));
    fireEvent.click(screen.getByTestId('wf-scores-upsell-guide'));
    expect(open).toHaveBeenCalledWith(
      `${GUIDE_ORIGIN}/guide/reading-the-forecast.html#fiery`, '_blank', 'noopener,noreferrer',
    );
    expect(screen.queryByRole('dialog', { name: 'PhotoCast guide' })).toBeNull();
    expect(screen.queryByTestId('guide-dock')).toBeNull();
    expect(screen.getByTestId('popup')).toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getAllByTestId('location-sheet-row').length).toBeGreaterThan(0);
  });
});
