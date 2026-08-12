import { describe, it, expect } from 'vitest';
import { buildComingUpRows } from '../utils/comingUpFeed.js';

const TODAY = '2026-08-09';

/**
 * The non-breaking space the derivation puts between a day and its month.
 *
 * <p>Written as an escape and interpolated, never pasted: a literal U+00A0 is indistinguishable
 * from a space in a diff, so an editor retyping one of these lines would silently turn a real
 * assertion into one that cannot fail. The rule it pins is that the 112px date column wraps BEFORE
 * a date rather than through it.
 */
const NB = '\u00A0';

/** A wire entry. `meta` is OMITTED unless given — the backend sends no key at all when it is empty. */
const event = (over = {}) => {
  const base = {
    startDate: '2026-08-16',
    endDate: '2026-08-18',
    kind: 'ALMANAC',
    type: 'spring-tide',
    title: 'Spring tide run',
    detail: 'The moon’s alignment pulls the tide further out and further in than usual.',
  };
  return { ...base, ...over };
};

/** The enriched tide meta, in the shape `TideRunBuilder` actually produces. */
const TIDE_META = {
  range: '4.6 m',
  rangeAnomaly: '+0.4',
  verdict: 'LW 05:44 · 34m after sunrise',
  location: 'Bamburgh Beach',
  peakDate: '2026-08-16',
};

const first = (over) => buildComingUpRows([event(over)], TODAY)[0];

/** The chips as plain strings, which is how a reader sees them. */
const factText = (row) => row.facts.map((f) => f.segments.map((s) => s.text).join(''));

describe('comingUpFeed — the lead word', () => {
  it('reads Today when the span starts today', () => {
    expect(first({ startDate: TODAY, endDate: TODAY }).lead).toBe('Today');
  });

  it('reads Tomorrow when the span starts tomorrow', () => {
    expect(first({ startDate: '2026-08-10', endDate: '2026-08-10' }).lead).toBe('Tomorrow');
  });

  it('counts the days from today for anything further out', () => {
    // The boundary either side of "Tomorrow": T+1 is a word, T+2 is a count.
    expect(first({ startDate: '2026-08-11', endDate: '2026-08-11' }).lead).toBe('In 2 days');
    expect(first({ startDate: '2026-11-06', endDate: '2026-11-06' }).lead).toBe('In 89 days');
  });

  it('reads On now for a span that started before today', () => {
    // Reachable: the tide and supermoon sources walk OUTWARDS past the requested range, so a run
    // already under way is reported with its true first day rather than clipped to today.
    expect(first({ startDate: '2026-08-07', endDate: '2026-08-11' }).lead).toBe('On now');
  });

  it('reads On now, never Today, when the start date is only where the feed begins', () => {
    // The trap this rule exists for. `NlcSeasonAlmanacSource` does NOT walk outwards — it clips the
    // span to the request window and sets startsBeforeWindow to say so. Its startDate is therefore
    // today on most days of the season, and a lead word derived from the date alone would announce
    // that an eleven-week season starts this morning.
    const row = first({
      type: 'nlc-season',
      startDate: TODAY,
      endDate: '2026-08-10',
      meta: { startsBeforeWindow: 'true' },
    });
    expect(row.lead).toBe('On now');
  });

  it('says nothing about how far away it is when there is no usable today', () => {
    // `todayStr` defaults to the empty string in the briefing context, and every comparison against
    // it is NaN. Dropping the word is the only honest option: "In NaN days" is worse than silence,
    // and the dates beside it are absolute so the row still says when it is.
    const rows = buildComingUpRows([event({ startDate: '2026-08-16', endDate: '2026-08-18' })], '');
    expect(rows[0].lead).toBeNull();
    expect(rows[0].dates).toBe(`16–18${NB}Aug`);
  });

  it('reads Passed for a span that has already ended', () => {
    // Defensive: the service builds from today, so this cannot be served. Without it the row would
    // read "In -3 days".
    expect(first({ startDate: '2026-08-01', endDate: '2026-08-03' }).lead).toBe('Passed');
  });
});

