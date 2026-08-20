import { formatInstantUk, parseUtcInstant } from './conversions.js';
import { ukDateStr } from './mapDates.js';

/**
 * When to leave, so the drive and the setup are done before the light is.
 *
 * <h2>A rating is an opinion; a departure time is a plan</h2>
 *
 * <p>Heat-field plan §4.6. The spot card already prints what the drive costs; this turns that
 * number into the one thing a reader can act on without arithmetic — the strip stops being a
 * ranking and becomes a shortlist of alarms. It is the same quantity on the card, the drill-down
 * sheet and the peek, because all three call this one function on the same two fields of the same
 * descriptor: a pure function of identical inputs cannot give two surfaces two answers.
 *
 * <h2>Null is the answer whenever any term is unknown — never a guess</h2>
 *
 * <p>Two of the three terms can be absent and each absence means something different, but both
 * yield the same silence:
 *
 * <ul>
 *   <li><b>No drive time.</b> The normal first-run state for a user with no home postcode, and
 *       plan §2.5's rule: absence means "unknown", never "out of reach". The card omits its reach
 *       line in exactly that case, so a leave-by line derived from a drive nobody measured would
 *       be the one figure on the card with nothing behind it.</li>
 *   <li><b>No event time.</b> Defensive rather than observed, and worth saying which: every live
 *       slot carries one — {@code solarEventTime} has been a {@code BriefingSlot} component since
 *       the first briefing commit, and {@code BriefingSlotBuilder} returns <em>null</em> rather
 *       than a slot it could not time — so this guards a payload shape nobody has seen, not one
 *       the schema used to emit. {@code formatShiftedEventTimeUk} answers null for an absent or
 *       unparseable instant, so the degrade needs no branch of its own here.</li>
 * </ul>
 *
 * <h2>The event time is the SPOT's, not the window's</h2>
 *
 * <p>{@code buildWindowSpots} carries each slot's own {@code solarEventTime}, and that is what the
 * callers pass. Sunrise on this roster spans tens of minutes across the country, and the leave-by
 * line is advice to one person driving to one place — the window header's single time is chosen
 * for determinism across a region set ({@code BriefingEventSummary.earliestEventTime}), which is
 * the right answer to a different question. {@code solarEventTimes.js} records the same
 * distinction for the run dialog.
 *
 * <h2>London, and the wrap that follows from it</h2>
 *
 * <p>The result is formatted on {@code Europe/London} by {@code formatShiftedEventTimeUk}, which is
 * the calendar and clock every other time on this screen is stated in. That matters most exactly
 * where the arithmetic crosses midnight: a 04:40 BST sunrise is 03:40 UTC, and a 3h45 drive leaves
 * at 23:35 <em>UTC on the previous day</em> — which is <b>00:35</b> on the reader's clock. Doing
 * the subtraction and then printing the UTC digits would put a leave time an hour out and on the
 * wrong side of midnight, which is the whole reason this delegates rather than formatting itself.
 *
 * <h2>Two things it does not say, both deliberate and both worth knowing</h2>
 *
 * <p><b>No day marker.</b> The rendered value is {@code HH:mm}, and a long drive to an early
 * sunrise genuinely lands the evening before ("leave 23:20" for an 04:40 sunrise). The card's
 * header names the <em>event's</em> day ("Tomorrow sunrise"), so in that case it names the day the
 * departure is <em>not</em> on — the reader recovers it by subtracting the drive printed one line
 * above. Left bare because the wrap needs a drive longer than the event's hour (over four hours,
 * for a UK sunrise), which is beyond every bounded reach tier and reachable only on {@code Any};
 * a second date vocabulary on every card to disambiguate that tail is the worse trade. Measured
 * rather than assumed: the roster spans 53.76–55.77°N, where the earliest sunrise is 04:23, so a
 * wrap needs over <b>4h03</b> of driving against a longest realistic catalogue drive of about 2h20
 * — and every wrapping card is a {@code far} card long before it wraps.
 *
 * <p><b>P7 re-checked this, as P5 required, and it holds — with a different guard.</b> P5 upheld
 * the charge only as a stale comment on the grounds that no HOME drive can reach four hours; the
 * origin move measures drives from a region base instead, which is exactly the change that could
 * have broken that basis. It does not, and the reason is structural rather than arithmetic:
 * {@code gateSpotsByOrigin} scopes an away window to the origin's <em>own</em> region, so every
 * drive this function is ever handed under an away origin is base-to-somewhere-in-that-region.
 * Those are county-scale and shorter than the home drives the ceiling was measured against — and
 * the away default tier is <b>90</b> rather than the weekend's 150, so the {@code far} mark fires
 * sooner too. Both clauses moved in the safe direction.
 *
 * <p>⚠️ <b>P8 built that surface, and the day marker shipped with it — on a SECOND function.</b>
 * The condition P7 named is exactly {@code LocationFourDaySheet}: reached from search, which
 * matches the whole roster rather than the scope, over a shared matrix that covers the whole roster
 * too, so Keswick to the far north-east is hours rather than minutes and a wrap is ordinary there.
 * The home arm has the same hole from the other end — {@code GET /api/user/settings/reach} also
 * covers the whole roster. {@link leaveByParts} is where that surface reads its departure, and it
 * returns the day as data. This function's shape is <b>unchanged</b>, deliberately: it is called
 * once per rendered spot card and per peek, {@code formatInstantUk} builds an {@code Intl}
 * formatter per call (the cost {@code buildWindowSpots} records for the same reason), and the two
 * bare-{@code HH:mm} surfaces still sit inside the ceiling this section measures. The arithmetic
 * itself is shared rather than copied, so the two cannot answer differently.
 *
 * <p><b>No mention of the twenty minutes.</b> A reader checking the arithmetic against the card's
 * own figures (event time minus drive) lands twenty minutes after this answer, and nothing on the
 * card names the difference. That is the design's own line ({@code ↰ leave 03:50}, bundle §3) and
 * the alternative is a legend on a 10px line; the term is stated here and in {@link SETUP_MINUTES}
 * instead. It becomes visible the moment §9.5 makes it a setting.
 */

