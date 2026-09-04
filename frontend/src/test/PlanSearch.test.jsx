import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import React from 'react';
import PlanSearch from '../components/PlanSearch.jsx';

/**
 * The search dialog (plan §4.8).
 *
 * <p><b>What breaks if these fail.</b> The keyboard contract is the whole of the feature for a
 * reader who reached it with {@code /} — a cursor that can rest on a row that does nothing, or an
 * Enter that opens a row belonging to the previous query, both send someone somewhere they did not
 * ask to go.
 */
describe('PlanSearch', () => {
  const WINDOWS = [
    {
      key: '2026-08-04:SUNSET',
      date: '2026-08-04',
      targetType: 'SUNSET',
      dow: 'Tue',
      label: 'Tonight Sunset',
      time: '21:11',
      verdictLabel: 'Worth it',
      away: false,
    },
    {
      key: '2026-08-06:SUNRISE',
      date: '2026-08-06',
      targetType: 'SUNRISE',
      dow: 'Thu',
      label: 'Thursday Sunrise',
      time: '05:22',
      verdictLabel: 'Maybe',
      away: false,
    },
  ];
  const LAKES = { id: 7, name: 'Lake District', baseName: 'Keswick', baseLat: 54.6, baseLon: -3.1 };
  const BASELESS = { id: 8, name: 'Lakeland Fringe', baseName: null, baseLat: null, baseLon: null };
  const LOCATIONS = [{ id: 2, name: 'Derwentwater', regionName: 'Lake District' }];

  const setup = (props = {}) => {
    const handlers = {
      onClose: vi.fn(),
      onPickWindow: vi.fn(),
      onPickRegion: vi.fn(),
      onPickLocation: vi.fn(),
    };
    render(
      <PlanSearch
        windows={WINDOWS}
        regions={[LAKES, BASELESS]}
        locations={LOCATIONS}
        {...handlers}
        {...props}
      />,
    );
    return handlers;
  };

  const type = (value) => fireEvent.change(screen.getByTestId('plan-search-input'), {
    target: { value },
  });

  it('opens on the sunrises and sunsets list and focuses the box, so typing needs no click', () => {
    setup();
    expect(document.activeElement).toBe(screen.getByTestId('plan-search-input'));
    expect(screen.getAllByTestId('plan-search-row')).toHaveLength(2);
    expect(screen.getByTestId('plan-search-group')).toHaveTextContent('Sunrises & sunsets');
  });

  it('opens pre-filled when a seed is handed in — the strip\'s beyond line', () => {
    setup({ initialQuery: 'Lake District' });
    expect(screen.getByTestId('plan-search-input')).toHaveValue('Lake District');
    expect(screen.getByRole('option', { name: /Lake District/ })).toBeInTheDocument();
  });

  it('opens a window and closes, in one gesture', () => {
    const { onPickWindow, onClose } = setup();
    fireEvent.click(screen.getAllByTestId('plan-search-row')[1]);
    expect(onPickWindow).toHaveBeenCalledWith('2026-08-06:SUNRISE');
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('moves the origin with the region RECORD, so the caller can re-check its base', () => {
    const { onPickRegion } = setup();
    type('lake district');
    fireEvent.click(screen.getByRole('option', { name: /Lake District/ }));
    expect(onPickRegion).toHaveBeenCalledWith(LAKES);
  });

  it('⚠️ shows a baseless region and refuses to choose it', () => {
    const { onPickRegion, onClose } = setup();
    type('lakeland');
    const row = screen.getByRole('option', { name: /Lakeland Fringe/ });
    expect(row).toHaveAttribute('aria-disabled', 'true');
    expect(within(row).getByText(/no base town/i)).toBeInTheDocument();
    fireEvent.click(row);
    expect(onPickRegion).not.toHaveBeenCalled();
    // And it does not dismiss either: a click that closes the box having done nothing reads as a
    // successful choice.
    expect(onClose).not.toHaveBeenCalled();
  });

  it('refuses the region you are already planning from', () => {
    const { onPickRegion } = setup({ originId: 7 });
    type('lake district');
    const row = screen.getByRole('option', { name: /Lake District/ });
    expect(row).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(row);
    expect(onPickRegion).not.toHaveBeenCalled();
  });

  it('hands a location to the map handler', () => {
    const { onPickLocation } = setup();
    type('derwent');
    fireEvent.click(screen.getByRole('option', { name: /Derwentwater/ }));
    expect(onPickLocation).toHaveBeenCalledWith(LOCATIONS[0]);
  });

  describe('keyboard', () => {
    const arrow = (key) => fireEvent.keyDown(screen.getByTestId('plan-search-input'), { key });

    it('starts on the first row', () => {
      setup();
      expect(screen.getAllByTestId('plan-search-row')[0]).toHaveAttribute('data-active', 'true');
    });

    it('↓ moves down and ↑ moves back', () => {
      setup();
      arrow('ArrowDown');
      expect(screen.getAllByTestId('plan-search-row')[1]).toHaveAttribute('data-active', 'true');
      arrow('ArrowUp');
      expect(screen.getAllByTestId('plan-search-row')[0]).toHaveAttribute('data-active', 'true');
    });

    it('↓ wraps from the last row to the first', () => {
      setup();
      arrow('ArrowDown');
      arrow('ArrowDown');
      expect(screen.getAllByTestId('plan-search-row')[0]).toHaveAttribute('data-active', 'true');
    });

    it('enter opens the active row', () => {
      const { onPickWindow } = setup();
      arrow('ArrowDown');
      fireEvent.keyDown(screen.getByTestId('plan-search-input'), { key: 'Enter' });
      expect(onPickWindow).toHaveBeenCalledWith('2026-08-06:SUNRISE');
    });

    it('⚠️ skips a row that cannot be chosen, so enter can never land on one', () => {
      // The BASELESS region is passed FIRST, so row 0 is unchoosable and the cursor must move past
      // it. With the order the other way round `firstSelectable` answers 0 either way and the test
      // would pass against `setSelected(0)` — which is exactly what it is here to catch.
      const { onPickRegion, onClose } = setup({ regions: [BASELESS, LAKES] });
      type('lake');
      const rows = screen.getAllByTestId('plan-search-row');
      expect(rows[0]).toHaveAttribute('aria-disabled', 'true');
      expect(rows[0]).toHaveAttribute('data-active', 'false');
      expect(rows[1]).toHaveAttribute('data-active', 'true');

      fireEvent.keyDown(screen.getByTestId('plan-search-input'), { key: 'Enter' });

      expect(onPickRegion).toHaveBeenCalledWith(LAKES);
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('⚠️ marks NO active row when nothing in the result set can be chosen', () => {
      // Resting on a disabled row put `aria-selected="true"` on something Enter refused and both
      // arrows refused to leave — three controls doing nothing, which reads as a hung dialog.
      const { onPickRegion } = setup({ regions: [BASELESS] });
      type('lakeland');
      const rows = screen.getAllByTestId('plan-search-row');
      expect(rows.every((row) => row.getAttribute('data-active') === 'false')).toBe(true);
      expect(screen.getByTestId('plan-search-input')).not.toHaveAttribute('aria-activedescendant');

      fireEvent.keyDown(screen.getByTestId('plan-search-input'), { key: 'Enter' });
      expect(onPickRegion).not.toHaveBeenCalled();
    });

    it('⚠️ re-anchors the cursor when the query changes, so enter cannot open a stale row', () => {
      const { onPickWindow, onPickRegion } = setup();
      arrow('ArrowDown');   // cursor is on the SECOND window
      type('lake district'); // one region row, and the second index no longer exists
      fireEvent.keyDown(screen.getByTestId('plan-search-input'), { key: 'Enter' });
      expect(onPickWindow).not.toHaveBeenCalled();
      expect(onPickRegion).toHaveBeenCalledWith(LAKES);
    });

    it('esc closes', () => {
      const { onClose } = setup();
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('announces the active row without moving focus off the field', () => {
      setup();
      const input = screen.getByTestId('plan-search-input');
      arrow('ArrowDown');
      expect(document.activeElement).toBe(input);
      expect(input).toHaveAttribute(
        'aria-activedescendant', screen.getAllByTestId('plan-search-row')[1].id,
      );
    });
  });

  it('⚠️ shows an away window and refuses to choose it', () => {
    // A travel day has no card — `buildWindowCards` drops it — so choosing one would close the
    // dialog having silently done nothing. The strip already draws it as a non-interactive cell.
    const { onPickWindow, onClose } = setup({
      windows: [{ ...WINDOWS[0], away: true }],
    });
    const row = screen.getByRole('option', { name: /Tonight Sunset/ });
    expect(row).toHaveAttribute('aria-disabled', 'true');
    expect(row).toHaveTextContent(/not forecast/i);

    fireEvent.click(row);

    expect(onPickWindow).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });

  it('shows a switched-off region and refuses to choose it', () => {
    // The briefing carries no event summaries for a disabled region, so every window would land on
    // the away empty state — an origin that renders as a page of nothing.
    const { onPickRegion } = setup({
      regions: [{ ...LAKES, enabled: false }],
    });
    type('lake');
    const row = screen.getByRole('option', { name: /Lake District/ });
    expect(row).toHaveAttribute('aria-disabled', 'true');
    expect(row).toHaveTextContent(/switched off/i);
    fireEvent.click(row);
    expect(onPickRegion).not.toHaveBeenCalled();
  });

  it('keeps the options out of the tab order — the field owns focus', () => {
    // Tabbable options let Tab walk the list while the field still claims a different row is
    // active, and past the last one out through a non-trapping backdrop into a page the dialog has
    // told assistive tech is inert.
    setup();
    screen.getAllByTestId('plan-search-row').forEach((row) => {
      expect(row).toHaveAttribute('tabindex', '-1');
    });
  });

  it('announces the result count in a live region that is always mounted', () => {
    setup();
    expect(screen.getByTestId('plan-search-status')).toHaveTextContent('2 results');
    type('zzzz');
    expect(screen.getByTestId('plan-search-status')).toHaveTextContent('No results for zzzz');
  });

  it('says what matched nothing, naming the query', () => {
    setup();
    type('zzzz');
    expect(screen.getByTestId('plan-search-empty')).toHaveTextContent('Nothing matches “zzzz”.');
    expect(screen.getByTestId('plan-search-input')).toHaveAttribute('aria-expanded', 'false');
  });

  /**
   * M3's row anatomy: glyph, name-over-subline, best figure, action chip.
   *
   * <p>Every figure below is a value some other surface of the Plan tab already draws — the card's
   * own `bestReach`, the sheet's id-first ratings, the page's reach map and its planning area. The
   * claim these tests make is that the box READS them rather than deriving a second answer, which
   * is why the fixtures are shaped like the real producers' output and the assertions name the
   * value the other surface would show.
   */
  describe('the row anatomy', () => {
    // ⚠️ `bestReach` rides the WINDOW DESCRIPTOR, which is where `buildHeatStripCards` already folds
    // it on from the matching card (`windowFirstStrip.js`). The first cut took a second `cards`
    // prop and joined on the window key to reach the identical object; one field in hand cannot
    // miss the join.
    const RATED = WINDOWS.map((w, i) => (i === 0
      ? { ...w, bestReach: { name: 'Bamburgh', rating: 4 } }
      : { ...w, bestReach: null }));
    const SCORE_INDEX = {
      byId: new Map([
        ['2|2026-08-04|SUNSET', { rating: 3, summary: null }],
        ['2|2026-08-06|SUNRISE', { rating: 5, summary: null }],
      ]),
      byName: new Map(),
    };

    it('gives a window row the card\'s own best-in-reach star, and none where there is no pool', () => {
      // `reachById` carries a measured drive, which is what licenses the caption — see the sibling
      // below for what happens without one.
      setup({ windows: RATED, reachById: new Map([[1, { driveMinutes: 40 }]]) });
      const [tonight, thursday] = screen.getAllByTestId('plan-search-row');

      expect(within(tonight).getByText('4★')).toBeInTheDocument();
      expect(within(tonight).getByText('in reach')).toBeInTheDocument();
      // ⚠️ Silence, never a placeholder: a window whose pool has nothing rated has no best to name,
      // and `bestReach` is null exactly there.
      expect(within(thursday).queryByText(/★/)).toBeNull();
    });

    it('⚠️ keeps the star and drops "in reach" where no drive time exists to have gated it', () => {
      // §6 clause 7, and the fourth surface in this arm to have carried the same claim. The caption
      // is the only word explaining the figure, so a false one is worse here than elsewhere: with no
      // home postcode the pool was never gated and the star is simply the best rated place in scope.
      setup({ windows: RATED, reachById: new Map([[1, { driveMinutes: null }]]) });
      const [tonight] = screen.getAllByTestId('plan-search-row');

      expect(within(tonight).getByText('4★')).toBeInTheDocument();
      expect(within(tonight).queryByText('in reach')).toBeNull();
    });

    it('gives a location row its OWN best window, captioned with which window that is', () => {
      // A max over one location's six windows — it aggregates nothing across locations, which is
      // what keeps it inside P8's recorded licence rather than reopening the server-owned-verdict
      // rule. 5★ on Thursday beats 3★ tonight.
      setup({ windows: RATED, scoreIndex: SCORE_INDEX });
      type('derwent');
      const row = screen.getAllByTestId('plan-search-row')
        .find((r) => r.dataset.kind === 'location');

      expect(within(row).getByText('5★')).toBeInTheDocument();
      expect(within(row).getByText('Thursday Sunrise')).toBeInTheDocument();
    });

    it('⚠️ gives a REGION row no figure at all', () => {
      // "The best in this region" is a cross-location max the server owns; the two figures above
      // are per-user reach joins the server cannot answer. A region's best is neither, so it would
      // be a new claim rather than a relocated one.
      setup({ windows: RATED });
      type('lake dis');
      const row = screen.getAllByTestId('plan-search-row')
        .find((r) => r.dataset.kind === 'region');

      expect(within(row).queryByText(/★/)).toBeNull();
      expect(within(row).getByText('Plan from here')).toBeInTheDocument();
    });

    it('names the action each row performs, and withholds it where Enter does nothing', () => {
      setup({ windows: RATED, originId: LAKES.id });
      expect(within(screen.getAllByTestId('plan-search-row')[0]).getByText('Open'))
        .toBeInTheDocument();

      type('lake');
      const rows = screen.getAllByTestId('plan-search-row');
      const current = rows.find((r) => r.dataset.kind === 'region' && r.textContent.includes('Lake District'));
      const baseless = rows.find((r) => r.textContent.includes('Lakeland Fringe'));
      expect(within(current).getByText('Planning now')).toBeInTheDocument();
      // A region with no base town cannot become an origin, so no chip promises that it can.
      expect(within(baseless).queryByText(/Plan from here/)).toBeNull();

      type('derwent');
      // ⚠️ NOT the bundle's `4 DAYS`. The sheet this opens derives its own span and prints it
      // ("The next 3 days here" whenever today still has both its windows ahead), so a fixed 4 up
      // here would be a number nobody measured beside the same number measured.
      expect(within(screen.getAllByTestId('plan-search-row')[0]).getByText('Next few days'))
        .toBeInTheDocument();
    });

    it('builds a location sub-line from region, drive and scope — each clause omitted when unknown', () => {
      setup({
        reachById: new Map([[2, { driveMinutes: 78 }]]),
        scopeRegionNames: ['Northumberland'],
        origin: null,
      });
      type('derwent');

      // Region · drive · outside-clause. The last one fires because Derwentwater's region is not
      // in the scope handed over, and it uses the SHEET's wording rather than the bundle's vaguer
      // "outside your plan" — one vocabulary across the box and the sheet it opens.
      //
      // ⚠️ Asserted as CLAUSES, not as one string, because that is how they render — and the whole
      // point of the clause list is that the last one survives a narrow row where a joined,
      // truncating line dropped it. The tone is asserted too: it is what makes it findable.
      const sub = within(screen.getAllByTestId('plan-search-row')[0]).getByTestId('plan-search-sub');
      expect([...sub.querySelectorAll('.wf-search-cl')].map((n) => n.textContent.replace('·', '')))
        .toEqual(['Lake District', '1h 18min', 'outside your 3h area']);
      expect(sub.querySelector('.on-outside').textContent).toContain('outside your 3h area');
    });

    it('⚠️ never takes a figure from an AWAY window, however the row is rated', () => {
      // A travel day's slots are collected and never evaluated, so even a stale row for one must
      // not become a forecast for a night nobody forecast — `buildLocationSheet`'s rule, restated
      // because this walk does not go through it. Thursday is the location's best window; making
      // it away must leave the row with the 3★ from tonight, not with the 5★ behind the travel day.
      setup({
        windows: [WINDOWS[0], { ...WINDOWS[1], away: true }],
        scoreIndex: SCORE_INDEX,
      });
      type('derwent');
      const row = screen.getAllByTestId('plan-search-row')
        .find((r) => r.dataset.kind === 'location');

      expect(within(row).getByText('3★')).toBeInTheDocument();
      expect(within(row).queryByText('5★')).toBeNull();
    });

    it('⚠️ withholds a figure entirely from a spot that is not a sky subject (rule 12)', () => {
      // `buildHeatSpots` keeps a waterfall or a wood in the catalogue and nulls only its `scores`,
      // carrying `skySubject: false` to say why — but this walk reads the RAW score rows, so
      // without the gate a waterfall would print a star in the box beside a field that refuses to
      // paint one. Same fixture, same rows; only the flag differs.
      setup({
        locations: [{ ...LOCATIONS[0], skySubject: false }],
        scoreIndex: SCORE_INDEX,
      });
      type('derwent');
      const row = screen.getAllByTestId('plan-search-row')
        .find((r) => r.dataset.kind === 'location');

      expect(row).toHaveTextContent('Derwentwater');
      expect(within(row).queryByText(/★/)).toBeNull();
    });

    it('⚠️ marks nothing outside-scope when the scope is EMPTY, which is unknown rather than none', () => {
      // `buildLocationSheet`'s own rule. At home the planning area folds to `areaRegions`, which is
      // empty whenever the catalogue is — a state the box can be open across, and no evidence that
      // a place is out of the plan.
      setup({ scopeRegionNames: [] });
      type('derwent');

      expect(screen.getAllByTestId('plan-search-row')[0].textContent).not.toMatch(/outside/);
    });
  });

  describe('the matched span', () => {
    it('marks what the reader typed, inside the name as rendered', () => {
      setup();
      type('derwent');
      const mark = within(screen.getAllByTestId('plan-search-row')[0]).getByTestId('plan-search-mark');
      expect(mark.textContent).toBe('Derwent');
    });

    it('⚠️ draws NO mark on a row only the wide fold matched, rather than guessing a span', () => {
      // `stmarys` matches `St Mary's Lighthouse` through the whitespace-blind pass, whose positions
      // name no single span of the label. The row is still the answer; it is shown unmarked,
      // because a mark in the wrong place is worse than none.
      setup({ locations: [{ id: 9, name: "St Mary's Lighthouse", regionName: 'Northumberland' }] });
      type('stmarys');
      const row = screen.getAllByTestId('plan-search-row')[0];

      expect(row).toHaveTextContent("St Mary's Lighthouse");
      expect(within(row).queryByTestId('plan-search-mark')).toBeNull();
    });
  });

  describe('where the panel is drawn', () => {
    /**
     * A14: the panel is positioned exactly over the masthead's tick line and covers it, which is
     * the "search replaces the tick line" the design draws — achieved by a position rather than by
     * a second piece of state, because `Modal` is `z-50` and the masthead is 45.
     *
     * <p>⚠️ jsdom measures every box as ZERO, so the un-anchored branch is what every other test in
     * this file renders. That is the honest fallback (a zero-width panel is not a dropdown), and it
     * is asserted here so the fallback is a decision rather than an accident.
     */
    it('falls back to the centred box when nothing can be measured', () => {
      setup();
      const panel = screen.getByTestId('plan-search-panel');

      expect(panel).toHaveAttribute('data-anchored', 'false');
      expect(panel.className).not.toContain('wf-search-anchored');
      expect(panel.style.top).toBe('');
    });

    it('anchors to the tick line\'s box when there is one to measure', () => {
      const tick = document.createElement('div');
      // ⚠️ The CLASS is the production coupling, not the testid — a testid rename in a later sweep
      // would otherwise leave the box silently centred with nothing failing, because `null` is a
      // legitimate measurement here.
      tick.className = 'wf-tick';
      tick.getBoundingClientRect = () => ({ top: 96, left: 40, width: 1000, height: 30 });
      document.body.appendChild(tick);
      try {
        setup();
        const panel = screen.getByTestId('plan-search-panel');

        expect(panel.className).toContain('wf-search-anchored');
        expect(panel.style.top).toBe('96px');
        expect(panel.style.left).toBe('40px');
        expect(panel.style.width).toBe('1000px');
        // The height cap is measured from the panel's own top rather than from a viewport
        // fraction, because the anchor moves as the sticky masthead settles. 96 + 16, summed in
        // JS — see the component's note on why a two-subtraction calc cannot be asserted here.
        expect(panel.style.maxHeight).toBe('calc(100dvh - 112px)');
      } finally {
        tick.remove();
      }
    });
  });
});
