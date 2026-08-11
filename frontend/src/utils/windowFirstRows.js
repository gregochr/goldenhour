import { badgeChannel } from './windowFirstCards.js';

/**
 * The window card's attribute rows — the tide the light falls on, and the snow underneath it.
 *
 * <h2>The cap is two, and it is arithmetic rather than convention</h2>
 *
 * <p>Plan §5. Each row costs ~38px on a card measured at 201px, on a shell already running 2.17
 * viewports at six windows, so the budget is the constraint the rows are designed against — see
 * {@link MAX_ROWS}. Nothing a topic could carry lets it argue its way past the cap.
 *
 * <h2>A topic renders once: as a row when it has numbers, as a badge otherwise</h2>
 *
 * <p>This is the duplication question the design leaves open — it draws a snow badge in the header
 * <em>and</em> a snow row beneath it — and the answer follows from what each surface can hold. A
 * badge has room for {@code label}; a row has room for {@code label} plus the topic's measured
 * facts. So a topic carrying facts is strictly richer as a row, and a topic carrying none would
 * render a row whose entire content is its own label repeated forty pixels lower, which is the
 * noise §6 bans. Hence: <b>facts promote, absence does not</b>, and a promoted topic leaves the
 * header. Nothing is ever lost — a topic the cap drops keeps its badge.
 *
 * <p><b>Only the snow channel is promotable, and that is a budget decision rather than a
 * generalisation.</b> Several topic kinds carry facts (NLC's clear-count, aurora's Kp), and
 * promoting all of them would put four rows on a busy window and blow the height budget the cap
 * exists to defend. The design draws exactly two rows; P7 ships exactly two. Widening the set is a
 * design call, not a refactor.
 *
 * <h2>Neither row is role-gated</h2>
 *
 * <p>Plan §7 asks P7 to settle this rather than let two surfaces disagree. {@code HotTopicStrip}
 * blurs every topic's fact chips for LITE — a blanket paywall tease over a promotional strip, not a
 * judgement about tides — and these rows are not that surface. They are the window's own context,
 * the equivalent of the v1 Plan tab's tide chips and tide-aligned markers, which are ungated for
 * every role today. `freemium_ui_strategy.md` blurs cloud-layer breakdown, aerosol metrics and the
 * technical panel; tide is almanac (the product already classifies it so — `topicCertainty`), and
 * `GET /api/tides` is deliberately Bearer rather than ADMIN for the same reason. So: no gate, one
 * rule for the whole row block, and no `role` plumbed into this arm.
 *
 * <p><b>⚠️ That reconvergence is now DUE, and this paragraph used to deny it.</b> It read
 * "{@code HotTopicStrip} … is not in this arm yet — P9 builds the hot-topics door — so nothing
 * disagrees on screen today", and promised that "when P9 wires it in, the blanket blur is the single
 * place to reconverge". <b>P9 shipped</b>: {@code WindowFirstDoors:222} renders {@code HotTopicStrip}
 * in this arm, deliberately unchanged. So the two surfaces DO now disagree on one screen, and for the
 * snow channel they disagree about the identical objects — {@code PlanWindowProjector:441-443} builds
 * the badges from {@code topic.facts()} and {@code buildWindowRows} below maps those same facts onto
 * row segments, so a LITE user reads a fact sharp in a row and blurred in a pill a door away.
 *
 * <p>It is left that way on purpose, and the reason is the one this file already gives: the fix is a
 * <em>pricing</em> decision, not a rendering one. {@code freemium_ui_strategy.md:79} grants LITE the
 * scores and {@code :98} forbids blocking the core score, so the blur is the deviation and these
 * ungated rows are the policy — but resolving it edits {@code HotTopicStrip} and
 * {@code MarkerPopupContent}, both shared with the frozen v1 arm the flag comparison rests on (§4).
 * Plan §5d and §5e hand it to whoever owns the pricing story, to be decided once across both arms.
 * The §6 sweep confirmed it is unreachable for the pilot roster (self-registration yields
 * {@code PRO_USER}), which is why it did not gate the sweep — not because it stopped being true.
 */

/**
 * Rows per window, enforced here rather than trusted to the callers.
 *
 * <p>It binds today: {@code SNOW_FRESH} (or {@code SNOW_MIST}) and {@code SNOW_TOPS} are separate
 * strategies, both anchored to SUNRISE, so a winter dawn genuinely offers a tide row and two snow
 * rows. The third is dropped and stays a header badge.
 */
