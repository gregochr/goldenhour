import React, {
  useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState,
} from 'react';
import PropTypes from 'prop-types';
import {
  aspect, bbox, centroid, clamp, drawGeo,
} from '../utils/heatField.js';
import { useHeatCanvas } from '../hooks/useHeatCanvas.js';
import { useIsMobile } from '../hooks/useIsMobile.js';
import { POINT_SCORE_INDEX } from '../utils/heatSpots.js';
import {
  badgeChannel,
  BLANKED_STAND_DOWN_SPOKEN,
  BLANKED_STAND_DOWN_VISIBLE,
  formatRegionMean,
  shortVerdictLabel,
} from '../utils/windowFirstCards.js';
import { beyondRegions, GLANCE_MINUTES } from '../utils/planningArea.js';
import { placeWithNudges } from '../utils/labelPlacement.js';
import { scopeRegions, scopeSpots } from '../utils/planOrigin.js';
import { getMode, rampGradientCss, rampRgb, rgb } from '../utils/scoreRamp.js';
import { spotBadgeStyle } from '../utils/windowFirstSpots.js';
import { confidenceScalar, daysOut, resolveConfidence } from '../utils/confidenceUtils.js';
import { topMovers } from '../utils/movement.js';
import { buildWindowMatrix, MATRIX_ROW } from '../utils/windowFirstMatrix.js';
import { buildFourDayTiles, glanceTopicsByColumn } from '../utils/fourDayTiles.js';
import {
  buildSpread, formatSpreadCount, poolWithinReach, spokenPoolPhrase, spreadBandsSpoken,
  spreadBars, spreadCountLayout, spreadCountLayoutAcross, spreadQualifier, spreadRowState, spreadTitle, unratedBar,
  unratedPhrase,
} from '../utils/windowFirstSpread.js';
import { thinVerdictCount } from '../utils/windowFirstThinSample.js';
import { buildTopicIndex, chipClock, windowTopics } from '../utils/windowFirstTopics.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { leaveBy } from '../utils/leaveBy.js';
import { DIRECTION_WORD, STATE_WORD } from '../utils/windowFirstRows.js';
import { tideRun } from '../utils/windowFirstTideRun.js';
import { stoodPhrase } from '../utils/mapStandDown.js';
import TideWave from './map/TideWave.jsx';
import WindowGlanceDayTopics, { GlanceTopicDoor, GlanceTopicKey } from './WindowGlanceDayTopics.jsx';
import ProUpsellDoor, {
  BEST_BETS_UPSELL_BODY, BEST_BETS_UPSELL_HEADING, BEST_BETS_UPSELL_LABEL,
} from './shared/ProUpsell.jsx';
import { prefersReducedMotion } from '../utils/askCamera.js';
import { picksNoteText } from '../utils/pickGeography.js';
import { SubRegionActionButton } from './subregions/SubRegionActions.jsx';

/**
 * The thumbnail frame's aspect clamps.
 *
 * <p>Not kernel behaviour — {@code aspect()} has no clamps of its own — so they are this
 * component's constants and this component's boundary tests.
 *
 * <p><b>0.78–1.0, tightened from P2's 0.85–1.22 by the v3 design.</b> The strip's six cards sat in
 * one row and could afford to be tall; the matrix stacks two rows of them, and every pixel of
 * thumbnail height is paid for twice down the column and again by the four value rows beneath each
 * canvas. Capping at 1.0 means a card's picture is never taller than it is wide, which is what
 * keeps two rows of cards plus the legend, change and beyond lines inside one screen.
 */
export const THUMB_ASPECT_MIN = 0.78;
/** @see THUMB_ASPECT_MIN */
export const THUMB_ASPECT_MAX = 1.0;

/** Grid step, radius floor, radius factor and blur — the prototype's thumbnail dials (plan §4.3). */
const THUMB_GRID = 4;
const THUMB_RADIUS_MIN = 10;
const THUMB_RADIUS_FACTOR = 0.155;
const THUMB_BLUR = 2.4;
const THUMB_LINE = 0.5;

/**
 * The bloom dials for this surface — `docs/design/map-tab-v2/README.md`, "The heat bloom
 * (required on a dark ground)" table's "Plan tab thumbnails" row (155/0.9). `bloomFrom` must stay
 * at 3.0 on every surface (the gate where the temperature ramp's own luminance peaks — see
 * `field()`'s comment in `heatField.js`); to tame this small surface's glow, `bloomBlur` is cut to
 * 0.9 — never raise the gate.
 */
const THUMB_BLOOM = 1;
const THUMB_BLOOM_FROM = 3;
const THUMB_BLOOM_A = 155;
const THUMB_BLOOM_BLUR = 0.9;

/**
 * The strip's own measurement floor, handed to {@link useHeatCanvas}.
 *
 * <p><b>26, not 21, and the extra five pixels are the height gate.</b> {@code drawGeo} tests BOTH
 * dimensions ({@code !(w > 20) || !(h > 20)}) while this measures width alone, and the height is
 * {@code width × frameAspect} with the aspect floored at {@link THUMB_ASPECT_MIN}. At a width just
 * above the floor the measurement passes, the height lands at 20 or below, and {@code drawGeo}
 * declines with the retry budget already spent — a permanently blank canvas from a gate the retry
 * cannot see.
 *
 * <p>⚠️ <b>It is derived from the aspect floor and MOVED WITH IT.</b> At P2's floor of 0.85 it was
 * 25; the v3 clamp is 0.78, where 25 × 0.78 = 19.5 and the height gate fires. The derivation is
 * {@code ceil(20 / aspectFloor)} = {@code ceil(25.64)} = <b>26</b> — and it works because the hook's
 * own test is STRICT ({@code !(width > minPx)}), so the smallest width that ever reaches
 * {@code drawGeo} is 27 and {@code round(27 × 0.78) = 21 > 20}. Anyone re-tuning
 * {@link THUMB_ASPECT_MIN} must re-derive this in the same edit.
 *
 * <p>Unreachable at any supported viewport (a 26px cell needs a ~120px container), so it closes a
 * latent mismatch rather than a live defect. The hook carries the general form of the rule as well
 * ({@code MIN_CANVAS_PX}); this constant is what makes it unreachable here, and is the boundary
 * this component's own tests pin.
 */
const MIN_THUMB_PX = 26;

/**
 * What the Spread label names on a shaped body (S5b-1): the pool is the places in the reader's sub-regions. Shaped
 * like {@code spreadQualifier}'s answer; {@code spoken} does not start with "within ", so the sentence joins it
 * with a comma ("7 locations, in your sub-regions").
 */
const PICKS_QUALIFIER = Object.freeze({ text: 'your sub-regions', spoken: 'in your sub-regions' });

/** The dashed outline's width on a thumbnail, in CSS px (handoff task 6: 1px where the Map's is heavier). */
const THUMB_OUTLINE_WIDTH = 1;

/** An unhandled door press: the strip is mounted without a Coming up route (tests, a lone render). */
const noop = () => {};


/** The planning-area threshold in whole hours, for the beyond line's own sentence. */
const GLANCE_HOURS = GLANCE_MINUTES / 60;

/**
 * The frame every thumbnail is projected into, clamped.
 *
 * <p>Exported for its boundary tests: the clamps are this component's, not the kernel's, and a
 * frame outside them is exactly what a coverage change produces.
 *
 * @param {object} fitTo a corner MultiPoint from {@code bbox}
 * @returns {number} height / width for the thumbnail canvases
 */
export function thumbAspect(fitTo) {
  return clamp(aspect(fitTo), THUMB_ASPECT_MIN, THUMB_ASPECT_MAX);
}

/**
 * The tint class for a served verdict, or null for the two states that take none.
 *
 * <p>⚠️ <b>A lookup on the served enum, never a threshold.</b> The design derives its own class
 * from an average ({@code ≥3.7} → {@code vg}, {@code ≥2.8} → {@code vm}); plan §3 rule 1 and A1
 * reject that outright, because the window's verdict is already the top region's band and a client
 * threshold would be a second, quieter answer to a question the backend owns. This function must
 * stay a map — a comparison operator appearing in it is the defect.
 *
 * <p>AWAITING, UNRATED and an away window take no tint. None is a judgement about the sky: AWAITING
 * means the window has neither a rating nor a triage signal, UNRATED that the weather passed it but
 * no trustworthy rating has called it (unrated-verdict-plan.md U2), and away that nothing was
 * forecast at all; a coloured card would read as one of the three grades.
 *
 * @param {?string} verdict the served {@code DisplayVerdict}
 * @returns {?string} `vg`, `vm`, `vp`, or null
 */
export function verdictTint(verdict) {
  return VERDICT_TINT[verdict] ?? null;
}

/**
 * The tint table behind {@link verdictTint}. Exported so a test can assert that EVERY word in
 * {@code VERDICT_LABEL} has an entry here — the explicit-arm rule (unrated-verdict-plan.md U2): a
 * verdict with no entry reads as "no tint" by lookup miss, which is how a new value would silently
 * become AWAITING.
 *
 * <p>{@code UNRATED} and {@code AWAITING} are both {@code null} — deliberately two entries, not one
 * shared fall-through: "the weather did not rule it out, but no trustworthy rating has called it"
 * must not wear green (that is the defect U2 removes) or amber (a Maybe is a rating's call), and a
 * future divergence between the two neutral states is then one edit here.
 *
 * @see verdictTint
 */
export const VERDICT_TINT = {
  WORTH_IT: 'vg',
  MAYBE: 'vm',
  STAND_DOWN: 'vp',
  UNRATED: null,
  AWAITING: null,
};

/**
 * The spoken form of a rating, for the card's one visually-hidden sentence.
 *
 * <p>The visible chip is {@code 4★}, and a star glyph is an icon rather than a word — the rule
 * this component already applies to the movement arrow and the sun glyph.
 */
function starsSpoken(rating) {
  return `${rating} star${rating === 1 ? '' : 's'}`;
}

/**
 * The Best row's line for the one state where "not scored yet" would be false of the WINDOW
 * (plan-card-verdict-honesty-plan.md H2): the window carries a rated verdict, and every rated place
 * behind it is further than the active reach tier.
 *
 * <p>Production, 2026-10-13: Friday's sunrise read "Worth it" (Northumberland &amp; Tyneside, 38
 * rated) over a Spread of "none rated yet" and a Best of "not scored yet", because from the
 * 45-minute tier every rated spot of that region was beyond the ring. "Not scored yet" is true only
 * of the reader's POOL; the card never said whose word the verdict was, or that it was out of reach.
 * This names the region ({@code card.hotRegionName} — the SAME argmax the verdict word and the H1
 * mean came from, never a fresh pick) and the tier, formatted by {@link formatDriveDuration}, the
 * one formatter {@code REACH_TIERS} derives the lens bar's chip labels with, so "45 min" here and
 * on the chip cannot differ.
 *
 * <p><b>"Beyond" is claimable only where the tier could act.</b> Every condition below is
 * load-bearing: a measured drive ({@code reachMeasured} — an unknown drive passes every tier, so
 * without one nothing was gated and a rated verdict always leaves a rated spot in the pool); an
 * active tier ({@code reachLimitMinutes} non-null — under "Any" the pool is {@code allSpots}); a
 * non-empty pool (an empty one is "nothing in reach", a different and already-true sentence); and
 * a pool with nothing rated, measured by {@link buildSpread} so "rated" means what the Spread row
 * means by it. Between {@code allSpots} and {@code pool} the reach gate is the ONLY filter, so a
 * window that has a region mean and a pool with no rated spot has had its rated spots removed by
 * that gate. Away (an origin set) the scope is the origin's region, whose own mean is the word:
 * "beyond 45 min" is then measured from the origin, and is just as true.
 *
 * <p><b>Not muted, and labelled "Region".</b> The muted ink and 400 weight of "nothing in reach"
 * mark a state with nothing to report; this is a fact about the window, so it keeps the primary
 * ink. The label is "Region" rather than "Best" because the line no longer names a best place —
 * it names whose verdict the word above is — and the word is six characters, as "Spread" is, so
 * the card's auto-width key column does not grow (a seven-character "Verdict" would take ~6px from
 * a value column that is only ~88px wide at 320px).
 *
 * @param {object} card the thumbnail descriptor
 * @returns {?object} the line (same shape as {@link bestReachLine}'s, plus {@code beyond: true}),
 *          or null when the card is not in this state
 */
function verdictBeyondReach(card) {
  const region = card?.hotRegionName;
  const limit = card?.reachLimitMinutes;
  if (card?.away || !region || card?.verdictMean == null) return null;
  // A shaped (Lite) body has no reach tier to be beyond; its pool is emptied by the picks, which this line never says.
  if (card.picksScoped === true) return null;
  if (!card.reachMeasured || limit == null) return null;
  const pool = card.pool ?? [];
  if (pool.length === 0 || buildSpread(pool).rated > 0) return null;
  const tier = formatDriveDuration(limit);
  return {
    rating: null,
    label: 'Region',
    value: `${region} · beyond ${tier}`,
    muted: false,
    beyond: true,
    // ⚠️ An UNRATED word is not a verdict "of" a region (unrated-verdict-plan.md U2, review F8):
    // "The verdict is X's" would assert one the sample gate declined to give. The rated verdict's
    // title is unchanged.
    title: card.verdict === 'UNRATED'
      ? `Too few rated in ${region} to call it, and its rated places are all further than ${tier} away`
      : `The verdict is ${region}'s, and its rated places are all further than ${tier} away`,
    tideAligned: false,
    tideState: null,
    // Starts with the visible label's own word (SC 2.5.3), then the same two facts in words.
    spoken: `region ${region}, beyond ${tier}`,
  };
}

/**
 * What the card's third row says about the best place this reader could actually drive to.
 *
 * <p><b>One function, three states, and the visible text and the spoken text are built together</b>
 * — the same discipline the accessible sentence has followed since P2, for the same reason: two
 * derivations of one claim are two chances to disagree about it.
 *
 * <ul>
 *   <li><b>A rated head</b> — the rating takes the label column in its own ramp colour and the name
 *       takes the whole value column, which is what lets a long name wrap to two lines rather than
 *       ellipsing.</li>
 *   <li><b>An empty pool</b> — nothing is within reach, which is the lens biting or a scope with
 *       nothing in it. The design's own copy.</li>
 *   <li><b>A pool with nothing rated</b> — the ORDINARY state on a far-horizon window, since T+4 is
 *       never evaluated. It must not read as "nothing in reach" (there are places, they are simply
 *       unrated) and it must not name the head as "best" (the order that chose it was alphabetical
 *       — {@code compareSpots} sorts unrated last, so an unrated head means no rating was compared
 *       at all). "Not scored yet" is the strip footer's own existing word for this state.</li>
 * </ul>
 *
 * <p><b>On a body shaped by the reader's sub-regions (S5b-1)</b> the pool holds their places only, so an empty one
 * is the picks' doing and the line is {@code null} (the row is not drawn); a pool with places and no star says
 * {@code None of your sub-regions are rated for this sunrise yet.}; and the beyond-reach and "nothing in reach"
 * lines never fire, since a Lite reader has no tier to be beyond.
 *
 * @param {object} card the thumbnail descriptor
 * @returns {?{rating: ?number, label: ?string, value: string, muted: boolean, title: ?string,
 *          tideAligned: boolean, tideState: ?string, spoken: string}} what to draw and what to say; null where
 *          the row is omitted (a shaped reader with no place of their own)
 */
