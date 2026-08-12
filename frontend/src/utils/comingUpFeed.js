/**
 * The "Coming up" tab's derivation: {@code AlmanacEvent[]} from {@code GET /api/almanac} into the
 * row descriptors the pane paints.
 *
 * <h2>Why this file exists at all, when the backend already formatted most of it</h2>
 *
 * <p>Three things force a derivation layer, and only three. Everything else is passed through.
 *
 * <ol>
 *   <li><b>{@code meta}'s key order is randomised per backend restart.</b> {@code AlmanacEvent}'s
 *       canonical constructor runs {@code Map.copyOf}, which returns a salt-randomised immutable
 *       map — so the insertion order {@code metaOf} carefully builds is destroyed before the JSON
 *       is written. Iterating {@code Object.entries(meta)} would reorder the fact line every time
 *       the backend restarts. The order below is therefore imposed here, and it is the only place
 *       it exists.</li>
 *   <li><b>Dates arrive as bare ISO strings</b> — {@code startDate}, {@code endDate},
 *       {@code peakDate}, {@code figuresFrom}. Somebody has to turn them into "16–18 Aug", and it
 *       cannot be the backend: the span label depends on {@code today}, which is the reader's day,
 *       not the build's.</li>
 *   <li><b>Five {@code meta} values are FLAGS, not measurements</b> — {@code washedOut},
 *       {@code partialCoverage}, {@code startsBeforeWindow}, {@code endsAfterWindow} and
 *       {@code noAlignment} are the literal string {@code "true"} and are never emitted as
 *       {@code "false"}. Printing one is a defect; each has to become a sentence or be dropped.</li>
 * </ol>
 *
 * <p>What is <b>not</b> done here: no number is parsed, compared or re-formatted. {@code range} is
 * {@code "4.6 m"} and ships as {@code "4.6 m"}; {@code verdict} is a finished sentence fragment and
 * ships whole. CLAUDE.md's rule is to push formatting back rather than parse forward, and the one
 * value that tempts an exception — {@code rangeAnomaly}, which is {@code "+0.4"} with a sign, one
 * decimal and deliberately <em>no unit</em> — is handled by composing it into the same chip as
 * {@code range} so the metres are stated once and the two cannot read as different quantities. No
 * unit is invented for it.
 *
 * <h2>The allow-list, and why a strict one</h2>
 *
 * <p>{@code factsFor} below is an exhaustive allow-list in a fixed order: a key it does not name
 * renders nothing. That is a deliberate
 * choice against the alternative of humanising unknown keys, and the reason is that every value on
 * this endpoint needs a label the wire does not carry — {@code zhr} is the bare integer
 * {@code "150"}, {@code radiant} is the bare compass point {@code "NE"}. A generic renderer would
 * produce {@code "washedOut true"} and {@code "zhr 150"}, and the failure would be silent. Silence
 * for an unrecognised key is the safer direction and is what the rest of this codebase does with an
 * unknown discriminator.
 *
 * <h2>What the meteor row deliberately drops</h2>
 *
 * <p>{@code zhr}, {@code radiant} and {@code bestHours} are absent from the allow-list even though
 * they are present on every meteor entry, because {@code MeteorAlmanacSource} composes exactly
 * those three into its own {@code detail} sentence: "Perseids peaks, around 100 meteors an hour at
 * best under a dark sky, radiating from the NE. Best watched 01:00–04:00." Rendering them again as
 * chips states the same three facts twice on one row.
 *
 * <p>{@code moonIllumination} is kept, and the asymmetry is deliberate rather than an oversight in
 * the same rule. On a dark peak the prose says nothing about the moon at all, and the moon is the
 * single fact that decides whether a shower is worth the drive — so without the chip the good
 * nights are the ones carrying the least information. On a washed-out peak the sentence does
 * mention it ("The moon is 80% lit that night…"), so those rows genuinely say it twice: once as
 * prose and once as a scannable number. That is accepted. Suppressing the chip when the prose
 * happens to mention it would make the fact line change shape by row, and would hide the number on
 * exactly the rows where a reader is deciding to stay in.
 */

/** Value every boolean-ish `meta` key carries. Absence is the negative; `"false"` is never sent. */
const FLAG_TRUE = 'true';