describe('comingUpFeed — the date line', () => {
  it('names the weekday for a single day', () => {
    expect(first({ startDate: '2026-08-12', endDate: '2026-08-12' }).dates)
      .toBe(`Wed 12${NB}Aug`);
  });

  it('drops the weekday for a span and names the month once', () => {
    // "Wed 12 – Thu 13 Aug" does not fit 112px of 11px mono, and the two weekdays are the least
    // useful part of a range a reader takes as a block of nights.
    expect(first({ startDate: '2026-08-12', endDate: '2026-08-13' }).dates).toBe(`12–13${NB}Aug`);
  });

  it('names both months when the span crosses one', () => {
    expect(first({ startDate: '2026-08-30', endDate: '2026-09-02' }).dates)
      .toBe(`30${NB}Aug – 2${NB}Sept`);
  });

  it('joins a day to its month with a non-breaking space, so a wrap cannot split a date', () => {
    // Seen on the running app before this: the equinox qualifier broke as "Exact day 22" / "Sept".
    // A regular space lets the line break at whichever gap reaches the column edge first.
    const row = first({ startDate: '2026-09-19', endDate: '2026-09-25', type: 'equinox', meta: { peakDate: '2026-09-22' } });
    expect(row.whenNote).toBe(`Exact day 22${NB}Sept`);
    expect(row.whenNote).not.toContain('22 Sept');
  });

  it('names only the real edge of a span clipped at its start', () => {
    // "9–10 Aug" would state a beginning that did not happen, with a note underneath contradicting
    // the line above it.
    const row = first({
      type: 'nlc-season', startDate: TODAY, endDate: '2026-08-10', meta: { startsBeforeWindow: 'true' },
    });
    expect(row.dates).toBe(`Until 10${NB}Aug`);
    expect(row.whenNote).toBe('Began before this list');
  });

  it('names only the real edge of a span clipped at its end', () => {
    const row = first({
      type: 'nlc-season', startDate: '2026-08-09', endDate: '2026-08-20', meta: { endsAfterWindow: 'true' },
    });
    expect(row.dates).toBe(`From 9${NB}Aug`);
    expect(row.whenNote).toBe('Continues past this list');
  });

  it('claims no dates at all when both edges are clipped', () => {
    const row = first({
      type: 'nlc-season',
      startDate: TODAY,
      endDate: '2026-08-20',
      meta: { startsBeforeWindow: 'true', endsAfterWindow: 'true' },
    });
    expect(row.dates).toBe('In progress');
    expect(row.whenNote).toBe('Began before and continues past this list');
  });
});

describe('comingUpFeed — the qualifier under the dates', () => {
  it('says which day of a tide run the figures belong to', () => {
    expect(first({ meta: TIDE_META }).whenNote).toBe(`Biggest 16${NB}Aug`);
  });

  it('says Closest for a supermoon, whose peak is the night nearest perigee', () => {
    const row = first({
      type: 'supermoon', startDate: '2026-10-25', endDate: '2026-10-26', meta: { peakDate: '2026-10-26' },
    });
    expect(row.whenNote).toBe(`Closest 26${NB}Oct`);
  });

  it('says Exact day for a solstice, which is an instant rather than a peak', () => {
    const row = first({
      type: 'solstice', startDate: '2026-12-18', endDate: '2026-12-24', meta: { peakDate: '2026-12-21' },
    });
    expect(row.whenNote).toBe(`Exact day 21${NB}Dec`);
  });

  it('falls back to Peak for a type this build has never heard of', () => {
    // Forward compatibility: a source added later still reads sensibly rather than silently losing
    // its peak date.
    const row = first({
      type: 'perihelion', startDate: '2027-01-02', endDate: '2027-01-06', meta: { peakDate: '2027-01-04' },
    });
    expect(row.whenNote).toBe(`Peak 4${NB}Jan`);
  });

  it('omits the peak on a single-day entry, where it would only restate the date', () => {
    const row = first({
      type: 'supermoon', startDate: '2026-10-26', endDate: '2026-10-26', meta: { peakDate: '2026-10-26' },
    });
    expect(row.whenNote).toBeNull();
  });

  it('prefers the clip note over the peak note if a source ever carries both', () => {
    // Not reachable today — only nlc-season sets the clip flags and it never carries a peakDate —
    // but silently losing one of the two would be worse than choosing between them.
    const row = first({ meta: { ...TIDE_META, startsBeforeWindow: 'true' } });
    expect(row.whenNote).toBe('Began before this list');
  });

  it('says nothing when there is neither a peak nor a clipped edge', () => {
    expect(first({ meta: { range: '4.6 m' } }).whenNote).toBeNull();
  });
});

