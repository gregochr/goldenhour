/**
 * Met Office warning entries in the Coming up pane (weather-warnings-plan.md W3b).
 *
 * <p>What breaks if these fail: a withdrawn warning lingers on the pane, the stale feed reads as an all-clear, the
 * flag-off pane prints a warning or its attribution, the entry disagrees with the polled level, the attribution or the
 * Met Office link goes missing, or a warning card becomes a rating or a map door.
 */
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';

vi.mock('../api/warningsApi.js', () => ({ getWarnings: vi.fn() }));
vi.mock('../context/GuideContext.jsx', async () => (await import('./guideContextMock.js')).guideContextMock);

import { getWarnings } from '../api/warningsApi.js';
import { WarningsProvider } from '../context/WarningsContext.jsx';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';
import { ATTRIBUTION, METOFFICE_URL, response, warning } from './warningsFixture.js';

const TODAY = '2026-08-14';

const wireWarning = (over = {}) => ({
  id: 'weather-warning:w-yellow-wind',
  type: 'weather-warning',
  family: 'warning',
  kind: 'FORECAST',
  kindTag: 'Met Office warning',
  startDate: '2026-08-15',
  endDate: '2026-08-20',
  enteredWindow: TODAY,
  title: 'Yellow warning of wind',
  detail: 'Strong winds',
  facts: [],
  interim: true,
  meta: { warningId: 'w-yellow-wind', level: 'YELLOW' },
  action: { label: 'See the plan for 2026-08-15 →', kind: 'plan', date: '2026-08-15' },
  ...over,
});

const meteor = () => ({
  id: 'meteor:2026-08-12:2026-08-12',
  type: 'meteor',
  startDate: '2026-08-14',
  endDate: '2026-08-14',
  kind: 'ALMANAC',
  family: 'night-sky',
  title: 'Perseids',
  kindTag: 'Almanac',
  prose: null,
  facts: [],
  action: { label: 'Show dark-sky spots →', kind: 'dark-sky-spots', date: '2026-08-14' },
});

const FEED = {
  entries: [meteor(), wireWarning()],
  counts: { fixed: 1, forecast: 1, byFamily: { 'night-sky': 1, warning: 1 } },
};

const POLLED = warning({
  warningId: 'w-yellow-wind',
  headline: 'Strong winds across the north',
  validFrom: '2026-08-15T06:00:00Z',
  validTo: '2026-08-20T18:00:00Z',
  whatToExpect: ['Large waves are likely', 'Some roads may close'],
  whatShouldIDo: 'Avoid exposed coasts.',
  furtherDetails: 'Winds ease on Thursday.',
});

const mount = async (served, events = FEED, props = {}) => {
  getWarnings.mockResolvedValue(served);
  const onGoToPlan = vi.fn();
  const result = render(
    <WarningsProvider>
      <WindowFirstComingUp
        id="p"
        labelledBy="t"
        status="ready"
        events={events}
        todayStr={TODAY}
        onRetry={vi.fn()}
        onGoToPlan={onGoToPlan}
        onShowOnMap={vi.fn()}
        {...props}
      />
    </WarningsProvider>,
  );
  await act(async () => {});
  return { ...result, onGoToPlan };
};

