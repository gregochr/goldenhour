import { formatShiftedEventTimeUk } from './conversions.js';

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
 * <p>⚠️ <b>The condition that WOULD make a wrap reachable is now nameable.</b> The shared matrix
 * covers the whole roster, not just the origin's region, so any future surface that renders a
 * base-measured drive to a spot <em>outside</em> the origin's region — a "somewhere is good, just
 * not near you" list (§9.8), or a P8 sheet that reaches past the card descriptor — reopens it
 * immediately, because Keswick to the far north-east is hours rather than minutes. If that is
 * built, the day marker ships with it. The fix costs no new words — {@code HotTopicStrip}'s
 * {@code leadDayWord} already prints {@code Today}/{@code Tomorrow}/{@code Sat} beside a clock
 * time — but it does cost this function's return shape, so it is not free.
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
  // Finite AND non-negative. A negative drive cannot come from `GET /api/user/settings/reach`, but
  // the descriptor this reads is joined from a payload rather than computed here, and "leave AFTER
  // the sun is up" is the one wrong answer that would look like a real one.
  if (!Number.isFinite(driveMinutes) || driveMinutes < 0) return null;
  return formatShiftedEventTimeUk(eventTimeUtc, -(driveMinutes + setupMinutes));
}
