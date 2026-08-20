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

  it('opens on the windows list and focuses the box, so typing needs no click', () => {
    setup();
    expect(document.activeElement).toBe(screen.getByTestId('plan-search-input'));
    expect(screen.getAllByTestId('plan-search-row')).toHaveLength(2);
    expect(screen.getByTestId('plan-search-group')).toHaveTextContent('Windows');
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
});
