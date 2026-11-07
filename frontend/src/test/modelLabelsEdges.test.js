import { describe, it, expect, beforeEach, vi } from 'vitest';

describe('modelLabels edges', () => {
  let modelLabel;

  beforeEach(async () => {
    vi.resetModules();
    ({ modelLabel } = await import('../utils/modelLabels.js'));
  });

  it.each([
    ['HAIKU', 'Haiku 4.5'],
    ['HAIKU_55', 'Haiku 5.5'],
    ['SONNET', 'Sonnet 4.6'],
    ['SONNET_ET', 'Sonnet 4.6 (ET)'],
    ['SONNET_55', 'Sonnet 5.5'],
    ['OPUS', 'Opus'],
    ['OPUS_ET', 'Opus (ET)'],
  ])('labels %s as %s', (model, label) => {
    expect(modelLabel(model)).toBe(label);
  });

  it('returns an unknown name as it came, an inherited property name too, and "" for none', () => {
    expect(modelLabel('MYSTERY')).toBe('MYSTERY');
    expect(modelLabel('toString')).toBe('toString');
    expect(modelLabel(null)).toBe('');
    expect(modelLabel(undefined)).toBe('');
    expect(modelLabel('')).toBe('');
  });
});
