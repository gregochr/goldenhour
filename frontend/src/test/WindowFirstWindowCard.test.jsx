import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
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
    expect(screen.getByTestId('window-card-best')).toHaveTextContent('best 4★');
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
    renderCard({ badges: [{ type: 'NLC', label: '✦ NLC' }] });
    expect(screen.getByTestId('window-card-head').textContent).not.toMatch(/within reach|spots?\b/i);
  });

  it('carries no expander, because there is nothing yet to collapse', () => {
    // Collapse/expand is P9's. At P5 a collapsed card and an open one differ by a few pixels of
    // padding — the rows are P7, the strip P6, the narrative deleted — so the control would be a
    // demo control, which §6 bans from the shipped build.
    renderCard();
    const head = screen.getByTestId('window-card-head');
    expect(within(head).queryByRole('button', { name: /open|collapse/i })).toBeNull();
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

  describe('the reach lens on the card', () => {
    it('counts what is within reach beside the star, once the lens has earned the word', () => {
      renderCard({ spots: [spot()], reachTotal: 6, withinReachCount: 1 });
      expect(screen.getByTestId('window-card-within-reach')).toHaveTextContent('1 within reach');
    });

    it('says what the lens hid, and how much widening would bring back', () => {
      // The design's line is "Nothing matches the current lens in this window." Two changes: the
      // word "lens" is ours rather than the product's, so the threshold on the chip above is named
      // instead; and the count is stated, because the useful fact is that widening would fill it.
      renderCard({ spots: [], reachTotal: 12 }, { reachLabel: '45 min' });
      expect(screen.getByTestId('window-card-lens-empty'))
        .toHaveTextContent('Nothing within 45 min in this window. 12 spots are further out.');
    });

    it('agrees with itself when one spot is beyond the tier', () => {
      renderCard({ spots: [], reachTotal: 1 }, { reachLabel: '1h 30min' });
      expect(screen.getByTestId('window-card-lens-empty'))
        .toHaveTextContent('Nothing within 1h 30min in this window. 1 spot is further out.');
    });

    it('says nothing about the lens on a window that had no spots to begin with', () => {
      // The line is a statement about the control. On a window the lens never touched there is
      // nothing widening could bring back, so it would blame a filter for an empty forecast.
      renderCard({ spots: [], reachTotal: 0 }, { reachLabel: '45 min' });
      expect(screen.queryByTestId('window-card-lens-empty')).toBeNull();
      expect(screen.queryByTestId('window-spot-strip')).toBeNull();
    });

    it('draws no strip and no footer beside the gated-out line', () => {
      // The footer would read "Listed alphabetically. 0 spots" over nothing at all.
      renderCard({ spots: [], reachTotal: 12 }, { reachLabel: '45 min' });
      expect(screen.queryByTestId('window-spot-strip')).toBeNull();
      expect(screen.queryByTestId('window-spot-foot')).toBeNull();
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

    it('never promotes a badge into a strip, which is a later phase\'s single-strip rule', () => {
      // topRarityRank is advice for the promoted strip and nothing here enforces the one-strip
      // rule — so the card must not read it or act on it.
      renderCard({ badges: [{ type: 'AURORA', label: '▣ Aurora Kp 4' }] });
      expect(screen.getAllByTestId('window-card-badge')).toHaveLength(1);
    });
  });

  describe('the attribute rows', () => {
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

    it('places the rows between the header and the spot strip', () => {
      // Where the design puts them, and the reason it matters beyond looks: P9 wraps the header's
      // siblings in one collapsible region, so a row that landed outside that run would stay open
      // on a collapsed card.
      renderCard({ rows: [tideRow()], spots: [spot()] });

      const card = screen.getByTestId('window-card');
      const order = [...card.children].map((el) => el.dataset.testid);

      expect(order).toEqual(['window-card-head', 'window-card-rows', 'window-spot-strip', 'window-spot-foot']);
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
