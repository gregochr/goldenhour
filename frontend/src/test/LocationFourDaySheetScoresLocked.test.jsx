import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import LocationFourDaySheet from '../components/LocationFourDaySheet.jsx';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';

/**
 * The four-day sheet for a reader without the scores (docs/engineering/pro-features-gate-plan.md §5.3).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's sheet draws a score bar or a per-location sentence
 * it should not have, tells them "Nothing written about this one yet." (which says the forecast has no
 * prose), loses the free region gloss, never offers the upsell, or one Escape inside the open upsell
 * closes the whole sheet.
 */

const TODAY = '2026-08-14';
const SPOT = { id: 7, name: 'Bamburgh', regionName: 'Northumberland' };

const WINDOWS = [
  { key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false },
  { key: '2026-08-15:SUNSET', date: '2026-08-15', targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tomorrow Sunset', time: '20:35', verdictLabel: 'Poor', confidence: 'high', away: false },
];

const FULL_ROWS = [
  {
    locationId: 7, locationName: 'Bamburgh', date: '2026-08-14', targetType: 'SUNSET', rating: 3,
    summary: 'High cloud thins after eight.', fierySkyPotential: 62, goldenHourPotential: 58,
  },
  {
    locationId: 7, locationName: 'Bamburgh', date: '2026-08-15', targetType: 'SUNSET', rating: 2,
    summary: 'Blanket low cloud to the west.', fierySkyPotential: 30, goldenHourPotential: 25,
  },
];

/** What the context hands a Lite reader: the same rows without the three Pro fields. */
const LITE_ROWS = FULL_ROWS.map((full) => {
  const lite = { ...full };
  delete lite.fierySkyPotential;
  delete lite.goldenHourPotential;
  delete lite.summary;
  return lite;
});

const DAY = {
  date: '2026-08-15',
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [{
      regionName: 'Northumberland',
      confidence: 'low',
      glossHeadline: null,
      glossDetail: 'High cloud canvas with modest colour potential across the region.',
      slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: '2026-08-15T19:39:00' }],
    }],
  }],
};

const row = (key) => screen.getAllByTestId('location-sheet-row')
  .find((el) => el.dataset.window === key);

function setup(rows, props = {}) {
  const handlers = { onClose: vi.fn(), onShowOnMap: vi.fn() };
  render(
    <LocationFourDaySheet
      spot={SPOT}
      windows={WINDOWS}
      scoreIndex={buildScoreIndex(rows)}
      slotIndex={buildSlotIndex([DAY])}
      regionGlossIndex={buildRegionGlossIndex([DAY])}
      scoresKnown
      todayStr={TODAY}
      {...handlers}
      {...props}
    />,
  );
  return handlers;
}

