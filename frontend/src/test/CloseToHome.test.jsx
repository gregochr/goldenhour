import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import CloseToHome from '../components/CloseToHome.jsx';

// Home at Whitley Bay; the roster matches the handoff's reference data plus one far location the
// distance gate must reject.
const HOME = { lat: 55.04, lon: -1.45 };

const LOCATIONS = [
  { name: "St Mary's Lighthouse", lat: 55.072, lon: -1.451, regionName: 'Northumberland' },
  { name: 'Souter Lighthouse', lat: 54.969, lon: -1.359, regionName: 'Tyne and Wear' },
  { name: 'Tynemouth Priory', lat: 55.017, lon: -1.418, regionName: 'Tyne and Wear' },
  { name: 'Malham Cove', lat: 54.070, lon: -2.157, regionName: 'The Yorkshire Dales' },
];

function dateStr(offset = 0) {
  const d = new Date();
  d.setDate(d.getDate() + offset);
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(d);
}

const TODAY = dateStr(0);
const TOMORROW = dateStr(1);
const DAY_AFTER = dateStr(2);

function slot(locationName, date, overrides = {}) {
  return {
    locationName,
    solarEventTime: `${date}T05:15:00`,
    verdict: 'GO',
    displayVerdict: 'WORTH_IT',
    claudeRating: 4,
    flags: [],
    ...overrides,
  };
}

function region(regionName, slots) {
  return {
    regionName, verdict: 'GO', displayVerdict: 'WORTH_IT', summary: '', tideHighlights: [], slots,
  };
}

function day(date, regions) {
  return { date, eventSummaries: [{ targetType: 'SUNRISE', regions, unregioned: [] }] };
}

function renderBlock(props = {}) {
  return render(
    <CloseToHome
      briefingDays={props.briefingDays ?? [day(TOMORROW, [
        region('Northumberland', [slot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 })]),
        region('Tyne and Wear', [slot('Souter Lighthouse', TOMORROW, { claudeRating: 4 })]),
      ])]}
      locations={props.locations ?? LOCATIONS}
      homeCoords={'homeCoords' in props ? props.homeCoords : HOME}
      driveMap={props.driveMap ?? new Map([["St Mary's Lighthouse", 22]])}
      evaluationScores={props.evaluationScores ?? new Map()}
      todayStr={TODAY}
      tomorrowStr={TOMORROW}
      onShowOnMap={props.onShowOnMap ?? null}
    />,
  );
}

