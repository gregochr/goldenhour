import PropTypes from 'prop-types';
import { rampHex } from '../../utils/scoreRamp.js';
import { VERDICT_LABEL } from '../../utils/windowFirstCards.js';
import { isNightRow } from '../../utils/mapPeek.js';
import { KindChip } from './WindowControl.jsx';

/**
 * The Map tab's phone peek sheet — map-mobile-sheet-plan.md §3 M2,
 * `docs/design/map-mobile-sheet/README.md` "Peek sheet".
 *
 * <p>Replaces the phone's floating map controls (the window pill's own dropdown, the tide strip,
 * `.wf-map-chrome-tr`'s Regions/Heat-Pins/Filters bar) with one bottom sheet that starts collapsed
 * (74px, three summary buttons — Tide arrives in M3) and opens to one section at a time.
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
 * collapse/expand CSS transition. The peek row's two button VALUES and the body content for each
 * section are handed in as props/children; this file only lays them out and wires the toggle
 * button presses back to the caller (map-mobile-sheet-plan.md §5 D-1).
 *
 * <p>⚠️ {@code data-testid="wf-map-peek"}, deliberately NOT {@code wf-peek}: `WindowSpotPeek.jsx`
 * already owns that class in `index.css` (`position: fixed`, a 280px width, an entry animation and
 * an {@code ::after} arrow), which a same-named sheet would silently inherit (a Codex finding on
 * the plan's M0 — every hook this component and its CSS use is `wf-map-peek-*`).
 *
 * @param {object} props
 * @param {?('win'|'lay')} props.section the open section, or null when collapsed. `'tide'` arrives
 *        at M3 — the Tide button is not rendered at all in M2 (a released control must never open
 *        an empty panel).
 * @param {Function} props.onPressWindows called when the "Other windows" button is pressed.
 * @param {Function} props.onPressLayers called when the "Layers" button is pressed.
 * @param {React.ReactNode} [props.otherWindowContent] the "Other windows" button's value line.
 * @param {React.ReactNode} [props.windowsBody] the Windows section's body, rendered while
 *        {@code section === 'win'}.
 * @param {React.ReactNode} [props.layersBody] the Layers section's body, rendered while
 *        {@code section === 'lay'}.
 */
export default function MapPeekSheet({
  section, onPressWindows, onPressLayers, otherWindowContent = null, windowsBody = null, layersBody = null,
  layersButtonRef = null,
}) {
  const open = section != null;
  return (
    <section
      data-testid="wf-map-peek"
      aria-label="Map panels"
      className={`wf-map-peek${open ? ' wf-map-peek-open' : ''}`}
    >
      <div className="wf-map-peek-hdl" aria-hidden="true"><i /></div>
      <div className="wf-map-peek-row">
        <button
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
      </div>
      {/* Rendered only when open — the design's own rule ("Body (visible only when open)"), and
          what makes a section swap tear the PREVIOUS section's content down rather than leaving two
          panes stacked in the DOM. */}
      {open && (
        <div id="wf-map-peek-body" data-testid="wf-map-peek-body" className="wf-map-peek-body">
          {section === 'win' && windowsBody}
          {section === 'lay' && layersBody}
        </div>
      )}
    </section>
  );
}

MapPeekSheet.propTypes = {
  section: PropTypes.oneOf(['win', 'tide', 'lay']),
  onPressWindows: PropTypes.func.isRequired,
  onPressLayers: PropTypes.func.isRequired,
  otherWindowContent: PropTypes.node,
  windowsBody: PropTypes.node,
  layersBody: PropTypes.node,
  /** Attached to the Layers button — the phone Regions/Filters `BottomSheet` hosts' own restore
   * target, since both unmount their trigger the instant they open (map-mobile-sheet-plan.md
   * §3 M2 task 6). */
  layersButtonRef: PropTypes.object,
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
        {!night && ` ${row.eventType === 'SUNRISE' ? 'AM' : 'PM'}`}
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