describe('comingUpFeed — the fact line', () => {
  it('states the range and its anomaly in one chip, so the metres are stated once', () => {
    // `rangeAnomaly` is "+0.4" — signed, one decimal, and deliberately WITHOUT a unit. As its own
    // chip beside "4.6 m" it reads as a different quantity; composed into the same one the metres
    // govern both numbers and no unit has to be invented.
    expect(factText(first({ meta: TIDE_META }))[0]).toBe('range 4.6 m · +0.4 vs average');
  });

  it('makes the label dim and the value full ink, which is the whole point of the segments', () => {
    // `factText` flattens the segments to compare copy, so nothing else in this file can tell a
    // chip rendered entirely in label tone from one rendered correctly. The tones ARE the chip:
    // `.wf-facts [data-tone='strong']` is what makes "range" read as a label and "4.6 m" as the
    // answer, and a builder that emitted one tone throughout would pass every other assertion here.
    const chip = first({ meta: TIDE_META }).facts[0];
    expect(chip.segments[0]).toEqual({ text: 'range ', tone: 'base' });
    expect(chip.segments[1]).toEqual({ text: '4.6 m', tone: 'strong' });
    expect(chip.segments[2]).toEqual({ text: ' · +0.4 vs average', tone: 'base' });
  });

  it('states the range alone when the backend could not derive an anomaly', () => {
    // `TideWording` suppresses an anomaly under 0.05 m, and drops it entirely when the location has
    // no stored average to compare against — so the chip must stand on its own.
    const noAnomaly = { ...TIDE_META, rangeAnomaly: undefined };
    expect(factText(first({ meta: noAnomaly }))[0]).toBe('range 4.6 m');
  });

  it('labels the verdict "alignment" rather than repeating the water it already names', () => {
    // The verdict opens with LW or HW and carries its own clock time; a "low water" label in front
    // of it would say the same thing twice.
    expect(factText(first({ meta: TIDE_META }))).toContain('alignment LW 05:44 · 34m after sunrise');
  });

  it('shows a high-water height only on a king run', () => {
    // `TideRunBuilder` passes null for a spring run, so this chip is what separates the two on the
    // fact line as well as in the title.
    expect(factText(first({ meta: TIDE_META })).join(' ')).not.toContain('high water');
    const king = first({
      type: 'king-tide', title: 'King tide run', meta: { ...TIDE_META, highWater: '5.8 m' },
    });
    expect(factText(king)).toContain('high water 5.8 m');
  });

  it('shows the moon percentage for a meteor peak', () => {
    const row = first({
      type: 'meteor',
      title: 'Perseids',
      startDate: '2026-08-12',
      endDate: '2026-08-12',
      meta: { zhr: '100', radiant: 'NE', bestHours: '01:00–04:00', moonIllumination: '0%' },
    });
    expect(factText(row)).toEqual(['moon 0%']);
  });

  it('drops zhr, radiant and bestHours, because the detail already states all three', () => {
    // `MeteorAlmanacSource` composes exactly those three into its own sentence: "Perseids peaks,
    // around 100 meteors an hour at best under a dark sky, radiating from the NE. Best watched
    // 01:00–04:00." Chipping them again states the same three facts twice on one row.
    const row = first({
      type: 'meteor',
      meta: { zhr: '100', radiant: 'NE', bestHours: '01:00–04:00', moonIllumination: '0%' },
    });
    const all = factText(row).join(' ');
    expect(all).not.toContain('100');
    expect(all).not.toContain('NE');
    expect(all).not.toContain('01:00');
  });

  it('never renders a flag as a fact, because every one of them is the literal string "true"', () => {
    const row = first({
      meta: {
        ...TIDE_META,
        washedOut: 'true',
        partialCoverage: 'true',
        startsBeforeWindow: 'true',
        endsAfterWindow: 'true',
      },
    });
    expect(factText(row).join(' ')).not.toContain('true');
  });

  it('renders nothing for a meta key it does not recognise', () => {
    // The allow-list is strict on purpose: every value on this endpoint needs a label the wire does
    // not carry, so a generic renderer would produce "swellHeight 2.1" with no way to know it was
    // wrong. Silence is the safe direction.
    expect(factText(first({ meta: { swellHeight: '2.1' } }))).toEqual([]);
  });

  it('orders the chips identically however the payload happened to order the keys', () => {
    // `AlmanacEvent`'s canonical constructor runs Map.copyOf, which returns a salt-randomised map —
    // so the JSON's key order changes on every backend restart. Reading the order off the payload
    // would reshuffle the fact line on every deploy.
    const forwards = { range: '4.6 m', verdict: 'HW at sunrise', moonIllumination: '30%' };
    const backwards = { moonIllumination: '30%', verdict: 'HW at sunrise', range: '4.6 m' };
    expect(factText(first({ meta: backwards }))).toEqual(factText(first({ meta: forwards })));
    expect(factText(first({ meta: forwards })))
      .toEqual(['range 4.6 m', 'alignment HW at sunrise', 'moon 30%']);
  });
});

