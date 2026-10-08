import { useEffect, useLayoutEffect, useRef } from 'react';
import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { VERDICT_LABEL } from '../../utils/windowFirstCards.js';
import { isNightRow } from '../../utils/mapPeek.js';
import { meridiemOf } from '../../utils/askModel.js';
import { KindChip } from './WindowControl.jsx';

/**
 * The Map tab's phone peek sheet — map-mobile-sheet-plan.md §3 M2,
 * `docs/design/map-mobile-sheet/README.md` "Peek sheet".
 *
 * <p>Replaces the phone's floating map controls (the window pill's own dropdown, the tide strip,
 * `.wf-map-chrome-tr`'s Regions/Heat-Pins/Filters bar) with one bottom sheet that starts collapsed
 * (three summary buttons: 74px, or 126px with Ask's row above them) and opens to one section at a
 * time (356px, or 408px with the Ask row; Ask's own section is 470px and, minimised, 112px — see
 * `utils/askPeek.js` for every figure and `MapPeekAsk` for the row).
 *
 * <h2>Why this is a NEW component rather than {@code BottomSheet}</h2>
 *
 * <p>{@code components/BottomSheet.jsx} has no detents (it returns {@code null} when closed, so it
 * cannot show a collapsed state), portals to {@code document.body} behind a backdrop that would
 * block the map, and is {@code role="dialog"}/{@code aria-modal} by default — every map Escape rule
 * (`utils/mapForeignModal.js`) would read it as a foreign dialog and stand down for it. The peek
 * sheet is the opposite on all counts: always mounted, in-frame, backdrop-free (the map must stay
 * touchable while it is open), and not a dialog at all (plan §1 #2).
 *
 * <h2>The sheet owns no data</h2>
 *
 * <p>{@code section} is the single caller-owned value of `MapView`'s own {@code openMapMenu}
 * (stripped of its {@code 'peek:'} prefix) — this component has no state of its own beyond the
 * collapse/expand CSS transition. The peek row's button VALUES and the body content for each
 * section are handed in as props/children; this file only lays them out and wires the toggle
 * button presses back to the caller (map-mobile-sheet-plan.md §5 D-1).
 *
 * <h2>The Tide button (map-mobile-sheet-plan.md §3 M3 task 4)</h2>
 *
 * <p>Withheld entirely in M2 ("a released control must never open an empty panel") and mounted
 * here from M3 on, gated on {@code tideVisible} — through M3 that gate is
 * {@code stripModel.visible} (M5 replaces it with the fuller {@code tideVisible(...)} rule without
 * touching this component at all). Its key line NEVER swaps to `CLOSE` the way the Windows
 * button's does — the design's own peek-row table gives Tide one fixed key,
 * {@code TIDE AT THIS LIGHT}, unlike Other windows' `OTHER WINDOWS ⇄ CLOSE` pair.
 *
 * <p>⚠️ {@code data-testid="wf-map-peek"}, deliberately NOT {@code wf-peek}: `WindowSpotPeek.jsx`
 * already owns that class in `index.css` (`position: fixed`, a 280px width, an entry animation and
 * an {@code ::after} arrow), which a same-named sheet would silently inherit (a Codex finding on
 * the plan's M0 — every hook this component and its CSS use is `wf-map-peek-*`).
 *
 * @param {object} props
 * @param {?('win'|'tide'|'lay')} props.section the open section, or null when collapsed.
 * @param {Function} props.onPressWindows called when the "Other windows" button is pressed.
 * @param {Function} props.onPressLayers called when the "Layers" button is pressed.
 * @param {?Function} [props.onPressTide] called when the Tide button is pressed; the button is not
 *        rendered at all when this is null (M2's "a released control must never open an empty
 *        panel" rule still applies to every future gate, not only M2's own withholding of it).
 * @param {boolean} [props.tideVisible=false] gates the Tide button's presence, independent of
 *        `onPressTide` being handed in at all — M3 passed `stripModel.visible`; M5 replaces the
 *        gate's SOURCE with the fuller `tideVisible(...)` rule (`utils/mapPeek.js`), never this
 *        prop's shape (map-mobile-sheet-plan.md §3 M3 task 4, §3 M5 task 2).
 * @param {boolean} [props.tidePulse=false] plays the Tide button's one-shot pulse (§3 M5 task 3) —
 *        a false → true transition of `tideVisible` after the first bounds-backed evaluation, never
 *        on mount. Cleared by `onTidePulseEnd` on the animation's own `animationend`.
 * @param {?Function} [props.onTidePulseEnd] called on the pulse animation's `animationend`, so the
 *        class can be removed once it has played (never removed by a timer, which could race a
 *        SECOND transition arriving mid-animation).
 * @param {React.ReactNode} [props.otherWindowContent] the "Other windows" button's value line.
 * @param {React.ReactNode} [props.tideButtonContent] the Tide button's value line
 *        (`utils/mapPeek.js#tideSummary`, after the `TideWave` glyph).
 * @param {React.ReactNode} [props.windowsBody] the Windows section's body, rendered while
 *        {@code section === 'win'}.
 * @param {React.ReactNode} [props.tideBody] the Tide section's body (`MapPeekTideSection`),
 *        rendered while {@code section === 'tide'}.
 * @param {React.ReactNode} [props.layersBody] the Layers section's body, rendered while
 *        {@code section === 'lay'}.
 * @param {?object} [props.windowsButtonRef] the "Other windows" button's own ref — the rescue
 *        target when the Tide button disappears while its section is open and held focus (§3 M3
 *        task 4's close-and-rescue rule).
 * @param {?object} [props.tideButtonRef] the Tide button's own ref — `MapPeekTideSection`'s own
 *        next-fit jump reads it as the stable focus target a disappearing link hands focus to.
 * @param {?Function} [props.onTideButtonFocus] called on the Tide button's own `focus` event —
 *        `MapView` uses this (not a `document.activeElement` comparison) to track whether the
 *        button holds focus at the moment its own gate might unmount it (§3 M3 task 4's
 *        close-and-rescue rule, fixed at M5 — see `MapView`'s `tideButtonHadFocusRef` for why).
 * @param {?Function} [props.onTideButtonBlur] called on the Tide button's own `blur` event —
 *        the counterpart to `onTideButtonFocus`.
 * @param {('off'|'collapsed'|'expanded'|'minimised'|'section')} [props.askMode='off'] the sheet's Ask
 *        state (`utils/askPeek.js#askPeekMode`, derived by `MapView`: this component still holds none).
 *        `'off'` is the sheet before Ask existed (its transition is the README's .28s now, not .26s). Otherwise {@code ask} is drawn above
 *        the three buttons, and while the Ask section is {@code 'expanded'} or the answer is
 *        {@code 'minimised'} the answer REPLACES the buttons (design README: "answer replaces the
 *        Window/Tide/Layers buttons") — they come back when the answer is cleared or another section
 *        is opened. It is `data-ask` on the root, which is what the stylesheet's heights key on.
 * @param {React.ReactNode} [props.ask] `MapPeekAsk` — the entry row, the Ask section's body and the
 *        minimised line, in one node, since the three share focus handling.
 * @param {?{current: ?HTMLElement}} [props.fallbackFocusRef] where focus goes when the button row is
 *        removed while one of its buttons holds it (the Ask row's entry button) — see `PeekButtonRow`.
 */
