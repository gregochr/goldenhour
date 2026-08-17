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

// `countLabel` is null here because `buildRailTiles` can no longer produce a count — §6 bans
// counting our own roster, and the string this fixture used to carry ("4 regions") is the exact
// one that was removed. The component's `: tile.countLabel` fallback arm still exists and is still
// exercised, by AWAY above, which is the only tile that now uses it.
const POOR = tile({
  peak: 'poor', peakLabel: 'All poor', countLabel: null, regions: [], ratedCount: 0, confidence: null,
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

  // `.rail-scroller` carries the horizontal scroll, the hidden scrollbar, the 4px of focus-ring room
  // that its negative margin gives straight back — and, since P14, the rail's whole padding
  // including the phone gutter. It used to be a class beside an inline style; it is now the only
  // owner, so a rename costs the rail its inset as well as its overflow, at every width. jsdom
  // evaluates none of that, which is exactly why the class name itself is worth pinning.
  it('carries the scroller class that owns its overflow and its gutter', () => {
    render(<WindowFirstDayRail tiles={[tile()]} />);
    expect(screen.getByTestId('window-first-day-rail')).toHaveClass('rail-scroller');
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
      // Named absences, not an exact list. The exact-list form was the point of this assertion —
      // "the component takes no role prop at all" — but it also failed for any ADDITIVE prop, and
      // it did: `onOpenPick` and `peeksSuppressed` arrived when the pick chip became a control, and
      // a red test said nothing about role gating. The card's suite hit this first and settled the
      // idiom, including why it is two `not.toContain` rather than one negated
      // `arrayContaining`: that matcher is conjunctive, so its negation passes unless EVERY listed
      // name is present, which let `isPro` — this codebase's real gate-prop name — through alone.
      expect(Object.keys(WindowFirstDayRail.propTypes)).not.toContain('role');
      expect(Object.keys(WindowFirstDayRail.propTypes)).not.toContain('isPro');
      expect(screen.queryByText(/pro|upgrade/i)).toBeNull();
    });

    // The plan's second handoff asks for this in as many words — "region chips open a gloss, pick
    // chips open the pick's prose" — and it was the one clause still undone.
    it('is a real button naming the pick, the event and the day it belongs to', () => {
      render(<WindowFirstDayRail tiles={[tile({ dayLabel: 'Tomorrow', pick: { kind: 'best', event: 'sunset', targetType: 'SUNSET' } })]} />);

      const chip = screen.getByTestId('rail-pick-flag');
      expect(chip.tagName).toBe('BUTTON');
      // The visible words come first and contiguously, so WCAG 2.5.3's label-in-name holds; the day
      // is what separates it from the identical-looking pill on the card below.
      expect(chip).toHaveAccessibleName('BEST sunset — Tomorrow');
    });

    it('asks its owner to open that window\'s prose, naming the day and the event', () => {
      const onOpenPick = vi.fn();
      render(<WindowFirstDayRail
        tiles={[tile({ date: '2026-08-12', pick: { kind: 'also', event: 'sunrise', targetType: 'SUNRISE' } })]}
        onOpenPick={onOpenPick}
      />);

      fireEvent.click(screen.getByTestId('rail-pick-flag'));
      // Both arguments: the date alone cannot pick between a day's sunrise and its sunset, which is
      // the whole reason the chip names the event.
      expect(onOpenPick).toHaveBeenCalledWith('2026-08-12', 'SUNRISE');
    });

    it('renders no chip on an away day, so there is never one that opens nothing', () => {
      render(<WindowFirstDayRail tiles={[tile({ isAway: true, pick: null })]} />);
      expect(screen.queryByTestId('rail-pick-flag')).toBeNull();
    });

    // The tile stays inert. A button inside a button is invalid HTML and fires axe's
    // nested-interactive — a hazard this app has live elsewhere, so it is worth pinning here.
    it('leaves the tile itself uninteractive, so the chip is not nested in a control', () => {
      render(<WindowFirstDayRail tiles={[tile({ pick: { kind: 'best', event: 'sunset', targetType: 'SUNSET' } })]} />);

      const chip = screen.getByTestId('rail-pick-flag');
      const t = screen.getByTestId('rail-day');
      expect(t).not.toHaveAttribute('role');
      expect(t).not.toHaveAttribute('tabindex');
      expect(chip.closest('[role="button"]')).toBeNull();
      // The chip's only button ancestor is itself.
      expect(chip.parentElement.closest('button')).toBeNull();
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

    it('marks a picked chip with a shape mark and its own accent, and leaves a plain one unmarked', () => {
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
      // The mark keeps a pick identifiable without relying on colour alone — and Best is
      // distinguishable from Also by SHAPE (◎ vs ●), not just the underline colour, per WCAG 1.4.1.
      expect(within(chips[0]).getByText('◎')).toBeInTheDocument();
      expect(within(chips[0]).queryByText('●')).toBeNull();
      expect(within(chips[1]).getByText('●')).toBeInTheDocument();
      expect(within(chips[1]).queryByText('◎')).toBeNull();
      expect(within(chips[2]).queryByText('◎')).toBeNull();
      expect(within(chips[2]).queryByText('●')).toBeNull();
    });

    it('names the pick kind in the accessible name, restating the SHORT name so it is not lost', () => {
      // `aria-label` REPLACES name-from-contents rather than extending it, so a naive
      // `aria-label="Best bet"` would delete the region name a screen reader announces for every
      // other chip. The visible text (shortName) must lead, contiguously, for WCAG 2.5.3.
      //
      // regionName and shortName are deliberately DIFFERENT here (unlike a same-valued fixture),
      // so this test can actually catch a `region.regionName` vs `region.shortName` mix-up rather
      // than passing either way — the chip renders shortName, so the label must restate shortName.
      render(<WindowFirstDayRail tiles={[tile({
        regions: [
          { ...tile().regions[0], regionName: 'Best Region Full Name', shortName: 'Best Rgn', pickKind: 'best' },
          { ...tile().regions[0], regionName: 'Also Region Full Name', shortName: 'Also Rgn', pickKind: 'also' },
        ],
        ratedCount: 2,
      })]} />);

      const chips = screen.getAllByTestId('rail-region-chip');
      expect(screen.getByRole('button', { name: 'Best Rgn — Best bet' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Also Rgn — Also good' })).toBeInTheDocument();
      // The label's own text stands in for the mark, so a screen reader must never double-announce
      // it — pinned here, not just visually, to catch a dropped `aria-hidden` on a copy-edit
      // between this file and its BriefingSummaryStrip sibling (whose own test pins this already).
      expect(chips[0].querySelector('.rn-mark')).toHaveAttribute('aria-hidden', 'true');
      expect(chips[1].querySelector('.rn-mark')).toHaveAttribute('aria-hidden', 'true');
    });

    it('leaves a plain chip named by its content alone, with no redundant label', () => {
      render(<WindowFirstDayRail tiles={[tile()]} />);
      const chip = screen.getByTestId('rail-region-chip');
      expect(chip).not.toHaveAttribute('aria-label');
      expect(chip).toHaveAccessibleName('Northumberland & Tyneside');
    });

    it('appends the day to a picked chip\'s name when the builder flags it ambiguous', () => {
      // `buildRailTiles` sets `pickDayLabel` only when the same picked region repeats across
      // rendered days — the case where "Best bet" alone would sound identical on every one of
      // them. The component just renders what the builder decided; this pins that it does.
      render(<WindowFirstDayRail tiles={[tile({
        dayLabel: 'Tomorrow',
        regions: [{ ...tile().regions[0], pickKind: 'best', pickDayLabel: 'Tomorrow' }],
      })]} />);

      expect(screen.getByRole('button', { name: 'Northumberland & Tyneside — Best bet, Tomorrow' }))
        .toBeInTheDocument();
    });

    it('leaves the day out of a picked chip\'s name when the builder found no ambiguity', () => {
      render(<WindowFirstDayRail tiles={[tile({
        regions: [{ ...tile().regions[0], pickKind: 'also', pickDayLabel: null }],
      })]} />);

      expect(screen.getByRole('button', { name: 'Northumberland & Tyneside — Also good' }))
        .toBeInTheDocument();
    });

    it('renders no chips and no count when no region is rated', () => {
      // The verdict line above already says "All poor". A count here would describe the roster,
      // not tonight — §6 bans it, and `buildRailTiles` no longer emits one.
      render(<WindowFirstDayRail tiles={[POOR]} />);
      expect(screen.queryByTestId('rail-region-chip')).toBeNull();
      expect(screen.getByTestId('rail-day-regions')).toHaveTextContent('');
      expect(screen.getByTestId('rail-day-regions').textContent).not.toMatch(/\d+\s+regions?/);
    });

    it('still renders the countLabel fallback arm for an away tile', () => {
      // Why POOR's null above does not make the fallback dead code.
      render(<WindowFirstDayRail tiles={[AWAY]} />);
      expect(screen.getByTestId('rail-day-regions')).toHaveTextContent('Not forecast');
    });
  });

  describe('verdict colours (§6 clause 5 — one family, and away is not a verdict)', () => {
    // Inline `style` values, so jsdom CAN read these back even though it evaluates no stylesheet.
    // Without them both token fixes revert green: the rail suite had no colour assertion at all.
    it('takes GO from the verdict family, not the badge family', () => {
      // --color-badge-go is scoped to ~10px type on a tint of its own hue; this line is on the
      // untinted panel. Mixing families made this the only verdict expression in the tree to do so.
      render(<WindowFirstDayRail tiles={[tile()]} />);
      expect(screen.getByTestId('rail-day-verdict').style.color).toBe('var(--color-verdict-go)');
    });

    it('takes MARGINAL from the same family', () => {
      render(<WindowFirstDayRail tiles={[tile({ peak: 'maybe', peakLabel: 'Maybe · sunset' })]} />);
      expect(screen.getByTestId('rail-day-verdict').style.color).toBe('var(--color-verdict-marginal)');
    });

    it('paints an away tile in the tide channel, never a verdict colour', () => {
      // A travel day has no verdict (ratedCount 0, confidence null), so it must not spend the one
      // colour family index.css calls meaning-carrying — least of all the MAYBE hue, which would
      // put "Maybe · sunset" and "✈ Away" in one colour on a six-day scanning surface.
      render(<WindowFirstDayRail tiles={[AWAY]} />);
      const colour = screen.getByTestId('rail-day-verdict').style.color;
      expect(colour).toBe('var(--color-tide)');
      expect(colour).not.toContain('verdict');
    });
  });

  describe('the gloss panel', () => {
    // The rail's gloss is `z-index: 60` and `Modal` is `z-50`, so a hover on the way to a dialog
    // painted a tooltip OVER it, with no focus trap to stop the pointer reaching either. The rail
    // was the last surface in the arm with no such guard. Both halves, because the positive is what
    // stops this passing for a rail whose gloss never opens at all.
    it('stays shut while a dialog is over the pane, which it would otherwise paint over', () => {
      render(<WindowFirstDayRail tiles={[tile()]} peeksSuppressed />);
      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));
      expect(screen.queryByTestId('popover-host')).toBeNull();
    });

    it('opens normally when nothing is over the pane', () => {
      render(<WindowFirstDayRail tiles={[tile()]} peeksSuppressed={false} />);
      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));
      expect(screen.getByTestId('popover-host')).toBeInTheDocument();
    });

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