/**
 * The two types whose {@code meta} can vanish because a figure could not be derived.
 *
 * <p><b>The wire's own {@code datesOnly} is NOT the degrade signal, and reading it as one puts a
 * "something is missing" caveat on a healthy row.</b> Empty {@code meta} means different things by
 * source. {@code TideAlmanacSource} drops the whole map — range, verdict, location and all —
 * when no day of the run could be derived from stored extremes, and that is a real absence a
 * reader must be told about. {@code NlcSeasonAlmanacSource}'s {@code meta} holds nothing but two
 * clip flags, so an <em>unclipped</em> season is empty by construction: it means the span shown is
 * the whole season, which is the good case. The remaining three sources always populate
 * {@code meta} — meteor writes five keys unconditionally, and equinox, solstice and supermoon each
 * write a {@code peakDate} from pure arithmetic — so they cannot reach the state at all.
 *
 * <p>So the caveat is gated on the type as well as on the absence. It is the one place this module
 * needs to know what a type is, and it is here rather than in the renderer so the rule has a single
 * home and a single test.
 */
const TYPES_WITH_FIGURES = new Set(['spring-tide', 'king-tide', 'eclipse']);

/** Midday UTC, so no timezone can push a bare `YYYY-MM-DD` onto the day either side. */
function atMidday(dateStr) {
  return new Date(`${dateStr}T12:00:00Z`);
}

/** `12` — day of the month, no padding. */
function dayNum(dateStr) {
  return atMidday(dateStr).toLocaleDateString('en-GB', { day: 'numeric', timeZone: 'UTC' });
}

/** `Aug` — short month. en-GB renders September as `Sept`, which is why the column is measured. */
function monthName(dateStr) {
  return atMidday(dateStr).toLocaleDateString('en-GB', { month: 'short', timeZone: 'UTC' });
}

/**
 * `12 Aug`, joined with a NON-BREAKING space.
 *
 * <p>The date column is 112px of 11px mono and its content wraps. Seen on the running app: the
 * equinox's qualifier broke as "Exact day 22" / "Sept", splitting a date across two lines. A
 * regular space lets the line break at whichever of the two gaps happens to fall at the edge; the
 * non-breaking one makes the date atomic, so a phrase that has to wrap wraps <em>before</em> it.
 */
function dayAndMonth(dateStr) {
  return `${dayNum(dateStr)}\u00A0${monthName(dateStr)}`;
}

/** `Wed 12 Aug` — the form used wherever one exact day is being named. */
function longDay(dateStr) {
  const dow = atMidday(dateStr).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' });
  return `${dow} ${dayAndMonth(dateStr)}`;
}

/**
 * Whole days from `fromStr` to `toStr`; negative when `toStr` is earlier, NaN when either is not a
 * date.
 *
 * <p>Both ends are taken at midday UTC and UTC has no daylight saving, so the difference is always
 * an exact multiple of a day even across the October clock change that falls inside a feed built in
 * August. The rounding is belt and braces rather than load-bearing.
 */
function daysBetween(fromStr, toStr) {
  const ms = atMidday(toStr).getTime() - atMidday(fromStr).getTime();
  return Math.round(ms / 86400000);
}

/**
 * The date line under the lead word.
 *
 * <p>A weekday appears only on a single-day entry. On a span it is dropped rather than doubled:
 * the column is 112px of 11px mono, and "Wed 12 – Thu 13 Aug" does not fit it, while the two
 * weekdays are the least useful part of a range a reader is reading as a block of nights.
 */
function spanLabel(startDate, endDate, clippedStart, clippedEnd) {
  // A clipped edge is NOT a date this row may print. `NlcSeasonAlmanacSource` cuts the span to the
  // request window, so on a clipped start `startDate` is simply the day the feed begins — printing
  // "9–10 Aug" for a season that opened in May states a beginning that did not happen, and a note
  // underneath saying it began earlier then contradicts the line above it. Only the real edge is
  // named, and when neither is real the row says so and names neither.
  if (clippedStart && clippedEnd) return 'In progress';
  if (clippedStart) return `Until ${dayAndMonth(endDate)}`;
  if (clippedEnd) return `From ${dayAndMonth(startDate)}`;
  if (startDate === endDate) return longDay(startDate);
  const startMonth = monthName(startDate);
  const endMonth = monthName(endDate);
  // En dash throughout, matching every other range this project prints (the backend's own
  // `bestHours` uses U+2013 too). Tight for one month, spaced across two, which is the ordinary
  // typographic rule and is what keeps "30 Aug – 2 Sept" readable.
  if (startMonth === endMonth) return `${dayNum(startDate)}–${dayAndMonth(endDate)}`;
  return `${dayAndMonth(startDate)} – ${dayAndMonth(endDate)}`;
}

