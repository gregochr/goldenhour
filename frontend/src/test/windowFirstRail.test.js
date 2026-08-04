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

function day(date, eventSummaries) {
  return { date, eventSummaries };
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

  describe('the day roll-up', () => {
    it('lets a region\'s WORTH_IT event outrank its MAYBE one on the same day', () => {
      // A region appearing on both of a day's events must contribute its BEST cell, or a good
      // sunset is hidden by a mediocre sunrise for the same place.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('Dales', 'MAYBE')]),
        summary('SUNSET', [region('Dales', 'WORTH_IT')]),
      ])];
      const [tile] = buildRailTiles(
        events([TODAY, 'SUNRISE'], [TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set(),
      );

      expect(tile.peak).toBe('go');
      expect(tile.regions).toHaveLength(1);
      expect(tile.regions[0].verdictLabel).toBe('Worth it sunset');
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

    it('falls back to a region count when no chip would render, and drops it when one would', () => {
      const poor = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN'), region('B', 'STAND_DOWN')])])];
      const rated = [day(TODAY, [summary('SUNSET', [region('A', 'WORTH_IT'), region('B', 'STAND_DOWN')])])];
      const cols = events([TODAY, 'SUNSET']);

      expect(buildRailTiles(cols, poor, TODAY, TOMORROW, new Set())[0].countLabel).toBe('2 regions');
      expect(buildRailTiles(cols, rated, TODAY, TOMORROW, new Set())[0].countLabel).toBeNull();
    });

    it('says "1 region" rather than "1 regions"', () => {
      const days = [day(TODAY, [summary('SUNSET', [region('A', 'STAND_DOWN')])])];
      expect(buildRailTiles(events([TODAY, 'SUNSET']), days, TODAY, TOMORROW, new Set())[0].countLabel)
        .toBe('1 region');
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
      // The two engines rank over different sets. The backend's pool is both events of the first
      // four dates carrying a live draft; the rail's is the first six non-past events. Between a
      // sunset and the next sunrise those six span only three dates, and either side can drop a
      // past event the other kept — so a day can carry a BEST for a sunrise the tile is not
      // describing. Flagging it would put "◎ BEST sunrise" above a verdict line rolled up from
      // the sunset alone.
      const days = [day(TODAY, [
        summary('SUNRISE', [region('A', 'WORTH_IT')], { pickOn: pick('BEST', 'A') }),
        summary('SUNSET', [region('B', 'WORTH_IT')]),
      ])];
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
    it('survives a day the briefing has no entry for', () => {
      const [tile] = buildRailTiles(events([TODAY, 'SUNSET']), [], TODAY, TOMORROW, new Set());
      expect(tile.peakLabel).toBe('All poor');
      expect(tile.countLabel).toBe('0 regions');
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
