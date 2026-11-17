/**
 * The window popup's WARNING row (weather-warnings-plan.md W3).
 *
 * <p>What breaks if these fail: warning text is truncated (the style guide wants it in full), the Updated label or
 * the update text is missing, the matrix or its text alternative is missing, the attribution or the Met Office links
 * disappear, a stale feed reads as an all-clear, the row leaks while the flag is off, a withdrawn warning lingers, or
 * the row writes into a rating.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, screen, within } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import WindowWarningRow, { WHAT_SHOULD_I_DO_URL } from '../components/WindowWarningRow.jsx';
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

const mount = async (served, card = CARD) => {
  getWarnings.mockResolvedValue(served);
  render(<WarningsProvider><WindowWarningRow card={card} /></WarningsProvider>);
  await act(async () => {});
};

describe('WindowWarningRow', () => {
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

  it('lists several warnings most severe first, each in full', async () => {
    await mount(response([YELLOW, AMBER]));
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

  it('keeps a stood-down or unscored window\'s warning: the row reads no rating', async () => {
    await mount(response([AMBER]), { ...CARD, verdict: 'STAND_DOWN', bestRating: null });
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
    expect(screen.getByTestId('window-sheet-warnings').textContent).not.toMatch(/★|stars?\b/i);
  });

  it('prints nothing for a window no warning covers on a fresh feed (and never says "no warnings")', async () => {
    await mount(response([AMBER]), { ...CARD, placeIds: [99], warningIds: [] });
    expect(screen.queryByTestId('window-sheet-warnings')).toBeNull();
    expect(document.body.textContent).not.toMatch(/no warnings/i);
  });

  it('prints only the stale admission, never an all-clear, when the feed is stale and none applies', async () => {
    await mount(response([], { stale: true }));
    const row = screen.getByTestId('window-sheet-warnings');
    expect(within(row).getByTestId('warning-stale')).toHaveTextContent('Warnings last checked');
    expect(within(row).queryByTestId('window-warning')).toBeNull();
    expect(within(row).queryByTestId('window-warning-advice-link')).toBeNull();
    expect(row.textContent).not.toMatch(/no warnings|all clear/i);
  });

  it('prints the stale line beside warnings that are shown', async () => {
    await mount(response([AMBER], { stale: true }));
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
    expect(screen.getByTestId('warning-stale')).toBeInTheDocument();
    expect(screen.getByTestId('warning-attribution')).toBeInTheDocument();
  });

  it('prints nothing while the feature is off', async () => {
    await mount({ enabled: false, warnings: [], stale: true });
    expect(screen.queryByTestId('window-sheet-warnings')).toBeNull();
  });

  it('prints nothing before the first answer, and after a refusal', async () => {
    getWarnings.mockResolvedValue(null);
    render(<WarningsProvider><WindowWarningRow card={CARD} /></WarningsProvider>);
    await act(async () => {});
    expect(screen.queryByTestId('window-sheet-warnings')).toBeNull();
  });

  it('drops a warning the next poll withdrew even though the window still names it', async () => {
    await mount(response([]), { ...CARD, warningIds: ['amber-snow'] });
    expect(screen.queryByTestId('window-warning')).toBeNull();
  });

  it('reads no role: the same row for every reader', async () => {
    await mount(response([AMBER]));
    expect(screen.getByTestId('window-warning')).toBeInTheDocument();
  });
});
