import React from 'react';
import PropTypes from 'prop-types';
import { rampHex, starFromScore } from '../utils/scoreRamp.js';


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
 * starts cold would paint a five-hue rainbow for one number.
 *
 * <p>⚠️ <b>The number is NOT tinted, and that is a decision rather than an omission.</b> Stage 5b
 * tinted it from the ramp, floored so it cleared AA as text; Design's call on 2026-08-26 was to
 * remove the tint entirely, on grounds broader than the contrast failure that raised it
 * (`heat-scale-unification-plan.md` Stage 7).
 *
 * <p><b>A ramp is a FILL scale, and a fill scale cannot double as a TEXT scale.</b> As a fill,
 * {@link readableInkOn} puts ink <em>on top</em> and picks it per fill. As text, the ramp colour
 * <em>is</em> the ink on a dark surface — so the two uses want opposite things from the same value,
 * and the monotonic hot leg that made fills better necessarily made text worse. There is no top-end
 * value that satisfies both. Dimming settles it: a tint that must survive
 * {@code .wf-loc-row[data-dim='true']}'s 0.8 opacity needs headroom, and a fill ramp's hot end has
 * none by construction.
 *
 * <p>Two reasons independent of contrast, and they are why re-introducing a tint would be wrong even
 * if every stop passed. A numeral is a <b>precise</b> value where colour is a <b>categorical</b>
 * impression, so tinting the numeral makes the exact thing look approximate — while the bar beside it
 * already encodes hot-ness twice, by length and by fill. And thin coloured text is the weakest place
 * to spend colour for colour-blind readers: two large fills stay separable where two similar numerals
 * do not. The tint was a third encoding of a datum already encoded twice, and it was the one costing
 * contrast.
 *
 * <p>The BAR's fill is of course still the ramp's — it is a plate, not text, and carries no contrast
 * requirement of its own (the same reasoning that excludes `.wf-peek-bar` from the row-dim selector
 * list in `index.css`). SC 1.4.1 is satisfied throughout because the numeral states the value in
 * text; nothing here is encoded by colour alone.
 *
 * <p>The two surfaces' markup stays genuinely different rather than being forced into one shape:
 * {@code dense} selects the Plan pane's Tailwind-classed, `.wf-peek-bar`-based markup at a 10px type
 * scale; the default renders the popup's inline-style markup at 11px, carried across unedited.
 * CLAUDE.md's "Tailwind only (no inline styles)" rule was already violated by both predecessors
 * before this merge — converting either markup style wholesale is pre-existing debt, not this stage's
 * job.
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

  if (dense) {
    return (
      <div data-testid={testId} data-score={score} style={{ marginTop: '6px' }}>
        <div
          className={`flex items-center justify-between font-mono${labelClassName ? ` ${labelClassName}` : ''}`}
          style={{ fontSize: '10px', marginBottom: '3px' }}
        >
          <span className="text-plex-text-secondary">{label}</span>
          <span className="text-plex-text" style={{ fontWeight: 600 }}>{pct}</span>
        </div>
        <div className="wf-peek-bar" style={{ background: fill ?? 'var(--color-plex-surface-light)' }}>
          <span className="wf-peek-bar-rest" style={{ width: pct != null ? `${100 - pct}%` : '100%' }} />
        </div>
      </div>
    );
  }

  return (
    <div data-testid={testId} style={{ marginBottom: '4px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', color: 'var(--color-plex-text-secondary)', marginBottom: '2px' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', borderBottom: '1px dotted var(--color-plex-text-muted)' }}>
          {label}
          {tooltip}
        </span>
        <span style={{ fontWeight: '600', fontFamily: "'IBM Plex Mono', monospace", color: pct != null ? 'var(--color-plex-text)' : 'var(--color-plex-text-muted)' }}>{pct != null ? pct : '\u2014'}</span>
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