export function bestReachLine(card) {
  const best = card?.bestReach ?? null;
  if (best) {
    const drive = formatDriveDuration(best.driveMinutes);
    const leave = leaveBy(best.solarEventTime, best.driveMinutes);
    // Only the parts that exist: a user with no postcode has no drive and no leave time, and a
    // title reading "Region · · leave " is worse than a title naming the region alone.
    const parts = [best.regionName, drive, leave ? `leave ${leave}` : null].filter(Boolean);
    // The preference-axis glyph (tide-plan-card-plan.md §3 C2 task 1) — `bestReach.tideAligned`
    // alone, `=== true` so a miss (`false`) and an inland spot (`null`) both read as no mark. Never
    // `tideOnTheLight`'s timing question — the two-tide-axes rule (§1 #6, §4 #3). A miss is
    // silent: no glyph, no tooltip clause, no spoken clause, anywhere below.
    const tideAligned = best.tideAligned === true;
    const tideStateWord = tideAligned ? STATE_WORD[best.tideState] : null;
    const titleParts = [...parts];
    if (tideAligned && tideStateWord) titleParts.push(`${tideStateWord} — the water it wants`);
    return {
      rating: best.rating,
      label: null,
      value: best.locationName,
      muted: false,
      title: titleParts.join(' · ') || null,
      tideAligned,
      // The matched state (tide-window-plan.md §4 #21) — the glyph's own letter reads this, never
      // a level or threshold computed here. Null on a miss or an inland pick, matching `tideAligned`.
      tideState: tideAligned ? (best.tideState ?? null) : null,
      // ⚠️ THE SAME PARTS THE TITLE CARRIES, joined for speech rather than for a tooltip. A `title`
      // on a non-focusable span inside a button named by `aria-labelledby` reaches nobody: not the
      // accessible name, not touch, not the keyboard. Leaving the region, the drive and the
      // departure there alone would make the one figure on this card a photographer actually acts
      // on — when to leave — pointer-only, which is the "number with no route to the thing it
      // counts" defect CLAUDE.md already records against Close-to-home. The tide clause rides the
      // same list for the same reason: `.wf-hc-pls` is `aria-hidden` (§1 #2), so this sentence is
      // the ONLY route the glyph's claim has to a screen reader. It now names the matched water
      // (tide-window-plan.md §4 #21), mirroring `mapTideFit.tideAccessibleClause`'s own match
      // wording, rather than the state-blind "the tide is right here" it used to read.
      spoken: [`best ${best.locationName}`, starsSpoken(best.rating), ...parts,
        ...(tideAligned ? [tideStateWord ? `${tideStateWord}, right here` : 'the tide is right here'] : [])]
        .join(', '),
    };
  }
  // ⚠️ Tested BEFORE the fall-throughs below: a withheld pool HAS rated spots (the outlier and
  // maybe more), so `empty` is false and `verdictBeyondReach` could otherwise answer, and the card
  // would print "not scored yet" over a pool the guard deliberately declined to name. The title
  // takes the same reach rule as everywhere: "within reach" only where a drive time exists.
  if (card?.bestReachWithheld === true) {
    return {
      rating: null,
      label: 'Best',
      value: 'no clear pick',
      muted: true,
      title: card?.reachMeasured
        ? 'The highest rating within reach is well above every place around it, so none is named.'
        : 'The highest rating here is well above every place around it, so none is named.',
      tideAligned: false,
      tideState: null,
      // Begins with the visible label's own word (WCAG 2.5.3). The reason rides here too: the
      // `title` above reaches a pointer only, and "no clear pick" with no why is a dead end for
      // everyone else.
      spoken: `Best, no clear pick: ${card?.reachMeasured
        ? 'the top rating within reach is well above the places around it'
        : 'the top rating here is well above the places around it'}`,
    };
  }
  const empty = (card?.pool?.length ?? 0) === 0;
  // A body shaped by the reader's sub-regions (S5b-1): the pool holds only the places inside them, so an empty
  // one is the picks' doing, never "nothing in reach" or "nothing to show", and "not scored yet" is true only of
  // places the reader holds. No place in the pool: the row is omitted (null). Places, none rated: its own line.
  if (card?.picksScoped === true) {
    if (empty) return null;
    const event = card.targetType === 'SUNRISE' ? 'sunrise' : 'sunset';
    const value = `None of your sub-regions are rated for this ${event} yet.`;
    return {
      rating: null,
      label: 'Best',
      value,
      muted: true,
      title: null,
      tideAligned: false,
      tideState: null,
      spoken: `best, ${value}`,
    };
  }
  const beyond = verdictBeyondReach(card);
  if (beyond) return beyond;
  // ⚠️ "In reach" only where the reach axis could have acted — `card.reachMeasured`, computed once
  // in `buildWindowCards` and folded onto this descriptor. An unknown drive passes every tier (plan
  // §2.5), so for a reader with no home postcode the pool was never gated and an empty one means
  // this window has no sky-gated slots at all; blaming a control that did nothing is §6 clause 7's
  // own sentence.
  const emptyWord = card?.reachMeasured ? 'nothing in reach' : 'nothing to show';
  return {
    rating: null,
    label: 'Best',
    value: empty ? emptyWord : 'not scored yet',
    muted: true,
    title: null,
    tideAligned: false,
    tideState: null,
    // Both forms keep the visible label's own word, so WCAG 2.5.3's label-in-name holds for a
    // speech-input user reading "Best" off the row.
    spoken: empty ? `best, ${emptyWord}` : 'best, not scored yet',
  };
}

/**
 * The tide chip's tooltip — the reach-scoped count and how well served, the run size, and whether
 * this is the one to take (tide-plan-card-plan.md §3 C2 task 2).
 *
 * <p>The state phrase reads the served WINDOW tide (`card.tide.state`/`.direction`, through the
 * same {@link STATE_WORD}/{@link DIRECTION_WORD} tables `windowFirstRows.js` exports) — never a
 * level or a threshold computed here (§4 #1/#2). "In reach" only when {@code card.reachMeasured}
 * (§1 #11); the run clause only when more than one window is live; the "most of any of them"
 * clause only on the ranked best.
 *
 * @param {object} card the thumbnail descriptor
 * @param {{matched: number, coastal: number}} fit the card's tide-fit summary
 * @param {number} liveCount how many windows in the run are live
 * @param {boolean} isBest whether this window is the run's ranked best
 * @returns {string} the tooltip text
 */
function tideChipTooltip(card, fit, liveCount, isBest) {
  const state = STATE_WORD[card?.tide?.state];
  const direction = DIRECTION_WORD[card?.tide?.direction];
  const stateClause = [state, direction].filter(Boolean).join(', ');
  const reachWord = card?.reachMeasured ? ' in reach' : '';
  const head = `${fit.matched} of ${fit.coastal} coastal locations${reachWord} get the water they want`
    + (stateClause ? ` — ${stateClause}` : '');
  const clauses = [head];
  if (liveCount > 1) clauses.push(`${liveCount} windows in this run are live`);
  if (isBest) clauses.push('the most of any of them');
  return clauses.join(' · ');
}

/**
 * Area-name tables for the thumbnail's region labels (field-geography plan §2.4), keyed in the
 * {@link plainName} form — <b>not</b> in the form the payload serves.
 *
 * <p>⚠️ <b>That distinction is the bug this comment used to hide.</b> Production serves
 * `The Lake District` / `The Yorkshire Dales` / `North York Moors & Coast` /
 * `Northumberland & Tyneside`, and all four of the local seed's regions carry an article or a
 * conjunct too (`scripts/dev-seed-locations.sh`). The tables were authored without either, so
 * every production lookup missed — and the fallback that caught them is not the harmless degrade
 * this comment once claimed: the tiny set keeps only the FIRST word, so both Lakes and Dales
 * rendered as the single word `THE` on every phone-width card, and the Moors as `YORK`.
 *
 * <p>{@link areaLabel} now tries the served name first and its plain form second, so the table is
 * reached whichever way a region is named. Two of the seed's four regions reach it and two still
 * degrade through the fallback, which leaves a local session exercising both paths.
 *
 * <p>Production regions are DB-managed via the Admin UI and {@code RegionService.setName} makes
 * renames routine (V137 exists to clean up after one) — this table is deliberately
 * non-authoritative and WILL drift. It is a styling nicety over the derivation rule below, never a
 * registry, which is why the fix above went into the lookup rather than into these keys.
 */
export const AREA_FULL = {
  Northumberland: 'NORTHUMBERLAND',
  'North Pennines': 'NORTH PENNINES',
  'North York Moors': 'NORTH YORK MOORS',
  'Lake District': 'LAKE DISTRICT',
  'Yorkshire Dales': 'YORKSHIRE DALES',
  Borders: 'BORDERS',
  'Peak District': 'PEAK DISTRICT',
};
/** @see AREA_FULL */
export const AREA_TINY = {
  Northumberland: 'NORTHUMB.',
  'North Pennines': 'PENNINES',
  'North York Moors': 'N Y MOORS',
  'Lake District': 'LAKES',
  'Yorkshire Dales': 'DALES',
  Borders: 'BORDERS',
  'Peak District': 'PEAK',
};

/**
 * The drawn width, in px, under which a thumbnail's region labels take {@link AREA_TINY} rather
 * than {@link AREA_FULL} — the well's own {@code clientWidth}, the same measurement the paint pass
 * reads. Strict: exactly 215 takes the full set.
 */
export const TINY_NAME_WIDTH = 215;

/**
 * A leading definite article, dropped by {@link plainName} — and so, transitively, by the tiny
 * fallback that derives from it.
 *
 * <p>Only `the`. `A`/`An` are left out because no region in this roster or any plausible successor
 * begins with one, and `A` in particular is as likely to be part of a real name as an article. The
 * narrower the drop list, the less it can misfire.
 */
const ARTICLE = /^the$/i;

/** A leading directional dropped from an unmapped region's tiny-set fallback. @see areaLabel */
const DIRECTIONAL = /^(north|south|east|west)$/i;

/**
 * A trailing `& Coast` / `and Tyneside` conjunct, dropped by {@link plainName}.
 *
 * <p>An appended second area narrows the region's SCOPE — `North York Moors & Coast` is the Moors
 * entry with the coast added — and the head alone is what a 175px thumbnail has room for. ⚠️ That
 * is a WIDTH argument and it expires with the width, which is why {@link areaLabel} spends this
 * ONLY on the tiny table. The conjunct half is not noise: V137 renamed these regions precisely so
 * the name would follow the roster ("to a user in South Shields a region called 'Northumberland'
 * reads as somewhere else"), so dropping it at full width — where there is room, and where the Map
 * tab prints the served name verbatim — would be discarding the thing that migration added.
 *
 * <p>⚠️ <b>{@link plainName} TRIMS before applying this, and that is the whole guard against it
 * eating a name.</b> Untrimmed, ` & Coast` matches from index 0 and reduces to the empty string —
 * and an empty label is not a short label, it is a MISSING one: the placement pass skips a
 * zero-measured candidate, so the region silently loses its name, the §2.4 outcome this fallback
 * exists to prevent. Trimmed, the pattern cannot match a name-final conjunction at all, since the
 * `\s+` after it would have to be trailing whitespace that no longer exists. This carried a `\S`
 * as a second guard on that same case; it was removed because, after the trim, no input
 * distinguishes it (swept over every head/separator/tail combination), and an unreachable guard
 * with a paragraph claiming necessity sends the next reader hunting for a test that cannot exist.
 */
const CONJUNCT = /\s+(?:&|and)\s+[\s\S]*$/i;

/**
 * A served region name reduced to the form {@link AREA_FULL}/{@link AREA_TINY} are keyed in —
 * leading article and trailing conjunct removed, nothing else touched.
 *
 * <p>⚠️ <b>This exists because keying the tables on the served names alone is what broke.</b> The
 * tables hold `Lake District`; the payload says `The Lake District`; the lookup missed, and the
 * tiny fallback's "keep the first word" rule turned it into `THE`. Re-keying the tables to the
 * served spelling would have fixed exactly today's four names and re-broken on the next admin
 * rename — the table's own doc comment says it WILL drift — so the lookup is what gives instead.
 *
 * <p>The article drop is here rather than in the fallback alone because the table is the surface
 * that carries the CURATED short name (`LAKES`, `DALES`); a fallback-only fix would have produced
 * the merely-tolerable `LAKE`/`YORKSHIRE` and left the curation unreachable.
 *
 * @param {string} regionName the region name as served
 * @returns {string} the plain form, or `''` for a null/blank name
 */
function plainName(regionName) {
  // TRIM FIRST — see CONJUNCT's own note; it is the only thing stopping a leading ` & Coast` from
  // matching at index 0 and reducing the whole name to ''.
  const words = String(regionName ?? '').trim().replace(CONJUNCT, '').split(/\s+/)
    .filter(Boolean);
  if (words.length > 1 && ARTICLE.test(words[0])) words.shift();
  return words.join(' ');
}

/**
 * One region's label, in the size table {@code tiny} selects.
 *
 * <p>Looked up twice — the served name first, so an exact table entry always wins, then
 * {@link plainName}'s reduction, which is what reaches the curated short name for every region
 * the payload happens to article- or conjunct-qualify.
 *
 * <p>⚠️ <b>The second lookup is TINY-ONLY, and that is load-bearing.</b> Every {@link AREA_FULL}
 * value is exactly its own key uppercased — the full table carries no curation at all — so a plain
 * lookup there could never return anything the fallback would not, and could only return something
 * SHORTER. Wiring it up on both tables (the first cut of this fix did) silently retitled
 * `Northumberland & Tyneside` as `NORTHUMBERLAND` on every full-width card: half the name dropped
 * where there was room for it, and a fresh disagreement with the Map tab, which renders the served
 * name verbatim (`MapLabels.jsx`). The curation this reduction exists to reach lives in
 * {@link AREA_TINY} alone.
 *
 * <p>The two fallbacks are deliberately different shapes. The full set falls back to the
 * uppercased name unchanged — always legible, if long. The tiny set does NOT take that fallback:
 * a long name at under {@link TINY_NAME_WIDTH} fails {@code placeWithNudges} outright and the
 * region silently loses its label, so the tiny fallback derives a short one instead — from the
 * PLAIN name, drop leading directionals, keep the first remaining word, uppercase it. The drop
 * loops rather than stepping once, so `North West Highlands` reaches `HIGHLANDS` rather than
 * stopping on `WEST`; it always leaves one word standing, so a region named only `North` keeps it.
 *
 * <p>Warns once per miss, dev-mode only, so a rename or roster growth surfaces at the next local
 * session rather than as an unlabeled blob (§2.4's three guards). ⚠️ It did not catch the defect
 * this function was rewritten for, and could not have: `import.meta.env.DEV` is false in a
 * production build, so no reader's console ever saw it, and a local session runs the SEEDED
 * roster rather than production's names. It is a local tripwire against the local catalogue, not
 * a monitor of the real one, and it does not discharge the table's drift on its own.
 *
 * @param {string} regionName the region name as served
 * @param {boolean} tiny whether this card drew under {@link TINY_NAME_WIDTH}
 * @returns {string} the label text
 */
function areaLabel(regionName, tiny) {
  const table = tiny ? AREA_TINY : AREA_FULL;
  // Own-property reads: region names are admin-authored, so a region named `constructor` would
  // otherwise resolve to `Object.prototype`'s member and hand React a function to render.
  const entry = (key) => (Object.hasOwn(table, key) ? table[key] : null);
  const plain = plainName(regionName);
  const known = entry(regionName) ?? (tiny ? entry(plain) : null);
  if (known) return known;
  if (import.meta.env.DEV) {
    console.warn(
      `WindowFirstHeatStrip: no area-name mapping for region "${regionName}" (nor for its plain `
      + `form "${plain}") — falling back. Regions are DB-managed and renamed routinely; this `
      + 'table is a styling nicety, not a registry (field-geography plan §2.4).',
    );
  }
  if (!tiny) return String(regionName ?? '').toUpperCase();
  const words = plain.split(/\s+/).filter(Boolean);
  while (words.length > 1 && DIRECTIONAL.test(words[0])) words.shift();
  return (words[0] ?? '').toUpperCase();
}