/**
 * The bold lead word: how far away this is.
 *
 * <p><b>A clipped entry reads "On now", never "Today", and that is the whole point of the flag.</b>
 * {@code NlcSeasonAlmanacSource} does not walk outwards the way the tide and supermoon sources do —
 * it clips the span to the request window and sets {@code startsBeforeWindow} to say so. So its
 * {@code startDate} is frequently today while the season began weeks ago, and a lead word derived
 * from the date alone would announce that an eleven-week season starts this morning.
 */
function leadWord(startDate, endDate, todayStr, clippedStart) {
  const until = daysBetween(todayStr, startDate);
  // No usable today means no relative word — the context default for `todayStr` is the empty
  // string, and every comparison against it is NaN. Returning null drops the element; the arithmetic
  // would otherwise reach the screen as "In NaN days", which is the one output worse than silence.
  // The dates beside it are absolute and stay correct, so the row still says when it is.
  if (!Number.isFinite(until)) return null;
  // ⚠️ REACHABLE, despite the obvious argument that the service builds from today. The feed's
  // "today" is UTC and this one is Europe/London, so during BST between midnight and 01:00 the
  // reader's day is one ahead of the build's — and `MeteorAlmanacSource` emits a peak lying exactly
  // on the build day. Such a row genuinely did end yesterday by the reader's clock, so "Passed" is
  // the true word for it rather than a fallback; the alternative was "In -1 days".
  // `useComingUpFeed` carries the whole two-clock argument.
  if (daysBetween(todayStr, endDate) < 0) return 'Passed';
  if (clippedStart || until < 0) return 'On now';
  if (until === 0) return 'Today';
  if (until === 1) return 'Tomorrow';
  return `In ${until} days`;
}

/**
 * The word that introduces {@code peakDate}, by type.
 *
 * <p>One word each rather than a single generic "Peak", because the backend's {@code peakDate}
 * means a different thing per source and only one of them is a peak: the tide's is the biggest
 * range of the run, the supermoon's is the night nearest perigee, and the equinox/solstice anchor
 * is an instant that does not peak at all.
 */
const PEAK_WORD = {
  'spring-tide': 'Biggest',
  'king-tide': 'Biggest',
  supermoon: 'Closest',
  equinox: 'Exact day',
  solstice: 'Exact day',
  eclipse: 'Maximum',
};

/** Fallback for a type this build has never heard of, so a future source still reads sensibly. */
const PEAK_WORD_DEFAULT = 'Peak';

/**
 * The qualifier under the dates: what the span's edges mean, or which day inside it is the one.
 *
 * <p>The two cases are mutually exclusive on today's wire — only {@code nlc-season} sets the clip
 * flags and it never carries a {@code peakDate} — but the precedence is written down rather than
 * assumed, because a source added later could carry both and silently losing one would be worse
 * than choosing badly between them.
 */
function whenNote(event, meta) {
  const clippedStart = meta.startsBeforeWindow === FLAG_TRUE;
  const clippedEnd = meta.endsAfterWindow === FLAG_TRUE;
  if (clippedStart && clippedEnd) return 'Began before and continues past this list';
  if (clippedStart) return 'Began before this list';
  if (clippedEnd) return 'Continues past this list';
  if (!meta.peakDate) return null;
  // Redundant on a one-day entry, where the peak IS the span, and on an entry whose peak the
  // dates already state. Suppressed rather than printed as a second copy of the same date.
  if (event.startDate === event.endDate) return null;
  const word = Object.hasOwn(PEAK_WORD, event.type) ? PEAK_WORD[event.type] : PEAK_WORD_DEFAULT;
  return `${word} ${dayAndMonth(meta.peakDate)}`;
}

