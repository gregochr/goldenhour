import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstWindowCard from '../components/WindowFirstWindowCard.jsx';
import { buildWindowRows } from '../utils/windowFirstRows.js';

const TODAY = '2026-08-04';

function card(overrides = {}) {
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    lead: false,
    kicker: null,
    when: 'Tomorrow sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 4,
    confidence: 'high',
    badges: [],
    rows: [],
    pick: null,
    spots: [],
    ...overrides,
  };
}

/** A spot as `buildWindowSpots` emits one. */
function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Castle',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 66,
    distanceMiles: 47,
    ...overrides,
  };
}

/** A tide attribute row, built through the real deriver so the shape can never drift from it. */
const tideRow = () => buildWindowRows({
  tide: {
    locationName: 'Whitby',
    state: 'MID',
    direction: 'FALLING',
    nearestType: 'HW',
    nearestTime: '19:28',
    nearestOffset: '1h43 before sunset',
    range: '4.9 m',
    rangeAnomaly: '1.2 m above an average tide',
    seas: '0.3 m · smooth',
    curve: [0, 0.5, 1],
    windowPosition: 0.5,
    windowLevel: 0.5,
  },
  badges: [],
}).rows[0];

const renderCard = (overrides = {}, props = {}) => render(
  <WindowFirstWindowCard card={card(overrides)} todayStr={TODAY} {...props} />,
);

/** The alpha of an `rgba()` string, so a test can compare tiers without pinning a serialisation. */
const alphaOf = (rgba) => parseFloat(/rgba\([^)]*,\s*([\d.]+)\s*\)/.exec(rgba)[1]);

