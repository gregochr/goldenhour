import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  cleanup, fireEvent, screen,
} from '@testing-library/react';
import { resetViewport } from './askViewport.js';
import { renderAskShell, shellCtx } from './askShellHarness.jsx';
import {
  NOW, readyResponse, settings, WHITBY,
} from './askFixtures.js';
import { installBackBrowser, uninstallBackBrowser } from './backTestKit.js';
import { __inspectForTests } from '../utils/dialogHistory.js';

/**
 * Back on the Plan shell's lazy dialogs while their chunks NEVER arrive (back-closes-dialogs-plan.md, PR 2b,
 * refuter amendment 6). `PlanSearch` and `LocationFourDaySheet` are replaced by components that suspend forever, so
 * the only thing that can put a layer in the store is the shell's own registration from its derived open state: a
 * registration moved inside either lazy component would leave `layers()` at 0 and fail here.
 */
vi.mock('../components/PlanSearch.jsx', () => ({
  default: () => { throw new Promise(() => {}); },
}));
vi.mock('../components/LocationFourDaySheet.jsx', () => ({
  default: () => { throw new Promise(() => {}); },
}));
vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(() => Promise.resolve({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  })),
  ALMANAC_DAYS: 90,
}));
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
import { getAskSettings, getReady } from '../api/askApi.js';

const layers = () => __inspectForTests().layers;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  localStorage.clear();
  vi.resetAllMocks();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  installBackBrowser();
});

afterEach(() => {
  cleanup();
  uninstallBackBrowser();
  vi.useRealTimers();
  vi.restoreAllMocks();
  resetViewport();
});

describe('a registration made by the shell, not by a lazy chunk', () => {
  it('Plan search registers in the commit that opens it, with its chunk still pending', async () => {
    renderAskShell({ width: 1280 });
    await screen.findByTestId('ask-field');

    fireEvent.click(screen.getByRole('button', { name: 'Search days, regions and places' }));

    expect(screen.queryByTestId('plan-search')).toBeNull();
    expect(layers()).toBe(1);
  });

  it('the location sheet registers in the commit that opens it, with its chunk still pending', async () => {
    const { rerenderShell } = renderAskShell({ width: 1280, ctx: shellCtx() });
    await screen.findByTestId('ask-field');

    rerenderShell({
      locationSheetHandoff: {
        id: WHITBY, name: 'Whitby', regionName: 'North York Moors & Coast', nonce: 1,
      },
    });

    expect(screen.queryByTestId('location-sheet')).toBeNull();
    expect(layers()).toBe(1);
  });
});
