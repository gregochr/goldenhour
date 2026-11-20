/**
 * The shared warning body (`WindowWarningsBody`, weather-warnings-plan.md W3) that the Map's expanded warning and
 * Coming up's warning entries draw. The window popup's card-adapter row over it was deleted in glance-to-map-plan.md G2c;
 * the feed gating (flag off, stale, withdrawn) lives with the surfaces that own it.
 *
 * <p>What breaks if these fail: warning text is truncated (the style guide wants it in full), the Updated label or
 * the update text is missing, the matrix or its text alternative is missing, or the attribution or the Met Office
 * links disappear.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import { WindowWarningsBody, WHAT_SHOULD_I_DO_URL } from '../components/WindowWarningsBody.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

const CARD = {
  key: '2026-08-14:SUNSET',
  date: '2026-08-14',
  eventIso: '2026-08-14T19:00:00',
  warningIds: [],
  placeIds: [7],
  warningPlaceCounts: [
    { warningId: 'amber-snow', regionName: 'Northumberland', covered: 4, total: 12 },
    { warningId: 'amber-snow', regionName: 'The Lakes', covered: 2, total: 9 },
  ],
  away: false,
};

const AMBER = warning({
  warningId: 'amber-snow',
  level: 'AMBER',
  weatherTypes: ['SNOW', 'ICE'],
  likelihood: 3,
  impact: 3,
  updated: true,
  warningVersion: '2.0',
  validFrom: '2026-08-14T11:00:00Z',
  validTo: '2026-08-15T07:30:00Z',
  headline: 'Heavy snow likely to cause disruption',
  whatToExpect: ['Travel delays are likely', 'Some rural communities may be cut off', 'Power cuts are possible'],
  whatShouldIDo: 'Plan your journey and take warm clothes.',
  furtherDetails: 'Snow will turn to rain on Saturday.',
  updateDescription: 'Upgraded from yellow.',
});
const YELLOW = warning({
  warningId: 'yellow-wind',
  level: 'YELLOW',
  likelihood: 3,
  impact: 2,
  headline: 'Strong winds',
  whatToExpect: [],
  validFrom: '2026-08-14T00:00:00Z',
  validTo: '2026-08-16T00:00:00Z',
});

const mount = async (served, warnings = served.warnings, counts = CARD.warningPlaceCounts) => {
  getWarnings.mockResolvedValue(served);
  render(<WarningsProvider><WindowWarningsBody warnings={warnings} counts={counts} /></WarningsProvider>);
  await act(async () => {});
};

describe('WindowWarningsBody', () => {
  beforeEach(() => { getWarnings.mockReset(); });

  it('shows the warning in full: headline, every what-to-expect line, advice, further details and update', async () => {
    await mount(response([AMBER]));
    const article = screen.getByTestId('window-warning');
    expect(within(article).getByTestId('window-warning-headline')).toHaveTextContent('Heavy snow likely to cause disruption');
    const items = within(within(article).getByTestId('window-warning-expect')).getAllByRole('listitem');
    expect(items.map((li) => li.textContent)).toEqual([
      'Travel delays are likely', 'Some rural communities may be cut off', 'Power cuts are possible',
    ]);
    expect(within(article).getByTestId('window-warning-advice')).toHaveTextContent('Plan your journey and take warm clothes.');
    expect(within(article).getByTestId('window-warning-further')).toHaveTextContent('Snow will turn to rain on Saturday.');
    expect(within(article).getByTestId('window-warning-update')).toHaveTextContent('Upgraded from yellow.');
  });

  it('states the level word, the weather types and the Updated label on the chip', async () => {
    await mount(response([AMBER]));
    const chip = within(screen.getByTestId('window-warning')).getByTestId('warning-chip');
    expect(chip).toHaveTextContent('Amber warning of snow and ice');
    expect(within(chip).getByTestId('warning-chip-updated')).toBeInTheDocument();
  });

  it('prints validity in UK time and the per-region place counts', async () => {
    await mount(response([AMBER]));
    expect(screen.getByTestId('window-warning-validity')).toHaveTextContent(
      'Valid Fri 14 Aug, 12:00 to Sat 15 Aug, 08:30 (UK time)',
    );
    expect(within(screen.getByTestId('window-warning-places')).getAllByRole('listitem').map((li) => li.textContent))
      .toEqual(['Northumberland: 4 of 12 places in this area', 'The Lakes: 2 of 9 places in this area']);
  });

  it('draws the matrix with its text alternative and the tick on the warning\'s cell', async () => {
    await mount(response([AMBER]));
    expect(screen.getByRole('img', { name: 'Likelihood 3 of 4, impact 3 of 4' })).toBeInTheDocument();
    const ticked = screen.getAllByTestId('warning-matrix-cell').find((c) => c.dataset.ticked === 'true');
    expect(ticked).toHaveAttribute('data-colour', 'amber');
  });

  it('omits what it was not sent: no update block on a first issue, no empty lists, no matrix without values', async () => {
    await mount(response([warning({
      warningId: 'bare', locationIds: [7], whatToExpect: [' '], headline: null, likelihood: null, impact: null,
    })]));
    const article = screen.getByTestId('window-warning');
    expect(within(article).queryByTestId('window-warning-headline')).toBeNull();
    expect(within(article).queryByTestId('window-warning-expect')).toBeNull();
    expect(within(article).queryByTestId('window-warning-update')).toBeNull();
    expect(within(article).queryByTestId('window-warning-places')).toBeNull();
    expect(screen.queryByTestId('warning-matrix')).toBeNull();
  });

  it('does not show the update text on a warning that is not flagged updated', async () => {
    await mount(response([{ ...AMBER, updated: false }]));
    expect(screen.queryByTestId('window-warning-update')).toBeNull();
  });

  it('draws several warnings in the order given, each in full', async () => {
    await mount(response([AMBER, YELLOW]));
    const articles = screen.getAllByTestId('window-warning');
    expect(articles.map((a) => a.dataset.level)).toEqual(['AMBER', 'YELLOW']);
  });

  it('carries the attribution, the Met Office link and the what-should-I-do link', async () => {
    await mount(response([AMBER]));
    expect(screen.getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    const source = screen.getByTestId('warning-source-link');
    expect(source).toHaveAttribute('href', METOFFICE_URL);
    expect(source).toHaveAttribute('rel', 'noopener noreferrer');
    const advice = screen.getByTestId('window-warning-advice-link');
    expect(advice).toHaveAttribute('href', WHAT_SHOULD_I_DO_URL);
    expect(advice).toHaveAttribute('target', '_blank');
    expect(advice).toHaveTextContent(/^What should I do/);
  });

  it('prints the stale line beside warnings that are shown', async () => {
    await mount(response([AMBER], { stale: true }));
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

});
