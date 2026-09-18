import PropTypes from 'prop-types';
import TideWave from './TideWave.jsx';
import { tierOf, wantPhrase } from '../../utils/mapTideFit.js';
import { eventWord } from '../../utils/windowFirstCards.js';

/**
 * The tide-fit block (tide-window-plan.md T5, design spec §4) — one component rendering BOTH
 * tiers this codebase serves (match/miss — the spec's "near" tier is not built, §4 #3 of the
 * plan), mounted by both the map callout and every solar row of the location sheet so the two
 * surfaces state one fact about one window in one wording.
 *
 * <h2>Match vs miss</h2>
 *
 * <p>Match: heading <b>Tide lands on the light</b>, body {@code fact.fitPhrase} (already the
 * complete server-formatted sentence — "high water, falling · HW 20:46 · 17m after sunset ·
 * 3.9 m"). Miss: heading <b>Wrong water, not wrong light</b>, the same kind of body (already
 * carrying its own "wants low water" clause — T1's {@code tideFitPhrase} miss form), then on its
 * own line either the jump or the denial (below). Omitted entirely — never a "no alignment" line
 * — when {@code fact} is null (not a coastal slot with a served tide state this window) or its
 * {@code fitPhrase} is null (no extremes to build one from), matching every other unmeasured-fact
 * row on this tab.
 *
 * <h2>The jump vs the denial</h2>
 *
 * <p>On a miss, {@code nextFitRow} is the resolved next-window-this-location-fits row (or falsy —
 * {@code null}/{@code -1}) that the CALLER found via {@code utils/mapTideFit.js#nextAlignedRow}
 * with no {@code want} argument — the any-want reading (tide-window-plan.md §1 #12: "the
 * callout's per-location jump passes no want"). A truthy row renders the text-button jump
 * ("Next low water on the light · Tomorrow sunset 20:25 ›"), calling {@code onSelectEv(row)} with
 * that SAME row object — never an index, matching the strip cells' own call at
 * {@code MapCallout.jsx:735}. A falsy one renders the italic denial ("Nothing in these four days
 * puts low water on the light here.") instead — never both, never neither, while {@code want}
 * resolves to something to name (see below).
 *
 * <h2>⚠️ Deliberate, documented deviation from the plan's illustrative prop list</h2>
 *
 * <p>The phase text names props {@code {fact, evRow, evRows, onSelectEv, horizonWord}}. That
 * signature cannot actually answer "does the reader see a jump or a denial" on its own: finding
 * the next-fit row needs the served alignment INDEX and the location's own key, neither of which
 * a pure-render component should be reaching into a briefing-shaped index to compute (and this
 * component is mounted by two hosts with two different "the rest of the rows" shapes — the map's
 * {@code evRows} and the sheet's own six windows — so a single {@code evRows} shape here would
 * force one host to pretend it is the other). Matching T3's own precedent for {@code stripModel}'s
 * three extra params, this component takes the ALREADY-RESOLVED {@code nextFitRow} and the
 * location's OWN wanted set ({@code want}) instead of {@code evRow}/{@code evRows}: the CALLER
 * (which already holds the alignment index, the evRows/sheet-rows list, and the location) resolves
 * the lookup once, via the tested {@code nextAlignedRow}, and hands this component a plain row
 * object to describe and to hand back to {@code onSelectEv} untouched — so this component reads no
 * height, no offset, no threshold, and no index at all; it only formats what it is given, which is
 * the whole point of a "pure render" component under CLAUDE.md's Backend-heavy licence.
 *
 * <h2>⚠️ A known, narrow edge: {@code want} is a LIVE read, {@code fact.fitPhrase} is a FROZEN one</h2>
 *
 * <p>{@code fact.fitPhrase} is baked into a cached forecast/briefing row at whatever time the
 * pipeline built it, and already contains its own "wants X" clause (T1's {@code TideWording}). This
 * component's {@code want} prop, by contrast, is read live off the CURRENT roster
 * ({@code location.tideType}, `MapCallout.jsx`/`LocationFourDaySheet.jsx` both read it off the
 * location object handed to them, which reflects the latest `GET /api/locations`). Between a build
 * and the next pipeline cycle (up to the freshness thresholds `FreshnessResolver` logs), an admin
 * editing that location's tide preference via {@code PUT /api/locations/{id}} would make this
 * block's own jump/denial line name a different water than {@code fitPhrase}'s "wants" clause one
 * line above it — a real, if narrow, contradiction on one card (found in adversarial review).
 * Closing it needs the pipeline to serve the want it built the phrase against (a new field beside
 * {@code tideFitPhrase}), which is a BACKEND change outside this frontend-only phase's scope; a
 * live-edited coastal location's tide preference between builds is rare enough, against a cache
 * window this short, that the plan accepts the window rather than block T5 on a T1 follow-up. Not
 * closed by this phase; recorded here rather than silently accepted.
 *
 * @param {object} props
 * @param {?{aligned: boolean, state: ?string, fitPhrase: ?string, shortfall: ?string}} props.fact
 *        this location's served tide-fit fact for the ACTIVE window, from
 *        {@code utils/locationSheet.buildTideAlignmentIndex} via {@code lookupForWindow} — null
 *        renders nothing
 * @param {?Array<string>} [props.want] the location's wanted {@code TideType} set
 *        ({@code location.tideType}) — joined with "or" for the jump/denial line's {@code <want>}.
 *        Null or empty omits that line entirely (a truthful claim needs something to name), while
 *        the heading and body still render — they come from {@code fact} alone
 * @param {(object|number|null)} [props.nextFitRow] the resolved next-fit EV/window row (any
 *        object; forwarded to {@code onSelectEv} untouched), or a falsy value ({@code null} or
 *        {@code -1}, matching {@code nextAlignedRow}'s own "no match" sentinel) for the denial.
 *        Read only on a MISS — a match never shows this line at all
 * @param {?Function} [props.onSelectEv] {@code (row) => void} — the jump's click handler, called
 *        with the exact {@code nextFitRow} object handed in
 * @param {string} [props.horizonWord] the denial's "these ___" clause — defaults to the design's
 *        own literal copy, "four days" (the sheet's own kicker word today,
 *        {@code utils/locationSheet.js#leadLine}'s {@code dayCount}). A caller whose horizon is
 *        not four days passes the real word instead of letting this default drift out of sync
 *        (plan §3 T5 task 1's own instruction)
 * @param {?number} [props.combinedRating] the served, COMBINED star for this same window (the
 *        figure rendered above this block — {@code MapCallout}'s {@code ratingRounded}, the
 *        location sheet row's {@code row.rating}) — read only to decide whether {@code
 *        fact.skyRating} says something the star does not (below). Never rendered itself; this
 *        block owns no star of its own.
 */
