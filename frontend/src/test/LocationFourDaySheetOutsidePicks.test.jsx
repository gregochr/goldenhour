import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import LocationFourDaySheet from '../components/LocationFourDaySheet.jsx';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { buildScoreIndex, buildSlotIndex } from '../utils/locationSheet.js';
import { buildRegionGlossIndex } from '../utils/regionGloss.js';
import { FIRST_CHOICE, LOCKED_FULL, OPEN } from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The four-day sheet for a place outside a Lite reader's sub-regions (sub-regions-plan §11 S5a-3, decision 12;
 * handoff task 5).
 *
 * <p><b>What breaks if these fail:</b> the sheet tells a reader a place is "Not scored yet" or "Not rated" when
 * the truth is that it is outside their picks, keeps a "best here" tag, a lead line or the scores door over
 * ratings that do not exist, opens the wrong row while the scores are still loading, stacks a third dialog when
 * Settings opens, or changes anything at all for a reader who is not shaped.
 */

const TODAY = '2026-08-14';
const SPOT = { id: 7, name: 'Malham Cove', regionName: 'The Yorkshire Dales' };
const LOCATION = {
  id: 7, name: 'Malham Cove', subRegionName: 'Malhamdale & Airedale', locationType: ['LANDSCAPE'], tideType: [],
};

const WINDOWS = [
  { key: '2026-08-14:SUNSET', date: '2026-08-14', targetType: 'SUNSET', dow: 'Fri', sunrise: false, label: 'Tonight Sunset', time: '20:37', verdictLabel: 'Worth it', confidence: 'high', away: false },
  { key: '2026-08-15:SUNRISE', date: '2026-08-15', targetType: 'SUNRISE', dow: 'Sat', sunrise: true, label: 'Tomorrow Sunrise', time: '05:41', verdictLabel: 'Poor', confidence: 'high', away: false },
];

/** What the server leaves of a gated place's row: an id and the window, no rating, and a served UNRATED verdict. */
const GATED_ROWS = WINDOWS.map((w) => ({
  locationId: 7, locationName: 'Malham Cove', date: w.date, targetType: w.targetType, rating: null, displayVerdict: 'UNRATED',
}));

/** The same place, rated, for a reader whose picks include it. */
const RATED_ROWS = [
  { locationId: 7, locationName: 'Malham Cove', date: '2026-08-14', targetType: 'SUNSET', rating: 4, summary: 'Clear west.' },
  { locationId: 7, locationName: 'Malham Cove', date: '2026-08-15', targetType: 'SUNRISE', rating: 2, summary: 'Low cloud.' },
];

const DAY = {
  date: '2026-08-15',
  eventSummaries: [{
    targetType: 'SUNRISE',
    regions: [{
      regionName: 'The Yorkshire Dales',
      confidence: 'low',
      glossHeadline: null,
      glossDetail: 'Cloud breaks over the Three Peaks before sunrise.',
      slots: [{ locationId: 7, locationName: 'Malham Cove', solarEventTime: '2026-08-15T05:41:00' }],
    }],
  }],
};

const FORBIDDEN = /not scored|not rated|unscored|best here|nothing written|at 4★\+|none at 4/i;

function setup(rows, props = {}, status = null) {
  getSubRegions.mockResolvedValue(status ?? OPEN);
  const handlers = { onClose: vi.fn(), onShowOnMap: vi.fn() };
  const view = render(
    <SubRegionStatusProvider enabled={status !== false} onOpen={vi.fn()}>
      <LocationFourDaySheet
        spot={SPOT}
        windows={WINDOWS}
        scoreIndex={buildScoreIndex(rows)}
        slotIndex={buildSlotIndex([DAY])}
        regionGlossIndex={buildRegionGlossIndex([DAY])}
        location={LOCATION}
        scoresKnown
        todayStr={TODAY}
        {...handlers}
        {...props}
      />
    </SubRegionStatusProvider>,
  );
  return { ...handlers, ...view };
}

const rows = () => screen.getAllByTestId('location-sheet-row');
const expandedRows = () => rows().filter(
  (r) => within(r).getByTestId('location-sheet-row-toggle').getAttribute('aria-expanded') === 'true',
);

