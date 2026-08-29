import React, { useState } from 'react';
import PropTypes from 'prop-types';
import WindowFirstRegionalPanel from './WindowFirstRegionalPanel.jsx';
import { useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { readStoredDoors, writeStoredDoors } from '../utils/planDoors.js';

/**
 * One door — the tile only. Its panel is a sibling of the row, not a child of the tile.
 *
 * @param {object}   props
 * @param {string}   props.testId
 * @param {string}   props.title
 * @param {string}   props.description
 * @param {string}   props.panelId  the element {@code aria-controls} names
 * @param {boolean}  props.open
 * @param {Function} props.onToggle
 */
function Door({ testId, title, description, panelId, open, onToggle }) {
  return (
    <button
      type="button"
      data-testid={testId}
      className="wf-door"
      aria-expanded={open}
      aria-controls={panelId}
      onClick={onToggle}
    >
      <span className="wf-door-t">
        {title}
        <span className="wf-door-ar">
          {open ? 'Collapse' : 'Open'}
          <span aria-hidden="true">{open ? ' ▴' : ' ▾'}</span>
        </span>
      </span>
      <span className="wf-door-d">{description}</span>
    </button>
  );
}

Door.propTypes = {
  testId: PropTypes.string.isRequired,
  title: PropTypes.string.isRequired,
  description: PropTypes.string.isRequired,
  panelId: PropTypes.string.isRequired,
  open: PropTypes.bool.isRequired,
  onToggle: PropTypes.func.isRequired,
};

/**
 * The one door at the foot of the Plan pane, and what it opens.
 *
 * <p>Coming-up redesign plan §9 (P6) removed the Hot topics door and its panel — the strip's
 * standing conditions and the "Coming up" chronology now cover the topics it used to hold
 * (`docs/engineering/coming-up-plan.md` D7). This leaves the Regional planner as the pane's only
 * door; `.wf-doors` is a flex row and `.wf-door` is `flex: 1`, so a single child already goes
 * full-width with no CSS change.
 *
 * <h2>A door is not rendered when there is nothing behind it</h2>
 *
 * <p>No grid to plan over, no regional-planner door — the reader learns whether it is worth
 * opening by whether it is there, and never opens a door onto an empty room.
 *
 * <p><b>"Nothing behind it" had three terms for the grid, and now has two.</b> The first gate was
 * {@code upcomingEvents.length > 0}, which was wrong twice:
 *
 * <ul>
 *   <li><b>The viewport — retired, and worth keeping the history.</b> {@code HeatmapGrid} used to
 *       render nothing below 640px ({@code hidden sm:grid} / {@code hidden sm:flex}), so the door
 *       drew a tile that opened an empty bordered box and fired one astro request per date for
 *       content that could not paint — a re-parenting loss, since the guard was not brought along
 *       with the copy, which is the failure mode §5a's "copy, don't extract" rule exists to catch.
 *       <b>The grid now has a phone layout</b> (a scroller with the region column pinned), so there
 *       is nothing left to gate and the term is gone. See
 *       {@code docs/engineering/phone-heatmap-blast-radius.md} for the history.</li>
 *   <li><b>Travel days.</b> {@code upcomingEvents} is the list <em>before</em> the travel filter,
 *       and the grid drops away columns itself ({@code gridEvents}, and it gates its whole grid on
 *       the filtered list being non-empty). An operator away across the entire capped horizon
 *       therefore got a door promising "every region, every window" over a panel holding one dashed
 *       band — whose own wording, "no forecast generated", is the phrase this arm's own away
 *       treatment deliberately rejects. (⚠️ That treatment is a MATRIX CELL now; the away row this
 *       comment used to name as sitting "directly above" went with the card list at M2, and nothing
 *       is between the matrix and this door.) {@code windowCards} is the travel-filtered set by construction, so
 *       it is the honest denominator.</li>
 * </ul>
 *
 * <h2>The panel is mounted once and then hidden, not unmounted</h2>
 *
 * <p>{@code aria-controls} must name an element that exists, so a closed door whose panel is
 * unmounted points at nothing. And the regional panel fetches one astro request per visible date
 * on mount, so unmounting would refire that wave on every reopen. {@code hidden} is
 * {@code display: none} — no layout, and removed from the accessibility tree — so a collapsed
 * panel costs the DOM nodes and nothing else. Nothing is fetched until the door is opened for the
 * first time, which is the point of the door.
 *
 * @param {object}   props
 * @param {Array}    props.locations     enabled locations, for the grid's two joins
 * @param {Function} [props.onShowOnMap] the map handoff
 */
export default function WindowFirstDoors({ locations, onShowOnMap }) {
  const { windowCards } = useWindowFirstBriefing();
  // Restored from the session, so a remount lands on the doors the reader left rather than
  // re-collapsing them.
  const [openDoors, setOpenDoors] = useState(readStoredDoors);
  // Sticky: once a panel has been mounted it stays mounted and is hidden instead. Grown in the
  // handler rather than during render — a render-phase mutation is double-run under StrictMode and
  // is a side effect whether or not it happens to be idempotent.
  //
  // Seeded from the SAME restored set rather than persisted separately: it is derivable (everOpened
  // always contains openDoors), so a second stored field would be a second source of truth. Without
  // the seed a restored door would render `aria-expanded="true"` over a panel that was never
  // mounted — the control claiming a state the DOM does not have.
  const [everOpened, setEverOpened] = useState(readStoredDoors);

  const toggle = (id) => {
    setEverOpened((prev) => (prev.has(id) ? prev : new Set(prev).add(id)));
    setOpenDoors((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      // Written here rather than from an effect on `openDoors`: an effect would also fire on mount
      // and rewrite what was just read, turning a restore into a write on every render of the arm.
      writeStoredDoors(next);
      return next;
    });
  };

  // `windowCards` rather than `upcomingEvents` because the grid drops away columns itself.
  //
  // The viewport term is GONE, and its removal is the point of the change rather than a tidy-up.
  // It read `!isMobile` because `HeatmapGrid` was `hidden sm:grid` and the door would otherwise
  // open an empty bordered box. The grid now has a phone layout, so there is nothing to gate: a
  // phone reader gets the full plan, which is what the door has always promised.
  //
  // It also retires one live manifestation of the rem/px seam. This gate was `useIsMobile`
  // (`max-width: 639px`, in **px**) standing in for Tailwind's `sm:` (**40rem**); at a non-default
  // browser font size the two disagree and that band drew a door onto a `display: none` grid. The
  // seam itself is untouched and still live for `useIsMobile`'s other callers — this file simply no
  // longer has a side in it.
  const showRegional = (windowCards || []).length > 0;
  if (!showRegional) return null;

  const regionalOpen = openDoors.has('regional');

  return (
    <div data-testid="window-first-doors" className="flex flex-col" style={{ gap: '10px' }}>
      <div className="wf-doors">
        <Door
          testId="window-first-door-regional"
          title="Regional planner"
          description="every region, every window"
          panelId="window-first-panel-regional"
          open={regionalOpen}
          onToggle={() => toggle('regional')}
        />
      </div>

      <div id="window-first-panel-regional" hidden={!regionalOpen}>
        {everOpened.has('regional') && (
          <div className="wf-door-panel" data-testid="window-first-panel-regional-body">
            <WindowFirstRegionalPanel locations={locations} onShowOnMap={onShowOnMap} />
          </div>
        )}
      </div>
    </div>
  );
}

WindowFirstDoors.propTypes = {
  locations: PropTypes.array,
  onShowOnMap: PropTypes.func,
};