export default function MapPeekSheet({
  section, onPressWindows, onPressLayers, onPressTide = null, tideVisible = false,
  tidePulse = false, onTidePulseEnd = null,
  otherWindowContent = null, tideButtonContent = null,
  windowsBody = null, tideBody = null, layersBody = null,
  layersButtonRef = null, windowsButtonRef = null, tideButtonRef = null,
  onTideButtonFocus = null, onTideButtonBlur = null,
  askMode = 'off', ask = null, fallbackFocusRef = null,
}) {
  const open = section != null;
  // Set by the button row when it unmounts WHILE holding focus (see `PeekButtonRow`); acted on once the
  // commit that removed it has landed.
  const focusLostRef = useRef(false);
  useEffect(() => {
    if (!focusLostRef.current) return;
    focusLostRef.current = false;
    const active = document.activeElement;
    if (!active || active === document.body) fallbackFocusRef?.current?.focus({ preventScroll: true });
  });
  // The answer takes the buttons' place (expanded) or the line's room (minimised); with Ask off
  // there is no answer, so the buttons are always drawn.
  const buttonsShown = askMode !== 'expanded' && askMode !== 'minimised';
  return (
    <section
      data-testid="wf-map-peek"
      aria-label="Map panels"
      className={`wf-map-peek${open ? ' wf-map-peek-open' : ''}`}
      data-ask={askMode}
    >
      <div className="wf-map-peek-hdl" aria-hidden="true"><i /></div>
      {askMode !== 'off' && ask}
      {buttonsShown && (
        <PeekButtonRow onUnmountWithFocus={() => { focusLostRef.current = true; }}>
          <button
            ref={windowsButtonRef}
            type="button"
            data-testid="wf-map-peek-btn-win"
            className={`wf-map-peek-btn${section === 'win' ? ' wf-map-peek-btn-on' : ''}`}
            aria-expanded={section === 'win'}
            aria-controls="wf-map-peek-body"
            onClick={onPressWindows}
          >
            {/* Tapping the ACTIVE button collapses; the design's own "OTHER WINDOWS" ⇄ "CLOSE"
                swap (README "Peek row" table) is the reader's only cue that a second tap closes it,
                since neither button ever moves or disappears. */}
            <span className="wf-map-peek-k">{section === 'win' ? 'CLOSE' : 'OTHER WINDOWS'}</span>
            <span className="wf-map-peek-v">{otherWindowContent}</span>
          </button>
          {tideVisible && onPressTide && (
            <button
              ref={tideButtonRef}
              type="button"
              data-testid="wf-map-peek-btn-tide"
              className={`wf-map-peek-btn wf-map-peek-btn-tide${section === 'tide' ? ' wf-map-peek-btn-on' : ''}${tidePulse ? ' wf-map-peek-btn-pulse' : ''}`}
              aria-expanded={section === 'tide'}
              aria-controls="wf-map-peek-body"
              onClick={onPressTide}
              onAnimationEnd={onTidePulseEnd}
              onFocus={onTideButtonFocus}
              onBlur={onTideButtonBlur}
            >
              {/* Fixed key — unlike Other windows, Tide never swaps to CLOSE (design README "Peek
                  row" table gives it one key throughout). */}
              <span className="wf-map-peek-k">TIDE AT THIS LIGHT</span>
              <span className="wf-map-peek-v">{tideButtonContent}</span>
            </button>
          )}
          <button
            ref={layersButtonRef}
            type="button"
            data-testid="wf-map-peek-btn-lay"
            className={`wf-map-peek-btn wf-map-peek-btn-lay${section === 'lay' ? ' wf-map-peek-btn-on' : ''}`}
            aria-expanded={section === 'lay'}
            aria-controls="wf-map-peek-body"
            onClick={onPressLayers}
          >
            <span className="wf-map-peek-k">LAYERS</span>
            <span className="wf-map-peek-v" aria-hidden="true">&#9776;</span>
          </button>
        </PeekButtonRow>
      )}
      {/* Rendered only when open — the design's own rule ("Body (visible only when open)"), and
          what makes a section swap tear the PREVIOUS section's content down rather than leaving two
          panes stacked in the DOM. */}
      {open && (
        <div id="wf-map-peek-body" data-testid="wf-map-peek-body" className="wf-map-peek-body">
          {section === 'win' && windowsBody}
          {section === 'tide' && tideBody}
          {section === 'lay' && layersBody}
        </div>
      )}
    </section>
  );
}

