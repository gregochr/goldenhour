import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

const state = vi.hoisted(() => ({ tab: 'map', loaded: [] }));


describe('preloadInitialTab edges', () => {
  let preloadInitialTab;

  beforeEach(async () => {
    vi.resetModules();
    // doMock afresh each test: a mock factory's result is cached, so a shared one would run only once
    // and a second, wrongly-made import would go unseen.
    vi.doMock('../utils/initialTab.js', () => ({ resolveInitialTab: () => state.tab }));
    vi.doMock('../components/WindowFirstMapPane.jsx', () => {
      state.loaded.push('map');
      return { default: () => null };
    });
    vi.doMock('../components/WindowFirstHeatStrip.jsx', () => {
      state.loaded.push('plan');
      return { default: () => null };
    });
    state.loaded.length = 0;
    state.tab = 'map';
    ({ preloadInitialTab } = await import('../utils/preloadInitialTab.js'));
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.doUnmock('../utils/initialTab.js');
    vi.doUnmock('../components/WindowFirstMapPane.jsx');
    vi.doUnmock('../components/WindowFirstHeatStrip.jsx');
  });

  const flush = async () => {
    await vi.dynamicImportSettled();
    await new Promise((resolve) => { setTimeout(resolve, 50); });
  };

  it('preloads the map chunk for a signed-in reader opening on the map', async () => {
    vi.stubGlobal('localStorage', { getItem: (k) => (k === 'goldenhour_token' ? 'tok' : null) });
    state.tab = 'map';
    preloadInitialTab();
    await flush();
    expect(state.loaded).toEqual(['map']);
  });

  it('preloads the heat strip chunk for a signed-in reader opening on the plan', async () => {
    vi.stubGlobal('localStorage', { getItem: () => 'tok' });
    state.tab = 'plan';
    preloadInitialTab();
    await flush();
    expect(state.loaded).toEqual(['plan']);
  });

  it('preloads nothing for any other tab', async () => {
    vi.stubGlobal('localStorage', { getItem: () => 'tok' });
    state.tab = 'coming-up';
    preloadInitialTab();
    await flush();
    expect(state.loaded).toEqual([]);
  });

  it('preloads nothing when there is no stored token', async () => {
    vi.stubGlobal('localStorage', { getItem: () => null });
    preloadInitialTab();
    await flush();
    expect(state.loaded).toEqual([]);
  });

  it('treats storage that throws as signed out', async () => {
    vi.stubGlobal('localStorage', { getItem: () => { throw new Error('blocked'); } });
    preloadInitialTab();
    await flush();
    expect(state.loaded).toEqual([]);
  });
});
