import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstDayRail, { DATE_ROW_MIN_HEIGHT_PX } from '../components/WindowFirstDayRail.jsx';

/** A rated tile — the common case, and the one every other fixture varies from. */
function tile(overrides = {}) {
  return {
    date: '2026-08-04',
    isToday: false,
    targetType: 'SUNSET',
    dow: 'Tue',
    dayNum: '4',
    dayLabel: 'Today',
    sunriseTime: '05:15',
    sunsetTime: '21:11',
    peak: 'go',
    peakLabel: 'Worth it · sunset',
    countLabel: null,
    pick: null,
    regions: [{
      regionName: 'Northumberland & Tyneside',
      shortName: 'Northumberland & Tyneside',
      targetType: 'SUNSET',
      verdictLabel: 'Worth it sunset',
      wx: '🌤️18°C 9mph',
      summary: 'clear at 3 of 7 locations',
      glossHeadline: 'Breaking clear',
      glossDetail: 'Low cloud clears from the west on cue.',
      glossKind: null,
      pickKind: null,
    }],
    ratedCount: 1,
    isAway: false,
    confidence: 'high',
    ...overrides,
  };
}

const AWAY = tile({
  isAway: true, peak: 'away', peakLabel: '✈ Away', countLabel: 'Not forecast',
  regions: [], ratedCount: 0, confidence: null, targetType: null,
});

const POOR = tile({
  peak: 'poor', peakLabel: 'All poor', countLabel: '4 regions', regions: [], ratedCount: 0, confidence: null,
});

