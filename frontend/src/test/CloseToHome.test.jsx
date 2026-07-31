import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import {
  render, screen, fireEvent, within, act,
} from '@testing-library/react';
import CloseToHome from '../components/CloseToHome.jsx';
import { buildBriefingScoreIndex } from '../utils/briefingScoreIndex.js';

/**
 * Render tests for the Close to home block.
 *
 * The derivation moved to the server, so these no longer exercise selection rules — those live in
 * `CloseToHomeServiceTest`, joined on the location FK rather than a name string. What remains here
 * is the part that stayed on the client and is worth pinning: the window grouping's readability,
 * the two signal flags, and the breadcrumb sentence the endpoint deliberately does not write.
 */

function dateStr(offset = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
}

const TODAY = dateStr(0);
const TOMORROW = dateStr(1);
const LATER = dateStr(3);

function card(locationName, rating, overrides = {}) {
  return {
    locationId: locationName.length,
    locationName,
    regionName: 'Tyne and Wear',
    locationTypes: ['SEASCAPE'],
    rating,
    distanceMiles: 9,
    driveMinutes: 14,
    tideLabel: null,
    lead: false,
    ...overrides,
  };
}

function eventWindow(date, targetType, cards, overrides = {}) {
  return {
    date,
    targetType,
    eventTime: `${date}T05:09:00`,
    bestRating: cards[0]?.rating ?? null,
    withinReach: cards.length,
    notInBriefing: false,
    flaggedRegionName: null,
    sameWindowAsBestBet: false,
    bestBetRegionName: null,
    cards,
    ...overrides,
  };
}

/**
 * The breadcrumb's "next local window": the window's own facts, plus the LOCATION RECORD.
 *
 * It carries the card rather than a copy of three of its fields, because the shortcut renders
 * everything a card renders — nothing displayed on it may be a second description of a place the
 * grid below already describes.
 */
function nextWindow(locationName, rating, date, targetType, hhmm, cardOverrides = {}) {
  return {
    date,
    targetType,
    eventTime: `${date}T${hhmm}:00`,
    card: card(locationName, rating, cardOverrides),
  };
}

function panel(overrides = {}) {
  return {
    radiusMiles: 22,
    horizonDays: 3,
    windows: [eventWindow(TOMORROW, 'SUNRISE', [card('Angel of the North', 4, { lead: true })])],
    breadcrumb: {
      worthIt: true,
      date: TOMORROW,
      targetType: 'SUNRISE',
      eventTime: `${TOMORROW}T05:09:00`,
      topLocationName: 'Angel of the North',
      topRating: 4,
      topHeadline: 'Soft light over the fields',
      topSummary: null,
      dominantReason: null,
      nextWindow: null,
    },
    ...overrides,
  };
}

/**
 * The UK-local time a UTC instant should render as, computed via Intl rather than the component's
 * own formatter — otherwise the assertion would be tautological. Solar times are stored UTC, so
 * through BST every one of them is an hour ahead of its raw string.
 */
function ukTime(isoUtc) {
  return new Date(`${isoUtc}Z`).toLocaleTimeString('en-GB', {
    hour: '2-digit', minute: '2-digit', timeZone: 'Europe/London',
  });
}

function renderBlock(p = panel(), props = {}) {
  return render(
    <CloseToHome panel={p} todayStr={TODAY} tomorrowStr={TOMORROW} {...props} />,
  );
}

