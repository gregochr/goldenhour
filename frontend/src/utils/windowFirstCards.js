import { formatTime, getEventTime } from './briefingDisplay.js';
import { ANY_TIER_ID, gateSpotsByReach } from './reachLens.js';
import { ANY_RATING_ID, gateSpotsByRating } from './ratingLens.js';
import { buildLensEmptyState } from './windowLensEmpty.js';
import { buildWindowSpots } from './windowFirstSpots.js';
import { badgeKey, buildWindowRows } from './windowFirstRows.js';

/**
 * The window-card descriptors — one per rendered solar window.
 *
 * <h2>It reads the window projection, and almost nothing else</h2>
 *
 * <p>{@code BriefingWindow} was built for exactly this surface, so the card is close to a
 * pass-through: verdict, best rating, confidence, badges and pick all arrive derived. What this
 * module adds is the framing the payload deliberately withholds — the window "carries no date and
 * no target type. Both are the enclosing BriefingDay and BriefingEventSummary" — plus the two
 * judgements that are the client's to make: which card leads, and what the header may honestly say.
 *
 * <h2>What the header may claim</h2>
 *
 * <p>The design's meta reads {@code best 4.0★ · 23 within reach}. Neither half survives here.
 * The star is {@code best spot 4★}: {@code bestRating} is an {@code Integer} in 1–5, so {@code 4.0★}
 * asserts a precision the field does not carry — and the word <em>spot</em> is load-bearing rather
 * than decorative. Since the verdict badge became the top region's AVERAGE (plan §2), this number
 * describes one location while the badge beside it describes a whole region, so the two can read
 * {@code Poor · best spot 4★} and both be true. The label is what makes that two facts instead of a
 * contradiction, which is the entire condition on which {@code bestRating} was allowed to stay on
 * the payload at all.
 *
 * <p><b>The header's count arrives at P8, and only when the word it uses is true.</b> P5 and P6 both
 * withheld it because the design's word is <em>reach</em> and nothing was gated: "23 within reach"
 * above an ungated strip describes a set nothing filtered, which §6 bans. The tier ships now, so the
 * sentence can be earned — but not unconditionally. {@code withinReachCount} is non-null only when
 * the gate had a threshold to apply <em>and</em> every drawn spot has a known drive time, because
 * plan §2.5 rule 1 lets an unknown drive time through every tier: a set containing one is a mix of
 * "within reach" and "not known", and calling the whole of it the former over-claims. Where the word
 * is not available the header stays as P5 shipped it — no count at all, rather than a bare "23
 * spots" duplicating the strip footer verbatim one element lower. Exactly one count when nothing was
 * gated, two complementary ones when something was.
 *
 * <p><b>And {@code bestRating} is deliberately NOT re-derived from the gated set</b>, which is
 * visible the moment the lens bites: a header can read {@code best spot 5★ · 7 within reach} over a
 * strip topping out at 4★, because the 5★ is beyond the tier. That is two true claims about two
 * different things — the star is the <em>window's</em> best, the count is what <em>this user</em>
 * can drive to — and it is useful in that state, since it says a better spot exists further out.
 * Re-deriving it would make one window's "best" differ per user for the same night, put it out of
 * step with every other surface that reads the projection, and move a quality signal when the
 * reader touched a control explicitly about distance. Plan §2.7's rule that the star is never
 * touched by the confidence channel applies here for the same reason.
 */

/**
 * Kickers and labels are day-relative.
 *
 * <p>Exported so {@code windowFirstStrip.js} names a window the same way this module does. It was
 * private while the day rail kept its own copy; with the rail retired at P2 there are two callers
 * and one definition, which is the right way round — a strip thumbnail and the card it opens
 * printing different words for one date is precisely the defect a second copy produces.
 */
export function dayLabelFor(dateStr, todayStr, tomorrowStr) {
  if (dateStr === todayStr) return 'Today';
  if (dateStr === tomorrowStr) return 'Tomorrow';
  return new Date(`${dateStr}T12:00:00Z`)
    .toLocaleDateString('en-GB', { weekday: 'long', timeZone: 'UTC' });
}

/** 'sunrise' | 'sunset' — the window's own event, lower case for use inside a phrase. Exported
 *  beside {@link dayLabelFor} and for the same reason. */
export function eventWord(targetType) {
  return targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
}

/** Sentence case for the standalone form, where the kicker already carries the day. */
function eventTitle(targetType) {
  return targetType === 'SUNRISE' ? 'Sunrise' : 'Sunset';
}