describe('WindowFirstDayRail', () => {
  it('renders nothing when there are no days', () => {
    const { container } = render(<WindowFirstDayRail tiles={[]} />);
    expect(container.innerHTML).toBe('');
  });

  it('renders one tile per day, carrying the date, both sun times and the verdict', () => {
    render(<WindowFirstDayRail tiles={[tile()]} />);

    const tiles = screen.getAllByTestId('rail-day');
    expect(tiles).toHaveLength(1);
    expect(tiles[0]).toHaveTextContent('Tue');
    expect(tiles[0]).toHaveTextContent('Today');
    expect(screen.getByTestId('rail-day-sun')).toHaveTextContent('↑ 05:15 ↓ 21:11');
    expect(screen.getByTestId('rail-day-verdict')).toHaveTextContent('Worth it · sunset');
  });

  it('shows only the event a day still has left', () => {
    render(<WindowFirstDayRail tiles={[tile({ sunriseTime: '' })]} />);
    const sun = screen.getByTestId('rail-day-sun');
    expect(sun).toHaveTextContent('↓ 21:11');
    expect(sun.textContent).not.toContain('↑');
  });

  it('marks today with its own treatment, and leaves the other days alone', () => {
    render(<WindowFirstDayRail tiles={[
      tile({ isToday: true }), tile({ date: '2026-08-05', isToday: false }),
    ]} />);

    const tiles = screen.getAllByTestId('rail-day');
    expect(tiles[0]).toHaveAttribute('data-today', 'true');
    expect(tiles[1]).not.toHaveAttribute('data-today');
  });

  describe('the show-on-map action', () => {
    it('is a real button naming the day it opens, carrying that day and its best event', () => {
      const onTileClick = vi.fn();
      render(<WindowFirstDayRail tiles={[tile()]} onTileClick={onTileClick} />);

      const action = screen.getByRole('button', { name: 'Show Today on the map' });
      fireEvent.click(action);
      expect(onTileClick).toHaveBeenCalledWith('2026-08-04', 'SUNSET');
    });

    it('puts the widget role on the action, never on the tile around it', () => {
      // Every clickable tile contains at least one region chip, itself a widget — so a tile that
      // was also a button nested one inside the other unconditionally, and announced its whole
      // subtree as a single ~20-word name before reading the chips again on Tab.
      render(<WindowFirstDayRail tiles={[tile()]} />);

      const t = screen.getByTestId('rail-day');
      expect(t).not.toHaveAttribute('role');
      expect(t).not.toHaveAttribute('tabindex');
      // The chips are siblings of the action, not descendants of it.
      const action = screen.getByTestId('rail-day-show-on-map');
      expect(action).not.toContainElement(screen.getByTestId('rail-region-chip'));
    });

    it('leaves the tile around the action inert, so nothing fires twice', () => {
      // The tile carries no click of its own. It cannot: with the region chips inside it, making
      // the tile a <button> would nest interactive content, and giving it role="button" + key
      // handlers reconstructs by hand what a native button already does — the trade CloseToHome
      // documents. The action is the only widget, and clicking the tile's chrome does nothing.
      const onTileClick = vi.fn();
      render(<WindowFirstDayRail tiles={[tile()]} onTileClick={onTileClick} />);

      fireEvent.click(screen.getByTestId('rail-day-sun'));
      expect(onTileClick).not.toHaveBeenCalled();

      fireEvent.click(screen.getByTestId('rail-day-show-on-map'));
      expect(onTileClick).toHaveBeenCalledTimes(1);
    });

    it('activates from the keyboard as a native button, with no hand-written key handling', () => {
      const onTileClick = vi.fn();
      render(<WindowFirstDayRail tiles={[tile()]} onTileClick={onTileClick} />);

      const action = screen.getByRole('button', { name: 'Show Today on the map' });
      action.focus();
      expect(action).toHaveFocus();
      fireEvent.click(action); // what Enter/Space synthesise on a real button
      expect(onTileClick).toHaveBeenCalledWith('2026-08-04', 'SUNSET');
    });

    it('leaves an all-poor tile inert, with no affordance to click', () => {
      // The map would open on a day with nothing to show. Offering the action and doing nothing
      // useful is worse than not offering it.
      const onTileClick = vi.fn();
      render(<WindowFirstDayRail tiles={[POOR]} onTileClick={onTileClick} />);

      const t = screen.getByTestId('rail-day');
      expect(screen.queryByTestId('rail-day-show-on-map')).toBeNull();
      expect(t.textContent).not.toContain('Show on map');
      fireEvent.click(t);
      expect(onTileClick).not.toHaveBeenCalled();
    });

    it('leaves an away tile inert even though the day is identified', () => {
      const onTileClick = vi.fn();
      render(<WindowFirstDayRail tiles={[AWAY]} onTileClick={onTileClick} />);

      const t = screen.getByTestId('rail-day');
      expect(t).toHaveAttribute('data-away', 'true');
      expect(screen.queryByTestId('rail-day-show-on-map')).toBeNull();
      expect(screen.getByTestId('rail-day-verdict')).toHaveTextContent('✈ Away');
      expect(t).toHaveTextContent('Not forecast');
      fireEvent.click(t);
      expect(onTileClick).not.toHaveBeenCalled();
    });

    it('marks an away day exactly once', () => {
      // It was marked twice: the sun line replaced its times with ✈ Away while the verdict line
      // already said it. One tile, one flight marker.
      render(<WindowFirstDayRail tiles={[AWAY]} />);
      expect(screen.getByTestId('rail-day').textContent.match(/✈ Away/g)).toHaveLength(1);
    });

    it('keeps an away day\'s sun times, which are almanac and still true', () => {
      // Sunrise and sunset happen whether or not a forecast was run, and someone away on business
      // can still shoot from where they are. The flight marker belongs on the verdict line, which
      // is the line that genuinely has nothing to say.
      render(<WindowFirstDayRail tiles={[AWAY]} />);
      expect(screen.getByTestId('rail-day-sun')).toHaveTextContent('↑ 05:15 ↓ 21:11');
    });
  });

  describe('the pick flag', () => {
    it('names the kind AND the event, on two lines', () => {
      // A pick is a window; a tile is a day. Without the event the reader has to open a card to
      // find out which half of the day was recommended.
      render(<WindowFirstDayRail tiles={[tile({ pick: { kind: 'best', event: 'sunrise', targetType: 'SUNRISE' } })]} />);

      const flag = screen.getByTestId('rail-pick-flag');
      expect(flag).toHaveAttribute('data-pick', 'best');
      expect(flag).toHaveTextContent('BEST');
      expect(flag).toHaveTextContent('sunrise');
    });

    it('distinguishes the runner-up from the Best bet', () => {
      render(<WindowFirstDayRail tiles={[
        tile({ pick: { kind: 'best', event: 'sunset', targetType: 'SUNSET' } }),
        tile({ date: '2026-08-05', pick: { kind: 'also', event: 'sunrise', targetType: 'SUNRISE' } }),
      ]} />);

      const flags = screen.getAllByTestId('rail-pick-flag');
      expect(flags.map((f) => f.dataset.pick)).toEqual(['best', 'also']);
      expect(flags[1]).toHaveTextContent('ALSO');
    });

    it('never draws a flag on an away day', () => {
      render(<WindowFirstDayRail tiles={[
        { ...AWAY, pick: { kind: 'best', event: 'sunset', targetType: 'SUNSET' } },
      ]} />);
      expect(screen.queryByTestId('rail-pick-flag')).toBeNull();
    });

    it('renders in full with no role gate, because the pick is not premium content', () => {
      // Plan §7 poses this as grey-vs-omit on the premise that the prose is PRO-gated. That is
      // true of BestBet.headline and false of BriefingWindow.Pick, which is region gloss: no role
      // check touches it on the /api/briefing path and LITE reads it on the v1 tab today. The
      // component therefore takes no role prop at all — the shape of the decision, not just its
      // effect. If a gate is ever added, this fails and the argument has to be made again.
      render(<WindowFirstDayRail tiles={[tile({ pick: { kind: 'best', event: 'sunset', targetType: 'SUNSET' } })]} />);

      expect(screen.getByTestId('rail-pick-flag')).toBeInTheDocument();
      expect(Object.keys(WindowFirstDayRail.propTypes)).toEqual(['tiles', 'onTileClick', 'onRegionClick']);
      expect(screen.queryByText(/pro|upgrade/i)).toBeNull();
    });

    it('reserves the flag\'s height on every tile so the rail\'s lines stay level', () => {
      // A two-line chip is taller than the date row, so on the flagged tiles it pushed the sun and
      // verdict lines 12px down and the rail read ragged — measured in a browser, invisible to
      // jsdom, which has no layout. What CAN be asserted here is that the reservation is applied
      // unconditionally; the pixel result is checked in the browser.
      render(<WindowFirstDayRail tiles={[
        tile({ pick: { kind: 'best', event: 'sunset', targetType: 'SUNSET' } }),
        tile({ date: '2026-08-05' }),
      ]} />);

      const rows = screen.getAllByTestId('rail-day-dateline');
      expect(rows).toHaveLength(2);
      rows.forEach((r) => expect(r).toHaveStyle({ minHeight: `${DATE_ROW_MIN_HEIGHT_PX}px` }));
    });

    it('derives that reserved height from the chip rather than hard-coding it', () => {
      // 2 lines × 9.5px × 1.25 leading + 2 × 2px padding. Pinned as arithmetic so a change to the
      // chip's type that forgets the row cannot silently bring the drift back.
      expect(DATE_ROW_MIN_HEIGHT_PX).toBeCloseTo(2 * 9.5 * 1.25 + 4, 5);
    });
  });

  describe('region chips', () => {
    it('renders one operable chip per rated region, labelled with that region', () => {
      // Queried by ROLE AND NAME rather than by test-id, because the name IS the payload. With a
      // test-id lookup the label could be deleted outright and this suite stayed green: the whole
      // rail would render bare ◎ glyphs and stray commas where the region names belong.
      const onRegionClick = vi.fn();
      render(<WindowFirstDayRail tiles={[tile()]} onRegionClick={onRegionClick} />);

      const chip = screen.getByRole('button', { name: 'Northumberland & Tyneside' });
      expect(chip).toHaveAttribute('tabindex', '0');
      fireEvent.click(chip);
      expect(onRegionClick).toHaveBeenCalledWith('Northumberland & Tyneside', '2026-08-04', 'SUNSET');
    });

    it('shows the abbreviated name, not the full one, on the chip itself', () => {
      // shortName is what fits a 150px tile on a phone; regionName is what every callback carries.
      // Asserting only the callback let the rendered label rot independently.
      render(<WindowFirstDayRail tiles={[tile({
        regions: [{ ...tile().regions[0], regionName: 'The North Yorkshire Coast', shortName: 'N. Yorks Coast' }],
      })]} />);

      expect(screen.getByTestId('rail-region-chip')).toHaveTextContent('N. Yorks Coast');
    });

    it('opens its own region rather than the whole day when clicked', () => {
      // The chip sits inside a clickable tile. Without stopPropagation the map would open on the
      // day, silently discarding the region the user actually pointed at.
      const onTileClick = vi.fn();
      const onRegionClick = vi.fn();
      render(<WindowFirstDayRail tiles={[tile()]} onTileClick={onTileClick} onRegionClick={onRegionClick} />);

      fireEvent.click(screen.getByTestId('rail-region-chip'));
      expect(onRegionClick).toHaveBeenCalledTimes(1);
      expect(onTileClick).not.toHaveBeenCalled();
    });

    it('operates from the keyboard without also firing the tile', () => {
      const onTileClick = vi.fn();
      const onRegionClick = vi.fn();
      render(<WindowFirstDayRail tiles={[tile()]} onTileClick={onTileClick} onRegionClick={onRegionClick} />);

      fireEvent.keyDown(screen.getByTestId('rail-region-chip'), { key: 'Enter' });
      expect(onRegionClick).toHaveBeenCalledTimes(1);
      expect(onTileClick).not.toHaveBeenCalled();
    });

    it('marks a picked chip with ◎ and its own accent, and leaves a plain one unmarked', () => {
      render(<WindowFirstDayRail tiles={[tile({
        regions: [
          { ...tile().regions[0], regionName: 'Best', shortName: 'Best', pickKind: 'best' },
          { ...tile().regions[0], regionName: 'Also', shortName: 'Also', pickKind: 'also' },
          { ...tile().regions[0], regionName: 'Plain', shortName: 'Plain', pickKind: null },
        ],
        ratedCount: 3,
      })]} />);

      const chips = screen.getAllByTestId('rail-region-chip');
      expect(chips.map((c) => c.dataset.pick)).toEqual(['best', 'also', undefined]);
      // The mark keeps a pick identifiable without relying on colour alone.
      expect(within(chips[0]).getByText('◎')).toBeInTheDocument();
      expect(within(chips[2]).queryByText('◎')).toBeNull();
    });

    it('falls back to the region count when no region is rated', () => {
      render(<WindowFirstDayRail tiles={[POOR]} />);
      expect(screen.queryByTestId('rail-region-chip')).toBeNull();
      expect(screen.getByTestId('rail-day-regions')).toHaveTextContent('4 regions');
    });
  });

  describe('the gloss panel', () => {
    it('opens on hover, portalled out of the rail, and closes on leave', () => {
      render(<WindowFirstDayRail tiles={[tile()]} />);
      const chip = screen.getByTestId('rail-region-chip');

      fireEvent.mouseEnter(chip);
      const panel = screen.getByTestId('popover-host');
      expect(panel).toHaveAttribute('role', 'tooltip');
      // Body-parented: no transform or overflow ancestor in the rail can clip it, and the rail is
      // a horizontal scroller on phone.
      expect(panel.parentElement).toBe(document.body);

      fireEvent.mouseLeave(chip);
      expect(screen.queryByTestId('popover-host')).toBeNull();
    });

    it('opens on focus too, so it is not pointer-only', () => {
      render(<WindowFirstDayRail tiles={[tile()]} />);
      fireEvent.focus(screen.getByTestId('rail-region-chip'));
      expect(screen.getByTestId('popover-host')).toBeInTheDocument();
    });

    it('prefers the verbose gloss detail over the headline and the terse summary', () => {
      render(<WindowFirstDayRail tiles={[tile()]} />);
      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));

      const panel = screen.getByTestId('popover-host');
      expect(panel).toHaveTextContent('Low cloud clears from the west on cue.');
      expect(panel).toHaveTextContent('Worth it sunset · 🌤️18°C 9mph');
    });

    it('falls back through headline to summary when the serve path nulled the gloss', () => {
      const noDetail = tile({ regions: [{ ...tile().regions[0], glossDetail: '' }] });
      const noGloss = tile({ regions: [{ ...tile().regions[0], glossDetail: '', glossHeadline: '' }] });

      const { rerender } = render(<WindowFirstDayRail tiles={[noDetail]} />);
      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));
      expect(screen.getByTestId('popover-host')).toHaveTextContent('Breaking clear');

      fireEvent.mouseLeave(screen.getByTestId('rail-region-chip'));
      rerender(<WindowFirstDayRail tiles={[noGloss]} />);
      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));
      expect(screen.getByTestId('popover-host')).toHaveTextContent('clear at 3 of 7 locations');
    });

    it('says so plainly when a region carries no prose at all', () => {
      const bare = tile({ regions: [{ ...tile().regions[0], glossDetail: '', glossHeadline: '', summary: '' }] });
      render(<WindowFirstDayRail tiles={[bare]} />);
      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));
      expect(screen.getByTestId('popover-host')).toHaveTextContent('No detail available.');
    });

    it('closes on Escape, which the panel it replaces could not do', () => {
      // The shipped v1 gloss has no keyboard dismissal: a keyboard user who opens one by focusing
      // a chip can only close it by moving focus again. The shared host is why this arm can.
      render(<WindowFirstDayRail tiles={[tile()]} />);
      fireEvent.focus(screen.getByTestId('rail-region-chip'));
      expect(screen.getByTestId('popover-host')).toBeInTheDocument();

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('popover-host')).toBeNull();
    });

    it('does not let a chip the pointer has left cancel the chip it has arrived on', () => {
      // A sweep along a row fires the outgoing chip's leave AFTER the incoming chip's enter, so an
      // unkeyed close blanks the panel exactly when it is wanted.
      render(<WindowFirstDayRail tiles={[tile({
        regions: [
          { ...tile().regions[0], regionName: 'A', shortName: 'A', glossDetail: 'detail A' },
          { ...tile().regions[0], regionName: 'B', shortName: 'B', glossDetail: 'detail B' },
        ],
        ratedCount: 2,
      })]} />);
      const [a, b] = screen.getAllByTestId('rail-region-chip');

      fireEvent.mouseEnter(a);
      fireEvent.mouseEnter(b);
      fireEvent.mouseLeave(a);

      expect(screen.getByTestId('popover-host')).toHaveTextContent('detail B');
    });
  });

  it('never renders a confidence marker, wherever the tile\'s confidence sits', () => {
    // Plan §2.7: the window card's verdict badge is the SINGLE render site for confidence. The v1
    // tile this is copied from carries a ProvisionalMark, and deleting it is a named requirement
    // of this phase — marking the same fact twice breaks "one uniform channel" as surely as
    // omitting it. Asserted across every tier so the conditional cannot come back for one of them.
    ['high', 'medium', 'low', null].forEach((confidence) => {
      const { unmount } = render(<WindowFirstDayRail tiles={[tile({ confidence })]} />);
      expect(screen.queryByTestId('provisional-mark')).toBeNull();
      unmount();
    });
  });
});
