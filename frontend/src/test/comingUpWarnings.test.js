/**
 * The pure half of the Coming up warning entries (weather-warnings-plan.md W3b).
 *
 * <p>What breaks if these fail: a withdrawn or expired warning lingers in the feed, a changed level disagrees with the
 * title, an id that arrives as a number never matches, the chip counts drift from the list, or an unchanged feed is
 * copied (a needless re-render of the whole pane).
 */
import { describe, it, expect } from 'vitest';
import {
  WARNING_ENTRY_FAMILY, WARNING_ENTRY_TYPE, isWarningEntry, reconcileFeed, warningsKey,
} from '../utils/comingUpWarnings.js';
import { buildEntryView } from '../utils/comingUpFeed.js';
import { response, warning } from './warningsFixture.js';

const NOW = Date.parse('2026-08-14T13:00:00Z');

const wireWarning = (over = {}) => ({
  id: 'weather-warning:w-yellow-wind',
  type: WARNING_ENTRY_TYPE,
  family: WARNING_ENTRY_FAMILY,
  kind: 'FORECAST',
  kindTag: 'Met Office warning',
  startDate: '2026-08-15',
  endDate: '2026-08-20',
  title: 'Yellow warning of wind',
  facts: [],
  meta: { warningId: 'w-yellow-wind', level: 'YELLOW' },
  action: { label: 'See the plan for 2026-08-15 →', kind: 'plan', date: '2026-08-15' },
  ...over,
});

const almanac = () => ({
  id: 'meteor:1', type: 'meteor', family: 'night-sky', kind: 'ALMANAC', startDate: '2026-08-12', endDate: '2026-08-12',
});

const served = (warnings, over = {}) => ({ ...response(warnings), ...over });

describe('isWarningEntry', () => {
  it('is true only for the warning type', () => {
    expect(isWarningEntry(wireWarning())).toBe(true);
    expect(isWarningEntry(almanac())).toBe(false);
    expect(isWarningEntry(null)).toBe(false);
    expect(isWarningEntry(undefined)).toBe(false);
  });
});

describe('warningsKey', () => {
  it('is empty while the feature is off, before an answer and for a malformed context', () => {
    expect(warningsKey(null)).toBe('');
    expect(warningsKey(undefined)).toBe('');
    expect(warningsKey({ enabled: false, warnings: [warning()] })).toBe('');
    expect(warningsKey({ enabled: true, warnings: null })).toBe('');
  });

  it('changes when an id appears, a version moves or a level moves, and not otherwise', () => {
    const a = warning({ warningId: 'a', warningVersion: '1.0', level: 'YELLOW' });
    const base = warningsKey({ enabled: true, warnings: [a] });
    expect(warningsKey({ enabled: true, warnings: [{ ...a }] })).toBe(base);
    expect(warningsKey({ enabled: true, warnings: [{ ...a, warningVersion: '2.0' }] })).not.toBe(base);
    expect(warningsKey({ enabled: true, warnings: [{ ...a, level: 'AMBER' }] })).not.toBe(base);
    expect(warningsKey({ enabled: true, warnings: [a, warning({ warningId: 'b' })] })).not.toBe(base);
    expect(warningsKey({ enabled: true, warnings: [] })).toBe('');
  });

  it('tolerates a numeric id and a missing entry', () => {
    expect(warningsKey({ enabled: true, warnings: [{ warningId: 7, warningVersion: 1, level: 'RED' }, null] }))
      .toBe('7@1@RED|undefined@undefined@undefined');
  });
});

