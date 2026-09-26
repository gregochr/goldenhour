import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { STATE_WORD, DIRECTION_WORD } from '../../utils/windowFirstRows.js';
import { eventWord } from '../../utils/windowFirstCards.js';

/**
 * The Map tab's tide strip (`docs/engineering/tide-window-plan.md` T6,
 * `docs/design/tide-window/README.md` §2). Draws the day's tide curve with HIGH/MID/LOW ruled
 * across it, night shaded, sunrise and sunset marked, the light on the curve with its height, and a
 * footer naming what is dimmed on the map and when it comes good.
 *
 * <p><b>Renders {@code null} unless {@code model.visible}</b> (§5 #7: solar row ∧ served window
 * tide ∧ ≥ 1 coastal spot in the padded viewport) — not a mode, no toggle, the only control is
 * collapse. `MapView` mounts this unconditionally as the last child of `.wf-map-chrome-bl` so the
 * legend chip and the viewline-upsell chip clear it by flex order (§4 #6, §1 #8) rather than by
 * `--tsh`, which this component publishes for the one chip in a DIFFERENT column — the bottom-centre
 * counts footer.
 *
 * <p><b>Every string is server-formatted</b> (§5 #5) except the footer's own count sentence, which
 * is the client's licensed tally over served facts (§1 #12) — {@link footerModel} and
 * {@link nextFitCopy} below build ONLY that sentence and the jump's day/event words
 * ({@code dayLabel}/{@link eventWord}, both already served or already a pure vocabulary lookup);
 * every clock time, height and offset comes off {@code tide} verbatim.
 *
 * <p><b>The whole chart is `aria-hidden`.</b> The header's kicker/state/meta line, the footer
 * sentence and the collapsed row are the entire accessible answer — nothing the SVG draws, INCLUDING
 * the sunrise/sunset/extrema/height labels drawn as HTML overlays beside it, may be the only
 * statement of a fact (design §2's own rule) — so the hiding is on the chart's whole wrapping `div`,
 * not merely the `<svg>` inside it (adversarial review, accessibility lens).
 *
 * <p>Root carries {@code role="group" aria-label="Tide"} so a screen reader can jump to it as one
 * unit (the same landmark treatment {@code MapCallout}/{@code MapLegendPanel} give their own
 * floating panels) and {@code tabIndex={-1}}, used only to receive focus programmatically after the
 * next-fit jump — never part of the tab order on its own.
 *
 * <h2>The header/chart/footer split (map-mobile-sheet-plan.md §3 M3 task 1)</h2>
 *
 * <p>{@link TideStripHeader}, {@link TideDayChart} and {@link TideStripFooter} are exported so the
 * phone peek sheet's Tide section (`MapPeekTideSection.jsx`) can reuse the chart and the footer
 * without a second copy of either — the section builds its OWN header line (a different layout the
 * design spec itself gives different copy, `docs/design/map-mobile-sheet/README.md` "Section:
 * Tide"), but the chart and the footer are the identical served facts read the identical way, so
 * duplicating either would be a second place a served string could drift from this one. This
 * component still composes all three unchanged — a pinned {@code outerHTML} test in
 * `MapTideStrip.test.jsx` proves the split changed nothing about what the desktop strip renders.
 */

/** `TY(l) = (26 − l·20) / 32 · 100` — the design's own vertical mapping (§2's chart table), shared
 *  by every HTML overlay positioned on top of the SVG and by the SVG's own path/line coordinates
 *  (which use the un-scaled `yForLevel` below). Exported for the dot-on-curve measurement
 *  (§7 check 5). Percentage-based, so it is unaffected by {@link TideDayChart}'s `tall` viewBox —
 *  a taller box changes only the SVG's own internal unit scale, never the proportion any level maps
 *  to, which is what lets the percentage-positioned HTML overlays (extremes, sun labels, the dot)
 *  render correctly in EITHER projection with no branch of their own. */