/**
 * The Plan pane's matrix — the week as day columns, sunrise over sunset, and the plan itself.
 *
 * <h2>It is no longer an index into a list; it IS the plan</h2>
 *
 * <p>Plan-matrix §1. P2 shipped this as one row of six thumbnails labelling the window rows
 * beneath them. The v3 design turns the row into a <b>matrix</b> — a column per day, sunrise on
 * the upper row and sunset on the lower — and moves the verdict word, the pick, the topics and
 * the best place you could actually drive to onto the card faces themselves. Reading down a
 * column is one day; reading across a row compares the same light day to day.
 *
 * <p><b>The list below stays alive this phase.</b> Card click keeps P2's behaviour (open the
 * window's row and scroll to it), so {@code aria-expanded}/{@code aria-controls} still point at a
 * real element. M2 replaces both with a dialog and deletes the list.
 *
 * <h2>Placement is explicit, and that is what makes the phone free</h2>
 *
 * <p>{@code buildWindowMatrix} derives the columns from the data and every cell carries
 * {@code --c}/{@code --r}, so the stylesheet places rather than flows. The phone transposes the
 * identical markup into two columns under a spanning day header with no second render path — a
 * day holding one window takes the full row ({@code .solo}) and the empty cell is
 * {@code display: none}, because a 390px screen has no width to spend on a hole.
 *
 * <h2>Every word on a card comes from the payload</h2>
 *
 * <p>Plan §3 rule 1, restated because the design breaks it and we do not: the bundle derives its
 * verdict word from a client threshold on a client average ({@code >= 3.7} / {@code >= 2.8}). Here
 * the word is {@code card.verdictLabel} and the tint is a LOOKUP on {@code card.verdict}
 * ({@link verdictTint}) — a comparison operator anywhere in this file's verdict path is a defect.
 * The picks are the served {@code BriefingWindow.pick}, so an away plan whose pick names a
 * scoped-out region legitimately shows no legend at all (D-5).
 *
 * <h2>The two client derivations, and why they are client-side</h2>
 *
 * <p>The spread histogram and the best-reachable line are measured over the card's <b>pool</b> —
 * origin-scoped, canopy-filtered, reach-gated, before the rating floor. Reach is per-user and must
 * never ride the shared, ETag-revalidated briefing payload (plan §3 rule 4), so "how many places
 * could I get to, and which is the best of them" has no servable answer on that contract. Both are
 * recorded as members of the per-user-derivation class in A10/A11, with a never-cached per-user
 * endpoint as their eventual exit (§8 O-4). They aggregate nothing the backend also answers: the
 * window's own {@code bestRating} is untouched and still describes the whole roster.
 *
 * <p><b>The floor is deliberately not applied to the pool</b> — the design's own reason is that an
 * average of things that already passed a 4-star filter always reads 4-something — so the
 * histogram's leading count exceeds the sum of its bars whenever an unrated spot is in reach. The
 * tooltip names that remainder rather than leaving the arithmetic open (A10).
 *
 * <h2>Topics are named in full, and filtered by exactly one rule</h2>
 *
 * <p>Every topic on a night is named on its card — the design's point is that a night carrying
 * three of them is the most interesting night of the week, so hiding two behind a {@code +2} had
 * it backwards. The only client judgement is the scope intersection in {@code windowFirstTopics.js}
 * (A8), which filters like the lens rather than re-deriving a served judgement, and which is
 * <b>exempt by type</b> for the whole-sky kinds — aurora and NLC serve populated {@code regions}
 * lists that mean Bortle coverage and where-it-is-clear, so an unexempted intersection would delete
 * aurora from every away plan while every naive test passed.
 *
 * <h2>Canvases are decorative; the card's own hidden sentence is the answer</h2>
 *
 * <p>Each card's accessible name is ONE visually-hidden sentence, and every visible string on it —
 * canvas included — is {@code aria-hidden}. That is not an {@code aria-label} in disguise: the
 * sentence is real text assembled from the same values the visible words are, in the same render,
 * so the two cannot drift, and WCAG 2.5.3 holds because it contains the visible time, the visible
 * verdict word, the visible best-reach words and the visible pick words verbatim.
 *
 * <p><b>It counts what is actually rendered</b> (plan §3 rule 15). When the card face grew, the
 * sentence grew with it: the pool count the histogram draws, the best-reach claim, and every topic
 * name. The histogram's per-band breakdown stays in its {@code title} alone — a five-clause
 * distribution inside a button name would make six cards unscannable, and the ranked list the card
 * opens carries every spot with its rating. The star glyph and the sun word's glyph forebear are
 * not spoken as glyphs: an icon is not a word, so 2.5.3 does not ask for them and the sentence
 * spells them out.
 *
 * <p>Relying on name-from-contents instead does not survive a browser — see the note at the render
 * site.
 *
 * <h2>An away window is not a button, and an unscored one is marked</h2>
 *
 * <p>An away cell has no row to open — the pipeline skips evaluation on a travel day — and §6 bans
 * a control with no visible effect, so it is a {@code <div>} (plan §3 rule 14). It keeps its cell,
 * because removing it would silently renumber the shape of the week, and it keeps its sun time,
 * which is almanac and true whether or not a forecast ran. It draws none of the card's derived rows
 * — there is no pool behind them.
 *
 * <p>An unscored window takes {@code drawGeo}'s whole-plate hatch (and no per-place layer), and the question is answered by
 * {@code card.bestRating} rather than by the point set. That distinction cost a production defect:
 * an empty point set is a fact about the JOIN behind the picture, while {@code bestRating} is the
 * payload's own "is anything in this window rated" — on 2026-08-19 the two disagreed for three of
 * six windows and a hatched plate reading "not scored" sat directly above a card reading
 * {@code best spot 5 stars}. One predicate, read by the plate, the per-place layer and the hidden
 * sentence. The footer no longer carries a conditional note for it: its standing swatches (hatch =
 * {@code not rated yet}, plain = {@code no places}) name the ground for every window.
 *
 * <h2>Movement is the change line's alone</h2>
 *
 * <p>P6's per-card delta chip is <b>deleted here</b> (deletion ledger, M1). The design's note is
 * that it earned nothing: a magnitude on a 55px tile with no room to name the region it belonged to
 * left the reader with a number attributable to nothing, while the change line under the footer
 * names the two biggest movers, their regions and the age of the run. That line is unchanged, and
 * its vocabulary is still {@code movement.js}' "moved at" family — never "since", for the reason
 * its own comment records.
 *
 * @param {object}   props
 * @param {Array}    props.cards      descriptors from {@code buildHeatStripCards}, chronological
 * @param {Map}      props.pointSets  window key to kernel points, from {@code buildHeatPointSets}
 * @param {?Map}     [props.stoodSets] window key to weather-stood points, from the provider's
 *                                    {@code heatStoodSets} — the "stood down" hatch (big-seas plan F4)
 * @param {?Map}     [props.unratedSets] window key to unrated points, from
 *                                    {@code buildUnratedPointSets} — the "not rated yet" hatch
 * @param {Array}    props.spots      the whole heat catalogue, for framing and the beyond line
 * @param {?Map}     [props.reachById] per-user reach, keyed by location id — framing only
 * @param {Array}    [props.hotTopics] the served {@code briefing.hotTopics}, for the scope filter
 *                                    and (at M2) the science notes. Absent simply means no topic is
 *                                    scope-filtered — see {@code windowFirstTopics.js}
 * @param {Set}      [props.openKeys] the window keys whose cards are open
 * @param {?Map<string, number>} [props.highlightKeys] Ask PhotoCast's selected pick, as the window key
 *                                    its card carries mapped to the pick's rank (F5, plan §2.8 — a Map
 *                                    and not a Set because the rank is drawn on the card). Empty or
 *                                    absent highlights nothing, and so does a key whose card is not a
 *                                    control (an away cell) or is not among the rendered windows. The
 *                                    highlight never opens the popup and is a different mark from
 *                                    {@code openKeys}'s: both can sit on one card.
 * @param {string}   props.todayStr   today's ISO date in Europe/London, for the horizon fallback,
 *                                    the today column and the elapsed-morning cell
 * @param {?string}  [props.runAge]   the last forecast run's age, already formatted by the shell
 *                                    from {@code briefing.generatedAt} — passed rather than
 *                                    recomputed so the change line and the footer's stamp can
 *                                    never disagree by a rounding boundary
 * @param {Function} [props.onOpenWindow] opens and reveals a window's card
 * @param {?object}  [props.origin]   the away origin, or null for home — framing and scope
 * @param {Function} [props.onSearchRegion] opens search pre-filled with a region name
 * @param {Array}    [props.comingUpEntries] the served almanac entries, for a topic pill's jump
 * @param {Function} [props.onOpenComingUp] `(entryId|null)` — opens Coming up (the pill's jump and the door)
 * @param {boolean}  [props.picksLocked] true for a reader without best bets (LITE): the key row carries one
 *                                    Pro upsell door. The picks themselves are already absent from {@code cards}
 *                                    (the context nulls them), so nothing here hides a chip.
 * @param {?object}  [props.homeCoords] {@code {lat, lon}}, or null with no postcode saved — the
 *                                    thumbnail's home marker (field-geography plan §2). Never
 *                                    drawn under an away origin, even when set.
 */
