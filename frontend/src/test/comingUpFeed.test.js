import { describe, it, expect } from 'vitest';
import {
  buildDateRail, chipCounts, buildEntryView, groupEntriesByMonth, buildChronology, footerCopy,
  FILTER_CHIPS, formatArrivalDate,
} from '../utils/comingUpFeed.js';

const TODAY = '2026-08-09';

/** A wire `ComingUpEntry`, in the shape P2 actually serves. */
const entry = (over = {}) => ({
  id: 'spring-tide:2026-08-16:2026-08-18',
  type: 'spring-tide',
  startDate: '2026-08-16',
  endDate: '2026-08-18',
  kind: 'ALMANAC',
  family: 'coastal',
  title: 'Spring tide run',
  kindTag: 'Almanac',
  superlative: null,
  metric: null,
  prose: null,
  facts: [],
  threshold: null,
  action: { label: 'Show coastal spots for 16 Aug →', kind: 'coastal-spots', date: '2026-08-16' },
  ...over,
});

describe('buildDateRail — a single day', () => {
  it('carries a day-of-week, the bare day number and the month', () => {
    const rail = buildDateRail('2026-09-22', '2026-09-22', TODAY);
    expect(rail.dow).toBe('Tue');
    expect(rail.day).toBe('22');
    expect(rail.month).toBe('Sept');
    expect(rail.isRange).toBe(false);
  });
});

describe('buildDateRail — a same-month span', () => {
  it('omits the day-of-week and prints a dash range for the day slot', () => {
    const rail = buildDateRail('2026-09-10', '2026-09-15', TODAY);
    expect(rail.dow).toBeNull();
    expect(rail.day).toBe('10–15');
    expect(rail.month).toBe('Sept');
    expect(rail.isRange).toBe(true);
  });
});

describe('buildDateRail — a span crossing a month', () => {
  it('uses BOTH slots — start day+month on top, end day+month (dashed) below', () => {
    // The trap this rule exists for: collapsing to "26–1 Sept" would state a date range that
    // does not exist, since the 1st is in October.
    const rail = buildDateRail('2026-09-26', '2026-10-01', TODAY);
    expect(rail.dow).toBeNull();
    expect(rail.day).toBe('26 Sept');
    expect(rail.month).toBe('–1 Oct');
    expect(rail.isRange).toBe(true);
  });

  it('never collapses the crossing to a single range string', () => {
    const rail = buildDateRail('2026-09-26', '2026-10-01', TODAY);
    expect(rail.day).not.toBe('26–1 Sept');
    expect(rail.month).not.toContain('Sept');
  });

  it('detects a crossing by year as well as by name, not just by month name', () => {
    // A same-named month a year apart ("Aug 2026" to "Aug 2027") must not read as a same-month
    // range — comparing formatted month NAMES alone would collide here.
    const rail = buildDateRail('2026-08-30', '2027-08-02', TODAY);
    expect(rail.day).toBe('30 Aug');
    expect(rail.month).toBe('–2 Aug');
  });
});

describe('buildDateRail — the countdown', () => {
  it('reads "now" for a span already under way', () => {
    expect(buildDateRail('2026-08-07', '2026-08-11', TODAY).countdown).toBe('now');
  });

  it('reads "now" for a span starting today', () => {
    expect(buildDateRail(TODAY, TODAY, TODAY).countdown).toBe('now');
  });

  it('reads "tomorrow" for a span starting tomorrow', () => {
    expect(buildDateRail('2026-08-10', '2026-08-10', TODAY).countdown).toBe('tomorrow');
  });

  it('counts the days for anything further out — the boundary either side of "tomorrow"', () => {
    expect(buildDateRail('2026-08-11', '2026-08-11', TODAY).countdown).toBe('in 2 days');
    expect(buildDateRail('2026-11-06', '2026-11-06', TODAY).countdown).toBe('in 89 days');
  });

  it('says nothing when there is no usable today', () => {
    expect(buildDateRail('2026-08-16', '2026-08-18', '').countdown).toBeNull();
  });
});