/**
 * The badge's word for a display verdict.
 *
 * <p><b>{@code AWAITING} is not a poor forecast and must not read as one.</b> The payload is
 * explicit about it — "AWAITING is reachable and means the window has neither a rating nor a triage
 * signal" — and the design's badge builder knows only three states, so the fourth needs a word the
 * product already speaks. {@code HeatmapGrid} and {@code DailyBriefing} both render "Awaiting", so
 * that is the word, on the neutral badge rather than the red one.
 */
export const VERDICT_LABEL = {
  WORTH_IT: 'Worth it',
  MAYBE: 'Maybe',
  STAND_DOWN: 'Poor',
  AWAITING: 'Awaiting',
};

/**
 * Which verdicts carry the confidence channel.
 *
 * <p>Confidence qualifies a <em>recommendation</em>. Decaying a Poor or an Awaiting badge would say
 * "we are unsure it is bad", which is not what the channel means and not a claim the derivation
 * supports. The same gate ships on `VerdictPill` and `HeatmapGrid`, and the rail already sets its
 * own tile confidence to null on an all-poor day. It also sidesteps a real defect: the neutral
 * badge's border is a token rather than an `rgba()` literal, so it cannot decay with its fill, and
 * a half-decayed badge would look like a rendering bug.
 */
export const CONFIDENCE_VERDICTS = new Set(['WORTH_IT', 'MAYBE']);

/**
 * How many of the drawn spots the header may call "within reach", or null when it may not.
 *
 * <p>Three conditions, and all are about honesty rather than presentation. The tier has to carry a
 * threshold — under "Any" nothing was gated, so the word describes no act. Every drawn spot has to
 * carry a drive time, because plan §2.5 rule 1 passes an unknown one through every tier: a set that
 * is part measured and part unknown is not a set that is within reach, and calling it one is the
 * same over-claim as counting a set nothing filtered.
 *
 * <p><b>And no rating floor may be active.</b> The drawn set is then gated on two axes, and this
 * clause names one of them — "6 within reach" over a strip a 4★ floor had trimmed to two counts
 * neither what the reader can drive to nor what is on screen. Withholding it is the same answer the
 * other two conditions give, and the bar's own count line states the page-wide cost instead. The
 * alternative — counting the reach-gated pool rather than the drawn set — was rejected: it would
 * put a number in the header that no card below it accounts for, which is what §6's rule about a
 * claimed count matching what is rendered exists to prevent.
 *
 * @param {Array}   spots       the spots as drawn
 * @param {?number} limitMinutes the active tier's threshold, or null for "Any"
 * @param {?number} minRating    the active floor's threshold, or null for "Any rating"
 * @returns {?number} the count, or null when the sentence is unavailable
 */
function withinReachCount(spots, limitMinutes, minRating) {
  if (limitMinutes == null || minRating != null || spots.length === 0) return null;
  return spots.every((spot) => spot.driveMinutes != null) ? spots.length : null;
}

/**
 * The regions of a window that may be ranked, named or counted — the projector's own filter.
 *
 * <p><b>An all-woodland region is excluded, and that exclusion is the whole of this function.</b>
 * {@code PlanWindowProjector.rank} drops a region holding no non-canopy slot before it ranks
 * anything, unless the entire window is canopy — and {@code BriefingRegion.meanRating} falls back
 * to canopy slots <em>per region</em>, so an all-wood region publishes a mean derived from
 * inverted-polarity scores. Reducing over the raw region list therefore lets a wood rated 4.8 on a
 * misty dawn outrank a sky window at 4.2 and take rank 1, under its own header reading "Poor" and
 * beside a thumbnail that paints no heat there at all (the field is sky-gated — `heatSpots.js`).
 * That is the same defect P1's review caught in the field itself, one surface along.
 *
 * <p>The gate is the presence of a sky slot, never whether one is <em>rated</em> — the projector's
 * rule, copied here as {@code buildWindowSpots} already copies it, and the difference is an
 * ordinary misty sunrise: the fog that leaves every sky slot unrated is the same fog that scores
 * the wood well.
 *
 * <p>Exported at P3 because the open row's rail and band rank, name and count the same set. Two
 * copies of this filter would be two answers to "which regions exist in this window", and the one
 * that got it wrong would be the surface a reader is looking at.
 *
 * @param {?object} es the event summary
 * @returns {Array<object>} the rankable regions, in payload order
 */
