import { describe, it, expect } from 'vitest';
import {
  ALL_REGIONS, FAILURE_THRESHOLD, STATUS_FILTERS, attentionSummary, bortleBlurb, failureThresholdWord, inScope,
  isAutoDisabled, lightWords, locationMeta, locationRowLabel, locationStatus, locationSummary, needsAttention,
  passesStatus, passesType, regionFromHash, statusSeverity, tideWords, typeWords,
} from '../utils/locationAdmin.js';

const ON = { id: 1, name: 'Durham', enabled: true, consecutiveFailures: 0, locationType: ['LANDSCAPE'] };

describe('needsAttention and isAutoDisabled', () => {
  it('counts a failing place, a turned-off place and a turned-off place whose counter was reset', () => {
    expect(needsAttention({ ...ON, consecutiveFailures: 1 })).toBe(true);
    expect(needsAttention({ ...ON, enabled: false, consecutiveFailures: 3, disabledReason: 'x' })).toBe(true);
    expect(needsAttention({ ...ON, enabled: false, consecutiveFailures: 0, disabledReason: 'x' })).toBe(true);
  });

  it('ignores a stale reason on a place that is on: it has no box and no action', () => {
    expect(needsAttention({ ...ON, disabledReason: 'old reason' })).toBe(false);
    expect(isAutoDisabled({ ...ON, disabledReason: 'old reason' })).toBe(false);
    expect(passesStatus({ ...ON, disabledReason: 'old reason' }, STATUS_FILTERS.ATTENTION)).toBe(false);
  });

  it('does not count a healthy place or one an admin switched off by hand', () => {
    expect(needsAttention(ON)).toBe(false);
    expect(needsAttention({ ...ON, enabled: false })).toBe(false);
    expect(needsAttention({ ...ON, consecutiveFailures: null })).toBe(false);
  });

  it('is auto-disabled only when it is off AND carries a reason', () => {
    expect(isAutoDisabled({ ...ON, enabled: false, disabledReason: 'x' })).toBe(true);
    expect(isAutoDisabled({ ...ON, enabled: false })).toBe(false);
    expect(isAutoDisabled({ ...ON, disabledReason: 'x' })).toBe(false);
  });

  it('keeps the failure threshold at the server\'s three, and says it as a word', () => {
    expect(FAILURE_THRESHOLD).toBe(3);
    expect(failureThresholdWord()).toBe('third');
  });
});

describe('locationStatus', () => {
  it('maps each state to its dot and words', () => {
    expect(locationStatus(ON)).toMatchObject({ tone: 'go', label: 'Active' });
    expect(locationStatus({ ...ON, consecutiveFailures: 1 })).toMatchObject({ tone: 'amber', label: '1 failed run' });
    expect(locationStatus({ ...ON, consecutiveFailures: 2 })).toMatchObject({ tone: 'amber', label: '2 failed runs' });
    expect(locationStatus({ ...ON, enabled: false })).toMatchObject({ tone: 'off', label: 'Disabled' });
    expect(locationStatus({ ...ON, enabled: false, disabledReason: 'x' }))
      .toMatchObject({ tone: 'coral', label: 'Disabled after failures' });
  });

  it('does not call a switched-off place with a failure count "failed runs" (off wins)', () => {
    expect(locationStatus({ ...ON, enabled: false, consecutiveFailures: 2 }).label).toBe('Disabled');
  });
});

describe('statusSeverity', () => {
  it('orders Active < Disabled < failed runs (by count, numerically) < Disabled after failures', () => {
    const ranked = [
      statusSeverity({ ...ON, consecutiveFailures: 2 }),
      statusSeverity({ ...ON, consecutiveFailures: 10 }),
      statusSeverity({ ...ON, enabled: false, disabledReason: 'x', consecutiveFailures: 3 }),
      statusSeverity({ ...ON, enabled: false }),
      statusSeverity(ON),
    ];
    expect(ranked[1]).toBeGreaterThan(ranked[0]);
    expect(ranked[2]).toBeGreaterThan(ranked[1]);
    expect(ranked[0]).toBeGreaterThan(ranked[3]);
    expect(ranked[3]).toBeGreaterThan(ranked[4]);
  });
});

