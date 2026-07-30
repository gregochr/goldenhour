import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import CloseToHome from '../components/CloseToHome.jsx';

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
        eventWindow(TOMORROW, 'SUNRISE', [card('A', 5, { lead: true }), card('B', 4)]),
        eventWindow(TOMORROW, 'SUNSET', [card('C', 5)]),
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
        nextWindow: {
          locationId: 1, locationName: "St Mary's Lighthouse", rating: 4,
          date: TOMORROW, targetType: 'SUNRISE', eventTime: `${TOMORROW}T05:15:00`,
          driveMinutes: 22,
        },
      },
    }));

    const next = screen.getByTestId('close-to-home-next-window');
    expect(next).toHaveTextContent("St Mary's Lighthouse");
    expect(next).toHaveTextContent(`Tomorrow sunrise ${ukTime(`${TOMORROW}T05:15:00`)}`);
  });
});