/**
 * Minutes to park, walk in and set up, before the light matters.
 *
 * <p>A client constant, per plan §3 — nothing on the backend knows it, and the drive time it is
 * added to is per-user data that never rides the shared briefing payload. Making it a user setting
 * is deferred with {@code GLANCE} (§9.5); until then one number for everyone is honest, because
 * the alternative is not a better estimate but a hidden one.
 */
export const SETUP_MINUTES = 20;

/**
 * The time to leave for a spot, on the UK clock, or null when it cannot be known.
 *
 * @param {string|Date|null} eventTimeUtc the slot's own {@code solarEventTime} — a bare UTC
 *        instant as the backend serialises {@code LocalDateTime}, a {@code Z}-suffixed one, or a
 *        {@code Date}. Absent or unparseable yields null.
 * @param {?number} driveMinutes this user's drive time to the spot. Null, absent, non-numeric or
 *        negative yields null — an unmeasured or impossible drive is not a departure time. It comes
 *        from {@code spot.driveMinutes}, which has exactly one producer (the reach join in
 *        {@code buildWindowSpots}) and is the same field the card's reach line prints — so P7's
 *        origin move reaches this line for free <em>if</em> it overwrites that value, and silently
 *        misses it if it adds {@code localDriveMinutes} beside it. It overwrites: P7 swaps the
 *        whole reach map at the provider ({@code planOrigin.originReachMap}), so the reach line
 *        and the leave line read one journey by construction.
 * @param {number} [setupMinutes] minutes to allow on arrival; defaults to {@link SETUP_MINUTES}.
 * @returns {?string} `HH:mm` on {@code Europe/London}, or null
 */
export function leaveBy(eventTimeUtc, driveMinutes, setupMinutes = SETUP_MINUTES) {
  const moment = departure(eventTimeUtc, driveMinutes, setupMinutes);
  return moment ? formatInstantUk(moment.leave, HH_MM) : null;
}

/** The one field set both renderings ask `Intl` for. */
const HH_MM = { hour: '2-digit', minute: '2-digit' };

