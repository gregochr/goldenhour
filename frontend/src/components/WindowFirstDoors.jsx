import React, { useState } from 'react';
import PropTypes from 'prop-types';
import HotTopicStrip from './HotTopicStrip.jsx';
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
 * The two doors at the foot of the Plan pane, and what they open.
 *
 * <h2>Neither door carries a count, and the design's two are why</h2>
 *
 * <p>The mock draws "4 regions →" and "3 live →". The first is the species plan §6 bans outright —
 * a count of our own roster, not of tonight ("11 aligned is a fact about the database, not about
 * tonight"), and the same charge that removed P7's "61 coastal locations →". The second is arguably
 * about tonight, since a topic is live because conditions made it so. It is dropped anyway, for a
 * reason about the pair rather than about either one: two tiles of identical construction where one
 * carries a number and the other cannot reads as a defect in the one that does not, and the
 * zero-topics case — the only thing a count was going to protect the reader from — is answered
 * structurally below instead. Settled here so a later phase does not re-derive it from the mock.
 *
 * <h2>A door is not rendered when there is nothing behind it</h2>
 *
 * <p>Which is what a count would otherwise have had to say. No hot topics, no hot-topics door; no
 * grid to plan over, no regional-planner door. This is the honest form of "3 live": the reader
 * learns whether it is worth opening by whether it is there, and never opens a door onto an empty
 * room.
 *
 * <p><b>"Nothing behind it" had three terms for the grid, and now has two.</b> The first gate was
 * {@code upcomingEvents.length > 0}, which was wrong twice:
 *
 * <ul>
 *   <li><b>The viewport — retired, and worth keeping the history.</b> {@code HeatmapGrid} used to
 *       render nothing below 640px ({@code hidden sm:grid} / {@code hidden sm:flex}), so the door
 *       drew a tile that opened an empty bordered box and fired one astro request per date for
 *       content that could not paint. That was a re-parenting loss — the v1 arm wraps the same
 *       disclosure in {@code hidden sm:block} ({@code DailyBriefing.jsx:1526}) and the copy did not
 *       bring the guard, which is the failure mode §5a's "copy, don't extract" rule exists to catch.
 *       <b>The grid now has a phone layout</b> (a scroller with the region column pinned), so there
 *       is nothing left to gate and the term is gone. See
 *       {@code docs/engineering/phone-heatmap-blast-radius.md} for why that change could not reach
 *       the frozen v1 arm.</li>
 *   <li><b>Travel days.</b> {@code upcomingEvents} is the list <em>before</em> the travel filter,
 *       and the grid drops away columns itself ({@code gridEvents}, and it gates its whole grid on
 *       the filtered list being non-empty). An operator away across the entire capped horizon
 *       therefore got a door promising "every region, every window" over a panel holding one dashed
 *       band — whose own wording, "no forecast generated", is the phrase the away row directly above
 *       it deliberately rejects. {@code windowCards} is the travel-filtered set by construction, so
 *       it is the honest denominator.</li>
 * </ul>
 *
 * <h2>The panels are mounted once and then hidden, not unmounted</h2>
 *
 * <p>Two reasons, one per door. {@code aria-controls} must name an element that exists, so a closed
 * door whose panel is unmounted points at nothing. And the regional panel fetches one astro request
 * per visible date on mount, so unmounting would refire that wave on every reopen. {@code hidden} is
 * {@code display: none} — no layout, and removed from the accessibility tree — so a collapsed panel
 * costs the DOM nodes and nothing else. Nothing is fetched until a door is opened for the first
 * time, which is the point of the door.
 *
 * <h2>The doors are independent, not a radio pair</h2>
 *
 * <p>Both panels are tall and both sit at the foot of a long pane, so making them mutually exclusive
 * would silently collapse one when the reader opened the other — a scroll jump with no cause the
 * reader can see. Two disclosures, two states.
 *
 * <h2>{@code HotTopicStrip} is passed through unchanged, and that is a decision</h2>
 *
 * <p>Plan §5b and §7 assign P9 a reconvergence: {@code TopicFacts} blurs every topic's fact chips
 * for LITE, P7 settled that the window card's attribute rows are <b>not</b> gated, and this door is
 * the first time the two surfaces are in the same arm. <b>The reconvergence is not made here, and
 * the premise it rested on is why.</b> The plan describes the blanket blur as "the single place to
 * make it"; on inspection it is one of five LITE treatments in that component — the pill is
 * {@code opacity: 0.45}, {@code canExpandRich} and {@code canRevealRegions} are both forced off,
 * {@code handleClick} returns early, the tide chart is blurred as well as the facts, and an
 * "Upgrade to Pro" call to action replaces the lot. Editing only the facts blur would leave a
 * greyed, inert pill carrying sharp numbers, which is strictly <em>more</em> incoherent than today.
 * Editing all of it is a freemium-policy change, not a layout fix:
 * {@code freemium_ui_strategy.md} does not mention hot topics at all, so there is no written policy
 * to appeal to, and the strip as it stands is exactly the treatment CLAUDE.md's own role-gating
 * pattern prescribes (opacity 0.45, "Upgrade to Pro"). The rows were the argued exception, not the
 * strip.
 *
 * <p>What is left standing, recorded rather than hidden: for a LITE user in this arm, a tide's
 * metres and a snow depth are readable on the window card's attribute row and blurred on the same
 * topic's pill behind this door, so for those two channels the tease is already defeated. That is a
 * real defect and it belongs to whoever owns the pricing story, decided once across both arms — the
 * shape plan §2.8 already settled for the pick gloss ("Reconverging the arms after the flag default
 * flips means making this one decision once, across both — not splitting it across two surfaces").
 * Handed to P15's pre-pilot sweep with the evidence.
 *
 * @param {object}   props
 * @param {Array}    props.locations     enabled locations, for the grid's two joins
 * @param {Function} [props.onShowOnMap] the map handoff
 */
export default function WindowFirstDoors({ locations, onShowOnMap }) {
  const { briefing, windowCards, isLiteUser } = useWindowFirstBriefing();
  // Restored from the session, so a flip to the other arm and back lands on the doors the reader
  // left rather than re-collapsing them — which is what the v1 arm has always done for its own
  // disclosure, and the asymmetry was visible on exactly the surface the two arms are compared on.
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

  const hotTopics = briefing?.hotTopics || [];
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
  const showTopics = hotTopics.length > 0;
  if (!showRegional && !showTopics) return null;

  const regionalOpen = openDoors.has('regional');
  const topicsOpen = openDoors.has('topics');

  return (
    <div data-testid="window-first-doors" className="flex flex-col" style={{ gap: '10px' }}>
      <div className="wf-doors">
        {showRegional && (
          <Door
            testId="window-first-door-regional"
            title="Regional planner"
            description="every region, every window"
            panelId="window-first-panel-regional"
            open={regionalOpen}
            onToggle={() => toggle('regional')}
          />
        )}
        {showTopics && (
          <Door
            testId="window-first-door-topics"
            title="Hot topics"
            description="the detail behind the badges"
            panelId="window-first-panel-topics"
            open={topicsOpen}
            onToggle={() => toggle('topics')}
          />
        )}
      </div>

      <div id="window-first-panel-regional" hidden={!regionalOpen}>
        {showRegional && everOpened.has('regional') && (
          <div className="wf-door-panel" data-testid="window-first-panel-regional-body">
            <WindowFirstRegionalPanel locations={locations} onShowOnMap={onShowOnMap} />
          </div>
        )}
      </div>

      <div id="window-first-panel-topics" hidden={!topicsOpen}>
        {showTopics && everOpened.has('topics') && (
          <div className="wf-door-panel" data-testid="window-first-panel-topics-body">
            <HotTopicStrip
              hotTopics={hotTopics}
              isLiteUser={isLiteUser}
              onTopicTap={(topic) => {
                // v1's `handleHotTopicTap`, two lines and reproduced rather than imported for the
                // same reason as everything else this arm borrows: `DailyBriefing` stays untouched.
                if (topic.filterAction && onShowOnMap) {
                  onShowOnMap({ filterAction: topic.filterAction, date: topic.date });
                }
              }}
              onShowOnMap={onShowOnMap}
              auroraTonight={briefing?.auroraTonight || null}
              auroraTomorrow={briefing?.auroraTomorrow || null}
            />
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
