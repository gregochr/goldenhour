/**
 * The window panel and the region panel for a body the server shaped by a Lite reader's picks
 * (sub-regions S5a-2): a region with none of the reader's places says so in words and wears a Pro pill, a
 * region with some counts only the reader's own places and names the rest separately, and nothing outside
 * the picks is ever "not scored", "0" or a dash. The same props with the shaped fields absent render what
 * they always did (the Pro, Admin and flag-off reader).
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import {
  render, screen, fireEvent, within,
} from '@testing-library/react';
import MapWindowPanel from '../components/map/MapWindowPanel.jsx';
import MapRegionPanel from '../components/map/MapRegionPanel.jsx';

const ROW = {
  id: 'solar:2026-10-28:SUNRISE', kind: 'solar', eventType: 'SUNRISE', label: 'Wed 28 sunrise', time: '07:43', confidence: 'high',
};

const NONE = {
  name: 'The Yorkshire Dales', tier: 'WORTH_IT', verdictLabel: 'Worth it', meanRating: null, bestRating: null,
  driveLabel: null, placeCount: 0, atFourPlus: 0, shaped: true, hasPicks: false, outsideCount: 62,
};
const SOME = {
  name: 'Northumberland & Tyneside', tier: 'WORTH_IT', verdictLabel: 'Worth it', meanRating: null, bestRating: 4,
  driveLabel: null, placeCount: 21, atFourPlus: 6, shaped: true, hasPicks: true, outsideCount: 0,
};
const MIXED = {
  name: 'The Lake District', tier: 'MAYBE', verdictLabel: 'Maybe', meanRating: null, bestRating: null,
  driveLabel: null, placeCount: 10, atFourPlus: 2, shaped: true, hasPicks: true, outsideCount: 54,
};

const FORBIDDEN = /not rated|not scored|unrated|—|\b0 rated\b|\b0 of 0\b/i;

function renderWindow(rows, props = {}) {
  const onSelectRegion = vi.fn();
  const view = render(
    <MapWindowPanel
      row={ROW}
      verdict={{ tier: 'WORTH_IT' }}
      note=""
      rows={rows}
      onClose={vi.fn()}
      onSelectRegion={onSelectRegion}
      {...props}
    />,
  );
  return { ...view, onSelectRegion };
}
const rowOf = (name) => screen.getAllByTestId('wf-win-panel-row').find((r) => r.dataset.region === name);

describe('MapWindowPanel — a shaped body', () => {
  it('states a region with none of the reader\'s places in words, keeps its verdict word and wears a plain Pro pill', () => {
    renderWindow([NONE]);
    const row = rowOf('The Yorkshire Dales');

    expect(within(row).getByTestId('wf-win-panel-stat')).toHaveTextContent('Outside your sub-regions · 62 places');
    expect(within(row).getByTestId('wf-win-panel-word')).toHaveTextContent(/worth it/i);
    const best = within(row).getByTestId('wf-win-panel-best');
    expect(within(best).getByText('Pro')).toHaveClass('wf-lens-pro');
    expect(best.textContent).toBe('Pro');
    expect(row.textContent).not.toMatch(FORBIDDEN);
  });

  it('keeps the row a real button that opens the region panel', () => {
    const { onSelectRegion } = renderWindow([NONE]);
    const row = rowOf('The Yorkshire Dales');

    expect(row.tagName).toBe('BUTTON');
    expect(row).not.toBeDisabled();
    fireEvent.click(row);
    expect(onSelectRegion).toHaveBeenCalledWith(expect.objectContaining({ name: 'The Yorkshire Dales' }));
  });

  it('prints one place singular', () => {
    renderWindow([{ ...NONE, outsideCount: 1 }]);

    expect(screen.getByTestId('wf-win-panel-stat')).toHaveTextContent('Outside your sub-regions · 1 place');
    expect(screen.getByTestId('wf-win-panel-stat')).not.toHaveTextContent('1 places');
  });

  it('prints "k of m at 4 stars+" for a region with picks, with no drive and no "more outside" when none lie outside', () => {
    renderWindow([SOME]);
    const stat = within(rowOf('Northumberland & Tyneside')).getByTestId('wf-win-panel-stat');

    expect(stat.textContent).toContain('6 of 21 at 4');
    expect(stat.textContent).not.toContain('more outside');
    expect(stat.textContent).not.toMatch(/\bmin\b|\bh\b/);
  });

  it('appends "· n more outside" when places lie outside the picks, and the served best in the best column', () => {
    renderWindow([{ ...MIXED, bestRating: 4 }]);
    const row = rowOf('The Lake District');

    expect(within(row).getByTestId('wf-win-panel-stat').textContent).toContain('2 of 10 at 4');
    expect(within(row).getByTestId('wf-win-panel-more-outside')).toHaveTextContent('· 54 more outside');
    expect(within(row).getByTestId('wf-win-panel-best').textContent).toContain('4');
    expect(within(row).queryByText('Pro')).toBeNull();
  });

  it('keeps "not scored" for a region the reader holds places in that nothing has rated', () => {
    renderWindow([MIXED]);

    expect(within(rowOf('The Lake District')).getByTestId('wf-win-panel-best')).toHaveTextContent('not scored');
  });

  it('draws no note at all when the note is empty (a reader with no pick)', () => {
    renderWindow([NONE]);

    expect(screen.queryByTestId('wf-win-panel-note')).toBeNull();
  });

  it('sets the lead region name in bold inside the note', () => {
    renderWindow([NONE, SOME], { note: 'Best in the Yorkshire Dales, outside your sub-regions.', noteStrong: 'Yorkshire Dales' });

    expect(screen.getByTestId('wf-win-panel-note').querySelector('b')).toHaveTextContent('Yorkshire Dales');
    expect(screen.getByTestId('wf-win-panel-note')).toHaveTextContent('Best in the Yorkshire Dales, outside your sub-regions.');
  });

  it('marks a no-pick stat so the phone can wrap it rather than cut it', () => {
    renderWindow([NONE, SOME]);

    expect(within(rowOf('The Yorkshire Dales')).getByTestId('wf-win-panel-stat')).toHaveAttribute('data-outside', 'true');
    expect(within(rowOf('Northumberland & Tyneside')).getByTestId('wf-win-panel-stat')).not.toHaveAttribute('data-outside');
  });

  it('draws the note it is given', () => {
    renderWindow([NONE, SOME], { note: 'Best in the Yorkshire Dales, outside your sub-regions.' });

    expect(screen.getByTestId('wf-win-panel-note')).toHaveTextContent('Best in the Yorkshire Dales, outside your sub-regions.');
  });
});

describe('MapWindowPanel — not shaped: byte-identical to before', () => {
  const plain = { ...MIXED, shaped: undefined, hasPicks: undefined, outsideCount: undefined };

  it('draws the old stat and best column, and never a Pro pill or "outside"', () => {
    const { container } = renderWindow([plain, { ...plain, name: 'North East', bestRating: 4 }], { note: 'Verdict.' });

    expect(within(rowOf('The Lake District')).getByTestId('wf-win-panel-stat')).toHaveTextContent('2 of 10 at 4');
    expect(within(rowOf('The Lake District')).getByTestId('wf-win-panel-best')).toHaveTextContent('not scored');
    expect(container.textContent).not.toMatch(/outside|Pro\b|more outside/i);
    expect(screen.getByTestId('wf-win-panel-note')).toHaveTextContent('Verdict.');
  });
});

describe('MapRegionPanel — a shaped body', () => {
  const REGION_NONE = { ...NONE, name: 'The Yorkshire Dales' };
  const PICKS_NONE = {
    places: 62,
    allowedPlaces: 0,
    outsidePlaces: [],
    subRegions: [
      { key: '1', name: 'Wasdale', places: 30, picked: false },
      { key: '2', name: 'Wensleydale', places: 1, picked: false },
    ],
    pickedNames: [],
  };
  const outsideOf = (n) => Array.from({ length: n }, (_, i) => ({
    id: 100 + i, name: `Place ${String(i).padStart(2, '0')}`, subRegionId: 10 + (i % 2), subRegionName: i % 2 ? 'Langdales' : 'Wasdale',
  }));
  const PICKED_MIXED = (n) => ({
    places: 10 + n,
    allowedPlaces: 10,
    outsidePlaces: outsideOf(n),
    subRegions: [],
    pickedNames: ['Keswick & Borrowdale', 'Newlands'],
  });

  function renderRegion(props = {}) {
    const handlers = {
      onBack: vi.fn(), onClose: vi.fn(), onZoomToRegion: vi.fn(), onOpenLocationSheet: vi.fn(),
    };
    const view = render(
      <MapRegionPanel
        row={ROW}
        region={REGION_NONE}
        locations={[]}
        gloss={{ headline: 'Brighter to the north.', detail: 'Keswick clears first.' }}
        picks={PICKS_NONE}
        {...handlers}
        {...props}
      />,
    );
    return { ...view, ...handlers };
  }

  it('says "k sub-regions · n places · none picked" with a grey-wash block and the sub-regions with counts', () => {
    renderRegion();

    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent('2 sub-regions · 62 places · none picked');
    expect(screen.getByTestId('wf-reg-panel-outside-block')).toHaveTextContent('Ratings here are outside your sub-regions.');
    const subs = screen.getAllByTestId('wf-reg-panel-sub');
    expect(subs.map((s) => s.textContent)).toEqual(['Wasdale30 places', 'Wensleydale1 place']);
    expect(screen.getByTestId('wf-reg-panel-gloss')).toBeInTheDocument();
  });

  it('offers Zoom to region only, leaving the two S5a-3 seams empty, and never "Nothing is rated"', () => {
    const { container } = renderRegion();

    expect(screen.getByTestId('wf-reg-panel-zoom')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-reg-panel-four-days')).toBeNull();
    expect(screen.queryByTestId('wf-reg-panel-empty')).toBeNull();
    expect(container.querySelector('.wf-reg-actions').querySelectorAll('button')).toHaveLength(1);
    expect(screen.getByTestId('wf-reg-panel-stats').textContent).not.toMatch(FORBIDDEN);
  });

  it('renders what S5a-3 puts in the two slots, where the seams are', () => {
    renderRegion({
      outsideSlot: <span data-testid="seam-outside">status</span>,
      actionsSlot: <button type="button" data-testid="seam-actions">change</button>,
    });

    expect(within(screen.getByTestId('wf-reg-panel-outside-block')).getByTestId('seam-outside')).toBeInTheDocument();
    expect(screen.getByTestId('seam-actions').closest('.wf-reg-actions')).not.toBeNull();
  });

  it('prints a single sub-region singular', () => {
    renderRegion({ picks: { ...PICKS_NONE, places: 1, subRegions: [PICKS_NONE.subRegions[0]] } });

    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent('1 sub-region · 1 place · none picked');
  });

  it('names the picked sub-regions, the places the reader holds and the 4-star count when some are picked', () => {
    renderRegion({ region: { ...MIXED, atFourPlus: 2 }, picks: PICKED_MIXED(5), locations: [] });

    expect(screen.getByTestId('wf-reg-panel-stats'))
      .toHaveTextContent('Keswick & Borrowdale, Newlands · 10 places · 2 at 4★+');
  });

  it('says the reader\'s own places are unrated, not the region\'s, when none of them has a rating', () => {
    renderRegion({ region: MIXED, picks: PICKED_MIXED(2), locations: [] });

    expect(screen.getByTestId('wf-reg-panel-empty')).toHaveTextContent('Nothing in your sub-regions in The Lake District is rated');
  });

  it('prints the divider with the count and the sub-regions it spans, and the outside rows with no star or tag', () => {
    renderRegion({ region: MIXED, picks: PICKED_MIXED(3) });

    expect(screen.getByTestId('wf-reg-panel-outside-head')).toHaveTextContent('OUTSIDE YOUR SUB-REGIONS');
    expect(screen.getByTestId('wf-reg-panel-outside-count')).toHaveTextContent('3 places in 2 sub-regions');
    const rows = screen.getAllByTestId('wf-reg-panel-outside-row');
    expect(rows.map((r) => r.dataset.location)).toEqual(['Place 00', 'Place 01', 'Place 02']);
    rows.forEach((r) => {
      expect(r.tagName).toBe('BUTTON');
      expect(r.textContent).not.toMatch(/★|\d\.\d|not scored|not rated|tide/i);
    });
    expect(rows[0]).toHaveTextContent('Wasdale');
  });

  it('prints one place and one sub-region singular in the divider', () => {
    renderRegion({
      region: MIXED,
      picks: { ...PICKED_MIXED(1), outsidePlaces: [{ id: 1, name: 'Only', subRegionId: 5, subRegionName: 'One' }] },
    });

    expect(screen.getByTestId('wf-reg-panel-outside-count')).toHaveTextContent('1 place in 1 sub-region');
  });

  it('does not collapse at four outside places', () => {
    renderRegion({ region: MIXED, picks: PICKED_MIXED(4) });

    expect(screen.getAllByTestId('wf-reg-panel-outside-row')).toHaveLength(4);
    expect(screen.queryByTestId('wf-reg-panel-show-all')).toBeNull();
  });

  it('collapses past four: three rows and "Show all n", a toggle that opens the list and folds it back', () => {
    renderRegion({ region: MIXED, picks: PICKED_MIXED(5) });

    expect(screen.getAllByTestId('wf-reg-panel-outside-row')).toHaveLength(3);
    const more = screen.getByTestId('wf-reg-panel-show-all');
    expect(more).toHaveTextContent('Show all 5');
    expect(more).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(more);
    expect(screen.getAllByTestId('wf-reg-panel-outside-row')).toHaveLength(5);
    expect(screen.getByTestId('wf-reg-panel-show-all')).toHaveTextContent('Show fewer');
    expect(screen.getByTestId('wf-reg-panel-show-all')).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(screen.getByTestId('wf-reg-panel-show-all'));
    expect(screen.getAllByTestId('wf-reg-panel-outside-row')).toHaveLength(3);
  });

  it('keeps focus on the toggle after expanding, so Escape still goes back', () => {
    const { onBack } = renderRegion({ region: MIXED, picks: PICKED_MIXED(5) });
    const more = screen.getByTestId('wf-reg-panel-show-all');

    more.focus();
    fireEvent.click(more);
    expect(document.activeElement).toBe(screen.getByTestId('wf-reg-panel-show-all'));
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(onBack).toHaveBeenCalled();
  });

  it('opens the location sheet from an outside row with the place', () => {
    const { onOpenLocationSheet } = renderRegion({ region: MIXED, picks: PICKED_MIXED(2) });

    fireEvent.click(screen.getAllByTestId('wf-reg-panel-outside-row')[1]);
    expect(onOpenLocationSheet).toHaveBeenCalledWith(expect.objectContaining({ name: 'Place 01' }));
  });

  it('draws no outside block and no divider when none of the region lies outside the picks', () => {
    renderRegion({ region: SOME, picks: PICKED_MIXED(0) });

    expect(screen.queryByTestId('wf-reg-panel-outside-head')).toBeNull();
  });

  it('is exactly the old panel when picks is absent', () => {
    const { container } = render(
      <MapRegionPanel
        row={ROW}
        region={{ ...MIXED, shaped: undefined, driveLabel: '1h 35min', meanRating: 4.2, bestRating: 5 }}
        locations={[{
          id: 1, name: 'Ashness Bridge', rating: 5, driveLabel: '1h 35min', leaveTime: '14:25', leaveDayWord: null, tideTier: null,
        }]}
        onBack={vi.fn()}
        onClose={vi.fn()}
        onZoomToRegion={vi.fn()}
        onOpenLocationSheet={vi.fn()}
      />,
    );

    expect(screen.getByTestId('wf-reg-panel-stats')).toHaveTextContent(/2 of 10 at 4★\+.*· nearest 1h 35min · average 4\.2★/);
    expect(screen.getByTestId('wf-reg-panel-four-days')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/outside|none picked|sub-region/i);
  });
});