export default function WindowFirstHeatStrip({
  cards, pointSets, unratedSets, stoodSets, spots, reachById, hotTopics, openKeys, todayStr, runAge, onOpenWindow,
  origin = null, onSearchRegion, colourMode = null, homeCoords = null, highlightKeys = null,
  picksLocked = false, groundSets = null, picks = null,
  comingUpEntries = null, renderedEntryIds = null, openableKeys = null, forecastDates = null,
  onOpenComingUp = undefined, onShowOnMap = undefined, onGoToPlanWindow = undefined,
}) {
  // A body the server SHAPED by the reader's sub-region picks (S5b-1): `picks` is handed in only then. Never the
  // role. `zeroPicks` is a shaped reader who holds no pick yet, whose every place is outside.
  const shaped = picks != null;
  const zeroPicks = shaped && picks.allowed === 0;
  // Framing is the ONE thing scope is allowed to decide about the field: which regions are in shot.
  // It must never become the point set — handing the scoped list to the kernel would turn the
  // framing into the reach filter plan §3 forbids, and the footer's own caption promises it does
  // not. `scopeSpots` is the planning area at home and the origin's own region when away, which is
  // what makes every thumbnail re-frame together on an origin move (plan §4.8's headline state).
  const framed = useMemo(() => scopeSpots(spots, reachById, origin), [spots, reachById, origin]);
  // The same scope, NAMED — the topic filter's other operand. Read from `scopeRegions` rather than
  // reduced out of `framed` so the two arms cannot drift: `scopeSpots` is itself defined in terms
  // of this function, and calling it directly is one call rather than a second derivation.
  const scopeNames = useMemo(
    () => scopeRegions(spots, reachById, origin), [spots, reachById, origin],
  );
  const beyond = useMemo(
    // Away the line is withheld entirely rather than recomputed from the base: "beyond 3h from
    // home" is a statement about the home planning area, and the shared matrix carries no such
    // threshold. A wrong frame of reference on a line whose whole job is to name distances is
    // worse than the line's absence, and the origin chip already says where you are.
    () => (origin ? [] : beyondRegions(spots, reachById)),
    [spots, reachById, origin],
  );
  const fitTo = useMemo(() => bbox(framed), [framed]);
  const frameAspect = useMemo(() => thumbAspect(fitTo), [fitTo]);
  // Only the windows that actually moved — see `topMovers`. Empty is the ordinary state on the
  // first serve after a deploy (no previous build recorded) and whenever nothing changed, and the
  // line is withheld entirely rather than printing an age with nothing attached to it.
  const movers = useMemo(() => topMovers(cards), [cards]);
  // The grid itself. Days, their holes and the column count all fall out of the rendered windows.
  const matrix = useMemo(() => buildWindowMatrix(cards, todayStr), [cards, todayStr]);
  // Indexed once for the whole matrix rather than scanned per card — `buildTopicIndex` records why.
  const topicIndex = useMemo(() => buildTopicIndex(hotTopics), [hotTopics]);
  // The day header's topic pills (glance-topic-strip-plan.md): the licensed `buildFourDayTiles` model over the
  // SAME `hotTopics`, then re-keyed to this matrix's columns. UNSCOPED, as the Coming up block it replaces was,
  // while the cards' own topic rows are scope-filtered — the design's stated source, and intended. No `onShowOnMap`
  // means no Map door and no window route without `onGoToPlanWindow`, so a pill's spoken destination and its tap
  // cannot disagree (the model builds only the routes the caller can honour).
  const hasMapDoor = typeof onShowOnMap === 'function';
  const glanceTopics = useMemo(() => {
    const { days } = buildFourDayTiles({
      todayStr,
      hotTopics,
      entries: comingUpEntries,
      renderedEntryIds,
      openableKeys: onGoToPlanWindow ? openableKeys : null,
      forecastDates,
      hasMapDoor,
    });
    const byDate = glanceTopicsByColumn(days, matrix.days.map((day) => day.date));
    const lastTopicDate = matrix.days.map((day) => day.date)
      .filter((date) => (byDate.get(date)?.length ?? 0) > 0).pop() ?? null;
    return { byDate, lastTopicDate };
  }, [
    todayStr, hotTopics, comingUpEntries, renderedEntryIds, openableKeys, forecastDates, hasMapDoor,
    onGoToPlanWindow, matrix,
  ]);
  const dayIdBase = useId();
  /**
   * What a pill's press does, first match wins — the old "Next four days" block's four routes (a topic with no
   * almanac counterpart, such as aurora or any weather topic, has no Coming up row to open, so the handoff's
   * "click opens Coming up" holds only where Coming up lists it): the Coming up entry; the Map door; this
   * strip's own window popup with the topic emphasised; else Coming up itself.
   */
  const tapTopic = (chip) => {
    if (chip.jump) onOpenComingUp?.(chip.jump.entryId);
    else if (chip.mapDoor) onShowOnMap({
      kind: 'event', date: chip.mapDoor.date, eventType: chip.mapDoor.eventType, topicType: chip.type,
    });
    else if (chip.target && onGoToPlanWindow) onGoToPlanWindow(chip.target, chip.type);
    else onOpenComingUp?.(null);
  };
  // The tide run across the WHOLE strip — which cards are live, and which of them is the one to
  // take (tide-plan-card-plan.md §3 C1 task 3). Computed once here, over the served, non-away
  // cards, rather than per card: `tideRun` itself ranks across the strip, so a per-card call would
  // either recompute the same answer six times or, worse, six different ones if a card's own list
  // slipped out of strip order.
  const run = useMemo(() => tideRun(cards), [cards]);

  /**
   * Everything each card derives from its own pool, computed once per render rather than inline.
   *
   * <p>Both figures read ONE list ({@code card.pool}) so the picture, the count and the name can
   * never describe different populations — which is the design's own requirement that the map, the
   * list and the line share a pool.
   */
  const derived = useMemo(() => {
    const byKey = new Map();
    for (const card of cards) {
      const pool = card.pool || [];
      const spread = buildSpread(pool);
      // ⚠️ `poolWithinReach([])` is TRUE by `Array.every`, so an empty pool would license the reach
      // word on a card where nothing was gated by distance. `card.reachMeasured` is what actually
      // answers "could the tier have acted", and it is the same field `bestReachLine` reads for its
      // own empty word, so the tooltip and the visible line agree by construction.
      const withinReach = pool.length === 0 ? Boolean(card.reachMeasured) : poolWithinReach(pool);
      // The ONE decision behind bars-vs-text (plan-matrix §4 row, minimum-sample rule) — the visible
      // row and the spoken sentence's suppression rules both read `state` rather than testing the
      // thresholds themselves, so they cannot disagree about which of a card's states this is. An
      // empty pool answers `bars: true` (the pre-existing five-hairline treatment, unchanged by this
      // rule), so `state` takes no `withinReach` argument — nothing below the gate needs it either.
      const state = spreadRowState(spread);
      const bars = spreadBars(spread);
      const unrated = unratedBar(spread);
      // The card's tide-fit chip, or null below the gate — `card.tideFit.live` alone, never a
      // second read of the gate's own thresholds (tide-plan-card-plan.md §3 C2 task 2). `isBest` is
      // this card's key against the strip-wide `run.bestKey`, so the emphasis and `best of N` can
      // only ever land on the one window `tideRun` ranked first.
      const isBest = run.bestKey === card.key;
      const tideChip = card.tideFit?.live ? {
        matched: card.tideFit.matched,
        isBest,
        liveCount: run.liveCount,
        tooltip: tideChipTooltip(card, card.tideFit, run.liveCount, isBest),
        // The accessible-name clause (§1 #2): `.wf-hc-tps` is `aria-hidden`, so this string is the
        // chip's only route to a screen reader — the same discipline `bestReachLine`'s `spoken`
        // already keeps for the glyph.
        accessible: `${card.tideFit.matched} on tide${isBest ? `, best of ${run.liveCount}` : ''}`,
      } : null;
      byKey.set(card.key, {
        spread,
        state,
        bars,
        // ⚠️ `unratedBar` is a function of `spread` ALONE — it does not know about `state.bars`, and
        // answers non-null whenever the pool has a real remainder, including a pool BELOW the
        // minimum-sample gate. The render is what ANDs this with `state.bars` before drawing
        // anything; do not read a non-null value here as "the bar is drawn".
        unratedBar: unrated,
        title: spreadTitle(spread, withinReach),
        withinReach,
        // The Spread row's second label line and the spoken pool clause both read this ONE answer
        // (spread-row-plan.md §2 #5), so the label and the sentence cannot name different pools.
        // ⚠️ `withinReach` is the pool-wide predicate computed above, at the top of this loop — the
        // very one the tooltip's "within reach" reads — and NEVER `card.reachMeasured` alone: that is true as
        // soon as ANY spot in the window has a drive, so a mixed pool (some measured, some unknown)
        // would wear "(within 45m)" over bars that also count the unknown ones. `reachLimitMinutes`
        // is null under "Any" (`buildHeatStripCards` folds it so); an `undefined` is an unresolved
        // lens and the helper keeps it silent.
        // On a shaped body the pool is the places in the reader's sub-regions, and that, not a drive tier, is what
        // the label names (S5b-1): a Lite reader's lens is pinned to "Any", so the plain label would read "any drive".
        qualifier: shaped
          ? PICKS_QUALIFIER
          : spreadQualifier({ reachLimitMinutes: card.reachLimitMinutes, withinReach }),
        // This card's OWN count layout, from the bars that are actually DRAWN: the hatched bar's
        // count takes part only when the row is in bars mode, because below the minimum-sample
        // gate `unratedBar` is non-null for a real remainder and nothing is drawn for it (the
        // `unratedBar` note above), and a text-state card draws no bars at all, so it is null and
        // contributes nothing. It is NOT what the card renders: the pass after this loop replaces
        // it with the strip-wide layout (spread-row-plan.md §4 #15).
        countLayout: state.bars ? spreadCountLayout(bars, unrated) : null,
        best: bestReachLine(card),
        // ⚠️ ONLY the clocked topics (an eclipse: `chipClock(badge)` non-null). A plain topic name is now said once
        // per day in the header's pills (glance-topic-strip-plan.md), so the card no longer repeats it; a clocked
        // one stays because its instant is this window's own fact, which the day header cannot carry. The visible
        // chips and the spoken sentence both read THIS list, so they lose exactly the same clauses.
        topics: windowTopics(card.key, card.badges, topicIndex, scopeNames).filter(({ badge }) => chipClock(badge)),
        tideChip,
      });
    }
    // One count-column width for the whole strip: neighbours in a row make the SAME decision, so
    // their wells line up (`spreadCountLayoutAcross` records why). The entries are this pass's own
    // objects, so overwriting the field is not a mutation anyone else can see.
    const stripLayout = spreadCountLayoutAcross([...byKey.values()].map((facts) => facts.countLayout));
    byKey.forEach((facts) => {
      facts.countLayout = stripLayout;
    });
    return byKey;
  }, [cards, topicIndex, scopeNames, run, shaped]);

  /**
   * The windows the payload says carry no rating at all.
   *
   * <p>{@code bestRating} and nothing else — the class comment records why the point set was the
   * wrong evidence and what it cost. The descriptor carries it straight off the window card, so
   * this cell and the row it opens answer one question from one field and cannot contradict one
   * another.
   *
   * <p><b>An away window is excluded, because it already has a truer word.</b> It carries no
   * rating either — nothing is evaluated on a travel day — but "Not forecast" is the more specific
   * claim and the one the payload supports: the weather was never run for it, whereas an unscored
   * live window has a real verdict and only lacks a rating.
   */
  const unscored = useMemo(() => {
    const keys = new Set();
    for (const card of cards) {
      if (card.away) continue;
      // ⚠️ A shaped (Lite) body's windows ALL carry a null `bestRating` (the server strips it), so the served best
      // cannot say "not scored" there (S5b-1, B1). The question goes to the place states, as the Map's does: a window
      // is not scored when the reader holds places in it and none of them is rated. Unknown (scores not loaded, or
      // no states) is silence, never "not scored"; and a reader with no pick has nothing the forecast failed to score.
      if (shaped) {
        const ground = groundSets?.get?.(card.key);
        if (ground && ground.rated === false && ground.allowed.length > 0) keys.add(card.key);
      } else if (card.bestRating == null) {
        keys.add(card.key);
      }
    }
    return keys;
  }, [cards, shaped, groundSets]);

  /**
   * A window's stood places inside the thumbnail's frame: the thumbnails crop to the scope regions,
   * so a stand-down in a region cropped out is painted (the list is handed over whole) but is
   * neither counted, spoken, nor named by the legend. Everything that DESCRIBES the picture reads
   * this; only the canvas reads the full list.
   */
  const stoodInFrame = useCallback(
    (key) => (stoodSets?.get?.(key) ?? []).filter((p) => scopeNames.includes(p.rid)),
    [stoodSets, scopeNames],
  );

  /**
   * Whether ANY card on screen has stood-down ground drawn on its thumbnail (big-seas plan F4): the
   * legend's third swatch shows only then. The same predicate the paint pass uses (a non-away card
   * with a non-empty stood list), so the key never names a mark that is not on screen.
   */
  const plateHatched = useCallback(
    // Never over a shaped body's plate: its hatch would also cover ground outside the picks, which is not "not rated
    // yet" but outside them. A shaped unscored window hatches around the reader's own unrated places instead.
    (card) => !shaped && unscored.has(card.key) && stoodInFrame(card.key).length === 0,
    [shaped, unscored, stoodInFrame],
  );
  /**
   * The places a card's canvas hatches as "not rated yet": the window's own unrated list, and on a SHAPED window
   * that is not scored (and stood nothing down) the reader's own unrated places, since a window with no rated
   * place has no evidence for the general list. Never a place outside the picks.
   */
  const unratedFor = useCallback((card) => {
    if (shaped && unscored.has(card.key) && stoodInFrame(card.key).length === 0) {
      return groundSets?.get?.(card.key)?.unrated ?? null;
    }
    return unratedSets?.get?.(card.key) ?? null;
  }, [shaped, unscored, stoodInFrame, groundSets, unratedSets]);
  const stoodShown = useMemo(
    () => cards.some((card) => !card.away && stoodInFrame(card.key).length > 0),
    [cards, stoodInFrame],
  );

  /**
   * Whether ANY card on screen is currently drawing the histogram's hatched, unrated bar — the
   * footer's own note for it (below) shows only while that mark is actually on screen, matching the
   * unscored-map-plate note's own rule one line above it (independently: the two conditions are
   * never OR'd into one, so a fixture that fires only one of them never brings up the other's note).
   * `facts.unratedBar` alone does NOT answer "is the bar drawn" — it is non-null for a below-gate
   * pool with a real remainder too — so this ANDs it with `facts.state.bars`, the same test the row
   * itself makes, rather than reading `unratedBar` as sufficient on its own.
   */
  const hatchedSpread = useMemo(
    () => Array.from(derived.values()).some((facts) => facts.state.bars && facts.unratedBar),
    [derived],
  );

  /**
   * Per-card geography for the thumbnail overlay's home marker and area names (field-geography
   * plan §2.5) — one state object keyed by card key, replaced WHOLESALE on every paint, never
   * mutated incrementally. Anchors must never outlive the projection that produced them: a card
   * whose {@code drawGeo} call declines (geo failed, or too small) is simply absent from the next
   * map, which is what clears its labels rather than leaving them pinned to a stale coastline.
   */
  const [frames, setFrames] = useState(() => new Map());
  /**
   * Where each card's home marker and region labels actually ended up, or absent while the placer
   * has not run for that card's current frame — the same two-pass measurement
   * {@code WindowRowFieldMap} uses for its chips, because these labels are droppable (G1's
   * {@code placeWithNudges}) rather than the popup's own never-dropped region labels.
   */
  const [labelPlacements, setLabelPlacements] = useState(() => new Map());
  /** Candidate DOM nodes for the measurement pass, keyed by `${cardKey}::home` / `${cardKey}::region::${name}`. */
  const labelNodeRefs = useRef(new Map());
  const registerLabelNode = useCallback((key, node) => {
    if (node) labelNodeRefs.current.set(key, node);
    else labelNodeRefs.current.delete(key);
  }, []);

  /**
   * One paint per card, at that card's OWN width.
   *
   * <p>⚠️ <b>The single measurement P2 took is no longer valid, and this is the change.</b> The
   * strip's six cards were one row of equal columns, so measuring the first well and spending it
   * on all six was exactly right. In the matrix they are not equal: on the phone a day holding one
   * window spans the full row, which is twice the width of a paired card in the same grid. Painting
   * it at a sibling's width would draw it at half scale inside its own box.
   *
   * <p>So the hook's measurement is used for the GATE (its retry budget, its floors, its
   * null-context guard — all properties of the document rather than of one card) and each canvas is
   * then measured against its own unpadded well. The fallback to the hook's width covers the
   * environment with no layout at all: jsdom reports every {@code clientWidth} as 0 unless a test
   * stubs it, and a zero-width paint would be a silent no-op rather than a visible one.
   *
   * <p>Memoised because the hook makes it a dependency of its paint effect; an inline arrow would
   * repaint on every render.
   *
   * <h3>Measured, because the plan asks for a number rather than an assertion (§10.1)</h3>
   *
   * <p>A full matrix repaint costs <b>60–210 ms</b> of main thread (median ~97 ms at 1180px, ~99 ms
   * at 390px), measured in Chrome with a {@code PerformanceObserver} on {@code longtask} over a
   * twelve-step width storm. The control run — the identical storm with this section
   * {@code display: none} — produced <b>zero</b> long tasks, so the cost is this surface's and not
   * the shell's. It is ~1.0–1.3 M device pixels across six canvases at DPR 2, roughly 3× P2's
   * one-row strip: the matrix trades six narrow thumbnails for two rows of wider ones. This figure
   * is the CANVAS paint alone and predates G2.
   *
   * <p><b>G2 re-measured the ADDED cost</b> — the geometry pass this paragraph now sits above
   * (anchor computation via {@code centroid}, plus the label placement {@code useLayoutEffect}) —
   * rather than replace the figure above with one from a different-sized catalogue, which would be
   * its own citation-rot. An eight-step width storm (1180→1000→820→640→390 and back) against a
   * seeded 21-location, 4-region local catalogue — far short of production's ~61-location roster,
   * so not a like-for-like comparison with the number above — recorded exactly <b>one</b> long
   * task, at 54 ms, across the whole storm. That is at the LOW end of the pre-G2 range on a
   * fraction of the roster, which is consistent with the added work being cheap relative to the
   * canvas paint it rides alongside, but a production-scale re-measurement of the combined pass is
   * a browser claim this session's local roster cannot make.
   *
   * <p>It is a cost per LAYOUT CHANGE, not per render — a resize, a rotate, an origin move — and the
   * dials are the plan's §5 invariants, so nothing here is retuned to buy it back. The identified
   * mitigation is a debounce on the observer's nonce (the design's own "debounced resize (170ms)"),
   * which would coalesce a desktop window-drag's many observations into one paint; it belongs in
   * {@link useHeatCanvas}, which the Map pane shares, and therefore in M5's performance pass rather
   * than here.
   */
  const paint = useCallback(({ width, canvases }) => {
    const nextFrames = new Map();
    for (const card of cards) {
      const canvas = canvases.get(card.key);
      if (!canvas) continue;
      const well = canvas.parentElement;
      const cardWidth = well && well.clientWidth > 0 ? well.clientWidth : width;
      const cardHeight = Math.round(cardWidth * frameAspect);
      // The point sets are KEYED, never positional (heatSpots.js): the integer the kernel takes
      // does not exist at this call site, and every point carries its one score at
      // POINT_SCORE_INDEX.
      const points = pointSets?.get?.(card.key) || [];
      const tier = resolveConfidence(
        { confidence: card.confidence }, daysOut(card.date, todayStr),
      );
      const projection = drawGeo(canvas, cardWidth, cardHeight, points, POINT_SCORE_INDEX, {
        grid: THUMB_GRID,
        // Scaled to THIS card, so a solo full-width card is not drawn with a paired card's blur
        // radius — the same reason its width is measured separately.
        radius: Math.max(THUMB_RADIUS_MIN, cardWidth * THUMB_RADIUS_FACTOR),
        blur: THUMB_BLUR,
        line: THUMB_LINE,
        // One scalar for the haze and the badge decay, so the picture cannot look more certain
        // than the word beside it (plan D3).
        conf: confidenceScalar(tier),
        // Off the same Set the markup reads, rather than off `points.length` here: one predicate,
        // so a hatched plate and an unmarked cell can never describe the same window.
        // ...except where the window stood places down: the plate's "not rated yet" would then lie
        // over ground that was looked at, so the plate hatch is withdrawn and the per-place layers
        // (stood, and any still-unrated places) say it instead (big-seas F4, Map parity).
        hatch: plateHatched(card),
        // "Not rated yet" ground (thin-sample plan F2): not under a whole-plate hatch (it is
        // already hatched — the same predicate, not an empty set), nor on an away cell.
        // A shaped, unscored window (S5b-1) has no evidence to hatch from (`unratedSets` needs a rated or stood place),
        // so it hatches around the reader's own unrated places alone, from the grounds below.
        ...(card.away || plateHatched(card) || !unratedFor(card)?.length
          ? null : { unrated: unratedFor(card) }),
        // Stood-down ground (big-seas plan F4): the Map's list, never on an away cell. Unlike the
        // unrated layer it is NOT withheld on an unscored window: a window where every place stood
        // down has no rating, and is the one this exists for. Disc radius = the field's `radius`.
        ...(card.away || !stoodSets?.get?.(card.key)?.length
          ? null : { stood: stoodSets.get(card.key) }),
        fit: fitTo,
        // Outside the reader's sub-regions (S5b-1, handoff task 6): the veil under the field and a 1px dashed outline
        // after it, the same layers the Map draws, from the window's own two grounds. Spread only on a shaped body
        // that holds place states for this window, so every other reader's options object is exactly what it was.
        // Zero picks: `allowed` is empty, so the veil covers all the land and no outline is drawn.
        ...(shaped && !card.away && groundSets?.get?.(card.key)
          ? {
            outside: groundSets.get(card.key).outside,
            allowed: groundSets.get(card.key).allowed,
            outlineWidth: THUMB_OUTLINE_WIDTH,
          }
          : null),
        // Bloom only in TEMPERATURE mode (map-tab-v2-plan.md §3 P2, decision D-1): the bloom's
        // whole rationale is the temp ramp's luminance inversion, and the verdict ramp has none —
        // an ember glow over a green "go" would be a false signal. In verdict mode this spreads NO
        // keys at all, not falsy ones, so `drawGeo` sees today's exact options object.
        ...(getMode() === 'temp' ? {
          bloom: THUMB_BLOOM,
          bloomFrom: THUMB_BLOOM_FROM,
          bloomA: THUMB_BLOOM_A,
          bloomBlur: THUMB_BLOOM_BLUR,
        } : null),
      });
      // No projection, no anchors — three different reasons (P0's note on `drawGeo`), none of
      // which leaves geometry worth keeping. Simply absent from `nextFrames`, which is what clears
      // this card's overlay below.
      if (!projection) continue;
      // Home is drawn from the SAME framing spots the region anchors read (`framed`), never this
      // card's own point set — the region's location on the coastline does not change from window
      // to window, only which projection is drawing it (field-geography plan §2.5).
      let home = null;
      if (!origin && homeCoords) {
        const at = projection([homeCoords.lon, homeCoords.lat]);
        if (at && Number.isFinite(at[0]) && Number.isFinite(at[1])) home = at;
      }
      const regions = [];
      for (const name of scopeNames) {
        const at = centroid(framed, name, (s) => projection([s.lng, s.lat]));
        if (!at || !Number.isFinite(at[0]) || !Number.isFinite(at[1])) continue;
        regions.push({ name, x: at[0], y: at[1] });
      }
      nextFrames.set(card.key, {
        width: cardWidth,
        height: cardHeight,
        home,
        regions,
        tiny: cardWidth < TINY_NAME_WIDTH,
        hot: card.hotRegionName ?? null,
      });
    }
    // Replaced wholesale, never merged with the previous map — a card missing from this pass (its
    // canvas not yet attached, or `drawGeo` declining) must not keep last paint's anchors.
    setFrames(nextFrames);
  // `colourMode` is a REPAINT KEY, not a colour source — the same rule `MapHeatLayer` states.
  // These thumbnails paint through `heatField.js` -> `rampRgb`, which reads `scoreRamp`'s live
  // module state; when the settings fetch resolves after the first paint, `MODE` changes with no
  // card, point or frame changing, so a memoised callback keeps the OLD ramp on every thumbnail
  // while the verdict words and badges beside them repaint. Whether that happens at all depends
  // on which fetch wins, which makes it a race rather than a consistent bug.
    // Referenced so it is a REAL dependency, not a suppressed one: `exhaustive-deps` cannot see
    // that this callback's colours come from `scoreRamp`'s module state, so it reads the entry as
    // unnecessary. `void` makes the dependency honest and keeps the rule on.
    void colourMode;
  }, [cards, pointSets, stoodSets, plateHatched, fitTo, todayStr, frameAspect, colourMode, framed, scopeNames,
    origin, homeCoords, shaped, groundSets, unratedFor]);

  /**
   * The greedy placement pass for the thumbnail overlay — home first, then regions in scope order
   * (field-geography plan §2.5), each through G1's {@code placeWithNudges} against the accumulating
   * box list. A layout effect rather than an ordinary one because it reads geometry (the candidate
   * elements' measured size) and then writes positions: an ordinary effect would let the browser
   * paint the off-screen measuring pass first, and every label would visibly snap into place a
   * frame later.
   *
   * <p>Two-pass measurement, the same technique {@code WindowRowFieldMap} uses for its chips: the
   * render below always emits a candidate for every anchor in {@code frames}, off-screen and
   * hidden until this effect has measured and placed it. Unlike that component's OWN region labels
   * (seeded, never dropped), these are droppable — a name that cannot find clear air is absent
   * rather than stacked or shrunk.
   */
  useLayoutEffect(() => {
    const next = new Map();
    for (const [cardKey, f] of frames) {
      const boxes = [];
      let home = null;
      if (f.home) {
        const node = labelNodeRefs.current.get(`${cardKey}::home`);
        const w = node?.offsetWidth ?? 0;
        const h = node?.offsetHeight ?? 0;
        if (w > 0 && h > 0) {
          const box = placeWithNudges({ x: f.home[0], y: f.home[1] }, { w, h }, boxes, f.width, f.height);
          if (box) {
            boxes.push(box);
            home = box;
          }
        }
      }
      const regions = new Map();
      for (const region of f.regions) {
        const node = labelNodeRefs.current.get(`${cardKey}::region::${region.name}`);
        const w = node?.offsetWidth ?? 0;
        const h = node?.offsetHeight ?? 0;
        // A zero-measured candidate means the browser has laid nothing out yet (the ordinary state
        // in jsdom) — placing on it would pin every region to one point.
        if (!(w > 0) || !(h > 0)) continue;
        const box = placeWithNudges({ x: region.x, y: region.y }, { w, h }, boxes, f.width, f.height);
        if (box) {
          boxes.push(box);
          regions.set(region.name, box);
        }
      }
      next.set(cardKey, { frame: f, home, regions });
    }
    // A setState in an effect, and it is the case the rule's own escape hatch is for: this is a
    // MEASUREMENT, not a derivation — a label's size is its text in a font the browser may still be
    // swapping, so it cannot be known from props alone. Bounded by `frames` being the only
    // dependency: this write changes `labelPlacements`, never `frames`, so it cannot loop.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLabelPlacements(next);
  }, [frames]);

  /**
   * The card whose well the hook's gate measures.
   *
   * <p>⚠️ Taken from the MATRIX, not from {@code cards[0]}. The two can differ:
   * {@code buildWindowMatrix} places only the target types the grid has a row for, so a card it
   * dropped would register no canvas — and the gate would then wait on a ref that never arrives,
   * spend its thirty-frame budget and leave <em>every</em> canvas blank while every word on every
   * card rendered correctly. Unreachable while {@code TargetType} has two constants; the point is
   * that the gate's key and the grid's placement are now two decisions and must not be two answers.
   */
  const measureKey = useMemo(() => {
    for (const day of matrix.days) {
      if (day.am) return day.am.key;
      if (day.pm) return day.pm.key;
    }
    return null;
  }, [matrix]);

  const {
    attachFrame, canvasRef, geoFailed, repaintNow,
  } = useHeatCanvas({
    enabled: cards.length > 0,
    // The first PLACED card's well is the GATE's measured box — see `paint` for why it is no longer
    // the width every canvas is drawn at.
    measureKey,
    aspect: frameAspect,
    minPx: MIN_THUMB_PX,
    paint,
  });

  // The row-rails restructure (matrix-axis plan §6, D3): one structure for a tablet/desktop reader
  // (day tiles + two rails, both sticky), the phone's own single day-major grid for the other. The
  // same 639px query the phone CSS block already keys on, so the JS branch and the CSS flip
  // together — see `useIsMobile`'s own doc.
  const isMobile = useIsMobile();

  /**
   * `.wf-dhrow`'s own rendered height, published as `--wf-dh-h` for the two rails' sticky `top`
   * calc (matrix-axis plan D11(b)) — the day-tile row sits ABOVE both rails, so each rail has to
   * know how tall it is to pin directly beneath it.
   *
   * <p>A STATE-carrying callback ref, not a plain {@code useRef}: the node exists only in the
   * desktop branch and arrives and leaves on every viewport flip, and a plain ref plus an effect
   * with an empty dependency array would run once against whatever was mounted at that moment and
   * never again — the exact defect `plan-matrix-plan.md`'s M3 already recorded against a
   * conditionally-mounted sentinel. Depending on the NODE itself is what makes the effect re-run on
   * arrival and on departure.
   */
  const [dhrowNode, setDhrowNode] = useState(null);

  useEffect(() => {
    if (!dhrowNode || typeof ResizeObserver === 'undefined') return undefined;
    // The property lands on the SECTION, the common ancestor of the tile row and both rails' sticky
    // `top` calcs — the same reasoning `useLensReserve` already applies to `--wf-lens-h`.
    const section = dhrowNode.closest('.wf-hstrip-block');
    if (!section) return undefined;
    // Synchronous measure on attach (the `useLensReserve` shape) — the observer covers everything
    // after. A resize/RO write while the pane is `hidden` (a background tab) is inert and simply
    // self-corrects on return; that is not the forbidden zero FALLBACK, which is a literal baked
    // into the stylesheet rather than a real, if stale, measurement.
    const write = () => {
      section.style.setProperty('--wf-dh-h', `${Math.round(dhrowNode.getBoundingClientRect().height)}px`);
    };
    write();
    const observer = new ResizeObserver(write);
    observer.observe(dhrowNode);
    return () => {
      observer.disconnect();
      section.style.removeProperty('--wf-dh-h');
    };
  }, [dhrowNode]);

  /**
   * Forces a repaint on the branch flip itself, rather than depending on the resize/RO events that
   * ordinarily accompany a viewport crossing (the risk §2 already names, and its own sanctioned
   * fix — "bumping the observer nonce on branch change" — rather than a rewrite of the hook).
   *
   * <p>{@code useHeatCanvas}'s paint effect re-runs only on ITS OWN nonces (a resize/RO observation,
   * or the vendored coastline resolving) — neither of which {@code isMobile} toggling is guaranteed
   * to produce. An ordinary drag across 639px keeps firing further resize events after the branch
   * swaps, which self-correct it; but a single, discrete jump (a window-manager snap, the last
   * settled resize of a gesture landing exactly at the boundary) can leave the freshly-mounted
   * canvases of the NEW branch blank — {@code attachFrame} re-seeds `lastSizeRef` to the new
   * container's current box on attach, so even that container's first `ResizeObserver` callback
   * reads as "unchanged" and skips the repaint it would otherwise deliver.
   *
   * <p>Skipped on the FIRST commit: the hook's own mount effect already paints the initial branch,
   * and calling {@code repaintNow} again immediately would be the doubled first paint this file's
   * other guards (`alreadyLoaded`, the seeded `lastSizeRef`) exist to avoid.
   */
  const mountedRef = useRef(false);
  useEffect(() => {
    if (!mountedRef.current) {
      mountedRef.current = true;
      return;
    }
    repaintNow();
  }, [isMobile, repaintNow]);

  /**
   * Ask PhotoCast's highlight, scrolled into view when it ARRIVES or MOVES (F5, plan §2.8) — never on
   * an unrelated re-render, which is why the effect keys on the highlight's own signature and not on
   * the cards. {@code block: 'nearest'} moves the page only as far as the card needs (the lens bar's
   * {@code scroll-margin-top} is what keeps it from landing under the sticky bar), and a reader who
   * asked the system for less motion gets none. {@code scrollIntoView} is guarded because jsdom has no
   * layout and no such method.
   */
  const stripRef = useRef(null);
  const highlightSignature = highlightKeys && highlightKeys.size > 0
    ? [...highlightKeys].map(([key, rank]) => `${key}#${rank}`).join('|')
    : '';
  useEffect(() => {
    if (!highlightSignature) return;
    const node = stripRef.current?.querySelector('[data-ask-highlight="true"]');
    if (node && typeof node.scrollIntoView === 'function') {
      node.scrollIntoView({
        block: 'nearest', inline: 'nearest', behavior: prefersReducedMotion() ? 'auto' : 'smooth',
      });
    }
  }, [highlightSignature]);

  // Nothing to index. The matrix is a picture of the field, so with no catalogue joined — a scores
  // fetch that failed, a session with no roster — it withdraws entirely rather than drawing six
  // empty coastlines under a header claiming to summarise them. The window rows are untouched.
  if (cards.length === 0 || spots.length === 0) return null;

  /**
   * One card, as a cell of the matrix.
   *
   * <p>A local closure rather than a component: it reads the derived map, the unscored set, the
   * canvas ref factory and the open set off this render, and hoisting it would mean threading every
   * one of them through a prop list for an element with exactly one call site.
   *
   * @param {object} card   the thumbnail descriptor
   * @param {number} column the grid column, 1-based
   * @param {number} row    the grid row — {@code MATRIX_ROW.sunrise} or {@code .sunset}
   * @param {boolean} solo  whether this card's day holds only one window
   */
  const renderCard = (card, column, row, solo) => {
    const notScored = unscored.has(card.key);
    const open = Boolean(openKeys?.has(card.key));
    // Ask's pick lands here — only on a card that is a control: an away cell is a read-out and a
    // highlight on it would promise a window nobody forecast.
    const askRank = card.away ? null : (highlightKeys?.get(card.key) ?? null);
    const facts = derived.get(card.key);
    // The thumbnail overlay's geometry for this card, and its placement once measured. `measured`
    // is false on the very first render after a paint — before the layout effect has had a chance
    // to run — which is exactly when the candidates below must render off-screen for measurement
    // rather than at a stale or absent position.
    const geoFrame = frames.get(card.key) ?? null;
    const placement = labelPlacements.get(card.key);
    const measured = Boolean(placement && placement.frame === geoFrame);
    const tint = card.away ? null : verdictTint(card.verdict);
    const place = { '--c': column, '--r': row };
    const poolTotal = facts.spread.total;
    // ⚠️ `spokenPoolPhrase`/`unratedPhrase`/`facts.state`, never a second spelling of any of them. The
    // pool count and the reach claim are VISIBLE TEXT nowhere else on the card once bars are not
    // drawn, so SC 2.5.3 (label in name) requires this sentence to keep saying them — a below-gate
    // clause that dropped straight to "1 of 4 rated" would read as a claim about the verdict rather
    // than about the pool, and would say less than the card's own tooltip. `poolTotal === 0` stays
    // silent here exactly as before: `bestReachLine`'s own "nothing in reach"/"nothing to show"
    // clause already states the identical fact a few lines below, and saying it twice in one
    // sentence is the defect this file's header comment warns against elsewhere. Below the gate,
    // when the window is ALSO `notScored`, the pool phrase is said ALONE — "none rated yet" would
    // restate what "not scored" (added below) already says about the same window.
    //
    // ⚠️ `facts.state.spoken`, NEVER `facts.state.text` — the row's own compact "1/4 rated" is a
    // VISUAL space saving (`spreadRowState`'s own doc comment), and a screen reader voices a
    // fraction-shaped string as a number or a date, not as two counts. `spoken` is the same fact in
    // words ("1 of 4 rated"); the two strings agree for "none rated yet", where there is no count to
    // mis-voice.
    //
    // ⚠️ The pool clause names the SAME pool the visible label names (spread-row-plan.md §2 #5,
    // §6 Q1): `spokenPoolPhrase(poolTotal, facts.qualifier)` — "7 locations within 45 min" / "7
    // locations, any drive" — and is the bare "7 locations" when the label is silent (an unmeasured
    // pool), byte for byte what `poolPhrase(n, false)` said. The tooltip keeps "within reach"; it
    // sits beside the label, speech has no such neighbour. In bars mode the NON-ZERO band counts
    // follow (`spreadBandsSpoken`) because the grid is `aria-hidden` and the counts are now visible
    // text — SC 1.3.1, the same principle `unratedPhrase` states; below the gate there are no
    // visible counts to match, so only the pool clause moves.
    //
    // ⚠️ An EMPTY pool no longer short-circuits to null when the label names a tier: the visible
    // label reads "(within 45m)" over an empty well, so the sentence says "no locations within 45
    // min" too. The Best row's own empty clause stays beside it (below) — the sentence carries "the
    // visible best-reach words verbatim" for speech control (SC 2.5.3), and the pool clause is not
    // those words; the redundancy is the price of that contract. With no qualifier the pool clause
    // is null, exactly as before.
    // A reader with no pick has no pool to describe, and the Spread and Best rows are not drawn for them (S5b-1).
    const poolClause = zeroPicks ? null : spokenPoolPhrase(poolTotal, facts.qualifier);
    const bandsSpoken = facts.state.bars ? spreadBandsSpoken(facts.spread) : '';
    const spokenPool = poolTotal === 0
      ? poolClause
      : (facts.state.bars
        ? `${poolClause}${bandsSpoken ? `, ${bandsSpoken}` : ''}${unratedPhrase(facts.spread, ', ')}`
        : `${poolClause}${notScored ? '' : `, ${facts.state.spoken}`}`);
    // Built once per card so the hidden sentence and the visible words cannot be assembled from
    // different values. The comma-separated form is what a screen reader pauses on.
    //
    // "not scored" lands straight after the verdict because that is what it qualifies — "Poor" is a
    // reading of the weather, and this says nothing rated it. When it fires, the best-reach clause
    // is suppressed: a window with no rating anywhere has no rated spot in its pool either, so the
    // two would be one fact said twice.
    // The region mean behind the verdict word (plan-card-verdict-honesty-plan.md H1). Null — a
    // triage-fallback verdict, an unrated window, an away cell — is SILENCE in both the face and the
    // sentence: no mean standing behind a word is itself the disclosure that no rating does.
    const regionMean = card.away ? null : formatRegionMean(card.verdictMean);
    // H4: a STAND_DOWN word over a region the honesty filter blanked is the weather's call, not a
    // rating. The mean is null there (never both), so the qualifier takes the mean's own slot.
    const blankedNote = !card.away && card.verdictBlanked === true && !regionMean;
    // thin-sample plan F3: `· N rated` on a rated verdict over a thin reach pool. Read off the SAME
    // `facts.spread` the Spread row draws, so "6/83 rated" and "· 6 rated" are one figure.
    const thinCount = thinVerdictCount({
      verdict: card.verdict, away: card.away, notScored, spread: facts.spread,
    });
    // A stood-down window is not "not scored": it was looked at (the Map's wording).
    const stoodHere = card.away ? [] : stoodInFrame(card.key);
    const notScoredWords = stoodHere.length === 0 ? 'not scored'
      : ((unratedSets?.get?.(card.key)?.length ?? 0) > 0
        ? 'some places stood down by the weather — none rated yet'
        : 'stood down by the weather — no place rated');
    const accessibleName = [card.label, card.time, card.verdictLabel]
      .concat(blankedNote ? [BLANKED_STAND_DOWN_SPOKEN] : [])
      .concat(regionMean ? [`${regionMean} star average`] : [])
      // The verdict line's `· N rated` is not voiced separately: below the gate the pool clause
      // below already says "N of M rated" in words, the same fact with its denominator.
      .filter(Boolean)
      // Shaped, with places and none rated: the Best row's own words ("None of your sub-regions are rated for this
      // sunset yet.") say it, so "not scored" is not said as well. A stood-down window keeps its own wording.
      .concat(notScored ? [shaped && facts.best && stoodHere.length === 0 ? facts.best.spoken : notScoredWords] : [])
      .concat(card.away || !spokenPool ? [] : [spokenPool])
      // Big-seas plan F4: the hatch is a picture, so its count is said too (same list the canvas gets).
      .concat(card.away ? [] : stoodPhrase(stoodHere.length))
      // ⚠️ Suppressed when the window carries no rating anywhere — the pool then has no rated spot
      // either, so "not scored" and "best, not scored yet" would be one fact said twice in a
      // sentence that has to stay scannable across six cards. NOT suppressed on an empty pool: the
      // face still says "nothing in reach", and "nothing is scored" is a different claim from
      // "nothing is reachable". ⚠️ Also suppressed whenever the POOL's own rated count is zero
      // (independent of `notScored`, which answers for the whole window rather than this reach-gated
      // pool): the spoken pool clause above says "none rated yet" for that state, and repeating "best,
      // not scored yet" right after it is the same fact twice. ⚠️ EXCEPT in the H2 state
      // (`facts.best.beyond`, plan-card-verdict-honesty-plan.md): there the Best row no longer says
      // "not scored yet" at all — it names the region whose verdict the word is and that it is
      // beyond the tier — so its clause is NEW information, not a repeat, and replaces the
      // suppressed one.
      .concat(
        card.away || !facts.best || (shaped && notScored)
          || ((notScored || facts.spread.rated === 0) && poolTotal > 0 && !facts.best.beyond)
          ? []
          : [facts.best.spoken],
      )
      // The tide chip's clause (tide-plan-card-plan.md §3 C2 task 2) — DOM order: the best-reach
      // row's own tide clause lands just above, and this one sits where the chip itself sits, first
      // in the topics line. Absent whenever the chip is (below the gate, or away), never a separate
      // predicate from the one that gates the visible chip.
      .concat(card.away || !facts.tideChip ? [] : [facts.tideChip.accessible])
      // A clocked topic (lunar-eclipse-plan.md §2.6) states its own instant here too — the chip's
      // divider is `aria-hidden`, so "Lunar eclipse at 05:13" is this sentence's only route to a
      // screen reader, built from the SAME `chipClock` read the visible chip renders from.
      .concat(card.away ? [] : facts.topics.map(({ badge }) => `${badge.label} at ${chipClock(badge)}`))
      .concat(card.pickKind === 'best' ? ['best bet'] : [])
      .concat(card.pickKind === 'also' ? ['also good'] : [])
      // Said last, after the forecast's own claims, so it reads as what it is: Ask's pick, not the
      // forecast's. The rank circle that draws it is aria-hidden below.
      .concat(askRank !== null ? [`Ask pick ${askRank}`] : [])
      .join(', ');
    const nameId = `wf-heat-name-${card.key.replace(/:/g, '-')}`;
    const body = (
      <>
        {/* ⚠️ EVERY visible string here is aria-hidden, and the accessible name comes from the
            one `sr-only` sentence below. That is deliberate and it is not an `aria-label` in
            disguise — the sentence is real text built from the same values in the same render,
            so the two cannot drift, and WCAG 2.5.3 holds because it contains the visible time,
            the visible verdict word, the visible best-reach words and the visible pick words
            verbatim.

            Why not name-from-contents over the visible spans? Not because a browser would glue
            them: this comment used to say it would announce "Tonight Sunset21:11Worth it", and it
            does not. Half of that reasoning holds — `.wf-hc` and `.wf-hc-pls` are flex and grid
            containers, whose whitespace-only text runs are not rendered (Flexbox §4, Grid §6), so
            the separators an earlier cut relied on were inert outside jsdom (which computes no
            layout, so every span reads as `display: inline` there). But every engine spaces flex
            and grid items itself: measured 2026-09-11 by Playwright's accessible-name algorithm
            over Chromium's, WebKit's and Firefox's layout, the visible spans — which carry no
            separators at all — read "SUNSET 21:11 Worth it Spread Best nothing in reach" once
            un-hidden. That reading is the real
            case for the sentence below — it has no day word, no pauses, a stray `Spread` chart
            label and a shouted `SUNSET`, where the sentence gets all four right. */}
        <span id={nameId} className="sr-only">{accessibleName}</span>
        {/* The WORD, not an arrow — the design's own call, and it is right: a down arrow for
            sunset reads as a falling forecast. The glyph it replaces was P2's. */}
        <span className="wf-hc-top" aria-hidden="true">
          <span data-testid="wf-heat-sun" className={`wf-hc-sun ${card.sunrise ? 'am' : 'pm'}`}>
            {card.sunrise ? 'SUNRISE' : 'SUNSET'}
          </span>
          {/* The pick's rank, in the top row beside the word (F5): the gold border alone is the open
              card's own look, so this circle is what says "Ask's pick" rather than "the open one". IN the
              row's flow and not hung over the card's edge like the pick legend: measured in a browser, a
              circle overhanging the top-left corner sat on the sun word's own top edge (1px) and, one
              px more, would have met the rail's label above the first column. */}
          {askRank !== null && (
            <span className="wf-hc-ask" data-testid="wf-heat-ask-rank">{askRank}</span>
          )}
        </span>
        {!geoFailed && (
          <span data-testid="wf-heat-well" className="wf-hc-cv">
            <canvas
              aria-hidden="true"
              data-testid="wf-heat-canvas"
              ref={canvasRef(card.key)}
            />
            {/* Decorative duplication of what the card already states accessibly — the popup's own
                `.wf-mlab` is the precedent (plan §2.2) — so the whole layer is `aria-hidden`. No
                `!geoFailed` gate of its own: the check above already unmounts the whole well on
                that path, and this is the SECOND, independent absence mechanism — `drawGeo`
                returning null clears `geoFrame` for this card while the well stays mounted. */}
            <span className="wf-tlab" aria-hidden="true" data-testid="wf-heat-labels">
              {geoFrame?.home && (() => {
                // Undefined while unmeasured (render off-screen to be measured); null once
                // measured and dropped (render nothing); a box once measured and placed.
                const box = measured ? placement.home : undefined;
                if (measured && !box) return null;
                return (
                  <span
                    ref={(node) => registerLabelNode(`${card.key}::home`, node)}
                    className="wf-hm"
                    data-testid="wf-heat-home"
                    style={box
                      ? { left: `${box.x}px`, top: `${box.y}px` }
                      : { left: '-9999px', top: '0px', visibility: 'hidden' }}
                  >
                    <i className="wf-hm-mk" aria-hidden="true" />
                    <b className="wf-hm-lb">HOME</b>
                  </span>
                );
              })()}
              {(geoFrame?.regions || []).map((region) => {
                const box = measured ? placement.regions.get(region.name) : undefined;
                if (measured && !box) return null;
                return (
                  <span
                    key={region.name}
                    ref={(node) => registerLabelNode(`${card.key}::region::${region.name}`, node)}
                    className="wf-tlab-rg"
                    data-testid="wf-heat-area"
                    data-region={region.name}
                    data-hot={region.name === geoFrame.hot ? 'true' : undefined}
                    style={box
                      ? { left: `${box.x}px`, top: `${box.y}px` }
                      : { left: '-9999px', top: '0px', visibility: 'hidden' }}
                  >
                    {areaLabel(region.name, geoFrame.tiny)}
                  </span>
                );
              })}
            </span>
          </span>
        )}
        <span data-testid="wf-heat-value-grid" className="wf-hc-pls" aria-hidden="true">
          <span data-testid="wf-heat-time" className="wf-hc-t">{card.time}</span>
          {/* ⚠️ ONE LINE, for every verdict, and no hook on the row for it to be otherwise. The value
              column is 83–89px at 320, 640 and a docked 1024 (measured), "Too few rated 3.6★" is
              113.8px, so the UNRATED word prints its SHORT form here ("Too few 3.6★" = 74.2px) and
              the row never wraps — a wrapped row would push this card's Spread and Best rows down
              against its neighbours'. The grid is `aria-hidden`; the full word is in the card's
              spoken sentence (`accessibleName` above) (unrated-verdict-plan.md U2, review F2). */}
          <span className="wf-hc-pv">
            <span
              data-testid="wf-heat-verdict"
              className="wf-hc-vd"
              data-verdict={card.verdict || 'AWAY'}
            >
              {shortVerdictLabel(card.verdict, card.verdictLabel)}
            </span>
            {/* H1: the served region mean, a SIBLING of the verdict word (not nested in it, so the
                word's text content stays exactly the word). One decimal always, so "3.5★" cannot be
                mistaken for the integer best-spot "4★" chip. Absent when null — no dash. The grid is
                `aria-hidden`; the same figure is voiced in the card's sentence above. */}
            {regionMean && (
              <span data-testid="wf-heat-mean" className="wf-hc-mean">{regionMean}★</span>
            )}
            {thinCount != null && (
              <span data-testid="wf-heat-thin" className="wf-hc-thin">
                <span className="wf-hc-thin-sep">{'· '}</span>
                {`${thinCount} rated`}
              </span>
            )}
            {blankedNote && (
              <span
                data-testid="wf-heat-blanked"
                className="wf-hc-mean wf-hc-note"
                title={BLANKED_STAND_DOWN_SPOKEN}
              >
                {BLANKED_STAND_DOWN_VISIBLE}
              </span>
            )}
          </span>
          {/* An away window draws none of the three derived rows: there is no card behind it, so
              there is no pool, and an empty histogram beside "Best — nothing in reach" would be
              three claims about a night nobody forecast. */}
          {!card.away && (
            <>
              {/* The Spread row's WRAPPER (spread-row-plan.md §2 #2): a grid child spanning both
                  columns, so the second label line can never widen the `auto` column the rating
                  pill below sits in. Both states — bars and the below-gate note — live inside it,
                  so the pool label is drawn in both. */}
              {!zeroPicks && (
              <span data-testid="wf-heat-spread-row" className="wf-hc-sprow">
                <span data-testid="wf-heat-spread-label" className="wf-hc-k wf-hc-sk">
                  Spread
                  {/* Absent — no element, not an empty one — when the pool was not wholly gated by
                      the active tier (`facts.qualifier`'s own note). Parenthesised in the markup,
                      not by CSS `::before`, so a text assertion reads what a sighted reader sees. */}
                  {facts.qualifier && (
                    <small data-testid="wf-heat-spread-qualifier">({facts.qualifier.text})</small>
                  )}
                </span>
                <span data-testid="wf-heat-spread-value" className="wf-hc-pv">
                  {/* `title` carries the per-band breakdown when bars are drawn (the pool count
                      itself is in the card's hidden sentence, so the figure a reader acts on is in
                      the accessibility tree and only the detail is pointer-only); below the
                      minimum-sample gate it carries the SAME short text the row and the sentence
                      show, never a longer one (`spreadTitle`'s own doc comment). The counts under
                      the bars are visible text now, and `accessibleName` voices them too. */}
                  <span
                    data-testid="wf-heat-spread"
                    className={facts.state.bars
                      ? `wf-hc-hist${facts.countLayout.small ? ' sm' : ''}`
                      : 'wf-hc-hist-note'}
                    style={facts.state.bars ? { '--cw': `${facts.countLayout.widthPx}px` } : undefined}
                    title={facts.title}
                  >
                    {facts.state.bars ? (
                      <>
                        {/* The unrated remainder (Part 2 of the minimum-sample rule) — a SIXTH,
                            hatched bar to the LEFT of 1★, on the bands' own scale. Hatched, never a
                            ramp colour: the map's own unscored plate already means "nothing was
                            scored here" with this exact texture, and 1★ means "scored, and poorly"
                            — giving the remainder a ramp colour would have this histogram
                            contradict the map over the same fact. Absent entirely when nothing is
                            unrated. Its COLUMN, not the bar, carries the wider gap
                            (`wf-hc-hb-unrated`): the margin is spent between outer items, and
                            inside a fixed-width column it would be swallowed (spread-row-plan.md
                            §2 #3, S2 task 4). Its count IS the "not yet rated" figure the tooltip
                            and the sentence already name. */}
                        {facts.unratedBar && (
                          <span className="wf-hc-hb wf-hc-hb-unrated">
                            <i
                              data-testid="wf-heat-spread-bar-unrated"
                              className="wf-hc-hist-unrated"
                              style={{ height: `${facts.unratedBar.heightPx}px` }}
                            />
                            <em data-testid="wf-heat-spread-count-unrated">
                              {formatSpreadCount(facts.unratedBar.count)}
                            </em>
                          </span>
                        )}
                        {facts.bars.map((bar) => (
                          <span
                            key={bar.star}
                            className={`wf-hc-hb${bar.count > 0 ? '' : ' wf-hc-hb-zero'}`}
                          >
                            <i
                              data-testid="wf-heat-spread-bar"
                              data-star={bar.star}
                              style={{
                                height: `${bar.heightPx}px`,
                                // The band's own ramp colour, from the same module the canvas
                                // above it is painted from — never a second table of five hexes.
                                // Full opacity, and the empty band lifted to 0.40: both are
                                // measured against the histogram's dark well rather than against
                                // the card, which is what puts every stop over SC 1.4.11's 3:1 —
                                // see the stylesheet's note for the whole table.
                                background: bar.filled
                                  ? rgb(rampRgb(bar.star), 1)
                                  : 'rgba(242,231,211,0.40)',
                              }}
                            />
                            <em data-testid="wf-heat-spread-count" data-star={bar.star}>
                              {formatSpreadCount(bar.count)}
                            </em>
                          </span>
                        ))}
                      </>
                    ) : (
                      <span data-testid="wf-heat-spread-note">{facts.state.text}</span>
                    )}
                  </span>
                </span>
              </span>
              )}
              {facts.best && (facts.best.rating != null ? (
                <span
                  data-testid="wf-heat-best-rating"
                  className="wf-hc-rt"
                  // The ramp as a FILL with `readableInkOn`'s per-stop ink, not as text in the ramp
                  // colour: the bottom two stops measure 2.9:1 and 3.7:1 as ink on this card and
                  // would ship a rating below AA on exactly the nights the best you can reach is a
                  // 1 or a 2. `spotBadgeStyle` is the arm's existing measured pair — see the
                  // stylesheet's note, and `windowFirstSpots.readableInkOn` for why one ink cannot
                  // serve five stops.
                  style={spotBadgeStyle(facts.best.rating) ?? undefined}
                >
                  {`${facts.best.rating}★`}
                </span>
              ) : (
                <span className="wf-hc-k">{facts.best.label}</span>
              ))}
              {facts.best && (
              <span className="wf-hc-pv2">
                <span
                  data-testid="wf-heat-best"
                  className={`wf-hc-best${facts.best.muted ? ' none' : ''}${facts.best.beyond ? ' beyond' : ''}`}
                  title={facts.best.title ?? undefined}
                >
                  {/* The preference-axis glyph (tide-plan-card-plan.md §3 C2 task 1) — the pick is
                      chosen on score, so a miss draws nothing here: no glyph, no recolour of the
                      name. `.wf-hc-pls` is `aria-hidden`, so the claim reaches a screen reader only
                      through `facts.best.spoken`'s own clause, never an `sr-only` span in this
                      subtree (§1 #2). */}
                  {facts.best.tideAligned && (
                    <TideWave
                      className="wf-hc-best-tw"
                      testId="wf-heat-best-tide"
                      state={facts.best.tideState}
                    />
                  )}
                  {facts.best.value}
                </span>
              </span>
              )}
              {/* Reserved even when empty, so a topic-free card's rows land on the same baselines
                  as its neighbours' — the design's own reason. */}
              <span data-testid="wf-heat-topics" className="wf-hc-tps">
                {/* The tide chip (tide-plan-card-plan.md §3 C2 task 2) — first in the line, as the
                    spec places it, by DOM order (§1 #4). A client-derived mark rather than a served
                    badge, so it is its own element rather than a member of `facts.topics`. Silent
                    below the gate: no greyed chip, no zero (§2). */}
                {facts.tideChip && (
                  <span
                    data-testid="wf-heat-tide-chip"
                    data-channel="tide"
                    className="wf-hc-tw wf-hc-tide"
                    data-best={facts.tideChip.isBest || undefined}
                    title={facts.tideChip.tooltip}
                  >
                    <TideWave />
                    {`${facts.tideChip.matched} on tide`}
                    {facts.tideChip.isBest && (
                      <em className="wf-hc-tide-best">{`best of ${facts.tideChip.liveCount}`}</em>
                    )}
                  </span>
                )}
                {facts.topics.map(({ badge }) => {
                  // The clocked chip (lunar-eclipse-plan.md §2.6) — a badge whose own clock is a
                  // DIFFERENT instant from the window's (both eclipse types) prints it after a
                  // divider in the tide chip's emphasised shape. `facts.topics` holds only these.
                  //
                  // ⚠️ The clock is a SEPARATE, non-shrinking flex item from the label, never a
                  // second text node inside the same ellipsis-clipped span. `.wf-hc-tw`'s base
                  // rule clips the WHOLE element's content as one run — at the supported ~137px
                  // card width a label such as "Deep partial eclipse" already fills the chip, so
                  // the trailing time was clipped along with it (found by review of #915 — the
                  // clock rendered in tests, which stub layout, but never at the actual card
                  // width). `.wf-hc-clocked-label` ellipsizes on its own, shrinkable; the clock
                  // is reserved (`flex: none`) so it is never the thing that gives way.
                  const clock = chipClock(badge);
                  return (
                    <span
                      key={`${badge.type}:${badge.label}`}
                      data-testid="wf-heat-topic"
                      data-channel={badgeChannel(badge.type)}
                      className="wf-hc-tw wf-hc-clocked"
                      title={badge.detail ? `${badge.label} — ${badge.detail}` : badge.label}
                    >
                      <span data-testid="wf-heat-topic-label" className="wf-hc-clocked-label">
                        {badge.label}
                      </span>
                      <em data-testid="wf-heat-topic-clock" className="wf-hc-clocked-time">
                        {clock}
                      </em>
                    </span>
                  );
                })}
              </span>
            </>
          )}
        </span>
        {/* The pick rides the card's own border, like a legend on a fieldset, so it stops
            competing with the verdict word for the same column and every card keeps its verdict
            in the same place. `background: inherit` is what cuts the border line behind it — see
            the stylesheet, where that pairing is load-bearing. */}
        {card.pickKind && (
          <span
            data-testid="wf-heat-legend"
            data-pick={card.pickKind}
            className="wf-hc-lg"
            aria-hidden="true"
          >
            {card.pickKind === 'best' ? 'Best bet' : 'Also good'}
          </span>
        )}
      </>
    );
    const className = [
      'wf-hc',
      tint,
      solo ? 'solo' : null,
      open ? 'on' : null,
      card.pickKind === 'best' ? 'best' : null,
      card.pickKind === 'also' ? 'also' : null,
    ].filter(Boolean).join(' ');
    // An away window has no row to open — see the class comment. It keeps its cell and its sun
    // time and simply is not a control.
    return card.away ? (
      <div
        key={card.key}
        data-testid="wf-heat-card"
        data-away="true"
        className={`${className} wf-hc-away`}
        style={place}
      >
        {body}
      </div>
    ) : (
      <button
        key={card.key}
        type="button"
        data-testid="wf-heat-card"
        // The same attribute name the region band's swatch uses for the same state, so the two
        // unscored marks on the Plan screen are one convention rather than two. It drives no
        // colour here: the mark is the canvas hatch, and dimming the verdict word instead would
        // put "Poor" — measured to AA in `index.css`, and the one word a reader most needs at a
        // glance — back under the floor that measurement set.
        data-unscored={notScored ? 'true' : undefined}
        data-open={open ? 'true' : undefined}
        // Ask PhotoCast's selected pick (F5) — a DIFFERENT mark from `data-open`, and the two can sit on
        // one card: the highlight never opens the popup, and an open popup never takes the highlight.
        data-ask-highlight={askRank !== null ? 'true' : undefined}
        // Which window is open was a CSS-only signal — a gold border tint and two recoloured words
        // — so a screen-reader user could not tell which one they were in, and neither could anyone
        // who cannot resolve the tint.
        //
        // ⚠️ M1 stated it as `aria-expanded` + `aria-controls` pointing at the row this cell
        // revealed. At M2 the row is gone and the cell opens a DIALOG, so both had to change rather
        // than merely be re-pointed: `aria-controls` would have named an id no longer in the
        // document (an IDREF that resolves to nothing is announced as nothing), and `aria-expanded`
        // on a control that opens a modal reads as an in-place disclosure a reader can Tab into.
        // `aria-haspopup="dialog"` is the pattern for a control that opens one, and the expanded
        // state stays alongside it — ARIA permits the pair, and it is how a reader hears which of
        // six cards the dialog on screen belongs to.
        aria-haspopup="dialog"
        aria-expanded={open}
        // `aria-labelledby` at the visually-hidden sentence, rather than leaving
        // name-from-contents to find it. Both routes end at the same string, but this one is
        // explicit: every accessible-name implementation honours an IDREF, whereas
        // name-from-contents has to walk into a clipped 1px box — and a tree dump of the running
        // app showed these buttons with NO computed name while every sibling control that uses an
        // IDREF or a label was named. Still not an `aria-label`: the label is real text in the
        // document, built from the same values the visible words are, so it cannot drift and WCAG
        // 2.5.3's label-in-name holds by construction.
        aria-labelledby={nameId}
        className={className}
        style={place}
        onClick={() => onOpenWindow?.(card.key)}
      >
        {body}
      </button>
    );
  };

  /**
   * A hole the matrix explains, or leaves in silence — see {@code windowFirstMatrix.js} for why
   * only two of them get words.
   */
  const renderEmpty = (copy, column, row, key) => (
    <div
      key={key}
      data-testid="wf-heat-empty"
      className="wf-hgap"
      style={{ '--c': column, '--r': row }}
    >
      {copy && <span>{copy}</span>}
    </div>
  );

  /**
   * The day, said once per column instead of once per card. The rule running to the right edge of
   * the column is its header — the same legend idiom the picks use.
   *
   * <p>Extracted (matrix-axis plan D3) so the desktop `.wf-dhrow` and the phone's day-major loop
   * share one definition rather than two spellings of the same tile drifting apart — the diff that
   * introduced this function moves the JSX unchanged rather than rewriting it.
   *
   * @param {object} day the matrix's day descriptor
   * @param {number} column the grid column, 1-based
   */
  const renderDayHeader = (day, column) => (
    <div
      key={day.date}
      id={`${dayIdBase}-${day.date}`}
      data-testid="wf-heat-day"
      data-today={day.today ? 'true' : undefined}
      className={`wf-hday${day.today ? ' today' : ''}`}
      style={{ '--c': column, '--r': MATRIX_ROW.header }}
    >
      <span className="wf-hday-cal">
        {/* ⚠️ The WORD for today, in the weekday's own slot, and it is a deliberate
            deviation from the bundle ("Today's column: … No 'TODAY' word"). The design's
            reason for omitting it was that "the cards below already carry Tonight/Today in
            their own labels" — true of P2's thumbnail, which drew `card.label`, and false
            of the v3 card face, which draws the sun word instead and leaves the day
            label in the visually-hidden sentence alone. That left the today column marked
            by a gold tile border and a gold digit and by nothing else. The window rows
            still below the matrix rescue it this phase; M2 deletes them, so restoring the
            word now is cheaper than restoring it as an M2 blocker. Same slot, same size,
            no second row — only the tile is a few pixels wider, which shortens its own
            rule and nothing else. */}
        <span className="wf-hday-dow">{day.today ? 'Today' : day.dow}</span>
        <span className="wf-hday-dn">{day.dn}</span>
      </span>
      <span className="wf-hday-rule" aria-hidden="true" />
    </div>
  );

  /**
   * One day's topic pills, in the column of that day's header.
   *
   * @param {object} day the matrix's day descriptor
   * @param {number} column the grid column, 1-based
   * @param {boolean} phone the phone's flowing grid, where a topic-free day draws nothing
   */
  const renderTopics = (day, column, phone) => (
    <WindowGlanceDayTopics
      key={`${day.date}:topics`}
      date={day.date}
      column={column}
      row={MATRIX_ROW.topics}
      chips={glanceTopics.byDate.get(day.date) ?? []}
      labelledBy={`${dayIdBase}-${day.date}`}
      phone={phone}
      onTap={tapTopic}
    />
  );

  /**
   * One sun-word rail — the full-width `SUNRISE`/`SUNSET` band above each row (matrix-axis plan D7).
   * A div, not a button: nothing here is clickable, and every card already self-describes its event
   * in its own hidden sentence, so the rail is `aria-hidden` rather than a second announcement of
   * the same axis.
   *
   * @param {boolean} am sunrise rail (`true`) or sunset (`false`)
   */
  const renderRail = (am) => (
    <div className={`wf-rail ${am ? 'am' : 'pm'}`} data-testid="wf-heat-rail" aria-hidden="true">
      <span>{am ? 'Sunrise' : 'Sunset'}</span>
      <span className="wf-rail-rule" />
    </div>
  );

  // A plain `<section>`, deliberately without an `aria-label`: a NAMED section becomes a `region`
  // landmark, and this would be the arm's only one — a landmark inside a tabpanel whose name
  // matches no visible text, announced ahead of buttons that each name themselves. The grouping
  // the matrix needs is visual, and the buttons carry the words.
  return (
    <section ref={stripRef} data-testid="wf-heat-strip" className="wf-hstrip-block">
      <div data-testid="wf-heat-head" className="wf-hstrip-h">
        {/* ⚠️ A HEADING, not a span. It is a section heading by every visual convention — 9.5px mono,
            600, letter-spaced, uppercase, beside a full-width hairline — and it was marked up as a
            `<span>`, which left the whole v2 Plan tab with exactly one heading (the masthead
            wordmark's `h1`). A browse-mode reader pressing `H` went from the wordmark to the end of
            the page. Level 2 under that `h1`; `.wf-hstrip-k` carries every visible property and
            Tailwind's preflight resets heading size and weight to inherited, so nothing moves. */}
        <h2 className="wf-hstrip-k">The days ahead</h2>
        <span className="wf-hstrip-rule" aria-hidden="true" />
        {glanceTopics.lastTopicDate && <GlanceTopicKey />}
      </div>

      {/* ⚠️ Two structures from one matrix, never both mounted at once (matrix-axis plan D3). A
          desktop reader gets sticky day tiles plus two row-scoped rails, which needs `.wf-dhrow` and
          `.wf-hrow` to contain rail + cards together — a rail can only pin over its own row's cards
          if its containing block wraps both, and a single grid confines a sticky item to its own row
          track. A phone reader keeps today's exact single-grid, day-major markup unchanged, because
          rendering both and hiding one would double the heat-field canvases and burn a hidden one's
          measurement retry budget at `clientWidth` 0. */}
      {isMobile ? (
        <div
          ref={attachFrame}
          data-testid="wf-heat-grid"
          className="wf-hstrip"
          style={{ '--dc': matrix.columns }}
        >
          {matrix.days.map((day, index) => {
            const column = index + 1;
            return (
              <React.Fragment key={day.date}>
                {renderDayHeader(day, column)}
                {/* The topic stack flows straight after its own day's header, spanning the row (the phone grid
                    has no sticky header to protect, so this is the cleanest place; the stack is NEVER inside
                    `renderDayHeader`, which the sticky, measured desktop `.wf-dhrow` shares). */}
                {renderTopics(day, column, true)}
                {day.date === glanceTopics.lastTopicDate && <GlanceTopicDoor onOpen={onOpenComingUp ?? noop} />}
                {day.am
                  ? renderCard(day.am, column, MATRIX_ROW.sunrise, day.solo)
                  : renderEmpty(day.amEmpty, column, MATRIX_ROW.sunrise, `${day.date}:am`)}
                {day.pm
                  ? renderCard(day.pm, column, MATRIX_ROW.sunset, day.solo)
                  : renderEmpty(day.pmEmpty, column, MATRIX_ROW.sunset, `${day.date}:pm`)}
              </React.Fragment>
            );
          })}
        </div>
      ) : (
        <div
          ref={attachFrame}
          data-testid="wf-heat-grid"
          className="wf-hstrip wf-hstrip-rows"
          style={{ '--dc': matrix.columns }}
        >
          <div ref={setDhrowNode} data-testid="wf-heat-dhrow" className="wf-dhrow">
            {matrix.days.map((day, index) => renderDayHeader(day, index + 1))}
          </div>
          {/* A NON-sticky row between the pinned date chips and the cards: inside `.wf-dhrow` the taller pills
              would pin a taller band over the cards (see `.wf-dtrow`). Every column keeps its cell. */}
          {glanceTopics.lastTopicDate && (
            <>
              <div data-testid="wf-heat-dtrow" className="wf-dtrow">
                {matrix.days.map((day, index) => renderTopics(day, index + 1, false))}
              </div>
              <GlanceTopicDoor onOpen={onOpenComingUp ?? noop} />
            </>
          )}
          <div className="wf-hrow">
            {renderRail(true)}
            <div data-testid="wf-heat-cards" className="wf-hcards">
              {matrix.days.map((day, index) => {
                const column = index + 1;
                return day.am
                  ? renderCard(day.am, column, MATRIX_ROW.sunrise, day.solo)
                  : renderEmpty(day.amEmpty, column, MATRIX_ROW.sunrise, `${day.date}:am`);
              })}
            </div>
          </div>
          <div className="wf-hrow">
            {renderRail(false)}
            <div data-testid="wf-heat-cards" className="wf-hcards">
              {matrix.days.map((day, index) => {
                const column = index + 1;
                return day.pm
                  ? renderCard(day.pm, column, MATRIX_ROW.sunset, day.solo)
                  : renderEmpty(day.pmEmpty, column, MATRIX_ROW.sunset, `${day.date}:pm`);
              })}
            </div>
          </div>
        </div>
      )}

      <div data-testid="wf-heat-foot" className="wf-hstrip-foot">
        {picksLocked && (
          <ProUpsellDoor
            className="wf-pro-upsell-door--foot"
            label={BEST_BETS_UPSELL_LABEL}
            heading={BEST_BETS_UPSELL_HEADING}
            body={BEST_BETS_UPSELL_BODY}
            testIdPrefix="wf-heat-bestbets-upsell"
          />
        )}
        <span
          data-testid="wf-heat-legbar"
          className="wf-hstrip-legbar"
          aria-hidden="true"
          style={{ background: rampGradientCss() }}
        />
        <span>poor → worth it</span>
        <span>later days look hazier — lower confidence</span>
        {/* The picture's three grounds, named (thin-sample plan F2): colour is a rating, hatch is a
            place nobody rated for this window, plain plate is no place at all. ALWAYS drawn — the
            per-place hatch appears on any window with unrated places, so the key can no longer be
            conditional the way the old "unshaded — not scored" note was (that note named only the
            whole-plate hatch of an unscored window, which is "not rated yet" too). */}
        <span className="wf-hstrip-key" data-testid="wf-heat-key-hatch">
          <span className="wf-hstrip-sw wf-hstrip-sw-hatch" aria-hidden="true" />
          not rated yet
        </span>
        {stoodShown && (
          <span className="wf-hstrip-key" data-testid="wf-heat-key-standdown">
            <span
              className="wf-hstrip-sw wf-legend-swatch-standdown"
              data-testid="wf-heat-key-standdown-swatch"
              aria-hidden="true"
            />
            Stood down — ruled out by the weather
          </span>
        )}
        <span className="wf-hstrip-key" data-testid="wf-heat-key-plain">
          <span className="wf-hstrip-sw" aria-hidden="true" />
          no places
        </span>
        {/* Outside your sub-regions (S5b-1, handoff task 6): the two grounds of a body shaped by the reader's picks,
            and the note under which the colour is drawn. "Outside your sub-regions" carries a plain Pro pill — a
            label, not a door (the one door on this surface is the best-bets one above). The dashed swatch names the
            outline, which a reader with no pick does not have; they get the way in instead, once, as a button. On a
            phone the whole group takes its own wrapped line. */}
        {shaped && (
          <span data-testid="wf-heat-picks" className="wf-hstrip-picks">
            <span className="wf-hstrip-key" data-testid="wf-heat-key-outside">
              <span className="wf-hstrip-sw wf-hstrip-sw-outside" aria-hidden="true" />
              outside your sub-regions
              <span className="wf-lens-pro">Pro</span>
            </span>
            {!zeroPicks && (
              <span className="wf-hstrip-key" data-testid="wf-heat-key-picks">
                <span className="wf-hstrip-sw wf-hstrip-sw-picks" aria-hidden="true" />
                your sub-regions
              </span>
            )}
            <span data-testid="wf-heat-picks-note" className="wf-hstrip-picks-note">
              {picksNoteText(picks)}
            </span>
            {zeroPicks && (
              <SubRegionActionButton className="wf-subregion-btn" testId="wf-heat-picks-action" />
            )}
          </span>
        )}
        {/* The histogram's own hatch (the minimum-sample rule's Part 2) gets the SAME treatment as
            the map plate's note above, for the same reason: a texture is not vocabulary and a
            card's own rows have no room for it. ⚠️ NOT rare in practice — a catalogue with any
            sparsely-rated windows shows this on most loads, closer to permanent than occasional —
            it is still conditional (absent whenever every card on screen either clears the gate
            with nothing unrated or draws no bars at all) and it renders at EVERY width, not just
            the phone, for the same reason the map's note above it does. Worded
            differently from the map's note on purpose — "unshaded" names the map's mark and
            "hatched bar" names the histogram's, so the two conditional clauses can never be
            mistaken for restating each other when both happen to be on screen at once. */}
        {hatchedSpread && (
          <span data-testid="wf-heat-spread-unrated-note">hatched bar — not yet rated</span>
        )}
      </div>

      {/* Under the footer and above the beyond line: it is a statement about the whole matrix, so
          it sits with the legend that decodes it rather than among the cards — and the beyond line
          stays last, because that one is the tail about what is NOT drawn.

          ⚠️ THE PREPOSITION IS "AT", NEVER "SINCE", and the two are not interchangeable here.
          The delta's basis is the build BEFORE the one whose age this line prints — the interval
          measured is [previous build, now], while `generatedAt` names the LAST build. "Since the
          last forecast run 52m ago" (the design's own sample copy) therefore claims the one
          interval in which almost none of the movement happened: with builds ~11h apart, a reader
          at 14:52 would be told a ten-hour change occurred in fifty-two minutes. "Changed at"
          attributes the change to the run rather than to the period after it, which is true of the
          run-to-run component and honest about the rest. The VERB is free — this line read "Moved
          at" until the copy pass, which is the same claim in clumsier English — the PREPOSITION is
          not.

          ⚠️ It is now the ONLY movement channel on this surface — the per-card chip died with the
          old card face (deletion ledger, M1) — so the region names and the age it carries are the
          whole of what a reader is told about change on the Plan screen until M2's popup header.

          The age stays `generatedAt` (not the served `previousGeneratedAt`) because it is the
          stamp the shell footer used to print, and two different ages for one forecast on one
          screen is its own defect. The residual caveat is on the field itself
          (`BriefingRegion.meanRatingDelta`): the current side is re-derived at serve time, so the
          quantity includes post-build batch drift as well as run-to-run change.

          ⚠️ THE AGE NOW HAS NOWHERE ELSE TO GO. M3 deleted the rail footer, so this is the page's
          only statement of how old the forecast is (Rule 7's "one age per screen", finally true
          rather than approximately so — the footer and this line both printed it before). That is
          why the branch below exists: with no movement basis the change line is withheld, and
          withholding the AGE with it would lose a fact that is not about movement at all. The two
          forms are mutually exclusive by construction and a test pins that they are, because two
          age lines is the defect this consolidation removes. The no-movement wording drops the
          verb entirely — "Changed at the last forecast run" over an empty list would assert a
          movement the same element has just declined to name. */}
      {movers.length === 0 && runAge && (
        <p data-testid="wf-heat-runage" className="wf-hstrip-change">
          {`Last forecast run ${runAge}`}
        </p>
      )}
      {movers.length > 0 && (
        <p data-testid="wf-heat-change" className="wf-hstrip-change">
          {runAge
            ? `Changed at the last forecast run, ${runAge}`
            : 'Changed at the last forecast run'}
          {movers.map((mover) => (
            <span key={mover.key} data-testid="wf-heat-change-item">
              {/* ⚠️ THE SEPARATOR AND THE REGION SIT OUTSIDE THE `nowrap` ATOM, and that is a
                  reflow fix rather than a preference. With `white-space: nowrap` on the whole item
                  the clause was ONE unbreakable run — JSX strips the whitespace-only lines between
                  array elements, so the last break opportunity in the paragraph was the space
                  before the run age, and a 25-character region name (the arm's own
                  "Northumberland & Tyneside") put ~328px of unbreakable text into a 315px line at
                  375px. That is a horizontal page scroller, which is exactly what `.wf-hstrip`'s
                  own comment says this surface exists not to be. Only `label ▲0.6` may not break.

                  The middle dot is `aria-hidden` with a real comma beside it: NVDA, JAWS and
                  VoiceOver do not speak `·` at default punctuation levels and do not pause on it,
                  so without the comma the clauses fuse into one run-on — while this file's own
                  accessible-name rule is that "the comma-separated form is what a screen reader
                  pauses on". */}
              <span aria-hidden="true">{' · '}</span>
              <span className="sr-only">{', '}</span>
              <span className="wf-hstrip-chg">
                {mover.label}
                {' '}
                {/* The glyph carries the direction visually and is unreadable aloud, so it is
                    hidden and the words beside it say the same thing — the treatment the region
                    band's direction dots already use. */}
                <b
                  data-testid="wf-heat-change-mark"
                  data-tone={mover.chip.tone}
                  aria-hidden="true"
                >
                  {mover.chip.mark}
                </b>
              </span>
              <span className="sr-only">{` ${mover.chip.shortSpoken}`}</span>
              {` in ${mover.regionName}`}
            </span>
          ))}
        </p>
      )}

      {/* Named, never counted, and only where a drive was actually measured: `beyondRegions`
          withholds an unmeasured region precisely so this line cannot claim a distance nobody
          computed.

          The link is P7's, deferred from P2 for the reason this arm defers every such control: a
          line naming places you cannot reach, with no route to them, is the "number with no route
          to the thing it counts" defect CLAUDE.md already records against Close-to-home. It opens
          search pre-filled with the FIRST beyond region — `beyondRegions` is sorted nearest-first,
          so that is the one a reader is most likely to want, and the box is left editable rather
          than the link being one-per-region: six links would make the tail longer than the matrix.
          It is a link into search rather than a direct origin move because a beyond region may
          have no base town, and a control that silently does nothing is worse than one that shows
          you why. */}
      {beyond.length > 0 && (
        <p data-testid="wf-heat-beyond" className="wf-hstrip-beyond">
          {`Beyond ${GLANCE_HOURS}h from home: ${beyond.join(' · ')}`}
          {onSearchRegion && (
            <>
              {' '}
              <button
                type="button"
                data-testid="wf-heat-beyond-search"
                className="wf-hstrip-beyond-act"
                onClick={() => onSearchRegion(beyond[0])}
              >
                {`Plan from ${beyond[0]}`}
                <span aria-hidden="true"> →</span>
              </button>
            </>
          )}
        </p>
      )}
    </section>
  );
}

