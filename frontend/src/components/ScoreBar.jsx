import React from 'react';
import PropTypes from 'prop-types';
import { rampHex, starFromScore } from '../utils/scoreRamp.js';

/**
 * The lowest star value the number's tint may sample — see {@link ScoreBar}'s module doc's ⚠️ note.
 * Chosen so the DIMMED case (`.wf-loc-row[data-dim='true']`'s 0.8 opacity, which this component
 * cannot see or control) still clears 4.5:1, not just the rest state.
 */
const NUMBER_TINT_FLOOR = 2.8;

/**
 * The Fiery Sky / Golden Hour score bar — a label, a number, and a filled track — used on both the
 * Plan pane and the map popup.
 *
 * <p>Merges what were two components (heat-scale-unification-plan.md, Stage 5b): the Plan pane's
 * {@code PlanScoreBar} (itself extracted from {@code WindowSpotPeek}'s module-private
 * {@code PeekScoreBar}, location-sheet superset plan Phase 1) and the map popup's module-private
 * {@code PopupScoreRow} in {@code MarkerPopupContent.jsx}. Both drew the same two measurements from
 * quoted copies of the same two gradient strings; once the fill is derived from the ramp instead of
 * hard-coded, the reason to keep them apart (importing MarkerPopupContent's ~1,300-line module graph
 * to fetch two strings) evaporates.
 *
 * <p><b>The fill is a continuous solid colour</b>, not a gradient —
 * {@code rampHex(starFromScore(score, metric))}. A bar has one value; a gradient across a ramp that
 * starts cold would paint a five-hue rainbow for one number. This also closes the Plan bar's
 * documented no-tint deviation: <b>the number is tinted to match the fill, on both surfaces.</b> That
 * does not reintroduce an SC 1.4.1 problem (nothing is encoded by colour alone) because the numeral
 * already states the value in text — 1.4.1 was the actual requirement the old no-tint note protected.
 *
 * <p>⚠️ <b>The number's tint is floored at {@link NUMBER_TINT_FLOOR}, not the bar's raw score.</b>
 * "Tinted to match" cannot mean the literal ramp hue for text: this component's number sits directly
 * on three real backgrounds — {@code --color-plex-surface} (`#221A15`, the Plan row and the Leaflet
 * popup panel share this token) and {@code --color-plex-surface-light} (`#2A2019`,
 * `WindowSpotPeek`'s `.wf-peek` tooltip) — and the ramp's own bottom stops fail AA on both as plain
 * text: 1★ `#B03A2A` measures 2.84:1 on `--color-plex-surface` and 2★ `#C8452F` measures 3.54:1,
 * both under the 4.5:1 floor, before dimming is even applied. This is the marker label's problem in
 * reverse: {@code readableInkOn} solves "text ON an arbitrary fill" by choosing between two fixed
 * inks, but there is no fill here to choose against — the text colour itself must clear AA on a fixed
 * background, and no choice of ink rescues a hue that is simply too dark. The floor is 2.8★, not the
 * 2.35–2.42★ where the ramp first crosses 4.5:1 at rest (varies slightly by background), because
 * `LocationFourDaySheet`'s `.wf-loc-row[data-dim='true']` rule dims `.wf-loc-score-label` — which
 * contains this number, on `--color-plex-surface` only; `.wf-peek` is never dimmed — to 0.8 opacity,
 * and opacity blends the rendered colour toward the surface, dropping contrast further: at 2.5★ rest
 * measures 5.2:1 but DIMMED drops to 3.81:1, still a failure. 2.8★ measures 6.68–6.91:1 at rest on
 * `--color-plex-surface` and 6.21:1 at rest on `--color-plex-surface-light`, 4.75–4.85:1 dimmed on
 * `--color-plex-surface`, and nothing between 2.8★ and 5★ dips back below 4.5:1 on either background
 * in either state (swept at 0.02★ resolution; floor itself is the minimum in that range). The BAR's
 * fill is deliberately left unclamped — it is a plate, not text, and carries no contrast requirement
 * of its own (the same reasoning that already excludes `.wf-peek-bar` from the row-dim selector list
 * in `index.css`).
 *
 * <p>⚠️ <b>This measurement covers {@code scoreRamp.js}'s default `verdict` mode only.</b> The module
 * also carries a `temp` mode ({@code STOPS_TEMP}) selected by a module-global {@code setMode}, not yet
 * wired to any live control — nothing in the app calls `setMode('temp')` today. `STOPS_TEMP`'s hot
 * leg is deliberately dark (its own doc: "descends monotonically in luminance... 0.139 at 5"), and at
 * `--color-plex-surface` its top stops measure as low as 3.08:1 at rest — below AA even before the
 * 2.8★ floor could help, since the floor only ever raises a low value and does nothing at the ramp's
 * hot end. If a later stage (`heat-scale-unification-plan.md` Stage 6/7, "the preference, full-stack")
 * wires {@code MODE} to a live toggle that a `ScoreBar` consumer can reach, this floor's coverage
 * claim must be re-measured against `STOPS_TEMP` before that ships — it is not automatically safe.
 *
 * <p>The two surfaces' markup stays genuinely different rather than being forced into one shape:
 * {@code dense} selects the Plan pane's Tailwind-classed, `.wf-peek-bar`-based markup at a 10px type
 * scale; the default renders the popup's inline-style markup at 11px, carried across unedited.
 * CLAUDE.md's "Tailwind only (no inline styles)" rule was already violated by both predecessors
 * before this merge — converting either markup style wholesale is pre-existing debt, not this stage's
 * job — but the dense number's `color: numberFill` is new, not carried-over debt: the deleted
 * `PlanScoreBar` coloured that span with the Tailwind class `text-plex-text` and left only
 * `fontWeight` inline. That class encoded a fixed token; it cannot express a JS-computed colour that
 * changes per score, and this codebase has already been burned once by assuming a runtime-interpolated
 * Tailwind class (`text-[${x}]`) would work — the JIT scanner reads source text at build time and
 * cannot see a value that only exists once the app runs, so such a class is silently pruned rather
 * than erroring (the exact class of defect a past review round on this project caught: a theme token
 * pruned to the empty string). An inline `style` is therefore the only correct way to paint a
 * per-score colour, matching the precedent this same component already set for the bar's own fill
 * (`.wf-peek-bar`'s `background: fill` was inline before this merge too, for the identical reason).
 * `dense` is the one prop distinguishing which markup renders, since both current Plan call sites —
 * the peek and the location sheet — used the Plan scale before this merge and neither asked to
 * change; only `metric` and `testId`/`tooltip` genuinely vary per call site beyond that.
 *
 * <p>The two null behaviours from before the merge both survive, because they answer different
 * questions: a Plan caller renders nothing for a missing score (guarded by its own `score != null`
 * before it ever reaches this component — a stray dash in a tooltip is noise), while a popup caller
 * passes `null` straight through and gets an em dash (a popup row that vanished would be a layout
 * jump). {@code dense} mode therefore never receives a null `score` in practice; the em-dash branch
 * only exists on the non-dense (popup) side.
 *
 * @param {object} props
 * @param {string} props.label the measurement's own name — 'Fiery Sky' | 'Golden Hour' — printed as-is
 * @param {number|null} props.score 0–100; null renders an em dash rather than a zero-length bar
 * @param {'fiery'|'golden'} props.metric which {@link starFromScore} anchor table maps this score onto
 * @param {string} props.testId test id for the whole bar
 * @param {React.ReactNode} [props.tooltip] an info tip beside the label; popup callers pass one, Plan
 *        callers pass none
 * @param {boolean} [props.dense=false] true selects the Plan pane's 10px scale and `.wf-peek-bar`
 *        markup; false (default) selects the popup's 11px inline-style markup
 * @param {string} [props.labelClassName] an extra class for the label row in `dense` mode only,
 *        appended rather than baked in — `LocationFourDaySheet`'s `.wf-loc-score-label` dimming hook
 */