describe('chipCounts', () => {
  const COUNTS = {
    fixed: 8, forecast: 1,
    byFamily: { coastal: 4, 'night-sky': 3, 'sun-moon': 1, eclipse: 1, air: 1, dust: 2 },
  };

  it('sums every family into the All chip', () => {
    const chips = chipCounts(COUNTS);
    expect(chips.find((c) => c.id === 'all').count).toBe(12);
  });

  it('reads a plain family straight off byFamily', () => {
    expect(chipCounts(COUNTS).find((c) => c.id === 'coastal').count).toBe(4);
    expect(chipCounts(COUNTS).find((c) => c.id === 'night-sky').count).toBe(3);
  });

  it('folds eclipse into Sun & moon, per D6', () => {
    expect(chipCounts(COUNTS).find((c) => c.id === 'sun-moon').count).toBe(2);
  });

  it('folds air into Air & dust, per D6', () => {
    expect(chipCounts(COUNTS).find((c) => c.id === 'air-dust').count).toBe(3);
  });

  it('asserts the All chip equals the sum of the four family chips — the D6 invariant, given the '
      + 'families the assembler actually emits', () => {
    // This holds for any fixture built from the wire's real family set — coastal, night-sky,
    // sun-moon, eclipse, air, dust — because those six between them are exactly what the four
    // chips cover. It is not a general law for every conceivable `byFamily` (see the next test):
    // `aurora` is D6's one documented exception, and COUNTS above deliberately excludes it so this
    // pins the invariant that actually matters — the one the served payload can produce today.
    const chips = chipCounts(COUNTS);
    const all = chips.find((c) => c.id === 'all').count;
    const sumOfFamilies = chips.filter((c) => c.id !== 'all').reduce((s, c) => s + c.count, 0);
    expect(all).toBe(sumOfFamilies);
  });

  it('counts an aurora entry into All while no chip claims it — D6’s one named exception', () => {
    // `aurora` is a legal wire family with no chip of its own (unreachable in v1, plan §1.4). If
    // one ever appears, "All" must still mean all entries — undercounting a real entry would be
    // the worse failure — while no individual family chip may claim it, since D6 gave it none.
    const chips = chipCounts({ ...COUNTS, byFamily: { ...COUNTS.byFamily, aurora: 1 } });
    expect(chips.find((c) => c.id === 'all').count).toBe(13);
    const sumOfFamilies = chips.filter((c) => c.id !== 'all').reduce((s, c) => s + c.count, 0);
    expect(sumOfFamilies).toBe(12);
  });

  it('defaults every count to zero when counts has not arrived yet', () => {
    const chips = chipCounts(undefined);
    expect(chips.every((c) => c.count === 0)).toBe(true);
    expect(chips).toHaveLength(FILTER_CHIPS.length);
  });
});

