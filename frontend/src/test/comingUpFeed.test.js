import { describe, it, expect } from 'vitest';
import {
  buildDateRail, chipCounts, buildEntryView, groupEntriesByMonth, buildChronology, footerCopy,
  FILTER_CHIPS, formatArrivalDate, MAX_CHIP_DAYS,
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

describe('buildEntryView — a map door needs a colour forecast for its date', () => {
  // The feed is 90 days and the reader holds a few days of forecast. A door dated beyond them opened
  // a map of unscored pins, so it is withheld and the card says why in place of the served label.
  const COASTAL = { label: 'Show coastal spots for 11 Oct →', kind: 'coastal-spots', date: '2026-10-11' };
  const DARK_SKY = { label: 'Show dark-sky spots for 11 Oct →', kind: 'dark-sky-spots', date: '2026-10-11' };

  // An OVER-CAP entry (the months-long NLC season's shape): the only map entry that still takes the
  // single, withheld-or-live door — every short or single-day one gets day chips (§11.25, §11.26).
  const overCap = (action) => entry({ startDate: '2026-09-01', endDate: '2026-12-31', action });
  const PLAN = { label: 'See the plan for 11 Oct →', kind: 'plan', date: '2026-10-11' };

  it('keeps a coastal door live, with no note, when its date is in the forecast dates', () => {
    const view = buildEntryView(overCap(COASTAL), TODAY, null, ['2026-10-10', '2026-10-11']);
    expect(view.interactive).toBe(true);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
  });

  it('keeps a dark-sky door live, with no note, when its date is in the forecast dates', () => {
    const view = buildEntryView(overCap(DARK_SKY), TODAY, null, ['2026-10-11']);
    expect(view.interactive).toBe(true);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
  });

  it('withholds a coastal door whose date has no forecast, and names the door and the date', () => {
    const view = buildEntryView(overCap(COASTAL), TODAY, null, ['2026-10-07', '2026-10-08']);
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(true);
    expect(view.actionNote).toBe('Coastal spots: no forecast for 11 Oct');
  });

  it('withholds a dark-sky door whose date has no forecast, naming its own door', () => {
    const view = buildEntryView(
      overCap({ ...DARK_SKY, date: '2026-10-21' }), TODAY, null, ['2026-10-07'],
    );
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(true);
    expect(view.actionNote).toBe('Dark-sky spots: no forecast for 21 Oct');
  });

  it('withholds on membership of the exact date — a gap in the list is not covered by its neighbours', () => {
    // Separates `includes(date)` from "some listed date is on or after it" and "from a range".
    const view = buildEntryView(overCap(COASTAL), TODAY, null, ['2026-10-10', '2026-10-12']);
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(true);
  });

  it('withholds when every listed date is later than the action date', () => {
    const view = buildEntryView(overCap(COASTAL), TODAY, null, ['2026-10-12']);
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(true);
  });

  it('still carries the served action untouched on a withheld view — the card prints the note, '
      + 'but nothing about the wire entry is rewritten', () => {
    const view = buildEntryView(overCap(COASTAL), TODAY, null, []);
    expect(view.action).toEqual(COASTAL);
  });

  it('treats an empty forecast-date list as KNOWN to be empty — every map door is withheld', () => {
    const view = buildEntryView(overCap(COASTAL), TODAY, null, []);
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(true);
  });

  it('leaves the door live when the forecast dates are undefined — not known yet is no claim', () => {
    const view = buildEntryView(overCap(COASTAL), TODAY, null, undefined);
    expect(view.interactive).toBe(true);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
  });

  it('leaves the door live when the forecast dates are null — the forecast has not loaded', () => {
    const view = buildEntryView(overCap(DARK_SKY), TODAY, null, null);
    expect(view.interactive).toBe(true);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
  });

  it('never withholds a plan door, whatever the list holds — its destination is the tab, not a date', () => {
    // Over the cap, so the single plan line is the path under test (a short one gets chips, §11.26).
    const view = buildEntryView(
      entry({ startDate: '2026-09-01', endDate: '2026-12-31', action: PLAN }), TODAY, null, [],
    );
    expect(view.interactive).toBe(true);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
  });

  it('does not make a kind-less action live just because its date has a forecast', () => {
    const view = buildEntryView(
      entry({ action: { label: 'nowhere', kind: null, date: '2026-10-11' } }), TODAY, null, ['2026-10-11'],
    );
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(false);
  });

  it('degrades to a date-less note, not "Invalid Date", when a map action carries no date', () => {
    const view = buildEntryView(
      entry({ startDate: '2026-10-11', endDate: '2026-10-11', action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: undefined } }),
      TODAY, null, ['2026-10-11'],
    );
    expect(view.actionNote).toBe('Coastal spots: no forecast');
  });

  it('formats the note’s date in the house short form (a September date reads Sept, a single '
      + 'digit has no padding)', () => {
    const view = buildEntryView(
      overCap({ ...COASTAL, date: '2026-09-03' }), TODAY, null, [],
    );
    expect(view.actionNote).toBe('Coastal spots: no forecast for 3 Sept');
  });
});

