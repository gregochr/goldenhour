import { describe, it, expect } from 'vitest';
import { buildRailTiles, indexPicks, RAIL_MAX_DAYS } from '../utils/windowFirstRail.js';

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

/** A region the roll-up will keep, with the gloss its chip reads. */
function region(name, displayVerdict, overrides = {}) {
  return {
    regionName: name,
    displayVerdict,
    verdict: displayVerdict === 'WORTH_IT' ? 'GO' : 'MARGINAL',
    summary: `terse ${name}`,
    glossHeadline: `headline ${name}`,
    glossDetail: `detail ${name}`,
    regionTemperatureCelsius: 18.4,
    regionWeatherCode: 2,
    regionWindSpeedMs: 4.1,
    scoredLocationCount: 3,
    confidence: 'high',
    slots: [{ locationName: 'Bamburgh', solarEventTime: `${TODAY}T20:11:00`, claudeRating: 4 }],
    ...overrides,
  };
}

function pick(kind, regionName) {
  return {
    kind, regionName, headline: 'Breaking clear', detail: 'The strongest window.', averageRating: 4.0,
  };
}

function summary(targetType, regions, { pickOn = null, eventTime = null } = {}) {
  return {
    targetType,
    regions,
    unregioned: [],
    window: {
      verdict: 'WORTH_IT', badges: [], ...(eventTime ? { eventTime } : {}), ...(pickOn ? { pick: pickOn } : {}),
    },
  };
}

/**
 * A day as the SERVED payload carries one — its summaries plus the backend's `peak` roll-up.
 *
 * <p>The peak is derived here so every fixture in this file is a payload production could actually
 * produce, exactly as a real `GET /api/briefing` response would be. **This mirror is not what these
 * tests exercise** — the rail no longer decides a day's band, it renders one, and the derivation
 * rule itself is pinned in `PlanWindowProjectorTest.RenderedListAndDayPeak`. Anything asserting how
 * the rail behaves for a GIVEN peak passes one explicitly through `peak` rather than relying on
 * this, so a rail bug can never be masked by a fixture that reproduces it.
 */
function day(date, eventSummaries, peak = derivePeak(eventSummaries)) {
  return { date, eventSummaries, peak };
}

/**
 * The backend's day roll-up, mirrored for fixture construction only. See `day` above.
 *
 * <p>It walks EVERY summary on the day. The real one is scoped to the rendered events, so any
 * fixture whose rendered set is narrower than its payload must state its peak explicitly.
 */
function derivePeak(eventSummaries) {
  const best = new Map();
  for (const es of eventSummaries || []) {
    for (const r of es.regions || []) {
      const dv = r.displayVerdict;
      if (dv !== 'WORTH_IT' && dv !== 'MAYBE') continue;
      const held = best.get(r.regionName);
      if (!held || (dv === 'WORTH_IT' && held.displayVerdict === 'MAYBE')) {
        best.set(r.regionName, {
          regionName: r.regionName, targetType: es.targetType, displayVerdict: dv,
        });
      }
    }
  }
  const all = [...best.values()];
  const verdict = all.some((r) => r.displayVerdict === 'WORTH_IT') ? 'WORTH_IT'
    : all.length > 0 ? 'MAYBE' : 'STAND_DOWN';
  const regions = all.filter((r) => r.displayVerdict === verdict);
  return { verdict, events: [...new Set(regions.map((r) => r.targetType))], regions };
}

/** A peak stated outright, for tests about what the rail RENDERS rather than what it is given. */
function peakOf(verdict, ...regions) {
  return {
    verdict,
    events: [...new Set(regions.map((r) => r.targetType))],
    regions,
  };
}

/** The (date, targetType) columns the rail is asked to roll up. */
function events(...pairs) {
  return pairs.map(([date, targetType]) => ({ date, targetType }));
}