export function eligibleRegions(es) {
  const regions = es?.regions || [];
  const slots = regions.flatMap((region) => region?.slots || []);
  // An all-canopy WINDOW keeps its woods, exactly as the projector does: there is no sky answer to
  // prefer, and dropping every region would leave the window unrankable rather than honestly ranked
  // on what was measured.
  const allCanopy = slots.length > 0 && slots.every((slot) => slot.canopy);
  return regions.filter((region) => {
    const regionSlots = region?.slots || [];
    // A region with NO slots is kept: it carries no canopy claim either way, and the projector's
    // own filter passes it (`r.slots().isEmpty() || …anyMatch(s -> !s.canopy())`).
    return allCanopy || regionSlots.length === 0 || regionSlots.some((slot) => !slot.canopy);
  });
}

/**
 * The best REGION mean in a window, over the same regions the backend's own ranking votes on.
 *
 * @param {?object} es the event summary
 * @returns {?number} the best eligible region mean, or null when none carries one
 */
function topMeanRating(es) {
  let best = null;
  for (const region of eligibleRegions(es)) {
    const mean = region?.meanRating;
    if (typeof mean !== 'number' || !Number.isFinite(mean)) continue;
    if (best === null || mean > best) best = mean;
  }
  return best;
}

/**
 * Builds the window cards for the rendered event set.
 *
 * <p><b>Away days get no card.</b> A travel day still carries slots — the pipeline skips
 * *evaluation*, not collection — so the projector turns them into STAND_DOWN or AWAITING and a
 * naive card list would put a "Poor" card under a rail tile reading "Not forecast". That is a
 * contradiction on one screen, and it is still the rule.
 *
 * <p>What changed at P9 is that the absence is no longer silent. {@code buildPaneItems} in
 * {@code windowFirstAway.js} folds a dashed row back in where the missing windows fall, because the
 * six-event cap is applied <em>before</em> the travel filter — so an away day spends a slot and then
 * vanishes, and the pane's date order skips a day with nothing to say why. This function stays
 * unchanged: it still returns cards only, and the away rows are built from the same event array
 * beside it rather than smuggled through as a fifth card variant.
 *
 * <p><b>The same event array the rail rolled up</b> is the input, so the two can never disagree
 * about which windows exist — which is also what lets the pick badge and the rail's pick flag point
 * at one set.
 *
 * @param {Array}  upcomingEvents [{date, targetType}], already ordered and capped
 * @param {Array}  briefingDays   briefing.days
 * @param {string} todayStr       today's ISO date in Europe/London
 * @param {string} tomorrowStr    tomorrow's ISO date in Europe/London
 * @param {Set}    travelDayDates dates the operator is away
 * @param {?Map}   reachById      per-user drive minutes and distance, keyed by location id. Empty
 *                                until its own request resolves, and empty for the whole session
 *                                for a user with no home postcode — in both cases every spot simply
 *                                renders without its reach line, because a lens with no data is not
 *                                a gate (plan §2.5).
 * @param {object} [lens]         the page-wide lens, both axes. {@code limitMinutes} is the active
 *                                reach tier's threshold and {@code minRating} the active floor's,
 *                                each null when that control gates nothing;
 *                                {@code defaultLimitMinutes} is today's derived default and is what
 *                                marks a spot {@code far}. The ids ride along because the empty
 *                                state has to name the control it would move, and a threshold
 *                                cannot name a chip. The default gates nothing and marks nothing,
 *                                which is what a caller with no lens should get — never a silent
 *                                gate at some assumed distance.
 * @returns {Array} card descriptors for {@code WindowFirstWindowCard}
 */