export const MAX_ROWS = 2;

/** The sparkline's box, in user units — the design's 104×24. */
export const CHART_W = 104;
export const CHART_H = 24;

/** Channel glyphs. The design's own, and the only two channels a row can carry. */
const KICKER_GLYPH = { tide: '≈', snow: '❄' };

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
 * and lose only their de-emphasis, which is hierarchy rather than meaning. This is the fourth time
 * this project has made the same correction (`CloseToHome`'s region line, its ordering explainer,
 * the spot card's sub-lines at §5a): `--ink-3` is not usable for small type on these surfaces, and
 * the honest fix is to stop reaching for it rather than to keep re-deriving that it fails.
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
 * The kicker for a promoted topic: the channel glyph and the topic's own label.
 *
 * <p>The glyph is prefixed only when the label does not already open with one. Nothing in the snow
 * strategies carries a glyph today, but topic labels elsewhere do (`✦ NLC`), and `❄ ❄ Fresh snow`
 * is the kind of thing that ships because a label changed in a file nobody thought was related.
 */
function kickerFor(channel, label) {
  const glyph = KICKER_GLYPH[channel];
  if (!glyph || !label) return label || '';
  return /^[a-z0-9]/i.test(label.trim()) ? `${glyph} ${label}` : label;
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
 * why this does not reach for `components/chart/solarDayGeometry.js`: that module exists for the
 * hot-topic strip's 1000×32 axis, where the client turns `"05:44"` into a position. Here the
 * backend has already done it, and borrowing the constants would imply a shared axis these two
 * charts do not have.
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

/** A badge's stable identity, so the card can drop exactly the ones this promoted. */
export function badgeKey(badge) {
  return `${badge?.type ?? ''}:${badge?.label ?? ''}`;
}

/**
 * A topic's facts, mapped onto the row's segment tones.
 *
 * <p>The distinction the data actually carries is {@code emphasis} — the headline quantity against
 * its context — and that is the one the row renders. {@code key} takes the base tone rather than
 * `HotTopicStrip`'s muted, for the AA reason recorded on {@link base}. `dir` is dropped: it is the
 * strip's look-direction arrow, no snow strategy emits one, and inventing a treatment for a field
 * with no live producer would be a guess the row states as fact.
 */
function topicFacts(badge) {
  return badge.facts.map((f) => {
    const value = f.emphasis ? strong(f.value) : base(f.value);
    return fact(f.key ? [base(f.key), value] : [value], Boolean(f.optional));
  });
}

/**
 * Builds a window's attribute rows and says which badges they consumed.
 *
 * <p>Order is tide first — it is the row that renders on every window of every day, so a card whose
 * rows moved about would read as a different card — then promoted topics by rarity, rarest first,
 * which is the ordering advice the payload already publishes for the promoted strip. Using the same
 * rank in both places means the two surfaces cannot disagree about which topic matters most.
 *
 * @param {?object} win the window projection from `/api/briefing`
 * @returns {{rows: Array, promoted: Set<string>}} the rows, capped, and the badge keys they took
 */
export function buildWindowRows(win) {
  const rows = [];

  if (win?.tide) {
    rows.push({
      key: 'tide',
      channel: 'tide',
      kicker: kickerFor('tide', 'Tide'),
      facts: tideFacts(win.tide),
      chart: tideSparkline(win.tide),
    });
  }

  const promotable = (win?.badges || [])
    .filter((b) => badgeChannel(b.type) === 'snow' && (b.facts || []).length > 0)
    .slice()
    .sort((a, b) => (a.rarityRank ?? Number.MAX_SAFE_INTEGER)
      - (b.rarityRank ?? Number.MAX_SAFE_INTEGER));

  for (const badge of promotable) {
    rows.push({
      key: badgeKey(badge),
      channel: 'snow',
      kicker: kickerFor('snow', badge.label),
      facts: topicFacts(badge),
      chart: null,
      badgeKey: badgeKey(badge),
    });
  }

  const capped = rows.slice(0, MAX_ROWS);
  // Only what SURVIVED the cap is promoted. A snow topic the cap dropped keeps its header badge,
  // so the budget costs the reader a row and never a fact.
  return {
    rows: capped,
    promoted: new Set(capped.map((r) => r.badgeKey).filter(Boolean)),
  };
}
