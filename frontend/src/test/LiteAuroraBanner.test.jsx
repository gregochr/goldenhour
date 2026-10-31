/**
 * The LITE reader's aurora banner (docs/engineering/aurora-lite-alert-plan.md §2.3 L2), driven through the
 * real provider and `AuroraBannerRoot` with only the API module mocked at the boundary: the alert's level
 * and wording, the Pro door, the per-level dismissal, the gate (quiet / inactive / ended / overcast), and
 * that a LITE reader never causes a score, location, forecast-result or viewline request. A Pro reader's
 * banner is unchanged (its own suites stay green); one control here pins that it is still the Pro banner.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, within } from '@testing-library/react';

let mockRole = 'LITE_USER';
let mockMobile = false;
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockMobile }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraAlert: vi.fn(),
  getAuroraStatus: vi.fn(),
  getAuroraLocations: vi.fn(() => Promise.resolve([])),
  getAuroraForecastResults: vi.fn(() => Promise.resolve([])),
  getAuroraViewline: vi.fn(() => Promise.resolve({ active: false })),
  getAuroraForecastViewline: vi.fn(() => Promise.resolve({ active: false })),
}));
vi.mock('../components/AuroraFieldThumb.jsx', () => ({
  default: () => <div data-testid="aurora-field-stub" />,
}));

import { focusPastGap } from './pollGap.js';
import AuroraBannerRoot from '../components/AuroraBannerRoot.jsx';
import { expandBanner } from './auroraBannerHarness.js';
import { AuroraStatusProvider } from '../context/AuroraStatusContext.jsx';
import {
  getAuroraAlert,
  getAuroraForecastResults,
  getAuroraForecastViewline,
  getAuroraLocations,
  getAuroraStatus,
  getAuroraViewline,
} from '../api/auroraApi.js';

const NOW = '2026-01-15T22:00:00Z';

const alertOf = (extra = {}) => ({
  level: 'MODERATE',
  active: true,
  triggerType: 'forecast',
  allOvercast: false,
  currentNightDate: '2026-01-15',
  currentNightEndsAt: '2026-01-16T06:00:00Z',
  ...extra,
});

async function mount() {
  await act(async () => {
    render(
      <AuroraStatusProvider>
        <AuroraBannerRoot />
      </AuroraStatusProvider>,
    );
  });
}

beforeEach(() => {
  mockRole = 'LITE_USER';
  mockMobile = false;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
  [getAuroraAlert, getAuroraStatus, getAuroraLocations, getAuroraForecastResults, getAuroraViewline,
    getAuroraForecastViewline].forEach((fn) => fn.mockClear());
  getAuroraAlert.mockResolvedValue(alertOf());
  getAuroraStatus.mockResolvedValue(null);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('LiteAuroraBanner — the card (640px and up)', () => {
  it('shows the level pill, the when-line, the title and the Pro door, with no activation', async () => {
    await mount();

    const banner = screen.getByTestId('aurora-banner');
    expect(banner).toHaveAttribute('data-layout', 'card');
    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(banner).not.toHaveAttribute('tabindex');
    expect(screen.getByTestId('aurora-banner-pill')).toHaveTextContent('Moderate');
    expect(screen.getByTestId('aurora-banner-when')).toHaveTextContent('Forecast · tonight');
    expect(screen.getByTestId('aurora-banner-title')).toHaveTextContent('Look north, low on the horizon, after dark.');
    expect(screen.getByTestId('aurora-banner-upsell-trigger')).toHaveTextContent('Aurora forecast');
    expect(screen.queryByTestId('aurora-banner-cta')).toBeNull();
    expect(screen.queryByTestId('aurora-banner-thumb')).toBeNull();
  });

  it('says "Active now" for a realtime alert and Strong for a STRONG one', async () => {
    getAuroraAlert.mockResolvedValue(alertOf({ level: 'STRONG', triggerType: 'realtime' }));
    await mount();

    expect(screen.getByTestId('aurora-banner-pill')).toHaveTextContent('Strong');
    expect(screen.getByTestId('aurora-banner-when')).toHaveTextContent('Active now');
  });

  it('keeps role="alert" on the text only: the door and the dismiss are outside the live region', async () => {
    await mount();

    const live = screen.getByRole('alert');
    expect(within(live).getByTestId('aurora-banner-pill')).toBeInTheDocument();
    expect(within(live).getByTestId('aurora-banner-when')).toBeInTheDocument();
    expect(within(live).getByTestId('aurora-banner-title')).toBeInTheDocument();
    expect(within(live).queryByTestId('aurora-banner-upsell-trigger')).toBeNull();
    expect(within(live).queryByTestId('aurora-banner-dismiss')).toBeNull();
  });

  it('opens the approved Pro door copy, with no upgrade text, and Escape closes only the popover', async () => {
    await mount();

    fireEvent.click(screen.getByTestId('aurora-banner-upsell-trigger'));
    const pop = screen.getByTestId('aurora-banner-upsell-popover');
    expect(within(pop).getByTestId('aurora-banner-upsell-heading')).toHaveTextContent('Aurora forecasts');
    expect(pop.textContent).not.toMatch(/upgrade/i);

    fireEvent.keyDown(pop, { key: 'Escape' });
    expect(screen.queryByTestId('aurora-banner-upsell-popover')).toBeNull();
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('dismisses at this level for the session and comes back on escalation', async () => {
    await mount();

    fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
    expect(screen.queryByTestId('aurora-banner')).toBeNull();

    getAuroraAlert.mockResolvedValue(alertOf({ level: 'STRONG' }));
    await act(async () => { focusPastGap(); });

    expect(screen.getByTestId('aurora-banner-pill')).toHaveTextContent('Strong');
  });
});

describe('LiteAuroraBanner — the strip (under 640px)', () => {
  beforeEach(() => { mockMobile = true; });

  it('draws the strip with the headline, the detail, the door and the dismiss', async () => {
    await mount();

    expect(screen.getByTestId('aurora-banner')).toHaveAttribute('data-layout', 'strip');
    expect(screen.getByTestId('aurora-banner-strip-headline')).toHaveTextContent('Moderate Aurora tonight');
    expect(screen.getByTestId('aurora-banner-strip-detail')).toHaveTextContent('Look north, low');
    expect(screen.getByTestId('aurora-banner-upsell-trigger')).toBeInTheDocument();
    expect(screen.getByTestId('aurora-banner-dismiss')).toBeInTheDocument();
  });

  it('says "Aurora active now" for a realtime alert and keeps the door outside the live region', async () => {
    getAuroraAlert.mockResolvedValue(alertOf({ triggerType: 'realtime' }));
    await mount();

    expect(screen.getByTestId('aurora-banner-strip-headline')).toHaveTextContent('Aurora active now');
    expect(within(screen.getByRole('alert')).queryByTestId('aurora-banner-upsell-trigger')).toBeNull();
  });
});

describe('LiteAuroraBanner — when nothing is shown', () => {
  it.each([
    ['a QUIET level', { level: 'QUIET', active: false }],
    ['a MINOR level', { level: 'MINOR' }],
    ['an inactive alert', { active: false }],
    ['an ended night', { currentNightEndsAt: '2026-01-15T05:00:00Z' }],
    ['every site overcast below STRONG', { allOvercast: true }],
  ])('draws nothing for %s', async (_name, extra) => {
    getAuroraAlert.mockResolvedValue(alertOf(extra));
    await mount();

    expect(screen.queryByTestId('aurora-banner')).toBeNull();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('shows a STRONG alert even when every site is overcast', async () => {
    getAuroraAlert.mockResolvedValue(alertOf({ level: 'STRONG', allOvercast: true }));
    await mount();

    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('draws nothing when the alert request fails (null)', async () => {
    getAuroraAlert.mockResolvedValue(null);
    await mount();

    expect(screen.queryByTestId('aurora-banner')).toBeNull();
  });
});

describe('LiteAuroraBanner — isolation', () => {
  it.each([[false], [true]])('a LITE reader (mobile %s) makes no status, scores, results or viewline request', async (mobile) => {
    mockMobile = mobile;
    await mount();
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();

    expect(getAuroraAlert).toHaveBeenCalled();
    expect(getAuroraStatus).not.toHaveBeenCalled();
    expect(getAuroraLocations).not.toHaveBeenCalled();
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
    expect(getAuroraViewline).not.toHaveBeenCalled();
    expect(getAuroraForecastViewline).not.toHaveBeenCalled();
  });

  it('a PRO reader still gets the Pro banner and never calls /alert', async () => {
    mockRole = 'PRO_USER';
    getAuroraStatus.mockResolvedValue({
      level: 'MODERATE', active: true, simulated: false, triggerType: 'forecast', description: 'Pro words',
      darkSkyLocationCount: 4, clearLocationCount: 3, currentNightEndsAt: '2026-01-16T06:00:00Z',
    });
    await mount();
    expandBanner();

    expect(screen.getByTestId('aurora-banner')).toHaveTextContent('Pro words');
    expect(screen.queryByTestId('aurora-banner-upsell-trigger')).toBeNull();
    expect(getAuroraAlert).not.toHaveBeenCalled();
  });
});