export function buildWindowCards(
  upcomingEvents, briefingDays, todayStr, tomorrowStr, travelDayDates, reachById,
  lens = { limitMinutes: null, defaultLimitMinutes: null },
) {
  const limitMinutes = lens?.limitMinutes ?? null;
  const minRating = lens?.minRating ?? null;
  const tierId = lens?.tierId ?? ANY_TIER_ID;
  const floorId = lens?.floorId ?? ANY_RATING_ID;
  const live = (upcomingEvents || []).filter((e) => !travelDayDates?.has(e.date));

  const cards = live.map(({ date, targetType }, index) => {
    const day = (briefingDays || []).find((d) => d.date === date);
    const es = (day?.eventSummaries || []).find((e) => e.targetType === targetType);
    const win = es?.window;

    // Lead is TODAY'S first window, not simply the first card. Both terms are load-bearing:
    // `date === todayStr` alone fires twice before dawn (today's sunrise AND its sunset are both
    // upcoming), and `index === 0` alone moves the gold onto tomorrow's sunrise the moment today's
    // last event passes — at which point the rail's gold tile has already gone, so the two surfaces
    // would contradict each other. Together they yield exactly one lead card, or none, and always
    // agree with the rail.
    const lead = index === 0 && date === todayStr;

    // "Tonight" only where it is true. A lead card can be today's SUNRISE, where the word is simply
    // wrong, and the alternative ("This morning") is vocabulary the product speaks nowhere else —
    // §6 bans inventing any. So the warmest word on the screen stays where it is honest, and the
    // day moves into the title everywhere else.
    const kicker = lead && targetType === 'SUNSET' ? 'Tonight' : null;
    const verdict = win?.verdict || 'AWAITING';

    // The attribute rows, and the badges they took with them. A topic that became a row is not
    // also a chip — see `windowFirstRows.js` for why that follows from what each surface can hold
    // rather than from a preference.
    const { rows, promoted } = buildWindowRows(win);

    // Derived from the SAME event summary the window projection was, so the strip's top card and
    // the header's `best N★` read one population — see `buildWindowSpots` for the two filters
    // that keep them in step. The gate runs after, so `reachTotal` is the set the lens chose from
    // and the card can say how many it left without either number describing a different thing.
    const allSpots = buildWindowSpots(es, reachById, lens?.defaultLimitMinutes ?? null);
    // Reach first, then rating — the order the two counts describe. `reached` is what the floor
    // chose from, so the bar can say "42 of 138" without either number naming a different thing.
    const reached = gateSpotsByReach(allSpots, limitMinutes);
    const spots = gateSpotsByRating(reached, minRating);

    return {
      key: `${date}:${targetType}`,
      date,
      targetType,
      lead,
      kicker,
      // With a kicker the day is already stated, so the title is the event alone. Without one it
      // carries both, exactly as every non-lead card in the design does.
      when: kicker ? eventTitle(targetType) : `${dayLabelFor(date, todayStr, tomorrowStr)} ${eventWord(targetType)}`,
      // The window's own time is the projection's answer; the slot scan is the fallback for a
      // payload cached before windows existed.
      time: formatTime(win?.eventTime || (es ? getEventTime(es) : null)) || '',
      verdict,
      verdictLabel: VERDICT_LABEL[verdict] || VERDICT_LABEL.AWAITING,
      // Null is "nothing in this window is rated", which is a different statement from a low one:
      // the header omits the star rather than printing a placeholder.
      bestRating: win?.bestRating ?? null,
      // The best of the window's REGION means, for the Order control's `Best` ranking (plan §2.12
      // and §4.3). Deliberately a different quantity from `bestRating` above, which is one
      // location's score: ranking six windows by a single best spot would put a window with one
      // exceptional location above one where a whole region is good, which is the opposite of what
      // "which window is the best bet" means. Null when nothing in the window carries a mean, and
      // `windowFirstOrder.js` ranks those last rather than treating the absence as a zero.
      topMeanRating: topMeanRating(es),
      // The TOP REGION's confidence. Through P14 this was "the single render site", because the
      // retired day rail derived a day-level tier and deliberately rendered nothing from it. That is
      // no longer true and the change is deliberate: the heat strip reads this same field per window
      // and feeds it to the kernel's haze through `confidenceScalar`, so the picture and the badge
      // decay by ONE number (plan D3). Two renderings of one value, never two derivations.
      confidence: CONFIDENCE_VERDICTS.has(verdict) ? (win?.confidence ?? null) : null,
      spots,
      // The set BEFORE the reach gate, carried for P11's drill-down — which owns its own reach
      // control and must be able to widen past the bar's tier, so handing it the gated list would
      // give it a control with nothing to reveal. It is the same array `reachTotal` counts, so the
      // sheet and the strip footer can never describe two different populations.
      allSpots,
      // How many the lens chose from. Equal to `spots.length` whenever nothing was gated, which is
      // exactly when the strip footer keeps P6's plain count rather than the design's "N of M".
      reachTotal: allSpots.length,
      // How many survived reach alone — the rating floor's own denominator, summed across the page
      // into the bar's "42 of 138". Deliberately a scalar rather than a third array: nothing renders
      // this set, only counts it.
      reachedTotal: reached.length,
      // The header's claim, or null when the word "reach" would over-claim — see the module comment.
      withinReachCount: withinReachCount(spots, limitMinutes, minRating),
      // What the card draws in place of its strip, or null when it draws a strip — or when the
      // window had no spots at all, which is a card the lens never touched and must not carry a
      // line about it.
      lensEmpty: buildLensEmptyState({
        allSpots, spots, tierId, limitMinutes, floorId, minRating,
      }),
      rows,
      badges: (win?.badges || []).filter((b) => !promoted.has(badgeKey(b))),
      // The window's badges BEFORE the row promotion above removed any of them — the same
      // before/after pairing `allSpots` and `spots` already carry, and for the same reason: a later
      // consumer needs the population, not what one surface left of it. `buildPromotedStrip` counts
      // these to decide whether two attributes landed on this window, and counting the filtered list
      // would make a winter dawn carrying SNOW_TOPS + SNOW_FRESH read as a single-badge window
      // because one of the two had become a row. Nothing is dropped from the card by this: the
      // strip promotes no badge out of the header (see `windowFirstPromoted.js`).
      allBadges: win?.badges || [],
      // The payload's own rarity answer for this window, carried verbatim — `undefined` when the
      // window has no badges, and also when a payload cached before the field existed is replayed.
      // `buildPromotedStrip` prefers it and recomputes only in the second case; the card itself must
      // not read it, which `WindowFirstWindowCard.test.jsx` pins.
      topRarityRank: win?.topRarityRank,
      pick: win?.pick
        ? {
          kind: win.pick.kind === 'BEST' ? 'best' : 'also',
          regionName: win.pick.regionName,
          headline: win.pick.headline,
          detail: win.pick.detail || null,
          locationName: win.pick.locationName || null,
          locationId: win.pick.locationId ?? null,
        }
        : null,
    };
  });

  // Belt-and-braces: an Also with no surviving Best badges a runner-up to a plan nobody on screen
  // can see. This explicit check is now the ONLY protection — the day rail had an emergent one (a
  // RAIL_MAX_DAYS=4 date window narrow enough that both picks' dates almost always fell inside it)
  // and the rail went at P2, taking it with it. It was never a rule this file could lean on. The backend's own pick pool is scoped to the
  // rendered event set (plan-verdict-consolidation-plan.md Phase 1), so this should be a no-op
  // against a fresh payload — it exists for the payload that is not fresh: an SWR-cached response
  // can sit for up to 12h, during which the window the BEST pick named can fall out of
  // `upcomingEvents` (passed, or pushed beyond the horizon by newer events) while the Also's window
  // survives. Kept even after the backend fix, since staleness is a client-side fact no backend fix
  // can reach.
  if (!cards.some((c) => c.pick?.kind === 'best')) {
    return cards.map((c) => (c.pick?.kind === 'also' ? { ...c, pick: null } : c));
  }
  return cards;
}