export default function TideFitBlock({
  fact, want = null, nextFitRow = null, onSelectEv = null, horizonWord = 'four days',
  combinedRating = null,
}) {
  if (!fact || !fact.fitPhrase) return null;
  const tier = tierOf(fact);
  if (!tier) return null;

  const wants = wantPhrase(want);
  const hasNextFitRow = nextFitRow != null && nextFitRow !== -1;
  // The sky visitor's own component score (tide gate lift, 2026-09-18,
  // docs/engineering/tide-window-plan.md §6 Q1) — Claude's rating of the light alone, with no
  // tide contribution averaged in. Stated beside the combined star wherever it says something the
  // star does not: always on a MISS (a tide-dimmed 3★ under "wrong water, not wrong light" needs
  // its own number to say the LIGHT was worth more than the star it sits beside), and on a MATCH
  // only when it actually differs from the served combined rating (a spring-aligned tide lifts a
  // 4★ sky to 5★, and that is worth stating; the ordinary case — the two already agree — says
  // nothing new, and an unknown combined rating is never asserted to "differ"). Never on the star
  // or swatch itself: the star and its colour are never re-coloured by this block (CLAUDE.md's
  // role-gating and confidence rules both keep the star/quality signal untouched by companion
  // channels, and this one follows the same discipline).
  const showSky = fact.skyRating != null
    && (tier === 'miss' || (combinedRating != null && fact.skyRating !== combinedRating));

  return (
    <div className="wf-tide-fit" data-testid="tide-fit-block" data-tier={tier}>
      <TideWave shortfall={fact.shortfall ?? null} />
      <span className="wf-tide-fit-text">
        <b>
          {tier === 'match' ? 'Tide lands on the light' : 'Wrong water, not wrong light'}
        </b>
        {fact.fitPhrase}
        {showSky && (
          <span data-testid="tide-fit-sky">{` · sky ${fact.skyRating}★`}</span>
        )}
        {tier === 'miss' && wants && (
          hasNextFitRow ? (
            <button
              type="button"
              data-testid="tide-fit-jump"
              className="wf-tide-fit-jump"
              onClick={() => onSelectEv?.(nextFitRow)}
            >
              {`Next ${wants} on the light · ${nextFitRow.dayLabel} ${eventWord(nextFitRow.eventType)} ${nextFitRow.time} `}
              <span aria-hidden="true">›</span>
            </button>
          ) : (
            <span data-testid="tide-fit-denial" className="wf-tide-fit-denial">
              {`Nothing in these ${horizonWord} puts ${wants} on the light here.`}
            </span>
          )
        )}
      </span>
    </div>
  );
}

TideFitBlock.propTypes = {
  fact: PropTypes.shape({
    aligned: PropTypes.bool,
    state: PropTypes.string,
    fitPhrase: PropTypes.string,
    shortfall: PropTypes.string,
    skyRating: PropTypes.number,
  }),
  want: PropTypes.arrayOf(PropTypes.string),
  // A plain object (forwarded to onSelectEv untouched; its own shape belongs to whichever caller
  // built it — a map EV row, or the sheet's own adapter row) or the number -1, matching
  // `nextAlignedRow`'s own "no match" sentinel.
  nextFitRow: PropTypes.oneOfType([PropTypes.object, PropTypes.number]),
  onSelectEv: PropTypes.func,
  horizonWord: PropTypes.string,
  combinedRating: PropTypes.number,
};
