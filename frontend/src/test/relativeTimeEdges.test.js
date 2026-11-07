import { describe, it, expect } from 'vitest';
import { formatRelativeAge } from '../utils/relativeTime.js';

const NOW = Date.parse('2026-11-02T12:00:00Z');
const agoMs = (ms) => new Date(NOW - ms).toISOString();

describe('formatRelativeAge — the one-minute boundary', () => {
  it('says just now under a minute and 1m ago from a full minute', () => {
    expect(formatRelativeAge(agoMs(29_000), { now: NOW })).toBe('just now');
    expect(formatRelativeAge(agoMs(60_000), { now: NOW })).toBe('1m ago');
    expect(formatRelativeAge(agoMs(60_000), { now: NOW, verbose: true })).toBe('1 min ago');
    expect(formatRelativeAge(agoMs(29_000), { now: NOW, verbose: true })).toBe('Just now');
  });

  it('allows a minute of clock skew into the future, and gives no age beyond that', () => {
    expect(formatRelativeAge(agoMs(-60_000), { now: NOW })).toBe('just now');
    expect(formatRelativeAge(agoMs(-120_000), { now: NOW })).toBeNull();
  });

  it('moves from minutes to hours and days', () => {
    expect(formatRelativeAge(agoMs(59 * 60_000), { now: NOW })).toBe('59m ago');
    expect(formatRelativeAge(agoMs(60 * 60_000), { now: NOW })).toBe('1h ago');
    expect(formatRelativeAge(agoMs(25 * 3_600_000), { now: NOW })).toBe('1d ago');
  });

  it('is null for nothing or an unparseable instant', () => {
    expect(formatRelativeAge(null, { now: NOW })).toBeNull();
    expect(formatRelativeAge('garbage', { now: NOW })).toBeNull();
  });
});