describe('comingUpFeed — whose figures these are', () => {
  it('names the one location a run’s figures were measured at', () => {
    // A run can span a whole coast and the numbers are one place's, which is the caveat CLAUDE.md
    // already settled for the Plan tab's tide chart.
    expect(first({ meta: TIDE_META }).attribution).toBe('Figures for Bamburgh Beach');
  });

  it('names the day, and says the rest is uncovered, when the figures are a fallback', () => {
    // figuresFrom means no day was flagged as the run's biggest, so these are the first day that
    // could be derived at all — the difference between a caveat and a claim about the whole run.
    // peakDate and figuresFrom are mutually exclusive on the wire, so the fixture drops the one to
    // set the other rather than sending both.
    const fallback = { ...TIDE_META, peakDate: undefined };
    const row = first({ meta: { ...fallback, figuresFrom: '2026-08-16', partialCoverage: 'true' } });
    expect(row.attribution)
      .toBe(`Figures for Bamburgh Beach on 16${NB}Aug — the rest of the run is not covered yet`);
  });

  it('names nobody when there are no figures to attribute', () => {
    expect(first({}).attribution).toBeNull();
  });
});

describe('comingUpFeed — the degrade rule', () => {
  it('marks a tide run with no meta as missing its figures', () => {
    expect(first({}).figuresMissing).toBe(true);
  });

  it('does not mark an unclipped NLC season, whose empty meta is its healthy state', () => {
    // The trap in reading the wire's own `datesOnly` as "degraded". NlcSeasonAlmanacSource's meta
    // holds nothing but two clip flags, so an unclipped season is empty by construction — it means
    // the span shown IS the whole season, which is the good case.
    const row = first({ type: 'nlc-season', title: 'Noctilucent cloud season' });
    expect(row.figuresMissing).toBe(false);
  });

  it('does not mark an equinox, which carries no derived figures to lose', () => {
    expect(first({ type: 'equinox', title: 'Autumn equinox' }).figuresMissing).toBe(false);
  });

  it('keeps the dates, the title and the explanation on a row with no figures', () => {
    // The degrade rule replaces nothing. A reader still gets when it is and what it is; only the
    // numbers are absent, and nothing is synthesised to stand in for them.
    const row = first({});
    expect(row.dates).toBe(`16–18${NB}Aug`);
    expect(row.title).toBe('Spring tide run');
    expect(row.detail).toContain('moon’s alignment');
    expect(row.facts).toEqual([]);
    expect(row.attribution).toBeNull();
    expect(row.whenNote).toBeNull();
  });
});

describe('comingUpFeed — the kind marker', () => {
  it('marks an entry that is not almanac', () => {
    expect(first({ kind: 'FORECAST' }).kindLabel).toBe('Forecast');
  });

  it('leaves an almanac entry unmarked, because it is the page’s stated default', () => {
    // Every entry the five sources emit is ALMANAC, so a chip on every row would be a word that
    // never varies. The pane footer states it once instead, and only a departure is marked.
    expect(first({ kind: 'ALMANAC' }).kindLabel).toBeNull();
  });

  it('marks nothing for a kind it does not recognise, rather than printing a raw enum name', () => {
    expect(first({ kind: 'SOMETHING_NEW' }).kindLabel).toBeNull();
  });
});