describe('buildEntryView', () => {
  it('marks a plan-action entry interactive', () => {
    const view = buildEntryView(entry({ action: { label: 'See the plan for 16 Aug →', kind: 'plan', date: '2026-08-16' } }), TODAY);
    expect(view.interactive).toBe(true);
  });

  it('marks a coastal-spots action interactive — the map channel now exists (P3b, D8)', () => {
    const view = buildEntryView(entry(), TODAY);
    expect(view.interactive).toBe(true);
  });

  it('marks a dark-sky-spots action interactive for the same reason', () => {
    const view = buildEntryView(
      entry({ action: { label: 'Show dark-sky spots →', kind: 'dark-sky-spots', date: '2026-08-16' } }),
      TODAY,
    );
    expect(view.interactive).toBe(true);
  });

  it('leaves an entry with no served action kind non-interactive', () => {
    const view = buildEntryView(entry({ action: { label: 'nowhere', kind: null, date: TODAY } }), TODAY);
    expect(view.interactive).toBe(false);
  });

  it('passes tide/coincidence/joinNote through unchanged, defaulting to null when absent', () => {
    expect(buildEntryView(entry(), TODAY).tide).toBeNull();
    expect(buildEntryView(entry(), TODAY).coincidence).toBeNull();
    expect(buildEntryView(entry(), TODAY).joinNote).toBeNull();
    const tide = { range: 5.2, delta: 1.9, phase: 'HW' };
    const coincidence = [{ family: 'sun-moon', name: 'Supermoon', factsLabel: 'Mon 26 Oct' }];
    const view = buildEntryView(entry({ tide, coincidence, joinNote: 'Same cause.' }), TODAY);
    expect(view.tide).toEqual(tide);
    expect(view.coincidence).toEqual(coincidence);
    expect(view.joinNote).toBe('Same cause.');
  });

  it('marks a card feature when it has a first-of-type explanation', () => {
    expect(buildEntryView(entry({ prose: 'The moon…' }), TODAY).isFeature).toBe(true);
  });

  it('marks a card feature when it has a superlative, even with no prose', () => {
    expect(buildEntryView(entry({ superlative: 'biggest until November' }), TODAY).isFeature)
      .toBe(true);
  });

  it('leaves a plain card unmarked when it has neither', () => {
    expect(buildEntryView(entry(), TODAY).isFeature).toBe(false);
  });

  it('reads FORECAST off the kind, not off any vocabulary the client invents', () => {
    expect(buildEntryView(entry({ kind: 'FORECAST' }), TODAY).isForecast).toBe(true);
    expect(buildEntryView(entry({ kind: 'ALMANAC' }), TODAY).isForecast).toBe(false);
  });

  it('carries the rail, built against the reader’s today', () => {
    const view = buildEntryView(entry(), TODAY);
    expect(view.rail.day).toBe('16–18');
  });

  describe('isNew (plan D3/D12)', () => {
    it('is true when enteredWindow is strictly after the stored last-seen date', () => {
      const view = buildEntryView(entry({ enteredWindow: '2026-08-09' }), TODAY, '2026-08-01');
      expect(view.isNew).toBe(true);
    });

    it('is false when enteredWindow is on or before the stored last-seen date', () => {
      expect(buildEntryView(entry({ enteredWindow: '2026-08-01' }), TODAY, '2026-08-01').isNew)
        .toBe(false);
      expect(buildEntryView(entry({ enteredWindow: '2026-07-20' }), TODAY, '2026-08-01').isNew)
        .toBe(false);
    });

    it('is false when the last-seen date is null (never opened) or undefined (not yet known)', () => {
      expect(buildEntryView(entry({ enteredWindow: '2026-08-09' }), TODAY, null).isNew).toBe(false);
      expect(buildEntryView(entry({ enteredWindow: '2026-08-09' }), TODAY, undefined).isNew)
        .toBe(false);
    });
  });

  it('does not throw on a wire entry with no facts key at all', () => {
    // Real, not hypothetical: `ComingUpEntry.facts` carries `@JsonInclude(NON_EMPTY)`, so an
    // entry the assembler gave no facts OMITS the key entirely rather than sending `[]`.
    const wire = entry();
    delete wire.facts;
    expect(() => buildEntryView(wire, TODAY)).not.toThrow();
    expect(buildEntryView(wire, TODAY).facts).toEqual([]);
  });

  it('does not throw on a wire entry with no action key at all', () => {
    // `action` is a required field on the real schema, but a hand-built or legacy fixture missing
    // it must degrade rather than crash the whole pane on one bad entry.
    const wire = entry();
    delete wire.action;
    expect(() => buildEntryView(wire, TODAY)).not.toThrow();
    expect(buildEntryView(wire, TODAY).interactive).toBe(false);
  });
});