describe('WindowFirstWindowCard', () => {
  it('states when the window is, at what time, and how good the best of it is', () => {
    renderCard();
    expect(screen.getByTestId('window-card-when')).toHaveTextContent('Tomorrow sunset');
    expect(screen.getByTestId('window-card-time')).toHaveTextContent('21:11');
    expect(screen.getByTestId('window-card-best')).toHaveTextContent('best spot 4★');
  });

  it('labels the best spot as a spot, and keeps it out of the verdict channel', () => {
    // Plan §7.5. The badge is the top region's AVERAGE and this star is one location's score, so
    // the two answer different questions and `Poor · best spot 4★` is the shape that proves it —
    // the exact header the 2026-08-16 screenshot got wrong by printing "Worth it · best 4★" over a
    // grid of Poor cells. Three things have to hold at once for that to read as two facts rather
    // than a contradiction, so all three are asserted here: the star says which thing it measures,
    // it borrows none of the four verdict words, and it is not inside the badge row where the
    // verdict lives.
    renderCard({ verdict: 'STAND_DOWN', verdictLabel: 'Poor', bestRating: 4 });

    const best = screen.getByTestId('window-card-best');
    expect(best).toHaveTextContent('best spot 4★');
    expect(best.textContent).not.toMatch(/worth it|maybe|poor|awaiting/i);
    expect(screen.getByTestId('window-card-badges').contains(best)).toBe(false);
    expect(screen.getByTestId('window-card-verdict')).toHaveTextContent('Poor');
  });

  it('omits the star entirely when nothing in the window is rated', () => {
    // Not "best —★": a null rating means nothing here is rated, which is a different statement
    // from a low one, and a placeholder would assert the card had looked and found little.
    renderCard({ bestRating: null });
    expect(screen.queryByTestId('window-card-best')).toBeNull();
  });

  it('claims no count of anything, because nothing it could count is on screen', () => {
    // The design's meta reads "best 4.0★ · 23 within reach". Reach is P8 and the spots it counts
    // are P6, so any count here would describe a set that was never filtered and is not rendered —
    // which §6 bans twice over. The count arrives when the thing it counts does.
    //
    // The pattern asks for a COUNT of spots, not for the word: `best spot 4★` (plan §2) names one
    // location and counts nothing, so a bare /spots?/ here would convict the labelled star this
    // card is required to carry. A leading number is what makes it a count.
    renderCard({ badges: [{ type: 'NLC', label: '✦ NLC' }] });
    expect(screen.getByTestId('window-card-head').textContent)
      .not.toMatch(/within reach|\d+\s+spots?\b/i);
  });

  describe('the expander', () => {
    it('sits last in the header, after the badges, where P5 reserved its slot', () => {
      // P5 put the slot there so P9 would insert one element and reflow nothing. If it drifts, the
      // badge group stops being the last thing the eye reaches on a collapsed row.
      renderCard({ badges: [{ type: 'NLC', label: '✦ NLC' }] });

      const head = screen.getByTestId('window-card-head');
      const order = [...head.children].map((el) => el.dataset.testid);
      expect(order[order.length - 1]).toBe('window-card-expander');
      expect(order[order.length - 2]).toBe('window-card-badges');
    });

    it('announces its state and names the window it controls', () => {
      // `aria-expanded` carries the state; the window is in the accessible name because six
      // identical "Open" buttons in a list are otherwise indistinguishable to a screen reader.
      // The visible word leads the label, so WCAG 2.5.3's label-in-name holds.
      renderCard({}, { open: false });

      const button = screen.getByTestId('window-card-expander');
      expect(button).toHaveAttribute('aria-expanded', 'false');
      expect(button).toHaveAccessibleName('Open Tomorrow sunset');
      expect(button).toHaveTextContent('Open');
    });

    it('reads Collapse when the region is open', () => {
      renderCard({}, { open: true });
      const button = screen.getByTestId('window-card-expander');
      expect(button).toHaveAttribute('aria-expanded', 'true');
      expect(button).toHaveAccessibleName('Collapse Tomorrow sunset');
    });

    it('points at a region that exists even while collapsed', () => {
      // `aria-controls` is an IDREF: unmounting the whole container on collapse would leave five of
      // six cards in the default state pointing at nothing.
      renderCard({ rows: [tideRow()], spots: [spot()] }, { open: false });

      const target = screen.getByTestId('window-card-expander').getAttribute('aria-controls');
      expect(document.getElementById(target)).toBe(screen.getByTestId('window-card-body'));
    });

    it('takes the rows, the strip and its footer with it, not just the strip', () => {
      // Plan §5a settled the split on measured heights: the rows cost 207px above the header and a
      // collapsed card that kept them would give back only 150 of it. Asserted by absence of each,
      // because a region that dropped only the strip would still look like it worked.
      renderCard({ rows: [tideRow()], spots: [spot()] }, { open: false });

      expect(screen.queryByTestId('window-card-rows')).toBeNull();
      expect(screen.queryByTestId('window-spot-strip')).toBeNull();
      expect(screen.queryByTestId('window-spot-foot')).toBeNull();
    });

    it('keeps the whole header on a collapsed card, which is what makes it scannable', () => {
      // A collapsed card is a row you read, not a stub. The verdict, the pick, the topic badges,
      // the star and the reach count are the reason it is worth collapsing rather than hiding.
      renderCard({
        bestRating: 4,
        withinReachCount: 3,
        badges: [{ type: 'NLC', label: '✦ NLC' }],
        pick: { kind: 'best', regionName: 'N&T', headline: 'Breaking clear' },
      }, { open: false });

      expect(screen.getByTestId('window-card-verdict')).toHaveTextContent('Worth it');
      expect(screen.getByTestId('window-card-pick')).toBeInTheDocument();
      expect(screen.getByTestId('window-card-badge')).toHaveTextContent('✦ NLC');
      expect(screen.getByTestId('window-card-best')).toHaveTextContent('best spot 4★');
      expect(screen.getByTestId('window-card-within-reach')).toHaveTextContent('3 within reach');
    });

    it('tightens the header when collapsed, so the row reads as one line rather than a card', () => {
      // The mock's own two changes (`.win.collapsed .wh` :157, `.wh .when` :160). Pinned because
      // they are the only visual difference between the two states apart from what is missing.
      //
      // The two halves are asserted differently now, and deliberately. `fontSize` is still computed
      // in JS and written inline, so its VALUE is assertable here. The header's padding moved to
      // `.wf-wh[data-open]` at P14, because the phone rule changes it and a media query cannot
      // reach an inline style — so what this file can honestly pin is the hook the stylesheet keys
      // on, not the pixels. `vite.config.js` sets `css: false`; jsdom parses no stylesheet and
      // resolves no media query, so a `toHaveStyle({ padding })` here would now read the empty
      // string and pass against anything. The padding VALUES are a browser measurement (P14 §5i),
      // and deleting the `[data-open='true']` rule is a change no unit test in this suite can see.
      renderCard({}, { open: false });
      expect(screen.getByTestId('window-card-head')).toHaveAttribute('data-open', 'false');
      expect(screen.getByTestId('window-card-when')).toHaveStyle({ fontSize: '13.5px' });

      renderCard({}, { open: true });
      expect(screen.getAllByTestId('window-card-head')[1]).toHaveAttribute('data-open', 'true');
      expect(screen.getAllByTestId('window-card-when')[1]).toHaveStyle({ fontSize: '15.5px' });
    });

    it('asks its owner to flip, and holds no state of its own', () => {
      // The default is a fact about the LIST ("the first card is open"), which a card cannot see.
      const onToggle = vi.fn();
      renderCard({}, { open: false, onToggle });

      fireEvent.click(screen.getByTestId('window-card-expander'));
      expect(onToggle).toHaveBeenCalledTimes(1);
      // Still collapsed: the card re-renders only when the owner hands back a new `open`.
      expect(screen.getByTestId('window-card-expander')).toHaveAttribute('aria-expanded', 'false');
    });
  });

  describe('the spot strip and its footer', () => {
    it('renders the strip and a footer stating the order and the count', () => {
      renderCard({ spots: [spot(), spot({ key: '2', locationId: 2, locationName: 'Simonside', rating: 3, driveMinutes: 19, distanceMiles: 10 })] });
      expect(screen.getByTestId('window-spot-strip')).toBeInTheDocument();
      expect(screen.getByTestId('window-spot-order')).toHaveTextContent('Ranked by rating, then drive time.');
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent('2 spots');
    });

    it('renders neither strip nor footer when the window has no spots', () => {
      // Not an empty bar counting nothing: P5 drew no footer at all rather than an empty one, and
      // the same rule holds one phase on. Reachable on real data — the local briefing's regions
      // carry no slots at all, and a payload cached before slots existed would look the same.
      renderCard({ spots: [] });
      expect(screen.queryByTestId('window-spot-strip')).toBeNull();
      expect(screen.queryByTestId('window-spot-foot')).toBeNull();
    });

    it('offers no "See all", because the sheet it would open does not exist yet', () => {
      // The design's third footer element opens P11's drill-down. Shipping it inert is the demo
      // control §6 bans — the same reason P5 shipped no expander.
      renderCard({ spots: [spot()] });
      expect(screen.getByTestId('window-card').textContent).not.toMatch(/see all/i);
    });

    it('claims nothing is "loaded", and claims no reach the lens did not measure', () => {
      // The design reads "7 of 18 loaded". "Loaded" never ships — nothing here is lazily fetched.
      // And with no `withinReachCount` the header stays silent rather than restating the footer's
      // own count one element higher: exactly one count when nothing was gated.
      renderCard({ spots: [spot()], reachTotal: 1 });
      expect(screen.getByTestId('window-card').textContent).not.toMatch(/loaded|within reach/i);
      expect(screen.queryByTestId('window-card-within-reach')).toBeNull();
    });

    it('hands the strip the set the lens chose from, so its count can say "N of M"', () => {
      renderCard({ spots: [spot()], reachTotal: 6 });
      expect(screen.getByTestId('window-spot-count')).toHaveTextContent('1 of 6');
    });

    it('names the strip\'s arrows after this window, not after "the strip"', () => {
      // The card is the ONLY site that supplies windowLabel, and nothing else asserted it — so
      // dropping the prop yielded "Scroll undefined spots left" with the suite green. P9 has to
      // move this JSX inside a collapsible region, which is exactly how a prop gets lost.
      //
      // The arrows only exist while the strip overflows, and jsdom reports every element as 0×0,
      // so the metrics have to be faked on the prototype before render — see WindowSpotStrip's own
      // suite for the full note. Restored in `finally`, as CloseToHome.test.jsx does for its
      // ResizeObserver.
      const overflow = { scrollWidth: 1200, clientWidth: 400, scrollLeft: 0 };
      for (const key of Object.keys(overflow)) {
        Object.defineProperty(HTMLElement.prototype, key, {
          configurable: true,
          get() { return this.dataset.testid === 'window-spot-scroller' ? overflow[key] : 0; },
        });
      }
      try {
        renderCard({ when: 'Tomorrow sunrise', spots: [spot()] });
        expect(screen.getByTestId('window-spot-prev'))
          .toHaveAccessibleName('Scroll Tomorrow sunrise spots left');
      } finally {
        for (const key of Object.keys(overflow)) delete HTMLElement.prototype[key];
      }
    });

    it('tells the strip when it is the lead card, so the fades can match the gold wash', () => {
      // Same one-site wiring problem: the lead assertions elsewhere all target the card root, and
      // the `the lead card` block renders with no spots at all, so the strip is not even mounted.
      renderCard({ lead: true, spots: [spot()] });
      expect(screen.getByTestId('window-spot-strip')).toHaveAttribute('data-lead', 'true');
    });

    it('opens the map on the spot, carrying the window it was clicked in', () => {
      const onOpenSpot = vi.fn();
      renderCard({ spots: [spot()] }, { onOpenSpot });
      fireEvent.click(screen.getByRole('button', { name: /Bamburgh Castle/ }));
      expect(onOpenSpot).toHaveBeenCalledWith(
        expect.objectContaining({ date: TODAY, targetType: 'SUNSET' }),
        expect.objectContaining({ locationName: 'Bamburgh Castle' }),
      );
    });
  });

  describe('the lens on the card', () => {
    /**
     * A window the page-wide lens emptied.
     *
     * <p>`lensEmpty` is a DESCRIPTOR now, not a sentence this component composes. With two gates a
     * card cannot say which one emptied it — that judgement needs both thresholds and the whole
     * ungated list, which is `buildWindowCards`' business and is tested against real spots in
     * `windowLensEmpty.test.js`. What is pinned here is that the card renders what it is handed and
     * draws nothing at all when it is handed nothing.
     */
    const emptied = (overrides = {}) => ({
      headline: 'Nothing within 45 min in this window.',
      body: '12 spots are further out.',
      actions: [],
      ...overrides,
    });

    it('counts what is within reach beside the star, once the lens has earned the word', () => {
      renderCard({ spots: [spot()], reachTotal: 6, withinReachCount: 1 });
      expect(screen.getByTestId('window-card-within-reach')).toHaveTextContent('1 within reach');
    });

    it('says what the lens hid, and how much loosening would bring back', () => {
      renderCard({ spots: [], reachTotal: 12, lensEmpty: emptied() });

      expect(screen.getByTestId('window-card-lens-empty-head'))
        .toHaveTextContent('Nothing within 45 min in this window.');
      expect(screen.getByTestId('window-card-lens-empty-body'))
        .toHaveTextContent('12 spots are further out.');
    });

    it('offers each way out as its own control, and hands the whole action back', () => {
      // The card learns nothing about which lenses exist — it returns the descriptor it was given,
      // so a third axis would not widen this component's prop surface.
      const onLoosenLens = vi.fn();
      const actions = [
        { kind: 'reach', id: '90', label: 'Try 1h 30min' },
        { kind: 'rating', id: '3', label: 'Or drop to 3★+' },
      ];
      renderCard({ spots: [], reachTotal: 12, lensEmpty: emptied({ actions }) }, { onLoosenLens });

      const buttons = screen.getAllByTestId('window-card-lens-loosen');
      expect(buttons.map((b) => b.textContent)).toEqual(['Try 1h 30min →', 'Or drop to 3★+ →']);
      fireEvent.click(buttons[1]);
      // The card's own key rides along, and it is not decoration: pressing this button removes it
      // from the DOM, so the shell has to know which card replaced it in order to put focus
      // somewhere. Asserted rather than matched loosely, because a card that handed back the wrong
      // key would send a keyboard reader to a different window.
      expect(onLoosenLens).toHaveBeenCalledWith(actions[1], `${TODAY}:SUNSET`);
    });

    it('draws no way out when the descriptor names none', () => {
      // Reachable and not a bug: a LITE reader is pinned to "Any" reach, so there is no wider tier
      // to offer and `buildLensEmptyState` withholds the action rather than drawing an inert one.
      renderCard({ spots: [], reachTotal: 12, lensEmpty: emptied() });
      expect(screen.queryByTestId('window-card-lens-loosen')).toBeNull();
    });

    it('says nothing about the lens on a window that had no spots to begin with', () => {
      // The line is a statement about the control. On a window the lens never touched there is
      // nothing loosening could bring back, so it would blame a filter for an empty forecast —
      // which is exactly the case `buildLensEmptyState` returns null for.
      renderCard({ spots: [], reachTotal: 0, lensEmpty: null });
      expect(screen.queryByTestId('window-card-lens-empty')).toBeNull();
      expect(screen.queryByTestId('window-spot-strip')).toBeNull();
    });

    it('draws no strip and no footer beside the gated-out card', () => {
      // The footer would read "Listed alphabetically. 0 spots" over nothing at all.
      renderCard({ spots: [], reachTotal: 12, lensEmpty: emptied() });
      expect(screen.queryByTestId('window-spot-strip')).toBeNull();
      expect(screen.queryByTestId('window-spot-foot')).toBeNull();
    });
  });

  describe('the drill-down trigger', () => {
    it('opens the sheet for this card from the strip footer', () => {
      const onSeeAllSpots = vi.fn();
      const c = card({ spots: [spot()] });
      render(<WindowFirstWindowCard card={c} todayStr={TODAY} onSeeAllSpots={onSeeAllSpots} />);
      fireEvent.click(screen.getByTestId('window-spot-all'));
      expect(onSeeAllSpots).toHaveBeenCalledWith(c);
    });

    it('offers it on the fully gated window too, where the number is otherwise unactionable', () => {
      // "12 spots are further out" with no route to those twelve is the defect CLAUDE.md records
      // against Close-to-home's old four-card cap.
      const onSeeAllSpots = vi.fn();
      const c = card({
        spots: [],
        reachTotal: 12,
        lensEmpty: { headline: 'Nothing within 45 min in this window.', body: '12 spots are further out.', actions: [] },
      });
      render(<WindowFirstWindowCard card={c} todayStr={TODAY} onSeeAllSpots={onSeeAllSpots} />);
      fireEvent.click(screen.getByTestId('window-card-lens-all'));
      expect(onSeeAllSpots).toHaveBeenCalledWith(c);
    });

    it('draws neither trigger when the shell withheld the handler', () => {
      renderCard({ spots: [spot()] });
      expect(screen.queryByTestId('window-spot-all')).toBeNull();
      renderCard({
        spots: [],
        reachTotal: 12,
        lensEmpty: { headline: 'Nothing within 45 min in this window.', body: '12 spots are further out.', actions: [] },
      });
      expect(screen.queryByTestId('window-card-lens-all')).toBeNull();
    });

    it('names the window in the gated line\'s trigger, as the strip\'s own does', () => {
      render(
        <WindowFirstWindowCard
          card={card({
            spots: [],
            reachTotal: 12,
            lensEmpty: { headline: 'Nothing within 45 min in this window.', body: '12 spots are further out.', actions: [] },
          })}
          todayStr={TODAY}
          onSeeAllSpots={vi.fn()}
        />,
      );
      expect(screen.getByRole('button', { name: 'See all spots in Tomorrow sunset' }))
        .toBeInTheDocument();
    });

    describe('the modal suppression it passes straight through', () => {
      // A boolean, so nothing in the card subtree learns anything it could act on — P7's rule.
      // Fake timers AND a real score index, because the peek needs both: without either, a test
      // asserting "no panel" passes whatever the flag does, which is the shape §5e convicted.
      const DATE = TODAY;
      const SCORES = new Map([[`${DATE}|SUNSET|Bamburgh Castle`, {
        locationName: 'Bamburgh Castle', fierySkyPotential: 68, goldenHourPotential: 74,
        summary: 'Mid-level cloud should catch the last light.',
      }]]);
      const OPEN_DELAY = 180;
      const renderWith = (peeksSuppressed) => render(
        <WindowFirstWindowCard
          card={card({ spots: [spot({ locationName: 'Bamburgh Castle' })] })}
          todayStr={TODAY}
          peeksSuppressed={peeksSuppressed}
          scoreIndex={SCORES}
        />,
      );

      beforeEach(() => vi.useFakeTimers());
      afterEach(() => vi.useRealTimers());

      it('opens a peek when it is not set — the control case that makes the next test mean something', () => {
        renderWith(false);
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        act(() => vi.advanceTimersByTime(OPEN_DELAY));
        expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
      });

      it('opens none when it is set', () => {
        renderWith(true);
        fireEvent.mouseEnter(screen.getByTestId('window-spot'));
        act(() => vi.advanceTimersByTime(OPEN_DELAY * 2));
        expect(screen.queryByTestId('wf-peek')).toBeNull();
      });
    });
  });

  describe('the lead card', () => {
    it('marks the lead and carries its kicker', () => {
      renderCard({ lead: true, kicker: 'Tonight', when: 'Sunset' });
      expect(screen.getByTestId('window-card')).toHaveAttribute('data-lead', 'true');
      expect(screen.getByTestId('window-card-kicker')).toHaveTextContent('Tonight');
    });

    it('renders no kicker element at all when there is no word for it', () => {
      renderCard({ lead: true, kicker: null, when: 'Today sunrise' });
      expect(screen.getByTestId('window-card')).toHaveAttribute('data-lead', 'true');
      expect(screen.queryByTestId('window-card-kicker')).toBeNull();
    });

    it('is not marked when it does not lead', () => {
      renderCard({ lead: false });
      expect(screen.getByTestId('window-card')).not.toHaveAttribute('data-lead');
    });
  });

  describe('the verdict badge — the confidence channel\'s only render site', () => {
    it('decays the fill and the border as confidence drops, and never the word', () => {
      // The whole point of the channel: a far-horizon "Worth it" reads more provisional than
      // tonight's without ever being harder to read. Asserted as an ordering rather than as three
      // literals, because scaleRgbaAlpha returns the ORIGINAL string untouched at scale 1.0 and a
      // re-serialised one below it — so the high tier and the others are spelled differently.
      const tiers = ['high', 'medium', 'low'].map((confidence) => {
        const { unmount } = renderCard({ confidence });
        const badge = screen.getByTestId('window-card-verdict');
        const style = { fill: badge.style.background, border: badge.style.border, text: badge.style.color };
        unmount();
        return style;
      });

      expect(alphaOf(tiers[0].fill)).toBeGreaterThan(alphaOf(tiers[1].fill));
      expect(alphaOf(tiers[1].fill)).toBeGreaterThan(alphaOf(tiers[2].fill));
      expect(alphaOf(tiers[0].border)).toBeGreaterThan(alphaOf(tiers[1].border));
      expect(alphaOf(tiers[1].border)).toBeGreaterThan(alphaOf(tiers[2].border));
      // The word stays exactly as bright at every tier.
      expect(new Set(tiers.map((t) => t.text)).size).toBe(1);
    });

    it('scales by the documented factors, not by whatever looks about right', () => {
      const { unmount } = renderCard({ confidence: 'medium' });
      const medium = alphaOf(screen.getByTestId('window-card-verdict').style.background);
      unmount();
      renderCard({ confidence: 'low' });
      const low = alphaOf(screen.getByTestId('window-card-verdict').style.background);

      expect(medium).toBeCloseTo(0.14 * 0.72, 3);
      expect(low).toBeCloseTo(0.14 * 0.5, 3);
    });

    it.each(['STAND_DOWN', 'AWAITING'])('leaves a %s badge at full strength', (verdict) => {
      // Confidence qualifies a recommendation. These are not recommendations, and the derivation
      // nulls the field for them — so the badge must not decay even though resolveConfidence would
      // happily infer a tier from the horizon.
      const labels = { STAND_DOWN: 'Poor', AWAITING: 'Awaiting' };
      renderCard({ verdict, verdictLabel: labels[verdict], confidence: null });

      const badge = screen.getByTestId('window-card-verdict');
      expect(alphaOf(badge.style.background)).toBeCloseTo(verdict === 'STAND_DOWN' ? 0.12 : 0.04, 3);
    });

    it('falls back to the horizon when a recommendation carries no backend confidence', () => {
      // The backend really does emit {verdict: WORTH_IT, confidence: absent} — a region whose stats
      // are empty but whose triage still says GO. Gating the decay on `confidence == null` rather
      // than on the verdict rendered that at FULL strength, identical to tonight's high-confidence
      // badge: the exact failure the channel exists to prevent, and a disagreement with the v1 arm,
      // which applies the scale unconditionally once past its Poor early-return.
      renderCard({ confidence: null, date: TODAY });
      // resolveConfidence infers from the horizon, capped at medium.
      expect(alphaOf(screen.getByTestId('window-card-verdict').style.background))
        .toBeCloseTo(0.14 * 0.72, 3);
    });

    it.each([
      ['WORTH_IT', 'Worth it'],
      ['MAYBE', 'Maybe'],
      ['STAND_DOWN', 'Poor'],
      ['AWAITING', 'Awaiting'],
    ])('inks a %s badge in its own colour', (verdict, verdictLabel) => {
      // The alpha assertions above pin the DECAY but not the hue: swapping WORTH_IT's treatment for
      // the Poor red left every one of them green. Verdict colour is the one colour in this UI that
      // carries meaning, so each is pinned by identity, and MAYBE had no render-site test at all.
      const expected = {
        WORTH_IT: 'var(--color-badge-go)',
        MAYBE: 'var(--color-badge-maybe)',
        STAND_DOWN: 'var(--color-badge-poor)',
        AWAITING: 'var(--color-plex-text-secondary)',
      };
      renderCard({ verdict, verdictLabel, confidence: verdict === 'WORTH_IT' ? 'high' : null });
      expect(screen.getByTestId('window-card-verdict').style.color).toBe(expected[verdict]);
    });

    it('carries no provisional marker at any tier', () => {
      // §2.7: the badge already carries ◎, and a second hollow circle is noise §6 bans. The rail
      // deliberately renders nothing from its own confidence so this stays the single site.
      ['high', 'medium', 'low', null].forEach((confidence) => {
        const { unmount } = renderCard({ confidence });
        expect(screen.queryByTestId('provisional-mark')).toBeNull();
        unmount();
      });
    });

    it('marks a recommendation with ◎', () => {
      renderCard({ verdict: 'WORTH_IT', verdictLabel: 'Worth it' });
      expect(screen.getByTestId('window-card-verdict').textContent).toContain('◎');
    });

    it.each([
      ['STAND_DOWN', 'Poor'],
      ['AWAITING', 'Awaiting'],
    ])('withholds it from %s, which recommends nothing', (verdict, verdictLabel) => {
      // Only the STAND_DOWN half was tested, so dropping AWAITING from the suppression left
      // "◎ Awaiting" — the recommendation mark on the one verdict that has not looked yet.
      renderCard({ verdict, verdictLabel, confidence: null });
      expect(screen.getByTestId('window-card-verdict').textContent).not.toContain('◎');
    });

    it('renders Awaiting on the neutral badge, never the red one', () => {
      // "AWAITING is reachable and means the window has neither a rating nor a triage signal — it
      // is not a synonym for a poor forecast, and must not render as one."
      const { unmount } = renderCard({ verdict: 'STAND_DOWN', verdictLabel: 'Poor', confidence: null });
      const poorFill = screen.getByTestId('window-card-verdict').style.background;
      unmount();

      renderCard({ verdict: 'AWAITING', verdictLabel: 'Awaiting', confidence: null });
      const awaitingFill = screen.getByTestId('window-card-verdict').style.background;

      expect(awaitingFill).not.toBe(poorFill);
      expect(awaitingFill).toMatch(/255,\s*255,\s*255/);
    });
  });

  describe('the pick badge', () => {
    const pick = { kind: 'best', regionName: 'The Yorkshire Dales', headline: 'Breaking clear' };

    it('is a real button that opens the pick, naming which pick it is', () => {
      const onOpenPick = vi.fn();
      renderCard({ pick }, { onOpenPick });

      const badge = screen.getByRole('button', { name: /best bet/i });
      expect(badge).toHaveAttribute('data-pick', 'best');
      fireEvent.click(badge);
      expect(onOpenPick).toHaveBeenCalledTimes(1);
      expect(onOpenPick.mock.calls[0][0].pick).toEqual(pick);
    });

    it('distinguishes the runner-up, in the pick channel\'s own colour', () => {
      const { unmount } = renderCard({ pick });
      const best = screen.getByTestId('window-card-pick').style.color;
      unmount();

      renderCard({ pick: { ...pick, kind: 'also' } });
      const also = screen.getByTestId('window-card-pick');
      expect(also).toHaveTextContent('Also good');
      expect(also.style.color).not.toBe(best);
    });

    it('is absent on the windows that are neither pick', () => {
      renderCard({ pick: null });
      expect(screen.queryByTestId('window-card-pick')).toBeNull();
    });

    it('renders for every reader, with no role gate', () => {
      // Settled at P4c: BriefingWindow.Pick is region gloss, ungated on the /api/briefing path and
      // already read by LITE on the v1 tab. The component takes no role prop at all — the shape of
      // the decision, not just its effect.
      renderCard({ pick });
      expect(screen.getByTestId('window-card-pick')).toBeInTheDocument();
      // Asserted as "no role prop exists" rather than as an exact key list. The list form broke on
      // the first additive prop (P6's onOpenSpot) while the rule it protects had not changed — a
      // test that fails for a reason it does not name is churn, not protection.
      //
      // Two separate `not.toContain`, NEVER `not.toEqual(expect.arrayContaining([...]))`: that
      // matcher is conjunctive, so its negation passes unless EVERY listed name is present. The
      // first attempt at this fix used it and went green with `isPro` added on its own — which is
      // this codebase's actual gate-prop name, so the one idiomatic reversal was the one it let
      // through.
      expect(Object.keys(WindowFirstWindowCard.propTypes)).not.toContain('role');
      expect(Object.keys(WindowFirstWindowCard.propTypes)).not.toContain('isPro');
      expect(screen.queryByText(/pro\b|upgrade/i)).toBeNull();
    });
  });

  describe('topic badges', () => {
    it('renders one per topic, each in its own channel', () => {
      renderCard({
        badges: [
          { type: 'NLC', label: '✦ NLC · clearest in 11 nights' },
          { type: 'SPRING_TIDE', label: '≈ Tide · LW on window' },
        ],
      });

      const badges = screen.getAllByTestId('window-card-badge');
      expect(badges.map((b) => b.dataset.channel)).toEqual(['nlc', 'tide']);
      expect(badges[0]).toHaveTextContent('✦ NLC · clearest in 11 nights');
      // The attribute alone proves nothing about the pixels: it is computed by a SECOND, independent
      // badgeChannel call, so `const channel = CHANNEL.plain` rendered every badge neutral grey
      // while data-channel still reported nlc and tide. A badge's colour names its channel.
      expect(badges[0].style.background).not.toBe(badges[1].style.background);
      expect(badges[0].style.color).not.toBe(badges[1].style.color);
    });

    it('gives an unknown topic the neutral badge rather than a colour that would claim a channel', () => {
      renderCard({ badges: [{ type: 'BLUEBELL', label: 'Bluebells out' }] });
      expect(screen.getByTestId('window-card-badge')).toHaveAttribute('data-channel', 'plain');
    });

    it('renders none when the window carries none', () => {
      renderCard({ badges: [] });
      expect(screen.queryByTestId('window-card-badge')).toBeNull();
    });

    it('carries a badge\'s safety warning, the one surface always present when the topic is', () => {
      // The promoted strip shows the warning only for the window it promotes, and the Hot Topics
      // pill sits behind a door that is shut on a fresh session — so without this there were
      // arrangements of the v2 pane where a "Deep partial eclipse" chip was on screen and the
      // solar-filter instruction was nowhere at all.
      renderCard({
        badges: [{
          type: 'ECLIPSE',
          label: 'Deep partial eclipse',
          safetyNote: 'Certified solar filter on the lens — not only over your eye',
        }],
      });

      expect(screen.getByTestId('window-card-safety'))
        .toHaveTextContent('Certified solar filter on the lens — not only over your eye');
    });

    it('draws the warning once when two badges carry one', () => {
      // A warning is about the hazard, not about the chip; two identical lines would be worse
      // than one.
      renderCard({
        badges: [
          { type: 'ECLIPSE', label: 'Deep partial eclipse', safetyNote: 'Filter on the lens' },
          { type: 'METEOR', label: 'Meteor shower', safetyNote: 'Filter on the lens' },
        ],
      });

      expect(screen.getAllByTestId('window-card-safety')).toHaveLength(1);
    });

    it('draws no warning when no badge carries one', () => {
      renderCard({ badges: [{ type: 'NLC', label: '✦ NLC' }] });
      expect(screen.queryByTestId('window-card-safety')).toBeNull();
    });

    it('never promotes a badge into a strip, which is a later phase\'s single-strip rule', () => {
      // topRarityRank is advice for the promoted strip and nothing here enforces the one-strip
      // rule — so the card must not read it or act on it.
      renderCard({ badges: [{ type: 'AURORA', label: '▣ Aurora Kp 4' }] });
      expect(screen.getAllByTestId('window-card-badge')).toHaveLength(1);
    });
  });

  describe('the attribute rows', () => {
    const snowRow = () => buildWindowRows({
      badges: [{
        type: 'SNOW_TOPS',
        label: 'Snow on the fells',
        facts: [{ key: 'snow line', value: '~650 m', emphasis: true, optional: false }],
        rarityRank: 8,
      }],
    }).rows[0];

    it('renders one row per descriptor, in the order it was given them', () => {
      renderCard({ rows: [tideRow(), snowRow()] });

      expect(screen.getAllByTestId('window-attribute-row').map((r) => r.dataset.channel))
        .toEqual(['tide', 'snow']);
    });

    it('renders no container at all when the window has no rows', () => {
      // P5 drew no footer rather than an empty one, and the same call applies here: an empty
      // bordered box is a bar with nothing to say.
      renderCard({ rows: [] });

      expect(screen.queryByTestId('window-card-rows')).toBeNull();
      expect(screen.queryByTestId('window-attribute-row')).toBeNull();
    });

    it('places the rows between the header and the spot strip, inside the collapsible region', () => {
      // Where the design puts them, and the reason it matters beyond looks: P9 wrapped the header's
      // siblings in one collapsible region, so a row that landed outside it would stay on screen
      // with a collapsed card. Both halves are asserted — the card has exactly two children, and
      // the rows are the first thing inside the second of them.
      renderCard({ rows: [tideRow()], spots: [spot()] });

      const card = screen.getByTestId('window-card');
      expect([...card.children].map((el) => el.dataset.testid))
        .toEqual(['window-card-head', 'window-card-body']);

      const body = screen.getByTestId('window-card-body');
      expect([...body.children].map((el) => el.dataset.testid))
        .toEqual(['window-card-rows', 'window-spot-strip', 'window-spot-foot']);
    });

    it('states its own tide facts rather than borrowing the header badge\'s words', () => {
      renderCard({ rows: [tideRow()] });

      expect(screen.getByTestId('window-attribute-row'))
        .toHaveTextContent('HW 19:28 ·1h43 before sunset');
      expect(screen.getByTestId('window-attribute-row')).toHaveTextContent('at Whitby');
    });

    it('is not role-gated, which is P7\'s settled answer to the LITE question', () => {
      // Plan §7. HotTopicStrip blurs every topic's fact chips for LITE; these rows are the window's
      // own context, not that promotional surface, and tide is almanac. One rule for the block.
      //
      // The gate-prop assertion is the load-bearing half, in the idiom this file already uses 120
      // lines up — two separate `not.toContain`, never the conjunctive
      // `not.toEqual(expect.arrayContaining([...]))`. `isLiteUser` is listed as well as `isPro`
      // because that is the name `HotTopicStrip` gates on, and it is the one a reconvergence at P9
      // would most plausibly thread down here.
      //
      // The filter assertion below is deliberately on the FACTS container, not on the row root:
      // `HotTopicStrip` applies `filter: blur(3.5px)` to the element carrying the chips
      // (`TopicFacts`), one level inside its pill. An assertion on the row root would be inert
      // against the one implementation this rule exists to forbid.
      renderCard({ rows: [tideRow(), snowRow()] });

      expect(Object.keys(WindowFirstWindowCard.propTypes)).not.toContain('role');
      expect(Object.keys(WindowFirstWindowCard.propTypes)).not.toContain('isPro');
      expect(Object.keys(WindowFirstWindowCard.propTypes)).not.toContain('isLiteUser');
      expect(screen.getAllByTestId('window-attribute-facts').map((f) => f.style.filter))
        .toEqual(['', '']);
      expect(screen.getByTestId('window-card-rows').style.filter).toBe('');
    });
  });
});