describe('buildEntryView — a short run gets a door per day (plan §11.25)', () => {
  const RUN_ACTION = { label: 'Show coastal spots for 11 Oct →', kind: 'coastal-spots', date: '2026-10-11' };
  /** The tide run on the card: 9–14 Oct, peaking on the 11th. */
  const run = (over = {}) => entry({
    startDate: '2026-10-09', endDate: '2026-10-14', action: RUN_ACTION, ...over,
  });
  const chipsOf = (view) => view.dayChips;

  it('builds one chip per day of the run, in order, with the weekday and bare day number', () => {
    const chips = chipsOf(buildEntryView(run(), '2026-10-07', null, null));
    expect(chips.map((c) => [c.date, c.dow, c.day]))
      .toEqual([
        ['2026-10-09', 'Fri', '9'], ['2026-10-10', 'Sat', '10'], ['2026-10-11', 'Sun', '11'],
        ['2026-10-12', 'Mon', '12'], ['2026-10-13', 'Tue', '13'], ['2026-10-14', 'Wed', '14'],
      ]);
  });

  it('flags the peak on the served action date and on no other chip', () => {
    const chips = chipsOf(buildEntryView(run(), '2026-10-07', null, null));
    expect(chips.filter((c) => c.peak).map((c) => c.date)).toEqual(['2026-10-11']);
  });

  it('flags today on the reader’s today when it falls inside the run, and nowhere otherwise', () => {
    const underWay = chipsOf(buildEntryView(run(), '2026-10-09', null, null));
    expect(underWay.filter((c) => c.today).map((c) => c.date)).toEqual(['2026-10-09']);
    const before = chipsOf(buildEntryView(run(), '2026-10-07', null, null));
    expect(before.some((c) => c.today)).toBe(false);
  });

  it('carries a month word only on the first chip of a new month, for a run that crosses one', () => {
    const view = buildEntryView(
      run({ startDate: '2026-09-30', endDate: '2026-10-03',
        action: { ...RUN_ACTION, date: '2026-10-01' } }),
      '2026-09-28', null, null,
    );
    expect(chipsOf(view).map((c) => [c.day, c.monthWord]))
      .toEqual([['30', null], ['1', 'Oct'], ['2', null], ['3', null]]);
  });

  it('carries no month word on any chip of a run inside one month', () => {
    const chips = chipsOf(buildEntryView(run(), '2026-10-07', null, null));
    expect(chips.every((c) => c.monthWord === null)).toBe(true);
  });

  it('gives every chip a full date label, month included, for the accessible name', () => {
    const chips = chipsOf(buildEntryView(run(), '2026-10-07', null, null));
    expect(chips[0].dateLabel).toBe('Fri 9 Oct');
    expect(chips[5].dateLabel).toBe('Wed 14 Oct');
  });

  it('makes every chip live while the forecast dates are not known yet (null or undefined)', () => {
    for (const unknown of [null, undefined]) {
      const chips = chipsOf(buildEntryView(run(), '2026-10-07', null, unknown));
      expect(chips.map((c) => c.live)).toEqual([true, true, true, true, true, true]);
    }
  });

  it('makes a chip live exactly when its own date is among the forecast dates', () => {
    const chips = chipsOf(
      buildEntryView(run(), '2026-10-07', null, ['2026-10-07', '2026-10-09', '2026-10-10']),
    );
    expect(chips.map((c) => [c.date, c.live])).toEqual([
      ['2026-10-09', true], ['2026-10-10', true], ['2026-10-11', false],
      ['2026-10-12', false], ['2026-10-13', false], ['2026-10-14', false],
    ]);
  });

  it('makes every chip dimmed when the forecast dates are known to be empty', () => {
    const chips = chipsOf(buildEntryView(run(), '2026-10-07', null, []));
    expect(chips.some((c) => c.live)).toBe(false);
  });

  it('turns the card off as a control and withholds nothing — each chip carries its own state', () => {
    const view = buildEntryView(run(), '2026-10-07', null, []);
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
    expect(view.doorNoun).toBe('Coastal spots');
  });

  it('keys the rule on the door kind, never the family: a short dark-sky run gets chips too', () => {
    const view = buildEntryView(entry({
      family: 'night-sky',
      startDate: '2026-10-20',
      endDate: '2026-10-22',
      action: { label: 'Show dark-sky spots →', kind: 'dark-sky-spots', date: '2026-10-21' },
    }), '2026-10-07', null, null);
    expect(chipsOf(view).map((c) => c.date)).toEqual(['2026-10-20', '2026-10-21', '2026-10-22']);
    expect(view.doorNoun).toBe('Dark-sky spots');
  });

  it('gives a seven-day plan solstice seven chips, the peak on the action date, door noun All spots', () => {
    const view = buildEntryView(entry({
      type: 'solstice',
      family: 'sun-moon',
      startDate: '2026-12-18',
      endDate: '2026-12-24',
      action: { label: 'See the plan for 21 Dec →', kind: 'plan', date: '2026-12-21' },
    }), '2026-12-10', null, null);
    expect(view.dayChips.map((c) => c.date)).toEqual([
      '2026-12-18', '2026-12-19', '2026-12-20', '2026-12-21', '2026-12-22', '2026-12-23', '2026-12-24',
    ]);
    expect(view.dayChips.filter((c) => c.peak).map((c) => c.date)).toEqual(['2026-12-21']);
    expect(view.doorNoun).toBe('All spots');
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(false);
  });

  it('gives a coastal-family entry with a plan action chips named for the plan door, not the family', () => {
    const view = buildEntryView(run({
      action: { label: 'See the plan for 9 Oct →', kind: 'plan', date: '2026-10-09' },
    }), '2026-10-07', null, null);
    expect(view.dayChips).toHaveLength(6);
    expect(view.doorNoun).toBe('All spots');
  });

  // A single-day map entry (a meteor shower, a supermoon night) gets a one-box row (§11.26).
  const meteor = (over = {}) => entry({
    type: 'meteor',
    family: 'night-sky',
    startDate: '2026-10-21',
    endDate: '2026-10-21',
    action: { label: 'Show dark-sky spots for 21 Oct →', kind: 'dark-sky-spots', date: '2026-10-21' },
    ...over,
  });

  it('gives a single-day dark-sky entry exactly one chip, which is its peak', () => {
    const view = buildEntryView(meteor(), '2026-10-07', null, null);
    expect(view.dayChips).toHaveLength(1);
    expect(view.dayChips[0]).toEqual({
      date: '2026-10-21', dow: 'Wed', day: '21', monthWord: null, dateLabel: 'Wed 21 Oct',
      today: false, gone: false, peak: true, live: true,
    });
    expect(view.doorNoun).toBe('Dark-sky spots');
    expect(view.interactive).toBe(false);
    expect(view.actionWithheld).toBe(false);
    expect(view.actionNote).toBeNull();
  });

  it('gives a single-day coastal entry a chip too — the kind decides, not the family', () => {
    const view = buildEntryView(meteor({
      family: 'coastal',
      action: { label: 'Show coastal spots →', kind: 'coastal-spots', date: '2026-10-21' },
    }), '2026-10-07', null, null);
    expect(view.dayChips).toHaveLength(1);
    expect(view.doorNoun).toBe('Coastal spots');
  });

  it('flags a single-day chip today when it is today, and gone when it has passed', () => {
    expect(buildEntryView(meteor(), '2026-10-21', null, null).dayChips[0])
      .toMatchObject({ today: true, gone: false, live: true });
    expect(buildEntryView(meteor(), '2026-10-22', null, ['2026-10-21']).dayChips[0])
      .toMatchObject({ today: false, gone: true, live: false });
  });

  it('makes a single-day chip live three-valued: unknown live, listed live, unlisted dimmed', () => {
    expect(buildEntryView(meteor(), '2026-10-07', null, undefined).dayChips[0].live).toBe(true);
    expect(buildEntryView(meteor(), '2026-10-07', null, ['2026-10-21']).dayChips[0].live).toBe(true);
    expect(buildEntryView(meteor(), '2026-10-07', null, ['2026-10-08']).dayChips[0].live).toBe(false);
    expect(buildEntryView(meteor(), '2026-10-07', null, []).dayChips[0].live).toBe(false);
  });

  it('does not call a single-day chip the peak when the action names a different date', () => {
    const view = buildEntryView(meteor({
      action: { label: 'x', kind: 'dark-sky-spots', date: '2026-10-22' },
    }), '2026-10-07', null, null);
    expect(view.dayChips).toHaveLength(1);
    expect(view.dayChips[0].peak).toBe(false);
  });

  it('gives a single-day plan entry (an eclipse) one chip, live by the same lookup as any other', () => {
    const eclipse = meteor({
      type: 'eclipse',
      family: 'eclipse',
      action: { label: 'See the plan for 21 Oct →', kind: 'plan', date: '2026-10-21' },
    });
    const unlisted = buildEntryView(eclipse, '2026-10-07', null, []);
    expect(unlisted.dayChips).toHaveLength(1);
    expect(unlisted.dayChips[0]).toMatchObject({ peak: true, live: false });
    expect(unlisted.doorNoun).toBe('All spots');
    expect(unlisted.interactive).toBe(false);
    expect(buildEntryView(eclipse, '2026-10-07', null, ['2026-10-21']).dayChips[0].live).toBe(true);
  });

  it('keeps a plan entry over the cap on its single plan line', () => {
    const view = buildEntryView(meteor({
      startDate: '2026-09-01',
      endDate: '2026-12-31',
      action: { label: 'See the plan for 21 Oct →', kind: 'plan', date: '2026-10-21' },
    }), '2026-10-07', null, []);
    expect(view.dayChips).toBeNull();
    expect(view.interactive).toBe(true);
  });

  it('keeps a single-day entry whose action carries no date on its single line', () => {
    const view = buildEntryView(meteor({
      action: { label: 'Show dark-sky spots →', kind: 'dark-sky-spots', date: undefined },
    }), '2026-10-07', null, ['2026-10-21']);
    expect(view.dayChips).toBeNull();
    expect(view.actionWithheld).toBe(true);
    expect(view.actionNote).toBe('Dark-sky spots: no forecast');
  });

  it('leaves the over-cap season on its single withheld door', () => {
    const view = buildEntryView(meteor({
      startDate: '2026-05-25',
      endDate: '2026-08-10',
      action: { label: 'Show dark-sky spots for 15 Jun →', kind: 'dark-sky-spots', date: '2026-06-15' },
    }), '2026-05-20', null, []);
    expect(view.dayChips).toBeNull();
    expect(view.actionWithheld).toBe(true);
    expect(view.actionNote).toBe('Dark-sky spots: no forecast for 15 Jun');
  });

  it('gives a run of exactly ten days chips, and of eleven days none — the cap is inclusive', () => {
    // Tide runs can run past a week (the almanac source walks up to ten days each side), and a run
    // over the cap would silently fall back to the single peak door — the defect chips fix.
    expect(MAX_CHIP_DAYS).toBe(10);
    const ten = buildEntryView(
      run({ startDate: '2026-10-09', endDate: '2026-10-18' }), '2026-10-07', null, null,
    );
    expect(ten.dayChips).toHaveLength(10);
    const eleven = buildEntryView(
      run({ startDate: '2026-10-09', endDate: '2026-10-19' }), '2026-10-07', null, [],
    );
    expect(eleven.dayChips).toBeNull();
    // Over the cap it is the single, gated door exactly as before: the NLC season's shape.
    expect(eleven.actionWithheld).toBe(true);
    expect(eleven.interactive).toBe(false);
  });

  it('gives a three-night supermoon chips — a short dark-sky entry is a run like any other', () => {
    const view = buildEntryView(entry({
      type: 'supermoon',
      family: 'sun-moon',
      startDate: '2026-11-23',
      endDate: '2026-11-25',
      action: { label: 'Show dark-sky spots for 24 Nov →', kind: 'dark-sky-spots', date: '2026-11-24' },
    }), '2026-11-20', null, null);
    expect(view.dayChips.map((c) => c.date)).toEqual(['2026-11-23', '2026-11-24', '2026-11-25']);
    expect(view.dayChips.find((c) => c.peak).date).toBe('2026-11-24');
    expect(view.doorNoun).toBe('Dark-sky spots');
  });

  it('gives no chips, and never an empty list, for a reversed span', () => {
    const view = buildEntryView(
      run({ startDate: '2026-10-14', endDate: '2026-10-09' }), '2026-10-07', null, null,
    );
    expect(view.dayChips).toBeNull();
  });

  it('gives no chips, and never an empty list, for an unparseable date', () => {
    expect(buildEntryView(run({ startDate: 'garbage' }), '2026-10-07', null, null).dayChips).toBeNull();
    expect(buildEntryView(run({ endDate: '2026-13-45' }), '2026-10-07', null, null).dayChips).toBeNull();
  });

  it('marks a day before today gone, and never live — even when the forecast dates list it', () => {
    // The forecast window reaches two days back, so a passed day of a run under way is listed.
    const chips = buildEntryView(
      run(), '2026-10-11', null, ['2026-10-09', '2026-10-10', '2026-10-11', '2026-10-12'],
    ).dayChips;
    expect(chips.map((c) => [c.date, c.gone, c.live])).toEqual([
      ['2026-10-09', true, false], ['2026-10-10', true, false], ['2026-10-11', false, true],
      ['2026-10-12', false, true], ['2026-10-13', false, false], ['2026-10-14', false, false],
    ]);
  });

  it('marks a gone day gone and not live when the forecast dates are not known yet', () => {
    const chips = buildEntryView(run(), '2026-10-11', null, null).dayChips;
    expect(chips.map((c) => [c.gone, c.live])).toEqual([
      [true, false], [true, false], [false, true], [false, true], [false, true], [false, true],
    ]);
  });

  it('leaves today itself neither gone nor dimmed by the clock', () => {
    const today = buildEntryView(run(), '2026-10-09', null, null).dayChips[0];
    expect(today.today).toBe(true);
    expect(today.gone).toBe(false);
    expect(today.live).toBe(true);
  });

  it('keeps a months-long dark-sky season on its single door', () => {
    const view = buildEntryView(entry({
      startDate: '2026-05-25',
      endDate: '2026-08-10',
      action: { label: 'Show dark-sky spots for 1 Jun →', kind: 'dark-sky-spots', date: '2026-06-01' },
    }), '2026-05-20', null, ['2026-05-20']);
    expect(view.dayChips).toBeNull();
    expect(view.actionWithheld).toBe(true);
  });
});