export function TY(level) {
  return ((26 - level * 20) / 32) * 100;
}

/**
 * The raw SVG y-coordinate for a level, scaled to a viewBox of height `vbHeight` (not a percent) —
 * `TY(level)/100 * vbHeight`, algebraically identical to the strip's original `26 - level*20` when
 * `vbHeight` is 32 (multiplying by `32/32 === 1` changes no bit of the float, which is what keeps
 * the desktop strip's own pinned `outerHTML` byte-identical after this split). The Tide section's
 * `tall` chart (map-mobile-sheet-plan.md §3 M3 task 2) passes 92 instead — a different internal
 * scale for the SAME proportions, never a different formula.
 */
function yForAt(level, vbHeight) {
  return (26 - level * 20) * (vbHeight / 32);
}

function clampPct(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

/** Water level → the word this footer's "wants" clause states it in — the same vocabulary
 *  `windowFirstRows.js`'s tide row already reads (tide-window-plan.md §4 #8). */
const WANT_WORD = STATE_WORD;

/**
 * The footer's leading count clause, split into a bold count and its plain-text tail so the JSX can
 * bold only the figures — design §2's `<b>13 of 16</b> coastal spots are…` — without a second place
 * stating the sentence. Exported so the exact three copy cases (§7, T6 #8) can be asserted without
 * rendering.
 *
 * @param {{namedCoastal: Array, dimmed: Array, matched: Array, dominantWant: ?string,
 *   dominantWantCount: number}} model `mapTideFit.stripModel`'s result
 * @returns {{countText: ?string, restText: string}}
 */
export function footerModel({
  namedCoastal, dimmed, matched, dominantWant, dominantWantCount,
}) {
  if (dimmed.length > 0) {
    const want = WANT_WORD[dominantWant] ?? '';
    const clause = dominantWantCount === dimmed.length
      ? `they want ${want}`
      : `${dominantWantCount} of them want ${want}`;
    return {
      countText: `${dimmed.length} of ${namedCoastal.length}`,
      restText: ` coastal spots are dimmed — ${clause}`,
    };
  }
  if (matched.length > 0) {
    return {
      countText: `${matched.length} of ${namedCoastal.length}`,
      restText: ' coastal spots have the water they want',
    };
  }
  return { countText: null, restText: 'No coastal spot here has its water on this light' };
}

/**
 * The footer's right-aligned jump — the next window that gives the dominant want, or the honest
 * denial when none is in the served horizon (§4 #7, §7 check 6). {@code null} when there is nothing
 * dimmed to jump away from.
 *
 * @param {object} model `stripModel`'s result
 * @param {?{eventType: string}} activeRow the window on screen — its OWN kind decides the "beyond"
 *   sentence's sunrise/sunset word (the design prototype's own {@code e.am?'sunrise':'sunset'}), not
 *   the dimmed spots' want
 * @returns {?{kind: 'jump'|'beyond', text: string, row: ?object}}
 */
export function nextFitCopy(model, activeRow) {
  const { dimmed, dominantWant, nextFitRow } = model;
  if (dimmed.length === 0 || !dominantWant) return null;
  const want = WANT_WORD[dominantWant] ?? '';
  if (nextFitRow && nextFitRow !== -1) {
    return {
      kind: 'jump',
      text: `Next ${want} on the light · ${nextFitRow.dayLabel} ${eventWord(nextFitRow.eventType)} ›`,
      row: nextFitRow,
    };
  }
  return {
    kind: 'beyond',
    text: `Next ${want} on a ${eventWord(activeRow?.eventType)} is beyond these four days`,
  };
}

/** `mid tide, rising` / `high water, falling` — one phrase, because "mid" alone does not say
 *  whether to hurry (design §2's header row table). Exported: the phone Tide section's own phase
 *  line reads the SAME state/direction words through this function rather than a second composition
 *  of `STATE_WORD`/`DIRECTION_WORD` (map-mobile-sheet-plan.md §3 M3 task 3). */
export function statePhrase(tide) {
  const state = STATE_WORD[tide?.state];
  const direction = DIRECTION_WORD[tide?.direction];
  if (state && direction) return `${state}, ${direction}`;
  return state || direction || '';
}

/** `2.6 m · about average · measured at Bamburgh Castle` — the codebase's own anomaly wording
 *  (§4 #8), plus the attribution clause §4 #4 requires: the strip states one coastline's water, and
 *  must say whose. Desktop-header-only (the design gives the phone Tide section its own, shorter
 *  `{height} · {time}` clause instead — §3 M3 task 3 — so this stays private to
 *  {@link TideStripHeader}). */
function headerMeta(tide) {
  const heightPart = [tide?.heightAtWindow, tide?.rangeAnomaly].filter(Boolean).join(' · ');
  const measured = tide?.locationName ? `measured at ${tide.locationName}` : '';
  return [heightPart, measured].filter(Boolean).join(' · ');
}

/** The desktop kicker text, exported so `MapPeekTideSection`'s own key line states the identical
 *  words (map-mobile-sheet-plan.md §3 M3 task 3) rather than a second string literal. */
export const TIDE_KICKER_TEXT = 'Tide at this light';

const BAND_LABEL = { HIGH: 'HIGH', MID: 'MID', LOW: 'LOW' };
const BAND_LEVEL = { HIGH: 1, MID: 0.5, LOW: 0 };

/**
 * The strip's own header — kicker, state/direction phrase, the height/anomaly/attribution meta
 * clause, and the collapse toggle. Exported (map-mobile-sheet-plan.md §3 M3 task 1) though it has
 * only one caller today ({@link MapTideStrip} itself) — the phone Tide section builds its own
 * header layout with different copy (`docs/design/map-mobile-sheet/README.md` "Section: Tide"), so
 * this is split out for the same reason {@link TideDayChart}/{@link TideStripFooter} are: one
 * subtree, one owner, provable by the pinned `outerHTML` test.
 *
 * @param {object} props
 * @param {?object} props.tide `BriefingWindowTide`-shaped
 * @param {?Function} props.onToggleCollapse
 */
export function TideStripHeader({ tide, onToggleCollapse = undefined }) {
  return (
    <div className="wf-tide-strip-header" data-testid="wf-tide-strip-header">
      <span className="wf-tide-strip-kicker">{TIDE_KICKER_TEXT}</span>
      <span className="wf-tide-strip-state" data-testid="wf-tide-strip-state">
        {statePhrase(tide)}
      </span>
      <span className="wf-tide-strip-meta">{headerMeta(tide)}</span>
      <button
        type="button"
        className="wf-tide-strip-toggle"
        data-testid="wf-tide-strip-toggle"
        onClick={onToggleCollapse}
        aria-label="Collapse"
      >
        ▾
      </button>
    </div>
  );
}

TideStripHeader.propTypes = {
  tide: PropTypes.object,
  onToggleCollapse: PropTypes.func,
};

/**
 * The day's tide curve — bands, the `aria-hidden` chart (night shading, dashed HIGH/MID/LOW rules,
 * the served curve, the sunrise/sunset verticals and labels, the served extremes, the light dot and
 * its height) and the hour axis. Exactly the strip's own `.wf-tide-strip-body` subtree
 * (map-mobile-sheet-plan.md §3 M3 task 1), reused unchanged by the desktop/tablet strip (`tall`
 * omitted, so `vbHeight` is 32 — algebraically identical to the pre-split literal, §3 M3 task 2's
 * own doc on {@link yForAt}) and by the phone Tide section's taller projection (`tall`, `vbHeight`
 * 92) of the SAME served `tide.curve`/extremes — no cosine, no re-derived level, no new threshold
 * (§4 #6: "the curve, bands, night shading and marker are the strip's").
 *
 * @param {object} props
 * @param {?object} props.tide `BriefingWindowTide`-shaped
 * @param {?string} props.sunriseTime the served clock time for the sibling sunrise row
 * @param {?string} props.sunsetTime the served clock time for the sibling sunset row
 * @param {boolean} [props.tall=false] the Tide section's taller viewBox (§3 M3 task 2)
 */
export function TideDayChart({
  tide, sunriseTime = null, sunsetTime = null, tall = false,
}) {
  const vbHeight = tall ? 92 : 32;
  const yForLevel = (level) => yForAt(level, vbHeight);

  const curve = Array.isArray(tide?.curve) ? tide.curve : [];
  const n = curve.length;
  const path = n >= 2
    ? curve.map((v, i) => `${i ? 'L' : 'M'}${((i / (n - 1)) * 1000).toFixed(1)} ${yForLevel(v).toFixed(2)}`).join(' ')
    : '';
  const areaPath = path ? `${path} L1000 ${vbHeight} L0 ${vbHeight} Z` : '';

  const hasNight = tide?.sunrisePosition != null && tide?.sunsetPosition != null;
  const dotPlaceable = Number.isFinite(tide?.windowPosition) && Number.isFinite(tide?.windowLevel);

  return (
    <div className={`wf-tide-strip-body${tall ? ' wf-tide-strip-body-tall' : ''}`}>
      <div className="wf-tide-strip-bands" aria-hidden="true">
        {(['HIGH', 'MID', 'LOW']).map((band) => (
          <span
            key={band}
            className="wf-tide-strip-band"
            data-hit={tide?.state === band ? 'true' : 'false'}
            style={{ top: `${TY(BAND_LEVEL[band])}%` }}
          >
            {BAND_LABEL[band]}
          </span>
        ))}
      </div>
      {/* `aria-hidden` on the WHOLE overlay, not only the `<svg>` — the sunrise/sunset, extrema
          and light-height labels below are real HTML text siblings of the svg, and "the whole
          chart is aria-hidden" (design §2, this file's own header doc) means them too. Left off
          the svg alone, a screen reader landing on this block by linear/browse-mode navigation
          would read disconnected fragments ("↑ 05:44", "HW 08:47", "2.6 m") with none of the
          sentence structure the header/footer already state them in (adversarial review). */}
      <div className="wf-tide-strip-chart" aria-hidden="true" data-testid="wf-tide-strip-chart">
        <svg
          viewBox={`0 0 1000 ${vbHeight}`} preserveAspectRatio="none" aria-hidden="true"
          data-testid="wf-tide-strip-svg"
        >
          {hasNight && (
            <>
              <rect
                x="0" y="0" width={(tide.sunrisePosition * 1000).toFixed(1)} height={vbHeight}
                fill="rgba(0,0,0,.32)"
              />
              <rect
                x={(tide.sunsetPosition * 1000).toFixed(1)} y="0"
                width={((1 - tide.sunsetPosition) * 1000).toFixed(1)} height={vbHeight}
                fill="rgba(0,0,0,.32)"
              />
            </>
          )}
          {areaPath && <path d={areaPath} fill="rgba(111,168,176,.17)" />}
          {path && (
            <path
              d={path} fill="none" stroke="var(--color-tide)" strokeWidth="1.5"
              vectorEffect="non-scaling-stroke"
            />
          )}
          {[1, 0.5, 0].map((l) => (
            <line
              key={l} x1="0" x2="1000" y1={yForLevel(l)} y2={yForLevel(l)}
              stroke="rgba(242,231,211,.17)" strokeWidth="1" strokeDasharray="4 3"
              vectorEffect="non-scaling-stroke"
            />
          ))}
          {hasNight && (
            <>
              <line
                x1={(tide.sunrisePosition * 1000).toFixed(1)} x2={(tide.sunrisePosition * 1000).toFixed(1)}
                y1="0" y2={vbHeight} stroke="var(--color-verdict-marginal)" strokeOpacity="0.5"
                vectorEffect="non-scaling-stroke"
              />
              <line
                x1={(tide.sunsetPosition * 1000).toFixed(1)} x2={(tide.sunsetPosition * 1000).toFixed(1)}
                y1="0" y2={vbHeight} stroke="var(--color-verdict-marginal)" strokeOpacity="0.5"
                vectorEffect="non-scaling-stroke"
              />
            </>
          )}
        </svg>
        {hasNight && sunriseTime && (
          <i
            className="wf-tide-strip-sun-label"
            data-edge="left"
            style={{ left: `${tide.sunrisePosition * 100}%` }}
          >
            ↑ {sunriseTime}
          </i>
        )}
        {hasNight && sunsetTime && (
          <i
            className="wf-tide-strip-sun-label"
            data-edge="right"
            style={{ left: `${tide.sunsetPosition * 100}%` }}
          >
            ↓ {sunsetTime}
          </i>
        )}
        {(tide?.extremes ?? []).map((ex) => (
          <span
            key={`${ex.kind}-${ex.time}`}
            className="wf-tide-strip-extreme"
            style={{ left: `${clampPct(ex.position * 100, 5, 95)}%` }}
          >
            {ex.kind} <b>{ex.time}</b>
          </span>
        ))}
        {dotPlaceable && (
          <>
            <span
              className="wf-tide-strip-dot"
              data-testid="wf-tide-strip-dot"
              style={{ left: `${tide.windowPosition * 100}%`, top: `${TY(tide.windowLevel)}%` }}
            />
            {tide.heightAtWindow && (
              <span
                className="wf-tide-strip-dot-label"
                style={{
                  left: `${clampPct(tide.windowPosition * 100, 9, 91)}%`,
                  top: `${TY(tide.windowLevel)}%`,
                }}
              >
                {tide.heightAtWindow}
              </span>
            )}
          </>
        )}
      </div>
      <div className="wf-tide-strip-axis" aria-hidden="true">
        <span>00</span><span>06</span><span>12</span><span>18</span><span>24</span>
      </div>
    </div>
  );
}

TideDayChart.propTypes = {
  tide: PropTypes.object,
  sunriseTime: PropTypes.string,
  sunsetTime: PropTypes.string,
  tall: PropTypes.bool,
};

/**
 * The footer — the leading count/want sentence ({@link footerModel}) and the right-aligned next-fit
 * jump or denial ({@link nextFitCopy}). Exported (map-mobile-sheet-plan.md §3 M3 task 1) so the
 * phone Tide section reuses the identical sentence and the identical jump behaviour, rather than a
 * second render of the same two pure functions with its own focus-after-jump wiring.
 *
 * <p>{@code focusAfterJump} generalises the strip's own "focus a stable target so the jump never
 * drops focus to `<body>`" rule (see the class doc) to a CALLER-OWNED target: the strip focuses its
 * own root ({@link MapTideStrip}'s `focusStrip`), while the Tide section focuses the sheet's Tide
 * peek button instead, since the section has no root of its own worth returning to
 * (`MapPeekTideSection.jsx`, §3 M3 task 3's own "stable focus target" requirement).
 *
 * <p>⚠️ <b>Called BEFORE {@code onSelectEv}, not after</b> (map-mobile-sheet-plan.md §3 M3 task 3 —
 * a deliberate reordering from this function's own pre-split shape, which called `onSelectEv` first
 * for the desktop strip's `focusStrip`; that STILL leaves the same element focused once React
 * flushes, since both calls run inside one synchronous handler either way, but parking focus on the
 * stable target first, before the very state change that can unmount the button being clicked, is
 * the safer order to keep should a future caller's `onSelectEv` ever do anything synchronous of its
 * own). The name keeps the word "after" because it still answers "what happens after the jump
 * WOULD leave focus stranded", not the literal call order.
 *
 * @param {object} props
 * @param {object} props.model `mapTideFit.stripModel`'s result
 * @param {?object} props.activeRow the window on screen
 * @param {?Function} props.onSelectEv `(row) => void`
 * @param {?Function} props.focusAfterJump called with no arguments right BEFORE `onSelectEv`
 */
export function TideStripFooter({
  model, activeRow = null, onSelectEv = undefined, focusAfterJump = undefined,
}) {
  const footer = footerModel(model);
  const nextFit = nextFitCopy(model, activeRow);
  return (
    <div className="wf-tide-strip-footer" data-testid="wf-tide-strip-footer">
      <span>
        {footer.countText != null && <b>{footer.countText}</b>}
        {footer.restText}
      </span>
      {nextFit && nextFit.kind === 'jump' && (
        <button
          type="button"
          className="wf-tide-strip-next"
          data-testid="wf-tide-strip-next"
          onClick={() => {
            // Focus the stable target FIRST — the jump commonly resolves the very fact this
            // button existed for, so it unmounts on the next render, and parking focus before
            // triggering that change is the safer order (adversarial review, accessibility lens;
            // reordered at §3 M3 — see this function's own doc for why).
            focusAfterJump?.();
            onSelectEv?.(nextFit.row);
          }}
        >
          {nextFit.text}
        </button>
      )}
      {nextFit && nextFit.kind === 'beyond' && (
        <span className="wf-tide-strip-beyond" data-testid="wf-tide-strip-beyond">
          {nextFit.text}
        </span>
      )}
    </div>
  );
}

TideStripFooter.propTypes = {
  model: PropTypes.shape({
    namedCoastal: PropTypes.array,
    dimmed: PropTypes.array,
    matched: PropTypes.array,
    dominantWant: PropTypes.string,
    dominantWantCount: PropTypes.number,
    nextFitRow: PropTypes.oneOfType([PropTypes.object, PropTypes.number]),
  }).isRequired,
  activeRow: PropTypes.object,
  onSelectEv: PropTypes.func,
  focusAfterJump: PropTypes.func,
};

export default function MapTideStrip({
  model = null, tide = null, activeRow = null, sunriseTime = null, sunsetTime = null,
  collapsed = false, onToggleCollapse = undefined, onSelectEv = undefined, mapPaneRef = null,
  onHeightChange = undefined,
}) {
  const [node, setNode] = useState(null);

  /**
   * Moves focus to the strip's own root after the next-fit jump (adversarial review, accessibility
   * lens) — the strip's own {@code focusAfterJump} target, handed to {@link TideStripFooter}. See
   * that component's own doc for why the target is now a caller-owned callback rather than a fixed
   * behaviour.
   */
  const focusStrip = () => node?.focus();

  /**
   * Publishes `--tsh: <offsetHeight>px` on the `.wf-map-tab` root and toggles `wf-tide-strip-on`
   * (T6 #6) — the same callback-ref + synchronous-measure-then-`ResizeObserver` shape
   * `WindowFirstHeatStrip`'s `--wf-dh-h` already uses, so the counts footer clears the strip's REAL
   * height rather than a guessed one: open and collapsed are roughly 3× apart (design §2).
   *
   * <p>Removes both on unmount OR the moment the strip stops being visible, since a hidden strip
   * (`return null` below) unmounts this effect's own node — the cleanup path is the same one either
   * way, which is exactly what keeps "the chrome returns to `bottom: 8px`" (§7 check 3) true without
   * a second code path.
   *
   * <p>⚠️ Also reports the same number up to `onHeightChange` (T7 follow-up, Codex P1 on the T7
   * PR) — `MapCallout`'s own placement band treats this strip as a floor/ceiling bar
   * (`BAND_BAR_SELECTOR`, T7), but that component's `paint()` only ever RUNS on a fixed list of
   * triggers, none of which fired when the strip toggled open ⇄ collapsed. `MapView` is the one
   * place both components already meet, so it is the one place a single state value can retrigger
   * a callout repaint without either `MapTideStrip` or `MapCallout` importing the other. This does
   * NOT hand the number a second job: `MapCallout`'s own `paint()` still re-measures the strip's
   * real rect fresh off the DOM every time it runs, exactly like every other bar — `onHeightChange`
   * exists purely so ITS IDENTITY changing is what makes `paint()` run again in the first place.
   * `null` on cleanup (unmount OR the strip going invisible) for the identical reason the CSS
   * property is removed on the same path: a stale height is exactly as wrong as no height at all.
   */
  useEffect(() => {
    const pane = mapPaneRef?.current;
    if (!node || !pane || typeof ResizeObserver === 'undefined') return undefined;
    const write = () => {
      const h = Math.round(node.offsetHeight);
      pane.style.setProperty('--tsh', `${h}px`);
      onHeightChange?.(h);
    };
    pane.classList.add('wf-tide-strip-on');
    write();
    const observer = new ResizeObserver(write);
    observer.observe(node);
    return () => {
      observer.disconnect();
      pane.classList.remove('wf-tide-strip-on');
      pane.style.removeProperty('--tsh');
      onHeightChange?.(null);
    };
  }, [node, mapPaneRef, onHeightChange]);

  if (!model?.visible || !tide) return null;

  if (collapsed) {
    const phrase = statePhrase(tide);
    const dimmedClause = model.dimmed.length > 0 ? ` · ${model.dimmed.length} dimmed` : '';
    return (
      <div
        className="wf-map-tide-strip" ref={setNode} tabIndex={-1}
        role="group" aria-label="Tide" data-testid="wf-tide-strip"
      >
        <div className="wf-tide-strip-collapsed" data-testid="wf-tide-strip-collapsed">
          <span className="wf-tide-strip-kicker">Tide</span>
          <span className="wf-tide-strip-state" data-testid="wf-tide-strip-state">{phrase}</span>
          <span className="wf-tide-strip-meta">
            {[tide.heightAtWindow, activeRow?.time && `at ${activeRow.time}`].filter(Boolean).join(' ')}
            {dimmedClause}
          </span>
          <button
            type="button"
            className="wf-tide-strip-toggle"
            data-testid="wf-tide-strip-toggle"
            onClick={onToggleCollapse}
          >
            {/* The design's `Open ▴` — visible text plus a decorative caret, the caret alone
                carrying no meaning a screen reader should announce (`TideWave.jsx`'s own house
                rule: "a bare glyph announces as nothing at all"; `MapCallout.jsx`'s identical
                ▾/▴ toggle wraps it the same way). */}
            Open <span aria-hidden="true">▴</span>
          </button>
        </div>
      </div>
    );
  }

  return (
    <div
      className="wf-map-tide-strip" ref={setNode} tabIndex={-1}
      role="group" aria-label="Tide" data-testid="wf-tide-strip"
    >
      <TideStripHeader tide={tide} onToggleCollapse={onToggleCollapse} />
      <TideDayChart tide={tide} sunriseTime={sunriseTime} sunsetTime={sunsetTime} />
      <TideStripFooter
        model={model} activeRow={activeRow} onSelectEv={onSelectEv} focusAfterJump={focusStrip}
      />
    </div>
  );
}

MapTideStrip.propTypes = {
  model: PropTypes.shape({
    visible: PropTypes.bool,
    representative: PropTypes.string,
    namedCoastal: PropTypes.array,
    dimmed: PropTypes.array,
    matched: PropTypes.array,
    dominantWant: PropTypes.string,
    dominantWantCount: PropTypes.number,
    nextFitRow: PropTypes.oneOfType([PropTypes.object, PropTypes.number]),
  }),
  tide: PropTypes.object,
  activeRow: PropTypes.object,
  sunriseTime: PropTypes.string,
  sunsetTime: PropTypes.string,
  collapsed: PropTypes.bool,
  onToggleCollapse: PropTypes.func,
  onSelectEv: PropTypes.func,
  mapPaneRef: PropTypes.object,
  onHeightChange: PropTypes.func,
};
