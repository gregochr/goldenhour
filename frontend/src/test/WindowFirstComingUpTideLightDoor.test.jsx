import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within, fireEvent } from '@testing-library/react';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';

let mockTideLocked = true;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => ({ tideLocked: mockTideLocked }),
}));

const TODAY = '2026-08-09';

const tide = (id, start, over = {}) => ({
  id, type: 'spring-tide', startDate: start, endDate: start, kind: 'ALMANAC', family: 'coastal',
  title: `Run ${id}`, kindTag: 'Almanac', superlative: null, metric: null, prose: null, facts: [],
  threshold: null,
  action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: start },
  ...over,
});

const COUNTS = { fixed: 3, forecast: 0, byFamily: { coastal: 3 } };

const renderPane = (entries) => render(
  <WindowFirstComingUp
    id="p"
    labelledBy="t"
    status="ready"
    events={{ entries, counts: COUNTS }}
    todayStr={TODAY}
    onRetry={vi.fn()}
    onGoToPlan={vi.fn()}
    onShowOnMap={vi.fn()}
    onMarkSeen={vi.fn()}
  />,
);

const WITHHELD = [
  tide('a', '2026-08-12', { lightRowsWithheld: true }),
  tide('b', '2026-08-20', { lightRowsWithheld: true }),
  tide('c', '2026-08-25', { lightRowsWithheld: true }),
];

beforeEach(() => { mockTideLocked = true; });

describe('Coming up — the Lite tide-light door', () => {
  it('draws exactly one door, on the first withheld card, inside its listitem and after the card', () => {
    renderPane(WITHHELD);
    expect(screen.getAllByTestId('coming-up-tide-light-upsell-door')).toHaveLength(1);
    const rows = screen.getAllByTestId('coming-up-entry');
    const door = within(rows[0]).getByTestId('coming-up-tide-light-upsell-door');
    expect(door.previousElementSibling).toBe(within(rows[0]).getByTestId('coming-up-card'));
    expect(within(rows[1]).queryByTestId('coming-up-tide-light-upsell-door')).toBeNull();
    expect(within(rows[0]).getByTestId('coming-up-tide-light-upsell-trigger'))
      .toHaveTextContent('When high water meets the light');
  });

  it('skips cards that lost nothing and lands on the first that did', () => {
    renderPane([tide('a', '2026-08-12'), tide('b', '2026-08-20', { lightRowsWithheld: true })]);
    const rows = screen.getAllByTestId('coming-up-entry');
    expect(within(rows[0]).queryByTestId('coming-up-tide-light-upsell-door')).toBeNull();
    expect(within(rows[1]).getByTestId('coming-up-tide-light-upsell-door')).toBeInTheDocument();
  });

  it('draws no door for Pro or Admin even if the marker were somehow true', () => {
    mockTideLocked = false;
    renderPane(WITHHELD);
    expect(screen.queryByTestId('coming-up-tide-light-upsell-door')).toBeNull();
  });

  it('puts the door outside the card button for an over-cap, button-card entry', () => {
    // A 40-day run is over the chip cap, so the card is a single action and renders as a <button>.
    renderPane([tide('a', '2026-08-12', { endDate: '2026-09-20', lightRowsWithheld: true })]);
    const card = screen.getByTestId('coming-up-card');
    expect(card.tagName).toBe('BUTTON');
    const door = screen.getByTestId('coming-up-tide-light-upsell-door');
    expect(card.contains(door)).toBe(false);
    expect(door.parentElement).toBe(screen.getByTestId('coming-up-entry'));
  });

  it('opens, names its dialog, and Escape closes it and refocuses the trigger', () => {
    renderPane(WITHHELD);
    const trigger = screen.getByTestId('coming-up-tide-light-upsell-trigger');
    fireEvent.click(trigger);
    const pop = screen.getByTestId('coming-up-tide-light-upsell-popover');
    expect(pop).toHaveAttribute('role', 'dialog');
    expect(screen.getByTestId('coming-up-tide-light-upsell-heading')).toHaveTextContent('High water at the light');
    expect(pop).not.toHaveTextContent(/upgrade/i);
    expect(pop.querySelector('a')).toBeNull();
    fireEvent.keyDown(pop, { key: 'Escape' });
    expect(screen.queryByTestId('coming-up-tide-light-upsell-popover')).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  it('closes on an outside press', () => {
    renderPane(WITHHELD);
    fireEvent.click(screen.getByTestId('coming-up-tide-light-upsell-trigger'));
    fireEvent.pointerDown(document.body);
    expect(screen.queryByTestId('coming-up-tide-light-upsell-popover')).toBeNull();
  });
});