describe('CloseToHome', () => {
  it('renders nothing without a saved home postcode', () => {
    renderBlock({ homeCoords: null });
    expect(screen.queryByTestId('close-to-home')).not.toBeInTheDocument();
  });

  it('renders nothing when nothing at all is within reach', () => {
    renderBlock({
      briefingDays: [day(TOMORROW, [
        region('The Yorkshire Dales', [slot('Malham Cove', TOMORROW)]),
      ])],
    });
    expect(screen.queryByTestId('close-to-home')).not.toBeInTheDocument();
  });

  it('names the radius it gated on', () => {
    renderBlock();
    expect(screen.getByText('Within 22 miles of home')).toBeInTheDocument();
  });

  it('counts what is within reach across the horizon', () => {
    renderBlock();
    expect(screen.getByTestId('close-to-home-count')).toHaveTextContent('2 within reach · next 3 days');
  });

  it('shows the location, its real region, distance and drive', () => {
    renderBlock();
    const [lead] = screen.getAllByTestId('close-to-home-card');
    expect(lead).toHaveTextContent("St Mary's Lighthouse");
    expect(lead).toHaveTextContent('Northumberland');
    expect(lead).toHaveTextContent('🚗 22 min · 2 mi');
    expect(lead).toHaveTextContent('5.0★');
  });

  it('still shows distance when no drive time is known for the user', () => {
    renderBlock({ driveMap: new Map() });
    const [lead] = screen.getAllByTestId('close-to-home-card');
    expect(lead).toHaveTextContent('2 mi');
    expect(lead).not.toHaveTextContent('🚗');
  });

  it('marks only the rank-1 card as the lead', () => {
    renderBlock();
    const cards = screen.getAllByTestId('close-to-home-card');
    expect(cards[0]).toHaveAttribute('data-lead', 'true');
    expect(cards[1]).not.toHaveAttribute('data-lead');
  });

  it('suppresses the day chip when every card lands on the same day', () => {
    renderBlock();
    expect(screen.queryByTestId('close-to-home-day-chip')).not.toBeInTheDocument();
  });

  it('shows the day chip when the result set spans more than one day', () => {
    renderBlock({
      briefingDays: [
        day(TOMORROW, [region('Northumberland', [slot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5 })])]),
        day(DAY_AFTER, [region('Tyne and Wear', [slot('Souter Lighthouse', DAY_AFTER, { claudeRating: 4 })])]),
      ],
    });
    expect(screen.getAllByTestId('close-to-home-day-chip')).toHaveLength(2);
  });

  it('shows a tide chip only where there is a tide fact', () => {
    renderBlock({
      briefingDays: [day(TOMORROW, [
        region('Northumberland', [slot("St Mary's Lighthouse", TOMORROW, { claudeRating: 5, isSpringTide: true })]),
        region('Tyne and Wear', [slot('Souter Lighthouse', TOMORROW, { claudeRating: 4 })]),
      ])],
    });
    const cards = screen.getAllByTestId('close-to-home-card');
    expect(cards[0]).toHaveTextContent('🌊 spring tide');
    expect(cards[1]).not.toHaveTextContent('🌊');
  });

  it('opens the map focused on that one location', () => {
    const onShowOnMap = vi.fn();
    renderBlock({ onShowOnMap });
    fireEvent.click(screen.getAllByTestId('close-to-home-card')[0]);
    // Signature matches every other location affordance on the Plan tab, so the overlay's
    // location trigger flies to the single pin and auto-opens its popup.
    expect(onShowOnMap).toHaveBeenCalledWith(TOMORROW, 'SUNRISE', "St Mary's Lighthouse");
  });

  // ── the breadcrumb ─────────────────────────────────────────────────────────

  it('reads "Worth it" and quotes the leading local headline', () => {
    renderBlock({
      briefingDays: [day(TOMORROW, [region('Northumberland', [
        slot("St Mary's Lighthouse", TOMORROW, {
          claudeRating: 5, claudeHeadline: 'Spring tide bares the causeway at first light',
        }),
      ])])],
    });
    expect(screen.getByTestId('close-to-home-verdict')).toHaveTextContent('◎ Worth it');
    expect(screen.getByTestId('close-to-home-breadcrumb'))
      .toHaveTextContent('Spring tide bares the causeway at first light');
  });

  // The explicit product decision: an honest "stay in" plus a date to look forward to beats an
  // absent block, so the breadcrumb survives even when part 2 has nothing to show.
  it('keeps the breadcrumb on a poor verdict with no cards', () => {
    renderBlock({
      briefingDays: [day(TOMORROW, [region('Tyne and Wear', [
        slot('Tynemouth Priory', TOMORROW, {
          verdict: 'STANDDOWN', displayVerdict: 'STAND_DOWN', standdownReason: 'Heavy cloud', claudeRating: 1,
        }),
      ])])],
    });
    expect(screen.getByTestId('close-to-home-breadcrumb')).toBeInTheDocument();
    expect(screen.getByTestId('close-to-home-verdict')).toHaveTextContent('○ Stay in');
    expect(screen.getByTestId('close-to-home-breadcrumb')).toHaveTextContent('Heavy cloud');
    expect(screen.queryByTestId('close-to-home-cards')).not.toBeInTheDocument();
    expect(screen.getByTestId('close-to-home-count')).toHaveTextContent('None within reach');
  });

  // Naming the best nearby spot on a bad night undercuts the stay-in verdict — removed by an
  // explicit product decision, so a poor night with nothing ahead names nobody.
  it('names no location when there is no local window ahead', () => {
    renderBlock({
      briefingDays: [day(TOMORROW, [region('Tyne and Wear', [
        slot('Tynemouth Priory', TOMORROW, {
          verdict: 'STANDDOWN', displayVerdict: 'STAND_DOWN', standdownReason: 'Rain', claudeRating: 1,
        }),
      ])])],
    });
    expect(screen.queryByTestId('close-to-home-next-window')).not.toBeInTheDocument();
    expect(screen.getByTestId('close-to-home-breadcrumb')).not.toHaveTextContent('Tynemouth Priory');
  });

  it('points at the next local window worth leaving the house for', () => {
    renderBlock({
      briefingDays: [
        day(TOMORROW, [region('Tyne and Wear', [
          slot('Tynemouth Priory', TOMORROW, {
            verdict: 'STANDDOWN', displayVerdict: 'STAND_DOWN', standdownReason: 'Overcast', claudeRating: 1,
          }),
        ])]),
        day(DAY_AFTER, [region('Northumberland', [
          slot("St Mary's Lighthouse", DAY_AFTER, { claudeRating: 5 }),
        ])]),
      ],
    });
    const window = screen.getByTestId('close-to-home-next-window');
    expect(window).toHaveTextContent('Next local window');
    expect(window).toHaveTextContent("St Mary's Lighthouse · 5.0★");
    expect(window).toHaveTextContent('sunrise');
    expect(window).toHaveTextContent('🚗 22 min');
  });
});
