/**
 * The window's attribute rows — the tide the light falls on.
 *
 * <h2>⚠️ It builds ONE channel now, and the snow half is deliberately gone</h2>
 *
 * <p>Through M1 this also promoted snow topics into rows and returned the badge keys it had
 * consumed, so the card header could drop their chips. Both halves died with the window card at M2:
 * the popup's topic rows state every topic once — label, {@code detail}, its measured facts through
 * {@link topicFacts}, and its science note behind an {@code i} — so a snow attribute row would print
 * one topic twice, eight pixels apart, and with no header left to de-duplicate, the promoted set had
 * no reader at all. The facts themselves are not lost: {@link topicFacts} is exported and the topic
 * rows call it, which is why the mapping still lives here rather than moving.
 *
 * <p><b>What is left is a list, not a single row, and that is on purpose.</b> The design's row band
 * holds more than one channel — storm surge and clearance carry facts today and have no event anchor
 * to reach a window by — so the next channel arrives here rather than reshaping the caller.
 *
 * <h2>The row is not role-gated</h2>
 *
 * <p>Plan §7 asks this to be settled rather than let two surfaces disagree. It is the window's own
 * context: tide chips and tide-aligned markers are ungated for every role. `freemium_ui_strategy.md`
 * blurs cloud-layer breakdown, aerosol metrics and the technical panel; tide is almanac, and
 * {@code GET /api/tides} is deliberately Bearer rather than ADMIN for the same reason. So: no gate,
 * and no `role` plumbed into this arm.
 *
 * <p><b>The reconvergence with {@code HotTopicStrip} that used to be DUE here is now DISCHARGED, by
 * removal rather than by a pricing decision.</b> {@code HotTopicStrip} used to blur every topic's
 * fact chips for LITE — a blanket paywall tease over a promotional strip, not a judgement about
 * tides — so this row and that strip disagreed on one screen: sharply here, blurred a door away.
 * The Coming up redesign's P6 (`docs/engineering/coming-up-plan.md` D7) deleted
 * {@code WindowFirstDoors}'s Hot topics door and {@code HotTopicStrip} along with it, so there is no
 * second surface left to disagree with this one — the debt is closed because the other party to the
 * disagreement no longer exists, not because {@code freemium_ui_strategy.md} was ever amended.
 */

/** The sparkline's box, in user units — the design's 104×24. */
export const CHART_W = 104;
export const CHART_H = 24;


/**
 * The tide row's kicker, as a constant.
 *
 * <p>⚠️ It was {@code kickerFor(channel, label)} — a channel glyph table plus a rule that prefixed
 * the glyph only when the label did not already open with one ({@code ❄ ❄ Fresh snow} is what ships
 * otherwise, when a label changes in a file nobody thought was related). Every branch of it became
 * unreachable at M2, because the snow promotion was the only caller passing a PAYLOAD label and the
 * one that survives passes two literals — CodeQL caught the residue as a trivial conditional.
 *
 * <p>The rule is not wrong, it simply has nothing to guard: it belongs to a label the payload
 * supplies. <b>Bring it back with the next channel that has one</b> (surge and clearance both carry
 * facts and no event anchor today), rather than rediscovering the double-glyph the hard way.
 */
const TIDE_KICKER = '≈ Tide';

/** Water level → the words the row states it in. */
const STATE_WORD = { HIGH: 'high water', MID: 'mid tide', LOW: 'low water' };

/** Which way the water is going — a separate axis from the level, and both are needed. */
const DIRECTION_WORD = { RISING: 'rising', FALLING: 'falling' };

/**
 * A fact segment at the row's base tone — `--color-plex-text-secondary`, 6.57:1 on the row.
 *
 * <p><b>There are two tones here, not the design's three.</b> Its `.dim` is `--ink-3`, and bone at
 * 0.42 over this row's surface measures <b>3.49:1</b> on a plain card and 3.38:1 on the lead card
 * — under AA's 4.5:1 for the 10.5px type the row is set in. So the caveat chips take the base tone
 * and lose only their de-emphasis, which is hierarchy rather than meaning. This project has made
 * the same correction before (the spot card's sub-lines at §5a): `--ink-3` is not usable for small
 * type on these surfaces, and the honest fix is to stop reaching for it rather than to keep
 * re-deriving that it fails.
 */
const base = (text) => ({ text, tone: 'base' });

/** The emphasised tail of a fact: full-strength ink at 600, the design's `<b>`. 13.39:1. */
const strong = (text) => ({ text, tone: 'strong' });

/**
 * One fact chip: ordered segments, plus whether it is the row's droppable one on a phone.
 *
 * <p>Segments rather than a string because the tone changes mid-chip — `mid tide, **falling**` is
 * one fact, not two — and because a string would have to carry markup to say so.
 */
const fact = (segments, optional = false) => ({ segments, optional });

/** True for a finite number — `null`, `undefined` and `NaN` all fail, which is the point. */
function isFinite_(value) {
  return typeof value === 'number' && Number.isFinite(value);
}


/**
 * The tide row's facts, in the order the design reads them: where the water is, the extreme nearest
 * the light, the sea, and the range with the caveat that names the coast it was measured on.
 *
 * <p>Each is skipped rather than half-stated when its inputs are missing. The whole row is the
 * accessible answer — the sparkline beside it is `aria-hidden` — so a fact that cannot be completed
 * must be absent, never approximated.
 */