/** A dim segment: the label half of a chip. */
const base = (text) => ({ text, tone: 'base' });

/** A full-ink 600 segment: the value half. Matches `.wf-facts [data-tone='strong']`. */
const strong = (text) => ({ text, tone: 'strong' });

/**
 * The fact line's chips, in a fixed order this module owns.
 *
 * <p>Ordered water first, then the light, then the sky — the sequence a reader asks them in. The
 * order is hard-coded because {@code meta}'s own key order is randomised per backend restart
 * (see this file's header); reading it from the payload would reshuffle the line on every deploy.
 *
 * @param {object}  meta        the entry's derived facts
 * @param {boolean} spansOneDay whether the entry's span is a single day, which decides whether a
 *                              date inside the span is worth naming or is a second copy of the
 *                              dates already printed to the left
 */
function factsFor(meta, spansOneDay) {
  const facts = [];

  if (meta.range) {
    // One chip, not two. `rangeAnomaly` is "+0.4" — signed, one decimal, and deliberately WITHOUT
    // a unit — so beside a separate "4.6 m" chip it reads as a different quantity. Composed into
    // the same chip the metres are stated once and govern both numbers, and no unit is invented.
    const segments = [base('range '), strong(meta.range)];
    if (meta.rangeAnomaly) segments.push(base(` · ${meta.rangeAnomaly} vs average`));
    facts.push({ segments });
  }

  // King runs only — `TideRunBuilder` passes null for a spring run, so this chip is what tells the
  // two apart on the fact line as well as in the title.
  if (meta.highWater) facts.push({ segments: [base('high water '), strong(meta.highWater)] });

  // No "low water"/"high water" label: the phrase already opens with LW or HW and carries its own
  // clock time. "alignment" names the question it answers instead of repeating its answer.
  //
  // The two keys are exclusive and the backend guarantees it — `alignment` is the clause for the
  // one day of the run whose water lands in the light, `noAlignment` the flag saying no day does.
  // They used to be one key carrying the biggest day's verdict, which always has a sentence for it,
  // so an unaligned run read `alignment  peak range · LW 2h12 after sunset`: a claim of alignment
  // over a range restatement over a low water two hours into the dark.
  //
  // The day is named because a run is several days and "the tide lines up with sunrise" is not
  // actionable without knowing which morning. Suppressed on a one-day run, where the dates already
  // say it — the same rule `whenNote` applies to `peakDate`.
  if (meta.alignment) {
    const onDay = spansOneDay || !meta.alignmentDate
      ? '' : ` on ${dayAndMonth(meta.alignmentDate)}`;
    facts.push({ segments: [base('alignment '), strong(`${meta.alignment}${onDay}`)] });
  } else if (meta.noAlignment === FLAG_TRUE) {
    facts.push({ segments: [base('alignment '), strong('none — water misses the light')] });
  }

  if (meta.moonIllumination) {
    facts.push({ segments: [base('moon '), strong(meta.moonIllumination)] });
  }

  // Eclipse. Two measurements, and then the recurrence line last, because it is the one fact that
  // is about the event's place in a century rather than about the night itself.
  if (meta.coverage) facts.push({ segments: [base('at maximum '), strong(meta.coverage)] });
  if (meta.maximum) facts.push({ segments: [base('peaks '), strong(meta.maximum)] });
  // Rendered whole and unlabelled. `EclipseAlmanacSource` composes the sentence — "nothing
  // comparable from the UK until 2081" — precisely so this file keeps its rule that no number is
  // parsed, compared or re-formatted here; a bare `returnYears` integer would have broken it.
  if (meta.rarity) facts.push({ segments: [strong(meta.rarity)] });

  return facts;
}

/**
 * The caveat under the facts: whose numbers these are.
 *
 * <p>Never omitted when there are figures. A tide run may span a whole coast and the figures are
 * one location's — CLAUDE.md settled that for the Plan tab's tide chart ("named in the footer,
 * since alignment differs by ~20 min across a coastline a topic may span") and the same numbers are
 * on this row.
 *
 * <p>{@code figuresFrom} is the fallback path: it means no single day of the run was flagged as its
 * biggest, so the figures come from the first day that could be derived at all. Saying which day,
 * and that the rest is not covered, is the difference between a caveat and a false claim that the
 * whole run looks like this.
 */