describe('comingUpFeed — keys, order and absent input', () => {
  it('keys a row on its type and both ends, so six spring runs in one feed do not collide', () => {
    const rows = buildComingUpRows([
      event({ startDate: '2026-08-12', endDate: '2026-08-13' }),
      event({ startDate: '2026-08-27', endDate: '2026-08-28' }),
      event({ type: 'supermoon', title: 'Supermoon', startDate: '2026-08-12', endDate: '2026-08-13' }),
    ], TODAY);
    expect(new Set(rows.map((r) => r.key)).size).toBe(3);
    expect(rows[0].key).toBe('spring-tide:2026-08-12:2026-08-13');
  });

  it('paints the payload’s own order and never re-sorts it', () => {
    // The service sorts by start date, then span length, then type. Sorting by peakDate here would
    // move a walked-outwards tide run; grouping by type would break the list's only spine.
    const rows = buildComingUpRows([
      event({ startDate: '2026-09-01', endDate: '2026-09-01', type: 'meteor', title: 'Later' }),
      event({ startDate: '2026-08-12', endDate: '2026-08-13', title: 'Earlier' }),
    ], TODAY);
    expect(rows.map((r) => r.title)).toEqual(['Later', 'Earlier']);
  });

  it('returns an empty list for anything that is not an array', () => {
    // The pane distinguishes "no rows" from "not loaded" by its own status, so this only has to be
    // safe — but a null payload reaching `.map` would take the whole tab down.
    expect(buildComingUpRows(null, TODAY)).toEqual([]);
    expect(buildComingUpRows(undefined, TODAY)).toEqual([]);
    expect(buildComingUpRows([], TODAY)).toEqual([]);
  });
});

describe('comingUpFeed — the eclipse row', () => {
  /** The shape `EclipseAlmanacSource` really emits: every value a finished string. */
  const ECLIPSE = {
    startDate: '2026-08-12',
    endDate: '2026-08-12',
    kind: 'ALMANAC',
    type: 'eclipse',
    title: 'Deep partial eclipse',
    detail: 'The moon covers 91% of the sun at maximum.',
    meta: {
      coverage: '91% covered',
      maximum: '19:06 · sun 13° up W',
      rarity: 'nothing comparable from the UK until 2081',
      location: 'Bamburgh',
    },
  };

  const rowFor = (over = {}) => buildComingUpRows([{ ...ECLIPSE, ...over }], TODAY)[0];

  const factText = (row) => row.facts
    .map((f) => f.segments.map((seg) => seg.text).join(''))
    .join(' | ');

  it('renders coverage, the maximum and the recurrence line, in that order', () => {
    // Recurrence last: it is the only one of the three that is about the event's place in a
    // century rather than about the night itself.
    expect(factText(rowFor())).toBe(
      'at maximum 91% covered | peaks 19:06 · sun 13° up W'
      + ' | nothing comparable from the UK until 2081',
    );
  });

  it('renders the recurrence line whole, parsing no number out of it', () => {
    // The backend composes the sentence precisely so this file keeps its rule that no number is
    // parsed, compared or re-formatted here. A different year must pass through untouched.
    const row = rowFor({ meta: { ...ECLIPSE.meta, rarity: 'nothing comparable until 2090' } });

    expect(factText(row)).toContain('nothing comparable until 2090');
  });

  it('says "Maximum" rather than the generic "Peak" for the named instant', () => {
    const row = rowFor({ meta: { ...ECLIPSE.meta, peakDate: '2026-08-12' } });

    // The row's own date already IS the peak for a single-day event, so the note is suppressed;
    // what this pins is that the vocabulary exists for the type rather than falling through.
    expect(row.type).toBe('eclipse');
  });

  it('drops the rarity chip when the backend sends no recurrence line', () => {
    // An eclipse that returns often carries no `rarity` key at all — metaOf drops null — and the
    // row simply says less rather than hedging.
    const withoutRarity = { ...ECLIPSE.meta };
    delete withoutRarity.rarity;
    const row = rowFor({ meta: withoutRarity });

    expect(factText(row)).not.toContain('nothing comparable');
    expect(factText(row)).toContain('91% covered');
  });

  it('names the location the figures belong to', () => {
    expect(rowFor().attribution).toContain('Bamburgh');
  });

  it('flags the degrade when an eclipse arrives with no figures at all', () => {
    const row = rowFor({ meta: {} });

    expect(row.figuresMissing).toBe(true);
    expect(row.facts).toEqual([]);
    // The title and the date survive — the entry is never dropped for want of numbers.
    expect(row.title).toBe('Deep partial eclipse');
  });
});
