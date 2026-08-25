import { formatDriveDuration } from './briefingDisplay.js';

/**
 * The reach lens — how far the user is willing to drive, and what that hides.
 *
 * <h2>The label IS the threshold, and that is the point of deriving it</h2>
 *
 * <p>The design's mock disagrees with itself: {@code LIM={any:9999,'30':30,'60':60,'120':120}} at
 * {@code Plan Window First v2.html:457} against {@code RL={'30':'45 min','60':'1h 30','120':'2h 30'}}
 * at {@code :494}. Every tier is wrong by a consistent offset — the chip reading "45 min" gates at
 * 30. Plan §2.5 states the intent as "45 min / 1h 30 / 2h 30 as a <em>time</em> lens", so the labels
 * are the spec and {@code LIM} is mock shorthand for a fixture whose drive times happened to be
 * round.
 *
 * <p><b>So the label is computed from the threshold rather than written beside it.</b> A chip reading
 * "45 min" that hides a 40-minute drive is the single most damaging thing this control can do, and
 * the only way to make it impossible is to leave no second number to drift. The formatter is the one
 * every spot card already prints its drive line with, so a chip and the cards it gates speak one
 * vocabulary — {@code 1h 30min} rather than the mock's {@code 1h 30}, which is the deviation this
 * buys and is recorded in the plan's §5c.
 *
 * <h2>The day-derived default is the line between an outing and a trip</h2>
 *
 * <p>Plan §2.5: the default "is a pure function of the date (weekend vs weekday), so it needs no
 * storage, no column and no owner". That same threshold does a second job here — it is what
 * {@link isFarSpot} measures against, so a spot is marked <em>far</em> exactly when it is beyond
 * what today would ordinarily allow. The two uses are deliberately one number: the gate's starting
 * point and the mark's meaning are the same judgement, and splitting them would let a card call a
 * drive long that the control had just called ordinary.
 *
 * <h2>A lens with no data is not a gate</h2>
 *
 * <p>Plan §2.5, rule 1, and it is the <b>normal first-run state</b> rather than a failure
 * ({@code CloseToHomeService.java:156} says so in a comment). A spot with no drive time is
 * <em>unknown</em>, not out of reach: it passes every tier and is never far. {@link gateSpotsByReach}
 * and {@link isFarSpot} both key on {@code driveMinutes != null} for that reason, so a user with no
 * home postcode sees the control move and nothing else — a visible no-op, never a silently empty page.
 */

/** The tier that gates nothing. Its own id, so a stored value can name it. */
export const ANY_TIER_ID = 'any';

/**
 * The four tiers, tightest first — the order they are drawn in.
 *
 * <p>Ids are the threshold in minutes so a stored value is self-describing, and {@code label} is
 * derived rather than authored (see the class comment). {@code limitMinutes} of null means the tier
 * gates nothing at all, which is a different statement from a very large number: no arithmetic runs.
 */
export const REACH_TIERS = [45, 90, 150].map((limitMinutes) => ({
  id: String(limitMinutes),
  limitMinutes,
  label: formatDriveDuration(limitMinutes),
})).concat([{ id: ANY_TIER_ID, limitMinutes: null, label: 'Any' }]);

/** The tightest tier, which is also the weekday default. */
export const WEEKDAY_TIER_ID = REACH_TIERS[0].id;

/** The weekend default — the third of four, as the design's own fixture sets it. */
export const WEEKEND_TIER_ID = REACH_TIERS[2].id;