/**
 * The three peek buttons' row, which is UNMOUNTED whenever Ask's answer replaces it (an answer lands,
 * the Ask section opens, a section closes back onto the minimised line). A focused control that is
 * removed takes focus to {@code <body>} with it, after which the pane's Escape rule — which runs only
 * while focus is inside the pane — goes quiet: the defect this tab's panels have had five times. So the
 * row reports, from its layout-effect CLEANUP (which runs before the DOM is removed, while the focused
 * button is still {@code document.activeElement}; no blur event is reliable for a removed node), that
 * it held focus, and {@code MapPeekSheet} hands focus to {@code fallbackFocusRef} — the Ask row's entry
 * button, which exists in every mode the row can vanish into — once the commit has landed.
 *
 * <p>The row's own class is the test for "was focus inside me", so it needs no ref (a ref is already
 * detached by the time a cleanup could read it) and no listener.
 */
function PeekButtonRow({ onUnmountWithFocus, children }) {
  const report = useRef(onUnmountWithFocus);
  useEffect(() => { report.current = onUnmountWithFocus; });
  useLayoutEffect(() => () => {
    if (document.activeElement?.closest?.('.wf-map-peek-row')) report.current?.();
  }, []);
  return <div className="wf-map-peek-row">{children}</div>;
}