function tideFacts(tide) {
  const facts = [];

  const state = STATE_WORD[tide.state];
  const direction = DIRECTION_WORD[tide.direction];
  if (state && direction) {
    facts.push(fact([base(`${state},`), strong(direction)]));
  } else if (state || direction) {
    facts.push(fact([strong(state || direction)]));
  }

  if (tide.nearestType && tide.nearestTime && tide.nearestOffset) {
    facts.push(fact([base(`${tide.nearestType} ${tide.nearestTime} ·`),
      strong(tide.nearestOffset)]));
  }

  // Already formatted upstream as `0.3 m · smooth`. Independently nullable: waves reach T+4 while
  // tides reach months ahead, so most of the rail has a full rollup and no sea — which must not
  // suppress the row. Marked droppable on a phone: it is the one fact here that describes the water
  // rather than the water's relationship to the light, which is what the row is for.
  if (tide.seas) {
    facts.push(fact([base(`seas ${tide.seas}`)], true));
  }

  // `rangeAnomaly` absent is NOT "about average" — the backend says that in words when it means it,
  // and null means no historical baseline existed. So the clause simply drops.
  //
  // `at <location>` is the row's load-bearing caveat, not a decoration: alignment differs ~20–30
  // minutes across a coastline and the fact above states an offset to the minute, so an
  // unattributed high-water time is a claim this project cannot make (plan §2.4). It rides this
  // span exactly so the row needs no extra column, and it renders whenever the name does — it
  // qualifies the clock time as much as the metres.
  const measured = [tide.range, tide.rangeAnomaly, tide.locationName && `at ${tide.locationName}`]
    .filter(Boolean);
  if (measured.length > 0) {
    facts.push(fact([base(measured.join(' · '))]));
  }

  return facts;
}

/**
 * The sparkline, as pure geometry over the payload's own normalised series.
 *
 * <p>`x = i/(n−1)·104`, `y = (1−curve[i])·24`, the mark at `windowPosition·104` /
 * `(1−windowLevel)·24` — plan §2.4a. No clock is parsed and nothing is normalised here, which is
 * why this never reached for the now-deleted `components/chart/solarDayGeometry.js` (P6,
 * `docs/engineering/coming-up-plan.md` D7): that module existed for the Hot topics strip's 1000×32
 * axis, where the client turned `"05:44"` into a position. Here the backend has already done it,
 * and borrowing the constants would have implied a shared axis these two charts never had.
 *
 * @param {object} tide the window's tide rollup
 * @returns {?{path: string, markX: ?number, markY: ?number}} null when there is no shape to draw
 */
export function tideSparkline(tide) {
  const curve = tide?.curve;
  // Two points is the minimum for a line, and it is also what keeps `n − 1` off zero. A curve
  // carrying anything non-finite draws nothing at all rather than a path with `NaN` in it — the
  // row's facts already carry the whole answer, so a missing picture costs nothing.
  if (!Array.isArray(curve) || curve.length < 2 || !curve.every(isFinite_)) {
    return null;
  }
  const path = curve
    .map((v, i) => `${i ? 'L' : 'M'}${((i / (curve.length - 1)) * CHART_W).toFixed(2)} `
      + `${((1 - v) * CHART_H).toFixed(2)}`)
    .join(' ');

  // The mark is dropped on its own when the instant cannot be placed: a trace with no mark still
  // says what the day's water did, where a mark defaulted to zero would say the window sits at
  // midnight at dead low water.
  const placeable = isFinite_(tide.windowPosition) && isFinite_(tide.windowLevel);
  return {
    path,
    markX: placeable ? Number((tide.windowPosition * CHART_W).toFixed(2)) : null,
    markY: placeable ? Number(((1 - tide.windowLevel) * CHART_H).toFixed(2)) : null,
  };
}


/**
 * A topic's facts, mapped onto the row's segment tones.
 *
 * <p>The distinction the data actually carries is {@code emphasis} — the headline quantity against
 * its context — and that is the one the row renders. {@code key} takes the base tone rather than
 * the now-deleted {@code HotTopicStrip}'s muted, for the AA reason recorded on {@link base}. `dir`
 * is dropped: it was the strip's look-direction arrow, no snow strategy emits one, and inventing a
 * treatment for a field with no live producer would be a guess the row states as fact.
 *
 * <p>Exported since M2: the window popup's topic rows carry the same facts (plan §5's "name,
 * `detail`, <b>facts</b>, `description` behind the `i`"), and a second mapping there would be a
 * second answer to which half of a fact is emphasised. The snow ATTRIBUTE row it was written for is
 * no longer drawn on the v2 arm — the popup's topic row states the same topic once, with its
 * science note — so this is now the mapping's only live reader on that arm.
 */
export function topicFacts(badge) {
  return badge.facts.map((f) => {
    const value = f.emphasis ? strong(f.value) : base(f.value);
    return fact(f.key ? [base(f.key), value] : [value], Boolean(f.optional));
  });
}

/**
 * Builds a window's attribute rows.
 *
 * <p>⚠️ <b>The tide row, and only the tide row, since M2.</b> This used to promote snow topics into
 * attribute rows as well, and return the badge keys it had consumed so the card header could drop
 * their chips. Both jobs died with the window card: the popup's topic rows now state every topic
 * once, with its detail, its measured facts and its science note, so a snow attribute row would
 * print one topic twice eight pixels apart — and with no header to de-duplicate, the {@code
 * promoted} set had no reader. The rarity ordering the promotion used survives where it is still
 * read, on the card faces and the popup's rows ({@code windowFirstTopics.windowTopics}).
 *
 * <p>It stays a LIST rather than becoming {@code buildTideRow}, because the design's row band is a
 * list — the surge and clearance channels have no event anchor today and would arrive here.
 *
 * @param {?object} win the window projection from `/api/briefing`
 * @returns {Array} the rows
 */
export function buildWindowRows(win) {
  if (!win?.tide) return [];
  return [{
    key: 'tide',
    channel: 'tide',
    kicker: TIDE_KICKER,
    facts: tideFacts(win.tide),
    chart: tideSparkline(win.tide),
  }];
}