describe('groupEntriesByMonth', () => {
  it('groups consecutive same-month entries into one section', () => {
    const views = [
      buildEntryView(entry({ id: 'a', startDate: '2026-08-12', endDate: '2026-08-13' }), TODAY),
      buildEntryView(entry({ id: 'b', startDate: '2026-08-27', endDate: '2026-08-28' }), TODAY),
    ];
    const groups = groupEntriesByMonth(views);
    expect(groups).toHaveLength(1);
    expect(groups[0].monthLabel).toBe('Aug');
    expect(groups[0].year).toBe('2026');
    expect(groups[0].entries).toHaveLength(2);
  });

  it('opens a new section on the next month', () => {
    const views = [
      buildEntryView(entry({ id: 'a', startDate: '2026-08-27', endDate: '2026-08-28' }), TODAY),
      buildEntryView(entry({ id: 'b', startDate: '2026-09-03', endDate: '2026-09-03' }), TODAY),
    ];
    const groups = groupEntriesByMonth(views);
    expect(groups.map((g) => g.monthLabel)).toEqual(['Aug', 'Sept']);
  });

  it('groups a month-crossing run under its OWN start month, never duplicating it', () => {
    const views = [
      buildEntryView(entry({ id: 'a', startDate: '2026-09-26', endDate: '2026-10-01' }), TODAY),
      buildEntryView(entry({ id: 'b', startDate: '2026-10-03', endDate: '2026-10-03' }), TODAY),
    ];
    const groups = groupEntriesByMonth(views);
    expect(groups).toHaveLength(2);
    expect(groups[0].monthLabel).toBe('Sept');
    expect(groups[0].entries).toHaveLength(1);
    expect(groups[1].monthLabel).toBe('Oct');
  });

  it('returns nothing for an empty list', () => {
    expect(groupEntriesByMonth([])).toEqual([]);
  });
});

describe('buildChronology', () => {
  const ENTRIES = [
    entry({ id: 'a', family: 'coastal' }),
    entry({ id: 'b', family: 'night-sky', title: 'Perseids' }),
  ];

  it('keeps everything under the "all" filter', () => {
    const groups = buildChronology(ENTRIES, TODAY, 'all');
    expect(groups[0].entries).toHaveLength(2);
  });

  it('drops entries outside the active family', () => {
    const groups = buildChronology(ENTRIES, TODAY, 'coastal');
    expect(groups[0].entries).toHaveLength(1);
    expect(groups[0].entries[0].title).toBe('Spring tide run');
  });

  it('folds eclipse entries into the sun-moon filter', () => {
    const groups = buildChronology([entry({ family: 'eclipse', title: 'Deep partial eclipse' })], TODAY, 'sun-moon');
    expect(groups[0].entries).toHaveLength(1);
  });

  it('returns no groups when the filter matches nothing', () => {
    expect(buildChronology(ENTRIES, TODAY, 'air-dust')).toEqual([]);
  });

  it('returns an empty list for anything that is not an array', () => {
    expect(buildChronology(null, TODAY, 'all')).toEqual([]);
    expect(buildChronology(undefined, TODAY, 'all')).toEqual([]);
  });

  it('threads the last-seen date through to each view’s isNew (plan P5)', () => {
    const groups = buildChronology(
      [entry({ id: 'a', enteredWindow: '2026-08-09' })], TODAY, 'all', '2026-08-01',
    );
    expect(groups[0].entries[0].isNew).toBe(true);
  });
});

describe('formatArrivalDate', () => {
  it('formats a served date as day + house-form short month', () => {
    expect(formatArrivalDate('2026-09-12')).toBe('12 Sept');
  });

  it('does not pad a single-digit day', () => {
    expect(formatArrivalDate('2026-10-03')).toBe('3 Oct');
  });
});

describe('footerCopy', () => {
  it('states every date is fixed when there are no forecast entries', () => {
    const text = footerCopy({ fixed: 9, forecast: 0 });
    expect(text).toContain('Every date here is fixed in advance');
    expect(text).not.toMatch(/orbital/i);
  });

  it('states both counts, singular, when there is exactly one of each type possible', () => {
    const text = footerCopy({ fixed: 1, forecast: 1 });
    expect(text).toContain('1 of these dates is fixed');
    expect(text).toContain('1 is a forecast peak');
  });

  it('states both counts, plural, for more than one', () => {
    const text = footerCopy({ fixed: 8, forecast: 2 });
    expect(text).toContain('8 of these dates are fixed');
    expect(text).toContain('2 are forecast peaks');
  });

  it('never claims the dates come from orbital mechanics', () => {
    expect(footerCopy({ fixed: 8, forecast: 1 })).not.toMatch(/orbital/i);
  });
});