PeekButtonRow.propTypes = {
  onUnmountWithFocus: PropTypes.func.isRequired,
  children: PropTypes.node,
};

MapPeekSheet.propTypes = {
  section: PropTypes.oneOf(['win', 'tide', 'lay']),
  onPressWindows: PropTypes.func.isRequired,
  onPressLayers: PropTypes.func.isRequired,
  onPressTide: PropTypes.func,
  tideVisible: PropTypes.bool,
  tidePulse: PropTypes.bool,
  onTidePulseEnd: PropTypes.func,
  otherWindowContent: PropTypes.node,
  tideButtonContent: PropTypes.node,
  windowsBody: PropTypes.node,
  tideBody: PropTypes.node,
  layersBody: PropTypes.node,
  /** Attached to the Layers button — the phone Regions/Filters `BottomSheet` hosts' own restore
   * target, since both unmount their trigger the instant they open (map-mobile-sheet-plan.md
   * §3 M2 task 6). */
  layersButtonRef: PropTypes.object,
  /** The "Other windows" button's own ref — the Tide close-and-rescue's fallback target (§3 M3
   * task 4). */
  windowsButtonRef: PropTypes.object,
  /** The Tide button's own ref — handed to `MapPeekTideSection` as its next-fit jump's stable
   * focus target (§3 M3 task 3). */
  tideButtonRef: PropTypes.object,
  /** Tracks whether the Tide button holds focus, via real DOM `focus`/`blur` events (§3 M3 task 4,
   * fixed at M5) — never removed via a `document.activeElement` comparison after the fact. */
  onTideButtonFocus: PropTypes.func,
  onTideButtonBlur: PropTypes.func,
  askMode: PropTypes.oneOf(['off', 'collapsed', 'expanded', 'minimised', 'section']),
  ask: PropTypes.node,
  /** Where focus goes when the buttons unmount while holding it — the Ask row's entry button. */
  fallbackFocusRef: PropTypes.shape({ current: PropTypes.any }),
};

/**
 * The peek row's "Other windows" value — `{dayLabel} {AM|PM}` plus the verdict word for a solar
 * row, or `{dayLabel}` plus the licensed `bestOfNight` figure for a night row (plan §4 #3 — never a
 * synthesised verdict word for a kind that carries no per-region rollup).
 *
 * <p>The day text truncates (`min-width: 0`, CSS); the verdict/star clause never does
 * (`flex: none`, CSS) — README Verify 5, "the verdict never cuts".
 *
 * @param {object} props
 * @param {?object} props.row from {@link module:utils/mapPeek.otherWindow}, or null
 * @param {?{tier: string}} props.verdict the row's own verdict, solar rows only
 */
export function OtherWindowValue({ row, verdict = null }) {
  if (!row) return <span className="wf-map-peek-v-empty" data-testid="wf-map-peek-other-empty">&mdash;</span>;
  const night = isNightRow(row);
  return (
    <>
      <span className="wf-map-peek-v-day">
        {row.dayLabel ?? row.label}
        {!night && ` ${meridiemOf(row.eventType)}`}
      </span>
      {night ? (
        row.scored && row.bestRating != null ? (
          <b className="wf-map-peek-v-verdict" data-testid="wf-map-peek-other-best">
            {row.bestRating}&#9733; best
          </b>
        ) : null
      ) : (
        verdict && (
          <b className="wf-map-peek-v-verdict" data-tier={verdict.tier} data-testid="wf-map-peek-other-verdict">
            {VERDICT_LABEL[verdict.tier] || VERDICT_LABEL.AWAITING}
          </b>
        )
      )}
    </>
  );
}