describe('the words a row and a panel show', () => {
  it('lists type labels in the catalogue order, falls back to Landscape, and keeps an unknown type visible', () => {
    expect(typeWords(['WOODLAND', 'LANDSCAPE'])).toBe('Landscape, Woodland');
    expect(typeWords([])).toBe('Landscape');
    expect(typeWords(undefined)).toBe('Landscape');
    expect(typeWords(['BLUEBELL'])).toBe('Bluebell');
    expect(typeWords(['MYSTERY'])).toBe('MYSTERY');
  });

  it('reads light and tides as words', () => {
    expect(lightWords(['SUNSET', 'ALLDAY'])).toBe('Sunset, All day');
    expect(lightWords(undefined)).toBe('Sunrise, Sunset');
    expect(tideWords(['LOW', 'HIGH'])).toBe('High, Low');
    expect(tideWords([])).toBe('Not tidal');
  });

  it('describes the Bortle class', () => {
    expect(bortleBlurb(null)).toBeNull();
    expect(bortleBlurb(1)).toBe('Excellent dark sky');
    expect(bortleBlurb(4)).toBe('Moderate, aurora-friendly');
    expect(bortleBlurb(5)).toBe('Light-polluted');
  });

  it('builds the card line, with Tides only for a seascape', () => {
    expect(locationSummary({ ...ON, bortleClass: 3 })).toBe('Landscape · Bortle 3');
    expect(locationSummary({ ...ON, locationType: ['SEASCAPE'], tideType: ['HIGH', 'LOW'], bortleClass: null }))
      .toBe('Seascape · Tides H L · Bortle —');
  });

  it('builds the row label and the panel meta', () => {
    const loc = { ...ON, subRegionName: 'Wear Valley', mistProne: true };
    expect(locationRowLabel(loc)).toBe('Durham, Wear Valley, Landscape, mist-prone, Active');
    expect(locationMeta(loc)).toBe('Landscape · Wear Valley · Active');
    expect(locationMeta(ON)).toBe('Landscape · Active');
  });
});

describe('attentionSummary', () => {
  it('is null when nothing needs attention', () => {
    expect(attentionSummary([ON])).toBeNull();
  });

  it('names the first failing and the first turned-off place alphabetically, whatever else needs attention', () => {
    const summary = attentionSummary([
      { ...ON, name: 'Zed', consecutiveFailures: 1 },
      { ...ON, name: 'Amble', consecutiveFailures: 2 },
      { ...ON, name: 'Yarm', enabled: false, consecutiveFailures: 3, disabledReason: 'x' },
      { ...ON, name: 'Bamburgh', enabled: false, consecutiveFailures: 0, disabledReason: 'x' },
    ]);

    expect(summary.count).toBe(4);
    expect(summary.headline).toBe('4 locations need attention.');
    expect(summary.failing).toEqual({ name: 'Amble', text: 'has failed 2 runs in a row.' });
    expect(summary.turnedOff).toEqual({ name: 'Bamburgh', text: 'was turned off after failed runs.' });
  });

  it('uses the singular', () => {
    const summary = attentionSummary([{ ...ON, consecutiveFailures: 1 }]);

    expect(summary.headline).toBe('1 location needs attention.');
    expect(summary.failing.text).toBe('has failed 1 run in a row.');
    expect(summary.turnedOff).toBeNull();
  });
});

describe('the filters', () => {
  const sub = { ...ON, subRegionName: 'Wear Valley', region: { id: 4 } };

  it('searches the name or the sub-region, in any case', () => {
    expect(inScope(sub, 'durh', ALL_REGIONS)).toBe(true);
    expect(inScope(sub, 'wear', ALL_REGIONS)).toBe(true);
    expect(inScope(sub, 'nope', ALL_REGIONS)).toBe(false);
    expect(inScope(ON, '', ALL_REGIONS)).toBe(true);
  });

  it('filters by region id', () => {
    expect(inScope(sub, '', '4')).toBe(true);
    expect(inScope(sub, '', '5')).toBe(false);
    expect(inScope(ON, '', '4')).toBe(false);
  });

  it('counts a location under each type it holds', () => {
    const both = { ...ON, locationType: ['LANDSCAPE', 'WOODLAND'] };
    expect(passesType(both, 'WOODLAND')).toBe(true);
    expect(passesType(both, 'SEASCAPE')).toBe(false);
    expect(passesType(both, '')).toBe(true);
  });

  it('passes the status chips', () => {
    const off = { ...ON, enabled: false };
    expect(passesStatus(off, STATUS_FILTERS.DISABLED)).toBe(true);
    expect(passesStatus(ON, STATUS_FILTERS.DISABLED)).toBe(false);
    expect(passesStatus({ ...ON, consecutiveFailures: 1 }, STATUS_FILTERS.ATTENTION)).toBe(true);
    expect(passesStatus(off, STATUS_FILTERS.ATTENTION)).toBe(false);
    expect(passesStatus(off, STATUS_FILTERS.NONE)).toBe(true);
  });
});

describe('regionFromHash', () => {
  it('reads the region a Regions link carries', () => {
    expect(regionFromHash('#manage/locations?region=3')).toBe('3');
  });

  it('is null for any other hash, another tab, or a non-numeric region', () => {
    expect(regionFromHash('#manage/locations')).toBeNull();
    expect(regionFromHash('#manage/locations/12')).toBeNull();
    expect(regionFromHash('#manage/locations/12?region=3')).toBe('3');
    expect(regionFromHash('#manage/tides?region=3')).toBeNull();
    expect(regionFromHash('#manage/locations?region=abc')).toBeNull();
    expect(regionFromHash('#other?region=3')).toBeNull();
    expect(regionFromHash('')).toBeNull();
  });
});
