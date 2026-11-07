import { describe, it, expect, vi, beforeEach } from 'vitest';

async function loadGuide() {
  vi.resetModules();
  return import('../utils/guide.js');
}

describe('guide tables', () => {
  beforeEach(() => {
    vi.resetModules();
  });

  it('names every guide page', async () => {
    const mod = await loadGuide();
    expect(mod.GUIDE_PAGES).toEqual({
      index: 'Guide',
      'getting-started': 'Getting started',
      'reading-the-forecast': 'Reading the forecast',
      'at-a-glance': 'At a Glance',
      'coming-up': 'Coming up',
      map: 'Map',
      'night-sky': 'Aurora and night sky',
      'ask-photocast': 'Ask PhotoCast',
      settings: 'Settings',
    });
  });

  it('maps each tab to its guide page', async () => {
    const mod = await loadGuide();
    expect(mod.TAB_GUIDE).toEqual({ plan: 'at-a-glance', 'coming-up': 'coming-up', map: 'map' });
  });

  it('lists every page and section the app opens', async () => {
    const mod = await loadGuide();
    expect(mod.GUIDE_SECTIONS).toEqual({
      lens: { page: 'at-a-glance', section: 'lens' },
      bestBet: { page: 'at-a-glance', section: 'bestbet' },
      fixed: { page: 'reading-the-forecast', section: 'fixed' },
      colour: { page: 'reading-the-forecast', section: 'colour' },
      fiery: { page: 'reading-the-forecast', section: 'fiery' },
      comingUpNew: { page: 'coming-up', section: 'new' },
      comingUpTopics: { page: 'coming-up', section: 'topics' },
      comingUpTides: { page: 'coming-up', section: 'tides' },
      mapWindow: { page: 'map', section: 'window' },
      mapFilters: { page: 'map', section: 'filters' },
      mapHotTopics: { page: 'map', section: 'hot-topics' },
      mapTide: { page: 'map', section: 'tide' },
      auroraAlerts: { page: 'night-sky', section: 'alerts' },
      auroraMap: { page: 'night-sky', section: 'aurora-map' },
      askTyped: { page: 'ask-photocast', section: 'typed' },
      subRegions: { page: 'settings', section: 'sub-regions' },
      settingsDrive: { page: 'settings', section: 'drive' },
      settingsRadius: { page: 'settings', section: 'radius' },
    });
  });

  it('freezes the tables and every section entry', async () => {
    const mod = await loadGuide();
    expect(Object.isFrozen(mod.GUIDE_PAGES)).toBe(true);
    expect(Object.isFrozen(mod.TAB_GUIDE)).toBe(true);
    expect(Object.isFrozen(mod.GUIDE_SECTIONS)).toBe(true);
    for (const entry of Object.values(mod.GUIDE_SECTIONS)) {
      expect(Object.isFrozen(entry)).toBe(true);
    }
  });

  it('builds URLs from the loaded tables', async () => {
    const mod = await loadGuide();
    expect(mod.guideUrl('map', 'filters')).toBe(`${mod.GUIDE_ORIGIN}/guide/map.html#filters`);
    expect(mod.guideUrl('nope')).toBe(`${mod.GUIDE_ORIGIN}/guide/index.html`);
  });
});

describe('guideUrl fragment', () => {
  it('strips only a leading hash, keeping a later one', async () => {
    const mod = await loadGuide();
    expect(mod.guideUrl('map', 'a#b')).toBe(`${mod.GUIDE_ORIGIN}/guide/map.html#a#b`);
    expect(mod.guideUrl('map', '#a#b')).toBe(`${mod.GUIDE_ORIGIN}/guide/map.html#a#b`);
    expect(mod.guideUrl('map', 'x', { embed: true })).toBe(`${mod.GUIDE_ORIGIN}/guide/map.html?embed=1#x`);
  });
});

describe('pageIdFromMessage', () => {
  it('drops a trailing .html only', async () => {
    const mod = await loadGuide();
    expect(mod.pageIdFromMessage('map.html')).toBe('map');
    expect(mod.pageIdFromMessage('map')).toBe('map');
    expect(mod.pageIdFromMessage('')).toBe('index');
    expect(mod.pageIdFromMessage('.htmlmap')).toBeNull();
    expect(mod.pageIdFromMessage('map.html.html')).toBeNull();
    expect(mod.pageIdFromMessage(5)).toBeNull();
  });
});

describe('resolveGuideOrigin', () => {
  it('falls back to the default for an empty, missing or malformed value', async () => {
    const mod = await loadGuide();
    expect(mod.resolveGuideOrigin('', false)).toBe('https://photocast.online');
    expect(mod.resolveGuideOrigin(undefined, false)).toBe('https://photocast.online');
    expect(mod.resolveGuideOrigin('::not a url', false)).toBe('https://photocast.online');
  });

  it('normalises a valid origin and refuses plain http in production', async () => {
    const mod = await loadGuide();
    expect(mod.resolveGuideOrigin('http://localhost:4000/guide/', false)).toBe('http://localhost:4000');
    expect(mod.resolveGuideOrigin('http://localhost:4000', true)).toBe('https://photocast.online');
    expect(mod.resolveGuideOrigin('https://guide.example.org/x', true)).toBe('https://guide.example.org');
  });
});