describe('reconcileFeed', () => {
  it('returns the very same object when the feed holds no warning entry', () => {
    const events = { entries: [almanac()], counts: { fixed: 1, forecast: 0, byFamily: {} } };
    expect(reconcileFeed(events, served([warning()]), NOW)).toBe(events);
    expect(reconcileFeed(null, served([]), NOW)).toBeNull();
    const noEntries = { entries: null };
    expect(reconcileFeed(noEntries, served([]), NOW)).toBe(noEntries);
  });

  it('keeps a confirmed entry and draws its title and warning from the polled list, not the feed copy', () => {
    const polled = warning({ level: 'RED', weatherTypes: ['WIND', 'RAIN'], updated: true });
    const out = reconcileFeed({ entries: [almanac(), wireWarning()], counts: null }, served([polled]), NOW);

    expect(out.entries).toHaveLength(2);
    const kept = out.entries[1];
    expect(kept.title).toBe('Red warning of wind and rain');
    expect(kept.warning).toBe(polled);
    expect(out.counts).toBeNull();
  });

  it('drops an entry whose warning is no longer polled (cancelled) and takes it out of the counts', () => {
    const events = {
      entries: [almanac(), wireWarning()],
      counts: { fixed: 1, forecast: 1, byFamily: { warning: 1, 'night-sky': 1 } },
    };

    const out = reconcileFeed(events, served([]), NOW);

    expect(out.entries).toEqual([almanac()]);
    expect(out.counts).toEqual({ fixed: 1, forecast: 0, byFamily: { warning: 0, 'night-sky': 1 } });
    // The wire payload is never mutated.
    expect(events.entries).toHaveLength(2);
  });

  it('never takes a count below zero, and leaves byFamily alone when it has no warning key', () => {
    const out = reconcileFeed(
      { entries: [wireWarning()], counts: { fixed: 0, forecast: 0, byFamily: {} } }, served([]), NOW,
    );
    expect(out.counts).toEqual({ fixed: 0, forecast: 0, byFamily: {} });
    const lowFamily = reconcileFeed(
      { entries: [wireWarning()], counts: { fixed: 0, forecast: 3 } }, served([]), NOW,
    );
    expect(lowFamily.counts).toEqual({ fixed: 0, forecast: 2, byFamily: {} });
    const floors = reconcileFeed(
      { entries: [wireWarning()], counts: { forecast: 0, byFamily: { warning: 0 } } }, served([]), NOW,
    );
    expect(floors.counts).toEqual({ forecast: 0, byFamily: { warning: 0 } });
    const noForecast = reconcileFeed({ entries: [wireWarning()], counts: { byFamily: {} } }, served([]), NOW);
    expect(noForecast.counts.forecast).toBe(0);
  });

  it('drops every warning entry while the feature is off or before the first poll answers', () => {
    const events = { entries: [wireWarning()], counts: { fixed: 0, forecast: 1, byFamily: { warning: 1 } } };
    const off = { enabled: false, warnings: [] };
    expect(reconcileFeed(events, off, NOW).entries).toEqual([]);
    expect(reconcileFeed(events, undefined, NOW).entries).toEqual([]);
    expect(reconcileFeed(events, { enabled: true, warnings: null }, NOW).entries).toEqual([]);
  });

  it('drops an entry whose polled validity has passed (time-driven expiry), keeps an unreadable end', () => {
    const expired = warning({ validTo: '2026-08-14T12:59:59Z' });
    const open = warning({ validTo: null });
    const events = { entries: [wireWarning()], counts: null };

    expect(reconcileFeed(events, served([expired]), NOW).entries).toEqual([]);
    expect(reconcileFeed(events, served([open]), NOW).entries).toHaveLength(1);
    expect(reconcileFeed(events, served([warning({ validTo: '2026-08-14T13:00:00Z' })]), NOW).entries).toEqual([]);
  });

  it('matches ids as strings, so a numeric polled id still meets a string entry id', () => {
    const events = { entries: [wireWarning({ meta: { warningId: '42' } })], counts: null };
    expect(reconcileFeed(events, served([warning({ warningId: 42 })]), NOW).entries).toHaveLength(1);
  });

  it('drops an entry with no id, a blank id or a warning with no level to print', () => {
    const noId = wireWarning({ meta: {} });
    const blank = wireWarning({ meta: { warningId: '' } });
    const noMeta = wireWarning({ meta: undefined });
    expect(reconcileFeed({ entries: [noId, blank, noMeta], counts: null }, served([warning()]), NOW).entries)
      .toEqual([]);
    const levelless = warning({ level: '' });
    expect(reconcileFeed({ entries: [wireWarning()], counts: null }, served([levelless]), NOW).entries).toEqual([]);
  });
});

describe('buildEntryView for a warning entry', () => {
  it('has no map door, no day chips and is not a button, whatever the served plan action would build', () => {
    const polled = warning();
    const [entry] = reconcileFeed({ entries: [wireWarning()], counts: null }, served([polled]), NOW).entries
      .map((e) => e);
    const view = buildEntryView(entry, '2026-08-14', null, ['2026-08-14', '2026-08-15']);

    expect(view.warning).toBe(polled);
    expect(view.interactive).toBe(false);
    expect(view.dayChips).toBeNull();
    expect(view.rangeScale).toBeNull();
    expect(view.doorNoun).toBeNull();
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
    expect(view.isForecast).toBe(true);
    expect(view.isNew).toBe(false);
    expect(view.family).toBe('warning');
  });

  it('adds nothing to a non-warning entry', () => {
    const view = buildEntryView(almanac(), '2026-08-14', null, null);
    expect('warning' in view).toBe(false);
  });

  it('carries a null warning when a warning entry was never reconciled', () => {
    expect(buildEntryView(wireWarning(), '2026-08-14', null, null).warning).toBeNull();
  });
});