describe('buildEntryView', () => {
  it('marks a plan-action entry interactive', () => {
    // Over the chip cap: a dated plan entry of up to ten days gets day chips instead (§11.26).
    const view = buildEntryView(entry({
      startDate: '2026-06-01',
      endDate: '2026-12-31',
      action: { label: 'See the plan for 16 Aug →', kind: 'plan', date: '2026-08-16' },
    }), TODAY);
    expect(view.interactive).toBe(true);
    expect(view.dayChips).toBeNull();
  });

  it('marks a coastal-spots action interactive — the map channel now exists (P3b, D8) — once the '
      + 'reader holds a forecast for its date', () => {
    const view = buildEntryView(entry({ startDate: '2026-06-01', endDate: '2026-12-31' }), TODAY, null, ['2026-08-16']);
    expect(view.interactive).toBe(true);
  });

  it('marks a dark-sky-spots action interactive for the same reason', () => {
    const view = buildEntryView(
      entry({
        startDate: '2026-06-01',
        endDate: '2026-12-31',
        action: { label: 'Show dark-sky spots →', kind: 'dark-sky-spots', date: '2026-08-16' },
      }),
      TODAY,
      null,
      ['2026-08-16'],
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

  it('passes aside through unchanged, defaulting to null when absent (L5, plan §2.8)', () => {
    expect(buildEntryView(entry(), TODAY).aside).toBeNull();
    const view = buildEntryView(
      entry({ aside: 'No filter needed — bracket, the shadow is ~10 stops under the lit edge' }),
      TODAY,
    );
    expect(view.aside).toBe('No filter needed — bracket, the shadow is ~10 stops under the lit edge');
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

describe('buildChronology — the forecast dates reach every entry view', () => {
  const MAP_ENTRIES = [
    entry({ id: 'a', startDate: '2026-09-01', endDate: '2026-12-31',
      action: { label: 'x', kind: 'coastal-spots', date: '2026-10-08' } }),
    entry({ id: 'b', startDate: '2026-09-01', endDate: '2026-12-31',
      action: { label: 'y', kind: 'coastal-spots', date: '2026-10-20' } }),
  ];
  const views = (groups) => groups.flatMap((g) => g.entries);

  it('withholds only the entry whose date is missing from the list it was handed', () => {
    const [near, far] = views(buildChronology(MAP_ENTRIES, '2026-10-07', 'all', null, ['2026-10-08']));
    expect(near.interactive).toBe(true);
    expect(far.interactive).toBe(false);
    expect(far.actionNote).toBe('Coastal spots: no forecast for 20 Oct');
  });

  it('withholds every map door when the list handed through is empty', () => {
    expect(views(buildChronology(MAP_ENTRIES, '2026-10-07', 'all', null, [])).map((v) => v.interactive))
      .toEqual([false, false]);
  });

  it('leaves every map door live when no list is handed through — not known yet', () => {
    expect(views(buildChronology(MAP_ENTRIES, '2026-10-07', 'all')).map((v) => v.interactive))
      .toEqual([true, true]);
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
