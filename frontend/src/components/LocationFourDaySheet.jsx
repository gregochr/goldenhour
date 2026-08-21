import React, { useMemo, useState } from 'react';
import PropTypes from 'prop-types';
import Modal from './shared/Modal.jsx';
import ProvisionalMark from './shared/ProvisionalMark.jsx';
import { confidenceTreatment } from '../utils/confidenceUtils.js';
import { formatDriveDuration } from '../utils/briefingDisplay.js';
import { buildLocationSheet } from '../utils/locationSheet.js';
import { spotBadgeStyle } from '../utils/windowFirstSpots.js';

/**
 * One place, its next solar windows, and what each one costs to reach (plan D10, P8).
 *
 * <h2>It hangs off the search, and off nothing else</h2>
 *
 * <p>§9.9 asked whether this replaces the card-click handoff to the map or hangs off the search
 * only; the owner resolved it (2026-08-20) in favour of the search. So a spot card's click and the
 * peek's click keep today's behaviour <b>byte-for-byte</b> — {@code WindowSpotStrip.test.jsx} and
 * {@code WindowSpotPeek.test.jsx} passing unedited is the proof — and nothing in this component is
 * reachable from either. The map is not lost from here either: the footer carries it, and names the
 * window it will open in the vocabulary the strip behind it uses.
 *
 * <h2>The one page-wide rule this surface can break, and how it does not</h2>
 *
 * <p>Search matches the <em>whole roster</em>, which is what makes this the first surface able to
 * render a drive to a place the current origin's scope does not contain — the condition
 * {@code leaveBy}'s Javadoc named in P7 as the one that would reopen the wrapped-{@code HH:mm}
 * ambiguity. It is reopened, and it is closed here rather than argued away: the departure line reads
 * {@code leaveByParts}, which returns the departure's own UK date, and the row names that day
 * whenever it is not the event's. Nothing is conditioned on a measured drive ceiling — that ceiling
 * has already moved twice.
 *
 * <h2>Confidence rides the existing channel and never touches the star</h2>
 *
 * <p>The design draws {@code ◐ 88%} on every row. D3 rejects the percentage outright — this
 * project's confidence is three-tier and a percentage would invent precision the backend never
 * claimed — so a row takes {@code ProvisionalMark} on the low tier, which is exactly what the shared
 * {@code VerdictPill} and the Best Bet pill already do. The fill decay that the grid cells carry has
 * no home here: the only coloured surface on a row is the rating badge, and CLAUDE.md is explicit
 * that the quality signal is never dimmed by the confidence channel. The mark is absent on an
 * unrated row, because the channel qualifies a forecast and there is none to qualify.
 *
 * <h2>⚠️ There is no "Plan from here" footer action, and that is a decision</h2>
 *
 * <p>The prototype's {@code renderSpot} offers one for a spot outside the current scope. Moving the
 * origin from inside an open sheet would swap {@code effectiveReachById} and the scope underneath
 * it: the drive figure, the base named beside it, the outside badge and <em>every departure time on
 * every row</em> would all change while the reader is looking at them, and the reader asked for none
 * of it. The sheet is a page about one place; the origin is a page-wide frame, and it is moved from
 * the chip and the search, which is where a reader can see what it does. Search is one keystroke
 * away from here.
 *
 * @param {object}   props
 * @param {object}   props.spot        the heat spot the reader searched for
 * @param {Array}    props.windows     {@code buildHeatStripCards}' descriptors
 * @param {?object}  [props.scoreIndex] from {@code buildScoreIndex}
 * @param {?object}  [props.slotIndex]  from {@code buildSlotIndex}
 * @param {boolean}  [props.scoresKnown] whether the ratings response has arrived. Defaults FALSE, so
 *        a caller that has not thought about it makes no claim — {@code WindowRowFieldMap}'s rule
 * @param {?Map}     [props.reachById]  the reach map the PAGE plans from
 * @param {string[]} [props.scopeRegionNames] the region names in scope
 * @param {?object}  [props.origin]     the origin descriptor, for the outside badge's wording
 * @param {string}   [props.originLabel] the place the drive is measured from
 * @param {string}   [props.todayStr]   today's UK date
 * @param {Function} props.onClose     dismisses the sheet
 * @param {Function} [props.onShowOnMap] opens the map on (date, targetType, location name)
 */
