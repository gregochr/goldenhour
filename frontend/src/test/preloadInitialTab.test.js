import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

const mapLoaded = vi.fn();
const stripLoaded = vi.fn();
vi.mock('../components/WindowFirstMapPane.jsx', () => { mapLoaded(); return { default: () => null }; });
vi.mock('../components/WindowFirstHeatStrip.jsx', () => { stripLoaded(); return { default: () => null }; });
vi.mock('../utils/initialTab.js', () => ({ resolveInitialTab: vi.fn() }));

import { preloadInitialTab } from '../utils/preloadInitialTab.js';
import { resolveInitialTab } from '../utils/initialTab.js';

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('preloadInitialTab', () => {
  beforeEach(() => {
    localStorage.clear();
    mapLoaded.mockClear();
    stripLoaded.mockClear();
    vi.mocked(resolveInitialTab).mockReset();
  });
  afterEach(() => localStorage.clear());

  it('loads only the map chunk for the map tab', async () => {
    localStorage.setItem('goldenhour_token', 't');
    vi.mocked(resolveInitialTab).mockReturnValue('map');
    preloadInitialTab();
    await flush();
    expect(mapLoaded).toHaveBeenCalledTimes(1);
    expect(stripLoaded).not.toHaveBeenCalled();
  });

  it('loads only the heat strip chunk for the plan tab', async () => {
    localStorage.setItem('goldenhour_token', 't');
    vi.mocked(resolveInitialTab).mockReturnValue('plan');
    preloadInitialTab();
    await flush();
    expect(stripLoaded).toHaveBeenCalledTimes(1);
    expect(mapLoaded).not.toHaveBeenCalled();
  });

  it('loads nothing without a stored token', async () => {
    vi.mocked(resolveInitialTab).mockReturnValue('map');
    preloadInitialTab();
    await flush();
    expect(mapLoaded).not.toHaveBeenCalled();
    expect(stripLoaded).not.toHaveBeenCalled();
  });

  it('loads nothing for any other tab value', async () => {
    localStorage.setItem('goldenhour_token', 't');
    vi.mocked(resolveInitialTab).mockReturnValue('manage');
    preloadInitialTab();
    await flush();
    expect(mapLoaded).not.toHaveBeenCalled();
    expect(stripLoaded).not.toHaveBeenCalled();
  });

  it('does not throw when localStorage throws', () => {
    vi.mocked(resolveInitialTab).mockReturnValue('map');
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
    expect(() => preloadInitialTab()).not.toThrow();
    spy.mockRestore();
  });
});