/**
 * The event instant and the departure instant, or null when either term is unknown.
 *
 * <p>Both exported functions read this, so the arithmetic exists once. That matters more than the
 * three lines it saves: {@link leaveBy} and {@link leaveByParts} print the same departure on two
 * surfaces of the same screen, and a second subtraction would be a second chance to answer
 * differently. {@code leaveByParts}' own test pins the two against each other across a table that
 * includes a wrap, so the sharing is proven rather than asserted.
 *
 * @param {string|Date|null} eventTimeUtc the slot's own event instant
 * @param {?number} driveMinutes          this user's drive to the spot
 * @param {number} setupMinutes           minutes to allow on arrival
 * @returns {?{event: Date, leave: Date}} the pair, or null
 */
function departure(eventTimeUtc, driveMinutes, setupMinutes) {
  // Finite AND non-negative. A negative drive cannot come from `GET /api/user/settings/reach`, but
  // the descriptor this reads is joined from a payload rather than computed here, and "leave AFTER
  // the sun is up" is the one wrong answer that would look like a real one.
  if (!Number.isFinite(driveMinutes) || driveMinutes < 0) return null;
  const event = parseUtcInstant(eventTimeUtc);
  if (!event) return null;
  return { event, leave: new Date(event.getTime() - (driveMinutes + setupMinutes) * 60000) };
}

/**
 * The departure, with the UK day it falls on — for the one surface that can outrun the ceiling
 * {@link leaveBy}'s bare {@code HH:mm} rests on.
 *
 * <h2>Why this exists, and the exact condition that called it into being</h2>
 *
 * <p>{@link leaveBy} prints no day marker, and its Javadoc argues the case twice: P5 measured a
 * wrap as needing over <b>4h03</b> of driving against a longest realistic catalogue drive of about
 * 2h20, and P7 re-checked it under the origin move and found the away arm <em>safer</em> — because
 * {@code gateSpotsByOrigin} scopes an away window to the origin's own region, so every drive a spot
 * card is handed under an away origin is base-to-somewhere-in-that-region.
 *
 * <p>The same Javadoc then names the condition that reopens it: <em>"any future surface that renders
 * a base-measured drive to a spot outside the origin's region … a P8 sheet that reaches past the
 * card descriptor … If that is built, the day marker ships with it."</em> That surface is
 * {@code LocationFourDaySheet}. It is reached from search, search matches the whole roster rather
 * than the scope, and the shared matrix covers the whole roster too — so Keswick to the far
 * north-east is hours, and a 04:2x sunrise minus that drive minus setup lands on the previous
 * evening. The home arm has the same hole from the other end: {@code GET /api/user/settings/reach}
 * also covers the whole roster, and no postcode in it is guaranteed to be within 4h of every spot.
 *
 * <p>So the day is <b>derived, never assumed</b>: this returns the departure's own UK date beside
 * the clock time and lets the caller compare it with the event's. Nothing is conditioned on a
 * measured ceiling, because a ceiling that has already moved twice is not a thing to condition on.
 *
 * <h2>Two dates rather than one flag</h2>
 *
 * <p>{@code sameDay} is what a renderer branches on; {@code date} is what it needs to <em>name</em>
 * the day, and the caller has the vocabulary for that ({@code Today}/{@code Tomorrow}/{@code Sat})
 * while this module has none. Both are read on {@code Europe/London}, the calendar the clock time
 * beside them is already formatted on — comparing a London clock against a browser-local date is
 * how a card ends up naming the wrong side of midnight, which is the whole failure this prevents.
 *
 * @param {string|Date|null} eventTimeUtc the slot's own {@code solarEventTime}
 * @param {?number} driveMinutes          this user's drive to the spot; see {@link leaveBy}
 * @param {number} [setupMinutes]         minutes to allow on arrival; defaults to
 *        {@link SETUP_MINUTES}
 * @returns {?{time: string, date: string, sameDay: boolean}} the departure's UK clock time, the UK
 *          date it falls on, and whether that is the event's own date — or null on the same terms
 *          {@link leaveBy} answers null
 */
export function leaveByParts(eventTimeUtc, driveMinutes, setupMinutes = SETUP_MINUTES) {
  const moment = departure(eventTimeUtc, driveMinutes, setupMinutes);
  if (!moment) return null;
  const date = ukDateStr(moment.leave);
  return { time: formatInstantUk(moment.leave, HH_MM), date, sameDay: date === ukDateStr(moment.event) };
}