export default function ScoreBar({
  label, score, metric, testId, tooltip = null, dense = false, labelClassName = '',
}) {
  const pct = score != null ? Math.min(100, Math.max(0, score)) : null;
  // starFromScore validates `metric` by throwing on an unrecognised one (5a's own guard) — called
  // unconditionally, even for a null score, so a typo'd metric fails immediately at the call site
  // that introduced it rather than staying silent until that same slot happens to receive a score.
  const rawStar = starFromScore(pct ?? 0, metric);
  const rampScore = pct != null ? rawStar : null;
  const fill = rampScore != null ? rampHex(rampScore) : null;
  // Reuses `fill` rather than a second rampHex call when the score is already at or above the floor
  // (the common case) — Math.max would otherwise recompute the identical ramp lookup twice per render.
  const numberFill = rampScore == null
    ? null
    : rampScore >= NUMBER_TINT_FLOOR ? fill : rampHex(NUMBER_TINT_FLOOR);

  if (dense) {
    return (
      <div data-testid={testId} data-score={score} style={{ marginTop: '6px' }}>
        <div
          className={`flex items-center justify-between font-mono${labelClassName ? ` ${labelClassName}` : ''}`}
          style={{ fontSize: '10px', marginBottom: '3px' }}
        >
          <span className="text-plex-text-secondary">{label}</span>
          <span style={{ fontWeight: 600, color: numberFill }}>{pct}</span>
        </div>
        <div className="wf-peek-bar" style={{ background: fill ?? 'var(--color-plex-surface-light)' }}>
          <span className="wf-peek-bar-rest" style={{ width: pct != null ? `${100 - pct}%` : '100%' }} />
        </div>
      </div>
    );
  }

  const numberColor = numberFill ?? 'var(--color-plex-text-muted)';
  return (
    <div data-testid={testId} style={{ marginBottom: '4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--color-plex-text-secondary)', marginBottom: '2px' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', borderBottom: '1px dotted var(--color-plex-text-muted)' }}>
          {label}
          {tooltip}
        </span>
        <span style={{ fontWeight: '600', fontFamily: "'IBM Plex Mono', monospace", color: numberColor }}>{pct != null ? pct : '—'}</span>
      </div>
      <div style={{ position: 'relative', height: '6px', background: fill ?? 'var(--color-plex-surface-light)', borderRadius: '999px', overflow: 'hidden' }}>
        <div
          style={{
            position: 'absolute', top: 0, right: 0, height: '100%',
            width: pct != null ? `${100 - pct}%` : '100%',
            background: 'var(--color-plex-surface-light)',
          }}
        />
      </div>
    </div>
  );
}

ScoreBar.propTypes = {
  label: PropTypes.string.isRequired,
  // Deliberately not `.isRequired`: the prop is required in the sense that every caller must pass it
  // explicitly (never omit it), but `null` is a valid, meaningful value (popup callers pass it for an
  // unscored slot) and PropTypes' `.isRequired` has no clean way to say "present but nullable" — it
  // would warn on every legitimate null.
  score: PropTypes.number,
  metric: PropTypes.oneOf(['fiery', 'golden']).isRequired,
  testId: PropTypes.string.isRequired,
  tooltip: PropTypes.node,
  dense: PropTypes.bool,
  labelClassName: PropTypes.string,
};
