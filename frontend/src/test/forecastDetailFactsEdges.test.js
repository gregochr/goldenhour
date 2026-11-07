import { describe, it, expect, vi } from 'vitest';

async function fresh() {
  vi.resetModules();
  return import('../utils/forecastDetailFacts.js');
}

describe('forecastDetailFacts edges', () => {
  it.each([
    ['HIGH_CLOUD', 'heavy low cloud at the solar horizon'],
    ['PRECIPITATION', 'active precipitation forecast at event time'],
    ['LOW_VISIBILITY', 'visibility too low (fog or heavy haze)'],
    ['TIDE_MISALIGNED', 'tide not aligned with location preference'],
    ['GENERIC', 'regional conditions predicted poor'],
  ])('words the %s triage stand-down', async (triageReason, words) => {
    const mod = await fresh();
    const footer = mod.generatedFooter(
      { forecastRunAt: '2026-11-07T08:00:00', triageReason }, null, false, true,
    );
    expect(footer).toBe(`Forecast generated: 7 Nov 2026 08:00 (not evaluated by Claude due to ${words})`);
  });

  it('words an unknown triage reason generically', async () => {
    const mod = await fresh();
    const footer = mod.generatedFooter(
      { forecastRunAt: '2026-11-07T08:00:00', triageReason: 'NOVEL' }, null, false, true,
    );
    expect(footer).toContain('due to regional conditions predicted poor)');
  });

  it('keeps the tide wording from a Lite reader', async () => {
    const mod = await fresh();
    const footer = mod.generatedFooter(
      { forecastRunAt: '2026-11-07T08:00:00', triageReason: 'TIDE_MISALIGNED' }, null, false, false,
    );
    expect(footer).toContain('due to regional conditions predicted poor)');
  });

  it('gives no footer for a run time that is not a time', async () => {
    const mod = await fresh();
    expect(mod.generatedFooter({ forecastRunAt: 'garbage' }, null)).toBeNull();
  });

  it('shows the bluebell badge from a score of exactly 2, and not below it', async () => {
    const mod = await fresh();
    const at2 = mod.detailBadges({ bluebellScore: 2 }, { eventType: 'SUNRISE', isPro: true });
    expect(at2.map((b) => b.key)).toContain('bluebell');
    expect(at2.find((b) => b.key === 'bluebell').tone).toBe('indigo');
    const at1 = mod.detailBadges({ bluebellScore: 1 }, { eventType: 'SUNRISE', isPro: true });
    expect(at1.map((b) => b.key)).not.toContain('bluebell');
    const lite = mod.detailBadges({ bluebellScore: 2 }, { eventType: 'SUNRISE', isPro: false });
    expect(lite.find((b) => b.key === 'bluebell').locked).toBe(true);
  });
});