WindowFirstHeatStrip.propTypes = {
  /** The served almanac entries, for a topic pill's jump into Coming up. */
  comingUpEntries: PropTypes.array,
  /** Ids of the entries Coming up lists under its All filter; a pill never jumps to any other. */
  renderedEntryIds: PropTypes.oneOfType([
    PropTypes.instanceOf(Set), PropTypes.arrayOf(PropTypes.string),
  ]),
  /** The `date:TYPE` keys of the windows the Plan matrix has a card for — the only windows a pill can open. */
  openableKeys: PropTypes.instanceOf(Set),
  /** The dates the reader holds a colour forecast for; null is "not known", an array (even empty) is known. */
  forecastDates: PropTypes.arrayOf(PropTypes.string),
  /** `(entryId|null)` — opens Coming up, at that entry when one is named. Also the topic door's route. */
  onOpenComingUp: PropTypes.func,
  /** `({kind:'event', date, eventType, topicType})` — the Map door of a weather pill. Absent: no Map door. */
  onShowOnMap: PropTypes.func,
  /** `(windowKey, topicType)` — opens that window's popup with the topic emphasised. */
  onGoToPlanWindow: PropTypes.func,
  /** True for a reader without best bets: draws the key row's one Pro door. */
  picksLocked: PropTypes.bool,
  /** The active scoreRamp mode — a repaint key only; see the paint callback's note. */
  colourMode: PropTypes.oneOf(['temp', 'verdict']),
  cards: PropTypes.arrayOf(PropTypes.shape({
    key: PropTypes.string.isRequired,
    date: PropTypes.string.isRequired,
    targetType: PropTypes.string,
    dow: PropTypes.string,
    sunrise: PropTypes.bool,
    label: PropTypes.string,
    time: PropTypes.string,
    verdict: PropTypes.string,
    verdictLabel: PropTypes.string,
    bestRating: PropTypes.number,
    /** The served pick's kind — `best`, `also`, or null. Never re-derived here (plan §3 rule 1). */
    pickKind: PropTypes.oneOf(['best', 'also']),
    away: PropTypes.bool,
    confidence: PropTypes.string,
    movement: PropTypes.shape({
      regionName: PropTypes.string,
      delta: PropTypes.number,
    }),
    /** The reach-gated, pre-floor spot pool the spread and the best-reach line are measured over. */
    pool: PropTypes.array,
    /** True when the neighbour guard skipped an outlier and nothing clears the named floor. */
    bestReachWithheld: PropTypes.bool,
    /** That pool's best non-outlier spot, or null when nothing in it is rated — see {@link bestReachLine}. */
    bestReach: PropTypes.shape({
      locationName: PropTypes.string,
      regionName: PropTypes.string,
      rating: PropTypes.number,
      driveMinutes: PropTypes.number,
      solarEventTime: PropTypes.string,
      /** The preference-axis match, read by {@link bestReachLine} — `=== true` for a match. */
      tideAligned: PropTypes.bool,
      /** The served state at the light this window — the glyph's own letter reads this on a
       * match (tide-window-plan.md §4 #21). */
      tideState: PropTypes.oneOf(['HIGH', 'MID', 'LOW']),
    }),
    /** The window's badges BEFORE row promotion — the matrix names every topic on the night. */
    badges: PropTypes.array,
    /** The window's leading region, for the thumbnail's brightened area name. Null when unrated. */
    hotRegionName: PropTypes.string,
    /** The served mean of the window's top region (the Order control's key). Not printed directly. */
    topMeanRating: PropTypes.number,
    /**
     * The mean the card may print beside its verdict word: `topMeanRating` only when the top region
     * is verdict-eligible, else null. Read by the face, the spoken sentence and the H2 "beyond the
     * tier" line, all of which claim a RATING stands behind the word.
     */
    verdictMean: PropTypes.number,
    verdictBlanked: PropTypes.bool,
    /** The active reach tier's threshold in minutes; null under "Any" (H2). */
    reachLimitMinutes: PropTypes.number,
    /** Whether a drive time exists for the tier to have gated on (H2 requires it). */
    reachMeasured: PropTypes.bool,
    /** Present (true) only on a body the server shaped by the reader's sub-regions (S5b-1): the pool holds their places only. */
    picksScoped: PropTypes.bool,
    /** The places of the window outside the reader's sub-regions; only on a shaped body's card. */
    outsideCount: PropTypes.number,
  })).isRequired,
  /**
   * Each window's two grounds on a body shaped by the reader's sub-regions (S5b-1), keyed by window key: the places
   * outside the picks, every other place, the allowed places nobody has rated, and whether any is rated
   * ({@code pickScope.buildGroundSets}). Absent or empty for every other reader.
   */
  groundSets: PropTypes.instanceOf(Map),
  /**
   * Present ONLY for a body the server shaped by the reader's sub-regions: {@code allowed} the places the picks
   * cover (the served {@code allowedLocationIds}), {@code total} the roster. Null (every Pro, Admin and flag-off
   * reader) draws exactly what it always did.
   */
  picks: PropTypes.shape({
    allowed: PropTypes.number.isRequired,
    total: PropTypes.number.isRequired,
  }),
  pointSets: PropTypes.instanceOf(Map),
  unratedSets: PropTypes.instanceOf(Map),
  stoodSets: PropTypes.instanceOf(Map),
  spots: PropTypes.array.isRequired,
  reachById: PropTypes.instanceOf(Map),
  /** The served hot topics, for the scope filter. Absent means nothing is scope-filtered. */
  hotTopics: PropTypes.array,
  openKeys: PropTypes.instanceOf(Set),
  highlightKeys: PropTypes.instanceOf(Map),
  todayStr: PropTypes.string,
  runAge: PropTypes.string,
  onOpenWindow: PropTypes.func,
  /**
   * The away origin ({@code {name, baseName}}), or null for home. Framing only — it never reaches
   * the kernel's point set.
   */
  origin: PropTypes.shape({
    name: PropTypes.string.isRequired,
    baseName: PropTypes.string.isRequired,
  }),
  /** Opens search pre-filled with a region name. Omit and the beyond line renders without its link. */
  onSearchRegion: PropTypes.func,
  /**
   * The user's saved geocode, or null with no postcode saved — never a constant (field-geography
   * plan §2.1). Drawn only when {@code origin} is also null.
   */
  homeCoords: PropTypes.shape({
    lat: PropTypes.number,
    lon: PropTypes.number,
  }),
};