describe('Coming up warning entries', () => {
  beforeEach(() => {
    sessionStorage.clear();
    getWarnings.mockReset();
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-08-14T13:00:00Z'));
  });
  afterEach(() => { vi.useRealTimers(); });

  const card = () => screen.getByText('Met Office warning').closest('[data-testid="coming-up-entry"]');

  it('draws a confirmed warning with the shared chip, headline, validity, attribution and the Met Office link', async () => {
    await mount(response([POLLED]));
    const entry = card();

    expect(within(entry).getByTestId('coming-up-title')).toHaveTextContent('Yellow warning of wind');
    const chip = within(entry).getByTestId('warning-chip');
    expect(chip).toHaveTextContent('Yellow warning of wind');
    expect(chip).toHaveAttribute('href', METOFFICE_URL);
    expect(within(entry).getByTestId('window-warning-headline')).toHaveTextContent('Strong winds across the north');
    expect(within(entry).getByTestId('window-warning-validity')).toHaveTextContent(/Valid .* \(UK time\)/);
    expect(within(entry).getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(within(entry).getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
    // The card is neither a rating nor a map door: no stars, no day chips, not a button.
    expect(within(entry).queryByTestId('coming-up-day-chips')).toBeNull();
    expect(within(entry).getByTestId('coming-up-card').tagName).toBe('DIV');
    expect(within(entry).queryByTestId('coming-up-action')).toBeNull();
  });

  it('shows the warning in full when open: every expect line, advice, further details and the advice link', async () => {
    await mount(response([POLLED]));
    const entry = card();

    const lines = within(within(entry).getByTestId('window-warning-expect')).getAllByRole('listitem');
    expect(lines.map((li) => li.textContent)).toEqual(['Large waves are likely', 'Some roads may close']);
    expect(within(entry).getByTestId('window-warning-advice')).toHaveTextContent('Avoid exposed coasts.');
    expect(within(entry).getByTestId('window-warning-further')).toHaveTextContent('Winds ease on Thursday.');
    expect(within(entry).getByTestId('window-warning-advice-link')).toHaveAttribute(
      'href', 'https://www.metoffice.gov.uk/weather/warnings-and-advice/seasonal-advice',
    );
    // The chip is drawn once: the full body does not repeat it.
    expect(within(entry).getAllByTestId('warning-chip')).toHaveLength(1);
  });

  it('a closed card still carries the coloured chip, Updated, the raise note and the attribution', async () => {
    const polled = { ...POLLED, level: 'AMBER', updated: true, upgradedFrom: 'YELLOW' };
    await mount(response([polled]));
    fireEvent.click(within(card()).getByTestId('coming-up-toggle'));
    const entry = card();

    expect(entry).toHaveAttribute('data-open', 'false');
    expect(within(entry).getByTestId('warning-chip')).toHaveAttribute('data-level', 'AMBER');
    expect(within(entry).getByTestId('warning-chip-updated')).toBeInTheDocument();
    expect(within(entry).getByTestId('coming-up-warning-raised')).toHaveTextContent('Raised from Yellow');
    expect(within(entry).getByTestId('warning-attribution')).toHaveTextContent(ATTRIBUTION);
    expect(within(entry).getByTestId('warning-source-link')).toHaveAttribute('href', METOFFICE_URL);
    // The full text and the plan door wait for the card to be opened.
    expect(within(entry).queryByTestId('window-warning-expect')).toBeNull();
    expect(within(entry).queryByTestId('coming-up-warning-plan-door')).toBeNull();
  });

  it('is never flagged New, even when it entered the window after the reader last looked', async () => {
    await mount(response([POLLED]), FEED, { comingUpLastSeenDate: '2026-08-01' });

    expect(within(card()).queryByTestId('coming-up-new-flag')).toBeNull();
  });

  it('opens the plan from the card when the warning reaches the scored days', async () => {
    const { onGoToPlan } = await mount(response([POLLED]));

    fireEvent.click(within(card()).getByTestId('coming-up-warning-plan-door'));

    expect(onGoToPlan).toHaveBeenCalledWith('2026-08-15');
  });

  it('has no plan door for a warning wholly beyond the scored days', async () => {
    const beyond = { entries: [wireWarning({ action: { label: 'Read it on the Met Office site →', kind: 'met-office', date: null } })], counts: null };
    await mount(response([POLLED]), beyond);

    expect(within(card()).queryByTestId('coming-up-warning-plan-door')).toBeNull();
    expect(within(card()).getByTestId('warning-chip')).toBeInTheDocument();
  });

  it('follows the polled level and the Updated flag, not the feed copy, and says when a level was raised', async () => {
    const polled = warning({
      warningId: 'w-yellow-wind', level: 'RED', updated: true, upgradedFrom: 'AMBER', headline: 'Violent winds',
      updateDescription: 'Raised after the model run.', validTo: '2026-08-20T18:00:00Z',
    });
    await mount(response([polled]));
    const entry = card();

    expect(within(entry).getByTestId('coming-up-title')).toHaveTextContent('Red warning of wind');
    expect(within(entry).getByTestId('warning-chip')).toHaveAttribute('data-level', 'RED');
    expect(within(entry).getByTestId('warning-chip-updated')).toBeInTheDocument();
    expect(within(entry).getByTestId('coming-up-warning-raised')).toHaveTextContent('Raised from Amber');
    expect(within(entry).getByTestId('window-warning-update')).toHaveTextContent('Raised after the model run.');
  });

  it('a warning the poll no longer lists (cancelled) leaves the pane and the All count', async () => {
    await mount(response([]));

    expect(screen.queryByText('Met Office warning')).toBeNull();
    expect(screen.queryByTestId('warning-attribution')).toBeNull();
    expect(screen.getByText('Perseids')).toBeInTheDocument();
    const all = screen.getAllByTestId('coming-up-chip').find((chip) => chip.dataset.chip === 'all');
    expect(all).toHaveTextContent('1');
  });

  it('with the feature off nothing is printed: no entry, no attribution, no stale line', async () => {
    await mount({ enabled: false, warnings: [], stale: true, lastCheckedAt: null });

    expect(screen.queryByText('Met Office warning')).toBeNull();
    expect(screen.queryByTestId('warning-footer')).toBeNull();
    expect(screen.queryByTestId('warning-stale')).toBeNull();
  });

  it('a refused poll prints nothing either, and never an all-clear', async () => {
    await mount(null);

    expect(screen.queryByText('Met Office warning')).toBeNull();
    expect(screen.queryByTestId('warning-footer')).toBeNull();
    expect(screen.queryByText(/no warnings|all clear/i)).toBeNull();
  });

  it('a stale feed prints the stale line once at the top, keeps the stored warning, and says no all-clear', async () => {
    await mount(response([POLLED], { stale: true, lastCheckedAt: '2026-08-14T12:10:00Z' }));

    const stale = screen.getAllByTestId('warning-stale');
    expect(stale).toHaveLength(1);
    expect(stale[0]).toHaveTextContent(/Warnings last checked/);
    expect(within(screen.getByTestId('warning-stale')).getByTestId('warning-stale-link'))
      .toHaveAttribute('href', METOFFICE_URL);
    expect(screen.getByText('Met Office warning')).toBeInTheDocument();
    expect(screen.queryByText(/no warnings|all clear/i)).toBeNull();
  });

  it('a stale feed with no warning entry still says so, so an empty list is not read as an all-clear', async () => {
    await mount(response([], { stale: true, lastCheckedAt: null }), { entries: [meteor()], counts: null });

    expect(screen.getByTestId('warning-stale')).toHaveTextContent('Warnings have not been checked yet');
    expect(screen.queryByText('Met Office warning')).toBeNull();
  });

  it('a fresh feed with nothing to show prints no warning text at all', async () => {
    await mount(response([]), { entries: [meteor()], counts: null });

    expect(screen.queryByTestId('warning-footer')).toBeNull();
    expect(screen.queryByText(/warning/i, { ignore: '[data-testid="coming-up-footer"]' })).toBeNull();
  });

  it('an expired polled warning does not linger even if the poll still lists it', async () => {
    await mount(response([warning({ warningId: 'w-yellow-wind', validTo: '2026-08-14T12:00:00Z' })]));

    expect(screen.queryByText('Met Office warning')).toBeNull();
  });

  it('while loading nothing about warnings is printed', async () => {
    await mount(response([POLLED]), null, { status: 'loading' });

    expect(screen.queryByTestId('warning-footer')).toBeNull();
    expect(screen.queryByText('Met Office warning')).toBeNull();
  });

  it('a stale line is not printed over an errored feed', async () => {
    await mount(response([], { stale: true }), null, { status: 'error' });

    expect(screen.queryByTestId('warning-stale')).toBeNull();
  });
});
