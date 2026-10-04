import { describe, it, expect } from 'vitest';
import { modelLabel } from '../utils/modelLabels.js';

describe('modelLabel', () => {
  it('maps every EvaluationModel name to its display label', () => {
    expect(modelLabel('HAIKU')).toBe('Haiku');
    expect(modelLabel('SONNET')).toBe('Sonnet 4.6');
    expect(modelLabel('SONNET_ET')).toBe('Sonnet 4.6 (ET)');
    expect(modelLabel('SONNET_55')).toBe('Sonnet 5.5');
    expect(modelLabel('OPUS')).toBe('Opus');
    expect(modelLabel('OPUS_ET')).toBe('Opus (ET)');
  });

  it('gives no two models the same label', () => {
    const names = ['HAIKU', 'SONNET', 'SONNET_ET', 'SONNET_55', 'OPUS', 'OPUS_ET'];
    expect(new Set(names.map(modelLabel)).size).toBe(names.length);
  });

  it('returns an unknown name unchanged and nothing for a missing one', () => {
    expect(modelLabel('FUTURE_9')).toBe('FUTURE_9');
    expect(modelLabel('toString')).toBe('toString');
    expect(modelLabel(null)).toBe('');
    expect(modelLabel(undefined)).toBe('');
  });
});