function attributionFor(meta) {
  if (!meta.location) return null;
  if (meta.figuresFrom) {
    const day = dayAndMonth(meta.figuresFrom);
    return `Figures for ${meta.location} on ${day} — the rest of the run is not covered yet`;
  }
  return `Figures for ${meta.location}`;
}

/**
 * Turns one wire entry into a row descriptor.
 *
 * @param {object} event   an `AlmanacEvent` as served
 * @param {string} todayStr the reader's today, `YYYY-MM-DD`
 * @returns {object} the row
 */
function toRow(event, todayStr) {
  // `meta` is ABSENT from the JSON when empty — @JsonInclude(NON_EMPTY) omits the key rather than
  // sending `{}` — so every read goes through this one normalisation. The wire also carries a
  // `datesOnly` boolean (Jackson serialises the `isDatesOnly()` getter), but it is a side effect of
  // bean naming rather than a declared record component and no backend test pins it, so the
  // degraded state is derived from the thing that IS pinned: the absence of `meta`.
  const meta = event.meta || {};
  const clippedStart = meta.startsBeforeWindow === FLAG_TRUE;
  const facts = factsFor(meta, event.startDate === event.endDate);
  return {
    // Composite: ~6 spring-tide entries share a title and a type across one 90-day feed, and a
    // supermoon and a tide run can share a start date. Type plus both ends is unique by
    // construction — one source cannot emit two entries with the same type over the same span.
    key: `${event.type}:${event.startDate}:${event.endDate}`,
    type: event.type,
    title: event.title,
    detail: event.detail,
    // Marker-on-exception, the treatment this project already gives its confidence channel: the
    // reassuring value is the page's stated default and goes unmarked, and only the provisional one
    // is called out. Every entry the five current sources emit is ALMANAC, so a chip rendered on
    // all of them would be a word that never varies; the pane's footer states it once instead. A
    // FORECAST entry — which plan §3 reserves for the ~3-day hot-topic path — is marked, and an
    // unrecognised kind is marked by nothing rather than by a raw enum name.
    kindLabel: event.kind === 'FORECAST' ? 'Forecast' : null,
    lead: leadWord(event.startDate, event.endDate, todayStr, clippedStart),
    dates: spanLabel(
      event.startDate, event.endDate, clippedStart, meta.endsAfterWindow === FLAG_TRUE,
    ),
    whenNote: whenNote(event, meta),
    facts,
    attribution: attributionFor(meta),
    // The degrade rule reaching a screen for the first time: "we know when, not how big". Gated on
    // the type as well as the absence — see TYPES_WITH_FIGURES for why an empty `meta` is a healthy
    // state on three of the five sources and a real absence on only one.
    //
    // Read off the NORMALISED map, not off `event.meta`. The three ways a caller can say "nothing
    // here" — the key absent (what @JsonInclude(NON_EMPTY) actually sends), null, and `{}` (what a
    // hand-written fixture writes) — must collapse to one state. `!event.meta` is false for `{}`,
    // so that spelling would silently drop the caveat from any test or future payload that sent an
    // empty object rather than omitting the key.
    figuresMissing: TYPES_WITH_FIGURES.has(event.type) && Object.keys(meta).length === 0,
  };
}

/**
 * Builds the feed's rows, in the order the payload already put them.
 *
 * <p><b>Never re-sorted.</b> {@code AlmanacService} sorts by start date, then by span length, then
 * by type, and that is the order to paint: chronological, with the shorter of two spans starting
 * the same day first. Sorting by {@code peakDate} instead would move a walked-outwards tide run,
 * and grouping by type would break the one spine a "what is coming" list has.
 *
 * @param {Array}  events   the wire payload, or null/undefined before it arrives
 * @param {string} todayStr the reader's today, `YYYY-MM-DD`
 * @returns {Array} row descriptors, one per entry
 */
export function buildComingUpRows(events, todayStr) {
  if (!Array.isArray(events)) return [];
  return events.map((event) => toRow(event, todayStr));
}
