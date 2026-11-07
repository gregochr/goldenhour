import { describe, it, expect, beforeEach, vi } from 'vitest';

// Loaded afresh in each test so the module-level tier tables are read during a test.
let utils;
let daysOut;
let scaleRgbaAlpha;

beforeEach(async () => {
  vi.resetModules();
  utils = await import('../utils/confidenceUtils.js');
  ({ daysOut, scaleRgbaAlpha } = utils);
});

describe('daysOut — dates that are not three numeric parts', () => {
  it('answers null when today has too few or too many parts', () => {
    expect(daysOut('2026-11-07', '2026-11')).toBeNull();
    expect(daysOut('2026-11-07', '2026-11-07-01')).toBeNull();
  });

  it('answers null when the date has too few or too many parts', () => {
    expect(daysOut('2026-11', '2026-11-07')).toBeNull();
    expect(daysOut('2026-11-07-01', '2026-11-07')).toBeNull();
  });

  it('answers null when only one part of either date is not a number', () => {
    expect(daysOut('2026-xx-07', '2026-11-07')).toBeNull();
    expect(daysOut('2026-11-07', '2026-11-xx')).toBeNull();
  });

  it('counts whole days across a month end in both directions', () => {
    expect(daysOut('2026-12-01', '2026-11-30')).toBe(1);
    expect(daysOut('2026-11-30', '2026-12-01')).toBe(-1);
  });
});

describe('scaleRgbaAlpha — which strings it will rewrite', () => {
  it('scales the alpha of a plain rgba string', () => {
    expect(scaleRgbaAlpha('rgba(10, 20, 30, 0.8)', 0.5)).toBe('rgba(10, 20, 30, 0.4)');
  });

  it('accepts whitespace around every part, including before the commas and the closing bracket', () => {
    expect(scaleRgbaAlpha('rgba( 10 , 20 , 30 , 0.8 )', 0.5)).toBe('rgba(10, 20, 30, 0.4)');
  });

  it('accepts whitespace before only the first comma', () => {
    expect(scaleRgbaAlpha('rgba(10 ,20,30,0.8)', 0.5)).toBe('rgba(10, 20, 30, 0.4)');
  });

  it('accepts whitespace before only the second comma', () => {
    expect(scaleRgbaAlpha('rgba(10,20 ,30,0.8)', 0.5)).toBe('rgba(10, 20, 30, 0.4)');
  });

  it('accepts whitespace before only the third comma', () => {
    expect(scaleRgbaAlpha('rgba(10,20,30 ,0.8)', 0.5)).toBe('rgba(10, 20, 30, 0.4)');
  });

  it('accepts whitespace before only the closing bracket', () => {
    expect(scaleRgbaAlpha('rgba(10,20,30,0.8 )', 0.5)).toBe('rgba(10, 20, 30, 0.4)');
  });

  it('leaves a string alone when something comes before or after the rgba expression', () => {
    expect(scaleRgbaAlpha('x rgba(10,20,30,0.8)', 0.5)).toBe('x rgba(10,20,30,0.8)');
    expect(scaleRgbaAlpha('rgba(10,20,30,0.8) !important', 0.5)).toBe('rgba(10,20,30,0.8) !important');
  });

  it('leaves a non-string, a full-strength scale and a non-rgba string alone', () => {
    expect(scaleRgbaAlpha(null, 0.5)).toBeNull();
    expect(scaleRgbaAlpha('rgba(10,20,30,0.8)', 1)).toBe('rgba(10,20,30,0.8)');
    expect(scaleRgbaAlpha('#ff0000', 0.5)).toBe('#ff0000');
  });
});

describe('the confidence tiers', () => {
  it('lists the tiers best first', () => {
    expect(utils.CONFIDENCE_TIERS).toEqual(['high', 'medium', 'low']);
  });

  it('gives each tier its own treatment', () => {
    expect(utils.confidenceTreatment('high')).toEqual({ fillScale: 1, provisional: false, label: 'High confidence' });
    expect(utils.confidenceTreatment('medium')).toEqual({ fillScale: 0.82, provisional: false, label: 'Medium confidence' });
    expect(utils.confidenceTreatment('low'))
      .toEqual({ fillScale: 0.5, provisional: true, label: 'Low confidence · provisional' });
    expect(utils.confidenceTreatment('bogus')).toBe(utils.confidenceTreatment('medium'));
    expect(utils.confidenceScalar('low')).toBe(0.5);
  });

  it('prefers a supplied tier, and otherwise infers from the horizon but never above medium', () => {
    expect(utils.resolveConfidence({ confidence: 'low' }, 0)).toBe('low');
    expect(utils.resolveConfidence({ confidence: 'high' }, 5)).toBe('high');
    expect(utils.resolveConfidence({}, 0)).toBe('medium');
    expect(utils.resolveConfidence({}, 3)).toBe('medium');
    expect(utils.resolveConfidence({}, 4)).toBe('low');
    expect(utils.resolveConfidence(null, null)).toBe('medium');
    expect(utils.resolveConfidence({ confidence: 'bogus' }, 9)).toBe('low');
  });

  it('maps days ahead to tiers at the boundaries', () => {
    expect(utils.horizonConfidence(1)).toBe('high');
    expect(utils.horizonConfidence(2)).toBe('medium');
    expect(utils.horizonConfidence(3)).toBe('medium');
    expect(utils.horizonConfidence(4)).toBe('low');
    expect(utils.horizonConfidence(null)).toBeNull();
  });
});