/**
 * The DOM id of a window card's root element.
 *
 * <p>Two callers need this string and they are in different files — the card writes it, and the
 * promoted strip's route into the list reads it back — so it is derived once here rather than
 * spelled the same way twice. The colons in {@code card.key} are replaced for the reason the card
 * already gives for its body id: a colon is a legal HTML5 id character and would work through
 * {@code getElementById}, but it silently breaks {@code querySelector('#…')} and any CSS id
 * selector, and laying that trap is cheaper to avoid than to find.
 *
 * @param {string} key the card's `${date}:${targetType}` key
 * @returns {string} the element id
 */
export function windowCardDomId(key) {
  return `window-card-${String(key).replace(/:/g, '-')}`;
}

/**
 * The badge channel a hot topic's type belongs to.
 *
 * <p>Five channels ship as tokens; anything unrecognised takes the neutral base badge rather than
 * being forced into the nearest one, because a badge's colour names its channel and a wrong colour
 * is a wrong claim. New topic types are therefore additive and fail quietly.
 *
 * <p>ECLIPSE is matched FIRST and exactly, ahead of the substring tests below it. Order matters
 * here in a way it does not for the others: the tests are substring matches on the whole type
 * string, so a future kind named for an eclipse of a different body — a lunar eclipse at a
 * coastal spot, say — must not be captured by whichever loose test happens to hit first.
 *
 * @param {string} type the topic type from the payload
 * @returns {'eclipse'|'tide'|'nlc'|'aurora'|'snow'|'plain'} the badge channel
 */
export function badgeChannel(type) {
  const t = String(type || '').toUpperCase();
  if (t === 'ECLIPSE') return 'eclipse';
  if (t.includes('TIDE') || t.includes('SURGE')) return 'tide';
  if (t.includes('NLC') || t.includes('NOCTILUCENT')) return 'nlc';
  if (t.includes('AURORA')) return 'aurora';
  if (t.includes('SNOW') || t.includes('FROST')) return 'snow';
  return 'plain';
}