/**
 * Storage key for the reach tier, and <b>for nothing else</b>.
 *
 * <p>This started as one {@code photocast.planLens} key holding every lens setting, so that P11's
 * rating floor and location type could ride along — which meant writing it read–modify–write, to
 * avoid dropping fields this module does not own. <b>That was the wrong shape twice over.</b>
 *
 * <p>The design reason: reach "expires at the day roll" and P11's controls are "drilldown taste —
 * persists" (plan §5). Two settings with two different expiry policies in one object made the
 * policy a naming convention — the stamp had to be called {@code reachDay} so it could not be read
 * as covering the whole object — where separate keys make it structural. One key per concern is
 * also what {@code PLAN_RATING_KEY} already does.
 *
 * <p>The mechanical reason: a read–modify–write on shared storage re-persists whatever it finds
 * under that key, unvalidated. Nothing writes anything else there today, so nothing was actually
 * echoed — but CodeQL's {@code js/clear-text-storage-of-sensitive-data} flagged it, and correctly
 * as a <em>shape</em>: it models {@code localStorage} as a single store with no notion of keys, so
 * a {@code getItem} feeding a {@code setItem} is a conduit from every other write in the app,
 * {@code AuthContext}'s among them. The finding is a false positive about this key and a fair
 * comment about the pattern. Writing only the two fields this module owns removes both.
 *
 * <p>Convention follows {@code PLAN_RATING_KEY}: no version suffix until the stored shape changes
 * meaning, at which point bump the name rather than reinterpreting the old value.
 */
export const PLAN_REACH_KEY = 'photocast.planReach';

/** Saturday and Sunday, as {@code Date.getUTCDay} numbers them. */
const SATURDAY = 6;
const SUNDAY = 0;

/** The tier for an id, or null when nothing matches. */
export function tierById(id) {
  return REACH_TIERS.find((tier) => tier.id === id) || null;
}

/**
 * Whether an ISO date falls at the weekend.
 *
 * <p>Read at noon UTC so no timezone can move the day; the caller's date string is already
 * Europe/London's own. An unparseable string is <em>not</em> evidence of a weekend, so it reads as a
 * weekday — the tighter default, which rule 1 then makes harmless for the user who has no drive
 * times at all.
 *
 * @param {string} dateStr ISO `YYYY-MM-DD`
 * @returns {boolean} true on Saturday or Sunday
 */
export function isWeekend(dateStr) {
  const day = new Date(`${dateStr}T12:00:00Z`).getUTCDay();
  return day === SATURDAY || day === SUNDAY;
}

/**
 * Today's default tier id — the pure function of the date plan §2.5 specifies.
 *
 * <p>Friday is deliberately a weekday. The bar is one control over up to six windows spanning four
 * days, so an evening-versus-morning judgement would have to be made per window and could not be
 * expressed by a single default; the readout states which of the two it landed on so the choice is
 * never silent.
 *
 * @param {string} todayStr ISO `YYYY-MM-DD` in Europe/London
 * @returns {string} a tier id
 */
export function defaultTierIdFor(todayStr) {
  return isWeekend(todayStr) ? WEEKEND_TIER_ID : WEEKDAY_TIER_ID;
}

/**
 * The stored tier id, or null when there is none that still applies.
 *
 * <p>Null covers five different absences on purpose — no key, unparseable JSON, no {@code reach}
 * field, a tier id this build no longer has, and a choice made on an earlier day. All five mean the
 * same thing to the caller: fall back to today's derived default. Nothing is written back, so an
 * expired choice does not resurrect itself the moment it is read.
 *
 * @param {string} todayStr ISO `YYYY-MM-DD` in Europe/London
 * @returns {?string} a tier id, or null
 */
export function readStoredTierId(todayStr) {
  try {
    const raw = localStorage.getItem(PLAN_REACH_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || parsed.reachDay !== todayStr) return null;
    return tierById(parsed.reach) ? parsed.reach : null;
  } catch {
    return null;
  }
}

/**
 * Records a tier choice against the day it was made on.
 *
 * <p><b>A whole-value write of the two fields this module owns — it never reads storage back.</b>
 * Both come from arguments a caller controls: {@code tierId} is rejected by {@link tierById} before
 * it gets here, and {@code todayStr} is the provider's own Europe/London date. Nothing that was
 * previously stored, under this key or any other, can survive into what is written. See
 * {@link PLAN_REACH_KEY} for why that matters more than the field-preserving merge it replaced.
 *
 * @param {string} tierId   the chosen tier
 * @param {string} todayStr ISO `YYYY-MM-DD` in Europe/London
 */