describe('LocationFourDaySheet — a reader without the scores', () => {
  it('draws no score bars and no per-location sentence', () => {
    setup(LITE_ROWS, { scoresLocked: true });
    expect(screen.queryByTestId('location-sheet-scores')).toBeNull();
    expect(screen.queryByTestId('location-sheet-fiery')).toBeNull();
    expect(screen.queryByTestId('location-sheet-golden')).toBeNull();
    expect(screen.queryByText(/High cloud thins after eight/)).toBeNull();
  });

  it('never says "Nothing written about this one yet."', () => {
    setup(LITE_ROWS, { scoresLocked: true });
    expect(screen.queryByText(/Nothing written about this one yet/)).toBeNull();
    expect(screen.queryByTestId('location-sheet-nowhy')).toBeNull();
  });

  it('keeps the star rating', () => {
    setup(LITE_ROWS, { scoresLocked: true });
    expect(within(row('2026-08-14:SUNSET')).getByTestId('location-sheet-rating')).toHaveTextContent('3★');
    expect(within(row('2026-08-15:SUNSET')).getByTestId('location-sheet-rating')).toHaveTextContent('2★');
  });

  it('keeps the free region gloss as the why where there is one', () => {
    setup(LITE_ROWS, { scoresLocked: true });
    const sat = within(row('2026-08-15:SUNSET'));
    expect(sat.getByTestId('location-sheet-why'))
      .toHaveTextContent('High cloud canvas with modest colour potential across the region.');
    expect(sat.getByTestId('location-sheet-why-region')).toHaveTextContent('Northumberland sky');
  });

  it('still says "Ratings are still loading." while the ratings are unknown', () => {
    setup(null, { scoresLocked: true, scoresKnown: false });
    expect(within(row('2026-08-14:SUNSET')).getByTestId('location-sheet-nowhy'))
      .toHaveTextContent('Ratings are still loading.');
  });

  it('keeps the away line on a travel day', () => {
    const away = { key: '2026-08-16:SUNRISE', date: '2026-08-16', targetType: 'SUNRISE', dow: 'Sun', sunrise: true, label: 'Sun Sunrise', time: '05:40', verdictLabel: 'Not forecast', confidence: null, away: true };
    setup(LITE_ROWS, { scoresLocked: true, windows: [...WINDOWS, away] });
    expect(within(row('2026-08-16:SUNRISE')).getByTestId('location-sheet-nowhy'))
      .toHaveTextContent('Nothing was forecast for this day — away.');
  });

  it('draws one upsell door, with no link and no "upgrade"', () => {
    setup(LITE_ROWS, { scoresLocked: true });
    expect(screen.getAllByTestId('wf-scores-upsell-trigger')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Scores and the why Pro' })).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('wf-scores-upsell-trigger'));
    const popover = screen.getByTestId('wf-scores-upsell-popover');
    expect(popover).toHaveTextContent('Fiery Sky and Golden Hour scores');
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByTestId('wf-scores-upsell-door').textContent.toLowerCase()).not.toContain('upgrade');
  });

  it('the door opens and "Not now" closes it again', () => {
    setup(LITE_ROWS, { scoresLocked: true });
    fireEvent.click(screen.getByTestId('wf-scores-upsell-trigger'));
    fireEvent.click(screen.getByTestId('wf-scores-upsell-dismiss'));
    expect(screen.queryByTestId('wf-scores-upsell-popover')).toBeNull();
    expect(screen.getByTestId('wf-scores-upsell-trigger')).toHaveFocus();
  });

  it('⚠️ one Escape closes only the popover, a second closes the sheet', () => {
    const { onClose } = setup(LITE_ROWS, { scoresLocked: true });
    fireEvent.click(screen.getByTestId('wf-scores-upsell-trigger'));
    expect(screen.getByTestId('wf-scores-upsell-heading')).toHaveFocus();

    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(screen.queryByTestId('wf-scores-upsell-popover')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-scores-upsell-trigger')).toHaveFocus();

    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('draws no door on a sheet with no windows to sell', () => {
    setup(LITE_ROWS, { scoresLocked: true, windows: [] });
    expect(screen.queryByTestId('wf-scores-upsell-trigger')).toBeNull();
  });
});

describe('LocationFourDaySheet — a reader with the scores', () => {
  it('draws the bars and the sentence, and no door', () => {
    setup(FULL_ROWS);
    const fri = within(row('2026-08-14:SUNSET'));
    expect(fri.getByTestId('location-sheet-fiery')).toHaveTextContent('62');
    expect(fri.getByTestId('location-sheet-golden')).toHaveTextContent('58');
    expect(fri.getByTestId('location-sheet-why')).toHaveTextContent('High cloud thins after eight.');
    expect(screen.queryByTestId('wf-scores-upsell-trigger')).toBeNull();
  });

  it('still says "Nothing written about this one yet." for a rated window with no sentence', () => {
    setup(LITE_ROWS);
    expect(within(row('2026-08-14:SUNSET')).getByTestId('location-sheet-nowhy'))
      .toHaveTextContent('Nothing written about this one yet.');
  });
});
