/**
 * The window control and the phone peek list on a body shaped by the reader's picks (sub-regions S5a-2,
 * decision 14): a row with no served best star draws nothing in place of the dash, and a night pill's name
 * leaves out "not scored yet", because the places behind it may merely lie outside the reader's sub-regions.
 * Unshaped, both stay exactly as they were.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowControl from '../components/map/WindowControl.jsx';
import { MapPeekWindowsSection } from '../components/map/MapPeekSheet.jsx';

const DAY = '2026-10-28';
const SUNSET = {
  id: `solar:${DAY}:SUNSET`, kind: 'solar', eventType: 'SUNSET', date: DAY, label: 'Wed sunset',
  dayLabel: 'Wed', time: '17:10', bestRating: null, scored: false, badges: [],
};
const ASTRO = {
  id: `astro:${DAY}:ASTRO`, kind: 'astro', eventType: 'ASTRO', date: DAY, label: 'Wed night',
  dayLabel: 'Wed night', time: '21:00', bestRating: null, scored: false, badges: [],
};
const EVENTS = [SUNSET, ASTRO];

const control = (props = {}) => render(
  <WindowControl events={EVENTS} activeIndex={1} onSelect={vi.fn()} {...props} />,
);

describe('WindowControl, shaped', () => {
  it('leaves "not scored yet" out of a night pill name', () => {
    control({ shaped: true });

    expect(screen.getByTestId('wf-win-pill').getAttribute('aria-label')).not.toMatch(/not scored/i);
  });

  it('keeps "not scored yet" on an unshaped night pill', () => {
    control();

    expect(screen.getByTestId('wf-win-pill').getAttribute('aria-label')).toMatch(/not scored yet/i);
  });

  it('keeps a night best star on the pill name when there is one', () => {
    render(
      <WindowControl
        shaped
        events={[SUNSET, { ...ASTRO, bestRating: 3, scored: true }]}
        activeIndex={1}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByTestId('wf-win-pill').getAttribute('aria-label')).toMatch(/best 3 stars/);
  });

  it('draws no dash in an unscored row of the dropdown', () => {
    control({ shaped: true });
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.getByTestId('wf-win-menu').querySelector('.wf-win-row-unscored')).toBeNull();
    screen.getAllByTestId('wf-win-row').forEach((row) => expect(row.textContent).not.toContain('—'));
  });

  it('draws the dash in the same row unshaped', () => {
    control();
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.getByTestId('wf-win-menu').querySelectorAll('.wf-win-row-unscored').length).toBeGreaterThan(0);
  });

  it('still shows a scored row\'s star', () => {
    control({ shaped: true, events: [{ ...SUNSET, bestRating: 4, scored: true }, ASTRO] });
    fireEvent.click(screen.getByTestId('wf-win-pill'));

    expect(screen.getAllByTestId('wf-win-row')[0]).toHaveTextContent('4★ best');
  });
});

describe('MapPeekWindowsSection, shaped', () => {
  const section = (props = {}) => render(
    <MapPeekWindowsSection heading="Next" rows={[ASTRO]} activeId={ASTRO.id} onSelect={vi.fn()} {...props} />,
  );

  it('draws no dash for an unscored night row', () => {
    section({ shaped: true });

    expect(document.querySelector('.wf-map-peek-wr-unscored')).toBeNull();
  });

  it('draws it unshaped', () => {
    section();

    expect(document.querySelector('.wf-map-peek-wr-unscored')).not.toBeNull();
  });
});