export function writeStoredTierId(tierId, todayStr) {
  try {
    localStorage.setItem(PLAN_REACH_KEY, JSON.stringify({ reach: tierId, reachDay: todayStr }));
  } catch {
    // Quota exceeded or private-browsing restriction — the choice still applies for this session
  }
}

/**
 * Whether a spot is beyond what today's default reach allows.
 *
 * <p>The design draws a {@code far} spot card — tide-tinted border, tinted meta line — and plan §5a
 * held it back from P6 because "a card asserting 'beyond weekday reach' while nothing on screen can
 * widen it is the same failure as a header counting a set that was never filtered". The tier ships
 * here, so the mark ships with it.
 *
 * <p><b>Measured against today's default, not against the selected tier.</b> Against the selection
 * the mark could never fire — {@link gateSpotsByReach} has already removed anything beyond it. And
 * against a fixed tightest tier it would mark most of a Saturday's strip, which turns a signal into
 * a wash: on a day the user has said 2h 30min is fine, a one-hour drive is not a different kind of
 * commitment. Against the default it marks exactly what widening bought, which is a small set by
 * construction and is the honest answer to "what did that control just do".
 *
 * <p>A null default limit — only reachable if the default were ever "Any" — marks nothing, and so
 * does an unknown drive time (rule 1).
 *
 * @param {?number} driveMinutes    the spot's drive time, or null when unknown
 * @param {?number} defaultLimitMinutes today's default tier's threshold
 * @returns {boolean} true when the spot lies beyond today's ordinary reach
 */
export function isFarSpot(driveMinutes, defaultLimitMinutes) {
  if (driveMinutes == null || defaultLimitMinutes == null) return false;
  return driveMinutes > defaultLimitMinutes;
}

/**
 * The spots a tier leaves on screen.
 *
 * <p>Inclusive at the boundary: a 45-minute drive is within "45 min". A null limit returns the input
 * untouched, and so does a spot with no drive time — rule 1 again, and it is why this is a filter
 * over a nullable field rather than a comparison with a default of zero.
 *
 * @param {Array} spots        ordered descriptors from {@code buildWindowSpots}
 * @param {?number} limitMinutes the active tier's threshold, or null for "Any"
 * @returns {Array} the spots that pass, in the order they arrived
 */
export function gateSpotsByReach(spots, limitMinutes) {
  if (limitMinutes == null) return spots;
  return spots.filter((spot) => spot.driveMinutes == null || spot.driveMinutes <= limitMinutes);
}

/**
 * How much is on screen, and what the rating floor cost to get there — split so the leading number
 * can be emphasised on its own.
 *
 * <p>The second number appears <b>only when the floor actually removed something</b>, which is the
 * rule {@code browseCountLine} already holds one surface down: with nothing trimmed, {@code 138 of
 * 138} is a count dressed as a comparison. The handoff asks the strip to "state what it cost"
 * whenever a filter is on, and a floor that cost nothing has nothing to state — the pressed chip is
 * already on screen saying what is set.
 *
 * <p>The word is "spots across N windows", never "within reach": plan §2.5's rule 2 keeps
 * <em>reach</em> for a set that was actually gated on distance, and this pair of numbers is gated on
 * two axes at once. "across N windows" is also what makes the count honest when one location appears
 * in several of them — it means cards, and there are exactly that many on the page.
 *
 * @param {object} args
 * <p>⚠️ <b>The "within reach" wording is withheld where the reach axis cannot have acted</b>, which
 * is a reader with no home postcode: every drive time is unknown, an unknown drive passes every tier
 * (plan §2.5 — absence means unknown, never out of reach), and so {@code reachedCount} is simply
 * everything. Naming a gate that gated nothing is §6 clause 7's own sentence ("no count describes a
 * set that was never filtered"), and this is the page's ONE count statement (§4 A7), so it is the
 * most load-bearing place the claim could have been wrong. Found by an adversarial review of M5,
 * which had fixed the same claim in the window popup and stopped there. The two NUMBERS are
 * unchanged — what goes is the assertion about which set the denominator is.
 *
 * @param {number} args.spotCount    spots drawn across every window, after both gates
 * @param {number} args.reachedCount spots the rating floor chose from — after reach, before rating
 * @param {number} args.windowCount  windows drawn
 * @param {boolean} [args.reachMeasured] whether any drive time exists for the tier to gate on.
 *        Defaults {@code true}, the pre-M5 wording, for a caller that cannot answer
 * @returns {?{lead: string, rest: string}} the number to emphasise and the rest, or null when there
 *          are no windows to count across
 */