describe('buildRailTiles', () => {
  it('caps the rail at four days even when the briefing carries five', () => {
    // The briefing window is five days and the rail is four, so it never implies a forecast
    // further out than the model is confident about. Five days in, four tiles out.
    const dates = ['2026-08-04', '2026-08-05', '2026-08-06', '2026-08-07', '2026-08-08'];
    const days = dates.map((d) => day(d, [summary('SUNSET', [region('N', 'WORTH_IT')])]));
    const tiles = buildRailTiles(
      events(...dates.map((d) => [d, 'SUNSET'])), days, TODAY, TOMORROW, new Set(),
    );

    expect(RAIL_MAX_DAYS).toBe(4);
    expect(tiles.map((t) => t.date)).toEqual(dates.slice(0, 4));
  });

  it('shows fewer than four days when the rendered events span fewer', () => {
    // Not a shortfall: the rail's days must be exactly the days that have windows, or a reader
    // clicks a day with no card under it. Two dates in, two tiles out.
    const days = [day(TODAY, [summary('SUNSET', [region('N', 'WORTH_IT')])]),
      day(TOMORROW, [summary('SUNRISE', [region('N', 'WORTH_IT')])])];
    const tiles = buildRailTiles(
      events([TODAY, 'SUNSET'], [TOMORROW, 'SUNRISE']), days, TODAY, TOMORROW, new Set(),
    );

    expect(tiles).toHaveLength(2);
  });

  it('marks today, and only today', () => {
    const days = [day(TODAY, [summary('SUNSET', [])]), day(TOMORROW, [summary('SUNSET', [])])];
    const tiles = buildRailTiles(
      events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
    );

    expect(tiles.map((t) => t.isToday)).toEqual([true, false]);
    expect(tiles.map((t) => t.dayLabel)).toEqual(['Today', 'Tomorrow']);
  });

  // The rail RENDERS the backend's `BriefingDay.peak`; it no longer derives one. The derivation
  // rule — which band a day reaches, which events it reached it on, a region counted once at its
  // better band — is pinned in PlanWindowProjectorTest.RenderedListAndDayPeak. What these assert is
  // the translation into what a tile shows.
  describe('the day peak, as rendered', () => {
    it('renders the region and event the peak names, not one it finds in the payload', () => {
      // The payload deliberately contradicts its own peak: "Dales" appears on BOTH events, and the
      // sunrise entry is the one a client-side roll-up would have to reject. The peak says sunset.
      // Re-derive here instead of reading it and the chip reads "Worth it sunrise", or two chips
      // appear for one region.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('Dales', 'WORTH_IT')]),
        summary('SUNSET', [region('Dales', 'WORTH_IT')]),
      ], peakOf('WORTH_IT',
        { regionName: 'Dales', targetType: 'SUNSET', displayVerdict: 'WORTH_IT' }))];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tile.peak).toBe('go');
      expect(tile.regions).toHaveLength(1);
      expect(tile.regions[0].verdictLabel).toBe('Worth it sunset');
    });

    it('drops a named region the payload does not actually carry', () => {
      // The peak names identity only; the chip needs the region object for its weather and gloss.
      // A name that resolves to nothing must vanish rather than render a chip of blanks — this is
      // the shape a region RENAME between build and serve would produce.
      const days = [day(TODAY, [summary('SUNSET', [region('Coast', 'WORTH_IT')])],
        peakOf('WORTH_IT',
          { regionName: 'Coast', targetType: 'SUNSET', displayVerdict: 'WORTH_IT' },
          { regionName: 'Renamed away', targetType: 'SUNSET', displayVerdict: 'WORTH_IT' }))];

      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.regions.map((r) => r.regionName)).toEqual(['Coast']);
      expect(tile.peak).toBe('go');
    });

    it('reads Awaiting, NOT All poor, when the payload carries no peak at all', () => {
      // A payload cached before the backend published the peak — the same one the provider's
      // degrade walk exists for. It still carries windows, so the CARDS read "Worth it" from
      // `window.verdict`; a tile reading "All poor" beside them is the contradiction this whole
      // plan removes, and it was reproduced in the browser before this guard existed.
      //
      // The summary's WORTH_IT region is deliberately present: this must not be answered by there
      // being nothing to roll up. It must be answered by there being no peak to render.
      const days = [{ date: TODAY, eventSummaries: [summary('SUNSET', [region('A', 'WORTH_IT')])] }];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.peak).toBe('awaiting');
      expect(tile.peakLabel).toBe('Awaiting');
      expect(tile.peakLabel).not.toMatch(/poor/i);
      expect(tile.regions).toEqual([]);
    });

    it('drops a peak region on an event the tile no longer draws, and says so in the label', () => {
      // The stale-payload filter, on the PARTIAL case. The peak names two Worth-it regions, one on
      // each event; the tile draws only the sunset. The band is unchanged — a survivor still holds
      // it — but the chip list and the event suffix must both narrow, or the tile reads
      // "Worth it · both" while drawing one event and offering one chip.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('Dawn', 'WORTH_IT')]),
        summary('SUNSET', [region('Dusk', 'WORTH_IT')]),
      ], peakOf('WORTH_IT',
        { regionName: 'Dawn', targetType: 'SUNRISE', displayVerdict: 'WORTH_IT' },
        { regionName: 'Dusk', targetType: 'SUNSET', displayVerdict: 'WORTH_IT' }))];

      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.peak).toBe('go');
      expect(tile.peakLabel).toBe('Worth it · sunset');
      expect(tile.regions.map((r) => r.regionName)).toEqual(['Dusk']);
    });

    it('reads Awaiting, not All poor, when every region the peak named was withdrawn', () => {
      // The case the band recompute alone gets WRONG, and the reason the third state exists. The
      // backend publishes only the regions at the TOP band — `dayPeak`'s own `atPeak` filter — so
      // when the withdrawn event carried all of them there is nothing left to fall back to. The
      // drawn sunset's card still reads "Maybe" from its own window verdict, so a tile saying "All
      // poor" beside it is a contradiction on one screen; "Awaiting" is a gap, which is what this
      // payload actually is.
      //
      // Note the fixture is producible, and deliberately so: `peak.verdict` is WORTH_IT and every
      // region it names is WORTH_IT. A peak carrying a MAYBE region under a WORTH_IT verdict is a
      // shape the projector cannot emit, and asserting against one would certify the very thing
      // that is not covered.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('Dawn', 'WORTH_IT')]),
        summary('SUNSET', [region('Dusk', 'MAYBE')]),
      ], peakOf('WORTH_IT',
        { regionName: 'Dawn', targetType: 'SUNRISE', displayVerdict: 'WORTH_IT' }))];

      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.peak).toBe('awaiting');
      expect(tile.peakLabel).toBe('Awaiting');
      expect(tile.peakLabel).not.toMatch(/poor/i);
      expect(tile.regions).toEqual([]);
    });

    it('still reads All poor when the peak is present and reached nothing', () => {
      // The other side of the boundary, and the reason the guard keys on the FIELD rather than on
      // the region list being empty: a peak that looked and found nothing is a forecast, and it
      // must keep saying so.
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN')])])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.peak).toBe('poor');
      expect(tile.peakLabel).toBe('All poor');
    });

    it('names the one good event, and says "both" when the day has two', () => {
      const oneEvent = [day(TODAY, [
        summary('SUNRISE', [region('A', 'MAYBE')]), summary('SUNSET', [region('B', 'WORTH_IT')]),
      ])];
      const twoEvents = [day(TODAY, [
        summary('SUNRISE', [region('A', 'WORTH_IT')]), summary('SUNSET', [region('B', 'WORTH_IT')]),
      ])];
      const cols = events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']);

      expect(buildRailTiles(cols, oneEvent, TODAY, TOMORROW, new Set())[0].peakLabel)
        .toBe('Worth it · sunset');
      expect(buildRailTiles(cols, twoEvents, TODAY, TOMORROW, new Set())[0].peakLabel)
        .toBe('Worth it · both');
    });

    it('reads "All poor" with no event suffix when nothing is rated', () => {
      // The suffix names which event is worth going out for. On a day with none, appending one
      // would name an event that is not being recommended.
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN'), region('B', 'STAND_DOWN')])])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.peakLabel).toBe('All poor');
      expect(tile.peak).toBe('poor');
    });

    it('never labels a tile with a count of the region roster, at any roster size', () => {
      // Plan §6 bans counts of our own data — "11 aligned is a fact about the database, not about
      // tonight". This line used to read "2 regions" on an all-poor day, counting the roster
      // gathered ABOVE the WORTH_IT/MAYBE filter, so it described a set nothing had filtered. The
      // verdict line already says "All poor", which is the honest answer; nothing replaces it.
      //
      // Sizes one and many are both here because the removed code branched on them for the
      // singular ("1 region" vs "2 regions") — that plural rule is what must not come back.
      const cols = events([TODAY, 'SUNSET']);
      const one = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN')])])];
      const many = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN'), region('B', 'STAND_DOWN')])])];
      const rated = [day(TODAY, [summary('SUNSET', [region('A', 'WORTH_IT'), region('B', 'STAND_DOWN')])])];

      expect(buildRailTiles(cols, one, TODAY, TOMORROW, new Set())[0].countLabel).toBeNull();
      expect(buildRailTiles(cols, many, TODAY, TOMORROW, new Set())[0].countLabel).toBeNull();
      expect(buildRailTiles(cols, rated, TODAY, TOMORROW, new Set())[0].countLabel).toBeNull();
    });

    it('keeps countLabel as a field for the away tile, which labels rather than counts', () => {
      // Why the field survives its only counting use being deleted: the away tile carries
      // "Not forecast" through it (pinned in the away describe block below). If a later change
      // deletes the field outright, that string has nowhere to go.
      const away = buildRailTiles(events([TODAY, 'SUNSET']), [], TODAY, TOMORROW, new Set([TODAY]));
      expect(away[0].countLabel).toBe('Not forecast');
      expect(away[0].countLabel).not.toMatch(/\d/);
    });

    it('carries no confidence on an all-poor day', () => {
      // Confidence qualifies a recommendation. A day recommending nothing has none to qualify.
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN')])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].confidence)
        .toBeNull();
    });

    it('takes the MOST confident of the peak-tier regions, whatever order they arrive in', () => {
      // The tile points at the day's best prospect, so it reads provisional only when even that
      // one does. Asserted both ways round because a reduce over an unordered list is exactly
      // where an order dependency hides.
      const lowFirst = [day(TODAY, [summary('SUNSET', [
        region('A', 'WORTH_IT', { confidence: 'low' }), region('B', 'WORTH_IT', { confidence: 'high' })])])];
      const highFirst = [day(TODAY, [summary('SUNSET', [
        region('B', 'WORTH_IT', { confidence: 'high' }), region('A', 'WORTH_IT', { confidence: 'low' })])])];
      const cols = events([TODAY, 'SUNSET']);

      expect(buildRailTiles(cols, lowFirst, TODAY, TOMORROW, new Set())[0].confidence).toBe('high');
      expect(buildRailTiles(cols, highFirst, TODAY, TOMORROW, new Set())[0].confidence).toBe('high');
    });
  });

  describe('away days', () => {
    it('renders an away day as Away, never as All poor', () => {
      // An absent forecast is not a poor one. This is the whole reason the provider fetches the
      // travel ranges at all.
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'WORTH_IT')])])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set([TODAY]),
      );

      expect(tile.isAway).toBe(true);
      expect(tile.peakLabel).toBe('✈ Away');
      expect(tile.countLabel).toBe('Not forecast');
      expect(tile.ratedCount).toBe(0);
      expect(tile.regions).toEqual([]);
    });

    it('suppresses an away day\'s pick flag', () => {
      // Flagging the best window of the forecast on a day the user is away recommends a trip they
      // have already told us they cannot take.
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'WORTH_IT')], { pickOn: pick('BEST', 'A') })])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set([TODAY]),
      );

      expect(tile.pick).toBeNull();
    });

    it('still identifies the day, and still carries its sun times', () => {
      const days = [day(TODAY, [
        summary('SUNRISE', [], { eventTime: `${TODAY}T04:15:00` }),
        summary('SUNSET', [], { eventTime: `${TODAY}T20:11:00` }),
      ])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set([TODAY]),
      );

      expect(tile.dayLabel).toBe('Today');
      expect(tile.dayNum).toBe('4');
      expect(tile.sunriseTime).toBe('05:15');
      expect(tile.sunsetTime).toBe('21:11');
    });
  });

  describe('picks come from the window projection, not from bestBets', () => {
    it('reads window.pick and ignores a contradicting bestBets entirely', () => {
      // Plan §2.3 rules bestBets out on four counts — stale-fallback prose up to 30h old, an
      // `event` that can land on no window, it runs before BriefingHonestyFilter, and it is
      // PRO-gated. `buildRailTiles` is not even given the briefing envelope, so this asserts the
      // shape of that guarantee: the only pick source is the window.
      const days = [day(TODAY, [summary('SUNSET', [region('Dales', 'WORTH_IT')], { pickOn: pick('BEST', 'Dales') })])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.pick).toEqual({ kind: 'best', event: 'sunset', targetType: 'SUNSET' });
      expect(tile.regions[0].pickKind).toBe('best');
    });

    it('names the event, because a pick is a window and a tile is a day', () => {
      // Two picks exist across the whole forecast and either may be a sunrise or a sunset. A
      // day-level BEST with no event sends the reader to a day and makes them open a card to find
      // out which half of it was meant.
      const days = [day(TODAY, [summary('SUNRISE', [region('A', 'WORTH_IT')], { pickOn: pick('ALSO', 'A') })])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNRISE']), days, TODAY, TOMORROW, new Set());

      expect(tile.pick.event).toBe('sunrise');
      expect(tile.pick.kind).toBe('also');
    });

    it('flags only the Best one when both picks land on the same day', () => {
      // Two chips would double the rail's densest element for a runner-up. The Also good still
      // carries its own chip accent on the same tile.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('A', 'WORTH_IT')], { pickOn: pick('ALSO', 'A') }),
        summary('SUNSET', [region('B', 'WORTH_IT')], { pickOn: pick('BEST', 'B') }),
      ])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tile.pick).toEqual({ kind: 'best', event: 'sunset', targetType: 'SUNSET' });
      expect(tile.regions.map((r) => [r.regionName, r.pickKind]))
        .toEqual(expect.arrayContaining([['A', 'also'], ['B', 'best']]));
    });

    it('floats the picked chips to the front, best before also', () => {
      const days = [day(TODAY, [
        summary('SUNRISE', [region('Plain', 'WORTH_IT'), region('Also', 'WORTH_IT'), region('Best', 'WORTH_IT')],
          { pickOn: pick('BEST', 'Best') }),
        summary('SUNSET', [region('Also', 'WORTH_IT')], { pickOn: pick('ALSO', 'Also') }),
      ])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tile.regions.map((r) => r.regionName)).toEqual(['Best', 'Also', 'Plain']);
    });

    it('carries no flag on the days that are neither pick, which is most of them', () => {
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'WORTH_IT')])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].pick)
        .toBeNull();
    });

    it('does not flag a pick on an event the tile never rolled up', () => {
      // Since Phase 3 both sides rank over the SAME published list, so against a fresh payload this
      // cannot happen. It is still reachable from a stale one: an SWR-cached response can sit for
      // up to 12h, and the provider's pastness guard then withdraws an event whose pick the cached
      // payload still names. Flagging it would put "◎ BEST sunrise" above a verdict line describing
      // the sunset alone.
      //
      // The peak is stated rather than derived for exactly that reason — this fixture models a
      // payload whose peak was computed when the sunrise WAS rendered and the tile no longer draws
      // it, which is a shape `derivePeak` (which walks every summary) cannot express.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('A', 'WORTH_IT')], { pickOn: pick('BEST', 'A') }),
        summary('SUNSET', [region('B', 'WORTH_IT')]),
      ], peakOf('WORTH_IT',
        { regionName: 'B', targetType: 'SUNSET', displayVerdict: 'WORTH_IT' }))];
      // Only the SUNSET column is rendered — the sunrise has already passed.
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.pick).toBeNull();
      // The chip identity survives: that region IS one of the forecast's picks, wherever it falls.
      expect(tile.regions.map((r) => r.regionName)).toEqual(['B']);
    });

    it('still flags a covered pick when the day\'s other event is uncovered', () => {
      // The counterpart: the guard must filter, not suppress. A day rendering only its sunset must
      // still flag a sunset pick.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('A', 'WORTH_IT')], { pickOn: pick('BEST', 'A') }),
        summary('SUNSET', [region('B', 'WORTH_IT')], { pickOn: pick('ALSO', 'B') }),
      ])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.pick).toEqual({ kind: 'also', event: 'sunset', targetType: 'SUNSET' });
    });

    it('does not break on a pick that falls beyond the rail\'s four days', () => {
      // Picks rank over every rendered WINDOW; the rail draws four DAYS. A pick on day five is a
      // real state, and the rail's job is to ignore it rather than to fail on it.
      const dates = ['2026-08-04', '2026-08-05', '2026-08-06', '2026-08-07', '2026-08-08'];
      const days = dates.map((d, i) => day(d, [summary('SUNSET', [region('A', 'WORTH_IT')],
        i === 4 ? { pickOn: pick('BEST', 'A') } : {})]));
      const tiles = buildRailTiles(
        events(...dates.map((d) => [d, 'SUNSET'])), days, TODAY, TOMORROW, new Set(),
      );

      expect(tiles).toHaveLength(4);
      expect(tiles.every((t) => t.pick === null)).toBe(true);
      // The chip identity still lands, because that region IS one of the forecast's two picks.
      expect(tiles[0].regions[0].pickKind).toBe('best');
    });
  });

  describe('the pick chip\'s day label (screen-reader disambiguation for a repeated pick)', () => {
    it('labels the chip with its own day when the same picked region repeats across rendered days', () => {
      // Settled weather is the ordinary case, not the edge case: the same region is often rated
      // WORTH_IT on more than one of the rendered days, and `pickKind` is assigned by region NAME
      // for the whole window (`indexPicks`), so both tiles' chips carry it. Without a day label
      // both would announce "Best bet" identically.
      const days = [
        day(TODAY, [summary('SUNSET', [region('Dales', 'WORTH_IT')], { pickOn: pick('BEST', 'Dales') })]),
        day(TOMORROW, [summary('SUNSET', [region('Dales', 'WORTH_IT')])]),
      ];
      const tiles = buildRailTiles(
        events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tiles[0].regions[0].pickDayLabel).toBe('Today');
      expect(tiles[1].regions[0].pickDayLabel).toBe('Tomorrow');
    });

    it('leaves it null when the picked region is rated on only one rendered day', () => {
      // The ordinary single-day case needs no disambiguation — appending a day label
      // unconditionally would claim one is needed when it is not.
      const days = [day(TODAY, [summary('SUNSET', [region('Dales', 'WORTH_IT')], { pickOn: pick('BEST', 'Dales') })])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.regions[0].pickKind).toBe('best');
      expect(tile.regions[0].pickDayLabel).toBeNull();
    });

    it('leaves a plain chip\'s day label null even when that region repeats across days', () => {
      const days = [
        day(TODAY, [summary('SUNSET', [region('Plain', 'WORTH_IT')])]),
        day(TOMORROW, [summary('SUNSET', [region('Plain', 'WORTH_IT')])]),
      ];
      const tiles = buildRailTiles(
        events([TODAY, 'SUNSET'], [TOMORROW, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tiles[0].regions[0].pickKind).toBeNull();
      expect(tiles[0].regions[0].pickDayLabel).toBeNull();
      expect(tiles[1].regions[0].pickDayLabel).toBeNull();
    });
  });

  describe('sun times', () => {
    it('prefers the window\'s own event time', () => {
      const days = [day(TODAY, [
        summary('SUNRISE', [region('A', 'WORTH_IT')], { eventTime: `${TODAY}T04:15:00` }),
        summary('SUNSET', [region('A', 'WORTH_IT')], { eventTime: `${TODAY}T20:11:00` }),
      ])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      // Formatted through the shipped UK formatter: the wire carries UTC, the rail shows BST.
      expect(tile.sunriseTime).toBe('05:15');
      expect(tile.sunsetTime).toBe('21:11');
    });

    it('falls back to a slot time for a payload cached before windows existed', () => {
      const legacy = [day(TODAY, [{
        targetType: 'SUNSET',
        regions: [region('A', 'WORTH_IT', { slots: [{ solarEventTime: `${TODAY}T20:11:00` }] })],
        unregioned: [],
      }])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), legacy, TODAY, TOMORROW, new Set());

      expect(tile.sunsetTime).toBe('21:11');
    });

    it('leaves a time empty rather than inventing one when nothing carries it', () => {
      const days = [day(TODAY, [summary('SUNRISE', []), summary('SUNSET', [])])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tile.sunriseTime).toBe('');
      expect(tile.sunsetTime).toBe('');
    });
  });

  describe('degrade paths', () => {
    it('survives a day the briefing has no entry for, and claims nothing about it', () => {
      // This used to assert "0 regions" — the worst instance of the banned count, because it
      // reported an empty roster as a measurement rather than admitting the day is unknown. It then
      // asserted "All poor", which is the same mistake one word quieter: a day the payload does not
      // contain has not been found poor, it has not been looked at. "Awaiting" is what this test's
      // own title has always asked for.
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), [], TODAY, TOMORROW, new Set());
      expect(tile.peakLabel).toBe('Awaiting');
      expect(tile.peakLabel).not.toMatch(/poor/i);
      expect(tile.countLabel).toBeNull();
    });

    it('returns nothing for an empty event list, and tolerates a null payload', () => {
      expect(buildRailTiles([], [], TODAY, TOMORROW, new Set())).toEqual([]);
      expect(buildRailTiles(null, null, TODAY, TOMORROW, undefined)).toEqual([]);
    });

    it('keeps a chip whose gloss is missing, with the fields empty rather than undefined', () => {
      // The serve path nulls the gloss whenever re-enrichment moves a region across a verdict
      // band, so this is a shipped state, not a theoretical one. The chip still opens — its panel
      // falls back to the terse summary — and it must never render "undefined".
      const days = [day(TODAY, [summary('SUNSET',
        [region('A', 'WORTH_IT', { glossHeadline: null, glossDetail: null })])])];
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set());

      expect(tile.regions[0].glossHeadline).toBe('');
      expect(tile.regions[0].glossDetail).toBe('');
      expect(tile.regions[0].summary).toBe('terse A');
    });

    it('leaves the weather string empty when the region carries no temperature', () => {
      const days = [day(TODAY, [summary('SUNSET',
        [region('A', 'WORTH_IT', { regionTemperatureCelsius: null })])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].regions[0].wx)
        .toBe('');
    });

    it('drops only the wind when the temperature is present and the wind is not', () => {
      const days = [day(TODAY, [summary('SUNSET',
        [region('A', 'WORTH_IT', { regionTemperatureCelsius: 18.6, regionWindSpeedMs: null })])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].regions[0].wx)
        .toBe('🌤️19°C');
    });
  });

  describe('the chip\'s derived strings', () => {
    it('builds the weather string icon-first, rounding the temperature and the wind', () => {
      // Asserted against a fixture that separates round from trunc (18.6 → 19, not 18) and a wind
      // that changes on conversion (4.47 m/s → 10 mph). Only the null-temperature degrade branch
      // was pinned before, so the icon lookup, the rounding and the whole wind segment were each
      // deletable with the suite green.
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'WORTH_IT', {
        regionTemperatureCelsius: 18.6, regionWeatherCode: 2, regionWindSpeedMs: 4.47,
      })])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].regions[0].wx)
        .toBe('🌤️19°C 10mph');
    });

    it.each([
      ['The North Yorkshire Coast', 'N. Yorks Coast'],
      ['Tyne and Wear', 'Tyne & Wear'],
      ['South West Moors', 'S. W. Moors'],
      ['Northumberland', 'Northumberland'],
    ])('abbreviates %s to %s', (full, short) => {
      // One example only exercised the leading "The", the compass and the Yorkshire rules at once;
      // each other rule could be deleted individually and stay green, and "Tyne and Wear" would
      // have rendered unabbreviated on a 150px tile.
      const days = [day(TODAY, [summary('SUNSET', [region(full, 'WORTH_IT')])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].regions[0].shortName)
        .toBe(short);
    });
  });

  it('abbreviates a long region name for the chip but keeps the full one for the callback', () => {
    // The short name is display-only; every callback and every map handoff carries the real name,
    // which is what the backend matches picks on.
    const days = [day(TODAY, [summary('SUNSET', [region('The North Yorkshire Coast', 'WORTH_IT')])])];
    const [chip] = buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].regions;

    expect(chip.shortName).toBe('N. Yorks Coast');
    expect(chip.regionName).toBe('The North Yorkshire Coast');
  });
});

describe('indexPicks', () => {
  it('lets BEST win a region that holds both identities', () => {
    // Possible when the same region is rank 1 on one window and rank 2 on another. One chip
    // cannot carry two identities, and the stronger one is the useful one.
    const days = [
      day(TODAY, [summary('SUNSET', [], { pickOn: pick('ALSO', 'Dales') })]),
      day(TOMORROW, [summary('SUNRISE', [], { pickOn: pick('BEST', 'Dales') })]),
    ];
    expect(indexPicks(days).byRegion.get('Dales')).toBe('best');
  });

  it('returns empty maps for a payload with no windows at all', () => {
    const { byDate, byRegion } = indexPicks([day(TODAY, [{ targetType: 'SUNSET', regions: [] }])]);
    expect(byDate.size).toBe(0);
    expect(byRegion.size).toBe(0);
  });
});