OtherWindowValue.propTypes = {
  row: PropTypes.shape({
    label: PropTypes.string,
    dayLabel: PropTypes.string,
    eventType: PropTypes.string,
    kind: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
  }),
  verdict: PropTypes.shape({ tier: PropTypes.string }),
};

/**
 * The Windows section's body — heading from {@code utils/mapLanding.js#landingCardModel}'s own
 * served-derived header (plan §4 #2, never the design's fixed "Tonight, or tomorrow?" string,
 * which is wrong two mornings a week), the pill's own roster, and the drilldown's only phone entry
 * as the last row (plan §1 #13, §4 #4).
 *
 * @param {object} props
 * @param {string} props.heading `landingCardModel(...).header`
 * @param {Array<object>} props.rows the EV list (`utils/mapEvents.js`'s rows)
 * @param {?Map<string, {tier: string}>} props.verdicts row id → verdict, solar rows only
 * @param {?string} props.activeId the row id on screen, or null
 * @param {Function} props.onSelect `(row) => void` — picks a row; the sheet stays open (rule 2)
 * @param {?Function} props.onOpenDrilldown opens `MapWindowPanel`; null withholds the row entirely
 *        (the same "no window to drill into" gate `WindowControl`'s own row observes)
 */
export function MapPeekWindowsSection({
  heading, rows, verdicts = null, activeId = null, onSelect, onOpenDrilldown = null,
}) {
  return (
    <div data-testid="wf-map-peek-windows" className="wf-map-peek-pane">
      <h3 className="wf-map-peek-h3">{heading}</h3>
      <div role="listbox" aria-label="Choose an event" className="wf-map-peek-wlist">
        {rows.map((row) => {
          const night = isNightRow(row);
          const verdict = night ? null : (verdicts?.get(row.id) ?? null);
          return (
            <button
              key={row.id}
              type="button"
              role="option"
              aria-selected={row.id === activeId}
              data-testid="wf-map-peek-win-row"
              data-ev-id={row.id}
              className={`wf-map-peek-wr${row.id === activeId ? ' on' : ''}`}
              onClick={() => onSelect(row)}
            >
              <KindChip row={row} />
              <span className="wf-map-peek-wr-day">{row.dayLabel ?? row.label}</span>
              <span className="wf-map-peek-wr-time">{row.time}</span>
              {night ? (
                row.scored && row.bestRating != null ? (
                  <b className="wf-map-peek-wr-verdict" data-testid="wf-map-peek-wr-best-of-night">
                    <i aria-hidden="true" style={{ background: rampHex(row.bestRating) }} />
                    {row.bestRating}&#9733; best
                  </b>
                ) : (
                  <span className="wf-map-peek-wr-verdict wf-map-peek-wr-unscored">&mdash;</span>
                )
              ) : (
                verdict ? (
                  <b className="wf-map-peek-wr-verdict" data-tier={verdict.tier}>
                    {VERDICT_LABEL[verdict.tier] || VERDICT_LABEL.AWAITING}
                  </b>
                ) : (
                  <span className="wf-map-peek-wr-verdict wf-map-peek-wr-unscored">&mdash;</span>
                )
              )}
            </button>
          );
        })}
      </div>
      {onOpenDrilldown && (
        <button
          type="button"
          data-testid="wf-map-peek-drilldown"
          className="wf-map-peek-wr wf-map-peek-drilldown"
          aria-haspopup="dialog"
          onClick={onOpenDrilldown}
        >
          <span aria-hidden="true">&#9636; </span>
          This window, region by region
        </button>
      )}
    </div>
  );
}

MapPeekWindowsSection.propTypes = {
  heading: PropTypes.string.isRequired,
  rows: PropTypes.arrayOf(PropTypes.shape({
    id: PropTypes.string.isRequired,
    kind: PropTypes.string,
    label: PropTypes.string,
    dayLabel: PropTypes.string,
    time: PropTypes.string,
    bestRating: PropTypes.number,
    scored: PropTypes.bool,
  })).isRequired,
  verdicts: PropTypes.instanceOf(Map),
  activeId: PropTypes.string,
  onSelect: PropTypes.func.isRequired,
  onOpenDrilldown: PropTypes.func,
};