export function formatLensCount({
  spotCount, reachedCount, windowCount, reachMeasured = true,
}) {
  if (!(windowCount > 0)) return null;
  const windows = `${windowCount} window${windowCount === 1 ? '' : 's'}`;
  if (spotCount < reachedCount) {
    // "within reach" is not decoration — it names WHICH SET the denominator counts, and without it
    // this line and a window card's footer are two different claims wearing one shape.
    //
    // `reachedCount` is the spots that cleared the DRIVE limit; the numerator has cleared the drive
    // limit AND the rating floor. So "42 of 138 spots" invited the reading "138 spots exist" and
    // the arithmetic "96 were hidden by your rating" — when some of those 96 are simply further out
    // than the tier allows. Meanwhile a card's footer reads "3 of 12", where 12 IS every spot at
    // that window. Both were true; nothing on screen said they counted different things.
    //
    // Naming the gate here rather than qualifying the footer keeps the fix to the line whose
    // denominator is the surprising one, and leaves the footer's unqualified count meaning what an
    // unqualified count should mean: everything there is.
    const spots = `spot${reachedCount === 1 ? '' : 's'}`;
    return {
      lead: `${spotCount}`,
      rest: reachMeasured
        ? `of ${reachedCount} ${spots} within reach across ${windows}`
        : `of ${reachedCount} ${spots} across ${windows}`,
    };
  }
  return {
    lead: `${spotCount}`,
    rest: `spot${spotCount === 1 ? '' : 's'} across ${windows}`,
  };
}

/**
 * The bar's readout — what both lenses are set to and how much is on screen because of them.
 *
 * <p>Every clause is checked against §6. The tier label is the control's own word. The default
 * clause appears only when the active tier <em>is</em> the default, and names which of the two days
 * derived it, so the reader can tell a deliberate choice from an inherited one.
 *
 * <p><b>The rating clause is stated even when it is "any rating".</b> This line is a summary of the
 * two controls, and a summary that names one of them and silently drops the other reads as though
 * the page had only one lens — which was true until this change and is the misreading worth
 * spending twelve characters to prevent. It is the same reasoning that keeps the tier label on
 * screen while it says "Any".
 *
 * @param {object}  args
 * @param {string}  args.tierLabel   the active tier's label
 * @param {boolean} args.isDefault   whether the active tier is today's derived default
 * @param {boolean} args.weekend     whether today derived the weekend default
 * @param {string}  [args.ratingLabel] the active floor's label, e.g. {@code 4★+}
 * @param {number}  args.spotCount   spots drawn across every window
 * @param {number}  args.reachedCount spots the rating floor chose from
 * @param {number}  args.windowCount windows drawn
 * @returns {string} the readout
 */
export function formatLensReadout({
  tierLabel, isDefault, weekend, originDefault, ratingLabel, spotCount, reachedCount, windowCount,
  reachMeasured = true,
}) {
  const parts = [tierLabel];
  // ⚠️ The day word is only true while the default IS the day's. With an away origin the default
  // comes from the origin (`useReachLens`'s `defaultOverrideId`), so "weekday default" would name a
  // rule that is not in force — on a Tuesday, beside a caption reading "Drive from Keswick", it
  // would be the one line on the bar contradicting the one above it. "here" is the caption's own
  // subject, so the two read as one sentence rather than as two claims.
  if (isDefault) parts.push(originDefault ? 'default here' : `${weekend ? 'weekend' : 'weekday'} default`);
  if (ratingLabel) parts.push(ratingLabel.toLowerCase());
  const count = formatLensCount({ spotCount, reachedCount, windowCount, reachMeasured });
  if (count) parts.push(`${count.lead} ${count.rest}`);
  return parts.join(' · ');
}