describe('CloseToHome', () => {
  it('renders nothing without a panel — no home postcode saved', () => {
    const { container } = render(
      <CloseToHome panel={null} todayStr={TODAY} tomorrowStr={TOMORROW} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('names the radius the server gated on, not a hardcoded one', () => {
    // The radius is a per-user setting now, so the client must never assume 22.
    renderBlock(panel({ radiusMiles: 35 }));
    expect(screen.getByText(/Within 35 miles of home/)).toBeInTheDocument();
  });

  // ── window grouping ───────────────────────────────────────────────────────

  it('groups cards under a header naming day, event and time', () => {
    // The flat list this replaced never said which event a card was for.
    renderBlock();
    expect(screen.getByTestId('cth-window-label'))
      .toHaveTextContent(`Tomorrow sunrise · ${ukTime(`${TOMORROW}T05:09:00`)}`);
  });

  it('renders windows chronologically, not by rating', () => {
    renderBlock(panel({
      windows: [
        eventWindow(TOMORROW, 'SUNRISE', [card('Angel of the North', 4)]),
        eventWindow(TOMORROW, 'SUNSET', [card('Souter Lighthouse', 5)]),
      ],
    }));

    const labels = screen.getAllByTestId('cth-window-label').map((n) => n.textContent);
    // The 5★ sunset stays second: the user is deciding WHEN, so order is chronological.
    expect(labels[0]).toMatch(/sunrise/);
    expect(labels[1]).toMatch(/sunset/);
  });

  it('shows both sunrise and sunset windows — never sunrise-only', () => {
    renderBlock(panel({
      windows: [
        eventWindow(TOMORROW, 'SUNRISE', [card('A', 4)]),
        eventWindow(TOMORROW, 'SUNSET', [card('B', 4)]),
      ],
    }));
    expect(screen.getAllByTestId('cth-window')).toHaveLength(2);
  });

  it('labels a distant window by weekday rather than Today/Tomorrow', () => {
    renderBlock(panel({ windows: [eventWindow(LATER, 'SUNSET', [card('A', 4)])] }));
    expect(screen.getByTestId('cth-window-label').textContent).not.toMatch(/Today|Tomorrow/);
  });

  it('renders event times in UK local, not raw UTC', () => {
    // Solar times are stored UTC. Slicing the ISO string rendered them an hour early for the whole
    // of BST, and the Plan tab's drill-down list — which already converted — then showed the SAME
    // event at a different time on the same screen.
    const iso = `${TOMORROW}T19:52:00`;
    renderBlock(panel({
      windows: [eventWindow(TOMORROW, 'SUNSET', [card('A', 4)], { eventTime: iso })],
    }));

    expect(screen.getByTestId('cth-window-label')).toHaveTextContent(ukTime(iso));
  });

  // ── the two signal flags ──────────────────────────────────────────────────

  it('flags NOT IN THE BRIEFING, naming the standing-down region in the tooltip', () => {
    // The case the region-level grid structurally cannot show.
    renderBlock(panel({
      windows: [eventWindow(TOMORROW, 'SUNRISE', [card('Angel of the North', 4)], {
        notInBriefing: true, flaggedRegionName: 'Tyne and Wear',
      })],
    }));

    const flag = screen.getByTestId('cth-flag-not-in-briefing');
    expect(flag).toBeInTheDocument();
    expect(flag.getAttribute('title')).toContain('Tyne and Wear');
  });

  it('flags SAME WINDOW AS BEST BET and names the Best Bet region', () => {
    renderBlock(panel({
      windows: [eventWindow(TOMORROW, 'SUNSET', [card('Souter Lighthouse', 4)], {
        sameWindowAsBestBet: true, bestBetRegionName: 'Northumberland',
      })],
    }), { isPro: true });

    expect(screen.getByTestId('cth-flag-same-as-best-bet').getAttribute('title'))
      .toContain('Northumberland');
  });

  it('does NOT name the Best Bet region to a LITE user', () => {
    // The pick's region is Pro content — the banner above is isPro-gated and LITE sees a blurred
    // placeholder. Naming it in a tooltip would hand over the fact that placeholder withholds.
    renderBlock(panel({
      windows: [eventWindow(TOMORROW, 'SUNSET', [card('A', 4)], {
        sameWindowAsBestBet: true, bestBetRegionName: 'Northumberland',
      })],
    }), { isPro: false });

    const flag = screen.getByTestId('cth-flag-same-as-best-bet');
    expect(flag).toBeInTheDocument();                       // the signal itself is not identifying
    expect(flag.getAttribute('title')).not.toContain('Northumberland');
  });

  it('shows no flags when neither applies', () => {
    renderBlock();
    expect(screen.queryByTestId('cth-flag-not-in-briefing')).not.toBeInTheDocument();
    expect(screen.queryByTestId('cth-flag-same-as-best-bet')).not.toBeInTheDocument();
  });

  // ── cards ─────────────────────────────────────────────────────────────────

  it('shows the location, its real region, distance and drive', () => {
    renderBlock();
    const c = screen.getByTestId('close-to-home-card');
    expect(c).toHaveTextContent('Angel of the North');
    expect(within(c).getByTestId('close-to-home-region')).toHaveTextContent('Tyne and Wear');
    expect(c.textContent).toMatch(/9 mi/);
    expect(c.textContent).toMatch(/14 min/);
  });

  it('still shows distance when no drive time is known for the user', () => {
    renderBlock(panel({
      windows: [eventWindow(TOMORROW, 'SUNRISE', [card('A', 4, { driveMinutes: null })])],
    }));
    const c = screen.getByTestId('close-to-home-card');
    expect(c.textContent).toMatch(/9 mi/);
    expect(c).not.toHaveTextContent('🚗');
  });

  it('carries the subject-type icon, labelled for screen readers', () => {
    renderBlock();
    expect(screen.getByLabelText('Seascape')).toBeInTheDocument();
  });

  it('marks exactly one lead card across the whole block', () => {
    // A lead per window would make the gold accent meaningless.
    renderBlock(panel({
      windows: [
        eventWindow(TOMORROW, 'SUNRISE',
          [card('A', 5, { locationId: 101, lead: true }), card('B', 4, { locationId: 102 })]),
        eventWindow(TOMORROW, 'SUNSET', [card('C', 5, { locationId: 103 })]),
      ],
    }));

    const leads = screen.getAllByTestId('close-to-home-card')
      .filter((n) => n.getAttribute('data-lead') === 'true');
    expect(leads).toHaveLength(1);
  });

  it('opens the map focused on the card, carrying its own window', () => {
    const onShowOnMap = vi.fn();
    renderBlock(panel(), { onShowOnMap });

    fireEvent.click(screen.getByTestId('close-to-home-card'));

    expect(onShowOnMap).toHaveBeenCalledWith(TOMORROW, 'SUNRISE', 'Angel of the North');
  });

  // ── the breadcrumb sentence, which the server deliberately does not write ──

  it('prefers the Claude headline for a worth-it verdict', () => {
    renderBlock();
    expect(screen.getByTestId('close-to-home-verdict')).toHaveTextContent('Worth it');
    expect(screen.getByText('Soft light over the fields')).toBeInTheDocument();
  });

  it('names the dominant cause on a stay-in night', () => {
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TOMORROW, targetType: 'SUNRISE',
        eventTime: `${TOMORROW}T05:09:00`, topLocationName: null, topRating: null,
        topHeadline: null, topSummary: null, dominantReason: 'Heavy cloud', nextWindow: null,
      },
    }));

    expect(screen.getByTestId('close-to-home-verdict')).toHaveTextContent('Stay in');
    expect(screen.getByText(/Heavy cloud nearby/)).toBeInTheDocument();
  });

  it('never names a nearby location on a stay-in night', () => {
    // An earlier iteration surfaced the best nearby spot at 1.8★ here, which undercut the verdict
    // it sat beside. The endpoint returns null for it deliberately; this pins the rendering.
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TOMORROW, targetType: 'SUNRISE',
        eventTime: `${TOMORROW}T05:09:00`, topLocationName: null, topRating: null,
        topHeadline: null, topSummary: null, dominantReason: 'Full cloud', nextWindow: null,
      },
    }));

    expect(screen.queryByTestId('close-to-home-card')).not.toBeInTheDocument();
    expect(screen.getByTestId('close-to-home-breadcrumb')).toBeInTheDocument();
  });

  it('shows the next local window — the hope that survives a stay-in verdict', () => {
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TODAY, targetType: 'SUNSET', eventTime: `${TODAY}T21:19:00`,
        topLocationName: null, topRating: null, topHeadline: null, topSummary: null,
        dominantReason: 'Heavy cloud',
        nextWindow: nextWindow("St Mary's Lighthouse", 4, TOMORROW, 'SUNRISE', '05:15',
          { driveMinutes: 22 }),
      },
    }));

    const next = screen.getByTestId('close-to-home-next-window');
    expect(next).toHaveTextContent("St Mary's Lighthouse");
    expect(next).toHaveTextContent(`Tomorrow sunrise ${ukTime(`${TOMORROW}T05:15:00`)}`);
  });

  it('shows everything a card shows — region, rating pill, drive, distance and tide', () => {
    // Users read this as "my next closest good opportunity" and act on it, so it has to be a
    // location affordance rather than a label. It was showing a bare `· 4.0★` and a drive time,
    // and withholding the three facts that decide whether the trip is on: where it actually is,
    // how far, and what the water is doing.
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TODAY, targetType: 'SUNSET', eventTime: `${TODAY}T21:19:00`,
        topLocationName: null, topRating: null, topHeadline: null, topSummary: null,
        dominantReason: 'Heavy cloud',
        nextWindow: nextWindow('Souter Lighthouse', 4, TODAY, 'SUNSET', '21:11', {
          regionName: 'Tyne and Wear', driveMinutes: 10, distanceMiles: 4,
          tideLabel: 'spring tide',
        }),
      },
    }));

    const next = screen.getByTestId('close-to-home-next-window');
    expect(within(next).getByTestId('close-to-home-next-window-region'))
      .toHaveTextContent('Tyne and Wear');
    // The same pill the cards carry, not a run-on `Name · 4.0★`.
    expect(within(next).getByTestId('close-to-home-next-window-stars')).toHaveTextContent('4.0★');
    expect(next.textContent).toMatch(/10 min/);
    expect(next.textContent).toMatch(/4 mi/);
    expect(next.textContent).toMatch(/spring tide/);
  });

  it('omits the tide from the next local window when there is none', () => {
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TODAY, targetType: 'SUNSET', eventTime: `${TODAY}T21:19:00`,
        topLocationName: null, topRating: null, topHeadline: null, topSummary: null,
        dominantReason: 'Heavy cloud',
        nextWindow: nextWindow('Copt Hill', 4, TOMORROW, 'SUNRISE', '05:15', { tideLabel: null }),
      },
    }));

    const next = screen.getByTestId('close-to-home-next-window');
    expect(next).not.toHaveTextContent('🌊');
    expect(next.textContent).toMatch(/9 mi/);      // distance is never absent
  });

  it('opens the map from the next local window, exactly as a card does', () => {
    // It names a location, a date and an event — the same three facts a card carries — so it was
    // the one dead end on a panel where every other location is one tap from the pin.
    const onShowOnMap = vi.fn();
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TODAY, targetType: 'SUNSET', eventTime: `${TODAY}T21:19:00`,
        topLocationName: null, topRating: null, topHeadline: null, topSummary: null,
        dominantReason: 'Heavy cloud',
        nextWindow: nextWindow('Copt Hill', 4, TOMORROW, 'SUNRISE', '05:15',
          { driveMinutes: 10 }),
      },
    }), { onShowOnMap });

    const next = screen.getByTestId('close-to-home-next-window');
    // A native button: focusable and Enter/Space-activatable with no key handling of our own,
    // which is exactly what the handoff's role="button" + tabindex="0" reconstructs by hand.
    expect(next.tagName).toBe('BUTTON');
    expect(next).toHaveTextContent('Open on map');

    fireEvent.click(next);
    // Positional (date, targetType, locationName) — the same signature the cards call.
    expect(onShowOnMap).toHaveBeenCalledWith(TOMORROW, 'SUNRISE', 'Copt Hill');
  });

  // ── hover preview ─────────────────────────────────────────────────────────

  /** A briefing-score map keyed the way the evaluation cache keys it. */
  function scoreIndexFor(locationName, date, targetType, score) {
    return buildBriefingScoreIndex(
      new Map([[`Tyne and Wear|${date}|${targetType}|${locationName}`, score]]),
    );
  }

  it('previews the summary and scores on hover, from data already in memory', () => {
    // The preview must cost nothing to open — sweeping a pointer across the grid cannot become a
    // burst of requests — so it reads the briefing scores the Plan tab already holds.
    renderBlock(panel(), {
      isPro: true,
      scoreIndex: scoreIndexFor('Angel of the North', TOMORROW, 'SUNRISE', {
        rating: 4, summary: 'Breaking low cloud frames golden westerly light',
        fierySkyPotential: 68, goldenHourPotential: 72,
      }),
    });

    expect(screen.queryByTestId('cth-hover-preview')).not.toBeInTheDocument();

    fireEvent.mouseEnter(screen.getByTestId('close-to-home-card'));

    const preview = screen.getByTestId('cth-hover-preview');
    expect(preview).toHaveTextContent('Angel of the North');
    expect(preview).toHaveTextContent('Breaking low cloud frames golden westerly light');
    expect(preview).toHaveTextContent('68');
    expect(preview).toHaveTextContent('72');

    fireEvent.mouseLeave(screen.getByTestId('close-to-home-card'));
    expect(screen.queryByTestId('cth-hover-preview')).not.toBeInTheDocument();
  });

  it('falls back to the map forecast row when no briefing score covers the slot', () => {
    renderBlock(panel(), {
      isPro: true,
      locations: [{
        name: 'Angel of the North',
        forecastsByDate: new Map([[TOMORROW, {
          sunrise: { rating: 3, fierySkyPotential: 41, goldenHourPotential: 55 },
        }]]),
      }],
    });

    fireEvent.mouseEnter(screen.getByTestId('close-to-home-card'));

    const preview = screen.getByTestId('cth-hover-preview');
    expect(preview).toHaveTextContent('41');
    // No Claude sentence on the slim forecast row — the preview shows scores, not a fabricated one.
    expect(screen.queryByTestId('cth-hover-summary')).not.toBeInTheDocument();
  });

  it('shows no preview at all when nothing is known about the slot', () => {
    // A hover shortcut is not worth a fetch. Knowing nothing means showing nothing.
    renderBlock(panel());

    fireEvent.mouseEnter(screen.getByTestId('close-to-home-card'));

    expect(screen.queryByTestId('cth-hover-preview')).not.toBeInTheDocument();
  });

  it('withholds the two Pro scores from a Lite user, as the map popup does', () => {
    // The map popup gates both score blocks on role and offers "Upgrade to Pro for Fiery Sky &
    // Golden Hour scores" instead. The values reach a Lite client anyway, so the gate is the UX
    // layer and has to hold at every surface that renders them.
    renderBlock(panel(), {
      isPro: false,
      scoreIndex: scoreIndexFor('Angel of the North', TOMORROW, 'SUNRISE', {
        rating: 4, summary: 'Breaking low cloud frames golden westerly light',
        fierySkyPotential: 68, goldenHourPotential: 72,
      }),
    });

    fireEvent.mouseEnter(screen.getByTestId('close-to-home-card'));

    expect(screen.queryByTestId('cth-hover-scores')).not.toBeInTheDocument();
    expect(screen.getByTestId('cth-hover-upsell')).toHaveTextContent('Upgrade to Pro');
    // The summary and stars are not Pro content and stay.
    expect(screen.getByTestId('cth-hover-summary')).toBeInTheDocument();
  });

  it('previews the next local window too, from the same handler', () => {
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TODAY, targetType: 'SUNSET', eventTime: `${TODAY}T21:19:00`,
        topLocationName: null, topRating: null, topHeadline: null, topSummary: null,
        dominantReason: 'Heavy cloud',
        nextWindow: nextWindow('Copt Hill', 4, TOMORROW, 'SUNRISE', '05:15',
          { driveMinutes: 10 }),
      },
    }), {
      scoreIndex: scoreIndexFor('Copt Hill', TOMORROW, 'SUNRISE', {
        rating: 4, summary: 'Clear horizon under a settled ridge',
        fierySkyPotential: 71, goldenHourPotential: 66,
      }),
    });

    fireEvent.mouseEnter(screen.getByTestId('close-to-home-next-window'));

    expect(screen.getByTestId('cth-hover-preview'))
      .toHaveTextContent('Clear horizon under a settled ridge');
  });

  it('dismisses the preview when the click opens the map, on every surface that opens it', () => {
    // The preview is `position: fixed` at coordinates captured on mouseenter and sits ABOVE the
    // map overlay. A click swaps that overlay in under a pointer that has not moved, so the card
    // is covered without a mouseleave necessarily firing and the panel is left floating over the
    // modal — the same failure the strip's onScroll already guards. Every surface that opens the
    // map has to dismiss on the way out, so all three are pinned here rather than just the card.
    const onShowOnMap = vi.fn();
    const scoreIndex = scoreIndexFor('Angel of the North', TOMORROW, 'SUNRISE', {
      rating: 4, summary: 'Breaking low cloud frames golden westerly light',
      fierySkyPotential: 68, goldenHourPotential: 72,
    });

    // 1. A card in the filmstrip.
    const { unmount } = renderBlock(panel(), { isPro: true, scoreIndex, onShowOnMap });
    fireEvent.mouseEnter(screen.getByTestId('close-to-home-card'));
    expect(screen.getByTestId('cth-hover-preview')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('close-to-home-card'));
    expect(screen.queryByTestId('cth-hover-preview')).not.toBeInTheDocument();
    expect(onShowOnMap).toHaveBeenCalledWith(TOMORROW, 'SUNRISE', 'Angel of the North');
    unmount();

    // 2. A row in the expanded ranked list — the same window, opened out.
    renderBlock(panel(), { isPro: true, scoreIndex, onShowOnMap });
    fireEvent.click(screen.getByTestId('cth-window-count'));
    fireEvent.mouseEnter(screen.getByTestId('cth-window-row'));
    expect(screen.getByTestId('cth-hover-preview')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('cth-window-row'));
    expect(screen.queryByTestId('cth-hover-preview')).not.toBeInTheDocument();
  });

  it('dismisses the preview when the next-window shortcut opens the map', () => {
    const onShowOnMap = vi.fn();
    renderBlock(panel({
      windows: [],
      breadcrumb: {
        worthIt: false, date: TODAY, targetType: 'SUNSET', eventTime: `${TODAY}T21:19:00`,
        topLocationName: null, topRating: null, topHeadline: null, topSummary: null,
        dominantReason: 'Heavy cloud',
        nextWindow: nextWindow('Copt Hill', 4, TOMORROW, 'SUNRISE', '05:15',
          { driveMinutes: 10 }),
      },
    }), {
      onShowOnMap,
      scoreIndex: scoreIndexFor('Copt Hill', TOMORROW, 'SUNRISE', {
        rating: 4, summary: 'Clear horizon under a settled ridge',
        fierySkyPotential: 71, goldenHourPotential: 66,
      }),
    });

    fireEvent.mouseEnter(screen.getByTestId('close-to-home-next-window'));
    expect(screen.getByTestId('cth-hover-preview')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('close-to-home-next-window'));

    expect(screen.queryByTestId('cth-hover-preview')).not.toBeInTheDocument();
    expect(onShowOnMap).toHaveBeenCalledWith(TOMORROW, 'SUNRISE', 'Copt Hill');
  });

  // ── overflow: reaching past the first four ────────────────────────────────

  /** A window holding `n` cards, rating-descending so the order is the server's. */
  function bigWindow(n, overrides = {}) {
    const cards = Array.from({ length: n }, (_, i) => card(`Spot ${i + 1}`, i < 3 ? 4 : 3, {
      locationId: i + 1, driveMinutes: 10 + i * 5, tideLabel: i % 2 === 0 ? 'low tide' : null,
    }));
    return eventWindow(TOMORROW, 'SUNRISE', cards, overrides);
  }

  it('carries every card, so the count is always reachable', () => {
    // The block used to render four cards under a header saying "10 within reach". Whatever the
    // client shows at once, all ten must be in the DOM to be scrolled or expanded to.
    renderBlock(panel({ windows: [bigWindow(10)] }));

    expect(screen.getAllByTestId('close-to-home-card')).toHaveLength(10);
    expect(screen.getByTestId('cth-window-count')).toHaveTextContent('10 within reach');
  });

  it('expands the count into a ranked list of the whole window', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));

    fireEvent.click(screen.getByTestId('cth-window-count'));

    // The strip gives way to the list — showing both would state the ranking twice.
    expect(screen.queryByTestId('cth-window-cards')).not.toBeInTheDocument();
    expect(screen.getAllByTestId('cth-window-row')).toHaveLength(10);

    fireEvent.click(screen.getByTestId('cth-window-count'));
    expect(screen.getByTestId('cth-window-cards')).toBeInTheDocument();
  });

  it('opens the map from a ranked row, exactly as its card does', () => {
    const onShowOnMap = vi.fn();
    renderBlock(panel({ windows: [bigWindow(10)] }), { onShowOnMap });

    fireEvent.click(screen.getByTestId('cth-window-count'));
    fireEvent.click(screen.getAllByTestId('cth-window-row')[0]);

    expect(onShowOnMap).toHaveBeenCalledWith(TOMORROW, 'SUNRISE', 'Spot 1');
  });

  it('states the ordering rule so a scrolling strip does not read as a sample', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));

    expect(screen.getByTestId('cth-ordering-explainer')).toHaveTextContent(
      'Ranked by rating, then by drive time. Showing the top 4 of 10 — scroll for the rest.',
    );
  });

  it('gates the "top 4 of N" clause on the CSS, not a JS breakpoint', () => {
    // The count is a fact about the 4.5-across flex basis, which only applies from 900px. Guarding
    // it in JS needs a second copy of that number: the first attempt used `isMobile` (639px) and
    // left the sentence claiming four cards across the whole 640-899px band, one card wide.
    renderBlock(panel({ windows: [bigWindow(10)] }));

    const clause = screen.getByText(/Showing the top 4 of 10/);
    expect(clause).toHaveClass('cth-wide-only');
  });

  it('says nothing at all when the whole window already fits', () => {
    // The note exists to make scrolling feel optional. A window with nothing off-screen has
    // nothing to reassure anyone about, and the sentence would be pure noise.
    renderBlock(panel({ windows: [bigWindow(3)] }));

    expect(screen.queryByTestId('cth-ordering-explainer')).not.toBeInTheDocument();
  });

  it('notes each overflowing window, not just the first one', () => {
    // The rule used to be printed once under window 1 whatever its size, so a 3-card first window
    // swallowed the sentence for a 20-card second one — the reader was left to guess whether the
    // strip that ran off the edge was ranked or arbitrary.
    renderBlock(panel({
      windows: [
        eventWindow(TOMORROW, 'SUNRISE',
          [card('A', 4, { locationId: 101 }), card('B', 4, { locationId: 102 })]),
        bigWindow(9, { date: TOMORROW, targetType: 'SUNSET' }),
      ],
    }));

    const notes = screen.getAllByTestId('cth-ordering-explainer');
    expect(notes).toHaveLength(1);
    expect(notes[0]).toHaveTextContent('Showing the top 4 of 9 — scroll for the rest.');
  });

  it('drops the note when the window is expanded into the ranked list', () => {
    // Nothing is off-screen in the list, so "scroll for the rest" would be a lie.
    renderBlock(panel({ windows: [bigWindow(10)] }));

    fireEvent.click(screen.getByTestId('cth-window-count'));

    expect(screen.queryByTestId('cth-ordering-explainer')).not.toBeInTheDocument();
  });

  // ── overflow: the scroll rail ─────────────────────────────────────────────

  /**
   * jsdom lays nothing out: every element reports `scrollWidth === clientWidth === 0`, so the rail
   * would never render and every assertion below would pass vacuously. Fake the one measurement it
   * takes — a 400px viewport over 1000px of cards — and give `scrollLeft` a real setter so writes
   * are observable and fire `scroll` as the browser's would.
   */
  function overflowStrip({ clientWidth = 400, scrollWidth = 1000, railWidth = 200 } = {}) {
    const strip = screen.getByTestId('cth-window-cards');
    let scrollLeft = 0;
    Object.defineProperty(strip, 'clientWidth', { configurable: true, value: clientWidth });
    Object.defineProperty(strip, 'scrollWidth', { configurable: true, value: scrollWidth });
    Object.defineProperty(strip, 'scrollLeft', {
      configurable: true,
      get: () => scrollLeft,
      set: (v) => { scrollLeft = v; fireEvent.scroll(strip); },
    });
    fireEvent(window, new Event('resize'));

    const rail = screen.getByTestId('cth-scroll-rail');
    rail.getBoundingClientRect = () => ({
      left: 0, right: railWidth, width: railWidth, top: 0, bottom: 2, height: 2, x: 0, y: 0,
    });
    rail.setPointerCapture = vi.fn();
    rail.releasePointerCapture = vi.fn();
    return { strip, rail };
  }

  it('sizes the thumb by what is visible and offsets it by what is scrolled', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const { strip, rail } = overflowStrip();

    expect(rail.querySelector('i')).toHaveStyle({ width: '40%', left: '0%' });

    strip.scrollLeft = 300;

    expect(rail.querySelector('i')).toHaveStyle({ left: '30%' });
  });

  it('scrolls the strip when the rail is clicked — it is the only scrollbar there is', () => {
    // The strip hides the native bar, so for a mouse user this rail IS the scrollbar. It shipped
    // as a read-only indicator: it looked exactly like a control and answered nothing.
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const { strip, rail } = overflowStrip();

    // Middle of the rail: 0.5 of the content, less half a thumb (0.2) → 300px in.
    fireEvent.pointerDown(rail, { clientX: 100, pointerId: 1 });

    expect(strip.scrollLeft).toBe(300);
  });

  it('tracks a drag along the rail, and clamps at both ends', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const { strip, rail } = overflowStrip();

    fireEvent.pointerDown(rail, { clientX: 100, pointerId: 1 });
    fireEvent.pointerMove(rail, { clientX: 200, pointerId: 1 });

    // Far right asks for 800; the strip only has 600 of travel.
    expect(strip.scrollLeft).toBe(600);

    fireEvent.pointerMove(rail, { clientX: 0, pointerId: 1 });

    expect(strip.scrollLeft).toBe(0);
  });

  it('ignores pointer movement that is not a drag — a sweep across the rail is not a grab', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const { strip, rail } = overflowStrip();

    fireEvent.pointerMove(rail, { clientX: 180, pointerId: 1 });

    expect(strip.scrollLeft).toBe(0);
  });

  it('suspends scroll snapping for the drag, and restores it on release', () => {
    // `scroll-snap-type: x mandatory` re-snaps every scrollLeft write, so a live drag stutters
    // card by card and reads as the rail fighting the pointer. Restoring it re-snaps once, which
    // is what leaves the strip on a card edge rather than mid-card.
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const { strip, rail } = overflowStrip();

    fireEvent.pointerDown(rail, { clientX: 100, pointerId: 1 });

    expect(strip.style.scrollSnapType).toBe('none');

    fireEvent.pointerUp(rail, { clientX: 100, pointerId: 1 });

    expect(strip.style.scrollSnapType).toBe('');
  });

  it('re-measures when the strip itself resizes, not only the window', () => {
    // Caught in a real browser: the first measure ran before layout settled and the thumb kept a
    // width describing a strip that no longer existed — 16% of a strip that was showing 41%. The
    // panel resizing, or a font swapping, changes the strip without firing a window `resize`.
    const observed = [];
    const originalRO = global.ResizeObserver;
    global.ResizeObserver = class {
      constructor(cb) { this.cb = cb; }

      observe(el) { observed.push({ el, fire: () => this.cb() }); }

      disconnect() {}
    };
    try {
      renderBlock(panel({ windows: [bigWindow(10)] }));
      const { strip, rail } = overflowStrip();

      expect(observed.map((o) => o.el)).toContain(strip);

      Object.defineProperty(strip, 'clientWidth', { configurable: true, value: 250 });
      // A real ResizeObserver callback arrives outside React's event system, so the state update
      // it triggers needs an explicit act() — fireEvent supplies one, a direct call does not.
      act(() => observed.find((o) => o.el === strip).fire());

      expect(rail.querySelector('i')).toHaveStyle({ width: '25%' });
    } finally {
      global.ResizeObserver = originalRO;
    }
  });

  it('renders no rail at all when the strip does not overflow', () => {
    // A full-width thumb on a strip that fits reads as a disabled control, and now that the rail
    // is draggable it would be one — a scrollbar for a scroll that cannot happen.
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const strip = screen.getByTestId('cth-window-cards');
    Object.defineProperty(strip, 'clientWidth', { configurable: true, value: 1000 });
    Object.defineProperty(strip, 'scrollWidth', { configurable: true, value: 1000 });
    fireEvent(window, new Event('resize'));

    expect(screen.queryByTestId('cth-scroll-rail')).not.toBeInTheDocument();
  });

  // ── the block header's total ──────────────────────────────────────────────

  it('counts each location ONCE across windows, not per window it qualifies in', () => {
    // The heading beside the number names a radius, so it has to count PLACES you could drive to.
    // Summing the windows counted a location once per window — two spots across two windows read
    // "4 within reach" under "Within 22 miles of home".
    // Same two locations, both windows — ids are what identify them, not the name string.
    const a = (rating) => card('Angel of the North', rating, { locationId: 101 });
    const b = (rating) => card('Copt Hill', rating, { locationId: 102 });
    renderBlock(panel({
      windows: [
        eventWindow(TOMORROW, 'SUNRISE', [a(4), b(4)]),
        eventWindow(TOMORROW, 'SUNSET', [b(5), a(3)]),
      ],
    }));

    expect(screen.getByTestId('close-to-home-count')).toHaveTextContent('2 within reach');
  });

  it('counts a location present in only one window, which the busiest-window rule missed', () => {
    // The previous fix for double-counting took the busiest single window, which undercounts the
    // other way: two windows holding one different location each are two places, not one.
    renderBlock(panel({
      windows: [
        eventWindow(TOMORROW, 'SUNRISE', [card('Angel of the North', 4, { locationId: 101 })]),
        eventWindow(TOMORROW, 'SUNSET', [card('Copt Hill', 4, { locationId: 102 })]),
      ],
    }));

    expect(screen.getByTestId('close-to-home-count')).toHaveTextContent('2 within reach');
  });

  // ── overflow: filters ─────────────────────────────────────────────────────

  it('recomputes every count from the filtered set, never the raw one', () => {
    // A count that survived its own filter is exactly the bug the expandable count exists to
    // avoid — the header would offer a door to locations no longer on the other side of it.
    renderBlock(panel({ windows: [bigWindow(10)] }));

    // Any drive → ≤ 15 min: only Spot 1 (10 min) and Spot 2 (15 min) survive.
    fireEvent.click(screen.getByTestId('cth-filter-drive'));

    expect(screen.getAllByTestId('close-to-home-card')).toHaveLength(2);
    expect(screen.getByTestId('cth-window-count')).toHaveTextContent('2 within reach');
    expect(screen.getByTestId('close-to-home-count')).toHaveTextContent('2 within reach');
  });

  it('cycles the drive filter back to Any rather than stranding it on', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));
    const chip = screen.getByTestId('cth-filter-drive');

    expect(chip).toHaveTextContent('Any drive');
    fireEvent.click(chip);
    expect(chip).toHaveTextContent('≤ 15 min');
    fireEvent.click(chip);
    fireEvent.click(chip);
    expect(chip).toHaveTextContent('≤ 45 min');
    fireEvent.click(chip);
    expect(chip).toHaveTextContent('Any drive');
  });

  it('keeps the window head when its cards all filter out', () => {
    // Removing the head would make the day/event rhythm shuffle as the user narrows the set.
    renderBlock(panel({ windows: [bigWindow(10)] }));

    fireEvent.click(screen.getByTestId('cth-filter-rating')); // 3.0★+
    fireEvent.click(screen.getByTestId('cth-filter-rating')); // 4.0★+
    fireEvent.click(screen.getByTestId('cth-filter-rating')); // 5.0★+ — nothing rates 5

    expect(screen.getByTestId('cth-window-label')).toBeInTheDocument();
    expect(screen.getByTestId('cth-window-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('close-to-home-card')).not.toBeInTheDocument();
  });

  it('keeps only tide-carrying spots when tide-only is on', () => {
    renderBlock(panel({ windows: [bigWindow(10)] }));

    fireEvent.click(screen.getByTestId('cth-filter-tide'));

    // Spots 1, 3, 5, 7, 9 carry a tide label in the fixture.
    expect(screen.getAllByTestId('close-to-home-card')).toHaveLength(5);
  });
});