describe('LocationFourDaySheet: a place outside the reader\'s sub-regions', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('names the scope in the badge, in the grey family, in place of the scope badge', () => {
    setup(GATED_ROWS, { outsidePicks: true, scopeRegionNames: ['Somewhere Else'], origin: { name: 'Keswick' } });

    const badge = screen.getByTestId('location-sheet-outside');
    expect(badge).toHaveTextContent('Outside your sub-regions');
    expect(badge).toHaveAttribute('data-scope', 'picks');
    expect(badge).toHaveClass('wf-loc-out');
    expect(screen.getAllByTestId('location-sheet-outside')).toHaveLength(1);
  });

  it('says why, with the sub-region, and that the light times and the region forecast still show', () => {
    setup(GATED_ROWS, { outsidePicks: true });

    expect(screen.getByTestId('location-sheet-outside-text')).toHaveTextContent(
      "Malhamdale & Airedale isn't one of your sub-regions, so Malham Cove has no ratings for you. "
      + 'Light times and the forecast for the Yorkshire Dales still show.',
    );
  });

  it('names the sub-region beside the region in the header, and leaves it out when none is served', () => {
    const { unmount } = setup(GATED_ROWS, { outsidePicks: true });
    expect(screen.getByTestId('location-sheet-meta')).toHaveTextContent('The Yorkshire Dales · Malhamdale & Airedale');
    unmount();

    setup(GATED_ROWS, { outsidePicks: true, location: { ...LOCATION, subRegionName: null } });
    expect(screen.getByTestId('location-sheet-meta').textContent).toBe('The Yorkshire Dales');
  });

  it('a wildlife hide outside the picks keeps the status line, the button and the door, and drops only the sentence', async () => {
    setup(GATED_ROWS, {
      outsidePicks: true, onOpenSubRegions: vi.fn(), location: { ...LOCATION, locationType: ['WILDLIFE'] },
    });

    expect(await screen.findByTestId('location-sheet-subregion-action')).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-outside-status')).toBeInTheDocument();
    expect(screen.getByTestId('wf-subregions-place-upsell-trigger')).toBeInTheDocument();
    expect(screen.queryByTestId('location-sheet-outside-text')).toBeNull();
    expect(screen.getByTestId('location-sheet-outside')).toHaveTextContent('Outside your sub-regions');
  });

  it('falls back to a sentence without the sub-region when none is served', () => {
    setup(GATED_ROWS, { outsidePicks: true, location: { ...LOCATION, subRegionName: null } });

    const text = screen.getByTestId('location-sheet-outside-text').textContent;
    expect(text).toMatch(/^Malham Cove isn't in one of your sub-regions, so it has no ratings for you\./);
    expect(text).not.toMatch(/null|undefined/);
  });

  it('never prints a rating slot, a state word, "best here", the lead line or "Nothing written"', () => {
    const { container } = setup(GATED_ROWS, { outsidePicks: true });

    expect(container.textContent).not.toMatch(FORBIDDEN);
    expect(screen.queryByTestId('location-sheet-rating')).toBeNull();
    expect(screen.queryByTestId('location-sheet-state')).toBeNull();
    expect(screen.queryByTestId('location-sheet-best')).toBeNull();
    expect(screen.queryByTestId('location-sheet-lead')).toBeNull();
    expect(screen.queryByTestId('location-sheet-nowhy')).toBeNull();
    expect(screen.queryByTestId('wf-scores-upsell-door')).toBeNull();
  });

  it('keeps the region\'s gloss under its region label', () => {
    setup(GATED_ROWS, { outsidePicks: true });

    fireEvent.click(within(rows()[1]).getByTestId('location-sheet-row-toggle'));

    expect(within(rows()[1]).getByTestId('location-sheet-why-region')).toHaveTextContent('The Yorkshire Dales sky');
    expect(within(rows()[1]).getByTestId('location-sheet-why')).toHaveTextContent('Cloud breaks over the Three Peaks');
  });

  it('draws one door, the place door, and none of the scores door even for a scores-locked reader', () => {
    setup(GATED_ROWS, { outsidePicks: true, scoresLocked: true });

    expect(screen.getAllByTestId(/-door$/)).toHaveLength(1);
    const trigger = screen.getByTestId('wf-subregions-place-upsell-trigger');
    expect(trigger).toHaveTextContent('Ratings at Malham Cove');
    expect(within(trigger).getByText('Pro')).toBeInTheDocument();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('one Escape closes the door and leaves the sheet open', () => {
    const { onClose } = setup(GATED_ROWS, { outsidePicks: true });
    fireEvent.click(screen.getByTestId('wf-subregions-place-upsell-trigger'));
    expect(screen.getByTestId('wf-subregions-place-upsell-popover')).toBeInTheDocument();

    fireEvent.keyDown(document.activeElement, { key: 'Escape' });

    expect(screen.queryByTestId('wf-subregions-place-upsell-popover')).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.getByTestId('location-sheet')).toBeInTheDocument();
  });

  it('a second Escape, with the door shut, then closes the sheet', () => {
    const { onClose } = setup(GATED_ROWS, { outsidePicks: true });
    fireEvent.keyDown(document, { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  describe('the S4 status button and line', () => {
    it('shows the status line and the button for the reader\'s status', async () => {
      setup(GATED_ROWS, { outsidePicks: true, onOpenSubRegions: vi.fn() }, FIRST_CHOICE);

      expect(await screen.findByTestId('location-sheet-subregion-action')).toHaveTextContent('Choose your sub-regions');
      expect(screen.getByTestId('location-sheet-outside-status')).toBeInTheDocument();
    });

    it('locked at the limit: the line says when, and there is no button', async () => {
      setup(GATED_ROWS, { outsidePicks: true, onOpenSubRegions: vi.fn() }, LOCKED_FULL);

      expect(await screen.findByTestId('location-sheet-outside-status'))
        .toHaveTextContent('You can next change your sub-regions on 25 November.');
      expect(screen.queryByTestId('location-sheet-subregion-action')).toBeNull();
    });

    it('presses through the sheet\'s own opener, not the provider\'s', async () => {
      const onOpenSubRegions = vi.fn();
      setup(GATED_ROWS, { outsidePicks: true, onOpenSubRegions }, OPEN);

      fireEvent.click(await screen.findByTestId('location-sheet-subregion-action'));

      expect(onOpenSubRegions).toHaveBeenCalledTimes(1);
    });
  });

  describe('which row opens', () => {
    it('opens the first row once the scores have loaded, and only that one', () => {
      setup(GATED_ROWS, { outsidePicks: true });

      expect(expandedRows()).toHaveLength(1);
      expect(expandedRows()[0]).toBe(rows()[0]);
    });

    it('opens nothing while the scores are still loading, then the first row when they land', () => {
      const { rerender } = setup([], { outsidePicks: true, scoresKnown: false });
      expect(expandedRows()).toHaveLength(0);

      rerender(
        <SubRegionStatusProvider enabled onOpen={vi.fn()}>
          <LocationFourDaySheet
            spot={SPOT}
            windows={WINDOWS}
            scoreIndex={buildScoreIndex(GATED_ROWS)}
            slotIndex={buildSlotIndex([DAY])}
            regionGlossIndex={buildRegionGlossIndex([DAY])}
            location={LOCATION}
            scoresKnown
            todayStr={TODAY}
            onClose={vi.fn()}
            onShowOnMap={vi.fn()}
            outsidePicks
          />
        </SubRegionStatusProvider>,
      );

      expect(expandedRows()).toHaveLength(1);
      expect(expandedRows()[0]).toBe(rows()[0]);
    });

    it('opens the window the reader arrived on, even before the scores load', () => {
      setup([], { outsidePicks: true, scoresKnown: false, focusWindowKey: '2026-08-15:SUNRISE' });

      expect(expandedRows()).toHaveLength(1);
      expect(expandedRows()[0].dataset.window).toBe('2026-08-15:SUNRISE');
    });
  });
});

describe('LocationFourDaySheet: a reader who is not shaped is untouched', () => {
  beforeEach(() => { vi.clearAllMocks(); });

  it('draws no badge, no block, no sub-region door and the old lead line, best tag and ratings', () => {
    setup(RATED_ROWS, {}, false);

    expect(screen.queryByTestId('location-sheet-outside')).toBeNull();
    expect(screen.queryByTestId('location-sheet-outside-block')).toBeNull();
    expect(screen.queryByTestId('wf-subregions-place-upsell-door')).toBeNull();
    expect(screen.getByTestId('location-sheet-lead')).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-best')).toBeInTheDocument();
    expect(screen.getAllByTestId('location-sheet-rating')).toHaveLength(2);
    expect(getSubRegions).not.toHaveBeenCalled();
  });

  it('still says "Not rated" for an unrated window when the reader is not shaped', () => {
    setup(GATED_ROWS, {}, false);

    expect(screen.getAllByTestId('location-sheet-state')[0]).toHaveTextContent('Not rated');
  });

  it('still opens the best row, not the first', () => {
    setup(RATED_ROWS, {}, false);

    expect(expandedRows()).toHaveLength(1);
    expect(expandedRows()[0].dataset.window).toBe('2026-08-14:SUNSET');
  });

  it('keeps the scope badge when the place is outside the plan\'s scope, not the picks', () => {
    setup(RATED_ROWS, { scopeRegionNames: ['Somewhere Else'], origin: { name: 'Keswick' } }, false);

    const badge = screen.getByTestId('location-sheet-outside');
    expect(badge).not.toHaveAttribute('data-scope');
    expect(badge).not.toHaveTextContent('Outside your sub-regions');
  });
});
