import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowFirstWindowCard from '../components/WindowFirstWindowCard.jsx';

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
    pick: null,
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

  it('carries no footer bar, rather than an empty one', () => {
    // Everything the design puts in it — the strip's sort statement, the film controls, "See all
    // N →" — is P6 and P11. A bar claiming a sort over a set that is not on screen is exactly the
    // failure §6 names.
    renderCard();
    expect(screen.getByTestId('window-card').textContent).not.toMatch(/ranked by|see all|loaded/i);
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
      expect(Object.keys(WindowFirstWindowCard.propTypes)).toEqual(['card', 'todayStr', 'onOpenPick']);
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
});