export default function LocationFourDaySheet({
  spot, windows, scoreIndex = null, slotIndex = null, scoresKnown = false, reachById = null,
  scopeRegionNames = null, origin = null, originLabel = null, todayStr = '', onClose, onShowOnMap, escapeEnabled = true,
}) {
  const sheet = useMemo(
    () => buildLocationSheet(spot, windows, {
      scoreIndex, slotIndex, scoresKnown, reachById, scopeRegionNames, origin, todayStr,
    }),
    [spot, windows, scoreIndex, slotIndex, scoresKnown, reachById, scopeRegionNames, origin,
      todayStr],
  );

  /**
   * Which rows are expanded — seeded from the first render that has something to seed FROM.
   *
   * <p>The best window opens on arrival, which is the prototype's own behaviour and the reason this
   * sheet needs no separate lead paragraph: the design repeats the best window's prose above a
   * timeline whose best row is already showing it, and §6's density rule is what that duplication
   * looks like one screen down. Where there is no best — fewer than two rated windows — the first
   * rated row opens instead, so the sheet never arrives fully closed while it has something to say.
   *
   * <p>⚠️ <b>Not a bare {@code useState} initialiser, and an adversarial review is why.</b> The
   * ratings arrive over their own fetch, so a sheet opened from a search result that landed first
   * mounts with nothing rated, seeds an empty set, and then — when the scores arrive — shows a lead
   * line and a {@code ◎ best here} tag above rows that are all still closed. Seeding is therefore
   * deferred until there IS a seed, once, tracked by its own flag so that a reader who closes the
   * seeded row is never re-opened by a later poll.
   */
  const [open, setOpen] = useState(() => new Set());
  const [seeded, setSeeded] = useState(false);
  const seed = sheet.bestKey ?? sheet.rows.find((row) => row.rating != null)?.key ?? null;
  // Adjusted DURING the render — React's own "adjusting state when a prop changes" pattern, the
  // same one `PlanSearch` uses for its cursor. In an effect there would be one commit showing the
  // unseeded sheet.
  if (!seeded && seed) {
    setSeeded(true);
    setOpen(new Set([seed]));
  }
  const toggle = (key) => setOpen((current) => {
    const next = new Set(current);
    if (!next.delete(key)) next.add(key);
    return next;
  });

  const drive = formatDriveDuration(sheet.driveMinutes);
  const handoff = sheet.rows.find((row) => row.key === sheet.handoffKey) ?? null;
  const meta = [
    sheet.regionName,
    // Named, never bare. The lens bar above the page names the base every other drive figure is
    // measured from, so a number here with no origin on it is one the reader cannot place against
    // it. `planOrigin` records the same rule for `distanceMiles`.
    drive && originLabel ? `${drive} from ${originLabel}` : drive,
  ].filter(Boolean);

  return (
    <Modal
      // Counted, never asserted: `heatStripCards` folds `upcomingEvents`, which shortens as the day
      // burns down and is uncapped on the degrade path — so "the next six windows" was a number the
      // payload does not guarantee, and it is this dialog's ENTIRE accessible name, invisible to a
      // sighted check. A four-window fixture was announcing six.
      label={`${sheet.name} — the next ${sheet.rows.length} window${sheet.rows.length === 1 ? '' : 's'}`}
      onClose={onClose}
      bare
      // Nothing here is lost by dismissing: rows of forecast the page behind still holds.
      // ⚠️ Conditional since M2, and that IS the Escape order. `Modal` installs a document-level
      // Escape listener per instance, so two open dialogs both close on one press. Each layer
      // declines the key while something sits over it, which makes a press take exactly one layer
      // (plan-matrix §6 M2.5). Defaults to true, so a caller that does not stack is unchanged.
      closeOnEscape={escapeEnabled}
      data-testid="location-sheet"
    >
      <div className="wf-sheet-card">
        <div className="wf-sheet-head">
          <span data-testid="location-sheet-title" className="wf-sheet-t">{sheet.name}</span>
          {meta.length > 0 && (
            <span data-testid="location-sheet-meta" className="wf-sheet-w font-mono">
              {meta.join(' · ')}
            </span>
          )}
          {/* ⚠️ It NAMES the scope. The prototype's bare "outside your plan" means two different
              things — outside the home planning area, or not in the origin's region — and only one
              of them is about distance, so a Dales spot 45 minutes from a Keswick base wore the
              badge directly above "45 min from Keswick" and read as a broken filter. */}
          {sheet.outsideScope && (
            <span data-testid="location-sheet-outside" className="wf-loc-out font-mono">
              {sheet.outsideLabel}
            </span>
          )}
          <button
            type="button"
            data-testid="location-sheet-close"
            className="wf-sheet-x font-mono"
            onClick={onClose}
          >
            Close · Esc
          </button>
        </div>

        {/* One scroll container, not four pinned bands. The lead line is prose ABOUT the rows and
            rides with them, which is what keeps the card inside `.wf-sheet-card`'s
            `max-height: calc(100dvh - 32px); overflow: hidden` at 400% zoom — with a fourth
            unshrinkable band the head, lead and footer together exceeded a 320×256 viewport and the
            map action clipped with nothing able to scroll. `WindowPickDialog` records the same
            defect and the same three-band shape. */}
        <div data-testid="location-sheet-rows" className="wf-loc-rows">
          {/* Omitted rather than zeroed when the ratings are unknown — `leadLine` carries why. */}
          {sheet.lead && (
            <p data-testid="location-sheet-lead" className="wf-loc-lead font-mono">{sheet.lead}</p>
          )}

          {sheet.rows.length === 0 && (
            // Reachable: the roster and the briefing arrive over two independent fetches, and search
            // reads the roster — so a sheet can be opened before there are any windows to show. It
            // says so rather than rendering a title over an empty card with no footer.
            <p data-testid="location-sheet-empty" className="wf-loc-lead font-mono">
              No forecast windows are loaded yet.
            </p>
          )}

          {sheet.rows.map((row) => {
            const badge = spotBadgeStyle(row.rating);
            const treatment = confidenceTreatment(row.confidence);
            const expanded = open.has(row.key);
            const body = `location-sheet-body-${row.key}`;
            return (
              <div key={row.key} className="wf-loc-row" data-testid="location-sheet-row"
                data-window={row.key} data-best={row.key === sheet.bestKey ? 'true' : undefined}>
                <button
                  type="button"
                  data-testid="location-sheet-row-toggle"
                  className="wf-loc-head"
                  aria-expanded={expanded}
                  aria-controls={body}
                  onClick={() => toggle(row.key)}
                >
                  <span className="wf-loc-day font-mono" aria-hidden="true">
                    <span className="wf-loc-dow">{row.dow}</span>
                    <span className="wf-loc-dn">{row.dayNum}</span>
                  </span>
                  <span className="wf-loc-mid">
                    <span className="wf-loc-ttl">
                      {/* The date box is decorative to a screen reader, so BOTH its words are spoken
                          here instead — the weekday, because a control named "Sunrise 05:12" six
                          times over is six identical names for six different days, and the
                          day-of-month, because it is visible label text and 2.5.3 requires the
                          accessible name to contain it (speech input has nothing to match
                          otherwise). */}
                      <span className="sr-only">{`${row.dow} ${row.dayNum} `}</span>
                      <span className="wf-loc-w">{row.eventWord}</span>
                      {row.time && <span className="wf-loc-t font-mono">{row.time}</span>}
                      {/* Scoped in words, because "best" unqualified reads as the forecast's own
                          Best pick — a different, server-owned claim about the whole roster
                          (`pickKind` on the matrix card). This one is a max over one location's own
                          windows, and only exists when more than one of them was rated. The glyph
                          is hidden: this arm wraps every decorative `◎`, and VoiceOver says
                          "bullseye" in the middle of the row's name otherwise. */}
                      {row.key === sheet.bestKey && (
                        <span data-testid="location-sheet-best" className="wf-loc-tag font-mono">
                          <span aria-hidden="true">◎ </span>
                          best here
                        </span>
                      )}
                      {badge ? (
                        <span
                          data-testid="location-sheet-rating"
                          className="wf-loc-st font-mono"
                          style={badge}
                        >
                          {/* NVDA at its default symbol level does not speak U+2605, so the row's
                              most decision-relevant datum would be announced as a bare integer
                              beside a clock time. `HeatmapGrid` spells it out for the same reason. */}
                          <span aria-hidden="true">{`${row.rating}★`}</span>
                          <span className="sr-only">{`${row.rating} stars`}</span>
                        </span>
                      ) : (
                        // Three states, three sentences. An away day says nobody forecast it; an
                        // unrated forecast window says nothing has looked YET; and while the
                        // ratings request is in flight or failed the sheet says nothing about the
                        // pipeline at all, because that would be a claim about the forecast built
                        // out of our own fetch (`scoresLoaded`'s own rule).
                        <span data-testid="location-sheet-state" className="wf-loc-none font-mono">
                          {row.away ? row.stateLabel : (row.scoresKnown ? 'Not scored yet' : 'Loading ratings…')}
                        </span>
                      )}
                      {treatment.provisional && <ProvisionalMark title={treatment.label} />}
                    </span>
                    {row.leave && (
                      <span data-testid="location-sheet-leave" className="wf-loc-lv font-mono">
                        <span aria-hidden="true">↰</span>
                        {' leave '}
                        {/* ⚠️ The day marker, and the whole reason this sheet reads `leaveByParts`
                            rather than `leaveBy`. A spot card may print a bare `HH:mm` because every
                            drive it is handed sits inside a measured ceiling; search reaches the
                            whole roster, so this one does not — a 3h45 drive to an 04:40 sunrise
                            leaves at 00:35 on the reader's clock, the evening before. Derived from
                            the departure's OWN UK date rather than from a "does the drive exceed N
                            hours" test: the arithmetic already knows, and a threshold would have to
                            be re-measured every time the origin rules move. It sits BEFORE the clock
                            time, where it reads as one phrase — a trailing "Thu" beside a row whose
                            date box says Fri is two days in one line with nothing saying which owns
                            which. */}
                        {row.leave.dayWord && (
                          <b data-testid="location-sheet-leave-day">{`${row.leave.dayWord} `}</b>
                        )}
                        <b>{row.leave.time}</b>
                        {drive ? ` · ${drive}` : ''}
                      </span>
                    )}
                  </span>
                  <span className="wf-loc-car font-mono" aria-hidden="true">
                    {expanded ? '▲' : '▾'}
                  </span>
                </button>
                {/* Always mounted, hidden by attribute: an accordion that unmounts its body makes
                    `aria-controls` point at nothing for every closed row, which is what the
                    attribute exists to avoid. */}
                <div id={body} data-testid="location-sheet-body" className="wf-loc-body"
                  hidden={!expanded}>
                  {row.summary ? (
                    // Serif italic is this app's typographic mark for generated prose — the
                    // drill-down gloss, the map overlay's summary and the peek's clause all use it.
                    <p data-testid="location-sheet-why" className="wf-loc-why">{row.summary}</p>
                  ) : (
                    <p data-testid="location-sheet-nowhy" className="wf-loc-why muted font-mono">
                      {/* ⚠️ No "you". A travel day is the OPERATOR'S, not the reader's — the arm is
                          scrupulously impersonal about it everywhere else (the away cell says
                          "away — n windows not forecast", `windowFirstStrip` exports
                          `AWAY_STATE_LABEL`), and a pilot reader sitting at home on that Sunday
                          would conclude the app holds a travel calendar of theirs. */}
                      {row.away
                        ? 'Nothing was forecast for this day — away.'
                        : (row.scoresKnown
                          ? 'No read for this window yet.'
                          : 'Ratings are still loading.')}
                    </p>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <div className="wf-sheet-foot font-mono">
          {/* Never withheld while there is a window to open — "the map is one tap further, never
              lost". It names the window in the STRIP's own vocabulary (`card.label` is
              `[kicker, when]`, so "Tonight Sunset" rather than a bare weekday), because a sheet
              showing several of them cannot leave the reader to guess which one a single button
              means, and a second vocabulary for the same window would make them translate. */}
          {handoff ? (
            <button
              type="button"
              data-testid="location-sheet-map"
              className="wf-loc-map"
              onClick={() => onShowOnMap?.(handoff.date, handoff.targetType, sheet.name)}
            >
              {`◍ Show on map → ${handoff.label}`}
            </button>
          ) : (
            <span data-testid="location-sheet-nomap">The map opens once a forecast window loads.</span>
          )}
        </div>
      </div>
    </Modal>
  );
}

LocationFourDaySheet.propTypes = {
  /**
   * Whether Escape closes THIS dialog. False while another layer sits over it — the shell owns that
   * ordering, because only it knows what else is open (plan-matrix §6 M2.5).
   */
  escapeEnabled: PropTypes.bool,
  spot: PropTypes.shape({
    id: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
    name: PropTypes.string,
    regionName: PropTypes.string,
  }).isRequired,
  windows: PropTypes.arrayOf(PropTypes.object),
  scoreIndex: PropTypes.object,
  slotIndex: PropTypes.object,
  scoresKnown: PropTypes.bool,
  reachById: PropTypes.instanceOf(Map),
  scopeRegionNames: PropTypes.arrayOf(PropTypes.string),
  origin: PropTypes.shape({ name: PropTypes.string }),
  originLabel: PropTypes.string,
  todayStr: PropTypes.string,
  onClose: PropTypes.func.isRequired,
  onShowOnMap: PropTypes.func,
};
